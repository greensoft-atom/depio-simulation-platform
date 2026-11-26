package com.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.backend.account.token.TestKeys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountSettingsTest {

    private static Map<String, String> with(String... kv) {
        Map<String, String> env = new HashMap<>();
        env.put("ACCOUNT_SIGNING_KEY", TestKeys.newPair().privateKey());
        for (int i = 0; i < kv.length; i += 2) {
            env.put(kv[i], kv[i + 1]);
        }
        return env;
    }

    @Test
    @DisplayName("unset, the development defaults: loopback, 8070, the development issuer, the platform's receiver")
    void defaults() {
        AccountSettings s = AccountSettings.from(with());
        assertThat(s.bind()).isEqualTo("127.0.0.1");
        assertThat(s.port()).isEqualTo(8070);
        assertThat(s.issuer()).isEqualTo("http://127.0.0.1:8083");
        assertThat(s.audience()).isEqualTo("backend");
        assertThat(s.events()).containsExactly(URI.create("http://127.0.0.1:8080/v1/account-events"));
        assertThat(s.metrics()).isNull();
        assertThat(s.toString()).as("the key never printed").doesNotContain(s.signingKey());
    }

    @Test
    @DisplayName("each setting as given; the receivers in their order; the key from a file when one is named")
    void given() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("signing", ".key");
        String key = TestKeys.newPair().privateKey();
        java.nio.file.Files.writeString(file, key + "\n");
        Map<String, String> env = with("ACCOUNT_BIND", "10.0.0.1", "ACCOUNT_PORT", "8070",
                "ACCOUNT_ISSUER", "https://203.0.113.7/account", "ACCOUNT_AUDIENCE", "backend",
                "ACCOUNT_EVENTS_URL", "http://10.0.0.1:8080/v1/account-events, http://10.0.0.2:8080/v1/account-events",
                "ACCOUNT_METRICS_ADDR", "127.0.0.1:9104", "ACCOUNT_SIGNING_KEY_FILE", file.toString(),
                "ACCOUNT_TRUSTED_PROXIES", "10.0.0.1");
        AccountSettings s = AccountSettings.from(env);
        assertThat(s.bind()).isEqualTo("10.0.0.1");
        assertThat(s.issuer()).isEqualTo("https://203.0.113.7/account");
        assertThat(s.events()).extracting(URI::getHost).containsExactly("10.0.0.1", "10.0.0.2");
        assertThat(s.metrics().getPort()).isEqualTo(9104);
        assertThat(s.signingKey()).isEqualTo(key);
        assertThat(s.proxies().toString()).contains("10.0.0.1");
    }

    @Test
    @DisplayName("a setting malformed refuses the start, naming the variable")
    void refusals() {
        List<String[]> bad = List.of(
                new String[] {"ACCOUNT_BIND", "account.internal"},
                new String[] {"ACCOUNT_PORT", "65536"},
                new String[] {"ACCOUNT_PORT", "0"},
                new String[] {"ACCOUNT_PORT", "eighty"},
                new String[] {"ACCOUNT_ISSUER", "203.0.113.7/account"},
                new String[] {"ACCOUNT_ISSUER", "ftp://203.0.113.7/account"},
                new String[] {"ACCOUNT_ISSUER", "https://203.0.113.7/account/"},
                new String[] {"ACCOUNT_ISSUER", "https://203.0.113.7/account?x=1"},
                new String[] {"ACCOUNT_AUDIENCE", ""},
                new String[] {"ACCOUNT_AUDIENCE", "back end"},
                new String[] {"ACCOUNT_EVENTS_URL", "not a url"},
                new String[] {"ACCOUNT_EVENTS_URL", "http://10.0.0.1:8080/v1/account-events,"},
                new String[] {"ACCOUNT_METRICS_ADDR", "127.0.0.1:99999"},
                new String[] {"ACCOUNT_METRICS_ADDR", "metrics:9104"},
                new String[] {"ACCOUNT_TRUSTED_PROXIES", "nginx.internal"});
        for (String[] kv : bad) {
            assertThatThrownBy(() -> AccountSettings.from(with(kv[0], kv[1])))
                    .as("%s=%s", kv[0], kv[1]).isInstanceOf(RefusedConfiguration.class).hasMessageContaining(kv[0]);
        }
        Map<String, String> none = with();
        none.remove("ACCOUNT_SIGNING_KEY");
        assertThatThrownBy(() -> AccountSettings.from(none)).isInstanceOf(RefusedConfiguration.class)
                .hasMessageContaining("ACCOUNT_SIGNING_KEY");
    }
}
