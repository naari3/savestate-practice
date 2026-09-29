package net.naari3.savestate.memory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.naari3.savestate.SavestateMod;

/**
 * オブジェクトのグラフをリフレクションで丸ごと複製する。
 *
 * - 同じオブジェクトは同じ複製に対応させる (IdentityHashMap)。循環参照もそのまま複製される
 * - 深い再帰を避けるため、「割り当て」と「フィールドの充填」を分け、充填は作業キューで順に行う
 * - ハッシュ・ソート系のコレクションへの要素の投入は、全オブジェクトの充填が終わってから行う (第 2 段階)。
 *   要素の hashCode / compareTo がフィールドの値に依存しうるため
 * - 共有するかどうかは {@link SharePolicy} で決める
 * - JDK のクラスは中身をリフレクションで触れない (Java 16 以降) ので、主要なものを公開 API で作り直す
 *
 * 1 インスタンスは 1 回の複製 (1 つの対応表) に使う。
 */
public final class DeepCloner {
	private static final Object UNSAFE;
	private static final Method ALLOCATE_INSTANCE;
	private static final Set<String> WARNED = Collections.newSetFromMap(new ConcurrentHashMap<>());

	static {
		try {
			Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
			Field f = unsafeClass.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			UNSAFE = f.get(null);
			ALLOCATE_INSTANCE = unsafeClass.getMethod("allocateInstance", Class.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private final SharePolicy policy;
	private final IdentityHashMap<Object, Object> map = new IdentityHashMap<>();
	private final ArrayDeque<Runnable> fillQueue = new ArrayDeque<>();
	/** 第 2 段階 (ハッシュ・ソート系の投入)。内側のコレクションを先に埋めるため、登録と逆順に実行する。 */
	private final List<Runnable> deferred = new ArrayList<>();
	private final Map<Class<?>, Integer> clonedCounts = new HashMap<>();

	public DeepCloner(SharePolicy policy) {
		this.policy = policy;
	}

	/** from を複製せず、to に対応させる (例: スナップショット内のプレイヤー → 今のプレイヤー)。 */
	public void bind(Object from, Object to) {
		this.map.put(from, to);
	}

	@SuppressWarnings("unchecked")
	public <T> List<T> copyAll(List<T> roots) {
		List<T> out = new ArrayList<>(roots.size());
		for (T r : roots) {
			out.add((T) this.map(r));
		}
		this.drain();
		return out;
	}

	@SuppressWarnings("unchecked")
	public <T> T copy(T root) {
		T out = (T) this.map(root);
		this.drain();
		return out;
	}

	/** 複製したクラスごとの個数 (調査用)。 */
	public Map<Class<?>, Integer> getClonedCounts() {
		return this.clonedCounts;
	}

	private void drain() {
		Runnable r;
		while ((r = this.fillQueue.poll()) != null) {
			r.run();
		}
		for (int i = this.deferred.size() - 1; i >= 0; i--) {
			this.deferred.get(i).run();
		}
		this.deferred.clear();
	}

	Object map(Object o) {
		if (o == null) {
			return null;
		}
		Object existing = this.map.get(o);
		if (existing != null) {
			return existing;
		}
		Class<?> c = o.getClass();
		if (c.isArray()) {
			return this.mapArray(o, c);
		}
		boolean jdk = ClassInfo.isJdk(c);
		ClassInfo info = jdk ? null : ClassInfo.of(c);
		if (this.policy.isShared(o, c, info)) {
			this.map.put(o, o);
			return o;
		}
		if (jdk) {
			return this.mapJdk(o, c);
		}
		if (c.isSynthetic() && c.getName().contains("$$Lambda")) {
			return this.mapLambda(o, c, info);
		}
		if (c.getName().startsWith("it.unimi.dsi.fastutil.") && (o instanceof Map || o instanceof Collection)) {
			return this.rebuildByConstructor(o, c, null);
		}
		if (o instanceof ImmutableList || o instanceof ImmutableSet || o instanceof ImmutableMap) {
			return this.mapGuavaImmutable(o);
		}
		if (info.jdkSuperclass != null && (o instanceof Collection || o instanceof Map) && !info.jdkSuperclass.getName().startsWith("java.util.Abstract")) {
			// MC のクラスが ArrayList などを直接継承している (例: TradeOfferList)。中身は JDK 側にあるので API で入れ直す
			return this.rebuildByConstructor(o, c, info);
		}
		if (info.jdkSuperclass != null && !(o instanceof Random) && !info.jdkSuperclass.getName().startsWith("java.util.Abstract")) {
			warnOnce("extends JDK class " + info.jdkSuperclass.getName() + ": " + c.getName() + " (JDK 側の状態は写せない)");
		}
		return this.mapReflective(o, c, info);
	}

	private Object mapReflective(Object o, Class<?> c, ClassInfo info) {
		Object dst = allocate(c);
		this.map.put(o, dst);
		this.clonedCounts.merge(c, 1, Integer::sum);
		this.fillQueue.add(() -> this.copyFields(o, dst, info));
		return dst;
	}

	private void copyFields(Object src, Object dst, ClassInfo info) {
		for (Field f : info.fields) {
			try {
				Class<?> t = f.getType();
				if (t.isPrimitive()) {
					if (t == int.class) {
						f.setInt(dst, f.getInt(src));
					} else if (t == long.class) {
						f.setLong(dst, f.getLong(src));
					} else if (t == double.class) {
						f.setDouble(dst, f.getDouble(src));
					} else if (t == float.class) {
						f.setFloat(dst, f.getFloat(src));
					} else if (t == boolean.class) {
						f.setBoolean(dst, f.getBoolean(src));
					} else if (t == byte.class) {
						f.setByte(dst, f.getByte(src));
					} else if (t == short.class) {
						f.setShort(dst, f.getShort(src));
					} else {
						f.setChar(dst, f.getChar(src));
					}
				} else {
					f.set(dst, this.map(f.get(src)));
				}
			} catch (IllegalAccessException e) {
				warnOnce("cannot copy field " + f + ": " + e);
			}
		}
	}

	private Object mapArray(Object o, Class<?> c) {
		int len = Array.getLength(o);
		Class<?> comp = c.getComponentType();
		Object dst = Array.newInstance(comp, len);
		this.map.put(o, dst);
		if (comp.isPrimitive()) {
			System.arraycopy(o, 0, dst, 0, len);
		} else {
			Object[] s = (Object[]) o;
			Object[] d = (Object[]) dst;
			this.fillQueue.add(() -> {
				for (int i = 0; i < len; i++) {
					d[i] = this.map(s[i]);
				}
			});
		}
		return dst;
	}

	private Object mapLambda(Object o, Class<?> c, ClassInfo info) {
		// hidden class のフィールドは書き換えられないので、捕捉した値を複製してコンストラクタで作り直す
		try {
			Constructor<?> ctor = c.getDeclaredConstructors()[0];
			ctor.setAccessible(true);
			Object[] args = new Object[info.fields.length];
			for (int i = 0; i < args.length; i++) {
				args[i] = this.map(info.fields[i].get(o));
			}
			Object dst = ctor.newInstance(args);
			this.map.put(o, dst);
			this.clonedCounts.merge(c, 1, Integer::sum);
			return dst;
		} catch (ReflectiveOperationException | RuntimeException e) {
			warnOnce("cannot clone lambda " + c.getName() + " (shared): " + e);
			this.map.put(o, o);
			return o;
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Object mapJdk(Object o, Class<?> c) {
		// リスト・キュー系: 順序だけが意味を持つので、充填段階でそのまま入れる
		if (c == ArrayList.class || c == LinkedList.class || c == ArrayDeque.class || c == CopyOnWriteArrayList.class
			|| c == ConcurrentLinkedQueue.class) {
			Collection dst = (Collection) newInstance(c);
			this.map.put(o, dst);
			Object[] elems = ((Collection) o).toArray();
			this.fillQueue.add(() -> {
				for (Object e : elems) {
					dst.add(this.map(e));
				}
			});
			return dst;
		}
		// ハッシュ・ソート系: 要素の割り当ては充填段階、投入は第 2 段階
		if (c == HashSet.class || c == LinkedHashSet.class) {
			return this.rebuildCollection(o, (Collection) newInstance(c));
		}
		if (c == TreeSet.class) {
			return this.rebuildCollection(o, new TreeSet(((TreeSet) o).comparator()));
		}
		if (c == PriorityQueue.class) {
			return this.rebuildCollection(o, new PriorityQueue(Math.max(1, ((PriorityQueue) o).size()), ((PriorityQueue) o).comparator()));
		}
		if (c == HashMap.class || c == LinkedHashMap.class || c == IdentityHashMap.class || c == WeakHashMap.class || c == ConcurrentHashMap.class) {
			return this.rebuildMap(o, (Map) newInstance(c));
		}
		if (c == TreeMap.class) {
			return this.rebuildMap(o, new TreeMap(((TreeMap) o).comparator()));
		}
		if (c == EnumMap.class) {
			EnumMap dst = new EnumMap((EnumMap) o);
			dst.clear();
			return this.rebuildMap(o, dst);
		}
		if (o instanceof EnumSet) {
			Object dst = ((EnumSet) o).clone();
			this.map.put(o, dst);
			return dst;
		}
		if (c == Optional.class) {
			Optional<?> opt = (Optional<?>) o;
			Object dst = opt.isPresent() ? Optional.of(this.map(opt.get())) : opt;
			this.map.put(o, dst);
			return dst;
		}
		if (c == AtomicInteger.class) {
			return this.put(o, new AtomicInteger(((AtomicInteger) o).get()));
		}
		if (c == AtomicLong.class) {
			return this.put(o, new AtomicLong(((AtomicLong) o).get()));
		}
		if (c == AtomicBoolean.class) {
			return this.put(o, new AtomicBoolean(((AtomicBoolean) o).get()));
		}
		if (c == AtomicReference.class) {
			AtomicReference dst = new AtomicReference();
			this.map.put(o, dst);
			dst.set(this.map(((AtomicReference) o).get()));
			return dst;
		}
		if (c == ReentrantLock.class) {
			return this.put(o, new ReentrantLock());
		}
		if (c == ReentrantReadWriteLock.class) {
			return this.put(o, new ReentrantReadWriteLock());
		}
		String n = c.getName();
		// Collections.unmodifiableXxx / singletonXxx / Arrays.asList: 中身を複製して同じ種類で包み直す
		if (n.startsWith("java.util.Collections$Unmodifiable") || n.startsWith("java.util.Collections$Singleton")
			|| n.equals("java.util.Arrays$ArrayList") || n.startsWith("java.util.Collections$Synchronized")) {
			return this.rewrapCollection(o, n);
		}
		if (n.startsWith("java.util.Collections$Empty") || n.startsWith("java.util.ImmutableCollections$") && isEmptyCollection(o)) {
			return this.put(o, o);
		}
		if (c == Random.class) {
			warnOnce("plain java.util.Random found (state cannot be copied; a new unseeded Random is used)");
			return this.put(o, new Random());
		}
		warnOnce("unhandled JDK class shared as-is: " + n);
		return this.put(o, o);
	}

	private static boolean isEmptyCollection(Object o) {
		return (o instanceof Collection && ((Collection<?>) o).isEmpty()) || (o instanceof Map && ((Map<?, ?>) o).isEmpty());
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Object rewrapCollection(Object o, String n) {
		if (o instanceof List) {
			List inner = new ArrayList();
			Object dst;
			if (n.equals("java.util.Arrays$ArrayList")) {
				Object[] arr = new Object[((List) o).size()];
				dst = Arrays.asList(arr);
				this.map.put(o, dst);
				Object[] elems = ((List) o).toArray();
				this.fillQueue.add(() -> {
					for (int i = 0; i < elems.length; i++) {
						arr[i] = this.map(elems[i]);
					}
				});
				return dst;
			}
			dst = n.contains("Synchronized") ? Collections.synchronizedList(inner) : Collections.unmodifiableList(inner);
			this.map.put(o, dst);
			Object[] elems = ((List) o).toArray();
			this.fillQueue.add(() -> {
				for (Object e : elems) {
					inner.add(this.map(e));
				}
			});
			return dst;
		}
		if (o instanceof Set) {
			Set inner = new LinkedHashSet();
			Object dst = n.contains("Synchronized") ? Collections.synchronizedSet(inner) : Collections.unmodifiableSet(inner);
			this.map.put(o, dst);
			this.fillQueueRebuild(((Set) o).toArray(), inner);
			return dst;
		}
		if (o instanceof Map) {
			Map inner = new LinkedHashMap();
			Object dst = n.contains("Synchronized") ? Collections.synchronizedMap(inner) : Collections.unmodifiableMap(inner);
			this.map.put(o, dst);
			this.fillQueueRebuildMap((Map) o, inner);
			return dst;
		}
		if (o instanceof Collection) {
			List inner = new ArrayList();
			Object dst = Collections.unmodifiableCollection(inner);
			this.map.put(o, dst);
			this.fillQueueRebuild(((Collection) o).toArray(), inner);
			return dst;
		}
		warnOnce("unhandled wrapper shared as-is: " + n);
		return this.put(o, o);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Object rebuildCollection(Object o, Collection dst) {
		this.map.put(o, dst);
		this.clonedCounts.merge(o.getClass(), 1, Integer::sum);
		this.fillQueueRebuild(((Collection) o).toArray(), dst);
		return dst;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Object rebuildMap(Object o, Map dst) {
		this.map.put(o, dst);
		this.clonedCounts.merge(o.getClass(), 1, Integer::sum);
		this.fillQueueRebuildMap((Map) o, dst);
		return dst;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void fillQueueRebuild(Object[] elems, Collection dst) {
		Object[] mapped = new Object[elems.length];
		this.fillQueue.add(() -> {
			for (int i = 0; i < elems.length; i++) {
				mapped[i] = this.map(elems[i]);
			}
		});
		this.deferred.add(() -> Collections.addAll(dst, mapped));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void fillQueueRebuildMap(Map src, Map dst) {
		List<Map.Entry> entries = new ArrayList<>(src.entrySet().size());
		for (Object e : src.entrySet()) {
			Map.Entry en = (Map.Entry) e;
			// fastutil の entrySet はエントリを使い回すことがあるので、ここでキーと値を取り出しておく
			entries.add(new AbstractMap.SimpleEntry(en.getKey(), en.getValue()));
		}
		Object[] keys = new Object[entries.size()];
		Object[] values = new Object[entries.size()];
		this.fillQueue.add(() -> {
			for (int i = 0; i < keys.length; i++) {
				keys[i] = this.map(entries.get(i).getKey());
				values[i] = this.map(entries.get(i).getValue());
			}
		});
		this.deferred.add(() -> {
			for (int i = 0; i < keys.length; i++) {
				dst.put(keys[i], values[i]);
			}
		});
	}

	/** 引数なしのコンストラクタで作り、要素を API で入れ直す (fastutil、JDK のコレクションを継承した MC のクラス)。 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private Object rebuildByConstructor(Object o, Class<?> c, ClassInfo mcFields) {
		Object dst;
		try {
			Constructor<?> ctor = c.getDeclaredConstructor();
			ctor.setAccessible(true);
			dst = ctor.newInstance();
		} catch (ReflectiveOperationException e) {
			warnOnce("no no-arg constructor for collection " + c.getName() + " (shared): " + e);
			return this.put(o, o);
		}
		this.map.put(o, dst);
		this.clonedCounts.merge(c, 1, Integer::sum);
		if (mcFields != null) {
			this.fillQueue.add(() -> this.copyFields(o, dst, mcFields));
		}
		if (o instanceof Map) {
			this.fillQueueRebuildMap((Map) o, (Map) dst);
		} else if (o instanceof List) {
			Object[] elems = ((Collection) o).toArray();
			this.fillQueue.add(() -> {
				for (Object e : elems) {
					((Collection) dst).add(this.map(e));
				}
			});
		} else {
			this.fillQueueRebuild(((Collection) o).toArray(), (Collection) dst);
		}
		return dst;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Object mapGuavaImmutable(Object o) {
		// 不変なので、中身がすべて共有 (複製しても同じ参照) なら自分も共有する。そうでなければ作り直す
		if (o instanceof ImmutableMap) {
			ImmutableMap<?, ?> src = (ImmutableMap<?, ?>) o;
			ImmutableMap.Builder b = ImmutableMap.builder();
			boolean same = true;
			for (Map.Entry<?, ?> e : src.entrySet()) {
				Object k = this.map(e.getKey());
				Object v = this.map(e.getValue());
				same &= k == e.getKey() && v == e.getValue();
				b.put(k, v);
			}
			return this.put(o, same ? o : b.build());
		}
		Collection<?> src = (Collection<?>) o;
		List<Object> mapped = new ArrayList<>(src.size());
		boolean same = true;
		for (Object e : src) {
			Object m = this.map(e);
			same &= m == e;
			mapped.add(m);
		}
		if (same) {
			return this.put(o, o);
		}
		if (o instanceof ImmutableSet) {
			warnOnce("ImmutableSet with cloned elements (hash computed before fields are filled): " + o.getClass().getName());
			return this.put(o, ImmutableSet.copyOf(mapped));
		}
		return this.put(o, ImmutableList.copyOf(mapped));
	}

	private Object put(Object from, Object to) {
		this.map.put(from, to);
		return to;
	}

	private static Object allocate(Class<?> c) {
		try {
			return ALLOCATE_INSTANCE.invoke(UNSAFE, c);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("cannot allocate " + c.getName(), e);
		}
	}

	private static Object newInstance(Class<?> c) {
		try {
			return c.getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("cannot instantiate " + c.getName(), e);
		}
	}

	private static void warnOnce(String message) {
		if (WARNED.add(message)) {
			SavestateMod.LOGGER.warn("[memory] {}", message);
		}
	}
}
