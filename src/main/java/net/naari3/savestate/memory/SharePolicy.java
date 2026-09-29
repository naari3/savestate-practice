package net.naari3.savestate.memory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerTickScheduler;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.state.State;
import net.minecraft.tag.Tag;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.IdList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.dynamic.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.village.raid.Raid;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkManager;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.storage.SerializingRegionBasedStorage;
import net.naari3.savestate.SavestateMod;

/**
 * 複製せずに参照をそのまま使う (共有する) オブジェクトの判定。
 *
 * 共有するもの:
 * - 不変の値 (String、ボックス型、enum、UUID、BlockPos (Mutable を除く)、Vec3d、Box、Identifier、BlockState など)
 * - レジストリに登録されたもの (Block、Item、EntityType、MemoryModuleType など)。起動後に全レジストリの全要素を集める
 * - 自分のクラス階層の static フィールドに入っている定数 (ItemStack.EMPTY、DamageSource.FALL など。== で比較するコードがある)
 * - インスタンスフィールドを 1 つも持たないもの (状態がないので共有して問題ない。匿名の TrackedDataHandler など)
 * - ワールド側の単一インスタンスや、今の段階では戻さないもの (World、サーバー、チャンク、プレイヤー、ネットワーク、Raid など)
 * - タグ (FluidTags.WATER などを、fastutil のマップのキーとして同一性で引く箇所がある)
 */
public final class SharePolicy {
	private static final Set<Class<?>> IMMUTABLE_EXACT = new HashSet<>();
	private static final String[] SHARED_PACKAGES = {
		"io.netty.", "org.apache.", "com.mojang.", "org.slf4j.", "org.lwjgl.", "com.google.gson.",
		"net.fabricmc.", "org.spongepowered.", "org.objectweb."
	};

	static {
		Collections.addAll(IMMUTABLE_EXACT,
			String.class, Boolean.class, Character.class, Byte.class, Short.class, Integer.class, Long.class,
			Float.class, Double.class, UUID.class, java.math.BigInteger.class, java.math.BigDecimal.class,
			BlockPos.class, Vec3i.class, Vec3d.class, Box.class, ChunkPos.class, ChunkSectionPos.class,
			Identifier.class, GlobalPos.class, EntityDimensions.class);
	}

	private final Set<Object> registryEntries = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<Class<?>, Set<Object>> staticConstants = new ConcurrentHashMap<>();
	private final boolean sharePlayers;
	private final boolean shareChunkData;

	/**
	 * @param sharePlayers   プレイヤーを複製せず共有する
	 * @param shareChunkData ChunkSection とブロックエンティティを複製せず共有する (エンティティの複製では共有、チャンクの複製では複製する)
	 */
	public SharePolicy(boolean sharePlayers, boolean shareChunkData) {
		this.sharePlayers = sharePlayers;
		this.shareChunkData = shareChunkData;
		for (Registry<?> registry : Registry.REGISTRIES) {
			this.registryEntries.add(registry);
			for (Object entry : registry) {
				this.registryEntries.add(entry);
			}
		}
		SavestateMod.LOGGER.info("[memory] share policy: {} registry entries", this.registryEntries.size());
	}

	public boolean isShared(Object o, Class<?> c, ClassInfo info) {
		if (IMMUTABLE_EXACT.contains(c) || o instanceof Enum || o instanceof Class || o instanceof ClassLoader || o instanceof Thread) {
			return true;
		}
		if (o instanceof State || o instanceof RegistryKey || o instanceof Text || o instanceof Tag
			|| o instanceof TrackedData || o instanceof TrackedDataHandler || o instanceof DimensionType) {
			return true;
		}
		if (o instanceof World || o instanceof MinecraftServer || o instanceof ChunkManager || o instanceof ThreadedAnvilChunkStorage
			|| o instanceof Chunk || o instanceof PlayerManager || o instanceof ServerPlayNetworkHandler
			|| o instanceof ClientConnection || o instanceof Scoreboard || o instanceof AbstractTeam || o instanceof BossBar
			|| o instanceof Raid || o instanceof PersistentState || o instanceof SerializingRegionBasedStorage
			|| o instanceof ServerTickScheduler || o instanceof Profiler || o instanceof IdList) {
			return true;
		}
		if (this.shareChunkData && (o instanceof ChunkSection || o instanceof BlockEntity)) {
			return true;
		}
		if (this.sharePlayers && o instanceof ServerPlayerEntity) {
			return true;
		}
		String name = c.getName();
		for (String p : SHARED_PACKAGES) {
			if (name.startsWith(p)) {
				return true;
			}
		}
		if (this.registryEntries.contains(o)) {
			return true;
		}
		if (info != null && info.hasNoInstanceState()) {
			return true;
		}
		return this.staticConstantsOf(c).contains(o);
	}

	/** c とその親クラス (JDK を除く) の static フィールドに入っているオブジェクト。 */
	private Set<Object> staticConstantsOf(Class<?> c) {
		Set<Object> set = this.staticConstants.get(c);
		if (set != null) {
			return set;
		}
		set = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Class<?> k = c; k != null && !ClassInfo.isJdk(k); k = k.getSuperclass()) {
			for (Field f : k.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers()) && !f.getType().isPrimitive()) {
					try {
						f.setAccessible(true);
						Object v = f.get(null);
						if (v != null) {
							set.add(v);
						}
					} catch (Throwable ignored) {
						// 読めない static は無視する
					}
				}
			}
		}
		this.staticConstants.put(c, set);
		return set;
	}
}
