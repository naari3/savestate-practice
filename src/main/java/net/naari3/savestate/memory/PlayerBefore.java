package net.naari3.savestate.memory;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.network.packet.s2c.play.CloseScreenS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.HeldItemChangeS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.RemoveEntityStatusEffectS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.naari3.savestate.mixin.accessor.ServerPlayerEntityAccessor;

/**
 * 復元前のプレイヤーの状態の一部を覚えておき、書き戻した後の後始末とクライアントへの同期を行う。
 *
 * プレイヤーは接続 (ServerPlayNetworkHandler) に結び付いているので、オブジェクトは差し替えずフィールドに書き戻す。
 * そのため、チャンクの所属やクライアント側の表示は自分で直す必要がある。
 */
final class PlayerBefore {
	private final boolean inChunk;
	private final int chunkX;
	private final int chunkY;
	private final int chunkZ;
	private final int openScreenSyncId;
	private final List<StatusEffectInstance> effects;
	/** チケットの管理側に登録されている、プレイヤーがチャンクを読み込ませている位置。 */
	private final ChunkSectionPos cameraPosition;

	PlayerBefore(ServerPlayerEntity player) {
		this.inChunk = player.updateNeeded;
		this.chunkX = player.chunkX;
		this.chunkY = player.chunkY;
		this.chunkZ = player.chunkZ;
		this.openScreenSyncId = player.currentScreenHandler.syncId;
		this.effects = new ArrayList<>(player.getStatusEffects());
		this.cameraPosition = player.getCameraPosition();
	}

	void afterRestore(ServerPlayerEntity player) {
		ServerWorld world = player.getServerWorld();

		// チャンクの所属: 書き戻しで chunkX などはスナップショットの値になっているが、実際の所属は復元前のチャンクのまま
		if (this.inChunk) {
			Chunk old = world.getChunk(this.chunkX, this.chunkZ, ChunkStatus.FULL, false);
			if (old instanceof WorldChunk) {
				((WorldChunk) old).remove(player, this.chunkY);
			}
		}
		Chunk now = world.getChunk(MathHelper.floor(player.getX() / 16.0), MathHelper.floor(player.getZ() / 16.0), ChunkStatus.FULL, false);
		if (now instanceof WorldChunk) {
			now.addEntity(player);
		}

		// 書き戻しで cameraPosition もスナップショットの値になっているが、チケットの管理側の登録は復元前の位置のまま。
		// 復元前の値に戻してから、今の位置へ移し直す (食い違うと、次に移動したときに登録のない位置から外そうとして落ちる)
		player.setCameraPosition(this.cameraPosition);
		world.getChunkManager().updateCameraPosition(player);

		// 書き戻しで取得時の一覧になっている。取得時にクライアントへ送る予定だった削除は、今のクライアントには関係ない
		// (今回外したものの削除は MemorySnapshot.apply で先に送ってある)
		((ServerPlayerEntityAccessor) player).savestate$getRemovedEntities().clear();

		// スナップショットで開いていた画面 (チェストなど) は再現せず、インベントリに戻す
		if (this.openScreenSyncId != 0) {
			player.networkHandler.sendPacket(new CloseScreenS2CPacket(this.openScreenSyncId));
		}
		player.currentScreenHandler = player.playerScreenHandler;

		player.networkHandler.requestTeleport(player.getX(), player.getY(), player.getZ(), player.yaw, player.pitch);
		player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));

		// 体力・満腹度・経験値は、次の tick で「前回送った値」と比べて送られるので、前回値を捨てておく
		player.markHealthDirty();
		((ServerPlayerEntityAccessor) player).savestate$setSyncedFoodLevel(-1);
		((ServerPlayerEntityAccessor) player).savestate$setSyncedExperience(-1);

		player.onHandlerRegistered(player.playerScreenHandler, player.playerScreenHandler.getStacks());
		player.networkHandler.sendPacket(new HeldItemChangeS2CPacket(player.inventory.selectedSlot));
		// ゲームモードはサーバー側だけ取得時に戻っている。クライアントに知らせないと、クライアントは今のモードのまま食い違い、
		// /gamemode で取得時と同じモードにしようとしても「変わらない」扱いになって直せない。
		// クライアントはゲームモードを受け取ると能力を既定に上書きするので、能力の送り直しより先に送る
		net.minecraft.world.GameMode mode = player.interactionManager.getGameMode();
		player.networkHandler.sendPacket(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.GAME_MODE_CHANGED, mode.getId()));
		player.server.getPlayerManager().sendToAll(new PlayerListS2CPacket(PlayerListS2CPacket.Action.UPDATE_GAME_MODE, player));
		if (mode != net.minecraft.world.GameMode.SPECTATOR && player.getCameraEntity() != player) {
			player.setCameraEntity(player);
		}
		player.sendAbilitiesUpdate();

		for (StatusEffectInstance e : this.effects) {
			player.networkHandler.sendPacket(new RemoveEntityStatusEffectS2CPacket(player.getEntityId(), e.getEffectType()));
		}
		for (StatusEffectInstance e : player.getStatusEffects()) {
			player.networkHandler.sendPacket(new EntityStatusEffectS2CPacket(player.getEntityId(), e));
		}

		// DataTracker (姿勢、燃えているか、など) は複製で入れ替わったので、全項目を送り直す
		world.getChunkManager().sendToNearbyPlayers(player, new EntityTrackerUpdateS2CPacket(player.getEntityId(), player.getDataTracker(), true));
	}
}
