package net.naari3.savestate.mixin.accessor;

import java.util.Set;
import java.util.TreeSet;
import net.minecraft.server.world.ServerTickScheduler;
import net.minecraft.world.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerTickScheduler.class)
public interface ServerTickSchedulerAccessor<T> {
	@Accessor("scheduledTickActions")
	Set<ScheduledTick<T>> savestate$getScheduledTickActions();

	@Accessor("scheduledTickActionsInOrder")
	TreeSet<ScheduledTick<T>> savestate$getScheduledTickActionsInOrder();
}
