package net.naari3.savestate;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

final class WorldFiles {
	private static final String SESSION_LOCK = "session.lock";

	private WorldFiles() {
	}

	/** dst は存在しないこと。 */
	static void copyWorld(Path src, Path dst) throws IOException {
		Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Path rel = src.relativize(file);
				if (rel.getNameCount() == 1 && rel.toString().equals(SESSION_LOCK)) {
					return FileVisitResult.CONTINUE;
				}
				Files.copy(file, dst.resolve(rel.toString()), StandardCopyOption.COPY_ATTRIBUTES);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	static void deleteRecursively(Path path) throws IOException {
		if (!Files.exists(path)) {
			return;
		}
		Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc != null) {
					throw exc;
				}
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Windows ではウイルス対策ソフトなどが一時的にファイルを掴んで rename が失敗することがあるので、何度か再試行する。
	 */
	static void moveWithRetry(Path from, Path to) throws IOException {
		IOException last = null;
		for (int i = 0; i < 20; i++) {
			try {
				Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
				return;
			} catch (IOException e) {
				last = e;
				try {
					Thread.sleep(50);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		throw last;
	}
}
