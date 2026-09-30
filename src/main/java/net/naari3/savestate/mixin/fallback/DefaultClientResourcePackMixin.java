package net.naari3.savestate.mixin.fallback;

import net.minecraft.client.resource.DefaultClientResourcePack;
import net.naari3.savestate.SavestateMod;
import org.apache.commons.lang3.ArrayUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * SpeedrunAPI も fabric-resource-loader もないときだけ適用する (SavestateMixinPlugin)。
 * 既定のリソースパックの名前空間にこの MOD のものを加え、assets/savestate-practice (lang) を読ませる。
 * 既定のリソースパックはクラスローダーからリソースを探すので、名前空間を加えるだけで MOD の jar 内のファイルも見つかる。
 */
@Mixin(DefaultClientResourcePack.class)
public class DefaultClientResourcePackMixin {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/resource/DefaultResourcePack;<init>([Ljava/lang/String;)V"))
	private static String[] savestate$addNamespace(String[] namespaces) {
		return ArrayUtils.add(namespaces, SavestateMod.MOD_ID);
	}
}
