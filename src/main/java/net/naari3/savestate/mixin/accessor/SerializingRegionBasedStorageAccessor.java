package net.naari3.savestate.mixin.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.Optional;
import net.minecraft.world.storage.SerializingRegionBasedStorage;
import net.minecraft.world.storage.StorageIoWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SerializingRegionBasedStorage.class)
public interface SerializingRegionBasedStorageAccessor {
	@Accessor("worker")
	StorageIoWorker savestate$getWorker();

	/** セクション (ChunkSectionPos の long) ごとの中身。 */
	@Accessor("loadedElements")
	Long2ObjectMap<Optional<?>> savestate$getLoadedElements();

	@Accessor("unsavedElements")
	LongLinkedOpenHashSet savestate$getUnsavedElements();
}
