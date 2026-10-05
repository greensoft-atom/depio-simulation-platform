package com.jredis.tests;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A TCP link to a server that can fall silent: from then on it passes no byte either way and
 * closes nothing, as a machine that has vanished does. Nothing reaches the kernel's reset, so a
 * client learns of it only by waiting for an answer.
 */
final class SilentLink implements AutoCloseable {

    private final ServerSocket listener;
    private final int target;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile boolean silent;

    private SilentLink(int target) throws IOException {
        this.target = target;
        this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofPlatform().daemon().name("silent-link-" + listener.getLocalPort()).start(this::accept);
    }

    static SilentLink to(int port) throws IOException {
        return new SilentLink(port);
    }

    int port() {
        return listener.getLocalPort();
    }

    /** No byte passes from now on, and every connection stays open. */
    void silence() {
        silent = true;
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket in = listener.accept();
                sockets.add(in);
                if (silent) {
                    pump(in, null);                     // held open, answered never
                    continue;
                }
                Socket out = new Socket(InetAddress.getLoopbackAddress(), target);
                sockets.add(out);
                pump(in, out);
                pump(out, in);
            } catch (IOException e) {
                return;                                 // closed
            }
        }
    }

    private void pump(Socket from, Socket to) {
        Thread.ofPlatform().daemon().start(() -> {
            byte[] buf = new byte[8192];
            try (InputStream in = from.getInputStream()) {
                OutputStream out = to == null ? null : to.getOutputStream();
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (!silent && out != null) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                }
            } catch (IOException e) {
                // either side closed
            }
        });
    }

    @Override
    public void close() throws IOException {
        listener.close();
        for (Socket s : sockets) {
            s.close();
        }
    }
}
