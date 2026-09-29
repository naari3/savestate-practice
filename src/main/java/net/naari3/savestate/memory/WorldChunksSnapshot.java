package net.naari3.savestate.memory;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
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
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.world.BlockEvent;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerTickScheduler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Tickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.ScheduledTick;
import net.minecraft.world.TickPriority;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.naari3.savestate.SavestateDebug;
import net.naari3.savestate.SavestateMod;
import net.naari3.savestate.detcheck.ThreadedAnvilChunkStorageAccess;
import net.naari3.savestate.mixin.accessor.ChunkTicketManagerInvoker;
import net.naari3.savestate.mixin.accessor.SerializingRegionBasedStorageAccessor;
import net.naari3.savestate.mixin.accessor.ServerChunkManagerInvoker;
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
	/** 取得時に tick 対象 (ticking) だったチャンク。 */
	private Set<Long> tickingKeys;
	/** POI のセクションごとの中身 (複製済み)。 */
	private Map<Long, Optional<?>> poi;
	/** 取得後の、読み込み済みチャンク以外への変化の記録。 */
	private ChunkJournal journal;

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
		Set<Long> ticking = new HashSet<>();
		for (ChunkHolder holder : ((ThreadedAnvilChunkStorageAccess) world.getChunkManager().threadedAnvilChunkStorage).savestate$chunkHolders()) {
			// getWorldChunk() は tick 対象 (ticking) のチャンクしか返さない。読み込まれているが tick されない境界のチャンク (レベル 33) も含めて保存する
			WorldChunk chunk = fullChunk(holder);
			if (chunk == null) {
				continue;
			}
			if (holder.getWorldChunk() != null) {
				ticking.add(chunk.getPos().toLong());
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
		snap.tickingKeys = ticking;

		SerializingRegionBasedStorageAccessor poiAcc = poiAccessor(world);
		snap.poi = new DeepCloner(chunkPolicy).copy(new LinkedHashMap<Long, Optional<?>>(poiAcc.savestate$getLoadedElements()));
		snap.journal = new ChunkJournal(world, new HashSet<>(keys), poiAcc.savestate$getWorker());
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

	ChunkJournal journal() {
		return this.journal;
	}

	/**
	 * 読み込み済みのチャンクの集合を、取得時と同じにそろえる (ワールドは進めない)。
	 * チケットの期限切れの処理、チケットの反映、読み込みを外す処理、保留中のタスクの実行を、
	 * 集合が取得時と同じになるか、変化しなくなるまで繰り返す。
	 * 取得後に読み込まれたチャンクは、中身を取得時点相当に戻してあるので、外すときにその内容でディスクに保存される。
	 * 戻り値は {繰り返した回数, そろえた後の読み込み済みチャンク数, 取得時にあって今ないチャンク数, 取得時になくて今あるチャンク数}。
	 */
	/** 復元中だけ取得時のチャンクを保持するチケット (期限なし)。 */
	private static final ChunkTicketType<ChunkPos> RESTORE_TICKET = ChunkTicketType.create("mcsr_savestate_restore", Comparator.comparingLong(ChunkPos::toLong));
	private static final int WAIT_STEP_MS = 10;
	private static final int MAX_WAIT_MS = 10000;
	/** 変化がないまま、この回数繰り返したらあきらめる (プレイヤーのチケットやチャンクの読み込みは非同期に進むので長めにとる)。 */
	private static final int STABLE_ITERATIONS = 300;

	int[] convergeLoadedSet(ServerWorld world) {
		ServerChunkManager chunkManager = world.getChunkManager();
		ThreadedAnvilChunkStorageAccess tacs = (ThreadedAnvilChunkStorageAccess) chunkManager.threadedAnvilChunkStorage;
		Set<Long> wanted = this.journal.snapshotLoaded;
		int iterations = 0;
		int stable = 0;
		int lastCount = -1;
		int[] diff = new int[3];
		// そろえる間は、取得時のチャンクに期限なしのチケット (FULL レベル) を付けて、読み込みが外れないようにする。
		// プレイヤーのチケットが非同期に届くまでの間にレベルが下がって外れると、中のエンティティが NBT から作り直され、
		// 実行時の状態 (AI など) が失われるため
		for (long k : wanted) {
			ChunkPos pos = new ChunkPos(k);
			chunkManager.addTicket(RESTORE_TICKET, pos, 0, pos);
		}
		// プレイヤーのチケットの追加・削除は別スレッドのキュー (playerTicketThrottler) を経由して非同期に届き、
		// チャンクの読み込みもワーカースレッドで進むので、少しずつ待ちながら繰り返す (最大 MAX_WAIT_MS)
		for (; iterations < MAX_WAIT_MS / WAIT_STEP_MS; iterations++) {
			// 期限付きのチケット (getChunk で付く unknown など、期限 1 tick) の期限切れを進める。
			// 取得時のチャンクは上の RESTORE_TICKET で保持しているので、ここで一時チケットが外れても読み込みは外れない
			((ChunkTicketManagerInvoker) ((ServerChunkManagerInvoker) chunkManager).savestate$getTicketManager()).savestate$purge();
			((ServerChunkManagerInvoker) chunkManager).savestate$updateTickets();
			tacs.savestate$unloadTick();
			// executeQueuedTasks は 1 回で 1 つしか実行しないので、キューが空になるまで回す
			// (プレイヤーのチケットの付け外しは、1 チャンクごとのタスクとしてここに積まれる)
			while (chunkManager.executeQueuedTasks()) {
			}
			((ServerChunkManagerInvoker) chunkManager).savestate$updateTickets();
			diff = loadedDiff(tacs, wanted);
			int[] tickingDiff = tickingDiff(tacs, this.tickingKeys);
			// 集合がそろっても、読み込みを外す処理 (エンティティの取り外しと保存) はタスクとして後から走るので、それも終わるまで待つ
			if (diff[1] == 0 && diff[2] == 0 && tickingDiff[0] == 0 && tickingDiff[1] == 0 && !tacs.savestate$unloadPending()) {
				break;
			}
			if (diff[0] == lastCount) {
				if (++stable >= STABLE_ITERATIONS) {
					break;
				}
			} else {
				stable = 0;
				lastCount = diff[0];
			}
			try {
				Thread.sleep(WAIT_STEP_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		for (long k : wanted) {
			ChunkPos pos = new ChunkPos(k);
			chunkManager.removeTicket(RESTORE_TICKET, pos, 0, pos);
		}
		((ServerChunkManagerInvoker) chunkManager).savestate$updateTickets();
		if (SavestateDebug.ENABLED && (diff[1] != 0 || diff[2] != 0)) {
			this.logTicketSamples(world, tacs, wanted);
		}
		int[] td = tickingDiff(tacs, this.tickingKeys);
		if (td[0] != 0 || td[1] != 0) {
			SavestateMod.LOGGER.warn("[memory] {}: ticking chunk set differs from the snapshot ({} missing, {} extra)",
				world.getRegistryKey().getValue(), td[0], td[1]);
		}
		return new int[] { iterations, diff[0], diff[1], diff[2] };
	}

	/** 調査用: 取得時とそろわなかったチャンクの読み込みレベルとチケットを、いくつか出す。 */
	private void logTicketSamples(ServerWorld world, ThreadedAnvilChunkStorageAccess tacs, Set<Long> wanted) {
		ChunkTicketManagerInvoker tickets = (ChunkTicketManagerInvoker) ((ServerChunkManagerInvoker) world.getChunkManager()).savestate$getTicketManager();
		int extraShown = 0;
		for (ChunkHolder holder : tacs.savestate$chunkHolders()) {
			long k = holder.getPos().toLong();
			if (fullChunk(holder) != null && !wanted.contains(k) && extraShown++ < 3) {
				SavestateDebug.log("extra chunk {} level={} tickets={}", holder.getPos(), holder.getLevel(), tickets.savestate$getTicket(k));
			}
		}
		int missingShown = 0;
		for (long k : wanted) {
			ChunkPos pos = new ChunkPos(k);
			if (world.getChunk(pos.x, pos.z, ChunkStatus.FULL, false) == null && missingShown++ < 3) {
				SavestateDebug.log("missing chunk {} tickets={}", pos, tickets.savestate$getTicket(k));
			}
		}
		for (net.minecraft.server.network.ServerPlayerEntity p : world.getPlayers()) {
			SavestateDebug.log("player {} at {} camera={}", p.getEntityName(), p.getBlockPos(), p.getCameraPosition());
		}
	}

	/** チケットの変化を反映し、読み込みを外す処理が残らなくなるまで回す (最大 MAX_WAIT_MS)。 */
	static void flushUnloads(ServerWorld world) {
		ServerChunkManager chunkManager = world.getChunkManager();
		ThreadedAnvilChunkStorageAccess tacs = (ThreadedAnvilChunkStorageAccess) chunkManager.threadedAnvilChunkStorage;
		for (int i = 0; i < MAX_WAIT_MS / WAIT_STEP_MS; i++) {
			((ServerChunkManagerInvoker) chunkManager).savestate$updateTickets();
			tacs.savestate$unloadTick();
			while (chunkManager.executeQueuedTasks()) {
			}
			((ServerChunkManagerInvoker) chunkManager).savestate$updateTickets();
			if (!tacs.savestate$unloadPending()) {
				return;
			}
			try {
				Thread.sleep(WAIT_STEP_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	/** 読み込み済み (FULL。tick されない境界のチャンクも含む) の WorldChunk。 */
	static WorldChunk fullChunk(ChunkHolder holder) {
		com.mojang.datafixers.util.Either<WorldChunk, ChunkHolder.Unloaded> e = holder.getBorderFuture().getNow(null);
		return e == null ? null : e.left().orElse(null);
	}

	/** {wanted にあって tick 対象でない数, wanted になくて tick 対象の数}。 */
	private static int[] tickingDiff(ThreadedAnvilChunkStorageAccess tacs, Set<Long> wanted) {
		Set<Long> ticking = new HashSet<>();
		for (ChunkHolder holder : tacs.savestate$chunkHolders()) {
			if (holder.getWorldChunk() != null) {
				ticking.add(holder.getPos().toLong());
			}
		}
		int missing = 0;
		for (long k : wanted) {
			if (!ticking.contains(k)) {
				missing++;
			}
		}
		int extra = 0;
		for (long k : ticking) {
			if (!wanted.contains(k)) {
				extra++;
			}
		}
		return new int[] { missing, extra };
	}

	/** {読み込み済みチャンク数, wanted にあって読み込まれていない数, wanted になくて読み込まれている数}。読み込み済み = FULL (境界を含む)。 */
	private static int[] loadedDiff(ThreadedAnvilChunkStorageAccess tacs, Set<Long> wanted) {
		Set<Long> loaded = new HashSet<>();
		for (ChunkHolder holder : tacs.savestate$chunkHolders()) {
			if (fullChunk(holder) != null) {
				loaded.add(holder.getPos().toLong());
			}
		}
		int missing = 0;
		for (long k : wanted) {
			if (!loaded.contains(k)) {
				missing++;
			}
		}
		int extra = 0;
		for (long k : loaded) {
			if (!wanted.contains(k)) {
				extra++;
			}
		}
		return new int[] { loaded.size(), missing, extra };
	}

	/** 調査用: スナップショットの予約された tick のうち、今のスケジューラーにないものの位置を出す。 */
	void logMissingTicks(ServerWorld world, String when) {
		java.util.Set<ScheduledTick<Block>> live = scheduler(world.getBlockTickScheduler()).savestate$getScheduledTickActions();
		int missing = 0;
		Map<Long, Integer> byChunk = new java.util.TreeMap<>();
		for (ScheduledTick<Block> t : this.blockTicks) {
			if (!live.contains(t)) {
				missing++;
				byChunk.merge(ChunkPos.toLong(t.pos.getX() >> 4, t.pos.getZ() >> 4), 1, Integer::sum);
			}
		}
		if (missing > 0) {
			StringBuilder sb = new StringBuilder();
			int shown = 0;
			for (Map.Entry<Long, Integer> e : byChunk.entrySet()) {
				if (shown++ >= 8) {
					break;
				}
				ChunkPos p = new ChunkPos(e.getKey());
				boolean loaded = world.getChunk(p.x, p.z, ChunkStatus.FULL, false) != null;
				sb.append(' ').append(p).append('x').append(e.getValue()).append(loaded ? "(loaded" : "(unloaded")
					.append(this.journal.snapshotLoaded.contains(e.getKey()) ? ",snap" : ",notsnap")
					.append(this.journal.loadedAfter.containsKey(e.getKey()) ? ",journal)" : ")");
			}
			SavestateDebug.log("{} {}: {} of {} snapshot block ticks missing; chunks:{}", when, world.getRegistryKey().getValue(), missing, this.blockTicks.size(), sb);
		}
	}

	void activateJournal() {
		this.journal.activate();
	}

	void dispose() {
		this.journal.deactivate();
	}

	/**
	 * 読み込み済みチャンク以外の復元 ({@link #restore} の前に呼ぶ)。
	 * - 取得時に読み込まれていて今は読み込まれていないチャンクを、同期で読み込む ({@link #restore} で戻せるように)
	 * - 取得後に読み込まれたチャンク: 今も読み込まれていれば記録した NBT をその場で当て、読み込まれていなければ (保存されていれば) ディスクに書き戻す
	 * - POI をメモリとディスクの両方で戻す
	 * 記録した NBT から作るエンティティの NBT を entityTags に足す (エンティティの入れ替えの後で作る)。
	 * 戻り値は {同期で読み込んだチャンク数, その場で当てたチャンク数, ディスクに書き戻したチャンク数}。
	 */
	int[] restoreOutside(ServerWorld world, SharePolicy chunkPolicy, List<CompoundTag> entityTags) {
		ServerChunkManager chunkManager = world.getChunkManager();
		// 途中まで進んでいる「読み込みを外す処理」を先に終わらせ、各チャンクを「読み込まれている」か「外れて保存済み」のどちらかにする。
		// 外す途中のチャンクも getChunk(..., false) では取れてしまい、そこへ記録の NBT やエンティティを入れると、
		// 直後の取り外しで内容が失われたり、どのチャンクにも属さないエンティティが残ったりするため
		flushUnloads(world);
		int syncLoaded = 0;
		for (long key : this.chunkKeys) {
			ChunkPos pos = new ChunkPos(key);
			if (world.getChunk(pos.x, pos.z, ChunkStatus.FULL, false) == null) {
				chunkManager.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
				syncLoaded++;
			}
		}

		// 取得後に読み込まれたチャンク (loadedAfter) は、ここでは触らない。読み込み済みのチャンクの集合をそろえる段階ですべて外れるので、
		// 外れた後に記録した NBT をディスクへ書き戻す (rewriteJournalChunks)。
		// その場に当ててエンティティを入れる方式は、直後の取り外しとの順序の問題で、どのチャンクにも属さないエンティティが残った

		this.restorePoi(world, chunkPolicy);
		return new int[] { syncLoaded, 0, 0 };
	}

	/**
	 * 読み込み済みのチャンクの集合をそろえた後 ({@link #convergeLoadedSet} の後) に呼ぶ。
	 * 取得後に読み込まれたチャンクのうち、外れたものは記録した NBT をディスクに書き戻す (外すときに保存された取得後の内容を上書きする)。
	 * 外れずに残ったもの (本来は起きない) は、NBT をその場で当て、エンティティの NBT を entityTags に足す。
	 * 戻り値は {書き戻した数, その場で当てた数}。
	 */
	int[] rewriteJournalChunks(ServerWorld world, List<CompoundTag> entityTags) {
		ServerChunkManager chunkManager = world.getChunkManager();
		int rewritten = 0;
		int applied = 0;
		for (Map.Entry<Long, CompoundTag> e : this.journal.loadedAfter.entrySet()) {
			ChunkPos pos = new ChunkPos(e.getKey());
			WorldChunk chunk = (WorldChunk) world.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
			if (chunk == null) {
				chunkManager.threadedAnvilChunkStorage.setTagAt(pos, e.getValue());
				rewritten++;
			} else {
				entityTags.addAll(applyChunkNbt(world, chunk, e.getValue()));
				applied++;
			}
		}
		return new int[] { rewritten, applied };
	}

	private void restorePoi(ServerWorld world, SharePolicy chunkPolicy) {
		SerializingRegionBasedStorageAccessor acc = poiAccessor(world);
		Map<Long, Optional<?>> fresh = new DeepCloner(chunkPolicy).copy(this.poi);
		// 取得後に読み込まれたセクション (取得時になかったもの) はメモリから外す。次に使われるときにディスクから読み直される
		acc.savestate$getLoadedElements().clear();
		acc.savestate$getUnsavedElements().clear();
		for (Map.Entry<Long, Optional<?>> e : fresh.entrySet()) {
			long key = e.getKey();
			acc.savestate$getLoadedElements().put(key, e.getValue());
			// ディスクの内容が取得後に変わっているかもしれないので、戻した内容で書き直させる
			acc.savestate$getUnsavedElements().add(key);
		}
		// 取得後に書き換えられたディスク上の POI を、書き換える前の内容に戻す
		for (Map.Entry<Long, CompoundTag> e : this.journal.poiBefore.entrySet()) {
			CompoundTag tag = e.getValue();
			if (tag == null) {
				tag = new CompoundTag();
				tag.put("Sections", new CompoundTag());
				tag.putInt("DataVersion", SharedConstants.getGameVersion().getWorldVersion());
			}
			acc.savestate$getWorker().setResult(new ChunkPos(e.getKey()), tag);
		}
	}

	/** 記録したチャンクの NBT を、読み込まれている WorldChunk にその場で当てる。エンティティの NBT を返す。 */
	private static List<CompoundTag> applyChunkNbt(ServerWorld world, WorldChunk chunk, CompoundTag root) {
		CompoundTag level = root.getCompound("Level");
		ChunkPos pos = chunk.getPos();
		ServerChunkManager chunkManager = world.getChunkManager();
		ServerLightingProvider lighting = chunkManager.getLightingProvider();

		// ブロック
		ChunkSection[] fresh = new ChunkSection[16];
		ListTag sections = level.getList("Sections", 10);
		for (int i = 0; i < sections.size(); i++) {
			CompoundTag st = sections.getCompound(i);
			int y = st.getByte("Y");
			if (y < 0 || y >= 16 || !st.contains("Palette", 9) || !st.contains("BlockStates", 12)) {
				continue;
			}
			ChunkSection section = new ChunkSection(y << 4);
			section.getContainer().read(st.getList("Palette", 10), st.getLongArray("BlockStates"));
			section.calculateCounts();
			if (!section.isEmpty()) {
				fresh[y] = section;
			}
		}
		ChunkSection[] live = chunk.getSectionArray();
		for (int y = 0; y < 16; y++) {
			if (sameContent(live[y], fresh[y])) {
				continue;
			}
			ChunkSection before = live[y];
			live[y] = fresh[y];
			notifyChanges(chunk, y, before, fresh[y], lighting, chunkManager);
			if (ChunkSection.isEmpty(before) != ChunkSection.isEmpty(fresh[y])) {
				lighting.updateSectionStatus(ChunkSectionPos.from(pos, y), ChunkSection.isEmpty(fresh[y]));
			}
		}
		WorldChunkAccessor acc = (WorldChunkAccessor) chunk;
		Heightmap.populateHeightmaps(chunk, EnumSet.copyOf(acc.savestate$getHeightmaps().keySet()));

		// ブロックエンティティ
		Map<BlockPos, BlockEntity> beMap = acc.savestate$getBlockEntities();
		for (BlockEntity be : beMap.values()) {
			be.markRemoved();
			world.blockEntities.remove(be);
			world.tickingBlockEntities.remove(be);
		}
		beMap.clear();
		ListTag bes = level.getList("TileEntities", 10);
		for (int i = 0; i < bes.size(); i++) {
			CompoundTag bt = bes.getCompound(i);
			BlockPos bp = new BlockPos(bt.getInt("x"), bt.getInt("y"), bt.getInt("z"));
			BlockEntity be = BlockEntity.createFromTag(chunk.getBlockState(bp), bt);
			if (be != null) {
				be.setLocation(world, bp);
				beMap.put(bp, be);
				world.addBlockEntity(be);
			}
		}

		// スケジュール済みの tick: このチャンクの分を外して、記録したものを入れ直す (時刻は記録時からの相対)
		world.getBlockTickScheduler().getScheduledTicksInChunk(pos, true, false);
		world.getFluidTickScheduler().getScheduledTicksInChunk(pos, true, false);
		scheduleFromNbt(level.getList("TileTicks", 10), world.getBlockTickScheduler(), Registry.BLOCK::get);
		scheduleFromNbt(level.getList("LiquidTicks", 10), world.getFluidTickScheduler(), Registry.FLUID::get);

		chunk.setInhabitedTime(level.getLong("InhabitedTime"));
		chunk.setShouldSave(true);

		ListTag entities = level.getList("Entities", 10);
		List<CompoundTag> out = new ArrayList<>(entities.size());
		for (int i = 0; i < entities.size(); i++) {
			out.add(entities.getCompound(i));
		}
		return out;
	}

	private static <T> void scheduleFromNbt(ListTag ticks, ServerTickScheduler<T> scheduler, java.util.function.Function<Identifier, T> byId) {
		for (int i = 0; i < ticks.size(); i++) {
			CompoundTag t = ticks.getCompound(i);
			T obj = byId.apply(new Identifier(t.getString("i")));
			scheduler.schedule(new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z")), obj, t.getInt("t"), TickPriority.byIndex(t.getInt("p")));
		}
	}

	private static SerializingRegionBasedStorageAccessor poiAccessor(ServerWorld world) {
		return (SerializingRegionBasedStorageAccessor) ((ThreadedAnvilChunkStorageAccess) world.getChunkManager().threadedAnvilChunkStorage)
			.savestate$getPointOfInterestStorage();
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
