package com.backend.platform.watch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.backend.common.MetricsText.Sample;

/**
 * The rules over the rounds (04 §12): keeps the hour of history of the series the rules read, evaluates
 * every rule on each round, and holds each alert's state. A condition that holds for less than its rule's
 * wait is pending; for the wait or longer, firing; one that stops holding resolves on that round. Not
 * thread-safe: the watch's own thread calls it.
 */
public final class AlertEngine {

    /** What changed in a round: alerts that began to fire, and firing ones that resolved. */
    public record Round(List<Alert> fired, List<Alert> resolved) { }

    private record Key(String rule, Map<String, String> labels) { }

    private final List<Rule> rules;
    private final Set<String> read = new HashSet<>();
    private final Map<String, Map<Map<String, String>, History>> histories = new HashMap<>();
    private final Map<Key, Long> since = new HashMap<>();
    private final Map<Key, Alert> firing = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));

    public AlertEngine(List<Rule> rules) {
        this.rules = List.copyOf(rules);
        for (Rule r : rules) {
            read.addAll(r.reads());
        }
    }

    public Round round(List<Sample> samples, long nowMillis) {
        // The round's series, each with its history; a series not sampled this round is not seen by a rule.
        Map<String, List<Rule.Series>> present = new HashMap<>();
        for (Sample s : samples) {
            if (!read.contains(s.name())) {
                continue;
            }
            History h = histories.computeIfAbsent(s.name(), k -> new HashMap<>())
                    .computeIfAbsent(s.labels(), k -> new History());
            h.add(nowMillis, s.value());
            present.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(new Rule.Series(s.labels(), h));
        }
        forget(nowMillis);
        Rule.View view = metric -> present.getOrDefault(metric, List.of());

        List<Alert> fired = new ArrayList<>();
        List<Alert> resolved = new ArrayList<>();
        Set<Key> holding = new HashSet<>();
        for (Rule rule : rules) {
            for (Map<String, String> labels : rule.evaluate(view, nowMillis)) {
                Key key = new Key(rule.name(), new TreeMap<>(labels));
                holding.add(key);
                long first = since.computeIfAbsent(key, k -> nowMillis);
                if (nowMillis - first >= rule.forMillis() && !firing.containsKey(key)) {
                    Alert a = new Alert(rule.name(), labels, rule.summary(), rule.runbook(), first);
                    firing.put(key, a);
                    fired.add(a);
                }
            }
        }
        since.keySet().retainAll(holding);
        for (var it = firing.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!holding.contains(e.getKey())) {
                resolved.add(e.getValue());
                it.remove();
            }
        }
        return new Round(fired, resolved);
    }

    /** The alerts firing now, in a stable order. */
    public List<Alert> firing() {
        return Collections.unmodifiableList(new ArrayList<>(firing.values()));
    }

    /** How many series' histories are kept: what the watch costs in memory, for the tests to report. */
    public int seriesKept() {
        int n = 0;
        for (var byLabels : histories.values()) {
            n += byLabels.size();
        }
        return n;
    }

    /** A series not sampled for over an hour has nothing a rule can ask: its history goes. */
    private void forget(long nowMillis) {
        for (var byLabels : histories.values()) {
            byLabels.values().removeIf(h -> nowMillis - h.latestTime() > 3_600_000L + 60_000L);
        }
    }
}
