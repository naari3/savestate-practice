package net.naari3.savestate;

import net.fabricmc.api.ClientModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.MixinEnvironment;

public class SavestateMod implements ClientModInitializer {
	public static final String MOD_ID = "savestate-practice";
	public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		SavestateKeys.register();
		LOGGER.info("Savestate Practice initialized");
		// 開発時の確認用: 対象クラスが読み込まれるのを待たず、全 Mixin をこの時点で適用して失敗を洗い出す
		if (Boolean.getBoolean("savestate-practice.audit")) {
			MixinEnvironment.getCurrentEnvironment().audit();
			LOGGER.info("Mixin audit finished");
		}
	}
}
