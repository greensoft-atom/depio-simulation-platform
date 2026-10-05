package com.jredis.server.core;

import com.jredis.common.Bytes;
import com.jredis.common.NumberCodec;
import com.jredis.common.RespWriter;
import com.jredis.server.command.CommandException;
import com.jredis.server.command.CommandSpec;
import com.jredis.server.config.ServerConfig;
import com.jredis.server.db.Db;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/** Everything a command handler needs: its arguments, the data, and reply/propagation helpers. */
public final class CommandContext {

    private final Engine engine;
    Client client;
    byte[][] argv;
    CommandSpec spec;

    CommandContext(Engine engine, Client client, byte[][] argv, CommandSpec spec) {
        this.engine = engine;
        this.client = client;
        this.argv = argv;
        this.spec = spec;
    }

    public Engine engine() {
        return engine;
    }

    public Db db() {
        return engine.db();
    }

    public ServerConfig config() {
        return engine.config();
    }

    public Client client() {
        return client;
    }

    public CommandSpec spec() {
        return spec;
    }

    /** Wall-clock time of this batch, in epoch milliseconds. */
    public long now() {
        return engine.clock().nowMillis();
    }

    // ------------------------------------------------------------------ arguments

    public int argc() {
        return argv.length;
    }

    public byte[] arg(int i) {
        return argv[i];
    }

    public byte[][] argv() {
        return argv;
    }

    public String argString(int i) {
        return new String(argv[i], StandardCharsets.UTF_8);
    }

    public boolean argIs(int i, String upperKeyword) {
        return Bytes.isKeyword(argv[i], upperKeyword);
    }

    public long longArg(int i) {
        try {
            return NumberCodec.parseLong(argv[i]);
        } catch (NumberFormatException e) {
            throw CommandException.NOT_INTEGER;
        }
    }

    public double doubleArg(int i) {
        try {
            return NumberCodec.parseDouble(argv[i]);
        } catch (NumberFormatException e) {
            throw CommandException.NOT_FLOAT;
        }
    }

    // ------------------------------------------------------------------ replies

    public ByteBuf out() {
        return engine.replyBuffer(client);
    }

    public void ok() {
        out().writeBytes(RespWriter.OK);
    }

    public void simple(String s) {
        RespWriter.simple(out(), s);
    }

    /** Full error text including its prefix, e.g. "ERR syntax error". */
    public void error(String message) {
        RespWriter.error(out(), message);
    }

    public void integer(long v) {
        RespWriter.integer(out(), v);
    }

    public void bool(boolean b) {
        out().writeBytes(b ? RespWriter.ONE : RespWriter.ZERO);
    }

    public void bulk(byte[] b) {
        RespWriter.bulk(out(), b);
    }

    public void bulk(String s) {
        RespWriter.bulk(out(), s);
    }

    public void bulkOrNull(byte[] b) {
        if (b == null) {
            nullBulk();
        } else {
            bulk(b);
        }
    }

    public void bulkDouble(double d) {
        RespWriter.bulk(out(), NumberCodec.formatDoubleBytes(d));
    }

    public void nullBulk() {
        out().writeBytes(RespWriter.NULL_BULK);
    }

    public void arrayHeader(long n) {
        RespWriter.arrayHeader(out(), n);
    }

    public void nullArray() {
        out().writeBytes(RespWriter.NULL_ARRAY);
    }

    public void emptyArray() {
        out().writeBytes(RespWriter.EMPTY_ARRAY);
    }

    // ------------------------------------------------------------------ persistence

    /** Logs this command's effect (the deterministic form, see the AOF effect column in docs/05). */
    public void propagate(byte[]... effect) {
        engine.propagate(effect);
    }

    /** Logs the command exactly as received (it is deterministic given the prior state). */
    public void propagateAsIs() {
        engine.propagate(argv);
    }
}
