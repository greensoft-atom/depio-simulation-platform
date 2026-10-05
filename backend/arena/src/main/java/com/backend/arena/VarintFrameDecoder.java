package com.backend.arena;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

/**
 * Splits the TCP stream on a LEB128 varint length prefix
 * (docs detailed-design/02-networking.md §1).
 *
 * Netty's LengthFieldBasedFrameDecoder only understands fixed-width lengths. A varint
 * costs one byte for anything under 128, which is nearly every message here, so it is
 * worth the small amount of custom code.
 */
public final class VarintFrameDecoder extends ByteToMessageDecoder {

    private final int maxFrameLength;

    public VarintFrameDecoder(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        in.markReaderIndex();

        long length = 0;
        int shift = 0;
        while (true) {
            if (!in.isReadable()) {
                in.resetReaderIndex();        // length itself is incomplete; wait for more
                return;
            }
            int b = in.readUnsignedByte();
            length |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 35) {
                throw new CorruptedFrameException("length varint too long");
            }
        }

        if (length < 0 || length > maxFrameLength) {
            // Do not reset: the stream is unusable, and resetting would spin on it.
            throw new CorruptedFrameException("frame length " + length + " exceeds " + maxFrameLength);
        }
        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }
        out.add(in.readRetainedSlice((int) length));
    }
}
