package net.naari3.savestate.mixin.rng;

import java.util.Random;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.naari3.savestate.detcheck.DetCheck;
import net.naari3.savestate.rng.RngState;
import net.naari3.savestate.rng.StatefulRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MinecraftServerRngMixin {
	@Redirect(method = "<init>*", at = @At(value = "NEW", target = "()Ljava/util/Random;"))
	private Random savestate$newRandom() {
		return new StatefulRandom();
	}

	// 保存対象の RNG (親 RNG、Math.random の置き換えなど) を使うのはサーバースレッドだけにする
	@Inject(method = "runServer", at = @At("HEAD"))
	private void savestate$markServerThread(CallbackInfo ci) {
		RngState.setServerThread(Thread.currentThread());
	}

	// loadstate 直後、プレイヤーが入って RNG を適用するまではワールドを進めない。
	// tickWorlds 全体を止めるとネットワーク処理 (ログイン) も止まるので、ServerWorld.tick の呼び出しだけを飛ばす
	@Redirect(method = "tickWorlds", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/ServerWorld;tick(Ljava/util/function/BooleanSupplier;)V"))
	private void savestate$holdWorldTick(ServerWorld world, BooleanSupplier shouldKeepTicking) {
		if (!RngState.isHoldingWorldTicks()) {
			world.tick(shouldKeepTicking);
		}
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void savestate$detCheck(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		DetCheck.onServerTickEnd((MinecraftServer) (Object) this);
	}

	@Inject(method = "shutdown", at = @At("HEAD"))
	private void savestate$clearPending(CallbackInfo ci) {
		RngState.clearPending();
	}
}
