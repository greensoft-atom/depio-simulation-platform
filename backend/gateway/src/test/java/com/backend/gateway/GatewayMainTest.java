package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.backend.common.RefusedConfiguration;

class GatewayMainTest {

    @Test
    @Timeout(20)
    @DisplayName("a port argument that is not one, its own or the store's, refuses the start before anything is made of it (O-101)")
    void aPortArgumentOutOfRangeIsRefused() {
        assertThatThrownBy(() -> GatewayMain.run(new String[] {"127.0.0.1", "70000"}))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("port").hasMessageContaining("'70000'");
        assertThatThrownBy(() -> GatewayMain.run(new String[] {"127.0.0.1", "8081", "http://127.0.0.1:8080", "127.0.0.1", "65536"}))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("store port");
    }
}
