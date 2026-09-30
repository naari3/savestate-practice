package net.naari3.savestate.compat;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.options.KeyBinding;

/** key-binding-api (SpeedrunAPI が同梱、または Fabric API) があるときだけ読み込まれる。 */
public final class KeyBindingApiCompat {
	private KeyBindingApiCompat() {
	}

	public static void register(KeyBinding... bindings) {
		for (KeyBinding binding : bindings) {
			KeyBindingHelper.registerKeyBinding(binding);
		}
	}
}
