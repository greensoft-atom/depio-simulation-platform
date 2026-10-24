package com.backend.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the text format {@link Metrics#render} writes, the one Prometheus's readers read, into samples: a name,
 * its labels and a value. For the watch (04 §12, D-86), which reads this machine's processes.
 *
 * Lenient where the format lets a writer vary (a timestamp after the value, spaces around the labels'
 * punctuation) and strict where a line cannot be meant: such a line is skipped, and the rest is read.
 */
public final class MetricsText {

    /** One series' value at a scrape. The labels are sorted by name, so two samples' labels compare as maps do. */
    public record Sample(String name, Map<String, String> labels, double value) {
        public Sample {
            labels = Collections.unmodifiableMap(new TreeMap<>(labels));
        }
    }

    private MetricsText() {
    }

    public static List<Sample> parse(String text) {
        List<Sample> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            Sample s = parseLine(line.strip());
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** @return the line's sample, or null for a comment, a blank line, or one that cannot be read */
    static Sample parseLine(String line) {
        if (line.isEmpty() || line.charAt(0) == '#') {
            return null;
        }
        int n = line.length();
        int i = 0;
        while (i < n && isNameChar(line.charAt(i), i == 0)) {
            i++;
        }
        if (i == 0) {
            return null;
        }
        String name = line.substring(0, i);
        Map<String, String> labels = new TreeMap<>();
        if (i < n && line.charAt(i) == '{') {
            i++;
            while (true) {
                i = skipSpaces(line, i);
                if (i >= n) {
                    return null;                                  // the braces never close
                }
                if (line.charAt(i) == '}') {
                    i++;
                    break;
                }
                int start = i;
                while (i < n && isNameChar(line.charAt(i), i == start)) {
                    i++;
                }
                if (i == start) {
                    return null;
                }
                String key = line.substring(start, i);
                i = skipSpaces(line, i);
                if (i >= n || line.charAt(i) != '=') {
                    return null;
                }
                i = skipSpaces(line, i + 1);
                if (i >= n || line.charAt(i) != '"') {
                    return null;
                }
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                i++;
                while (i < n) {
                    char c = line.charAt(i++);
                    if (c == '\\') {
                        if (i >= n) {
                            return null;
                        }
                        char e = line.charAt(i++);
                        value.append(e == 'n' ? '\n' : e);        // \\ and \" are themselves; \n a newline
                    } else if (c == '"') {
                        closed = true;
                        break;
                    } else {
                        value.append(c);
                    }
                }
                if (!closed) {
                    return null;
                }
                labels.put(key, value.toString());
                i = skipSpaces(line, i);
                if (i < n && line.charAt(i) == ',') {
                    i++;
                }
            }
        }
        i = skipSpaces(line, i);
        if (i >= n) {
            return null;                                          // a name with no value
        }
        int start = i;
        while (i < n && line.charAt(i) != ' ' && line.charAt(i) != '\t') {
            i++;
        }
        Double value = number(line.substring(start, i));          // anything after it is a timestamp, ignored
        return value == null ? null : new Sample(name, labels, value);
    }

    private static Double number(String s) {
        switch (s) {
            case "NaN", "nan":
                return Double.NaN;
            case "+Inf", "Inf", "inf", "+inf":
                return Double.POSITIVE_INFINITY;
            case "-Inf", "-inf":
                return Double.NEGATIVE_INFINITY;
            default:
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    return null;
                }
        }
    }

    private static boolean isNameChar(char c, boolean first) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == ':' || (!first && c >= '0' && c <= '9');
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }
}
