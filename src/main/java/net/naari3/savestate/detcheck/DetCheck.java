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
 * 要約は「項目名 → 値の文字列」の対応 (中身は {@link #snapshot} と {@link #snapshotChunks})。
 * 同じスロットから複数回 load した記録を {@link #compare} で比べ、最初にずれた tick と項目を報告する。
 */
public final class DetCheck {
	private static final net.minecraft.network.PacketByteBuf SECTION_BUF = new net.minecraft.network.PacketByteBuf(io.netty.buffer.Unpooled.buffer());
	private static volatile boolean armed;
	private static int runIndex = -1;
	/** 調査用: UUID の先頭が一致する ("type:種類" なら種類が一致する) エンティティを DUMP_TICKS の範囲でファイルに書き出す。 */
	private static final String DUMP_ENTITY = System.getProperty("savestate-practice.detcheck.dump");
	/** 書き出す tick の範囲 ("from-to"、既定は 0-2)。 */
	private static final int[] DUMP_TICKS = parseRange(System.getProperty("savestate-practice.detcheck.dumpTicks", "0-2"));
	private static volatile int ticksToRecord;
	private static volatile List<Map<String, String>> recording;
	private static volatile List<Map<String, String>> finished;

	private DetCheck() {
	}

	/** 次の loadstate の再開時点から ticks 回分を記録する。 */
	public static void arm(int ticks) {
		runIndex++;
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

	/**
	 * サーバースレッド。インメモリ方式の復元直後 (tick の合間) に呼ぶ。
	 * 復元した状態をその場で tick 0 として記録する (次の記録は 1 tick 進んだ後)。
	 */
	public static void onResumeImmediate(MinecraftServer server) {
		if (armed) {
			armed = false;
			List<Map<String, String>> r = new ArrayList<>();
			recording = r;
			r.add(snapshot(server));
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

	public static Map<String, String> describe(MinecraftServer server) {
		return snapshot(server);
	}

	public static String summarizeDiff(Map<String, String> a, Map<String, String> b) {
		List<String> diffs = diffKeys(a, b);
		StringBuilder sb = new StringBuilder();
		sb.append(diffs.size()).append(" items differ");
		for (int i = 0; i < Math.min(8, diffs.size()); i++) {
			String k = diffs.get(i);
			sb.append("; ").append(k).append(": ").append(a.get(k)).append(" -> ").append(b.get(k));
		}
		return sb.toString();
	}

	private static int[] parseRange(String s) {
		String[] parts = s.split("-", 2);
		int from = Integer.parseInt(parts[0].trim());
		return new int[] { from, parts.length > 1 ? Integer.parseInt(parts[1].trim()) : from };
	}

	private static void dumpEntity(MinecraftServer server, int tick) {
		for (ServerWorld world : server.getWorlds()) {
			for (Entity e : world.iterateEntities()) {
				boolean match = DUMP_ENTITY.startsWith("type:")
					? EntityType.getId(e.getType()).getPath().equals(DUMP_ENTITY.substring(5))
					: e.getUuidAsString().startsWith(DUMP_ENTITY);
				if (match) {
					java.nio.file.Path out = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("savestates")
						.resolve("dump-" + e.getUuidAsString().substring(0, 8) + "-run" + runIndex + "-tick" + tick + ".txt");
					try {
						java.nio.file.Files.write(out, ObjectDumper.dump(e, 12));
					} catch (java.io.IOException ex) {
						throw new RuntimeException(ex);
					}
				}
			}
		}
	}

	private static Map<String, String> snapshot(MinecraftServer server) {
		List<Map<String, String>> r = recording;
		if (DUMP_ENTITY != null && r != null && r.size() >= DUMP_TICKS[0] && r.size() <= DUMP_TICKS[1]) {
			dumpEntity(server, r.size());
		}
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
				m.put(k + " id", Integer.toString(e.getEntityId()));
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
			snapshotChunks(world, w, m);
		}
		return m;
	}

	private static void snapshotChunks(ServerWorld world, String w, Map<String, String> m) {
		int loaded = 0;
		int ticking = 0;
		long all = 17;
		int holders = 0;
		long holderOrder = 17;
		for (net.minecraft.server.world.ChunkHolder holder : ((ThreadedAnvilChunkStorageAccess) world.getChunkManager().threadedAnvilChunkStorage).savestate$chunkHolders()) {
			// tickChunks はこの並びのリストをシャッフルして処理するので、並びが違うと乱数を引くチャンクの順が変わる
			holders++;
			holderOrder = holderOrder * 31 + holder.getPos().toLong();
			com.mojang.datafixers.util.Either<net.minecraft.world.chunk.WorldChunk, net.minecraft.server.world.ChunkHolder.Unloaded> full = holder.getBorderFuture().getNow(null);
			net.minecraft.world.chunk.WorldChunk chunk = full == null ? null : full.left().orElse(null);
			if (chunk == null) {
				continue;
			}
			loaded++;
			if (holder.getWorldChunk() != null) {
				ticking++;
			}
			long h = 1;
			for (net.minecraft.world.chunk.ChunkSection section : chunk.getSectionArray()) {
				if (section == null || section.isEmpty()) {
					h = h * 31;
					continue;
				}
				// クライアントに送る形式 (パレット + データ配列) に書き出してハッシュを取る。4096 ブロックを 1 つずつ読むより速い
				SECTION_BUF.clear();
				section.toPacket(SECTION_BUF);
				for (int i = 0; i < SECTION_BUF.writerIndex(); i++) {
					h = h * 31 + SECTION_BUF.getByte(i);
				}
			}
			h = h * 31 + chunk.getBlockEntities().size();
			List<net.minecraft.util.math.BlockPos> bePositions = new ArrayList<>(chunk.getBlockEntities().keySet());
			bePositions.sort(null);
			for (net.minecraft.util.math.BlockPos p : bePositions) {
				h = h * 31 + chunk.getBlockEntities().get(p).toTag(new net.minecraft.nbt.CompoundTag()).toString().hashCode();
			}
			m.put("chunk " + w + " " + chunk.getPos().x + "," + chunk.getPos().z + " blocks", Long.toHexString(h));
			all = all * 31 + h;
		}
		m.put("world " + w + " chunkHolders", holders + " " + Long.toHexString(holderOrder));
		m.put("world " + w + " loadedChunks", Integer.toString(loaded));
		m.put("world " + w + " tickingChunks", Integer.toString(ticking));
		m.put("world " + w + " scheduledBlockTicks", Integer.toString(world.getBlockTickScheduler().getTicks()));
		m.put("world " + w + " scheduledFluidTicks", Integer.toString(world.getFluidTickScheduler().getTicks()));
	}

	/** runs[0] を基準に、他の回と tick ごとに比べた結果の文章を返す。runs が 3 回以上なら、runs[1] を基準にした比較も出す。 */
	public static String compare(List<List<Map<String, String>>> runs) {
		StringBuilder sb = new StringBuilder();
		sb.append("runs=").append(runs.size()).append(" ticksPerRun=").append(runs.get(0).size()).append('\n');
		sb.append("tick 0 = 復元して tick を再開した直後 (ワールドはまだ 1 tick も進んでいない)\n");
		compareAgainst(runs, 0, sb);
		if (runs.size() >= 3) {
			compareAgainst(runs, 1, sb);
		}
		return sb.toString();
	}

	private static void compareAgainst(List<List<Map<String, String>>> runs, int baseIndex, StringBuilder sb) {
		List<Map<String, String>> base = runs.get(baseIndex);
		for (int r = baseIndex + 1; r < runs.size(); r++) {
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
			sb.append("\n=== run ").append(r).append(" vs run ").append(baseIndex).append(" ===\n");
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
	}

	private static String category(String key) {
		if (key.startsWith("chunk ")) {
			String[] p = key.split(" ");
			return "chunk " + p[1] + " " + p[3];
		}
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
