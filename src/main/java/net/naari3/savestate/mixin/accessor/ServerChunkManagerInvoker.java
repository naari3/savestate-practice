package net.naari3.savestate.mixin.accessor;

import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ServerChunkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkManager.class)
public interface ServerChunkManagerInvoker {
	@Accessor("ticketManager")
	ChunkTicketManager savestate$getTicketManager();

	/** チケットの変化を読み込みレベルに反映し、チャンクの一覧を更新する (チャンクの tick はしない)。 */
	@Invoker("tick")
	boolean savestate$updateTickets();
}
