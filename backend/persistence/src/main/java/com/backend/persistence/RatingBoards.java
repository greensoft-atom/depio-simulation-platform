package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * The rating boards (docs detailed-design/04-platform-services.md §7, Q-40): each rated mode's
 * players by rating, read where the ratings are. A player is listed once they have
 * {@value #MIN_RATED} rated matches in the mode; ties go to more rated matches, then to the
 * lower id, so the order is the same on every read.
 */
public final class RatingBoards {

    /** Rated matches in a mode before its board lists a player: V20's columns hold it too (D-57). */
    public static final int MIN_RATED = 10;

    /** A board and its columns, fixed here: nothing a caller sends is put into the SQL. */
    public enum Board {
        DUEL("duel", 1, "board_duel", "rated_duels"),
        RFFA("rffa", 3, "board_rffa", "rated_rffas"),
        TVT("tvt", 2, "board_tvt", "rated_tvts");

        public final String apiName;
        /** The mode's id (handoff's MatchMode): how a season's places name the board (V27). */
        public final int mode;
        /** The rating of a player listed, NULL for anyone else (V20): what the board is indexed by. */
        private final String rating;
        private final String rated;

        Board(String apiName, int mode, String rating, String rated) {
            this.apiName = apiName;
            this.mode = mode;
            this.rating = rating;
            this.rated = rated;
        }

        /** @return the board of a mode's id, or null */
        public static Board byMode(int mode) {
            for (Board b : values()) {
                if (b.mode == mode) {
                    return b;
                }
            }
            return null;
        }

        String ratingColumn() {
            return rating;
        }

        String ratedColumn() {
            return rated;
        }

        /** @return the board, or null for a name that is not one */
        public static Board byApiName(String name) {
            for (Board b : values()) {
                if (b.apiName.equals(name)) {
                    return b;
                }
            }
            return null;
        }

        String order() {
            return columns().order();
        }

        Columns columns() {
            return new Columns("player", "display_name", rating, rated);
        }
    }

    /**
     * Where a board is read: its table, the name shown, the listed rating (NULL for the unlisted) and
     * the rated count that breaks its ties. A player board's, or the board of teams' (D-65).
     */
    record Columns(String table, String name, String rating, String rated) {

        String order() {
            return rating + " DESC, " + rated + " DESC, id";
        }

        /** Those after a key in the board's order, as one condition on its index. */
        String above() {
            return rating + " > ? OR (" + rating + " = ? AND (" + rated + " > ? OR (" + rated + " = ? AND id < ?)))";
        }

        /** The row at the key, and those after it. */
        String atOrBelow() {
            return rating + " < ? OR (" + rating + " = ? AND (" + rated + " < ? OR (" + rated + " = ? AND id >= ?)))";
        }
    }

    /** The board of teams (D-65): V28's listed rating, after ten rated team matches. */
    static final Columns TEAMS = new Columns("team", "name", "board_rating", "rated_matches");

    /** A row: its rank from 1, the player, and the rating. */
    public record Row(long rank, long playerId, String name, int rating) { }

    /** A player's own place, and the rows either side of it, theirs among them. */
    public record Place(long rank, int rating, List<Row> window) { }

    private final DataSource ds;

    public RatingBoards(DataSource ds) {
        this.ds = ds;
    }

    public List<Row> top(Board board, int limit) throws SQLException {
        return top(board.columns(), limit);
    }

    /** A board's top: for a player board, rows of players; for the board of teams, of teams. */
    List<Row> top(Columns board, int limit) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, " + board.name() + ", " + board.rating() + " FROM " + board.table() + " WHERE "
                     + board.rating() + " IS NOT NULL ORDER BY " + board.order() + " LIMIT ?")) {
            ps.setInt(1, limit);
            return rows(ps, 1);
        }
    }

    /**
     * A player's place: their rank is one more than the players listed above them in the board's
     * order, counted by the board's index of the listed only, and the rows either side read by
     * key from theirs, not by offset (D-57). In one read-only transaction, so they agree.
     *
     * @return null for a player not listed
     */
    public Place place(Board board, long playerId, int each) throws SQLException {
        return place(board.columns(), playerId, each);
    }

    /** A row's place, by its id: a player's on a player board, a team's on the board of teams. */
    Place place(Columns board, long playerId, int each) throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try {
                int rating;
                int rated;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT " + board.rating() + ", " + board.rated() + " FROM " + board.table() + " WHERE id = ?")) {
                    ps.setLong(1, playerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getObject(1) == null) {
                            return null;
                        }
                        rating = rs.getInt(1);
                        rated = rs.getInt(2);
                    }
                }
                long above;
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + board.table() + " WHERE " + board.above())) {
                    key(ps, rating, rated, playerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        above = rs.getLong(1);
                    }
                }
                // The rows before, nearest first: MySQL sorts rather than read the board's index
                // backwards, so the sort reads keys only, and just the rows shown are fetched whole.
                List<Row> before;
                String reverse = board.rating() + ", " + board.rated() + ", id DESC";
                try (PreparedStatement ps = c.prepareStatement("SELECT p.id, p." + board.name() + ", p." + board.rating()
                        + " FROM (SELECT id, " + board.rating() + ", " + board.rated() + " FROM " + board.table() + " WHERE " + board.above()
                        + " ORDER BY " + reverse + " LIMIT ?) k JOIN " + board.table() + " p ON p.id = k.id ORDER BY k." + board.rating()
                        + ", k." + board.rated() + ", k.id DESC")) {
                    key(ps, rating, rated, playerId);
                    ps.setInt(6, each);
                    before = new ArrayList<>(rows(ps, 0));
                }
                java.util.Collections.reverse(before);
                List<Row> window = new ArrayList<>(before.size() + each + 1);
                long rank = above - before.size();
                for (Row r : before) {
                    window.add(new Row(++rank, r.playerId(), r.name(), r.rating()));
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT id, " + board.name() + ", " + board.rating()
                        + " FROM " + board.table() + " WHERE " + board.atOrBelow() + " ORDER BY " + board.order() + " LIMIT ?")) {
                    key(ps, rating, rated, playerId);
                    ps.setInt(6, each + 1);
                    window.addAll(rows(ps, above + 1));
                }
                return new Place(above + 1, rating, window);
            } finally {
                c.commit();
            }
        }
    }

    /** A key in the board's order, as {@link Columns#above()} and {@link Columns#atOrBelow()} take it. */
    private static void key(PreparedStatement ps, int rating, int rated, long playerId) throws SQLException {
        ps.setInt(1, rating);
        ps.setInt(2, rating);
        ps.setInt(3, rated);
        ps.setInt(4, rated);
        ps.setLong(5, playerId);
    }

    /** The rows a read returns, ranked from {@code first}. */
    private static List<Row> rows(PreparedStatement ps, long first) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            long rank = first;
            while (rs.next()) {
                out.add(new Row(rank++, rs.getLong(1), rs.getString(2), rs.getInt(3)));
            }
        }
        return out;
    }
}
