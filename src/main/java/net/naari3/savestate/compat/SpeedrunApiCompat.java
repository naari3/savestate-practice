package net.naari3.savestate.compat;

import net.naari3.savestate.SavestateConfig;
import net.naari3.savestate.Settings;

/** SpeedrunAPI があるときだけ読み込まれる (Settings.HAS_SPEEDRUNAPI のときだけ呼ぶ)。 */
public final class SpeedrunApiCompat {
	private SpeedrunApiCompat() {
	}

	public static Settings.Mode mode() {
		return SavestateConfig.get().mode;
	}

	public static int slotCount() {
		return SavestateConfig.get().slotCount;
	}
}
