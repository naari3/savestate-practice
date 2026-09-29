package net.naari3.savestate;

import java.util.Collections;
import java.util.Map;
import net.minecraft.client.options.KeyBinding;
import net.naari3.savestate.mixin.accessor.KeyBindingAccessor;
import org.lwjgl.glfw.GLFW;

public final class SavestateKeys {
	// Fabric API がないので MOD の lang ファイルは読まれない。表示名をそのまま翻訳キーに使う
	public static final String CATEGORY = "MCSR Savestate";

	public static final KeyBinding SAVE = new KeyBinding("Save state", GLFW.GLFW_KEY_F6, CATEGORY);
	public static final KeyBinding LOAD = new KeyBinding("Load state", GLFW.GLFW_KEY_F7, CATEGORY);
	public static final KeyBinding NEXT_SLOT = new KeyBinding("Next slot", GLFW.GLFW_KEY_F8, CATEGORY);
	public static final KeyBinding PREV_SLOT = new KeyBinding("Previous slot", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
	/** 直前の load を取り消す (初期状態では割り当てなし)。 */
	public static final KeyBinding UNDO = new KeyBinding("Undo last load", GLFW.GLFW_KEY_UNKNOWN, CATEGORY);
	/** 決定論の検査 (debug 時のみ登録)。 */
	public static final KeyBinding DETCHECK = SavestateDebug.ENABLED
		? new KeyBinding("Determinism check (debug)", GLFW.GLFW_KEY_F9, CATEGORY)
		: null;

	static {
		// 未登録のカテゴリだとキー設定画面のソートで NPE になるので末尾に登録する
		Map<String, Integer> order = KeyBindingAccessor.savestate$getCategoryOrderMap();
		if (!order.containsKey(CATEGORY)) {
			order.put(CATEGORY, order.isEmpty() ? 1 : Collections.max(order.values()) + 1);
		}
	}

	private SavestateKeys() {
	}

	public static KeyBinding[] all() {
		return DETCHECK != null
			? new KeyBinding[] { SAVE, LOAD, NEXT_SLOT, PREV_SLOT, UNDO, DETCHECK }
			: new KeyBinding[] { SAVE, LOAD, NEXT_SLOT, PREV_SLOT, UNDO };
	}
}
