package net.naari3.savestate.mixin.accessor;

import java.util.function.BooleanSupplier;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.naari3.savestate.detcheck.ThreadedAnvilChunkStorageAccess;
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
