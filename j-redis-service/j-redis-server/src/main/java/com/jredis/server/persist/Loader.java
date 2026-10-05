package com.jredis.server.persist;

import com.jredis.common.Bytes;
import com.jredis.common.RespProtocolException;
import com.jredis.common.RespRequestParser;
import com.jredis.server.core.Client;
import com.jredis.server.core.Engine;
import com.jredis.server.db.Db;
import com.jredis.server.db.KeyEntry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds the dataset from the manifest: the base image, then each incremental file replayed
 * through the normal command handlers in loading mode (no replies, no propagation, and nothing
 * deleted on time grounds, so replay sees exactly what the original run saw).
 */
final class Loader {

    private static final Logger log = LoggerFactory.getLogger(Loader.class);
    private static final int READ_CHUNK = 1 << 20;

    private Loader() {
    }

    static final class Result {
        long baseKeys;
        long commands;
        long errors;
    }

    /**
     * @param mayTruncateLast whether a truncated final record of the last incr file may be cut off
     */
    static Result load(Path dir, Manifest m, Engine engine, boolean mayTruncateLast) throws DataLoadException {
        Result r = new Result();
        Db db = engine.db();
        db.loading(true);
        try {
            if (m.base != null) {
                Path base = dir.resolve(m.base);
                try {
                    r.baseKeys = BaseFormat.read(base, db.hasher(), (type, key, expireAt, value) -> {
                        if (db.peek(key) != null) {
                            throw new DataLoadException(base + ": duplicate key " + Bytes.printable(key, 64));
                        }
                        KeyEntry e = db.add(key, type, value);
                        if (expireAt >= 0) {
                            db.setExpire(e, expireAt);
                        }
                    });
                } catch (IOException e) {
                    throw new DataLoadException("cannot read " + base + ": " + e, e);
                }
            }
            Client loader = engine.newLoadingClient();
            for (int i = 0; i < m.incrs.size(); i++) {
                boolean last = i == m.incrs.size() - 1;
                replay(dir.resolve(m.incrs.get(i)), engine, loader, last && mayTruncateLast, r);
            }
            if (loader.reply != null) {
                loader.reply.release();
                loader.reply = null;
            }
        } finally {
            db.loading(false);
        }
        engine.stats().aofLoadErrors += r.errors;
        return r;
    }

    private static void replay(Path file, Engine engine, Client loader, boolean mayTruncate, Result r) throws DataLoadException {
        RespRequestParser parser = new RespRequestParser(Integer.MAX_VALUE - 16, 0, false);
        ByteBuf buf = Unpooled.buffer(READ_CHUNK);
        long fileSize;
        long readTotal = 0;
        long goodOffset = 0;          // end of the last fully applied command or transaction
        List<byte[][]> tx = null;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            fileSize = ch.size();
            boolean eof = false;
            while (true) {
                byte[][] argv;
                try {
                    argv = parser.parse(buf);
                } catch (RespProtocolException e) {
                    long at = readTotal - buf.readableBytes();
                    throw new DataLoadException("corrupt AOF " + file + " near offset " + at + ": " + e.getMessage()
                            + ". Inspect it with: j-redis-check-aof " + file);
                }
                if (argv == null) {
                    if (eof) {
                        break;
                    }
                    buf.discardReadBytes();
                    buf.ensureWritable(READ_CHUNK);
                    int n = buf.writeBytes(ch, READ_CHUNK);
                    if (n < 0) {
                        eof = true;
                    } else {
                        readTotal += n;
                    }
                    continue;
                }
                long end = readTotal - buf.readableBytes();
                if (argv.length == 0) {
                    continue;
                }
                if (Bytes.isKeyword(argv[0], "MULTI")) {
                    if (tx != null) {
                        throw new DataLoadException("corrupt AOF " + file + ": nested MULTI near offset " + end);
                    }
                    tx = new ArrayList<>();
                } else if (Bytes.isKeyword(argv[0], "EXEC")) {
                    if (tx == null) {
                        throw new DataLoadException("corrupt AOF " + file + ": EXEC without MULTI near offset " + end);
                    }
                    for (byte[][] cmd : tx) {
                        execute(engine, loader, cmd, file, r);
                    }
                    tx = null;
                    goodOffset = end;
                } else if (tx != null) {
                    tx.add(argv);
                } else {
                    execute(engine, loader, argv, file, r);
                    goodOffset = end;
                }
            }
        } catch (IOException e) {
            throw new DataLoadException("cannot read " + file + ": " + e, e);
        } finally {
            buf.release();
        }
        boolean truncated = goodOffset < fileSize;
        if (truncated) {
            long lost = fileSize - goodOffset;
            if (!mayTruncate) {
                throw new DataLoadException("AOF " + file + " ends with an incomplete record at offset " + goodOffset
                        + " (" + lost + " bytes). Run j-redis-check-aof " + file + " --fix, or set aof-load-truncated yes");
            }
            log.warn("AOF {} ends with an incomplete record (the process probably stopped mid-write): "
                    + "discarding the last {} bytes from offset {}", file, lost, goodOffset);
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
                ch.truncate(goodOffset);
                ch.force(true);
            } catch (IOException e) {
                throw new DataLoadException("cannot truncate " + file + ": " + e, e);
            }
        }
    }

    private static void execute(Engine engine, Client loader, byte[][] argv, Path file, Result r) throws DataLoadException {
        try {
            engine.executeLoading(loader, argv);
        } catch (IllegalStateException e) {
            throw new DataLoadException("replaying " + file + " failed: " + e.getMessage(), e);
        }
        r.commands++;
        if (loader.reply != null) {
            if (loader.reply.isReadable() && loader.reply.getByte(loader.reply.readerIndex()) == '-') {
                r.errors++;
                if (r.errors <= 10) {
                    log.error("AOF replay of {} returned an error: {}", Bytes.printable(argv[0], 32),
                            loader.reply.toString(java.nio.charset.StandardCharsets.UTF_8).trim());
                }
            }
            loader.reply.clear();
        }
    }
}
