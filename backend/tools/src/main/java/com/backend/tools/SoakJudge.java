package com.backend.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Judges a soak (07 §4, plan item 47): does anything grow that should not. Reads the sampler's
 * lines, {@code second<TAB>series<TAB>value}, and judges the samples from {@code from} on, the
 * run's second hour; the first is an allowance for the JIT, the pools and the caches filling.
 *
 * <p>Usage: SoakJudge [samples] [fromSecond]. Prints a verdict a rule and exits 1 if any failed.
 */
public final class SoakJudge {

    public record Sample(long second, String series, double value) { }

    public record Verdict(String what, boolean passed, String detail) { }

    /** The processes sampled: each its live heap, threads and open files. */
    private static final List<String> PROCESSES = List.of("arena", "platform", "gateway", "worker", "store");
    /** A live heap whose slope, run on for a day, would grow it by more than this share leaks. */
    static final double HEAP_DAY_SHARE = 0.25;
    /** Threads, open files, keys, connections: up by more than this over the hour is a climb. */
    static final double COUNT_SLACK = 2;
    /** A result on the stream, with the stream's own overhead (D-26). */
    static final double BYTES_PER_ENTRY = 530;
    /** The store's heap may grow up to this many times what the stream's new entries take... */
    static final double STORE_HEAP_FACTOR = 2;
    /** ...and this much more: the duels' new players' sessions, names and board places. */
    static final double STORE_SLACK_BYTES = 1024 * 1024;
    /** Keys a duel run adds by design: two new players, each a session and its index. */
    static final Map<String, Double> KEYS_PER_DUEL = Map.of("sess", 4.0);
    /** Login throttles: two a duel run (its new accounts), each gone after 15 minutes. */
    static final String THROTTLES = "rl";
    static final double THROTTLES_PER_DUEL = 2;
    static final long THROTTLE_SECONDS = 15 * 60;
    /** Fewer samples than this in the hour cannot be judged. */
    static final int MIN_SAMPLES = 6;
    /** A count's start and end are each the median of this many samples, so one spike is not a climb. */
    private static final int EDGE = 3;

    private SoakJudge() {
    }

    public static void main(String[] args) throws Exception {
        List<Sample> samples = parse(Files.readAllLines(Path.of(args[0])));
        boolean passed = true;
        for (Verdict v : judge(samples, Long.parseLong(args[1]))) {
            System.out.printf(Locale.ROOT, "  %s %-20s %s%n", v.passed() ? "PASS" : "FAIL", v.what(), v.detail());
            passed &= v.passed();
        }
        System.exit(passed ? 0 : 1);
    }

    static List<Sample> parse(List<String> lines) {
        List<Sample> out = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            // A value the sampler could not read is empty: kept as one, for the judge to fail.
            String[] f = line.split("\t");
            out.add(new Sample(Long.parseLong(f[0]), f[1], f.length < 3 ? Double.NaN : Double.parseDouble(f[2])));
        }
        return out;
    }

    static List<Verdict> judge(List<Sample> samples, long from) {
        TreeMap<Long, Double> allDuels = new TreeMap<>();     // from the start: a window looks back
        for (Sample s : samples) {
            if (s.series().equals("duels")) {
                allDuels.put(s.second(), s.value());
            }
        }
        List<Verdict> out = new ArrayList<>();
        Map<String, TreeMap<Long, Double>> series = new TreeMap<>();
        TreeSet<Long> rounds = new TreeSet<>();
        TreeSet<Long> scanned = new TreeSet<>();
        Map<String, Integer> unreadable = new TreeMap<>();
        for (Sample s : samples) {
            if (s.second() >= from) {
                rounds.add(s.second());
                if (Double.isNaN(s.value())) {
                    unreadable.merge(s.series(), 1, Integer::sum);
                    continue;
                }
                series.computeIfAbsent(s.series(), k -> new TreeMap<>()).put(s.second(), s.value());
                if (s.series().startsWith("keys.")) {
                    scanned.add(s.second());
                }
            }
        }
        unreadable.forEach((what, n) -> out.add(new Verdict(what, false, n + " samples the sampler could not read")));
        // The store always holds keys, so a round with none is a scan that failed, not every family at 0.
        List<Long> unscanned = rounds.stream().filter(t -> !scanned.contains(t)).toList();
        if (!unscanned.isEmpty()) {
            out.add(new Verdict("keys", false, "no key read at second " + unscanned + ": the scan failed"));
        }
        // SCAN finds no key of a family that has none: a family missing from a round had 0.
        for (Map.Entry<String, TreeMap<Long, Double>> e : series.entrySet()) {
            if (e.getKey().startsWith("keys.")) {
                for (long t : rounds) {
                    e.getValue().putIfAbsent(t, 0.0);
                }
            }
        }

        for (String p : PROCESSES) {
            if (!p.equals("store")) {
                out.add(heap("heap." + p, series.get("heap." + p)));
            }
        }
        out.add(storeHeap(series.get("heap.store"), series.get("stream")));
        for (String p : PROCESSES) {
            out.add(count("threads." + p, series.get("threads." + p), 0));
            out.add(count("fds." + p, series.get("fds." + p), 0));
        }
        for (Map.Entry<String, TreeMap<Long, Double>> e : series.entrySet()) {
            if (e.getKey().equals("keys." + THROTTLES)) {
                out.add(throttles(e.getValue(), allDuels));
            } else if (e.getKey().startsWith("keys.")) {
                Double perDuel = KEYS_PER_DUEL.get(e.getKey().substring("keys.".length()));
                double allowed = 0;
                if (perDuel != null) {
                    TreeMap<Long, Double> duels = series.get("duels");
                    if (tooFew(duels)) {
                        out.add(new Verdict(e.getKey(), false, "no duel runs counted to judge it by"));
                        continue;
                    }
                    allowed = perDuel * rise(duels);
                }
                out.add(count(e.getKey(), e.getValue(), allowed));
            }
        }
        out.add(stream(series.get("stream"), series.get("results")));
        out.add(count("mysql.connections", series.get("mysql.connections"), 0));
        return out;
    }

    private static boolean tooFew(TreeMap<Long, Double> s) {
        return s == null || s.size() < MIN_SAMPLES;
    }

    private static Verdict tooFew(String what, TreeMap<Long, Double> s) {
        return new Verdict(what, false, (s == null ? 0 : s.size()) + " samples in the hour, too few to judge");
    }

    private static Verdict heap(String what, TreeMap<Long, Double> s) {
        if (tooFew(s)) {
            return tooFew(what, s);
        }
        double mean = s.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double perSecond = slope(s);
        return new Verdict(what, perSecond * 86_400 < HEAP_DAY_SHARE * mean, String.format(Locale.ROOT,
                "%.1f MB live on average, %+.3f MB an hour (a day's run under %.0f %% of it)",
                mean / 1048576, perSecond * 3600 / 1048576, HEAP_DAY_SHARE * 100));
    }

    private static Verdict storeHeap(TreeMap<Long, Double> heap, TreeMap<Long, Double> stream) {
        if (tooFew(heap)) {
            return tooFew("heap.store", heap);
        }
        if (tooFew(stream)) {
            return tooFew("heap.store", stream);
        }
        // Both by their slopes over the same span: the heap's growth against the entries added.
        double span = heap.lastKey() - heap.firstKey();
        double grew = slope(heap) * span;
        double entries = slope(stream) * span;
        double allowed = STORE_HEAP_FACTOR * BYTES_PER_ENTRY * entries + STORE_SLACK_BYTES;
        return new Verdict("heap.store", grew <= allowed, String.format(Locale.ROOT,
                "%+.2f MB over the hour, against %.2f MB for %.0f new entries",
                grew / 1048576, allowed / 1048576, entries));
    }

    private static Verdict stream(TreeMap<Long, Double> stream, TreeMap<Long, Double> results) {
        if (tooFew(stream)) {
            return tooFew("stream", stream);
        }
        if (tooFew(results)) {
            return tooFew("stream", results);
        }
        return new Verdict("stream", rise(stream) <= rise(results) + COUNT_SLACK, String.format(Locale.ROOT,
                "%+.0f entries for %.0f results published", rise(stream), rise(results)));
    }

    /** At every sample, no more than the duels started in the window before it could hold. */
    private static Verdict throttles(TreeMap<Long, Double> s, TreeMap<Long, Double> duels) {
        String what = "keys." + THROTTLES;
        if (tooFew(s)) {
            return tooFew(what, s);
        }
        for (Map.Entry<Long, Double> e : s.entrySet()) {
            Map.Entry<Long, Double> now = duels.floorEntry(e.getKey());
            Map.Entry<Long, Double> before = duels.floorEntry(e.getKey() - THROTTLE_SECONDS);
            if (now == null) {
                return new Verdict(what, false, "no duel runs counted to judge it by");
            }
            double bound = THROTTLES_PER_DUEL * (now.getValue() - (before == null ? 0 : before.getValue())) + COUNT_SLACK;
            if (e.getValue() > bound) {
                return new Verdict(what, false, String.format(Locale.ROOT,
                        "%.0f at second %d, against %.0f for the duels of the 15 minutes before", e.getValue(), e.getKey(), bound));
            }
        }
        return new Verdict(what, true, String.format(Locale.ROOT,
                "at most %.0f, never more than two a duel of the 15 minutes before",
                s.values().stream().mapToDouble(Double::doubleValue).max().orElse(0)));
    }

    private static Verdict count(String what, TreeMap<Long, Double> s, double allowed) {
        if (tooFew(s)) {
            return tooFew(what, s);
        }
        double rise = rise(s);
        return new Verdict(what, rise <= allowed + COUNT_SLACK, String.format(Locale.ROOT,
                "%.0f to %.0f (%+.0f, %.0f allowed)", edge(s, true), edge(s, false), rise, allowed + COUNT_SLACK));
    }

    /** The end's median less the start's. */
    private static double rise(TreeMap<Long, Double> s) {
        return edge(s, false) - edge(s, true);
    }

    private static double edge(TreeMap<Long, Double> s, boolean start) {
        List<Double> values = new ArrayList<>(s.values());
        List<Double> edge = start ? values.subList(0, EDGE) : values.subList(values.size() - EDGE, values.size());
        double[] sorted = edge.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        return sorted[EDGE / 2];
    }

    /**
     * Value a second: the median of every pair's slope (Theil–Sen). A least-squares line was
     * swung by a round or two taken while a match's room was alive and gone at the next.
     */
    private static double slope(TreeMap<Long, Double> s) {
        List<Map.Entry<Long, Double>> points = new ArrayList<>(s.entrySet());
        double[] slopes = new double[points.size() * (points.size() - 1) / 2];
        int k = 0;
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                slopes[k++] = (points.get(j).getValue() - points.get(i).getValue())
                        / (points.get(j).getKey() - points.get(i).getKey());
            }
        }
        Arrays.sort(slopes);
        return slopes[slopes.length / 2];
    }
}
