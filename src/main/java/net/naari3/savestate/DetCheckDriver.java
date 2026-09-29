package net.naari3.savestate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.naari3.savestate.detcheck.DetCheck;

/**
 * 決定論の検査 (クライアント側の手順)。
 *
 * 自動モード (-Dmcsr-savestate.detcheck.world=<ワールドのフォルダ名>):
 *   タイトル画面でそのワールドを開く → 少し待つ → 検査用スロットに save → そのスロットから runs 回 load し、
 *   各回 ticks tick を記録 → 比較してレポートを書く → (exit=true なら) ゲームを終了する。
 * 手動モード (debug 時のみのキー): 今のスロットから同じ手順で load と記録だけを行う。
 *
 * 検査中は操作しないこと (プレイヤーの入力は揃えられない)。
 */
public final class DetCheckDriver {
	private static final String AUTO_WORLD = System.getProperty("mcsr-savestate.detcheck.world");
	private static final int TICKS = Integer.getInteger("mcsr-savestate.detcheck.ticks", 100);
	private static final int RUNS = Integer.getInteger("mcsr-savestate.detcheck.runs", 3);
	private static final boolean EXIT = Boolean.getBoolean("mcsr-savestate.detcheck.exit");
	private static final int AUTO_SLOT = SavestateManager.SLOT_COUNT;
	private static final int SETTLE_TICKS = 40;
	private static final boolean DISTURB = Boolean.getBoolean("mcsr-savestate.detcheck.disturb");
	/** 飛ばしてから元のチャンクの読み込みが外れて保存されるまで待つ tick 数。 */
	private static final int DISTURB_FAR_TICKS = 400;
	private static int disturbTicks;
	private static final int STABLE_TICKS = 100;
	private static int lastChunkCount = -1;
	private static int stableTicks;

	private enum State {
		IDLE, SETTLE, SAVING, RUNNING, DISTURBING
	}

	private static State state = State.IDLE;
	private static boolean autoStarted = false;
	private static int settle;
	private static int slot;
	private static final List<List<Map<String, String>>> results = new ArrayList<>();

	private DetCheckDriver() {
	}

	static void onClientTick(MinecraftClient client) {
		if (SavestateKeys.DETCHECK != null) {
			while (SavestateKeys.DETCHECK.wasPressed()) {
				if (state == State.IDLE && client.world != null && client.getServer() != null) {
					slot = SavestateManager.getCurrentSlot();
					startRuns(client);
				}
			}
		}

		if (state == State.IDLE && AUTO_WORLD != null && !autoStarted && client.currentScreen instanceof TitleScreen) {
			autoStarted = true;
			// ウィンドウが非アクティブでもポーズメニューを開かない (開くと統合サーバーが止まって記録が進まない)
			client.options.pauseOnLostFocus = false;
			SavestateMod.LOGGER.info("[DetCheck] opening world '{}'", AUTO_WORLD);
			state = State.SETTLE;
			settle = 0;
			client.send(() -> client.startIntegratedServer(AUTO_WORLD));
			return;
		}

		switch (state) {
			case SETTLE:
				// 周囲のチャンクの読み込みが続いている間に save すると、回ごとに tick 対象のチャンクが増えてずれる。
				// 読み込み済みのチャンク数が STABLE_TICKS の間変わらなくなるまで待つ
				if (client.player == null || client.getServer() == null) {
					break;
				}
				int chunks = 0;
				for (ServerWorld w : client.getServer().getWorlds()) {
					chunks += w.getChunkManager().getTotalChunksLoadedCount();
				}
				if (chunks != lastChunkCount) {
					lastChunkCount = chunks;
					stableTicks = 0;
				} else {
					stableTicks++;
				}
				if (++settle >= SETTLE_TICKS && stableTicks >= STABLE_TICKS) {
					SavestateMod.LOGGER.info("[DetCheck] chunks settled at {} after {} ticks", chunks, settle);
					state = State.SAVING;
					slot = AUTO_SLOT;
					SavestateMod.LOGGER.info("[DetCheck] saving slot {}", slot);
					SavestateManager.saveSlot(client, slot, () -> startRuns(client));
				}
				break;
			case RUNNING:
				List<Map<String, String>> run = DetCheck.takeFinished();
				if (run != null) {
					results.add(run);
					SavestateMod.LOGGER.info("[DetCheck] run {}/{} recorded ({} ticks)", results.size(), RUNS, run.size());
					if (results.size() < RUNS && DISTURB) {
						startDisturbance(client);
					} else if (results.size() < RUNS) {
						startNextRun(client);
					} else {
						finish(client);
					}
				}
				break;
			case DISTURBING:
				disturbTicks++;
				if (disturbTicks == DISTURB_FAR_TICKS) {
					client.getServer().execute(DetCheckDriver::disturbFar);
				}
				if (disturbTicks >= DISTURB_FAR_TICKS + 40) {
					state = State.RUNNING;
					startNextRun(client);
				}
				break;
			default:
				break;
		}
	}

	/**
	 * かき乱し (-Dmcsr-savestate.detcheck.disturb=true): 2 回目以降の復元の前に、取得時に読み込まれていたチャンクを変更し、
	 * プレイヤーを遠くへ飛ばして元のチャンクの読み込みを外させ (ディスクに書かせ)、飛んだ先の新しいチャンクも変更する。
	 * 読み込み済みチャンク以外の復元 (ChunkJournal) が正しければ、それでも復元後の記録は 1 回目と一致するはず。
	 */
	private static void startDisturbance(MinecraftClient client) {
		state = State.DISTURBING;
		disturbTicks = 0;
		client.getServer().execute(() -> {
			ServerPlayerEntity player = client.getServer().getPlayerManager().getPlayerList().get(0);
			ServerWorld world = player.getServerWorld();
			BlockPos base = player.getBlockPos().up(3);
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					world.setBlockState(base.add(dx, 0, dz), Blocks.GLASS.getDefaultState());
				}
			}
			// 落下で死なないように飛行状態にする (能力は復元で戻る)
			player.abilities.allowFlying = true;
			player.abilities.flying = true;
			player.sendAbilitiesUpdate();
			player.teleport(world, player.getX() + 800, 200, player.getZ(), player.yaw, player.pitch);
			SavestateMod.LOGGER.info("[DetCheck] disturbance: placed glass near {} and teleported player away", base);
		});
	}

	private static void disturbFar() {
		MinecraftClient client = MinecraftClient.getInstance();
		ServerPlayerEntity player = client.getServer().getPlayerManager().getPlayerList().get(0);
		ServerWorld world = player.getServerWorld();
		BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, player.getBlockPos());
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				world.setBlockState(top.add(dx, 0, dz), Blocks.GOLD_BLOCK.getDefaultState());
			}
		}
		SavestateMod.LOGGER.info("[DetCheck] disturbance: placed gold near {} (new chunks)", top);
	}

	private static void startRuns(MinecraftClient client) {
		results.clear();
		state = State.RUNNING;
		startNextRun(client);
	}

	private static void startNextRun(MinecraftClient client) {
		DetCheck.arm(TICKS);
		SavestateMod.LOGGER.info("[DetCheck] run {}/{}: loading slot {}", results.size() + 1, RUNS, slot);
		if (!SavestateManager.loadSlot(client, slot)) {
			SavestateMod.LOGGER.error("[DetCheck] could not start load of slot {}", slot);
			state = State.IDLE;
		}
	}

	private static void finish(MinecraftClient client) {
		state = State.IDLE;
		String report = DetCheck.compare(results);
		SavestateMod.LOGGER.info("[DetCheck] report:\n{}", report);
		Path out = FabricLoader.getInstance().getGameDir().resolve("savestates").resolve("detcheck-report.txt");
		try {
			Files.createDirectories(out.getParent());
			Files.write(out, report.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			SavestateMod.LOGGER.error("[DetCheck] failed to write report", e);
		}
		if (client.inGameHud != null) {
			client.inGameHud.setOverlayMessage(new LiteralText("[DetCheck] done: " + out.getFileName()), false);
		}
		if (EXIT) {
			client.scheduleStop();
		}
	}
}
