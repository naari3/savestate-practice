package net.naari3.savestate;

import java.util.Random;
import net.naari3.savestate.rng.StatefulRandom;

/** 調査用のログ。-Dmcsr-savestate.debug=true のときだけ出す。 */
public final class SavestateDebug {
	public static final boolean ENABLED = Boolean.getBoolean("mcsr-savestate.debug");

	private SavestateDebug() {
	}

	public static void log(String format, Object... args) {
		if (ENABLED) {
			SavestateMod.LOGGER.info("[DBG] " + format, args);
		}
	}

	public static String rand(Random random) {
		return random instanceof StatefulRandom ? ((StatefulRandom) random).describe() : "vanilla:" + random.getClass().getSimpleName();
	}
}
