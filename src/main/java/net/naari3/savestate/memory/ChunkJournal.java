package net.naari3.savestate.memory;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.StorageIoWorker;
import net.naari3.savestate.SavestateMod;

/**
 * スナップショットの取得後に起きた、読み込み済みチャンク以外への変化の記録 (1 つのスナップショットの 1 つのワールド分)。
 *
 * - {@link #loadedAfter}: 取得時に読み込まれていなかったチャンクが、取得後に初めて読み込まれた瞬間の NBT。
 *   読み込まれるまでチャンクは変化しないので、これが取得時点の状態に相当する (新しく生成されたチャンクは、生成直後の状態が
 *   「まだ生成されていない」状態の代わりになる。ワールド生成はシードから決まるため)
 * - {@link #written}: 取得後にディスクへ保存されたチャンク。ディスクの内容が取得時点と変わっているかもしれない
 * - {@link #poiBefore}: 取得後に初めて POI をディスクへ書く直前の、ディスク上の内容 (なければ null)。
 *   POI (ベッド、職業ブロック、ネザーポータルなど) はチャンクとは別のファイルにある
 *
 * 記録はサーバースレッドの Mixin (チャンクの読み込み・保存、POI の書き込み) から呼ばれる。
 */
public final class ChunkJournal {
	private static final List<ChunkJournal> ACTIVE = new CopyOnWriteArrayList<>();
	/**
	 * 復元中のスナップショットの記録は止める (復元のための同期読み込みや書き戻しを記録してしまわないように)。
	 * ほかのスナップショットの記録は止めない (この復元によるディスクへの書き込みも、ほかのスナップショットにとっては変化なので)。
	 */
	private static final Set<ChunkJournal> RESTORING = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

	final ServerWorld world;
	final Set<Long> snapshotLoaded;
	final StorageIoWorker poiWorker;
	final Map<Long, CompoundTag> loadedAfter = new HashMap<>();
	final Set<Long> written = new HashSet<>();
	final Map<Long, CompoundTag> poiBefore = new HashMap<>();

	ChunkJournal(ServerWorld world, Set<Long> snapshotLoaded, StorageIoWorker poiWorker) {
		this.world = world;
		this.snapshotLoaded = snapshotLoaded;
		this.poiWorker = poiWorker;
	}

	void activate() {
		ACTIVE.add(this);
	}

	void deactivate() {
		ACTIVE.remove(this);
	}

	static void beginRestore(List<ChunkJournal> journals) {
		RESTORING.addAll(journals);
	}

	static void endRestore(List<ChunkJournal> journals) {
		RESTORING.removeAll(journals);
	}

	private boolean skip() {
		return RESTORING.contains(this);
	}

	/** 別のワールドを開いたときなどに、全スナップショットの記録をやめる。 */
	public static void clearAll() {
		ACTIVE.clear();
	}

	public static void onChunkLoaded(ServerWorld world, WorldChunk chunk) {
		if (ACTIVE.isEmpty()) {
			return;
		}
		long pos = chunk.getPos().toLong();
		CompoundTag tag = null;
		for (ChunkJournal j : ACTIVE) {
			if (j.skip() || j.world != world || j.snapshotLoaded.contains(pos) || j.loadedAfter.containsKey(pos)) {
				continue;
			}
			if (tag == null) {
				// 同じ NBT を複数のスナップショットで共有する (以後書き換えないので問題ない)
				tag = ChunkSerializer.serialize(world, chunk);
			}
			j.loadedAfter.put(pos, tag);
		}
	}

	public static void onChunkSaved(ServerWorld world, ChunkPos pos) {
		if (ACTIVE.isEmpty()) {
			return;
		}
		long key = pos.toLong();
		for (ChunkJournal j : ACTIVE) {
			if (!j.skip() && j.world == world) {
				j.written.add(key);
			}
		}
	}

	/** StorageIoWorker.setResult の先頭から呼ばれる。POI の書き込みなら、書く前のディスクの内容を取っておく。 */
	public static void onWorkerWrite(StorageIoWorker worker, ChunkPos pos) {
		if (ACTIVE.isEmpty()) {
			return;
		}
		long key = pos.toLong();
		boolean read = false;
		CompoundTag before = null;
		for (ChunkJournal j : ACTIVE) {
			if (j.skip() || j.poiWorker != worker || j.poiBefore.containsKey(key)) {
				continue;
			}
			if (!read) {
				try {
					before = worker.getNbt(pos);
				} catch (IOException e) {
					SavestateMod.LOGGER.error("[memory] failed to read POI data at {} before overwrite", pos, e);
				}
				read = true;
			}
			j.poiBefore.put(key, before);
		}
	}
}
