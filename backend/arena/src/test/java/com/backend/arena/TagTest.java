package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.backend.sim.Entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tag's conversions (01 §8.8, Q-30). */
class TagTest {

    private static Tag sixOfThem() {
        Tag t = new Tag();
        for (long p = 1; p <= 6; p++) {
            t.join(p, p <= 3 ? 1 : 2);
        }
        return t;
    }

    @Test
    @DisplayName("a player killed by a player of the other team goes over to it; a kill by nobody, or of nobody, converts nobody")
    void conversions() {
        Tag t = sixOfThem();
        t.onKill(1L, 4L, Entity.KIND_TANK);
        assertThat(t.teamOf(4L)).isEqualTo(1);
        t.onKill(0L, 5L, Entity.KIND_TANK);
        assertThat(t.teamOf(5L)).as("a shape's kill").isEqualTo(2);
        t.onKill(2L, 0L, Entity.KIND_TANK);
        assertThat(t.teamOf(2L)).as("the arena's own tank killed: nothing").isEqualTo(1);
        t.onKill(3L, 6L, Entity.KIND_SHAPE);
        assertThat(t.teamOf(6L)).as("not a tank").isEqualTo(2);
        t.onKill(5L, 4L, Entity.KIND_TANK);
        assertThat(t.teamOf(4L)).as("and back again, killed by team 2").isEqualTo(2);
        t.onKill(99L, 6L, Entity.KIND_TANK);
        assertThat(t.teamOf(6L)).as("by nobody playing").isEqualTo(2);
        t.onKill(1L, 5L, Entity.KIND_TANK);
        t.join(5L, 2);
        assertThat(t.teamOf(5L)).as("one who comes back keeps where they went").isEqualTo(1);
        assertThat(t.teamOf(42L)).as("nobody playing: no team").isZero();
    }

    @Test
    @DisplayName("one team when every player still playing is on it; each team's head count")
    void oneTeam() {
        Tag t = sixOfThem();
        List<Long> all = List.of(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(t.oneTeam(all)).isFalse();
        assertThat(t.heads(all)).isEqualTo(Map.of(1, 3, 2, 3));
        t.onKill(1L, 4L, Entity.KIND_TANK);
        t.onKill(1L, 5L, Entity.KIND_TANK);
        assertThat(t.heads(all)).isEqualTo(Map.of(1, 5, 2, 1));
        assertThat(t.oneTeam(all)).isFalse();
        assertThat(t.oneTeam(List.of(1L, 2L, 3L, 4L, 5L))).as("6 left: those still playing are one team").isTrue();
        t.onKill(2L, 6L, Entity.KIND_TANK);
        assertThat(t.oneTeam(all)).isTrue();
        assertThat(t.heads(all)).isEqualTo(Map.of(1, 6));
    }
}
