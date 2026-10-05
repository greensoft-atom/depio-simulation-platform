package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * The board of teams (docs detailed-design/04-platform-services.md §7, D-65): teams by their rating,
 * listed once they have ten rated team matches, read as the player boards are and by the same
 * queries ({@link RatingBoards.Columns}), over the team table.
 */
public final class TeamBoards {

    /** A row: its rank from 1, the team, and its rating. */
    public record Row(long rank, long teamId, String name, int rating) { }

    /** A team's place, and the teams either side of it. */
    public record Place(long rank, int rating, List<Row> window) { }

    /** {@link #placeOfPlayer}'s answer for a player in no team. */
    public static final Place NO_TEAM = new Place(-1, 0, List.of());

    private final DataSource ds;
    private final RatingBoards boards;

    public TeamBoards(DataSource ds) {
        this.ds = ds;
        this.boards = new RatingBoards(ds);
    }

    public List<Row> top(int limit) throws SQLException {
        return rows(boards.top(RatingBoards.TEAMS, limit));
    }

    /** @return the player's team's place; null for a team not listed; {@link #NO_TEAM} for none */
    public Place placeOfPlayer(long playerId, int each) throws SQLException {
        long team;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT team_id FROM team_member WHERE player_id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return NO_TEAM;
                }
                team = rs.getLong(1);
            }
        }
        RatingBoards.Place place = boards.place(RatingBoards.TEAMS, team, each);
        return place == null ? null : new Place(place.rank(), place.rating(), rows(place.window()));
    }

    /** A board row's id is the team's here. */
    static List<Row> rows(List<RatingBoards.Row> rows) {
        List<Row> out = new ArrayList<>(rows.size());
        for (RatingBoards.Row r : rows) {
            out.add(new Row(r.rank(), r.playerId(), r.name(), r.rating()));
        }
        return out;
    }
}
