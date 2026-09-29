package net.naari3.savestate.mixin.accessor;

import java.util.List;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.gen.Spawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerWorld.class)
public interface ServerWorldAccessor {
	@Accessor("field_25141")
	List<Spawner> savestate$getSpawners();
}
