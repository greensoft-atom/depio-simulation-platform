package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import com.backend.common.Metrics;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A slow lobby client's pushes (docs detailed-design/03-gateway.md §8, plan item 35). */
class PushesTest {

    private static final long UNWRITABLE_CLOSE_MILLIS = Pushes.UNWRITABLE_CLOSE_SECONDS * 1_000L;

    private final Metrics.LabeledCounter counted = new Metrics.LabeledCounter();
    private final LongAdder slowClosed = new LongAdder();
    private final Metrics.LabeledHistogram unwritable = new Metrics.LabeledHistogram(Pushes.UNWRITABLE_BUCKETS);
    private final Pushes pushes = new Pushes(counted, slowClosed, unwritable);
    private final EmbeddedChannel channel = new EmbeddedChannel(pushes);

    /** As Netty marks it with more than its high-water mark waiting to go out. */
    private void writable(boolean yes) {
        channel.unsafe().outboundBuffer().setUserDefinedWritability(1, yes);
        channel.runPendingTasks();
    }

    private List<String> sent() {
        List<String> out = new ArrayList<>();
        for (Object m; (m = channel.readOutbound()) != null; ) {
            TextWebSocketFrame f = (TextWebSocketFrame) m;
            out.add(f.text());
            f.release();
        }
        return out;
    }

    private void pass(long millis) {
        channel.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
    }

    @Test
    @DisplayName("a connection that keeps up gets each push at once")
    void atOnce() {
        pushes.offer("a", false);
        pushes.offer("b", false);
        assertThat(sent()).containsExactly("a", "b");
        assertThat(counted.get("held")).isZero();
    }

    @Test
    @DisplayName("held while it cannot take more, then sent in order when it drains")
    void heldThenSent() {
        writable(false);
        pushes.offer("a", false);
        pushes.offer("b", false);
        pushes.offer("c", false);
        assertThat(sent()).isEmpty();
        writable(true);
        assertThat(sent()).containsExactly("a", "b", "c");
        assertThat(counted.get("held")).isEqualTo(3);
        pushes.offer("d", false);
        assertThat(sent()).as("and straight through again").containsExactly("d");
    }

    @Test
    @DisplayName("past sixteen held, the backlog is dropped and one evt.resync goes instead")
    void overflow() {
        writable(false);
        for (int i = 0; i < Pushes.RING + 1; i++) {
            pushes.offer("p" + i, false);
        }
        writable(true);
        assertThat(sent()).containsExactly(Pushes.RESYNC);
        assertThat(counted.get("dropped")).as("none of the seventeen went").isEqualTo(Pushes.RING + 1);
        pushes.offer("next", false);
        assertThat(sent()).as("the resync is said once").containsExactly("next");
        writable(false);
        pushes.offer("x", false);
        writable(true);
        assertThat(sent()).as("a backlog that fits is not a resync").containsExactly("x");
    }

    @Test
    @DisplayName("unwritable for thirty seconds: closed, and counted")
    void slowIsClosed() {
        channel.freezeTime();
        writable(false);
        pass(29_999);
        assertThat(channel.isOpen()).isTrue();
        pass(1);
        assertThat(channel.isOpen()).isFalse();
        assertThat(slowClosed.sum()).isEqualTo(1);
    }

    @Test
    @DisplayName("draining in time keeps it; the thirty seconds start again each time it stalls")
    void drainingInTime() {
        channel.freezeTime();
        writable(false);
        pass(20_000);
        writable(true);
        pass(20_000);
        assertThat(channel.isOpen()).as("the first stall's close was called off").isTrue();
        writable(false);
        pass(29_000);
        assertThat(channel.isOpen()).as("thirty from this stall, not the first").isTrue();
        pass(1_000);
        assertThat(channel.isOpen()).isFalse();
        assertThat(slowClosed.sum()).isEqualTo(1);
    }

    @Test
    @DisplayName("a revocation is never held: written then closed, or closed at once while it cannot be written")
    void revoked() {
        pushes.offer("bye", true);
        assertThat(sent()).containsExactly("bye");
        assertThat(channel.isOpen()).isFalse();

        Pushes slow = new Pushes(counted, slowClosed, unwritable);
        EmbeddedChannel other = new EmbeddedChannel(slow);
        other.freezeTime();
        other.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        other.runPendingTasks();
        slow.offer("held", false);
        slow.offer("bye", true);
        Object written = other.readOutbound();
        assertThat(written).isNull();
        assertThat(other.isOpen()).isFalse();
        other.advanceTimeBy(UNWRITABLE_CLOSE_MILLIS, TimeUnit.MILLISECONDS);
        other.runScheduledPendingTasks();
        assertThat(slowClosed.sum()).as("not slow: revoked, and its slow close called off").isZero();
    }

    /** How many spells ended {@code end} lasted no more than {@code le} seconds. */
    private long spells(String end, String le) {
        Metrics m = new Metrics();
        m.labeledHistogram("t_seconds", "test", "end", unwritable);
        String prefix = "t_seconds_bucket{end=\"" + end + "\",le=\"" + le + "\"} ";
        for (String line : m.render().split("\n")) {
            if (line.startsWith(prefix)) {
                return Long.parseLong(line.substring(prefix.length()));
            }
        }
        return 0;
    }

    @Test
    @DisplayName("a spell unwritable is timed to its draining, by the connection's own clock (03 §10)")
    void drainedSpellTimed() {
        channel.freezeTime();
        writable(false);
        pass(2_000);
        writable(true);
        assertThat(unwritable.count("drained")).isEqualTo(1);
        assertThat(spells("drained", "1")).as("2 s is more than 1").isZero();
        assertThat(spells("drained", "2.5")).isEqualTo(1);
        assertThat(unwritable.count("closed")).isZero();

        writable(false);
        pass(100);
        writable(true);
        assertThat(unwritable.count("drained")).as("each spell its own").isEqualTo(2);
        assertThat(spells("drained", "0.1")).isEqualTo(1);
        channel.close();
        assertThat(unwritable.count("closed")).as("closed keeping up: no spell to end").isZero();
    }

    @Test
    @DisplayName("a spell ended by a close is timed to it: the 30 s rule's at 30 s, a client leaving at its leaving")
    void closedSpellTimed() {
        channel.freezeTime();
        writable(false);
        pass(UNWRITABLE_CLOSE_MILLIS);
        assertThat(channel.isOpen()).isFalse();
        assertThat(unwritable.count("closed")).isEqualTo(1);
        assertThat(spells("closed", "20")).isZero();
        assertThat(spells("closed", "30")).isEqualTo(1);
        assertThat(unwritable.count("drained")).isZero();

        Pushes other = new Pushes(counted, slowClosed, unwritable);
        EmbeddedChannel leaving = new EmbeddedChannel(other);
        leaving.freezeTime();
        leaving.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        leaving.runPendingTasks();
        leaving.advanceTimeBy(700, TimeUnit.MILLISECONDS);
        leaving.close();
        assertThat(unwritable.count("closed")).isEqualTo(2);
        assertThat(spells("closed", "1")).as("the one that left after 0.7 s").isEqualTo(1);
    }

    @Test
    @DisplayName("a connection that keeps up is never counted, closed or not")
    void keepingUpIsNotCounted() {
        pushes.offer("a", false);
        channel.close();
        assertThat(unwritable.count("drained") + unwritable.count("closed")).isZero();
    }
}
