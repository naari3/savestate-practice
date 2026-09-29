package net.naari3.savestate.mixin.accessor;

import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(World.class)
public interface WorldAccessor {
	@Accessor("lcgBlockSeed")
	int savestate$getLcgBlockSeed();

	@Accessor("lcgBlockSeed")
	void savestate$setLcgBlockSeed(int seed);
}
