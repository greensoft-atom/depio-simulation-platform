package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.common.RefusedConfiguration;

class ArenaMainTest {

    @Test
    @DisplayName("an arena announces the port players dial: BACKEND_ADVERTISE_PORT when set (D-82), else none")
    void theAnnouncedPort() {
        assertThat(ArenaMain.advertisedPort(Map.of())).as("unset: the port it listens on").isNull();
        assertThat(ArenaMain.advertisedPort(Map.of("BACKEND_ADVERTISE_PORT", " "))).isNull();
        assertThat(ArenaMain.advertisedPort(Map.of("BACKEND_ADVERTISE_PORT", "9001"))).isEqualTo(9001);
        assertThat(ArenaMain.advertisedPort(Map.of("BACKEND_ADVERTISE_PORT", "65535"))).isEqualTo(65535);
    }

    @Test
    @DisplayName("an announced port that is not one refuses the start, before the arena binds")
    void aWrongPortIsRefused() {
        for (String bad : List.of("0", "65536", "-1", "nine", "9001 ", "9 001")) {
            assertThatThrownBy(() -> ArenaMain.advertisedPort(Map.of("BACKEND_ADVERTISE_PORT", bad)))
                    .as("'%s'", bad)
                    .isInstanceOf(RefusedConfiguration.class)
                    .hasMessageContaining("BACKEND_ADVERTISE_PORT");
        }
    }

    @Test
    @DisplayName("an arena behind the front announces its TLS name (D-82): a host name, lower case, else none")
    void theAnnouncedTlsName() {
        assertThat(ArenaMain.advertisedTlsName(Map.of())).as("unset: its host, as before").isNull();
        assertThat(ArenaMain.advertisedTlsName(Map.of("BACKEND_ADVERTISE_TLS_NAME", ""))).isNull();
        assertThat(ArenaMain.advertisedTlsName(Map.of("BACKEND_ADVERTISE_TLS_NAME", "a1.arena.internal")))
                .isEqualTo("a1.arena.internal");
        assertThat(ArenaMain.advertisedTlsName(Map.of("BACKEND_ADVERTISE_TLS_NAME", "B3.Arena.Internal")))
                .as("nginx's map compares exactly; the handshake's case is the grant's").isEqualTo("b3.arena.internal");
    }

    @Test
    @DisplayName("a TLS name that a handshake cannot carry, or one beside the arena's own keystore, refuses the start")
    void aWrongTlsNameIsRefused() {
        for (String bad : List.of("203.0.113.10", "a1", "-a.arena.internal", "a..internal", "a_1.arena.internal",
                "a1.arena.internal.", "a1 .arena.internal", "x".repeat(64) + ".internal")) {
            assertThatThrownBy(() -> ArenaMain.advertisedTlsName(Map.of("BACKEND_ADVERTISE_TLS_NAME", bad)))
                    .as("'%s'", bad)
                    .isInstanceOf(RefusedConfiguration.class)
                    .hasMessageContaining("BACKEND_ADVERTISE_TLS_NAME");
        }
        assertThatThrownBy(() -> ArenaMain.advertisedTlsName(Map.of("BACKEND_ADVERTISE_TLS_NAME", "a1.arena.internal",
                "BACKEND_ARENA_TLS_KEYSTORE", "/run/credentials/arena-tls.p12")))
                .as("behind the front nginx ends TLS, and speaks plaintext to the arena")
                .isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("BACKEND_ARENA_TLS_KEYSTORE");
    }
}
