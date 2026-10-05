package com.backend.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.backend.common.Xorshift;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The balance tables: what they promise, and what they refuse to be built with. */
class ContentTest {

    // ---- the experience curve ---------------------------------------------------------

    @Test
    @DisplayName("the curve passes exactly through the four anchors the design names")
    void curveHitsItsAnchors() {
        LevelTable levels = LevelTable.defaults();

        // Interpolation is what produced the levels in between, so if these drift the curve
        // has been reshaped rather than retuned, and the design document is now wrong.
        assertThat(levels.xpRequired(2)).isEqualTo(4);
        assertThat(levels.xpRequired(10)).isEqualTo(160);
        assertThat(levels.xpRequired(30)).isEqualTo(5_300);
        assertThat(levels.xpRequired(45)).isEqualTo(23_000);
        assertThat(levels.maxLevel()).isEqualTo(45);
    }

    @Test
    @DisplayName("every level costs strictly more than the one before it")
    void curveIsStrictlyIncreasing() {
        LevelTable levels = LevelTable.defaults();
        for (int level = 2; level <= levels.maxLevel(); level++) {
            assertThat(levels.xpRequired(level))
                    .as("level %d must cost more than level %d", level, level - 1)
                    .isGreaterThan(levels.xpRequired(level - 1));
        }
    }

    @Test
    @DisplayName("a level past the top is unreachable rather than free")
    void beyondMaxLevelIsUnreachable() {
        LevelTable levels = LevelTable.defaults();
        assertThat(levels.xpRequired(1)).isZero();
        assertThat(levels.xpRequired(0)).isZero();
        assertThat(levels.xpRequired(levels.maxLevel() + 1)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("levelling to the top grants 33 points, against a cap of 7 in any one stat")
    void pointBudget() {
        LevelTable levels = LevelTable.defaults();

        assertThat(levels.totalPointsBy(levels.maxLevel())).isEqualTo(33);
        assertThat(levels.pointsAt(1)).as("level 1 is where you start, not a reward").isZero();
        assertThat(levels.pointsAt(2)).isEqualTo(1);
        assertThat(levels.pointsAt(28)).isEqualTo(1);
        assertThat(levels.pointsAt(29)).as("the drip slows after 28").isZero();
        assertThat(levels.pointsAt(30)).isEqualTo(1);
        // Four stats can be maxed and a fifth cannot, which is what makes a build a choice.
        // Computed from the real table and the real cap: this used to divide two literals,
        // which exercised nothing and could only fail if someone edited the number 33.
        int budget = levels.totalPointsBy(levels.maxLevel());
        assertThat(budget).as("enough for four maxed stats")
                .isGreaterThanOrEqualTo(4 * Stat.MAX_POINTS_PER_STAT);
        assertThat(budget).as("but not five").isLessThan(5 * Stat.MAX_POINTS_PER_STAT);
    }

    // ---- stats -------------------------------------------------------------------------

    @Test
    @DisplayName("additive stats add and proportional stats scale")
    void bothGrowthModes() {
        StatTable stats = StatTable.defaults();

        // MAX_HEALTH: 50 + 2 per level + 20 per point.
        assertThat(stats.valueOf(Stat.MAX_HEALTH, 1, 0)).isEqualTo(52f);
        assertThat(stats.valueOf(Stat.MAX_HEALTH, 10, 0)).isEqualTo(70f);
        assertThat(stats.valueOf(Stat.MAX_HEALTH, 1, 3)).isEqualTo(112f);

        // RELOAD: 8 ticks, 8 % off per point, and level does not touch it.
        assertThat(stats.valueOf(Stat.RELOAD, 1, 0)).isEqualTo(8f);
        assertThat(stats.valueOf(Stat.RELOAD, 45, 0)).isEqualTo(8f);
        assertThat(stats.valueOf(Stat.RELOAD, 1, 7)).isCloseTo(8f * 0.44f, within());
    }

    @Test
    @DisplayName("a fully upgraded reload is faster but never reaches zero")
    void reloadStaysPositive() {
        StatTable stats = StatTable.defaults();
        float best = stats.valueOf(Stat.RELOAD, 45, Stat.MAX_POINTS_PER_STAT);

        // The reason RELOAD scales rather than subtracts: seven points of a flat -0.08 would
        // be fine, but any future table that subtracted more would pass through zero and out
        // the other side into a tank that fires backwards in time.
        assertThat(best).isGreaterThan(0f).isLessThan(stats.valueOf(Stat.RELOAD, 45, 0));
    }

    @Test
    @DisplayName("a table that is not eight stats long is refused")
    void statTableIsChecked() {
        assertThatThrownBy(() -> new StatTable(new StatTable.Entry[3]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8");
    }

    // ---- shapes ------------------------------------------------------------------------

    @Test
    @DisplayName("shapes are drawn in proportion to their weights")
    void weightedSpawning() {
        ShapeTable shapes = ShapeTable.defaults();
        Xorshift rng = new Xorshift(99L);
        int[] counts = new int[shapes.size()];

        int draws = 200_000;
        for (int i = 0; i < draws; i++) {
            counts[shapes.pick(rng).id()]++;
        }

        // Weights are 600/300/95/5 out of 1000. Wide bands: this asserts the distribution is
        // the shape of the table, not that a particular seed produced particular numbers.
        assertThat(counts[0] / (double) draws).isBetween(0.57, 0.63);
        assertThat(counts[1] / (double) draws).isBetween(0.27, 0.33);
        assertThat(counts[2] / (double) draws).isBetween(0.08, 0.11);
        assertThat(counts[3] / (double) draws).as("an alpha is a find").isBetween(0.003, 0.008);
    }

    @Test
    @DisplayName("experience by wire id matches the table, and an unknown id is worth nothing")
    void experienceLookup() {
        ShapeTable shapes = ShapeTable.defaults();

        assertThat(shapes.xpOf((byte) 0)).isEqualTo(10);
        assertThat(shapes.xpOf((byte) 3)).isEqualTo(3_000);
        assertThat(shapes.xpOf((byte) 99)).as("a kind this build does not know").isZero();
        assertThat(shapes.byId((byte) 2).name()).isEqualTo("pentagon");
        assertThat(shapes.byId((byte) 99)).isNull();
    }

    @Test
    @DisplayName("a shape table nothing can spawn from is refused at construction")
    void shapeTableIsChecked() {
        ShapeTable.Type weightless =
                new ShapeTable.Type((byte) 0, "ghost", 10f, 1f, 1f, 1f, 1, 0);

        assertThatThrownBy(() -> new ShapeTable(new ShapeTable.Type[0]))
                .isInstanceOf(IllegalArgumentException.class);
        // Every weight zero would otherwise be a room that silently never fills, which looks
        // like a spawning bug rather than a table someone mis-edited.
        assertThatThrownBy(() -> new ShapeTable(new ShapeTable.Type[] {weightless}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight");
    }

    @Test
    @DisplayName("the content knows the largest shape, which is what collision reach needs")
    void largestShapeIsKnown() {
        assertThat(Content.defaults().maxShapeRadius())
                .isEqualTo(ShapeTable.defaults().byId((byte) 3).radius())
                .isEqualTo(80f);
    }

    private static org.assertj.core.data.Offset<Float> within() {
        return org.assertj.core.data.Offset.offset(0.0001f);
    }
}
