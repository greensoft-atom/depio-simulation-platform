package com.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountLevelsTest {

    @Test
    @DisplayName("the ends are exact, and every level costs more than the one before it")
    void curve() {
        assertThat(AccountLevels.xpRequired(1)).isZero();
        assertThat(AccountLevels.xpRequired(2)).isEqualTo(3_000);
        assertThat(AccountLevels.xpRequired(10)).isBetween(110_000L, 117_000L);
        assertThat(AccountLevels.xpRequired(30)).isBetween(760_000L, 810_000L);
        assertThat(AccountLevels.xpRequired(100)).isEqualTo(6_000_000);
        for (int level = 3; level <= AccountLevels.MAX_LEVEL; level++) {
            // Also each step is larger than the last, or a level could be skipped by less
            // than the one before it cost: the curve only steepens.
            long step = AccountLevels.xpRequired(level) - AccountLevels.xpRequired(level - 1);
            long previous = AccountLevels.xpRequired(level - 1) - AccountLevels.xpRequired(level - 2);
            assertThat(step).as("level %d", level).isPositive();
            if (level > 3) {
                assertThat(step).as("level %d steepens", level).isGreaterThanOrEqualTo(previous);
            }
        }
    }

    @Test
    @DisplayName("a total is worth exactly the levels it has reached")
    void boundaries() {
        assertThat(AccountLevels.levelFor(-5)).isEqualTo(1);
        assertThat(AccountLevels.levelFor(0)).isEqualTo(1);
        assertThat(AccountLevels.levelFor(2_999)).isEqualTo(1);
        assertThat(AccountLevels.levelFor(3_000)).isEqualTo(2);
        for (int level = 2; level <= AccountLevels.MAX_LEVEL; level++) {
            long at = AccountLevels.xpRequired(level);
            assertThat(AccountLevels.levelFor(at)).as("at %d", level).isEqualTo(level);
            assertThat(AccountLevels.levelFor(at - 1)).as("just below %d", level).isEqualTo(level - 1);
        }
        assertThat(AccountLevels.levelFor(Long.MAX_VALUE)).as("capped").isEqualTo(AccountLevels.MAX_LEVEL);
    }
}
