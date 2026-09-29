package net.naari3.savestate.mixin.accessor;

import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityNavigation.class)
public interface EntityNavigationAccessor {
	@Accessor("world")
	World savestate$getWorld();
}
