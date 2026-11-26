package com.backend.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CredentialRulesTest {

    @Test
    @DisplayName("a username is 3 to 32 ASCII letters, digits, _ and -; its key is its lower case")
    void usernames() {
        assertThat(CredentialRules.validUsername("ada")).isTrue();
        assertThat(CredentialRules.validUsername("A_d-a_9")).isTrue();
        assertThat(CredentialRules.validUsername("x".repeat(32))).isTrue();
        assertThat(CredentialRules.validUsername("ab")).as("too short").isFalse();
        assertThat(CredentialRules.validUsername("x".repeat(33))).as("too long").isFalse();
        assertThat(CredentialRules.validUsername("has space")).isFalse();
        assertThat(CredentialRules.validUsername("áda")).as("an accent: not ASCII, so never a second ada").isFalse();
        assertThat(CredentialRules.validUsername(null)).isFalse();
        assertThat(CredentialRules.usernameKey("AdA")).isEqualTo("ada");
        assertThat(CredentialRules.usernameKey("áda")).as("no key for a name no account can have").isNull();
    }

    @Test
    @DisplayName("a password is 8 to 128 characters, with no lone half of a surrogate pair")
    void passwords() {
        assertThat(CredentialRules.validPassword("hunter2-hunter2".toCharArray())).isTrue();
        assertThat(CredentialRules.validPassword("x".repeat(8).toCharArray())).isTrue();
        assertThat(CredentialRules.validPassword("x".repeat(128).toCharArray())).isTrue();
        assertThat(CredentialRules.validPassword("x".repeat(7).toCharArray())).isFalse();
        assertThat(CredentialRules.validPassword("x".repeat(129).toCharArray())).isFalse();
        assertThat(CredentialRules.validPassword(("pass😀word").toCharArray())).as("a whole pair").isTrue();
        assertThat(CredentialRules.validPassword(("password\ud800").toCharArray())).as("a lone high half").isFalse();
        assertThat(CredentialRules.validPassword(("\udc00password").toCharArray())).as("a lone low half").isFalse();
        assertThat(CredentialRules.validPassword(null)).isFalse();
    }
}
