package net.naari3.savestate.mixin.accessor;

import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.world.poi.PointOfInterestStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ThreadedAnvilChunkStorage.class)
public interface ThreadedAnvilChunkStorageInvoker {
	@Invoker("getPointOfInterestStorage")
	PointOfInterestStorage savestate$getPointOfInterestStorage();
}
