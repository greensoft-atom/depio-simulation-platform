package com.backend.examples.crypto;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.CertPathValidatorException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import javax.security.auth.x500.X500Principal;

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CertificatesTest {

    @Test
    @DisplayName("openssl's certificate reads from PEM: its names, its key, and the fingerprint openssl prints")
    void readsOpensslsCertificate() throws Exception {
        List<X509Certificate> read = Certificates.read(OpensslFiles.text("rsa-self-signed.pem").getBytes(US_ASCII));

        assertThat(read).hasSize(1);
        X509Certificate certificate = read.get(0);
        assertThat(Certificates.dnsNames(certificate)).containsExactly("a.example.com", "b.example.com");
        assertThat(certificate.getPublicKey()).isEqualTo(Pem.readPublicKey(OpensslFiles.text("rsa-public.pem"), "RSA"));
        // As DER too, and its fingerprint is the SHA-256 of that DER.
        assertThat(Certificates.read(certificate.getEncoded())).containsExactly(certificate);
        assertThat(Certificates.fingerprint(certificate))
                .matches("([0-9A-F]{2}:){31}[0-9A-F]{2}")
                .isEqualTo(java.util.HexFormat.ofDelimiter(":").withUpperCase().formatHex(Hashes.sha256(certificate.getEncoded())));
    }

    @Test
    @DisplayName("a CA issues for a request: the names it asked, a server's uses, and a chain that validates to the root")
    void issuesAndValidates() throws Exception {
        CertificateAuthority ca = CertificateAuthority.create("backend example root", Signatures.ecP256(), Duration.ofDays(3650));
        KeyPair server = Signatures.ecP256();
        PKCS10CertificationRequest request = CertificateAuthority.request(server, "db.internal", List.of("db.internal", "10-0-0-1.internal"));

        X509Certificate leaf = ca.issue(request, Duration.ofDays(90));

        assertThat(leaf.getPublicKey()).isEqualTo(server.getPublic());
        assertThat(Certificates.dnsNames(leaf)).containsExactly("db.internal", "10-0-0-1.internal");
        assertThat(leaf.getBasicConstraints()).isEqualTo(-1);   // not a CA
        assertThat(leaf.getExtendedKeyUsage()).contains("1.3.6.1.5.5.7.3.1");   // serverAuth
        assertThat(ca.certificate().getBasicConstraints()).isZero();   // a CA, with nothing below it
        leaf.verify(ca.certificate().getPublicKey());

        Certificates.validate(List.of(leaf), List.of(ca.certificate()), new Date());
    }

    @Test
    @DisplayName("a chain fails validation under another root, after it expires, and through a leaf posing as a CA")
    void validationRefuses() throws Exception {
        CertificateAuthority ca = CertificateAuthority.create("backend example root", Signatures.rsa(2048), Duration.ofDays(3650));
        CertificateAuthority other = CertificateAuthority.create("another root", Signatures.ecP256(), Duration.ofDays(3650));
        KeyPair serverKeys = Signatures.ecP256();
        X509Certificate leaf = ca.issue(CertificateAuthority.request(serverKeys, "db.internal", List.of("db.internal")), Duration.ofDays(90));

        assertThatThrownBy(() -> Certificates.validate(List.of(leaf), List.of(other.certificate()), new Date()))
                .isInstanceOf(CertPathValidatorException.class);
        Date afterExpiry = Date.from(Instant.now().plus(Duration.ofDays(91)));
        assertThatThrownBy(() -> Certificates.validate(List.of(leaf), List.of(ca.certificate()), afterExpiry))
                .isInstanceOf(CertPathValidatorException.class);

        // The leaf's key signs a certificate of its own: the leaf is no CA, so the chain is refused.
        X509Certificate posing = signedBy(leaf, serverKeys.getPrivate());
        assertThatThrownBy(() -> Certificates.validate(List.of(posing, leaf), List.of(ca.certificate()), new Date()))
                .isInstanceOf(CertPathValidatorException.class);
    }

    @Test
    @DisplayName("the CA refuses a request whose signature is not its key's, and one naming no host")
    void refusesBadRequests() throws Exception {
        CertificateAuthority ca = CertificateAuthority.create("backend example root", Signatures.ecP256(), Duration.ofDays(3650));
        KeyPair claimed = Signatures.ecP256();
        KeyPair signer = Signatures.ecP256();
        PKCS10CertificationRequest forged = new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=db.internal"), claimed.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer.getPrivate()));
        assertThatThrownBy(() -> ca.issue(forged, Duration.ofDays(90)))
                .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("signature");

        PKCS10CertificationRequest noNames = new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=db.internal"), claimed.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(claimed.getPrivate()));
        assertThat(noNames.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest)).isEmpty();
        assertThatThrownBy(() -> ca.issue(noNames, Duration.ofDays(90)))
                .isInstanceOf(GeneralSecurityException.class).hasMessageContaining("names no host");
    }

    /** A certificate for a new key, signed by {@code issuer}'s key whatever {@code issuer} may do. */
    private static X509Certificate signedBy(X509Certificate issuer, PrivateKey issuerKey) throws Exception {
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.TEN,
                Date.from(now.minusSeconds(60)), Date.from(now.plus(Duration.ofDays(1))),
                new X500Principal("CN=posing.internal"), Signatures.ecP256().getPublic());
        return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey)));
    }

    @Test
    @DisplayName("the runtime trusts public roots")
    void systemRoots() throws Exception {
        assertThat(Certificates.systemRoots()).hasSizeGreaterThan(50);
    }
}
