package net.naari3.savestate.mixin.accessor;

import java.util.Random;
import net.minecraft.world.WanderingTraderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WanderingTraderManager.class)
public interface WanderingTraderManagerAccessor {
	@Accessor("random")
	Random savestate$getRandom();
}
