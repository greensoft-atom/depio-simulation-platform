package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * Seasons of the rating boards (docs detailed-design/04-platform-services.md §7, 06 §3, D-63): two
 * calendar months each. When one ends it is closed in three steps, each recorded in its row and
 * each safe to run again: its places, its gems (paid by the worker from the places), its reset.
 */
public final class SeasonRepository {

    /** The rating a season's reset moves every rating halfway back to. */
    public static final int RESET_TOWARDS = 1_200;

    /**
     * The gems a place pays, a first cut of the balance (04 §7): up to and including each place,
     * then {@link #LISTED_GEMS} for every other player listed. Against a boost's 20.
     */
    private static final long[][] PLACE_GEMS = {{1, 100}, {2, 60}, {3, 40}, {10, 25}, {100, 10}};
    static final int LISTED_GEMS = 5;

    public record Season(int id, Instant startsAt, Instant endsAt, Instant placedAt, Instant paidAt, Instant resetAt,
                         Instant teamResetAt) { }



    /** A player's final place on a board in a season; {@code board} the mode's id. */
    public record Place(int seasonId, int board, long place, long playerId, String name, int rating, int rated, int gems) { }

    private static final String SEASON_COLUMNS = "id, starts_at, ends_at, placed_at, paid_at, reset_at, team_reset_at";

    private final DataSource ds;
    private final Tx tx;

    public SeasonRepository(DataSource ds) {
        this.ds = ds;
        this.tx = new Tx(ds);
    }

    /**
     * When a season that starts or is running at {@code t} ends: 00:00 UTC on the first of the next
     * odd month after the two-month block {@code t} is in (V27 makes season 1 by the same rule).
     */
    public static Instant endAfter(Instant t) {
        LocalDate first = t.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        LocalDate blockStart = first.minusMonths((first.getMonthValue() - 1) % 2);
        return blockStart.plusMonths(2).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public static int gems(long place) {
        for (long[] upTo : PLACE_GEMS) {
            if (place <= upTo[0]) {
                return (int) upTo[1];
            }
        }
        return LISTED_GEMS;
    }

    /** {@link #gems} in SQL, over a column holding the place. */
    private static String gemsCase(String place) {
        StringBuilder sql = new StringBuilder("CASE");
        for (long[] upTo : PLACE_GEMS) {
            sql.append(" WHEN ").append(place).append(" <= ").append(upTo[0]).append(" THEN ").append(upTo[1]);
        }
        return sql.append(" ELSE ").append(LISTED_GEMS).append(" END").toString();
    }

    /** The season being played: the newest not yet placed. */
    public Season current() throws SQLException {
        List<Season> one = seasons("SELECT " + SEASON_COLUMNS + " FROM season WHERE placed_at IS NULL ORDER BY id DESC LIMIT 1");
        return one.isEmpty() ? null : one.get(0);
    }

    /** A season by its number, or null. */
    public Season get(int id) throws SQLException {
        List<Season> one = seasons("SELECT " + SEASON_COLUMNS + " FROM season WHERE id = " + id);
        return one.isEmpty() ? null : one.get(0);
    }

    /** The newest seasons first, the current among them. */
    public List<Season> recent(int limit) throws SQLException {
        return seasons("SELECT " + SEASON_COLUMNS + " FROM season ORDER BY id DESC LIMIT " + limit);
    }

    /** Seasons placed but not yet both paid and reset, oldest first: the season job's work. */
    public List<Season> unfinished() throws SQLException {
        return seasons("SELECT " + SEASON_COLUMNS + " FROM season WHERE placed_at IS NOT NULL"
                + " AND (paid_at IS NULL OR reset_at IS NULL OR team_reset_at IS NULL) ORDER BY id");
    }

    /**
     * An operator's "end it now" (04 §7): the current season ends at {@code now}, if it would end later.
     *
     * @return the season it ended, or null when the current one has ended already
     */
    public Season endNow(Instant at) throws SQLException {
        Instant now = at.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);       // as the column holds it
        return tx.execute(c -> {
            Season open;
            try (PreparedStatement ps = c.prepareStatement("SELECT " + SEASON_COLUMNS
                    + " FROM season WHERE placed_at IS NULL ORDER BY id DESC LIMIT 1 FOR UPDATE")) {
                List<Season> one = read(ps);
                if (one.isEmpty() || !one.get(0).endsAt().isAfter(now)) {
                    return null;
                }
                open = one.get(0);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE season SET ends_at = ? WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setInt(2, open.id());
                ps.executeUpdate();
            }
            return new Season(open.id(), open.startsAt(), now, null, null, null, null);
        });
    }

    /**
     * The first step (D-63): every board's listed players, in the board's order, written as the
     * season's places with the gems each pays; the highest player id then, which the reset stops
     * at; and the next season, from this one's end to {@code nextEndsAt}. One transaction.
     *
     * @return whether this call wrote them: false when the season has not ended by {@code now},
     *         or was placed already
     */
    public boolean place(int seasonId, Instant nextEndsAt, Instant now) throws SQLException {
        return tx.execute(c -> {
            Instant endsAt;
            Timestamp startsAt;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ends_at, placed_at, starts_at FROM season WHERE id = ? FOR UPDATE")) {
                ps.setInt(1, seasonId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getTimestamp(2) != null) {
                        return false;
                    }
                    endsAt = rs.getTimestamp(1).toInstant();
                    startsAt = rs.getTimestamp(3);
                }
            }
            if (now.isBefore(endsAt)) {
                return false;
            }
            for (RatingBoards.Board b : RatingBoards.Board.values()) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO season_place (season_id, board, place, player_id, rating, rated, gems)"
                        + " SELECT ?, ?, r.place, r.id, r.rating, r.rated, " + gemsCase("r.place")
                        + " FROM (SELECT id, " + b.ratingColumn() + " AS rating, " + b.ratedColumn() + " AS rated,"
                        + " ROW_NUMBER() OVER (ORDER BY " + b.order() + ") AS place"
                        + " FROM player WHERE " + b.ratingColumn() + " IS NOT NULL) r")) {
                    ps.setInt(1, seasonId);
                    ps.setInt(2, b.mode);
                    ps.executeUpdate();
                }
            }
            // The board of teams (D-65): its places, and whom each pays, a member at the end who played
            // one of the team's rated team matches in the season.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO season_team_place (season_id, place, team_id, name, rating, rated, gems)"
                    + " SELECT ?, r.place, r.id, r.name, r.rating, r.rated, " + gemsCase("r.place")
                    + " FROM (SELECT id, name, " + RatingBoards.TEAMS.rating() + " AS rating, "
                    + RatingBoards.TEAMS.rated() + " AS rated, ROW_NUMBER() OVER (ORDER BY " + RatingBoards.TEAMS.order()
                    + ") AS place FROM team WHERE " + RatingBoards.TEAMS.rating() + " IS NOT NULL) r")) {
                ps.setInt(1, seasonId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO season_team_payee (season_id, player_id, team_id)"
                    + " SELECT DISTINCT stp.season_id, tm.player_id, tm.team_id FROM season_team_place stp"
                    + " JOIN team_member tm ON tm.team_id = stp.team_id"
                    + " JOIN match_team mt ON mt.team_id = stp.team_id"
                    + " JOIN matches m ON m.id = mt.match_id"
                    + " JOIN match_player mp ON mp.match_id = mt.match_id AND mp.team = mt.side AND mp.player_id = tm.player_id"
                    + " WHERE stp.season_id = ? AND m.mode = " + MatchResultRepository.MODE_TEAMS
                    + " AND m.kind = 1 AND NOT m.cut_short AND m.ended_at >= ? AND m.ended_at < ?")) {
                ps.setInt(1, seasonId);
                ps.setTimestamp(2, startsAt);
                ps.setTimestamp(3, Timestamp.from(endsAt));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE season SET placed_at = ?,"
                    + " reset_bound = (SELECT COALESCE(MAX(id), 0) FROM player),"
                    + " team_bound = (SELECT COALESCE(MAX(id), 0) FROM team) WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setInt(2, seasonId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO season (id, starts_at, ends_at) VALUES (?, ?, ?)")) {
                ps.setInt(1, seasonId + 1);
                ps.setTimestamp(2, Timestamp.from(endsAt));
                ps.setTimestamp(3, Timestamp.from(nextEndsAt));
                ps.executeUpdate();
            }
            return true;
        });
    }

    /** The second step done: every place's gems paid. */
    public void markPaid(int seasonId, Instant now) throws SQLException {
        tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE season SET paid_at = ? WHERE id = ? AND placed_at IS NOT NULL AND paid_at IS NULL")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setInt(2, seasonId);
                return ps.executeUpdate();
            }
        });
    }

    /**
     * The third step, one batch (D-63): the next {@code batch} player ids up to the season's bound,
     * every rating halfway back to {@link #RESET_TOWARDS} and every rated count 0, with the
     * season's progress in the same transaction, so a batch is done once whoever runs it.
     *
     * @return whether more remain
     */
    public boolean resetBatch(int seasonId, int batch, Instant now) throws SQLException {
        return tx.execute(c -> {
            long bound;
            long through;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT placed_at, reset_at, reset_bound, reset_through FROM season WHERE id = ? FOR UPDATE")) {
                ps.setInt(1, seasonId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getTimestamp(1) == null || rs.getTimestamp(2) != null) {
                        return false;
                    }
                    bound = rs.getLong(3);
                    through = rs.getLong(4);
                }
            }
            long upTo = Math.min(bound, through + batch);
            try (PreparedStatement ps = c.prepareStatement("UPDATE player SET"
                    + " rating_duel = " + halfway("rating_duel") + ", rated_duels = 0,"
                    + " rating_tvt = " + halfway("rating_tvt") + ", rated_tvts = 0,"
                    + " rating_rffa = " + halfway("rating_rffa") + ", rated_rffas = 0"
                    + " WHERE id > ? AND id <= ?")) {
                ps.setLong(1, through);
                ps.setLong(2, upTo);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE season SET reset_through = ?, reset_at = ? WHERE id = ?")) {
                ps.setLong(1, upTo);
                ps.setTimestamp(2, upTo >= bound ? Timestamp.from(now) : null);
                ps.setInt(3, seasonId);
                ps.executeUpdate();
            }
            return upTo < bound;
        });
    }

    /**
     * The teams' reset (D-65): every team's rating halfway back to {@link #RESET_TOWARDS} and its rated
     * matches 0, up to the highest team id at the end, in one statement; its record kept.
     *
     * @return whether this call did it: false before the places, or once done
     */
    public boolean resetTeams(int seasonId, Instant now) throws SQLException {
        return tx.execute(c -> {
            long bound;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT placed_at, team_reset_at, team_bound FROM season WHERE id = ? FOR UPDATE")) {
                ps.setInt(1, seasonId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getTimestamp(1) == null || rs.getTimestamp(2) != null) {
                        return false;
                    }
                    bound = rs.getLong(3);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE team SET rating = " + halfway("rating")
                    + ", rated_matches = 0 WHERE id <= ?")) {
                ps.setLong(1, bound);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE season SET team_reset_at = ? WHERE id = ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setInt(2, seasonId);
                ps.executeUpdate();
            }
            return true;
        });
    }

    /** A member a team's place pays (D-65): the team, its place and the gems. */
    public record TeamPayee(long playerId, long teamId, long place, int gems) { }

    /** Those a season's team places pay, by player id after {@code afterPlayer}: the pages they are paid in. */
    public List<TeamPayee> teamPayees(int seasonId, long afterPlayer, int limit) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT p.player_id, p.team_id, s.place, s.gems"
                     + " FROM season_team_payee p JOIN season_team_place s ON s.season_id = p.season_id AND s.team_id = p.team_id"
                     + " WHERE p.season_id = ? AND p.player_id > ? ORDER BY p.player_id LIMIT ?")) {
            ps.setInt(1, seasonId);
            ps.setLong(2, afterPlayer);
            ps.setInt(3, limit);
            List<TeamPayee> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TeamPayee(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)));
                }
            }
            return out;
        }
    }

    /** A past season's board of teams, from its first place, each by the name it had then. */
    public List<TeamBoards.Row> teamTop(int seasonId, int limit) throws SQLException {
        return teamRows(seasonId, 1, limit);
    }

    /** The place of the team a player was paid for in a past season, the teams either side; null for none. */
    public TeamBoards.Place teamPlaceOf(int seasonId, long playerId, int each) throws SQLException {
        long place;
        int rating;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT s.place, s.rating FROM season_team_payee p"
                     + " JOIN season_team_place s ON s.season_id = p.season_id AND s.team_id = p.team_id"
                     + " WHERE p.season_id = ? AND p.player_id = ?")) {
            ps.setInt(1, seasonId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                place = rs.getLong(1);
                rating = rs.getInt(2);
            }
        }
        long first = Math.max(1, place - each);
        return new TeamBoards.Place(place, rating, teamRows(seasonId, first, (int) (place + each - first + 1)));
    }

    private List<TeamBoards.Row> teamRows(int seasonId, long first, int limit) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT place, team_id, name, rating FROM season_team_place"
                     + " WHERE season_id = ? AND place >= ? ORDER BY place LIMIT ?")) {
            ps.setInt(1, seasonId);
            ps.setLong(2, first);
            ps.setInt(3, limit);
            List<TeamBoards.Row> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TeamBoards.Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4)));
                }
            }
            return out;
        }
    }

    /** Halfway back to the pivot, toward it: 1 601 → 1 400, 799 → 1 000. The column is unsigned. */
    private static String halfway(String column) {
        return RESET_TOWARDS + " + (CAST(" + column + " AS SIGNED) - " + RESET_TOWARDS + ") DIV 2";
    }

    /** A season's places after ({@code board}, {@code place}), by board then place: the pages it is paid in. */
    public List<Place> places(int seasonId, int afterBoard, long afterPlace, int limit) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(PLACE_SELECT + " WHERE sp.season_id = ?"
                     + " AND (sp.board > ? OR (sp.board = ? AND sp.place > ?)) ORDER BY sp.board, sp.place LIMIT ?")) {
            ps.setInt(1, seasonId);
            ps.setInt(2, afterBoard);
            ps.setInt(3, afterBoard);
            ps.setLong(4, afterPlace);
            ps.setInt(5, limit);
            return places(ps);
        }
    }

    /** A past season's board, from its first place. */
    public List<Place> top(int seasonId, RatingBoards.Board board, int limit) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(PLACE_SELECT
                     + " WHERE sp.season_id = ? AND sp.board = ? ORDER BY sp.place LIMIT ?")) {
            ps.setInt(1, seasonId);
            ps.setInt(2, board.mode);
            ps.setInt(3, limit);
            return places(ps);
        }
    }

    /** A player's place on a past season's board, or null for one not listed then. */
    public Place placeOf(int seasonId, RatingBoards.Board board, long playerId) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(PLACE_SELECT
                     + " WHERE sp.season_id = ? AND sp.board = ? AND sp.player_id = ?")) {
            ps.setInt(1, seasonId);
            ps.setInt(2, board.mode);
            ps.setLong(3, playerId);
            List<Place> one = places(ps);
            return one.isEmpty() ? null : one.get(0);
        }
    }

    /** The places either side of a place, it among them, in order. */
    public List<Place> around(int seasonId, RatingBoards.Board board, long place, int each) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(PLACE_SELECT
                     + " WHERE sp.season_id = ? AND sp.board = ? AND sp.place BETWEEN ? AND ? ORDER BY sp.place")) {
            ps.setInt(1, seasonId);
            ps.setInt(2, board.mode);
            ps.setLong(3, Math.max(1, place - each));
            ps.setLong(4, place + each);
            return places(ps);
        }
    }

    private static final String PLACE_SELECT = "SELECT sp.season_id, sp.board, sp.place, sp.player_id, p.display_name,"
            + " sp.rating, sp.rated, sp.gems FROM season_place sp JOIN player p ON p.id = sp.player_id";

    private static List<Place> places(PreparedStatement ps) throws SQLException {
        List<Place> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Place(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
                        rs.getInt(6), rs.getInt(7), rs.getInt(8)));
            }
        }
        return out;
    }

    private List<Season> seasons(String sql) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            return read(ps);
        }
    }

    private static List<Season> read(PreparedStatement ps) throws SQLException {
        List<Season> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Season(rs.getInt(1), instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3)),
                        instant(rs.getTimestamp(4)), instant(rs.getTimestamp(5)), instant(rs.getTimestamp(6)),
                        instant(rs.getTimestamp(7))));
            }
        }
        return out;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
