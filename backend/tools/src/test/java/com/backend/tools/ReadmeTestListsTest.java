package com.backend.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each module's README lists its test classes in a table, most with the number of tests each has
 * (DOC-29). Checked here against the sources, so a test added without its row fails the build
 * rather than leaving the README behind the code.
 */
class ReadmeTestListsTest {

    /** The backend's modules, beside this one: Maven runs the tests in the module's directory. */
    private static final Path BACKEND = Path.of("..").toAbsolutePath().normalize();
    private static final Pattern A_TEST = Pattern.compile(
            "^\\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory)\\b", Pattern.MULTILINE);
    /** A class named in a row: {@code `ArenaServerTest`}, or with its package, {@code `net/AdminServerTest`}. */
    private static final Pattern NAMED = Pattern.compile("`(?:\\w+/)*(\\w+Test)`");

    @Test
    @DisplayName("every module's README names each of its test classes, with the number of tests where its row gives one (DOC-29)")
    void everyReadmeListsItsTests() throws IOException {
        List<String> wrong = new ArrayList<>();
        int modules = 0;
        try (Stream<Path> dirs = Files.list(BACKEND)) {
            for (Path module : dirs.filter(d -> Files.isDirectory(d.resolve("src/test/java"))).sorted().toList()) {
                modules++;
                String name = module.getFileName().toString();
                Map<String, Integer> tests = testsIn(module.resolve("src/test/java"));
                Set<String> named = new HashSet<>();
                for (String line : Files.readAllLines(module.resolve("README.md"))) {
                    String[] cells = line.split("\\|", -1);
                    if (!line.startsWith("| `") || cells.length < 4) {
                        continue;
                    }
                    List<String> classes = NAMED.matcher(cells[1]).results().map(m -> m.group(1)).toList();
                    named.addAll(classes);
                    String given = cells[2].trim();
                    if (classes.size() == 1 && given.matches("[0-9]+")) {
                        Integer has = tests.get(classes.get(0));
                        if (has == null) {
                            wrong.add(name + ": " + classes.get(0) + " is in its README, and no such test class");
                        } else if (has != Integer.parseInt(given)) {
                            wrong.add(name + ": " + classes.get(0) + " has " + has + " tests, its README says " + given);
                        }
                    }
                }
                for (String test : tests.keySet()) {
                    if (!named.contains(test)) {
                        wrong.add(name + ": " + test + " is not in its README");
                    }
                }
            }
        }
        assertThat(modules).as("modules with tests under " + BACKEND).isGreaterThanOrEqualTo(10);
        assertThat(wrong).as("each module's README against its tests").isEmpty();
    }

    /** Each test class under {@code root}, by its simple name, and how many tests it declares. */
    private static Map<String, Integer> testsIn(Path root) throws IOException {
        Map<String, Integer> tests = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(f -> f.getFileName().toString().endsWith("Test.java")).toList()) {
                String simple = f.getFileName().toString().replace(".java", "");
                tests.put(simple, (int) A_TEST.matcher(Files.readString(f)).results().count());
            }
        }
        return tests;
    }
}
