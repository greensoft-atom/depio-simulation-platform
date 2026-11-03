package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import com.backend.common.Metrics;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The line nginx sends an arena behind the front before the phone's bytes (D-105). */
class ProxyLineTest {

    private static final String LINE = "PROXY TCP4 198.51.100.7 10.0.0.1 51234 9011\r\n";

    private final Metrics.LabeledCounter dropped = new Metrics.LabeledCounter();

    private static ByteBuf bytes(String text, int... after) {
        ByteBuf b = Unpooled.buffer();
        b.writeCharSequence(text, StandardCharsets.US_ASCII);
        for (int a : after) {
            b.writeByte(a);
        }
        return b;
    }

    private static byte[] received(EmbeddedChannel channel) {
        ByteBuf all = Unpooled.buffer();
        for (ByteBuf b; (b = channel.readInbound()) != null; b.release()) {
            all.writeBytes(b);
        }
        byte[] out = new byte[all.readableBytes()];
        all.readBytes(out);
        return out;
    }

    @Test
    @DisplayName("the line read, its address kept, then the phone's bytes as they came, and the reader gone")
    void aLineThenTheFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyLine(dropped));
        channel.writeInbound(bytes(LINE, 3, 1, 2, 3));
        assertThat(channel.attr(ProxyLine.PHONE).get()).isEqualTo("198.51.100.7:51234");
        assertThat(received(channel)).containsExactly(3, 1, 2, 3);
        assertThat(channel.pipeline().get(ProxyLine.class)).as("out of the pipeline once read").isNull();
        channel.writeInbound(bytes("", 2, 7, 7));
        assertThat(received(channel)).as("later bytes untouched").containsExactly(2, 7, 7);
        assertThat(channel.isOpen()).isTrue();
        assertThat(dropped.get("proxy_line")).isZero();
    }

    @Test
    @DisplayName("a line that arrives in pieces is waited for, and nothing passed before it")
    void aLineInPieces() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyLine(dropped));
        channel.writeInbound(bytes(LINE.substring(0, 3)));
        channel.writeInbound(bytes(LINE.substring(3, 20)));
        assertThat(channel.attr(ProxyLine.PHONE).get()).isNull();
        assertThat(received(channel)).isEmpty();
        assertThat(channel.isOpen()).isTrue();
        channel.writeInbound(bytes(LINE.substring(20), 9));
        assertThat(channel.attr(ProxyLine.PHONE).get()).isEqualTo("198.51.100.7:51234");
        assertThat(received(channel)).containsExactly(9);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "PROXY TCP4 198.51.100.7 10.0.0.1 51234 9011|198.51.100.7:51234",
        "PROXY TCP6 2001:db8::7 2001:db8::1 51234 9011|[2001:db8::7]:51234",
        "PROXY TCP4 198.51.100.7 10.0.0.1 0 9011|198.51.100.7:0",
        "PROXY UNKNOWN|unknown",
        "PROXY UNKNOWN ffff::1 ffff::2 1 2|unknown",
    })
    @DisplayName("the address as a log names it")
    void theAddressRead(String line, String address) {
        assertThat(ProxyLine.address(line + "\r\n")).isEqualTo(address);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\u0003\u0001ab",                                          // a frame: a client that did not come through nginx
        "GET / HTTP/1.1\r\n",
        "PROXY TCP4 198.51.100.7 10.0.0.1 51234\r\n",              // a field short
        "PROXY TCP4 198.51.100.7 10.0.0.1 51234 9011 x\r\n",       // a field over
        "PROXY TCP4 198.51.100.7 10.0.0.1 70000 9011\r\n",         // no such port
        "PROXY TCP4 198.51.100.7 10.0.0.1 -1 9011\r\n",
        "PROXY TCP4 198.51.100.x 10.0.0.1 51234 9011\r\n",         // no address
        "PROXY TCP4 2001:db8::7 10.0.0.1 51234 9011\r\n",          // IPv6 said to be IPv4
        "PROXY TCP5 198.51.100.7 10.0.0.1 51234 9011\r\n",
        "PROXY TCP4 198.51.100.7 10.0.0.1 51234 9011\n",           // no CR
        "proxy TCP4 198.51.100.7 10.0.0.1 51234 9011\r\n",
    })
    @DisplayName("anything but a version 1 line, refused: closed and counted, nothing passed on")
    void notALineRefused(String first) {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyLine(dropped));
        channel.writeInbound(bytes(first, 5, 5));
        assertThat(channel.isOpen()).isFalse();
        assertThat(received(channel)).isEmpty();
        assertThat(channel.attr(ProxyLine.PHONE).get()).isNull();
        assertThat(dropped.get("proxy_line")).isEqualTo(1);
    }

    @Test
    @DisplayName("a line longer than the protocol's 107 bytes, refused before its end comes")
    void aLineTooLongRefused() {
        EmbeddedChannel channel = new EmbeddedChannel(new ProxyLine(dropped));
        channel.writeInbound(bytes("PROXY TCP4 " + "1".repeat(ProxyLine.MAX_LINE)));
        assertThat(channel.isOpen()).isFalse();
        assertThat(dropped.get("proxy_line")).isEqualTo(1);
    }

    @Test
    @DisplayName("what a log names: the phone's address when nginx gave it, the peer's otherwise")
    void thePeerALogNames() {
        EmbeddedChannel told = new EmbeddedChannel(new ProxyLine(dropped));
        told.writeInbound(bytes(LINE));
        assertThat(ProxyLine.peer(told)).isEqualTo("198.51.100.7:51234");
        EmbeddedChannel direct = new EmbeddedChannel();
        assertThat(ProxyLine.peer(direct)).isEqualTo(String.valueOf(direct.remoteAddress()));
    }
}
