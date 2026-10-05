package com.backend.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.backend.handoff.MatchOutcome;

import com.backend.handoff.Ticket;
import com.backend.handoff.TicketStore;
import com.backend.protocol.ClientMessage;
import com.backend.protocol.ClientWorld;
import com.backend.protocol.SnapshotReader;
import com.backend.protocol.Wire;
import com.backend.sim.ClassTable;
import com.backend.sim.PhraseTable;
import com.backend.sim.Stat;
import com.backend.sim.TankStats;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end over a real socket: a client connects, joins, sends input and receives
 * snapshots. Deliberately uses a plain {@link Socket} rather than a Netty client, so the
 * test exercises the wire format rather than a shared codec that could be wrong on both
 * sides at once.
 */
class ArenaServerTest {

    private static JRedisEmbedded store;
    private static TicketStore tickets;
    private static long nextPlayerId = 1;

    @BeforeAll
    static void startStore() {
        // A real store, embedded. The join path is now half ticket claim, so a stubbed
        // store would leave the half that actually rejects people untested.
        store = JRedisEmbedded.start();
        tickets = new TicketStore(store.newClient());
    }

    @AfterAll
    static void stopStore() {
        if (store != null) {
            store.close();
        }
    }

    /** Issues a ticket the way platform will, and hands back its id. */
    private static synchronized String issueTicket(int team) throws Exception {
        Ticket t = Ticket.forPlayer(nextPlayerId++, "bot-" + nextPlayerId, team);
        tickets.issue(t).get(5, TimeUnit.SECONDS);
        return t.id();
    }

    private static ArenaServer newServer(float mapSize, int capacity, int maxPlayers,
                                         int shapes, int maxRooms) {
        return new ArenaServer(tickets, mapSize, capacity, maxPlayers, shapes, 2, maxRooms);
    }

    /** Minimal client: varint-framed messages over a blocking socket. */
    private static final class TestClient implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;
        int serverTick;

        TestClient(int port) throws IOException {
            this(plain(port));
        }

        /**
         * Over TLS, verifying the certificate and that it names the host dialled, as a real
         * client must: trusting any certificate would make the encryption decorative.
         */
        TestClient(int port, javax.net.ssl.SSLContext tls) throws IOException {
            this(secure(port, tls));
        }

        private TestClient(Socket connected) throws IOException {
            socket = connected;
            socket.setSoTimeout(5000);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        private static Socket plain(int port) throws IOException {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            s.setTcpNoDelay(true);
            return s;
        }

        private static Socket secure(int port, javax.net.ssl.SSLContext tls) throws IOException {
            javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) tls.getSocketFactory()
                    .createSocket(plain(port), "127.0.0.1", port, true);
            javax.net.ssl.SSLParameters p = s.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            s.setSSLParameters(p);
            s.startHandshake();
            return s;
        }

        String protocol() {
            return socket instanceof javax.net.ssl.SSLSocket s ? s.getSession().getProtocol() : "none";
        }

        void send(byte[] body) throws IOException {
            ByteArrayOutputStream framed = new ByteArrayOutputStream(body.length + 5);
            int v = body.length;
            while ((v & ~0x7F) != 0) {
                framed.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            framed.write(v);
            framed.write(body);
            out.write(framed.toByteArray());
            out.flush();
        }

        void join(String ticketId) throws IOException {
            join(ticketId, -1);
        }

        /** {@code profile}: the optional byte after the ticket (02 §8), or -1 for none. */
        void join(String ticketId, int profile) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(ClientMessage.JOIN);
            b.write(Wire.VERSION);
            byte[] ticket = ticketId.getBytes(StandardCharsets.UTF_8);
            b.write(ticket.length);              // length < 128, so one varint byte
            b.write(ticket, 0, ticket.length);
            if (profile >= 0) {
                b.write(profile);
            }
            send(b.toByteArray());
        }

        /** In place of a join after a lost connection: the secret from the last Welcome (02 §10). */
        void resume(String secret) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(ClientMessage.RESUME);
            b.write(Wire.VERSION);
            byte[] bytes = secret.getBytes(StandardCharsets.US_ASCII);
            b.write(bytes.length);
            b.write(bytes, 0, bytes.length);
            send(b.toByteArray());
        }

        void simple(int type) throws IOException {
            send(new byte[] {(byte) type});
        }

        void ping(long clientTimeMs) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(ClientMessage.PING);
            b.write((int) (clientTimeMs >>> 24));
            b.write((int) (clientTimeMs >>> 16));
            b.write((int) (clientTimeMs >>> 8));
            b.write((int) clientTimeMs);
            send(b.toByteArray());
        }

        void lifecycle(int state) throws IOException {
            send(new byte[] {(byte) ClientMessage.LIFECYCLE, (byte) state});
        }

        void soTimeout(int millis) throws IOException {
            socket.setSoTimeout(millis);
        }

        /** An Input that only acknowledges {@code ack}: no movement, aim 0, not firing. */
        void acknowledge(int seq, int ack) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(ClientMessage.INPUT);
            writeVarint(b, seq);
            writeVarint(b, ack);
            b.write(0);
            b.write(0);
            b.write(0);
            b.write(0);
            send(b.toByteArray());
        }

        void input(int seq, int move, int aim, int flags) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(ClientMessage.INPUT);
            writeVarint(b, seq);
            writeVarint(b, serverTick);
            b.write(move);
            b.write(aim & 0xFF);
            b.write((aim >>> 8) & 0xFF);
            b.write(flags);
            send(b.toByteArray());
        }

        /** @return the frame payload, without its length prefix. */
        byte[] readFrame() throws IOException {
            int length = 0;
            int shift = 0;
            while (true) {
                int c = in.read();
                if (c < 0) {
                    throw new IOException("closed while reading a frame length");
                }
                length |= (c & 0x7F) << shift;
                if ((c & 0x80) == 0) {
                    break;
                }
                shift += 7;
            }
            byte[] payload = new byte[length];
            in.readFully(payload);
            return payload;
        }

        private static void writeVarint(ByteArrayOutputStream b, int v) {
            while ((v & ~0x7F) != 0) {
                b.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            b.write(v);
        }

        static long readVarint(InputStream s) throws IOException {
            long v = 0;
            int shift = 0;
            while (true) {
                int c = s.read();
                v |= (long) (c & 0x7F) << shift;
                if ((c & 0x80) == 0) {
                    return v;
                }
                shift += 7;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a client joins over TCP and receives welcome then snapshots")
    void joinAndReceiveSnapshots() throws Exception {
        try (ArenaServer server = newServer(3000f, 4096, 50, 200, 4)) {
            int port = server.start(0);

            try (TestClient client = new TestClient(port)) {
                client.join(issueTicket(0));

                byte[] welcome = client.readFrame();
                assertThat(welcome[0] & 0xFF).as("first frame is Welcome").isEqualTo(Wire.MSG_WELCOME);

                int snapshots = 0;
                int nonEmpty = 0;
                for (int i = 0; i < 20; i++) {
                    client.input(i + 1, ClientMessage.MOVE_RIGHT, 0, ClientMessage.FLAG_AUTOFIRE);
                    byte[] frame = client.readFrame();
                    if ((frame[0] & 0xFF) != Wire.MSG_SNAPSHOT) {
                        continue;
                    }
                    snapshots++;
                    // header is type + tickDelta + inputSeqDelta + two svarints
                    var bis = new java.io.ByteArrayInputStream(frame, 1, frame.length - 1);
                    client.serverTick += (int) TestClient.readVarint(bis);
                    if (frame.length > 9) {
                        nonEmpty++;
                    }
                }

                assertThat(snapshots).as("snapshots arrive").isGreaterThan(5);
                assertThat(nonEmpty).as("at least some snapshots carry entities").isPositive();
                assertThat(client.serverTick).as("server tick advances").isPositive();
                assertThat(server.registry().totalPlayers()).isEqualTo(1);
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("several clients share a room and the room survives them leaving")
    void multipleClientsAndDisconnects() throws Exception {
        // A connection lost without a word keeps its player's place until the stay's keep runs
        // out (02 §10); a second here, rather than the minute a phone gets.
        try (ArenaServer server = new ArenaServer(tickets, 3000f, 4096, 50, 200, 2, 4,
                MatchRules.open("arena-test").withResume(25, 25), outcome -> { })) {
            int port = server.start(0);

            TestClient[] clients = new TestClient[8];
            for (int i = 0; i < clients.length; i++) {
                clients[i] = new TestClient(port);
                clients[i].join(issueTicket(i % 2));
                assertThat(clients[i].readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
            }
            for (int round = 0; round < 10; round++) {
                for (TestClient c : clients) {
                    c.input(round + 1, ClientMessage.MOVE_UP, 1000, 0);
                }
            }
            waitUntil(() -> server.registry().totalPlayers() == clients.length);
            assertThat(server.registry().totalPlayers()).isEqualTo(clients.length);

            for (TestClient c : clients) {
                c.close();
            }
            waitUntil(() -> server.registry().totalPlayers() == 0);
            assertThat(server.registry().totalPlayers())
                    .as("a disconnect must free the player slot").isZero();
            for (RoomThread rt : server.registry().rooms()) {
                assertThat(rt.ticks()).isPositive();
                assertThat(rt.overruns()).as("no catastrophic tick overruns").isZero();
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a frame longer than the limit closes the connection instead of allocating")
    void oversizedFrameIsRejected() throws Exception {
        try (ArenaServer server = newServer(3000f, 4096, 50, 50, 4)) {
            int port = server.start(0);
            try (TestClient client = new TestClient(port)) {
                // Claim a 1 MB frame without sending it.
                client.send(new byte[0]);                       // a valid empty frame first
                OutputStream raw = client.socket.getOutputStream();
                raw.write(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x40});   // varint 1048576
                raw.flush();

                assertThatEventuallyClosed(client);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a continuous room never ends a match on its own")
    void continuousRoomPublishesNothingWhileEveryoneStays() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 120, 2, 1,
                MatchRules.open("arena-test"), published::add)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);

                for (int round = 0; round < 40; round++) {
                    c.input(round + 1, ClientMessage.MOVE_UP, round * 977, ClientMessage.FLAG_AUTOFIRE);
                    c.readFrame();
                }
                // A timed room of the same length would have published several results by
                // now. This one has no rounds to end, so the player is simply still playing.
                assertThat(published).as("a continuous room has no clock").isEmpty();
                assertThat(server.registry().totalPlayers()).isEqualTo(1);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("leaving is what ends a session, and it is recorded")
    void leavingPublishesTheSession() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 120, 2, 1,
                MatchRules.open("arena-test"), published::add)) {
            int port = server.start(0);
            TestClient first = new TestClient(port);
            TestClient second = new TestClient(port);
            first.join(issueTicket(0));
            second.join(issueTicket(0));
            assertThat(first.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
            assertThat(second.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
            for (int round = 0; round < 10; round++) {
                first.input(round + 1, ClientMessage.MOVE_UP, 0, ClientMessage.FLAG_AUTOFIRE);
                second.input(round + 1, ClientMessage.MOVE_DOWN, 0, ClientMessage.FLAG_AUTOFIRE);
                first.readFrame();
                second.readFrame();
            }

            first.simple(ClientMessage.LEAVE);    // leaving, not a lost connection (02 §10)
            first.close();
            waitUntil(() -> published.size() == 1);
            assertThat(published).as("one player left, so one session ended").hasSize(1);

            MatchOutcome session = published.get(0);
            assertThat(session.players()).as("a session is one player's, not the room's").hasSize(1);
            assertThat(session.players().get(0).placement())
                    .as("no round, no field, so no placement to invent").isZero();
            assertThat(session.won(session.players().get(0))).isFalse();
            assertThat(session.endedAtMillis()).isGreaterThanOrEqualTo(session.startedAtMillis());

            second.simple(ClientMessage.LEAVE);
            second.close();
            waitUntil(() -> published.size() == 2);
            assertThat(published).hasSize(2);
            assertThat(published.get(1).matchUid())
                    .as("each session is its own record").isNotEqualTo(session.matchUid());
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a planned restart publishes the match of every player still connected")
    void shutdownPublishesEveryConnectedPlayer() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 120, 2, 1,
                MatchRules.open("arena-test"), published::add);
        int port = server.start(0);
        TestClient first = new TestClient(port);
        TestClient second = new TestClient(port);
        try {
            first.join(issueTicket(0));
            second.join(issueTicket(0));
            assertThat(first.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
            assertThat(second.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
            for (int round = 0; round < 10; round++) {
                first.input(round + 1, ClientMessage.MOVE_UP, 0, ClientMessage.FLAG_AUTOFIRE);
                second.input(round + 1, ClientMessage.MOVE_DOWN, 0, ClientMessage.FLAG_AUTOFIRE);
                first.readFrame();
                second.readFrame();
            }
            assertThat(published).as("nobody has left yet").isEmpty();

            // A deploy, not a crash: nobody left, the process was asked to stop. The room
            // threads used to be told to stop and not waited for, and nothing finished the
            // tallies of the players still in them — so every deploy dropped up to ten
            // minutes of progress for everyone online.
            server.close();

            assertThat(published).as("by the time close() returns, not eventually").hasSize(2);
            for (MatchOutcome match : published) {
                assertThat(match.players()).hasSize(1);
                assertThat(match.endedAtMillis()).isGreaterThanOrEqualTo(match.startedAtMillis());
            }
            assertThat(published.get(0).players().get(0).playerId())
                    .isNotEqualTo(published.get(1).players().get(0).playerId());
        } finally {
            first.close();
            second.close();
            server.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a long session is checkpointed without interrupting the player")
    void longSessionsAreFlushed() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        // One second instead of ten minutes, so several checkpoints happen inside a test.
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 120, 2, 1,
                MatchRules.open("arena-test", 25), published::add)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);

                // Bounded by the clock, not by a round count: how many frames arrive in a
                // second is not something this test should be predicting.
                int snapshots = 0;
                int seq = 0;
                long deadline = System.nanoTime() + 20_000_000_000L;
                while (published.size() < 2 && System.nanoTime() < deadline) {
                    c.input(++seq, ClientMessage.MOVE_UP, 0, ClientMessage.FLAG_AUTOFIRE);
                    if ((c.readFrame()[0] & 0xFF) == Wire.MSG_SNAPSHOT) {
                        snapshots++;
                    }
                }

                // Without this a player who never disconnects is paid for nothing, and loses
                // everything if the process dies first.
                assertThat(published).as("long sessions are checkpointed").hasSizeGreaterThanOrEqualTo(2);
                assertThat(published.get(0).matchUid()).isNotEqualTo(published.get(1).matchUid());
                assertThat(snapshots).as("and the player never noticed").isPositive();
                assertThat(server.registry().totalPlayers())
                        .as("still playing after the checkpoint").isEqualTo(1);
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a ping is answered with the time it carried and the server's tick")
    void pingIsAnswered() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 60, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);

                c.ping(0xDEADBEEFL);
                byte[] pong = readUntilType(c, Wire.MSG_PONG);

                long echoed = ((long) (pong[1] & 0xFF) << 24) | ((pong[2] & 0xFF) << 16)
                        | ((pong[3] & 0xFF) << 8) | (pong[4] & 0xFF);
                // Echoed rather than re-stamped: the client measures the round trip against
                // its own clock, and the two clocks are not related.
                assertThat(echoed).isEqualTo(0xDEADBEEFL);
                var tickIn = new java.io.ByteArrayInputStream(pong, 5, pong.length - 5);
                assertThat(TestClient.readVarint(tickIn)).as("and the server's tick").isPositive();
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a class the tank cannot have, or a message not served yet, does not disconnect")
    void unimplementedMessagesAreTolerated() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 60, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);

                c.send(new byte[] {(byte) ClientMessage.CHOOSE_CLASS, 7});
                c.send(new byte[] {(byte) ClientMessage.CHOOSE_CLASS});      // no class at all
                c.send(new byte[] {(byte) ClientMessage.PHRASE, 12});

                // The proof the socket survived: it still answers.
                c.ping(1234);
                assertThat(readUntilType(c, Wire.MSG_PONG)).isNotNull();
                assertThat(server.registry().totalPlayers()).isEqualTo(1);
            }
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("a player levels up by playing, and can spend the point it earned")
    void upgradingAStatReachesTheSimulation() throws Exception {
        // A small map packed with shapes: level 2 costs 4 experience and the cheapest shape
        // is worth 10, so the first thing shot pays for it. Shapes do not heal, so damage
        // from separate bullets accumulates until one of them finishes the job.
        try (ArenaServer server = newServer(800f, 2_048, 20, 150, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);

                TankStats stats = playAndWait(c, server, s -> s.unspentPoints > 0, 60);
                assertThat(stats).as("nobody levelled up in a minute of shooting").isNotNull();
                assertThat(stats.level).isGreaterThan(1);
                assertThat(stats.points[Stat.BULLET_DAMAGE]).isZero();

                c.send(new byte[] {(byte) ClientMessage.UPGRADE_STAT, (byte) Stat.BULLET_DAMAGE});

                TankStats spent = playAndWait(c, server,
                        s -> s.points[Stat.BULLET_DAMAGE] > 0, 20);
                // The whole point of the change: this message used to be logged and dropped.
                assertThat(spent).as("the upgrade never reached the simulation").isNotNull();
                assertThat(spent.value(Stat.BULLET_DAMAGE))
                        .as("and it changed what the tank actually does")
                        .isGreaterThan(server.registry().rooms().get(0).room()
                                .content().stats().valueOf(Stat.BULLET_DAMAGE, spent.level, 0));
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("ChooseClass reaches the simulation once the tank has the level, and the client and its view see it")
    void choosingAClassReachesTheSimulation() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 30, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                RoomThread room = server.registry().rooms().get(0);
                ClientWorld w = new ClientWorld();

                // Asked for at level 1: refused, and nothing else happens.
                c.send(new byte[] {(byte) ClientMessage.CHOOSE_CLASS, (byte) ClassTable.SNIPER});
                for (int i = 0; i < 10; i++) {
                    c.input(i + 1, 0, 0, 0);
                    applyNext(c, w);
                }
                assertThat(w.entity(Wire.SELF_HANDLE).classId).isEqualTo(ClassTable.BASIC);

                // Level 15, given on the room thread, which owns the tank; and the view the
                // player is sent, read there too.
                java.util.concurrent.atomic.AtomicBoolean levelled = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.concurrent.atomic.AtomicReference<Float> seen = new java.util.concurrent.atomic.AtomicReference<>();
                room.tickHook = () -> {
                    Connection player = room.connections().get(0);
                    seen.set(player.view().viewWidth);
                    if (levelled.compareAndSet(false, true)) {
                        TankStats s = room.room().world().tankStats[player.entityId()];
                        s.addXp(room.room().content().levels().xpRequired(15), room.room().content().levels());
                    }
                };
                waitUntil(levelled::get);
                assertThat(seen.get()).as("Basic's view").isEqualTo(1_600f);

                c.send(new byte[] {(byte) ClientMessage.CHOOSE_CLASS, (byte) ClassTable.SNIPER});
                for (int i = 0; i < 40 && w.entity(Wire.SELF_HANDLE).classId != ClassTable.SNIPER; i++) {
                    c.input(i + 11, 0, 0, 0);
                    applyNext(c, w);
                }
                assertThat(w.entity(Wire.SELF_HANDLE).classId).as("told of its own class").isEqualTo(ClassTable.SNIPER);
                // 1 600 × 1.2: a Sniper sees further. In floats, a hair over 1 920.
                waitUntil(() -> Math.abs(seen.get() - 1_920f) < 0.01f);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("the ticket's bonus is on every tank the player spawns, a respawn's too (D-37)")
    void theTicketsBonusIsOnTheTank() throws Exception {
        assertThat(Ticket.BONUS_STATS).as("the ticket names sim's stats").isEqualTo(Stat.COUNT);
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 30, 1)) {
            int port = server.start(0);
            Ticket worn = Ticket.forPlayer(9601, "worn", 0, "5:20,6:10");
            tickets.issue(worn).get(5, TimeUnit.SECONDS);
            try (TestClient c = new TestClient(port)) {
                c.join(worn.id());
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                RoomThread room = server.registry().rooms().get(0);
                com.backend.sim.StatTable table = room.room().content().stats();
                float damage = table.valueOf(Stat.BULLET_DAMAGE, 1, 0) * 1.2f;
                float reload = table.valueOf(Stat.RELOAD, 1, 0) / 1.1f;
                // What the player's tank has, read on the room thread: its slot, damage, reload.
                java.util.concurrent.atomic.AtomicReference<float[]> tank = new java.util.concurrent.atomic.AtomicReference<>();
                java.util.concurrent.atomic.AtomicBoolean kill = new java.util.concurrent.atomic.AtomicBoolean();
                room.tickHook = () -> {
                    int id = room.connections().get(0).entityId();
                    if (id < 0) {
                        tank.set(null);
                        return;
                    }
                    if (kill.compareAndSet(true, false)) {
                        room.room().world().kill(room.room().world().entities[id]);
                        return;
                    }
                    TankStats s = room.room().world().tankStats[id];
                    s.refresh(table);
                    tank.set(new float[] {id, s.value(Stat.BULLET_DAMAGE), s.value(Stat.RELOAD)});
                };
                int seq = 1;
                while (tank.get() == null) {
                    c.input(seq++, 0, 0, 0);
                    c.readFrame();
                }
                assertThat(tank.get()[1]).as("bullet damage, 20 % more").isCloseTo(damage, within(1e-3f));
                assertThat(tank.get()[2]).as("reload, 10 % quicker").isCloseTo(reload, within(1e-3f));

                kill.set(true);
                // Until the kill, then the death, are seen: waited for, not read for, since a dead
                // player is sent the death and then nothing until they respawn (T-22).
                while (kill.get() || tank.get() != null) {
                    Thread.sleep(5);
                }
                c.send(new byte[] {(byte) ClientMessage.RESPAWN});
                while (tank.get() == null) {
                    c.input(seq++, 0, 0, 0);
                    c.readFrame();
                }
                assertThat(tank.get()[1]).as("the respawned tank's damage").isCloseTo(damage, within(1e-3f));
                assertThat(tank.get()[2]).as("and reload").isCloseTo(reload, within(1e-3f));
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("the ticket's skin is on the player's tank, a respawn's too, and its own first frame says so (D-70)")
    void theTicketsSkinIsOnTheTank() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 30, 1)) {
            int port = server.start(0);
            Ticket worn = Ticket.forPlayer(9602, "skinned", 0, "", 4);
            tickets.issue(worn).get(5, TimeUnit.SECONDS);
            try (TestClient c = new TestClient(port)) {
                c.join(worn.id());
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                RoomThread room = server.registry().rooms().get(0);
                java.util.concurrent.atomic.AtomicInteger skin = new java.util.concurrent.atomic.AtomicInteger(-1);
                java.util.concurrent.atomic.AtomicBoolean kill = new java.util.concurrent.atomic.AtomicBoolean();
                room.tickHook = () -> {
                    int id = room.connections().get(0).entityId();
                    if (id < 0) {
                        skin.set(-1);
                        return;
                    }
                    if (kill.compareAndSet(true, false)) {
                        room.room().world().kill(room.room().world().entities[id]);
                        return;
                    }
                    skin.set(room.room().world().tankStats[id].skin);
                };
                ClientWorld w = new ClientWorld();
                List<SnapshotReader.Skin> told = new ArrayList<>();
                int seq = 1;
                // Bounded, so a skin never told fails here rather than blocking in a read past the timeout (M-13).
                for (int frames = 0; frames < 250 && (skin.get() < 0 || told.isEmpty()); frames++) {
                    c.input(seq++, 0, 0, 0);
                    byte[] frame = c.readFrame();
                    if ((frame[0] & 0xFF) == Wire.MSG_SNAPSHOT) {
                        w.apply(frame);
                        w.events().stream().filter(e -> e.type() == Wire.EVT_SKIN).map(SnapshotReader::skin).forEach(told::add);
                    }
                }
                assertThat(skin.get()).isEqualTo(4);
                assertThat(told).as("its own tank's create told").contains(new SnapshotReader.Skin(Wire.SELF_HANDLE, 4));

                kill.set(true);
                while (kill.get() || skin.get() >= 0) {
                    Thread.sleep(5);
                }
                c.send(new byte[] {(byte) ClientMessage.RESPAWN});
                while (skin.get() < 0) {
                    c.input(seq++, 0, 0, 0);
                    c.readFrame();
                }
                assertThat(skin.get()).as("the respawned tank's").isEqualTo(4);
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a held trigger is the tank's attacking, as the latest input says, for its drones to follow")
    void theTriggerHeldIsAttacking() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 30, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                RoomThread room = server.registry().rooms().get(0);
                java.util.concurrent.atomic.AtomicReference<Boolean> attacking = new java.util.concurrent.atomic.AtomicReference<>();
                room.tickHook = () -> {
                    Connection player = room.connections().get(0);
                    attacking.set(room.room().world().entities[player.entityId()].attacking);
                };
                for (int seq = 1; seq <= 10; seq++) {
                    c.input(seq, 0, 0, ClientMessage.FLAG_FIRE);
                    c.readFrame();
                }
                waitUntil(() -> Boolean.TRUE.equals(attacking.get()));
                for (int seq = 11; seq <= 20; seq++) {
                    c.input(seq, 0, 0, 0);
                    c.readFrame();
                }
                waitUntil(() -> Boolean.FALSE.equals(attacking.get()));
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("the welcome's versions are the class table's and the phrase list's, the ones platform serves (D-24, 01 §9)")
    void theWelcomeNamesTheTable() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 30, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                Welcome welcome = Welcome.of(c.readFrame());
                assertThat(welcome.contentVersion()).isEqualTo(ClassTable.defaults().version());
                assertThat(welcome.phraseListVersion()).isEqualTo(com.backend.sim.PhraseTable.defaults().version());
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("zoom held, as the latest input says, is the tank's zooming (01 §4)")
    void theZoomHeldIsZooming() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 30, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                RoomThread room = server.registry().rooms().get(0);
                java.util.concurrent.atomic.AtomicReference<Boolean> zooming = new java.util.concurrent.atomic.AtomicReference<>();
                room.tickHook = () -> {
                    Connection player = room.connections().get(0);
                    zooming.set(room.room().world().entities[player.entityId()].zooming);
                };
                for (int seq = 1; seq <= 10; seq++) {
                    c.input(seq, 0, 0, ClientMessage.FLAG_ZOOM);
                    c.readFrame();
                }
                waitUntil(() -> Boolean.TRUE.equals(zooming.get()));
                for (int seq = 11; seq <= 20; seq++) {
                    c.input(seq, 0, 0, 0);
                    c.readFrame();
                }
                waitUntil(() -> Boolean.FALSE.equals(zooming.get()));
            }
        }
    }

    /**
     * Drives the client and watches the server's copy of its tank until {@code done} holds.
     *
     * White-box on the assertion side deliberately: the alternative is decoding a snapshot's
     * event section, which needs a full reader for the entity sections in front of it. The
     * input side is still a real socket speaking the real protocol.
     */
    private static TankStats playAndWait(TestClient c, ArenaServer server,
                                         java.util.function.Predicate<TankStats> done,
                                         int seconds) throws Exception {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        for (int seq = 1; System.nanoTime() < deadline; seq++) {
            c.input(seq, ClientMessage.MOVE_RIGHT, seq * 2141, ClientMessage.FLAG_AUTOFIRE);
            c.readFrame();                      // keep draining, or the server's writes back up
            TankStats stats = statsOfOnlyPlayer(server);
            if (stats != null && done.test(stats)) {
                return stats;
            }
        }
        return null;
    }

    /** The server's view of the one player in the one room. */
    private static TankStats statsOfOnlyPlayer(ArenaServer server) {
        if (server.registry().rooms().isEmpty()) {
            return null;
        }
        var world = server.registry().rooms().get(0).room().world();
        for (int i = 0; i < world.tanks.size; i++) {
            var tank = world.entities[world.tanks.items[i]];
            if (tank.playerControlled) {
                return world.tankStats[tank.id];
            }
        }
        return null;
    }

    @Test
    @Timeout(30)
    @DisplayName("a message type outside the protocol still closes the connection")
    void unknownMessageStillCloses() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 60, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);

                c.simple(200);                      // not in the protocol at all
                assertThatEventuallyClosed(c);
            }
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("dying tells the client who did it, and Respawn brings it back")
    void deathAndRespawn() throws Exception {
        // Four players on a 200-unit map: measured, first death lands at 4.5-5.0 s across
        // every seed tried. Two players on a larger map often never die at all.
        try (ArenaServer server = newServer(200f, 2_048, 20, 0, 1)) {
            int port = server.start(0);
            TestClient[] clients = new TestClient[4];
            try {
                for (int i = 0; i < clients.length; i++) {
                    clients[i] = new TestClient(port);
                    clients[i].join(issueTicket(0));
                    assertThat(clients[i].readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                }

                byte[] death = null;
                TestClient victim = null;
                long deadline = System.nanoTime() + 60_000_000_000L;
                for (int seq = 1; death == null && System.nanoTime() < deadline; seq++) {
                    for (TestClient c : clients) {
                        c.input(seq, ClientMessage.MOVE_RIGHT, seq * 2141, ClientMessage.FLAG_AUTOFIRE);
                    }
                    for (TestClient c : clients) {
                        byte[] frame = c.readFrame();
                        if (isDeathOnlySnapshot(frame)) {
                            death = frame;
                            victim = c;
                            break;
                        }
                    }
                }

                assertThat(death).as("somebody should have died by now").isNotNull();
                assertThat(death[9] & 0xFF).as("the event is a death").isEqualTo(Wire.EVT_DEATH);
                var in = new java.io.ByteArrayInputStream(death, 10, death.length - 10);
                long payloadLength = TestClient.readVarint(in);
                assertThat(payloadLength)
                        .as("the length that lets an older client skip an event it cannot read")
                        .isPositive();
                long score = TestClient.readVarint(in);
                int nameLength = in.read();
                assertThat(score).as("their score at the moment they died").isNotNegative();
                assertThat(nameLength).as("and who killed them, if anyone").isBetween(0, 64);

                // Dead means dead until asked otherwise: no silent respawn.
                victim.soTimeout(1_500);
                assertThatNoNormalSnapshotArrives(victim);

                victim.soTimeout(5_000);
                victim.simple(ClientMessage.RESPAWN);
                assertThat(readNormalSnapshot(victim)).as("back in the world").isNotNull();
            } finally {
                for (TestClient c : clients) {
                    if (c != null) {
                        try {
                            c.close();
                        } catch (IOException ignored) {
                            // already closed
                        }
                    }
                }
            }
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("over TCP, a decoding client sees its inputs echoed and stays in step through death and respawn")
    void decodingClientStaysInStep() throws Exception {
        // deathAndRespawn's arena: four players on a 200-unit map die within seconds.
        try (ArenaServer server = newServer(200f, 2_048, 20, 0, 1)) {
            int port = server.start(0);
            TestClient[] clients = new TestClient[4];
            ClientWorld[] worlds = new ClientWorld[clients.length];
            try {
                for (int i = 0; i < clients.length; i++) {
                    clients[i] = new TestClient(port);
                    clients[i].join(issueTicket(0));
                    assertThat(clients[i].readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                    worlds[i] = new ClientWorld();
                }

                // Play until somebody dies, checking every echo on the way: the server can only
                // have applied a seq this client actually sent.
                int victim = -1;
                int seq = 0;
                long deadline = System.nanoTime() + 60_000_000_000L;
                while (victim < 0 && System.nanoTime() < deadline) {
                    seq++;
                    for (int i = 0; i < clients.length; i++) {
                        clients[i].input(seq, ClientMessage.MOVE_RIGHT, seq * 2141,
                                ClientMessage.FLAG_AUTOFIRE);
                    }
                    for (int i = 0; i < clients.length && victim < 0; i++) {
                        if (applyNext(clients[i], worlds[i]) && diedIn(worlds[i])) {
                            victim = i;
                        }
                        assertThat(worlds[i].lastProcessedInputSeq()).isBetween(0L, (long) seq);
                    }
                }
                assertThat(victim).as("somebody should have died by now").isNotNegative();
                assertThat(worlds[victim].lastProcessedInputSeq())
                        .as("inputs were applied and echoed").isPositive();

                // Respawn, keep playing, and hold one seq still: once the server has applied it,
                // the client must be told exactly that seq.
                //
                // The others stop shooting, but their bullets fly on for three seconds, and on a
                // map this small one can kill the victim again. A dead client with nothing to be
                // told is sent nothing, so this client does what a real one does: asks again.
                // Without that, a second death left it waiting for a frame that never came:
                // one run in seven, caught with a probe as "killed again at frame 30, stalled
                // at 31".
                for (int i = 0; i < clients.length; i++) {
                    if (i != victim) {
                        clients[i].input(seq + 1, 0, 0, 0);
                    }
                }
                TestClient c = clients[victim];
                ClientWorld w = worlds[victim];
                c.soTimeout(5_000);
                c.simple(ClientMessage.RESPAWN);
                int held = seq + 1;
                int deathsAgain = 0;
                int alive = 0;
                while (alive < 40) {
                    c.input(held, ClientMessage.MOVE_LEFT, 0, 0);
                    if (applyNext(c, w) && diedIn(w)) {
                        assertThat(++deathsAgain).as("killed again and again").isLessThan(10);
                        c.simple(ClientMessage.RESPAWN);
                        alive = 0;
                    } else {
                        alive++;
                    }
                }
                assertThat(w.lastProcessedInputSeq()).isEqualTo(held);
                assertThat(w.entity(Wire.SELF_HANDLE).alive).as("back in the world, as handle 1").isTrue();

                // And the client's tick is the server's. Before P-6 was fixed, a respawn
                // added the whole elapsed match to it a second time.
                c.ping(System.currentTimeMillis() & 0xFFFFFFFFL);
                long serverTick = -1;
                while (serverTick < 0) {
                    byte[] f = c.readFrame();
                    if ((f[0] & 0xFF) == Wire.MSG_PONG) {
                        serverTick = TestClient.readVarint(
                                new java.io.ByteArrayInputStream(f, 5, f.length - 5));
                    } else if ((f[0] & 0xFF) == Wire.MSG_SNAPSHOT) {
                        w.apply(f);
                    }
                }
                assertThat(serverTick - w.serverTick()).as("client tick %d, server tick %d",
                        w.serverTick(), serverTick).isBetween(0L, 5L);
            } finally {
                for (TestClient t : clients) {
                    if (t != null) {
                        try {
                            t.close();
                        } catch (IOException ignored) {
                            // already closed
                        }
                    }
                }
            }
        }
    }

    /** Reads one frame and, if it is a snapshot, applies it. The ack then follows the client. */
    private static boolean applyNext(TestClient c, ClientWorld w) throws IOException {
        byte[] frame = c.readFrame();
        if ((frame[0] & 0xFF) != Wire.MSG_SNAPSHOT) {
            return false;
        }
        w.apply(frame);
        c.serverTick = (int) w.serverTick();
        return true;
    }

    private static boolean diedIn(ClientWorld w) {
        return w.events().stream().anyMatch(e -> e.type() == Wire.EVT_DEATH);
    }

    @Test
    @Timeout(30)
    @DisplayName("one tick that throws is survived: the room and its players carry on")
    void oneFailedTickIsSurvived() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 60, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                RoomThread room = server.registry().rooms().get(0);
                java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
                room.tickHook = () -> {
                    if (once.compareAndSet(false, true)) {
                        throw new IllegalStateException("a bug, once");
                    }
                };
                waitUntil(once::get);
                // Before, the exception ended the room thread: no frame would ever come again.
                assertThat(readNormalSnapshot(c)).isNotNull();
                assertThat(readNormalSnapshot(c)).isNotNull();
                assertThat(room.hasFailed()).isFalse();
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a room that keeps failing pays its players, sends them back, and is replaced")
    void failingRoomClosesProperly() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 1,
                MatchRules.open("arena-test"), published::add)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                RoomThread room = server.registry().rooms().get(0);
                room.tickHook = () -> {
                    throw new IllegalStateException("a bug, every tick");
                };

                // Told to come back through the lobby, with the reason that means "retry".
                expectKickAfterSnapshots(c, Wire.KICK_INTERNAL);
                assertThat(room.hasFailed()).isTrue();
                assertThat(published).as("what the player had earned is still paid").hasSize(1);
            }
            // The failed room is dropped and a healthy one takes its place, even at the
            // one-room limit: a new player is welcomed rather than queued into a dead room.
            try (TestClient next = new TestClient(port)) {
                next.join(issueTicket(0));
                assertThat(next.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                assertThat(server.registry().rooms()).hasSize(1).noneMatch(RoomThread::hasFailed);
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("faults 40 ticks apart still close the room: three in 100 ticks, not three in a row")
    void intermittentFailuresCloseTheRoom() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 60, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                RoomThread room = server.registry().rooms().get(0);
                java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                room.tickHook = () -> {
                    if (calls.incrementAndGet() % 40 == 0) {
                        throw new IllegalStateException("a bug every 40 ticks");
                    }
                };
                // Good ticks between the bad ones, as with a fault that strikes only on
                // snapshot ticks: "three in a row" never fired. Three within 80 ticks must.
                expectKickAfterSnapshots(c, Wire.KICK_INTERNAL);
                assertThat(room.hasFailed()).isTrue();
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("faults far enough apart are each survived")
    void sparseFailuresAreSurvived() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 60, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                RoomThread room = server.registry().rooms().get(0);
                java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                room.tickHook = () -> {
                    if (calls.incrementAndGet() % 50 == 0) {
                        throw new IllegalStateException("a rare bug");
                    }
                };
                // Faults at 50, 100 and 150: the three span 100 ticks, one more than the window.
                waitUntil(() -> calls.get() > 160);
                assertThat(room.hasFailed()).isFalse();
                assertThat(readNormalSnapshot(c)).isNotNull();
            }
        }
    }

    @Test
    @Timeout(40)
    @DisplayName("a room that hangs is given up on: its players go back to the lobby and it is replaced")
    void hungRoomIsAbandoned() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 60, 2)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port); TestClient queued = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                RoomThread room = server.registry().rooms().get(0);
                java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
                // Stuck, not throwing: nothing the exception boundary can see.
                room.tickHook = () -> {
                    if (once.compareAndSet(false, true)) {
                        try {
                            release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                };
                long start = System.nanoTime();

                // Joined before the hang is 2 s old: queued behind a thread that will never
                // drain it. It used to wait there, ticket spent, with no Welcome and no Kick.
                Thread.sleep(300);
                queued.join(issueTicket(0));

                // Once the hang is 2 s old the room is not offered any more: this player is
                // welcomed by a new room at once, instead of joining the queue of a dead one.
                Thread.sleep(2_500);
                try (TestClient later = new TestClient(port)) {
                    later.join(issueTicket(0));
                    assertThat(later.readFrame()[0] & 0xFF).as("welcomed elsewhere, not queued")
                            .isEqualTo(Wire.MSG_WELCOME);
                }

                c.soTimeout(15_000);
                expectKickAfterSnapshots(c, Wire.KICK_INTERNAL);
                long waited = (System.nanoTime() - start) / 1_000_000;
                assertThat(waited).as("given up on after the 10 s stall, not before")
                        .isBetween(9_000L, 13_000L);
                assertThat(room.hasFailed()).isTrue();
                queued.soTimeout(5_000);
                expectKick(queued, Wire.KICK_INTERNAL);

                // A new player is welcomed by a replacement, not queued into the stuck room.
                try (TestClient next = new TestClient(port)) {
                    next.join(issueTicket(0));
                    assertThat(next.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                }
                // And if the stuck thread ever returns, it finds itself stopped.
                release.countDown();
                assertThat(room.awaitStopped(5_000)).isTrue();
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a frame that cannot be encoded for one player disconnects that player alone")
    void oneClientsFailureIsItsOwn() throws Exception {
        // 600 shapes on a map smaller than the view, so every shape is in every view.
        try (ArenaServer server = newServer(1_500f, 4_096, 20, 600, 1)) {
            int port = server.start(0);
            try (TestClient broken = new TestClient(port); TestClient fine = new TestClient(port)) {
                broken.join(issueTicket(0));
                assertThat(broken.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                fine.join(issueTicket(0));
                assertThat(fine.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                RoomThread room = server.registry().rooms().get(0);
                waitUntil(() -> room.playerCount() == 2);

                // A budget past what a section's one-byte count can hold, set behind the cap:
                // the next crowded frame for this player cannot be encoded (P-11's failure).
                java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
                long brokenId = nextPlayerId - 2;
                room.tickHook = () -> {
                    if (done.compareAndSet(false, true)) {
                        for (Connection conn : room.connections()) {
                            if (conn.identity().playerId() == brokenId) {
                                conn.view().entityBudget = 200;
                            }
                        }
                    }
                };

                expectKickAfterSnapshots(broken, Wire.KICK_INTERNAL);
                assertThat(readNormalSnapshot(fine)).as("the other player never noticed").isNotNull();
                assertThat(readNormalSnapshot(fine)).isNotNull();
                assertThat(room.hasFailed()).isFalse();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("over TLS a client joins and plays, and a plaintext client gets nothing")
    void tlsConnections(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // A certificate made here, for 127.0.0.1, so no key is ever committed.
        java.nio.file.Path keystore = dir.resolve("arena.p12");
        Process keytool = new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "arena", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=arena-test", "-ext", "SAN=ip:127.0.0.1", "-validity", "2",
                "-keystore", keystore.toString(), "-storetype", "PKCS12",
                "-storepass", "test-pass", "-keypass", "test-pass")
                .redirectErrorStream(true).start();
        assertThat(keytool.waitFor()).as("keytool").isZero();

        java.security.KeyStore trust = java.security.KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(keystore)) {
            trust.load(in, "test-pass".toCharArray());
        }
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        javax.net.ssl.SSLContext trusting = javax.net.ssl.SSLContext.getInstance("TLS");
        trusting.init(null, tmf.getTrustManagers(), null);

        try (ArenaServer server = newServer(2_000f, 2_048, 20, 60, 1)) {
            server.useTls(ArenaTls.fromKeystore(keystore, "test-pass".toCharArray()));
            assertThat(server.tls()).isTrue();
            server.limits(new MatchFrameHandler.Limits(1_000, 30_000, 5_000));
            int port = server.start(0);

            try (TestClient c = new TestClient(port, trusting)) {
                assertThat(c.protocol()).isEqualTo("TLSv1.3");
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                ClientWorld w = new ClientWorld();
                for (int i = 1; i <= 10; i++) {
                    c.input(i, ClientMessage.MOVE_RIGHT, 0, 0);
                    applyNext(c, w);
                }
                assertThat(w.entity(Wire.SELF_HANDLE).alive).as("played, through TLS").isTrue();
            }

            // Plaintext to a TLS port: the handshake fails on the first bytes and the
            // connection closes. The proof that the join was never read is the ticket: a
            // claim deletes it, and it is still there.
            String ticket = issueTicket(0);
            try (TestClient plain = new TestClient(port)) {
                plain.join(ticket);
                assertThatEventuallyClosed(plain);
            }
            try (JRedisClient probe = store.newClient()) {
                assertThat(probe.sync().exists("ticket:" + ticket)).as("never claimed").isEqualTo(1);
            }

            // Connected and silent, never starting a handshake: closed at the join deadline,
            // and counted once, as the handshake it never finished. Closing it also ends the
            // handshake, which would count it a second time if nothing stopped it.
            try (TestClient silent = new TestClient(port)) {
                assertThatEventuallyClosed(silent);
            }
            waitUntil(() -> server.dropped().get("tls_handshake") >= 2);
            Thread.sleep(200);
            assertThat(server.dropped().get("tls_handshake")).as("the plaintext join, and the silent one")
                    .isEqualTo(2);
            assertThat(server.dropped().get("join_deadline")).isZero();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("pings do not stand in for a join, silence ends a joined connection, and half a frame is silence")
    void silentConnectionsAreClosed() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 60, 1)) {
            server.limits(new MatchFrameHandler.Limits(1_000, 1_500, 5_000));
            int port = server.start(0);

            // Never joins, pings throughout: whole frames, so the idle rule alone would keep
            // it for ever. The join deadline does not.
            try (TestClient c = new TestClient(port)) {
                long start = System.nanoTime();
                Thread pinger = Thread.ofVirtual().start(() -> {
                    try {
                        while (true) {
                            c.ping(1);
                            Thread.sleep(200);
                        }
                    } catch (IOException | InterruptedException gone) {
                        // closed: done
                    }
                });
                assertThatEventuallyClosed(c);
                assertThat((System.nanoTime() - start) / 1_000_000).as("closed by the deadline, not at once")
                        .isGreaterThanOrEqualTo(900);
                pinger.interrupt();
            }
            assertThat(server.dropped().get("join_deadline")).isEqualTo(1);

            // Joined, then kept alive by pings for twice the idle limit, then a frame sent a
            // byte at a time. Only whole frames count, so the connection closes one idle
            // limit after the last ping; if bytes counted, the dribble would hold it open.
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                long welcomed = System.nanoTime();
                java.util.concurrent.atomic.AtomicLong lastPing = new java.util.concurrent.atomic.AtomicLong();
                Thread sender = Thread.ofVirtual().start(() -> {
                    try {
                        while (System.nanoTime() - welcomed < 3_000_000_000L) {
                            c.ping(1);
                            lastPing.set(System.nanoTime());
                            Thread.sleep(300);
                        }
                        c.out.write(100);                   // a 100-byte frame, begun...
                        for (int i = 0; i < 40; i++) {      // ...and never finished
                            Thread.sleep(200);
                            c.out.write(ClientMessage.PING);
                            c.out.flush();
                        }
                    } catch (IOException | InterruptedException gone) {
                        // closed: done
                    }
                });
                long closed = readUntilClosed(c, 15_000);
                sender.interrupt();
                assertThat((closed - welcomed) / 1_000_000).as("the pings kept it open")
                        .isGreaterThanOrEqualTo(3_000);
                assertThat((closed - lastPing.get()) / 1_000_000).as("the half frame did not")
                        .isBetween(1_000L, 3_500L);
            }
            assertThat(server.dropped().get("idle")).isEqualTo(1);
            // Silence is a lost connection, not a leave: the stay waits for its player (02 §10).
            waitUntil(() -> server.registry().rooms().get(0).suspendedCount() == 1);
            assertThat(server.registry().totalPlayers()).as("their place kept").isEqualTo(1);
        }
    }

    /** Reads and discards until the server closes the connection; returns when, in nanoseconds. */
    private static long readUntilClosed(TestClient c, long withinMillis) throws IOException {
        c.soTimeout(200);
        long deadline = System.nanoTime() + withinMillis * 1_000_000;
        byte[] sink = new byte[4096];
        while (System.nanoTime() < deadline) {
            try {
                if (c.in.read(sink) < 0) {
                    return System.nanoTime();
                }
            } catch (java.net.SocketTimeoutException quiet) {
                // nothing yet
            } catch (IOException reset) {
                return System.nanoTime();
            }
        }
        throw new AssertionError("the server kept the connection open");
    }

    @Test
    @Timeout(30)
    @DisplayName("an arena that stops tells its players why, instead of just closing")
    void shutdownTellsPlayersWhy() throws Exception {
        ArenaServer server = newServer(2_000f, 2_048, 20, 60, 1);
        int port = server.start(0);
        try (TestClient c = new TestClient(port)) {
            c.join(issueTicket(0));
            assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
            waitUntil(() -> server.registry().totalPlayers() == 1);

            Thread closing = Thread.ofVirtual().start(server::close);
            // A bare close looks like the network failing, and a client retries the same
            // arena; reason 5 sends it back through the lobby, with backoff, to one that is up.
            expectKickAfterSnapshots(c, Wire.KICK_INTERNAL);
            closing.join(10_000);
        }
        // Its javadoc said this was safe; it submitted to event loops that had shut down. Not
        // straight away: for the two-second quiet period a stopping loop still takes tasks.
        Thread.sleep(3_000);
        server.close();
    }

    @Test
    @Timeout(40)
    @DisplayName("backgrounding stops the snapshots, and returning starts them again")
    void backgroundingParksTheClient() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 60, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                assertThat(readNormalSnapshot(c)).isNotNull();

                c.lifecycle(ClientMessage.LIFECYCLE_BACKGROUND);
                Thread.sleep(400);                  // let the room see the change
                drainQuietly(c);
                c.soTimeout(1_500);
                // A backgrounded phone cannot render them and is still charged for the data.
                assertThatNoNormalSnapshotArrives(c);

                c.soTimeout(5_000);
                c.lifecycle(ClientMessage.LIFECYCLE_FOREGROUND);
                assertThat(readNormalSnapshot(c)).as("and it resumes on return").isNotNull();
                assertThat(server.registry().totalPlayers())
                        .as("the player was parked, never removed").isEqualTo(1);
            }
        }
    }

    /** A dead player's frame: every section empty except the events. */
    private static boolean isDeathOnlySnapshot(byte[] frame) {
        if (frame.length < 10 || (frame[0] & 0xFF) != Wire.MSG_SNAPSHOT) {
            return false;
        }
        for (int i = 1; i <= 7; i++) {
            if (frame[i] != 0) {
                return false;
            }
        }
        return (frame[8] & 0xFF) > 0;
    }

    private static byte[] readUntilType(TestClient c, int type) throws IOException {
        for (int i = 0; i < 60; i++) {
            byte[] frame = c.readFrame();
            if ((frame[0] & 0xFF) == type) {
                return frame;
            }
        }
        throw new AssertionError("no frame of type " + type + " arrived");
    }

    @Test
    @Timeout(60)
    @DisplayName("a client that asks for saver is told 10 a second and sent 10; no byte, or one unknown, gets 15")
    void profileFromTheJoin() throws Exception {
        try (ArenaServer server = newServer(4_000f, 1_024, 20, 0, 1)) {
            int port = server.start(0);
            int[] asked = {Wire.PROFILE_SAVER, -1, Wire.PROFILE_HIGH, 9};
            int[] expected = {10, 15, 15, 15};
            for (int i = 0; i < asked.length; i++) {
                try (TestClient c = new TestClient(port)) {
                    c.join(issueTicket(0), asked[i]);
                    byte[] welcome = c.readFrame();
                    assertThat(welcome[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                    assertThat(welcome[2] & 0xFF).as("the rate in the welcome, asked %d", asked[i])
                            .isEqualTo(expected[i]);
                    // Measured in the frames' own ticks, not the test's clock: every snapshot,
                    // an empty one included, carries the ticks since the one before. The first
                    // counts from zero, the room's whole age, so the count starts after it.
                    int ticks = 0;
                    byte[] first;
                    do {
                        first = c.readFrame();
                    } while ((first[0] & 0xFF) != Wire.MSG_SNAPSHOT);
                    for (int n = 0; n < 40; ) {
                        byte[] frame = c.readFrame();
                        if ((frame[0] & 0xFF) == Wire.MSG_SNAPSHOT) {
                            ticks += (int) TestClient.readVarint(new java.io.ByteArrayInputStream(frame, 1, 5));
                            n++;
                        }
                    }
                    assertThat(40 * 25.0 / ticks).as("snapshots a second, asked %d", asked[i])
                            .isCloseTo(expected[i], org.assertj.core.data.Offset.offset(0.8));
                }
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a client whose acknowledgements fall a second behind is stepped down, alone")
    void aLaggingClientIsSteppedDown() throws Exception {
        try (ArenaServer server = newServer(4_000f, 1_024, 20, 0, 1)) {
            int port = server.start(0);
            try (TestClient lagging = new TestClient(port); TestClient prompt = new TestClient(port)) {
                lagging.join(issueTicket(0));
                long laggingId = nextPlayerId - 1;
                prompt.join(issueTicket(0));
                long promptId = nextPlayerId - 1;
                RoomThread room = server.registry().rooms().get(0);
                java.util.concurrent.atomic.AtomicBoolean lag = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
                // Each reads everything it is sent and acknowledges ten times a second, as the
                // contract asks; the lagging one, once told to, acknowledges what it had 1.2 s
                // earlier, as a client behind a 1.2 s queue does.
                Thread[] readers = new Thread[2];
                TestClient[] clients = {lagging, prompt};
                for (int k = 0; k < 2; k++) {
                    TestClient c = clients[k];
                    boolean lags = k == 0;
                    readers[k] = new Thread(() -> {
                        java.util.ArrayDeque<long[]> seen = new java.util.ArrayDeque<>();
                        long nextAck = System.nanoTime();
                        int seq = 0;
                        try {
                            c.soTimeout(100);
                            while (!stop.get()) {
                                try {
                                    byte[] frame = c.readFrame();
                                    if ((frame[0] & 0xFF) == Wire.MSG_SNAPSHOT) {
                                        c.serverTick += (int) TestClient.readVarint(
                                                new java.io.ByteArrayInputStream(frame, 1, 5));
                                        seen.add(new long[] {System.nanoTime(), c.serverTick});
                                    }
                                } catch (java.net.SocketTimeoutException quiet) {
                                    // nothing this tenth of a second; acknowledge anyway
                                }
                                long now = System.nanoTime();
                                if (now >= nextAck) {
                                    nextAck = now + 100_000_000L;
                                    int ack = c.serverTick;
                                    if (lags && lag.get()) {
                                        ack = 0;
                                        for (long[] s : seen) {
                                            if (s[0] <= now - 1_200_000_000L) {
                                                ack = (int) s[1];
                                            }
                                        }
                                    }
                                    while (seen.size() > 200) {
                                        seen.poll();
                                    }
                                    if (ack > 0) {
                                        c.acknowledge(++seq, ack);
                                    }
                                }
                            }
                        } catch (IOException e) {
                            // closed at the end of the test
                        }
                    });
                    readers[k].start();
                }
                try {
                    Thread.sleep(2_000);                  // prompt acknowledgements: the floor
                    lag.set(true);
                    Thread.sleep(4_000);

                    java.util.Map<Long, TrafficProfile> profiles = new java.util.concurrent.ConcurrentHashMap<>();
                    java.util.Map<Long, Integer> budgets = new java.util.concurrent.ConcurrentHashMap<>();
                    java.util.concurrent.CountDownLatch read = new java.util.concurrent.CountDownLatch(1);
                    room.tickHook = () -> {
                        for (Connection conn : room.connections()) {
                            profiles.put(conn.identity().playerId(), conn.traffic().profile());
                            budgets.put(conn.identity().playerId(), conn.view().entityBudget);
                        }
                        read.countDown();
                    };
                    assertThat(read.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    room.tickHook = null;
                    assertThat(profiles.get(laggingId)).isEqualTo(TrafficProfile.SAVER);
                    assertThat(profiles.get(promptId)).as("the other player").isEqualTo(TrafficProfile.MOBILE);
                    assertThat(budgets.get(laggingId)).as("and its frames hold fewer entities")
                            .isEqualTo(TrafficProfile.SAVER.budget);
                    assertThat(budgets.get(promptId)).isEqualTo(TrafficProfile.MOBILE.budget);
                    assertThat(room.profileStepsDown()).isEqualTo(1);
                    assertThat(room.clientsAt(TrafficProfile.SAVER)).isEqualTo(1);
                    assertThat(room.clientsAt(TrafficProfile.MOBILE)).isEqualTo(1);
                    assertThat(room.bytesSentAt(TrafficProfile.SAVER)).isPositive();
                } finally {
                    stop.set(true);
                    for (Thread t : readers) {
                        t.join(5_000);
                    }
                }
            }
        }
    }

    // ---- phrases (01 §9, D-32) ------------------------------------------------------------------

    private static String namedTicket(long playerId, String name) throws Exception {
        Ticket t = Ticket.forPlayer(playerId, name, 0);
        tickets.issue(t).get(5, TimeUnit.SECONDS);
        return t.id();
    }

    private static void say(TestClient c, long phraseId) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(ClientMessage.PHRASE);
        long v = phraseId;
        while ((v & ~0x7FL) != 0) {
            b.write((int) (v & 0x7F) | 0x80);
            v >>>= 7;
        }
        b.write((int) v);
        c.send(b.toByteArray());
    }

    /** Reads until {@code millis} after {@code since}, nanoTime, discarding what is heard. */
    private static void waitOut(TestClient c, ClientWorld w, long since, long millis) throws IOException {
        long left = millis - (System.nanoTime() - since) / 1_000_000L;
        if (left > 0) {
            assertThat(heard(c, w, left)).isEmpty();
        }
    }

    /** On the room thread: a player's tank to a spot, still and unprotected; nothing if it has none. */
    private static void pin(RoomThread room, long playerId, float x, float y) {
        for (Connection c : room.connections()) {
            if (c.identity().playerId() == playerId && c.entityId() >= 0) {
                var t = room.room().world().entities[c.entityId()];
                t.x = x;
                t.y = y;
                t.vx = t.vy = 0f;
                t.protectedUntilTick = 0;
            }
        }
    }

    /** On the room thread: a player's tank at half a point of health, while it has one. */
    private static void weaken(RoomThread room, long playerId) {
        for (Connection c : room.connections()) {
            if (c.identity().playerId() == playerId && c.entityId() >= 0) {
                room.room().world().entities[c.entityId()].hp = 0.5f;
            }
        }
    }

    /**
     * Every phrase a client hears for a while, as {@code handle:id:name}, its frames applied as they
     * come. A dead player is sent a frame only when it has an event (M-16), so silence is waited out.
     */
    private static List<String> heard(TestClient c, ClientWorld w, long millis) throws IOException {
        List<String> phrases = new ArrayList<>();
        long end = System.nanoTime() + millis * 1_000_000L;
        c.soTimeout(200);
        while (System.nanoTime() < end) {
            try {
                if (!applyNext(c, w)) {
                    continue;
                }
            } catch (java.net.SocketTimeoutException quiet) {
                continue;
            }
            for (var e : w.events()) {
                if (e.type() == Wire.EVT_PHRASE) {
                    byte[] p = e.payload();
                    java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(p, 1, p.length - 1);
                    long id = TestClient.readVarint(in);
                    byte[] name = in.readNBytes(in.read());
                    phrases.add((p[0] & 0xFF) + ":" + id + ":" + new String(name, StandardCharsets.UTF_8));
                }
            }
        }
        c.soTimeout(5_000);
        return phrases;
    }

    /** The handle a client's view gives the tank standing at a spot, or 0. */
    private static int handleAt(ClientWorld w, float x, float y) {
        for (int h = 1; h <= ClientView.MAX_ENTITY_BUDGET; h++) {
            ClientWorld.Entity e = w.entity(h);
            if (e != null && e.alive && e.kind == Wire.KIND_TANK
                    && Math.abs(e.x / Wire.POS_SCALE - x) < 20f
                    && Math.abs(e.y / Wire.POS_SCALE - y) < 20f) {
                return h;
            }
        }
        return 0;
    }

    @Test
    @Timeout(90)
    @DisplayName("in the public arena a phrase is heard by those who see the speaker, the speaker too, not one out of sight, and never from a speaker with no tank (01 §9, D-32)")
    void aPhraseIsHeardByThoseWhoSeeTheSpeaker() throws Exception {
        try (ArenaServer server = newServer(6_000f, 2_048, 20, 0, 1)) {
            int port = server.start(0);
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port);
                 TestClient c = new TestClient(port)) {
                a.join(namedTicket(9401, "ada"));
                Welcome.of(readReply(a));
                b.join(namedTicket(9402, "bo"));
                Welcome.of(readReply(b));
                c.join(namedTicket(9403, "cy"));
                Welcome.of(readReply(c));
                RoomThread room = server.registry().rooms().get(0);
                room.tickHook = () -> {
                    pin(room, 9401, 1_000f, 1_000f);
                    pin(room, 9402, 1_080f, 1_000f);     // in front of A, in A's view and A in B's
                    pin(room, 9403, 5_000f, 5_000f);     // out of both
                };
                ClientWorld wa = new ClientWorld();
                ClientWorld wb = new ClientWorld();
                ClientWorld wc = new ClientWorld();
                heard(a, wa, 700);
                heard(b, wb, 700);
                heard(c, wc, 700);
                int aInB = handleAt(wb, 1_000f, 1_000f);
                assertThat(aInB).as("B sees A").isNotZero();

                say(a, 3);
                assertThat(heard(a, wa, 1_000)).as("the speaker").containsExactly(Wire.SELF_HANDLE + ":3:ada");
                assertThat(heard(b, wb, 1_000)).as("one who sees the speaker, by the handle it knows").containsExactly(aInB + ":3:ada");
                assertThat(heard(c, wc, 1_000)).as("out of sight").isEmpty();

                // B dies to A's fire, and speaks with no tank: in a mode without teams nobody hears.
                room.tickHook = () -> {
                    pin(room, 9401, 1_000f, 1_000f);
                    pin(room, 9402, 1_080f, 1_000f);
                    weaken(room, 9402);
                    pin(room, 9403, 5_000f, 5_000f);
                };
                boolean died = false;
                for (int seq = 1; seq < 300 && !died; seq++) {
                    a.input(seq, 0, 32_768, ClientMessage.FLAG_AUTOFIRE);  // aim along +x, at B
                    applyNext(a, wa);
                    if (applyNext(b, wb)) {
                        died = diedIn(wb);
                    }
                }
                assertThat(died).as("B died").isTrue();
                say(b, 4);
                assertThat(heard(b, wb, 1_000)).as("the speaker, with no tank").isEmpty();
                assertThat(heard(a, wa, 1_000)).as("the one who killed them, standing there").isEmpty();
                assertThat(heard(c, wc, 500)).isEmpty();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a client sent no snapshots, backgrounded, is not told a phrase by the handle of the slot's earlier occupant (01 §9, P-13)")
    void aStaleHandleNamesNobody() throws Exception {
        try (ArenaServer server = newServer(6_000f, 2_048, 20, 0, 1)) {
            int port = server.start(0);
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(namedTicket(9431, "ada"));
                Welcome.of(readReply(a));
                b.join(namedTicket(9432, "bo"));
                Welcome.of(readReply(b));
                RoomThread room = server.registry().rooms().get(0);
                room.tickHook = () -> {
                    pin(room, 9431, 1_000f, 1_000f);
                    pin(room, 9432, 1_080f, 1_000f);
                };
                ClientWorld wa = new ClientWorld();
                ClientWorld wb = new ClientWorld();
                heard(a, wa, 700);
                heard(b, wb, 700);
                assertThat(handleAt(wb, 1_000f, 1_000f)).as("B sees A").isNotZero();
                b.lifecycle(ClientMessage.LIFECYCLE_BACKGROUND);
                heard(b, wb, 300);

                // A's slot now holds another incarnation, as a death and a respawn into it would;
                // B, sent nothing while in the background, still holds the handle of the first.
                java.util.concurrent.atomic.AtomicBoolean bumped = new java.util.concurrent.atomic.AtomicBoolean();
                room.tickHook = () -> {
                    pin(room, 9431, 1_000f, 1_000f);
                    pin(room, 9432, 1_080f, 1_000f);
                    for (Connection c : room.connections()) {
                        if (c.identity().playerId() == 9431 && c.entityId() >= 0 && bumped.compareAndSet(false, true)) {
                            room.room().world().entities[c.entityId()].generation++;
                        }
                    }
                };
                heard(a, wa, 700);                       // A's own view takes the new one
                assertThat(bumped).isTrue();
                say(a, 3);
                assertThat(heard(a, wa, 700)).as("the speaker").containsExactly(Wire.SELF_HANDLE + ":3:ada");
                b.lifecycle(ClientMessage.LIFECYCLE_FOREGROUND);
                assertThat(heard(b, wb, 1_000)).as("B saw only the earlier occupant").isEmpty();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a phrase too soon after the last, or not in the list, is dropped without a kick, and costs no turn (01 §9)")
    void aPhraseTooSoonOrUnknownIsDropped() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 0, 1)) {
            int port = server.start(0);
            try (TestClient a = new TestClient(port)) {
                a.join(namedTicket(9411, "ada"));
                Welcome.of(readReply(a));
                ClientWorld w = new ClientWorld();
                heard(a, w, 300);
                long first = System.nanoTime();
                say(a, 1);
                assertThat(heard(a, w, 600)).containsExactly("1:1:ada");
                waitOut(a, w, first, 1_500);
                say(a, 2);                               // a second and a half on: too soon
                assertThat(heard(a, w, 400)).as("too soon").isEmpty();
                waitOut(a, w, first, 2_200);
                long second = System.nanoTime();
                say(a, 6);                               // two seconds on, and no more
                assertThat(heard(a, w, 600)).as("its turn").containsExactly("1:6:ada");
                waitOut(a, w, second, 2_200);
                say(a, 0);
                assertThat(heard(a, w, 250)).as("0 is no phrase").isEmpty();
                say(a, PhraseTable.defaults().size() + 1);
                assertThat(heard(a, w, 250)).as("past the list").isEmpty();
                say(a, 300);
                assertThat(heard(a, w, 250)).as("far past it, two bytes").isEmpty();
                say(a, (1L << 32) + 5);
                assertThat(heard(a, w, 250)).as("wider than an int, which cast would be 5").isEmpty();
                say(a, 5);                               // at once: the refused ids took no turn
                assertThat(heard(a, w, 600)).as("still connected, and heard").containsExactly("1:5:ada");
            }
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("in a team mode a phrase is heard by the speaker's team wherever they are, not by the other team beside them, and from a speaker with no tank too (01 §9, D-32)")
    void aTeamHearsItsOwn() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), outcome -> { })) {
            server.registry().adjustMade = r -> r.withJoinWindow(25);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port);
                 TestClient d = new TestClient(port); TestClient e = new TestClient(port)) {
                a.join(tvtTicket(uid, 9421, 1));
                Welcome.of(readReply(a));
                b.join(tvtTicket(uid, 9422, 1));
                Welcome.of(readReply(b));
                d.join(tvtTicket(uid, 9423, 2));
                Welcome.of(readReply(d));
                e.join(tvtTicket(uid, 9424, 2));
                Welcome.of(readReply(e));
                RoomThread room = roomOf(server, uid);
                room.tickHook = () -> {
                    var w = room.room().world();
                    clearAround(w, 500f, 1_000f);
                    pin(room, 9421, 500f, 1_000f);
                    pin(room, 9423, 580f, 1_000f);       // the other team, beside A
                    pin(room, 9422, 2_700f, 2_700f);     // A's team, out of A's sight
                    pin(room, 9424, 2_700f, 300f);
                };
                ClientWorld wa = new ClientWorld();
                ClientWorld wb = new ClientWorld();
                ClientWorld wd = new ClientWorld();
                ClientWorld we = new ClientWorld();
                heard(a, wa, 700);
                heard(b, wb, 700);
                heard(d, wd, 700);
                heard(e, we, 700);
                assertThat(handleAt(wd, 500f, 1_000f)).as("D sees A").isNotZero();

                say(a, 9);
                assertThat(heard(a, wa, 1_000)).as("the speaker").containsExactly(Wire.SELF_HANDLE + ":9:t-9421");
                assertThat(heard(b, wb, 1_000)).as("a teammate out of sight, by name").containsExactly("0:9:t-9421");
                assertThat(heard(d, wd, 1_000)).as("the other team, though it sees A").isEmpty();
                assertThat(heard(e, we, 500)).isEmpty();

                // D dies to A's fire and calls for help with no tank: D's team hears, A's does not.
                room.tickHook = () -> {
                    var w = room.room().world();
                    clearAround(w, 500f, 1_000f);
                    pin(room, 9421, 500f, 1_000f);
                    pin(room, 9423, 580f, 1_000f);
                    weaken(room, 9423);
                    pin(room, 9422, 2_700f, 2_700f);
                    pin(room, 9424, 2_700f, 300f);
                };
                boolean died = false;
                for (int seq = 1; seq < 300 && !died; seq++) {
                    a.input(seq, 0, 32_768, ClientMessage.FLAG_AUTOFIRE);
                    applyNext(a, wa);
                    if (applyNext(d, wd)) {
                        died = diedIn(wd);
                    }
                }
                assertThat(died).as("D died").isTrue();
                say(d, 7);
                assertThat(heard(d, wd, 1_000)).as("the speaker, with no tank").containsExactly("0:7:t-9423");
                assertThat(heard(e, we, 1_000)).as("D's teammate").containsExactly("0:7:t-9423");
                assertThat(heard(a, wa, 1_000)).as("the other team").isEmpty();
                assertThat(heard(b, wb, 500)).isEmpty();
            }
        }
    }

    /** What a client needs from its Welcome to come back: its tank, and the secret to ask with. */
    private record Welcome(int selfEntityId, String resumeSecret, int mode, long contentVersion,
                           long phraseListVersion, long mazeSeed) {
        static Welcome of(byte[] frame) throws IOException {
            assertThat(frame[0] & 0xFF).as("a welcome").isEqualTo(Wire.MSG_WELCOME);
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(frame, 3, frame.length - 3);
            TestClient.readVarint(in);                  // map width
            TestClient.readVarint(in);                  // map height
            int mode = in.read();
            long content = TestClient.readVarint(in);
            long phrases = TestClient.readVarint(in);
            int self = (int) TestClient.readVarint(in);
            int length = (int) TestClient.readVarint(in);
            byte[] secret = in.readNBytes(length);
            long maze = in.available() > 0 ? TestClient.readVarint(in) : -1;
            return new Welcome(self, new String(secret, StandardCharsets.US_ASCII), mode, content, phrases, maze);
        }
    }

    // ---- a match the matcher made (04 §4; D-20) ------------------------------------------------

    /** A place in a made duel, as the matcher issues it. */
    private static String duelTicket(String matchUid, long playerId, int team) throws Exception {
        Ticket t = Ticket.forMatch(playerId, "d-" + playerId, team, matchUid, com.backend.handoff.MatchMode.DUEL.id);
        tickets.issue(t).get(5, TimeUnit.SECONDS);
        return t.id();
    }

    /** A place in a made team-vs-team, on team 1 or 2 (01 §8.4). */
    private static String tvtTicket(String matchUid, long playerId, int team) throws Exception {
        Ticket t = Ticket.forMatch(playerId, "t-" + playerId, team, matchUid, com.backend.handoff.MatchMode.TVT.id);
        tickets.issue(t).get(5, TimeUnit.SECONDS);
        return t.id();
    }

    @Test
    @Timeout(60)
    @DisplayName("a team-vs-team only one team came to is a walkover, however many of it came: at once, all first")
    void aTeamMatchWithOneTeamIsAWalkover() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            server.registry().adjustMade = r -> r.withJoinWindow(25);   // one second; five minutes of play
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(tvtTicket(uid, 9101, 1));
                Welcome.of(readReply(a));
                b.join(tvtTicket(uid, 9102, 1));
                Welcome.of(readReply(b));
                expectKickAfterSnapshots(a, Wire.KICK_MATCH_OVER);
                expectKickAfterSnapshots(b, Wire.KICK_MATCH_OVER);
            }
            assertThat(published).singleElement().satisfies(o -> {
                assertThat(o.mode()).isEqualTo(com.backend.handoff.MatchMode.TVT.id);
                assertThat(o.players()).hasSize(2).allSatisfy(p -> assertThat(p.placement()).isEqualTo(1));
            });
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a team-vs-team two teams came to is played, short-handed, to the clock, and the room closes")
    void aTeamMatchIsPlayedShortHanded() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            server.registry().adjustMade = r -> r.withJoinWindow(25).withDuration(50);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(tvtTicket(uid, 9111, 1));
                assertThat(Welcome.of(readReply(a)).mode()).isEqualTo(com.backend.handoff.MatchMode.TVT.id);
                b.join(tvtTicket(uid, 9112, 2));
                Welcome.of(readReply(b));
                expectKickAfterSnapshots(a, Wire.KICK_MATCH_OVER);
                expectKickAfterSnapshots(b, Wire.KICK_MATCH_OVER);
            }
            assertThat(published).singleElement().satisfies(o -> {
                assertThat(o.mode()).isEqualTo(com.backend.handoff.MatchMode.TVT.id);
                assertThat(o.players()).extracting(MatchOutcome.PlayerOutcome::team).containsExactlyInAnyOrder(1, 2);
                assertThat(o.players()).as("no kills: a draw").allSatisfy(p -> assertThat(p.placement()).isEqualTo(1));
            });
            waitUntil(() -> roomOf(server, uid) == null);
        }
    }

    /** A place in a made domination, on team 1 or 2 (01 §8.7). */
    private static String dominationTicket(String matchUid, long playerId, int team) throws Exception {
        Ticket t = Ticket.forMatch(playerId, "m-" + playerId, team, matchUid,
                com.backend.handoff.MatchMode.DOMINATION.id);
        tickets.issue(t).get(5, TimeUnit.SECONDS);
        return t.id();
    }

    @Test
    @Timeout(150)
    @DisplayName("domination: three dominators in the room, teams placed by what they hold, and all three held for 60 s wins at once (01 §8.7)")
    void dominationIsWonByHoldingAll() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 3_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            // The clock at 110 s: only holding can end it before then.
            server.registry().adjustMade = r -> r.withJoinWindow(25).withDuration(110 * 25);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(dominationTicket(uid, 9301, 1));
                assertThat(Welcome.of(readReply(a)).mode()).isEqualTo(com.backend.handoff.MatchMode.DOMINATION.id);
                b.join(dominationTicket(uid, 9302, 2));
                Welcome.of(readReply(b));
                RoomThread room = roomOf(server, uid);
                java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
                room.tickHook = () -> {
                    // Team 1 takes all three, as capturing them would, and keeps them.
                    var w = room.room().world();
                    int n = 0;
                    for (int i = 0; i < w.tanks.size; i++) {
                        var t = w.entities[w.tanks.items[i]];
                        if (t.alive && t.anchored) {
                            t.team = 1;
                            n++;
                        }
                    }
                    seen.set(n);
                };
                long started = System.nanoTime();
                // b pings too, from another thread, while a is waited on.
                Thread keepB = Thread.ofPlatform().start(() -> {
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            Thread.sleep(5_000);
                            b.ping(System.currentTimeMillis());
                        }
                    } catch (InterruptedException | IOException stopped) {
                        // the kick closed it, or the test is done
                    }
                });
                expectKickWhilePinging(a, Wire.KICK_MATCH_OVER, 3_000);
                keepB.interrupt();
                keepB.join();
                expectKickWhilePinging(b, Wire.KICK_MATCH_OVER, 3_000);
                long seconds = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);
                assertThat(seen.get()).as("three dominators in the room").isEqualTo(3);
                assertThat(seconds).as("ended by the hold, not the clock").isBetween(55L, 100L);
            }
            assertThat(published).singleElement().satisfies(o -> {
                assertThat(o.mode()).isEqualTo(com.backend.handoff.MatchMode.DOMINATION.id);
                assertThat(o.players()).filteredOn(p -> p.team() == 1).singleElement()
                        .satisfies(p -> assertThat(p.placement()).as("no kills, but all three held").isEqualTo(1));
                assertThat(o.players()).filteredOn(p -> p.team() == 2).singleElement()
                        .satisfies(p -> assertThat(p.placement()).isEqualTo(2));
            });
        }
    }

    /** A place in a made tag, on team 1 or 2 (01 §8.8). */
    private static String tagTicket(String matchUid, long playerId, int team) throws Exception {
        Ticket t = Ticket.forMatch(playerId, "g-" + playerId, team, matchUid, com.backend.handoff.MatchMode.TAG.id);
        tickets.issue(t).get(5, TimeUnit.SECONDS);
        return t.id();
    }

    /**
     * Tag, on the room's thread: b is held just in front of a, with almost nothing left, while b
     * is still on team 2; a holds fire along +x from its client. @return b's team once it is not 2
     */
    private static java.util.concurrent.atomic.AtomicInteger tagHook(RoomThread room, long a, long b) {
        java.util.concurrent.atomic.AtomicInteger comeBackAs = new java.util.concurrent.atomic.AtomicInteger(-1);
        room.tickHook = () -> {
            var w = room.room().world();
            com.backend.sim.Entity ta = null;
            com.backend.sim.Entity tb = null;
            for (Connection c : room.connections()) {
                long id = c.identity().playerId();
                if (c.entityId() >= 0 && id == a) {
                    ta = w.entities[c.entityId()];
                } else if (c.entityId() >= 0 && id == b) {
                    tb = w.entities[c.entityId()];
                }
            }
            if (ta == null || tb == null) {
                return;
            }
            // By b's team, not by a death seen: a respawn asked for can follow the death in the
            // same tick, before this hook runs again.
            if (tb.team != 2) {
                comeBackAs.set(tb.team);
                return;
            }
            clearAround(w, ta.x + 90f, ta.y);                    // a shape's kill converts nobody (M-15)
            tb.x = ta.x + 90f;
            tb.y = ta.y;
            tb.protectedUntilTick = 0;
            if (!ta.protectedAt(room.room().tick())) {
                tb.hp = Math.min(tb.hp, 1f);                     // once a's shots count
            }
        };
        return comeBackAs;
    }

    /** Client a holding fire along +x, and client b asking to come back, until stopped. */
    private static Thread fireAndAsk(TestClient a, TestClient b) {
        return Thread.ofPlatform().start(() -> {
            try {
                for (int seq = 1; !Thread.currentThread().isInterrupted(); seq++) {
                    // Aim is a u16 over [-π, π): +x is its middle.
                    a.input(seq, 0, 32_768, ClientMessage.FLAG_AUTOFIRE);
                    b.simple(ClientMessage.RESPAWN);             // dropped while alive
                    Thread.sleep(300);
                }
            } catch (InterruptedException | IOException stopped) {
                // the kick closed it, or the test is done
            }
        });
    }

    @Test
    @Timeout(90)
    @DisplayName("tag: a kill that leaves everyone on one team ends it at once, each placed by the team they started on (01 §8.8)")
    void tagEndsWhenOneTeamHasEveryone() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 3_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            server.registry().adjustMade = r -> r.withJoinWindow(25).withDuration(110 * 25);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(tagTicket(uid, 9401, 1));
                assertThat(Welcome.of(readReply(a)).mode()).isEqualTo(com.backend.handoff.MatchMode.TAG.id);
                b.join(tagTicket(uid, 9402, 2));
                Welcome.of(readReply(b));
                tagHook(roomOf(server, uid), 9401, 9402);
                Thread firing = fireAndAsk(a, b);
                long started = System.nanoTime();
                expectKickWhilePinging(a, Wire.KICK_MATCH_OVER, 1_500);
                firing.interrupt();
                firing.join();
                assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))
                        .as("at once, not at the clock").isLessThan(60L);
            }
            assertThat(published).singleElement().satisfies(o -> {
                assertThat(o.mode()).isEqualTo(com.backend.handoff.MatchMode.TAG.id);
                assertThat(o.players()).filteredOn(p -> p.playerId() == 9401).singleElement().satisfies(p -> {
                    assertThat(p.placement()).isEqualTo(1);
                    assertThat(p.kills()).as("a conversion is a kill").isEqualTo(1);
                    assertThat(p.team()).isEqualTo(1);
                });
                assertThat(o.players()).filteredOn(p -> p.playerId() == 9402).singleElement().satisfies(p -> {
                    assertThat(p.placement()).as("placed by the team b started on").isEqualTo(2);
                    assertThat(p.team()).isEqualTo(2);
                });
            });
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("tag: a player killed by the other team comes back on it, and at the clock the team with more players wins")
    void aTaggedPlayerComesBackOnTheOtherTeam() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 3_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            server.registry().adjustMade = r -> r.withJoinWindow(25).withDuration(20 * 25);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port);
                 TestClient c = new TestClient(port)) {
                a.join(tagTicket(uid, 9411, 1));
                Welcome.of(readReply(a));
                b.join(tagTicket(uid, 9412, 2));
                Welcome.of(readReply(b));
                c.join(tagTicket(uid, 9413, 2));
                Welcome.of(readReply(c));
                java.util.concurrent.atomic.AtomicInteger comeBackAs = tagHook(roomOf(server, uid), 9411, 9412);
                Thread firing = fireAndAsk(a, b);
                Thread keepC = Thread.ofPlatform().start(() -> {
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            Thread.sleep(5_000);
                            c.ping(System.currentTimeMillis());
                        }
                    } catch (InterruptedException | IOException stopped) {
                        // the kick closed it, or the test is done
                    }
                });
                expectKickWhilePinging(a, Wire.KICK_MATCH_OVER, 1_500);
                firing.interrupt();
                keepC.interrupt();
                firing.join();
                keepC.join();
                assertThat(comeBackAs.get()).as("b came back on a's team").isEqualTo(1);
            }
            assertThat(published).singleElement().satisfies(o -> {
                assertThat(o.players()).filteredOn(p -> p.playerId() == 9411).singleElement()
                        .satisfies(p -> assertThat(p.placement()).as("team 1 ended two to one").isEqualTo(1));
                assertThat(o.players()).filteredOn(p -> p.playerId() == 9412).singleElement()
                        .satisfies(p -> assertThat(p.placement()).as("b started on team 2").isEqualTo(2));
                assertThat(o.players()).filteredOn(p -> p.playerId() == 9413).singleElement()
                        .satisfies(p -> assertThat(p.placement()).isEqualTo(2));
            });
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("tag: a player converted by a kill, then resumed while dead, comes back on the team they were converted to (M-17)")
    void aTaggedPlayerResumedStaysConverted() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 3_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            server.registry().adjustMade = r -> r.withJoinWindow(25).withDuration(60 * 25);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port);
                 TestClient c = new TestClient(port)) {
                a.join(tagTicket(uid, 9431, 1));
                Welcome.of(readReply(a));
                b.join(tagTicket(uid, 9432, 2));
                Welcome bw = Welcome.of(readReply(b));
                c.join(tagTicket(uid, 9433, 2));
                Welcome.of(readReply(c));
                RoomThread room = roomOf(server, uid);
                java.util.concurrent.atomic.AtomicBoolean dead = new java.util.concurrent.atomic.AtomicBoolean();
                room.tickHook = () -> {
                    var w = room.room().world();
                    com.backend.sim.Entity ta = null;
                    Connection cb = null;
                    for (Connection conn : room.connections()) {
                        if (conn.identity().playerId() == 9431 && conn.entityId() >= 0) {
                            ta = w.entities[conn.entityId()];
                        } else if (conn.identity().playerId() == 9432) {
                            cb = conn;
                        }
                    }
                    if (ta == null || cb == null) {
                        return;
                    }
                    if (cb.entityId() < 0) {
                        dead.set(true);                               // killed by a, so converted; not respawned
                        return;
                    }
                    com.backend.sim.Entity tb = w.entities[cb.entityId()];
                    clearAround(w, ta.x + 90f, ta.y);
                    tb.x = ta.x + 90f;
                    tb.y = ta.y;
                    tb.protectedUntilTick = 0;
                    if (!ta.protectedAt(room.room().tick())) {
                        tb.hp = Math.min(tb.hp, 1f);
                    }
                };
                Thread firing = Thread.ofPlatform().start(() -> {
                    try {
                        for (int seq = 1; !Thread.currentThread().isInterrupted(); seq++) {
                            a.input(seq, 0, 32_768, ClientMessage.FLAG_AUTOFIRE);
                            Thread.sleep(300);
                        }
                    } catch (InterruptedException | IOException stopped) {
                        // the test is done
                    }
                });
                waitUntil(dead::get);
                firing.interrupt();
                firing.join();
                room.tickHook = null;
                try (TestClient back = new TestClient(port)) {
                    back.resume(bw.resumeSecret());                   // a new connection, the old one taken over
                    Welcome.of(readReply(back));
                    java.util.concurrent.atomic.AtomicInteger team = new java.util.concurrent.atomic.AtomicInteger(-1);
                    java.util.concurrent.CountDownLatch read = new java.util.concurrent.CountDownLatch(1);
                    room.tickHook = () -> {
                        for (Connection conn : room.connections()) {
                            if (conn.identity().playerId() == 9432 && conn.entityId() >= 0) {
                                team.set(room.room().world().entities[conn.entityId()].team);
                                room.tickHook = null;
                                read.countDown();
                                return;
                            }
                        }
                    };
                    assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(team.get()).as("on a's team, where the kill put them, not their ticket's").isEqualTo(1);
                }
            }
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("tag: placed by the players each team has at the end, not by kills (01 §8.8)")
    void tagIsPlacedByHeadsNotKills() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 3_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            server.registry().adjustMade = r -> r.withJoinWindow(25).withDuration(110 * 25);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port);
                 TestClient c = new TestClient(port)) {
                a.join(tagTicket(uid, 9421, 1));
                Welcome.of(readReply(a));
                b.join(tagTicket(uid, 9422, 2));
                Welcome.of(readReply(b));
                c.join(tagTicket(uid, 9423, 2));
                Welcome.of(readReply(c));
                RoomThread room = roomOf(server, uid);
                // a takes b; then b, on team 1, takes c: everyone on team 1, and one kill a side. The
                // stage is read from the teams, as a respawn can follow a death within one tick.
                room.tickHook = () -> {
                    var w = room.room().world();
                    java.util.Map<Long, com.backend.sim.Entity> tanks = new java.util.HashMap<>();
                    for (Connection conn : room.connections()) {
                        if (conn.entityId() >= 0) {
                            tanks.put(conn.identity().playerId(), w.entities[conn.entityId()]);
                        }
                    }
                    com.backend.sim.Entity ta = tanks.get(9421L);
                    com.backend.sim.Entity tb = tanks.get(9422L);
                    com.backend.sim.Entity tc = tanks.get(9423L);
                    com.backend.sim.Entity shooter = null;
                    com.backend.sim.Entity victim = null;
                    if (ta != null && tb != null && tb.team == 2) {
                        shooter = ta;
                        victim = tb;
                    } else if (tb != null && tb.team == 1 && tc != null && tc.team == 2) {
                        shooter = tb;
                        victim = tc;
                    }
                    if (shooter != null) {
                        // Nothing but the shooter may kill it: a shape's kill converts nobody (M-15).
                        clearAround(w, shooter.x + 90f, shooter.y);
                        victim.x = shooter.x + 90f;
                        victim.y = shooter.y;
                        victim.protectedUntilTick = 0;
                        if (!shooter.protectedAt(room.room().tick())) {
                            victim.hp = Math.min(victim.hp, 1f);   // once the shooter's shots count
                        }
                    }
                };
                Thread firing = fireAndAsk(a, b);
                Thread bFires = Thread.ofPlatform().start(() -> {
                    try {
                        for (int seq = 1; !Thread.currentThread().isInterrupted(); seq++) {
                            b.input(seq, 0, 32_768, ClientMessage.FLAG_AUTOFIRE);
                            c.ping(System.currentTimeMillis());
                            Thread.sleep(300);
                        }
                    } catch (InterruptedException | IOException stopped) {
                        // the kick closed it, or the test is done
                    }
                });
                expectKickWhilePinging(a, Wire.KICK_MATCH_OVER, 1_500);
                firing.interrupt();
                bFires.interrupt();
                firing.join();
                bFires.join();
            }
            assertThat(published).singleElement().satisfies(o -> {
                assertThat(o.players()).extracting(MatchOutcome.PlayerOutcome::kills).as("one kill a side")
                        .containsExactlyInAnyOrder(1, 1, 0);
                assertThat(o.players()).filteredOn(p -> p.playerId() == 9421).singleElement()
                        .satisfies(p -> assertThat(p.placement()).as("team 1 has everyone").isEqualTo(1));
                assertThat(o.players()).filteredOn(p -> p.team() == 2)
                        .allSatisfy(p -> assertThat(p.placement()).as("by heads, not the tied kills").isEqualTo(2));
            });
        }
    }

    /**
     * Every kill a client is told of for a while, as {@code killer>victim}, its frames applied as
     * they come; and whether it was told of its own death meanwhile.
     */
    private static List<String> killsTold(TestClient c, ClientWorld w, long millis, boolean[] died) throws IOException {
        List<String> kills = new ArrayList<>();
        long end = System.nanoTime() + millis * 1_000_000L;
        c.soTimeout(200);
        while (System.nanoTime() < end) {
            try {
                if (!applyNext(c, w)) {
                    continue;
                }
            } catch (java.net.SocketTimeoutException quiet) {
                continue;
            }
            for (var e : w.events()) {
                if (e.type() == Wire.EVT_DEATH && died != null) {
                    died[0] = true;
                }
                if (e.type() == Wire.EVT_KILL) {
                    java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(e.payload());
                    String killer = new String(in.readNBytes(in.read()), StandardCharsets.UTF_8);
                    String victim = new String(in.readNBytes(in.read()), StandardCharsets.UTF_8);
                    kills.add(killer + ">" + victim);
                }
            }
        }
        c.soTimeout(5_000);
        return kills;
    }

    /** On the room thread: b's tank in front of a's, its protection gone and, once a can hurt it, at one point. */
    private static Runnable inFrontOf(RoomThread room, long a, long b) {
        return () -> {
            var w = room.room().world();
            com.backend.sim.Entity ta = null;
            com.backend.sim.Entity tb = null;
            for (Connection conn : room.connections()) {
                if (conn.entityId() >= 0) {
                    if (conn.identity().playerId() == a) {
                        ta = w.entities[conn.entityId()];
                    } else if (conn.identity().playerId() == b) {
                        tb = w.entities[conn.entityId()];
                    }
                }
            }
            if (ta != null && tb != null) {
                clearAround(w, ta.x + 90f, ta.y);
                tb.x = ta.x + 90f;
                tb.y = ta.y;
                tb.protectedUntilTick = 0;
                if (!ta.protectedAt(room.room().tick())) {
                    tb.hp = Math.min(tb.hp, 1f);
                }
            }
        };
    }

    @Test
    @Timeout(60)
    @DisplayName("the kill feed in a made match: every player in it is told who killed whom (01 §9, Q-34)")
    void aMatchsKillsAreToldToEveryone() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(sandboxTicket(uid, 9701));
                Welcome.of(readReply(a));
                b.join(sandboxTicket(uid, 9702));
                Welcome.of(readReply(b));
                RoomThread room = roomOf(server, uid);
                room.tickHook = inFrontOf(room, 9701, 9702);
                Thread firing = fireAndAsk(a, b);
                List<String> toldA = killsTold(a, new ClientWorld(), 6_000, null);
                firing.interrupt();
                firing.join();
                room.tickHook = null;
                List<String> toldB = killsTold(b, new ClientWorld(), 1_000, null);
                assertThat(toldA).as("the killer is told").contains("s-9701>s-9702");
                assertThat(toldB).as("and so is the victim: it is the match's feed").contains("s-9701>s-9702");
                assertThat(toldA).as("only tanks: no shape broken is in it").allMatch(k -> k.equals("s-9701>s-9702"));
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("the kill feed in the public arena: the killer alone is told; the victim has its death, and nobody else hears")
    void aPublicKillIsToldToItsKillerOnly() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            int port = server.start(0);
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port);
                 TestClient c = new TestClient(port)) {
                a.join(ticketFor(9711));
                Welcome.of(readReply(a));
                b.join(ticketFor(9712));
                Welcome.of(readReply(b));
                c.join(ticketFor(9713));
                Welcome.of(readReply(c));
                RoomThread room = server.registry().rooms().get(0);
                room.tickHook = inFrontOf(room, 9711, 9712);
                Thread firing = fireAndAsk(a, b);
                List<String> toldA = killsTold(a, new ClientWorld(), 6_000, null);
                firing.interrupt();
                firing.join();
                room.tickHook = null;
                boolean[] died = {false};
                List<String> toldB = killsTold(b, new ClientWorld(), 1_000, died);
                List<String> toldC = killsTold(c, new ClientWorld(), 1_000, null);
                assertThat(toldA).as("the killer is told").contains("p-9711>p-9712");
                assertThat(died[0]).as("the victim is told of its death").isTrue();
                assertThat(toldB).as("by its death, not the feed").isEmpty();
                assertThat(toldC).as("a bystander hears nothing").isEmpty();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("the kill feed is of tanks: a shape broken is told to nobody")
    void aShapeBrokenIsNotInTheFeed() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            int port = server.start(0);
            try (TestClient a = new TestClient(port)) {
                a.join(ticketFor(9721));
                Welcome.of(readReply(a));
                RoomThread room = server.registry().rooms().get(0);
                java.util.concurrent.atomic.AtomicInteger xp = new java.util.concurrent.atomic.AtomicInteger();
                room.tickHook = () -> {
                    var w = room.room().world();
                    for (Connection conn : room.connections()) {
                        if (conn.entityId() < 0) {
                            continue;
                        }
                        var ta = w.entities[conn.entityId()];
                        xp.set(w.tankStats[ta.id].xp);
                        boolean ahead = false;
                        for (int i = 0; i < w.shapes.size; i++) {
                            var s = w.entities[w.shapes.items[i]];
                            ahead |= s.alive && Math.abs(s.y - ta.y) < 5f && s.x > ta.x && s.x < ta.x + 200f;
                        }
                        if (!ahead && xp.get() == 0) {
                            var s = room.room().spawnShape();
                            s.x = ta.x + 90f;
                            s.y = ta.y;
                            s.vx = 0f;
                            s.vy = 0f;
                            s.hp = 1f;
                        }
                    }
                };
                Thread firing = Thread.ofPlatform().start(() -> {
                    try {
                        for (int seq = 1; !Thread.currentThread().isInterrupted(); seq++) {
                            a.input(seq, 0, 32_768, ClientMessage.FLAG_AUTOFIRE);
                            Thread.sleep(300);
                        }
                    } catch (InterruptedException | IOException stopped) {
                        // the test is done
                    }
                });
                List<String> told = killsTold(a, new ClientWorld(), 6_000, null);
                firing.interrupt();
                firing.join();
                room.tickHook = null;
                assertThat(xp.get()).as("a shape was broken: the tank was paid for it").isPositive();
                assertThat(told).as("and nobody was told of it").isEmpty();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a player sees another player's tank by name, in its create (02 §4, P-35)")
    void aTankIsSeenByItsPlayersName() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            int port = server.start(0);
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(ticketFor(9731));
                Welcome.of(readReply(a));
                b.join(ticketFor(9732));
                Welcome.of(readReply(b));
                RoomThread room = server.registry().rooms().get(0);
                room.tickHook = inFrontOf(room, 9731, 9732);         // in a's view
                ClientWorld w = new ClientWorld();
                boolean seen = false;
                long end = System.nanoTime() + 10_000_000_000L;
                while (!seen && System.nanoTime() < end) {
                    if (!applyNext(a, w)) {
                        continue;
                    }
                    for (int h = 1; h < Wire.MAX_HANDLES && !seen; h++) {
                        ClientWorld.Entity e = w.entity(h);
                        seen = e.alive && e.kind == Wire.KIND_TANK && "p-9732".equals(e.name);
                    }
                }
                room.tickHook = null;
                assertThat(seen).isTrue();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a tank made again for a resume past its grace carries its player's name too")
    void aResumedTankKeepsItsName() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test").withResume(25, 25 * 10), o -> { })) {
            int port = server.start(0);
            try (TestClient a = new TestClient(port)) {
                a.join(ticketFor(9741));
                Welcome.of(readReply(a));
                TestClient b = new TestClient(port);
                b.join(ticketFor(9742));
                String secret = Welcome.of(readReply(b)).resumeSecret();
                b.close();                                       // lost, not left
                Thread.sleep(2_000);                             // past the second of grace: the tank is gone
                try (TestClient back = new TestClient(port)) {
                    back.resume(secret);
                    Welcome.of(readReply(back));
                    RoomThread room = server.registry().rooms().get(0);
                    room.tickHook = inFrontOf(room, 9741, 9742);
                    ClientWorld w = new ClientWorld();
                    boolean seen = false;
                    long end = System.nanoTime() + 10_000_000_000L;
                    while (!seen && System.nanoTime() < end) {
                        if (!applyNext(a, w)) {
                            continue;
                        }
                        for (int h = 1; h < Wire.MAX_HANDLES && !seen; h++) {
                            ClientWorld.Entity e = w.entity(h);
                            seen = e.alive && e.kind == Wire.KIND_TANK && "p-9742".equals(e.name);
                        }
                    }
                    room.tickHook = null;
                    assertThat(seen).isTrue();
                }
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a tank respawned after a death carries its player's name too (P-36)")
    void aRespawnedTankKeepsItsName() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            int port = server.start(0);
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(ticketFor(9751));
                Welcome.of(readReply(a));
                b.join(ticketFor(9752));
                Welcome.of(readReply(b));
                RoomThread room = server.registry().rooms().get(0);
                java.util.concurrent.atomic.AtomicInteger firstTank = new java.util.concurrent.atomic.AtomicInteger(-1);
                java.util.concurrent.atomic.AtomicInteger respawned = new java.util.concurrent.atomic.AtomicInteger(-1);
                java.util.concurrent.atomic.AtomicBoolean sawDead = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.concurrent.atomic.AtomicInteger respawnTick = new java.util.concurrent.atomic.AtomicInteger();
                Runnable inFront = inFrontOf(room, 9751, 9752);
                room.tickHook = () -> {
                    for (Connection conn : room.connections()) {
                        if (conn.identity().playerId() != 9752) {
                            continue;
                        }
                        if (conn.entityId() < 0) {
                            sawDead.set(firstTank.get() >= 0);
                        } else if (firstTank.get() < 0) {
                            firstTank.set(conn.entityId());
                            room.room().world().kill(room.room().world().entities[conn.entityId()]);   // dead
                        } else if (sawDead.get() && respawned.get() < 0) {
                            respawned.set(conn.entityId());      // the slot may be the same one
                            respawnTick.set(room.room().tick());
                        }
                    }
                    if (respawned.get() >= 0) {
                        inFront.run();                       // the new tank, in a's view
                    }
                };
                waitUntil(sawDead::get);
                b.simple(ClientMessage.RESPAWN);
                waitUntil(() -> respawned.get() >= 0);
                boolean named = false;
                ClientWorld w = new ClientWorld();
                long end = System.nanoTime() + 10_000_000_000L;
                a.soTimeout(200);
                while (!named && System.nanoTime() < end) {
                    byte[] frame;
                    try {
                        frame = a.readFrame();
                    } catch (java.net.SocketTimeoutException quiet) {
                        continue;
                    }
                    if ((frame[0] & 0xFF) != Wire.MSG_SNAPSHOT) {
                        continue;
                    }
                    a.serverTick = (int) w.apply(frame);
                    if (w.serverTick() < respawnTick.get()) {
                        continue;                            // from before the death: the old tank's
                    }
                    for (SnapshotReader.Create c : new SnapshotReader().decode(frame, 0, frame.length).creates()) {
                        named |= c.kind() == Wire.KIND_TANK && "p-9752".equals(c.name());
                    }
                }
                room.tickHook = null;
                assertThat(named).as("the new tank's create names its player").isTrue();
            }
        }
    }

    /**
     * Two lives of one player, each grown to {@code xp[i]} experience and then killed; returns the
     * experience each respawned tank started with. On the room thread, through its hook.
     */
    private static int[] respawnedWith(RoomThread room, TestClient c, long playerId, int[] grow) throws Exception {
        int[] startedWith = new int[grow.length];
        java.util.concurrent.atomic.AtomicInteger life = new java.util.concurrent.atomic.AtomicInteger();   // 0: the first
        java.util.concurrent.atomic.AtomicBoolean dead = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean grown = new java.util.concurrent.atomic.AtomicBoolean();
        // Counted, not watched: the next life is grown and killed a tick after it comes back, so
        // "alive" lasts one tick, which a waiting thread can miss.
        java.util.concurrent.atomic.AtomicInteger back = new java.util.concurrent.atomic.AtomicInteger();
        room.tickHook = () -> {
            var w = room.room().world();
            for (Connection conn : room.connections()) {
                if (conn.identity().playerId() != playerId) {
                    continue;
                }
                int id = conn.entityId();
                if (id < 0) {
                    dead.set(true);
                    continue;
                }
                if (dead.get()) {                           // back: what it started with
                    startedWith[life.get() - 1] = w.tankStats[id].xp;
                    dead.set(false);
                    grown.set(false);
                    back.incrementAndGet();
                }
                if (!grown.get() && life.get() < grow.length) {
                    room.room().grantExperience(w.entities[id], grow[life.get()] - w.tankStats[id].xp);
                    w.kill(w.entities[id]);
                    grown.set(true);
                    life.incrementAndGet();
                }
            }
        };
        for (int i = 0; i < grow.length; i++) {
            final int lives = i + 1;
            waitUntil(() -> life.get() == lives && dead.get());
            c.simple(ClientMessage.RESPAWN);
            waitUntil(() -> back.get() == lives);
        }
        room.tickHook = null;
        return startedWith;
    }

    @Test
    @Timeout(60)
    @DisplayName("the rebate: a respawn in the public arena starts with a quarter of the last life's experience, up to level 20's (01 §7, Q-36)")
    void aPublicRespawnKeepsAQuarter() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            int port = server.start(0);
            try (TestClient a = new TestClient(port)) {
                a.join(ticketFor(9761));
                Welcome.of(readReply(a));
                RoomThread room = server.registry().rooms().get(0);
                com.backend.sim.LevelTable levels = room.room().content().levels();
                int small = levels.xpRequired(10);
                int large = levels.xpRequired(levels.maxLevel());
                int[] started = respawnedWith(room, a, 9761, new int[] {small, large});
                assertThat(started[0]).as("a quarter of a level-10 life").isEqualTo(small / 4);
                assertThat(started[1]).as("a quarter of a level-45 life is more than level 20 needs: level 20's")
                        .isEqualTo(Math.min(large / 4, levels.xpRequired(20))).isLessThan(large / 4);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("the rebate is the public arena's: a respawn in a made match starts at level 1")
    void aMatchRespawnStartsAtLevelOne() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port)) {
                a.join(sandboxTicket(uid, 9771));
                Welcome.of(readReply(a));
                RoomThread room = roomOf(server, uid);
                int[] started = respawnedWith(room, a, 9771, new int[] {room.room().content().levels().xpRequired(10)});
                assertThat(started[0]).isZero();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("co-op: a wave cleared adds 100 to the score of each player still in the match, a stay waiting too, and none to one gone (01 §8.5, Q-37)")
    @SuppressWarnings("try")        // b's close mid-match, a connection lost rather than left, is the test
    void aWaveClearedPays() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            // The first wave comes 5 s in and is cleared at once; the clock ends it before the second.
            server.registry().adjustMade = r -> r.withJoinWindow(25).withDuration(Waves.DELAY_TICKS * 2 - 25);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port);
                 TestClient c = new TestClient(port)) {
                a.join(coopTicket(uid, 9781));
                Welcome.of(readReply(a));
                b.join(coopTicket(uid, 9782));
                Welcome.of(readReply(b));
                c.join(coopTicket(uid, 9783));
                Welcome.of(readReply(c));
                RoomThread room = roomOf(server, uid);
                java.util.concurrent.atomic.AtomicBoolean cleared = new java.util.concurrent.atomic.AtomicBoolean();
                room.tickHook = () -> {
                    var w = room.room().world();
                    for (Connection conn : room.connections()) {
                        if (conn.entityId() >= 0) {
                            w.entities[conn.entityId()].hp = w.entities[conn.entityId()].maxHp;   // nobody dies
                        }
                    }
                    if (cleared.get()) {
                        return;
                    }
                    for (int i = 0; i < w.tanks.size; i++) {
                        var t = w.entities[w.tanks.items[i]];
                        if (t.alive && t.hunts) {
                            w.kill(t);                               // the first wave, all of it, as it comes
                            cleared.set(true);
                        }
                    }
                };
                c.simple(ClientMessage.LEAVE);                       // gone before any wave
                b.close();                                           // lost, not left: its stay waits, and counts
                expectKickWhilePinging(a, Wire.KICK_MATCH_OVER, 1_500);
                room.tickHook = null;
            }
            waitUntil(() -> published.size() == 1);
            assertThat(published.get(0).players()).allSatisfy(p -> assertThat(p.score())
                    .as("player %d", p.playerId()).isEqualTo(p.playerId() == 9783 ? 0 : 100));
        }
    }

    /** One labelled series of a histogram as a scrape renders it: {@code [count, sum]}. */
    private static double[] scraped(RoomRegistry registry, String profile) {
        com.backend.common.Metrics m = new com.backend.common.Metrics();
        m.labeledHistogram("r", "rates", "profile", registry.connectionRates);
        double count = 0;
        double sum = 0;
        for (String line : m.render().split("\n")) {
            if (line.startsWith("r_count{profile=\"" + profile + "\"}")) {
                count = Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            } else if (line.startsWith("r_sum{profile=\"" + profile + "\"}")) {
                sum = Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return new double[] {count, sum};
    }

    @Test
    @Timeout(60)
    @DisplayName("NFR-2 per connection: as a connection ends, its own bytes a second, if it lasted the minimum (02 §13)")
    void aConnectionsRateIsObserved() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            int port = server.start(0);
            server.registry().rateMinMillis = 1_000;                // a second, not a minute
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(ticketFor(9791));
                Welcome.of(readReply(a));
                long received = 0;
                long end = System.nanoTime() + 2_000_000_000L;
                while (System.nanoTime() < end) {
                    received += a.readFrame().length;
                }
                a.simple(ClientMessage.LEAVE);
                waitUntil(() -> scraped(server.registry(), "mobile")[0] == 1);
                double rate = scraped(server.registry(), "mobile")[1];
                assertThat(rate).as("bytes a second, about what the client read over its two seconds (%d bytes)", received)
                        .isBetween(received / 2.0 / 2, received / 2.0 * 2);

                b.join(ticketFor(9792));
                Welcome.of(readReply(b));
                long until = System.nanoTime() + 300_000_000L;      // frames, but under the second
                while (System.nanoTime() < until) {
                    b.readFrame();
                }
                b.simple(ClientMessage.LEAVE);
                Thread.sleep(500);
                assertThat(scraped(server.registry(), "mobile")[0]).as("too short to count").isEqualTo(1);
            }
        }
    }

    /** A place in a made maze (01 §8.9). */
    private static String mazeTicket(String matchUid, long playerId) throws Exception {
        Ticket t = Ticket.forMatch(playerId, "z-" + playerId, 0, matchUid, com.backend.handoff.MatchMode.MAZE.id);
        tickets.issue(t).get(5, TimeUnit.SECONDS);
        return t.id();
    }

    @Test
    @Timeout(60)
    @DisplayName("maze: the room has the maze its Welcome names by seed, the match's own, and nobody is placed in a wall (01 §8.9, D-48)")
    void aMazeIsNamedByItsSeed() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 3_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            server.registry().adjustMade = r -> r.withJoinWindow(25).withDuration(25 * 20);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(mazeTicket(uid, 9501));
                Welcome wa = Welcome.of(readReply(a));
                assertThat(wa.mode()).isEqualTo(com.backend.handoff.MatchMode.MAZE.id);
                assertThat(wa.mazeSeed()).as("the match's own seed").isEqualTo(RoomThread.mazeSeedOf(uid)).isNotZero();
                b.join(mazeTicket(uid, 9502));
                assertThat(Welcome.of(readReply(b)).mazeSeed()).as("the same for everyone in it").isEqualTo(wa.mazeSeed());
                RoomThread room = roomOf(server, uid);
                var walls = room.room().walls();
                assertThat(walls).isNotNull();
                assertThat(walls.all()).isEqualTo(com.backend.sim.MazeGenerator.walls(wa.mazeSeed()));
                java.util.concurrent.atomic.AtomicBoolean inWall = new java.util.concurrent.atomic.AtomicBoolean();
                java.util.concurrent.atomic.AtomicInteger checked = new java.util.concurrent.atomic.AtomicInteger();
                room.tickHook = () -> {
                    var w = room.room().world();
                    for (Connection c : room.connections()) {
                        if (c.entityId() >= 0) {
                            var t = w.entities[c.entityId()];
                            inWall.compareAndSet(false, walls.hits(t.x, t.y, t.radius - 0.01f));
                            checked.incrementAndGet();
                        }
                    }
                };
                waitUntil(() -> checked.get() >= 20);
                assertThat(inWall.get()).as("nobody in a wall").isFalse();
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a room with no maze names none: its Welcome's seed is 0")
    void noMazeIsSeedZero() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            server.registry().adjustMade = r -> r.withJoinWindow(25);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port)) {
                a.join(duelTicket(uid, 9511, 1));
                assertThat(Welcome.of(readReply(a)).mazeSeed()).isZero();
                assertThat(roomOf(server, uid).room().walls()).isNull();
            }
        }
    }

    /** Moves every shape off a spot a test pins a tank to: nothing but the wave may kill it there (M-15). */
    private static void clearAround(com.backend.sim.World w, float x, float y) {
        for (int i = 0; i < w.shapes.size; i++) {
            var s = w.entities[w.shapes.items[i]];
            if (Math.abs(s.x - x) < 250f && Math.abs(s.y - y) < 250f) {
                s.y = s.y < y ? y - 600f : y + 600f;
            }
        }
    }

    /** A place in a made co-op, on the players' team, 1 (01 §8.5). */
    private static String coopTicket(String matchUid, long playerId) throws Exception {
        Ticket t = Ticket.forMatch(playerId, "c-" + playerId, 1, matchUid, com.backend.handoff.MatchMode.COOP.id);
        tickets.issue(t).get(5, TimeUnit.SECONDS);
        return t.id();
    }

    @Test
    @Timeout(90)
    @DisplayName("co-op: one who came plays, no walkover; the first wave hunts them down, nobody comes back on asking, and the wipe ends it (01 §8.5)")
    void aCoopMatchEndsInAWipe() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            server.registry().adjustMade = r -> r.withJoinWindow(25);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port)) {
                a.join(coopTicket(uid, 9201));
                assertThat(Welcome.of(readReply(a)).mode()).isEqualTo(com.backend.handoff.MatchMode.COOP.id);
                a.soTimeout(30_000);                                // dead, it is sent events only
                RoomThread room = roomOf(server, uid);
                // Where the wave will come, and one hit from dying: the wave need not cross the map.
                room.tickHook = () -> {
                    clearAround(room.room().world(), 2_600f, 1_500f);
                    Connection c = room.connections().stream().findFirst().orElse(null);
                    if (c != null && c.entityId() >= 0) {
                        var t = room.room().world().entities[c.entityId()];
                        t.x = 2_600f;
                        t.y = 1_500f;
                        t.vx = t.vy = 0f;
                        t.protectedUntilTick = 0;
                        t.hp = Math.min(t.hp, 0.5f);
                    }
                };
                boolean over = false;
                for (int seq = 1; seq < 600 && !over; seq++) {
                    try {
                        a.simple(ClientMessage.RESPAWN);                 // refused in co-op
                    } catch (IOException closed) {
                        // read the kick below
                    }
                    byte[] f = a.readFrame();
                    over = (f[0] & 0xFF) == Wire.MSG_KICK && (f[1] & 0xFF) == Wire.KICK_MATCH_OVER;
                }
                assertThat(over).as("told the match is over").isTrue();
            }
            assertThat(published).singleElement().satisfies(o -> {
                assertThat(o.mode()).isEqualTo(com.backend.handoff.MatchMode.COOP.id);
                assertThat(o.players()).singleElement().satisfies(p -> {
                    assertThat(p.placement()).isEqualTo(1);
                    assertThat(p.deaths()).as("once: asking did not bring it back").isEqualTo(1);
                    assertThat(p.team()).isEqualTo(1);
                });
            });
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("co-op: the dead come back at the next wave, and it is a wipe only when none of the team lives (01 §8.5)")
    void theDeadComeBackAtTheNextWave() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            server.registry().adjustMade = r -> r.withJoinWindow(25);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(coopTicket(uid, 9211));
                Welcome.of(readReply(a));
                b.join(coopTicket(uid, 9212));
                Welcome.of(readReply(b));
                // A player dead until the next wave is sent nothing but events: silent for the five
                // seconds between waves and more, which a five-second read took for a dead server.
                a.soTimeout(30_000);
                b.soTimeout(30_000);
                RoomThread room = roomOf(server, uid);
                // 0: A dies to the first wave while B lives far off; then the wave is cleared.
                // 1: waiting for the second wave to bring A back. 2: B goes to die too.
                java.util.concurrent.atomic.AtomicInteger phase = new java.util.concurrent.atomic.AtomicInteger();
                room.tickHook = () -> {
                    var w = room.room().world();
                    clearAround(w, 2_600f, 1_600f);
                    clearAround(w, 300f, 1_500f);
                    Connection ca = null;
                    Connection cb = null;
                    for (Connection c : room.connections()) {
                        if (c.identity().playerId() == 9211) {
                            ca = c;
                        } else {
                            cb = c;
                        }
                    }
                    if (ca == null || cb == null) {
                        return;
                    }
                    if (ca.entityId() >= 0) {
                        var ta = w.entities[ca.entityId()];
                        ta.x = 2_600f;
                        ta.y = 1_500f;
                        ta.vx = ta.vy = 0f;
                        ta.protectedUntilTick = 0;
                        ta.hp = Math.min(ta.hp, 0.5f);
                        if (phase.get() == 1) {
                            phase.set(2);                        // back at the second wave
                        }
                    } else if (phase.get() == 0) {
                        // Dead to the first wave, and not before it: dead before the match began, it
                        // is put back into the world at the start, which is not coming back at a wave.
                        boolean wave = false;
                        for (int i = 0; i < w.tanks.size; i++) {
                            var t = w.entities[w.tanks.items[i]];
                            wave |= t.alive && t.hunts;
                            t.alive &= !t.hunts;
                        }
                        if (wave) {
                            phase.set(1);                        // the first wave, cleared
                        }
                    }
                    if (cb.entityId() >= 0) {
                        var tb = w.entities[cb.entityId()];
                        tb.vx = tb.vy = 0f;
                        if (phase.get() == 2) {
                            tb.x = 2_600f;
                            tb.y = 1_700f;
                            tb.protectedUntilTick = 0;
                            tb.hp = Math.min(tb.hp, 0.5f);
                        } else {
                            tb.x = 300f;
                            tb.y = 1_500f;
                            tb.hp = tb.maxHp;
                        }
                    }
                };
                boolean overA = false;
                boolean overB = false;
                for (int i = 0; i < 2_000 && !(overA && overB); i++) {
                    if (!overA) {
                        byte[] f = a.readFrame();
                        overA = (f[0] & 0xFF) == Wire.MSG_KICK && (f[1] & 0xFF) == Wire.KICK_MATCH_OVER;
                    }
                    if (!overB) {
                        byte[] f = b.readFrame();
                        overB = (f[0] & 0xFF) == Wire.MSG_KICK && (f[1] & 0xFF) == Wire.KICK_MATCH_OVER;
                    }
                }
                assertThat(overA && overB).as("both told the match is over").isTrue();
                assertThat(phase.get()).as("A came back at the second wave").isEqualTo(2);
            }
            assertThat(published).singleElement().satisfies(o -> {
                MatchOutcome.PlayerOutcome pa = o.players().stream().filter(p -> p.playerId() == 9211).findFirst().orElseThrow();
                MatchOutcome.PlayerOutcome pb = o.players().stream().filter(p -> p.playerId() == 9212).findFirst().orElseThrow();
                assertThat(pa.deaths()).as("died, came back, died").isEqualTo(2);
                assertThat(pb.deaths()).isEqualTo(1);
            });
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("draining: a public room's player is sent back at once, no new room nor public player is taken, and a made duel plays on to its end (01 §8.6)")
    void aDrainLetsMatchesEnd() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            server.registry().adjustMade = r -> r.withDuration(250);      // ten seconds of play
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient pub = new TestClient(port); TestClient a = new TestClient(port);
                 TestClient b = new TestClient(port)) {
                pub.join(issueTicket(0));
                Welcome.of(readReply(pub));
                a.join(duelTicket(uid, 9302, 0));
                Welcome.of(readReply(a));
                b.join(duelTicket(uid, 9303, 0));
                Welcome.of(readReply(b));

                java.util.concurrent.CompletableFuture<Boolean> drained = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return server.registry().drain(30_000);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                });
                expectKickAfterSnapshots(pub, Wire.KICK_INTERNAL);          // back to the lobby, at once
                try (TestClient late = new TestClient(port)) {
                    late.join(duelTicket(com.backend.handoff.Ulid.generate(), 9304, 0));
                    expectKick(late, Wire.KICK_ROOM_FULL);                  // no new match here
                }
                try (TestClient late = new TestClient(port)) {
                    late.join(issueTicket(0));
                    expectKick(late, Wire.KICK_ROOM_FULL);                  // nor a new public player
                }
                assertThat(drained).as("the duel is still on").isNotDone();
                assertThat(server.joins().get("no_room")).as("both refused, counted").isEqualTo(2);
                expectKickAfterSnapshots(a, Wire.KICK_MATCH_OVER);
                expectKickAfterSnapshots(b, Wire.KICK_MATCH_OVER);
                assertThat(drained.get(10, TimeUnit.SECONDS)).as("drained: every made match ended").isTrue();
            }
            assertThat(published).filteredOn(o -> o.kind() == MatchOutcome.KIND_TIMED).singleElement()
                    .satisfies(o -> {
                        assertThat(o.matchUid()).isEqualTo(uid);
                        assertThat(o.players()).allSatisfy(p -> assertThat(p.placement()).isEqualTo(1));
                        assertThat(o.cutShort()).as("it ended by its clock").isFalse();
                    });
            assertThat(published).filteredOn(o -> o.kind() == MatchOutcome.KIND_OPEN).as("the public player's stay").hasSize(1);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a drain that runs out cuts short the made match still playing, and says so in its result (D-29)")
    @SuppressWarnings("try")        // the server's close, with both players still on, is the test
    void aDrainThatRunsOutCutsShort() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            int port = server.start(0);                                   // the duel's three minutes
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(duelTicket(uid, 9311, 0));
                Welcome.of(readReply(a));
                b.join(duelTicket(uid, 9312, 0));
                Welcome.of(readReply(b));
                waitUntil(() -> published.isEmpty() && roomOf(server, uid) != null);
                assertThat(server.registry().drain(500)).as("still playing").isFalse();
                server.close();
            }
            assertThat(published).singleElement().satisfies(o -> {
                assertThat(o.matchUid()).isEqualTo(uid);
                assertThat(o.cutShort()).isTrue();
            });
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("an operator closes a room: its players are sent back with Kick(7), removed, and their stays published (04 §10)")
    void anOperatorClosesARoom() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            int port = server.start(0);
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(issueTicket(0));
                Welcome.of(readReply(a));
                b.join(issueTicket(0));
                Welcome.of(readReply(b));
                String room = server.registry().views().get(0).room();
                assertThat(server.registry().closeRoom(room, Wire.KICK_REMOVED)).isTrue();
                assertThat(server.registry().closeRoom("room-99", Wire.KICK_REMOVED)).as("no such room").isFalse();
                expectKickAfterSnapshots(a, Wire.KICK_REMOVED);
                expectKickAfterSnapshots(b, Wire.KICK_REMOVED);
            }
            assertThat(published).as("both stays").hasSize(2);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("an operator removes a player, by the arena's channel: they alone get Kick(7), their stay published; the room plays on")
    void anOperatorRemovesAPlayer() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add);
             com.jredis.client.JRedisClient client = store.newClient()) {
            int port = server.start(0);
            com.backend.handoff.ArenaDirectory directory = new com.backend.handoff.ArenaDirectory(client);
            ArenaAnnouncer announcer = new ArenaAnnouncer(directory, "arena-ops", "127.0.0.1", port, server.registry());
            announcer.start();
            try (announcer; TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                Ticket ta = Ticket.forPlayer(9401, "ra", 0);
                tickets.issue(ta).get(5, TimeUnit.SECONDS);
                a.join(ta.id());
                Welcome.of(readReply(a));
                b.join(issueTicket(0));
                Welcome.of(readReply(b));
                assertThat(directory.command("arena-ops", new com.fasterxml.jackson.databind.ObjectMapper()
                        .createObjectNode().put("cmd", "kick").put("player", 9401)).get(5, TimeUnit.SECONDS)).isEqualTo(1L);
                expectKickAfterSnapshots(a, Wire.KICK_REMOVED);
                for (int i = 0; i < 20; i++) {
                    assertThat(b.readFrame()[0] & 0xFF).as("the other plays on").isEqualTo(Wire.MSG_SNAPSHOT);
                }
                waitUntil(() -> published.size() == 1);
                assertThat(published.get(0).players()).extracting(MatchOutcome.PlayerOutcome::playerId).containsExactly(9401L);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a made match an operator closes is cut short: recorded, not rated (D-29)")
    void aClosedMatchIsCutShort() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(duelTicket(uid, 9411, 0));
                Welcome.of(readReply(a));
                b.join(duelTicket(uid, 9412, 0));
                Welcome.of(readReply(b));
                RoomThread.RoomView view = server.registry().views().stream()
                        .filter(v -> uid.equals(v.matchUid())).findFirst().orElseThrow();
                assertThat(view.mode()).isEqualTo("duel");
                waitUntil(() -> "playing".equals(server.registry().views().stream()
                        .filter(v -> uid.equals(v.matchUid())).findFirst().orElseThrow().stage()));
                server.registry().closeRoom(view.room(), Wire.KICK_REMOVED);
                expectKickAfterSnapshots(a, Wire.KICK_REMOVED);
            }
            waitUntil(() -> published.size() == 1);
            assertThat(published.get(0).cutShort()).isTrue();
        }
    }

    /** A place in a sandbox (01 §8.10). */
    private static String sandboxTicket(String matchUid, long playerId) throws Exception {
        Ticket t = Ticket.forMatch(playerId, "s-" + playerId, 0, matchUid, com.backend.handoff.MatchMode.SANDBOX.id);
        tickets.issue(t).get(5, TimeUnit.SECONDS);
        return t.id();
    }

    private static byte[] sandbox(int action, int value) {
        return new byte[] {(byte) ClientMessage.SANDBOX, (byte) action, (byte) value};   // values below 128
    }

    /** Whether the arena still answers this client: a ping, and its pong among the frames. */
    private static boolean answers(TestClient c) throws IOException {
        c.ping(System.currentTimeMillis());
        for (int i = 0; i < 100; i++) {
            if ((c.readFrame()[0] & 0xFF) == Wire.MSG_PONG) {
                return true;
            }
        }
        return false;
    }

    @Test
    @Timeout(60)
    @DisplayName("sandbox: plays at once, holds a party of three and no more, and its clock ends it with nothing published (01 §8.10)")
    void aSandboxPlaysAtOnceAndPublishesNothing() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            server.registry().adjustMade = r -> r.withDuration(25 * 4);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port);
                 TestClient c = new TestClient(port); TestClient d = new TestClient(port)) {
                a.join(sandboxTicket(uid, 9601));
                assertThat(Welcome.of(readReply(a)).mode()).isEqualTo(com.backend.handoff.MatchMode.SANDBOX.id);
                waitUntil(() -> "playing".equals(server.registry().views().stream()
                        .filter(v -> uid.equals(v.matchUid())).findFirst().orElseThrow().stage()));
                b.join(sandboxTicket(uid, 9602));
                Welcome.of(readReply(b));
                c.join(sandboxTicket(uid, 9603));
                Welcome.of(readReply(c));
                d.join(sandboxTicket(uid, 9604));
                expectKick(d, Wire.KICK_ROOM_FULL);
                expectKickAfterSnapshots(a, Wire.KICK_MATCH_OVER);
            }
            waitUntil(() -> roomOf(server, uid) == null);
            Thread.sleep(200);
            assertThat(published).as("nothing published").isEmpty();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("sandbox: each player's hold is kept while they are in it, and given back when they leave or it ends (D-54)")
    void aSandboxsHoldsFollowItsPlayers() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { });
             JRedisClient client = store.newClient()) {
            server.registry().holdSandboxes(new com.backend.handoff.SandboxHolds(client));
            server.registry().adjustMade = r -> r.withDuration(25 * 6);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(sandboxTicket(uid, 9701));
                Welcome.of(readReply(a));
                b.join(sandboxTicket(uid, 9702));
                Welcome.of(readReply(b));
                waitUntil(() -> client.sync().send("TTL", "sbx:9701").asLong() > 60
                        && client.sync().send("TTL", "sbx:9702").asLong() > 60);
                assertThat(client.sync().get("sbx:9701")).as("kept for the room's life").isEqualTo(uid);
                b.simple(ClientMessage.LEAVE);
                waitUntil(() -> client.sync().get("sbx:9702") == null);
                assertThat(client.sync().get("sbx:9701")).as("the one still in it keeps theirs").isEqualTo(uid);
                expectKickWhilePinging(a, Wire.KICK_MATCH_OVER, 1_500);
            }
            waitUntil(() -> client.sync().get("sbx:9701") == null);
            try (TestClient d = new TestClient(port)) {
                Ticket duel = Ticket.forMatch(9703, "s-9703", 1, com.backend.handoff.Ulid.generate(),
                        com.backend.handoff.MatchMode.DUEL.id);
                tickets.issue(duel).get(5, TimeUnit.SECONDS);
                d.join(duel.id());
                Welcome.of(readReply(d));
                Thread.sleep(300);
                assertThat(client.sync().get("sbx:9703")).as("no other room holds anything").isNull();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("sandbox: a player whose connection was lost keeps the hold to resume, until the room ends or they do not come back (D-54)")
    void aLostSandboxPlayersHoldWaits() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { });
             JRedisClient client = store.newClient()) {
            server.registry().holdSandboxes(new com.backend.handoff.SandboxHolds(client));
            int port = server.start(0);
            server.registry().adjustMade = r -> r.withDuration(25 * 4);
            String ends = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port)) {
                a.join(sandboxTicket(ends, 9711));
                Welcome.of(readReply(a));
                waitUntil(() -> ends.equals(client.sync().get("sbx:9711")));
            }                                                    // lost, not left: the stay waits
            Thread.sleep(500);
            assertThat(client.sync().get("sbx:9711")).as("kept while they may resume").isEqualTo(ends);
            waitUntil(() -> roomOf(server, ends) == null);
            waitUntil(() -> client.sync().get("sbx:9711") == null);

            server.registry().adjustMade = r -> r.withDuration(25 * 30).withResume(25, 25);
            String lasts = com.backend.handoff.Ulid.generate();
            try (TestClient b = new TestClient(port)) {
                b.join(sandboxTicket(lasts, 9712));
                Welcome.of(readReply(b));
                waitUntil(() -> lasts.equals(client.sync().get("sbx:9712")));
            }
            waitUntil(() -> client.sync().get("sbx:9712") == null);
            assertThat(roomOf(server, lasts)).as("given back when the stay expired, the room still open").isNotNull();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a made match's first arrival is marked, for a tournament to wait for its result; a sandbox's is not (Q-45)")
    void aMadeMatchsArrivalIsMarked() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { });
             JRedisClient client = store.newClient()) {
            server.registry().markArrivals(new com.backend.handoff.MatchArrivals(client));
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port)) {
                Ticket duel = Ticket.forMatch(9801, "s-9801", 1, uid, com.backend.handoff.MatchMode.DUEL.id);
                tickets.issue(duel).get(5, TimeUnit.SECONDS);
                a.join(duel.id());
                Welcome.of(readReply(a));
                waitUntil(() -> client.sync().get("marr:" + uid) != null);
                assertThat(client.sync().send("TTL", "marr:" + uid).asLong()).isBetween(1_800L, 3_600L);
            }
            String sandbox = com.backend.handoff.Ulid.generate();
            try (TestClient b = new TestClient(port)) {
                b.join(sandboxTicket(sandbox, 9802));
                Welcome.of(readReply(b));
                Thread.sleep(300);
                assertThat(client.sync().get("marr:" + sandbox)).as("never a tournament's").isNull();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("sandbox: an operator closing one publishes nothing either")
    void aClosedSandboxPublishesNothing() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port)) {
                a.join(sandboxTicket(uid, 9611));
                Welcome.of(readReply(a));
                RoomThread.RoomView view = server.registry().views().stream()
                        .filter(v -> uid.equals(v.matchUid())).findFirst().orElseThrow();
                waitUntil(() -> "playing".equals(server.registry().views().stream()
                        .filter(v -> uid.equals(v.matchUid())).findFirst().orElseThrow().stage()));
                server.registry().closeRoom(view.room(), Wire.KICK_REMOVED);
                expectKickAfterSnapshots(a, Wire.KICK_REMOVED);
            }
            waitUntil(() -> roomOf(server, uid) == null);
            Thread.sleep(200);
            assertThat(published).isEmpty();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("sandbox: ends once nobody has been in it for its time, not while someone is")
    void anEmptySandboxEnds() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port)) {
                a.join(sandboxTicket(uid, 9621));
                Welcome.of(readReply(a));
                RoomThread room = roomOf(server, uid);
                room.emptyEndTicks = 25;                            // a second, not a minute
                Thread.sleep(2_000);
                assertThat(answers(a)).as("still in it after twice that").isTrue();
                assertThat(roomOf(server, uid)).isNotNull();
                a.simple(ClientMessage.LEAVE);
            }
            waitUntil(() -> roomOf(server, uid) == null);
            assertThat(published).isEmpty();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("sandbox: a stay waiting for its player is somebody in it")
    void aWaitingStayKeepsASandbox() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            server.registry().adjustMade = r -> r.withResume(25, 25 * 4);
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            TestClient a = new TestClient(port);
            a.join(sandboxTicket(uid, 9631));
            Welcome.of(readReply(a));
            RoomThread room = roomOf(server, uid);
            room.emptyEndTicks = 25;
            a.close();                                          // lost, not left: kept four seconds
            Thread.sleep(2_500);
            assertThat(roomOf(server, uid)).as("the stay is still waiting").isNotNull();
            waitUntil(() -> roomOf(server, uid) == null);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("sandbox: a player rebuilds their tank at any level from 1 to 45; outside that, nothing, and the connection kept")
    void aSandboxSetsALevel() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port)) {
                a.join(sandboxTicket(uid, 9641));
                Welcome.of(readReply(a));
                RoomThread room = roomOf(server, uid);
                java.util.concurrent.atomic.AtomicInteger level = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicInteger unspent = new java.util.concurrent.atomic.AtomicInteger();
                room.tickHook = () -> {
                    for (Connection c : room.connections()) {
                        if (c.entityId() >= 0) {
                            var w = room.room().world();
                            w.entities[c.entityId()].hp = w.entities[c.entityId()].maxHp;   // nothing kills it meanwhile
                            level.set(w.tankStats[c.entityId()].level);
                            unspent.set(w.tankStats[c.entityId()].unspentPoints);
                        }
                    }
                };
                a.send(sandbox(ClientMessage.SANDBOX_LEVEL, 45));
                waitUntil(() -> level.get() == 45);
                assertThat(unspent.get()).isPositive();
                a.send(sandbox(ClientMessage.SANDBOX_LEVEL, 46));
                a.send(sandbox(ClientMessage.SANDBOX_LEVEL, 0));
                a.send(sandbox(9, 3));                              // no such action
                // 2^32 + 3: cast to an int it would be level 3.
                a.send(new byte[] {(byte) ClientMessage.SANDBOX, (byte) ClientMessage.SANDBOX_LEVEL,
                        (byte) 0x83, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10});
                assertThat(answers(a)).isTrue();
                Thread.sleep(300);
                assertThat(level.get()).as("none of those did anything").isEqualTo(45);
                a.send(sandbox(ClientMessage.SANDBOX_LEVEL, 3));
                waitUntil(() -> level.get() == 3);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("sandbox: a Guardian summoned hunts, one at a time, enraged below half its health, and another after it dies")
    void aSandboxSummonsOneGuardian() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port)) {
                a.join(sandboxTicket(uid, 9651));
                Welcome.of(readReply(a));
                RoomThread room = roomOf(server, uid);
                java.util.concurrent.atomic.AtomicInteger guardians = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicInteger most = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicInteger enraged = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicInteger hurt = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicBoolean kill = new java.util.concurrent.atomic.AtomicBoolean();
                room.tickHook = () -> {
                    var w = room.room().world();
                    for (Connection c : room.connections()) {
                        if (c.entityId() >= 0) {
                            w.entities[c.entityId()].hp = w.entities[c.entityId()].maxHp;
                        }
                    }
                    int n = 0;
                    int angry = 0;
                    for (int i = 0; i < w.tanks.size; i++) {
                        var t = w.entities[w.tanks.items[i]];
                        int cls = w.tankStats[t.id].classId;
                        if (!t.alive || !t.hunts) {
                            continue;
                        }
                        if (cls == com.backend.sim.ClassTable.GUARDIAN || cls == com.backend.sim.ClassTable.GUARDIAN_ENRAGED) {
                            n++;
                            angry += cls == com.backend.sim.ClassTable.GUARDIAN_ENRAGED ? 1 : 0;
                            if (hurt.get() == 1) {
                                t.hp = t.maxHp * 0.4f;
                                hurt.set(2);
                            }
                            if (kill.getAndSet(false)) {
                                w.kill(t);
                                n--;
                            }
                        }
                    }
                    guardians.set(n);
                    enraged.set(angry);
                    most.accumulateAndGet(n, Math::max);
                };
                a.send(sandbox(ClientMessage.SANDBOX_GUARDIAN, 0));
                waitUntil(() -> guardians.get() == 1);
                a.send(sandbox(ClientMessage.SANDBOX_GUARDIAN, 0));
                assertThat(answers(a)).isTrue();
                Thread.sleep(300);
                assertThat(most.get()).as("one at a time").isEqualTo(1);

                hurt.set(1);
                waitUntil(() -> enraged.get() == 1);

                kill.set(true);
                waitUntil(() -> guardians.get() == 0);
                a.send(sandbox(ClientMessage.SANDBOX_GUARDIAN, 0));
                waitUntil(() -> guardians.get() == 1 && enraged.get() == 0);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("sandbox: someone coming back starts the empty count again")
    void anEmptySandboxCountsAfresh() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            java.util.concurrent.atomic.AtomicInteger tick = new java.util.concurrent.atomic.AtomicInteger();
            try (TestClient a = new TestClient(port)) {
                a.join(sandboxTicket(uid, 9671));
                Welcome.of(readReply(a));
                RoomThread room = roomOf(server, uid);
                room.emptyEndTicks = 50;
                room.tickHook = () -> tick.set(room.room().tick());
                a.simple(ClientMessage.LEAVE);
            }
            Thread.sleep(1_000);                                    // empty for about half its time
            try (TestClient b = new TestClient(port)) {
                b.join(sandboxTicket(uid, 9672));
                Welcome.of(readReply(b));
                Thread.sleep(500);
                int left = tick.get();
                b.simple(ClientMessage.LEAVE);
                waitUntil(() -> roomOf(server, uid) == null);
                assertThat(tick.get() - left).as("a whole empty time after the second left").isGreaterThanOrEqualTo(45);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("the Sandbox message in any room but a sandbox, a duel here: dropped, again and again, and the connection kept")
    void theSandboxMessageIsDroppedElsewhere() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), o -> { })) {
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(duelTicket(uid, 9661, 0));
                Welcome.of(readReply(a));
                b.join(duelTicket(uid, 9662, 1));
                Welcome.of(readReply(b));
                waitUntil(() -> "playing".equals(server.registry().views().stream()
                        .filter(v -> uid.equals(v.matchUid())).findFirst().orElseThrow().stage()));
                for (int i = 0; i < 20; i++) {
                    a.send(sandbox(ClientMessage.SANDBOX_LEVEL, 45));
                    a.send(sandbox(ClientMessage.SANDBOX_GUARDIAN, 0));
                    Thread.sleep(50);                               // a tick or so apart: each one taken
                }
                a.send(new byte[] {(byte) ClientMessage.SANDBOX, (byte) ClientMessage.SANDBOX_LEVEL});   // no value
                assertThat(answers(a)).isTrue();
                RoomThread room = roomOf(server, uid);
                java.util.concurrent.atomic.AtomicInteger level = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicInteger hunters = new java.util.concurrent.atomic.AtomicInteger(-1);
                room.tickHook = () -> {
                    var w = room.room().world();
                    for (Connection c : room.connections()) {
                        if (c.entityId() >= 0) {
                            level.accumulateAndGet(w.tankStats[c.entityId()].level, Math::max);
                        }
                    }
                    int n = 0;
                    for (int i = 0; i < w.tanks.size; i++) {
                        n += w.entities[w.tanks.items[i]].hunts ? 1 : 0;
                    }
                    hunters.set(n);
                };
                waitUntil(() -> hunters.get() >= 0);
                assertThat(level.get()).isEqualTo(1);
                assertThat(hunters.get()).isZero();
            }
        }
    }

    /**
     * The player's tank put in the middle of an empty map, still, before any input arrives: no edge
     * within reach and nothing to knock it, so the room moves it by its input alone. Waits for it.
     */
    private static RoomThread placedInTheMiddle(ArenaServer server, float mapSize) throws InterruptedException {
        RoomThread room = server.registry().rooms().get(0);
        java.util.concurrent.atomic.AtomicBoolean placed = new java.util.concurrent.atomic.AtomicBoolean();
        room.tickHook = () -> {
            for (Connection conn : room.connections()) {
                if (!placed.get() && conn.entityId() >= 0) {
                    var e = room.room().world().entities[conn.entityId()];
                    e.x = mapSize / 2;
                    e.y = mapSize / 2;
                    e.vx = 0f;
                    e.vy = 0f;
                    placed.set(true);
                }
            }
        };
        waitUntil(placed::get);
        room.tickHook = null;
        return room;
    }

    /** The frame's own motion, failing unless there is exactly one; its rules into {@code rules}. */
    private static SnapshotReader.Motion motionOf(ClientWorld w, List<SnapshotReader.MotionRule> rules) {
        SnapshotReader.Motion motion = null;
        for (SnapshotReader.Event e : w.events()) {
            if (e.type() == Wire.EVT_MOTION_RULE) {
                rules.add(SnapshotReader.motionRule(e));
            } else if (e.type() == Wire.EVT_MOTION) {
                assertThat(motion).as("one a frame").isNull();
                motion = SnapshotReader.motion(e);
            }
        }
        assertThat(motion).as("every frame while the tank lives").isNotNull();
        return motion;
    }

    @Test
    @Timeout(30)
    @DisplayName("the own tank's motion in every frame: the ticks the echoed input has driven it and the velocity the room gave it; its rule in the first frame only (D-62)")
    void theOwnTanksMotionIsTold() throws Exception {
        try (ArenaServer server = newServer(6_000f, 2_048, 20, 0, 1)) {           // no shapes
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                Welcome.of(readReply(c));
                placedInTheMiddle(server, 6_000f);
                ClientWorld w = new ClientWorld();
                List<SnapshotReader.MotionRule> rules = new ArrayList<>();
                int previousTicks = -1;
                long previousTick = -1;
                int driven = 0;
                for (int frames = 0; frames < 20; ) {
                    c.input(1, ClientMessage.MOVE_RIGHT, 0, 0);    // one input, held: never stale
                    if (!applyNext(c, w)) {
                        continue;
                    }
                    frames++;
                    SnapshotReader.Motion m = motionOf(w, rules);
                    if (w.lastProcessedInputSeq() != 1) {
                        assertThat(m).as("before the input: not driven, and still").isEqualTo(new SnapshotReader.Motion(0, 0, 0));
                        continue;
                    }
                    driven++;
                    if (previousTicks > 0) {
                        assertThat(m.inputTicks()).as("each tick since the last frame counted")
                                .isEqualTo(previousTicks + (int) (w.serverTick() - previousTick));
                    }
                    previousTicks = m.inputTicks();
                    previousTick = w.serverTick();
                    // The room's rule, from rest, once a tick it applied the input.
                    float v = 0f;
                    for (int t = 0; t < m.inputTicks(); t++) {
                        v = (v + 1f * 0.16f) * 0.90f;
                    }
                    assertThat(m.vx()).as("%d ticks to the right", m.inputTicks()).isEqualTo(Math.round(v * Wire.VELOCITY_SCALE));
                    assertThat(m.vy()).isZero();
                }
                assertThat(driven).as("frames after the input arrived").isGreaterThan(10);
                assertThat(rules).as("its rule, in the first frame and not again").hasSize(1);
                assertThat(Float.floatToRawIntBits(rules.get(0).accel())).isEqualTo(Float.floatToRawIntBits(0.16f));
                assertThat(rules.get(0).radius()).as("a Basic's body").isEqualTo(30f);
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("the own tank's rule again when its acceleration or its radius changes, and only then: equipment's movement bonus, a bigger body (D-62)")
    void aMotionRuleIsToldAgainOnAChange() throws Exception {
        try (ArenaServer server = newServer(6_000f, 2_048, 20, 0, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                Welcome.of(readReply(c));
                RoomThread room = placedInTheMiddle(server, 6_000f);
                ClientWorld w = new ClientWorld();
                List<SnapshotReader.MotionRule> rules = new ArrayList<>();
                for (int frames = 0; frames < 5; ) {
                    if (applyNext(c, w)) {
                        frames++;
                        motionOf(w, rules);
                    }
                }
                assertThat(rules).hasSize(1);
                java.util.concurrent.atomic.AtomicBoolean worn = new java.util.concurrent.atomic.AtomicBoolean();
                room.tickHook = () -> {
                    for (Connection conn : room.connections()) {
                        if (!worn.get() && conn.entityId() >= 0) {
                            byte[] bonus = new byte[Stat.COUNT];
                            bonus[Stat.MOVEMENT_SPEED] = 10;
                            room.room().world().tankStats[conn.entityId()].setBonus(bonus);
                            worn.set(true);
                        }
                    }
                };
                waitUntil(worn::get);
                room.tickHook = null;
                for (int frames = 0; frames < 10; ) {
                    if (applyNext(c, w)) {
                        frames++;
                        motionOf(w, rules);
                    }
                }
                assertThat(rules).as("the first, and one for the change").hasSize(2);
                float faster = 0.16f * (1f * (1f + 10 / 100f));        // as the room works it out
                assertThat(Float.floatToRawIntBits(rules.get(1).accel())).isEqualTo(Float.floatToRawIntBits(faster));
                assertThat(rules.get(1).radius()).isEqualTo(30f);

                // A class whose body is not a Basic's: its radius the next tick, and a rule for it.
                ClassTable classes = room.room().content().classes();
                int bigger = -1;
                for (int id = 0; id < classes.size() && bigger < 0; id++) {
                    if (classes.get(id).bodySize() > 1f) {
                        bigger = id;
                    }
                }
                assertThat(bigger).as("a class with a bigger body").isPositive();
                int chosen = bigger;
                java.util.concurrent.atomic.AtomicBoolean grown = new java.util.concurrent.atomic.AtomicBoolean();
                room.tickHook = () -> {
                    for (Connection conn : room.connections()) {
                        if (!grown.get() && conn.entityId() >= 0) {
                            room.room().world().tankStats[conn.entityId()].classId = chosen;
                            grown.set(true);
                        }
                    }
                };
                waitUntil(grown::get);
                room.tickHook = null;
                for (int frames = 0; frames < 10; ) {
                    if (applyNext(c, w)) {
                        frames++;
                        motionOf(w, rules);
                    }
                }
                assertThat(rules).as("and one for the body").hasSize(3);
                assertThat(rules.get(2).radius()).isEqualTo(30f * classes.get(chosen).bodySize());
                assertThat(rules.get(2).accel()).isEqualTo(faster);
            }
        }
    }

    private static RoomThread roomOf(ArenaServer server, String matchUid) {
        return server.registry().rooms().stream().filter(r -> matchUid.equals(r.matchUid())).findFirst().orElse(null);
    }

    @Test
    @Timeout(60)
    @DisplayName("a made duel: both tickets reach one room made for it, the clock ends it, a draw is published, and the room closes")
    void aMadeDuelIsPlayedAndItsRoomCloses() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            server.registry().adjustMade = r -> r.withDuration(50);     // two seconds of play
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                // Both at once, as two players matched together arrive: the second was refused
                // as the room admitted the first, until a match's room stopped counting places.
                String ta = duelTicket(uid, 9001, 0);
                String tb = duelTicket(uid, 9002, 1);
                a.join(ta);
                b.join(tb);
                Welcome wa = Welcome.of(readReply(a));
                assertThat(wa.mode()).as("the Welcome names the mode").isEqualTo(1);
                Welcome.of(readReply(b));
                assertThat(server.registry().rooms().stream().filter(r -> uid.equals(r.matchUid())))
                        .as("the first ticket made the room, the second found it, and made no other")
                        .hasSize(1);
                try (TestClient third = new TestClient(port)) {
                    // Only a matcher bug could issue one; the room refuses it at the door.
                    third.join(duelTicket(uid, 9003, 0));
                    expectKick(third, Wire.KICK_ROOM_FULL);
                }

                expectKickAfterSnapshots(a, Wire.KICK_MATCH_OVER);
                expectKickAfterSnapshots(b, Wire.KICK_MATCH_OVER);
            }
            assertThat(published).hasSize(1);
            MatchOutcome duel = published.get(0);
            assertThat(duel.matchUid()).as("the matcher's id, not one the arena made").isEqualTo(uid);
            assertThat(duel.mode()).isEqualTo(com.backend.handoff.MatchMode.DUEL.id);
            assertThat(duel.kind()).isEqualTo(MatchOutcome.KIND_TIMED);
            assertThat(duel.players()).extracting(MatchOutcome.PlayerOutcome::placement).containsExactly(1, 1);

            waitUntil(() -> roomOf(server, uid) == null);
            assertThat(server.registry().failedRooms()).as("over is not failed").isZero();
            try (TestClient late = new TestClient(port)) {
                late.join(duelTicket(uid, 9004, 0));
                expectKick(late, Wire.KICK_ROOM_FULL);          // over here: back to the queue
            }
            assertThat(roomOf(server, uid)).as("and it did not start again").isNull();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a duel only one side came to is a walkover: one player, placed first, at once")
    void aDuelWithOneSideIsAWalkover() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            server.registry().adjustMade = r -> r.withJoinWindow(25);   // one second to arrive
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port)) {
                a.join(duelTicket(uid, 9011, 0));
                Welcome.of(readReply(a));
                expectKickAfterSnapshots(a, Wire.KICK_MATCH_OVER);
            }
            assertThat(published).singleElement().satisfies(o -> {
                assertThat(o.players()).singleElement().satisfies(p -> {
                    assertThat(p.playerId()).isEqualTo(9011);
                    assertThat(p.placement()).isEqualTo(1);
                });
            });
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a duel ends the moment one side has three kills, and is placed by kills")
    void aDuelEndsAtThreeKills() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 3,
                MatchRules.open("arena-test"), published::add)) {
            int port = server.start(0);
            String uid = com.backend.handoff.Ulid.generate();
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(duelTicket(uid, 9021, 0));
                Welcome.of(readReply(a));
                b.join(duelTicket(uid, 9022, 1));
                Welcome.of(readReply(b));
                RoomThread room = roomOf(server, uid);
                // On the room thread, which owns the world: whenever both are alive, B stands
                // in front of A's barrel with half a point of health, and nobody is protected.
                // A fires because its client says so; B asks to come back each time it dies.
                room.tickHook = () -> {
                    List<Connection> here = room.connections();
                    Connection ca = here.stream().filter(c -> c.identity().playerId() == 9021).findFirst().orElse(null);
                    Connection cb = here.stream().filter(c -> c.identity().playerId() == 9022).findFirst().orElse(null);
                    if (ca == null || cb == null || ca.entityId() < 0 || cb.entityId() < 0) {
                        return;
                    }
                    var ta = room.room().world().entities[ca.entityId()];
                    var tb = room.room().world().entities[cb.entityId()];
                    ta.x = 1_000f;
                    ta.y = 1_000f;
                    tb.x = 1_080f;
                    tb.y = 1_000f;
                    ta.vx = ta.vy = tb.vx = tb.vy = 0f;
                    ta.protectedUntilTick = tb.protectedUntilTick = 0;
                    tb.hp = 0.5f;
                    // Nothing else may kill B there: a shape drifting onto the spot did, on every
                    // respawn, twelve times in one run, and the deaths no longer matched A's kills (M-15).
                    var world = room.room().world();
                    for (int i = 0; i < world.shapes.size; i++) {
                        var s = world.entities[world.shapes.items[i]];
                        if (Math.abs(s.x - 1_040f) < 200f && Math.abs(s.y - 1_000f) < 200f) {
                            s.y = s.y < 1_000f ? 700f : 1_300f;
                        }
                    }
                };
                boolean overA = false;
                boolean overB = false;
                for (int seq = 1; seq < 2_000 && !(overA && overB); seq++) {
                    // A write can meet the socket the server closed after its kick; the kick is
                    // still there to read, which is what is asserted.
                    if (!overA) {
                        try {
                            a.input(seq, 0, 32_768, ClientMessage.FLAG_AUTOFIRE);  // aim along +x
                        } catch (IOException closed) {
                            // read the kick below
                        }
                        byte[] f = a.readFrame();
                        overA = (f[0] & 0xFF) == Wire.MSG_KICK && (f[1] & 0xFF) == Wire.KICK_MATCH_OVER;
                    }
                    if (!overB) {
                        try {
                            b.input(seq, 0, 0, 0);
                            b.simple(ClientMessage.RESPAWN);                     // ignored while alive
                        } catch (IOException closed) {
                            // read the kick below
                        }
                        byte[] f = b.readFrame();
                        overB = (f[0] & 0xFF) == Wire.MSG_KICK && (f[1] & 0xFF) == Wire.KICK_MATCH_OVER;
                    }
                }
                assertThat(overA && overB).as("both told the match is over").isTrue();
            }
            MatchOutcome duel = published.get(0);
            MatchOutcome.PlayerOutcome winner = duel.players().get(0);
            assertThat(winner.playerId()).isEqualTo(9021);
            assertThat(winner.kills()).as("ended at three, not later").isEqualTo(3);
            assertThat(winner.placement()).isEqualTo(1);
            assertThat(duel.players().get(1).placement()).isEqualTo(2);
            assertThat(duel.players().get(1).deaths()).isEqualTo(3);
        }
    }

    /** The first frame that is not a snapshot: a Welcome or a Kick. */
    private static byte[] readReply(TestClient c) throws IOException {
        for (int i = 0; i < 200; i++) {
            byte[] frame = c.readFrame();
            if ((frame[0] & 0xFF) != Wire.MSG_SNAPSHOT) {
                return frame;
            }
        }
        throw new AssertionError("no reply");
    }

    private static ArenaServer resumeServer(List<MatchOutcome> published, int graceTicks, int keepTicks) {
        return new ArenaServer(tickets, 4_000f, 1_024, 20, 0, 2, 1,
                MatchRules.open("arena-test").withResume(graceTicks, keepTicks), published::add);
    }

    /** Reads everything until the server closes the socket; false if it is still open after 10 s. */
    private static boolean closedByServer(TestClient c) throws IOException {
        c.soTimeout(10_000);
        try {
            while (true) {
                c.readFrame();
            }
        } catch (java.net.SocketTimeoutException stillOpen) {
            return false;
        } catch (IOException closed) {
            return true;
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a client that loses its connection resumes its own tank, and its stay is one stay")
    void resumeTakesBackTheSameTank() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = resumeServer(published, 250, 1_500)) {
            int port = server.start(0);
            Welcome first;
            try (TestClient a = new TestClient(port)) {
                a.join(issueTicket(0));
                first = Welcome.of(a.readFrame());
                readNormalSnapshot(a);
                for (int i = 1; i <= 5; i++) {
                    a.input(i, ClientMessage.MOVE_RIGHT, 0, ClientMessage.FLAG_AUTOFIRE);
                }
                Thread.sleep(200);
            }                                           // gone, without a word: a phone out of range
            RoomThread room = server.registry().rooms().get(0);
            waitUntil(() -> room.suspendedCount() == 1);
            assertThat(room.playerCount()).as("its place is kept").isEqualTo(1);
            assertThat(published).as("nothing ends while it can come back").isEmpty();

            // Parked where it stands: the last input it had, running right and firing, no
            // longer drives it.
            Thread.sleep(3_500);                        // bullets already out expire, it coasts to rest
            float[] at = new float[2];
            int[] bullets = new int[1];
            for (int k = 0; k < 2; k++) {
                int slot = k;
                java.util.concurrent.CountDownLatch seen = new java.util.concurrent.CountDownLatch(1);
                room.tickHook = () -> {
                    at[slot] = room.room().world().entities[first.selfEntityId()].x;
                    bullets[0] = room.room().world().bullets.size;
                    room.tickHook = null;
                    seen.countDown();
                };
                assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(1_000);
            }
            assertThat(Math.abs(at[1] - at[0])).as("not moving").isLessThan(1f);
            assertThat(bullets[0]).as("not firing").isZero();

            try (TestClient b = new TestClient(port)) {
                b.resume(first.resumeSecret());
                Welcome again = Welcome.of(readReply(b));
                assertThat(again.selfEntityId()).as("the same tank").isEqualTo(first.selfEntityId());
                assertThat(again.resumeSecret()).as("a new secret each time").isNotEqualTo(first.resumeSecret());
                assertThat(readNormalSnapshot(b)).isNotNull();
                assertThat(room.suspendedCount()).isZero();
                b.simple(ClientMessage.LEAVE);
            }
            waitUntil(() -> published.size() == 1);
            Thread.sleep(300);
            assertThat(published).as("one stay, published once").hasSize(1);
            // Connected for about a second of it, away for six: the time away is not play.
            assertThat(published.get(0).players().get(0).playtimeSeconds()).isLessThanOrEqualTo(2);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a resume takes over a connection the server still thinks is open")
    void resumeTakesOverALiveConnection() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = resumeServer(published, 250, 1_500)) {
            int port = server.start(0);
            TestClient old = new TestClient(port);
            old.join(issueTicket(0));
            Welcome first = Welcome.of(old.readFrame());
            RoomThread room = server.registry().rooms().get(0);
            try (TestClient b = new TestClient(port)) {
                // The phone knows its wifi is gone long before the server's idle limit does.
                b.resume(first.resumeSecret());
                Welcome again = Welcome.of(readReply(b));
                assertThat(again.selfEntityId()).isEqualTo(first.selfEntityId());
                assertThat(closedByServer(old)).as("the old connection is closed").isTrue();
                assertThat(room.playerCount()).isEqualTo(1);
                assertThat(readNormalSnapshot(b)).as("and the new one carries on").isNotNull();
                assertThat(published).isEmpty();
                b.simple(ClientMessage.LEAVE);
            } finally {
                old.close();
            }
            waitUntil(() -> published.size() == 1);
            Thread.sleep(300);
            assertThat(published).hasSize(1);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a removal ends a stay waiting for its player too: published, and it cannot be resumed (T-35)")
    void aRemovalEndsAWaitingStay() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = resumeServer(published, 250, 1_500)) {
            int port = server.start(0);
            Ticket t = Ticket.forPlayer(9901, "rm-9901", 0);
            tickets.issue(t).get(5, TimeUnit.SECONDS);
            Welcome first;
            try (TestClient a = new TestClient(port)) {
                a.join(t.id());
                first = Welcome.of(a.readFrame());
            }                                           // lost, not left: the stay waits
            RoomThread room = server.registry().rooms().get(0);
            waitUntil(() -> room.suspendedCount() == 1);
            server.registry().remove(9901, false);              // a kick, as a ban, reaches it
            waitUntil(() -> published.size() == 1);
            assertThat(room.suspendedCount()).isZero();
            try (TestClient b = new TestClient(port)) {
                b.resume(first.resumeSecret());
                byte[] reply = readReply(b);
                assertThat(reply[0] & 0xFF).isEqualTo(Wire.MSG_KICK);
                assertThat(reply[1] & 0xFF).as("back to the lobby").isEqualTo(Wire.KICK_BAD_TICKET);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a banned player's ticket is refused for a ticket's life, Kick(7), as one issued before the ban was; then they may join; a kicked one's is not (T-35)")
    void aRemovedPlayersTicketIsRefused() throws Exception {
        try (ArenaServer server = resumeServer(new CopyOnWriteArrayList<>(), 250, 1_500)) {
            server.registry().removedMillis = 1_000;            // a ticket's life, shortened
            int port = server.start(0);
            server.registry().remove(9903, false);
            Ticket kicked = Ticket.forPlayer(9903, "rm-9903", 0);
            tickets.issue(kicked).get(5, TimeUnit.SECONDS);
            try (TestClient k = new TestClient(port)) {
                k.join(kicked.id());
                assertThat(Welcome.of(k.readFrame()).selfEntityId()).as("a kick is not remembered").isGreaterThanOrEqualTo(0);
            }
            server.registry().remove(9902, true);
            Ticket early = Ticket.forPlayer(9902, "rm-9902", 0);
            tickets.issue(early).get(5, TimeUnit.SECONDS);
            try (TestClient a = new TestClient(port)) {
                a.join(early.id());
                byte[] reply = readReply(a);
                assertThat(reply[0] & 0xFF).isEqualTo(Wire.MSG_KICK);
                assertThat(reply[1] & 0xFF).isEqualTo(Wire.KICK_REMOVED);
            }
            Thread.sleep(1_100);
            Ticket later = Ticket.forPlayer(9902, "rm-9902", 0);
            tickets.issue(later).get(5, TimeUnit.SECONDS);
            try (TestClient b = new TestClient(port)) {
                b.join(later.id());
                assertThat(Welcome.of(b.readFrame()).selfEntityId()).as("the minute over").isGreaterThanOrEqualTo(0);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("leaving on purpose ends the stay at once, and it cannot be resumed")
    void leavingEndsTheStay() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = resumeServer(published, 250, 1_500)) {
            int port = server.start(0);
            Welcome first;
            try (TestClient a = new TestClient(port)) {
                a.join(issueTicket(0));
                first = Welcome.of(a.readFrame());
                a.simple(ClientMessage.LEAVE);
            }
            waitUntil(() -> published.size() == 1);
            try (TestClient b = new TestClient(port)) {
                b.resume(first.resumeSecret());
                byte[] reply = readReply(b);
                assertThat(reply[0] & 0xFF).isEqualTo(Wire.MSG_KICK);
                assertThat(reply[1] & 0xFF).as("back to the lobby").isEqualTo(Wire.KICK_BAD_TICKET);
            }
            assertThat(server.registry().rooms().get(0).playerCount()).isZero();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("after the grace the tank leaves the world, and what it grew into waits for the player")
    void progressWaitsAfterTheTankHasGone() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = resumeServer(published, 25, 1_500)) {
            int port = server.start(0);
            RoomThread room = server.registry().rooms().get(0);
            Welcome first;
            try (TestClient a = new TestClient(port)) {
                a.join(issueTicket(0));
                first = Welcome.of(a.readFrame());
                java.util.concurrent.CountDownLatch grown = new java.util.concurrent.CountDownLatch(1);
                room.tickHook = () -> {
                    com.backend.sim.TankStats stats = room.room().world().tankStats[first.selfEntityId()];
                    stats.level = 7;
                    stats.unspentPoints = 6;
                    room.tickHook = null;
                    grown.countDown();
                };
                assertThat(grown.await(5, TimeUnit.SECONDS)).isTrue();
            }
            waitUntil(() -> room.suspendedCount() == 1);
            waitUntil(() -> !room.room().world().entities[first.selfEntityId()].alive);
            assertThat(room.playerCount()).as("its place is still kept").isEqualTo(1);

            try (TestClient b = new TestClient(port)) {
                b.resume(first.resumeSecret());
                Welcome again = Welcome.of(readReply(b));
                int[] level = new int[1];
                java.util.concurrent.CountDownLatch read = new java.util.concurrent.CountDownLatch(1);
                room.tickHook = () -> {
                    level[0] = room.room().world().tankStats[again.selfEntityId()].level;
                    room.tickHook = null;
                    read.countDown();
                };
                assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(level[0]).as("the tank it had grown").isEqualTo(7);
                assertThat(room.room().world().entities[again.selfEntityId()].alive).isTrue();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a stay nobody comes back for is published when its time runs out, and cannot be resumed after")
    void anAbandonedStayIsPublished() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = resumeServer(published, 25, 75)) {
            int port = server.start(0);
            Welcome first;
            try (TestClient a = new TestClient(port)) {
                a.join(issueTicket(0));
                first = Welcome.of(a.readFrame());
            }
            RoomThread room = server.registry().rooms().get(0);
            waitUntil(() -> published.size() == 1);
            assertThat(room.suspendedCount()).isZero();
            assertThat(room.playerCount()).isZero();
            try (TestClient b = new TestClient(port)) {
                b.resume(first.resumeSecret());
                byte[] reply = readReply(b);
                assertThat(reply[0] & 0xFF).isEqualTo(Wire.MSG_KICK);
                assertThat(reply[1] & 0xFF).isEqualTo(Wire.KICK_BAD_TICKET);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("stopping the arena publishes a stay that was waiting for its player")
    void shutdownPublishesAWaitingStay() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        ArenaServer server = resumeServer(published, 250, 1_500);
        try {
            int port = server.start(0);
            try (TestClient a = new TestClient(port)) {
                a.join(issueTicket(0));
                Welcome.of(a.readFrame());
            }
            RoomThread room = server.registry().rooms().get(0);
            waitUntil(() -> room.suspendedCount() == 1);
        } finally {
            server.close();
        }
        assertThat(published).as("the stay's result").hasSize(1);
    }

    @Test
    @Timeout(60)
    @DisplayName("a fresh ticket for a player whose stay is waiting ends that stay first: one tank, two records")
    void aFreshJoinEndsTheWaitingStay() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = resumeServer(published, 250, 1_500)) {
            int port = server.start(0);
            long playerId = 900_001L;
            try (TestClient a = new TestClient(port)) {
                a.join(ticketFor(playerId));
                Welcome.of(a.readFrame());
            }
            RoomThread room = server.registry().rooms().get(0);
            waitUntil(() -> room.suspendedCount() == 1);
            try (TestClient b = new TestClient(port)) {
                b.join(ticketFor(playerId));        // the app lost its secret: back through the lobby
                Welcome.of(readReply(b));
                assertThat(published).as("the first stay, ended").hasSize(1);
                assertThat(room.suspendedCount()).isZero();
                assertThat(room.playerCount()).isEqualTo(1);
                // Counted on the room thread, at the start of a tick: the ended tank is dead at
                // once but leaves the list only in the sweep at the end of the tick that welcomed
                // the new one, and read from here the count raced it (T-14).
                java.util.concurrent.atomic.AtomicInteger tanks = new java.util.concurrent.atomic.AtomicInteger(-1);
                room.tickHook = () -> tanks.set(room.room().world().tanks.size);
                waitUntil(() -> tanks.get() >= 0);
                assertThat(tanks.get()).as("one tank, not two").isEqualTo(1);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a fresh ticket for a player the server still holds a connection for ends that one: one tank")
    void aFreshJoinEndsALiveConnection() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = resumeServer(published, 250, 1_500)) {
            int port = server.start(0);
            long playerId = 900_002L;
            try (TestClient old = new TestClient(port)) {
                old.join(ticketFor(playerId));
                Welcome.of(old.readFrame());
                RoomThread room = server.registry().rooms().get(0);
                // The phone lost this socket without the server noticing, and came back
                // through the lobby with a new ticket, having lost its resume secret.
                try (TestClient fresh = new TestClient(port)) {
                    fresh.join(ticketFor(playerId));
                    Welcome.of(readReply(fresh));
                    assertThat(closedByServer(old)).as("the old connection is closed").isTrue();
                    waitUntil(() -> published.size() == 1);    // the old stay, ended
                    assertThat(room.playerCount()).isEqualTo(1);
                    assertThat(room.suspendedCount()).as("not left waiting either").isZero();
                    java.util.concurrent.atomic.AtomicInteger tanks = new java.util.concurrent.atomic.AtomicInteger(-1);
                    room.tickHook = () -> tanks.set(room.room().world().tanks.size);   // on its thread (T-14)
                    waitUntil(() -> tanks.get() >= 0);
                    assertThat(tanks.get()).as("one tank, not two").isEqualTo(1);
                }
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a tank killed while its player was away: the player comes back to the news, and a new tank, wearing what the ticket said")
    void killedWhileAway() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = resumeServer(published, 250, 1_500)) {
            int port = server.start(0);
            RoomThread room = server.registry().rooms().get(0);
            Welcome first;
            Ticket worn = Ticket.forPlayer(9701, "away", 0, "5:20");
            tickets.issue(worn).get(5, TimeUnit.SECONDS);
            try (TestClient a = new TestClient(port)) {
                a.join(worn.id());
                first = Welcome.of(a.readFrame());
            }
            waitUntil(() -> room.suspendedCount() == 1);
            java.util.concurrent.CountDownLatch killed = new java.util.concurrent.CountDownLatch(1);
            room.tickHook = () -> {
                room.room().world().kill(room.room().world().entities[first.selfEntityId()]);
                room.tickHook = null;
                killed.countDown();
            };
            assertThat(killed.await(5, TimeUnit.SECONDS)).isTrue();

            try (TestClient b = new TestClient(port)) {
                b.resume(first.resumeSecret());
                Welcome again = Welcome.of(readReply(b));
                ClientWorld w = new ClientWorld();
                boolean told = false;
                for (int i = 0; i < 10 && !told; i++) {
                    byte[] frame = b.readFrame();
                    if ((frame[0] & 0xFF) == Wire.MSG_SNAPSHOT) {
                        w.apply(frame);
                        told = diedIn(w);
                    }
                }
                assertThat(told).as("a death event in its first frames").isTrue();
                assertThat(room.room().world().entities[again.selfEntityId()].alive).as("and a tank").isTrue();
                com.backend.sim.StatTable table = room.room().content().stats();
                java.util.concurrent.atomic.AtomicReference<Float> damage = new java.util.concurrent.atomic.AtomicReference<>();
                room.tickHook = () -> {
                    TankStats s = room.room().world().tankStats[again.selfEntityId()];
                    s.refresh(table);
                    damage.set(s.value(Stat.BULLET_DAMAGE));
                    room.tickHook = null;
                };
                waitUntil(() -> damage.get() != null);
                assertThat(damage.get()).as("the new tank wears it too (D-37)")
                        .isCloseTo(table.valueOf(Stat.BULLET_DAMAGE, 1, 0) * 1.2f, within(1e-3f));
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a client whose input stops, its socket still open, stops driving its tank within a second")
    void staleInputStopsDriving() throws Exception {
        try (ArenaServer server = newServer(4_000f, 1_024, 20, 0, 1)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                int self = Welcome.of(c.readFrame()).selfEntityId();
                RoomThread room = server.registry().rooms().get(0);
                c.input(1, ClientMessage.MOVE_RIGHT, 0, ClientMessage.FLAG_AUTOFIRE);
                c.soTimeout(100);
                // Then nothing: a phone whose link has stalled, and a server still holding its
                // socket. Before, the last input drove the tank for the 30 s until the idle
                // limit: running right and firing, with nobody at the controls.
                float[] x = new float[3];
                boolean[] firing = new boolean[1];
                long[] at = {300, 2_500, 3_500};           // parked at 1 s, coasted to rest by 2.5
                long start = System.nanoTime();
                for (int k = 0; k < 3; k++) {
                    while (System.nanoTime() - start < at[k] * 1_000_000L) {
                        try {
                            c.readFrame();
                        } catch (java.net.SocketTimeoutException quiet) {
                            // keep time
                        }
                    }
                    int slot = k;
                    java.util.concurrent.CountDownLatch seen = new java.util.concurrent.CountDownLatch(1);
                    room.tickHook = () -> {
                        x[slot] = room.room().world().entities[self].x;
                        firing[0] = room.room().world().entities[self].wantsFire;
                        room.tickHook = null;
                        seen.countDown();
                    };
                    assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
                }
                assertThat(x[1]).as("the input was obeyed while fresh").isGreaterThan(x[0] + 5);
                assertThat(Math.abs(x[2] - x[1])).as("and not after a second without any").isLessThan(1f);
                assertThat(firing[0]).isFalse();

                c.input(2, ClientMessage.MOVE_RIGHT, 0, 0);    // back: obeyed at once
                // Half a second, not one: at one it is the stale limit itself, and a tick that
                // came a millisecond late parked the tank again and failed this under load.
                Thread.sleep(500);
                float[] after = new float[2];
                java.util.concurrent.CountDownLatch seen = new java.util.concurrent.CountDownLatch(1);
                room.tickHook = () -> {
                    after[0] = room.room().world().entities[self].x;
                    after[1] = room.room().world().entities[self].moveX;
                    room.tickHook = null;
                    seen.countDown();
                };
                assertThat(seen.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(after[1]).as("driving again").isEqualTo(1f);
                assertThat(after[0]).as("and moving, as fast as a tank gathers speed").isGreaterThan(x[2] + 5);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a room that fails ends the stays waiting in it: a resume goes back to the lobby")
    void aFailedRoomEndsItsWaitingStays() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = resumeServer(published, 250, 1_500)) {
            int port = server.start(0);
            Welcome first;
            try (TestClient a = new TestClient(port)) {
                a.join(issueTicket(0));
                first = Welcome.of(a.readFrame());
            }
            RoomThread room = server.registry().rooms().get(0);
            waitUntil(() -> room.suspendedCount() == 1);
            room.tickHook = () -> {
                throw new IllegalStateException("injected");
            };
            waitUntil(room::hasFailed);
            waitUntil(() -> published.size() == 1);        // the stay, published as it ended

            try (TestClient b = new TestClient(port)) {
                b.resume(first.resumeSecret());
                byte[] reply = readReply(b);
                assertThat(reply[0] & 0xFF).isEqualTo(Wire.MSG_KICK);
                // Its secret stayed in the arena's map, pointing at a dead room: Kick(5), "try
                // again", for a stay that no longer existed, and the room was never let go.
                assertThat(reply[1] & 0xFF).as("back to the lobby").isEqualTo(Wire.KICK_BAD_TICKET);
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a resume waiting in a room that hangs is answered when the room is given up on")
    void aResumeQueuedInAHungRoomIsAnswered() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = resumeServer(published, 250, 1_500)) {
            int port = server.start(0);
            Welcome first;
            try (TestClient a = new TestClient(port)) {
                a.join(issueTicket(0));
                first = Welcome.of(a.readFrame());
            }
            RoomThread room = server.registry().rooms().get(0);
            waitUntil(() -> room.suspendedCount() == 1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
            room.tickHook = () -> {
                if (once.compareAndSet(false, true)) {
                    try {
                        release.await(40, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            try (TestClient b = new TestClient(port)) {
                Thread.sleep(300);                          // the room is stuck now
                b.resume(first.resumeSecret());             // queued behind a thread that will not drain it
                b.soTimeout(25_000);
                byte[] reply = readReply(b);
                assertThat(reply[0] & 0xFF).isEqualTo(Wire.MSG_KICK);
                assertThat(reply[1] & 0xFF).as("sent away by the watchdog, not left in silence")
                        .isEqualTo(Wire.KICK_INTERNAL);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a failed room neither takes its counts with it nor leaves its clients in the gauges")
    void aDroppedRoomKeepsItsCounts() throws Exception {
        try (ArenaServer server = newServer(2_000f, 2_048, 20, 0, 2)) {
            int port = server.start(0);
            RoomRegistry registry = server.registry();
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                for (int i = 0; i < 10; i++) {
                    c.readFrame();
                }
                RoomThread room = registry.rooms().get(0);
                room.tickHook = () -> {
                    throw new IllegalStateException("injected");
                };
                waitUntil(room::hasFailed);
                assertThat(room.awaitStopped(5_000)).isTrue();
                // Its last tick's count stayed: a client nobody was sending to, in the gauge.
                for (TrafficProfile p : TrafficProfile.values()) {
                    assertThat(room.clientsAt(p)).as(p.label()).isZero();
                }
                long before = registry.total(RoomRegistry.Counter.SNAPSHOTS_SENT);
                long bytesBefore = registry.bytesSentAt(TrafficProfile.MOBILE);
                assertThat(before).isPositive();
                assertThat(bytesBefore).isPositive();

                registry.allocate();                        // drops it
                assertThat(registry.rooms()).doesNotContain(room);
                // Summed over the rooms there are, they fell back to zero with the room, and a
                // counter that falls reads as a restart to whatever scrapes it.
                assertThat(registry.total(RoomRegistry.Counter.SNAPSHOTS_SENT)).isGreaterThanOrEqualTo(before);
                assertThat(registry.bytesSentAt(TrafficProfile.MOBILE)).isGreaterThanOrEqualTo(bytesBefore);
                assertThat(registry.failedRooms()).isEqualTo(1);
            }
        }
    }

    private static byte[] readNormalSnapshot(TestClient c) throws IOException {
        for (int i = 0; i < 60; i++) {
            byte[] frame = c.readFrame();
            if ((frame[0] & 0xFF) == Wire.MSG_SNAPSHOT && !isDeathOnlySnapshot(frame)) {
                return frame;
            }
        }
        throw new AssertionError("no snapshot arrived");
    }

    private static void assertThatNoNormalSnapshotArrives(TestClient c) {
        try {
            readNormalSnapshot(c);
        } catch (IOException expected) {
            return;                                 // the read timed out, which is the point
        } catch (AssertionError expected) {
            return;
        }
        throw new AssertionError("a snapshot arrived when none should have");
    }

    private static void drainQuietly(TestClient c) throws IOException {
        c.soTimeout(300);
        try {
            for (int i = 0; i < 40; i++) {
                c.readFrame();
            }
        } catch (IOException drained) {
            // nothing left, which is what we wanted
        }
    }

    /** Reads the kick frame and asserts its reason, then expects the server to close. */
    /** A playing client has snapshots queued ahead of its kick: read through them to it. */
    private static void expectKickAfterSnapshots(TestClient client, int reason) throws IOException {
        for (int i = 0; i < 200; i++) {
            byte[] frame = client.readFrame();
            if ((frame[0] & 0xFF) == Wire.MSG_SNAPSHOT) {
                continue;
            }
            assertThat(frame[0] & 0xFF).as("a kicked client is told why").isEqualTo(Wire.MSG_KICK);
            assertThat(frame[1] & 0xFF).as("kick reason").isEqualTo(reason);
            assertThatEventuallyClosed(client);
            return;
        }
        throw new AssertionError("no kick within 200 frames");
    }

    /**
     * As above, through a match that runs a minute and more: a ping every 75 frames keeps the
     * client inside the arena's 30 s of silence (02 §1), and its Pongs are passed over.
     */
    private static void expectKickWhilePinging(TestClient client, int reason, int frames) throws IOException {
        for (int i = 0; i < frames; i++) {
            if (i % 75 == 0) {
                try {
                    client.ping(System.currentTimeMillis());
                } catch (IOException closing) {
                    // kicked and closed already: the kick is still there to read
                }
            }
            byte[] frame = client.readFrame();
            int type = frame[0] & 0xFF;
            if (type == Wire.MSG_SNAPSHOT || type == Wire.MSG_PONG) {
                continue;
            }
            assertThat(type).as("a kicked client is told why").isEqualTo(Wire.MSG_KICK);
            assertThat(frame[1] & 0xFF).as("kick reason").isEqualTo(reason);
            assertThatEventuallyClosed(client);
            return;
        }
        throw new AssertionError("no kick within " + frames + " frames");
    }

    private static void expectKick(TestClient client, int reason) throws IOException {
        byte[] frame = client.readFrame();
        assertThat(frame[0] & 0xFF).as("a refused client is told why").isEqualTo(Wire.MSG_KICK);
        assertThat(frame[1] & 0xFF).as("kick reason").isEqualTo(reason);
        assertThatEventuallyClosed(client);
    }

    @Test
    @Timeout(30)
    @DisplayName("a join with an unknown ticket is refused")
    void unknownTicketIsRefused() throws Exception {
        try (ArenaServer server = newServer(3000f, 4096, 50, 50, 4)) {
            int port = server.start(0);
            try (TestClient client = new TestClient(port)) {
                client.join(Ticket.newId());          // never issued

                expectKick(client, Wire.KICK_BAD_TICKET);
                assertThat(server.registry().totalPlayers())
                        .as("no tank was spawned for an unidentified client").isZero();
                assertThat(server.joins().get("bad_ticket")).as("counted (04 §11)").isEqualTo(1);
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a ticket cannot be replayed by a second connection")
    void replayedTicketIsRefused() throws Exception {
        try (ArenaServer server = newServer(3000f, 4096, 50, 50, 4)) {
            int port = server.start(0);
            String ticketId = issueTicket(0);

            try (TestClient first = new TestClient(port); TestClient second = new TestClient(port)) {
                first.join(ticketId);
                assertThat(first.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);

                // The interesting case is not a stolen ticket in the abstract: it is the
                // client that retries a join after a timeout, and must not end up with two
                // tanks answering to one player.
                second.join(ticketId);
                expectKick(second, Wire.KICK_BAD_TICKET);

                waitUntil(() -> server.registry().totalPlayers() == 1);
                assertThat(server.registry().totalPlayers()).isEqualTo(1);
                assertThat(server.joins().get("joined")).as("a ticket claimed and placed").isEqualTo(1);
                assertThat(server.joins().get("bad_ticket")).as("and its replay").isEqualTo(1);
            }
        }
    }

    /**
     * Bounded by the clock, not by a byte count.
     *
     * An earlier version read fifty bytes and gave up. That works for a client being sent
     * nothing, and fails for one being streamed snapshots at 15 Hz — it simply consumed
     * fifty bytes of snapshot before the close arrived.
     *
     * The server closes the connection within five seconds. A read that merely times out is
     * not a close: this used to count one as success, so a server that went silent and kept
     * the socket open passed every test that asked whether it had closed.
     */
    private static void assertThatEventuallyClosed(TestClient client) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        try {
            client.soTimeout(500);
            while (System.nanoTime() < deadline) {
                try {
                    if (client.socket.getInputStream().read() < 0) {
                        return;                                 // server closed: correct
                    }
                } catch (java.net.SocketTimeoutException stillOpen) {
                    // silent, not closed: keep waiting
                }
            }
        } catch (IOException reset) {
            return;                                             // reset by the server is a close
        }
        throw new AssertionError("the server kept the connection open");
    }

    @Test
    @Timeout(40)
    @DisplayName("rooms fill up and new ones open, until the room limit refuses a join")
    void roomsFillThenOverflow() throws Exception {
        int perRoom = 4;
        int maxRooms = 3;
        try (ArenaServer server = newServer(2000f, 2048, perRoom, 30, maxRooms)) {
            int port = server.start(0);
            var clients = new java.util.ArrayList<TestClient>();
            try {
                for (int i = 0; i < perRoom * maxRooms; i++) {
                    TestClient c = new TestClient(port);
                    clients.add(c);
                    c.join(issueTicket(0));
                    assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                }
                waitUntil(() -> server.registry().totalPlayers() == perRoom * maxRooms);

                assertThat(server.registry().totalPlayers()).isEqualTo(perRoom * maxRooms);
                assertThat(server.registry().roomCount())
                        .as("players are concentrated, not spread one per room")
                        .isEqualTo(maxRooms);
                for (RoomThread rt : server.registry().rooms()) {
                    assertThat(rt.playerCount()).isLessThanOrEqualTo(perRoom);
                }

                // Every room is full and no more may be created: the join must be refused,
                // not silently dropped or allowed to overfill a room.
                TestClient overflow = new TestClient(port);
                clients.add(overflow);
                overflow.join(issueTicket(0));
                expectKick(overflow, Wire.KICK_ROOM_FULL);
                assertThat(server.registry().totalPlayers()).isEqualTo(perRoom * maxRooms);
            } finally {
                for (TestClient c : clients) {
                    try {
                        c.close();
                    } catch (IOException ignored) {
                        // already closed by the server
                    }
                }
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a player who reconnects inside a timed match is one player in its result")
    void reconnectInsideATimedMatchIsOnePlayer() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        // 150 ticks is six seconds: room for a leave and a rejoin inside one match.
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 120, 2, 1,
                MatchRules.timed("arena-test", 150), published::add)) {
            int port = server.start(0);
            long ada = 900_000 + nextPlayerId++;
            try (TestClient bob = new TestClient(port)) {
                bob.join(issueTicket(0));
                assertThat(bob.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                int before = published.size();
                try (TestClient first = new TestClient(port)) {
                    first.join(ticketFor(ada));
                    assertThat(first.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                }
                Thread.sleep(300);
                try (TestClient again = new TestClient(port)) {
                    again.join(ticketFor(ada));            // a new ticket, the same player
                    assertThat(again.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                    // Wait for the match this happened in to end, and take its result.
                    waitUntil(() -> published.size() > before);
                }
                MatchOutcome match = published.get(before);
                assertThat(match.players()).filteredOn(p -> p.playerId() == ada)
                        .as("one entry, not one per connection").hasSize(1);
                assertThat(match.players()).extracting(MatchOutcome.PlayerOutcome::placement)
                        .as("no rank skipped for a phantom second entry")
                        .allMatch(p -> p <= match.players().size());
            }
        }
    }

    /** A ticket for a chosen player, as platform would issue one for a reconnect. */
    private static String ticketFor(long playerId) throws Exception {
        Ticket t = Ticket.forPlayer(playerId, "p-" + playerId, 0);
        tickets.issue(t).get(5, TimeUnit.SECONDS);
        return t.id();
    }

    @Test
    @Timeout(60)
    @DisplayName("a decoding client stays in step across a match boundary")
    void clientStaysInStepAcrossTheMatchBoundary() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 60, 2, 1,
                MatchRules.timed("arena-test", 50), published::add)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                ClientWorld w = new ClientWorld();
                int ended = published.size();
                // Play through at least one boundary: 50 ticks is two seconds.
                for (int i = 1; published.size() < ended + 1 || i < 20; i++) {
                    c.input(i, ClientMessage.MOVE_RIGHT, 0, 0);
                    applyNext(c, w);
                }
                // Standing still, but acknowledging as every client does while alive (02 §9):
                // one that stops is taken for a link that has stopped, and is sent less.
                for (int i = 0; i < 10; i++) {
                    applyNext(c, w);
                    c.acknowledge(100 + i, c.serverTick);
                }
                c.ping(System.currentTimeMillis() & 0xFFFFFFFFL);
                long serverTick = -1;
                while (serverTick < 0) {
                    byte[] f = c.readFrame();
                    if ((f[0] & 0xFF) == Wire.MSG_PONG) {
                        serverTick = TestClient.readVarint(
                                new java.io.ByteArrayInputStream(f, 5, f.length - 5));
                    } else if ((f[0] & 0xFF) == Wire.MSG_SNAPSHOT) {
                        w.apply(f);
                    }
                }
                // A new view at the boundary restarted the tick from zero, as P-6 did.
                assertThat(serverTick - w.serverTick()).as("client %d, server %d",
                        w.serverTick(), serverTick).isBetween(0L, 5L);
                assertThat(w.entity(Wire.SELF_HANDLE).alive).isTrue();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("a match ends on the clock and reports what each player did")
    void matchEndsAndReportsResults() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        // 50 ticks at 25 Hz is two seconds, so the test watches several whole matches.
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 120, 2, 1,
                MatchRules.timed("arena-test", 50), published::add)) {
            int port = server.start(0);
            try (TestClient a = new TestClient(port); TestClient b = new TestClient(port)) {
                a.join(issueTicket(0));
                b.join(issueTicket(0));
                assertThat(a.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);
                assertThat(b.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);

                for (int round = 0; round < 60 && published.size() < 2; round++) {
                    a.input(round + 1, ClientMessage.MOVE_RIGHT, round * 977, ClientMessage.FLAG_AUTOFIRE);
                    b.input(round + 1, ClientMessage.MOVE_LEFT, round * 613, ClientMessage.FLAG_AUTOFIRE);
                    Thread.sleep(100);
                }
            }
            waitUntil(() -> published.size() >= 2);

            assertThat(published).as("matches end on their own").hasSizeGreaterThanOrEqualTo(2);
            MatchOutcome first = published.get(0);
            assertThat(first.matchUid()).hasSize(26);
            assertThat(first.arena()).isEqualTo("arena-test");
            assertThat(first.endedAtMillis()).isGreaterThan(first.startedAtMillis());
            assertThat(first.players()).as("both players are in the result").hasSize(2);
            assertThat(first.players()).allSatisfy(p -> {
                assertThat(p.playerId()).isPositive();
                assertThat(p.placement()).isPositive();
                assertThat(p.deaths()).isNotNegative();
            });
            assertThat(first.players()).allSatisfy(p ->
                    assertThat(p.playtimeSeconds()).isNotNegative());
            // Deliberately no assertion on score here. A shape takes fifteen bullets and a
            // reset restores every shape to full health, so whether anything dies inside a
            // two-second match is luck: measured over four matches it went 0, 1, 2, 0. An
            // earlier version asserted a positive score, passed once and then failed three
            // runs in a row. Kills reaching the tally is covered deterministically by
            // RoomTallyIntegrationTest instead.

            assertThat(published.get(1).matchUid())
                    .as("each match gets its own id").isNotEqualTo(first.matchUid());
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("players keep playing across a match boundary")
    void playersSurviveTheMatchBoundary() throws Exception {
        List<MatchOutcome> published = new CopyOnWriteArrayList<>();
        try (ArenaServer server = new ArenaServer(tickets, 2_000f, 2_048, 20, 120, 2, 1,
                MatchRules.timed("arena-test", 25), published::add)) {
            int port = server.start(0);
            try (TestClient c = new TestClient(port)) {
                c.join(issueTicket(0));
                assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);

                int snapshotsAfterReset = 0;
                for (int round = 0; round < 80; round++) {
                    c.input(round + 1, ClientMessage.MOVE_UP, 0, ClientMessage.FLAG_AUTOFIRE);
                    byte[] frame = c.readFrame();
                    if ((frame[0] & 0xFF) == Wire.MSG_SNAPSHOT && !published.isEmpty()) {
                        snapshotsAfterReset++;
                    }
                }
                // The reset recycles every entity slot. A connection that kept its old id
                // would be reading whatever landed in that slot next, or be dropped.
                assertThat(published).as("at least one match ended during this").isNotEmpty();
                assertThat(snapshotsAfterReset)
                        .as("snapshots keep arriving after the world was reset").isPositive();
                assertThat(server.registry().totalPlayers()).isEqualTo(1);
            }
        }
    }

    /**
     * Waits for a condition, and fails if it never comes. This used to give up silently after
     * five seconds, so a test waiting longer carried on as if the condition held: one of them
     * (sparseFailuresAreSurvived) passed before the third fault it exists to test had happened.
     */
    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 30 s");
            }
            Thread.sleep(50);
        }
    }
}
