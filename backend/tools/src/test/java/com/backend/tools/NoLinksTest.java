package com.backend.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nothing in the checkout is a symbolic link (O-98, D-106): a copy that does not keep links, Git for Windows by
 * default among them, leaves in each one's place a text file holding the name it pointed to, and mysqld stops on
 * {@code libaio.so.1}, "file too short". What builds and runs write is not read: {@code work/}, a Maven project's
 * {@code target/}, a .NET project's {@code bin/} and {@code obj/}.
 */
class NoLinksTest {

    /** The repository, from this module's directory, where Maven runs the tests. */
    private static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
    private static final Set<String> NOT_READ = Set.of(".git", "work", "node_modules");

    @Test
    @DisplayName("nothing in the checkout is a symbolic link, so a copy that does not keep links loses nothing (O-98)")
    void noLinks() throws IOException {
        List<String> links = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        Files.walkFileTree(ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return NOT_READ.contains(String.valueOf(dir.getFileName())) || output(dir) ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                seen.add(ROOT.relativize(file).toString());
                if (attrs.isSymbolicLink()) {
                    links.add(ROOT.relativize(file).toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        assertThat(seen).as("the files read").contains("vendor/mysql-8.4/bin/mysqld", "vendor/mysql-8.4/lib/private/libaio.so.1");
        assertThat(links).as("links in the checkout").isEmpty();
    }

    /** A project's build output; vendor/mysql-8.4/bin is read. */
    private static boolean output(Path dir) throws IOException {
        Path project = dir.getParent();
        switch (String.valueOf(dir.getFileName())) {
            case "target":
                return Files.exists(project.resolve("pom.xml"));
            case "bin", "obj":
                try (Stream<Path> files = Files.list(project)) {
                    return files.anyMatch(f -> f.toString().endsWith(".csproj"));
                }
            default:
                return false;
        }
    }
}
