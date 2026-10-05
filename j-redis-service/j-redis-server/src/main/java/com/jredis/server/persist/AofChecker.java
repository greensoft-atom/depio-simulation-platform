package com.jredis.server.persist;

import com.jredis.common.Bytes;
import com.jredis.common.RespProtocolException;
import com.jredis.common.RespRequestParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Offline validation of an incremental AOF file (used by j-redis-check-aof). */
public final class AofChecker {

    public static final class Result {
        public final long fileSize;
        public final long goodOffset;
        public final long commands;
        public final String problem;

        Result(long fileSize, long goodOffset, long commands, String problem) {
            this.fileSize = fileSize;
            this.goodOffset = goodOffset;
            this.commands = commands;
            this.problem = problem;
        }

        public boolean ok() {
            return problem == null;
        }
    }

    private AofChecker() {
    }

    public static Result check(Path file) throws IOException {
        RespRequestParser parser = new RespRequestParser(Integer.MAX_VALUE - 16, 0, false);
        ByteBuf buf = Unpooled.buffer(1 << 20);
        long readTotal = 0;
        long good = 0;
        long commands = 0;
        boolean inTx = false;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = ch.size();
            boolean eof = false;
            while (true) {
                byte[][] argv;
                try {
                    argv = parser.parse(buf);
                } catch (RespProtocolException e) {
                    return new Result(size, good, commands, "invalid RESP near offset " + (readTotal - buf.readableBytes()) + ": " + e.getMessage());
                }
                if (argv == null) {
                    if (eof) {
                        break;
                    }
                    buf.discardReadBytes();
                    buf.ensureWritable(1 << 20);
                    int n = buf.writeBytes(ch, 1 << 20);
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
                    if (inTx) {
                        return new Result(size, good, commands, "nested MULTI at offset " + good);
                    }
                    inTx = true;
                } else if (Bytes.isKeyword(argv[0], "EXEC")) {
                    if (!inTx) {
                        return new Result(size, good, commands, "EXEC without MULTI at offset " + good);
                    }
                    inTx = false;
                    good = end;
                } else {
                    commands++;
                    if (!inTx) {
                        good = end;
                    }
                }
            }
            if (good < size) {
                return new Result(size, good, commands, "incomplete record at the end: " + (size - good) + " bytes after offset " + good);
            }
            return new Result(size, good, commands, null);
        } finally {
            buf.release();
        }
    }

    /** Cuts the file at {@code offset}: everything after it is lost. */
    public static void truncate(Path file, long offset) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.truncate(offset);
            ch.force(true);
        }
    }
}
