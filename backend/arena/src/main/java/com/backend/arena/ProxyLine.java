package com.backend.arena;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import com.backend.common.Metrics;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What nginx sends an arena behind the front before the phone's own bytes, one of two:
 * <ul>
 *   <li>from 9001 (D-105), version 1 of the PROXY protocol's line, {@code PROXY TCP4 198.51.100.7 10.0.0.1 51234 9001\r\n};
 *   <li>from 80, for a match without TLS (D-115), its request for the arena by name, {@code GET /match/a1.arena.internal
 *       HTTP/1.1}, with nginx's own headers to the blank line, the phone's address in {@code X-Forwarded-For}; answered
 *       {@code 101 Switching Protocols}, after which nginx passes the bytes both ways.
 * </ul>
 * Read once, the phone's address kept on the channel for the logs, and then out of the pipeline, so no frame after it
 * passes through here. Anything else first is refused: only nginx reaches an arena behind the front, and it always
 * sends one or the other.
 */
final class ProxyLine extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(ProxyLine.class);

    /**
     * The phone's address as nginx gave it: from 9001 {@code 198.51.100.7:51234}, {@code [2001:db8::7]:51234} or
     * {@code unknown}; from 80, without the port, which nginx's HTTP proxy does not pass, {@code 198.51.100.7}.
     */
    static final AttributeKey<String> PHONE = AttributeKey.valueOf("phone");
    /** Version 1's longest line, its CRLF included. */
    static final int MAX_LINE = 107;
    /** 80's request, its line, nginx's four headers and the blank line, with room for a long Host: about 200 bytes. */
    static final int MAX_REQUEST = 1024;

    private static final byte[] START = "PROXY ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] REQUEST_START = "GET /match/".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern REQUEST_LINE = Pattern.compile("GET /match/[A-Za-z0-9.-]+ HTTP/1\\.1");
    private static final byte[] SWITCHING = ("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\n"
            + "Upgrade: backend-match\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
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
        boolean request = in.getByte(from) == 'G';
        byte[] start = request ? REQUEST_START : START;
        int max = request ? MAX_REQUEST : MAX_LINE;
        int seen = Math.min(in.readableBytes(), max);
        // Refused as soon as the bytes so far cannot begin the line or the request, not at the last byte allowed.
        for (int i = 0; i < Math.min(seen, start.length); i++) {
            if (in.getByte(from + i) != start[i]) {
                refuse(ctx, in);
                return;
            }
        }
        int end = request ? endOfHead(in, from, seen) : in.indexOf(from, from + seen, (byte) '\n');
        if (end < 0) {
            if (seen == max) {
                refuse(ctx, in);
            }
            return;                                     // the rest is still to come
        }
        String text = in.readCharSequence(end + 1 - from, StandardCharsets.US_ASCII).toString();
        String address = request ? requestAddress(text) : address(text);
        if (address == null) {
            refuse(ctx, in);
            return;
        }
        ctx.channel().attr(PHONE).set(address);
        if (request) {
            ctx.writeAndFlush(Unpooled.wrappedBuffer(SWITCHING));
        }
        ctx.pipeline().remove(this);                    // what came after goes on as it came
    }

    /** @return the index of the blank line's last byte, the request's end, or -1 when it has not come yet */
    private static int endOfHead(ByteBuf in, int from, int seen) {
        for (int i = from + 3; i < from + seen; i++) {
            if (in.getByte(i) == '\n' && in.getByte(i - 1) == '\r' && in.getByte(i - 2) == '\n' && in.getByte(i - 3) == '\r') {
                return i;
            }
        }
        return -1;
    }

    private void refuse(ChannelHandlerContext ctx, ByteBuf in) {
        dropped.increment("proxy_line");
        log.debug("closing {}: it did not begin with nginx's PROXY line or request", ctx.channel().remoteAddress());
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

    /**
     * @param head 80's request, its line to its blank line
     * @return the phone's address a log names, from nginx's one {@code X-Forwarded-For}, or null when the request is
     *         not nginx's for a match
     */
    static String requestAddress(String head) {
        String[] lines = head.split("\r\n", -1);       // the line, the headers, and the blank line's two empty ends
        if (!head.endsWith("\r\n\r\n") || !REQUEST_LINE.matcher(lines[0]).matches()) {
            return null;
        }
        String address = null;
        for (int i = 1; i < lines.length - 2; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0 && lines[i].substring(0, colon).equalsIgnoreCase("X-Forwarded-For")) {
                if (address != null) {
                    return null;                        // nginx sends one
                }
                address = lines[i].substring(colon + 1).trim();
            }
        }
        if (address == null) {
            return null;
        }
        if (IPV4.matcher(address).matches()) {
            return address;
        }
        return IPV6.matcher(address).matches() ? "[" + address + "]" : null;
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
