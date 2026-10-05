package com.jredis.server.command;

/** A command's name, arity, flags and handler. */
public final class CommandSpec {

    // flags
    public static final int WRITE = 1;
    public static final int READONLY = 1 << 1;
    public static final int DENYOOM = 1 << 2;
    public static final int FAST = 1 << 3;
    public static final int BLOCKING = 1 << 4;
    public static final int PUBSUB = 1 << 5;          // allowed in subscriber mode
    public static final int NOAUTH = 1 << 6;          // allowed before AUTH
    public static final int ADMIN = 1 << 7;
    public static final int NO_MULTI = 1 << 8;        // rejected inside MULTI
    public static final int TX_CONTROL = 1 << 9;      // MULTI/EXEC/DISCARD/WATCH run instead of being queued
    public static final int LOADING_OK = 1 << 10;

    public final String name;
    /** N = exactly N arguments including the name; -N = at least N. */
    public final int arity;
    public final int flags;
    public final CommandHandler handler;
    /**
     * Disabled with {@code disable-command}: clients get "unknown command", but the AOF loader may
     * still replay it (other commands log it as their effect, e.g. FLUSHDB logs FLUSHALL).
     */
    public boolean disabled;
    /** Mutable per-command statistics (command thread only). */
    public final CommandStat stat = new CommandStat();

    public CommandSpec(String name, int arity, int flags, CommandHandler handler) {
        this.name = name;
        this.arity = arity;
        this.flags = flags;
        this.handler = handler;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public boolean arityOk(int argc) {
        return arity >= 0 ? argc == arity : argc >= -arity;
    }
}
