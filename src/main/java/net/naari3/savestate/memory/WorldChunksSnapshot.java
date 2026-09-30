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
import java.util.concurrent.locks.LockSupport;
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
import net.minecraft.server.world.ChunkTicketManager;
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
 * 1 つのワールドのチャンクの状態のスナップショット。
 *
 * - チャンクごと: ChunkSection、ハイトマップ、ブロックエンティティ、inhabitedTime
 * - ワールドごと: ブロックエンティティのリストの並び (tick の順序)、スケジュール済みの tick、ブロックイベントのキュー、POI
 *
 * 復元では setBlockState を使わない (置き換え時の処理、例えばチェストの中身をばらまく処理が走ってしまうため)。
 * ChunkSection を差し替え、変わったブロックについてだけ光の再計算とクライアントへの更新を行う。
 * 取得時に読み込まれていなかったチャンクは、{@link ChunkJournal} の記録を使って {@link #rewriteJournalChunks} で戻す。
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
	private Set<Long> tickingKeys;
	private long[] holderOrder;
	/** セクションごと。複製済み。 */
	private Map<Long, Optional<?>> poi;
	/** 取得時にまだディスクへ保存されていなかった POI セクション (保存される順)。 */
	private final List<Long> poiUnsaved = new ArrayList<>();
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
		snap.holderOrder = ((ThreadedAnvilChunkStorageAccess) world.getChunkManager().threadedAnvilChunkStorage).savestate$holderOrder();

		SerializingRegionBasedStorageAccessor poiAcc = poiAccessor(world);
		snap.poi = new DeepCloner(chunkPolicy).copy(new LinkedHashMap<Long, Optional<?>>(poiAcc.savestate$getLoadedElements()));
		snap.poiUnsaved.addAll(poiAcc.savestate$getUnsavedElements());
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

	/** 復元中だけ取得時のチャンクを保持するチケット (期限なし)。 */
	private static final ChunkTicketType<ChunkPos> RESTORE_TICKET = ChunkTicketType.create("savestate_practice_restore", Comparator.comparingLong(ChunkPos::toLong));
	/** 1 回の待ちの上限。メインスレッドにタスクが届けば (ThreadExecutor.send が unpark する)、それより早く起きる。 */
	private static final long WAIT_STEP_NANOS = 1_000_000L;
	private static final long MAX_WAIT_NANOS = 10_000_000_000L;
	/** 読み込み済みのチャンク数が変わらないまま、この時間たったらあきらめる (プレイヤーのチケットやチャンクの読み込みは非同期に進むので長めにとる)。 */
	private static final long STABLE_NANOS = 3_000_000_000L;
	private static final long PROGRESS_LOG_NANOS = 250_000_000L;

	/**
	 * 読み込み済みのチャンクの集合を、取得時と同じにそろえる (ワールドは進めない)。
	 * チケットの期限切れの処理、チケットの反映、読み込みを外す処理、保留中のタスクの実行を、
	 * 集合が取得時と同じになるか、変化しなくなるまで繰り返す。
	 * 取得後に読み込まれたチャンクは外すときに今の内容で保存されるので、この後 {@link #rewriteJournalChunks} で上書きする。
	 * 戻り値は {繰り返した回数, かかった時間 (ms), そろえた後の読み込み済みチャンク数, 取得時にあって今ないチャンク数, 取得時になくて今あるチャンク数}。
	 */
	int[] convergeLoadedSet(ServerWorld world) {
		ServerChunkManager chunkManager = world.getChunkManager();
		ThreadedAnvilChunkStorageAccess tacs = (ThreadedAnvilChunkStorageAccess) chunkManager.threadedAnvilChunkStorage;
		ChunkTicketManager ticketManager = ((ServerChunkManagerInvoker) chunkManager).savestate$getTicketManager();
		Set<Long> wanted = this.journal.snapshotLoaded;
		long begin = System.nanoTime();
		long lastChange = begin;
		long lastProgressLog = begin;
		int iterations = 0;
		int lastCount = -1;
		int[] diff = new int[3];
		int[] tickingDiff = new int[2];
		boolean unloadPending = false;
		// そろえる間は、取得時のチャンクに期限なしのチケット (FULL レベル) を付けて、読み込みが外れないようにする。
		// プレイヤーのチケットが非同期に届くまでの間にレベルが下がって外れると、中のエンティティが NBT から作り直され、
		// 実行時の状態 (AI など) が失われるため
		for (long k : wanted) {
			ChunkPos pos = new ChunkPos(k);
			chunkManager.addTicket(RESTORE_TICKET, pos, 0, pos);
		}
		// プレイヤーのチケットの追加・削除は、player ticket throttler を経由して 1 チャンクずつ届く
		// (1 チャンク付けるごとに、そのチャンクが entity ticking になるのを待ってから次へ進む)。
		// チャンクの読み込みもワーカースレッドで進むので、メインスレッドのタスクを実行しては、次のタスクが届くまで短く待つ、を繰り返す
		while (true) {
			iterations++;
			// 期限付きのチケット (getChunk で付く unknown など、期限 1 tick) の期限切れを進める。
			// 取得時のチャンクは上の RESTORE_TICKET で保持しているので、ここで一時チケットが外れても読み込みは外れない
			((ChunkTicketManagerInvoker) ticketManager).savestate$purge();
			((ServerChunkManagerInvoker) chunkManager).savestate$updateTickets();
			tacs.savestate$unloadTick();
			// executeQueuedTasks は 1 回で 1 つしか実行しないので、キューが空になるまで回す
			// (プレイヤーのチケットの付け外しは、1 チャンクごとのタスクとしてここに積まれる)
			while (chunkManager.executeQueuedTasks()) {
			}
			((ServerChunkManagerInvoker) chunkManager).savestate$updateTickets();
			diff = loadedDiff(tacs, wanted);
			tickingDiff = tickingDiff(tacs, this.tickingKeys);
			unloadPending = tacs.savestate$unloadPending();
			// 集合がそろっても、読み込みを外す処理 (エンティティの取り外しと保存) はタスクとして後から走るので、それも終わるまで待つ
			if (diff[1] == 0 && diff[2] == 0 && tickingDiff[0] == 0 && tickingDiff[1] == 0 && !unloadPending) {
				break;
			}
			long now = System.nanoTime();
			if (SavestateDebug.ENABLED && now - lastProgressLog >= PROGRESS_LOG_NANOS) {
				lastProgressLog = now;
				SavestateDebug.log("{}: converging, {} ms: loaded diff {}/{}, ticking diff {}/{}, unload pending {}, throttler {}",
					world.getRegistryKey().getValue(), (now - begin) / 1_000_000L, diff[1], diff[2], tickingDiff[0], tickingDiff[1], unloadPending,
					ticketManager.toDumpString());
			}
			if (diff[0] != lastCount) {
				lastCount = diff[0];
				lastChange = now;
			} else if (now - lastChange >= STABLE_NANOS) {
				break;
			}
			if (now - begin >= MAX_WAIT_NANOS) {
				break;
			}
			LockSupport.parkNanos("savestate-practice: waiting for chunk tasks", WAIT_STEP_NANOS);
			if (Thread.interrupted()) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		for (long k : wanted) {
			ChunkPos pos = new ChunkPos(k);
			chunkManager.removeTicket(RESTORE_TICKET, pos, 0, pos);
		}
		((ServerChunkManagerInvoker) chunkManager).savestate$updateTickets();
		// tickChunks は ChunkHolder の並びのリストをシャッフルして処理する。外れて作り直された ChunkHolder は後ろに付くので、
		// 並びが取得時と違うと、同じ乱数でシャッフルしても処理の順 (= ワールドの乱数を引く順) が変わる
		int extraHolders = tacs.savestate$reorderHolders(this.holderOrder);
		if (extraHolders != 0) {
			SavestateMod.LOGGER.warn("[memory] {}: {} chunk holders were not present at capture", world.getRegistryKey().getValue(), extraHolders);
		}
		if (SavestateDebug.ENABLED && (diff[1] != 0 || diff[2] != 0)) {
			this.logTicketSamples(world, tacs, wanted);
		}
		int[] td = tickingDiff(tacs, this.tickingKeys);
		if (td[0] != 0 || td[1] != 0 || unloadPending) {
			SavestateMod.LOGGER.warn("[memory] {}: ticking chunk set differs from the snapshot ({} missing, {} extra), unload pending {}",
				world.getRegistryKey().getValue(), td[0], td[1], unloadPending);
		}
		return new int[] { iterations, (int) ((System.nanoTime() - begin) / 1_000_000L), diff[0], diff[1], diff[2] };
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

	/** チケットの変化を反映し、読み込みを外す処理が残らなくなるまで回す (最大 MAX_WAIT_NANOS)。 */
	static void flushUnloads(ServerWorld world) {
		ServerChunkManager chunkManager = world.getChunkManager();
		ThreadedAnvilChunkStorageAccess tacs = (ThreadedAnvilChunkStorageAccess) chunkManager.threadedAnvilChunkStorage;
		long begin = System.nanoTime();
		while (true) {
			((ServerChunkManagerInvoker) chunkManager).savestate$updateTickets();
			tacs.savestate$unloadTick();
			while (chunkManager.executeQueuedTasks()) {
			}
			((ServerChunkManagerInvoker) chunkManager).savestate$updateTickets();
			if (!tacs.savestate$unloadPending() || System.nanoTime() - begin >= MAX_WAIT_NANOS) {
				return;
			}
			LockSupport.parkNanos("savestate-practice: waiting for chunk tasks", WAIT_STEP_NANOS);
			if (Thread.interrupted()) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	/**
	 * 予約してあるブロックの変更 (markForUpdate) を、今すぐクライアントへ送る。通常は次の tick の tickChunks で送られる。
	 * 復元ではプレイヤーの位置の同期をすぐ送るので、先にブロックを送っておかないと、クライアントは古いブロックのまま
	 * 復帰位置に置かれ、埋まったブロックから押し出されるなどして位置がずれる。送るだけで、サーバーの状態は変えない。
	 */
	static void flushBlockUpdates(ServerWorld world) {
		for (ChunkHolder holder : ((ThreadedAnvilChunkStorageAccess) world.getChunkManager().threadedAnvilChunkStorage).savestate$chunkHolders()) {
			// vanilla (ServerChunkManager.tickChunks) と同じく、tick 対象のチャンクだけ
			WorldChunk chunk = holder.getWorldChunk();
			if (chunk != null) {
				holder.flushUpdates(chunk);
			}
		}
	}

	static Set<Long> fullChunkKeys(ServerWorld world) {
		Set<Long> keys = new HashSet<>();
		for (ChunkHolder holder : ((ThreadedAnvilChunkStorageAccess) world.getChunkManager().threadedAnvilChunkStorage).savestate$chunkHolders()) {
			if (fullChunk(holder) != null) {
				keys.add(holder.getPos().toLong());
			}
		}
		return keys;
	}

	/** getWorldChunk() と違い、tick されない境界のチャンクも返す。 */
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

	static final class Prepared {
		final List<Object[]> chunks;
		final Map<Long, Optional<?>> poi;

		Prepared(List<Object[]> chunks, Map<Long, Optional<?>> poi) {
			this.chunks = chunks;
			this.poi = poi;
		}
	}

	/** 復元の準備。ワールドには手を入れず、スナップショットのまた複製を作る (失敗しても何も変わらない)。 */
	Prepared prepare(SharePolicy chunkPolicy) {
		return new Prepared(new DeepCloner(chunkPolicy).copyAll(this.chunkData), new DeepCloner(chunkPolicy).copy(this.poi));
	}

	/**
	 * {@link #restore} の前に呼ぶ。取得時に読み込まれていて今は読み込まれていないチャンクを同期で読み込み
	 * ({@link #restore} で戻せるように)、POI をメモリとディスクの両方で戻す。
	 * 取得後に読み込まれたチャンクはここでは扱わず、{@link #rewriteJournalChunks} で戻す。
	 * 同期で読み込んだチャンク数を返す。
	 */
	int restoreOutside(ServerWorld world, Prepared prepared) {
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

		// loadedAfter をここでその場に当ててエンティティを入れると、直後の取り外しとの順序の問題で、
		// どのチャンクにも属さないエンティティが残った。集合をそろえる段階で外れるのを待ってから書き戻す

		this.restorePoi(world, prepared.poi);
		return syncLoaded;
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

	private void restorePoi(ServerWorld world, Map<Long, Optional<?>> fresh) {
		SerializingRegionBasedStorageAccessor acc = poiAccessor(world);
		// 取得後に読み込まれたセクション (取得時になかったもの) はメモリから外す。次に使われるときにディスクから読み直される
		acc.savestate$getLoadedElements().clear();
		acc.savestate$getUnsavedElements().clear();
		for (Map.Entry<Long, Optional<?>> e : fresh.entrySet()) {
			acc.savestate$getLoadedElements().put(e.getKey(), e.getValue());
		}
		// 取得後にディスクへ書かれた分は下で取得時のディスクの内容に戻すので、
		// メモリ・未保存の集合・ディスクの 3 つが取得時と同じになる
		for (long key : this.poiUnsaved) {
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

		// NBT の tick の時刻は記録時からの相対
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

	/** 戻り値は {戻したチャンク数, 見つからなかったチャンク数, 変わったブロック数}。 */
	int[] restore(ServerWorld world, Prepared prepared) {
		List<Object[]> fresh = prepared.chunks;
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

			Map<BlockPos, BlockEntity> beMap = acc.savestate$getBlockEntities();
			for (BlockEntity be : beMap.values()) {
				be.markRemoved();
				removed.add(be);
			}
			beMap.clear();

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

			@SuppressWarnings("unchecked")
			Map<Heightmap.Type, Heightmap> heightmaps = (Map<Heightmap.Type, Heightmap>) data[1];
			acc.savestate$getHeightmaps().clear();
			acc.savestate$getHeightmaps().putAll(heightmaps);

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

		// ワールドのブロックエンティティのリストの並びは tick の順序なので、取得時の並びで作り直す
		rebuildList(world.blockEntities, this.blockEntityOrder, removed, restoredByPos, false);
		rebuildList(world.tickingBlockEntities, this.tickingBlockEntityOrder, removed, restoredByPos, true);

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
		// 取得時のリストになかったもの (戻したチャンクで新しく増えたもの、戻さなかったチャンクのもの) は末尾に足す
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
