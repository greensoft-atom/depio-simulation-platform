package com.jredis.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What XAddArgs puts before the ID: j-redis trims exactly either way, a Redis would not. */
class XAddArgsTest {

    @Test
    void trimsAndOptionsAreSentAsGiven() {
        assertThat(XAddArgs.none().args()).isEmpty();
        assertThat(XAddArgs.maxLen(5).args()).containsExactly("MAXLEN", 5L);
        assertThat(XAddArgs.minId("7-0").approximately().args()).containsExactly("MINID", "~", "7-0");
        assertThat(XAddArgs.maxLen(5).approximately().noMkStream().args()).containsExactly("MAXLEN", "~", 5L, "NOMKSTREAM");
    }
}
