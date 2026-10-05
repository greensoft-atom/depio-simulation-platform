package com.jredis.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class GlobTest {

    private static boolean m(String p, String t) {
        return Glob.match(p.getBytes(StandardCharsets.UTF_8), t.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void basics() {
        assertThat(m("*", "")).isTrue();
        assertThat(m("*", "anything")).isTrue();
        assertThat(m("h?llo", "hello")).isTrue();
        assertThat(m("h?llo", "hllo")).isFalse();
        assertThat(m("h*llo", "heeeello")).isTrue();
        assertThat(m("h[ae]llo", "hallo")).isTrue();
        assertThat(m("h[ae]llo", "hillo")).isFalse();
        assertThat(m("h[^e]llo", "hallo")).isTrue();
        assertThat(m("h[^e]llo", "hello")).isFalse();
        assertThat(m("h[a-b]llo", "hbllo")).isTrue();
        assertThat(m("h[b-a]llo", "hallo")).isTrue();          // reversed range
        assertThat(m("h\\*llo", "h*llo")).isTrue();
        assertThat(m("h\\*llo", "hello")).isFalse();
        assertThat(m("user:*:name", "user:42:name")).isTrue();
        assertThat(m("user:*:name", "user:42:level")).isFalse();
        assertThat(m("a*b*c", "abc")).isTrue();
        assertThat(m("a*b*c", "acb")).isFalse();
        assertThat(m("", "")).isTrue();
        assertThat(m("", "x")).isFalse();
        assertThat(m("[\\]]", "]")).isTrue();
    }

    @Test
    void pathologicalPatternIsFast() {
        byte[] text = new byte[10_000];
        Arrays.fill(text, (byte) 'a');
        byte[] pattern = "*a*a*a*a*a*a*a*a*a*a*b".getBytes(StandardCharsets.US_ASCII);
        long t0 = System.nanoTime();
        assertThat(Glob.match(pattern, text)).isFalse();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).isLessThan(500);
    }

    @Test
    void agreesWithRegexOnRandomInputs() {
        Random r = new Random(7);
        String alphabet = "ab";
        for (int i = 0; i < 20_000; i++) {
            String p = randomPattern(r);
            StringBuilder t = new StringBuilder();
            int tl = r.nextInt(8);
            for (int j = 0; j < tl; j++) {
                t.append(alphabet.charAt(r.nextInt(2)));
            }
            String regex = p.replace("?", ".").replace("*", ".*");
            assertThat(m(p, t.toString())).as(p + " vs " + t).isEqualTo(Pattern.matches(regex, t));
        }
    }

    private static String randomPattern(Random r) {
        String tokens = "ab?*";
        StringBuilder sb = new StringBuilder();
        int n = r.nextInt(6);
        for (int i = 0; i < n; i++) {
            sb.append(tokens.charAt(r.nextInt(tokens.length())));
        }
        return sb.toString();
    }
}
