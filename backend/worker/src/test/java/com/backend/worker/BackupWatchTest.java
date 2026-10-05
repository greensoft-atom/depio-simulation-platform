package com.backend.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import com.backend.persistence.BackupRunRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The backups as every worker reports them (docs 06 §10, D-71). */
class BackupWatchTest {

    @Test
    @DisplayName("every kind named, always: when it last succeeded, NaN for never; 1 when its last run failed")
    void series() {
        Map<Integer, BackupRunRepository.Latest> latest = Map.of(
                BackupRunRepository.DUMP, new BackupRunRepository.Latest(Instant.ofEpochSecond(1_000), true, null),
                BackupRunRepository.PROOF, new BackupRunRepository.Latest(Instant.ofEpochSecond(2_000), false, 812.5),
                BackupRunRepository.OFFSITE, new BackupRunRepository.Latest(null, true, null));
        assertThat(BackupWatch.succeeded(latest)).containsOnlyKeys("dump", "proof", "offsite")
                .containsEntry("dump", 1_000.0).containsEntry("proof", 2_000.0);
        assertThat(BackupWatch.succeeded(latest).get("offsite")).as("never succeeded").isNaN();
        assertThat(BackupWatch.succeeded(Map.of()).get("proof")).as("never run").isNaN();
        assertThat(BackupWatch.failed(latest)).containsExactlyInAnyOrderEntriesOf(
                Map.of("dump", 1.0, "proof", 0.0, "offsite", 1.0));
        assertThat(BackupWatch.failed(Map.of())).as("never run: nothing failed").containsExactlyInAnyOrderEntriesOf(
                Map.of("dump", 0.0, "proof", 0.0, "offsite", 0.0));
        assertThat(BackupWatch.restoreSeconds(latest)).as("the last proof's restore (D-72)").isEqualTo(812.5);
        assertThat(BackupWatch.restoreSeconds(Map.of())).as("none yet").isNaN();
    }
}
