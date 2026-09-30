package net.naari3.savestate.memory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ClassInfo {
	private static final Map<Class<?>, ClassInfo> CACHE = new ConcurrentHashMap<>();

	final Class<?> type;
	/** JDK 以外のクラスで宣言された非 static フィールド (setAccessible 済み)。 */
	final Field[] fields;
	/** 親クラスをたどって最初に出てくる JDK のクラス (Object 以外)。null なら JDK のクラスを継承していない。 */
	final Class<?> jdkSuperclass;

	private ClassInfo(Class<?> type) {
		this.type = type;
		List<Field> list = new ArrayList<>();
		Class<?> jdk = null;
		for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
			if (isJdk(k)) {
				if (jdk == null) {
					jdk = k;
				}
				continue;
			}
			for (Field f : k.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) {
					continue;
				}
				f.setAccessible(true);
				list.add(f);
			}
		}
		this.fields = list.toArray(new Field[0]);
		this.jdkSuperclass = jdk;
	}

	static ClassInfo of(Class<?> type) {
		return CACHE.computeIfAbsent(type, ClassInfo::new);
	}

	static boolean isJdk(Class<?> k) {
		String n = k.getName();
		return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") || n.startsWith("sun.") || n.startsWith("com.sun.");
	}

	boolean hasNoInstanceState() {
		return this.fields.length == 0 && this.jdkSuperclass == null;
	}
}
