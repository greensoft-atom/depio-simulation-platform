package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.backend.common.Metrics;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformClientTest {

    @Test
    @DisplayName("a call's time is in seconds: an answer 100 ms late lands between 50 ms and 1 s (03 §10)")
    void answeredCallIsTimedInSeconds() throws Exception {
        HttpServer platform = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platform.createContext("/", exchange -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        platform.start();
        try {
            PlatformClient client = new PlatformClient(
                    "http://127.0.0.1:" + platform.getAddress().getPort(), Duration.ofSeconds(5));

            assertThat(client.requestMatch("token").get(10, TimeUnit.SECONDS).status()).isEqualTo(200);

            Metrics m = new Metrics();
            m.labeledHistogram("t_seconds", "test", "route", client.latency);
            assertThat(m.render())
                    .contains("t_seconds_bucket{route=\"/v1/match-requests\",le=\"0.05\"} 0")
                    .contains("t_seconds_bucket{route=\"/v1/match-requests\",le=\"1\"} 1");
        } finally {
            platform.stop(0);
        }
    }

    @Test
    @DisplayName("a call that fails is timed too, under its own route (03 §10)")
    void failedCallIsTimed() throws Exception {
        PlatformClient client = new PlatformClient("http://127.0.0.1:1", Duration.ofSeconds(1));

        assertThat(client.requestMatch("token").get(10, TimeUnit.SECONDS)).as("no reply: platform unreachable").isNull();

        Metrics m = new Metrics();
        m.labeledHistogram("t_seconds", "test", "route", client.latency);
        assertThat(m.render()).contains("t_seconds_count{route=\"/v1/match-requests\"} 1");
    }
}
