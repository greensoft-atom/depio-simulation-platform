package com.jredis.server.persist;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Durable file operations. */
public final class FileUtil {

    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private FileUtil() {
    }

    /**
     * Makes a directory entry change (create, rename, delete) durable. Works on Linux; on Windows
     * a directory cannot be opened as a channel, so this is skipped — a weaker guarantee that is
     * acceptable for development machines.
     */
    public static void fsyncDir(Path dir) throws IOException {
        if (WINDOWS) {
            return;
        }
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        }
    }

    public static void writeFully(FileChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    /** Writes a small file atomically: temp file, fsync, rename over the target, fsync directory. */
    static void writeAtomically(Path target, byte[] content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(ch, ByteBuffer.wrap(content));
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        fsyncDir(target.getParent());
    }

    /** Deletes a file, retrying briefly: on Windows a scanner may hold a new file open for a moment. */
    static void deleteQuietly(Path file) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
