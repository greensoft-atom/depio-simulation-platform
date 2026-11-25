package com.backend.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.backend.common.RefusedConfiguration;

class WorkerMainTest {

    @Test
    @Timeout(20)
    @DisplayName("the store's port argument that is not one refuses the start before anything is made of it (O-101)")
    void aPortArgumentOutOfRangeIsRefused() {
        assertThatThrownBy(() -> WorkerMain.run(new String[] {"127.0.0.1", "70000"}))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("store port").hasMessageContaining("'70000'");
    }
}
