package net.naari3.savestate.mixin;

import net.minecraft.client.options.GameOptions;
import net.minecraft.client.options.KeyBinding;
import net.naari3.savestate.SavestateKeys;
import org.apache.commons.lang3.ArrayUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameOptions.class)
public class GameOptionsMixin {
	@Shadow
	@Final
	@Mutable
	public KeyBinding[] keysAll;

	// Keys must be registered before load() so that saved bindings in options.txt are applied.
	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/options/GameOptions;load()V"))
	private void savestate$addKeys(CallbackInfo ci) {
		this.keysAll = ArrayUtils.addAll(this.keysAll, SavestateKeys.all());
	}
}
