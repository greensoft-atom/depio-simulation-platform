package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import com.backend.arena.ArenaAnnouncer;
import com.backend.arena.ArenaServer;
import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.SessionStore;
import com.backend.handoff.TicketStore;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.Database;
import com.backend.protocol.ClientMessage;
import com.backend.protocol.Wire;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The whole path, with nothing stubbed: register and log in against MySQL, ask platform for
 * a match, and connect to a real arena over TCP with the ticket it hands back.
 *
 * It exists because every part of this chain passed its own tests while the chain itself had
 * never run. The interesting failures live in the joins between components — a ticket
 * written with one field name and read with another, an arena that never announces itself,
 * an endpoint advertised on the wrong port — and none of those are visible from inside a
 * single module.
 */
class PlatformToArenaTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static Database db;
    private static JRedisEmbedded store;
    private static JRedisClient client;

    private static AccountRepository accounts;
    private static AuthService auth;
    private static ArenaDirectory directory;
    private static TicketStore tickets;
    private static JoinService joins;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 8);
        db.resetForTests();
        store = JRedisEmbedded.start();
        client = store.newClient();

        accounts = new AccountRepository(db.dataSource());
        auth = new AuthService(accounts, new PasswordHasher(), new SessionStore(client));
        directory = new ArenaDirectory(client);
        tickets = new TicketStore(client);
        joins = new JoinService(auth, accounts, directory, tickets,
                playerId -> new byte[com.backend.handoff.Ticket.BONUS_STATS]);   // nobody wears anything here
    }

    @AfterAll
    static void tearDown() {
        if (store != null) {
            store.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
        client.sync().send("FLUSHALL");
    }

    @Test
    @Timeout(60)
    @DisplayName("register, log in, get a ticket, and play")
    void theWholeChain() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 3000f, 4096, 50, 200, 2, 4)) {
            int port = server.start(0);
            try (ArenaAnnouncer announcer = new ArenaAnnouncer(
                    directory, "arena-test", "127.0.0.1", port, server.registry())) {
                announcer.start();

                long playerId = auth.register("ada", "Ada", "hunter2-hunter2".toCharArray()).playerId();
                String token = auth.login("ada", "hunter2-hunter2".toCharArray()).token();

                JoinService.Grant grant = joins.requestJoin(token);

                assertThat(grant.outcome()).isEqualTo(JoinService.Outcome.OK);
                assertThat(grant.arenaHost()).isEqualTo("127.0.0.1");
                assertThat(grant.arenaPort()).as("the advertised port is the one bound").isEqualTo(port);

                try (TestClient c = new TestClient(grant.arenaPort())) {
                    c.join(grant.ticketId());
                    assertThat(c.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);

                    // Asserted while the socket is open. Outside the try block this waited
                    // for a count of 1 after having just closed the connection that produced
                    // it, and passed only while the leave had not been drained yet.
                    waitUntil(() -> server.registry().totalPlayers() == 1);
                    assertThat(server.registry().totalPlayers()).isEqualTo(1);
                    c.leave();
                }

                waitUntil(() -> server.registry().totalPlayers() == 0);
                assertThat(server.registry().totalPlayers())
                        .as("and leaving frees the slot").isZero();
                assertThat(playerId).isPositive();
            }
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("the ticket platform issued cannot be used twice")
    void grantedTicketIsSingleUse() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 3000f, 4096, 50, 200, 2, 4)) {
            int port = server.start(0);
            try (ArenaAnnouncer announcer = new ArenaAnnouncer(
                    directory, "arena-test", "127.0.0.1", port, server.registry())) {
                announcer.start();
                auth.register("ada", "Ada", "hunter2-hunter2".toCharArray());
                String token = auth.login("ada", "hunter2-hunter2".toCharArray()).token();
                JoinService.Grant grant = joins.requestJoin(token);

                try (TestClient first = new TestClient(grant.arenaPort());
                     TestClient second = new TestClient(grant.arenaPort())) {
                    first.join(grant.ticketId());
                    assertThat(first.readFrame()[0] & 0xFF).isEqualTo(Wire.MSG_WELCOME);

                    second.join(grant.ticketId());
                    byte[] kick = second.readFrame();
                    assertThat(kick[0] & 0xFF).isEqualTo(Wire.MSG_KICK);
                    assertThat(kick[1] & 0xFF).isEqualTo(Wire.KICK_BAD_TICKET);
                }
            }
        }
    }

    @Test
    @DisplayName("a session that does not exist gets no ticket")
    void unknownSessionIsRefused() throws Exception {
        assertThat(joins.requestJoin("not-a-session").outcome())
                .isEqualTo(JoinService.Outcome.NO_SESSION);
        assertThat(joins.requestJoin(null).outcome()).isEqualTo(JoinService.Outcome.NO_SESSION);
    }

    @Test
    @DisplayName("with no arena announced, nobody is sent anywhere")
    void noArenaMeansNoGrant() throws Exception {
        auth.register("ada", "Ada", "hunter2-hunter2".toCharArray());
        String token = auth.login("ada", "hunter2-hunter2".toCharArray()).token();

        JoinService.Grant grant = joins.requestJoin(token);

        // The alternative is handing out an endpoint that nothing is listening on, which
        // the client can only discover by failing to connect.
        assertThat(grant.outcome()).isEqualTo(JoinService.Outcome.NO_ARENA);
        assertThat(grant.ticketId()).as("no ticket is spent when there is nowhere to go").isNull();
    }

    @Test
    @Timeout(60)
    @DisplayName("an arena that stops announcing drops out of the directory")
    void withdrawnArenaIsNotOffered() throws Exception {
        try (ArenaServer server = new ArenaServer(tickets, 3000f, 4096, 50, 200, 2, 4)) {
            int port = server.start(0);
            ArenaAnnouncer announcer = new ArenaAnnouncer(
                    directory, "arena-test", "127.0.0.1", port, server.registry());
            announcer.start();
            assertThat(directory.pick()).isNotNull();

            announcer.close();

            // A clean shutdown withdraws rather than waiting out the TTL, so the next player
            // is not sent to a process that is on its way down.
            assertThat(directory.pick()).isNull();
            assertThat(directory.live()).isEmpty();
        }
    }

    // ---- a minimal client, so the test exercises the wire rather than a shared codec ----

    private static final class TestClient implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;

        TestClient(int port) throws IOException {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(10_000);
            in = new DataInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        void join(String ticketId) throws IOException {
            byte[] ticket = ticketId.getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(ClientMessage.JOIN);
            body.write(Wire.VERSION);
            body.write(ticket.length);                 // < 128, so one varint byte
            body.write(ticket, 0, ticket.length);

            byte[] payload = body.toByteArray();
            ByteArrayOutputStream framed = new ByteArrayOutputStream();
            framed.write(payload.length);
            framed.write(payload);
            out.write(framed.toByteArray());
            out.flush();
        }

        /** Leaving on purpose; a connection merely lost keeps its place for a resume (02 §10). */
        void leave() throws IOException {
            out.write(new byte[] {1, (byte) ClientMessage.LEAVE});
            out.flush();
        }

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

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /** Fails if the condition never comes; a silent give-up lets a test pass on nothing. */
    private static void waitUntil(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 30 s");
            }
            Thread.sleep(50);
        }
    }
}
