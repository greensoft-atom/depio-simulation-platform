package com.backend.examples.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeyStoresAndTlsTest {

    private static final char[] PASSWORD = "store-password".toCharArray();

    private static CertificateAuthority ca;
    private static KeyPair serverKeys;
    private static X509Certificate serverCertificate;

    @BeforeAll
    static void issue() throws Exception {
        ca = CertificateAuthority.create("backend example root", Signatures.ecP256(), Duration.ofDays(3650));
        serverKeys = Signatures.ecP256();
        serverCertificate = ca.issue(CertificateAuthority.request(serverKeys, "a.internal", List.of("a.internal")), Duration.ofDays(30));
    }

    @Test
    @DisplayName("a PKCS#12 store gives back its key and chain with its password, and nothing without it")
    void pkcs12() throws Exception {
        byte[] file = KeyStores.pkcs12("server", serverKeys.getPrivate(), List.of(serverCertificate, ca.certificate()), PASSWORD);

        KeyStore store = KeyStores.load(file, PASSWORD);
        assertThat(((PrivateKey) store.getKey("server", PASSWORD)).getEncoded()).isEqualTo(serverKeys.getPrivate().getEncoded());
        assertThat(store.getCertificateChain("server")).containsExactly(serverCertificate, ca.certificate());
        assertThatThrownBy(() -> KeyStores.load(file, "wrong".toCharArray())).isInstanceOf(IOException.class);

        KeyStore trust = KeyStores.trustStore(List.of(ca.certificate()));
        assertThat(trust.isCertificateEntry("root-0")).isTrue();
        assertThat(trust.getCertificate("root-0")).isEqualTo(ca.certificate());
    }

    @Test
    @DisplayName("TLS: a client trusting our root talks to the server by its name")
    void handshake() throws Exception {
        assertThat(talk(ca.certificate(), "a.internal")).isEqualTo("echo: hello");
    }

    @Test
    @DisplayName("TLS: a client trusting another root, or asking for another name, is refused in the handshake")
    void refusals() throws Exception {
        CertificateAuthority other = CertificateAuthority.create("another root", Signatures.ecP256(), Duration.ofDays(3650));
        assertThatThrownBy(() -> talk(other.certificate(), "a.internal")).isInstanceOf(SSLHandshakeException.class);
        assertThatThrownBy(() -> talk(ca.certificate(), "b.internal")).isInstanceOf(SSLHandshakeException.class)
                .hasMessageContaining("b.internal");
    }

    /** One line through a fresh server on loopback, trusting {@code root}, asking for {@code host}. */
    private static String talk(X509Certificate root, String host) throws Exception {
        KeyStore keys = KeyStores.load(KeyStores.pkcs12("server", serverKeys.getPrivate(),
                List.of(serverCertificate, ca.certificate()), PASSWORD), PASSWORD);
        SSLContext serverContext = Tls.server(keys, PASSWORD);
        SSLContext clientContext = Tls.client(KeyStores.trustStore(List.of(root)));
        try (SSLServerSocket listening = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> served = CompletableFuture.runAsync(() -> {
                try (SSLSocket accepted = (SSLSocket) listening.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(accepted.getInputStream(), UTF_8));
                    new PrintWriter(accepted.getOutputStream(), true, UTF_8).println("echo: " + in.readLine());
                } catch (IOException refusedByTheClient) {
                    // the refusal tests end here
                }
            });
            try (SSLSocket socket = Tls.connect(clientContext, new Socket(InetAddress.getLoopbackAddress(), listening.getLocalPort()), host)) {
                new PrintWriter(socket.getOutputStream(), true, UTF_8).println("hello");
                String reply = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8)).readLine();
                assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.3");
                return reply;
            } finally {
                served.get(10, TimeUnit.SECONDS);
            }
        }
    }
}
