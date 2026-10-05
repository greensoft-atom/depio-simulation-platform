package com.backend.arena;

import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.KeyManagerFactory;

import com.backend.common.RefusedConfiguration;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

/**
 * The arena's TLS (S-6; 02-networking §1): the JDK's provider, not a native library - it needs
 * nothing the offline bundle lacks, and nobody has measured a need for more speed.
 *
 * TLS 1.3 and 1.2. The design named 1.3 alone, but Android 5 to 9 speak only 1.2, and a mobile
 * product cannot turn those devices away. The cost per frame is 22 bytes on 1.3 and 29 on
 * 1.2 (RFC 8446 and RFC 5288), both inside the per-packet overhead the bandwidth budget
 * already allows (02 §4).
 *
 * @param expires when the first certificate served expires, the intermediates included. The
 *        keystore is read once, at start, so a renewed certificate is served from the next
 *        restart; this is what the metrics expose so the renewal is not left to memory.
 * @param certificates the certificates served, one per key in the keystore
 */
public record ArenaTls(SslContext context, Instant expires, List<X509Certificate> certificates) {

    /**
     * Whether a client dialling {@code host} would accept the certificate's name: an exact DNS
     * name, a wildcard standing for exactly one leftmost label (RFC 6125 §6.4.3), or an IP
     * address. Subject alternative names only, as clients check nothing else any more.
     *
     * The arena tells clients which host to dial, and if the certificate does not name it
     * every client that checks - which is every client - refuses the connection, while the
     * arena announces itself as healthy.
     */
    public boolean covers(String host) {
        for (X509Certificate c : certificates) {
            Collection<List<?>> names;
            try {
                names = c.getSubjectAlternativeNames();
            } catch (java.security.cert.CertificateParsingException unreadable) {
                continue;
            }
            if (names != null && namesCover(names, host)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Over one certificate's names, as {@link X509Certificate#getSubjectAlternativeNames}
     * gives them. An other-name, X.400 or EDI name comes as its DER bytes, not a string, and
     * is skipped: cast, it threw, and a certificate carrying one stopped the arena starting.
     */
    static boolean namesCover(Collection<List<?>> names, String host) {
        for (List<?> name : names) {
            int type = (Integer) name.get(0);
            if (!(name.get(1) instanceof String value)) {
                continue;
            }
            if (type == 2 && dnsMatches(value, host) || type == 7 && sameAddress(value, host)) {
                return true;
            }
        }
        return false;
    }

    private static boolean dnsMatches(String pattern, String host) {
        String p = pattern.toLowerCase(Locale.ROOT);
        String h = host.toLowerCase(Locale.ROOT);
        if (!p.startsWith("*.")) {
            return p.equals(h);
        }
        int dot = h.indexOf('.');
        return dot > 0 && h.substring(dot).equals(p.substring(1));
    }

    /** Compared as addresses, so "::1" and "0:0:0:0:0:0:0:1" agree; a name is never looked up. */
    private static boolean sameAddress(String certified, String host) {
        if (!isAddressLiteral(host)) {
            return false;
        }
        try {
            return InetAddress.getByName(certified).equals(InetAddress.getByName(host));
        } catch (java.net.UnknownHostException malformed) {
            return false;
        }
    }

    private static boolean isAddressLiteral(String host) {
        return !host.isEmpty()
                && (host.contains(":") || host.chars().allMatch(ch -> ch == '.' || Character.isDigit(ch)));
    }

    /**
     * Loads a PKCS#12 keystore holding the certificate for the name clients dial, and its key.
     *
     * Refuses what would start and then fail every client: a store with no private key in it
     * (a trust store, given by mistake, makes every handshake fail with "no cipher suites in
     * common") and a certificate, or an intermediate served with it, that has expired or is not
     * valid yet.
     */
    public static ArenaTls fromKeystore(Path keystore, char[] password) {
        KeyStore ks;
        try (InputStream in = Files.newInputStream(keystore)) {
            ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
        } catch (Exception e) {
            throw new RefusedConfiguration("cannot load the arena's TLS keystore " + keystore
                    + ": " + e, e);
        }
        Instant expires = null;
        List<X509Certificate> served = new ArrayList<>();
        try {
            for (String alias : Collections.list(ks.aliases())) {
                Certificate[] chain = ks.isKeyEntry(alias) ? ks.getCertificateChain(alias) : null;
                if (chain == null || !(chain[0] instanceof X509Certificate leaf)) {
                    continue;                  // a trusted certificate, or a secret key
                }
                // The whole chain is served, and a device refuses it when any link has expired.
                for (Certificate link : chain) {
                    X509Certificate certificate = (X509Certificate) link;
                    Instant from = certificate.getNotBefore().toInstant();
                    Instant until = certificate.getNotAfter().toInstant();
                    Instant now = Instant.now();
                    if (now.isAfter(until) || now.isBefore(from)) {
                        throw new RefusedConfiguration("the certificate " + certificate.getSubjectX500Principal()
                                + " in '" + alias + "' in " + keystore + " is valid from " + from
                                + " until " + until + ", and it is " + now);
                    }
                    expires = expires == null || until.isBefore(expires) ? until : expires;
                }
                served.add(leaf);
            }
            if (expires == null) {
                throw new RefusedConfiguration(keystore + " holds no private key with a"
                        + " certificate: is it a trust store?");
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            SslContext context = SslContextBuilder.forServer(kmf)
                    .sslProvider(SslProvider.JDK)
                    .protocols("TLSv1.3", "TLSv1.2")
                    .build();
            return new ArenaTls(context, expires, List.copyOf(served));
        } catch (RefusedConfiguration refused) {
            throw refused;
        } catch (Exception e) {
            throw new RefusedConfiguration("cannot use the arena's TLS keystore " + keystore
                    + ": " + e, e);
        }
    }
}
