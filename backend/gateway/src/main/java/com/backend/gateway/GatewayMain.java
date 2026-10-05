package com.backend.gateway;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import com.backend.common.Arguments;
import com.backend.common.Logs;
import com.backend.common.Metrics;
import com.backend.common.MetricsServer;
import com.backend.common.RefusedConfiguration;
import com.backend.handoff.StoreClients;
import com.jredis.client.JRedisClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one gateway process.
 *
 * Usage: GatewayMain [bindHost] [port] [platformUrl] [storeHost] [storePort] [gatewayId]
 *
 * Binds to loopback by default: clients reach it through this machine's nginx, which
 * terminates TLS. Listening on every interface put a plaintext lobby, around the TLS, in
 * front of anyone who could reach the port.
 */
public final class GatewayMain {

    private static final Logger log = LoggerFactory.getLogger(GatewayMain.class);

    public static void main(String[] args) {
        try {
            run(args);
        } catch (RefusedConfiguration refused) {
            // 2: restarting cannot fix a setting, so the units do not restart on it.
            Logs.flush();
            System.err.println("refusing to start: " + refused.getMessage());
            System.exit(2);
        } catch (Throwable failed) {
            // Exit, rather than let main end by throwing. The store client's threads are not
            // daemons, so a main that only threw left a process that looked alive and served
            // nothing — a wrong database password did exactly that to platform — and systemd
            // restarts only a process that has ended.
            Logs.flush();
            failed.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        String bindHost = Arguments.text(args, 0, "bind address", "127.0.0.1");
        int port = Arguments.integer(args, 1, "port", 8081);
        String platformUrl = Arguments.text(args, 2, "platform url", "http://127.0.0.1:8080");
        if (!platformUrl.startsWith("http://") && !platformUrl.startsWith("https://")) {
            // Without a scheme every lobby request failed inside the handler, closing its connection without a word.
            throw new RefusedConfiguration("platform url must start with http:// or https://, not '" + platformUrl + "'");
        }
        String storeHost = Arguments.text(args, 3, "store host", "127.0.0.1");
        int storePort = Arguments.integer(args, 4, "store port", 6379);
        String gatewayId = Arguments.text(args, 5, "gateway id", "gateway-" + port);

        JRedisClient store = StoreClients.open(storeHost, storePort, "gateway");

        GatewayServer server = new GatewayServer(store, platformUrl, gatewayId, 2);
        int bound = server.start(bindHost, port);
        System.out.printf("gateway %s on %s:%d/lobby, platform %s, store %s:%d%n",
                gatewayId, bindHost, bound, platformUrl, storeHost, storePort);

        Metrics metrics = new Metrics();
        metrics.jvm();
        StoreClients.registerMetrics(metrics, store);
        server.registerMetrics(metrics);
        String edgeCertificate = System.getenv("BACKEND_EDGE_CERTIFICATE");
        if (edgeCertificate != null) {
            metrics.gauge("backend_gateway_edge_certificate_expiry_timestamp_seconds",
                    "When the certificate this machine's nginx shows clients expires, in Unix"
                            + " seconds; 0 if its file cannot be read.",
                    () -> EdgeCertificate.expiry(Path.of(edgeCertificate)));
        }
        MetricsServer metricsServer = MetricsServer.startIfConfigured(metrics, System.getenv());

        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (metricsServer != null) {
                metricsServer.close();
            }
            // Logged, not printed: a journal that has stopped reading would hold the hook here.
            log.info("connections held at shutdown: {}", server.connectionCount());
            server.close();
            store.close();
            Logs.flush();                    // last: the lines above are queued, and the JVM halts next
            done.countDown();
        }));
        done.await();
    }

    private GatewayMain() {
    }
}
