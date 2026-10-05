package com.jredis.server.net;

import com.jredis.common.RespProtocolException;
import com.jredis.common.RespRequestParser;
import com.jredis.server.config.ServerConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/** Turns bytes into requests ({@code byte[][]}); malformed input raises RespProtocolException. */
final class RequestDecoder extends ByteToMessageDecoder {

    private final RespRequestParser parser;
    private final ServerConfig config;

    RequestDecoder(ServerConfig config) {
        this.config = config;
        this.parser = new RespRequestParser(config.protoMaxBulkLen(), RespRequestParser.DEFAULT_MAX_INLINE, true);
    }

    private boolean failed;

    /**
     * After the first protocol error everything else is read and dropped: nothing more is executed,
     * no second error is reported, and reading on means the connection closes with a FIN rather
     * than a reset that could destroy replies still owed for earlier commands.
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (failed) {
            in.skipBytes(in.readableBytes());
            return;
        }
        parser.maxBulkLen(config.protoMaxBulkLen());           // CONFIG SET applies to open connections too
        try {
            while (true) {
                if (in.readableBytes() > config.clientQueryBufferLimit()) {
                    throw new RespProtocolException("query buffer limit exceeded (client-query-buffer-limit)");
                }
                byte[][] argv = parser.parse(in);
                if (argv == null) {
                    return;
                }
                if (argv.length > 0) {
                    out.add(argv);
                }
            }
        } catch (RespProtocolException e) {
            failed = true;
            in.skipBytes(in.readableBytes());
            throw e;
        }
    }
}
