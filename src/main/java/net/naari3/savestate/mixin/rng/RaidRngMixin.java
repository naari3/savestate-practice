package net.naari3.savestate.mixin.rng;

import java.util.Random;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.raid.Raid;
import net.naari3.savestate.rng.StatefulRandom;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Raid.random を StatefulRandom にし、その状態を raids.dat の各 Raid に保存する。 */
@Mixin(Raid.class)
public class RaidRngMixin {
	private static final String KEY = "mcsr-savestate:Random";

	@Shadow
	@Final
	private Random random;

	@Redirect(method = "<init>*", at = @At(value = "NEW", target = "()Ljava/util/Random;"))
	private Random savestate$newRandom() {
		return new StatefulRandom();
	}

	@Inject(method = "toTag", at = @At("RETURN"))
	private void savestate$writeRandom(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
		if (this.random instanceof StatefulRandom) {
			tag.put(KEY, ((StatefulRandom) this.random).toTag());
		}
	}

	@Inject(method = "<init>(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/nbt/CompoundTag;)V", at = @At("TAIL"))
	private void savestate$readRandom(ServerWorld world, CompoundTag tag, CallbackInfo ci) {
		if (this.random instanceof StatefulRandom && tag.contains(KEY, 10)) {
			((StatefulRandom) this.random).fromTag(tag.getCompound(KEY));
		}
	}
}
