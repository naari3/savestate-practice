package net.naari3.savestate.mixin.rng;

import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.naari3.savestate.rng.RngState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** かまどの経験値の端数を決める Math.random()。 */
@Mixin(AbstractFurnaceBlockEntity.class)
public class AbstractFurnaceBlockEntityRngMixin {
	// 同名のインスタンスメソッド dropExperience(PlayerEntity) があるので descriptor で static 版を指定する
	@Redirect(method = "dropExperience(Lnet/minecraft/world/World;Lnet/minecraft/util/math/Vec3d;IF)V", at = @At(value = "INVOKE", target = "Ljava/lang/Math;random()D"))
	private static double savestate$mathRandom() {
		return RngState.mathRandom();
	}
}
