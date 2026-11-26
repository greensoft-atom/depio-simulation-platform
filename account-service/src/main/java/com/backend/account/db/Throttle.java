package com.backend.account.db;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HexFormat;

import javax.sql.DataSource;

/**
 * The login limits (10 §3.3, the platform's of 04 §1): fixed windows counted in MySQL, so they hold across every
 * account service process without a store of the backend's (D-117).
 *
 * <ul>
 * <li><b>Per address, {@value #ADDRESS_LIMIT} a minute</b>, counting every sign-up, login, password change and
 *     deletion: what one machine may spend of Argon2, and how fast it may guess. IPv6 by its /64.</li>
 * <li><b>Per account, {@value #ACCOUNT_LIMIT} a quarter hour</b>, counting every attempt that names it, whether it
 *     exists or not: guessing one account from many addresses.</li>
 * </ul>
 *
 * Every attempt counts, before its password is checked, so attempts made at once cannot all slip under a limit. One
 * statement a key, {@code INSERT … ON DUPLICATE KEY UPDATE n = LAST_INSERT_ID(n + 1)}: the count is the
 * connection's {@code LAST_INSERT_ID()}, with no read that another attempt could come between. MySQL not answering
 * throws, and the attempt is refused (503): a limit that fails open is off when it is attacked hardest.
 */
public final class Throttle {

    static final int ADDRESS_LIMIT = 30;
    static final int ADDRESS_WINDOW_SECONDS = 60;
    static final int ACCOUNT_LIMIT = 10;
    static final int ACCOUNT_WINDOW_SECONDS = 15 * 60;

    /** Allowed, or not and for how long. */
    public record Verdict(boolean allowed, int retryAfterSeconds) { }

    private static final Verdict ALLOWED = new Verdict(true, 0);

    private final DataSource dataSource;
    private final Clock clock;

    public Throttle(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    /**
     * One attempt counted against its address, and against its account when it names one.
     *
     * @param address from {@link #addressKey}
     * @param account a username's key ({@code CredentialRules.usernameKey}); null when the attempt names none
     */
    public Verdict attempt(String address, String account) throws SQLException {
        long now = clock.instant().getEpochSecond();
        int retryAfter = 0;
        try (Connection c = dataSource.getConnection()) {
            retryAfter = over(c, "a:" + address, now, ADDRESS_WINDOW_SECONDS, ADDRESS_LIMIT);
            if (account != null) {
                retryAfter = Math.max(retryAfter, over(c, "u:" + account, now, ACCOUNT_WINDOW_SECONDS, ACCOUNT_LIMIT));
            }
        }
        return retryAfter == 0 ? ALLOWED : new Verdict(false, retryAfter);
    }

    /** Counts one attempt in the key's window: 0 within the limit, else the seconds until the window ends. */
    private static int over(Connection c, String key, long now, int window, int limit) throws SQLException {
        long start = now - Math.floorMod(now, window);
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO attempt (k, window_start, n) VALUES (?, ?, LAST_INSERT_ID(1))"
                + " ON DUPLICATE KEY UPDATE n = LAST_INSERT_ID(n + 1)")) {
            ps.setString(1, key);
            ps.setLong(2, start);
            ps.executeUpdate();
        }
        long count;
        try (PreparedStatement ps = c.prepareStatement("SELECT LAST_INSERT_ID()"); ResultSet r = ps.executeQuery()) {
            r.next();
            count = r.getLong(1);
        }
        return count > limit ? (int) (start + window - now) : 0;
    }

    /** The key an address is counted under: an IPv4 address as itself, an IPv6 address by its /64, one subscriber's. */
    public static String addressKey(InetAddress address) {
        if (address instanceof Inet4Address) {
            return address.getHostAddress();
        }
        return HexFormat.of().formatHex(address.getAddress(), 0, 8) + "/64";
    }
}
