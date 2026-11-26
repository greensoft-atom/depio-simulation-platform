package com.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import com.backend.account.db.TestDatabase;
import com.backend.account.token.TestKeys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountMainTest {

    @Test
    @DisplayName("arguments are refused, not read: settings on the command line are every local user's to read")
    void noArguments() {
        Map<String, String> settings = Map.of("ACCOUNT_SIGNING_KEY", TestKeys.newPair().privateKey(),
                "ACCOUNT_DB_URL", TestDatabase.URL);
        assertThatThrownBy(() -> AccountMain.run(new String[] {"--password", "x"}, settings))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("takes no arguments");
    }

    @Test
    @DisplayName("from its settings alone: migrated, serving its routes and its metrics, and stopped")
    void startsAndStops() throws Exception {
        TestDatabase.dropEverything();                   // nothing in account_test: the start migrates it
        Map<String, String> env = new HashMap<>();
        env.put("ACCOUNT_SIGNING_KEY", TestKeys.newPair().privateKey());
        env.put("ACCOUNT_DB_URL", TestDatabase.URL);
        int api;
        int metrics;
        try (java.net.ServerSocket a = new java.net.ServerSocket(0); java.net.ServerSocket b = new java.net.ServerSocket(0)) {
            api = a.getLocalPort();
            metrics = b.getLocalPort();
        }
        env.put("ACCOUNT_PORT", Integer.toString(api));
        env.put("ACCOUNT_METRICS_ADDR", "127.0.0.1:" + metrics);
        HttpClient http = HttpClient.newHttpClient();
        try (AccountMain.Running running = AccountMain.run(new String[0], env)) {
            assertThat(running.port).isEqualTo(api);
            HttpResponse<String> up = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api + "/v1/accounts"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"ada\",\"password\":\"hunter2-hunter2\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(up.statusCode()).as(up.body()).isEqualTo(201);
            String scraped = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + metrics + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            assertThat(scraped).contains("account_requests_total{route=\"/v1/accounts\",method=\"POST\",status=\"201\"} 1",
                    "account_events_pending 0", "account_hasher_line 0");
        }
        assertThatThrownBy(() -> http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api + "/v1/health")).build(),
                HttpResponse.BodyHandlers.ofString())).as("stopped").isInstanceOf(java.io.IOException.class);
    }
}
