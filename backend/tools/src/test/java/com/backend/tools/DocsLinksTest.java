package com.backend.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every relative link in the documents names a file that exists and, when it carries an anchor, a heading in
 * that file (DOC-33): a link to a section that is not there fails the build rather than waiting for a
 * reader. Anchors as GitHub makes them from a heading: lower case, spaces to hyphens, punctuation dropped, a
 * repeated heading numbered. The research and archive folders are not read: they are kept, not kept current.
 */
class DocsLinksTest {

    /** The repository's docs/, from this module's directory, where Maven runs the tests. */
    private static final Path DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final Set<String> NOT_READ = Set.of("research", "archive");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern INLINE_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");

    private final Map<Path, Set<String>> anchors = new HashMap<>();

    @Test
    @DisplayName("every relative link in docs/ names a file that exists, and its anchor a heading in it (DOC-33)")
    void everyLinkResolves() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(DOCS)) {
            files = walk.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !NOT_READ.contains(DOCS.relativize(p).getName(0).toString()) || DOCS.relativize(p).getNameCount() == 1)
                    .sorted().toList();
        }
        assertThat(files).as("the documents").hasSizeGreaterThan(20);
        List<String> bad = new ArrayList<>();
        int links = 0;
        for (Path file : files) {
            Matcher m = LINK.matcher(Files.readString(file));
            while (m.find()) {
                String target = m.group(1);
                if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) {
                    continue;
                }
                links++;
                int hash = target.indexOf('#');
                String path = hash < 0 ? target : target.substring(0, hash);
                String anchor = hash < 0 ? "" : target.substring(hash + 1);
                Path full = path.isEmpty() ? file : file.getParent().resolve(path).normalize();
                if (!Files.exists(full)) {
                    bad.add(DOCS.relativize(file) + ": " + target + " (no such file)");
                } else if (!anchor.isEmpty() && full.toString().endsWith(".md") && !anchorsOf(full).contains(anchor)) {
                    bad.add(DOCS.relativize(file) + ": " + target + " (no such heading)");
                }
            }
        }
        assertThat(links).as("relative links read").isGreaterThan(100);
        assertThat(bad).as("links that do not resolve").isEmpty();
    }

    /** The anchors GitHub gives a file's headings; a heading repeated gets -1, -2 and so on. */
    private Set<String> anchorsOf(Path file) throws IOException {
        Set<String> known = anchors.get(file);
        if (known != null) {
            return known;
        }
        known = new HashSet<>();
        Map<String, Integer> seen = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            Matcher h = HEADING.matcher(line);
            if (!h.matches()) {
                continue;
            }
            String text = INLINE_LINK.matcher(h.group(1).replace("`", "")).replaceAll("$1");
            String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s_-]", "").strip().replaceAll("\\s", "-");
            int n = seen.merge(slug, 1, Integer::sum);
            known.add(n == 1 ? slug : slug + "-" + (n - 1));
        }
        anchors.put(file, known);
        return known;
    }
}
