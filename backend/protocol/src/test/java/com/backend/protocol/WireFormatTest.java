package com.backend.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The byte-level contract with the client.
 *
 * This module had no tests at all until the audit of 2026-09-23, which is how six defects
 * survived in the snapshot format. Every number here is a value a real client will meet: the
 * boundaries of each encoding, and the quantisation conventions a decoder has to match
 * exactly or render the world wrong.
 */
class WireFormatTest {

    private static WireReader readerOf(SnapshotWriter w) {
        return new WireReader(w.toBytes());
    }

    // ---- varint ---------------------------------------------------------------------------

    @Test
    @DisplayName("varint round-trips at every boundary, and the widths are what the reader allows")
    void varintBoundaries() {
        long[] values = {0, 1, 127, 128, 16_383, 16_384, 2_097_151, 2_097_152,
                Integer.MAX_VALUE, Long.MAX_VALUE};
        for (long v : values) {
            SnapshotWriter w = new SnapshotWriter(16);
            w.varint(v);
            assertThat(readerOf(w).varint()).as("varint %d", v).isEqualTo(v);
        }

        SnapshotWriter one = new SnapshotWriter(16);
        one.varint(127);
        assertThat(one.length()).as("anything under 128 is one byte").isEqualTo(1);

        SnapshotWriter nine = new SnapshotWriter(16);
        nine.varint(Long.MAX_VALUE);
        assertThat(nine.length()).isEqualTo(9);
    }

    @Test
    @DisplayName("a negative varint costs ten bytes, which is why deltas use svarint")
    void negativeVarintIsTenBytes() {
        SnapshotWriter w = new SnapshotWriter(16);
        w.varint(-1);

        // Sign-extended LEB128. The reader accepts exactly this width, so the two agree —
        // but a field that can go negative must be svarint, or every frame pays ten bytes.
        assertThat(w.length()).isEqualTo(10);
        assertThat(readerOf(w).varint()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("a length or a count no frame could hold is refused as a bad frame, not an array allocated or an index overrun (P-51)")
    void aLengthPastTheFrameIsRefused() {
        // A snapshot, then a removal count of 2^40: the reader once allocated an array of it.
        SnapshotWriter w = new SnapshotWriter(32);
        w.u8(Wire.MSG_SNAPSHOT);
        w.varint(1);
        w.varint(0);
        w.svarint(0);
        w.svarint(0);
        w.varint(1L << 40);
        assertThatThrownBy(() -> new SnapshotReader().decode(w.array(), 0, w.length()))
                .isInstanceOf(IllegalStateException.class);
        // A string whose length runs past the frame's end, by far.
        SnapshotWriter s = new SnapshotWriter(16);
        s.varint(Integer.MAX_VALUE + 10L);
        WireReader r = new WireReader();
        r.reset(s.array(), 0, s.length());
        assertThatThrownBy(r::str).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a varint wider than the reader allows is refused, not silently truncated")
    void overlongVarintIsRefused() {
        SnapshotWriter w = new SnapshotWriter(32);
        for (int i = 0; i < 11; i++) {
            w.u8(0x80);                     // eleven continuation bytes and no terminator
        }
        w.u8(0x01);

        assertThatThrownBy(() -> readerOf(w).varint())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too long");
    }

    @Test
    @DisplayName("svarint round-trips negatives cheaply, including the extremes")
    void svarintBoundaries() {
        long[] values = {0, -1, 1, -63, 64, -64, 8_191, -8_192,
                Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE};
        for (long v : values) {
            SnapshotWriter w = new SnapshotWriter(16);
            w.svarint(v);
            assertThat(readerOf(w).svarint()).as("svarint %d", v).isEqualTo(v);
        }

        SnapshotWriter small = new SnapshotWriter(16);
        small.svarint(-1);
        assertThat(small.length()).as("zigzag keeps small negatives to one byte").isEqualTo(1);
    }

    // ---- quantisation: the conventions a decoder must match -------------------------------

    @Test
    @DisplayName("positions are world units times four, and rounding is not symmetric about zero")
    void positionQuantisation() {
        assertThat(Wire.quantisePos(0f)).isZero();
        assertThat(Wire.quantisePos(1f)).isEqualTo(4);
        assertThat(Wire.quantisePos(0.25f)).isEqualTo(1);

        // Math.round is floor(x + 0.5), so +0.625 rounds away from zero and -0.625 towards
        // it. Worth pinning: a vector generated from mirrored inputs will not be symmetric,
        // and a client that assumes it is will chase a phantom off-by-one.
        assertThat(Wire.quantisePos(0.625f)).isEqualTo(3);
        assertThat(Wire.quantisePos(-0.625f)).isEqualTo(-2);
    }

    @Test
    @DisplayName("i16 positions hold a view rectangle, and the limit is where it is claimed to be")
    void positionFieldRange() {
        // Positions travel relative to the view origin, so the i16 bounds the view rect, not
        // the map. This is the arithmetic behind that claim.
        assertThat(Wire.quantisePos(8_191.75f)).isEqualTo(32_767);
        assertThat((short) Wire.quantisePos(8_192f))
                .as("one quarter-unit past the limit wraps to the far end").isEqualTo((short) -32_768);
        // The shipped view is 1 600 units across, so a half-rect is 800 × 4 = 3 200.
        assertThat(Wire.quantisePos(800f)).isEqualTo(3_200);
    }

    @Test
    @DisplayName("heading is a full turn in u16, offset by pi, and aimToRadians is its inverse")
    void headingQuantisation() {
        assertThat(Wire.quantiseHeading((float) -Math.PI)).isZero();
        assertThat(Wire.quantiseHeading(0f)).isEqualTo(32_768);
        assertThat(Wire.quantiseHeading((float) Math.PI))
                .as("+pi and -pi are the same heading").isZero();
        // Outside [-pi, pi] the mask does the modular reduction: -1.5pi is +0.5pi.
        assertThat(Wire.quantiseHeading((float) (-1.5 * Math.PI))).isEqualTo(49_152);
        assertThat(Wire.quantiseHeading((float) (3 * Math.PI))).isZero();

        for (double radians = -Math.PI; radians < Math.PI; radians += 0.05) {
            float back = ClientMessage.aimToRadians(Wire.quantiseHeading((float) radians));
            assertThat(back).as("radians %.3f", radians)
                    .isCloseTo((float) radians, org.assertj.core.data.Offset.offset(0.0002f));
        }
    }

    @Test
    @DisplayName("tank facing is a u8 offset by pi — the same convention as heading, not a bare turn")
    void angleQuantisation() {
        // Pinned because the design document stated this formula WITHOUT the +pi offset,
        // which is exactly 180 degrees out. A client written from the document rendered every
        // tank backwards, and nothing caught it because this module had no tests (P-7).
        assertThat(Wire.quantiseAngle((float) -Math.PI)).isZero();
        assertThat(Wire.quantiseAngle(0f)).isEqualTo(128);
        assertThat(Wire.quantiseAngle((float) (Math.PI / 2))).isEqualTo(192);
        assertThat(Wire.quantiseAngle((float) (-Math.PI / 2))).isEqualTo(64);
        assertThat(Wire.quantiseAngle((float) Math.PI)).as("wraps, does not saturate").isZero();
        // Shapes are spawned with an angle in [0, 2pi), which must reduce correctly.
        assertThat(Wire.quantiseAngle((float) (1.75 * Math.PI))).isBetween(0, 255);
    }

    @Test
    @DisplayName("health is a fraction of maximum, clamped, and a live tank never encodes as dead")
    void healthQuantisation() {
        assertThat(Wire.quantiseHp(100f, 100f)).isEqualTo(255);
        assertThat(Wire.quantiseHp(50f, 100f)).isEqualTo(128);
        assertThat(Wire.quantiseHp(0f, 100f)).isZero();
        assertThat(Wire.quantiseHp(-5f, 100f)).as("clamped, not negative").isZero();
        assertThat(Wire.quantiseHp(200f, 100f)).as("clamped, not wrapped").isEqualTo(255);
        assertThat(Wire.quantiseHp(1f, 0f)).as("no maximum means no bar").isZero();

        // A tank on a sliver of health is still alive and still shooting, so it must not
        // encode as 0 — the client would draw it dead. 1 of 1000 would round to 0 without the
        // floor at 1 (P-12).
        assertThat(Wire.quantiseHp(1f, 1_000f))
                .as("a living tank must not encode as dead").isPositive();
    }

    // ---- reader safety --------------------------------------------------------------------

    @Test
    @DisplayName("a truncated frame throws rather than reading past its end")
    void truncationIsRefused() {
        SnapshotWriter w = new SnapshotWriter(16);
        w.u8(1);
        WireReader r = readerOf(w);
        r.u8();

        assertThat(r.done()).isTrue();
        assertThatThrownBy(r::u8).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("truncated");
        assertThatThrownBy(() -> r.skip(4)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("strings carry their own byte length and survive multi-byte codepoints")
    void stringRoundTrip() {
        for (String s : new String[] {"", "Ada", "éà中文", "🙂"}) {
            SnapshotWriter w = new SnapshotWriter(64);
            byte[] utf8 = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            w.varint(utf8.length);
            w.bytes(utf8, utf8.length);
            assertThat(readerOf(w).str()).as("%s", s).isEqualTo(s);
        }
    }

    @Test
    @DisplayName("a reserved count slot refuses a value it cannot encode in its one byte")
    void countSlotIsOneByte() {
        SnapshotWriter w = new SnapshotWriter(64);
        int slot = w.reserveCount();
        w.patchCount(slot, 127);
        assertThat(readerOf(w).varint()).isEqualTo(127);

        // The slot is one byte, so 128 does not fit. It throws rather than corrupting the
        // frame — but it means no section can exceed 127 entries (P-11).
        SnapshotWriter tooMany = new SnapshotWriter(64);
        int slot2 = tooMany.reserveCount();
        assertThatThrownBy(() -> tooMany.patchCount(slot2, 128))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- input packing --------------------------------------------------------------------

    @Test
    @DisplayName("an input packs into one long with no field overlapping another")
    void inputPacking() {
        long packed = ClientMessage.packInput(16_777_215, 65_535, 255, 255);
        assertThat(ClientMessage.inputSeq(packed)).isEqualTo(16_777_215);
        assertThat(ClientMessage.inputAim(packed)).isEqualTo(65_535);
        assertThat(ClientMessage.inputMove(packed)).isEqualTo(255);
        assertThat(ClientMessage.inputFlags(packed)).isEqualTo(255);

        // One field at a time, to prove the masks do not bleed.
        assertThat(ClientMessage.inputSeq(ClientMessage.packInput(42, 0, 0, 0))).isEqualTo(42);
        assertThat(ClientMessage.inputAim(ClientMessage.packInput(0, 42, 0, 0))).isEqualTo(42);
        assertThat(ClientMessage.inputMove(ClientMessage.packInput(0, 0, 42, 0))).isEqualTo(42);
        assertThat(ClientMessage.inputFlags(ClientMessage.packInput(0, 0, 0, 42))).isEqualTo(42);

        // The sequence is 24 bits and wraps silently. At 20 inputs a second that is 233
        // hours away, but anything deriving a delta from it has to expect the wrap.
        assertThat(ClientMessage.inputSeq(ClientMessage.packInput(16_777_216, 0, 0, 0))).isZero();
    }
}
