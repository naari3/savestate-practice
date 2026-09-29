package net.naari3.savestate.mixin.accessor;

import net.minecraft.world.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ScheduledTick.class)
public interface ScheduledTickAccessor {
	/** 同じ時刻・優先度の予約の順序を決める通し番号の採番カウンタ。 */
	@Accessor("idCounter")
	static long savestate$getIdCounter() {
		throw new AssertionError();
	}

	@Accessor("idCounter")
	static void savestate$setIdCounter(long value) {
		throw new AssertionError();
	}
}
