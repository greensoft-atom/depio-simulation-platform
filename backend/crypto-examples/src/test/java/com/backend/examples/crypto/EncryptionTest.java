package com.backend.examples.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.PrivateKey;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKey;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EncryptionTest {

    private static final byte[] ROW_7 = "accounts.id=7".getBytes(UTF_8);

    private static SecretKey keyFor(String algorithm) throws Exception {
        return Aead.AES_GCM.equals(algorithm) ? Aead.newAesKey() : Aead.newChaChaKey();
    }

    @ParameterizedTest
    @ValueSource(strings = {Aead.AES_GCM, Aead.CHACHA20_POLY1305})
    @DisplayName("AEAD: what is encrypted decrypts, 28 bytes longer, and differently each time")
    void aeadRoundTrip(String algorithm) throws Exception {
        SecretKey key = keyFor(algorithm);
        byte[] plaintext = "someone@example.com".getBytes(UTF_8);

        byte[] once = Aead.encrypt(algorithm, key, plaintext, ROW_7);
        byte[] twice = Aead.encrypt(algorithm, key, plaintext, ROW_7);

        assertThat(once).hasSize(plaintext.length + 28);
        assertThat(once).isNotEqualTo(twice);   // a fresh nonce each time
        assertThat(Aead.decrypt(algorithm, key, once, ROW_7)).isEqualTo(plaintext);
        assertThat(Aead.decrypt(algorithm, key, twice, ROW_7)).isEqualTo(plaintext);
        assertThat(Aead.decrypt(algorithm, key, Aead.encrypt(algorithm, key, new byte[0], null), null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {Aead.AES_GCM, Aead.CHACHA20_POLY1305})
    @DisplayName("AEAD: a changed byte anywhere, another key or another row's associated data fails to decrypt")
    void aeadRefusesChanges(String algorithm) throws Exception {
        SecretKey key = keyFor(algorithm);
        byte[] sealed = Aead.encrypt(algorithm, key, "someone@example.com".getBytes(UTF_8), ROW_7);

        for (int i = 0; i < sealed.length; i++) {
            byte[] changed = sealed.clone();
            changed[i] ^= 0x01;
            assertThatThrownBy(() -> Aead.decrypt(algorithm, key, changed, ROW_7)).isInstanceOf(AEADBadTagException.class);
        }
        assertThatThrownBy(() -> Aead.decrypt(algorithm, keyFor(algorithm), sealed, ROW_7)).isInstanceOf(AEADBadTagException.class);
        assertThatThrownBy(() -> Aead.decrypt(algorithm, key, sealed, "accounts.id=8".getBytes(UTF_8)))
                .isInstanceOf(AEADBadTagException.class);
        assertThatThrownBy(() -> Aead.decrypt(algorithm, key, sealed, null)).isInstanceOf(AEADBadTagException.class);
        assertThatThrownBy(() -> Aead.decrypt(algorithm, key, new byte[27], ROW_7)).isInstanceOf(AEADBadTagException.class);
    }

    @Test
    @DisplayName("RSA-OAEP: a few bytes round-trip, and seal/open carries a megabyte")
    void rsa() throws Exception {
        KeyPair rsa = Signatures.rsa(2048);
        byte[] secret = new byte[190];   // the most a 2048-bit key takes with SHA-256
        assertThat(RsaEncryption.decrypt(rsa.getPrivate(), RsaEncryption.encrypt(rsa.getPublic(), secret))).isEqualTo(secret);
        assertThatThrownBy(() -> RsaEncryption.encrypt(rsa.getPublic(), new byte[191])).isInstanceOf(IllegalBlockSizeException.class);

        byte[] large = new byte[1024 * 1024];
        new java.util.Random(2).nextBytes(large);
        byte[] sealed = RsaEncryption.seal(rsa.getPublic(), large);
        assertThat(RsaEncryption.open(rsa.getPrivate(), sealed)).isEqualTo(large);

        sealed[sealed.length - 1] ^= 1;
        assertThatThrownBy(() -> RsaEncryption.open(rsa.getPrivate(), sealed)).isInstanceOf(AEADBadTagException.class);
        byte[] other = RsaEncryption.seal(rsa.getPublic(), large);
        assertThatThrownBy(() -> RsaEncryption.open(Signatures.rsa(2048).getPrivate(), other)).isInstanceOf(BadPaddingException.class);
    }

    @Test
    @DisplayName("RSA-OAEP: openssl's ciphertext (SHA-256, MGF1 SHA-256) decrypts, and Java's default for that name does not")
    void rsaOaepAgainstOpenssl() throws Exception {
        PrivateKey key = Pem.readPrivateKey(OpensslFiles.text("rsa-pkcs8.pem"), "RSA");
        byte[] fromOpenssl = OpensslFiles.base64("rsa-oaep-sha256.b64");

        assertThat(new String(RsaEncryption.decrypt(key, fromOpenssl), UTF_8)).isEqualTo(OpensslFiles.OAEP_PLAINTEXT);

        // The trap RsaEncryption names both digests for: this name alone keeps MGF1 on SHA-1.
        Cipher javaDefault = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        javaDefault.init(Cipher.DECRYPT_MODE, key);
        assertThatThrownBy(() -> javaDefault.doFinal(fromOpenssl)).isInstanceOf(BadPaddingException.class);
    }

    @Test
    @DisplayName("key agreement: both sides reach the same secret, X25519 and P-256, and HKDF gives RFC 5869's keys")
    void agreement() throws Exception {
        for (KeyPair[] pair : new KeyPair[][] {
                {SharedSecrets.x25519(), SharedSecrets.x25519()}, {Signatures.ecP256(), Signatures.ecP256()}}) {
            byte[] mine = SharedSecrets.agree(pair[0].getPrivate(), pair[1].getPublic());
            byte[] theirs = SharedSecrets.agree(pair[1].getPrivate(), pair[0].getPublic());
            assertThat(mine).isEqualTo(theirs);
            byte[] info = "backend example: one purpose".getBytes(UTF_8);
            assertThat(SharedSecrets.hkdf(mine, null, info, 32)).isEqualTo(SharedSecrets.hkdf(theirs, null, info, 32))
                    .isNotEqualTo(SharedSecrets.hkdf(mine, null, "another purpose".getBytes(UTF_8), 32));
        }

        // RFC 5869, test case 1.
        java.util.HexFormat hex = java.util.HexFormat.of();
        byte[] okm = SharedSecrets.hkdf(hex.parseHex("0b".repeat(22)), hex.parseHex("000102030405060708090a0b0c"),
                hex.parseHex("f0f1f2f3f4f5f6f7f8f9"), 42);
        assertThat(hex.formatHex(okm))
                .isEqualTo("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
    }

    @Test
    @DisplayName("KEM: sealed to a public key, it opens with its private key, only in the same context")
    void sealToPublicKey() throws Exception {
        KeyPair recipient = SharedSecrets.x25519();
        byte[] context = "backup key, 2026-10".getBytes(UTF_8);
        byte[] plaintext = "the backup's key".getBytes(UTF_8);

        byte[] sealed = SharedSecrets.sealTo(recipient.getPublic(), plaintext, context);
        assertThat(SharedSecrets.openWith(recipient.getPrivate(), sealed, context)).isEqualTo(plaintext);
        assertThat(SharedSecrets.sealTo(recipient.getPublic(), plaintext, context)).isNotEqualTo(sealed);

        assertThatThrownBy(() -> SharedSecrets.openWith(recipient.getPrivate(), sealed, "another".getBytes(UTF_8)))
                .isInstanceOf(AEADBadTagException.class);
        assertThatThrownBy(() -> SharedSecrets.openWith(SharedSecrets.x25519().getPrivate(), sealed, context))
                .isInstanceOf(AEADBadTagException.class);
    }
}
