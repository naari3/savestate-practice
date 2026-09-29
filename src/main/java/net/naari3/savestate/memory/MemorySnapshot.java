package net.naari3.savestate.memory;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.naari3.savestate.mixin.accessor.EntityAccessor;
import net.naari3.savestate.mixin.accessor.PersistentStateManagerAccessor;
import net.naari3.savestate.mixin.accessor.ScheduledTickAccessor;
import net.naari3.savestate.mixin.accessor.ServerWorldAccessor;
import net.naari3.savestate.mixin.accessor.WorldAccessor;
import net.naari3.savestate.rng.RngState;

/**
 * インメモリ方式のスナップショット (段階 1: エンティティ + RNG + 時刻・天候 + エンティティ ID の採番)。
 *
 * - プレイヤー以外の全エンティティを {@link DeepCloner} で複製して保持する。スナップショット自体は生きたワールドに入れない
 * - 復元では、スナップショットをもう一度複製し (同じスナップショットから何度でも戻せるように)、今のエンティティと入れ替える
 * - entitiesById の並び (= エンティティの tick の順序) も保存時と同じにする
 * - プレイヤーは共有のまま (段階 3 で戻す)
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
		/** ServerWorld のスポーンの管理 (ファントム、行商人など) の複製。今のリストと同じ並び。 */
		final List<Object> spawners = new ArrayList<>();
		/** PersistentState (raids、map_N、idcounts など。スコアボードは除く) の複製。 */
		final Map<String, PersistentState> states = new LinkedHashMap<>();
		/** ドラゴン戦 (ジ・エンドのみ) の複製。 */
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
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = new WorldSnap();
			for (Entity e : ((ServerWorldAccessor) world).savestate$getEntitiesById().values()) {
				if (!(e instanceof EnderDragonPart)) {
					ws.order.add(e);
					originals.add(e);
				}
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
		SavestateMod.LOGGER.info("[memory] captured {} entities, {} players, {} chunks in {} ms", snap.roots.size(), snap.players.size(), chunkCount, ms);
		if (SavestateDebug.ENABLED) {
			logCounts("capture", cloner.getClonedCounts());
		}
		return snap;
	}

	public void restore(MinecraftServer server) {
		List<ChunkJournal> journals = new ArrayList<>();
		for (WorldSnap ws : this.worlds.values()) {
			journals.add(ws.chunks.journal());
		}
		ChunkJournal.beginRestore(journals);
		try {
			this.restoreInner(server);
		} finally {
			ChunkJournal.endRestore(journals);
		}
	}

	/** このスナップショットを使わなくなったとき (スロットの上書き、別のワールドを開いたとき) に呼ぶ。 */
	public void dispose() {
		for (WorldSnap ws : this.worlds.values()) {
			ws.chunks.dispose();
		}
	}

	private void restoreInner(MinecraftServer server) {
		long start = System.nanoTime();
		PlayerManager playerManager = server.getPlayerManager();

		// 0. 別のディメンションにいるプレイヤーは、先に保存時のディメンションへ移す
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

		int[] chunkStats = new int[3];
		int[] outsideStats = new int[3];
		Map<ServerWorld, List<CompoundTag>> journalEntities = new IdentityHashMap<>();
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = this.worlds.get(world.getRegistryKey());
			// 1. チャンク (ブロック) を先に戻す。エンティティは戻したチャンクに入れる
			if (ws != null) {
				// 1a. 取得時に読み込まれていなかったチャンク、今は読み込まれていないチャンク、POI
				List<CompoundTag> tags = new ArrayList<>();
				journalEntities.put(world, tags);
				int[] o = ws.chunks.restoreOutside(world, chunkPolicy(), tags);
				for (int k = 0; k < 3; k++) {
					outsideStats[k] += o[k];
				}
				// 1b. 取得時に読み込まれていたチャンク
				int[] s = ws.chunks.restore(world, chunkPolicy());
				for (int k = 0; k < 3; k++) {
					chunkStats[k] += s[k];
				}
			}
			// 2. 今のエンティティ (プレイヤー以外) を外す
			Int2ObjectMap<Entity> byId = ((ServerWorldAccessor) world).savestate$getEntitiesById();
			for (Entity e : new ArrayList<>(byId.values())) {
				if (!(e instanceof ServerPlayerEntity) && !(e instanceof EnderDragonPart)) {
					world.removeEntity(e);
				}
			}
			byId.values().removeIf(e -> e instanceof EnderDragonPart);
		}

		// 3. スナップショットはそのまま残し、そのまた複製を作る。プレイヤーは生きているオブジェクトに書き戻す
		DeepCloner cloner = new DeepCloner(policy());
		for (ServerPlayerEntity live : before.keySet()) {
			cloner.copyInto(this.players.get(live.getUuid()), live);
		}
		List<PersistentState> touchedStates = new ArrayList<>();
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = this.worlds.get(world.getRegistryKey());
			if (ws != null) {
				this.bindWorldLevel(world, ws, cloner, touchedStates);
			}
		}
		IdentityHashMap<Object, Entity> toFresh = new IdentityHashMap<>();
		for (Entity root : this.roots) {
			toFresh.put(root, cloner.mapRoot(root));
		}
		cloner.finish();
		for (PersistentState state : touchedStates) {
			state.markDirty();
		}

		// 4. エンティティを入れ、entitiesById の並び (tick の順序) を保存時と同じにする
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

		// 5. プレイヤーの後始末とクライアントへの同期
		for (Map.Entry<ServerPlayerEntity, PlayerBefore> en : before.entrySet()) {
			en.getValue().afterRestore(en.getKey());
		}

		// 6. 読み込み済みのチャンクの集合を取得時と同じにそろえる (取得後に読み込まれたチャンクを外す)
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = this.worlds.get(world.getRegistryKey());
			if (ws != null) {
				if (SavestateDebug.ENABLED) {
					ws.chunks.logMissingTicks(world, "before convergence");
				}
				int[] c = ws.chunks.convergeLoadedSet(world);
				if (SavestateDebug.ENABLED) {
					ws.chunks.logMissingTicks(world, "after convergence");
				}
				List<CompoundTag> leftover = new ArrayList<>();
				int[] rw = ws.chunks.rewriteJournalChunks(world, leftover);
				outsideStats[1] += rw[1];
				outsideStats[2] += rw[0];
				spawnFromTags(world, leftover);
				if (SavestateDebug.ENABLED) {
					int orphan = 0;
					for (Entity e : world.iterateEntities()) {
						if (world.getChunk(e.chunkX, e.chunkZ, ChunkStatus.FULL, false) == null) {
							if (orphan++ < 5) {
								SavestateDebug.log("entity without loaded chunk: {} at {} chunk=({}, {}) inSnapshotChunks={} fromSnapshot={}",
									e.getType(), e.getBlockPos(), e.chunkX, e.chunkZ,
									ws.chunks.journal().snapshotLoaded.contains(net.minecraft.util.math.ChunkPos.toLong(e.chunkX, e.chunkZ)),
									toFresh.containsValue(e));
							}
						}
					}
					SavestateDebug.log("{}: {} entities without a loaded chunk after convergence", world.getRegistryKey().getValue(), orphan);
				}
				SavestateMod.LOGGER.info("[memory] {}: loaded chunk set converged after {} iterations: {} loaded, {} missing, {} extra",
					world.getRegistryKey().getValue(), c[0], c[1], c[2], c[3]);
			}
		}

		RngState.apply(server, this.rng);
		restoreMaxEntityId(server);
		ScheduledTickAccessor.savestate$setIdCounter(this.scheduledTickIdCounter);

		long ms = (System.nanoTime() - start) / 1_000_000L;
		SavestateMod.LOGGER.info("[memory] restored {} entities, {} chunks ({} missing, {} changed blocks), outside: {} sync-loaded, {} re-applied in place, {} rewritten on disk, in {} ms",
			restored, chunkStats[0], chunkStats[1], chunkStats[2], outsideStats[0], outsideStats[1], outsideStats[2], ms);
		if (chunkStats[1] > 0) {
			SavestateMod.LOGGER.warn("[memory] {} chunks from the snapshot are not loaded now and were not restored", chunkStats[1]);
		}
		if (SavestateDebug.ENABLED) {
			logCounts("restore", cloner.getClonedCounts());
		}
	}

	/**
	 * ワールド全体の状態 (スポーンの管理、PersistentState、ドラゴン戦) を、生きているオブジェクトへの書き戻しとして複製器に登録する。
	 * エンティティと同じ対応表なので、レイドの参加者やドラゴン戦の水晶などの参照は、戻したエンティティを指す。
	 */
	private void bindWorldLevel(ServerWorld world, WorldSnap ws, DeepCloner cloner, List<PersistentState> touchedStates) {
		List<Spawner> liveSpawners = ((ServerWorldAccessor) world).savestate$getSpawners();
		for (int i = 0; i < Math.min(liveSpawners.size(), ws.spawners.size()); i++) {
			if (liveSpawners.get(i).getClass() == ws.spawners.get(i).getClass()) {
				cloner.copyInto(ws.spawners.get(i), liveSpawners.get(i));
			}
		}
		Map<String, PersistentState> liveStates = persistentStates(world);
		for (Map.Entry<String, PersistentState> e : ws.states.entrySet()) {
			PersistentState live = liveStates.get(e.getKey());
			if (live != null && live.getClass() == e.getValue().getClass()) {
				cloner.copyInto(e.getValue(), live);
				touchedStates.add(live);
			} else if (live == null) {
				PersistentState fresh = cloner.mapRoot(e.getValue());
				liveStates.put(e.getKey(), fresh);
				touchedStates.add(fresh);
			}
		}
		if (ws.dragonFight != null && world.getEnderDragonFight() != null) {
			cloner.copyInto(ws.dragonFight, world.getEnderDragonFight());
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

	private void restoreMaxEntityId(MinecraftServer server) {
		// 保存後に作られて今も生きているエンティティ (リスポーンしたプレイヤーなど) の ID と重ならないときだけ戻す
		int maxLive = 0;
		for (ServerWorld world : server.getWorlds()) {
			for (Entity e : world.iterateEntities()) {
				if (e instanceof ServerPlayerEntity) {
					maxLive = Math.max(maxLive, e.getEntityId());
				}
			}
		}
		AtomicInteger counter = EntityAccessor.savestate$getMaxEntityId();
		if (maxLive <= this.maxEntityId) {
			counter.set(this.maxEntityId);
		} else {
			SavestateMod.LOGGER.warn("[memory] entity id counter not restored (player id {} > saved counter {})", maxLive, this.maxEntityId);
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
