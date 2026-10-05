import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reference encoder/decoder for the match snapshot
 * (docs/detailed-design/02-networking.md §4), plus the golden vector generator.
 *
 * The C# client decoder must reproduce the decoded values in vectors.txt byte for byte.
 * That file is the contract between the Java encoder and the C# decoder; nothing else
 * stops the two drifting.
 */
public final class SnapshotCodec {

    // ---- entity kinds -------------------------------------------------------------------
    public static final int KIND_TANK = 0, KIND_PREDICTED = 1, KIND_STATIC = 2, KIND_UNIT = 3;

    // ---- update field mask --------------------------------------------------------------
    public static final int F_POS = 1, F_ANGLE = 2, F_HP = 4, F_LEVEL = 8,
                            F_CLASS = 16, F_TEAM = 32, F_FLAGS = 64;

    // ---- wire buffer --------------------------------------------------------------------

    /** Little-endian writer with LEB128 varints. */
    public static final class Out {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream(256);

        public Out u8(int v) {
            b.write(v & 0xFF);
            return this;
        }
        public Out u16(int v) {
            b.write(v & 0xFF);
            b.write((v >>> 8) & 0xFF);
            return this;
        }
        public Out i16(int v) {
            return u16(v);
        }
        public Out varint(long v) {
            if (v < 0) {
                throw new IllegalArgumentException("varint is unsigned: " + v);
            }
            do {
                int part = (int) (v & 0x7F);
                v >>>= 7;
                b.write(v != 0 ? (part | 0x80) : part);
            } while (v != 0);
            return this;
        }
        public Out svarint(long v) {
            return varint((v << 1) ^ (v >> 63));       // zigzag
        }
        /** An IEEE 754 float's bits, as a big-endian u32 (02 §4, MotionRule). */
        public Out f32(float v) {
            int bits = Float.floatToRawIntBits(v);
            b.write(bits >>> 24);
            b.write((bits >>> 16) & 0xFF);
            b.write((bits >>> 8) & 0xFF);
            b.write(bits & 0xFF);
            return this;
        }
        public Out str(String s) {
            byte[] raw = s.getBytes(StandardCharsets.UTF_8);
            varint(raw.length);
            b.write(raw, 0, raw.length);
            return this;
        }
        public byte[] toBytes() {
            return b.toByteArray();
        }
    }

    /** Reader mirroring {@link Out}. */
    public static final class In {
        private final byte[] a;
        private int p;

        public In(byte[] a) {
            this.a = a;
        }
        public int u8() {
            return a[p++] & 0xFF;
        }
        public int u16() {
            return u8() | (u8() << 8);
        }
        public int i16() {
            return (short) u16();
        }
        public long varint() {
            long v = 0;
            int shift = 0;
            while (true) {
                int c = u8();
                v |= (long) (c & 0x7F) << shift;
                if ((c & 0x80) == 0) {
                    return v;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalStateException("varint too long");
                }
            }
        }
        public long svarint() {
            long v = varint();
            return (v >>> 1) ^ -(v & 1);
        }
        public int f32bits() {
            return (u8() << 24) | (u8() << 16) | (u8() << 8) | u8();
        }
        public String str() {
            int n = (int) varint();
            String s = new String(a, p, n, StandardCharsets.UTF_8);
            p += n;
            return s;
        }
        public boolean done() {
            return p == a.length;
        }
        public int position() {
            return p;
        }
    }

    // ---- model --------------------------------------------------------------------------

    public record Create(int handle, int kind, int x, int y,
                         int angle, int hp, int classId, int team, int level, String name,
                         int heading, int speed, int spawnTickOffset, int lifetimeTicks, int owner,
                         int subtype, int rotationRate, int radius) {

        public static Create tank(int h, int x, int y, int angle, int hp,
                                  int classId, int team, int level, String name) {
            return new Create(h, KIND_TANK, x, y, angle, hp, classId, team, level, name,
                    0, 0, 0, 0, 0, 0, 0, 0);
        }
        public static Create predicted(int h, int x, int y, int heading, int speed,
                                       int spawnTickOffset, int lifetimeTicks, int owner, int radius) {
            return new Create(h, KIND_PREDICTED, x, y, 0, 0, 0, 0, 0, "",
                    heading, speed, spawnTickOffset, lifetimeTicks, owner, 0, 0, radius);
        }
        public static Create stat1c(int h, int x, int y, int subtype, int rotationRate) {
            return new Create(h, KIND_STATIC, x, y, 0, 0, 0, 0, 0, "",
                    0, 0, 0, 0, 0, subtype, rotationRate, 0);
        }
        /** A trap (subtype 1) or a drone (2): protocol 3. Its radius since protocol 4. */
        public static Create unit(int h, int x, int y, int angle, int hp, int subtype, int team, int owner,
                                  int radius) {
            return new Create(h, KIND_UNIT, x, y, angle, hp, 0, team, 0, "",
                    0, 0, 0, 0, owner, subtype, 0, radius);
        }
    }

    /** Only the fields named by {@code mask} are on the wire. */
    public record Update(int handle, int mask, int dx, int dy,
                         int angle, int hp, int level, int classId, int team, int flags) { }

    /**
     * An event as it travels: a type, a byte length, and bytes.
     *
     * The length is what lets a client skip a type it has never heard of. An earlier draft
     * encoded the payload as a list of varints, which cannot carry the killer's name in a
     * death event and gave a client no way to step over an unknown type.
     */
    public record Event(int type, byte[] payload) { }

    /** EVT_DEATH: varint score, u8 nameLength, UTF-8 name. */
    static Event death(int score, String killerName) {
        Out o = new Out();
        o.varint(score);
        byte[] name = killerName == null || killerName.isEmpty()
                ? new byte[0]
                : killerName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        o.u8(name.length);
        for (byte b : name) {
            o.u8(b & 0xFF);
        }
        return new Event(1, o.toBytes());
    }

    /**
     * EVT_STATS: varint level, varint xp, varint xpForNextLevel, u8 unspentPoints,
     * u8[8] pointsPerStat.
     *
     * Sent only to the player it describes. {@code xpForNextLevel} is 0 at the top of the
     * curve, which is how a client knows to draw a full bar rather than divide by zero.
     */
    static Event stats(int level, int xp, int xpForNext, int unspent, int[] points) {
        Out o = new Out();
        o.varint(level);
        o.varint(xp);
        o.varint(xpForNext);
        o.u8(unspent);
        for (int p : points) {
            o.u8(p);
        }
        return new Event(2, o.toBytes());
    }

    /**
     * EVT_MOTION: u8 inputTicks, svarint vx, svarint vy (1/256 world unit a tick). The own
     * tank's, every frame while it lives (D-62).
     */
    static Event motion(int inputTicks, int vx, int vy) {
        Out o = new Out();
        o.u8(inputTicks);
        o.svarint(vx);
        o.svarint(vy);
        return new Event(5, o.toBytes());
    }

    /** EVT_MOTION_RULE: f32 accel, f32 radius. The own tank's, when either changes (D-62). */
    static Event motionRule(float accel, float radius) {
        Out o = new Out();
        o.f32(accel);
        o.f32(radius);
        return new Event(6, o.toBytes());
    }

    public record Snapshot(long tickDelta, long inputSeqDelta, long viewOriginDX, long viewOriginDY,
                           int[] removes, List<Create> creates, List<Update> updates,
                           List<Event> events) { }

    // ---- encode -------------------------------------------------------------------------

    public static byte[] encode(Snapshot s) {
        Out o = new Out();
        o.u8(2);                                     // type = Snapshot
        o.varint(s.tickDelta());
        o.varint(s.inputSeqDelta());
        o.svarint(s.viewOriginDX());
        o.svarint(s.viewOriginDY());

        o.varint(s.removes().length);                // removes first: frees handles
        for (int h : s.removes()) {
            o.u8(h);
        }

        o.varint(s.creates().size());
        for (Create c : s.creates()) {
            o.u8(c.handle()).u8(c.kind()).i16(c.x()).i16(c.y());
            switch (c.kind()) {
                case KIND_TANK -> o.u8(c.angle()).u8(c.hp()).u8(c.classId())
                                   .u8(c.team()).u8(c.level()).str(c.name());
                case KIND_PREDICTED -> o.u16(c.heading()).u8(c.speed())
                                        .u8(c.spawnTickOffset()).u8(c.lifetimeTicks()).u8(c.owner())
                                        .u8(c.radius());
                case KIND_STATIC -> o.u8(c.subtype()).u8(c.rotationRate());
                case KIND_UNIT -> o.u8(c.angle()).u8(c.hp()).u8(c.subtype()).u8(c.team()).u8(c.owner()).u8(c.radius());
                default -> throw new IllegalArgumentException("kind " + c.kind());
            }
        }

        o.varint(s.updates().size());
        for (Update u : s.updates()) {
            o.u8(u.handle()).u8(u.mask());
            if ((u.mask() & F_POS) != 0) {
                o.svarint(u.dx()).svarint(u.dy());
            }
            if ((u.mask() & F_ANGLE) != 0) {
                o.u8(u.angle());
            }
            if ((u.mask() & F_HP) != 0) {
                o.u8(u.hp());
            }
            if ((u.mask() & F_LEVEL) != 0) {
                o.u8(u.level());
            }
            if ((u.mask() & F_CLASS) != 0) {
                o.u8(u.classId());
            }
            if ((u.mask() & F_TEAM) != 0) {
                o.u8(u.team());
            }
            if ((u.mask() & F_FLAGS) != 0) {
                o.u8(u.flags());
            }
        }

        o.varint(s.events().size());
        for (Event e : s.events()) {
            o.u8(e.type()).varint(e.payload().length);
            for (byte b : e.payload()) {
                o.u8(b & 0xFF);
            }
        }
        return o.toBytes();
    }

    // ---- decode -------------------------------------------------------------------------

    public static Snapshot decode(byte[] raw) {
        In in = new In(raw);
        int type = in.u8();
        if (type != 2) {
            throw new IllegalStateException("not a snapshot: type " + type);
        }
        long tickDelta = in.varint(), inputSeqDelta = in.varint();
        long odx = in.svarint(), ody = in.svarint();

        int[] removes = new int[(int) in.varint()];
        for (int i = 0; i < removes.length; i++) {
            removes[i] = in.u8();
        }

        int nc = (int) in.varint();
        List<Create> creates = new ArrayList<>(nc);
        for (int i = 0; i < nc; i++) {
            int h = in.u8(), kind = in.u8(), x = in.i16(), y = in.i16();
            switch (kind) {
                case KIND_TANK -> creates.add(Create.tank(h, x, y, in.u8(), in.u8(),
                        in.u8(), in.u8(), in.u8(), in.str()));
                case KIND_PREDICTED -> creates.add(Create.predicted(h, x, y, in.u16(), in.u8(),
                        in.u8(), in.u8(), in.u8(), in.u8()));
                case KIND_STATIC -> creates.add(Create.stat1c(h, x, y, in.u8(), in.u8()));
                case KIND_UNIT -> creates.add(Create.unit(h, x, y, in.u8(), in.u8(), in.u8(), in.u8(), in.u8(), in.u8()));
                default -> throw new IllegalStateException("unknown kind " + kind);
            }
        }

        int nu = (int) in.varint();
        List<Update> updates = new ArrayList<>(nu);
        for (int i = 0; i < nu; i++) {
            int h = in.u8(), mask = in.u8();
            int dx = 0, dy = 0, angle = 0, hp = 0, level = 0, cls = 0, team = 0, flags = 0;
            if ((mask & F_POS) != 0) {
                dx = (int) in.svarint();
                dy = (int) in.svarint();
            }
            if ((mask & F_ANGLE) != 0) {
                angle = in.u8();
            }
            if ((mask & F_HP) != 0) {
                hp = in.u8();
            }
            if ((mask & F_LEVEL) != 0) {
                level = in.u8();
            }
            if ((mask & F_CLASS) != 0) {
                cls = in.u8();
            }
            if ((mask & F_TEAM) != 0) {
                team = in.u8();
            }
            if ((mask & F_FLAGS) != 0) {
                flags = in.u8();
            }
            updates.add(new Update(h, mask, dx, dy, angle, hp, level, cls, team, flags));
        }

        int ne = (int) in.varint();
        List<Event> events = new ArrayList<>(ne);
        for (int i = 0; i < ne; i++) {
            int t = in.u8();
            byte[] payload = new byte[(int) in.varint()];
            for (int j = 0; j < payload.length; j++) {
                payload[j] = (byte) in.u8();
            }
            events.add(new Event(t, payload));
        }

        if (!in.done()) {
            throw new IllegalStateException("trailing bytes: consumed " + in.position()
                    + " of " + raw.length);
        }
        return new Snapshot(tickDelta, inputSeqDelta, odx, ody, removes, creates, updates, events);
    }

    // ---- vectors ------------------------------------------------------------------------

    static String hex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for (byte b : a) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    /** A realistic mobile snapshot: 12 tanks updating, 3 bullets born, 3 gone, 1 event. */
    static Snapshot realistic() {
        List<Update> updates = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            updates.add(new Update(10 + i, F_POS | F_ANGLE | (i % 4 == 0 ? F_HP : 0),
                    3 - i % 7, i % 5 - 2, (i * 21) & 0xFF, 200 - i, 0, 0, 0, 0));
        }
        List<Create> creates = List.of(
                Create.predicted(60, 1200, -340, 34000, 29, 2, 75, 11, 8),
                Create.predicted(61, -80, 900, 9000, 29, 1, 75, 14, 8),
                Create.predicted(62, 40, 41, 61000, 61, 0, 90, 12, 8));
        return new Snapshot(2, 3, -17, 44, new int[] {57, 58, 59},
                creates, updates, List.of(death(350, "Ada")));
    }

    /**
     * The second tier on the wire (protocol 3): a Destroyer's bullet twice the size, a trap and
     * a drone as units, and a drone moving, as units are updated. Since protocol 4 a unit
     * carries its radius too: a trap outlives its owner, so its size cannot be looked up.
     */
    static Snapshot secondTier() {
        List<Create> creates = List.of(
                Create.predicted(70, 300, -20, 32768, 14, 0, 75, 1, 16),
                Create.unit(71, -60, 90, 12, 255, 1, 1, 1, 12),      // a Trapper's trap
                Create.unit(72, 400, 400, 200, 180, 2, 2, 5, 10));   // an Overseer's drone
        List<Update> updates = List.of(new Update(73, F_POS | F_ANGLE | F_HP, 18, -18, 64, 90, 0, 0, 0, 0));
        return new Snapshot(1, 1, 0, 0, new int[0], creates, updates, List.of());
    }

    static Snapshot minimal() {
        return new Snapshot(1, 1, 0, 0, new int[0], List.of(), List.of(), List.of());
    }

    /**
     * A tank that just levelled up, killed somebody on the way, and has a point to spend.
     *
     * Two events in one frame on purpose: the length prefix has to carry a decoder from the
     * end of one payload to the start of the next, and a single-event case never tests that.
     */
    static Snapshot progression() {
        return new Snapshot(1, 4, 3, -2, new int[0], List.of(), List.of(),
                List.of(stats(17, 1_240, 1_580, 3, new int[] {2, 0, 1, 0, 4, 7, 3, 0}),
                        death(1_240, "Ada")));
    }

    /**
     * The own tank's motion (D-62): its rule, as in a view's first frame, then its motion, moving
     * right and a little up, three ticks into the input the frame echoes.
     */
    static Snapshot motion() {
        return new Snapshot(2, 1, 6, -1, new int[0], List.of(), List.of(),
                List.of(motionRule(0.16f, 30f), motion(3, 384, -64)));
    }

    static Snapshot joinBurst() {
        List<Create> creates = new ArrayList<>();
        creates.add(Create.tank(1, -8000, 8000, 250, 255, 4, 2, 17, "Ada"));
        creates.add(Create.tank(2, 8191, -8192, 0, 1, 0, 0, 1, "éà中文"));
        creates.add(Create.stat1c(3, 12, -12, 2, 200));
        return new Snapshot(0, 0, 8000, -8000, new int[0], creates, List.of(), List.of());
    }

    public static void main(String[] args) throws IOException {
        record Case(String name, Snapshot s) { }

        List<Case> cases = List.of(
                new Case("minimal", minimal()),
                new Case("join_burst", joinBurst()),
                new Case("realistic_mobile", realistic()),
                new Case("progression", progression()),
                new Case("second_tier", secondTier()),
                new Case("motion", motion()));

        Path out = Path.of(args.length > 0 ? args[0] : "vectors.txt");
        int failures = 0;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("# Golden vectors for the match snapshot codec.");
            w.println("# docs/detailed-design/02-networking.md §4");
            w.println("# The C# client decoder must produce exactly these decoded values.");
            w.println("# Format: name, byte length, hex, then the decoded summary.");
            for (Case c : cases) {
                byte[] enc = encode(c.s());
                Snapshot back = decode(enc);
                byte[] re = encode(back);

                boolean ok = java.util.Arrays.equals(enc, re);
                if (!ok) {
                    failures++;
                }
                System.out.printf(Locale.ROOT, "%-18s %4d bytes   round-trip %s%n",
                        c.name(), enc.length, ok ? "OK" : "FAILED");

                w.println();
                w.printf(Locale.ROOT, "case %s%n", c.name());
                w.printf(Locale.ROOT, "bytes %d%n", enc.length);
                w.printf(Locale.ROOT, "hex %s%n", hex(enc));
                w.printf(Locale.ROOT, "tickDelta %d%n", back.tickDelta());
                w.printf(Locale.ROOT, "inputSeqDelta %d%n", back.inputSeqDelta());
                w.printf(Locale.ROOT, "viewOrigin %d %d%n", back.viewOriginDX(), back.viewOriginDY());
                w.printf(Locale.ROOT, "removes %s%n", java.util.Arrays.toString(back.removes()));
                for (Create cr : back.creates()) {
                    if (cr.kind() == KIND_TANK) {
                        w.printf(Locale.ROOT, "create tank h=%d x=%d y=%d angle=%d hp=%d cls=%d team=%d lvl=%d name=%s%n",
                                cr.handle(), cr.x(), cr.y(), cr.angle(), cr.hp(), cr.classId(),
                                cr.team(), cr.level(), cr.name());
                    } else if (cr.kind() == KIND_PREDICTED) {
                        w.printf(Locale.ROOT, "create predicted h=%d x=%d y=%d heading=%d speed=%d spawnOff=%d life=%d owner=%d radius=%d%n",
                                cr.handle(), cr.x(), cr.y(), cr.heading(), cr.speed(),
                                cr.spawnTickOffset(), cr.lifetimeTicks(), cr.owner(), cr.radius());
                    } else if (cr.kind() == KIND_UNIT) {
                        w.printf(Locale.ROOT, "create unit h=%d x=%d y=%d angle=%d hp=%d subtype=%d team=%d owner=%d radius=%d%n",
                                cr.handle(), cr.x(), cr.y(), cr.angle(), cr.hp(), cr.subtype(), cr.team(), cr.owner(),
                                cr.radius());
                    } else {
                        w.printf(Locale.ROOT, "create static h=%d x=%d y=%d subtype=%d rot=%d%n",
                                cr.handle(), cr.x(), cr.y(), cr.subtype(), cr.rotationRate());
                    }
                }
                for (Update u : back.updates()) {
                    w.printf(Locale.ROOT, "update h=%d mask=%d dx=%d dy=%d angle=%d hp=%d%n",
                            u.handle(), u.mask(), u.dx(), u.dy(), u.angle(), u.hp());
                }
                for (Event e : back.events()) {
                    if (e.type() == 2) {
                        In ev = new In(e.payload());
                        long level = ev.varint();
                        long xp = ev.varint();
                        long next = ev.varint();
                        int unspent = ev.u8();
                        StringBuilder pts = new StringBuilder();
                        for (int i = 0; i < 8; i++) {
                            pts.append(i == 0 ? "" : ",").append(ev.u8());
                        }
                        w.printf(Locale.ROOT,
                                "event stats level=%d xp=%d next=%d unspent=%d points=[%s]%n",
                                level, xp, next, unspent, pts);
                        continue;
                    }
                    if (e.type() == 1) {
                        In ev = new In(e.payload());
                        long score = ev.varint();
                        int nameLen = ev.u8();
                        StringBuilder name = new StringBuilder();
                        for (int i = 0; i < nameLen; i++) {
                            name.append((char) ev.u8());
                        }
                        w.printf(Locale.ROOT, "event death score=%d killer=%s%n", score, name);
                        continue;
                    }
                    if (e.type() == 5) {
                        In ev = new In(e.payload());
                        int ticks = ev.u8();
                        long vx = ev.svarint();
                        long vy = ev.svarint();
                        w.printf(Locale.ROOT, "event motion inputTicks=%d vx=%d vy=%d%n", ticks, vx, vy);
                        continue;
                    }
                    if (e.type() == 6) {
                        In ev = new In(e.payload());
                        int accel = ev.f32bits();
                        int radius = ev.f32bits();
                        w.printf(Locale.ROOT, "event motion_rule accel=%08x radius=%08x%n", accel, radius);
                        continue;
                    }
                    w.printf(Locale.ROOT, "event type=%d payloadBytes=%s%n",
                            e.type(), java.util.Arrays.toString(e.payload()));
                }
            }
        }
        System.out.println("vectors written to " + out.toAbsolutePath());
        if (failures > 0) {
            throw new IllegalStateException(failures + " round-trip failures");
        }
    }
}
