package com.backend.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MatchModeTest {

    @Test
    @DisplayName("domination: queued, three a side, five minutes, no kills end it, unrated (Q-29)")
    void domination() {
        MatchMode m = MatchMode.ofKey("domination");
        assertThat(m).isSameAs(MatchMode.DOMINATION);
        assertThat(m.id).as("on the wire and in MySQL: a new id").isEqualTo(6);
        assertThat(m.queued()).isTrue();
        assertThat(m.teams()).isTrue();
        assertThat(m.teamSize).isEqualTo(3);
        assertThat(m.roster).isEqualTo(6);
        assertThat(m.durationSeconds).isEqualTo(300);
        assertThat(m.winKills).isZero();
        assertThat(m.rated).isFalse();
    }

    @Test
    @DisplayName("tag: queued, three a side, five minutes, no kills end it, unrated (Q-30)")
    void tag() {
        MatchMode m = MatchMode.ofKey("tag");
        assertThat(m).isSameAs(MatchMode.TAG);
        assertThat(m.id).as("on the wire and in MySQL: a new id").isEqualTo(7);
        assertThat(m.queued()).isTrue();
        assertThat(m.teams()).isTrue();
        assertThat(m.teamSize).isEqualTo(3);
        assertThat(m.roster).isEqualTo(6);
        assertThat(m.durationSeconds).isEqualTo(300);
        assertThat(m.winKills).isZero();
        assertThat(m.rated).isFalse();
    }

    @Test
    @DisplayName("maze: queued, eight each for themselves, four minutes, no kills end it, unrated (Q-31)")
    void maze() {
        MatchMode m = MatchMode.ofKey("maze");
        assertThat(m).isSameAs(MatchMode.MAZE);
        assertThat(m.id).as("on the wire and in MySQL: a new id").isEqualTo(8);
        assertThat(m.queued()).isTrue();
        assertThat(m.teams()).isFalse();
        assertThat(m.roster).isEqualTo(8);
        assertThat(m.mapSize).isEqualTo(3_000f);
        assertThat(m.durationSeconds).isEqualTo(240);
        assertThat(m.winKills).isZero();
        assertThat(m.rated).isFalse();
    }

    @Test
    @DisplayName("sandbox: made, never queued; a party at most, 2 000 units, 60 shapes, twenty minutes, unrated (Q-32)")
    void sandbox() {
        MatchMode m = MatchMode.ofKey("sandbox");
        assertThat(m).isSameAs(MatchMode.SANDBOX);
        assertThat(m.id).as("on the wire: a new id").isEqualTo(9);
        assertThat(m.queued()).as("opened by a request, never queued for").isFalse();
        assertThat(m.made()).isTrue();
        assertThat(m.places()).as("a party's most").isEqualTo(3);
        assertThat(m.teams()).isFalse();
        assertThat(m.mapSize).isEqualTo(2_000f);
        assertThat(m.shapes).isEqualTo(60);
        assertThat(m.durationSeconds).isEqualTo(1_200);
        assertThat(m.winKills).isZero();
        assertThat(m.rated).isFalse();
    }

    @Test
    @DisplayName("every mode but the public arena is made; a made room holds its roster, but a sandbox's")
    void madeAndPlaces() {
        for (MatchMode m : MatchMode.values()) {
            assertThat(m.made()).as(m.key).isEqualTo(m != MatchMode.FFA);
            if (m != MatchMode.SANDBOX) {
                assertThat(m.places()).as(m.key).isEqualTo(m.roster);
            }
        }
    }
}
