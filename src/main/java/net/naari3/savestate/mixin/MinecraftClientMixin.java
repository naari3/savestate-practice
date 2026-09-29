package net.naari3.savestate.mixin;

import net.minecraft.client.MinecraftClient;
import net.naari3.savestate.SavestateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void savestate$tick(CallbackInfo ci) {
		SavestateManager.onClientTick((MinecraftClient) (Object) this);
	}
}
