package com.backend.handoff;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.jredis.client.JRedisClient;
import com.jredis.client.ScoredMember;
import com.jredis.client.ZAround;

/**
 * Leaderboards in j-redis (docs detailed-design/04-platform-services.md §7).
 *
 * <h2>One metric: the best score in a single match</h2>
 *
 * Not a running total. A best is a <em>maximum</em>, and a maximum is idempotent and
 * commutative — applying the same match twice, or two matches in either order, gives the
 * same board. That matters more here than it looks, because the thing that feeds this is an
 * at-least-once queue: {@code worker} commits to MySQL and then acknowledges, so a crash
 * between the two redelivers the result by design (05 §7).
 *
 * {@code ZINCRBY} under that redelivery double-counts, and a doubled score on a competitive
 * board is both visible and disputed. {@code ZADD ... GT} cannot: it only ever raises a
 * score, so the second write of a score already on the board changes nothing. No dedupe
 * key, no read-back of a total, no window in which two stores disagree.
 *
 * <h2>Why it lives here</h2>
 *
 * Two processes touch it and neither should inherit the other's dependencies: {@code
 * worker} writes after it commits, {@code platform} reads to serve the API. The same reason
 * {@link SessionStore} is here.
 *
 * <h2>Durability</h2>
 *
 * j-redis appends to its AOF by default, so a restart keeps the boards, and a replica follows
 * the primary (D-34, {@link StoreClients}). Losing both loses the boards until {@link #raise}
 * puts them back from what MySQL holds (worker's {@code LeaderboardRebuild}, 05 §8).
 */
public final class LeaderboardStore {

    /**
     * Which stretch of time a board covers.
     *
     * All-time alone would be a board no new player can ever enter: after a few months the
     * top is frozen by whoever played most in the first weeks, and it stops being a reason
     * to play. The short windows are where an ordinary player can actually appear.
     */
    public enum Board {
        /** Best score ever. Never expires. */
        ALLTIME("alltime", 0),
        /** Best score on one UTC day. Kept for three days, so yesterday is still readable. */
        DAILY("daily", 3 * 86_400),
        /** Best score in one ISO week. Kept ten days, for the same reason. */
        WEEKLY("weekly", 10 * 86_400);

        private static final DateTimeFormatter DAY =
                DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT).withZone(ZoneOffset.UTC);

        private final String apiName;
        private final int ttlSeconds;

        Board(String apiName, int ttlSeconds) {
            this.apiName = apiName;
            this.ttlSeconds = ttlSeconds;
        }

        /** The name this board goes by on the wire. */
        public String apiName() {
            return apiName;
        }

        /** 0 for a board that never expires. */
        public int ttlSeconds() {
            return ttlSeconds;
        }

        public static Board byApiName(String name) {
            for (Board board : values()) {
                if (board.apiName.equals(name)) {
                    return board;
                }
            }
            return null;
        }

        /**
         * The key holding this board for the period containing {@code at}.
         *
         * UTC throughout. A local day would have to pick someone's local, and the one it
         * picked would silently decide whose midnight resets the board.
         */
        public String key(Instant at) {
            return switch (this) {
                case ALLTIME -> "lb:score:alltime";
                case DAILY -> "lb:score:day:" + DAY.format(at);
                case WEEKLY -> "lb:score:week:" + week(at);
            };
        }

        private static String week(Instant at) {
            var date = at.atZone(ZoneOffset.UTC).toLocalDate();
            return "%d-W%02d".formatted(date.get(IsoFields.WEEK_BASED_YEAR),
                    date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        }
    }

    /**
     * Player id to display name, so rendering a board is one extra round trip rather than
     * one per row. Never expires: it is the size of the player table times a name, and an
     * entry whose player never plays again costs a few dozen bytes for ever.
     */
    static final String NAME_KEY = "lb:name";

    /** One player's score, as a rebuild offers it to a board. */
    public record Score(long playerId, String displayName, int score) { }

    /** Members per command when raising a whole board: few commands, none of them long. */
    private static final int RAISE_CHUNK = 500;

    /** One row of a board. {@code rank} is 0-based, as the store counts it. */
    public record Entry(long rank, long playerId, String name, long score) { }

    /** A player's own position, and the rows on either side of it. */
    public record Neighbourhood(long rank, long score, List<Entry> window) { }

    private final JRedisClient client;
    private final java.time.Clock clock;

    public LeaderboardStore(JRedisClient client) {
        this(client, java.time.Clock.systemUTC());
    }

    /** With the clock that decides which boards a late result is still in time for. */
    public LeaderboardStore(JRedisClient client, java.time.Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    // ---- writing ---------------------------------------------------------------------------

    /** The name the boards show for a player, as a rename leaves it (04 §1, D-60). */
    public CompletableFuture<Void> rename(long playerId, String displayName) {
        return client.hset(NAME_KEY, Long.toString(playerId), displayName).thenApply(added -> null);
    }

    /**
     * Offers one match's score to every board, raising each only if it beats what is there.
     *
     * {@code endedAtMillis} is the match's own clock, not the worker's. A result that sat in
     * the queue over midnight belongs to the day it was played, and taking the wall clock
     * here would quietly move it to the next day's board — rarely, and only under the delays
     * nobody is watching.
     */
    public CompletableFuture<Void> record(long playerId, String displayName, int score,
            long endedAtMillis) {
        if (score <= 0) {
            // Nothing to rank, and writing it would put every player who ever joined and
            // left immediately onto the board at zero.
            return CompletableFuture.completedFuture(null);
        }
        Instant at = Instant.ofEpochMilli(endedAtMillis);
        String member = Long.toString(playerId);

        var batch = client.multi();
        long now = clock.millis();
        for (Board board : Board.values()) {
            if (board.ttlSeconds() > 0 && endedAtMillis + board.ttlSeconds() * 1000L <= now) {
                // Its board has had its time: written now, it would come back holding only the
                // late scores for three more days. The rebuild skips it the same way (05 §8).
                continue;
            }
            String key = board.key(at);
            batch.send("ZADD", key, "GT", Integer.toString(score), member);
            if (board.ttlSeconds() > 0) {
                // Set on every write rather than only when the key is new. There is nothing
                // to remember — a later EXPIRE just replaces the earlier one — and it makes
                // the promise "kept for three days after the last score on it" rather than
                // three days after the first, which is the one a reader would guess.
                batch.send("EXPIRE", key, Integer.toString(board.ttlSeconds()));
            }
        }
        if (displayName != null && !displayName.isEmpty()) {
            batch.send("HSET", NAME_KEY, member, displayName);
        }
        // EXEC runs every command even when one fails, and hands back its error as a reply:
        // unread, a board the store refused looked written, and the worker counted it ranked.
        return batch.exec().thenApply(replies -> {
            if (replies == null) {
                throw new IllegalStateException("transaction aborted");
            }
            for (var r : replies) {
                if (r.isError()) {
                    throw new IllegalStateException("j-redis: " + r.asString());
                }
            }
            return null;
        });
    }

    /**
     * Raises one board to at least these scores: rebuilding after a store loss (05 §8).
     *
     * {@code ZADD GT}, like every write here, so a score already higher stays, and on a board
     * that was never lost this changes nothing but what a lost write left out. Names are
     * written as {@link #record} writes them.
     *
     * A board that expires is given {@code expiresAt}, when the live board would have expired,
     * with {@code EXPIREAT ... NX}: a rebuilt key has no expiry and gets this one, and a live
     * board keeps its own. {@code GT} would be wrong here: a key without an expiry counts as
     * never expiring, so a rebuilt board would have been kept for ever. A caller does not raise
     * a board whose moment has already passed.
     */
    public CompletableFuture<Void> raise(Board board, Instant period, List<Score> scores,
            Instant expiresAt) {
        String key = board.key(period);
        List<CompletableFuture<?>> sent = new ArrayList<>();
        for (int from = 0; from < scores.size(); from += RAISE_CHUNK) {
            List<Score> chunk = scores.subList(from, Math.min(from + RAISE_CHUNK, scores.size()));
            List<Object> zadd = new ArrayList<>(List.of("ZADD", key, "GT"));
            List<Object> names = new ArrayList<>(List.of("HSET", NAME_KEY));
            for (Score s : chunk) {
                if (s.score() <= 0) {
                    continue;                       // never ranked live, so not here either
                }
                String member = Long.toString(s.playerId());
                zadd.add(Integer.toString(s.score()));
                zadd.add(member);
                if (s.displayName() != null && !s.displayName().isEmpty()) {
                    names.add(member);
                    names.add(s.displayName());
                }
            }
            if (zadd.size() > 3) {
                sent.add(client.send(zadd.toArray()));
            }
            if (names.size() > 2) {
                sent.add(client.send(names.toArray()));
            }
        }
        if (board.ttlSeconds() > 0 && !sent.isEmpty()) {
            sent.add(client.send("EXPIREAT", key, Long.toString(expiresAt.getEpochSecond()), "NX"));
        }
        return CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new));
    }

    // ---- reading ---------------------------------------------------------------------------

    /** The top of a board, best first. */
    public CompletableFuture<List<Entry>> top(Board board, int limit, Instant now) {
        if (limit <= 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        return client.zrevrangeWithScores(board.key(now), 0, limit - 1L)
                .thenCompose(members -> withNames(members, 0));
    }

    /**
     * Where one player stands, with {@code radius} rows on each side.
     *
     * One command: j-redis's {@code J.ZAROUND} returns the rank and the window together.
     * Done as {@code ZREVRANK} then {@code ZRANGE} it would be two round trips against a
     * board that other players are writing to in between, and the rank could disagree with
     * the window it was supposed to label.
     *
     * @return null when the player is not on that board at all.
     */
    public CompletableFuture<Neighbourhood> around(Board board, long playerId, int radius,
            Instant now) {
        return client.zaround(board.key(now), Long.toString(playerId), radius, true)
                .thenCompose(found -> {
                    if (found == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return withNames(found.window, found.firstRank)
                            .thenApply(window -> new Neighbourhood(found.rank,
                                    scoreOf(found, playerId), window));
                });
    }

    private static long scoreOf(ZAround found, long playerId) {
        String member = Long.toString(playerId);
        for (ScoredMember scored : found.window) {
            if (scored.member.equals(member)) {
                return (long) scored.score;
            }
        }
        return 0;
    }

    /** Turns members into rows, resolving every name in one {@code HMGET}. */
    private CompletableFuture<List<Entry>> withNames(List<ScoredMember> members, long firstRank) {
        if (members.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        String[] ids = new String[members.size()];
        for (int i = 0; i < members.size(); i++) {
            ids[i] = members.get(i).member;
        }
        return client.hmget(NAME_KEY, ids).thenApply(names -> {
            List<Entry> entries = new ArrayList<>(members.size());
            for (int i = 0; i < members.size(); i++) {
                ScoredMember scored = members.get(i);
                String name = i < names.size() ? names.get(i) : null;
                entries.add(new Entry(firstRank + i, parseId(scored.member),
                        name == null ? "" : name, (long) scored.score));
            }
            return entries;
        });
    }

    /** A member that is not a number cannot have got here through {@link #record}. */
    private static long parseId(String member) {
        try {
            return Long.parseLong(member);
        } catch (NumberFormatException corrupt) {
            return -1;
        }
    }
}
