package net.naari3.savestate;

import com.google.gson.JsonNull;
import java.lang.reflect.Field;
import java.util.function.Predicate;
import me.contaria.speedrunapi.config.SpeedrunConfigAPI;
import me.contaria.speedrunapi.config.api.SpeedrunConfig;
import me.contaria.speedrunapi.config.api.SpeedrunOption;
import me.contaria.speedrunapi.config.api.annotations.Config;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * SpeedrunAPI の設定 (config/mcsr/savestate-practice.json、設定画面は Options の SpeedrunAPI の MOD 一覧から開く)。
 * fabric.mod.json の custom.speedrunapi.config で登録し、SpeedrunAPI がインスタンスを作る。
 * SpeedrunAPI があるときだけ読み込まれる。他のクラスからは Settings と compat.SpeedrunApiCompat を通して使う。
 */
@SuppressWarnings("FieldMayBeFinal")
public class SavestateConfig implements SpeedrunConfig {
	private static SavestateConfig instance;

	/** memory: インメモリ方式 (既定)。disk: ワールドのフォルダを複製して開き直す方式。 */
	public Settings.Mode mode = Settings.DEFAULT_MODE;

	/** Next / Previous slot で切り替えるスロットの数。 */
	@Config.Numbers.Whole.Bounds(min = 1, max = Settings.MAX_SLOTS)
	public int slotCount = Settings.DEFAULT_SLOT_COUNT;

	// キー割り当て。設定画面に並べるための項目で、値はここには持たない (vanilla の操作設定と同じく options.txt に保存する)。
	// 設定ファイルには null として書かれるが、読み込みでは無視する
	@Config.Category("keys")
	@Config.Name("key.savestate-practice.save")
	public InputUtil.Key saveKey;
	@Config.Category("keys")
	@Config.Name("key.savestate-practice.load")
	public InputUtil.Key loadKey;
	@Config.Category("keys")
	@Config.Name("key.savestate-practice.next_slot")
	public InputUtil.Key nextSlotKey;
	@Config.Category("keys")
	@Config.Name("key.savestate-practice.prev_slot")
	public InputUtil.Key prevSlotKey;
	@Config.Category("keys")
	@Config.Name("key.savestate-practice.undo")
	public InputUtil.Key undoKey;

	/** 割り当て待ちのキー (ボタンを押してから、次のキー入力まで)。 */
	@Config.Ignored
	private KeyBinding focusedKey;

	{
		instance = this;
	}

	public static SavestateConfig get() {
		// SpeedrunAPI が作る前に呼ばれた場合は既定値のインスタンスを作る (作ったものが instance になる)
		return instance != null ? instance : new SavestateConfig();
	}

	@Override
	public String modID() {
		return SavestateMod.MOD_ID;
	}

	private static @Nullable KeyBinding keyFor(String fieldName) {
		switch (fieldName) {
			case "saveKey":
				return SavestateKeys.SAVE;
			case "loadKey":
				return SavestateKeys.LOAD;
			case "nextSlotKey":
				return SavestateKeys.NEXT_SLOT;
			case "prevSlotKey":
				return SavestateKeys.PREV_SLOT;
			case "undoKey":
				return SavestateKeys.UNDO;
			default:
				return null;
		}
	}

	@Override
	public @Nullable SpeedrunOption<?> parseField(Field field, SpeedrunConfig config, String... idPrefix) {
		KeyBinding binding = keyFor(field.getName());
		if (binding != null) {
			return new SpeedrunConfigAPI.CustomOption.Builder<InputUtil.Key>(config, this, field, idPrefix)
				.getter((option, config_, storage, optionField) -> KeyBindingHelper.getBoundKeyOf(binding))
				.setter((option, config_, storage, optionField, value) -> setKey(binding, value))
				.fromJson((option, config_, storage, optionField, json) -> {
				})
				.toJson((option, config_, storage, optionField) -> JsonNull.INSTANCE)
				.createWidget((option, config_, storage, optionField) -> new ButtonWidget(0, 0, 150, 20, binding.getBoundKeyLocalizedText(), button -> this.focusedKey = binding) {
					@Override
					public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
						this.setMessage(keyText(binding, SavestateConfig.this.focusedKey == binding));
						super.render(matrices, mouseX, mouseY, delta);
					}
				})
				.build();
		}
		return SpeedrunConfig.super.parseField(field, config, idPrefix);
	}

	@Override
	public @Nullable Predicate<InputUtil.Key> createInputListener() {
		return key -> {
			if (this.focusedKey == null) {
				return false;
			}
			// vanilla の操作設定と同じく、Esc は割り当ての解除
			if (key.getCategory() == InputUtil.Type.KEYSYM && key.getCode() == GLFW.GLFW_KEY_ESCAPE) {
				key = InputUtil.UNKNOWN_KEY;
			}
			setKey(this.focusedKey, key);
			this.focusedKey = null;
			return true;
		};
	}

	private static void setKey(KeyBinding binding, InputUtil.Key key) {
		// setKeyCode は options.txt への書き込みも行う
		MinecraftClient.getInstance().options.setKeyCode(binding, key);
		KeyBinding.updateKeysByCode();
	}

	private static MutableText keyText(KeyBinding binding, boolean focused) {
		MutableText text = binding.getBoundKeyLocalizedText().shallowCopy();
		if (focused) {
			return new LiteralText("> ").append(text).append(" <").formatted(Formatting.YELLOW);
		}
		if (conflicts(binding)) {
			return text.formatted(Formatting.RED);
		}
		return text;
	}

	/** 同じキーが他の操作 (vanilla や他の MOD のものも含む) に割り当てられているか。 */
	private static boolean conflicts(KeyBinding binding) {
		if (binding.isUnbound()) {
			return false;
		}
		for (KeyBinding other : MinecraftClient.getInstance().options.keysAll) {
			if (other != binding && binding.equals(other)) {
				return true;
			}
		}
		return false;
	}

}
