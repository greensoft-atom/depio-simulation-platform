package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import com.backend.common.RefusedConfiguration;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The packs of gems sold for money (04 §8, revenue, D-67). */
class PacksTest {

    private static Packs read(String json) throws Exception {
        return Packs.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("packs are read in their order and found by their id")
    void readsPacks() throws Exception {
        Packs packs = read("""
                [ {"productId":"gems_80","gems":80,"priceCents":99},
                  {"productId":"gems_500","gems":500,"priceCents":499} ]
                """);
        assertThat(packs.all()).extracting(Packs.Pack::productId).containsExactly("gems_80", "gems_500");
        assertThat(packs.find("gems_500").gems()).isEqualTo(500);
        assertThat(packs.find("gems_500").priceCents()).isEqualTo(499);
        assertThat(packs.find("nope")).isNull();
    }

    @Test
    @DisplayName("a pack that breaks a rule stops the start: an unknown or missing field, a product twice, a number out of range, an id not lower case")
    void refusesWhatBreaksARule() {
        for (String bad : new String[] {
                "{}",
                "[ {\"productId\":\"gems_80\",\"gems\":80,\"priceCents\":99,\"bonus\":80} ]",
                "[ {\"productId\":\"gems_80\",\"gems\":80} ]",
                "[ {\"productId\":\"gems_80\",\"gems\":80,\"priceCents\":99}, {\"productId\":\"gems_80\",\"gems\":81,\"priceCents\":99} ]",
                "[ {\"productId\":\"gems_80\",\"gems\":0,\"priceCents\":99} ]",
                "[ {\"productId\":\"gems_80\",\"gems\":100001,\"priceCents\":99} ]",
                "[ {\"productId\":\"gems_80\",\"gems\":80,\"priceCents\":0} ]",
                "[ {\"productId\":\"gems_80\",\"gems\":80,\"priceCents\":100001} ]",
                "[ {\"productId\":\"gems_80\",\"gems\":80.5,\"priceCents\":99} ]",
                "[ {\"productId\":\"Gems_80\",\"gems\":80,\"priceCents\":99} ]",
                "[ {\"productId\":\"" + "g".repeat(65) + "\",\"gems\":80,\"priceCents\":99} ]",
        }) {
            assertThatThrownBy(() -> read(bad)).as(bad).isInstanceOf(RefusedConfiguration.class)
                    .hasMessageContaining("packs.json");
        }
    }

    @Test
    @DisplayName("the extremes are allowed: 1 and 100 000, and an id of 64")
    void theExtremes() throws Exception {
        String id = "g".repeat(64);
        Packs packs = read("[ {\"productId\":\"" + id + "\",\"gems\":1,\"priceCents\":100000},"
                + " {\"productId\":\"b\",\"gems\":100000,\"priceCents\":1} ]");
        assertThat(packs.find(id).gems()).isEqualTo(1);
        assertThat(packs.find("b").gems()).isEqualTo(100_000);
    }

    @Test
    @DisplayName("the release's packs are 04 §8's first cut")
    void theShippedPacks() {
        assertThat(Packs.fromClasspath().all())
                .extracting(Packs.Pack::productId, Packs.Pack::gems, Packs.Pack::priceCents)
                .containsExactly(Tuple.tuple("gems_80", 80, 99), Tuple.tuple("gems_500", 500, 499),
                        Tuple.tuple("gems_1100", 1_100, 999), Tuple.tuple("gems_2400", 2_400, 1_999),
                        Tuple.tuple("gems_6500", 6_500, 4_999));
    }
}
