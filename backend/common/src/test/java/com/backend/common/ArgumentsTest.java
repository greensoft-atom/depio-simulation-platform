package com.backend.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ArgumentsTest {

    @Test
    @DisplayName("a missing argument takes its default, a present one is read")
    void readsOrDefaults() {
        String[] args = {"127.0.0.1", "8080", "5700.5"};
        assertThat(Arguments.integer(args, 1, "port", 9)).isEqualTo(8080);
        assertThat(Arguments.integer(args, 3, "store port", 6379)).isEqualTo(6379);
        assertThat(Arguments.decimal(args, 2, "map size", 1f)).isEqualTo(5700.5f);
    }

    @Test
    @DisplayName("an empty or malformed argument refuses to start, naming it, instead of failing")
    void refusesWhatIsNotANumber() {
        // What systemd passes for ${PLATFORM_PORT} when the env file does not set it.
        assertThatThrownBy(() -> Arguments.integer(new String[] {"0.0.0.0", ""}, 1, "port", 8080))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("port");
        assertThatThrownBy(() -> Arguments.integer(new String[] {"80x"}, 0, "port", 8080))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("'80x'");
        assertThatThrownBy(() -> Arguments.decimal(new String[] {"NaN"}, 0, "map size", 1f))
                .isInstanceOf(RefusedConfiguration.class);
    }

    @Test
    @DisplayName("a host, a name or a path: read, or its default when absent; empty, refused, not dialled as \"\" (the arena review)")
    void textIsRefusedEmpty() {
        assertThat(Arguments.text(new String[] {"a", " 10.0.0.2 "}, 1, "store host", "127.0.0.1")).isEqualTo("10.0.0.2");
        assertThat(Arguments.text(new String[] {"a"}, 1, "store host", "127.0.0.1")).isEqualTo("127.0.0.1");
        assertThatThrownBy(() -> Arguments.text(new String[] {"a", "  "}, 1, "store host", "127.0.0.1"))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("store host");
    }
}
