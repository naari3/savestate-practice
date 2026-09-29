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

	@Accessor("rainGradientPrev")
	float savestate$getRainGradientPrev();

	@Accessor("rainGradientPrev")
	void savestate$setRainGradientPrev(float v);

	@Accessor("rainGradient")
	float savestate$getRainGradient();

	@Accessor("rainGradient")
	void savestate$setRainGradient(float v);

	@Accessor("thunderGradientPrev")
	float savestate$getThunderGradientPrev();

	@Accessor("thunderGradientPrev")
	void savestate$setThunderGradientPrev(float v);

	@Accessor("thunderGradient")
	float savestate$getThunderGradient();

	@Accessor("thunderGradient")
	void savestate$setThunderGradient(float v);
}
