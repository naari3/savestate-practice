package net.naari3.savestate.mixin.rng;

import net.minecraft.network.ClientConnection;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.naari3.savestate.rng.RngState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * loadstate で開き直したワールドの RNG は、プレイヤーが入った時点で戻す。
 * それまでの間 (スポーン周辺の準備、ログイン処理) に消費される分はタイミング次第で変わるので、なるべく遅く適用する。
 */
@Mixin(PlayerManager.class)
public class PlayerManagerRngMixin {
	@Inject(method = "onPlayerConnect", at = @At("TAIL"))
	private void savestate$applyRng(ClientConnection connection, ServerPlayerEntity player, CallbackInfo ci) {
		RngState.applyPending(((PlayerManager) (Object) this).getServer());
	}
}
