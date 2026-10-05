package com.backend.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * A secret from the environment, never the command line (S-3): {@code NAME_FILE} names a file
 * holding it — what systemd's {@code LoadCredential=} and container secrets provide, readable
 * by the service alone — and wins over {@code NAME}, the value itself.
 *
 * A file that is named and cannot be read, or is empty, refuses the configuration rather than
 * falling back: falling back would start a production process on whatever the fallback is,
 * to fail later and less clearly.
 */
public final class Secrets {

    /** A secret and where it came from. Printing it never prints the value. */
    public record Secret(String value, String source) {
        @Override
        public String toString() {
            return "secret from " + source;
        }
    }

    /** @return the secret, or null when neither variable is set. */
    public static Secret read(Map<String, String> env, String name) {
        String file = env.get(name + "_FILE");
        if (file != null) {
            return new Secret(readFile(name, Path.of(file)), "file " + file);
        }
        String value = env.get(name);
        return value == null ? null : new Secret(value, name);
    }

    private static String readFile(String name, Path file) {
        String s;
        try {
            s = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RefusedConfiguration(name + "_FILE names " + file + ", which cannot be read: " + e, e);
        }
        // One line ending is how an editor, echo or a secret store ends a file; it is not
        // part of the secret. Only one: anything more is the secret's own business.
        if (s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("\r")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            throw new RefusedConfiguration(name + "_FILE names " + file + ", which is empty");
        }
        return s;
    }

    private Secrets() {
    }
}
