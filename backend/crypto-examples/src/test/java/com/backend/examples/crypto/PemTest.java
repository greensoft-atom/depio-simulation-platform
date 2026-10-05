package com.backend.examples.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PemTest {

    @Test
    @DisplayName("Java alone: public and private keys of each kind round-trip through PEM")
    void roundTrip() throws Exception {
        for (Object[] kind : new Object[][] {
                {"RSA", Signatures.rsa(2048)}, {"EC", Signatures.ecP256()}, {"Ed25519", Signatures.ed25519()}}) {
            String algorithm = (String) kind[0];
            KeyPair keys = (KeyPair) kind[1];
            String publicPem = Pem.publicKey(keys.getPublic());
            String privatePem = Pem.privateKey(keys.getPrivate());

            assertThat(publicPem).startsWith("-----BEGIN PUBLIC KEY-----\n").endsWith("-----END PUBLIC KEY-----\n");
            assertThat(privatePem.lines()).allMatch(line -> line.length() <= 64);
            assertThat(Pem.readPublicKey(publicPem, algorithm)).isEqualTo(keys.getPublic());
            assertThat(Pem.readPrivateKey(privatePem, algorithm).getEncoded()).isEqualTo(keys.getPrivate().getEncoded());
        }
        assertThatThrownBy(() -> Pem.read("nothing here", "PUBLIC KEY")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Java alone reads openssl's PUBLIC KEY and PRIVATE KEY, and they are one pair")
    void readsOpensslsStandardForms() throws Exception {
        PrivateKey key = Pem.readPrivateKey(OpensslFiles.text("rsa-pkcs8.pem"), "RSA");
        PublicKey pub = Pem.readPublicKey(OpensslFiles.text("rsa-public.pem"), "RSA");
        byte[] m = "pair".getBytes(UTF_8);

        assertThat(Signatures.verify(Signatures.RSA_SHA256, pub, m, Signatures.sign(Signatures.RSA_SHA256, key, m))).isTrue();
    }

    @Test
    @DisplayName("BouncyCastle reads every form openssl writes: PKCS#8, PKCS#1, encrypted PKCS#8 and SEC1")
    void readsEveryOpensslForm() throws Exception {
        PrivateKey pkcs8 = AnyPem.privateKey(OpensslFiles.text("rsa-pkcs8.pem"), null);
        PrivateKey pkcs1 = AnyPem.privateKey(OpensslFiles.text("rsa-pkcs1.pem"), null);
        PrivateKey encrypted = AnyPem.privateKey(OpensslFiles.text("rsa-encrypted.pem"), OpensslFiles.KEY_PASSWORD);

        assertThat(pkcs1.getEncoded()).isEqualTo(pkcs8.getEncoded());
        assertThat(encrypted.getEncoded()).isEqualTo(pkcs8.getEncoded());
        assertThatThrownBy(() -> AnyPem.privateKey(OpensslFiles.text("rsa-encrypted.pem"), "wrong".toCharArray()))
                .isInstanceOf(GeneralSecurityException.class);

        // SEC1: the key that made openssl's ECDSA signature, so it signs what openssl's public key verifies.
        PrivateKey sec1 = AnyPem.privateKey(OpensslFiles.text("ec-sec1.pem"), null);
        PublicKey ecPublic = Pem.readPublicKey(OpensslFiles.text("ec-public.pem"), "EC");
        byte[] m = "sec1".getBytes(UTF_8);
        assertThat(Signatures.verify(Signatures.ECDSA_SHA256, ecPublic, m, Signatures.sign(Signatures.ECDSA_SHA256, sec1, m))).isTrue();

        assertThatThrownBy(() -> AnyPem.privateKey(OpensslFiles.text("rsa-public.pem"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an encrypted key written here reads back only with its password")
    void encryptedRoundTrip() throws Exception {
        KeyPair keys = Signatures.ecP256();
        String pem = AnyPem.encrypted(keys.getPrivate(), "a long passphrase".toCharArray());

        assertThat(pem).startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----");
        assertThat(AnyPem.privateKey(pem, "a long passphrase".toCharArray()).getEncoded()).isEqualTo(keys.getPrivate().getEncoded());
        assertThatThrownBy(() -> AnyPem.privateKey(pem, "a long passphrasf".toCharArray())).isInstanceOf(GeneralSecurityException.class);
    }
}
