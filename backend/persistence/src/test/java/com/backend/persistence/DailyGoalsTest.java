package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The daily goals' table and draw (docs 04 §8, D-66): no database. */
class DailyGoalsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 4);

    @Test
    @DisplayName("the table: seven kinds, each an easy goal then a harder one paying more")
    void theTable() {
        assertThat(DailyGoals.POOL).hasSize(7);
        for (List<DailyGoals.Goal> tiers : DailyGoals.POOL) {
            assertThat(tiers).hasSize(2);
            assertThat(tiers.get(0).kind()).isEqualTo(tiers.get(1).kind());
            assertThat(tiers.get(1).target()).as(tiers.get(1).id()).isGreaterThan(tiers.get(0).target());
            assertThat(tiers.get(1).coins()).as(tiers.get(1).id()).isGreaterThan(tiers.get(0).coins());
        }
        assertThat(DailyGoals.byId("kills_10")).isEqualTo(new DailyGoals.Goal("kills_10", DailyGoals.Kind.KILLS, 10, 100));
        assertThat(DailyGoals.byId("playtime_60m").target()).as("in seconds").isEqualTo(3_600);
        assertThat(DailyGoals.byId("nope")).isNull();
        assertThat(DailyGoals.SET_GEMS).isEqualTo(3);
    }

    @Test
    @DisplayName("a player's day: three different kinds, the same three each time asked; across players, every goal drawn")
    void theDraw() {
        Set<String> seen = new HashSet<>();
        for (long player = 1; player <= 300; player++) {
            List<DailyGoals.Goal> three = DailyGoals.of(player, DAY);
            assertThat(three).hasSize(3);
            assertThat(three.stream().map(DailyGoals.Goal::kind).distinct()).as("player %d", player).hasSize(3);
            assertThat(DailyGoals.of(player, DAY)).isEqualTo(three);
            three.forEach(g -> seen.add(g.id()));
        }
        assertThat(seen).as("all fourteen, easy and hard, over three hundred players").hasSize(14);
    }

    @Test
    @DisplayName("another day, another draw: most players' three change at midnight")
    void anotherDay() {
        int changed = 0;
        for (long player = 1; player <= 300; player++) {
            if (!DailyGoals.of(player, DAY).equals(DailyGoals.of(player, DAY.plusDays(1)))) {
                changed++;
            }
        }
        assertThat(changed).isGreaterThan(250);
    }
}
