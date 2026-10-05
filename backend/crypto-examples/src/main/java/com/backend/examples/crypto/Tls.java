package com.backend.examples.crypto;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS with Java alone: a server's context from its key store, a client's that trusts only our own
 * root, and a connection that checks the server's name. Where: a service of ours calling another
 * over a network we do not trust; the arena serves its players this way (its {@code ArenaTls}
 * narrows the protocols and suites). For mutual TLS, the client gets a key manager too and the
 * server {@code setNeedClientAuth(true)}.
 */
public final class Tls {

    private Tls() {
    }

    /** A server's: its key and chain from a PKCS#12 store ({@link KeyStores}). */
    public static SSLContext server(KeyStore keys, char[] password) throws GeneralSecurityException {
        KeyManagerFactory managers = KeyManagerFactory.getInstance("PKIX");
        managers.init(keys, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }

    /** A client's that trusts the roots in this store and nothing else: not the system's public CAs. */
    public static SSLContext client(KeyStore trusted) throws GeneralSecurityException {
        TrustManagerFactory managers = TrustManagerFactory.getInstance("PKIX");
        managers.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    /**
     * TLS over a connected socket, to the server named {@code host}: the name goes in the
     * handshake (SNI) and must be one the certificate names. The trust managers check only that the
     * chain leads to a trusted root; without {@code setEndpointIdentificationAlgorithm("HTTPS")} any
     * certificate from that root would be accepted for any host.
     */
    public static SSLSocket connect(SSLContext client, Socket connected, String host) throws IOException {
        SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket(connected, host, connected.getPort(), true);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setServerNames(List.of(new SNIHostName(host)));
        socket.setSSLParameters(parameters);
        socket.startHandshake();
        return socket;
    }
}
