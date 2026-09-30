package net.naari3.savestate;

import me.contaria.speedrunapi.config.api.SpeedrunConfig;
import me.contaria.speedrunapi.config.api.annotations.Config;

/**
 * SpeedrunAPI の設定 (config/mcsr-savestate.json、設定画面は Options の SpeedrunAPI の MOD 一覧から開く)。
 * fabric.mod.json の custom.speedrunapi.config で登録し、SpeedrunAPI がインスタンスを作る。
 */
@SuppressWarnings("FieldMayBeFinal")
public class SavestateConfig implements SpeedrunConfig {
	/** スロット数の上限 (スナップショットの配列の大きさ)。 */
	public static final int MAX_SLOTS = 9;

	private static SavestateConfig instance;

	/** memory: インメモリ方式 (既定)。disk: ワールドのフォルダを複製して開き直す方式。 */
	public Mode mode = Mode.MEMORY;

	/** Next / Previous slot で切り替えるスロットの数。 */
	@Config.Numbers.Whole.Bounds(min = 1, max = MAX_SLOTS)
	public int slotCount = MAX_SLOTS;

	{
		instance = this;
	}

	public static SavestateConfig get() {
		// SpeedrunAPI が作る前に呼ばれた場合は既定値のインスタンスを作る (作ったものが instance になる)
		return instance != null ? instance : new SavestateConfig();
	}

	/** インメモリ方式か。-Dmcsr-savestate.mode=memory|disk があればそちらを優先する (検査用)。 */
	public static boolean memoryMode() {
		String override = System.getProperty("mcsr-savestate.mode");
		if (override != null) {
			return !"disk".equals(override);
		}
		return get().mode == Mode.MEMORY;
	}

	public static int slotCount() {
		return Math.max(1, Math.min(MAX_SLOTS, get().slotCount));
	}

	@Override
	public String modID() {
		return SavestateMod.MOD_ID;
	}

	public enum Mode {
		MEMORY,
		DISK
	}
}
