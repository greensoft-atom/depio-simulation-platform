package com.jredis.client;

import com.jredis.common.Reply;
import com.jredis.common.RespReplyParser;
import com.jredis.common.RespWriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Which of several servers to use (docs/16-replication.md §10, D-37): each is asked {@code ROLE};
 * the primary with the highest epoch wins, and none whose epoch is lower than any server this
 * client has seen, a replica included, since a replica's epoch is its primary's. Blocking: run on
 * the client's resolver thread, never on an event loop.
 */
final class PrimaryFinder {

    private static final Logger log = LoggerFactory.getLogger(PrimaryFinder.class);

    private PrimaryFinder() {
    }

    /** What one server said it is. */
    private record Role(boolean primary, long epoch) { }

    /**
     * @param seen the highest epoch this client has seen; raised by what the servers say
     * @return the address to use, or null if no server qualifies now
     */
    static InetSocketAddress find(List<InetSocketAddress> addresses, String password, int timeoutMillis, AtomicLong seen) {
        InetSocketAddress best = null;
        long bestEpoch = -1;
        long highest = seen.get();
        for (InetSocketAddress a : addresses) {
            Role r;
            try {
                r = ask(a, password, timeoutMillis);
            } catch (IOException | RuntimeException e) {
                log.debug("cannot ask {} for its role: {}", a, e.toString());
                continue;
            }
            highest = Math.max(highest, r.epoch());
            if (r.primary() && r.epoch() > bestEpoch) {
                best = a;
                bestEpoch = r.epoch();
            }
        }
        seen.accumulateAndGet(highest, Math::max);
        if (best == null || bestEpoch < highest) {
            if (best != null) {
                log.warn("{} is a primary at epoch {}, but epoch {} has been seen: not using it", best, bestEpoch, highest);
            }
            return null;
        }
        return best;
    }

    private static Role ask(InetSocketAddress address, String password, int timeoutMillis) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(address.getHostString(), address.getPort()), timeoutMillis);
            s.setSoTimeout(timeoutMillis);
            ByteBuf out = Unpooled.buffer();
            int expected = 1;
            if (password != null && !password.isEmpty()) {
                RespWriter.command(out, words("AUTH", "default", password));
                expected = 2;
            }
            RespWriter.command(out, words("ROLE"));
            OutputStream os = s.getOutputStream();
            os.write(out.array(), out.arrayOffset() + out.readerIndex(), out.readableBytes());
            os.flush();
            InputStream is = s.getInputStream();
            RespReplyParser parser = new RespReplyParser();
            ByteBuf in = Unpooled.buffer();
            byte[] chunk = new byte[4096];
            Reply role = null;
            for (int got = 0; got < expected; ) {
                Reply r = parser.parse(in);
                if (r == null) {
                    int n = is.read(chunk);
                    if (n < 0) {
                        throw new IOException("closed before replying");
                    }
                    in.writeBytes(chunk, 0, n);
                    continue;
                }
                got++;
                if (r.isError()) {
                    throw new IOException(r.asString());
                }
                role = r;
            }
            List<Reply> parts = role.asList();
            boolean primary = parts.get(0).asString().equals("primary");
            long epoch = parts.get(parts.size() - 1).asLong();
            return new Role(primary, epoch);
        }
    }

    private static byte[][] words(String... w) {
        byte[][] a = new byte[w.length][];
        for (int i = 0; i < w.length; i++) {
            a[i] = w[i].getBytes(StandardCharsets.UTF_8);
        }
        return a;
    }
}
