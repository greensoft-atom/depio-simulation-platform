package com.backend.examples.crypto;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXCertPathValidatorResult;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * X.509 certificates with Java alone: reading them (PEM or DER, one or a chain), what they name,
 * their fingerprint, and whether a chain leads to a root we trust. Making them takes BouncyCastle:
 * {@link CertificateAuthority}.
 */
public final class Certificates {

    private Certificates() {
    }

    /** Every certificate in it, in order: PEM (one block or several) or DER. */
    public static List<X509Certificate> read(byte[] pemOrDer) throws GeneralSecurityException {
        List<X509Certificate> chain = new ArrayList<>();
        for (var certificate : CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pemOrDer))) {
            chain.add((X509Certificate) certificate);
        }
        return chain;
    }

    /**
     * The DNS names it is for, from the subject alternative names: all a device checks a host
     * against. The subject's CN is not one of them.
     */
    public static List<String> dnsNames(X509Certificate certificate) throws GeneralSecurityException {
        Collection<List<?>> names = certificate.getSubjectAlternativeNames();
        List<String> dns = new ArrayList<>();
        if (names != null) {
            for (List<?> name : names) {
                if ((Integer) name.get(0) == 2) {   // 2 is dNSName in RFC 5280's GeneralName
                    dns.add((String) name.get(1));
                }
            }
        }
        return dns;
    }

    /** SHA-256 of the DER, as {@code openssl x509 -fingerprint -sha256} prints it: AB:CD:... */
    public static String fingerprint(X509Certificate certificate) throws GeneralSecurityException {
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(Hashes.sha256(certificate.getEncoded()));
    }

    /**
     * Checks a chain, the leaf first and the intermediates after it (not the root), against the
     * roots trusted, at a moment: each signature, each validity period, each CA's constraints. It
     * throws {@code CertPathValidatorException}, saying which and why, when it does not hold.
     *
     * <p>What it does not check: the host name (the TLS layer does, {@link Tls}), the purpose
     * (extended key usage, also TLS's), and revocation, turned off here because it needs the
     * network to reach the CA. To turn it on: the validator's {@code getRevocationChecker()},
     * given to {@code params.addCertPathChecker}.
     */
    public static PKIXCertPathValidatorResult validate(List<X509Certificate> chain, Collection<X509Certificate> roots, Date at)
            throws GeneralSecurityException {
        CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(chain);
        Set<TrustAnchor> anchors = new HashSet<>();
        for (X509Certificate root : roots) {
            anchors.add(new TrustAnchor(root, null));
        }
        PKIXParameters params = new PKIXParameters(anchors);
        params.setRevocationEnabled(false);
        params.setDate(at);
        return (PKIXCertPathValidatorResult) CertPathValidator.getInstance("PKIX").validate(path, params);
    }

    /** The roots the runtime trusts ({@code lib/security/cacerts}): what a public CA's chain is checked against. */
    public static List<X509Certificate> systemRoots() throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (var manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return List.of(x509.getAcceptedIssuers());
            }
        }
        return List.of();
    }
}
