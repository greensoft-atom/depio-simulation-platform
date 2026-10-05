package com.jredis.server;

import com.jredis.server.config.ConfigException;
import com.jredis.server.config.ConfigLoader;
import com.jredis.server.config.ServerConfig;
import com.jredis.server.persist.DataDirLockedException;
import com.jredis.server.persist.DataLoadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code java -jar j-redis-server.jar [config-file] [--directive value ...]}
 *
 * <p>Exit codes: 0 clean shutdown, 1 configuration or start-up error, 2 data directory locked,
 * 3 data could not be loaded, 4 fail-stop (see docs/11).
 */
public final class Main {

    static {
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback-jredis-server.xml");
        }
    }

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        for (String a : args) {
            if (a.equals("--help") || a.equals("-h")) {
                usage();
                return;
            }
            if (a.equals("--version") || a.equals("-v")) {
                System.out.println("j-redis-server " + Version.VERSION);
                return;
            }
        }
        Logger log = LoggerFactory.getLogger(Main.class);
        ServerConfig config;
        try {
            config = ConfigLoader.load(args);
        } catch (ConfigException e) {
            System.err.println("configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }
        JRedisServer server;
        try {
            server = JRedisServer.start(config);
        } catch (DataDirLockedException e) {
            log.error(e.getMessage());
            exit(2);
            return;
        } catch (DataLoadException e) {
            log.error("cannot load data: {}", e.getMessage());
            exit(3);
            return;
        } catch (Exception e) {
            log.error("start-up failed: {}", e.toString(), e);
            exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "jredis-shutdown-hook"));
        server.awaitTermination();
        System.exit(server.engine().failed() ? 4 : 0);
    }

    private static void exit(int code) {
        FatalHandlers.haltProcess().fatal("start-up failed", null, code);
    }

    private static void usage() {
        System.out.println("Usage: java [JVM options] -jar j-redis-server.jar [config-file] [--directive value ...]");
        System.out.println();
        System.out.println("  config-file          Redis-style configuration file (directive value per line)");
        System.out.println("  --directive value    override any directive, e.g. --port 6380 --appendonly no");
        System.out.println("  --version            print the version");
        System.out.println();
        System.out.println("Exit codes: 0 ok, 1 config/start-up error, 2 data directory locked, 3 data not loadable, 4 fail-stop");
    }
}
