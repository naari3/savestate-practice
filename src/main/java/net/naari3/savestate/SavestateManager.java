package net.naari3.savestate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.SaveLevelScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.level.storage.LevelStorage;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.naari3.savestate.mixin.accessor.MinecraftServerAccessor;
import net.naari3.savestate.mixin.accessor.SerializingRegionBasedStorageAccessor;
import net.naari3.savestate.detcheck.ThreadedAnvilChunkStorageAccess;
import net.naari3.savestate.detcheck.DetCheck;
import net.naari3.savestate.memory.MemorySnapshot;
import net.naari3.savestate.rng.RngState;

/**
 * フェーズ 1: ディスク方式の savestate / loadstate。
 *
 * save: サーバースレッド上でワールドを保存し、IO の完了を待ってからワールドフォルダをスロットへ複製する。
 * load: ワールドを閉じ (サーバー停止まで待つ)、ワールドフォルダをスロットの内容と入れ替えて開き直す。
 */
public final class SavestateManager {
	private static final String RNG_FILE = "mcsr-savestate-rng.dat";
	/** 方式 (インメモリ / ディスク) は SavestateConfig.memoryMode() で決まる。スロットは 1 から MAX_SLOTS まで。 */
	private static final MemorySnapshot[] memorySlots = new MemorySnapshot[SavestateConfig.MAX_SLOTS + 1];
	/** memorySlots を取ったサーバー。別のワールドを開いたら (サーバーが変わったら) 使わない。 */
	private static IntegratedServer memorySlotServer;
	/** 直前の load の取り消し用 (load する直前の状態)。 */
	private static MemorySnapshot lastUndo;
	private static IntegratedServer lastUndoServer;

	private static int currentSlot = 1;
	private static volatile boolean busy = false;
	private static String pendingMessage = null;

	private SavestateManager() {
	}

	private static boolean loggedKeys = false;

	public static void onClientTick(MinecraftClient client) {
		if (SavestateDebug.ENABLED && !loggedKeys && client.world != null) {
			// 調査用: キーが設定画面と options.txt の対象 (keysAll) に入ったか、lang が読まれたか
			loggedKeys = true;
			SavestateDebug.log("keys registered in options: {}, translated name: {}",
				java.util.Arrays.asList(client.options.keysAll).contains(SavestateKeys.SAVE),
				net.minecraft.client.resource.language.I18n.translate(SavestateKeys.SAVE.getTranslationKey()));
		}
		if (pendingMessage != null && client.player != null) {
			overlay(client, pendingMessage);
			pendingMessage = null;
		}

		boolean inWorld = client.world != null && client.getServer() != null;
		while (SavestateKeys.NEXT_SLOT.wasPressed()) {
			if (inWorld) changeSlot(client, 1);
		}
		while (SavestateKeys.PREV_SLOT.wasPressed()) {
			if (inWorld) changeSlot(client, -1);
		}
		while (SavestateKeys.SAVE.wasPressed()) {
			if (inWorld) save(client);
		}
		while (SavestateKeys.LOAD.wasPressed()) {
			if (inWorld) load(client);
		}
		while (SavestateKeys.UNDO.wasPressed()) {
			if (inWorld && SavestateConfig.memoryMode()) undoLoad(client);
		}

		DetCheckDriver.onClientTick(client);
	}

	private static void changeSlot(MinecraftClient client, int delta) {
		// スロット数を設定で減らした後は、範囲外のスロットから範囲内に戻す
		currentSlot = Math.floorMod(Math.min(currentSlot, SavestateConfig.slotCount()) - 1 + delta, SavestateConfig.slotCount()) + 1;
		IntegratedServer server = client.getServer();
		boolean exists = SavestateConfig.memoryMode()
			? memorySlotServer == server && memorySlots[currentSlot] != null
			: server != null && Files.isDirectory(slotDir(worldDirName(server), currentSlot));
		overlay(client, "Slot " + currentSlot + (exists ? "" : " (empty)"));
	}

	private static void save(MinecraftClient client) {
		saveSlot(client, currentSlot, null);
	}

	/** onSuccess はクライアントスレッドで呼ばれる。 */
	public static void saveSlot(MinecraftClient client, int slot, Runnable onSuccess) {
		if (SavestateConfig.memoryMode()) {
			saveMemory(client, slot, onSuccess);
		} else {
			saveDisk(client, slot, onSuccess);
		}
	}

	private static void saveMemory(MinecraftClient client, int slot, Runnable onSuccess) {
		IntegratedServer server = client.getServer();
		if (server == null || busy) {
			return;
		}
		busy = true;
		overlay(client, "Saving slot " + slot + "...");
		long start = System.nanoTime();
		server.submit(() -> MemorySnapshot.capture(server)).whenComplete((snap, t) -> client.execute(() -> {
			busy = false;
			if (t != null) {
				SavestateMod.LOGGER.error("Failed to save state to memory slot {}", slot, t);
				overlayError(client, "Failed to save slot " + slot + " (see log)");
				return;
			}
			if (memorySlotServer != server) {
				// 別のワールドのスナップショットは使えないので捨てる (記録も止める)
				for (int i = 0; i < memorySlots.length; i++) {
					if (memorySlots[i] != null) {
						memorySlots[i].dispose();
						memorySlots[i] = null;
					}
				}
			} else if (memorySlots[slot] != null) {
				memorySlots[slot].dispose();
			}
			memorySlots[slot] = snap;
			memorySlotServer = server;
			long ms = (System.nanoTime() - start) / 1_000_000L;
			overlay(client, "Saved slot " + slot + " (" + ms + " ms)");
			if (onSuccess != null) {
				onSuccess.run();
			}
		}));
	}

	private static boolean loadMemory(MinecraftClient client, int slot) {
		IntegratedServer server = client.getServer();
		if (server == null || busy) {
			return false;
		}
		MemorySnapshot snap = memorySlotServer == server ? memorySlots[slot] : null;
		if (snap == null) {
			overlayError(client, "Slot " + slot + " is empty");
			return false;
		}
		restoreMemory(client, server, snap, "slot " + slot);
		return true;
	}

	/** 直前の load (インメモリ方式) を取り消し、load する直前の状態に戻す。 */
	private static void undoLoad(MinecraftClient client) {
		IntegratedServer server = client.getServer();
		if (server == null || busy) {
			return;
		}
		MemorySnapshot undo = lastUndoServer == server ? lastUndo : null;
		if (undo == null) {
			overlayError(client, "Nothing to undo");
			return;
		}
		// 取り消しの取り消しもできるように、ここでも取り消し用のスナップショットを取る (restoreMemory が lastUndo を入れ替える)
		restoreMemory(client, server, undo, "undo");
	}

	/**
	 * snap を復元する。復元の直前の状態を取り消し用のスナップショットとして取り、lastUndo に入れる。
	 * 復元の途中で失敗した場合は、MemorySnapshot 側で取り消し用のスナップショットに戻される。
	 */
	private static void restoreMemory(MinecraftClient client, IntegratedServer server, MemorySnapshot snap, String label) {
		busy = true;
		overlay(client, "Loading " + label + "...");
		long start = System.nanoTime();
		server.submit(() -> {
			MemorySnapshot undo = snap.restore(server, true);
			DetCheck.onResumeImmediate(server);
			return undo;
		}).whenComplete((undo, t) -> client.execute(() -> {
			busy = false;
			if (t != null) {
				SavestateMod.LOGGER.error("Failed to load state ({})", label, t);
				overlayError(client, "Failed to load " + label + " (see log)");
				DetCheckDriver.onLoadFailed(client);
				return;
			}
			MemorySnapshot old = lastUndo;
			lastUndo = undo;
			lastUndoServer = server;
			// 取り消し用のスナップショットを捨てる。ただし、スロットや今入れたものと同じなら捨てない
			if (old != null && old != undo && old != snap && !isSlotted(old)) {
				old.dispose();
			}
			long ms = (System.nanoTime() - start) / 1_000_000L;
			overlay(client, "Loaded " + label + " (" + ms + " ms)");
		}));
	}

	private static boolean isSlotted(MemorySnapshot s) {
		for (MemorySnapshot m : memorySlots) {
			if (m == s) {
				return true;
			}
		}
		return false;
	}

	private static void saveDisk(MinecraftClient client, int slot, Runnable onSuccess) {
		IntegratedServer server = client.getServer();
		if (server == null || busy) {
			return;
		}
		busy = true;
		overlay(client, "Saving slot " + slot + "...");
		long start = System.nanoTime();
		server.submit(() -> {
			try {
				saveOnServerThread(server, slot);
				return null;
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}).whenComplete((v, t) -> client.execute(() -> {
			busy = false;
			if (t != null) {
				SavestateMod.LOGGER.error("Failed to save state to slot {}", slot, t);
				overlayError(client, "Failed to save slot " + slot + " (see log)");
			} else {
				long ms = (System.nanoTime() - start) / 1_000_000L;
				overlay(client, "Saved slot " + slot + " (" + ms + " ms)");
				if (onSuccess != null) {
					onSuccess.run();
				}
			}
		}));
	}

	/** サーバースレッドで実行する。この間ワールドは進まない。 */
	private static void saveOnServerThread(IntegratedServer server, int slot) throws IOException {
		server.getPlayerManager().saveAllPlayerData();
		server.save(true, true, true);

		// save(flush=true) はチャンク用の IO worker しか待たない。POI は別の worker が非同期で書くので明示的に待つ
		for (ServerWorld world : server.getWorlds()) {
			ThreadedAnvilChunkStorage tacs = world.getChunkManager().threadedAnvilChunkStorage;
			PointOfInterestStorage poi = ((ThreadedAnvilChunkStorageAccess) tacs).savestate$getPointOfInterestStorage();
			((SerializingRegionBasedStorageAccessor) poi).savestate$getWorker().completeAll().join();
		}

		Path worldDir = worldDir(server);
		Path dst = slotDir(worldDirName(server), slot);
		Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
		Path old = dst.resolveSibling(dst.getFileName() + ".old");

		WorldFiles.deleteRecursively(tmp);
		WorldFiles.deleteRecursively(old);
		Files.createDirectories(dst.getParent());
		WorldFiles.copyWorld(worldDir, tmp);
		// 保存から複製までの間はサーバースレッドを止めているので、ここで取る RNG 状態はワールドのファイルと同じ時点のもの
		try (OutputStream out = Files.newOutputStream(tmp.resolve(RNG_FILE))) {
			NbtIo.writeCompressed(RngState.capture(server), out);
		}
		if (Files.exists(dst)) {
			WorldFiles.moveWithRetry(dst, old);
		}
		WorldFiles.moveWithRetry(tmp, dst);
		WorldFiles.deleteRecursively(old);
	}

	private static void load(MinecraftClient client) {
		loadSlot(client, currentSlot);
	}

	/** load を開始できたら true。 */
	public static boolean loadSlot(MinecraftClient client, int slot) {
		return SavestateConfig.memoryMode() ? loadMemory(client, slot) : loadDisk(client, slot);
	}

	private static boolean loadDisk(MinecraftClient client, int slot) {
		IntegratedServer server = client.getServer();
		if (server == null || busy) {
			return false;
		}
		String dirName = worldDirName(server);
		Path worldDir = worldDir(server);
		Path src = slotDir(dirName, slot);
		if (!Files.isDirectory(src)) {
			overlayError(client, "Slot " + slot + " is empty");
			return false;
		}
		busy = true;
		overlay(client, "Loading slot " + slot + "...");
		// tick の途中でワールドを閉じないよう、タスクキューに積んで tick の外で実行する
		client.send(() -> loadOutsideTick(client, dirName, worldDir, slot, src));
		return true;
	}

	public static int getCurrentSlot() {
		return currentSlot;
	}

	private static void loadOutsideTick(MinecraftClient client, String dirName, Path worldDir, int slot, Path src) {
		// ポーズメニューの「セーブしてタイトルへ」と同じ手順。disconnect はサーバースレッドの終了 (session.lock の解放後) まで待つ
		if (client.world != null) {
			client.world.disconnect();
		}
		client.disconnect(new SaveLevelScreen(new LiteralText("Loading state...")));

		try {
			replaceWorld(worldDir, src);
			Path rngFile = src.resolve(RNG_FILE);
			if (Files.isRegularFile(rngFile)) {
				try (InputStream in = Files.newInputStream(rngFile)) {
					RngState.setPending(NbtIo.readCompressed(in));
				}
			} else {
				RngState.setPending(null);
			}
		} catch (IOException e) {
			busy = false;
			RngState.setPending(null);
			SavestateMod.LOGGER.error("Failed to load state from slot {}", slot, e);
			client.openScreen(new TitleScreen());
			return;
		}

		busy = false;
		pendingMessage = "Loaded slot " + slot;
		client.startIntegratedServer(dirName);
	}

	private static void replaceWorld(Path worldDir, Path src) throws IOException {
		String name = worldDir.getFileName().toString();
		Path tmp = worldDir.resolveSibling(name + ".sstmp");
		Path old = worldDir.resolveSibling(name + ".ssold");

		WorldFiles.deleteRecursively(tmp);
		WorldFiles.deleteRecursively(old);
		WorldFiles.copyWorld(src, tmp);
		WorldFiles.moveWithRetry(worldDir, old);
		try {
			WorldFiles.moveWithRetry(tmp, worldDir);
		} catch (IOException e) {
			// 差し替えに失敗したら元のワールドを戻す
			WorldFiles.moveWithRetry(old, worldDir);
			throw e;
		}
		WorldFiles.deleteRecursively(old);
	}

	private static LevelStorage.Session session(IntegratedServer server) {
		return ((MinecraftServerAccessor) server).savestate$getSession();
	}

	private static Path worldDir(IntegratedServer server) {
		return session(server).getDirectory(WorldSavePath.ROOT).toAbsolutePath().normalize();
	}

	private static String worldDirName(IntegratedServer server) {
		return session(server).getDirectoryName();
	}

	private static Path slotDir(String worldDirName, int slot) {
		return FabricLoader.getInstance().getGameDir().resolve("savestates").resolve(worldDirName).resolve("slot" + slot);
	}

	private static void overlay(MinecraftClient client, String message) {
		if (client.inGameHud != null) {
			client.inGameHud.setOverlayMessage(new LiteralText("[Savestate] " + message), false);
		}
	}

	private static void overlayError(MinecraftClient client, String message) {
		if (client.inGameHud != null) {
			client.inGameHud.setOverlayMessage(new LiteralText("[Savestate] " + message).formatted(Formatting.RED), false);
		}
	}
}
