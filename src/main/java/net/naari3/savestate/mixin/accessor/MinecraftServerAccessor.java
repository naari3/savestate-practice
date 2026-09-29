package net.naari3.savestate.mixin.accessor;

import java.util.Random;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecraftServer.class)
public interface MinecraftServerAccessor {
	@Accessor("session")
	LevelStorage.Session savestate$getSession();

	@Accessor("random")
	Random savestate$getRandom();
}
