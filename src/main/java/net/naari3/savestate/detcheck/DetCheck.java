package net.naari3.savestate.detcheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.naari3.savestate.SavestateDebug;
import net.naari3.savestate.mixin.accessor.WorldAccessor;
import net.naari3.savestate.rng.RngState;

/**
 * 決定論の検査 (サーバー側)。
 *
 * loadstate 後に tick が再開した時点から、毎 tick の終わりにワールドの状態を要約して記録する。
 * 要約は「項目名 → 値の文字列」の対応で、RNG の状態、ワールドの時刻、全エンティティの位置・速度・向き・Random・体力を含む。
 * 同じスロットから複数回 load した記録を {@link #compare} で比べ、最初にずれた tick と項目を報告する。
 */
public final class DetCheck {
	private static volatile boolean armed;
	private static volatile int ticksToRecord;
	private static volatile List<Map<String, String>> recording;
	private static volatile List<Map<String, String>> finished;

	private DetCheck() {
	}

	/** 次の loadstate の再開時点から ticks 回分を記録する。 */
	public static void arm(int ticks) {
		ticksToRecord = ticks;
		recording = null;
		finished = null;
		armed = true;
	}

	/** サーバースレッド。loadstate の RNG を適用し、tick を再開した時点で呼ばれる。 */
	public static void onResume() {
		if (armed) {
			armed = false;
			recording = new ArrayList<>();
		}
	}

	/** サーバースレッド。MinecraftServer.tick の終わりで呼ばれる。 */
	public static void onServerTickEnd(MinecraftServer server) {
		List<Map<String, String>> r = recording;
		if (r == null || RngState.isHoldingWorldTicks()) {
			return;
		}
		r.add(snapshot(server));
		if (r.size() >= ticksToRecord) {
			recording = null;
			finished = r;
		}
	}

	/** 記録が終わっていれば取り出す (クライアントスレッドから呼ぶ)。 */
	public static List<Map<String, String>> takeFinished() {
		List<Map<String, String>> r = finished;
		finished = null;
		return r;
	}

	private static Map<String, String> snapshot(MinecraftServer server) {
		Map<String, String> m = new TreeMap<>();
		RngState.describeInto(m);
		for (ServerWorld world : server.getWorlds()) {
			String w = world.getRegistryKey().getValue().getPath();
			m.put("world " + w + " time", Long.toString(world.getTime()));
			m.put("world " + w + " random", SavestateDebug.rand(world.random));
			m.put("world " + w + " lcg", Integer.toString(((WorldAccessor) world).savestate$getLcgBlockSeed()));
			int count = 0;
			for (Entity e : world.iterateEntities()) {
				count++;
				String k = "entity " + w + " " + EntityType.getId(e.getType()).getPath() + " " + e.getUuidAsString().substring(0, 8);
				m.put(k + " pos", e.getX() + "," + e.getY() + "," + e.getZ());
				Vec3d v = e.getVelocity();
				m.put(k + " vel", v.x + "," + v.y + "," + v.z);
				m.put(k + " rot", e.yaw + "," + e.pitch);
				m.put(k + " rand", SavestateDebug.rand(((EntityRandomAccess) e).savestate$random()));
				if (e instanceof LivingEntity) {
					m.put(k + " hp", Float.toString(((LivingEntity) e).getHealth()));
				}
			}
			m.put("world " + w + " entityCount", Integer.toString(count));
		}
		return m;
	}

	/** runs[0] を基準に、他の回と tick ごとに比べた結果の文章を返す。 */
	public static String compare(List<List<Map<String, String>>> runs) {
		StringBuilder sb = new StringBuilder();
		List<Map<String, String>> base = runs.get(0);
		sb.append("runs=").append(runs.size()).append(" ticksPerRun=").append(base.size()).append('\n');
		sb.append("tick 0 = RNG を適用して tick を再開した直後 (ワールドはまだ 1 tick も進んでいない)\n");
		for (int r = 1; r < runs.size(); r++) {
			List<Map<String, String>> other = runs.get(r);
			int n = Math.min(base.size(), other.size());
			int first = -1;
			int divergedTicks = 0;
			List<String> firstDiffs = null;
			for (int t = 0; t < n; t++) {
				List<String> diffs = diffKeys(base.get(t), other.get(t));
				if (!diffs.isEmpty()) {
					divergedTicks++;
					if (first < 0) {
						first = t;
						firstDiffs = diffs;
					}
				}
			}
			sb.append("\n=== run ").append(r).append(" vs run 0 ===\n");
			if (first < 0) {
				sb.append("IDENTICAL for all ").append(n).append(" ticks\n");
				continue;
			}
			sb.append("FIRST DIVERGENCE at tick ").append(first).append(" (").append(firstDiffs.size())
				.append(" items differ; ").append(divergedTicks).append('/').append(n).append(" ticks differ)\n");
			int shown = 0;
			for (String k : firstDiffs) {
				if (shown++ >= 60) {
					sb.append("  ...\n");
					break;
				}
				sb.append("  ").append(k).append(": ").append(base.get(first).get(k)).append("  ->  ").append(other.get(first).get(k)).append('\n');
			}
			if (first > 0) {
				sb.append("  (tick ").append(first - 1).append(" は全項目一致)\n");
			}
			// 種類ごと (エンティティは world + 種類、それ以外は項目そのもの) に最初にずれた tick。
			// 早い段階でずれる種類 (例: アイテム) に隠れて、他の種類のずれが見えなくなるのを避けるため
			Map<String, Integer> firstByCategory = new TreeMap<>();
			Map<String, String> exampleByCategory = new TreeMap<>();
			for (int t = 0; t < n; t++) {
				for (String k : diffKeys(base.get(t), other.get(t))) {
					String cat = category(k);
					if (!firstByCategory.containsKey(cat)) {
						firstByCategory.put(cat, t);
						exampleByCategory.put(cat, k + ": " + base.get(t).get(k) + "  ->  " + other.get(t).get(k));
					}
				}
			}
			sb.append("  --- first divergence by category ---\n");
			firstByCategory.entrySet().stream()
				.sorted(Map.Entry.comparingByValue())
				.forEach(e -> sb.append("  tick ").append(e.getValue()).append("  ").append(e.getKey())
					.append("    e.g. ").append(exampleByCategory.get(e.getKey())).append('\n'));
		}
		return sb.toString();
	}

	private static String category(String key) {
		if (key.startsWith("entity ")) {
			String[] p = key.split(" ");
			return "entity " + p[1] + " " + p[2] + " " + p[4];
		}
		return key;
	}

	private static List<String> diffKeys(Map<String, String> a, Map<String, String> b) {
		TreeSet<String> keys = new TreeSet<>(a.keySet());
		keys.addAll(b.keySet());
		List<String> out = new ArrayList<>();
		for (String k : keys) {
			if (!Objects.equals(a.get(k), b.get(k))) {
				out.add(k);
			}
		}
		return out;
	}
}
