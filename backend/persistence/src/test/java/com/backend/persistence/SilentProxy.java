package com.backend.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A TCP proxy to MySQL that can fall silent: its connections stay open and nothing more is
 * passed either way, as when a server's machine vanishes or is fenced off without a reset. One
 * made silent from the start accepts connections and never answers, as a frozen server does,
 * whose kernel still accepts them.
 */
final class SilentProxy implements AutoCloseable {

    private final ServerSocket server;
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private volatile boolean silent;

    SilentProxy(int targetPort, boolean silentFromTheStart) throws IOException {
        this.silent = silentFromTheStart;
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    open.add(client);
                    if (silent) {
                        continue;                       // accepted, as a kernel does, and never answered
                    }
                    Socket target = new Socket("127.0.0.1", targetPort);
                    open.add(target);
                    Thread.ofVirtual().start(() -> pipe(client, target));
                    Thread.ofVirtual().start(() -> pipe(target, client));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    int port() {
        return server.getLocalPort();
    }

    /** From now on nothing is passed, either way; every connection stays open. */
    void silence() {
        silent = true;
    }

    private void pipe(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (!silent) {
                    out.write(buffer, 0, n);
                    out.flush();
                }
            }
        } catch (IOException e) {
            // one side closed: the test is over with this connection
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket s : open) {
            s.close();
        }
    }
}
