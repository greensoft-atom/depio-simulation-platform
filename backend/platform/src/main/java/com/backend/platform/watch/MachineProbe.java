package com.backend.platform.watch;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.backend.common.MetricsText.Sample;

/**
 * This machine as samples (04 §12): each watched path's file system, free and size; the memory free and in
 * all, from {@code /proc/meminfo}'s {@code MemAvailable}, which counts what the kernel would give up (the
 * page cache), where the JDK's "free" counts what nobody touches; the load over the last minute, and the
 * cores it is measured against. What node_exporter would have read, by the JDK alone.
 */
final class MachineProbe {

    static final Path MEMINFO = Path.of("/proc/meminfo");

    private MachineProbe() {
    }

    static List<Sample> read(List<Path> disks, String machine) {
        List<Sample> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path path : disks) {
            try {
                if (!Files.exists(path)) {
                    continue;
                }
                FileStore fs = Files.getFileStore(path);
                if (!seen.add(fs.name() + " " + fs.type())) {
                    continue;                                     // the same file system under another path
                }
                Map<String, String> labels = new TreeMap<>(Map.of("mount", path.toString(), "machine", machine));
                out.add(new Sample("machine_disk_free_bytes", labels, fs.getUsableSpace()));
                out.add(new Sample("machine_disk_size_bytes", labels, fs.getTotalSpace()));
            } catch (IOException | RuntimeException e) {
                // A path that cannot be read gives no series; its absence shows in the admin API's view of the round.
            }
        }
        Map<String, String> here = Map.of("machine", machine);
        long[] memory = meminfo();
        if (memory != null) {
            out.add(new Sample("machine_memory_free_bytes", here, memory[0]));
            out.add(new Sample("machine_memory_size_bytes", here, memory[1]));
        }
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        double load = os.getSystemLoadAverage();
        if (load >= 0) {
            out.add(new Sample("machine_load_1m", here, load));
        }
        out.add(new Sample("machine_cores", here, os.getAvailableProcessors()));
        return out;
    }

    /** {MemAvailable, MemTotal} in bytes from {@code /proc/meminfo}; the JDK's figures where there is none. */
    private static long[] meminfo() {
        try {
            long available = -1;
            long total = -1;
            for (String line : Files.readAllLines(MEMINFO)) {
                if (line.startsWith("MemAvailable:")) {
                    available = kilobytes(line);
                } else if (line.startsWith("MemTotal:")) {
                    total = kilobytes(line);
                }
            }
            if (available >= 0 && total > 0) {
                return new long[] {available, total};
            }
        } catch (IOException | RuntimeException e) {
            // not Linux, or no /proc: below
        }
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sun && sun.getTotalMemorySize() > 0) {
            return new long[] {sun.getFreeMemorySize(), sun.getTotalMemorySize()};
        }
        return null;
    }

    private static long kilobytes(String line) {
        String[] parts = line.split("\\s+");
        return Long.parseLong(parts[1]) * 1024;
    }
}
