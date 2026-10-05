package com.jredis.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The version of this build, taken from the Maven project version (see version.properties). */
public final class BuildVersion {

    public static final String VERSION = load();

    private BuildVersion() {
    }

    private static String load() {
        try (InputStream in = BuildVersion.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties p = new Properties();
            p.load(in);
            String v = p.getProperty("version", "unknown");
            return v.startsWith("${") ? "unknown" : v;       // not filtered, e.g. run from an IDE without Maven
        } catch (IOException e) {
            return "unknown";
        }
    }
}
