package net.naari3.savestate.mixin.accessor;

import java.util.List;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerPlayerEntity.class)
public interface ServerPlayerEntityAccessor {
	/** クライアントにまとめて送る予定のエンティティ削除 (ID)。 */
	@Accessor("removedEntities")
	List<Integer> savestate$getRemovedEntities();

	@Accessor("syncedFoodLevel")
	void savestate$setSyncedFoodLevel(int value);

	@Accessor("syncedExperience")
	void savestate$setSyncedExperience(int value);
}
