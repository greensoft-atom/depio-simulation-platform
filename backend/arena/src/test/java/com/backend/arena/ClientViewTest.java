package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientViewTest {

    @Test
    @DisplayName("a handle stands for the incarnation it was given to: the slot's next occupant has none yet (P-13, 01 §9)")
    void aHandleIsHeldForOneIncarnation() {
        // A client that is not sent snapshots, backgrounded, keeps the handle of an entity long
        // gone; the slot's next occupant, a phrase's speaker, must not be named by it.
        ClientView v = new ClientView(0, 64, 1_600f, 1_600f, 30);
        int h = v.allocateHandle(5, (short) 1);
        assertThat(h).isNotEqualTo(ClientView.NO_HANDLE);
        assertThat(v.heldHandle(5, (short) 1)).isEqualTo(h);
        assertThat(v.heldHandle(5, (short) 2)).as("the slot's next occupant").isEqualTo(ClientView.NO_HANDLE);
        assertThat(v.heldHandle(6, (short) 1)).as("never given one").isEqualTo(ClientView.NO_HANDLE);
    }

    @Test
    @DisplayName("an input's ticks: each tick the room applies the same seq counts, a new seq starts again at 1, and 255 is the most (D-62)")
    void anInputsTicksAreCounted() {
        ClientView v = new ClientView(0, 64, 1_600f, 1_600f, 30);
        assertThat(v.inputTicks()).as("before any input").isZero();
        v.inputApplied(7);
        v.inputApplied(7);
        v.inputApplied(7);
        assertThat(v.inputTicks()).isEqualTo(3);
        v.inputApplied(8);
        assertThat(v.inputTicks()).as("a new input").isEqualTo(1);
        for (int i = 0; i < 300; i++) {
            v.inputApplied(8);
        }
        assertThat(v.inputTicks()).as("a byte on the wire").isEqualTo(255);
        v.inputApplied(8 + (1 << 24));
        assertThat(v.inputTicks()).as("the same seq, as the wire carries it: 24 bits").isEqualTo(255);
        v.inputApplied(0);
        assertThat(v.inputTicks()).as("a wrap to 0 is a new input").isEqualTo(1);
    }

    @Test
    @DisplayName("a motion rule is told when it is not what the view was last told: first, then on a change of either (D-62)")
    void aMotionRuleIsToldOnChange() {
        ClientView v = new ClientView(0, 64, 1_600f, 1_600f, 30);
        assertThat(v.motionRuleChanged(0.16f, 30f)).as("a view's first").isTrue();
        assertThat(v.motionRuleChanged(0.16f, 30f)).isFalse();
        assertThat(v.motionRuleChanged(0.1712f, 30f)).as("a point in movement speed").isTrue();
        assertThat(v.motionRuleChanged(0.1712f, 30f)).isFalse();
        assertThat(v.motionRuleChanged(0.1712f, 36f)).as("a bigger body").isTrue();
        assertThat(v.motionRuleChanged(0.1712f, 36f)).isFalse();
    }
}
