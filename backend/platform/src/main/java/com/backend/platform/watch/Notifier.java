package com.backend.platform.watch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends alerts to the webhook (04 §12, D-86): one {@code POST} of what fired or resolved this round, as JSON,
 * {@code {"status", "machine", "alerts": [{"name", "labels", "summary", "runbook", "since"}]}}. A receiver that
 * does not answer 2xx within ten seconds is tried once more; then the round's caller counts the failure and
 * leaves the alerts to be sent again next round.
 */
public final class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int TRIES = 2;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI webhook;
    private final HttpClient http;

    public Notifier(URI webhook, HttpClient http) {
        this.webhook = webhook;
        this.http = http;
    }

    /** @return whether the receiver took it */
    public boolean send(String status, String machine, List<Alert> alerts) {
        String body = body(status, machine, alerts);
        for (int attempt = 1; attempt <= TRIES; attempt++) {
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(webhook).timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() / 100 == 2) {
                    return true;
                }
                log.warn("the alert webhook answered {} (try {} of {})", response.statusCode(), attempt, TRIES);
            } catch (IOException e) {
                log.warn("the alert webhook could not be reached (try {} of {}): {}", attempt, TRIES, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    static String body(String status, String machine, List<Alert> alerts) {
        ObjectNode root = JSON.createObjectNode().put("status", status).put("machine", machine);
        ArrayNode list = root.putArray("alerts");
        for (Alert a : alerts) {
            ObjectNode node = list.addObject().put("name", a.name());
            ObjectNode labels = node.putObject("labels");
            for (Map.Entry<String, String> e : a.labels().entrySet()) {
                labels.put(e.getKey(), e.getValue());
            }
            node.put("summary", a.summary()).put("runbook", a.runbook())
                    .put("since", Instant.ofEpochMilli(a.sinceMillis()).toString());
        }
        return root.toString();
    }
}
