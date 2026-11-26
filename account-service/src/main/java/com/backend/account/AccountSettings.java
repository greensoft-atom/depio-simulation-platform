package com.backend.account;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.backend.account.db.DatabaseSettings;

/**
 * The account service's settings, from its settings file's variables, never its command line (10 §3.1; D-102, S-3).
 * Unset, each is the development one's (O-40); malformed, each refuses the start, exit 2, naming the variable, since
 * restarting cannot mend a setting.
 */
public record AccountSettings(String bind, int port, String issuer, String audience, String signingKey, String retiredKeys,
                              List<URI> events, TrustedProxies proxies, InetSocketAddress metrics, DatabaseSettings database) {

    static final String DEV_ISSUER = "http://127.0.0.1:8083";
    static final String DEV_EVENTS = "http://127.0.0.1:8080/v1/account-events";
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6 = Pattern.compile("(?=.*:)[0-9A-Fa-f:][0-9A-Fa-f:.]*");
    private static final Pattern AUDIENCE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public static AccountSettings fromEnvironment() {
        return from(System.getenv());
    }

    public static AccountSettings from(Map<String, String> env) {
        String bind = env.getOrDefault("ACCOUNT_BIND", "127.0.0.1").strip();
        if (!ipLiteral(bind)) {
            throw refused("ACCOUNT_BIND", bind, "an IP address");
        }
        int port = port("ACCOUNT_PORT", env.getOrDefault("ACCOUNT_PORT", "8070"));
        String issuer = issuer(env.getOrDefault("ACCOUNT_ISSUER", DEV_ISSUER).strip());
        String audience = env.getOrDefault("ACCOUNT_AUDIENCE", "backend").strip();
        if (!AUDIENCE.matcher(audience).matches()) {
            throw refused("ACCOUNT_AUDIENCE", audience, "1 to 64 letters, digits and . _ : -");
        }
        Secrets.Secret key = Secrets.read(env, "ACCOUNT_SIGNING_KEY");
        if (key == null || key.value().isBlank()) {
            throw new RefusedConfiguration("ACCOUNT_SIGNING_KEY is not set: make one with scripts/account-key.sh");
        }
        List<URI> events = new ArrayList<>();
        String list = env.getOrDefault("ACCOUNT_EVENTS_URL", DEV_EVENTS);
        for (String entry : list.split(",", -1)) {
            events.add(url("ACCOUNT_EVENTS_URL", entry.strip()));
        }
        TrustedProxies proxies = TrustedProxies.fromEnvironment(env);
        String metricsAddr = env.get("ACCOUNT_METRICS_ADDR");
        InetSocketAddress metrics = metricsAddr == null || metricsAddr.isBlank() ? null : address("ACCOUNT_METRICS_ADDR", metricsAddr.strip());
        return new AccountSettings(bind, port, issuer, audience, key.value().strip(), env.get("ACCOUNT_RETIRED_KEYS"),
                List.copyOf(events), proxies, metrics, DatabaseSettings.from(env));
    }

    /** Everything but the signing key, which a record's own toString would print. */
    @Override
    public String toString() {
        return "AccountSettings[" + bind + ":" + port + ", issuer " + issuer + ", audience " + audience
                + ", events to " + events + ", trusted proxies " + proxies + (metrics == null ? "" : ", metrics on " + metrics)
                + ", " + database + "]";
    }

    /**
     * An absolute http or https URL with a host, no query, fragment or trailing slash: the backend compares it to a
     * token's {@code iss} exactly, and a slash one side has and the other has not is a token refused for ever.
     */
    private static String issuer(String value) {
        URI u;
        try {
            u = new URI(value);
        } catch (URISyntaxException e) {
            throw refused("ACCOUNT_ISSUER", value, "an absolute https:// or http:// URL");
        }
        if (!("https".equals(u.getScheme()) || "http".equals(u.getScheme())) || u.getHost() == null
                || u.getRawQuery() != null || u.getRawFragment() != null || value.endsWith("/")) {
            throw refused("ACCOUNT_ISSUER", value, "an absolute https:// or http:// URL, with no query and no trailing slash");
        }
        return value;
    }

    private static URI url(String setting, String value) {
        try {
            URI u = new URI(value);
            if (("https".equals(u.getScheme()) || "http".equals(u.getScheme())) && u.getHost() != null) {
                return u;
            }
        } catch (URISyntaxException e) {
            // refused below
        }
        throw refused(setting, value, "http:// or https:// URLs separated by commas");
    }

    private static int port(String setting, String value) {
        try {
            int p = Integer.parseInt(value.strip());
            if (p >= 1 && p <= 65535) {
                return p;
            }
        } catch (NumberFormatException e) {
            // refused below
        }
        throw refused(setting, value, "a port, 1 to 65535");
    }

    private static InetSocketAddress address(String setting, String value) {
        int colon = value.lastIndexOf(':');
        if (colon <= 0) {
            throw refused(setting, value, "an IP address and a port, host:port");
        }
        String host = value.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (!ipLiteral(host)) {
            throw refused(setting, value, "an IP address and a port, host:port");
        }
        return new InetSocketAddress(host, port(setting, value.substring(colon + 1)));
    }

    /** Parsed as a literal, never looked up: a name would ask DNS. */
    private static boolean ipLiteral(String s) {
        java.util.regex.Matcher v4 = IPV4.matcher(s);
        if (v4.matches()) {
            for (int i = 1; i <= 4; i++) {
                if (Integer.parseInt(v4.group(i)) > 255) {
                    return false;
                }
            }
            return true;
        }
        if (!IPV6.matcher(s).matches()) {
            return false;
        }
        try {
            java.net.InetAddress.getByName(s);
            return true;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }

    private static RefusedConfiguration refused(String setting, String value, String what) {
        return new RefusedConfiguration(setting + " must be " + what + ", not '" + value + "'");
    }
}
