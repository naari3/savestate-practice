package net.naari3.savestate.mixin.rng;

import net.minecraft.entity.LivingEntity;
import net.naari3.savestate.rng.RngState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** コンストラクタ (初期の向きなど) と damage (ノックバック方向) の Math.random()。 */
@Mixin(LivingEntity.class)
public class LivingEntityRngMixin {
	@Redirect(method = { "<init>*", "damage" }, at = @At(value = "INVOKE", target = "Ljava/lang/Math;random()D"))
	private double savestate$mathRandom() {
		return RngState.mathRandom();
	}
}
