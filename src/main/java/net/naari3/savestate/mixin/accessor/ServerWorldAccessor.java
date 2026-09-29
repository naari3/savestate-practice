package net.naari3.savestate.mixin.accessor;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.gen.Spawner;
import net.minecraft.world.level.ServerWorldProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerWorld.class)
public interface ServerWorldAccessor {
	@Accessor("field_25141")
	List<Spawner> savestate$getSpawners();

	@Accessor("entitiesById")
	Int2ObjectMap<Entity> savestate$getEntitiesById();

	@Accessor("worldProperties")
	ServerWorldProperties savestate$getWorldProperties();
}
