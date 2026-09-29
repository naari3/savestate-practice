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
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.level.ServerWorldProperties;
import net.naari3.savestate.SavestateDebug;
import net.naari3.savestate.SavestateMod;
import net.naari3.savestate.mixin.accessor.EntityAccessor;
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

		// 全ワールドのエンティティとプレイヤーを 1 つの対応表で複製する (乗り物、ターゲット、釣り針など、互いの参照をそろえるため)
		DeepCloner cloner = new DeepCloner(policy());
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
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = this.worlds.get(world.getRegistryKey());
			// 1. チャンク (ブロック) を先に戻す。エンティティは戻したチャンクに入れる
			if (ws != null) {
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
		IdentityHashMap<Object, Entity> toFresh = new IdentityHashMap<>();
		for (Entity root : this.roots) {
			toFresh.put(root, cloner.mapRoot(root));
		}
		cloner.finish();

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
			// スナップショットにないもの (後から入ったプレイヤー) は末尾
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

		RngState.apply(server, this.rng);
		restoreMaxEntityId(server);
		ScheduledTickAccessor.savestate$setIdCounter(this.scheduledTickIdCounter);

		long ms = (System.nanoTime() - start) / 1_000_000L;
		SavestateMod.LOGGER.info("[memory] restored {} entities, {} chunks ({} missing, {} changed blocks) in {} ms",
			restored, chunkStats[0], chunkStats[1], chunkStats[2], ms);
		if (chunkStats[1] > 0) {
			SavestateMod.LOGGER.warn("[memory] {} chunks from the snapshot are not loaded now and were not restored", chunkStats[1]);
		}
		if (SavestateDebug.ENABLED) {
			logCounts("restore", cloner.getClonedCounts());
		}
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
