package net.naari3.savestate.mixin.fallback;

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

/** key-binding-api がないときだけ適用する (SavestateMixinPlugin)。キーを操作設定と options.txt の対象に加える。 */
@Mixin(GameOptions.class)
public class GameOptionsKeysMixin {
	@Shadow
	@Final
	@Mutable
	public KeyBinding[] keysAll;

	// options.txt の割り当てが反映されるように、load() より前に加える
	@Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/options/GameOptions;load()V"))
	private void savestate$addKeys(CallbackInfo ci) {
		this.keysAll = ArrayUtils.addAll(this.keysAll, SavestateKeys.all());
	}
}
