package com.backend.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The peers whose {@code X-Forwarded-For} is believed: nginx. Loopback always, nginx on this
 * machine; and the addresses in {@code BACKEND_TRUSTED_PROXIES}, since the front's nginx reaches
 * every machine's processes over the private network (D-82).
 *
 * The setting is a comma-separated list of IP literals. Anything else refuses the start: a name
 * would have to be looked up, and a range or a typo would believe headers it should not.
 */
public final class TrustedProxies {

    /** This machine alone. */
    public static final TrustedProxies LOOPBACK = new TrustedProxies(Set.of());

    private static final String SETTING = "BACKEND_TRUSTED_PROXIES";
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    /** Hex digits, colons and dots with a colon among them: the JDK parses it as IPv6 or rejects it, never asking DNS. */
    private static final Pattern IPV6 = Pattern.compile("(?=.*:)[0-9A-Fa-f:][0-9A-Fa-f:.]*");

    private final Set<InetAddress> named;

    private TrustedProxies(Set<InetAddress> named) {
        this.named = named;
    }

    public static TrustedProxies fromEnvironment(Map<String, String> env) {
        String list = env.get(SETTING);
        if (list == null || list.isBlank()) {
            return LOOPBACK;
        }
        Set<InetAddress> named = new LinkedHashSet<>();
        for (String entry : list.split(",", -1)) {
            InetAddress address = literal(entry.strip());
            if (address == null) {
                throw new RefusedConfiguration(SETTING + " must be IP addresses separated by commas, not '"
                        + entry.strip() + "' in " + list);
            }
            named.add(address);
        }
        return new TrustedProxies(Set.copyOf(named));
    }

    public boolean trusts(InetAddress peer) {
        return peer != null && (peer.isLoopbackAddress() || named.contains(peer));
    }

    @Override
    public String toString() {
        return named.isEmpty() ? "loopback" : "loopback and " + named;
    }

    private static InetAddress literal(String s) {
        try {
            Matcher v4 = IPV4.matcher(s);
            if (v4.matches()) {
                byte[] b = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(v4.group(i + 1));
                    if (octet > 255) {
                        return null;
                    }
                    b[i] = (byte) octet;
                }
                return InetAddress.getByAddress(b);
            }
            return IPV6.matcher(s).matches() ? InetAddress.getByName(s) : null;
        } catch (UnknownHostException notAnAddress) {
            return null;
        }
    }
}
