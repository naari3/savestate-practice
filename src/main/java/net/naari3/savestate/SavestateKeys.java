package net.naari3.savestate;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.options.KeyBinding;
import org.lwjgl.glfw.GLFW;

/** キー割り当て。SpeedrunAPI が同梱する key-binding-api で登録する (表示名は assets/mcsr-savestate/lang)。 */
public final class SavestateKeys {
	public static final String CATEGORY = "key.categories.mcsr-savestate";

	public static final KeyBinding SAVE = new KeyBinding("key.mcsr-savestate.save", GLFW.GLFW_KEY_F6, CATEGORY);
	public static final KeyBinding LOAD = new KeyBinding("key.mcsr-savestate.load", GLFW.GLFW_KEY_F7, CATEGORY);
	public static final KeyBinding NEXT_SLOT = new KeyBinding("key.mcsr-savestate.next_slot", GLFW.GLFW_KEY_F8, CATEGORY);
	public static final KeyBinding PREV_SLOT = new KeyBinding("key.mcsr-savestate.prev_slot", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
	/** 直前の load を取り消す (初期状態では割り当てなし)。 */
	public static final KeyBinding UNDO = new KeyBinding("key.mcsr-savestate.undo", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
	/** 決定論の検査 (debug 時のみ登録)。 */
	public static final KeyBinding DETCHECK = SavestateDebug.ENABLED
		? new KeyBinding("key.mcsr-savestate.detcheck", GLFW.GLFW_KEY_F9, CATEGORY)
		: null;

	private SavestateKeys() {
	}

	/** onInitializeClient から呼ぶ (options.txt が読まれる前)。 */
	static void register() {
		KeyBindingHelper.registerKeyBinding(SAVE);
		KeyBindingHelper.registerKeyBinding(LOAD);
		KeyBindingHelper.registerKeyBinding(NEXT_SLOT);
		KeyBindingHelper.registerKeyBinding(PREV_SLOT);
		KeyBindingHelper.registerKeyBinding(UNDO);
		if (DETCHECK != null) {
			KeyBindingHelper.registerKeyBinding(DETCHECK);
		}
	}
}
