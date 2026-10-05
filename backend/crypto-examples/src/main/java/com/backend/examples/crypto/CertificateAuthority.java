package com.backend.examples.crypto;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCSException;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

/**
 * A private CA with BouncyCastle's {@code bcpkix}: a root, a certificate signing request (CSR) as a
 * server makes one, and a server certificate issued for it. Java 21 can read and check all of these
 * ({@link Certificates}) but has no API to make them.
 *
 * <p>Where: TLS inside our own network, where no public CA is needed and every client is ours to
 * configure: MySQL's private CA (operations/01 §9) is this, made with {@code openssl}; mutual TLS
 * between services. Not for what players' devices reach: those trust public CAs only.
 *
 * <p>The root's private key is the whole CA: whoever has it issues anything. Kept offline,
 * encrypted ({@link AnyPem#encrypted}), never on a server that serves traffic.
 */
public final class CertificateAuthority {

    private final X509Certificate certificate;
    private final PrivateKey key;

    private CertificateAuthority(X509Certificate certificate, PrivateKey key) {
        this.certificate = certificate;
        this.key = key;
    }

    /**
     * A new root: self-signed, allowed to sign certificates and CRLs, and with no CA below it
     * (path length 0), so a certificate it issues cannot issue more.
     */
    public static CertificateAuthority create(String name, KeyPair keys, Duration validity) throws GeneralSecurityException {
        X500Name subject = new X500Name("CN=" + name);
        Instant now = Instant.now();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, serial(),
                Date.from(now.minus(Duration.ofHours(1))), Date.from(now.plus(validity)), subject, keys.getPublic());
        JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
        try {
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            builder.addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(keys.getPublic()));
        } catch (CertIOException e) {
            throw new GeneralSecurityException(e);
        }
        return new CertificateAuthority(sign(builder, keys.getPrivate()), keys.getPrivate());
    }

    public X509Certificate certificate() {
        return certificate;
    }

    /** What a server sends a CA: its public key and the names it wants, signed with its private key. */
    public static PKCS10CertificationRequest request(KeyPair keys, String commonName, List<String> dnsNames)
            throws GeneralSecurityException {
        try {
            ExtensionsGenerator wanted = new ExtensionsGenerator();
            wanted.addExtension(Extension.subjectAlternativeName, false, dnsNames(dnsNames));
            return new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=" + commonName), keys.getPublic())
                    .addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, wanted.generate())
                    .build(signer(keys.getPrivate()));
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    /**
     * A server certificate for a request, once its signature shows its sender holds the key. It
     * takes the request's key and DNS names, and nothing else the request asks: the CA decides what a
     * certificate may do (here: a TLS server or client, not a CA). A real CA would also check that
     * the sender may have those names.
     */
    public X509Certificate issue(PKCS10CertificationRequest csr, Duration validity) throws GeneralSecurityException {
        JcaPKCS10CertificationRequest request = new JcaPKCS10CertificationRequest(csr);
        PublicKey subjectKey = request.getPublicKey();
        try {
            if (!request.isSignatureValid(new JcaContentVerifierProviderBuilder().build(subjectKey))) {
                throw new GeneralSecurityException("the request's signature does not match its key");
            }
        } catch (OperatorCreationException | PKCSException e) {
            throw new GeneralSecurityException("the request's signature cannot be checked", e);
        }
        List<String> names = requestedNames(request);
        if (names.isEmpty()) {
            throw new GeneralSecurityException("the request names no host: devices would refuse its certificate");
        }
        Instant now = Instant.now();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new JcaX509CertificateHolder(certificate).getSubject(), serial(),
                Date.from(now.minus(Duration.ofHours(1))), Date.from(now.plus(validity)), request.getSubject(), subjectKey);
        JcaX509ExtensionUtils utils = new JcaX509ExtensionUtils();
        try {
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
            builder.addExtension(Extension.subjectAlternativeName, false, dnsNames(names));
            builder.addExtension(Extension.authorityKeyIdentifier, false, utils.createAuthorityKeyIdentifier(certificate));
            builder.addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(subjectKey));
        } catch (CertIOException e) {
            throw new GeneralSecurityException(e);
        }
        return sign(builder, key);
    }

    private static List<String> requestedNames(JcaPKCS10CertificationRequest request) {
        List<String> names = new ArrayList<>();
        for (Attribute attribute : request.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest)) {
            for (ASN1Encodable value : attribute.getAttributeValues()) {
                GeneralNames requested = GeneralNames.fromExtensions(Extensions.getInstance(value), Extension.subjectAlternativeName);
                for (GeneralName name : requested == null ? new GeneralName[0] : requested.getNames()) {
                    if (name.getTagNo() == GeneralName.dNSName) {
                        names.add(DERIA5String.getInstance(name.getName()).getString());
                    }
                }
            }
        }
        return names;
    }

    private static GeneralNames dnsNames(List<String> names) {
        return new GeneralNames(names.stream().map(n -> new GeneralName(GeneralName.dNSName, n)).toArray(GeneralName[]::new));
    }

    /** Random and positive, at most 20 bytes (RFC 5280): never a counter, which lets certificates be predicted. */
    private static BigInteger serial() {
        return new BigInteger(1, RandomTokens.bytes(16));
    }

    private static ContentSigner signer(PrivateKey key) throws GeneralSecurityException {
        String algorithm = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "Ed25519", "EdDSA" -> "Ed25519";
            default -> throw new GeneralSecurityException("no signature for a " + key.getAlgorithm() + " key");
        };
        try {
            return new JcaContentSignerBuilder(algorithm).build(key);
        } catch (OperatorCreationException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static X509Certificate sign(X509v3CertificateBuilder builder, PrivateKey key) throws GeneralSecurityException {
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer(key)));
    }
}
