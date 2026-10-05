package com.backend.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.backend.common.Xorshift;
import com.backend.protocol.ClientMessage;
import com.backend.protocol.Wire;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Headless clients for load testing. It substitutes for the Unity client everywhere except
 * "does it feel right", which is the one thing it cannot answer.
 *
 * <h2>Every bot takes the path a real client takes</h2>
 *
 * Register, log in, open a lobby connection, ask for a match, then connect to whichever
 * arena platform named. An earlier version wrote tickets straight into the store, which
 * meant a load run never touched {@code platform} or {@code gateway} at all and measured a
 * path no client will ever use.
 *
 * The lobby part is the JDK's HTTP and WebSocket client, once per bot. The arena
 * connection is Netty, because that one carries 20 messages a second for the whole match.
 *
 * Usage: BotClient [bots] [seconds] [platformUrl] [lobbyUrl]
 *
 * <p>With {@code BACKEND_BOT_STAYS=<min>:<max>} seconds, a stay ends: the bot leaves as a player
 * does and plays another, closing its lobby connection and opening another every fourth stay,
 * as an app sent to the background does (07 §4's soak). Unset, a bot plays one stay, the run.
 */
public final class BotClient {

    /** As a client sends: ten input packets a second (02 §9); twenty doubled the upstream every load run carried. */
    private static final int INPUT_HZ = 10;
    /** And a Ping every ten seconds, as a client does (02 §1). */
    private static final int PING_EVERY_MS = 10_000;

    /**
     * Up to three Argon2 operations per bot on a fresh database (a refused login's decoy
     * verify, the registration's hash, the login), one on a rerun; the server admits eight at
     * a time.
     */
    private static final int ONBOARD_CONCURRENCY = 16;

    private static final AtomicLong bytesIn = new AtomicLong();
    private static final AtomicLong snapshotsIn = new AtomicLong();
    private static final AtomicInteger welcomes = new AtomicInteger();
    private static final AtomicInteger disconnects = new AtomicInteger();
    private static final AtomicInteger kicks = new AtomicInteger();
    private static final AtomicInteger kickReason = new AtomicInteger();
    private static final AtomicInteger lobbyFailures = new AtomicInteger();
    /** Stays ended on purpose, each joined first: a paid result each (07 §4). */
    private static final AtomicInteger staysEnded = new AtomicInteger();

    /**
     * {@code BACKEND_BOT_PROFILE}: saver, mobile or high, sent after the ticket in Join; unset,
     * nothing is sent and the arena serves mobile, as it does a client built before profiles.
     */
    private static final int PROFILE = switch (System.getenv().getOrDefault("BACKEND_BOT_PROFILE", "")) {
        case "saver" -> Wire.PROFILE_SAVER;
        case "mobile" -> Wire.PROFILE_MOBILE;
        case "high" -> Wire.PROFILE_HIGH;
        default -> -1;
    };

    /** {@code BACKEND_BOT_STAYS=<min>:<max>}: a stay's seconds, drawn for each; null, the run's. */
    private static final int[] STAYS = System.getenv("BACKEND_BOT_STAYS") == null ? null
            : java.util.Arrays.stream(System.getenv("BACKEND_BOT_STAYS").split(":")).mapToInt(Integer::parseInt).toArray();
    /** Every so many stays the bot's lobby connection is closed and another opened. */
    private static final int LOBBY_EVERY = 4;
    /** A client pings its lobby at least this often, or the gateway closes it at two minutes (03 §3). */
    private static final long PING_MILLIS = 30_000;

    private static final String[] VIA = System.getenv("BACKEND_BOT_VIA") == null
            ? null : System.getenv("BACKEND_BOT_VIA").split(":");

    public static void main(String[] args) throws Exception {
        int bots = args.length > 0 ? Integer.parseInt(args[0]) : 50;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        String platformUrl = args.length > 2 ? args[2] : "http://127.0.0.1:8080";
        String lobbyUrl = args.length > 3 ? args[3] : "ws://127.0.0.1:8081/lobby";


        // The whole lobby journey, per bot, before a single packet of match traffic.
        // Registration costs an Argon2 hash each and logging in costs another, so this is
        // the slow part of starting a run and it is reported separately for that reason.
        long lobbyStart = System.nanoTime();
        List<LobbyClient.Grant> grants = java.util.Collections.synchronizedList(new ArrayList<>(bots));
        List<LobbyClient> lobbies = java.util.Collections.synchronizedList(new ArrayList<>(bots));

        // Bounded, not unbounded. Registering and logging in cost an Argon2 hash each and
        // the server admits eight at a time, so a thousand at once would simply queue past
        // the client's own request timeout and report failures the server never caused.
        // Sequentially this measured 657 ms a bot, which is eleven minutes for a thousand.
        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(ONBOARD_CONCURRENCY);
        java.net.http.HttpClient sharedHttp = LobbyClient.newHttpClient();
        try (var onboarding = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < bots; i++) {
                final String name = "bot" + i;
                final int index = i;
                onboarding.submit(() -> {
                    // After the permit, not before: constructing them all up front spawned
                    // sixty HTTP stacks in a burst before anything throttled.
                    permits.acquireUninterruptibly();
                    // Each bot its own address from 198.18.0.0/15, which RFC 2544 reserves for
                    // benchmarking: a real client population, not one address to throttle.
                    LobbyClient lobby = new LobbyClient(platformUrl, lobbyUrl, sharedHttp)
                            .forwardedFor("198.18." + ((index >> 8) & 0xff) + "." + (index & 0xff));
                    try {
                        // Login first: a rerun against the same database would otherwise
                        // pay an Argon2 hash per bot to be told the name is taken.
                        try {
                            lobby.login(name, "hunter2-hunter2");
                        } catch (LobbyClient.LobbyException notYet) {
                            lobby.registerIfNeeded(name, name, "hunter2-hunter2");
                            lobby.login(name, "hunter2-hunter2");
                        }
                        lobby.openLobby();
                        LobbyClient.Grant grant = lobby.requestMatch();
                        synchronized (grants) {           // a bot's grant and lobby at one index
                            grants.add(grant);
                            lobbies.add(lobby);
                        }
                    } catch (Exception e) {
                        lobbyFailures.incrementAndGet();
                        lobby.close();
                        System.err.printf(Locale.ROOT, "bot %d never reached an arena: %s%n",
                                index, e);
                    } finally {
                        permits.release();
                    }
                });
            }
        }
        double lobbySeconds = (System.nanoTime() - lobbyStart) / 1e9;
        if (grants.isEmpty()) {
            System.err.println("no bot got a ticket; is platform running?");
            return;
        }
        String host = grants.get(0).arenaHost();
        int port = grants.get(0).arenaPort();
        System.out.printf(Locale.ROOT,
                "lobby: %d of %d bots registered, logged in and got a ticket in %.1f s (%.0f ms of wall"
                        + " time per bot)%n",
                grants.size(), bots, lobbySeconds, lobbySeconds * 1000 / bots);

        var group = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        // Concurrent: with stays that end, bots leave it and join it while the ticker reads it.
        java.util.Set<Bot> all = java.util.concurrent.ConcurrentHashMap.newKeySet();
        try {
            Bootstrap b = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            Bot bot = new Bot(ch);
                            ch.pipeline()
                                    .addLast(new com.backend.arena.VarintFrameDecoder(8192))
                                    .addLast(bot);
                            ch.attr(BOT).set(bot);
                        }
                    });

            io.netty.handler.ssl.SslContext tls = grants.stream().anyMatch(LobbyClient.Grant::tls) ? botTls() : null;
            List<Bot> first = new ArrayList<>(grants.size());
            for (LobbyClient.Grant grant : grants) {
                Bot bot = connect(b, grant, tls);
                first.add(bot);
                all.add(bot);
            }
            System.out.printf(Locale.ROOT, "%d bots connected to %s:%d%n", all.size(), host, port);

            // One scheduler drives every bot's input, so the harness does not need a
            // thread per bot to generate 10 Hz of traffic.
            var ticker = group.next().scheduleAtFixedRate(() -> {
                for (Bot bot : all) {
                    bot.sendInput();
                }
            }, 50, 1000 / INPUT_HZ, TimeUnit.MILLISECONDS);
            var pinger = group.next().scheduleAtFixedRate(() -> {
                for (Bot bot : all) {
                    bot.sendPing();
                }
            }, PING_EVERY_MS, PING_EVERY_MS, TimeUnit.MILLISECONDS);

            // The server no longer respawns anyone by itself, so a bot that dies stays dead
            // and a long run quietly measures an emptying room. Asking blindly is enough:
            // the request is dropped while the tank is alive.
            var respawner = group.next().scheduleAtFixedRate(() -> {
                for (Bot bot : all) {
                    bot.sendRespawn();
                }
            }, 1_000, 2_000, TimeUnit.MILLISECONDS);

            // Measured from here, once every bot is in: counted from each bot's first frame and
            // divided by the run alone, the rates took in the traffic received while the rest
            // were still connecting, which grows with the round trip to the arena.
            long bytesAtStart = bytesIn.get();
            long snapsAtStart = snapshotsIn.get();
            long windowStart = System.nanoTime();
            List<Thread> stays = new ArrayList<>();
            if (STAYS != null) {
                long until = windowStart + TimeUnit.SECONDS.toNanos(seconds);
                for (int i = 0; i < first.size(); i++) {
                    Bot bot = first.get(i);
                    LobbyClient lobby = lobbies.get(i);
                    stays.add(Thread.ofVirtual().start(() -> playOn(bot, lobby, b, tls, all, until)));
                }
            }
            Thread.sleep(seconds * 1000L);
            for (Thread t : stays) {
                t.join();                    // none joins again once the leaving below has begun
            }
            double window = (System.nanoTime() - windowStart) / 1e9;
            ticker.cancel(false);
            respawner.cancel(false);
            pinger.cancel(false);

            long bytes = bytesIn.get() - bytesAtStart;
            long snaps = snapshotsIn.get() - snapsAtStart;
            System.out.printf(Locale.ROOT,
                    "%nwelcomes %d   disconnects %d   kicks %d (last reason %d)   lobby failures %d%n"
                            + "over %.1f s with every bot in: snapshots %d   bytes %d%n",
                    welcomes.get(), disconnects.get(), kicks.get(), kickReason.get(),
                    lobbyFailures.get(), window, snaps, bytes);
            if (snaps > 0) {
                System.out.printf(Locale.ROOT,
                        "mean frame %.1f B   per bot %.2f snapshots/s, %.2f KB/s (payload only)%n",
                        bytes / (double) snaps,
                        snaps / (double) all.size() / window,
                        bytes / (double) all.size() / window / 1024.0);
            }
            // Leaving on purpose, as a player quitting a match does: a connection merely
            // dropped keeps its stay waiting a minute for a resume (02 §10), and its result
            // with it.
            List<io.netty.channel.ChannelFuture> left = new ArrayList<>();
            for (Bot bot : all) {
                left.add(bot.sendLeave());
            }
            for (io.netty.channel.ChannelFuture f : left) {
                f.awaitUninterruptibly(2, TimeUnit.SECONDS);
            }
            // Closed by the arena first, which it does on reading the Leave: a socket closed with
            // frames unread is reset, and the arena, writing, can meet the reset before it reads
            // the Leave, and keep the stay a minute for a resume (P-34).
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            for (Bot bot : all) {
                bot.channel.closeFuture().awaitUninterruptibly(
                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())));
                if (bot.welcomed) {
                    staysEnded.incrementAndGet();
                }
            }
            System.out.printf(Locale.ROOT, "stays %d, each joined and left on purpose%n", staysEnded.get());
        } finally {
            group.shutdownGracefully().await(5, TimeUnit.SECONDS);
            for (LobbyClient lobby : lobbies) {
                lobby.close();
            }
        }
    }

    /** Dials the grant's arena and joins with its ticket. */
    private static Bot connect(Bootstrap b, LobbyClient.Grant grant, io.netty.handler.ssl.SslContext tls)
            throws InterruptedException {
        // BACKEND_BOT_VIA=host:port dials there instead, such as a proxy that shapes the
        // link; the certificate is still checked against the host the grant names.
        Channel ch = VIA == null
                ? b.connect(grant.arenaHost(), grant.arenaPort()).sync().channel()
                : b.connect(VIA[0], Integer.parseInt(VIA[1])).sync().channel();
        if (grant.tls()) {
            // Added once connected and before the first byte: the handler starts
            // the handshake as it is added, and holds the join below until it is done.
            ch.pipeline().addFirst(handshake(tls, ch, grant.arenaHost(), grant.arenaPort()));
        }
        Bot bot = ch.attr(BOT).get();
        bot.sendJoin(grant.ticketId());
        return bot;
    }

    /**
     * One bot's stays, one after another (07 §4's soak): each of a length drawn from
     * {@code BACKEND_BOT_STAYS}, left as a player leaves, and none begun that would outlast the
     * run, whose end leaves the last.
     */
    private static void playOn(Bot bot, LobbyClient lobby, Bootstrap b, io.netty.handler.ssl.SslContext tls,
                               java.util.Set<Bot> all, long until) {
        Xorshift rng = new Xorshift(System.nanoTime());
        try {
            for (int stay = 1; ; stay++) {
                long millis = (STAYS[0] + rng.nextInt(STAYS[1] - STAYS[0] + 1)) * 1000L;
                if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis) >= until) {
                    return;
                }
                for (long slept = 0; slept < millis; slept += PING_MILLIS) {
                    Thread.sleep(Math.min(PING_MILLIS, millis - slept));
                    lobby.ping();
                }
                all.remove(bot);
                bot.sendLeave().awaitUninterruptibly(2, TimeUnit.SECONDS);
                // Closed by the arena on reading the Leave, as at the run's end (P-34).
                bot.channel.closeFuture().awaitUninterruptibly(2, TimeUnit.SECONDS);
                if (bot.welcomed) {
                    staysEnded.incrementAndGet();
                }
                if (stay % LOBBY_EVERY == 0) {
                    lobby.close();
                    lobby.openLobby();
                }
                bot = connect(b, lobby.requestMatch(), tls);
                all.add(bot);
            }
        } catch (Exception e) {
            lobbyFailures.incrementAndGet();
            System.err.printf(Locale.ROOT, "a bot stopped playing on: %s%n", e);
        }
    }

    private static final io.netty.util.AttributeKey<Bot> BOT =
            io.netty.util.AttributeKey.valueOf("bot");

    /** One simulated player: wanders, fires, and acknowledges what it receives. */
    private static final class Bot extends SimpleChannelInboundHandler<ByteBuf> {

        private final Channel channel;
        private final Xorshift rng = new Xorshift(System.nanoTime());
        private volatile boolean welcomed;
        private int seq;
        private int serverTick;
        private int aim;
        private int move;

        Bot(Channel channel) {
            this.channel = channel;
        }

        void sendJoin(String ticketId) {
            byte[] ticket = ticketId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            ByteBuf out = channel.alloc().buffer(64);
            ByteBuf body = channel.alloc().buffer(48);
            body.writeByte(ClientMessage.JOIN);
            body.writeByte(Wire.VERSION);
            writeVarint(body, ticket.length);
            body.writeBytes(ticket);
            if (PROFILE >= 0) {
                body.writeByte(PROFILE);          // the most this bot wants sent (02 §8)
            }
            frame(out, body);
            body.release();
            channel.writeAndFlush(out);
        }

        io.netty.channel.ChannelFuture sendLeave() {
            ByteBuf out = channel.alloc().buffer(8);
            ByteBuf body = channel.alloc().buffer(4);
            body.writeByte(ClientMessage.LEAVE);
            frame(out, body);
            body.release();
            return channel.writeAndFlush(out);
        }

        /** A Ping: its type and the client's time, a big-endian u32 the Pong echoes (02 §2). */
        void sendPing() {
            if (!channel.isActive() || !channel.isWritable()) {
                return;
            }
            ByteBuf out = channel.alloc().buffer(12);
            ByteBuf body = channel.alloc().buffer(8);
            body.writeByte(ClientMessage.PING);
            body.writeInt((int) System.currentTimeMillis());
            frame(out, body);
            body.release();
            channel.writeAndFlush(out);
        }

        void sendRespawn() {
            if (!channel.isActive() || !channel.isWritable()) {
                return;
            }
            ByteBuf out = channel.alloc().buffer(8);
            ByteBuf body = channel.alloc().buffer(4);
            body.writeByte(ClientMessage.RESPAWN);
            frame(out, body);
            body.release();
            channel.writeAndFlush(out);
        }

        void sendInput() {
            if (!channel.isActive() || !channel.isWritable()) {
                return;
            }
            if ((seq & 7) == 0) {                 // change heading now and then
                aim = rng.nextInt(65_536);
                move = 1 << rng.nextInt(4);
            }
            ByteBuf out = channel.alloc().buffer(32);
            ByteBuf body = channel.alloc().buffer(24);
            body.writeByte(ClientMessage.INPUT);
            writeVarint(body, ++seq);
            writeVarint(body, serverTick);        // the ack rides on the input
            body.writeByte(move);
            body.writeShortLE(aim);
            body.writeByte(ClientMessage.FLAG_AUTOFIRE);
            frame(out, body);
            body.release();
            channel.writeAndFlush(out);
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            bytesIn.addAndGet(frame.readableBytes());
            int type = frame.readUnsignedByte();
            if (type == Wire.MSG_WELCOME) {
                welcomed = true;
                welcomes.incrementAndGet();
                return;
            }
            if (type == Wire.MSG_KICK) {
                kicks.incrementAndGet();
                kickReason.set(frame.readUnsignedByte());
                return;
            }
            if (type != Wire.MSG_SNAPSHOT) {
                return;
            }
            snapshotsIn.incrementAndGet();
            serverTick += (int) readVarint(frame);   // tickDelta, so the ack is absolute
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            disconnects.incrementAndGet();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }

        private static void frame(ByteBuf out, ByteBuf body) {
            writeVarint(out, body.readableBytes());
            out.writeBytes(body);
        }

        private static void writeVarint(ByteBuf b, int v) {
            while ((v & ~0x7F) != 0) {
                b.writeByte((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            b.writeByte(v);
        }

        private static long readVarint(ByteBuf in) {
            long v = 0;
            int shift = 0;
            while (true) {
                int b = in.readUnsignedByte();
                v |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return v;
                }
                shift += 7;
            }
        }
    }

    private BotClient() {
    }

    /**
     * TLS for the bots. With {@code BACKEND_BOT_TRUSTSTORE} (a PKCS#12 file, password in
     * {@code BACKEND_BOT_TRUSTSTORE_PASSWORD}) they verify the certificate and the host name as
     * a real client must. Without it they trust any certificate, and say so: acceptable for a
     * load tool against a development arena, and nothing a real client may copy.
     */
    private static io.netty.handler.ssl.SslContext botTls() throws Exception {
        var builder = io.netty.handler.ssl.SslContextBuilder.forClient()
                .sslProvider(io.netty.handler.ssl.SslProvider.JDK);
        String truststore = System.getenv("BACKEND_BOT_TRUSTSTORE");
        if (truststore == null || truststore.isBlank()) {
            System.err.println("WARNING: no BACKEND_BOT_TRUSTSTORE: the bots trust any arena certificate");
            builder.trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE);
        } else {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            String password = System.getenv().getOrDefault("BACKEND_BOT_TRUSTSTORE_PASSWORD", "");
            try (var in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(truststore))) {
                ks.load(in, password.toCharArray());
            }
            var tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            builder.trustManager(tmf);
        }
        return builder.build();
    }

    private static io.netty.handler.ssl.SslHandler handshake(io.netty.handler.ssl.SslContext tls,
                                                             Channel ch, String host, int port) {
        io.netty.handler.ssl.SslHandler h = tls.newHandler(ch.alloc(), host, port);
        // The same test as botTls: a blank setting trusts any certificate there, and checking
        // the host name of a certificate nothing vouched for is only half a check.
        String truststore = System.getenv("BACKEND_BOT_TRUSTSTORE");
        if (truststore != null && !truststore.isBlank()) {
            javax.net.ssl.SSLParameters p = h.engine().getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            h.engine().setSSLParameters(p);
        }
        return h;
    }
}
