package net.naari3.savestate.mixin.memory;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.world.chunk.Chunk;
import net.naari3.savestate.memory.ChunkJournal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** チャンクがディスクへ保存されたことを記録する。 */
@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class ThreadedAnvilChunkStorageJournalMixin {
	@Shadow
	@Final
	private ServerWorld world;

	@Inject(method = "save(Lnet/minecraft/world/chunk/Chunk;)Z", at = @At("RETURN"))
	private void savestate$onSaved(Chunk chunk, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			ChunkJournal.onChunkSaved(this.world, chunk.getPos());
		}
	}
}
