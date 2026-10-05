package com.backend.tools;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

import com.backend.persistence.Database;
import com.backend.persistence.RatingBoards;

/**
 * What a player's own place on a rating board costs (defect D-35, plan item 58): the duel board's
 * listed players among many accounts that never played a rated match, every one of them at the
 * starting 1 200, as a title screen reads it, {@link RatingBoards#place}.
 *
 * Usage: RankBenchmark jdbcUrl user password [accounts] [listed] [reads]
 *
 * It drops everything in the database and fills it, so it runs only on one whose name ends in
 * {@code _test}. For a listed player at the top, in the middle and at the bottom of the board it
 * prints how long a place took (the median and the slowest of {@code reads}) and how many index
 * rows MySQL read for it; and the same for the board's top hundred.
 */
public final class RankBenchmark {

    private static final int EACH = 5;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: RankBenchmark jdbcUrl user password [accounts] [listed] [reads]");
            System.exit(2);
        }
        String url = args[0];
        if (!ApplyBenchmark.isTestDatabase(url)) {
            System.err.println("refused: it drops everything in the database, and " + url
                    + " does not name one ending in _test");
            System.exit(2);
        }
        int accounts = args.length > 3 ? Integer.parseInt(args[3]) : 200_000;
        int listed = args.length > 4 ? Integer.parseInt(args[4]) : 5_000;
        int reads = args.length > 5 ? Integer.parseInt(args[5]) : 21;

        try (Database db = new Database(url, args[1], args[2], 2)) {
            db.resetForTests();
            long[] byRating = seed(db, accounts, listed);
            RatingBoards boards = new RatingBoards(db.dataSource());
            System.out.printf(Locale.ROOT, "%d accounts, %d of them listed on the duel board%n", accounts, listed);
            String[] where = {"top", "middle", "bottom"};
            long[] who = {byRating[0], byRating[listed / 2], byRating[listed - 1]};
            for (int i = 0; i < who.length; i++) {
                long player = who[i];
                double[] ms = new double[reads];
                RatingBoards.Place place = null;
                for (int r = 0; r < reads; r++) {
                    long started = System.nanoTime();
                    place = boards.place(RatingBoards.Board.DUEL, player, EACH);
                    ms[r] = (System.nanoTime() - started) / 1e6;
                }
                Arrays.sort(ms);
                System.out.printf(Locale.ROOT, "%-6s rank %6d: a place in %.1f ms (median), %.1f ms slowest;"
                                + " %d index rows read%n", where[i], place.rank(), ms[reads / 2], ms[reads - 1],
                        rowsRead(db, b -> b.place(RatingBoards.Board.DUEL, player, EACH)));
            }
            double[] ms = new double[reads];
            for (int r = 0; r < reads; r++) {
                long started = System.nanoTime();
                boards.top(RatingBoards.Board.DUEL, 100);
                ms[r] = (System.nanoTime() - started) / 1e6;
            }
            Arrays.sort(ms);
            System.out.printf(Locale.ROOT, "the top 100: %.1f ms (median), %.1f ms slowest; %d index rows read%n",
                    ms[reads / 2], ms[reads - 1], rowsRead(db, b -> b.top(RatingBoards.Board.DUEL, 100)));
        }
    }

    /**
     * Every account a player at 1 200 with no rated match; {@code listed} of them given a duel
     * rating, spread as a board's would be, and enough rated matches to be listed.
     *
     * @return the listed players' ids, best first
     */
    private static long[] seed(Database db, int accounts, int listed) throws Exception {
        try (Connection c = db.dataSource().getConnection()) {
            c.setAutoCommit(false);
            for (int from = 1; from <= accounts; from += 1_000) {
                int to = Math.min(accounts, from + 999);
                StringBuilder a = new StringBuilder("INSERT INTO account (id, username, password_hash) VALUES ");
                StringBuilder p = new StringBuilder("INSERT INTO player (id, public_code, display_name) VALUES ");
                for (int id = from; id <= to; id++) {
                    String sep = id == from ? "" : ",";
                    a.append(sep).append("(").append(id).append(",'rb").append(id).append("',x'00')");
                    p.append(sep).append("(").append(id).append(",'").append(String.format("P%011d", id))
                            .append("','rb").append(id).append("')");
                }
                try (Statement st = c.createStatement()) {
                    st.executeUpdate(a.toString());
                    st.executeUpdate(p.toString());
                }
            }
            c.commit();
            Random random = new Random(58);
            long[][] rated = new long[listed][2];
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE player SET rating_duel = ?, rated_duels = ? WHERE id = ?")) {
                for (int i = 0; i < listed; i++) {
                    long id = 1 + (long) i * (accounts / listed);
                    int rating = (int) Math.max(100, Math.min(3_000, Math.round(1_200 + random.nextGaussian() * 200)));
                    rated[i][0] = id;
                    rated[i][1] = rating;
                    ps.setInt(1, rating);
                    ps.setInt(2, RatingBoards.MIN_RATED + random.nextInt(200));
                    ps.setLong(3, id);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            try (Statement st = c.createStatement()) {
                st.execute("ANALYZE TABLE player");
            }
            // The board's own order decides top and bottom: read it rather than re-derive it.
            long[] ids = new long[listed];
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id FROM player WHERE rated_duels >= " + RatingBoards.MIN_RATED
                         + " ORDER BY rating_duel DESC, rated_duels DESC, id")) {
                int i = 0;
                while (rs.next()) {
                    ids[i++] = rs.getLong(1);
                }
            }
            c.commit();
            return ids;
        }
    }

    /**
     * The index rows MySQL read for a board read, on one connection held for it: the session's
     * handler reads before and after, less what reading them costs itself.
     */
    private static long rowsRead(Database db, Read read) throws Exception {
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
            long itself = handlerReads(c) - before;
            long start = handlerReads(c);
            read.on(new RatingBoards(one));
            return handlerReads(c) - start - itself;
        }
    }

    private static long handlerReads(Connection c) throws Exception {
        long sum = 0;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SHOW SESSION STATUS WHERE Variable_name IN ('Handler_read_first',"
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
