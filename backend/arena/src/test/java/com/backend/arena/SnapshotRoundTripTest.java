package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.protocol.ClientMessage;
import com.backend.protocol.ClientWorld;
import com.backend.protocol.SnapshotReader;
import com.backend.protocol.SnapshotWriter;
import com.backend.protocol.Wire;
import com.backend.sim.Entity;
import com.backend.sim.Room;
import com.backend.sim.World;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The server's own bytes, decoded.
 *
 * <h2>What this closes</h2>
 *
 * Nothing used to decode what {@link SnapshotEncoder} produces. {@link SnapshotEncoderTest}
 * asserts on encoder-side state — lengths, counts, handle tables — and never parsed a byte;
 * the bot harness reads the frame type and one varint; and the golden vectors are generated
 * by a second, independently written codec. Six defects lived in that gap, two of them
 * putting every entity in the wrong place on every client.
 *
 * So these tests do the one thing none of the others did: run the real encoder, feed its
 * output to the real decoder, and compare the client's idea of the world against the
 * server's. Nothing is asserted about how the bytes are laid out — only that what comes out
 * the far end is true.
 */
class SnapshotRoundTripTest {

    private static final int BUDGET = 40;

    /** One world unit, in quantised units. What extrapolation may cost, and no more. */
    private static final int PREDICTED_TOLERANCE = (int) Wire.POS_SCALE;
    private static final float VIEW = 1_600f;

    private final SnapshotEncoder encoder = new SnapshotEncoder();
    private final SnapshotWriter out = new SnapshotWriter(4_096);
    private final EventBuffer noEvents = new EventBuffer();

    /**
     * A room whose observer always has company, by construction.
     *
     * Spawn positions are random, so leaving the geometry to the seed means the view is
     * sometimes empty — and a test that compares nothing passes for the wrong reason, which
     * is how the first run of this file reported success on one seed and failure on the next.
     * The tanks are therefore laid out on a grid around the observer, who sits in the middle.
     */
    private static Room roomWith(long seed, int tanks, int shapes) {
        World w = new World(2_000f, 2_000f, 4_096, 200f, seed);
        Room r = new Room(w);
        r.resetForNewMatch(shapes);
        for (int i = 0; i < tanks; i++) {
            r.spawnTank((byte) (i % 2));
        }
        int perRow = Math.max(1, (int) Math.ceil(Math.sqrt(tanks)));
        for (int i = 0; i < r.world().tanks.size; i++) {
            Entity e = r.world().entities[r.world().tanks.items[i]];
            if (i == 0) {
                e.x = 1_000f;                     // the observer, in the centre
                e.y = 1_000f;
            } else {
                e.x = 700f + (i % perRow) * 150f;
                e.y = 700f + (i / perRow) * 150f;
            }
        }
        r.step(Room.newTimer());
        return r;
    }

    /** The frame most recently exchanged, kept so a failure can say what was in it. */
    private SnapshotReader.Frame lastFrame;

    /** Encodes one snapshot for {@code view} and applies it to {@code client}. */
    private void exchange(Room r, ClientView view, ClientWorld client, int tick) {
        encoder.encode(r.world(), view, tick, out, noEvents);
        lastFrame = new SnapshotReader().decode(out.array(), 0, out.length());
        client.apply(out.array(), 0, out.length());
    }

    /**
     * What the last frame said about one handle.
     *
     * A bare "expected 3998 but was 3999" says a position is wrong and nothing about why.
     * Whether the handle was created, updated with which mask, or left out entirely is the
     * whole diagnosis, and reconstructing it by hand each time is how an afternoon goes.
     */
    private String frameSaidAbout(int handle) {
        if (lastFrame == null) {
            return "no frame";
        }
        StringBuilder sb = new StringBuilder();
        for (int h : lastFrame.removes()) {
            if (h == handle) {
                sb.append("removed ");
            }
        }
        lastFrame.creates().stream().filter(c -> c.handle() == handle)
                .forEach(c -> sb.append("created(kind=").append(c.kind()).append(") "));
        lastFrame.updates().stream().filter(u -> u.handle() == handle)
                .forEach(u -> sb.append("updated(mask=").append(u.mask())
                        .append(",dx=").append(u.dx()).append(",dy=").append(u.dy()).append(") "));
        if (sb.isEmpty()) {
            sb.append("not mentioned");
        }
        return sb.toString().trim();
    }

    /**
     * Every entity the client holds must sit exactly where the server has it.
     *
     * The comparison is in quantised world units, which is the finest the wire can express,
     * so an exact equality is the right assertion — any difference at all is a protocol bug
     * rather than rounding.
     */
    private void assertPositionsAgree(Room r, ClientView view, ClientWorld client,
                                            String when) {
        int checked = 0;
        for (int h = 1; h < Wire.MAX_HANDLES; h++) {
            ClientWorld.Entity ce = client.entity(h);
            if (!ce.alive) {
                continue;
            }
            int id = view.entityOf(h);
            assertThat(id).as("%s: handle %d is alive on the client but maps to no entity", when, h)
                    .isNotNegative();
            Entity se = r.world().entities[id];
            if (!se.alive) {
                continue;               // died this tick; its remove goes out next frame
            }
            if (ce.kind == Wire.KIND_PREDICTED) {
                // A predicted entity is extrapolated rather than told, so the client is
                // allowed to be a rounding error out — but only that. The tolerance is one
                // world unit; a bullet drawn further from the server's than that is a speed
                // the wire got wrong, not arithmetic.
                assertThat(Math.abs(ce.x - Wire.quantisePos(se.x)))
                        .as("%s: handle %d extrapolated x", when, h)
                        .isLessThanOrEqualTo(PREDICTED_TOLERANCE);
                assertThat(Math.abs(ce.y - Wire.quantisePos(se.y)))
                        .as("%s: handle %d extrapolated y", when, h)
                        .isLessThanOrEqualTo(PREDICTED_TOLERANCE);
            } else {
                assertThat(ce.x).as("%s: handle %d (kind %d) x — frame %s",
                                when, h, ce.kind, frameSaidAbout(h))
                        .isEqualTo(Wire.quantisePos(se.x));
                assertThat(ce.y).as("%s: handle %d (kind %d) y — frame %s",
                                when, h, ce.kind, frameSaidAbout(h))
                        .isEqualTo(Wire.quantisePos(se.y));
            }
            checked++;
        }
        assertThat(checked).as("%s: nothing was actually compared", when).isPositive();
    }

    @Test
    @DisplayName("a client following a moving room agrees with the server on every position")
    void clientTracksTheServer() {
        Room r = roomWith(7L, 12, 0);
        ClientView view = new ClientView(r.world().tanks.items[0], r.world().capacity(),
                VIEW, VIEW, BUDGET);
        ClientWorld client = new ClientWorld();

        // Deliberately never acknowledging anything. An ack is the client saying "I have
        // this"; it is not what makes a delta decodable, and a client that has just connected
        // or has a slow link has acknowledged nothing at all.
        for (int tick = 1; tick <= 120; tick++) {
            r.step(Room.newTimer());
            exchange(r, view, client, tick);
            assertPositionsAgree(r, view, client, "tick " + tick);
        }
        assertThat(client.serverTick()).isEqualTo(120);
    }

    @Test
    @DisplayName("acknowledging every frame changes nothing about where entities are")
    void acknowledgingChangesNothing() {
        Room r = roomWith(11L, 12, 0);
        ClientView view = new ClientView(r.world().tanks.items[0], r.world().capacity(),
                VIEW, VIEW, BUDGET);
        ClientWorld client = new ClientWorld();

        for (int tick = 1; tick <= 120; tick++) {
            r.step(Room.newTimer());
            exchange(r, view, client, tick);
            view.acknowledge(tick);          // the opposite extreme from the test above
            assertPositionsAgree(r, view, client, "tick " + tick);
        }
    }

    @Test
    @DisplayName("scenery stays where it was put while the camera moves over it")
    void sceneryDoesNotFollowTheCamera() {
        Room r = roomWith(13L, 1, 250);
        Entity self = r.world().entities[r.world().tanks.items[0]];
        // Drive the observer across the map so the view origin travels a long way.
        self.playerControlled = true;
        self.moveX = 1f;
        self.moveY = 0.35f;
        self.aimAngle = 0f;

        ClientView view = new ClientView(self.id, r.world().capacity(), VIEW, VIEW, BUDGET);
        ClientWorld client = new ClientWorld();

        int originAtStart = Integer.MIN_VALUE;
        for (int tick = 1; tick <= 200; tick++) {
            r.step(Room.newTimer());
            exchange(r, view, client, tick);
            if (originAtStart == Integer.MIN_VALUE) {
                originAtStart = client.originX();
            }
            // Static entities receive a create and never an update, so if the client held
            // them relative to the view origin they would slide along with the player.
            assertPositionsAgree(r, view, client, "tick " + tick);
        }
        assertThat(Math.abs(client.originX() - originAtStart))
                .as("the camera has to actually move for this to prove anything")
                .isGreaterThan(Wire.quantisePos(100f));
    }

    @Test
    @DisplayName("at the arena's own rate, 15 frames in 25 ticks, every entity is still the one the server has")
    void productionCadence() {
        Room r = roomWith(19L, 30, 300);
        ClientView view = new ClientView(r.world().tanks.items[0], r.world().capacity(),
                VIEW, VIEW, BUDGET);
        ClientWorld client = new ClientWorld();

        // Every other test here encodes on every tick; the arena does not. Between two frames
        // a bullet can die, its slot be swept, and a new bullet take the same slot, all
        // unseen by this view. The accumulator is RoomThread's.
        int accumulator = 0;
        int frames = 0;
        for (int tick = 1; tick <= 500; tick++) {
            r.step(Room.newTimer());
            accumulator += 15;
            if (accumulator < 25) {
                continue;
            }
            accumulator -= 25;
            exchange(r, view, client, tick);
            if (frames++ % 2 == 0) {
                view.acknowledge(tick);
            }
            assertPositionsAgree(r, view, client, "tick " + tick);
        }
        assertThat(frames).isEqualTo(300);
    }

    @Test
    @DisplayName("after a gap in frames - backgrounded, or dead and respawned - the client is still in step")
    void keptViewSurvivesAGap() {
        for (String how : java.util.List.of("background", "respawn in the same slot",
                "respawn in another slot")) {
            Room r = roomWith(23L, 16, 200);
            // Nobody fires, so nobody dies unless this test says so, and the dying tank is
            // the only slot a sweep frees. Churn across skipped frames is productionCadence's.
            for (int i = 0; i < r.world().tanks.size; i++) {
                r.world().entities[r.world().tanks.items[i]].playerControlled = true;
            }
            int selfId = r.world().tanks.items[0];
            ClientView view = new ClientView(selfId, r.world().capacity(), VIEW, VIEW, BUDGET);
            ClientWorld client = new ClientWorld();
            for (int i = 0; i < 50; i++) {
                r.step(Room.newTimer());
                exchange(r, view, client, r.tick());
            }
            view.acknowledge(r.tick());

            // The gap: a parked or dead player is sent events at most, which move no tick. The
            // observer drives off first, so the camera the client last saw is far behind.
            Entity observer = r.world().entities[selfId];
            observer.moveX = 1f;
            observer.moveY = 0.4f;
            for (int i = 0; i < 50; i++) {
                r.step(Room.newTimer());
            }
            if (!how.equals("background")) {
                r.world().kill(observer);
                r.step(Room.newTimer());            // the sweep frees the slot
                if (how.equals("respawn in another slot")) {
                    r.world().spawn(Entity.KIND_SHAPE);    // and something else takes it first
                }
                Entity fresh = r.spawnTank((byte) 0);
                fresh.playerControlled = true;
                assertThat(fresh.id == selfId).as("%s: slot", how)
                        .isEqualTo(how.equals("respawn in the same slot"));
                view = respawn(view, fresh.id, r);
            }

            for (int i = 0; i < 50; i++) {
                r.step(Room.newTimer());
                exchange(r, view, client, r.tick());
                assertThat(client.serverTick()).as("%s: tick", how).isEqualTo(r.tick());
                assertPositionsAgree(r, view, client, how + ", tick " + r.tick());
            }
            Entity self = r.world().entities[view.selfId];
            ClientWorld.Entity me = client.entity(Wire.SELF_HANDLE);
            assertThat(me.alive).as("%s: the client has itself at handle 1", how).isTrue();
            assertThat(me.x).as(how).isEqualTo(Wire.quantisePos(self.x));
            assertThat(me.y).as(how).isEqualTo(Wire.quantisePos(self.y));
        }
    }

    /**
     * What RoomThread does when a player respawns: the same view, pointed at the new tank.
     * It used to build a new view here, and this test then failed with the client's tick at
     * 154 against the server's 103: a new view's first delta counts from tick zero.
     */
    private static ClientView respawn(ClientView view, int tankId, Room r) {
        view.respawnAs(tankId);
        return view;
    }

    @Test
    @DisplayName("the client learns which of its inputs the server applied, through wrap-around and disorder")
    void inputSeqIsEchoed() {
        Room r = roomWith(29L, 4, 0);
        ClientView view = new ClientView(r.world().tanks.items[0], r.world().capacity(),
                VIEW, VIEW, BUDGET);
        ClientWorld client = new ClientWorld();
        // Unchanged, advancing, the wrap from 2^24 - 1 to 0, and a client whose seqs go
        // backwards: every one must arrive as itself, and no delta may be negative.
        int[] applied = {0, 1, 1, 2, 7, 7, 0xFFFFFE, 0xFFFFFF, 0, 3, 2, 0x7FFFFF, 5};
        for (int i = 0; i < applied.length; i++) {
            r.step(Room.newTimer());
            view.inputApplied(applied[i]);
            exchange(r, view, client, i + 2);
            assertThat(client.lastProcessedInputSeq()).as("frame %d", i).isEqualTo(applied[i]);
            assertThat(lastFrame.inputSeqDelta()).as("frame %d", i)
                    .isBetween(0L, (long) ClientMessage.INPUT_SEQ_MASK);
        }
    }

    @Test
    @DisplayName("a busy room with bullets and deaths decodes cleanly, frame after frame")
    void aBusyRoomDecodesCleanly() {
        Room r = roomWith(17L, 30, 400);
        ClientView view = new ClientView(r.world().tanks.items[0], r.world().capacity(),
                VIEW, VIEW, BUDGET);
        ClientWorld client = new ClientWorld();

        int framesWithCreates = 0;
        int framesWithRemoves = 0;
        SnapshotReader reader = new SnapshotReader();
        for (int tick = 1; tick <= 400; tick++) {
            r.step(Room.newTimer());
            encoder.encode(r.world(), view, tick, out, noEvents);

            // Decoding twice: once to inspect the frame's shape, once through the client so
            // its state advances. Both go through the same reader, which throws on a
            // truncated frame, an unknown entity kind, or a single trailing byte.
            SnapshotReader.Frame f = reader.decode(out.array(), 0, out.length());
            if (!f.creates().isEmpty()) {
                framesWithCreates++;
            }
            if (f.removes().length > 0) {
                framesWithRemoves++;
            }
            client.apply(out.array(), 0, out.length());
            if (tick % 3 == 0) {
                view.acknowledge(tick);      // a realistically intermittent ack
            }
        }

        // A run that never created or removed anything would decode cleanly and prove
        // nothing, so check the traffic was actually varied.
        assertThat(framesWithCreates).as("bullets and shapes should have come and gone")
                .isGreaterThan(20);
        assertThat(framesWithRemoves).isPositive();
        assertThat(client.aliveCount()).isPositive();
    }

    @Test
    @DisplayName("a client can find itself, at the handle the welcome promised")
    void theClientCanFindItself() {
        Room r = roomWith(31L, 10, 60);
        Entity self = r.world().entities[r.world().tanks.items[0]];
        ClientView view = new ClientView(self.id, r.world().capacity(), VIEW, VIEW, BUDGET);
        ClientWorld client = new ClientWorld();

        r.step(Room.newTimer());
        exchange(r, view, client, 1);

        // Welcome says "you are Wire.SELF_HANDLE". That has to be true on the very first
        // snapshot, or the client renders an opponent as itself and never learns its own
        // health — which is what it did before, because the encoder left self out entirely.
        assertThat(view.entityOf(Wire.SELF_HANDLE))
                .as("the promised handle must belong to the client's own tank")
                .isEqualTo(self.id);
        ClientWorld.Entity me = client.entity(Wire.SELF_HANDLE);
        assertThat(me.alive).isTrue();
        assertThat(me.kind).isEqualTo(Wire.KIND_TANK);
        assertThat(me.x).isEqualTo(Wire.quantisePos(self.x));
        assertThat(me.hp).as("its own health, which nothing else carries").isPositive();

        // And it keeps the handle as the room churns around it.
        for (int tick = 2; tick <= 80; tick++) {
            r.step(Room.newTimer());
            exchange(r, view, client, tick);
            assertThat(view.entityOf(Wire.SELF_HANDLE)).as("tick %d", tick).isEqualTo(self.id);
            assertThat(client.entity(Wire.SELF_HANDLE).x)
                    .as("tick %d: the client's own position", tick)
                    .isEqualTo(Wire.quantisePos(self.x));
        }
    }

    @Test
    @DisplayName("what the client holds is what the encoder said it sent")
    void handleTablesAgree() {
        Room r = roomWith(23L, 12, 120);
        ClientView view = new ClientView(r.world().tanks.items[0], r.world().capacity(),
                VIEW, VIEW, BUDGET);
        ClientWorld client = new ClientWorld();

        for (int tick = 1; tick <= 60; tick++) {
            r.step(Room.newTimer());
            exchange(r, view, client, tick);
        }

        // Every handle the server believes is in use, the client holds — and nothing else.
        for (int h = 1; h < Wire.MAX_HANDLES; h++) {
            boolean serverHasIt = view.entityOf(h) >= 0;
            assertThat(client.entity(h).alive).as("handle %d", h).isEqualTo(serverHasIt);
        }
    }

    @Test
    @DisplayName("a tank's create carries its player's name; one nobody plays, or a name past the length byte's limit, none (02 §4)")
    void aTanksNameIsInItsCreate() {
        Room r = roomWith(23L, 4, 0);
        World w = r.world();
        Entity named = w.entities[w.tanks.items[1]];
        Entity tooLong = w.entities[w.tanks.items[2]];
        Entity nobodys = w.entities[w.tanks.items[3]];
        named.name = "Zoë".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        tooLong.name = new byte[Wire.MAX_NAME_BYTES + 1];
        java.util.Arrays.fill(tooLong.name, (byte) 'a');
        ClientView view = new ClientView(w.tanks.items[0], w.capacity(), VIEW, VIEW, BUDGET);
        ClientWorld client = new ClientWorld();
        exchange(r, view, client, 1);
        java.util.Map<Integer, String> nameOf = new java.util.HashMap<>();
        for (int h = 1; h < Wire.MAX_HANDLES; h++) {
            ClientWorld.Entity ce = client.entity(h);
            if (ce.alive && ce.kind == Wire.KIND_TANK) {
                nameOf.put(view.entityOf(h), ce.name);
            }
        }
        assertThat(nameOf.get(named.id)).isEqualTo("Zoë");
        assertThat(nameOf.get(tooLong.id)).as("dropped, not cut through a character").isEmpty();
        assertThat(nameOf.get(nobodys.id)).isEmpty();
    }
}
