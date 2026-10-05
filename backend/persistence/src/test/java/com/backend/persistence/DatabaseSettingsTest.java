package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseSettingsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a password file wins over the variable, and loses one line ending")
    void fileFirst() throws Exception {
        Path file = Files.writeString(dir.resolve("db-password"), "s3cret pass\n");
        DatabaseSettings s = DatabaseSettings.from(Map.of(
                "BACKEND_DB_PASSWORD_FILE", file.toString(),
                "BACKEND_DB_PASSWORD", "from-the-variable",
                "BACKEND_DB_URL", "jdbc:mysql://db.internal:3306/backend",
                "BACKEND_DB_USER", "backend_app"));

        assertThat(s.password()).isEqualTo("s3cret pass");
        assertThat(s.url()).isEqualTo("jdbc:mysql://db.internal:3306/backend");
        assertThat(s.user()).isEqualTo("backend_app");
        assertThat(s.usesDevelopmentPassword()).isFalse();

        Files.writeString(file, "windows\r\n");
        assertThat(DatabaseSettings.from(Map.of("BACKEND_DB_PASSWORD_FILE", file.toString()))
                .password()).isEqualTo("windows");
        Files.writeString(file, "two\n\n");
        assertThat(DatabaseSettings.from(Map.of("BACKEND_DB_PASSWORD_FILE", file.toString()))
                .password()).as("only one line ending is taken off").isEqualTo("two\n");
    }

    @Test
    @DisplayName("a named file that cannot be read stops the process rather than falling back")
    void unreadableFileIsAnError() throws Exception {
        String missing = dir.resolve("absent").toString();
        assertThatThrownBy(() -> DatabaseSettings.from(Map.of(
                "BACKEND_DB_PASSWORD_FILE", missing, "BACKEND_DB_PASSWORD", "would-be-fallback")))
                .isInstanceOf(com.backend.common.RefusedConfiguration.class).hasMessageContaining(missing);

        Path empty = Files.writeString(dir.resolve("empty"), "\n");
        assertThatThrownBy(() -> DatabaseSettings.from(Map.of(
                "BACKEND_DB_PASSWORD_FILE", empty.toString())))
                .isInstanceOf(com.backend.common.RefusedConfiguration.class).hasMessageContaining("empty");
    }

    @Test
    @DisplayName("the variable when there is no file, and the development password when neither")
    void thenVariableThenDevelopment() {
        assertThat(DatabaseSettings.from(Map.of("BACKEND_DB_PASSWORD", "from-the-variable"))
                .password()).isEqualTo("from-the-variable");

        DatabaseSettings dev = DatabaseSettings.from(Map.of());
        assertThat(dev.usesDevelopmentPassword()).isTrue();
        assertThat(dev.url()).isEqualTo(DatabaseSettings.DEV_URL);
        assertThat(dev.user()).isEqualTo("backend");
    }

    @Test
    @DisplayName("the pool is sized by BACKEND_DB_POOL_SIZE, for the database's host, else the process's own default (06 §7)")
    void poolSize() {
        assertThat(DatabaseSettings.from(Map.of()).poolSizeOr(16)).isEqualTo(16);
        assertThat(DatabaseSettings.from(Map.of("BACKEND_DB_POOL_SIZE", " 24 ")).poolSizeOr(16)).isEqualTo(24);
        for (String bad : new String[] {"", "0", "101", "lots", "-3"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> DatabaseSettings.from(Map.of("BACKEND_DB_POOL_SIZE", bad)))
                    .as("'%s'", bad).isInstanceOf(com.backend.common.RefusedConfiguration.class)
                    .hasMessageContaining("BACKEND_DB_POOL_SIZE");
        }
        assertThat(DatabaseSettings.from(Map.of("BACKEND_DB_POOL_SIZE", "100")).poolSizeOr(16)).isEqualTo(100);
        assertThat(DatabaseSettings.from(Map.of("BACKEND_DB_POOL_SIZE", "1")).poolSizeOr(16)).isEqualTo(1);
    }

    @Test
    @DisplayName("printing the settings never prints the password, even one inside the URL")
    void toStringHidesThePassword() {
        DatabaseSettings s = DatabaseSettings.from(Map.of(
                "BACKEND_DB_PASSWORD", "hunter2-the-real-one",
                "BACKEND_DB_URL", "jdbc:mysql://db:3306/backend?user=x&password=in-the-url"));

        assertThat(s.toString())
                .doesNotContain("hunter2-the-real-one")
                .doesNotContain("in-the-url")
                .contains("jdbc:mysql://db:3306/backend", "BACKEND_DB_PASSWORD");
    }
}
