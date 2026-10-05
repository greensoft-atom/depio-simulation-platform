package com.jredis.client;

import com.jredis.common.Reply;
import com.jredis.common.RespReplyParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/** Bytes → {@link Reply}s, one per complete RESP reply. */
final class ReplyDecoder extends ByteToMessageDecoder {

    private final RespReplyParser parser = new RespReplyParser();

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        Reply r;
        while ((r = parser.parse(in)) != null) {
            out.add(r);
        }
    }
}
