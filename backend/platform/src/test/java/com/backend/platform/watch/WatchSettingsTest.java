package com.backend.platform.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.backend.common.RefusedConfiguration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The watch's settings (04 §12): what it reads, where it sends, and what it refuses before the platform serves. */
class WatchSettingsTest {

    @Test
    @DisplayName("every setting read: targets with their jobs, peers by name, the disks, the machine's name, the webhook, the repeat")
    void everySetting() {
        Map<String, String> env = Map.of(
                "BACKEND_WATCH_TARGETS", "platform=127.0.0.1:9101, gateway=127.0.0.1:9103,arena=127.0.0.1:9110,arena=127.0.0.1:9111",
                "BACKEND_WATCH_PEERS", "B=10.0.0.2:8080,C=10.0.0.3:8080",
                "BACKEND_WATCH_DISKS", "/,/var/lib",
                "BACKEND_WATCH_MACHINE", "A",
                "BACKEND_ALERT_WEBHOOK_URL", "https://chat.example.test/hooks/abc",
                "BACKEND_ALERT_REPEAT_HOURS", "2");
        WatchSettings s = WatchSettings.fromEnvironment(env);
        assertThat(s.enabled()).isTrue();
        assertThat(s.targets()).containsExactly(
                new WatchSettings.Target("platform", "127.0.0.1", 9101), new WatchSettings.Target("gateway", "127.0.0.1", 9103),
                new WatchSettings.Target("arena", "127.0.0.1", 9110), new WatchSettings.Target("arena", "127.0.0.1", 9111));
        assertThat(s.peers()).containsExactly(new WatchSettings.Peer("B", "10.0.0.2", 8080), new WatchSettings.Peer("C", "10.0.0.3", 8080));
        assertThat(s.disks()).containsExactly(Path.of("/"), Path.of("/var/lib"));
        assertThat(s.machine()).isEqualTo("A");
        assertThat(s.webhook()).hasToString("https://chat.example.test/hooks/abc");
        assertThat(s.repeatMillis()).isEqualTo(2 * 3600_000L);
    }

    @Test
    @DisplayName("the defaults: off without targets; the disks the release writes to; the hostname as the machine; no webhook; four hours")
    void defaults() {
        WatchSettings off = WatchSettings.fromEnvironment(Map.of());
        assertThat(off.enabled()).isFalse();
        WatchSettings s = WatchSettings.fromEnvironment(Map.of("BACKEND_WATCH_TARGETS", "platform=127.0.0.1:9101"));
        assertThat(s.enabled()).isTrue();
        assertThat(s.peers()).isEmpty();
        assertThat(s.disks()).containsExactly(Path.of("/"), Path.of("/var/lib"), Path.of("/var/log"), Path.of("/opt/backend"));
        assertThat(s.machine()).isNotBlank();
        assertThat(s.webhook()).isNull();
        assertThat(s.repeatMillis()).isEqualTo(4 * 3600_000L);
    }

    @Test
    @DisplayName("the webhook from a file wins over the value, as every secret does, and a trailing newline is not part of it")
    void webhookFromAFile() throws Exception {
        Path f = java.nio.file.Files.createTempFile("webhook", ".txt");
        try {
            java.nio.file.Files.writeString(f, "https://chat.example.test/hooks/from-file\n");
            WatchSettings s = WatchSettings.fromEnvironment(Map.of("BACKEND_WATCH_TARGETS", "platform=127.0.0.1:9101",
                    "BACKEND_ALERT_WEBHOOK_URL", "https://wrong.example.test/", "BACKEND_ALERT_WEBHOOK_URL_FILE", f.toString()));
            assertThat(s.webhook()).hasToString("https://chat.example.test/hooks/from-file");
        } finally {
            java.nio.file.Files.deleteIfExists(f);
        }
    }

    @Test
    @DisplayName("refused before anything runs: a target without its job or port, a port that is not a number, a peer without a name, a webhook that is not http(s), a repeat outside 1 to 24 hours")
    void refusals() {
        Map<String, String> base = new HashMap<>(Map.of("BACKEND_WATCH_TARGETS", "platform=127.0.0.1:9101"));
        for (String bad : new String[] {"127.0.0.1:9101", "platform=127.0.0.1", "platform=127.0.0.1:nine", "platform=:9101", "=127.0.0.1:9101", "platform=127.0.0.1:70000"}) {
            assertThatThrownBy(() -> WatchSettings.fromEnvironment(Map.of("BACKEND_WATCH_TARGETS", bad))).as(bad)
                    .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("BACKEND_WATCH_TARGETS");
        }
        for (String bad : new String[] {"10.0.0.2:8080", "B=10.0.0.2", "=10.0.0.2:8080"}) {
            Map<String, String> env = new HashMap<>(base);
            env.put("BACKEND_WATCH_PEERS", bad);
            assertThatThrownBy(() -> WatchSettings.fromEnvironment(env)).as(bad)
                    .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("BACKEND_WATCH_PEERS");
        }
        for (String bad : new String[] {"ftp://x", "chat.example.test/hook", "not a url"}) {
            Map<String, String> env = new HashMap<>(base);
            env.put("BACKEND_ALERT_WEBHOOK_URL", bad);
            assertThatThrownBy(() -> WatchSettings.fromEnvironment(env)).as(bad)
                    .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("BACKEND_ALERT_WEBHOOK_URL");
        }
        for (String bad : new String[] {"0", "25", "4.5", "four", ""}) {
            Map<String, String> env = new HashMap<>(base);
            env.put("BACKEND_ALERT_REPEAT_HOURS", bad);
            assertThatThrownBy(() -> WatchSettings.fromEnvironment(env)).as("'" + bad + "'")
                    .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("BACKEND_ALERT_REPEAT_HOURS");
        }
    }
}
