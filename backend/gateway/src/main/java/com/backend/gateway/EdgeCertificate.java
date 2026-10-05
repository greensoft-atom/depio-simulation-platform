package com.backend.gateway;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * The certificate this machine's nginx shows clients for login, purchases and the lobby
 * (operations/01 §4). It is watched from here because nginx exports nothing, and the gateway
 * is the process behind nginx on every machine. The arenas report their own.
 */
final class EdgeCertificate {

    /**
     * When the earliest-expiring certificate in {@code pem} expires, in Unix seconds: the leaf or an
     * intermediate, since a device refuses the chain when either has. Read at every scrape, so
     * a renewal shows at once.
     *
     * @return 0 when the file cannot be read or holds no certificate. An absent series fires
     *         no alert, and 0 fires every expiry alert.
     */
    static double expiry(Path pem) {
        try (InputStream in = Files.newInputStream(pem)) {
            long earliest = Long.MAX_VALUE;
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                earliest = Math.min(earliest, ((X509Certificate) c).getNotAfter().toInstant().getEpochSecond());
            }
            return earliest == Long.MAX_VALUE ? 0 : earliest;
        } catch (Exception unreadable) {
            return 0;
        }
    }

    private EdgeCertificate() {
    }
}
