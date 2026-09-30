package net.naari3.savestate.detcheck;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.poi.PointOfInterestStorage;

/** ThreadedAnvilChunkStorage の内部を読むためのインターフェイス。mixin の ThreadedAnvilChunkStorageInvoker が実装する。 */
public interface ThreadedAnvilChunkStorageAccess {
	/** FULL でない (境界より外の読み込みレベルの) ChunkHolder も含む。currentChunkHolders の複製で、updateHolderMap の時点のもの。 */
	Iterable<ChunkHolder> savestate$chunkHolders();

	PointOfInterestStorage savestate$getPointOfInterestStorage();

	/** 読み込みを外す対象になったチャンクを外して保存する (ThreadedAnvilChunkStorage.tick)。 */
	void savestate$unloadTick();

	/** 読み込みを外す処理が残っているか (外す予定、外している途中、外す処理のタスク)。 */
	boolean savestate$unloadPending();
}
