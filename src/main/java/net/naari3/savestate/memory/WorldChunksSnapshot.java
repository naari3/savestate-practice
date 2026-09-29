package net.naari3.savestate.memory;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.world.BlockEvent;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerTickScheduler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Tickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.ScheduledTick;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.naari3.savestate.detcheck.ThreadedAnvilChunkStorageAccess;
import net.naari3.savestate.mixin.accessor.ServerTickSchedulerAccessor;
import net.naari3.savestate.mixin.accessor.ServerWorldAccessor;
import net.naari3.savestate.mixin.accessor.WorldChunkAccessor;

/**
 * 1 つのワールドの、読み込み済みチャンクの状態のスナップショット (段階 2)。
 *
 * - チャンクごと: ChunkSection (ブロックの状態)、ハイトマップ、ブロックエンティティ、inhabitedTime
 * - ワールドごと: ブロックエンティティのリストの並び (tick の順序)、スケジュール済みの tick (ブロック・流体)、ブロックイベントのキュー
 *
 * 復元では setBlockState を使わない (置き換え時の処理、例えばチェストの中身をばらまく処理が走ってしまうため)。
 * ChunkSection を差し替え、変わったブロックについてだけ光の再計算とクライアントへの更新を行う。
 * 保存時に読み込まれていなかったチャンク、復元時に読み込まれていないチャンクは扱わない (段階 3)。
 */
final class WorldChunksSnapshot {
	private final List<Long> chunkKeys = new ArrayList<>();
	private final List<Long> inhabitedTimes = new ArrayList<>();
	/** チャンクごとに {sections (ChunkSection[]), heightmaps (Map), blockEntities (Map)}。複製済み。 */
	private final List<Object[]> chunkData;
	private final List<BlockPos> blockEntityOrder = new ArrayList<>();
	private final List<BlockPos> tickingBlockEntityOrder = new ArrayList<>();
	private final List<ScheduledTick<Block>> blockTicks;
	private final List<ScheduledTick<Fluid>> fluidTicks;
	private final List<BlockEvent> blockEvents;

	private WorldChunksSnapshot(List<Object[]> chunkData, List<ScheduledTick<Block>> blockTicks, List<ScheduledTick<Fluid>> fluidTicks, List<BlockEvent> blockEvents) {
		this.chunkData = chunkData;
		this.blockTicks = blockTicks;
		this.fluidTicks = fluidTicks;
		this.blockEvents = blockEvents;
	}

	static WorldChunksSnapshot capture(ServerWorld world, SharePolicy chunkPolicy) {
		List<Object[]> roots = new ArrayList<>();
		List<Long> keys = new ArrayList<>();
		List<Long> inhabited = new ArrayList<>();
		for (ChunkHolder holder : ((ThreadedAnvilChunkStorageAccess) world.getChunkManager().threadedAnvilChunkStorage).savestate$chunkHolders()) {
			WorldChunk chunk = holder.getWorldChunk();
			if (chunk == null) {
				continue;
			}
			WorldChunkAccessor acc = (WorldChunkAccessor) chunk;
			roots.add(new Object[] { chunk.getSectionArray(), acc.savestate$getHeightmaps(), acc.savestate$getBlockEntities() });
			keys.add(chunk.getPos().toLong());
			inhabited.add(chunk.getInhabitedTime());
		}
		List<Object[]> clones = new DeepCloner(chunkPolicy).copyAll(roots);

		WorldChunksSnapshot snap = new WorldChunksSnapshot(clones,
			new ArrayList<>(scheduler(world.getBlockTickScheduler()).savestate$getScheduledTickActionsInOrder()),
			new ArrayList<>(scheduler(world.getFluidTickScheduler()).savestate$getScheduledTickActionsInOrder()),
			new ArrayList<>(((ServerWorldAccessor) world).savestate$getSyncedBlockEventQueue()));
		snap.chunkKeys.addAll(keys);
		snap.inhabitedTimes.addAll(inhabited);
		for (BlockEntity be : world.blockEntities) {
			snap.blockEntityOrder.add(be.getPos().toImmutable());
		}
		for (BlockEntity be : world.tickingBlockEntities) {
			snap.tickingBlockEntityOrder.add(be.getPos().toImmutable());
		}
		return snap;
	}

	int chunkCount() {
		return this.chunkKeys.size();
	}

	/** 戻したチャンクの数、見つからなかったチャンクの数、変わったブロックの数を返す。 */
	int[] restore(ServerWorld world, SharePolicy chunkPolicy) {
		List<Object[]> fresh = new DeepCloner(chunkPolicy).copyAll(this.chunkData);
		ServerChunkManager chunkManager = world.getChunkManager();
		ServerLightingProvider lighting = chunkManager.getLightingProvider();
		Set<BlockEntity> removed = Collections.newSetFromMap(new IdentityHashMap<>());
		Map<BlockPos, BlockEntity> restoredByPos = new HashMap<>();
		int restoredChunks = 0;
		int missing = 0;
		int changedBlocks = 0;

		for (int i = 0; i < this.chunkKeys.size(); i++) {
			ChunkPos pos = new ChunkPos(this.chunkKeys.get(i));
			WorldChunk chunk = (WorldChunk) world.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
			if (chunk == null) {
				missing++;
				continue;
			}
			WorldChunkAccessor acc = (WorldChunkAccessor) chunk;
			Object[] data = fresh.get(i);

			// 1. 今のブロックエンティティを外す (チャンクのマップとワールドのリストから)
			Map<BlockPos, BlockEntity> beMap = acc.savestate$getBlockEntities();
			for (BlockEntity be : beMap.values()) {
				be.markRemoved();
				removed.add(be);
			}
			beMap.clear();

			// 2. ChunkSection を差し替える。中身が変わったセクションだけ、変わったブロックを光とクライアントに知らせる
			ChunkSection[] live = chunk.getSectionArray();
			ChunkSection[] snapSections = (ChunkSection[]) data[0];
			for (int y = 0; y < live.length; y++) {
				ChunkSection before = live[y];
				ChunkSection after = snapSections[y];
				if (sameContent(before, after)) {
					continue;
				}
				live[y] = after;
				changedBlocks += notifyChanges(chunk, y, before, after, lighting, chunkManager);
				if (ChunkSection.isEmpty(before) != ChunkSection.isEmpty(after)) {
					lighting.updateSectionStatus(ChunkSectionPos.from(pos, y), ChunkSection.isEmpty(after));
				}
			}

			// 3. ハイトマップ
			@SuppressWarnings("unchecked")
			Map<Heightmap.Type, Heightmap> heightmaps = (Map<Heightmap.Type, Heightmap>) data[1];
			acc.savestate$getHeightmaps().clear();
			acc.savestate$getHeightmaps().putAll(heightmaps);

			// 4. ブロックエンティティを入れる
			@SuppressWarnings("unchecked")
			Map<BlockPos, BlockEntity> snapBes = (Map<BlockPos, BlockEntity>) data[2];
			for (Map.Entry<BlockPos, BlockEntity> e : snapBes.entrySet()) {
				BlockEntity be = e.getValue();
				be.setLocation(world, e.getKey());
				be.cancelRemoval();
				beMap.put(e.getKey(), be);
				restoredByPos.put(e.getKey(), be);
			}

			chunk.setInhabitedTime(this.inhabitedTimes.get(i));
			chunk.setShouldSave(true);
			restoredChunks++;
		}

		// 5. ワールドのブロックエンティティのリスト (tick の順序) を保存時の並びで作り直す
		rebuildList(world.blockEntities, this.blockEntityOrder, removed, restoredByPos, false);
		rebuildList(world.tickingBlockEntities, this.tickingBlockEntityOrder, removed, restoredByPos, true);

		// 6. スケジュール済みの tick、ブロックイベント
		restoreTicks(scheduler(world.getBlockTickScheduler()), this.blockTicks);
		restoreTicks(scheduler(world.getFluidTickScheduler()), this.fluidTicks);
		((ServerWorldAccessor) world).savestate$getSyncedBlockEventQueue().clear();
		((ServerWorldAccessor) world).savestate$getSyncedBlockEventQueue().addAll(this.blockEvents);

		return new int[] { restoredChunks, missing, changedBlocks };
	}

	private static void rebuildList(List<BlockEntity> list, List<BlockPos> order, Set<BlockEntity> removed, Map<BlockPos, BlockEntity> restoredByPos, boolean tickingOnly) {
		// 戻さなかったチャンクのブロックエンティティ (今も生きているもの) は位置で引けるようにしておく
		Map<BlockPos, BlockEntity> keep = new LinkedHashMap<>();
		for (BlockEntity be : list) {
			if (!removed.contains(be)) {
				keep.put(be.getPos(), be);
			}
		}
		List<BlockEntity> out = new ArrayList<>(list.size());
		Set<BlockEntity> added = Collections.newSetFromMap(new IdentityHashMap<>());
		for (BlockPos pos : order) {
			BlockEntity be = restoredByPos.get(pos);
			if (be == null) {
				be = keep.get(pos);
			}
			if (be != null && added.add(be)) {
				out.add(be);
			}
		}
		// 保存時のリストになかったもの (戻したチャンクで新しく増えたもの、戻さなかったチャンクのもの) は末尾に足す
		for (BlockEntity be : restoredByPos.values()) {
			if ((!tickingOnly || be instanceof Tickable) && added.add(be)) {
				out.add(be);
			}
		}
		for (BlockEntity be : keep.values()) {
			if (added.add(be)) {
				out.add(be);
			}
		}
		list.clear();
		list.addAll(out);
	}

	private static <T> void restoreTicks(ServerTickSchedulerAccessor<T> scheduler, List<ScheduledTick<T>> ticks) {
		scheduler.savestate$getScheduledTickActions().clear();
		scheduler.savestate$getScheduledTickActionsInOrder().clear();
		scheduler.savestate$getScheduledTickActions().addAll(ticks);
		scheduler.savestate$getScheduledTickActionsInOrder().addAll(ticks);
	}

	@SuppressWarnings("unchecked")
	private static <T> ServerTickSchedulerAccessor<T> scheduler(ServerTickScheduler<T> scheduler) {
		return (ServerTickSchedulerAccessor<T>) scheduler;
	}

	private static final PacketByteBuf BUF_A = new PacketByteBuf(Unpooled.buffer());
	private static final PacketByteBuf BUF_B = new PacketByteBuf(Unpooled.buffer());

	/** 2 つの ChunkSection のブロックの中身が同じか (クライアント送信用の形式に書き出して比べる)。 */
	private static boolean sameContent(ChunkSection a, ChunkSection b) {
		boolean emptyA = ChunkSection.isEmpty(a);
		boolean emptyB = ChunkSection.isEmpty(b);
		if (emptyA || emptyB) {
			return emptyA == emptyB;
		}
		BUF_A.clear();
		BUF_B.clear();
		a.toPacket(BUF_A);
		b.toPacket(BUF_B);
		return BUF_A.equals(BUF_B);
	}

	/** 変わったブロックごとに、光の再計算とクライアントへの送信を予約する。変わったブロックの数を返す。 */
	private static int notifyChanges(WorldChunk chunk, int sectionY, ChunkSection before, ChunkSection after, ServerLightingProvider lighting, ServerChunkManager chunkManager) {
		int changed = 0;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int baseX = chunk.getPos().getStartX();
		int baseY = sectionY << 4;
		int baseZ = chunk.getPos().getStartZ();
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState s1 = before == null ? Blocks.AIR.getDefaultState() : before.getBlockState(x, y, z);
					BlockState s2 = after == null ? Blocks.AIR.getDefaultState() : after.getBlockState(x, y, z);
					if (s1 != s2) {
						pos.set(baseX + x, baseY + y, baseZ + z);
						BlockPos immutable = pos.toImmutable();
						lighting.checkBlock(immutable);
						chunkManager.markForUpdate(immutable);
						changed++;
					}
				}
			}
		}
		return changed;
	}
}
