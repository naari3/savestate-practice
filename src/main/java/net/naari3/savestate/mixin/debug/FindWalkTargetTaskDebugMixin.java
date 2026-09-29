package net.naari3.savestate.mixin.debug;

import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.task.FindWalkTargetTask;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.server.world.ServerWorld;
import net.naari3.savestate.SavestateDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 調査用: 徘徊の行き先を決める直前・直後の Random の状態と、決まった行き先を出す。 */
@Mixin(FindWalkTargetTask.class)
public class FindWalkTargetTaskDebugMixin {
	@Inject(method = "run", at = @At("HEAD"))
	private void savestate$before(ServerWorld world, PathAwareEntity entity, long time, CallbackInfo ci) {
		SavestateDebug.log("FindWalkTarget BEGIN {} time={} rand={}",
			entity.getUuidAsString().substring(0, 8), time, SavestateDebug.rand(entity.getRandom()));
	}

	@Inject(method = "run", at = @At("TAIL"))
	private void savestate$after(ServerWorld world, PathAwareEntity entity, long time, CallbackInfo ci) {
		SavestateDebug.log("FindWalkTarget END   {} time={} rand={} walk={}",
			entity.getUuidAsString().substring(0, 8), time, SavestateDebug.rand(entity.getRandom()),
			entity.getBrain().getOptionalMemory(MemoryModuleType.WALK_TARGET).map(w -> w.getLookTarget().getBlockPos().toShortString()).orElse("-"));
	}
}
