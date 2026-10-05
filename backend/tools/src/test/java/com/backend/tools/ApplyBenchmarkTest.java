package com.backend.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApplyBenchmarkTest {

    @Test
    @DisplayName("it runs only on a database named for tests, since it drops everything in it")
    void onlyATestDatabase() {
        assertThat(ApplyBenchmark.isTestDatabase("jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false")).isTrue();
        assertThat(ApplyBenchmark.isTestDatabase("jdbc:mysql://db-a:3306/bench_test")).isTrue();
        assertThat(ApplyBenchmark.isTestDatabase("jdbc:mysql://127.0.0.1:3306/backend?useSSL=false")).isFalse();
        assertThat(ApplyBenchmark.isTestDatabase("jdbc:mysql://127.0.0.1:3306/backend_dev")).isFalse();
        assertThat(ApplyBenchmark.isTestDatabase("jdbc:mysql://127.0.0.1:3306/")).isFalse();
        assertThat(ApplyBenchmark.isTestDatabase("jdbc:mysql://h1:3306,h2:3306/backend_test")).as("a primary and its replica")
                .isFalse();
    }
}
