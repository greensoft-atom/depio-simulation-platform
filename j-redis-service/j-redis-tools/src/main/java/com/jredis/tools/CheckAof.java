package com.jredis.tools;

import com.jredis.server.db.SipHash;
import com.jredis.server.persist.AofChecker;
import com.jredis.server.persist.BaseFormat;
import com.jredis.server.persist.DataDirLock;
import com.jredis.server.persist.DataDirLockedException;
import com.jredis.server.persist.DataLoadException;
import com.jredis.server.persist.Manifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * {@code check-aof [--fix] <data-dir | file.aof>}
 *
 * <p>For a data directory: verifies the manifest, the base file (CRC) and every incremental file.
 * Only the last incremental file may end in a torn record (a crash mid-write); {@code --fix} cuts
 * it at the last complete command. Anything else is real corruption and is reported, not fixed.
 * Exit code 0 = healthy, 1 = problem found (or fixed), 2 = usage.
 */
final class CheckAof {

    private CheckAof() {
    }

    static int run(String[] args) throws Exception {
        boolean fix = false;
        Path target = null;
        for (String a : args) {
            if (a.equals("--fix")) {
                fix = true;
            } else if (a.startsWith("-")) {
                throw new Tools.UsageException("usage: check-aof [--fix] <data-dir | file.aof>");
            } else {
                target = Paths.get(a);
            }
        }
        if (target == null) {
            throw new Tools.UsageException("usage: check-aof [--fix] <data-dir | file.aof>");
        }
        if (!Files.isDirectory(target)) {
            return checkSingleFile(target, fix);
        }
        DataDirLock lock;
        try {
            lock = DataDirLock.acquire(target);          // refuses while a server uses the directory
        } catch (DataDirLockedException e) {
            System.err.println(e.getMessage() + " - stop the server before checking its files.");
            return 1;
        }
        try {
            return checkDir(target, fix);
        } finally {
            lock.close();
        }
    }

    /**
     * One incr file. With --fix the file's directory must not be in use by a server (the LOCK), and
     * the file must be the last one in that directory's manifest: only its tail may be torn.
     */
    private static int checkSingleFile(Path file, boolean fix) throws IOException {
        if (!fix) {
            return checkIncr(file, true, false) ? 0 : 1;
        }
        Path dir = file.toAbsolutePath().getParent();
        DataDirLock lock;
        try {
            lock = DataDirLock.acquire(dir);
        } catch (DataDirLockedException e) {
            System.err.println(e.getMessage() + " - stop the server before fixing its files.");
            return 1;
        }
        try {
            Path manifestFile = dir.resolve(Manifest.FILE);
            if (Files.exists(manifestFile)) {
                String last = Manifest.read(manifestFile).lastIncr();
                if (!file.getFileName().toString().equals(last)) {
                    System.out.println(file.getFileName() + ": --fix refused: only the last incr file (" + last
                            + ") may end in a torn record");
                    return checkIncr(file, false, false) ? 0 : 1;
                }
            }
            return checkIncr(file, true, true) ? 0 : 1;
        } finally {
            lock.close();
        }
    }

    private static int checkDir(Path dir, boolean fix) throws IOException {
        Path manifestFile = dir.resolve(Manifest.FILE);
        if (!Files.exists(manifestFile)) {
            System.out.println(dir + ": no manifest - the directory holds no data yet.");
            return 0;
        }
        Manifest m = Manifest.read(manifestFile);
        System.out.println("manifest: generation " + m.generation + ", base " + (m.base == null ? "(none)" : m.base)
                + ", incr " + m.incrs);
        boolean healthy = true;
        if (m.base != null) {
            Path base = dir.resolve(m.base);
            try {
                long records = BaseFormat.read(base, SipHash.random(), (type, key, expireAt, value) -> { });
                System.out.println(m.base + ": OK, " + records + " keys, CRC verified");
            } catch (DataLoadException | IOException e) {
                System.out.println(m.base + ": CORRUPT - " + e.getMessage());
                System.out.println("  The base file cannot be repaired; restore the data directory from a backup.");
                healthy = false;
            }
        }
        List<String> incrs = m.incrs;
        for (int i = 0; i < incrs.size(); i++) {
            boolean last = i == incrs.size() - 1;
            healthy &= checkIncr(dir.resolve(incrs.get(i)), last, fix && last);
        }
        System.out.println(healthy ? "Data directory is healthy." : "Problems found (see above).");
        return healthy ? 0 : 1;
    }

    /** @return true if the file is healthy (a fixed file counts as not healthy for the exit code) */
    private static boolean checkIncr(Path file, boolean mayBeTorn, boolean fix) throws IOException {
        if (!Files.exists(file)) {
            System.out.println(file.getFileName() + ": MISSING");
            return false;
        }
        AofChecker.Result r = AofChecker.check(file);
        if (r.ok()) {
            System.out.println(file.getFileName() + ": OK, " + r.commands + " commands, " + r.fileSize + " bytes");
            return true;
        }
        System.out.println(file.getFileName() + ": " + r.problem);
        boolean tornTail = r.problem.startsWith("incomplete record at the end");
        if (!tornTail || !mayBeTorn) {
            System.out.println("  This is not a torn tail; it cannot be fixed safely. Restore from a backup,");
            System.out.println("  or cut the file by hand at offset " + r.goodOffset + " accepting the loss of what follows.");
            return false;
        }
        if (!fix) {
            System.out.println("  A crash during a write leaves this. The server accepts it at startup when");
            System.out.println("  aof-load-truncated is yes; to cut it now run again with --fix ("
                    + (r.fileSize - r.goodOffset) + " bytes would be removed).");
            return false;
        }
        AofChecker.truncate(file, r.goodOffset);
        System.out.println("  FIXED: truncated to " + r.goodOffset + " bytes (" + (r.fileSize - r.goodOffset) + " bytes removed).");
        return false;
    }
}
