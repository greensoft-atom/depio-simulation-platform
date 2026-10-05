package com.jredis.tests;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Streams ([15](../../../../../../../docs/15-streams.md)): the entries, their IDs, ranges and trims. */
class StreamCommandsTest extends EmbeddedTest {

    private static final String MAX = "18446744073709551615";

    @Test
    void addAndRead() {
        expect("XADD s 1-1 name ada", "1-1");
        expect("XADD s 1-2 name bo score 7", "1-2");
        expect("XADD s 5 x y", "5-0");                      // an ID without its sequence is ms-0
        expect("XADD s 5-* x z", "5-1");                    // the sequence picked
        expect("XADD s 7-* x w", "7-0");
        expect("XLEN s", ":5");
        expect("TYPE s", "+stream");
        expect("XRANGE s - +", "[[1-1, [name, ada]], [1-2, [name, bo, score, 7]], [5-0, [x, y]], [5-1, [x, z]], [7-0, [x, w]]]");
        expect("XRANGE s 1 1", "[[1-1, [name, ada]], [1-2, [name, bo, score, 7]]]");   // a millisecond, whole
        expect("XRANGE s (1-1 5", "[[1-2, [name, bo, score, 7]], [5-0, [x, y]], [5-1, [x, z]]]");
        expect("XRANGE s - + COUNT 2", "[[1-1, [name, ada]], [1-2, [name, bo, score, 7]]]");
        expect("XREVRANGE s + - COUNT 2", "[[7-0, [x, w]], [5-1, [x, z]]]");
        // As Redis: a bare millisecond as an exclusive end is ms-max, less one, so 7-0 is in.
        expect("XREVRANGE s (7 (1-2", "[[7-0, [x, w]], [5-1, [x, z]], [5-0, [x, y]]]");
        expect("XREVRANGE s (7-0 (1-2", "[[5-1, [x, z]], [5-0, [x, y]]]");
        expect("XREVRANGE s 5 5", "[[5-1, [x, z]], [5-0, [x, y]]]");
        expect("XRANGE s 9 +", "[]");
        expect("XRANGE s 5 1", "[]");
        expect("XRANGE s - + COUNT 0", "[]");
        expect("XRANGE s - + COUNT -1", "[]");              // as Redis: a negative count is 0
        expect("XRANGE missing - +", "[]");
        expect("XLEN missing", ":0");
        expectError("XRANGE s nope +", "ERR Invalid stream ID specified as stream command argument");
        expectError("XRANGE s (" + MAX + "-" + MAX + " +", "ERR invalid start ID for the interval");
        expectError("XRANGE s - (0-0", "ERR invalid end ID for the interval");
        expectError("XRANGE s - + COUNT", "ERR syntax error");
    }

    @Test
    void idsOnlyGrow() {
        expect("XADD s 5-3 a 1", "5-3");
        expectError("XADD s 5-3 a 1", "ERR The ID specified in XADD is equal or smaller than the target stream top item");
        expectError("XADD s 4-9 a 1", "ERR The ID specified in XADD is equal or smaller than the target stream top item");
        expect("XADD s 5-* a 1", "5-4");
        expectError("XADD t 0-0 a 1", "ERR The ID specified in XADD must be greater than 0-0");
        expectError("XADD t nope a 1", "ERR Invalid stream ID specified as stream command argument");
        expectError("XADD t 1-2-3 a 1", "ERR Invalid stream ID specified as stream command argument");
        expectError("XADD t -1 a 1", "ERR Invalid stream ID specified as stream command argument");
        expectError("XADD t 5-3-* a 1", "ERR Invalid stream ID specified as stream command argument");
        expectError("XADD t +5 a 1", "ERR Invalid stream ID specified as stream command argument");
        expectError("XADD t * a", "ERR wrong number of arguments for 'xadd' command");
        expect("EXISTS t", ":0");

        // From the clock: the next sequence within a millisecond, and never backwards.
        expect("XADD c * a 1", T0 + "-0");
        expect("XADD c * a 2", T0 + "-1");
        clock.advance(5);
        expect("XADD c * a 3", (T0 + 5) + "-0");
        clock.set(T0);                                       // the clock stepped back
        expect("XADD c * a 4", (T0 + 5) + "-1");
        expect("XADD c " + (T0 + 1_000) + "-0 a 5", (T0 + 1_000) + "-0");
        expect("XADD c * a 6", (T0 + 1_000) + "-1");         // * carries on from an ID ahead of the clock

        // The last ID outlives its entry.
        expect("XDEL c " + (T0 + 1_000) + "-1", ":1");
        expectError("XADD c " + (T0 + 1_000) + "-1 a 7", "ERR The ID specified in XADD is equal or smaller than the target stream top item");

        // The largest ID there is.
        expect("XADD m " + MAX + "-" + MAX + " a 1", MAX + "-" + MAX);
        expectError("XADD m * a 2", "ERR The stream has exhausted the last possible ID, unable to add more items");
        expectError("XADD m " + MAX + "-* a 2", "ERR The ID specified in XADD is equal or smaller than the target stream top item");
        expect("XADD n " + MAX + "-* a 2", MAX + "-0");
    }

    @Test
    void deleteAndTrim() {
        for (int i = 1; i <= 6; i++) {
            expect("XADD s " + i + " n " + i, i + "-0");
        }
        expect("XDEL s 2 4 9", ":2");
        expect("XDEL s 2", ":0");
        expect("XDEL missing 1", ":0");
        expectError("XDEL s nope", "ERR Invalid stream ID specified as stream command argument");
        expect("XRANGE s - +", "[[1-0, [n, 1]], [3-0, [n, 3]], [5-0, [n, 5]], [6-0, [n, 6]]]");
        expect("XTRIM s MAXLEN 3", ":1");
        expect("XTRIM s MINID = 5", ":1");
        expect("XRANGE s - +", "[[5-0, [n, 5]], [6-0, [n, 6]]]");
        expect("XTRIM s MAXLEN ~ 0", ":2");                  // approximate is accepted, and trims exactly
        expect("XLEN s", ":0");
        expect("EXISTS s", ":1");                           // an empty stream stays, with its last ID
        expect("TYPE s", "+stream");
        expectError("XADD s 6 n 6", "ERR The ID specified in XADD is equal or smaller than the target stream top item");

        for (int i = 10; i < 20; i++) {
            call("XADD s " + i + " n " + i);
        }
        expect("XTRIM s MAXLEN ~ 2 LIMIT 3", ":3");         // LIMIT caps an approximate trim
        expect("XLEN s", ":7");
        expectError("XTRIM s MAXLEN 2 LIMIT 3", "ERR syntax error, LIMIT cannot be used without the special ~ option");
        expectError("XTRIM s MAXLEN -1", "ERR The MAXLEN argument must be >= 0.");
        expectError("XTRIM s MINID nope", "ERR Invalid stream ID specified as stream command argument");
        expectError("XTRIM s LEN 2", "ERR syntax error");

        expect("XADD s MAXLEN 2 30 n 30", "30-0");           // trimmed after the new entry is in
        expect("XRANGE s - +", "[[19-0, [n, 19]], [30-0, [n, 30]]]");
        expect("XADD s MINID 30 * n 31", T0 + "-0");
        expect("XLEN s", ":2");
        expectError("XADD s MAXLEN 1 MINID 2 * n 1", "ERR syntax error, MAXLEN and MINID options at the same time are not compatible");
        expectError("XADD s MAXLEN 1 LIMIT 5 * n 1", "ERR syntax error, LIMIT cannot be used without the special ~ option");
        expect("XADD fresh NOMKSTREAM * n 1", "(nil)");
        expect("EXISTS fresh", ":0");
        expect("XADD s NOMKSTREAM " + (T0 + 1) + "-0 n 2", (T0 + 1) + "-0");
    }

    @Test
    void wrongType() {
        expect("SET str x", "+OK");
        expectError("XADD str * a 1", "WRONGTYPE");
        expectError("XRANGE str - +", "WRONGTYPE");
        expectError("XLEN str", "WRONGTYPE");
        expectError("XDEL str 1", "WRONGTYPE");
        expectError("XTRIM str MAXLEN 1", "WRONGTYPE");
        expect("XADD s 1 a 1", "1-0");
        expectError("HSET s f v", "WRONGTYPE");
    }

    @Test
    void acrossChunks() {
        // More than three chunks of 256, so each read and trim crosses boundaries.
        for (int i = 1; i <= 1_000; i++) {
            call("XADD s " + i + " v " + i);
        }
        expect("XLEN s", ":1000");
        // Ends that are entries' exact IDs, one of them the first of a chunk.
        expect("XREVRANGE s 257-0 255-0", "[[257-0, [v, 257]], [256-0, [v, 256]], [255-0, [v, 255]]]");
        expect("XREVRANGE s 300-0 298-0", "[[300-0, [v, 300]], [299-0, [v, 299]], [298-0, [v, 298]]]");
        expect("XRANGE s 255-0 257-0", "[[255-0, [v, 255]], [256-0, [v, 256]], [257-0, [v, 257]]]");
        expect("XRANGE s 255 258", "[[255-0, [v, 255]], [256-0, [v, 256]], [257-0, [v, 257]], [258-0, [v, 258]]]");
        expect("XDEL s 256 257 512 513", ":4");
        expect("XRANGE s 255 258", "[[255-0, [v, 255]], [258-0, [v, 258]]]");
        expect("XREVRANGE s 514 511", "[[514-0, [v, 514]], [511-0, [v, 511]]]");
        expect("XRANGE s (255 + COUNT 1", "[[258-0, [v, 258]]]");
        expect("XTRIM s MINID 300", ":297");
        expect("XRANGE s - + COUNT 1", "[[300-0, [v, 300]]]");
        expect("XREVRANGE s + - COUNT 3", "[[1000-0, [v, 1000]], [999-0, [v, 999]], [998-0, [v, 998]]]");
        expect("XTRIM s MAXLEN 10", ":689");
        expect("XRANGE s - + COUNT 1", "[[991-0, [v, 991]]]");
        List<String> all = new ArrayList<>();
        for (int i = 991; i <= 1_000; i++) {
            all.add("[" + i + "-0, [v, " + i + "]]");
        }
        expect("XRANGE s - +", "[" + String.join(", ", all) + "]");
    }

    @Test
    void aCopyIsItsOwn() {
        expect("XADD s 1 a 1", "1-0");
        expect("XADD s 2 a 2", "2-0");
        expect("COPY s d", ":1");
        expect("XADD d 3 a 3", "3-0");
        expect("XADD s 4 a 4", "4-0");
        expect("XDEL s 1", ":1");
        expect("XRANGE s - +", "[[2-0, [a, 2]], [4-0, [a, 4]]]");
        expect("XRANGE d - +", "[[1-0, [a, 1]], [2-0, [a, 2]], [3-0, [a, 3]]]");
        assertThat(call("SCAN 0 TYPE stream COUNT 100")).isIn("[0, [d, s]]", "[0, [s, d]]");
        assertThat(call("SCAN 0 TYPE hash COUNT 100")).isEqualTo("[0, []]");
    }
}
