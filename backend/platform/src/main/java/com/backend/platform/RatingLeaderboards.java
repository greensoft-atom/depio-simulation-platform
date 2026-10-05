package com.backend.platform;

import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.backend.persistence.RatingBoards;
import com.backend.persistence.SeasonRepository;

/**
 * The rating boards for the API (docs detailed-design/04-platform-services.md §7, Q-40): each
 * board's top kept {@value #KEEP_MILLIS} ms, so a title screen read by everyone costs the database
 * one query a board that often; a player's own place read fresh.
 */
public final class RatingLeaderboards {

    /** How long a board's top is kept: fresher than a rating moves, a match at a time. */
    static final long KEEP_MILLIS = 30_000;

    private record Kept(long atMillis, List<RatingBoards.Row> rows) { }

    private final RatingBoards boards;
    private final com.backend.persistence.TeamBoards teams;
    private final SeasonRepository seasons;
    private final Clock clock;
    private final int most;
    private final Map<RatingBoards.Board, Kept> tops = new ConcurrentHashMap<>();

    /** @param most the most rows a read may ask for: what is kept */
    public RatingLeaderboards(RatingBoards boards, com.backend.persistence.TeamBoards teams, SeasonRepository seasons,
                              Clock clock, int most) {
        this.boards = boards;
        this.teams = teams;
        this.seasons = seasons;
        this.clock = clock;
        this.most = most;
    }

    /** The season being played, and those before it, newest first (04 §7). */
    public List<SeasonRepository.Season> seasons(int limit) throws SQLException {
        return seasons.recent(limit);
    }

    /**
     * A season's board: a past one's final places, or, for the season being played, the board as it
     * stands.
     *
     * @return null for a season that does not exist
     */
    public List<RatingBoards.Row> top(int season, RatingBoards.Board board, int limit) throws SQLException {
        SeasonRepository.Season current = seasons.current();
        if (current != null && current.id() == season) {
            return top(board, limit);
        }
        if (!placed(season)) {
            return null;
        }
        return rows(seasons.top(season, board, Math.min(limit, most)));
    }

    /**
     * A player's place on a season's board, the rows either side of it.
     *
     * @return null for a player not placed then; {@link #NO_SEASON} for a season that does not exist
     */
    public RatingBoards.Place place(int season, RatingBoards.Board board, long playerId, int each) throws SQLException {
        SeasonRepository.Season current = seasons.current();
        if (current != null && current.id() == season) {
            return place(board, playerId, each);
        }
        if (!placed(season)) {
            return NO_SEASON;
        }
        SeasonRepository.Place mine = seasons.placeOf(season, board, playerId);
        if (mine == null) {
            return null;
        }
        return new RatingBoards.Place(mine.place(), mine.rating(), rows(seasons.around(season, board, mine.place(), each)));
    }

    private record KeptTeams(long atMillis, List<com.backend.persistence.TeamBoards.Row> rows) { }

    private volatile KeptTeams teamTop;

    /** The board of teams' top (D-65), kept as a player board's is. */
    public List<com.backend.persistence.TeamBoards.Row> teamTop(int limit) throws SQLException {
        long now = clock.millis();
        KeptTeams kept = teamTop;
        if (kept == null || now - kept.atMillis() >= KEEP_MILLIS) {
            kept = new KeptTeams(now, teams.top(most));
            teamTop = kept;
        }
        return kept.rows().subList(0, Math.min(limit, kept.rows().size()));
    }

    /** The caller's team's place, read fresh: null for one not listed, {@code TeamBoards.NO_TEAM} for none. */
    public com.backend.persistence.TeamBoards.Place teamPlace(long playerId, int each) throws SQLException {
        return teams.placeOfPlayer(playerId, each);
    }

    /** A season's board of teams: a past one's places, or the board as it stands; null for no such season. */
    public List<com.backend.persistence.TeamBoards.Row> teamTop(int season, int limit) throws SQLException {
        SeasonRepository.Season current = seasons.current();
        if (current != null && current.id() == season) {
            return teamTop(limit);
        }
        return placed(season) ? seasons.teamTop(season, Math.min(limit, most)) : null;
    }

    /**
     * The place of the team a player was paid for in a past season; in the current one, their team's now.
     *
     * @return null for none; {@link #NO_TEAM_SEASON} for a season that does not exist
     */
    public com.backend.persistence.TeamBoards.Place teamPlace(int season, long playerId, int each) throws SQLException {
        SeasonRepository.Season current = seasons.current();
        if (current != null && current.id() == season) {
            return teamPlace(playerId, each);
        }
        return placed(season) ? seasons.teamPlaceOf(season, playerId, each) : NO_TEAM_SEASON;
    }

    /** {@link #teamPlace(int, long, int)}'s answer for a season that does not exist. */
    public static final com.backend.persistence.TeamBoards.Place NO_TEAM_SEASON =
            new com.backend.persistence.TeamBoards.Place(-2, 0, List.of());

    /** {@link #place(int, RatingBoards.Board, long, int)}'s answer for a season that does not exist. */
    public static final RatingBoards.Place NO_SEASON = new RatingBoards.Place(-1, 0, List.of());

    private boolean placed(int season) throws SQLException {
        SeasonRepository.Season s = seasons.get(season);
        return s != null && s.placedAt() != null;
    }

    private static List<RatingBoards.Row> rows(List<SeasonRepository.Place> places) {
        List<RatingBoards.Row> rows = new java.util.ArrayList<>(places.size());
        for (SeasonRepository.Place p : places) {
            rows.add(new RatingBoards.Row(p.place(), p.playerId(), p.name(), p.rating()));
        }
        return rows;
    }

    public List<RatingBoards.Row> top(RatingBoards.Board board, int limit) throws SQLException {
        long now = clock.millis();
        Kept kept = tops.get(board);
        if (kept == null || now - kept.atMillis() >= KEEP_MILLIS) {
            kept = new Kept(now, boards.top(board, most));
            tops.put(board, kept);
        }
        return kept.rows().subList(0, Math.min(limit, kept.rows().size()));
    }

    /** @return null for a player not listed */
    public RatingBoards.Place place(RatingBoards.Board board, long playerId, int each) throws SQLException {
        return boards.place(board, playerId, each);
    }
}
