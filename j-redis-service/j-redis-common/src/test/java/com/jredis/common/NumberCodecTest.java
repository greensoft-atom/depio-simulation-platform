package com.jredis.common;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NumberCodecTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void acceptsStrictIntegers() {
        assertThat(NumberCodec.parseLong(b("0"))).isEqualTo(0);
        assertThat(NumberCodec.parseLong(b("7"))).isEqualTo(7);
        assertThat(NumberCodec.parseLong(b("-42"))).isEqualTo(-42);
        assertThat(NumberCodec.parseLong(b("9223372036854775807"))).isEqualTo(Long.MAX_VALUE);
        assertThat(NumberCodec.parseLong(b("-9223372036854775808"))).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void rejectsEverythingElse() {
        for (String s : new String[]{"", "+5", "007", "-0", "-", " 1", "1 ", "1.0", "1e3", "0x10",
                "9223372036854775808", "-9223372036854775809", "abc", "12a", "99999999999999999999999"}) {
            assertThat(NumberCodec.isLong(b(s))).as(s).isFalse();
            assertThatThrownBy(() -> NumberCodec.parseLong(b(s))).as(s).isInstanceOf(NumberFormatException.class);
        }
    }

    @Test
    void parsesDoubles() {
        assertThat(NumberCodec.parseDouble(b("1.5"))).isEqualTo(1.5);
        assertThat(NumberCodec.parseDouble(b("-2"))).isEqualTo(-2.0);
        assertThat(NumberCodec.parseDouble(b(".5"))).isEqualTo(0.5);
        assertThat(NumberCodec.parseDouble(b("5."))).isEqualTo(5.0);
        assertThat(NumberCodec.parseDouble(b("1e3"))).isEqualTo(1000.0);
        assertThat(NumberCodec.parseDouble(b("+1E-2"))).isEqualTo(0.01);
        assertThat(NumberCodec.parseDouble(b("inf"))).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(NumberCodec.parseDouble(b("-INF"))).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(NumberCodec.parseDouble(b("+Infinity"))).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void rejectsInvalidDoubles() {
        for (String s : new String[]{"", "nan", "NaN", " 1", "1 ", "1.5d", "1f", "0x10", "1e", "e5", ".",
                "1e999", "1e-999", "--1", "1..2", "Infinityx"}) {
            assertThatThrownBy(() -> NumberCodec.parseDouble(b(s))).as(s).isInstanceOf(NumberFormatException.class);
        }
    }

    @Test
    void formatsDoublesLikeRedis() {
        assertThat(NumberCodec.formatDouble(1.0)).isEqualTo("1");
        assertThat(NumberCodec.formatDouble(-42.0)).isEqualTo("-42");
        assertThat(NumberCodec.formatDouble(1.5)).isEqualTo("1.5");
        assertThat(NumberCodec.formatDouble(0.1)).isEqualTo("0.1");
        assertThat(NumberCodec.formatDouble(12345678.5)).isEqualTo("12345678.5");
        assertThat(NumberCodec.formatDouble(1e20)).isEqualTo("1e+20");
        assertThat(NumberCodec.formatDouble(1.25e-7)).isEqualTo("1.25e-7");
        assertThat(NumberCodec.formatDouble(Double.POSITIVE_INFINITY)).isEqualTo("inf");
        assertThat(NumberCodec.formatDouble(Double.NEGATIVE_INFINITY)).isEqualTo("-inf");
        assertThat(NumberCodec.formatDouble(0.0)).isEqualTo("0");
        assertThat(NumberCodec.formatDouble(-0.0)).isEqualTo("-0");
        assertThat(NumberCodec.formatDouble(1e17)).isEqualTo("100000000000000000");
    }

    @Test
    void formattedDoublesRoundTripExactly() {
        Random r = new Random(42);
        for (int i = 0; i < 200_000; i++) {
            double d;
            switch (i % 4) {
                case 0: d = r.nextDouble(); break;
                case 1: d = r.nextGaussian() * 1e6; break;
                case 2: d = Double.longBitsToDouble(r.nextLong()); break;
                default: d = r.nextInt(100_000) / 100.0; break;
            }
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                continue;
            }
            String s = NumberCodec.formatDouble(d);
            double back;
            try {
                back = NumberCodec.parseDouble(b(s));
            } catch (NumberFormatException e) {
                // only subnormal magnitudes can underflow the strict parser, as in Redis
                assertThat(Math.abs(d)).as(s).isLessThan(Double.MIN_NORMAL);
                continue;
            }
            assertThat(back).as(s).isEqualTo(d);
        }
    }

    @Test
    void writesAsciiWithoutAllocatingStrings() {
        long[] values = {0, 9, 10, 10_000, 10_001, -1, -123456789, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long v : values) {
            ByteBuf buf = Unpooled.buffer();
            NumberCodec.writeAscii(buf, v);
            assertThat(buf.toString(StandardCharsets.US_ASCII)).isEqualTo(Long.toString(v));
            buf.release();
        }
    }

    @Test
    void parsesFullRangeFromBuffers() {
        ByteBuf buf = Unpooled.copiedBuffer("-9223372036854775808", StandardCharsets.US_ASCII);
        assertThat(NumberCodec.parseLong(buf, 0, buf.writerIndex())).isEqualTo(Long.MIN_VALUE);
        buf.release();
    }
}
