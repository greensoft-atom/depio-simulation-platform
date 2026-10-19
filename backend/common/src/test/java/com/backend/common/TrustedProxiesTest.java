package com.backend.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrustedProxiesTest {

    private static InetAddress at(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    @DisplayName("unset, only this machine is believed: loopback, as before D-82")
    void unsetTrustsLoopbackAlone() throws Exception {
        for (Map<String, String> env : List.of(Map.<String, String>of(), Map.of("BACKEND_TRUSTED_PROXIES", "  "))) {
            TrustedProxies p = TrustedProxies.fromEnvironment(env);
            assertThat(p.trusts(at("127.0.0.1"))).isTrue();
            assertThat(p.trusts(at("::1"))).isTrue();
            assertThat(p.trusts(at("10.0.0.1"))).as("a machine not named").isFalse();
        }
        assertThat(TrustedProxies.LOOPBACK.trusts(at("10.0.0.1"))).isFalse();
    }

    @Test
    @DisplayName("the named addresses are believed besides loopback, and no other")
    void namedAddressesAreTrusted() throws Exception {
        TrustedProxies p = TrustedProxies.fromEnvironment(
                Map.of("BACKEND_TRUSTED_PROXIES", "10.0.0.1, 10.0.0.2,10.0.0.3 ,2001:db8::5"));
        assertThat(p.trusts(at("10.0.0.1"))).isTrue();
        assertThat(p.trusts(at("10.0.0.3"))).isTrue();
        assertThat(p.trusts(at("2001:db8::5"))).isTrue();
        assertThat(p.trusts(at("127.0.0.1"))).as("this machine still").isTrue();
        assertThat(p.trusts(at("10.0.0.4"))).isFalse();
        assertThat(p.trusts(at("198.51.100.7"))).isFalse();
        assertThat(p.trusts(null)).as("no peer").isFalse();
    }

    @Test
    @DisplayName("anything but IP literals refuses the start, and nothing reaches DNS")
    void anythingElseIsRefused() {
        for (String bad : List.of("front.example.com", "10.0.0.256", "10.0.0", "10.0.0.1,,10.0.0.2",
                "10.0.0.1,", "fe80::1%eth0", "10.0.0.0/24", "localhost")) {
            assertThatThrownBy(() -> TrustedProxies.fromEnvironment(Map.of("BACKEND_TRUSTED_PROXIES", bad)))
                    .as("'%s'", bad)
                    .isInstanceOf(RefusedConfiguration.class)
                    .hasMessageContaining("BACKEND_TRUSTED_PROXIES");
        }
    }
}
