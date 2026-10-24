package com.backend.platform.watch;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * One alert the watch raises (04 §12, D-86): a rule's name, the labels of the series it fired for, the
 * rule's words, and when the condition first held. Its identity is the name and the labels.
 */
public record Alert(String name, Map<String, String> labels, String summary, String runbook, long sinceMillis) {

    public Alert {
        labels = Collections.unmodifiableMap(new TreeMap<>(labels));
    }

    /** The name and the labels, as the store keys an alert and a notification is deduplicated: stable across machines. */
    public String fingerprint() {
        StringBuilder out = new StringBuilder(name);
        for (Map.Entry<String, String> e : labels.entrySet()) {
            out.append(',').append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }
}
