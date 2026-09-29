package net.naari3.savestate.mixin.memory;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.naari3.savestate.memory.ChunkJournal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** チャンクがワールドに読み込まれた (FULL になった) 瞬間を記録する。 */
@Mixin(WorldChunk.class)
public abstract class WorldChunkJournalMixin {
	@Shadow
	@Final
	private World world;

	@Inject(method = "loadToWorld", at = @At("TAIL"))
	private void savestate$onLoaded(CallbackInfo ci) {
		if (this.world instanceof ServerWorld) {
			ChunkJournal.onChunkLoaded((ServerWorld) this.world, (WorldChunk) (Object) this);
		}
	}
}
