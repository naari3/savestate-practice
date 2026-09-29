package net.naari3.savestate.mixin.rng;

import java.util.Random;
import net.minecraft.world.WanderingTraderManager;
import net.naari3.savestate.rng.StatefulRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WanderingTraderManager.class)
public class WanderingTraderManagerRngMixin {
	@Redirect(method = "<init>*", at = @At(value = "NEW", target = "()Ljava/util/Random;"))
	private Random savestate$newRandom() {
		return new StatefulRandom();
	}
}
