package net.naari3.savestate.mixin.fallback;

import java.util.Map;
import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** key-binding-api がないときだけ適用する (SavestateMixinPlugin)。 */
@Mixin(KeyBinding.class)
public interface KeyBindingCategoryAccessor {
	@Accessor("categoryOrderMap")
	static Map<String, Integer> savestate$getCategoryOrderMap() {
		throw new AssertionError();
	}
}
