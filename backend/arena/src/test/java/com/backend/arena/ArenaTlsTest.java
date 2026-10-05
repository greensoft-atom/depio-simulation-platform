package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.backend.common.RefusedConfiguration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArenaTlsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("a valid keystore loads, and says when its certificate expires")
    void loads() throws Exception {
        Path ks = keypair("good.p12", null, 30);

        ArenaTls tls = ArenaTls.fromKeystore(ks, "pass-word".toCharArray());

        assertThat(tls.context().isServer()).isTrue();
        assertThat(tls.expires()).isBetween(Instant.now().plus(Duration.ofDays(29)),
                Instant.now().plus(Duration.ofDays(31)));
    }

    @Test
    @DisplayName("an expired certificate, or one not valid yet, is refused rather than served")
    void refusesCertificatesOutsideTheirValidity() throws Exception {
        Path expired = keypair("expired.p12", "-3d", 1);
        assertThatThrownBy(() -> ArenaTls.fromKeystore(expired, "pass-word".toCharArray()))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("valid from");

        Path early = keypair("early.p12", "+2d", 30);
        assertThatThrownBy(() -> ArenaTls.fromKeystore(early, "pass-word".toCharArray()))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("valid from");
    }

    @Test
    @DisplayName("a trust store given in place of a keystore is refused, not served")
    void refusesAStoreWithoutAKey() throws Exception {
        Path ks = keypair("key.p12", null, 30);
        Path cert = dir.resolve("arena.crt");
        keytool("-exportcert", "-alias", "arena", "-keystore", ks.toString(),
                "-storepass", "pass-word", "-file", cert.toString());
        Path trust = dir.resolve("trust.p12");
        keytool("-importcert", "-noprompt", "-alias", "arena", "-file", cert.toString(),
                "-storetype", "PKCS12", "-keystore", trust.toString(), "-storepass", "pass-word");

        // Without the check this loads, and every handshake fails on "no cipher suites in common".
        assertThatThrownBy(() -> ArenaTls.fromKeystore(trust, "pass-word".toCharArray()))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("no private key");
    }

    @Test
    @DisplayName("the certificate covers exactly the hosts clients would accept it for")
    void coversTheHostsItNames() throws Exception {
        ArenaTls byAddress = ArenaTls.fromKeystore(keypair("ip.p12", null, 30, "SAN=ip:127.0.0.1,ip:::1"),
                "pass-word".toCharArray());
        assertThat(byAddress.covers("127.0.0.1")).isTrue();
        assertThat(byAddress.covers("0:0:0:0:0:0:0:1")).as("the same address, spelled out").isTrue();
        assertThat(byAddress.covers("10.0.0.7")).isFalse();
        assertThat(byAddress.covers("localhost")).as("a name is not its address").isFalse();

        ArenaTls byName = ArenaTls.fromKeystore(keypair("dns.p12", null, 30,
                "SAN=dns:*.arena.example.test,dns:Lobby.Example.Test"), "pass-word".toCharArray());
        assertThat(byName.covers("a1.arena.example.test")).isTrue();
        assertThat(byName.covers("A1.Arena.Example.Test")).as("names ignore case").isTrue();
        assertThat(byName.covers("lobby.example.test")).isTrue();
        assertThat(byName.covers("arena.example.test")).as("a wildcard needs its label").isFalse();
        assertThat(byName.covers("x.a1.arena.example.test")).as("and stands for one label only").isFalse();
        assertThat(byName.covers("a1.arena.example.test.evil.test")).isFalse();
        assertThat(byName.covers("")).isFalse();

        ArenaTls noNames = ArenaTls.fromKeystore(keypair("cn.p12", null, 30, null), "pass-word".toCharArray());
        assertThat(noNames.covers("arena-test")).as("the common name is not checked by clients").isFalse();
    }

    @Test
    @DisplayName("a name that comes as bytes is passed over, not cast")
    void namesGivenAsBytesAreSkipped() {
        // An other-name (type 0), which keytool cannot write, as the JDK hands it over.
        List<List<?>> names = List.of(List.of(0, new byte[] {0x30, 0x00}), List.of(2, "a1.arena.example.test"));
        assertThat(ArenaTls.namesCover(names, "a1.arena.example.test")).isTrue();
        assertThat(ArenaTls.namesCover(names, "a2.arena.example.test")).isFalse();
    }

    @Test
    @DisplayName("a wrong password is refused")
    void refusesAWrongPassword() throws Exception {
        Path ks = keypair("good.p12", null, 30);
        assertThatThrownBy(() -> ArenaTls.fromKeystore(ks, "not-it".toCharArray()))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("cannot load");
    }

    @Test
    @DisplayName("an intermediate that expires first is when the chain expires: devices refuse it then")
    void theChainExpiresWithItsFirstCertificate() throws Exception {
        Path ks = signedByIntermediate("chain.p12", null, 2, 30);

        ArenaTls tls = ArenaTls.fromKeystore(ks, "pass-word".toCharArray());

        assertThat(tls.expires()).isBetween(Instant.now().plus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(3)));
    }

    @Test
    @DisplayName("an expired intermediate is refused like an expired certificate")
    void refusesAnExpiredIntermediate() throws Exception {
        Path ks = signedByIntermediate("stale.p12", "-3d", 1, 30);

        assertThatThrownBy(() -> ArenaTls.fromKeystore(ks, "pass-word".toCharArray()))
                .isInstanceOf(RefusedConfiguration.class).hasMessageContaining("valid from")
                .hasMessageContaining("intermediate");
    }

    /**
     * A keystore as a public CA's certificate makes it: one key, whose chain is the leaf and
     * the intermediate that signed it, and no other entry.
     */
    private Path signedByIntermediate(String name, String caStart, int caDays, int leafDays) throws Exception {
        Path ks = dir.resolve(name);
        String store = ks.toString();
        List<String> ca = new ArrayList<>(List.of("-genkeypair", "-alias", "ca", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=test intermediate", "-ext", "bc:c",
                "-validity", Integer.toString(caDays), "-keystore", store, "-storetype", "PKCS12",
                "-storepass", "pass-word", "-keypass", "pass-word"));
        if (caStart != null) {
            ca.addAll(List.of("-startdate", caStart));
        }
        keytool(ca.toArray(String[]::new));
        keypairInto(store, leafDays);
        Path request = dir.resolve(name + ".csr");
        Path signed = dir.resolve(name + ".crt");
        keytool("-certreq", "-alias", "arena", "-keystore", store, "-storepass", "pass-word",
                "-file", request.toString());
        keytool("-gencert", "-alias", "ca", "-keystore", store, "-storepass", "pass-word",
                "-infile", request.toString(), "-outfile", signed.toString(),
                "-validity", Integer.toString(leafDays), "-ext", "SAN=ip:127.0.0.1", "-rfc");
        keytool("-importcert", "-noprompt", "-alias", "arena", "-file", signed.toString(),
                "-keystore", store, "-storepass", "pass-word");
        keytool("-delete", "-alias", "ca", "-keystore", store, "-storepass", "pass-word");
        return ks;
    }

    private void keypairInto(String store, int validityDays) throws Exception {
        keytool("-genkeypair", "-alias", "arena", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=arena-test", "-validity", Integer.toString(validityDays),
                "-keystore", store, "-storetype", "PKCS12",
                "-storepass", "pass-word", "-keypass", "pass-word");
    }

    private Path keypair(String name, String startDate, int validityDays) throws Exception {
        return keypair(name, startDate, validityDays, "SAN=ip:127.0.0.1");
    }

    private Path keypair(String name, String startDate, int validityDays, String san) throws Exception {
        Path ks = dir.resolve(name);
        List<String> args = new ArrayList<>(List.of("-genkeypair", "-alias", "arena",
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=arena-test",
                "-validity", Integer.toString(validityDays),
                "-keystore", ks.toString(), "-storetype", "PKCS12",
                "-storepass", "pass-word", "-keypass", "pass-word"));
        if (san != null) {
            args.add("-ext");
            args.add(san);
        }
        if (startDate != null) {
            args.add("-startdate");
            args.add(startDate);
        }
        keytool(args.toArray(String[]::new));
        return ks;
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
