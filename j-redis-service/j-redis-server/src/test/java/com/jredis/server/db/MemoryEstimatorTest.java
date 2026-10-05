package com.jredis.server.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Header and reference sizes are decided by two <em>independent</em> VM flags. Tying both to
 * UseCompressedOops made the estimate 4 bytes per object too high under ZGC, which disables
 * compressed oops but keeps compressed class pointers.
 */
class MemoryEstimatorTest {

    @Test
    @DisplayName("the header follows compressed class pointers, not compressed oops")
    void headerFollowsClassPointers() {
        assertThat(MemoryEstimator.headerBytes(true)).isEqualTo(12);
        assertThat(MemoryEstimator.headerBytes(false)).isEqualTo(16);
    }

    @Test
    @DisplayName("a reference field follows compressed oops")
    void refFollowsOops() {
        assertThat(MemoryEstimator.refBytes(true)).isEqualTo(4);
        assertThat(MemoryEstimator.refBytes(false)).isEqualTo(8);
    }

    @Test
    @DisplayName("the ZGC combination is a 12-byte header with 8-byte references")
    void zgcCombination() {
        // ZGC: UseCompressedOops=false, UseCompressedClassPointers=true.
        assertThat(MemoryEstimator.headerBytes(true)).isEqualTo(12);
        assertThat(MemoryEstimator.refBytes(false)).isEqualTo(8);
    }

    @Test
    @DisplayName("the constants agree with the flags this JVM actually reports")
    void constantsMatchThisJvm() {
        assertThat(MemoryEstimator.HEADER)
                .isEqualTo(MemoryEstimator.headerBytes(MemoryEstimator.COMPRESSED_CLASS_POINTERS));
        assertThat(MemoryEstimator.REF)
                .isEqualTo(MemoryEstimator.refBytes(MemoryEstimator.COMPRESSED_OOPS));

        com.sun.management.HotSpotDiagnosticMXBean hotspot =
                ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        if (hotspot != null) {          // HotSpot only; the detection falls back elsewhere
            assertThat(MemoryEstimator.COMPRESSED_OOPS).isEqualTo(
                    Boolean.parseBoolean(hotspot.getVMOption("UseCompressedOops").getValue()));
            assertThat(MemoryEstimator.COMPRESSED_CLASS_POINTERS).isEqualTo(
                    Boolean.parseBoolean(hotspot.getVMOption("UseCompressedClassPointers").getValue()));
        }
    }

    @Test
    @DisplayName("every published size is positive and 8-byte aligned")
    void sizesAreSane() {
        assertThat(MemoryEstimator.KEY_ENTRY).isPositive();
        assertThat(MemoryEstimator.byteArray(0) % 8).isZero();
        assertThat(MemoryEstimator.refArray(4) % 8).isZero();
        assertThat(MemoryEstimator.align(1)).isEqualTo(8);
        assertThat(MemoryEstimator.align(8)).isEqualTo(8);
        assertThat(MemoryEstimator.align(9)).isEqualTo(16);
    }
}
