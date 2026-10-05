package com.backend.common;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Serves {@link Metrics#render} at {@code GET /metrics}, for the machine's own monitoring.
 *
 * Started only when {@code BACKEND_METRICS_ADDR} names an address (host:port): a process run by
 * hand or by a test takes no port it was not asked to. The units set it to a loopback address,
 * because metrics say a lot about a system and are for this machine's scraper, not the
 * internet.
 */
public final class MetricsServer implements AutoCloseable {

    private final HttpServer server;

    private MetricsServer(HttpServer server) {
        this.server = server;
    }

    /** @return the running server, or null when no address is configured. */
    public static MetricsServer startIfConfigured(Metrics metrics, Map<String, String> env) {
        String addr = env.get("BACKEND_METRICS_ADDR");
        if (addr == null || addr.isBlank()) {
            return null;
        }
        int colon = addr.lastIndexOf(':');
        if (colon <= 0) {
            throw new RefusedConfiguration("BACKEND_METRICS_ADDR must be host:port, not " + addr);
        }
        String host = addr.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(addr.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new RefusedConfiguration("BACKEND_METRICS_ADDR must be host:port, not " + addr);
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 16);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "metrics");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/metrics", exchange -> serve(exchange, metrics));
            server.start();
            return new MetricsServer(server);
        } catch (IOException e) {
            throw new RefusedConfiguration("cannot serve metrics on " + addr + ": " + e, e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static void serve(HttpExchange exchange, Metrics metrics) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())
                    || !"/metrics".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = metrics.render().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
