package com.jredis.server.persist;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Exclusive lock on the data directory, so two servers can never write the same files. Directories
 * locked by this JVM are also remembered in a set and checked first: on Linux, opening and closing
 * the LOCK file a second time from the same process would silently drop the first lock.
 */
public final class DataDirLock implements AutoCloseable {

    private static final Set<Path> LOCKED_IN_THIS_JVM = new HashSet<>();

    private final Path key;
    private final FileChannel channel;
    private final FileLock lock;

    private DataDirLock(Path key, FileChannel channel, FileLock lock) {
        this.key = key;
        this.channel = channel;
        this.lock = lock;
    }

    public static DataDirLock acquire(Path dir) throws IOException, DataDirLockedException {
        Files.createDirectories(dir);
        Path key = dir.toRealPath();
        synchronized (LOCKED_IN_THIS_JVM) {
            if (!LOCKED_IN_THIS_JVM.add(key)) {
                throw new DataDirLockedException("data directory " + key + " is in use by another server in this process");
            }
        }
        FileChannel ch = null;
        FileLock l = null;
        try {
            ch = FileChannel.open(dir.resolve("LOCK"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                l = ch.tryLock();
            } catch (OverlappingFileLockException e) {
                l = null;
            }
        } finally {
            if (l == null) {
                if (ch != null) {
                    ch.close();
                }
                synchronized (LOCKED_IN_THIS_JVM) {
                    LOCKED_IN_THIS_JVM.remove(key);
                }
            }
        }
        if (l == null) {
            throw new DataDirLockedException("data directory " + key + " is in use by another process");
        }
        return new DataDirLock(key, ch, l);
    }

    @Override
    public void close() {
        try {
            lock.release();
            channel.close();
        } catch (IOException ignored) {
            // the lock disappears with the process anyway
        } finally {
            synchronized (LOCKED_IN_THIS_JVM) {
                LOCKED_IN_THIS_JVM.remove(key);
            }
        }
    }
}
