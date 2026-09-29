package net.naari3.savestate.mixin.accessor;

import java.util.Map;
import net.minecraft.client.options.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
	@Accessor("categoryOrderMap")
	static Map<String, Integer> savestate$getCategoryOrderMap() {
		throw new AssertionError();
	}
}
