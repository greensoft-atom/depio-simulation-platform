package com.backend.account;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import com.backend.account.db.Database;
import com.backend.account.db.Housekeeping;
import com.backend.account.db.Outbox;
import com.backend.account.db.Throttle;
import com.backend.account.http.AccountHttpServer;
import com.backend.account.token.BearerCheck;
import com.backend.account.token.SigningKeys;
import com.backend.account.token.TokenIssuer;
import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The account service's process (D-117, 10 §3.1): its settings read, its schema migrated, its routes served, its
 * events delivered, its housekeeping run every ten minutes, its metrics served when asked for.
 *
 * <p>Exit 2 for a setting it cannot run with, which the unit does not restart on (as every backend process); exit 1
 * for anything else that stops the start.
 */
public final class AccountMain {

    private static final Logger log = LoggerFactory.getLogger(AccountMain.class);

    static final Duration HOUSEKEEPING_EVERY = Duration.ofMinutes(10);
    private static final int POOL_SIZE = 16;

    public static void main(String[] args) {
        try {
            Running running = run(args, System.getenv());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.close();
                log.info("account service stopped");
                Logs.flush();
            }, "shutdown"));
        } catch (RefusedConfiguration refused) {
            Logs.flush();
            System.err.println("refusing to start: " + refused.getMessage());
            System.exit(2);
        } catch (Throwable failed) {
            Logs.flush();
            failed.printStackTrace();
            System.exit(1);
        }
    }

    /** The process running: what a stop closes, in this order. */
    static final class Running implements AutoCloseable {
        private final AccountHttpServer http;
        private final EventDelivery delivery;
        private final Thread housekeeping;
        private final HttpServer metricsServer;
        private final Database db;
        final int port;
        final int metricsPort;

        Running(AccountHttpServer http, EventDelivery delivery, Thread housekeeping, HttpServer metricsServer, Database db,
                int port) {
            this.http = http;
            this.delivery = delivery;
            this.housekeeping = housekeeping;
            this.metricsServer = metricsServer;
            this.db = db;
            this.port = port;
            this.metricsPort = metricsServer == null ? -1 : metricsServer.getAddress().getPort();
        }

        @Override
        public void close() {
            http.close();
            delivery.close();
            housekeeping.interrupt();
            if (metricsServer != null) {
                metricsServer.stop(0);
            }
            db.close();
        }
    }

    static Running run(String[] args, Map<String, String> env) throws IOException {
        if (args.length > 0) {
            // Settings on the command line are readable by every local user (ps): refused, not read.
            throw new RefusedConfiguration("the account service takes no arguments: its settings are the ACCOUNT_*"
                    + " variables of its settings file, /etc/backend/account.env (D-102)");
        }
        AccountSettings s = AccountSettings.from(env);
        SigningKeys keys = SigningKeys.from(s.signingKey(), s.retiredKeys());
        log.info("starting: {}, keys {}", s, keys.keyIds());
        if (s.database().usesDevelopmentPassword()) {
            log.warn("the database password is the development default: set ACCOUNT_DB_PASSWORD outside development");
        }
        Database db = new Database(s.database().url(), s.database().user(), s.database().password(),
                s.database().poolSizeOr(POOL_SIZE));
        db.migrate();
        Clock clock = Clock.systemUTC();
        Outbox outbox = new Outbox(db.dataSource());
        EventDelivery delivery = new EventDelivery(outbox, s.events(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), clock);
        AccountService service = new AccountService(db.dataSource(), new PasswordHasher(),
                new Throttle(db.dataSource(), clock), new TokenIssuer(keys, s.issuer(), s.audience()),
                new BearerCheck(keys, s.issuer()), outbox, delivery::wake, clock);
        Metrics metrics = new Metrics();
        metrics.gauge("account_hasher_line", service::hasherLine);
        metrics.gauge("account_events_pending", () -> {
            try {
                return outbox.pending();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        metrics.gauge("account_events_delivered_total", delivery::delivered);
        metrics.gauge("account_event_failures_total", delivery::failures);
        AccountHttpServer http = new AccountHttpServer(service, keys, s.proxies(), metrics, s.bind(), s.port());
        int port = http.start();
        delivery.start();
        Housekeeping keeping = new Housekeeping(db.dataSource());
        Thread housekeeping = Thread.ofVirtual().name("housekeeping").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Housekeeping.Purged p = keeping.run(clock.instant());
                    if (p.tokens() + p.windows() + p.events() > 0) {
                        log.info("housekeeping purged {} refresh tokens, {} limit windows, {} events",
                                p.tokens(), p.windows(), p.events());
                    }
                } catch (SQLException e) {
                    log.warn("housekeeping: {}", e.toString());
                }
                try {
                    Thread.sleep(HOUSEKEEPING_EVERY);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        HttpServer metricsServer = s.metrics() == null ? null : metricsServer(s.metrics(), metrics);
        log.info("account service on {}:{}, issuer {}, events to {}", s.bind(), port, s.issuer(), s.events());
        return new Running(http, delivery, housekeeping, metricsServer, db, port);
    }

    private static HttpServer metricsServer(InetSocketAddress at, Metrics metrics) throws IOException {
        HttpServer server = HttpServer.create(at, 16);
        server.createContext("/metrics", ex -> {
            byte[] body = metrics.scrape().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
            ex.close();
        });
        server.start();
        log.info("account service metrics on {}:{}", at.getHostString(), server.getAddress().getPort());
        return server;
    }

    private AccountMain() {
    }
}
