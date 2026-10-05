package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The certificate nginx shows clients, watched through the gateway's metrics: nginx exports
 * nothing, and an expired certificate there stops every login and purchase while every process
 * reports itself healthy.
 */
class EdgeCertificateTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a full chain expires with its first certificate to expire, the intermediate included")
    void theEarliestInTheFileCounts() throws Exception {
        Path chain = dir.resolve("fullchain.pem");
        Files.writeString(chain, pem("leaf", 90) + pem("intermediate", 3));

        double expiry = EdgeCertificate.expiry(chain);

        assertThat(Instant.ofEpochSecond((long) expiry)).isBetween(
                Instant.now().plus(Duration.ofDays(2)), Instant.now().plus(Duration.ofDays(4)));
    }

    @Test
    @DisplayName("a renewal shows at the next scrape, with no restart")
    void readsTheFileEachTime() throws Exception {
        Path chain = dir.resolve("fullchain.pem");
        Files.writeString(chain, pem("old", 5));
        double before = EdgeCertificate.expiry(chain);

        Files.writeString(chain, pem("renewed", 90));

        assertThat(EdgeCertificate.expiry(chain) - before).isGreaterThan(Duration.ofDays(80).toSeconds());
    }

    @Test
    @DisplayName("a file that cannot be read reads as long expired, so every expiry alert fires")
    void unreadableIsExpired() throws Exception {
        assertThat(EdgeCertificate.expiry(dir.resolve("absent.pem"))).isZero();
        Path garbage = dir.resolve("garbage.pem");
        Files.writeString(garbage, "-----BEGIN CERTIFICATE-----\nbm90IGEgY2VydGlmaWNhdGU=\n-----END CERTIFICATE-----\n");
        assertThat(EdgeCertificate.expiry(garbage)).isZero();
        Path empty = dir.resolve("empty.pem");
        Files.writeString(empty, "");
        assertThat(EdgeCertificate.expiry(empty)).isZero();
    }

    /** A certificate valid for {@code days}, as PEM; self-signed, since only its dates matter. */
    private String pem(String alias, int days) throws Exception {
        Path ks = dir.resolve(alias + ".p12");
        Path out = dir.resolve(alias + ".pem");
        keytool("-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=" + alias, "-validity", Integer.toString(days),
                "-keystore", ks.toString(), "-storetype", "PKCS12", "-storepass", "pass-word");
        keytool("-exportcert", "-rfc", "-alias", alias, "-keystore", ks.toString(),
                "-storepass", "pass-word", "-file", out.toString());
        return Files.readString(out);
    }

    private static void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as("keytool: %s", output).isZero();
    }
}
