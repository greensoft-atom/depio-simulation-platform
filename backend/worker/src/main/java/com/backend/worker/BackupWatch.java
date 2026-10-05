package com.backend.worker;

import java.util.Map;
import java.util.TreeMap;

import com.backend.persistence.BackupRunRepository;

/**
 * The backups as every worker reports them (docs detailed-design/06-persistence-mysql.md §10, D-71), from
 * what their scripts recorded: each kind always named, so an alert can tell one that never ran.
 */
final class BackupWatch {

    private static final Map<Integer, String> KINDS = Map.of(BackupRunRepository.DUMP, "dump",
            BackupRunRepository.PROOF, "proof", BackupRunRepository.OFFSITE, "offsite");

    private BackupWatch() {
    }

    /** When each kind last succeeded, in seconds since the epoch; NaN for never. */
    static Map<String, Double> succeeded(Map<Integer, BackupRunRepository.Latest> latest) {
        Map<String, Double> out = new TreeMap<>();
        KINDS.forEach((kind, name) -> {
            BackupRunRepository.Latest l = latest.get(kind);
            out.put(name, l == null || l.succeeded() == null ? Double.NaN : (double) l.succeeded().getEpochSecond());
        });
        return out;
    }

    /** How long the last successful proof's restore and replay took, NaN for none: the restore objective's (D-72). */
    static double restoreSeconds(Map<Integer, BackupRunRepository.Latest> latest) {
        BackupRunRepository.Latest proof = latest.get(BackupRunRepository.PROOF);
        return proof == null || proof.seconds() == null ? Double.NaN : proof.seconds();
    }

    /** 1 for each kind whose last run failed, 0 otherwise. */
    static Map<String, Double> failed(Map<Integer, BackupRunRepository.Latest> latest) {
        Map<String, Double> out = new TreeMap<>();
        KINDS.forEach((kind, name) -> {
            BackupRunRepository.Latest l = latest.get(kind);
            out.put(name, l != null && l.failed() ? 1.0 : 0.0);
        });
        return out;
    }
}
