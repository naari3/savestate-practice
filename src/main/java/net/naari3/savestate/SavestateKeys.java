package net.naari3.savestate;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.options.KeyBinding;
import org.lwjgl.glfw.GLFW;

/** キー割り当て。SpeedrunAPI が同梱する key-binding-api で登録する (表示名は assets/savestate-practice/lang)。 */
public final class SavestateKeys {
	public static final String CATEGORY = "key.categories.savestate-practice";

	public static final KeyBinding SAVE = new KeyBinding("key.savestate-practice.save", GLFW.GLFW_KEY_F6, CATEGORY);
	public static final KeyBinding LOAD = new KeyBinding("key.savestate-practice.load", GLFW.GLFW_KEY_F7, CATEGORY);
	public static final KeyBinding NEXT_SLOT = new KeyBinding("key.savestate-practice.next_slot", GLFW.GLFW_KEY_F8, CATEGORY);
	public static final KeyBinding PREV_SLOT = new KeyBinding("key.savestate-practice.prev_slot", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
	/** 直前の load を取り消す (初期状態では割り当てなし)。 */
	public static final KeyBinding UNDO = new KeyBinding("key.savestate-practice.undo", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
	/** 決定論の検査 (debug 時のみ登録)。 */
	public static final KeyBinding DETCHECK = SavestateDebug.ENABLED
		? new KeyBinding("key.savestate-practice.detcheck", GLFW.GLFW_KEY_F9, CATEGORY)
		: null;

	private SavestateKeys() {
	}

	public static KeyBinding[] all() {
		return DETCHECK != null
			? new KeyBinding[] { SAVE, LOAD, NEXT_SLOT, PREV_SLOT, UNDO, DETCHECK }
			: new KeyBinding[] { SAVE, LOAD, NEXT_SLOT, PREV_SLOT, UNDO };
	}

	/** onInitializeClient から呼ぶ (options.txt が読まれる前)。 */
	static void register() {
		for (KeyBinding binding : all()) {
			KeyBindingHelper.registerKeyBinding(binding);
		}
	}
}
