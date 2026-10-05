package com.jredis.server.repl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** An image's end found wherever the reads split it ([16 §5.2](../../../../../../../../docs/16-replication.md)). */
class EndMarkTest {

    private static final byte[] MARK = "0123456789abcdef0123456789abcdef01234567".getBytes(StandardCharsets.US_ASCII);

    /** Feeds the stream in the given pieces, as the link does, and returns the image and what followed. */
    private static byte[][] feed(byte[] stream, int... cuts) throws Exception {
        EndMark end = new EndMark(MARK);
        ByteBuf acc = Unpooled.buffer();
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        int from = 0;
        boolean found = false;
        int[] bounds = Arrays.copyOf(cuts, cuts.length + 1);
        bounds[cuts.length] = stream.length;
        for (int to : bounds) {
            acc.writeBytes(stream, from, to - from);
            from = to;
            if (!found) {
                found = end.scan(acc, (buf, n) -> {
                    byte[] b = new byte[n];
                    buf.readBytes(b);
                    image.write(b);
                });
                acc.discardReadBytes();
            }
        }
        assertThat(found).as("the mark was found").isTrue();
        byte[] rest = new byte[acc.readableBytes()];
        acc.readBytes(rest);
        return new byte[][] {image.toByteArray(), rest};
    }

    @Test
    void theImageComesOutWholeWhereverTheReadsSplitIt() throws Exception {
        byte[] img = new byte[300];
        new Random(7).nextBytes(img);
        System.arraycopy(MARK, 0, img, 100, 39);                 // most of the mark, inside the image
        byte[] tail = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] stream = new byte[img.length + MARK.length + tail.length];
        System.arraycopy(img, 0, stream, 0, img.length);
        System.arraycopy(MARK, 0, stream, img.length, MARK.length);
        System.arraycopy(tail, 0, stream, img.length + MARK.length, tail.length);
        for (int a = 1; a < stream.length; a++) {
            byte[][] got = feed(stream, a);
            assertThat(got[0]).as("one cut at %d", a).isEqualTo(img);
            assertThat(got[1]).isEqualTo(tail);
        }
        for (int a = img.length - 5; a < img.length + MARK.length; a++) {
            for (int b = a + 1; b < img.length + MARK.length + 3; b++) {
                byte[][] got = feed(stream, a, b);
                assertThat(got[0]).as("cuts at %d and %d", a, b).isEqualTo(img);
                assertThat(got[1]).isEqualTo(tail);
            }
        }
    }
}
