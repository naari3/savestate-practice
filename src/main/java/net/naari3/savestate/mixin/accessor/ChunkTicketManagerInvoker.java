package net.naari3.savestate.mixin.accessor;

import net.minecraft.server.world.ChunkTicketManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkTicketManager.class)
public interface ChunkTicketManagerInvoker {
	/** 期限付きのチケット (同期読み込みのチケットなど) の期限を進め、切れたものを外す。 */
	@Invoker("purge")
	void savestate$purge();

	@Invoker("getTicket")
	String savestate$getTicket(long pos);
}
