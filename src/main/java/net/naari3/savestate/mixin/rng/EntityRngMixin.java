package net.naari3.savestate.mixin.rng;

import java.util.Random;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.entity.passive.VillagerEntity;
import net.naari3.savestate.SavestateDebug;
import net.naari3.savestate.detcheck.EntityRandomAccess;
import net.naari3.savestate.rng.StatefulRandom;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entity.random を StatefulRandom にし、その状態をエンティティの NBT に保存する。 */
@Mixin(Entity.class)
public abstract class EntityRngMixin implements EntityRandomAccess {
	private static final String KEY = "savestate-practice:Random";
	/** 改名前 (mcsr-savestate) に保存されたワールドのキー。 */
	private static final String LEGACY_KEY = "mcsr-savestate:Random";

	@Override
	public Random savestate$random() {
		return this.random;
	}

	@Shadow
	@Final
	protected Random random;

	@Redirect(method = "<init>*", at = @At(value = "NEW", target = "()Ljava/util/Random;"))
	private Random savestate$newRandom() {
		return new StatefulRandom();
	}

	@Inject(method = "toTag", at = @At("RETURN"))
	private void savestate$writeRandom(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
		if (this.random instanceof StatefulRandom) {
			tag.put(KEY, ((StatefulRandom) this.random).toTag());
		}
	}

	@Inject(method = "fromTag", at = @At("TAIL"))
	private void savestate$readRandom(CompoundTag tag, CallbackInfo ci) {
		if (this.random instanceof StatefulRandom && tag.contains(KEY, 10)) {
			((StatefulRandom) this.random).fromTag(tag.getCompound(KEY));
		} else if (this.random instanceof StatefulRandom && tag.contains(LEGACY_KEY, 10)) {
			((StatefulRandom) this.random).fromTag(tag.getCompound(LEGACY_KEY));
			if (SavestateDebug.ENABLED && (Object) this instanceof VillagerEntity) {
				Entity self = (Entity) (Object) this;
				SavestateDebug.log("fromTag villager {} restored rand={} thread={}",
					self.getUuidAsString().substring(0, 8), SavestateDebug.rand(this.random), Thread.currentThread().getName());
			}
		}
	}
}
