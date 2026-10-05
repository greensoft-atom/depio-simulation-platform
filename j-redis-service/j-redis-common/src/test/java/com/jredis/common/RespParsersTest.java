package com.jredis.common;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RespParsersTest {

    private static ByteBuf buf(String s) {
        return Unpooled.copiedBuffer(s, StandardCharsets.ISO_8859_1);
    }

    private static List<String> strings(byte[][] argv) {
        List<String> out = new ArrayList<>();
        for (byte[] a : argv) {
            out.add(new String(a, StandardCharsets.ISO_8859_1));
        }
        return out;
    }

    private static RespRequestParser requestParser() {
        return new RespRequestParser(64L * 1024 * 1024, RespRequestParser.DEFAULT_MAX_INLINE, true);
    }

    @Test
    void parsesMultibulkAndPipelines() {
        ByteBuf in = buf("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nvalue\r\n*1\r\n$4\r\nPING\r\n");
        RespRequestParser p = requestParser();
        assertThat(strings(p.parse(in))).containsExactly("SET", "k", "value");
        assertThat(strings(p.parse(in))).containsExactly("PING");
        assertThat(p.parse(in)).isNull();
    }

    @Test
    void resumesWhenFedOneByteAtATime() {
        String wire = "*2\r\n$4\r\nECHO\r\n$12\r\nhello\r\nworld\r\n";   // binary-safe payload with CRLF inside
        RespRequestParser p = requestParser();
        ByteBuf in = Unpooled.buffer();
        byte[][] result = null;
        for (byte b : wire.getBytes(StandardCharsets.ISO_8859_1)) {
            assertThat(result).isNull();
            in.writeByte(b);
            result = p.parse(in);
        }
        assertThat(strings(result)).containsExactly("ECHO", "hello\r\nworld");
    }

    @Test
    void parsesInlineCommands() {
        RespRequestParser p = requestParser();
        ByteBuf in = buf("SET greeting \"hello world\"\r\nPING\n\r\n");
        assertThat(strings(p.parse(in))).containsExactly("SET", "greeting", "hello world");
        assertThat(strings(p.parse(in))).containsExactly("PING");
        assertThat(p.parse(in)).isSameAs(RespRequestParser.EMPTY_REQUEST);
    }

    @Test
    void emptyMultibulkIsSkipped() {
        RespRequestParser p = requestParser();
        assertThat(p.parse(buf("*0\r\n"))).isSameAs(RespRequestParser.EMPTY_REQUEST);
        assertThat(p.parse(buf("*-1\r\n"))).isSameAs(RespRequestParser.EMPTY_REQUEST);
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> requestParser().parse(buf("*x\r\n"))).isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> requestParser().parse(buf("*1\r\n+PING\r\n"))).isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> requestParser().parse(buf("*1\r\n$-5\r\n"))).isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> requestParser().parse(buf("*1\r\n$2\r\nabcd"))).isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> requestParser().parse(buf("*1\n$4\r\nPING\r\n"))).isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> requestParser().parse(buf("SET k \"open\r\n"))).isInstanceOf(RespProtocolException.class);
        RespRequestParser small = new RespRequestParser(10, 16, true);
        assertThatThrownBy(() -> small.parse(buf("*1\r\n$11\r\n"))).isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> small.parse(buf("AAAAAAAAAAAAAAAAAAAAAAAAAA"))).isInstanceOf(RespProtocolException.class);
        RespRequestParser noInline = new RespRequestParser(10, 16, false);
        assertThatThrownBy(() -> noInline.parse(buf("PING\r\n"))).isInstanceOf(RespProtocolException.class);
    }

    @Test
    void randomGarbageNeverEscapesAsAnythingButProtocolErrors() {
        Random r = new Random(1);
        for (int i = 0; i < 50_000; i++) {
            byte[] junk = new byte[r.nextInt(64)];
            r.nextBytes(junk);
            if (r.nextBoolean() && junk.length > 0) {
                junk[0] = (byte) "*$+-:".charAt(r.nextInt(5));
            }
            ByteBuf in = Unpooled.wrappedBuffer(junk);
            RespRequestParser p = requestParser();
            try {
                while (p.parse(in) != null && in.isReadable()) {
                    // keep going
                }
            } catch (RespProtocolException expected) {
                // fine
            }
        }
    }

    @Test
    void writerAndReplyParserRoundTrip() {
        ByteBuf out = Unpooled.buffer();
        RespWriter.simple(out, "OK");
        RespWriter.error(out, "WRONGTYPE Operation against a key holding the wrong kind of value");
        RespWriter.integer(out, Long.MIN_VALUE);
        RespWriter.bulk(out, "héllo".getBytes(StandardCharsets.UTF_8));
        RespWriter.nullBulk(out);
        RespWriter.nullArray(out);
        RespWriter.arrayHeader(out, 2);
        RespWriter.arrayHeader(out, 1);
        RespWriter.integer(out, 7);
        RespWriter.arrayHeader(out, 0);

        // feed in random-sized chunks to exercise resumption
        byte[] wire = new byte[out.readableBytes()];
        out.readBytes(wire);
        RespReplyParser p = new RespReplyParser();
        ByteBuf in = Unpooled.buffer();
        List<Reply> replies = new ArrayList<>();
        Random r = new Random(3);
        int pos = 0;
        while (pos < wire.length) {
            int n = Math.min(wire.length - pos, 1 + r.nextInt(4));
            in.writeBytes(wire, pos, n);
            pos += n;
            Reply reply;
            while ((reply = p.parse(in)) != null) {
                replies.add(reply);
            }
        }
        assertThat(replies).hasSize(7);
        assertThat(replies.get(0).asString()).isEqualTo("OK");
        assertThat(((Reply.ErrorReply) replies.get(1)).prefix()).isEqualTo("WRONGTYPE");
        assertThat(replies.get(2).asLong()).isEqualTo(Long.MIN_VALUE);
        assertThat(replies.get(3).asString()).isEqualTo("héllo");
        assertThat(replies.get(4).isNull()).isTrue();
        assertThat(((Reply.NullReply) replies.get(5)).isNullArray()).isTrue();
        Reply nested = replies.get(6);
        assertThat(nested.asList()).hasSize(2);
        assertThat(nested.asList().get(0).asList().get(0).asLong()).isEqualTo(7);
        assertThat(nested.asList().get(1).asList()).isEmpty();
    }

    @Test
    void commandEncodingRoundTripsThroughRequestParser() {
        byte[][] argv = {"ZADD".getBytes(StandardCharsets.US_ASCII), new byte[]{0, 1, '\r', '\n', (byte) 0xff}, new byte[0]};
        ByteBuf out = Unpooled.buffer();
        RespWriter.command(out, argv);
        assertThat(out.readableBytes()).isEqualTo(RespWriter.commandSize(argv));
        byte[][] back = requestParser().parse(out);
        assertThat(back.length).isEqualTo(3);
        for (int i = 0; i < argv.length; i++) {
            assertThat(back[i]).isEqualTo(argv[i]);
        }
    }
}
