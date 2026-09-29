package net.naari3.savestate.mixin.accessor;

import java.util.Map;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldChunk.class)
public interface WorldChunkAccessor {
	@Accessor("blockEntities")
	Map<BlockPos, BlockEntity> savestate$getBlockEntities();

	@Accessor("heightmaps")
	Map<Heightmap.Type, Heightmap> savestate$getHeightmaps();
}
