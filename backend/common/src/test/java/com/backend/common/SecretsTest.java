package com.backend.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a file wins over the variable, loses one line ending, and is never printed")
    void fileFirst() throws Exception {
        Path file = Files.writeString(dir.resolve("store-password"), "s3cret\n");
        Secrets.Secret s = Secrets.read(Map.of("X_FILE", file.toString(), "X", "variable"), "X");
        assertThat(s.value()).isEqualTo("s3cret");
        assertThat(s.toString()).doesNotContain("s3cret").contains(file.toString());

        assertThat(Secrets.read(Map.of("X", "variable"), "X").value()).isEqualTo("variable");
        assertThat(Secrets.read(Map.of(), "X")).as("neither set").isNull();
    }

    @Test
    @DisplayName("a named file that cannot be read, or is empty, refuses the configuration")
    void unreadableOrEmptyIsRefused() throws Exception {
        String missing = dir.resolve("absent").toString();
        assertThatThrownBy(() -> Secrets.read(Map.of("X_FILE", missing, "X", "fallback"), "X"))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining(missing);
        Path empty = Files.writeString(dir.resolve("empty"), "\n");
        assertThatThrownBy(() -> Secrets.read(Map.of("X_FILE", empty.toString()), "X"))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("empty");
    }
}
