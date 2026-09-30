package net.naari3.savestate;

import java.util.Random;
import net.naari3.savestate.rng.StatefulRandom;

/** 調査用のログ。-Dsavestate-practice.debug=true のときだけ出す。 */
public final class SavestateDebug {
	public static final boolean ENABLED = Boolean.getBoolean("savestate-practice.debug");
	/** 調査用: N 回目の復元 (インメモリ方式) の適用段階で、わざと例外を投げる (0 なら投げない)。 */
	private static final int FAIL_APPLY_AT = Integer.getInteger("savestate-practice.debug.failApply", 0);
	private static int restoreCount;

	public static boolean faultInjectionEnabled() {
		return FAIL_APPLY_AT > 0;
	}

	/** 復元の適用段階から呼ぶ。 */
	public static void maybeInjectFault() {
		if (FAIL_APPLY_AT > 0 && ++restoreCount == FAIL_APPLY_AT) {
			throw new IllegalStateException("[debug] injected failure during restore #" + restoreCount);
		}
	}

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
