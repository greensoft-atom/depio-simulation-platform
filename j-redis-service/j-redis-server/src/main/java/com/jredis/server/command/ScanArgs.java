package com.jredis.server.command;

import com.jredis.server.core.CommandContext;

/** Parsed {@code cursor [MATCH pattern] [COUNT count] [TYPE type]} arguments of the SCAN family. */
final class ScanArgs {

    final long cursor;
    final byte[] match;
    final long count;
    final String type;

    private ScanArgs(long cursor, byte[] match, long count, String type) {
        this.cursor = cursor;
        this.match = match;
        this.count = count;
        this.type = type;
    }

    /**
     * @param cursorIdx index of the cursor argument
     * @param allowType whether TYPE is accepted (only SCAN)
     */
    static ScanArgs parse(CommandContext ctx, int cursorIdx, boolean allowType) {
        long cursor;
        try {
            cursor = Long.parseUnsignedLong(ctx.argString(cursorIdx));
        } catch (NumberFormatException e) {
            throw new CommandException("ERR invalid cursor");
        }
        byte[] match = null;
        long count = 10;
        String type = null;
        int i = cursorIdx + 1;
        while (i < ctx.argc()) {
            if (i + 1 >= ctx.argc()) {
                throw CommandException.SYNTAX;
            }
            if (ctx.argIs(i, "MATCH")) {
                match = ctx.arg(i + 1);
                if (match.length == 1 && match[0] == '*') {
                    match = null;
                }
            } else if (ctx.argIs(i, "COUNT")) {
                count = ctx.longArg(i + 1);
                if (count < 1) {
                    throw CommandException.SYNTAX;
                }
            } else if (allowType && ctx.argIs(i, "TYPE")) {
                type = ctx.argString(i + 1).toLowerCase(java.util.Locale.ROOT);
            } else {
                throw CommandException.SYNTAX;
            }
            i += 2;
        }
        return new ScanArgs(cursor, match, Math.min(count, 1_000_000), type);
    }

    static void replyCursor(CommandContext ctx, long cursor) {
        ctx.arrayHeader(2);
        ctx.bulk(Long.toUnsignedString(cursor));
    }
}
