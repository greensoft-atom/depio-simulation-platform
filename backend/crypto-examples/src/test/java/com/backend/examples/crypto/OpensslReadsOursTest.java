package com.backend.examples.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The other direction from the files openssl made: openssl reads and checks what the examples
 * write. Skipped where the openssl command is not installed.
 */
class OpensslReadsOursTest {

    @TempDir
    Path dir;

    @BeforeAll
    static void needsOpenssl() {
        boolean present;
        try {
            present = new ProcessBuilder("openssl", "version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException absent) {
            present = false;
        }
        assumeTrue(present, "the openssl command is not installed");
    }

    @Test
    @DisplayName("openssl verifies our CA's certificate, our request's signature, and reads our key store and encrypted key")
    void certificatesAndStores() throws Exception {
        CertificateAuthority ca = CertificateAuthority.create("backend example root", Signatures.rsa(2048), Duration.ofDays(3650));
        KeyPair keys = Signatures.ecP256();
        PKCS10CertificationRequest request = CertificateAuthority.request(keys, "db.internal", List.of("db.internal"));
        X509Certificate leaf = ca.issue(request, Duration.ofDays(90));
        write("ca.pem", AnyPem.write(ca.certificate()));
        write("leaf.pem", AnyPem.write(leaf));
        write("request.pem", AnyPem.write(request));
        write("key.pem", AnyPem.encrypted(keys.getPrivate(), "a long passphrase".toCharArray()));
        Files.write(dir.resolve("store.p12"),
                KeyStores.pkcs12("server", keys.getPrivate(), List.of(leaf, ca.certificate()), "store-password".toCharArray()));

        assertThat(openssl("verify", "-CAfile", "ca.pem", "leaf.pem")).contains("leaf.pem: OK");
        assertThat(openssl("x509", "-in", "leaf.pem", "-noout", "-ext", "subjectAltName")).contains("DNS:db.internal");
        assertThat(openssl("req", "-in", "request.pem", "-verify", "-noout")).containsIgnoringCase("verify OK");
        openssl("pkey", "-in", "key.pem", "-passin", "pass:a long passphrase", "-noout");
        openssl("pkcs12", "-in", "store.p12", "-passin", "pass:store-password", "-noout");
    }

    @Test
    @DisplayName("openssl verifies our RSASSA-PSS, ECDSA and Ed25519 signatures, and decrypts our RSA-OAEP")
    void signaturesAndEncryption() throws Exception {
        byte[] message = "signed here, verified by openssl".getBytes(UTF_8);
        Files.write(dir.resolve("message.txt"), message);

        KeyPair ec = Signatures.ecP256();
        write("ec.pem", Pem.publicKey(ec.getPublic()));
        Files.write(dir.resolve("ec.sig"), Signatures.sign(Signatures.ECDSA_SHA256, ec.getPrivate(), message));
        assertThat(openssl("dgst", "-sha256", "-verify", "ec.pem", "-signature", "ec.sig", "message.txt")).contains("Verified OK");

        KeyPair ed = Signatures.ed25519();
        write("ed.pem", Pem.publicKey(ed.getPublic()));
        Files.write(dir.resolve("ed.sig"), Signatures.sign(Signatures.ED25519, ed.getPrivate(), message));
        assertThat(openssl("pkeyutl", "-verify", "-pubin", "-inkey", "ed.pem", "-rawin", "-in", "message.txt", "-sigfile", "ed.sig"))
                .contains("Signature Verified Successfully");

        KeyPair rsa = Signatures.rsa(2048);
        write("rsa-public.pem", Pem.publicKey(rsa.getPublic()));
        Files.write(dir.resolve("pss.sig"), Signatures.sign(Signatures.RSA_PSS, rsa.getPrivate(), message));
        assertThat(openssl("dgst", "-sha256", "-sigopt", "rsa_padding_mode:pss", "-sigopt", "rsa_pss_saltlen:digest",
                "-verify", "rsa-public.pem", "-signature", "pss.sig", "message.txt")).contains("Verified OK");

        write("rsa.pem", Pem.privateKey(rsa.getPrivate()));
        Files.write(dir.resolve("secret.bin"), RsaEncryption.encrypt(rsa.getPublic(), "for openssl".getBytes(UTF_8)));
        assertThat(openssl("pkeyutl", "-decrypt", "-inkey", "rsa.pem", "-pkeyopt", "rsa_padding_mode:oaep",
                "-pkeyopt", "rsa_oaep_md:sha256", "-in", "secret.bin")).isEqualTo("for openssl");
    }

    private void write(String name, String text) throws IOException {
        Files.writeString(dir.resolve(name), text);
    }

    /** Its output, after checking it exited 0. */
    private String openssl(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("openssl"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("openssl %s: %s", String.join(" ", arguments), output).isZero();
        return output;
    }
}
