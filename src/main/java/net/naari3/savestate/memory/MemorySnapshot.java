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

	private final Map<RegistryKey<World>, WorldSnap> worlds = new LinkedHashMap<>();
	private final List<Entity> roots = new ArrayList<>();
	private final CompoundTag rng;
	private final int maxEntityId;

	private static final class WorldSnap {
		/** entitiesById の並び。要素は複製したエンティティ、またはプレイヤーの UUID。 */
		final List<Object> order = new ArrayList<>();
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

	private MemorySnapshot(CompoundTag rng, int maxEntityId) {
		this.rng = rng;
		this.maxEntityId = maxEntityId;
	}

	private static synchronized SharePolicy policy() {
		if (policy == null) {
			policy = new SharePolicy(true);
		}
		return policy;
	}

	public static MemorySnapshot capture(MinecraftServer server) {
		long start = System.nanoTime();
		MemorySnapshot snap = new MemorySnapshot(RngState.capture(server), EntityAccessor.savestate$getMaxEntityId().get());
		List<Entity> originals = new ArrayList<>();
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = new WorldSnap();
			for (Entity e : ((ServerWorldAccessor) world).savestate$getEntitiesById().values()) {
				if (e instanceof ServerPlayerEntity) {
					ws.order.add(e.getUuid());
				} else if (!(e instanceof EnderDragonPart)) {
					ws.order.add(e);
					originals.add(e);
				}
			}
			captureWorldState(world, ws);
			snap.worlds.put(world.getRegistryKey(), ws);
		}

		// 全ワールドを 1 つの対応表で複製する (ワールドをまたぐ参照もそろえるため)
		DeepCloner cloner = new DeepCloner(policy());
		List<Entity> clones = cloner.copyAll(originals);
		IdentityHashMap<Object, Object> toClone = new IdentityHashMap<>();
		for (int i = 0; i < originals.size(); i++) {
			toClone.put(originals.get(i), clones.get(i));
		}
		for (WorldSnap ws : snap.worlds.values()) {
			ws.order.replaceAll(o -> o instanceof Entity ? toClone.get(o) : o);
		}
		snap.roots.addAll(clones);

		long ms = (System.nanoTime() - start) / 1_000_000L;
		SavestateMod.LOGGER.info("[memory] captured {} entities in {} ms", clones.size(), ms);
		if (SavestateDebug.ENABLED) {
			logCounts("capture", cloner.getClonedCounts());
		}
		return snap;
	}

	public void restore(MinecraftServer server) {
		long start = System.nanoTime();
		// スナップショットはそのまま残し、そのまた複製を生きたワールドに入れる
		DeepCloner cloner = new DeepCloner(policy());
		List<Entity> fresh = cloner.copyAll(this.roots);
		IdentityHashMap<Object, Entity> toFresh = new IdentityHashMap<>();
		for (int i = 0; i < this.roots.size(); i++) {
			toFresh.put(this.roots.get(i), fresh.get(i));
		}

		int restored = 0;
		for (ServerWorld world : server.getWorlds()) {
			WorldSnap ws = this.worlds.get(world.getRegistryKey());
			Int2ObjectMap<Entity> byId = ((ServerWorldAccessor) world).savestate$getEntitiesById();

			// 1. 今のエンティティ (プレイヤー以外) を外す
			for (Entity e : new ArrayList<>(byId.values())) {
				if (!(e instanceof ServerPlayerEntity) && !(e instanceof EnderDragonPart)) {
					world.removeEntity(e);
				}
			}
			byId.values().removeIf(e -> e instanceof EnderDragonPart);
			if (ws == null) {
				continue;
			}

			// 2. 複製を入れる
			Map<Integer, Entity> desired = new LinkedHashMap<>();
			for (Object o : ws.order) {
				if (o instanceof UUID) {
					Entity player = world.getEntity((UUID) o);
					if (player != null) {
						desired.put(player.getEntityId(), player);
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

			// 3. entitiesById の並び (tick の順序) を保存時と同じにする。スナップショットにないもの (後から入ったプレイヤー) は末尾
			for (Int2ObjectMap.Entry<Entity> en : byId.int2ObjectEntrySet()) {
				desired.putIfAbsent(en.getIntKey(), en.getValue());
			}
			byId.clear();
			for (Map.Entry<Integer, Entity> en : desired.entrySet()) {
				byId.put((int) en.getKey(), en.getValue());
			}

			restoreWorldState(world, ws);
		}

		RngState.apply(server, this.rng);
		restoreMaxEntityId(server);

		long ms = (System.nanoTime() - start) / 1_000_000L;
		SavestateMod.LOGGER.info("[memory] restored {} entities in {} ms", restored, ms);
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
