package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.sim.ClassTable;
import com.backend.sim.Stat;

import io.netty.channel.embedded.EmbeddedChannel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a connection holds for the room between ticks. */
class ConnectionTest {

    @Test
    @DisplayName("a burst of point requests is held to the most any class allows in a stat: a Smasher's 10")
    void upgradeRequestsAreClamped() {
        Connection c = new Connection(new EmbeddedChannel());
        for (int i = 0; i < 15; i++) {
            c.requestUpgrade(Stat.BODY_DAMAGE);
        }
        c.requestUpgrade(Stat.COUNT);                       // not a stat: dropped
        assertThat(c.takeUpgradeRequests(Stat.BODY_DAMAGE)).isEqualTo(ClassTable.MOST_POINTS);
        assertThat(ClassTable.MOST_POINTS).isEqualTo(10);
        assertThat(c.takeUpgradeRequests(Stat.BODY_DAMAGE)).as("taken, and cleared").isZero();
    }
}
