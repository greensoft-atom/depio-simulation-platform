package com.backend.examples.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SignaturesTest {

    private static final byte[] MESSAGE = "release 0.1.0, sha256 3a6f...".getBytes(UTF_8);

    static Stream<Arguments> algorithms() throws Exception {
        KeyPair rsa = Signatures.rsa(3072);
        KeyPair ec = Signatures.ecP256();
        return Stream.of(
                Arguments.of(Signatures.RSA_SHA256, rsa, Signatures.rsa(3072)),
                Arguments.of(Signatures.RSA_PSS, rsa, Signatures.rsa(3072)),
                Arguments.of(Signatures.ECDSA_SHA256, ec, Signatures.ecP256()),
                Arguments.of(Signatures.ECDSA_SHA256_RAW, ec, Signatures.ecP256()),
                Arguments.of(Signatures.ED25519, Signatures.ed25519(), Signatures.ed25519()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("algorithms")
    @DisplayName("a signature verifies with its key, and not for changed data, another key or a damaged signature")
    void signAndVerify(String algorithm, KeyPair keys, KeyPair others) throws Exception {
        byte[] signature = Signatures.sign(algorithm, keys.getPrivate(), MESSAGE);

        assertThat(Signatures.verify(algorithm, keys.getPublic(), MESSAGE, signature)).isTrue();
        assertThat(Signatures.verify(algorithm, keys.getPublic(), "release 0.1.1".getBytes(UTF_8), signature)).isFalse();
        assertThat(Signatures.verify(algorithm, others.getPublic(), MESSAGE, signature)).isFalse();
        byte[] damaged = signature.clone();
        damaged[damaged.length / 2] ^= 0x10;
        assertThat(Signatures.verify(algorithm, keys.getPublic(), MESSAGE, damaged)).isFalse();
        assertThat(Signatures.verify(algorithm, keys.getPublic(), MESSAGE, new byte[3])).isFalse();
        // A stream signs as the same bytes do.
        assertThat(Signatures.verify(algorithm, keys.getPublic(), MESSAGE,
                Signatures.sign(algorithm, keys.getPrivate(), new ByteArrayInputStream(MESSAGE)))).isTrue();
    }

    @Test
    @DisplayName("ECDSA's two encodings: P1363 is 64 bytes, and neither verifies as the other")
    void ecdsaEncodings() throws Exception {
        KeyPair ec = Signatures.ecP256();
        byte[] der = Signatures.sign(Signatures.ECDSA_SHA256, ec.getPrivate(), MESSAGE);
        byte[] raw = Signatures.sign(Signatures.ECDSA_SHA256_RAW, ec.getPrivate(), MESSAGE);

        assertThat(raw).hasSize(64);
        assertThat(der[0]).isEqualTo((byte) 0x30);   // a DER SEQUENCE
        assertThat(Signatures.verify(Signatures.ECDSA_SHA256, ec.getPublic(), MESSAGE, raw)).isFalse();
        assertThat(Signatures.verify(Signatures.ECDSA_SHA256_RAW, ec.getPublic(), MESSAGE, der)).isFalse();
    }

    @Test
    @DisplayName("openssl's RSASSA-PSS, ECDSA P-256 and Ed25519 signatures verify here, with openssl's public keys")
    void opensslSignaturesVerify() throws Exception {
        byte[] message = OpensslFiles.SIGNED_MESSAGE.getBytes(UTF_8);
        PublicKey rsa = Pem.readPublicKey(OpensslFiles.text("rsa-public.pem"), "RSA");
        PublicKey ec = Pem.readPublicKey(OpensslFiles.text("ec-public.pem"), "EC");
        PublicKey ed = Pem.readPublicKey(OpensslFiles.text("ed25519-public.pem"), "Ed25519");

        assertThat(Signatures.verify(Signatures.RSA_PSS, rsa, message, OpensslFiles.base64("rsa-pss-sha256.sig.b64"))).isTrue();
        assertThat(Signatures.verify(Signatures.ECDSA_SHA256, ec, message, OpensslFiles.base64("ec-sha256.sig.b64"))).isTrue();
        assertThat(Signatures.verify(Signatures.ED25519, ed, message, OpensslFiles.base64("ed25519.sig.b64"))).isTrue();
        assertThat(Signatures.verify(Signatures.ED25519, ed, "another message".getBytes(UTF_8), OpensslFiles.base64("ed25519.sig.b64")))
                .isFalse();
    }
}
