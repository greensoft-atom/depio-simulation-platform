package com.backend.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A TCP proxy to MySQL that can fall silent: its connections stay open and nothing more is
 * passed either way, as when a server's machine vanishes or is fenced off without a reset. One
 * made silent from the start accepts connections and never answers, as a frozen server does,
 * whose kernel still accepts them. And one can hold its next connections a while before passing
 * them on, the server's greeting with them, as a starved machine answers a new connection late.
 */
final class SilentProxy implements AutoCloseable {

    private final ServerSocket server;
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private volatile boolean silent;
    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger toHold = new AtomicInteger();
    private volatile long holdMillis;

    SilentProxy(int targetPort, boolean silentFromTheStart) throws IOException {
        this.silent = silentFromTheStart;
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    open.add(client);
                    accepted.incrementAndGet();
                    if (silent) {
                        continue;                       // accepted, as a kernel does, and never answered
                    }
                    long hold = toHold.getAndUpdate(n -> Math.max(0, n - 1)) > 0 ? holdMillis : 0;
                    Thread.ofVirtual().start(() -> pass(client, targetPort, hold));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    int port() {
        return server.getLocalPort();
    }

    /** Connections accepted so far, answered or not. */
    int accepted() {
        return accepted.get();
    }

    /** The next {@code connections} are passed on only after {@code millis}: the server's greeting that late. */
    void holdNext(int connections, long millis) {
        holdMillis = millis;
        toHold.set(connections);
    }

    /** From now on nothing is passed, either way; every connection stays open. */
    void silence() {
        silent = true;
    }

    private void pass(Socket client, int targetPort, long holdMillis) {
        try {
            Thread.sleep(holdMillis);
            Socket target = new Socket("127.0.0.1", targetPort);
            open.add(target);
            Thread.ofVirtual().start(() -> pipe(client, target));
            pipe(target, client);
        } catch (IOException | InterruptedException e) {
            // closed, or the target refused: the client sees its connection end
        }
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
