package net.naari3.savestate.detcheck;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * 調査用: オブジェクトのフィールドを再帰的に「パス = 値」の行で書き出す。
 * 同じエンティティのダンプを回ごとに比べ、複製で写し損ねた状態や、並び順の違いを探すのに使う。
 *
 * 同一性ハッシュやオブジェクトのアドレスは回ごとに変わるので出さない (クラス名と、既に出したオブジェクトへの参照番号だけ出す)。
 */
public final class ObjectDumper {
	private final List<String> lines = new ArrayList<>();
	private final Map<Object, String> seen = new IdentityHashMap<>();
	private final Object root;
	private final int maxDepth;

	private ObjectDumper(Object root, int maxDepth) {
		this.root = root;
		this.maxDepth = maxDepth;
	}

	public static List<String> dump(Object root, int maxDepth) {
		ObjectDumper d = new ObjectDumper(root, maxDepth);
		d.walk("", root, 0);
		return d.lines;
	}

	private void walk(String path, Object o, int depth) {
		if (o == null) {
			this.lines.add(path + " = null");
			return;
		}
		Class<?> c = o.getClass();
		if (c.isPrimitive() || o instanceof Number || o instanceof Boolean || o instanceof Character || o instanceof String || o instanceof Enum) {
			this.lines.add(path + " = " + o);
			return;
		}
		if (o instanceof BlockState || o instanceof net.minecraft.util.math.Vec3d || o instanceof net.minecraft.util.math.Vec3i
			|| o instanceof net.minecraft.util.Identifier || o instanceof java.util.UUID || o instanceof net.minecraft.util.math.Box) {
			this.lines.add(path + " = " + o);
			return;
		}
		// 外側の世界 (ワールド、サーバー、チャンク、他のエンティティ) には入らない
		if (o != this.root && (o instanceof World || o instanceof MinecraftServer || o instanceof Chunk || o instanceof Entity)) {
			this.lines.add(path + " -> " + c.getSimpleName() + (o instanceof Entity ? " " + ((Entity) o).getUuidAsString().substring(0, 8) : ""));
			return;
		}
		String ref = this.seen.get(o);
		if (ref != null) {
			this.lines.add(path + " -> @" + ref);
			return;
		}
		this.seen.put(o, path.isEmpty() ? "root" : path);
		if (depth >= this.maxDepth) {
			this.lines.add(path + " : " + c.getName() + " (depth limit)");
			return;
		}
		this.lines.add(path + " : " + c.getName());
		if (c.isArray()) {
			int n = Array.getLength(o);
			if (c.getComponentType().isPrimitive()) {
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < Math.min(n, 64); i++) {
					sb.append(Array.get(o, i)).append(',');
				}
				this.lines.add(path + " = [" + n + "] " + sb);
				return;
			}
			for (int i = 0; i < n; i++) {
				this.walk(path + "[" + i + "]", Array.get(o, i), depth + 1);
			}
			return;
		}
		if (o instanceof Map) {
			int i = 0;
			for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
				this.walk(path + "{" + i + "}.k", e.getKey(), depth + 1);
				this.walk(path + "{" + i + "}.v", e.getValue(), depth + 1);
				i++;
			}
			return;
		}
		if (o instanceof Collection) {
			int i = 0;
			for (Object e : (Collection<?>) o) {
				this.walk(path + "<" + i + ">", e, depth + 1);
				i++;
			}
			return;
		}
		if (c.getName().startsWith("java.")) {
			this.lines.add(path + " = (jdk) " + c.getName());
			return;
		}
		for (Class<?> k = c; k != null && k != Object.class && !k.getName().startsWith("java."); k = k.getSuperclass()) {
			for (Field f : k.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers())) {
					continue;
				}
				try {
					f.setAccessible(true);
					this.walk(path + "." + f.getName(), f.get(o), depth + 1);
				} catch (Throwable t) {
					this.lines.add(path + "." + f.getName() + " = <" + t.getClass().getSimpleName() + ">");
				}
			}
		}
	}
}
