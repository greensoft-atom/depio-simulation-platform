package com.backend.examples.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HashesAndHmacsTest {

    @Test
    @DisplayName("SHA-256 gives the published value for \"abc\" (FIPS 180-4), from bytes and from a file alike")
    void sha256(@TempDir Path dir) throws Exception {
        assertThat(Hashes.hex(Hashes.sha256("abc".getBytes(UTF_8))))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

        byte[] large = new byte[3 * 1024 * 1024 + 17];
        new java.util.Random(1).nextBytes(large);
        Path file = Files.write(dir.resolve("large.bin"), large);
        try (InputStream in = Files.newInputStream(file)) {
            assertThat(Hashes.sha256(in)).isEqualTo(Hashes.sha256(large));
        }
    }

    @Test
    @DisplayName("secrets compare equal only when every byte is")
    void sameSecret() {
        assertThat(Hashes.sameSecret(new byte[] {1, 2, 3}, new byte[] {1, 2, 3})).isTrue();
        assertThat(Hashes.sameSecret(new byte[] {1, 2, 3}, new byte[] {1, 2, 4})).isFalse();
        assertThat(Hashes.sameSecret(new byte[] {1, 2, 3}, new byte[] {1, 2})).isFalse();
    }

    @Test
    @DisplayName("HMAC-SHA256 gives RFC 4231's value, and a changed message or tag does not verify")
    void hmac() throws Exception {
        SecretKey key = Hmacs.key("Jefe".getBytes(UTF_8));
        byte[] message = "what do ya want for nothing?".getBytes(UTF_8);
        byte[] tag = Hmacs.tag(key, message);

        assertThat(Hashes.hex(tag)).isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
        assertThat(Hmacs.verify(key, message, tag)).isTrue();
        assertThat(Hmacs.verify(key, "what do ya want for something?".getBytes(UTF_8), tag)).isFalse();
        tag[31] ^= 1;
        assertThat(Hmacs.verify(key, message, tag)).isFalse();
        assertThat(Hmacs.verify(Hmacs.newKey(), message, Hmacs.tag(key, message))).isFalse();
    }

    @Test
    @DisplayName("a signed token opens with its key before its expiry, and not after, nor changed, nor under another key")
    void signedTokens() throws Exception {
        SecretKey key = Hmacs.newKey();
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        String token = SignedTokens.issue(key, "player:42 may download report 7", now.plus(Duration.ofMinutes(5)));

        assertThat(SignedTokens.open(key, token, now)).contains("player:42 may download report 7");
        assertThat(SignedTokens.open(key, token, now.plus(Duration.ofMinutes(5)))).isEmpty();
        assertThat(SignedTokens.open(Hmacs.newKey(), token, now)).isEmpty();

        // Another payload, or a later expiry, with the old tag: the tag covers both.
        String[] parts = token.split("\\.");
        String otherPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("player:43 may download report 7".getBytes(UTF_8));
        assertThat(SignedTokens.open(key, otherPayload + "." + parts[1] + "." + parts[2], now)).isEmpty();
        assertThat(SignedTokens.open(key, parts[0] + "." + (Long.parseLong(parts[1]) + 3600) + "." + parts[2], now)).isEmpty();

        assertThat(SignedTokens.open(key, "", now)).isEmpty();
        assertThat(SignedTokens.open(key, "no-dots-at-all", now)).isEmpty();
        assertThat(SignedTokens.open(key, parts[0] + "." + parts[1] + ".%%%", now)).isEmpty();
    }

    @Test
    @DisplayName("random tokens are 43 URL-safe characters and do not repeat")
    void randomTokens() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String token = RandomTokens.token();
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
            seen.add(token);
        }
        assertThat(seen).hasSize(10_000);
        assertThat(HexFormat.of().formatHex(RandomTokens.bytes(16))).hasSize(32);
    }
}
