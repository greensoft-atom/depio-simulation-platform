package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stepping a client's traffic down when its link cannot carry it, and back up when it can
 * (02 §8), driven through a simulated link: a delay each way, a downstream capacity, and a
 * client that applies each snapshot as it arrives and acknowledges ten times a second, as the
 * contract asks (§9). What is checked is what a player gets: the profile, and how stale the
 * newest snapshot on their screen is.
 */
class TrafficControlTest {

    private static final long MS = 1_000_000L;
    private static final long TICK = 40 * MS;

    /** The size of a frame on the link, packet overhead included, by the entities it may hold. */
    private static int bytes(TrafficProfile p) {
        return p.budget * 3 + 60;          // mobile 150 B at 15 Hz = 2.25 KB/s, saver 1.2 KB/s
    }

    /** One client on one link, run tick by tick at the room's 25 Hz. */
    private static final class Run {
        final TrafficControl control;
        final long oneWay;
        double bytesPerSecond;
        long busyUntil;
        final ArrayDeque<long[]> inTransit = new ArrayDeque<>();   // {tick, deliveredAt}
        final ArrayDeque<long[]> acks = new ArrayDeque<>();        // {tick, arrivesAt}
        int applied = -1;
        int serverAck = -1;
        long worstStaleness;
        final int[] ticksPerProfile = new int[TrafficProfile.values().length];
        int held;
        boolean acking = true;
        boolean writable = true;
        int tick;

        Run(TrafficProfile ceiling, long oneWayMillis, double bytesPerSecond) {
            this.control = new TrafficControl(ceiling);
            this.oneWay = oneWayMillis * MS;
            this.bytesPerSecond = bytesPerSecond;
        }

        long now() {
            return tick * TICK;
        }

        Run seconds(double s) {
            int end = tick + (int) Math.round(s * 25);
            for (; tick < end; tick++) {
                step();
            }
            return this;
        }

        private void step() {
            long now = now();
            while (!inTransit.isEmpty() && inTransit.peek()[1] <= now) {
                applied = (int) inTransit.poll()[0];
            }
            if (acking && applied >= 0 && now % (100 * MS) == 0) {
                acks.add(new long[] {applied, now + oneWay});
            }
            while (!acks.isEmpty() && acks.peek()[1] <= now) {
                serverAck = Math.max(serverAck, (int) acks.poll()[0]);
            }
            // How old the newest state on the player's screen is.
            if (applied >= 0) {
                worstStaleness = Math.max(worstStaleness, now - applied * TICK);
            }
            ticksPerProfile[control.profile().ordinal()]++;
            if (!control.due(25)) {
                return;
            }
            if (serverAck >= 0) {
                control.acknowledged(serverAck, now);
            }
            if (!control.round(now, writable)) {
                held++;
                return;
            }
            int size = bytes(control.profile());
            control.sent(tick, now, size);
            long start = Math.max(now, busyUntil);
            busyUntil = start + (long) (size / bytesPerSecond * 1e9);
            inTransit.add(new long[] {tick, busyUntil + oneWay});
        }

        double shareAt(TrafficProfile p) {
            int total = 0;
            for (int n : ticksPerProfile) {
                total += n;
            }
            return ticksPerProfile[p.ordinal()] / (double) total;
        }

        void resetWatch() {
            worstStaleness = 0;
            java.util.Arrays.fill(ticksPerProfile, 0);
            held = 0;
        }
    }

    @Test
    @DisplayName("a link with room to spare is left alone, near or far")
    void healthyLinksAreLeftAlone() {
        Run near = new Run(TrafficProfile.MOBILE, 30, 1_000_000).seconds(120);
        Run far = new Run(TrafficProfile.MOBILE, 300, 1_000_000).seconds(120);

        for (Run r : new Run[] {near, far}) {
            assertThat(r.control.profile()).isEqualTo(TrafficProfile.MOBILE);
            assertThat(r.control.stepsDown()).as("a delay the link always had is not a queue").isZero();
            assertThat(r.held).isZero();
        }
    }

    @Test
    @DisplayName("a link slower than the stream steps down, and what the player sees stays fresh")
    void aSlowLinkStepsDown() {
        // 1.8 KB/s: less than mobile's 2.25, more than saver's 1.2.
        Run r = new Run(TrafficProfile.MOBILE, 40, 1_800).seconds(5);
        assertThat(r.control.profile()).as("within five seconds").isEqualTo(TrafficProfile.SAVER);

        r.resetWatch();
        r.seconds(120);
        // It tries mobile again every ten seconds and comes straight back: mostly saver.
        assertThat(r.shareAt(TrafficProfile.SAVER)).isGreaterThan(0.75);
        assertThat(r.worstStaleness).as("the newest state a player sees").isLessThan(1_600 * MS);
    }

    @Test
    @DisplayName("a link that recovers is stepped back up, one step per ten seconds, to the ceiling")
    void aRecoveredLinkStepsBackUp() {
        Run r = new Run(TrafficProfile.HIGH, 40, 1_000).seconds(10);
        assertThat(r.control.profile()).isEqualTo(TrafficProfile.SAVER);

        r.bytesPerSecond = 1_000_000;
        r.seconds(9);
        assertThat(r.control.profile()).as("not before ten healthy seconds").isEqualTo(TrafficProfile.SAVER);
        r.seconds(12);
        assertThat(r.control.profile()).isEqualTo(TrafficProfile.MOBILE);
        r.seconds(10);
        assertThat(r.control.profile()).isEqualTo(TrafficProfile.HIGH);
        r.seconds(60);
        assertThat(r.control.profile()).as("and never above the ceiling").isEqualTo(TrafficProfile.HIGH);
    }

    @Test
    @DisplayName("a link that stays slow is tried again less and less often, and a recovery is still found")
    void failedProbesBackOff() {
        Run r = new Run(TrafficProfile.MOBILE, 40, 1_800).seconds(300);
        // Every probe costs the player a moment of queue; 10, 20, 40, 80 and 160 s apart they
        // are five in five minutes, not thirty.
        assertThat(r.control.stepsUp()).isBetween(3, 6);

        r.bytesPerSecond = 1_000_000;
        r.seconds(175);
        assertThat(r.control.profile()).as("within the longest wait of a recovery")
                .isEqualTo(TrafficProfile.MOBILE);
    }

    @Test
    @DisplayName("a player who chose saver is never raised, however good the link")
    void saverIsNeverRaised() {
        Run r = new Run(TrafficProfile.SAVER, 20, 1_000_000).seconds(120);

        assertThat(r.control.profile()).isEqualTo(TrafficProfile.SAVER);
        assertThat(r.control.stepsUp()).isZero();
    }

    @Test
    @DisplayName("a link between high and mobile settles on mobile")
    void settlesOnWhatTheLinkCarries() {
        // 3 KB/s: high needs 3.6, mobile 2.25.
        Run r = new Run(TrafficProfile.HIGH, 40, 3_000).seconds(10);
        assertThat(r.control.profile()).isEqualTo(TrafficProfile.MOBILE);
        r.resetWatch();
        r.seconds(120);
        assertThat(r.shareAt(TrafficProfile.MOBILE)).isGreaterThan(0.75);
        assertThat(r.shareAt(TrafficProfile.SAVER)).as("not further than it needs").isLessThan(0.05);
    }

    @Test
    @DisplayName("while more than a second is queued, nothing is added to it")
    void aFullQueueIsNotAddedTo() {
        // Saver's 1.2 KB/s into 0.6: even the lowest step is too much. What went out before
        // the first late acknowledgement cannot be recalled, and drains at the link's pace:
        // some seconds, once. After that nothing is added to a queue already a second long.
        Run r = new Run(TrafficProfile.MOBILE, 40, 600).seconds(10);
        assertThat(r.control.profile()).isEqualTo(TrafficProfile.SAVER);

        r.resetWatch();
        r.seconds(60);
        assertThat(r.held).as("rounds held back").isPositive();
        assertThat(r.worstStaleness).isLessThan(2_000 * MS);
    }

    @Test
    @DisplayName("a link slow for the whole session stays held to its bound, not only for its first minutes")
    void aLongCongestionDoesNotMoveTheFloor() {
        // A queue that never empties must not become the link's idea of an empty one.
        Run r = new Run(TrafficProfile.MOBILE, 40, 600).seconds(10);
        r.resetWatch();
        r.seconds(600);
        assertThat(r.worstStaleness).isLessThan(2_000 * MS);
    }

    @Test
    @DisplayName("a pause with nothing sent, such as a death, is not mistaken for a queue")
    void aPauseIsNotAQueue() {
        Run r = new Run(TrafficProfile.MOBILE, 40, 1_000_000).seconds(5);
        r.control.idle();
        r.tick += 10 * 25;                      // ten seconds dead: nothing sent, nothing acked
        r.inTransit.clear();
        r.seconds(10);

        assertThat(r.control.stepsDown()).isZero();
        assertThat(r.held).isZero();
    }

    @Test
    @DisplayName("before the first acknowledgement nothing is judged, and a client that never acks is left as it is")
    void noAcksNoJudgement() {
        Run r = new Run(TrafficProfile.MOBILE, 40, 1_000);
        r.acking = false;
        r.seconds(30);

        assertThat(r.control.profile()).isEqualTo(TrafficProfile.MOBILE);
        assertThat(r.held).isZero();
    }

    @Test
    @DisplayName("fifteen rounds in twenty-five ticks at 15 Hz, ten at 10 Hz, with no drift")
    void roundsKeepTheirRate() {
        TrafficControl mobile = new TrafficControl(TrafficProfile.MOBILE);
        TrafficControl saver = new TrafficControl(TrafficProfile.SAVER);
        int fast = 0;
        int slow = 0;
        for (int tick = 0; tick < 25 * 3_600; tick++) {           // an hour of ticks
            fast += mobile.due(25) ? 1 : 0;
            slow += saver.due(25) ? 1 : 0;
        }
        assertThat(fast).isEqualTo(15 * 3_600);
        assertThat(slow).isEqualTo(10 * 3_600);
    }

    @Test
    @DisplayName("two unwritable rounds in a row step down; one does not; neither is sent")
    void unwritableRounds() {
        TrafficControl c = new TrafficControl(TrafficProfile.MOBILE);
        c.sent(0, 0, 100);
        c.acknowledged(0, 50 * MS);

        assertThat(c.round(100 * MS, false)).isFalse();
        assertThat(c.round(166 * MS, true)).isTrue();
        assertThat(c.profile()).isEqualTo(TrafficProfile.MOBILE);

        assertThat(c.round(233 * MS, false)).isFalse();
        assertThat(c.round(300 * MS, false)).isFalse();
        assertThat(c.profile()).isEqualTo(TrafficProfile.SAVER);
    }
}
