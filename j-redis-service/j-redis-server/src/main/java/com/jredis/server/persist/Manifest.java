package com.jredis.server.persist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which files make up the dataset, in load order: an optional base (point-in-time image) followed
 * by one or more incremental AOF files. Replaced only atomically, so on disk it is always one of
 * its complete versions. Immutable.
 *
 * <pre>
 * format 1
 * generation 7
 * base base.7.jrdb
 * incr incr.7.aof
 * </pre>
 */
public final class Manifest {

    public static final String FILE = "manifest";
    static final int FORMAT = 1;

    public final long generation;
    public final String base;
    public final List<String> incrs;

    public Manifest(long generation, String base, List<String> incrs) {
        this.generation = generation;
        this.base = base;
        this.incrs = Collections.unmodifiableList(new ArrayList<>(incrs));
    }

    public static String baseName(long generation) {
        return "base." + generation + ".jrdb";
    }

    public static String incrName(long generation) {
        return "incr." + generation + ".aof";
    }

    public String lastIncr() {
        return incrs.get(incrs.size() - 1);
    }

    public Manifest withIncr(long newGeneration, String incr) {
        List<String> next = new ArrayList<>(incrs);
        next.add(incr);
        return new Manifest(newGeneration, base, next);
    }

    public static Manifest read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        long generation = -1;
        int format = -1;
        String base = null;
        List<String> incrs = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] kv = line.split("\\s+", 2);
            if (kv.length != 2) {
                throw new IOException("malformed manifest line: " + line);
            }
            switch (kv[0]) {
                case "format": format = Integer.parseInt(kv[1]); break;
                case "generation": generation = Long.parseLong(kv[1]); break;
                case "base": base = checkName(kv[1]); break;
                case "incr": incrs.add(checkName(kv[1])); break;
                default: throw new IOException("unknown manifest entry: " + kv[0]);
            }
        }
        if (format != FORMAT) {
            throw new IOException("unsupported manifest format " + format + " (this server reads format " + FORMAT + ")");
        }
        if (generation < 1 || incrs.isEmpty()) {
            throw new IOException("manifest must name a generation and at least one incr file");
        }
        return new Manifest(generation, base, incrs);
    }

    private static String checkName(String name) throws IOException {
        if (name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            throw new IOException("invalid file name in manifest: " + name);
        }
        return name;
    }

    public void write(Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("format ").append(FORMAT).append('\n');
        sb.append("generation ").append(generation).append('\n');
        if (base != null) {
            sb.append("base ").append(base).append('\n');
        }
        for (String incr : incrs) {
            sb.append("incr ").append(incr).append('\n');
        }
        FileUtil.writeAtomically(dir.resolve(FILE), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** True if the file name is one of ours but not referenced by this manifest (a leftover). */
    public boolean isLeftover(String fileName) {
        boolean ours = fileName.matches("base\\.\\d+\\.jrdb(\\.tmp)?") || fileName.matches("incr\\.\\d+\\.aof")
                || fileName.equals(FILE + ".tmp") || fileName.equals("replication.tmp")   // an epoch half written
                || fileName.matches("sync-\\d+\\.jrdb\\.tmp");      // an image from a primary, half received
        if (!ours) {
            return false;
        }
        return !fileName.equals(base) && !incrs.contains(fileName);
    }
}
