package com.jredis.server.repl;

import com.jredis.server.persist.FileUtil;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The epoch kept in {@code <dir>/replication} (docs/16-replication.md §9, D-37): replaced
 * atomically, as the manifest is, and synced before a promotion is answered.
 */
final class EpochFile {

    static final String NAME = "replication";

    private EpochFile() {
    }

    /** The epoch recorded in the directory, or 0 if none is. */
    static long read(Path dir) throws IOException {
        Path f = dir.resolve(NAME);
        if (!Files.exists(f)) {
            return 0;
        }
        for (String line : Files.readAllLines(f, StandardCharsets.US_ASCII)) {
            if (line.startsWith("epoch ")) {
                return Long.parseLong(line.substring(6).trim());
            }
        }
        throw new IOException(f + " names no epoch");
    }

    static void write(Path dir, long epoch) throws IOException {
        Path tmp = dir.resolve(NAME + ".tmp");
        byte[] text = ("format 1\nepoch " + epoch + "\n").getBytes(StandardCharsets.US_ASCII);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            FileUtil.writeFully(ch, java.nio.ByteBuffer.wrap(text));
            ch.force(true);
        }
        Files.move(tmp, dir.resolve(NAME), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        FileUtil.fsyncDir(dir);
    }
}
