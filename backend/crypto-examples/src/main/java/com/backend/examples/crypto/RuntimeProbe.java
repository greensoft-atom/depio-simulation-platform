package com.backend.examples.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.KeyPair;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Security;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.List;

import javax.crypto.KEM;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.net.ssl.SSLContext;

/**
 * Runs the examples that need Java alone on whatever runtime starts it: the release's is made by
 * {@code jlink} with only the modules {@code make-release.sh} names, so something the tests pass on
 * the full JDK can still be missing there. Before a process relies on an algorithm, add it here and
 * run it on the release's runtime (docs/development/04-crypto.md §5):
 *
 * <pre>
 * backend/target/release/backend-*&#47;runtime/bin/java -cp backend/crypto-examples/target/classes \
 *     com.backend.examples.crypto.RuntimeProbe
 * </pre>
 *
 * Exits 1 when anything failed.
 */
public final class RuntimeProbe {

    private int failed;

    interface Check {
        boolean holds() throws Exception;
    }

    RuntimeProbe() {
    }

    public static void main(String[] args) {
        System.exit(new RuntimeProbe().run() == 0 ? 0 : 1);
    }

    /** The number of checks that failed. */
    int run() {
        byte[] m = "probe".getBytes(UTF_8);
        System.out.println("java " + Runtime.version() + " at " + System.getProperty("java.home"));
        check("SHA-256, SHA-512, SHA3-256", () -> {
            for (String digest : List.of("SHA-256", "SHA-512", "SHA3-256")) {
                MessageDigest.getInstance(digest).digest(m);
            }
            return Hashes.sha256(m).length == 32;
        });
        check("HMAC-SHA256", () -> {
            SecretKey key = Hmacs.newKey();
            return Hmacs.verify(key, m, Hmacs.tag(key, m));
        });
        check("AES-256-GCM", () -> {
            SecretKey key = Aead.newAesKey();
            return Arrays.equals(m, Aead.decrypt(Aead.AES_GCM, key, Aead.encrypt(Aead.AES_GCM, key, m, null), null));
        });
        check("ChaCha20-Poly1305", () -> {
            SecretKey key = Aead.newChaChaKey();
            return Arrays.equals(m, Aead.decrypt(Aead.CHACHA20_POLY1305, key, Aead.encrypt(Aead.CHACHA20_POLY1305, key, m, null), null));
        });
        check("RSA 3072: SHA256withRSA, RSASSA-PSS, OAEP-SHA256", () -> {
            KeyPair rsa = Signatures.rsa(3072);
            return Signatures.verify(Signatures.RSA_SHA256, rsa.getPublic(), m, Signatures.sign(Signatures.RSA_SHA256, rsa.getPrivate(), m))
                    && Signatures.verify(Signatures.RSA_PSS, rsa.getPublic(), m, Signatures.sign(Signatures.RSA_PSS, rsa.getPrivate(), m))
                    && Arrays.equals(m, RsaEncryption.decrypt(rsa.getPrivate(), RsaEncryption.encrypt(rsa.getPublic(), m)));
        });
        check("ECDSA P-256, DER and P1363", () -> {
            KeyPair ec = Signatures.ecP256();
            return Signatures.verify(Signatures.ECDSA_SHA256, ec.getPublic(), m, Signatures.sign(Signatures.ECDSA_SHA256, ec.getPrivate(), m))
                    && Signatures.verify(Signatures.ECDSA_SHA256_RAW, ec.getPublic(), m,
                            Signatures.sign(Signatures.ECDSA_SHA256_RAW, ec.getPrivate(), m));
        });
        check("Ed25519", () -> {
            KeyPair ed = Signatures.ed25519();
            return Signatures.verify(Signatures.ED25519, ed.getPublic(), m, Signatures.sign(Signatures.ED25519, ed.getPrivate(), m));
        });
        check("X25519 and ECDH P-256", () -> {
            for (KeyPair[] pair : List.of(new KeyPair[] {x25519(), x25519()}, new KeyPair[] {Signatures.ecP256(), Signatures.ecP256()})) {
                if (!Arrays.equals(agree(pair[0], pair[1]), agree(pair[1], pair[0]))) {
                    return false;
                }
            }
            return true;
        });
        check("KEM, DHKEM over X25519", () -> {
            KeyPair recipient = x25519();
            KEM kem = KEM.getInstance("DHKEM");
            KEM.Encapsulated sent = kem.newEncapsulator(recipient.getPublic()).encapsulate();
            return Arrays.equals(sent.key().getEncoded(),
                    kem.newDecapsulator(recipient.getPrivate()).decapsulate(sent.encapsulation()).getEncoded());
        });
        check("PKCS#12, X.509, PKIX", () -> {
            KeyStore.getInstance("PKCS12").load(null, null);
            CertificateFactory.getInstance("X.509");
            CertPathValidator.getInstance("PKIX");
            return true;
        });
        check("the trusted roots (cacerts)", () -> !Certificates.systemRoots().isEmpty());
        check("TLS 1.3 and 1.2", () -> List.of(SSLContext.getDefault().getSupportedSSLParameters().getProtocols())
                .containsAll(List.of("TLSv1.3", "TLSv1.2")));
        System.out.println("  "
                + (Security.getProvider("SunPKCS11") != null ? "has  " : "lacks")
                + " PKCS#11, keys in a hardware module: the jdk.crypto.cryptoki module (make-release.sh's modules)");
        System.out.println("--- " + failed + " failed");
        return failed;
    }

    private static KeyPair x25519() throws Exception {
        return java.security.KeyPairGenerator.getInstance("X25519").generateKeyPair();
    }

    private static byte[] agree(KeyPair own, KeyPair theirs) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("EC".equals(own.getPrivate().getAlgorithm()) ? "ECDH" : "XDH");
        agreement.init(own.getPrivate());
        agreement.doPhase(theirs.getPublic(), true);
        return agreement.generateSecret();
    }

    private void check(String name, Check check) {
        try {
            if (check.holds()) {
                System.out.println("  ok    " + name);
                return;
            }
            System.out.println("  FAIL  " + name);
        } catch (Exception | LinkageError e) {
            System.out.println("  FAIL  " + name + ": " + e);
        }
        failed++;
    }
}
