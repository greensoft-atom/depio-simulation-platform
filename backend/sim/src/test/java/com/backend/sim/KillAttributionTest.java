package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.common.PhaseTimer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Who gets credited for a kill.
 *
 * The case worth testing is not "a bullet killed a tank" — it is a bullet that outlives its
 * shooter, which happens constantly, and whose pool slot is reused by someone else before it
 * lands.
 */
class KillAttributionTest {

    private static final PhaseTimer TIMER = Room.newTimer();

    private static void run(Room r, int ticks) {
        for (int i = 0; i < ticks; i++) {
            r.step(TIMER);
        }
    }

    @Test
    @DisplayName("a bullet carries its shooter's tag, not the shooter's slot")
    void bulletCarriesTheTag() {
        World w = new World(2_000f, 2_000f, 256, 100f, 7L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        Entity shooter = r.spawnTank((byte) 0, 4242L);
        shooter.playerControlled = true;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;

        run(r, 1);

        assertThat(w.bullets.size).isPositive();
        Entity bullet = w.entities[w.bullets.items[0]];
        assertThat(bullet.playerTag).isEqualTo(4242L);
        assertThat(bullet.ownerId).isEqualTo(shooter.id);
    }

    @Test
    @DisplayName("a kill by a dead shooter whose slot was reused is credited to the shooter")
    void slotReuseDoesNotStealTheKill() {
        World w = new World(2_000f, 2_000f, 64, 200f, 11L);
        Room r = new Room(w, Fixtures.UNPROTECTED);

        // Shooter at point blank range, aimed at the victim.
        Entity shooter = r.spawnTank((byte) 0, 1001L);
        Entity victim = r.spawnTank((byte) 1, 2002L);
        shooter.x = 500f;
        shooter.y = 500f;
        victim.x = 620f;
        victim.y = 500f;
        victim.hp = 1f;                          // one hit is fatal
        shooter.playerControlled = true;
        shooter.aimAngle = 0f;
        shooter.angle = 0f;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        victim.playerControlled = true;          // so it does not wander off or shoot back

        run(r, 1);                               // the bullet exists now
        int shooterSlot = shooter.id;
        w.kill(shooter);                         // the shooter dies while the shot is in flight
        run(r, 1);                               // sweep recycles the slot

        // Someone else takes the freed slot, exactly as a respawn would.
        Entity newcomer = r.spawnTank((byte) 0, 3003L);
        assertThat(newcomer.id).as("the test needs the slot to be reused").isEqualTo(shooterSlot);
        newcomer.x = 100f;
        newcomer.y = 100f;
        // Spelled out rather than argued: a slot lookup now resolves to the newcomer, so if
        // attribution went through ownerId the kill below would be credited to 3003.
        assertThat(w.entities[shooterSlot].playerTag).isEqualTo(3003L);

        long credited = -1;
        for (int i = 0; i < 40 && credited < 0; i++) {
            r.step(TIMER);
            for (int k = 0; k < r.kills().size(); k++) {
                if (r.kills().victimTag(k) == 2002L) {
                    credited = r.kills().killerTag(k);
                }
            }
            r.kills().clear();
        }

        // By slot this would read 3003 — the newcomer would be credited with a kill made by
        // a player who had already left.
        assertThat(credited).as("credited to the shooter, not to whoever inherited the slot")
                .isEqualTo(1001L);
    }

    /**
     * A shooter at (500, 500) aimed along +x has fired once, died, and had its slot swept.
     * Returns the bullet still in flight; the shooter's slot is free for the next spawn.
     */
    private static Entity orphanedBullet(World w, Room r) {
        Entity shooter = r.spawnTank((byte) 0, 1001L);
        shooter.x = 500f;
        shooter.y = 500f;
        shooter.playerControlled = true;
        shooter.aimAngle = 0f;
        shooter.angle = 0f;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;
        run(r, 1);
        assertThat(w.bullets.size).isEqualTo(1);
        Entity bullet = w.entities[w.bullets.items[0]];
        w.kill(shooter);
        w.sweep();
        return bullet;
    }

    @Test
    @DisplayName("a bullet hits a tank that inherited its dead shooter's slot")
    void bulletHitsTheTankInItsShootersSlot() {
        World w = new World(2_000f, 2_000f, 64, 200f, 21L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        Entity bullet = orphanedBullet(w, r);
        int shooterSlot = bullet.ownerId;

        Entity newcomer = r.spawnTank((byte) 1, 3003L);
        assertThat(newcomer.id).as("the test needs the slot reused").isEqualTo(shooterSlot);
        newcomer.x = bullet.x + 40f;             // dead ahead
        newcomer.y = bullet.y;
        newcomer.hp = 1f;
        newcomer.playerControlled = true;        // still, and not shooting

        run(r, 5);

        // The collision pass used to skip any target in the bullet's owner SLOT, as if it
        // were the shooter. So the bullet flew straight through whoever sat there next —
        // while the credit check, 88 lines further down the same file, already compared
        // the generation. Measured before the fix: hp 1.0 -> 1.19, regen only.
        assertThat(newcomer.alive).as("hit: this is not the tank that fired").isFalse();
    }

    @Test
    @DisplayName("a bullet hits a shape that inherited its dead shooter's slot")
    void bulletHitsTheShapeInItsShootersSlot() {
        World w = new World(2_000f, 2_000f, 64, 200f, 22L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        Entity bullet = orphanedBullet(w, r);

        // The common case in a live room, not a contrived one: a destroyed shape is replaced
        // at once, so a slot freed by a tank is most often taken by a shape.
        Entity shape = r.spawnShape();
        assertThat(shape.id).isEqualTo(bullet.ownerId);
        shape.x = bullet.x + 40f;
        shape.y = bullet.y;
        shape.vx = 0f;
        shape.vy = 0f;
        shape.hp = 1f;

        run(r, 5);

        assertThat(shape.alive).isFalse();
    }

    @Test
    @DisplayName("bullets from different shooters that shared a slot still collide")
    void bulletsOfSuccessiveSlotOwnersCollide() {
        World w = new World(2_000f, 2_000f, 64, 200f, 23L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        Entity first = orphanedBullet(w, r);

        // The next occupant of the slot fires straight back along the same line.
        Entity newcomer = r.spawnTank((byte) 1, 3003L);
        assertThat(newcomer.id).isEqualTo(first.ownerId);
        newcomer.x = first.x + 200f;
        newcomer.y = first.y;
        newcomer.playerControlled = true;
        newcomer.aimAngle = (float) Math.PI;
        newcomer.angle = (float) Math.PI;
        newcomer.wantsFire = true;
        newcomer.reloadTicks = 0;
        run(r, 1);
        Entity second = null;
        for (int i = 0; i < w.bullets.size; i++) {
            Entity b = w.entities[w.bullets.items[i]];
            if (b != first) {
                second = b;
            }
        }
        assertThat(second).isNotNull();
        assertThat(second.ownerId).as("same owner slot, different shooter").isEqualTo(first.ownerId);
        float before = first.hp + second.hp;

        run(r, 12);

        // "Same slot" was read as "same shooter", so they passed through each other.
        boolean interacted = !first.alive || !second.alive || first.hp + second.hp < before;
        assertThat(interacted).as("two different players' bullets meet").isTrue();
    }

    @Test
    @DisplayName("a tank removed between ticks does not fire on the next one")
    void aRemovedTankDoesNotFire() {
        World w = new World(2_000f, 2_000f, 64, 200f, 24L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        Entity tank = r.spawnTank((byte) 0, 1001L);
        tank.playerControlled = true;
        tank.wantsFire = true;
        tank.reloadTicks = 0;

        // What the room thread does when a player leaves: it kills the tank from outside
        // the step, before the step runs. The update phases then walked the tank list with
        // no alive check, and the departed player fired one more bullet — carrying their tag,
        // after their result had already been published.
        w.kill(tank);
        run(r, 1);

        assertThat(w.bullets.size).as("no bullet from a player who has gone").isZero();
    }

    @Test
    @DisplayName("shape kills are recorded separately from tank kills")
    void shapeKillsAreDistinguishable() {
        World w = new World(2_000f, 2_000f, 256, 100f, 13L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        Entity shooter = r.spawnTank((byte) 0, 5005L);
        shooter.x = 500f;
        shooter.y = 500f;
        shooter.playerControlled = true;
        shooter.aimAngle = 0f;
        shooter.angle = 0f;
        shooter.wantsFire = true;
        shooter.reloadTicks = 0;

        Entity shape = r.spawnShape();
        shape.x = 600f;
        shape.y = 500f;
        shape.vx = 0f;
        shape.vy = 0f;
        shape.hp = 1f;

        byte kind = -1;
        for (int i = 0; i < 40 && kind < 0; i++) {
            r.step(TIMER);
            for (int k = 0; k < r.kills().size(); k++) {
                if (r.kills().killerTag(k) == 5005L) {
                    kind = r.kills().victimKind(k);
                }
            }
            r.kills().clear();
        }

        assertThat(kind).isEqualTo(Entity.KIND_SHAPE);
    }

    @Test
    @DisplayName("nothing is recorded when no player is involved")
    void botsOnlyRecordNothing() {
        World w = new World(1_500f, 1_500f, 1_024, 100f, 17L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        for (int i = 0; i < 20; i++) {
            r.spawnTank((byte) (i % 2));         // no tags: bots
        }
        for (int i = 0; i < 40; i++) {
            r.spawnShape();
        }

        int recorded = 0;
        for (int i = 0; i < 300; i++) {
            r.step(TIMER);
            recorded += r.kills().size();
            r.kills().clear();
        }

        // The comment used to say "kills certainly happened" and nothing checked it, so this
        // passed just as well in a room where nothing ever died. Experience is only ever
        // earned by killing something, so a positive total is the proof.
        long earned = 0;
        for (TankStats stats : w.tankStats) {
            if (stats != null) {
                earned += stats.xp;
            }
        }
        assertThat(earned).as("kills did happen").isPositive();
        assertThat(recorded).as("and a room full of bots credits nobody for them").isZero();
    }

    @Test
    @DisplayName("resetting for a new match empties the world and puts the shapes back")
    void resetForNewMatch() {
        World w = new World(2_000f, 2_000f, 512, 100f, 19L);
        Room r = new Room(w, Fixtures.UNPROTECTED);
        for (int i = 0; i < 5; i++) {
            r.spawnTank((byte) 0, 100L + i);
        }
        for (int i = 0; i < 30; i++) {
            r.spawnShape();
        }
        run(r, 10);
        assertThat(w.tanks.size).isEqualTo(5);

        r.resetForNewMatch(30);

        assertThat(w.tanks.size).as("every tank is gone, players included").isZero();
        assertThat(w.bullets.size).as("and so are the shots still in the air").isZero();
        assertThat(w.shapes.size).isEqualTo(30);
        assertThat(r.kills().size()).isZero();
        assertThat(w.liveCount()).isEqualTo(30);
    }
}
