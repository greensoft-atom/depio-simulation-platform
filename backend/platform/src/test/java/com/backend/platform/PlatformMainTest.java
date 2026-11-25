package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.backend.common.RefusedConfiguration;

class PlatformMainTest {

    @Test
    @Timeout(20)
    @DisplayName("a port argument that is not one, its own or the store's, refuses the start before anything is made of it (O-101)")
    void aPortArgumentOutOfRangeIsRefused() {
        assertThatThrownBy(() -> PlatformMain.run(new String[] {"127.0.0.1", "70000"}))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("port").hasMessageContaining("'70000'");
        assertThatThrownBy(() -> PlatformMain.run(new String[] {"127.0.0.1", "8080", "127.0.0.1", "-1"}))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("store port");
    }
}
