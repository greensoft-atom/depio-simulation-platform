package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import com.backend.common.RefusedConfiguration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The season pass's boosts are items the platform has (04 §8, D-69). */
class PassServiceTest {

    @Test
    @DisplayName("the release's items have every boost the pass names; a table without one stops the start")
    void thePassNamesItemsThatExist() throws Exception {
        assertThatCode(() -> PassService.checkItems(Items.fromClasspath())).doesNotThrowAnyException();
        Items without = Items.read(new ByteArrayInputStream("""
                [ {"id":"boost_xp_hour","type":"BOOST","kind":"xp","percent":100,"minutes":60} ]
                """.getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> PassService.checkItems(without)).isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("boost_coins_hour");
    }
}
