package com.backend.platform.watch;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One of the runbook's alerts as code (04 §12): what it reads, how long its condition must hold, and which
 * series fire at a round. {@link Rules} holds them all.
 */
public interface Rule {

    /** What the round saw: the series of a metric sampled this round, each with its hour of history. */
    interface View {
        List<Series> present(String metric);
    }

    record Series(Map<String, String> labels, History history) { }

    String name();

    String summary();

    /** The runbook section to open, as a path and anchor: {@code docs/operations/02-runbook.md#5-what-to-watch}. */
    String runbook();

    /** How long the condition must hold before the alert fires; 0 fires at once. */
    long forMillis();

    /** The metrics it reads, so the engine keeps their history and no other's. */
    Set<String> reads();

    /** The labels of each series the condition holds for, this round. */
    List<Map<String, String>> evaluate(View view, long nowMillis);
}
