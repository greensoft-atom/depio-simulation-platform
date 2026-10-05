package com.backend.examples.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RuntimeProbeTest {

    @Test
    @DisplayName("on the full JDK that runs the tests, every check of the probe holds")
    void allHoldOnTheJdk() {
        assertThat(new RuntimeProbe().run()).isZero();
    }
}
