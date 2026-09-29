package net.naari3.savestate.mixin.accessor;

import java.util.Map;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PersistentStateManager.class)
public interface PersistentStateManagerAccessor {
	/** 読み込み済みの PersistentState (ID ごと。raids、map_N、idcounts など)。 */
	@Accessor("loadedStates")
	Map<String, PersistentState> savestate$getLoadedStates();
}
