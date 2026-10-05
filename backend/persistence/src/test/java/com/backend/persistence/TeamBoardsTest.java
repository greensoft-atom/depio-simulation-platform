package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The board of teams (docs 04 §7, D-65): the player boards' kind, over the team table. */
class TeamBoardsTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");

    private static Database db;
    private static AccountRepository accounts;
    private static TeamBoards boards;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        accounts = new AccountRepository(db.dataSource());
        boards = new TeamBoards(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
    }

    private static long player(String name) throws SQLException {
        return accounts.register(name, name, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
    }

    /** A team with this rating after so many rated team matches, and these members, the first its leader. */
    static long team(Database db, String name, int rating, int rated, long... members) throws SQLException {
        try (Connection c = db.dataSource().getConnection()) {
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO team (name, member_count, rating, rated_matches)"
                    + " VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setInt(2, members.length);
                ps.setInt(3, rating);
                ps.setInt(4, rated);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            for (int i = 0; i < members.length; i++) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO team_member (team_id, player_id, role) VALUES (?, ?, ?)")) {
                    ps.setLong(1, id);
                    ps.setLong(2, members[i]);
                    ps.setInt(3, i == 0 ? 2 : 0);
                    ps.executeUpdate();
                }
            }
            return id;
        }
    }

    @Test
    @DisplayName("the top: teams listed after ten rated team matches, by rating, then more rated matches, then id; ranks from 1")
    void top() throws SQLException {
        long a = team(db, "Alpha", 1_500, 12, player("a1"));
        long b = team(db, "Bravo", 1_600, 9, player("b1"));            // nine: not listed
        long c = team(db, "Charlie", 1_500, 30, player("c1"));
        long d = team(db, "Delta", 1_450, 10, player("d1"));
        List<TeamBoards.Row> top = boards.top(10);
        assertThat(top).extracting(TeamBoards.Row::teamId).containsExactly(c, a, d);
        assertThat(top).extracting(TeamBoards.Row::rank).containsExactly(1L, 2L, 3L);
        assertThat(top.get(0).name()).isEqualTo("Charlie");
        assertThat(top.get(0).rating()).isEqualTo(1_500);
        assertThat(boards.top(1)).hasSize(1);
        assertThat(b).isPositive();
    }

    @Test
    @DisplayName("a player's own team's place, the teams either side; null for a team not listed; NO_TEAM for none")
    void theCallersTeam() throws SQLException {
        long[] leaders = new long[6];
        for (int i = 0; i < leaders.length; i++) {
            leaders[i] = player("l" + i);
            team(db, "T" + i, 2_000 - i * 10, 10, leaders[i]);
        }
        long member = player("m3");
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO team_member (team_id, player_id, role)"
                     + " SELECT team_id, ?, 0 FROM team_member WHERE player_id = ?")) {
            ps.setLong(1, member);
            ps.setLong(2, leaders[3]);
            ps.executeUpdate();
        }
        TeamBoards.Place mine = boards.placeOfPlayer(member, 1);
        assertThat(mine.rank()).isEqualTo(4);
        assertThat(mine.rating()).isEqualTo(1_970);
        assertThat(mine.window()).extracting(TeamBoards.Row::name).containsExactly("T2", "T3", "T4");
        assertThat(mine.window()).extracting(TeamBoards.Row::rank).containsExactly(3L, 4L, 5L);

        long fresh = player("fresh");
        team(db, "New", 1_200, 2, fresh);
        assertThat(boards.placeOfPlayer(fresh, 1)).as("a team not yet listed").isNull();
        assertThat(boards.placeOfPlayer(player("loner"), 1)).as("in no team").isSameAs(TeamBoards.NO_TEAM);
    }

    @Test
    @DisplayName("the player boards are as they were: a team's rating is in no player board")
    void thePlayerBoardsUntouched() throws SQLException {
        team(db, "Alpha", 1_500, 12, player("a1"));
        assertThat(new RatingBoards(db.dataSource()).top(RatingBoards.Board.DUEL, 10)).isEmpty();
    }
}
