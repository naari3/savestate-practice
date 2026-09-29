package net.naari3.savestate.mixin.rng;

import java.util.Random;
import net.minecraft.loot.context.LootContext;
import net.naari3.savestate.rng.StatefulRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** random を指定されなかった LootContext の既定の Random。 */
@Mixin(LootContext.Builder.class)
public class LootContextBuilderRngMixin {
	@Redirect(method = "build", at = @At(value = "NEW", target = "()Ljava/util/Random;"))
	private Random savestate$newRandom() {
		return new StatefulRandom();
	}
}
