package net.naari3.savestate.mixin.memory;

import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.StorageIoWorker;
import net.naari3.savestate.memory.ChunkJournal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** POI のディスクへの書き込みの直前に、書く前の内容を取っておく (どの worker が POI かは ChunkJournal が判定する)。 */
@Mixin(StorageIoWorker.class)
public abstract class StorageIoWorkerJournalMixin {
	@Inject(method = "setResult", at = @At("HEAD"))
	private void savestate$beforeWrite(ChunkPos pos, CompoundTag nbt, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
		ChunkJournal.onWorkerWrite((StorageIoWorker) (Object) this, pos);
	}
}
