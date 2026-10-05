package com.backend.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongToDoubleFunction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SoakJudgeTest {

    private static final long HOUR = 3600;
    private static final double MB = 1024 * 1024;

    /** Two hours sampled every five minutes, every series flat but what a test changes. */
    private static List<SoakJudge.Sample> run(String changed, LongToDoubleFunction value) {
        return run(300, changed, value);
    }

    private static List<SoakJudge.Sample> run(long every, String changed, LongToDoubleFunction value) {
        List<SoakJudge.Sample> out = new ArrayList<>();
        for (long t = 0; t <= 2 * HOUR; t += every) {
            for (String p : new String[] {"arena", "platform", "gateway", "worker", "store"}) {
                add(out, t, "heap." + p, 50 * MB, changed, value);
                add(out, t, "threads." + p, 40, changed, value);
                add(out, t, "fds." + p, 120, changed, value);
            }
            add(out, t, "keys.conn", 300, changed, value);
            add(out, t, "keys.sess", 300 + 4 * (t / 30), changed, value);   // a duel run every 30 s
            add(out, t, "keys.rl", 2 * Math.min(t / 30, 30) + 1, changed, value);  // the last 15 minutes' logins
            add(out, t, "duels", t / 30, changed, value);
            add(out, t, "results", 1.7 * t, changed, value);
            add(out, t, "stream", 1.7 * t, changed, value);
            add(out, t, "mysql.connections", 21, changed, value);
        }
        return out;
    }

    private static void add(List<SoakJudge.Sample> out, long t, String series, double flat,
                            String changed, LongToDoubleFunction value) {
        out.add(new SoakJudge.Sample(t, series, series.equals(changed) ? value.applyAsDouble(t) : flat));
    }

    private static List<SoakJudge.Verdict> failed(List<SoakJudge.Sample> samples) {
        return SoakJudge.judge(samples, HOUR).stream().filter(v -> !v.passed()).toList();
    }

    private static double storeHeap(long t, double perEntry) {
        return 50 * MB + 1.7 * t * perEntry;
    }

    @Test
    @DisplayName("a run where nothing grows but by design passes, and every rule is reported")
    void flatPasses() {
        List<SoakJudge.Verdict> verdicts = SoakJudge.judge(run("", t -> 0), HOUR);
        assertThat(verdicts).allMatch(SoakJudge.Verdict::passed);
        assertThat(verdicts).extracting(SoakJudge.Verdict::what)
                .contains("heap.arena", "heap.platform", "heap.gateway", "heap.worker", "heap.store",
                        "threads.store", "fds.gateway", "keys.conn", "keys.sess", "stream", "mysql.connections");
    }

    @Test
    @DisplayName("a live heap that would grow a quarter of itself in a day fails; one just under passes")
    void heapSlope() {
        // 50 MB and a quarter of it a day: 12.5 MB over 86 400 s.
        double quarterADay = 12.5 * MB / 86_400;
        assertThat(failed(run("heap.worker", t -> 50 * MB + t * quarterADay * 1.1)))
                .extracting(SoakJudge.Verdict::what).containsExactly("heap.worker");
        assertThat(failed(run("heap.worker", t -> 50 * MB + t * quarterADay * 0.9))).isEmpty();
    }

    @Test
    @DisplayName("a room made and freed between rounds is not a slope: two rounds 4 MB down do not make one")
    void dipsAreNotASlope() {
        assertThat(failed(run("heap.arena", t -> t == HOUR + 1200 || t == HOUR + 3300 ? 46 * MB : 50 * MB))).isEmpty();
        assertThat(failed(run("heap.arena", t -> t == HOUR + 300 || t == HOUR + 600 ? 46 * MB : 50 * MB)))
                .as("early in the hour").isEmpty();
        double leak = 2 * 12.5 * MB / 86_400;                 // twice what a day may grow
        assertThat(failed(run("heap.arena", t -> 50 * MB + t * leak - (t == HOUR + 1800 ? 4 * MB : 0))))
                .as("nor do they hide a slope").extracting(SoakJudge.Verdict::what).containsExactly("heap.arena");
    }

    @Test
    @DisplayName("only the judged hour counts: what the first hour grew, filling caches, does not")
    void warmUpIsNotJudged() {
        assertThat(failed(run("heap.arena", t -> t < HOUR ? 20 * MB + t * 8000 : 20 * MB + HOUR * 8000))).isEmpty();
        assertThat(failed(run("threads.platform", t -> t < HOUR ? 10 + t / 100 : 46))).isEmpty();
    }

    @Test
    @DisplayName("threads and open files: more than two up over the hour fails, noise of a sample does not")
    void countsMayNotClimb() {
        assertThat(failed(run("threads.gateway", t -> 40 + Math.max(0, t - HOUR) / 600)))
                .extracting(SoakJudge.Verdict::what).containsExactly("threads.gateway");
        assertThat(failed(run("fds.arena", t -> t >= HOUR + 1800 ? 123 : 120)))
                .extracting(SoakJudge.Verdict::what).containsExactly("fds.arena");
        assertThat(failed(run("fds.arena", t -> t == 2 * HOUR ? 140 : 120))).as("the last sample's spike").isEmpty();
        assertThat(failed(run("fds.arena", t -> t >= HOUR + 1800 ? 122 : 120))).as("two is noise").isEmpty();
    }

    @Test
    @DisplayName("a key family grows only by its own rule: sessions by the duels' new players, the rest not at all")
    void keyFamilies() {
        assertThat(failed(run("keys.conn", t -> 300 + Math.max(0, t - HOUR) / 300)))
                .extracting(SoakJudge.Verdict::what).containsExactly("keys.conn");
        assertThat(failed(run("keys.sess", t -> 300 + 5 * (t / 30))))
                .extracting(SoakJudge.Verdict::what).containsExactly("keys.sess");
        // SCAN finds no key of a family that has none: a family missing from a round counts as 0.
        List<SoakJudge.Sample> appearing = new ArrayList<>(run("", t -> 0));
        for (long t = HOUR + 1800; t <= 2 * HOUR; t += 300) {
            appearing.add(new SoakJudge.Sample(t, "keys.tkt", 5));
        }
        assertThat(failed(appearing)).as("a family first seen in the hour")
                .extracting(SoakJudge.Verdict::what).containsExactly("keys.tkt");
    }

    @Test
    @DisplayName("login throttles live 15 minutes: no more than two a duel started in the 15 before a sample")
    void throttlesExpire() {
        assertThat(failed(run("keys.rl", t -> 2 * (t / 30) + 1))).as("never expiring")
                .extracting(SoakJudge.Verdict::what).containsExactly("keys.rl");
        // 30 duels in any 15 minutes: 60 accounts' keys, and the address's own.
        assertThat(failed(run("keys.rl", t -> t == HOUR + 1800 ? 64 : 61))).as("one sample over the bound")
                .extracting(SoakJudge.Verdict::what).containsExactly("keys.rl");
        assertThat(failed(run("keys.rl", t -> t == HOUR + 1800 ? 62 : 61))).as("within the slack").isEmpty();
        // A round takes seconds, so samples fall between the window's edges: the edge's duels are
        // taken from the sample before it, never after, which would undercount them.
        assertThat(failed(run(310, "keys.rl", t -> 2 * Math.min(t / 30 - Math.max(0, t - 900) / 30, 30) + 1)))
                .as("rounds 310 s apart").isEmpty();
        assertThat(SoakJudge.judge(run("", t -> 0), 0)).as("judged from the start, a window reaching before it")
                .allMatch(SoakJudge.Verdict::passed);
    }

    @Test
    @DisplayName("the stream grows an entry a result, and the store's heap no more than twice what its entries take")
    void streamAndStoreHeap() {
        assertThat(failed(run("stream", t -> 2.0 * t))).extracting(SoakJudge.Verdict::what).containsExactly("stream");
        assertThat(failed(run("stream", t -> 1.7 * t + (t >= HOUR + 1800 ? 2 : 0))))
                .as("a result published between the two reads").isEmpty();
        assertThat(failed(run("heap.store", t -> storeHeap(t, 900)))).isEmpty();
        assertThat(failed(run("heap.store", t -> storeHeap(t, 1150)))).as("within the new players' megabyte").isEmpty();
        assertThat(failed(run("heap.store", t -> storeHeap(t, 1300))))
                .extracting(SoakJudge.Verdict::what).containsExactly("heap.store");
    }

    @Test
    @DisplayName("MySQL's connections are flat: every pool is fixed")
    void mysqlConnections() {
        assertThat(failed(run("mysql.connections", t -> 21 + Math.max(0, t - HOUR) / 400)))
                .extracting(SoakJudge.Verdict::what).containsExactly("mysql.connections");
    }

    @Test
    @DisplayName("a series sampled too few times in the hour to judge fails rather than passes")
    void tooFewSamples() {
        List<SoakJudge.Sample> samples = new ArrayList<>(run("", t -> 0));
        samples.removeIf(s -> s.series().equals("heap.gateway") && s.second() > HOUR + 1200);
        assertThat(failed(samples)).as("five").extracting(SoakJudge.Verdict::what).containsExactly("heap.gateway");

        List<SoakJudge.Sample> six = new ArrayList<>(run("", t -> 0));
        six.removeIf(s -> s.series().equals("heap.gateway") && s.second() > HOUR + 1500);
        assertThat(failed(six)).as("six, the first at the hour's start").isEmpty();

        List<SoakJudge.Sample> noDuels = new ArrayList<>(run("", t -> 0));
        noDuels.removeIf(s -> s.series().equals("duels"));
        assertThat(failed(noDuels)).as("the families duels explain, with no duels counted")
                .extracting(SoakJudge.Verdict::what).containsExactlyInAnyOrder("keys.rl", "keys.sess");
    }

    @Test
    @DisplayName("a sample the sampler could not read fails its series, rather than the judge")
    void unreadableSamples() {
        List<String> lines = new ArrayList<>();
        for (SoakJudge.Sample s : run("", t -> 0)) {
            boolean lost = s.series().equals("stream") && s.second() == HOUR + 1800;
            lines.add(s.second() + "\t" + s.series() + "\t" + (lost ? "" : Double.toString(s.value())));
        }
        assertThat(failed(SoakJudge.parse(lines))).extracting(SoakJudge.Verdict::what).containsExactly("stream");
    }

    @Test
    @DisplayName("a round with no key at all is a scan that failed, not every family at 0")
    void aFailedScanIsNotZero() {
        List<SoakJudge.Sample> samples = new ArrayList<>(run("", t -> 0));
        samples.removeIf(s -> s.series().startsWith("keys.") && s.second() >= HOUR + 1800);
        assertThat(failed(samples)).extracting(SoakJudge.Verdict::what).containsExactly("keys");
    }

    @Test
    @DisplayName("the sampler's lines are read as second, series and value, tab-separated")
    void parse() {
        assertThat(SoakJudge.parse(List.of("300\theap.arena\t52428800", "", "600\tkeys.sess\t312")))
                .containsExactly(new SoakJudge.Sample(300, "heap.arena", 52428800),
                        new SoakJudge.Sample(600, "keys.sess", 312));
    }
}
