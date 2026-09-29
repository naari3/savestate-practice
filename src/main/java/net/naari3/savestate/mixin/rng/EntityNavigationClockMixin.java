package net.naari3.savestate.mixin.rng;

import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.SwimNavigation;
import net.naari3.savestate.mixin.accessor.EntityNavigationAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 経路のノードで詰まったかの判定 (checkTimeouts) は、バニラでは実時間 (Util.getMeasuringTimeMs) を使っている。
 * そのままだと、復元したときに「保存からの経過時間」がそのまま詰まった時間として数えられ、経路が打ち切られる。
 * 復元されるワールドの時刻 (tick) × 50 ms に置き換える (20 TPS で動いているときの進み方はバニラと同じ)。
 *
 * SwimNavigation は checkTimeouts を上書きしているので、両方に当てる。
 * 対象が複数ある Mixin では @Shadow を使えない (remap できない) ので、world はアクセサ経由で読む。
 */
@Mixin({ EntityNavigation.class, SwimNavigation.class })
public abstract class EntityNavigationClockMixin {
	@Redirect(method = "checkTimeouts", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMeasuringTimeMs()J"))
	private long savestate$gameClock() {
		return ((EntityNavigationAccessor) this).savestate$getWorld().getTime() * 50L;
	}
}
