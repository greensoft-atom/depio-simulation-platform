package com.jredis.cli;

import com.jredis.common.Reply;
import com.jredis.common.RespReplyParser;
import com.jredis.common.RespWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A plain blocking socket speaking RESP, for the interactive CLI. Unlike the application client it
 * allows every command (MULTI, WATCH, BLPOP, SUBSCRIBE...), because one person drives it in order.
 */
final class RawConnection implements AutoCloseable {

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final RespReplyParser parser = new RespReplyParser();
    private final ByteBuf buf = Unpooled.buffer(64 * 1024);
    private final byte[] chunk = new byte[64 * 1024];

    RawConnection(String host, int port, int timeoutMillis) throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    void send(byte[][] argv) throws IOException {
        ByteBuf b = Unpooled.buffer(RespWriter.commandSize(argv));
        RespWriter.command(b, argv);
        out.write(b.array(), b.arrayOffset() + b.readerIndex(), b.readableBytes());
        out.flush();
    }

    Reply read() throws IOException {
        while (true) {
            Reply r = parser.parse(buf);
            if (r != null) {
                buf.discardSomeReadBytes();
                return r;
            }
            int n = in.read(chunk);
            if (n < 0) {
                throw new IOException("connection closed by server");
            }
            buf.writeBytes(chunk, 0, n);
        }
    }

    Reply call(byte[][] argv) throws IOException {
        send(argv);
        return read();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
