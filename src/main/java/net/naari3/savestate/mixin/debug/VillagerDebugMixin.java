package net.naari3.savestate.mixin.debug;

import java.util.Optional;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.naari3.savestate.SavestateDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 調査用: load 後の村人の Random の状態と WALK_TARGET を tick ごとに出す。
 * 最初の 60 tick は毎 tick、それ以降 600 tick までは WALK_TARGET が変わったときだけ。
 */
@Mixin(VillagerEntity.class)
public abstract class VillagerDebugMixin {
	@Unique
	private int savestate$dbgTicks;
	@Unique
	private BlockPos savestate$dbgLastTarget;

	@Inject(method = "tick", at = @At("HEAD"))
	private void savestate$debugTick(CallbackInfo ci) {
		if (!SavestateDebug.ENABLED) {
			return;
		}
		VillagerEntity self = (VillagerEntity) (Object) this;
		if (self.world.isClient || this.savestate$dbgTicks > 600) {
			return;
		}
		int t = this.savestate$dbgTicks++;
		Optional<WalkTarget> walk = self.getBrain().getOptionalMemory(MemoryModuleType.WALK_TARGET);
		BlockPos target = walk.map(w -> w.getLookTarget().getBlockPos()).orElse(null);
		boolean changed = target == null ? this.savestate$dbgLastTarget != null : !target.equals(this.savestate$dbgLastTarget);
		if (t < 60 || changed) {
			SavestateDebug.log("villager {} t={} time={} rand={} pos={} walk={} activity={}",
				self.getUuidAsString().substring(0, 8), t, self.world.getTime(), SavestateDebug.rand(self.getRandom()),
				self.getBlockPos().toShortString(), target == null ? "-" : target.toShortString(),
				self.getBrain().getFirstPossibleNonCoreActivity().map(Object::toString).orElse("-"));
		}
		this.savestate$dbgLastTarget = target;
	}
}
