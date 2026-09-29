package net.naari3.savestate.mixin.rng;

import java.util.List;
import net.minecraft.server.world.ServerChunkManager;
import net.naari3.savestate.rng.RngState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** チャンクの処理順を混ぜる Collections.shuffle(list) は Collections 内部の static Random を使うので、保存できる Random に差し替える。 */
@Mixin(ServerChunkManager.class)
public class ServerChunkManagerRngMixin {
	@Redirect(method = "tickChunks", at = @At(value = "INVOKE", target = "Ljava/util/Collections;shuffle(Ljava/util/List;)V"))
	private void savestate$shuffle(List<?> list) {
		RngState.shuffle(list);
	}
}
