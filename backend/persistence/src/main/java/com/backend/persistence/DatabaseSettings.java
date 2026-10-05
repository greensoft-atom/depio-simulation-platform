package com.backend.persistence;

import java.util.Map;

import com.backend.common.RefusedConfiguration;
import com.backend.common.Secrets;

/**
 * Where the database is and how to log in to it, read from the environment (S-3).
 *
 * <h2>Never from the command line</h2>
 *
 * A process's arguments are readable by every local user — {@code /proc/<pid>/cmdline} is
 * what {@code ps} prints — so a password passed there is published to the whole machine.
 * An environment variable is readable only by the process's owner and root, but a systemd
 * unit's {@code Environment=} lines are shown to any user by {@code systemctl show}. A file
 * is the one place that can be kept to the service alone: systemd's {@code LoadCredential=}
 * puts it under {@code $CREDENTIALS_DIRECTORY}, readable by nothing else, and container
 * secrets arrive the same way.
 *
 * So the password comes from {@code BACKEND_DB_PASSWORD_FILE}; failing that from
 * {@code BACKEND_DB_PASSWORD}; failing that it is the development password, and the caller
 * says so loudly. The rules are {@link Secrets}', shared with the store's password: a file
 * that is named and cannot be read refuses the configuration rather than falling back.
 */
public record DatabaseSettings(String url, String user, String password, String passwordSource, int poolSize) {

    static final String DEV_URL =
            "jdbc:mysql://127.0.0.1:3306/backend_dev?useSSL=false&allowPublicKeyRetrieval=true";
    static final String DEV_USER = "backend";
    static final String DEV_PASSWORD = "backend-dev-password";
    static final String DEV_SOURCE = "the development default";

    /** What a main prints when it is handed database settings as arguments, then exits. */
    public static final String NOT_ON_THE_COMMAND_LINE = "Database settings are not taken on "
            + "the command line, where every local user can read them with ps. Set "
            + "BACKEND_DB_URL and BACKEND_DB_USER, and BACKEND_DB_PASSWORD_FILE (or "
            + "BACKEND_DB_PASSWORD).";

    public static DatabaseSettings fromEnvironment() {
        return from(System.getenv());
    }

    /** The most connections one process may hold: short of MySQL's default max_connections, 151. */
    static final int MOST_CONNECTIONS = 100;

    static DatabaseSettings from(Map<String, String> env) {
        String url = env.getOrDefault("BACKEND_DB_URL", DEV_URL);
        String user = env.getOrDefault("BACKEND_DB_USER", DEV_USER);
        int pool = poolSize(env.get("BACKEND_DB_POOL_SIZE"));
        Secrets.Secret password = Secrets.read(env, "BACKEND_DB_PASSWORD");
        return password == null
                ? new DatabaseSettings(url, user, DEV_PASSWORD, DEV_SOURCE, pool)
                : new DatabaseSettings(url, user, password.value(), password.source(), pool);
    }

    /**
     * The pool's size, for the database host's cores rather than the application's threads
     * (06 §7): named, 1 to {@value #MOST_CONNECTIONS}; else 0, which is the process's own default.
     */
    private static int poolSize(String value) {
        if (value == null) {
            return 0;
        }
        try {
            int size = Integer.parseInt(value.trim());
            if (size >= 1 && size <= MOST_CONNECTIONS) {
                return size;
            }
        } catch (NumberFormatException e) {
            // refused below
        }
        throw new RefusedConfiguration("BACKEND_DB_POOL_SIZE must be a whole number from 1 to "
                + MOST_CONNECTIONS + ", not '" + value + "'");
    }

    /** The pool size named in the environment, or this process's own default. */
    public int poolSizeOr(int processDefault) {
        return poolSize > 0 ? poolSize : processDefault;
    }

    public boolean usesDevelopmentPassword() {
        return DEV_SOURCE.equals(passwordSource);
    }

    /**
     * Everything but the password. A record's own {@code toString} prints every field, so
     * the first log line that printed these settings would have printed the password. The
     * URL loses its query string too, which is where a JDBC URL can carry one.
     */
    @Override
    public String toString() {
        int query = url.indexOf('?');
        return "DatabaseSettings[url=" + (query < 0 ? url : url.substring(0, query))
                + ", user=" + user + ", password from " + passwordSource
                + (poolSize > 0 ? ", pool " + poolSize : "") + "]";
    }
}
