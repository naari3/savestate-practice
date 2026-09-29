package net.naari3.savestate.mixin.accessor;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface EntityAccessor {
	@Accessor("MAX_ENTITY_ID")
	static AtomicInteger savestate$getMaxEntityId() {
		throw new AssertionError();
	}
}
