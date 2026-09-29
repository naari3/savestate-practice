package net.naari3.savestate.mixin.accessor;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.server.world.BlockEvent;
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

	/** プレイヤーがいなくなってからの tick 数 (300 を超えるとエンティティの tick が止まる)。 */
	@Accessor("idleTimeout")
	int savestate$getIdleTimeout();

	@Accessor("idleTimeout")
	void savestate$setIdleTimeout(int value);

	@Accessor("syncedBlockEventQueue")
	ObjectLinkedOpenHashSet<BlockEvent> savestate$getSyncedBlockEventQueue();
}
