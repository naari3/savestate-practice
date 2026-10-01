package net.naari3.savestate.mixin.accessor;

import com.google.common.collect.Table;
import com.google.common.primitives.UnsignedLong;
import java.util.Queue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.timer.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** /schedule の予約一覧 (level.dat に保存される)。 */
@Mixin(Timer.class)
public interface TimerAccessor {
	@Accessor("events")
	Queue<?> savestate$getEvents();

	@Accessor("eventsByName")
	Table<?, ?, ?> savestate$getEventsByName();

	@Accessor("eventCounter")
	UnsignedLong savestate$getEventCounter();

	@Accessor("eventCounter")
	void savestate$setEventCounter(UnsignedLong counter);

	@Invoker("addEvent")
	void savestate$addEvent(CompoundTag tag);
}
