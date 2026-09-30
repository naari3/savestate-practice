package net.naari3.savestate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.naari3.savestate.compat.SpeedrunApiCompat;

/**
 * 設定値の窓口。SpeedrunAPI があれば、その設定 (SavestateConfig、設定画面から変えられる) の値を使う。
 * ない場合は、SpeedrunAPI が書く設定ファイルを起動時に一度だけ読む (なければ既定値)。
 * SpeedrunAPI のクラスはここから直接参照しない (ないときに読み込まれて落ちないように、compat に隔離している)。
 */
public final class Settings {
	/** スロット数の上限 (スナップショットの配列の大きさ)。 */
	public static final int MAX_SLOTS = 9;
	public static final Mode DEFAULT_MODE = Mode.MEMORY;
	public static final int DEFAULT_SLOT_COUNT = MAX_SLOTS;

	public static final boolean HAS_SPEEDRUNAPI = FabricLoader.getInstance().isModLoaded("speedrunapi");

	/** SpeedrunAPI がないときに使う値。 */
	private static Mode fileMode = DEFAULT_MODE;
	private static int fileSlotCount = DEFAULT_SLOT_COUNT;

	private Settings() {
	}

	static void init() {
		if (HAS_SPEEDRUNAPI) {
			SavestateMod.LOGGER.info("Using SpeedrunAPI for settings");
			return;
		}
		// SpeedrunAPI の設定ディレクトリ (config/mcsr) と同じ場所。SpeedrunAPI を入れていた時期の設定をそのまま読める
		Path file = FabricLoader.getInstance().getConfigDir().resolve("mcsr").resolve(SavestateMod.MOD_ID + ".json");
		if (!Files.isRegularFile(file)) {
			SavestateMod.LOGGER.info("SpeedrunAPI is not installed; using default settings");
			return;
		}
		try (Reader reader = Files.newBufferedReader(file)) {
			JsonObject json = new JsonParser().parse(reader).getAsJsonObject();
			JsonElement mode = json.get("mode");
			if (mode != null && mode.isJsonPrimitive()) {
				for (Mode m : Mode.values()) {
					if (m.name().equals(mode.getAsString())) {
						fileMode = m;
					}
				}
			}
			JsonElement slots = json.get("slotCount");
			if (slots != null && slots.isJsonPrimitive() && slots.getAsJsonPrimitive().isNumber()) {
				fileSlotCount = slots.getAsInt();
			}
			SavestateMod.LOGGER.info("SpeedrunAPI is not installed; read settings from {}", file);
		} catch (Exception e) {
			SavestateMod.LOGGER.error("Failed to read {}; using default settings", file, e);
		}
	}

	/** -Dsavestate-practice.mode=memory|disk があればそちらを優先する (検査用)。 */
	public static boolean memoryMode() {
		String override = System.getProperty("savestate-practice.mode");
		if (override != null) {
			return !"disk".equals(override);
		}
		return (HAS_SPEEDRUNAPI ? SpeedrunApiCompat.mode() : fileMode) == Mode.MEMORY;
	}

	public static int slotCount() {
		int count = HAS_SPEEDRUNAPI ? SpeedrunApiCompat.slotCount() : fileSlotCount;
		return Math.max(1, Math.min(MAX_SLOTS, count));
	}

	public enum Mode {
		MEMORY,
		DISK
	}
}
