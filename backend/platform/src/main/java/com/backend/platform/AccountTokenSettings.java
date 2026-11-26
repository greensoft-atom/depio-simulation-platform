package com.backend.platform;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.regex.Pattern;

import com.backend.common.RefusedConfiguration;

/**
 * Where the account service's tokens come from, and whose they must be (10 §4.1, D-117): {@code BACKEND_ACCOUNT_ISSUER},
 * the {@code iss} taken, exactly; {@code BACKEND_ACCOUNT_AUDIENCE}, this backend's name, which {@code aud} must hold;
 * {@code BACKEND_ACCOUNT_JWKS_URL}, the account service's key set. Unset, the development account service's (O-40).
 *
 * <p>The key set decides which tokens are believed, so it is read by {@code https://}, or by {@code http://} only from
 * an IP address on this machine or the private network (loopback, RFC 1918, IPv6's unique local addresses), never from
 * a name, which would ask DNS. Anything else refuses the start, exit 2.
 */
public record AccountTokenSettings(String issuer, String audience, URI jwks) {

    static final String DEV_ISSUER = "http://127.0.0.1:8083";
    static final String DEV_JWKS = DEV_ISSUER + "/.well-known/jwks.json";
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern AUDIENCE = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    public static AccountTokenSettings fromEnvironment(Map<String, String> env) {
        String issuer = env.getOrDefault("BACKEND_ACCOUNT_ISSUER", DEV_ISSUER).strip();
        URI i = uri("BACKEND_ACCOUNT_ISSUER", issuer);
        if (!("https".equals(i.getScheme()) || "http".equals(i.getScheme())) || i.getHost() == null
                || i.getRawQuery() != null || i.getRawFragment() != null || issuer.endsWith("/")) {
            throw refused("BACKEND_ACCOUNT_ISSUER", issuer, "the account service's issuer as its tokens carry it: an absolute"
                    + " https:// or http:// URL, with no query and no trailing slash");
        }
        String audience = env.getOrDefault("BACKEND_ACCOUNT_AUDIENCE", "backend").strip();
        if (!AUDIENCE.matcher(audience).matches()) {
            throw refused("BACKEND_ACCOUNT_AUDIENCE", audience, "1 to 64 letters, digits and . _ : -");
        }
        String jwks = env.getOrDefault("BACKEND_ACCOUNT_JWKS_URL", DEV_JWKS).strip();
        URI k = uri("BACKEND_ACCOUNT_JWKS_URL", jwks);
        boolean ok = k.getHost() != null && ("https".equals(k.getScheme())
                || "http".equals(k.getScheme()) && privateLiteral(k.getHost()));
        if (!ok) {
            throw refused("BACKEND_ACCOUNT_JWKS_URL", jwks, "the account service's key set by https://, or by http:// from"
                    + " an IP address on this machine or the private network");
        }
        return new AccountTokenSettings(issuer, audience, k);
    }

    /** An IP literal, not a name, that is loopback or private. */
    private static boolean privateLiteral(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!IPV4.matcher(h).matches() && !h.contains(":")) {
            return false;                                   // a name: never looked up
        }
        try {
            InetAddress a = InetAddress.getByName(h);       // a literal: parsed, not resolved
            byte[] b = a.getAddress();
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || (b.length == 16 && (b[0] & 0xfe) == 0xfc);
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static URI uri(String setting, String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw refused(setting, value, "a URL");
        }
    }

    private static RefusedConfiguration refused(String setting, String value, String what) {
        return new RefusedConfiguration(setting + " must be " + what + ", not '" + value + "'");
    }
}
