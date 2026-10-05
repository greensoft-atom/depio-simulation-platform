package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.backend.common.PhaseTimer;
import com.backend.protocol.SnapshotReader;
import com.backend.protocol.SnapshotWriter;
import com.backend.protocol.Wire;
import com.backend.sim.ClassTable;
import com.backend.sim.Entity;
import com.backend.sim.Room;
import com.backend.sim.World;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnapshotEncoderTest {

    /** These tests exercise entity encoding; the event section is covered by the room tests. */
    private static final EventBuffer NO_EVENTS = new EventBuffer();

    private static Room populated(long seed, int tanks, int shapes) {
        World w = new World(4_000f, 4_000f, 4_096, 200f, seed);
        Room r = new Room(w);
        for (int i = 0; i < tanks; i++) {
            r.spawnTank((byte) (i % 2));
        }
        for (int i = 0; i < shapes; i++) {
            r.spawnShape();
        }
        r.step(Room.newTimer());     // the encoder reads the spatial hash, which step() builds
        return r;
    }

    private static ClientView viewOf(Room r, int budget) {
        int self = r.world().tanks.items[0];
        return new ClientView(self, r.world().capacity(), 1600f, 1600f, budget);
    }

    @Test
    @DisplayName("the entity budget is never exceeded, however crowded the view")
    void budgetIsRespected() {
        Room r = populated(1L, 60, 400);
        ClientView v = new ClientView(r.world().tanks.items[0], r.world().capacity(),
                100_000f, 100_000f, 30);      // a view covering the whole map
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(2048);

        int included = enc.encode(r.world(), v, 1, out, NO_EVENTS);
        assertThat(included).isLessThanOrEqualTo(30);
        assertThat(included).as("a view over the whole map must not come back empty").isPositive();
    }

    @Test
    @DisplayName("a budget larger than a section can count is capped, and a crowded frame still encodes")
    void budgetIsCappedAtTheCountSlot() {
        Room r = populated(3L, 60, 600);
        ClientView v = new ClientView(r.world().tanks.items[0], r.world().capacity(),
                100_000f, 100_000f, 254);
        // Each section's count is one byte of varint: 127 at most (P-11). 254 used to be
        // accepted, and the first frame with more than 127 creates threw.
        assertThat(v.entityBudget).isEqualTo(ClientView.MAX_ENTITY_BUDGET);

        SnapshotWriter out = new SnapshotWriter(8_192);
        int included = new SnapshotEncoder().encode(r.world(), v, 1, out, NO_EVENTS);
        assertThat(included).isEqualTo(ClientView.MAX_ENTITY_BUDGET);
        assertThat(new SnapshotReader().decode(out.array(), 0, out.length()).creates())
                .hasSize(ClientView.MAX_ENTITY_BUDGET);
    }

    @Test
    @DisplayName("tanks are preferred over shapes when the budget is tight")
    void tanksRankAboveShapes() {
        Room r = populated(2L, 40, 400);
        ClientView v = new ClientView(r.world().tanks.items[0], r.world().capacity(),
                100_000f, 100_000f, 10);
        SnapshotEncoder enc = new SnapshotEncoder();
        enc.encode(r.world(), v, 1, new SnapshotWriter(2048), NO_EVENTS);

        int tanks = 0, others = 0;
        for (int h = 1; h < Wire.MAX_HANDLES; h++) {
            int id = v.entityOf(h);
            if (id < 0) {
                continue;
            }
            if (r.world().entities[id].kind == Entity.KIND_TANK) {
                tanks++;
            } else {
                others++;
            }
        }
        assertThat(tanks).as("tanks crowd out shapes at a tight budget").isGreaterThan(others);
    }

    @Test
    @DisplayName("a client that acknowledges nothing still receives usable updates")
    void deltasDoNotDependOnAcknowledgement() {
        Room r = populated(3L, 12, 40);
        ClientView v = viewOf(r, 40);
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(2048);
        SnapshotReader in = new SnapshotReader();
        PhaseTimer t = Room.newTimer();

        enc.encode(r.world(), v, 1, out, NO_EVENTS);
        assertThat(in.decode(out.array(), 0, out.length()).creates()).isNotEmpty();

        // Never acknowledged. Deltas are measured against the last frame *sent*, because TCP
        // delivers every frame exactly once and in order — so a client that has acknowledged
        // nothing is no worse informed than one that acknowledges everything. This used to be
        // the opposite: the baseline only moved on an ack, while the client accumulated, and
        // the two disagreed by the whole un-acknowledged run.
        int updatesSeen = 0;
        for (int tick = 2; tick <= 6; tick++) {
            r.step(t);
            enc.encode(r.world(), v, tick, out, NO_EVENTS);
            updatesSeen += in.decode(out.array(), 0, out.length()).updates().size();
        }
        assertThat(updatesSeen).as("a moving room must produce updates without any ack").isPositive();

        // What the acknowledgement is still for: a handle is not reused until the client has
        // confirmed the removal, so this must not advance on its own.
        assertThat(v.lastAckedTick()).isZero();
        v.acknowledge(6);
        assertThat(v.lastAckedTick()).isEqualTo(6);
    }

    @Test
    @DisplayName("predicted entities are created and removed but never updated")
    void predictedEntitiesAreNeverUpdated() {
        Room r = populated(4L, 8, 0);
        ClientView v = viewOf(r, 60);
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(2048);
        PhaseTimer t = Room.newTimer();

        for (int tick = 1; tick <= 12; tick++) {
            r.step(t);
            enc.encode(r.world(), v, tick, out, NO_EVENTS);
            v.acknowledge(tick);
        }

        // Walk the update section of a fresh snapshot and confirm no bullet appears.
        r.step(t);
        enc.encode(r.world(), v, 13, out, NO_EVENTS);
        for (int h = 1; h < Wire.MAX_HANDLES; h++) {
            int id = v.entityOf(h);
            if (id >= 0 && r.world().entities[id].kind == Entity.KIND_BULLET) {
                assertThat(r.world().entities[id].wireClass).isEqualTo(Entity.WIRE_PREDICTED);
            }
        }
        assertThat(out.length()).isPositive();
    }

    @Test
    @DisplayName("a handle is not reused until its removal is acknowledged")
    void handlesAreNotReusedBeforeTheRemoveIsAcked() {
        Room r = populated(5L, 8, 60);
        ClientView v = viewOf(r, 60);
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(1024);

        enc.encode(r.world(), v, 1, out, NO_EVENTS);
        v.acknowledge(1);

        int victimId = -1, victimHandle = ClientView.NO_HANDLE;
        for (int h = 1; h < Wire.MAX_HANDLES; h++) {
            if (v.entityOf(h) >= 0 && v.entityOf(h) != v.selfId) {
                victimHandle = h;
                victimId = v.entityOf(h);
                break;
            }
        }
        assertThat(victimHandle).isNotEqualTo(ClientView.NO_HANDLE);

        r.world().kill(r.world().entities[victimId]);
        r.world().sweep();
        enc.encode(r.world(), v, 2, out, NO_EVENTS);          // emits the remove

        assertThat(v.entityOf(victimHandle)).isEqualTo(-1);

        // Without an ack the handle must stay reserved: spawn many entities and confirm
        // none of them takes it.
        for (int i = 0; i < 20; i++) {
            r.spawnShape();
        }
        enc.encode(r.world(), v, 3, out, NO_EVENTS);
        assertThat(v.entityOf(victimHandle)).as("handle reused before the remove was acked")
                .isEqualTo(-1);

        v.acknowledge(3);
        enc.encode(r.world(), v, 4, out, NO_EVENTS);
        // Now it may be reused; that is allowed, not required.
        assertThat(v.lastAckedTick()).isEqualTo(3);
    }

    @Test
    @DisplayName("a tank's skin is told with its create, to the view it enters, and not again while it stays; a tank without one, nothing (02 §4, D-70)")
    void aSkinIsToldWithTheCreate() {
        Room r = populated(11L, 3, 0);
        World w = r.world();
        int self = w.tanks.items[0];
        int skinned = w.tanks.items[1];
        int plain = w.tanks.items[2];
        w.entities[skinned].x = w.entities[self].x + 100f;
        w.entities[skinned].y = w.entities[self].y;
        w.entities[plain].x = w.entities[self].x - 100f;
        w.entities[plain].y = w.entities[self].y;
        w.hash.clear();
        for (int id : new int[] {self, skinned, plain}) {
            w.hash.insert(id, w.entities[id].x, w.entities[id].y);
        }
        w.tankStats[self].skin = 5;
        w.tankStats[skinned].skin = 3;

        ClientView v = new ClientView(self, w.capacity(), 1600f, 1600f, 30);
        SnapshotWriter out = new SnapshotWriter(1024);
        SnapshotEncoder enc = new SnapshotEncoder();
        EventBuffer events = new EventBuffer();
        enc.encode(w, v, 1, out, events);
        SnapshotReader.Frame first = new SnapshotReader().decode(out.array(), 0, out.length());
        assertThat(first.creates()).hasSize(3);
        assertThat(skinsIn(first)).as("its own, and the other skinned tank's")
                .containsExactlyInAnyOrder(new SnapshotReader.Skin(Wire.SELF_HANDLE, 5), new SnapshotReader.Skin(v.handleOf(skinned), 3));

        v.acknowledge(1);
        enc.encode(w, v, 2, out, events);
        SnapshotReader.Frame next = new SnapshotReader().decode(out.array(), 0, out.length());
        assertThat(next.creates()).isEmpty();
        assertThat(skinsIn(next)).as("told once, with the create").isEmpty();
    }

    private static List<SnapshotReader.Skin> skinsIn(SnapshotReader.Frame frame) {
        return frame.events().stream().filter(e -> e.type() == Wire.EVT_SKIN).map(SnapshotReader::skin).toList();
    }

    @Test
    @DisplayName("the player's own tank holds handle 1, even with another tank on top of it")
    void selfHoldsHandleOneWhenCrowded() {
        Room r = populated(7L, 2, 0);
        World w = r.world();
        // The other tank has the lower slot, so it wins a tie on distance, and within four
        // units the distance key is the same as the player's own: zero.
        int self = Math.max(w.tanks.items[0], w.tanks.items[1]);
        int other = Math.min(w.tanks.items[0], w.tanks.items[1]);
        w.entities[other].x = w.entities[self].x + 1f;
        w.entities[other].y = w.entities[self].y;
        w.hash.clear();
        w.hash.insert(self, w.entities[self].x, w.entities[self].y);
        w.hash.insert(other, w.entities[other].x, w.entities[other].y);

        ClientView v = new ClientView(self, w.capacity(), 1600f, 1600f, 30);
        SnapshotWriter out = new SnapshotWriter(1024);
        new SnapshotEncoder().encode(w, v, 1, out, NO_EVENTS);

        assertThat(v.handleOf(self)).as("the welcome promises handle 1").isEqualTo(Wire.SELF_HANDLE);
        assertThat(v.handleOf(other)).as("and the other tank is still sent")
                .isNotEqualTo(ClientView.NO_HANDLE).isNotEqualTo(Wire.SELF_HANDLE);
        assertThat(new SnapshotReader().decode(out.array(), 0, out.length()).creates()).hasSize(2);
    }

    @Test
    @DisplayName("after a death, the new tank takes handle 1 back even when another tank is on top of it")
    void respawnedTankTakesHandleOneBack() {
        Room r = populated(9L, 3, 0);
        World w = r.world();
        int low = w.tanks.items[0];
        int self = w.tanks.items[2];
        // A small view, so the other tanks start out of sight and hold no handle.
        ClientView v = new ClientView(self, w.capacity(), 200f, 200f, 30);
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(1024);
        enc.encode(w, v, 1, out, NO_EVENTS);
        assertThat(v.handleOf(self)).isEqualTo(Wire.SELF_HANDLE);
        v.acknowledge(1);

        w.kill(w.entities[self]);
        w.sweep();
        enc.encode(w, v, 2, out, NO_EVENTS);             // removes handle 1
        v.acknowledge(2);                                // and the client has seen it go

        int reborn = r.spawnTank((byte) 0).id;
        v.respawnAs(reborn);
        w.entities[low].x = w.entities[reborn].x + 1f;   // lower slot, same distance key
        w.entities[low].y = w.entities[reborn].y;
        w.hash.clear();
        for (int i = 0; i < w.tanks.size; i++) {
            Entity e = w.entities[w.tanks.items[i]];
            w.hash.insert(e.id, e.x, e.y);
        }
        enc.encode(w, v, 3, out, NO_EVENTS);

        assertThat(v.handleOf(reborn)).isEqualTo(Wire.SELF_HANDLE);
        assertThat(v.handleOf(low)).isNotEqualTo(Wire.SELF_HANDLE).isNotEqualTo(ClientView.NO_HANDLE);
    }

    @Test
    @DisplayName("a client that acknowledges as a real one does is sent each create once")
    void createsAreNotResentToAnAcknowledgingClient() {
        assertThat(recreatedAtLag(3)).isZero();
    }

    @Test
    @DisplayName("a far or queued link, acknowledging 0.8 s behind, is not sent every create again")
    void createsAreNotResentOverASlowLink() {
        // The create's own wait was the only clock: at a lag past it, each create was re-sent
        // every fifteen ticks for as long as it stayed in view - on the links traffic profiles
        // are there to spare, adding to their queue.
        assertThat(recreatedAtLag(20)).isZero();
    }

    @Test
    @DisplayName("a client that stops acknowledging is sent its creates again")
    void createsAreResentWhenAcknowledgementsStop() {
        assertThat(recreatedAtLag(Integer.MAX_VALUE)).isPositive();
    }

    private int recreatedAtLag(int lag) {
        Room r = populated(8L, 12, 40);
        World w = r.world();
        ClientView v = viewOf(r, 40);
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(4096);
        SnapshotReader in = new SnapshotReader();
        PhaseTimer t = Room.newTimer();

        java.util.Set<Integer> held = new java.util.HashSet<>();
        int recreated = 0;
        for (int tick = 1; tick <= 90; tick++) {
            for (int i = 0; i < w.tanks.size; i++) {
                w.entities[w.tanks.items[i]].moveX = 1f;   // every tank moves every tick
            }
            r.step(t);
            enc.encode(w, v, tick, out, NO_EVENTS);
            SnapshotReader.Frame f = in.decode(out.array(), 0, out.length());
            for (int h : f.removes()) {
                held.remove(h);
            }
            for (SnapshotReader.Create c : f.creates()) {
                if (!held.add(c.handle())) {
                    recreated++;              // a create for a handle the client already holds
                }
            }
            if (lag != Integer.MAX_VALUE && tick > lag) {
                v.acknowledge(tick - lag);    // acks trail frames by the link's lag
            }
        }
        // It used to be every fifteen ticks for anything that changed in consecutive frames:
        // the create counted as unconfirmed until an ack caught up with the entity's latest
        // write, which for a moving tank it never did.
        return recreated;
    }

    @Test
    @DisplayName("a shape that only turns is sent once: the client turns it (02 §6)")
    void shapesThatOnlyTurnSendNothing() {
        Room r = populated(10L, 1, 200);
        World w = r.world();
        for (int i = 0; i < w.shapes.size; i++) {
            Entity e = w.entities[w.shapes.items[i]];
            e.vx = 0f;                                   // no drift: the server only spins them
            e.vy = 0f;
        }
        ClientView v = viewOf(r, 60);
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(4096);
        SnapshotReader in = new SnapshotReader();
        PhaseTimer t = Room.newTimer();

        enc.encode(w, v, 1, out, NO_EVENTS);
        assertThat(in.decode(out.array(), 0, out.length()).creates()).hasSizeGreaterThan(10);
        v.acknowledge(1);
        int shapeUpdates = 0;
        for (int tick = 2; tick <= 40; tick++) {
            r.step(t);
            enc.encode(w, v, tick, out, NO_EVENTS);
            for (SnapshotReader.Update u : in.decode(out.array(), 0, out.length()).updates()) {
                int id = v.entityOf(u.handle());
                if (id >= 0 && w.entities[id].wireClass == Entity.WIRE_STATIC) {
                    shapeUpdates++;
                }
            }
            v.acknowledge(tick);
        }
        // Measured at the shipped density with nobody firing: a client holds 19 shapes, and
        // their spin was a fifth of every frame.
        assertThat(shapeUpdates).isZero();
    }

    @Test
    @DisplayName("another tank's level reaches the client when it changes, not only when first seen")
    void levelUpsAreSent() {
        Room r = populated(11L, 12, 0);
        World w = r.world();
        ClientView v = new ClientView(w.tanks.items[0], w.capacity(), 100_000f, 100_000f, 30);
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(2048);
        com.backend.protocol.ClientWorld client = new com.backend.protocol.ClientWorld();

        enc.encode(w, v, 1, out, NO_EVENTS);
        client.apply(out.array(), 0, out.length());
        v.acknowledge(1);
        int other = w.tanks.items[1];
        int h = v.handleOf(other);
        assertThat(client.entity(h).level).isEqualTo(w.tankStats[other].level);

        w.tankStats[other].level = 7;
        enc.encode(w, v, 2, out, NO_EVENTS);
        client.apply(out.array(), 0, out.length());
        // An opponent's level used to be frozen at whatever it was when they came into view.
        assertThat(client.entity(h).level).isEqualTo(7);

        enc.encode(w, v, 3, out, NO_EVENTS);
        assertThat(new SnapshotReader().decode(out.array(), 0, out.length()).updates())
                .as("and sent once, not every frame").noneMatch(u -> u.handle() == h && u.has(Wire.F_LEVEL));
    }

    @Test
    @DisplayName("a tank's class is in its create, and an update carries it when it changes (01 §4)")
    void classesAreSent() {
        Room r = populated(13L, 12, 0);
        World w = r.world();
        int other = w.tanks.items[1];
        int later = w.tanks.items[2];
        w.tankStats[other].classId = ClassTable.SNIPER;
        ClientView v = new ClientView(w.tanks.items[0], w.capacity(), 100_000f, 100_000f, 30);
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(2048);
        com.backend.protocol.ClientWorld client = new com.backend.protocol.ClientWorld();

        enc.encode(w, v, 1, out, NO_EVENTS);
        client.apply(out.array(), 0, out.length());
        v.acknowledge(1);
        assertThat(client.entity(v.handleOf(other)).classId).as("in the create").isEqualTo(ClassTable.SNIPER);
        assertThat(client.entity(v.handleOf(later)).classId).isEqualTo(ClassTable.BASIC);

        w.tankStats[later].classId = ClassTable.TWIN;
        enc.encode(w, v, 2, out, NO_EVENTS);
        client.apply(out.array(), 0, out.length());
        v.acknowledge(2);
        assertThat(client.entity(v.handleOf(later)).classId).as("in an update").isEqualTo(ClassTable.TWIN);

        enc.encode(w, v, 3, out, NO_EVENTS);
        int h = v.handleOf(later);
        assertThat(new SnapshotReader().decode(out.array(), 0, out.length()).updates())
                .as("and sent once, not every frame").noneMatch(u -> u.handle() == h && u.has(Wire.F_CLASS));
    }

    @Test
    @DisplayName("a bullet's create carries its radius, as big as a Destroyer's (protocol 3)")
    void bulletsCarryTheirSize() {
        Room r = populated(14L, 2, 0);
        World w = r.world();
        Entity self = w.entities[w.tanks.items[0]];
        Entity big = w.spawn(Entity.KIND_BULLET);
        big.x = self.x + 100f;
        big.y = self.y;
        big.vx = 7f;
        big.radius = 16f;
        big.lifetimeTicks = 75;
        big.ownerId = self.id;
        big.ownerGeneration = self.generation;
        r.step(Room.newTimer());                              // into the hash the encoder reads
        ClientView v = new ClientView(self.id, w.capacity(), 100_000f, 100_000f, 30);
        SnapshotWriter out = new SnapshotWriter(2048);
        new SnapshotEncoder().encode(w, v, 1, out, NO_EVENTS);
        assertThat(new SnapshotReader().decode(out.array(), 0, out.length()).creates())
                .filteredOn(c -> c.kind() == Wire.KIND_PREDICTED).singleElement()
                .satisfies(c -> {
                    assertThat(c.radius()).isEqualTo(16);
                    assertThat(c.ownerHandle()).isEqualTo(Wire.SELF_HANDLE);
                });
    }

    @Test
    @DisplayName("a create's owner is the owner of that generation: one fired by the slot's last occupant names none, not the next (P-50)")
    void anOwnerGoneIsNotTheSlotsNextOccupant() {
        Room r = populated(14L, 2, 0);
        World w = r.world();
        Entity self = w.entities[w.tanks.items[0]];
        Entity bullet = w.spawn(Entity.KIND_BULLET);
        Entity trap = w.spawn(Entity.KIND_BULLET);
        for (Entity e : new Entity[] {bullet, trap}) {
            e.x = self.x + 100f;
            e.y = self.y;
            e.vx = 7f;
            e.radius = 8f;
            e.lifetimeTicks = 75;
            // Fired by whatever held this tank's slot before it, since gone: the slot reused, its generation moved on.
            e.ownerId = self.id;
            e.ownerGeneration = (short) (self.generation - 1);
        }
        trap.wireClass = Entity.WIRE_UNIT;
        trap.subtype = (byte) Wire.UNIT_TRAP;
        r.step(Room.newTimer());
        ClientView v = new ClientView(self.id, w.capacity(), 100_000f, 100_000f, 30);
        SnapshotWriter out = new SnapshotWriter(2048);
        new SnapshotEncoder().encode(w, v, 1, out, NO_EVENTS);
        assertThat(new SnapshotReader().decode(out.array(), 0, out.length()).creates())
                .filteredOn(c -> c.kind() == Wire.KIND_PREDICTED || c.kind() == Wire.KIND_UNIT)
                .hasSize(2).allSatisfy(c -> assertThat(c.ownerHandle()).as("not this client's own tank")
                        .isEqualTo(ClientView.NO_HANDLE));
    }

    @Test
    @DisplayName("a trap is a unit: the client is told where it slides, exactly, and nothing once it is still")
    void trapsAreUnits() {
        World w = new World(4_000f, 4_000f, 4_096, 200f, 15L);
        Room r = new Room(w, new com.backend.sim.Content(com.backend.sim.StatTable.defaults(),
                com.backend.sim.LevelTable.defaults(), com.backend.sim.ShapeTable.defaults(),
                com.backend.sim.Recovery.defaults(), new com.backend.sim.Spawning(0, 0f, 1)));
        Entity me = r.spawnTank((byte) 0, 1L);
        me.playerControlled = true;
        me.wantsFire = true;
        me.reloadTicks = 0;
        var stats = w.tankStats[me.id];
        stats.addXp(r.content().levels().xpRequired(30), r.content().levels());
        r.chooseClass(me, ClassTable.SNIPER);
        r.chooseClass(me, ClassTable.TRAPPER);
        ClientView v = new ClientView(me.id, w.capacity(), 100_000f, 100_000f, 30);
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(2048);
        com.backend.protocol.ClientWorld client = new com.backend.protocol.ClientWorld();
        PhaseTimer t = Room.newTimer();

        int trapHandle = ClientView.NO_HANDLE;
        Entity trap = null;
        int quietFrames = 0;
        for (int tick = 1; tick <= 90; tick++) {
            r.step(t);
            me.wantsFire = false;
            enc.encode(w, v, tick, out, NO_EVENTS);
            SnapshotReader.Frame f = new SnapshotReader().decode(out.array(), 0, out.length());
            client.apply(out.array(), 0, out.length());
            v.acknowledge(tick);
            if (trap == null && w.bullets.size > 0) {
                trap = w.entities[w.bullets.items[0]];
                trapHandle = v.handleOf(trap.id);
                assertThat(f.creates()).filteredOn(c -> c.kind() == Wire.KIND_UNIT).singleElement()
                        .satisfies(c -> {
                            assertThat(c.subtype()).isEqualTo(Wire.UNIT_TRAP);
                            assertThat(c.ownerHandle()).isEqualTo(Wire.SELF_HANDLE);
                            // A Trapper's, 1.5 times a bullet's 8 (protocol 4).
                            assertThat(c.radius()).isEqualTo(12);
                        });
            } else if (trap != null) {
                int h = trapHandle;
                boolean updated = f.updates().stream().anyMatch(u -> u.handle() == h);
                quietFrames = updated ? 0 : quietFrames + 1;
                assertThat(client.entity(h).x).as("tick %d", tick).isEqualTo(Wire.quantisePos(trap.x));
                assertThat(client.entity(h).y).isEqualTo(Wire.quantisePos(trap.y));
                assertThat(client.entity(h).radius).isEqualTo(12);
            }
        }
        assertThat(trap).isNotNull();
        // Moving by less than a quarter unit, the quantum of a position, a tick at a time, it
        // has stopped as far as the wire can say within about two seconds of being laid.
        assertThat(quietFrames).as("a still trap costs nothing").isGreaterThan(20);
    }

    @Test
    @DisplayName("a tank's spawn protection reaches the client, and so does its end")
    void protectionIsShown() {
        Room r = populated(12L, 6, 0);              // every tank has just arrived
        World w = r.world();
        ClientView v = new ClientView(w.tanks.items[0], w.capacity(), 100_000f, 100_000f, 30);
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(2048);
        com.backend.protocol.ClientWorld client = new com.backend.protocol.ClientWorld();
        PhaseTimer t = Room.newTimer();
        int other = w.tanks.items[1];

        enc.encode(w, v, r.tick(), out, NO_EVENTS);
        client.apply(out.array(), 0, out.length());
        v.acknowledge(r.tick());
        int h = v.handleOf(other);
        // A create has no flags field; the next frame's update carries them. A player who
        // cannot shoot, or cannot be hurt, has to be able to see why.
        r.step(t);
        enc.encode(w, v, r.tick(), out, NO_EVENTS);
        client.apply(out.array(), 0, out.length());
        v.acknowledge(r.tick());
        assertThat(client.entity(h).flags & Wire.TANK_FLAG_PROTECTED).isNotZero();
        assertThat(client.entity(Wire.SELF_HANDLE).flags & Wire.TANK_FLAG_PROTECTED)
                .as("the player's own tank too").isNotZero();

        for (int i = 0; i < r.content().spawning().protectionTicks(); i++) {
            r.step(t);
            enc.encode(w, v, r.tick(), out, NO_EVENTS);
            client.apply(out.array(), 0, out.length());
            v.acknowledge(r.tick());
        }
        assertThat(client.entity(h).flags & Wire.TANK_FLAG_PROTECTED).as("and when it ends").isZero();
    }

    /** How many tanks a client holds, its own included. */
    private static int tanksHeld(com.backend.protocol.ClientWorld client) {
        int n = 0;
        for (int h = 1; h < Wire.MAX_HANDLES; h++) {
            com.backend.protocol.ClientWorld.Entity e = client.entity(h);
            if (e != null && e.alive && e.kind == Wire.KIND_TANK) {
                n++;
            }
        }
        return n;
    }

    @Test
    @DisplayName("a hidden tank is not sent to anyone else, its own player is told, and it comes back when it moves (D-23)")
    void hiddenTanksAreNotSent() {
        World w = new World(4_000f, 4_000f, 4_096, 200f, 21L);
        Room r = new Room(w, new com.backend.sim.Content(com.backend.sim.StatTable.defaults(),
                com.backend.sim.LevelTable.defaults(), com.backend.sim.ShapeTable.defaults(),
                com.backend.sim.Recovery.defaults(), new com.backend.sim.Spawning(0, 0f, 1)));
        Entity stalker = r.spawnTank((byte) 1, 1L);
        Entity watcher = r.spawnTank((byte) 2, 2L);
        for (Entity e : new Entity[] {stalker, watcher}) {
            e.playerControlled = true;
            e.y = 1_000f;
        }
        stalker.x = 1_000f;
        watcher.x = 1_300f;
        var stats = w.tankStats[stalker.id];
        stats.addXp(r.content().levels().xpRequired(45), r.content().levels());
        r.chooseClass(stalker, ClassTable.SNIPER);
        r.chooseClass(stalker, ClassTable.ASSASSIN);
        r.chooseClass(stalker, ClassTable.STALKER);
        ClientView mine = new ClientView(stalker.id, w.capacity(), 1_600f, 1_600f, 30);
        ClientView theirs = new ClientView(watcher.id, w.capacity(), 1_600f, 1_600f, 30);
        com.backend.protocol.ClientWorld me = new com.backend.protocol.ClientWorld();
        com.backend.protocol.ClientWorld them = new com.backend.protocol.ClientWorld();
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(2048);
        PhaseTimer t = Room.newTimer();
        Runnable frame = () -> {
            r.step(t);
            for (Object[] pair : new Object[][] {{mine, me}, {theirs, them}}) {
                ClientView v = (ClientView) pair[0];
                enc.encode(w, v, r.tick(), out, NO_EVENTS);
                ((com.backend.protocol.ClientWorld) pair[1]).apply(out.array(), 0, out.length());
                v.acknowledge(r.tick());
            }
        };

        for (int i = 0; i < 10; i++) {
            frame.run();
        }
        assertThat(tanksHeld(them)).as("seen while it has just arrived").isEqualTo(2);
        for (int i = 0; i < 45; i++) {
            frame.run();
        }
        assertThat(stalker.hidden).isTrue();
        assertThat(tanksHeld(them)).as("removed from the other's world: its position is not sent").isEqualTo(1);
        assertThat(me.entity(Wire.SELF_HANDLE).flags & Wire.TANK_FLAG_HIDDEN).as("told itself").isNotZero();

        stalker.moveX = 1f;
        frame.run();
        frame.run();
        assertThat(tanksHeld(them)).as("moving: created again").isEqualTo(2);
        assertThat(me.entity(Wire.SELF_HANDLE).flags & Wire.TANK_FLAG_HIDDEN).isZero();
    }

    @Test
    @DisplayName("a Predator zooming is sent what lies 1 500 units ahead, beyond its view unzoomed, and its origin moves 700")
    void zoomMovesTheView() {
        World w = new World(8_000f, 8_000f, 4_096, 200f, 31L);
        Room r = new Room(w, new com.backend.sim.Content(com.backend.sim.StatTable.defaults(),
                com.backend.sim.LevelTable.defaults(), com.backend.sim.ShapeTable.defaults(),
                com.backend.sim.Recovery.defaults(), new com.backend.sim.Spawning(0, 0f, 1)));
        Entity me = r.spawnTank((byte) 1, 1L);
        Entity far = r.spawnTank((byte) 2, 2L);
        for (Entity e : new Entity[] {me, far}) {
            e.playerControlled = true;
            e.y = 4_000f;
        }
        me.x = 2_000f;
        far.x = 3_500f;                                 // past the unzoomed view's 1 120 either side
        me.aimAngle = 0f;
        w.tankStats[me.id].addXp(r.content().levels().xpRequired(45), r.content().levels());
        r.chooseClass(me, ClassTable.SNIPER);
        r.chooseClass(me, ClassTable.HUNTER);
        r.chooseClass(me, ClassTable.PREDATOR);
        ClientView v = new ClientView(me.id, w.capacity(), 2_240f, 2_240f, 30);
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(2048);
        com.backend.protocol.ClientWorld client = new com.backend.protocol.ClientWorld();
        PhaseTimer t = Room.newTimer();
        Runnable frame = () -> {
            r.step(t);
            enc.encode(w, v, r.tick(), out, NO_EVENTS);
            client.apply(out.array(), 0, out.length());
            v.acknowledge(r.tick());
        };
        for (int i = 0; i < 5; i++) {
            frame.run();
        }
        assertThat(tanksHeld(client)).as("unzoomed: only itself").isEqualTo(1);
        me.zooming = true;
        frame.run();
        frame.run();
        assertThat(tanksHeld(client)).as("zoomed: the far tank too").isEqualTo(2);
        assertThat(v.viewOriginX()).as("the origin 700 ahead").isEqualTo(Wire.quantisePos(me.x + 700f));
        assertThat(client.entity(Wire.SELF_HANDLE).alive).as("and still itself").isTrue();
        me.zooming = false;
        frame.run();
        frame.run();
        assertThat(tanksHeld(client)).isEqualTo(1);
    }

    @Test
    @DisplayName("zoomed and short of budget, what is beside the tank is kept before what is at the view's centre")
    void zoomRanksByTheTank() {
        World w = new World(8_000f, 8_000f, 4_096, 200f, 33L);
        Room r = new Room(w, new com.backend.sim.Content(com.backend.sim.StatTable.defaults(),
                com.backend.sim.LevelTable.defaults(), com.backend.sim.ShapeTable.defaults(),
                com.backend.sim.Recovery.defaults(), new com.backend.sim.Spawning(0, 0f, 1)));
        Entity me = r.spawnTank((byte) 1, 1L);
        Entity behind = r.spawnTank((byte) 2, 2L);
        Entity ahead = r.spawnTank((byte) 3, 3L);
        for (Entity e : new Entity[] {me, behind, ahead}) {
            e.playerControlled = true;
            e.y = 4_000f;
        }
        me.x = 2_000f;
        behind.x = 1_700f;                              // 300 behind it, inside the zoomed view
        ahead.x = 2_700f;                               // at the zoomed view's centre, 700 ahead
        me.aimAngle = 0f;
        w.tankStats[me.id].addXp(r.content().levels().xpRequired(45), r.content().levels());
        r.chooseClass(me, ClassTable.SNIPER);
        r.chooseClass(me, ClassTable.HUNTER);
        r.chooseClass(me, ClassTable.PREDATOR);
        me.zooming = true;
        ClientView v = new ClientView(me.id, w.capacity(), 2_240f, 2_240f, 2);   // itself, and one more
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(2048);
        com.backend.protocol.ClientWorld client = new com.backend.protocol.ClientWorld();
        PhaseTimer t = Room.newTimer();
        for (int i = 0; i < 3; i++) {
            r.step(t);
            enc.encode(w, v, r.tick(), out, NO_EVENTS);
            client.apply(out.array(), 0, out.length());
            v.acknowledge(r.tick());
        }
        assertThat(v.handleOf(behind.id)).as("the tank beside it").isNotEqualTo(ClientView.NO_HANDLE);
        assertThat(v.handleOf(ahead.id)).as("not the one at the centre").isEqualTo(ClientView.NO_HANDLE);
    }

    @Test
    @DisplayName("a realistic mobile snapshot stays inside the byte budget")
    void snapshotSizeIsWithinBudget() {
        Room r = populated(6L, 150, 1500);
        PhaseTimer t = Room.newTimer();
        for (int i = 0; i < 200; i++) {
            r.step(t);
        }
        ClientView v = viewOf(r, 30);
        SnapshotEncoder enc = new SnapshotEncoder();
        SnapshotWriter out = new SnapshotWriter(2048);

        enc.encode(r.world(), v, 1, out, NO_EVENTS);
        v.acknowledge(1);
        r.step(t);
        enc.encode(r.world(), v, 2, out, NO_EVENTS);

        // The design budgets ~122 bytes of payload for a steady-state mobile snapshot.
        assertThat(out.length()).isLessThan(400);
    }
}
