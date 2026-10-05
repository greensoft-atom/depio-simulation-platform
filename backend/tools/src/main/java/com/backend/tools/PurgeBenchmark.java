package com.backend.tools;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.Locale;

import com.backend.handoff.Ulid;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.Database;
import com.backend.persistence.MatchResultRepository;

/**
 * How fast retention deletes match history (docs 06 §9, plan item 76 (b)): the worker's own batches, a
 * thousand matches each with their players' rows, over matches past their 90 days. The rate says how long a
 * day's purge takes at a given number of stays a day, and so whether the tables need more than it.
 *
 * Usage: PurgeBenchmark jdbcUrl user password [matches] [players a match]      defaults: 200000, 4
 *
 * It drops everything in the database and migrates it afresh, so it runs only on one whose name ends in
 * {@code _test}.
 */
public final class PurgeBenchmark {

    private static final int PLAYERS = 200;
    private static final int INSERT_ROWS = 1_000;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PurgeBenchmark jdbcUrl user password [matches] [players a match]");
            System.exit(2);
        }
        if (!ApplyBenchmark.isTestDatabase(args[0])) {
            System.err.println("refused: it drops everything in the database, and " + args[0]
                    + " does not name one ending in _test");
            System.exit(2);
        }
        int matches = args.length > 3 ? Integer.parseInt(args[3]) : 200_000;
        int each = args.length > 4 ? Integer.parseInt(args[4]) : 4;
        try (Database db = new Database(args[0], args[1], args[2], 2)) {
            db.resetForTests();
            AccountRepository accounts = new AccountRepository(db.dataSource());
            long[] players = new long[PLAYERS];
            for (int i = 0; i < PLAYERS; i++) {
                players[i] = accounts.register("pb" + i, "pb" + i, "$argon2id$bench".getBytes(StandardCharsets.UTF_8));
            }
            long old = System.currentTimeMillis() - 100L * 24 * 3_600_000;       // past their 90 days
            long t0 = System.nanoTime();
            try (Connection c = db.dataSource().getConnection()) {
                for (int from = 1; from <= matches; from += INSERT_ROWS) {
                    int to = Math.min(matches, from + INSERT_ROWS - 1);
                    insertMatches(c, from, to, old);
                    insertPlayers(c, from, to, each, players);
                }
            }
            double filled = (System.nanoTime() - t0) / 1e9;
            System.out.printf(Locale.ROOT, "filled %d matches and %d players' rows in %.1f s%n", matches,
                    (long) matches * each, filled);

            MatchResultRepository repository = new MatchResultRepository(db.dataSource(), xp -> 1);
            long t1 = System.nanoTime();
            int purged = repository.purgeMatchesEndedBefore(System.currentTimeMillis() - MatchResultRepository.MATCH_RETENTION_MILLIS,
                    1_000);
            double took = (System.nanoTime() - t1) / 1e9;
            System.out.printf(Locale.ROOT, "purged %d matches in %.1f s: %.0f matches/s, %.0f players' rows/s%n",
                    purged, took, purged / took, purged * (double) each / took);
        }
    }

    private static void insertMatches(Connection c, int from, int to, long endedAt) throws Exception {
        StringBuilder sql = new StringBuilder("INSERT INTO matches (id, match_uid, mode, arena, started_at, ended_at) VALUES ");
        for (int id = from; id <= to; id++) {
            sql.append(id == from ? "" : ",").append("(?, ?, 0, 'bench', ?, ?)");
        }
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int k = 1;
            for (int id = from; id <= to; id++) {
                ps.setLong(k++, id);
                ps.setString(k++, Ulid.generate());
                ps.setTimestamp(k++, new Timestamp(endedAt + id - 300_000));
                ps.setTimestamp(k++, new Timestamp(endedAt + id));
            }
            ps.executeUpdate();
        }
    }

    private static void insertPlayers(Connection c, int from, int to, int each, long[] players) throws Exception {
        StringBuilder sql = new StringBuilder("INSERT INTO match_player (match_id, player_id, team, placement, kills, deaths,"
                + " score, xp_gained) VALUES ");
        boolean first = true;
        for (int id = from; id <= to; id++) {
            for (int j = 0; j < each; j++) {
                sql.append(first ? "" : ",").append("(?, ?, 0, 0, 1, 1, 100, 10)");
                first = false;
            }
        }
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int k = 1;
            for (int id = from; id <= to; id++) {
                for (int j = 0; j < each; j++) {
                    ps.setLong(k++, id);
                    ps.setLong(k++, players[(id * each + j) % players.length]);
                }
            }
            ps.executeUpdate();
        }
    }
}
