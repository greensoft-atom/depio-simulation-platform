package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Seasons (docs 04 §7, 06 §3, D-63), against a real MySQL: places, gems by place, the reset. */
class SeasonRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final Instant END = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant NEXT_END = Instant.parse("2027-01-01T00:00:00Z");

    private static Database db;
    private static AccountRepository accounts;
    private static SeasonRepository seasons;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        accounts = new AccountRepository(db.dataSource());
        seasons = new SeasonRepository(db.dataSource());
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() throws SQLException {
        db.resetForTests();
        sql("UPDATE season SET starts_at = '2026-09-01 00:00:00', ends_at = '2026-11-01 00:00:00' WHERE id = 1");
    }

    private static void sql(String statement, Object... args) throws SQLException {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static long[] row(String query, Object... args) throws SQLException {
        try (Connection c = db.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as(query).isTrue();
                long[] values = new long[rs.getMetaData().getColumnCount()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = rs.getLong(i + 1);
                }
                return values;
            }
        }
    }

    /** A player with these duel and ranked free-for-all ratings and rated counts. */
    private static long player(String name, int duel, int duels, int rffa, int rffas) throws SQLException {
        long id = accounts.register(name, name, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
        sql("UPDATE player SET rating_duel = ?, rated_duels = ?, rating_rffa = ?, rated_rffas = ? WHERE id = ?",
                duel, duels, rffa, rffas, id);
        return id;
    }

    @Test
    @DisplayName("season 1 is made by the migration, and ends where the rule says: the first of the next odd month, 00:00 UTC")
    void theMigrationsSeasonEndsAtTheBoundary() throws SQLException {
        db.resetForTests();                                      // as the migration left it
        SeasonRepository.Season one = seasons.current();
        assertThat(one.id()).isEqualTo(1);
        assertThat(one.endsAt()).isEqualTo(SeasonRepository.endAfter(one.startsAt()));
    }

    @Test
    @DisplayName("a season ends at 00:00 UTC on the first of January, March, May, July, September and November")
    void theCalendar() {
        assertThat(SeasonRepository.endAfter(Instant.parse("2026-10-03T12:00:00Z"))).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(SeasonRepository.endAfter(Instant.parse("2026-11-01T00:00:00Z"))).as("a season ended on time: the next two months")
                .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(SeasonRepository.endAfter(Instant.parse("2026-12-31T23:59:59Z"))).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(SeasonRepository.endAfter(Instant.parse("2028-02-29T10:00:00Z"))).isEqualTo(Instant.parse("2028-03-01T00:00:00Z"));
        assertThat(SeasonRepository.endAfter(Instant.parse("2027-01-01T00:00:00.001Z"))).isEqualTo(Instant.parse("2027-03-01T00:00:00Z"));
    }

    @Test
    @DisplayName("the gems a place pays: 100, 60, 40, 25 to the tenth, 10 to the hundredth, 5 to everyone else listed")
    void gemsByPlace() {
        assertThat(List.of(1L, 2L, 3L, 4L, 10L, 11L, 100L, 101L, 5_000L).stream().map(SeasonRepository::gems).toList())
                .containsExactly(100, 60, 40, 25, 25, 10, 10, 5, 5);
    }

    @Test
    @DisplayName("its places: each board's listed players in the board's order, with the gems each place pays; the next season made; once")
    void placesAndTheNextSeason() throws SQLException {
        long ada = player("ada", 1_500, 12, 1_200, 0);
        long bob = player("bob", 1_600, 10, 1_400, 15);
        long cyd = player("cyd", 1_500, 30, 1_200, 0);       // ties ada's rating, more rated: above her
        long dee = player("dee", 1_900, 9, 1_200, 0);        // nine: not listed
        assertThat(seasons.place(1, NEXT_END, END.plusSeconds(90))).as("the job a minute and a half late").isTrue();

        assertThat(seasons.top(1, RatingBoards.Board.DUEL, 10)).extracting(SeasonRepository.Place::playerId)
                .containsExactly(bob, cyd, ada);
        assertThat(seasons.top(1, RatingBoards.Board.DUEL, 10)).extracting(SeasonRepository.Place::gems)
                .containsExactly(100, 60, 40);
        assertThat(seasons.top(1, RatingBoards.Board.RFFA, 10)).extracting(SeasonRepository.Place::playerId)
                .containsExactly(bob);
        assertThat(seasons.top(1, RatingBoards.Board.TVT, 10)).isEmpty();
        SeasonRepository.Place cyds = seasons.placeOf(1, RatingBoards.Board.DUEL, cyd);
        assertThat(List.of(cyds.place(), (long) cyds.rating(), (long) cyds.rated())).containsExactly(2L, 1_500L, 30L);
        assertThat(seasons.placeOf(1, RatingBoards.Board.DUEL, dee)).as("not listed, no place").isNull();

        SeasonRepository.Season two = seasons.current();
        assertThat(List.of((long) two.id(), two.startsAt().toEpochMilli(), two.endsAt().toEpochMilli()))
                .containsExactly(2L, END.toEpochMilli(), NEXT_END.toEpochMilli());
        assertThat(seasons.recent(5)).extracting(SeasonRepository.Season::id).containsExactly(2, 1);
        assertThat(seasons.recent(5).get(1).placedAt()).isNotNull();
        assertThat(seasons.place(1, NEXT_END, END.plusSeconds(150))).as("once").isFalse();
        assertThat(row("SELECT COUNT(*) FROM season_place")[0]).isEqualTo(4);
        assertThat(row("SELECT COUNT(*) FROM season")[0]).isEqualTo(2);
    }

    @Test
    @DisplayName("not before its end: a season still running is not placed")
    void notBeforeItsEnd() throws SQLException {
        player("ada", 1_500, 12, 1_200, 0);
        assertThat(seasons.place(1, NEXT_END, END.minusMillis(1))).isFalse();
        assertThat(seasons.current().id()).isEqualTo(1);
        assertThat(row("SELECT COUNT(*) FROM season_place")[0]).isZero();
    }

    @Test
    @DisplayName("the reset: every rating halfway back to 1 200 and every rated count 0, in batches, up to the highest id at the end, once")
    void theResetInBatches() throws SQLException {
        long[] ids = new long[5];
        ids[0] = player("p0", 1_601, 40, 799, 12);           // 1 601 → 1 400, 799 → 1 000: halves rounded toward 1 200
        ids[1] = player("p1", 1_200, 0, 1_200, 0);
        ids[2] = player("p2", 2_400, 300, 1_300, 11);
        ids[3] = player("p3", 1_000, 10, 1_201, 10);
        ids[4] = player("p4", 1_300, 10, 1_300, 10);
        sql("UPDATE player SET rating_tvt = 1500, rated_tvts = 20 WHERE id = ?", ids[2]);
        assertThat(seasons.place(1, NEXT_END, END)).isTrue();
        long late = player("late", 1_350, 3, 1_200, 0);     // made after the season ended: the new one's already

        assertThat(seasons.resetBatch(1, 2, END)).as("more to do").isTrue();
        assertThat(row("SELECT rating_duel, rated_duels, rating_rffa, rated_rffas FROM player WHERE id = ?", ids[0]))
                .containsExactly(1_400, 0, 1_000, 0);
        assertThat(row("SELECT rating_duel, rated_duels FROM player WHERE id = ?", ids[2])).as("past the first batch: as it was")
                .containsExactly(2_400, 300);
        while (seasons.resetBatch(1, 2, END)) {
            // the rest
        }
        assertThat(row("SELECT rating_duel, rated_duels, rating_rffa, rated_rffas, rating_tvt, rated_tvts FROM player WHERE id = ?", ids[2]))
                .containsExactly(1_800, 0, 1_250, 0, 1_350, 0);
        assertThat(row("SELECT rating_duel, rating_rffa FROM player WHERE id = ?", ids[3])).containsExactly(1_100, 1_200);
        assertThat(row("SELECT rating_duel, rated_duels FROM player WHERE id = ?", late)).as("after the end: untouched")
                .containsExactly(1_350, 3);
        assertThat(seasons.recent(5).get(1).resetAt()).isNotNull();
        assertThat(seasons.resetBatch(1, 2, END)).as("done").isFalse();
        assertThat(row("SELECT rating_duel FROM player WHERE id = ?", ids[0])).as("and nobody halved twice").containsExactly(1_400);
        assertThat(seasons.top(1, RatingBoards.Board.DUEL, 10)).as("the season's places, kept").hasSize(4);
    }

    @Test
    @DisplayName("the reset waits for the places: a season not yet placed is not reset")
    void theResetWaitsForThePlaces() throws SQLException {
        long ada = player("ada", 1_500, 12, 1_200, 0);
        assertThat(seasons.resetBatch(1, 100, END)).isFalse();
        assertThat(row("SELECT rating_duel, rated_duels FROM player WHERE id = ?", ada)).containsExactly(1_500, 12);
    }

    @Test
    @DisplayName("paid, and the unfinished: a season placed is unfinished until paid and reset")
    void paidAndUnfinished() throws SQLException {
        player("ada", 1_500, 12, 1_200, 0);
        assertThat(seasons.unfinished()).isEmpty();
        seasons.place(1, NEXT_END, END);
        assertThat(seasons.unfinished()).extracting(SeasonRepository.Season::id).containsExactly(1);
        seasons.markPaid(1, END);
        assertThat(seasons.unfinished().get(0).paidAt()).isNotNull();
        while (seasons.resetBatch(1, 100, END)) {
            // the rest
        }
        assertThat(seasons.unfinished()).as("its teams not yet reset").extracting(SeasonRepository.Season::id).containsExactly(1);
        seasons.resetTeams(1, END);
        assertThat(seasons.unfinished()).isEmpty();
    }

    @Test
    @DisplayName("an operator ends the season now: it ends then, and only a season still running")
    void endNow() throws SQLException {
        Instant now = Instant.parse("2026-10-15T09:30:00Z");
        SeasonRepository.Season ended = seasons.endNow(now);
        assertThat(ended.id()).isEqualTo(1);
        assertThat(seasons.current().endsAt()).isEqualTo(now);
        assertThat(seasons.endNow(now.plusSeconds(60))).as("already ended, not yet placed: nothing to end").isNull();
    }

    /** A match ended then, played for a team on side 1 by these players: rated unless cut short or not a team match. */
    private static void playedFor(long team, String endedAt, boolean cutShort, int mode, long... players) throws SQLException {
        try (Connection c = db.dataSource().getConnection()) {
            long match;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO matches (match_uid, mode, kind, arena, started_at,"
                    + " ended_at, cut_short) VALUES (?, ?, 1, 'arena-1', ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, String.format("01JBTEAMSEASON%012d", System.nanoTime() % 1_000_000_000_000L));
                ps.setInt(2, mode);
                ps.setString(3, endedAt);
                ps.setString(4, endedAt);
                ps.setBoolean(5, cutShort);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    match = keys.getLong(1);
                }
            }
            sqlOn(c, "INSERT INTO match_team (match_id, side, team_id, placement, rating_delta) VALUES (?, 1, ?, 1, 0)", match, team);
            for (long p : players) {
                sqlOn(c, "INSERT INTO match_player (match_id, player_id, team, placement, kills, deaths, score, xp_gained)"
                        + " VALUES (?, ?, 1, 1, 0, 0, 0, 0)", match, p);
            }
        }
    }

    private static void sqlOn(Connection c, String statement, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("its teams' places, and whom each pays: a member at the end who played one of the team's rated team matches in the season (D-65)")
    void teamPlacesAndPayees() throws SQLException {
        long ada = player("ada", 1_200, 0, 1_200, 0);
        long amy = player("amy", 1_200, 0, 1_200, 0);
        long bob = player("bob", 1_200, 0, 1_200, 0);
        long ben = player("ben", 1_200, 0, 1_200, 0);
        long cyd = player("cyd", 1_200, 0, 1_200, 0);
        long dan = player("dan", 1_200, 0, 1_200, 0);
        long alpha = TeamBoardsTest.team(db, "Alpha", 1_600, 12, ada, amy);
        long bravo = TeamBoardsTest.team(db, "Bravo", 1_500, 10, bob, ben, cyd);
        long charlie = TeamBoardsTest.team(db, "Charlie", 1_400, 9, dan);        // nine: not listed
        playedFor(alpha, "2026-10-10 12:00:00", false, 5, ada);                   // paid
        playedFor(alpha, "2026-10-14 12:00:00", false, 2, amy);                   // team-vs-team, not a team match
        playedFor(bravo, "2026-08-20 12:00:00", false, 5, bob);                   // before the season
        playedFor(bravo, "2026-10-11 12:00:00", false, 5, ben);                   // paid
        playedFor(bravo, "2026-10-12 12:00:00", true, 5, cyd);                    // cut short: not rated
        playedFor(alpha, "2026-10-13 12:00:00", false, 5, cyd);                   // for Alpha, but in Bravo at the end
        playedFor(charlie, "2026-10-13 12:00:00", false, 5, dan);                 // a team not listed
        // Eve played against Alpha, for Charlie's side, and joined Alpha after: not Alpha's payee.
        long eve = player("eve", 1_200, 0, 1_200, 0);
        sql("INSERT INTO team_member (team_id, player_id, role) VALUES (?, ?, 0)", alpha, eve);
        try (Connection c = db.dataSource().getConnection()) {
            sqlOn(c, "INSERT INTO matches (match_uid, mode, kind, arena, started_at, ended_at)"
                    + " VALUES ('01JBTEAMSEASONAGAINST00001', 5, 1, 'arena-1', '2026-10-15 12:00:00', '2026-10-15 12:00:00')");
            sqlOn(c, "INSERT INTO match_team (match_id, side, team_id, placement, rating_delta)"
                    + " SELECT id, 1, ?, 1, 0 FROM matches WHERE match_uid = '01JBTEAMSEASONAGAINST00001'", alpha);
            sqlOn(c, "INSERT INTO match_team (match_id, side, team_id, placement, rating_delta)"
                    + " SELECT id, 2, ?, 2, 0 FROM matches WHERE match_uid = '01JBTEAMSEASONAGAINST00001'", charlie);
            sqlOn(c, "INSERT INTO match_player (match_id, player_id, team, placement, kills, deaths, score, xp_gained)"
                    + " SELECT id, ?, 2, 2, 0, 0, 0, 0 FROM matches WHERE match_uid = '01JBTEAMSEASONAGAINST00001'", eve);
        }
        assertThat(seasons.place(1, NEXT_END, END)).isTrue();

        assertThat(seasons.teamTop(1, 10)).extracting(TeamBoards.Row::teamId, TeamBoards.Row::name, TeamBoards.Row::rating)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(alpha, "Alpha", 1_600),
                        org.assertj.core.groups.Tuple.tuple(bravo, "Bravo", 1_500));
        assertThat(seasons.teamPayees(1, 0, 10)).extracting(SeasonRepository.TeamPayee::playerId,
                SeasonRepository.TeamPayee::teamId, SeasonRepository.TeamPayee::gems)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ada, alpha, 100),
                        org.assertj.core.groups.Tuple.tuple(ben, bravo, 60));
        assertThat(seasons.teamPayees(1, ada, 10)).as("a page after ada").extracting(SeasonRepository.TeamPayee::playerId)
                .containsExactly(ben);
        TeamBoards.Place adas = seasons.teamPlaceOf(1, ada, 1);
        assertThat(List.of(adas.rank(), (long) adas.rating())).containsExactly(1L, 1_600L);
        assertThat(adas.window()).extracting(TeamBoards.Row::name).containsExactly("Alpha", "Bravo");
        assertThat(seasons.teamPlaceOf(1, amy, 1)).as("a member who did not play for it: no place").isNull();
        TeamBoards.Place bens = seasons.teamPlaceOf(1, ben, 1);
        assertThat(bens.rank()).isEqualTo(2);
        assertThat(bens.window()).extracting(TeamBoards.Row::name).as("the place before it too").containsExactly("Alpha", "Bravo");
        TeamBoardsTest.team(db, "Echo", 1_300, 0);                                 // renamed or made since: the place keeps its name
        assertThat(row("SELECT COUNT(*) FROM season_team_place")[0]).isEqualTo(2);
    }

    @Test
    @DisplayName("the teams' reset: every rating halfway back to 1 200 and every rated count 0, the record kept; once; a team made after the end untouched")
    void theTeamsReset() throws SQLException {
        long alpha = TeamBoardsTest.team(db, "Alpha", 1_601, 40, player("a1", 1_200, 0, 1_200, 0));
        long low = TeamBoardsTest.team(db, "Low", 799, 12, player("l1", 1_200, 0, 1_200, 0));
        sql("UPDATE team SET wins = 7, losses = 3, draws = 1 WHERE id = ?", alpha);
        assertThat(seasons.resetTeams(1, END)).as("not before the places").isFalse();
        assertThat(seasons.place(1, NEXT_END, END)).isTrue();
        long late = TeamBoardsTest.team(db, "Late", 1_350, 3, player("t1", 1_200, 0, 1_200, 0));

        assertThat(seasons.unfinished().get(0).teamResetAt()).isNull();
        assertThat(seasons.resetTeams(1, END)).isTrue();
        assertThat(row("SELECT rating, rated_matches, wins, losses, draws FROM team WHERE id = ?", alpha))
                .containsExactly(1_400, 0, 7, 3, 1);
        assertThat(row("SELECT rating, rated_matches FROM team WHERE id = ?", low)).containsExactly(1_000, 0);
        assertThat(row("SELECT rating, rated_matches FROM team WHERE id = ?", late)).as("after the end: untouched")
                .containsExactly(1_350, 3);
        assertThat(seasons.resetTeams(1, END)).as("once").isFalse();
        assertThat(row("SELECT rating FROM team WHERE id = ?", alpha)).containsExactly(1_400);

        seasons.markPaid(1, END);
        while (seasons.resetBatch(1, 100, END)) {
            // the players
        }
        assertThat(seasons.unfinished()).as("paid, its players and its teams reset").isEmpty();
    }

    @Test
    @DisplayName("a season's places read in pages, by board then place, for paying")
    void placesInPages() throws SQLException {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(player("q" + i, 1_500 - i, 10, 1_400 - i, 10));
        }
        seasons.place(1, NEXT_END, END);
        List<SeasonRepository.Place> all = new ArrayList<>();
        List<SeasonRepository.Place> page = seasons.places(1, 0, 0, 3);
        while (!page.isEmpty()) {
            all.addAll(page);
            SeasonRepository.Place last = page.get(page.size() - 1);
            page = seasons.places(1, last.board(), last.place(), 3);
        }
        assertThat(all).hasSize(10);
        assertThat(all).extracting(SeasonRepository.Place::board).containsExactly(1, 1, 1, 1, 1, 3, 3, 3, 3, 3);
        assertThat(all).extracting(SeasonRepository.Place::playerId).startsWith(ids.get(0), ids.get(1));
    }
}
