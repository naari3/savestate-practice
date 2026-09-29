package net.naari3.savestate.mixin.rng;

import java.util.Random;
import net.minecraft.block.entity.EnchantingTableBlockEntity;
import net.naari3.savestate.rng.RngState;
import net.naari3.savestate.rng.StatefulRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EnchantingTableBlockEntity.class)
public class EnchantingTableBlockEntityRngMixin {
	@Redirect(method = "<clinit>", at = @At(value = "NEW", target = "()Ljava/util/Random;"))
	private static Random savestate$newRandom() {
		return RngState.registerStatic("enchantingTable", new StatefulRandom());
	}
}
