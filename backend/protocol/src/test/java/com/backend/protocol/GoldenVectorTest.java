package com.backend.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The golden vectors, decoded by the production reader.
 *
 * <h2>The link this closes</h2>
 *
 * {@code protocol-spike/vectors/vectors.txt} is the contract the Unity client is written
 * against, and it is produced by {@code protocol-spike/java/SnapshotCodec.java} — an
 * independently written codec that the server does not use. So the chain used to be:
 *
 * <pre>
 *   spike codec  ↔ vectors      round-trips
 *   C# reader    ↔ vectors      never run
 *   real encoder ↔ nothing
 * </pre>
 *
 * {@code SnapshotRoundTripTest} in {@code arena} binds the real encoder to
 * {@link SnapshotReader}. This binds {@link SnapshotReader} to the vectors. Together the two
 * ends meet: bytes the server produces and bytes the client is tested against are read by one
 * decoder, and a disagreement between the two encoders now fails a build.
 *
 * <h2>The bytes are copied in on purpose</h2>
 *
 * Reading the file would couple this module to a path outside it and turn a moved directory
 * into a skipped test, which is worse than a failing one. A golden vector is meant to be
 * pinned: if the format changes deliberately, these change with it, and the diff is the
 * record of what changed.
 */
class GoldenVectorTest {

    private static final String MINIMAL = "020101000000000000";

    private static final String JOIN_BURST =
            "020000807dff7c00030100c0e0401ffaff040211034164610200ff1f00e000010000010ac3a9c3a0"
            + "e4b8ade6968703020c00f4ff02c80000";

    private static final String REALISTIC_MOBILE =
            "020203215803393a3b033c01b004acfed0841d024b0b083d01b0ff840328231d014b0e083e012800"
            + "290048ee3d005a0c080c0a07060300c80b030401150c0302002a0d0300023f0e07010454c40f0303"
            + "0369100305017e110306009312070402a8c013030204bd14030003d215030101e7010106de020341"
            + "6461";

    private static final String PROGRESSION =
            "020104060300000002020e11d809ac0c0302000100040703000106d80903416461";

    private static final String SECOND_TIER =
            "0201010000000346012c01ecff00800e004b01104703c4ff5a000cff0101010c480390019001c8b4"
            + "0202050a0149072423405a00";

    private static final String MOTION = "0202010c010000000206083e23d70a41f0000005040380067f";

    private static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private final SnapshotReader reader = new SnapshotReader();

    @Test
    @DisplayName("every vector decodes with nothing left over")
    void everyVectorDecodes() {
        for (String hex : List.of(MINIMAL, JOIN_BURST, REALISTIC_MOBILE, PROGRESSION, SECOND_TIER, MOTION)) {
            byte[] frame = bytes(hex);
            SnapshotReader.Frame f = reader.decode(frame);
            // decode() throws on a trailing byte, so reaching here means this decoder and the
            // one that produced the vector agree on every field's width, in order.
            assertThat(f).isNotNull();
        }
    }

    @Test
    @DisplayName("the smallest legal snapshot is a header and four empty sections")
    void minimal() {
        SnapshotReader.Frame f = reader.decode(bytes(MINIMAL));

        assertThat(f.tickDelta()).isEqualTo(1);
        assertThat(f.inputSeqDelta()).isEqualTo(1);
        assertThat(f.originDX()).isZero();
        assertThat(f.originDY()).isZero();
        assertThat(f.removes()).isEmpty();
        assertThat(f.creates()).isEmpty();
        assertThat(f.updates()).isEmpty();
        assertThat(f.events()).isEmpty();
    }

    @Test
    @DisplayName("a join burst carries two tanks and a shape, names and all")
    void joinBurst() {
        SnapshotReader.Frame f = reader.decode(bytes(JOIN_BURST));

        assertThat(f.originDX()).isEqualTo(8_000);
        assertThat(f.originDY()).isEqualTo(-8_000);
        assertThat(f.creates()).hasSize(3);

        SnapshotReader.Create ada = f.creates().get(0);
        assertThat(ada.kind()).isEqualTo(Wire.KIND_TANK);
        assertThat(ada.handle()).isEqualTo(1);
        assertThat(ada.x()).isEqualTo(-8_000);
        assertThat(ada.y()).isEqualTo(8_000);
        assertThat(ada.hp()).isEqualTo(255);
        assertThat(ada.level()).isEqualTo(17);
        assertThat(ada.name()).isEqualTo("Ada");

        // Positions with the sign bit set, and a name whose UTF-8 is longer than its character count —
        // the two things most likely to be got wrong by a decoder in another language.
        SnapshotReader.Create edge = f.creates().get(1);
        assertThat(edge.x()).isEqualTo(8_191);
        assertThat(edge.y()).isEqualTo(-8_192);
        assertThat(edge.name()).isEqualTo("éà中文");

        SnapshotReader.Create shape = f.creates().get(2);
        assertThat(shape.kind()).isEqualTo(Wire.KIND_STATIC);
        assertThat(shape.subtype()).isEqualTo(2);
    }

    @Test
    @DisplayName("the mobile frame is the traffic budget's reference, and it is 122 bytes")
    void realisticMobile() {
        byte[] frame = bytes(REALISTIC_MOBILE);
        // The number every downstream bandwidth figure in the design is derived from.
        assertThat(frame).hasSize(122);

        SnapshotReader.Frame f = reader.decode(frame);
        assertThat(f.removes()).containsExactly(57, 58, 59);
        assertThat(f.creates()).isNotEmpty();
        assertThat(f.updates()).isNotEmpty();
        assertThat(f.events()).hasSize(1);
        assertThat(f.events().get(0).type()).isEqualTo(Wire.EVT_DEATH);

        // A Sniper's bullet, at 30.5 units a tick: beyond anything a version-1 speed index could
        // say, which stopped at 20.5.
        SnapshotReader.Create sniper = f.creates().get(2);
        assertThat(sniper.kind()).isEqualTo(Wire.KIND_PREDICTED);
        assertThat(Wire.speedOf(sniper.speed())).isEqualTo(30.5f);
        assertThat(sniper.lifetimeTicks()).isEqualTo(90);
    }

    @Test
    @DisplayName("protocols 3 and 4: a bullet and a unit carry their radius, and a trap and a drone arrive as units, updated as tanks are")
    void secondTier() {
        SnapshotReader.Frame f = reader.decode(bytes(SECOND_TIER));
        SnapshotReader.Create destroyer = f.creates().get(0);
        assertThat(destroyer.kind()).isEqualTo(Wire.KIND_PREDICTED);
        assertThat(destroyer.radius()).isEqualTo(16);
        assertThat(destroyer.ownerHandle()).isEqualTo(1);

        SnapshotReader.Create trap = f.creates().get(1);
        assertThat(trap.kind()).isEqualTo(Wire.KIND_UNIT);
        assertThat(trap.subtype()).isEqualTo(Wire.UNIT_TRAP);
        assertThat(trap.hp()).isEqualTo(255);
        assertThat(trap.team()).isEqualTo(1);
        // Its own: a trap outlives its owner, so the client cannot look its size up (protocol 4).
        assertThat(trap.radius()).isEqualTo(12);
        SnapshotReader.Create drone = f.creates().get(2);
        assertThat(drone.subtype()).isEqualTo(Wire.UNIT_DRONE);
        assertThat(drone.ownerHandle()).isEqualTo(5);
        assertThat(drone.angle()).isEqualTo(200);
        assertThat(drone.radius()).isEqualTo(10);
        // The version these bytes are: a format change that forgets to move it is refused by nobody.
        assertThat(Wire.VERSION).isEqualTo(4);
        assertThat(f.updates()).singleElement().satisfies(u -> {
            assertThat(u.handle()).isEqualTo(73);
            assertThat(u.dx()).isEqualTo(18);
            assertThat(u.hp()).isEqualTo(90);
        });
    }

    @Test
    @DisplayName("a bullet created spawnTickOffset ticks ago is that far along its path, as the C# reader has it")
    void spawnTickOffsetIsHonoured() {
        // A frame holding one bullet, created as the vectors' h=60 is: spawnOff 2, life 75.
        SnapshotWriter out = new SnapshotWriter(64);
        out.u8(Wire.MSG_SNAPSHOT);
        out.varint(40);                             // tickDelta
        out.varint(0);                              // inputSeqDelta
        out.svarint(0);                             // view origin
        out.svarint(0);
        out.varint(0);                              // removes
        out.varint(1);                              // creates
        out.u8(60);
        out.u8(Wire.KIND_PREDICTED);
        out.i16(1_200);
        out.i16(-340);
        out.u16(34_000);                            // heading
        out.u8(29);                                 // speed: 14.5 units a tick
        out.u8(2);                                  // spawnTickOffset
        out.u8(75);                                 // lifetimeTicks
        out.u8(0);                                  // owner: none
        out.u8(8);                                  // radius
        out.varint(0);                              // updates
        out.varint(0);                              // events
        ClientWorld w = new ClientWorld();
        w.apply(java.util.Arrays.copyOf(out.array(), out.length()));

        // Its position is where it was two ticks before this frame, and its lifetime counts
        // from then (02 §4).
        ClientWorld.Entity bullet = w.entity(60);
        assertThat(w.serverTick() - bullet.baseTick).isEqualTo(2);
        assertThat(bullet.deathTick).isEqualTo(bullet.baseTick + 75);
        double radians = 34_000 * (2 * Math.PI / 65_536) - Math.PI;
        double travelled = 14.5 * Wire.POS_SCALE * 2;
        assertThat(bullet.x - bullet.baseX).isEqualTo((int) Math.round(Math.cos(radians) * travelled));
    }

    @Test
    @DisplayName("two events in one frame: the length prefix has to carry a reader between them")
    void progression() {
        SnapshotReader.Frame f = reader.decode(bytes(PROGRESSION));

        assertThat(f.events()).hasSize(2);
        assertThat(f.events().get(0).type()).isEqualTo(Wire.EVT_STATS);
        assertThat(f.events().get(1).type()).isEqualTo(Wire.EVT_DEATH);

        // Stepping from the end of one payload to the next type byte is the whole reason the
        // length is a count of BYTES. The C# reader read it as a count of varints, which
        // desynchronised every frame carrying an event, and nothing caught it for months
        // because nothing ever ran it.
        WireReader stats = new WireReader(f.events().get(0).payload());
        assertThat(stats.varint()).as("level").isEqualTo(17);
        assertThat(stats.varint()).as("experience").isEqualTo(1_240);
        assertThat(stats.varint()).as("experience for the next level").isEqualTo(1_580);
        assertThat(stats.u8()).as("unspent points").isEqualTo(3);
        int[] points = new int[Stat_COUNT];
        for (int i = 0; i < points.length; i++) {
            points[i] = stats.u8();
        }
        assertThat(points).containsExactly(2, 0, 1, 0, 4, 7, 3, 0);
        assertThat(stats.done()).as("the stats payload is exactly as long as it says").isTrue();

        WireReader death = new WireReader(f.events().get(1).payload());
        assertThat(death.varint()).as("score at death").isEqualTo(1_240);
        assertThat(death.u8()).as("killer name length").isEqualTo(3);
    }

    @Test
    @DisplayName("the own tank's motion (D-62): its rule as the server's floats, then the input's ticks and its velocity")
    void motion() {
        SnapshotReader.Frame f = reader.decode(bytes(MOTION));

        assertThat(f.events()).extracting(SnapshotReader.Event::type)
                .containsExactly(Wire.EVT_MOTION_RULE, Wire.EVT_MOTION);
        SnapshotReader.MotionRule rule = SnapshotReader.motionRule(f.events().get(0));
        // The bits, not a tolerance: the client steps with exactly the server's float.
        assertThat(Float.floatToRawIntBits(rule.accel())).isEqualTo(Float.floatToRawIntBits(0.16f));
        assertThat(Float.floatToRawIntBits(rule.radius())).isEqualTo(Float.floatToRawIntBits(30f));
        assertThat(SnapshotReader.motion(f.events().get(1)))
                .isEqualTo(new SnapshotReader.Motion(3, 384, -64));
        assertThat(SnapshotReader.motion(f.events().get(1)).vx() / (float) Wire.VELOCITY_SCALE)
                .as("1.5 units a tick, in 1/256 of a unit").isEqualTo(1.5f);
    }

    /**
     * The number of upgradeable stats.
     *
     * Duplicated from {@code sim.Stat.COUNT} rather than imported: {@code protocol} does not
     * depend on {@code sim}, and it must not start doing so to check a wire field's width.
     * The wire says eight; if the simulation ever disagrees, that is a protocol change.
     */
    private static final int Stat_COUNT = 8;

    @Test
    @DisplayName("a corrupted vector is refused rather than half-read")
    void corruptionIsRefused() {
        byte[] truncated = new byte[MINIMAL.length() / 2 - 1];
        System.arraycopy(bytes(MINIMAL), 0, truncated, 0, truncated.length);
        assertThatThrownBy(() -> reader.decode(truncated))
                .isInstanceOf(IllegalStateException.class);

        // One byte too many is just as wrong as one too few: it means some field's width is
        // not what this decoder thinks, and the next frame would start in the wrong place.
        byte[] extended = new byte[MINIMAL.length() / 2 + 1];
        System.arraycopy(bytes(MINIMAL), 0, extended, 0, extended.length - 1);
        assertThatThrownBy(() -> reader.decode(extended))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trailing");

        byte[] wrongType = bytes(MINIMAL);
        wrongType[0] = (byte) Wire.MSG_WELCOME;
        assertThatThrownBy(() -> reader.decode(wrongType))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a snapshot");
    }
}
