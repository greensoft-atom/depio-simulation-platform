package com.backend.arena;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import com.backend.common.Metrics;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The line nginx sends an arena behind the front before the phone's own bytes (D-105): version 1 of the PROXY
 * protocol, {@code PROXY TCP4 198.51.100.7 10.0.0.1 51234 9011\r\n}. Read once, the phone's address kept on the
 * channel for the logs, and then out of the pipeline, so no frame after it passes through here. Anything else first
 * is refused: only nginx reaches an arena behind the front, and it always sends the line.
 */
final class ProxyLine extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(ProxyLine.class);

    /** The phone's address as nginx gave it: {@code 198.51.100.7:51234}, {@code [2001:db8::7]:51234} or {@code unknown}. */
    static final AttributeKey<String> PHONE = AttributeKey.valueOf("phone");
    /** Version 1's longest line, its CRLF included. */
    static final int MAX_LINE = 107;

    private static final byte[] START = "PROXY ".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern IPV4 = Pattern.compile("(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");
    private static final Pattern PORT = Pattern.compile("\\d{1,5}");

    private final Metrics.LabeledCounter dropped;

    ProxyLine(Metrics.LabeledCounter dropped) {
        this.dropped = dropped;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int from = in.readerIndex();
        int seen = Math.min(in.readableBytes(), MAX_LINE);
        // Refused as soon as the bytes so far cannot begin the line, not at the 107th.
        for (int i = 0; i < Math.min(seen, START.length); i++) {
            if (in.getByte(from + i) != START[i]) {
                refuse(ctx, in);
                return;
            }
        }
        int end = in.indexOf(from, from + seen, (byte) '\n');
        if (end < 0) {
            if (seen == MAX_LINE) {
                refuse(ctx, in);
            }
            return;                                     // the rest of the line is still to come
        }
        String address = address(in.readCharSequence(end + 1 - from, StandardCharsets.US_ASCII).toString());
        if (address == null) {
            refuse(ctx, in);
            return;
        }
        ctx.channel().attr(PHONE).set(address);
        ctx.pipeline().remove(this);                    // what came after the line goes on as it came
    }

    private void refuse(ChannelHandlerContext ctx, ByteBuf in) {
        dropped.increment("proxy_line");
        log.debug("closing {}: it did not begin with nginx's PROXY line", ctx.channel().remoteAddress());
        in.skipBytes(in.readableBytes());
        ctx.close();
    }

    /** @return the address a log names, or null when the line is not version 1's */
    static String address(String line) {
        if (!line.endsWith("\r\n")) {
            return null;
        }
        String[] f = line.substring(0, line.length() - 2).split(" ", -1);
        if (f.length >= 2 && f[0].equals("PROXY") && f[1].equals("UNKNOWN")) {
            return "unknown";                           // what nginx says when it has no IP address to give
        }
        if (f.length != 6 || !f[0].equals("PROXY") || !(f[1].equals("TCP4") || f[1].equals("TCP6"))) {
            return null;
        }
        Pattern ip = f[1].equals("TCP4") ? IPV4 : IPV6;
        if (!ip.matcher(f[2]).matches() || !ip.matcher(f[3]).matches() || !port(f[4]) || !port(f[5])) {
            return null;
        }
        return (f[1].equals("TCP4") ? f[2] : "[" + f[2] + "]") + ":" + f[4];
    }

    private static boolean port(String s) {
        return PORT.matcher(s).matches() && Integer.parseInt(s) <= 65_535;
    }

    /** What a log names for a connection: the phone's address when nginx gave it, the peer's otherwise. */
    static String peer(Channel channel) {
        String phone = channel.attr(PHONE).get();
        return phone != null ? phone : String.valueOf(channel.remoteAddress());
    }
}
