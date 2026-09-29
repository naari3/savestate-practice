package net.naari3.savestate.mixin.accessor;

import net.minecraft.world.storage.SerializingRegionBasedStorage;
import net.minecraft.world.storage.StorageIoWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SerializingRegionBasedStorage.class)
public interface SerializingRegionBasedStorageAccessor {
	@Accessor("worker")
	StorageIoWorker savestate$getWorker();
}
