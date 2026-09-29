package net.naari3.savestate.rng;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.WanderingTraderManager;
import net.minecraft.world.gen.Spawner;
import net.naari3.savestate.SavestateDebug;
import net.naari3.savestate.SavestateMod;
import net.naari3.savestate.detcheck.DetCheck;
import net.naari3.savestate.mixin.accessor.MinecraftServerAccessor;
import net.naari3.savestate.mixin.accessor.ServerWorldAccessor;
import net.naari3.savestate.mixin.accessor.WanderingTraderManagerAccessor;
import net.naari3.savestate.mixin.accessor.WorldAccessor;

/**
 * サーバー側の RNG 状態をまとめて保存・復元する。
 *
 * 対象:
 * - MASTER: シードなしの new Random() (Mixin で StatefulRandom に置き換えたもの) がサーバースレッドで初めて使われたときのシード元
 * - MATH: サーバースレッドでの Math.random() の置き換え先
 * - SHUFFLE: ServerChunkManager.tickChunks の Collections.shuffle(list) の置き換え先
 * - static な Random (Sensor.RANDOM など。{@link #registerStatic} で登録)
 * - MinecraftServer.random
 * - ワールドごと: World.random、World.lcgBlockSeed、WanderingTraderManager.random
 *
 * エンティティごとの Random はエンティティの NBT に、Raid の Random は Raid の NBT に入れる (別の Mixin)。
 */
public final class RngState {
	private static final StatefulRandom MASTER = new StatefulRandom(new Random().nextLong());
	private static final StatefulRandom MATH = new StatefulRandom(new Random().nextLong());
	private static final StatefulRandom SHUFFLE = new StatefulRandom(new Random().nextLong());
	private static final Map<String, StatefulRandom> STATICS = new ConcurrentHashMap<>();

	private static volatile Thread serverThread;
	private static volatile CompoundTag pending;

	private RngState() {
	}

	public static void setServerThread(Thread thread) {
		serverThread = thread;
	}

	public static boolean isServerThread() {
		return Thread.currentThread() == serverThread;
	}

	static long freshSeed() {
		if (isServerThread()) {
			return MASTER.nextLong();
		}
		return new Random().nextLong();
	}

	public static StatefulRandom registerStatic(String name, StatefulRandom random) {
		// static な Random はクライアントスレッドやワーカースレッドからも使われるので、サーバースレッドの分だけを保存対象にする
		STATICS.put(name, random.serverOnly());
		return random;
	}

	public static double mathRandom() {
		return isServerThread() ? MATH.nextDouble() : Math.random();
	}

	public static void shuffle(List<?> list) {
		if (isServerThread()) {
			Collections.shuffle(list, SHUFFLE);
		} else {
			Collections.shuffle(list);
		}
	}

	/** サーバースレッドで呼ぶ。 */
	public static CompoundTag capture(MinecraftServer server) {
		CompoundTag root = new CompoundTag();
		root.put("master", MASTER.toTag());
		root.put("math", MATH.toTag());
		root.put("shuffle", SHUFFLE.toTag());

		CompoundTag statics = new CompoundTag();
		for (Map.Entry<String, StatefulRandom> e : STATICS.entrySet()) {
			statics.put(e.getKey(), e.getValue().toTag());
		}
		root.put("statics", statics);

		put(root, "server", ((MinecraftServerAccessor) server).savestate$getRandom());

		CompoundTag worlds = new CompoundTag();
		for (ServerWorld world : server.getWorlds()) {
			CompoundTag w = new CompoundTag();
			put(w, "random", world.random);
			w.putInt("lcgBlockSeed", ((WorldAccessor) world).savestate$getLcgBlockSeed());
			WanderingTraderManager trader = findTraderManager(world);
			if (trader != null) {
				put(w, "wanderingTrader", ((WanderingTraderManagerAccessor) trader).savestate$getRandom());
			}
			worlds.put(world.getRegistryKey().getValue().toString(), w);
		}
		root.put("worlds", worlds);
		return root;
	}

	/** サーバースレッドで呼ぶ。 */
	public static void apply(MinecraftServer server, CompoundTag root) {
		MASTER.fromTag(root.getCompound("master"));
		MATH.fromTag(root.getCompound("math"));
		SHUFFLE.fromTag(root.getCompound("shuffle"));

		CompoundTag statics = root.getCompound("statics");
		for (Map.Entry<String, StatefulRandom> e : STATICS.entrySet()) {
			// save の時点でまだクラスが読み込まれていなかった static は保存ファイルにない。
			// その場合は未シードに戻し、最初に使われた時点で (復元済みの) 親 RNG からシードを取らせる
			e.getValue().fromTag(statics.getCompound(e.getKey()));
		}

		get(root, "server", ((MinecraftServerAccessor) server).savestate$getRandom());

		CompoundTag worlds = root.getCompound("worlds");
		for (ServerWorld world : server.getWorlds()) {
			String key = world.getRegistryKey().getValue().toString();
			if (!worlds.contains(key)) {
				continue;
			}
			CompoundTag w = worlds.getCompound(key);
			get(w, "random", world.random);
			((WorldAccessor) world).savestate$setLcgBlockSeed(w.getInt("lcgBlockSeed"));
			WanderingTraderManager trader = findTraderManager(world);
			if (trader != null) {
				get(w, "wanderingTrader", ((WanderingTraderManagerAccessor) trader).savestate$getRandom());
			}
		}
	}

	/**
	 * loadstate で開き直したワールドに、プレイヤーが入った時点で適用する状態を予約する。
	 * 予約中はワールドの tick を止める ({@link #isHoldingWorldTicks()})。
	 * プレイヤーが入るまでの間にエンティティが tick すると、未復元の static な Random や親 RNG を消費してしまう。
	 * さらに、何 tick 進むかは読み込みの速さ次第で変わるので、load のたびに状態がずれる。
	 */
	public static void setPending(CompoundTag tag) {
		pending = tag;
	}

	/** 決定論の検査用。サーバー全体で共有する RNG の状態を (消費せずに) 書き出す。 */
	public static void describeInto(Map<String, String> out) {
		out.put("rng master", MASTER.describe());
		out.put("rng math", MATH.describe());
		out.put("rng shuffle", SHUFFLE.describe());
		for (Map.Entry<String, StatefulRandom> e : STATICS.entrySet()) {
			out.put("rng static " + e.getKey(), e.getValue().describe());
		}
	}

	public static boolean isHoldingWorldTicks() {
		return pending != null;
	}

	/** サーバー停止時に呼ぶ。予約が残ったまま別のワールドを開いて tick が止まり続けるのを防ぐ。 */
	public static void clearPending() {
		pending = null;
	}

	/** サーバースレッドで呼ぶ。予約があれば適用し、ワールドの tick を再開させる。 */
	public static void applyPending(MinecraftServer server) {
		CompoundTag tag = pending;
		pending = null;
		if (tag != null) {
			apply(server, tag);
			SavestateMod.LOGGER.info("Restored RNG state");
			DetCheck.onResume();
			SavestateDebug.log("applied master={} math={} shuffle={} statics={} overworldTime={} overworldRand={}",
				MASTER.describe(), MATH.describe(), SHUFFLE.describe(), STATICS.keySet(),
				server.getOverworld().getTime(), SavestateDebug.rand(server.getOverworld().random));
		}
	}

	private static WanderingTraderManager findTraderManager(ServerWorld world) {
		for (Spawner spawner : ((ServerWorldAccessor) world).savestate$getSpawners()) {
			if (spawner instanceof WanderingTraderManager) {
				return (WanderingTraderManager) spawner;
			}
		}
		return null;
	}

	private static void put(CompoundTag tag, String key, Random random) {
		if (random instanceof StatefulRandom) {
			tag.put(key, ((StatefulRandom) random).toTag());
		} else {
			SavestateMod.LOGGER.warn("RNG '{}' is not a StatefulRandom ({}); it will not be saved", key, random.getClass().getName());
		}
	}

	private static void get(CompoundTag tag, String key, Random random) {
		if (random instanceof StatefulRandom && tag.contains(key)) {
			((StatefulRandom) random).fromTag(tag.getCompound(key));
		}
	}
}
