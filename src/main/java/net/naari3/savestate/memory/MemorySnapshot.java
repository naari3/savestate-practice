package net.naari3.savestate.memory;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.scoreboard.ScoreboardState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ForcedChunkState;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import net.minecraft.world.gen.Spawner;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.level.ServerWorldProperties;
import net.naari3.savestate.SavestateDebug;
import net.naari3.savestate.SavestateMod;
import net.naari3.savestate.detcheck.DetCheck;
import net.naari3.savestate.mixin.accessor.EntityAccessor;
import net.naari3.savestate.mixin.accessor.PersistentStateManagerAccessor;
import net.naari3.savestate.mixin.accessor.ScheduledTickAccessor;
import net.naari3.savestate.mixin.accessor.ServerWorldAccessor;
import net.naari3.savestate.mixin.accessor.WorldAccessor;
import net.naari3.savestate.rng.RngState;

/**
 * インメモリ方式のスナップショット。
 *
 * - スナップショット自体は生きたワールドに入れない。復元ではもう一度複製するので、同じスナップショットから何度でも戻せる
 * - プレイヤーは接続に結び付いているので差し替えず、生きているオブジェクトへ書き戻す ({@link PlayerBefore})
 * - entitiesById の並び (エンティティの tick の順序) も取得時と同じにする
 *
 * サーバースレッドの tick の合間 (server.submit のタスク) で呼ぶこと。
 */
public final class MemorySnapshot {
	private static SharePolicy policy;
	private static SharePolicy chunkPolicy;

	private final Map<RegistryKey<World>, WorldSnap> worlds = new LinkedHashMap<>();
	private final List<Entity> roots = new ArrayList<>();
	/** プレイヤーの複製 (UUID ごと)。復元では生きているプレイヤーに書き戻す。 */
	private final Map<UUID, ServerPlayerEntity> players = new LinkedHashMap<>();
	private final CompoundTag rng;
	private final int maxEntityId;
	private final long scheduledTickIdCounter;

	private static final class WorldSnap {
		/** entitiesById の並び (複製したエンティティとプレイヤー)。 */
		final List<Object> order = new ArrayList<>();
		WorldChunksSnapshot chunks;
		/** ServerWorld の spawners と同じ並び。 */
		final List<Object> spawners = new ArrayList<>();
		/** スコアボードは除く。 */
		final Map<String, PersistentState> states = new LinkedHashMap<>();
		Object dragonFight;
		int traderSpawnDelay;
		int idleTimeout;
		int traderSpawnChance;
		long time;
		long timeOfDay;
		int rainTime;
		int thunderTime;
		int clearWeatherTime;
		boolean raining;
		boolean thundering;
		float rainGradientPrev;
		float rainGradient;
		float thunderGradientPrev;
		float thunderGradient;
	}

	private MemorySnapshot(CompoundTag rng, int maxEntityId, long scheduledTickIdCounter) {
		this.rng = rng;
		this.maxEntityId = maxEntityId;
		this.scheduledTickIdCounter = scheduledTickIdCounter;
	}

	private static synchronized SharePolicy policy() {
		if (policy == null) {
			policy = new SharePolicy(false, true);
		}
		return policy;
	}

	private static synchronized SharePolicy chunkPolicy() {
		if (chunkPolicy == null) {
			chunkPolicy = new SharePolicy(true, false);
		}
		return chunkPolicy;
	}

	public static MemorySnapshot capture(MinecraftServer server) {
		long start = System.nanoTime();
		MemorySnapshot snap = new MemorySnapshot(RngState.capture(server), EntityAccessor.savestate$getMaxEntityId().get(),
			ScheduledTickAccessor.savestate$getIdCounter());
		List<Entity> originals = new ArrayList<>();
		int skipped = 0;
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = new WorldSnap();
			// 読み込まれた (FULL の) チャンクにいないエンティティは取り込まない。チャンクの読み込みを外す処理は後からタスクとして走るので、
			// 外れかけのチャンクにいたエンティティがしばらく entitiesById に残っている。それらはチャンクと一緒にディスクへ保存されるので、
			// 取り込むと復元時に「チャンクが読み込まれていれば入る、いなければ入らない」と結果が回ごとに変わる
			Set<Long> full = WorldChunksSnapshot.fullChunkKeys(world);
			for (Entity e : ((ServerWorldAccessor) world).savestate$getEntitiesById().values()) {
				if (e instanceof EnderDragonPart) {
					continue;
				}
				if (!(e instanceof ServerPlayerEntity) && !full.contains(ChunkPos.toLong(e.chunkX, e.chunkZ))) {
					skipped++;
					continue;
				}
				ws.order.add(e);
				originals.add(e);
			}
			captureWorldState(world, ws);
			ws.chunks = WorldChunksSnapshot.capture(world, chunkPolicy());
			snap.worlds.put(world.getRegistryKey(), ws);
		}

		// 全ワールドのエンティティ、プレイヤー、ワールド全体の状態を 1 つの対応表で複製する。
		// 乗り物、ターゲット、釣り針、レイドの参加者、ドラゴン戦など、互いの参照をそろえるため
		DeepCloner cloner = new DeepCloner(policy());
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = snap.worlds.get(world.getRegistryKey());
			for (Spawner spawner : ((ServerWorldAccessor) world).savestate$getSpawners()) {
				cloner.forceClone(spawner);
				ws.spawners.add(cloner.mapRoot(spawner));
			}
			for (Map.Entry<String, PersistentState> e : persistentStates(world).entrySet()) {
				// 値が null のもの (バニラが「ファイルがなかった」ことを覚えておくために入れている) とスコアボードは扱わない
				if (e.getValue() == null || e.getValue() instanceof ScoreboardState) {
					continue;
				}
				cloner.forceClone(e.getValue());
				ws.states.put(e.getKey(), cloner.mapRoot(e.getValue()));
			}
			if (world.getEnderDragonFight() != null) {
				cloner.forceClone(world.getEnderDragonFight());
				ws.dragonFight = cloner.mapRoot(world.getEnderDragonFight());
			}
		}
		List<Entity> clones = cloner.copyAll(originals);
		IdentityHashMap<Object, Object> toClone = new IdentityHashMap<>();
		for (int i = 0; i < originals.size(); i++) {
			toClone.put(originals.get(i), clones.get(i));
			if (clones.get(i) instanceof ServerPlayerEntity) {
				snap.players.put(clones.get(i).getUuid(), (ServerPlayerEntity) clones.get(i));
			} else {
				snap.roots.add(clones.get(i));
			}
		}
		for (WorldSnap ws : snap.worlds.values()) {
			ws.order.replaceAll(toClone::get);
			// ここから先の、読み込み済みチャンク以外への変化を記録し始める
			ws.chunks.activateJournal();
		}

		long ms = (System.nanoTime() - start) / 1_000_000L;
		int chunkCount = 0;
		for (WorldSnap ws : snap.worlds.values()) {
			chunkCount += ws.chunks.chunkCount();
		}
		SavestateMod.LOGGER.info("[memory] captured {} entities, {} players, {} chunks in {} ms ({} entities in chunks being unloaded were skipped)",
			snap.roots.size(), snap.players.size(), chunkCount, ms, skipped);
		if (SavestateDebug.ENABLED) {
			logCounts("capture", cloner.getClonedCounts());
		}
		return snap;
	}

	/**
	 * 復元する。
	 *
	 * 1. 準備: ワールドに手を入れずに、失敗しうる処理 (複製の作成、書き戻す値の計算) をすべて済ませる。ここで失敗したら何も変わらない
	 * 2. 取り消し用のスナップショットを取る (makeUndo のとき)
	 * 3. 適用: ワールドを書き換える。途中で例外が起きたら、取り消し用のスナップショットで元に戻す
	 *
	 * 戻り値は取り消し用のスナップショット (makeUndo でないときは null)。「直前の load を取り消す」に使える。
	 */
	public MemorySnapshot restore(MinecraftServer server, boolean makeUndo) {
		long t0 = System.nanoTime();
		Prepared prepared = this.prepare(server);
		long t1 = System.nanoTime();
		MemorySnapshot undo = makeUndo ? capture(server) : null;
		SavestateDebug.log("restore timing: prepare {} ms, undo capture {} ms", (t1 - t0) / 1_000_000L, (System.nanoTime() - t1) / 1_000_000L);
		// 調査用: 故障の注入を有効にしているときは、ロールバックで元に戻ったかを比べるため、今の状態の要約を取っておく
		Map<String, String> stateBefore = makeUndo && SavestateDebug.faultInjectionEnabled() ? DetCheck.describe(server) : null;

		List<ChunkJournal> journals = new ArrayList<>();
		for (WorldSnap ws : this.worlds.values()) {
			journals.add(ws.chunks.journal());
		}
		ChunkJournal.beginRestore(journals);
		try {
			this.apply(server, prepared);
		} catch (Throwable t) {
			SavestateMod.LOGGER.error("[memory] restore failed while modifying the world", t);
			for (ServerWorld world : server.getWorlds()) {
				WorldSnap ws = this.worlds.get(world.getRegistryKey());
				if (ws != null) {
					ws.chunks.releaseRestoreTickets(world);
				}
			}
			if (undo != null) {
				ChunkJournal.endRestore(journals);
				journals.clear();
				try {
					undo.restore(server, false);
					SavestateMod.LOGGER.warn("[memory] rolled back to the state before the failed restore");
					if (stateBefore != null) {
						SavestateMod.LOGGER.warn("[memory] [debug] after rollback: {}", DetCheck.summarizeDiff(stateBefore, DetCheck.describe(server)));
					}
				} catch (Throwable t2) {
					SavestateMod.LOGGER.error("[memory] rollback also failed; the world may be in an inconsistent state", t2);
				}
			} else {
				SavestateMod.LOGGER.error("[memory] no rollback snapshot; the world may be in an inconsistent state");
			}
			throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
		} finally {
			ChunkJournal.endRestore(journals);
		}
		return undo;
	}

	/** このスナップショットを使わなくなったとき (スロットの上書き、別のワールドを開いたとき) に呼ぶ。 */
	public void dispose() {
		for (WorldSnap ws : this.worlds.values()) {
			ws.chunks.dispose();
		}
	}

	private static final class PhaseTimer {
		private final StringBuilder sb = new StringBuilder();
		private long last = System.nanoTime();

		void mark(String name) {
			long now = System.nanoTime();
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(name).append(' ').append((now - last) / 1_000_000L).append(" ms");
			last = now;
		}

		@Override
		public String toString() {
			return sb.toString();
		}
	}

	private static final class Prepared {
		final Map<RegistryKey<World>, WorldChunksSnapshot.Prepared> chunks = new LinkedHashMap<>();
		DeepCloner cloner;
		final IdentityHashMap<Object, Entity> toFresh = new IdentityHashMap<>();
		final List<PersistentState> touchedStates = new ArrayList<>();
		final List<Runnable> pendingPuts = new ArrayList<>();
	}

	/** ワールドには手を入れない。 */
	private Prepared prepare(MinecraftServer server) {
		Prepared p = new Prepared();
		for (Map.Entry<RegistryKey<World>, WorldSnap> e : this.worlds.entrySet()) {
			p.chunks.put(e.getKey(), e.getValue().chunks.prepare(chunkPolicy()));
		}
		// スナップショットはそのまま残し、そのまた複製を作る。プレイヤーとワールド全体の状態は、生きているオブジェクトへ書き戻す値を計算しておく
		DeepCloner cloner = new DeepCloner(policy());
		for (Map.Entry<UUID, ServerPlayerEntity> en : this.players.entrySet()) {
			ServerPlayerEntity live = server.getPlayerManager().getPlayer(en.getKey());
			if (live != null) {
				cloner.copyIntoDeferred(en.getValue(), live);
			}
		}
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = this.worlds.get(world.getRegistryKey());
			if (ws != null) {
				this.bindWorldLevel(world, ws, cloner, p.touchedStates, p.pendingPuts);
			}
		}
		for (Entity root : this.roots) {
			p.toFresh.put(root, cloner.mapRoot(root));
		}
		cloner.finish();
		p.cloner = cloner;
		return p;
	}

	private void apply(MinecraftServer server, Prepared prepared) {
		long start = System.nanoTime();
		PlayerManager playerManager = server.getPlayerManager();
		IdentityHashMap<Object, Entity> toFresh = prepared.toFresh;
		Map<RegistryKey<World>, LongSet> forcedBefore = new LinkedHashMap<>();
		for (ServerWorld world : server.getWorlds()) {
			forcedBefore.put(world.getRegistryKey(), new LongOpenHashSet(world.getForcedChunks()));
		}

		// 別のディメンションにいるプレイヤーは、PlayerBefore を取る前に取得時のディメンションへ移す
		Map<ServerPlayerEntity, PlayerBefore> before = new IdentityHashMap<>();
		for (Map.Entry<UUID, ServerPlayerEntity> en : this.players.entrySet()) {
			ServerPlayerEntity live = playerManager.getPlayer(en.getKey());
			if (live == null) {
				continue;
			}
			ServerPlayerEntity snapPlayer = en.getValue();
			ServerWorld target = (ServerWorld) snapPlayer.world;
			if (live.world != target) {
				live.teleport(target, snapPlayer.getX(), snapPlayer.getY(), snapPlayer.getZ(), snapPlayer.yaw, snapPlayer.pitch);
			}
			before.put(live, new PlayerBefore(live));
		}

		PhaseTimer timer = new PhaseTimer();
		timer.mark("teleport");

		int[] chunkStats = new int[3];
		int syncLoaded = 0;
		int appliedInPlace = 0;
		int rewrittenOnDisk = 0;
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = this.worlds.get(world.getRegistryKey());
			// チャンクを先に戻す。エンティティは戻したチャンクに入れる
			if (ws != null) {
				WorldChunksSnapshot.Prepared pc = prepared.chunks.get(world.getRegistryKey());
				syncLoaded += ws.chunks.restoreOutside(world, pc);
				timer.mark(world.getRegistryKey().getValue().getPath() + " outside");
				int[] s = ws.chunks.restore(world, pc);
				for (int k = 0; k < 3; k++) {
					chunkStats[k] += s[k];
				}
			}
			Int2ObjectMap<Entity> byId = ((ServerWorldAccessor) world).savestate$getEntitiesById();
			for (Entity e : new ArrayList<>(byId.values())) {
				if (!(e instanceof ServerPlayerEntity) && !(e instanceof EnderDragonPart)) {
					world.removeEntity(e);
				}
			}
			byId.values().removeIf(e -> e instanceof EnderDragonPart);
			timer.mark(world.getRegistryKey().getValue().getPath() + " chunks+remove");
		}

		SavestateDebug.maybeInjectFault();

		prepared.cloner.applyDeferredWrites();
		for (Runnable put : prepared.pendingPuts) {
			put.run();
		}
		for (PersistentState state : prepared.touchedStates) {
			state.markDirty();
		}
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = this.worlds.get(world.getRegistryKey());
			if (ws != null) {
				syncForcedChunkTickets(world, ws, forcedBefore.get(world.getRegistryKey()));
			}
		}

		// entitiesById の並びは tick の順序なので、取得時と同じにする
		int restored = 0;
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = this.worlds.get(world.getRegistryKey());
			if (ws == null) {
				continue;
			}
			Int2ObjectMap<Entity> byId = ((ServerWorldAccessor) world).savestate$getEntitiesById();
			Map<Integer, Entity> desired = new LinkedHashMap<>();
			for (Object o : ws.order) {
				if (o instanceof ServerPlayerEntity) {
					ServerPlayerEntity live = playerManager.getPlayer(((ServerPlayerEntity) o).getUuid());
					if (live != null && live.world == world) {
						desired.put(live.getEntityId(), live);
					}
					continue;
				}
				Entity e = toFresh.get(o);
				Chunk chunk = world.getChunk(MathHelper.floor(e.getX() / 16.0), MathHelper.floor(e.getZ() / 16.0), ChunkStatus.FULL, false);
				if (!(chunk instanceof WorldChunk)) {
					SavestateMod.LOGGER.warn("[memory] chunk for {} at {} is not loaded; entity skipped", e, e.getBlockPos());
					continue;
				}
				chunk.addEntity(e);
				world.loadEntity(e);
				restored++;
				desired.put(e.getEntityId(), e);
				if (e instanceof EnderDragonEntity) {
					for (EnderDragonPart part : ((EnderDragonEntity) e).getBodyParts()) {
						desired.put(part.getEntityId(), part);
					}
				}
			}
			// スナップショットにないもの (後から入ったプレイヤーなど) は末尾
			for (Int2ObjectMap.Entry<Entity> en : byId.int2ObjectEntrySet()) {
				desired.putIfAbsent(en.getIntKey(), en.getValue());
			}
			byId.clear();
			for (Map.Entry<Integer, Entity> en : desired.entrySet()) {
				byId.put((int) en.getKey(), en.getValue());
			}
			restoreWorldState(world, ws);
		}
		// この後、取得後に読み込まれたチャンクのエンティティを記録から作り直す (spawnFromTags) ので、その前に戻す。
		// 後で戻すと、作り直したエンティティの ID と、その後に作られるエンティティの ID が重なる
		restoreMaxEntityId(server);

		timer.mark("entities");

		// プレイヤーの位置の同期より先に、戻したブロックをクライアントへ送る (flushBlockUpdates を参照)
		for (ServerWorld world : server.getWorlds()) {
			WorldChunksSnapshot.flushBlockUpdates(world);
		}
		for (Map.Entry<ServerPlayerEntity, PlayerBefore> en : before.entrySet()) {
			en.getValue().afterRestore(en.getKey());
		}
		timer.mark("players");

		// 読み込み済みのチャンクの集合を取得時と同じにそろえ (取得後に読み込まれたチャンクを外し)、外れたチャンクに記録した NBT を書き戻す
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = this.worlds.get(world.getRegistryKey());
			if (ws == null) {
				continue;
			}
			if (SavestateDebug.ENABLED) {
				ws.chunks.logMissingTicks(world, "before convergence");
			}
			int[] c = ws.chunks.convergeLoadedSet(world);
			timer.mark(world.getRegistryKey().getValue().getPath() + " converge");
			if (SavestateDebug.ENABLED) {
				ws.chunks.logMissingTicks(world, "after convergence");
			}
			List<CompoundTag> leftover = new ArrayList<>();
			int[] rw = ws.chunks.rewriteJournalChunks(world, leftover);
			rewrittenOnDisk += rw[0];
			appliedInPlace += rw[1];
			spawnFromTags(world, leftover);
			timer.mark(world.getRegistryKey().getValue().getPath() + " rewrite");
			SavestateMod.LOGGER.info("[memory] {}: loaded chunk set converged after {} iterations in {} ms: {} loaded, {} missing, {} extra",
				world.getRegistryKey().getValue(), c[0], c[1], c[2], c[3], c[4]);
		}

		RngState.apply(server, this.rng);
		ScheduledTickAccessor.savestate$setIdCounter(this.scheduledTickIdCounter);

		if (SavestateDebug.ENABLED) {
			// 調査用: 復元後のワールドにいる、スナップショットから入れたものでもプレイヤーでもないエンティティ
			Set<Entity> fresh = Collections.newSetFromMap(new IdentityHashMap<>());
			fresh.addAll(toFresh.values());
			for (ServerWorld world : server.getWorlds()) {
				WorldSnap ws = this.worlds.get(world.getRegistryKey());
				int strays = 0;
				for (Entity e : world.iterateEntities()) {
					if (fresh.contains(e) || e instanceof ServerPlayerEntity || e instanceof EnderDragonPart) {
						continue;
					}
					if (strays++ < 6) {
						long ck = ChunkPos.toLong(e.chunkX, e.chunkZ);
						SavestateDebug.log("stray entity after restore: {} {} at {} chunk=({}, {}) loaded={} inSnapshot={} age={}",
							e.getType(), e.getUuidAsString().substring(0, 8), e.getBlockPos(), e.chunkX, e.chunkZ,
							world.getChunk(e.chunkX, e.chunkZ, ChunkStatus.FULL, false) != null,
							ws != null && ws.chunks.journal().snapshotLoaded.contains(ck), e.age);
					}
				}
				if (strays > 0) {
					SavestateDebug.log("{}: {} stray entities after restore", world.getRegistryKey().getValue(), strays);
				}
			}
		}

		SavestateDebug.log("apply timing: {}", timer);
		long ms = (System.nanoTime() - start) / 1_000_000L;
		SavestateMod.LOGGER.info("[memory] restored {} entities, {} chunks ({} missing, {} changed blocks), outside: {} sync-loaded, {} re-applied in place, {} rewritten on disk, in {} ms (apply only)",
			restored, chunkStats[0], chunkStats[1], chunkStats[2], syncLoaded, appliedInPlace, rewrittenOnDisk, ms);
		if (chunkStats[1] > 0) {
			SavestateMod.LOGGER.warn("[memory] {} chunks from the snapshot are not loaded now and were not restored", chunkStats[1]);
		}
		if (SavestateDebug.ENABLED) {
			logCounts("restore", prepared.cloner.getClonedCounts());
		}
	}

	/**
	 * ワールド全体の状態 (スポーンの管理、PersistentState、ドラゴン戦) を、生きているオブジェクトへの書き戻しとして複製器に登録する。
	 * エンティティと同じ対応表なので、レイドの参加者やドラゴン戦の水晶などの参照は、戻したエンティティを指す。
	 */
	private void bindWorldLevel(ServerWorld world, WorldSnap ws, DeepCloner cloner, List<PersistentState> touchedStates, List<Runnable> pendingPuts) {
		List<Spawner> liveSpawners = ((ServerWorldAccessor) world).savestate$getSpawners();
		for (int i = 0; i < Math.min(liveSpawners.size(), ws.spawners.size()); i++) {
			if (liveSpawners.get(i).getClass() == ws.spawners.get(i).getClass()) {
				cloner.copyIntoDeferred(ws.spawners.get(i), liveSpawners.get(i));
			}
		}
		Map<String, PersistentState> liveStates = persistentStates(world);
		for (Map.Entry<String, PersistentState> e : ws.states.entrySet()) {
			PersistentState live = liveStates.get(e.getKey());
			if (live != null && live.getClass() == e.getValue().getClass()) {
				cloner.copyIntoDeferred(e.getValue(), live);
				touchedStates.add(live);
			} else if (live == null) {
				PersistentState fresh = cloner.mapRoot(e.getValue());
				String key = e.getKey();
				pendingPuts.add(() -> liveStates.put(key, fresh));
				touchedStates.add(fresh);
			}
		}
		if (ws.dragonFight != null && world.getEnderDragonFight() != null) {
			cloner.copyIntoDeferred(ws.dragonFight, world.getEnderDragonFight());
		}
	}

	/** 記録した NBT からエンティティを作ってワールドに入れる (取得後に読み込まれたチャンクが外れずに残ったときだけ使う)。 */
	private static void spawnFromTags(ServerWorld world, List<CompoundTag> tags) {
		for (CompoundTag tag : tags) {
			EntityType.loadEntityWithPassengers(tag, world, ent -> {
				Chunk c = world.getChunk(MathHelper.floor(ent.getX() / 16.0), MathHelper.floor(ent.getZ() / 16.0), ChunkStatus.FULL, false);
				if (c instanceof WorldChunk && world.loadEntity(ent)) {
					c.addEntity(ent);
				}
				return ent;
			});
		}
	}

	private static Map<String, PersistentState> persistentStates(ServerWorld world) {
		return ((PersistentStateManagerAccessor) world.getPersistentStateManager()).savestate$getLoadedStates();
	}

	/**
	 * 強制読み込み (/forceload) の一覧は ForcedChunkState (PersistentState) として戻るが、チャンクを読み込ませ続けるチケットは別にあり、
	 * 戻らない。一覧に合わせてチケットを付け外しする。合わせないと、取得後に強制読み込みしたチャンクが外れなくなる。
	 */
	private static void syncForcedChunkTickets(ServerWorld world, WorldSnap ws, LongSet before) {
		PersistentState snap = ws.states.get("chunks");
		LongSet target = snap instanceof ForcedChunkState ? ((ForcedChunkState) snap).getChunks() : LongSets.EMPTY_SET;
		if (!(snap instanceof ForcedChunkState)) {
			// 取得時には一覧のデータ自体がなかった (取得後に初めて強制読み込みした)。一覧を空に戻す
			PersistentState live = persistentStates(world).get("chunks");
			if (live instanceof ForcedChunkState) {
				((ForcedChunkState) live).getChunks().clear();
				live.markDirty();
			}
		}
		for (long k : before) {
			if (!target.contains(k)) {
				world.getChunkManager().setChunkForced(new ChunkPos(k), false);
			}
		}
		for (long k : target) {
			if (!before.contains(k)) {
				world.getChunkManager().setChunkForced(new ChunkPos(k), true);
			}
		}
	}

	private void restoreMaxEntityId(MinecraftServer server) {
		// 今生きているエンティティ (取得後にリスポーンしたプレイヤーなど) の ID と重ならないときだけ戻す
		int maxLive = 0;
		for (ServerWorld world : server.getWorlds()) {
			for (Entity e : world.iterateEntities()) {
				maxLive = Math.max(maxLive, e.getEntityId());
			}
		}
		AtomicInteger counter = EntityAccessor.savestate$getMaxEntityId();
		if (maxLive <= this.maxEntityId) {
			counter.set(this.maxEntityId);
		} else {
			SavestateMod.LOGGER.warn("[memory] entity id counter not restored (live entity id {} > saved counter {})", maxLive, this.maxEntityId);
		}
	}

	private static void captureWorldState(ServerWorld world, WorldSnap ws) {
		ServerWorldProperties p = ((ServerWorldAccessor) world).savestate$getWorldProperties();
		WorldAccessor wa = (WorldAccessor) world;
		ws.idleTimeout = ((ServerWorldAccessor) world).savestate$getIdleTimeout();
		ws.traderSpawnDelay = p.getWanderingTraderSpawnDelay();
		ws.traderSpawnChance = p.getWanderingTraderSpawnChance();
		ws.time = p.getTime();
		ws.timeOfDay = p.getTimeOfDay();
		ws.rainTime = p.getRainTime();
		ws.thunderTime = p.getThunderTime();
		ws.clearWeatherTime = p.getClearWeatherTime();
		ws.raining = p.isRaining();
		ws.thundering = p.isThundering();
		ws.rainGradientPrev = wa.savestate$getRainGradientPrev();
		ws.rainGradient = wa.savestate$getRainGradient();
		ws.thunderGradientPrev = wa.savestate$getThunderGradientPrev();
		ws.thunderGradient = wa.savestate$getThunderGradient();
	}

	private static void restoreWorldState(ServerWorld world, WorldSnap ws) {
		ServerWorldProperties p = ((ServerWorldAccessor) world).savestate$getWorldProperties();
		WorldAccessor wa = (WorldAccessor) world;
		((ServerWorldAccessor) world).savestate$setIdleTimeout(ws.idleTimeout);
		p.setWanderingTraderSpawnDelay(ws.traderSpawnDelay);
		p.setWanderingTraderSpawnChance(ws.traderSpawnChance);
		p.setTime(ws.time);
		p.setTimeOfDay(ws.timeOfDay);
		p.setRainTime(ws.rainTime);
		p.setThunderTime(ws.thunderTime);
		p.setClearWeatherTime(ws.clearWeatherTime);
		p.setRaining(ws.raining);
		p.setThundering(ws.thundering);
		wa.savestate$setRainGradientPrev(ws.rainGradientPrev);
		wa.savestate$setRainGradient(ws.rainGradient);
		wa.savestate$setThunderGradientPrev(ws.thunderGradientPrev);
		wa.savestate$setThunderGradient(ws.thunderGradient);
	}

	private static void logCounts(String label, Map<Class<?>, Integer> counts) {
		StringBuilder sb = new StringBuilder();
		counts.entrySet().stream()
			.sorted((a, b) -> b.getValue() - a.getValue())
			.forEach(e -> sb.append("\n  ").append(e.getValue()).append("  ").append(e.getKey().getName()));
		SavestateDebug.log("{} cloned classes ({} kinds):{}", label, counts.size(), sb);
	}
}
