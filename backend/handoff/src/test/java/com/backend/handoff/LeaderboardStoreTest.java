package com.backend.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.backend.handoff.LeaderboardStore.Board;
import com.backend.handoff.LeaderboardStore.Entry;
import com.backend.handoff.LeaderboardStore.Neighbourhood;

import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs against a real j-redis, embedded in this JVM.
 *
 * The whole design rests on what {@code ZADD ... GT} does under a repeat, so a fake store
 * would only prove that the fake was written to agree with the design.
 */
class LeaderboardStoreTest {

    /** A fixed moment, so a test never depends on when it runs. */
    private static final Instant NOON = Instant.parse("2026-09-23T12:00:00Z");

    private static JRedisEmbedded server;
    private static JRedisClient client;
    private static LeaderboardStore store;

    @BeforeAll
    static void setUp() {
        server = JRedisEmbedded.start();
        client = server.newClient();
        // An hour after NOON: a board keeps a score for a time after its match, measured from
        // the match, so the store's clock is fixed along with the matches'.
        store = new LeaderboardStore(client, java.time.Clock.fixed(NOON.plus(Duration.ofHours(1)),
                java.time.ZoneOffset.UTC));
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void clean() {
        client.sync().send("FLUSHALL");
    }

    private void record(long playerId, String name, int score, Instant at) throws Exception {
        store.record(playerId, name, score, at.toEpochMilli()).get(5, TimeUnit.SECONDS);
    }

    private List<Entry> top(Board board, int limit) throws Exception {
        return store.top(board, limit, NOON).get(5, TimeUnit.SECONDS);
    }

    // ---- the property the pipeline depends on ----------------------------------------------

    @Test
    @DisplayName("two results redelivered in any order leave the same board")
    void redeliveryInAnyOrderChangesNothing() throws Exception {
        Instant later = NOON.plus(Duration.ofMinutes(5));

        record(7, "Ada", 900, NOON);                    // the good match
        record(7, "Ada", 120, later);                   // a worse one afterwards
        List<Entry> settled = top(Board.ALLTIME, 10);

        // Both redelivered, worse one first: the crash between commit and acknowledge that
        // 05 §7 says will happen, replaying whichever entries were in flight.
        record(7, "Ada", 120, later);
        record(7, "Ada", 900, NOON);

        assertThat(top(Board.ALLTIME, 10))
                .as("a maximum does not care how often, or in what order").isEqualTo(settled);
        assertThat(settled).singleElement()
                .isEqualTo(new Entry(0, 7, "Ada", 900));
    }

    @Test
    @DisplayName("a later, worse match does not lower a player's best")
    void aWorseMatchDoesNotLower() throws Exception {
        record(7, "Ada", 900, NOON);
        record(7, "Ada", 120, NOON.plus(Duration.ofMinutes(5)));

        assertThat(top(Board.ALLTIME, 10)).singleElement()
                .isEqualTo(new Entry(0, 7, "Ada", 900));
    }

    @Test
    @DisplayName("a better match raises it")
    void aBetterMatchRaises() throws Exception {
        record(7, "Ada", 120, NOON);
        record(7, "Ada", 900, NOON.plus(Duration.ofMinutes(5)));

        assertThat(top(Board.ALLTIME, 10)).singleElement()
                .isEqualTo(new Entry(0, 7, "Ada", 900));
    }

    @Test
    @DisplayName("a score of zero is not ranked at all")
    void zeroIsNotRanked() throws Exception {
        record(7, "Ada", 0, NOON);

        assertThat(top(Board.ALLTIME, 10)).isEmpty();
        assertThat(client.sync().exists(Board.ALLTIME.key(NOON))).isZero();
    }

    // ---- which period a result belongs to --------------------------------------------------

    @Test
    @DisplayName("a result belongs to the day it was played, not the day it was applied")
    void bucketedByMatchClockNotWallClock() throws Exception {
        Instant lateYesterday = NOON.minus(Duration.ofHours(13));   // 2026-09-22T23:00Z

        record(7, "Ada", 900, lateYesterday);

        assertThat(client.sync().zscore(Board.DAILY.key(lateYesterday), "7"))
                .as("on the day it was played").isEqualTo(900.0);
        assertThat(client.sync().exists(Board.DAILY.key(NOON)))
                .as("and not on the day the worker got to it").isZero();
    }

    @Test
    @DisplayName("day and week keys are UTC and ISO")
    void keyNaming() {
        assertThat(Board.ALLTIME.key(NOON)).isEqualTo("lb:score:alltime");
        assertThat(Board.DAILY.key(NOON)).isEqualTo("lb:score:day:2026-09-23");
        assertThat(Board.WEEKLY.key(NOON)).isEqualTo("lb:score:week:2026-W39");
        // A Sunday belongs to the ISO week that started on the preceding Monday.
        assertThat(Board.WEEKLY.key(Instant.parse("2026-01-04T00:00:00Z")))
                .isEqualTo("lb:score:week:2026-W01");
    }

    @Test
    @DisplayName("the short boards expire and the all-time board does not")
    void expiry() throws Exception {
        record(7, "Ada", 900, NOON);

        assertThat(client.sync().ttl(Board.ALLTIME.key(NOON)))
                .as("-1 is j-redis for 'no expiry'").isEqualTo(-1L);
        assertThat(client.sync().ttl(Board.DAILY.key(NOON)))
                .isBetween(1L, (long) Board.DAILY.ttlSeconds());
        assertThat(client.sync().ttl(Board.WEEKLY.key(NOON)))
                .isBetween(1L, (long) Board.WEEKLY.ttlSeconds());
    }

    @Test
    @DisplayName("a result arriving after its day's board has expired does not bring that board back")
    void aLateResultDoesNotReviveAnExpiredBoard() throws Exception {
        Instant fiveDaysBefore = NOON.minus(Duration.ofDays(5));   // past the day's keep, not the week's

        record(7, "Ada", 900, fiveDaysBefore);

        assertThat(client.sync().exists(Board.DAILY.key(fiveDaysBefore)))
                .as("a day board holding only the late scores, for three more days").isZero();
        assertThat(client.sync().zscore(Board.WEEKLY.key(fiveDaysBefore), "7")).isEqualTo(900.0);
        assertThat(client.sync().zscore(Board.ALLTIME.key(fiveDaysBefore), "7")).isEqualTo(900.0);
    }

    @Test
    @DisplayName("a board the store refuses to write is reported, not taken as written")
    void aRefusedWriteFails() {
        client.sync().set(Board.WEEKLY.key(NOON), "not a board");     // WRONGTYPE for ZADD

        // EXEC runs every command and returns the error in its reply; unread, the score was
        // missing from the board and the worker counted it as ranked.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> record(7, "Ada", 900, NOON))
                .hasMessageContaining("WRONGTYPE");
    }

    // ---- reading ---------------------------------------------------------------------------

    @Test
    @DisplayName("the top is ordered best first and capped at the limit")
    void topIsOrdered() throws Exception {
        record(1, "one", 100, NOON);
        record(2, "two", 300, NOON);
        record(3, "three", 200, NOON);

        assertThat(top(Board.ALLTIME, 2)).containsExactly(
                new Entry(0, 2, "two", 300),
                new Entry(1, 3, "three", 200));
    }

    @Test
    @DisplayName("players on the same score keep a stable order between reads")
    void tiesDoNotShuffle() throws Exception {
        record(11, "eleven", 500, NOON);
        record(2, "two", 500, NOON);
        record(33, "thirty-three", 500, NOON);

        List<Entry> first = top(Board.ALLTIME, 10);
        // Read again, and again after an unrelated player joins the board below them.
        record(4, "four", 100, NOON);

        assertThat(top(Board.ALLTIME, 10)).startsWith(first.toArray(new Entry[0]));
        // Tied players are ordered by member, and the member is the player id as a *string*,
        // so a descending board puts "33" before "2" before "11". Arbitrary, but the same on
        // every read: a board that reshuffled tied players on each refresh would look broken
        // to the two people watching it.
        assertThat(first).extracting(Entry::playerId).containsExactly(33L, 2L, 11L);
    }

    @Test
    @DisplayName("around me gives the rank and the rows on either side, in one command")
    void aroundMe() throws Exception {
        for (int i = 1; i <= 9; i++) {
            record(i, "p" + i, i * 100, NOON);          // p9 is first, p1 is last
        }

        Neighbourhood me = store.around(Board.ALLTIME, 5, 2, NOON).get(5, TimeUnit.SECONDS);

        assertThat(me).isNotNull();
        assertThat(me.rank()).as("four players scored higher").isEqualTo(4);
        assertThat(me.score()).isEqualTo(500);
        assertThat(me.window()).containsExactly(
                new Entry(2, 7, "p7", 700),
                new Entry(3, 6, "p6", 600),
                new Entry(4, 5, "p5", 500),
                new Entry(5, 4, "p4", 400),
                new Entry(6, 3, "p3", 300));
    }

    @Test
    @DisplayName("around me at the top is not padded with rows that do not exist")
    void aroundTheTop() throws Exception {
        record(1, "one", 100, NOON);
        record(2, "two", 300, NOON);

        Neighbourhood me = store.around(Board.ALLTIME, 2, 5, NOON).get(5, TimeUnit.SECONDS);

        assertThat(me.rank()).isZero();
        assertThat(me.window()).containsExactly(
                new Entry(0, 2, "two", 300),
                new Entry(1, 1, "one", 100));
    }

    @Test
    @DisplayName("a player who has never scored is not on the board")
    void aroundAStranger() throws Exception {
        record(1, "one", 100, NOON);

        assertThat(store.around(Board.ALLTIME, 999, 2, NOON).get(5, TimeUnit.SECONDS)).isNull();
        assertThat(store.around(Board.DAILY, 1, 2, NOON.plus(Duration.ofDays(2)))
                .get(5, TimeUnit.SECONDS))
                .as("nor on a day they did not play").isNull();
    }

    @Test
    @DisplayName("a board row whose name was never recorded still renders")
    void missingNameIsEmptyNotNull() throws Exception {
        record(1, "one", 100, NOON);
        client.sync().hdel(LeaderboardStore.NAME_KEY, "1");

        assertThat(top(Board.ALLTIME, 10)).singleElement()
                .isEqualTo(new Entry(0, 1, "", 100));
    }

    @Test
    @DisplayName("a display name change shows on the board after the next match")
    void nameFollowsTheLatestMatch() throws Exception {
        record(1, "before", 100, NOON);
        record(1, "after", 200, NOON.plus(Duration.ofMinutes(1)));

        assertThat(top(Board.ALLTIME, 10)).singleElement()
                .isEqualTo(new Entry(0, 1, "after", 200));
    }

    @Test
    @DisplayName("board names on the wire map to boards, and nothing else does")
    void apiNames() {
        assertThat(Board.byApiName("alltime")).isEqualTo(Board.ALLTIME);
        assertThat(Board.byApiName("daily")).isEqualTo(Board.DAILY);
        assertThat(Board.byApiName("weekly")).isEqualTo(Board.WEEKLY);
        assertThat(Board.byApiName("lb:score:alltime")).isNull();
        assertThat(Board.byApiName("")).isNull();
    }
}
