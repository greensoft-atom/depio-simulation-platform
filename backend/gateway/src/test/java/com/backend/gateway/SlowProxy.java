package com.backend.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A TCP proxy to the store that holds everything sent to it for a while, in order, and passes
 * the answers back at once: a store whose writes land late, as one far away or busy does.
 */
final class SlowProxy implements AutoCloseable {

    private final ServerSocket server;
    private final List<Socket> open = new CopyOnWriteArrayList<>();

    SlowProxy(int targetPort, long holdMillis) throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    Socket target = new Socket("127.0.0.1", targetPort);
                    open.add(client);
                    open.add(target);
                    held(client, target, holdMillis);
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

    /** What the client sends, passed on {@code holdMillis} after it came, in the order it came. */
    private static void held(Socket from, Socket to, long holdMillis) {
        record Chunk(long due, byte[] bytes) { }
        BlockingQueue<Chunk> queue = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = from.getInputStream()) {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    queue.add(new Chunk(System.nanoTime() + holdMillis * 1_000_000, Arrays.copyOf(buffer, n)));
                }
            } catch (IOException closed) {
                // the proxy or a side closed
            }
        });
        Thread.ofVirtual().start(() -> {
            try (OutputStream out = to.getOutputStream()) {
                while (true) {
                    Chunk chunk = queue.take();
                    long wait = chunk.due() - System.nanoTime();
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    }
                    out.write(chunk.bytes());
                    out.flush();
                }
            } catch (IOException | InterruptedException closed) {
                // the proxy or a side closed
            }
        });
    }

    private static void pipe(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException closed) {
            // the proxy or a side closed
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
