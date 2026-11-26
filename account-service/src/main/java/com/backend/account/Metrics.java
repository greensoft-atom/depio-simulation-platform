package com.backend.account;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * The account service's own counts, for the watch (10 §3.1), in Prometheus's text format: every response by its
 * route, method and status, and the gauges given. A route is the server's own name for it, never the path a client
 * chose, so there is one series a route.
 */
public final class Metrics {

    private final Map<String, LongAdder> responses = new ConcurrentHashMap<>();
    private final Map<String, LongSupplier> gauges = new ConcurrentHashMap<>();

    public void response(String route, String method, int status) {
        responses.computeIfAbsent("route=\"" + route + "\",method=\"" + method + "\",status=\"" + status + "\"",
                k -> new LongAdder()).increment();
    }

    /** A value read at each scrape; one that throws is left out of that scrape. */
    public void gauge(String name, LongSupplier value) {
        gauges.put(name, value);
    }

    public String scrape() {
        StringBuilder out = new StringBuilder();
        out.append("# TYPE account_requests_total counter\n");
        new TreeMap<>(responses).forEach((labels, n) ->
                out.append("account_requests_total{").append(labels).append("} ").append(n.sum()).append('\n'));
        new TreeMap<>(gauges).forEach((name, value) -> {
            try {
                long v = value.getAsLong();
                out.append("# TYPE ").append(name).append(name.endsWith("_total") ? " counter\n" : " gauge\n");
                out.append(name).append(' ').append(v).append('\n');
            } catch (RuntimeException unreadable) {
                // MySQL not answering, say: the series is missing from this scrape, and the watch sees the gap.
            }
        });
        return out.toString();
    }
}
