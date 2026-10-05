package com.backend.arena;

import java.util.concurrent.TimeUnit;

import com.backend.protocol.Wire;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

/** Writing a length-prefixed frame. Safe to call from any thread; Netty does the scheduling. */
final class Frames {

    /** How long a kick's reason has to reach the client before the connection closes regardless. */
    static final long KICK_GRACE_MILLIS = 2_000;

    /** Prefixes the payload with its varint length and writes it. Netty releases the buffer. */
    static void write(Channel ch, byte[] payload, int length) {
        ByteBuf buf = ch.alloc().buffer(length + 5);
        int v = length;
        while ((v & ~0x7F) != 0) {
            buf.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf.writeByte(v);
        buf.writeBytes(payload, 0, length);
        ch.writeAndFlush(buf, ch.voidPromise());
    }

    /**
     * Tells the client why it is being disconnected, then disconnects it.
     *
     * The close waits for the write, because closing first would discard the reason and
     * leave the client unable to tell a rejection from a network fault. It does not wait
     * for ever: a client that has stopped reading never lets the write complete, and the
     * kick alone used to leave its connection open for as long as it liked.
     */
    static void kick(Channel ch, int reason) {
        if (!ch.isActive()) {
            return;
        }
        ByteBuf buf = ch.alloc().buffer(3);
        buf.writeByte(2);                       // frame length
        buf.writeByte(Wire.MSG_KICK);
        buf.writeByte(reason);
        ch.writeAndFlush(buf).addListener(ChannelFutureListener.CLOSE);
        ch.eventLoop().schedule((Runnable) ch::close, KICK_GRACE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private Frames() {
    }
}
