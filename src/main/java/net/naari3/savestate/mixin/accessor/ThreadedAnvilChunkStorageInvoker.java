package net.naari3.savestate.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Queue;
import java.util.function.BooleanSupplier;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.naari3.savestate.detcheck.ThreadedAnvilChunkStorageAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class ThreadedAnvilChunkStorageInvoker implements ThreadedAnvilChunkStorageAccess {
	@Shadow
	protected abstract PointOfInterestStorage getPointOfInterestStorage();

	@Shadow
	protected abstract Iterable<ChunkHolder> entryIterator();

	@Shadow
	protected abstract void tick(BooleanSupplier shouldKeepTicking);

	/** 外す予定のチャンク。 */
	@Shadow
	@Final
	private LongSet unloadedChunks;

	/** 外している途中のチャンク (保存などの完了待ち)。 */
	@Shadow
	@Final
	private Long2ObjectLinkedOpenHashMap<ChunkHolder> field_18807;

	/** 外す処理 (エンティティの取り外し、保存) のタスクキュー。 */
	@Shadow
	@Final
	private Queue<Runnable> field_19343;

	@Override
	public boolean savestate$unloadPending() {
		return !this.unloadedChunks.isEmpty() || !this.field_18807.isEmpty() || !this.field_19343.isEmpty();
	}

	@Override
	public PointOfInterestStorage savestate$getPointOfInterestStorage() {
		return this.getPointOfInterestStorage();
	}

	@Override
	public Iterable<ChunkHolder> savestate$chunkHolders() {
		return this.entryIterator();
	}

	@Override
	public void savestate$unloadTick() {
		this.tick(() -> true);
	}
}
