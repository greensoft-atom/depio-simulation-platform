package com.backend.gateway;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gateway's side of the call to {@code platform}.
 *
 * <h2>Asynchronous, because the caller is an event loop</h2>
 *
 * Every call here is made from a Netty thread that is also serving other connections. A
 * blocking request would stall every one of them for the duration, so these return futures
 * and the reply is written when it arrives.
 *
 * <h2>The gateway makes no product decisions</h2>
 *
 * It does not interpret the answer beyond routing it: a status becomes a reply or an error
 * frame, and the body is passed through. Rules about what a player may do live in
 * {@code platform}, so the gateway can be restarted or replaced without touching them
 * (03 §1).
 */
final class PlatformClient {

    private static final Logger log = LoggerFactory.getLogger(PlatformClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final String base;
    private final Duration timeout;

    /** Seconds, the bounds of a call's time: 5 ms to the 5 s a call may take before it times out. */
    static final double[] LATENCY_BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5};
    /** Each call's time to its answer or its failure, by the API's path (03 §10). */
    final com.backend.common.Metrics.LabeledHistogram latency =
            new com.backend.common.Metrics.LabeledHistogram(LATENCY_BUCKETS);

    PlatformClient(String base, Duration timeout) {
        this.base = base;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    record Reply(int status, JsonNode body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        /** The error code platform sent, or a stand-in if the body was not what we expect. */
        String code() {
            JsonNode node = body == null ? null : body.get("code");
            return node == null ? "upstream_error" : node.asText();
        }
    }

    /** Completes with platform's answer, or with null when there was none: unreachable, or too slow. */
    CompletableFuture<Reply> requestMatch(String sessionToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/match-requests"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + sessionToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request);
    }

    /** Joins a queue for a timed match (04 §4). */
    CompletableFuture<Reply> joinQueue(String sessionToken, String mode) {
        String body = MAPPER.createObjectNode().put("mode", mode).toString();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/queue"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + sessionToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    CompletableFuture<Reply> leaveQueue(String sessionToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/queue"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + sessionToken)
                .DELETE()
                .build();
        return send(request);
    }

    /**
     * A POST carrying only the one field it takes, or none: a party request, {@code /v1/party/…},
     * or an answer to a match found, {@code /v1/queue/accept} or {@code /decline} (04 §4).
     */
    CompletableFuture<Reply> post(String sessionToken, String path, JsonNode field, String name) {
        ObjectNode body = MAPPER.createObjectNode();
        if (field != null) {
            body.set(name, field);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + sessionToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(request);
    }

    private CompletableFuture<Reply> send(HttpRequest request) {
        long start = System.nanoTime();
        String route = request.uri().getPath();          // one of the few above: a bounded label
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> latency.observe(route, (System.nanoTime() - start) / 1e9))
                .thenApply(response -> new Reply(response.statusCode(), parse(response.body())))
                .exceptionally(error -> {
                    // Platform being unreachable is our fault, not the player's, and it must
                    // not be reported to them as a rejection they could act on. No reply, then:
                    // a made-up 503 reached them as "platform refused the request".
                    log.warn("platform call failed: {}", error.toString());
                    return null;
                });
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException malformed) {
            log.warn("platform sent a body that is not JSON");
            return null;
        }
    }
}
