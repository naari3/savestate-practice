package net.naari3.savestate;

import java.util.Collections;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.options.KeyBinding;
import net.naari3.savestate.compat.KeyBindingApiCompat;
import net.naari3.savestate.mixin.fallback.KeyBindingCategoryAccessor;
import org.lwjgl.glfw.GLFW;

/**
 * キー割り当て (表示名は assets/savestate-practice/lang)。
 * key-binding-api (SpeedrunAPI が同梱) があればそれで登録し、なければ代わりの Mixin (mixin.fallback.GameOptionsKeysMixin) で加える。
 */
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
		if (FabricLoader.getInstance().isModLoaded("fabric-key-binding-api-v1")) {
			KeyBindingApiCompat.register(all());
			return;
		}
		// 未登録のカテゴリだと、操作設定の画面の並べ替えで NPE になるので末尾に登録する (キー自体は GameOptionsKeysMixin が加える)
		Map<String, Integer> order = KeyBindingCategoryAccessor.savestate$getCategoryOrderMap();
		if (!order.containsKey(CATEGORY)) {
			order.put(CATEGORY, order.isEmpty() ? 1 : Collections.max(order.values()) + 1);
		}
	}
}
