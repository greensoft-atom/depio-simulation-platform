package com.jredis.tests;

import com.jredis.common.Reply;
import com.jredis.common.RespReplyParser;
import com.jredis.common.RespWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** A plain blocking RESP socket for protocol-level tests: sends anything, including MULTI or SUBSCRIBE. */
final class RawClient implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final RespReplyParser parser = new RespReplyParser();
    private final ByteBuf buf = Unpooled.buffer();
    private final byte[] chunk = new byte[64 * 1024];

    RawClient(int port) {
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(5_000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Sends one command given as a redis-cli style line and returns the rendered reply. */
    String call(String line) {
        send(line);
        return R.render(read());
    }

    void send(String line) {
        Object[] a = R.args(line);
        byte[][] argv = new byte[a.length][];
        for (int i = 0; i < a.length; i++) {
            argv[i] = (byte[]) a[i];
        }
        ByteBuf b = Unpooled.buffer(RespWriter.commandSize(argv));
        RespWriter.command(b, argv);
        writeRaw(b.array(), b.arrayOffset() + b.readerIndex(), b.readableBytes());
    }

    void writeRaw(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeRaw(b, 0, b.length);
    }

    void writeRaw(byte[] b, int off, int len) {
        try {
            out.write(b, off, len);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Reply read() {
        return read(5_000);
    }

    Reply read(int timeoutMillis) {
        try {
            socket.setSoTimeout(timeoutMillis);
            while (true) {
                Reply r = parser.parse(buf);
                if (r != null) {
                    buf.discardSomeReadBytes();
                    return r;
                }
                int n = in.read(chunk);
                if (n < 0) {
                    throw new IllegalStateException("connection closed by the server");
                }
                buf.writeBytes(chunk, 0, n);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True if nothing arrives within the given time. */
    boolean silentFor(int millis) {
        try {
            read(millis);
            return false;
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof SocketTimeoutException) {
                return true;
            }
            throw e;
        }
    }

    /** True once the server has closed the connection (reads EOF), waiting up to 5 s. */
    boolean closedByServer() {
        try {
            socket.setSoTimeout(5_000);
            while (true) {
                int n = in.read(chunk);
                if (n < 0) {
                    return true;
                }
            }
        } catch (IOException e) {
            return e.getMessage() != null && e.getMessage().contains("reset");
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing
        }
        buf.release();
    }
}
