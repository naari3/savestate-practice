package net.naari3.savestate.detcheck;

import net.minecraft.server.world.ChunkHolder;
import net.minecraft.world.poi.PointOfInterestStorage;

/** ThreadedAnvilChunkStorage の内部を読むためのインターフェイス。mixin の ThreadedAnvilChunkStorageInvoker が実装する。 */
public interface ThreadedAnvilChunkStorageAccess {
	/** 読み込み済みのチャンク。 */
	Iterable<ChunkHolder> savestate$chunkHolders();

	PointOfInterestStorage savestate$getPointOfInterestStorage();
}
