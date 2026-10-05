package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The rating boards (04 §7, Q-40), against a real MySQL. */
class RatingBoardsTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static Database db;
    private static AccountRepository accounts;
    private static RatingBoards boards;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        accounts = new AccountRepository(db.dataSource());
        boards = new RatingBoards(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
    }

    /** A player with a duel rating after so many rated duels, and a ranked free-for-all's of 1 500 after twenty. */
    private static long player(String name, int rating, int rated) throws SQLException {
        long id = accounts.register(name, name, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE player SET rating_duel = ?, rated_duels = ?,"
                     + " rating_rffa = 1500, rated_rffas = 20 WHERE id = ?")) {
            ps.setInt(1, rating);
            ps.setInt(2, rated);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
        return id;
    }

    @Test
    @DisplayName("the top: listed after ten rated matches, by rating, then more rated matches, then id; ranks from 1")
    void top() throws SQLException {
        long a = player("ada", 1_300, 12);
        long b = player("bob", 1_400, 9);                  // nine: not yet listed
        long c = player("cyd", 1_300, 30);
        long d = player("dee", 1_250, 10);                 // ten: listed
        long e = player("eve", 1_300, 12);
        List<RatingBoards.Row> top = boards.top(RatingBoards.Board.DUEL, 10);
        assertThat(top).extracting(RatingBoards.Row::playerId).containsExactly(c, Math.min(a, e), Math.max(a, e), d);
        assertThat(top).extracting(RatingBoards.Row::rank).containsExactly(1L, 2L, 3L, 4L);
        assertThat(top.get(0).name()).isEqualTo("cyd");
        assertThat(top.get(0).rating()).isEqualTo(1_300);
        assertThat(boards.top(RatingBoards.Board.DUEL, 2)).hasSize(2);
        assertThat(boards.top(RatingBoards.Board.RFFA, 10)).as("another mode's board, its own columns")
                .hasSize(5).allSatisfy(r -> assertThat(r.rating()).isEqualTo(1_500));
        assertThat(boards.top(RatingBoards.Board.TVT, 10)).as("nobody rated there").isEmpty();
        assertThat(b).isPositive();
    }

    @Test
    @DisplayName("a player's own place: their rank, counting those above under the same order, and the rows either side")
    void around() throws SQLException {
        long[] ids = new long[12];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = player("p" + i, 2_000 - i * 10, 10);  // p0 highest
        }
        long unlisted = player("new", 1_900, 3);
        RatingBoards.Place p5 = boards.place(RatingBoards.Board.DUEL, ids[5], 2);
        assertThat(p5.rank()).isEqualTo(6);
        assertThat(p5.rating()).isEqualTo(1_950);
        assertThat(p5.window()).extracting(RatingBoards.Row::playerId).containsExactly(ids[3], ids[4], ids[5], ids[6], ids[7]);
        assertThat(p5.window()).extracting(RatingBoards.Row::rank).containsExactly(4L, 5L, 6L, 7L, 8L);
        assertThat(boards.place(RatingBoards.Board.DUEL, ids[0], 2).window()).as("at the top, the window starts there")
                .extracting(RatingBoards.Row::rank).containsExactly(1L, 2L, 3L);
        assertThat(boards.place(RatingBoards.Board.DUEL, unlisted, 2)).as("not listed yet").isNull();

        long tied = player("tie", 1_950, 11);              // above p5: same rating, more rated matches
        assertThat(boards.place(RatingBoards.Board.DUEL, ids[5], 2).rank()).isEqualTo(7);
        assertThat(boards.place(RatingBoards.Board.DUEL, tied, 0).rank()).isEqualTo(6);
    }

    @Test
    @DisplayName("the window is read by key: ties in the board's order at its edges, and none after the last (D-57)")
    void theWindowByKey() throws SQLException {
        long[] tied = new long[5];
        for (int i = 0; i < tied.length; i++) {
            tied[i] = player("tie" + i, 1_500, 20);       // one rating, one count: by id
        }
        long top = player("top", 1_600, 10);
        long last = player("last", 900, 10);
        RatingBoards.Place mid = boards.place(RatingBoards.Board.DUEL, tied[2], 2);
        assertThat(mid.rank()).isEqualTo(4);
        assertThat(mid.window()).extracting(RatingBoards.Row::playerId).containsExactly(tied[0], tied[1], tied[2], tied[3], tied[4]);
        assertThat(mid.window()).extracting(RatingBoards.Row::rank).containsExactly(2L, 3L, 4L, 5L, 6L);
        RatingBoards.Place bottom = boards.place(RatingBoards.Board.DUEL, last, 2);
        assertThat(bottom.rank()).isEqualTo(7);
        assertThat(bottom.window()).extracting(RatingBoards.Row::playerId).as("none after the last").containsExactly(tied[3], tied[4], last);
        assertThat(boards.place(RatingBoards.Board.DUEL, top, 3).window()).extracting(RatingBoards.Row::rank)
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("a place, and the top, read the listed players and not the accounts never rated, however many (D-35, D-57)")
    void aPlaceReadsOnlyTheListed() throws Exception {
        for (int i = 0; i < 300; i++) {
            accounts.register("idle" + i, "idle" + i, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
        }
        player("ada", 1_400, 10);
        player("bob", 1_300, 10);
        player("cyd", 1_100, 10);
        long dee = player("dee", 1_000, 10);
        assertThat(rowsRead(b -> b.place(RatingBoards.Board.DUEL, dee, 2))).as("the bottom's place").isLessThan(40);
        assertThat(rowsRead(b -> b.top(RatingBoards.Board.DUEL, 10))).as("the top").isLessThan(40);
    }

    @Test
    @DisplayName("the schema lists a player at the code's ten rated matches, and not before (D-57)")
    void theThresholdIsTheSchemas() throws SQLException {
        long nine = player("nine", 1_300, RatingBoards.MIN_RATED - 1);
        long ten = player("ten", 1_300, RatingBoards.MIN_RATED);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, board_duel FROM player WHERE id IN (?, ?)")) {
            ps.setLong(1, nine);
            ps.setLong(2, ten);
            java.util.Map<Long, Object> listed = new java.util.HashMap<>();
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    listed.put(rs.getLong(1), rs.getObject(2));
                }
            }
            assertThat(listed.get(nine)).isNull();
            assertThat(((Number) listed.get(ten)).intValue()).isEqualTo(1_300);
        }
    }

    /** What a board read cost: the index rows MySQL read for it, on one connection, its own reads of the count left out. */
    private static long rowsRead(Read read) throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            Connection kept = (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, m, args) -> {
                        if (m.getName().equals("close")) {
                            return null;
                        }
                        try {
                            return m.invoke(c, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            javax.sql.DataSource one = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                    javax.sql.DataSource.class.getClassLoader(), new Class<?>[] {javax.sql.DataSource.class},
                    (proxy, m, args) -> {
                        if (m.getName().equals("getConnection")) {
                            return kept;
                        }
                        throw new UnsupportedOperationException(m.getName());
                    });
            long before = handlerReads(c);
            long nothing = handlerReads(c) - before;          // what reading the count costs itself
            long start = handlerReads(c);
            read.on(new RatingBoards(one));
            return handlerReads(c) - start - nothing;
        }
    }

    private static long handlerReads(Connection c) throws SQLException {
        long sum = 0;
        try (java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SHOW SESSION STATUS WHERE Variable_name IN ('Handler_read_first',"
                     + " 'Handler_read_key', 'Handler_read_last', 'Handler_read_next', 'Handler_read_prev')")) {
            while (rs.next()) {
                sum += rs.getLong(2);
            }
        }
        return sum;
    }

    @FunctionalInterface
    private interface Read {
        Object on(RatingBoards boards) throws Exception;
    }
}
