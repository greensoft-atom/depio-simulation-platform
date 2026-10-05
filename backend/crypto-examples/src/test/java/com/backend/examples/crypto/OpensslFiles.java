package com.backend.examples.crypto;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

/** The files {@code src/test/resources/openssl/make.sh} made with the openssl command line. */
final class OpensslFiles {

    static final String SIGNED_MESSAGE = "a message signed by openssl";
    static final String OAEP_PLAINTEXT = "a secret for rsa-oaep";
    static final char[] KEY_PASSWORD = "example-password".toCharArray();

    private OpensslFiles() {
    }

    static String text(String name) throws IOException {
        try (InputStream in = OpensslFiles.class.getResourceAsStream("/openssl/" + name)) {
            if (in == null) {
                throw new IOException("no test file " + name);
            }
            return new String(in.readAllBytes(), US_ASCII);
        }
    }

    static byte[] base64(String name) throws IOException {
        return Base64.getDecoder().decode(text(name).trim());
    }
}
