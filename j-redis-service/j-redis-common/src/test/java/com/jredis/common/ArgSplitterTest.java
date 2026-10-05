package com.jredis.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArgSplitterTest {

    private static List<String> split(String s) {
        List<byte[]> r = ArgSplitter.split(s.getBytes(StandardCharsets.UTF_8));
        if (r == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (byte[] b : r) {
            out.add(new String(b, StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void splitsWordsAndQuotes() {
        assertThat(split("SET key value")).containsExactly("SET", "key", "value");
        assertThat(split("  SET   key\tvalue  ")).containsExactly("SET", "key", "value");
        assertThat(split("SET k \"hello world\"")).containsExactly("SET", "k", "hello world");
        assertThat(split("SET k 'it\\'s'")).containsExactly("SET", "k", "it's");
        assertThat(split("SET k \"a\\nb\\x41\"")).containsExactly("SET", "k", "a\nbA");
        assertThat(split("SET k \"\"")).containsExactly("SET", "k", "");
        assertThat(split("")).isEmpty();
        assertThat(split("   ")).isEmpty();
    }

    @Test
    void rejectsUnbalancedQuotes() {
        assertThat(split("SET k \"abc")).isNull();
        assertThat(split("SET k 'abc")).isNull();
        assertThat(split("SET k \"abc\"def")).isNull();
    }
}
