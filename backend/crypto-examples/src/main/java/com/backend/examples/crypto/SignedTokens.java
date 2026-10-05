package com.backend.examples.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.SecretKey;

/**
 * A token the server can check without looking anything up: what it says, until when, and an
 * HMAC over both. {@code base64url(payload) "." expirySecond "." base64url(tag)}.
 *
 * <p>The payload is readable by whoever holds the token: signed, not encrypted. To hide it too,
 * encrypt it ({@link Aead}) instead. Such a token cannot be revoked before it expires, which is why
 * the backend's sessions are random tokens in the store instead ({@link RandomTokens}); this suits
 * short-lived things, a download link or an invitation.
 *
 * <p>To change the key without breaking the tokens out there, put a key name in front and keep the
 * old key for verifying until the last token it signed has expired.
 */
public final class SignedTokens {

    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODE = Base64.getUrlDecoder();

    private SignedTokens() {
    }

    public static String issue(SecretKey key, String payload, Instant expiresAt) throws GeneralSecurityException {
        String body = ENCODE.encodeToString(payload.getBytes(UTF_8)) + "." + expiresAt.getEpochSecond();
        return body + "." + ENCODE.encodeToString(Hmacs.tag(key, body.getBytes(UTF_8)));
    }

    /** The payload, or empty when the token is malformed, was changed, was made with another key, or has expired. */
    public static Optional<String> open(SecretKey key, String token, Instant now) throws GeneralSecurityException {
        int last = token.lastIndexOf('.');
        if (last < 0) {
            return Optional.empty();
        }
        String body = token.substring(0, last);
        byte[] tag;
        try {
            tag = DECODE.decode(token.substring(last + 1));
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
        if (!Hmacs.verify(key, body.getBytes(UTF_8), tag)) {
            return Optional.empty();
        }
        // The tag held, so the body is one this server made: it parses.
        int dot = body.indexOf('.');
        if (!now.isBefore(Instant.ofEpochSecond(Long.parseLong(body.substring(dot + 1))))) {
            return Optional.empty();
        }
        return Optional.of(new String(DECODE.decode(body.substring(0, dot)), UTF_8));
    }
}
