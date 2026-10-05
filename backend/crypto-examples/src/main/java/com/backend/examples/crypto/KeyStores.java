package com.backend.examples.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * PKCS#12 key stores: a private key and its chain in one password-protected file, which Java and
 * OpenSSL read (nginx takes PEM files instead). The arena reads its certificate so
 * ({@code BACKEND_ARENA_TLS_KEYSTORE}); {@code install-certificate.sh} makes that file with
 * {@code openssl pkcs12}. A trust store is the same format holding certificates only: the roots a
 * client trusts.
 *
 * <p>Java 21 writes PKCS#12 with AES-256 and an HMAC-SHA256 integrity check, which OpenSSL 3 reads
 * (and a Java 8 older than 8u301 does not).
 */
public final class KeyStores {

    private KeyStores() {
    }

    /** One key and its chain, the leaf first. */
    public static byte[] pkcs12(String alias, PrivateKey key, List<X509Certificate> chain, char[] password)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry(alias, key, password, chain.toArray(Certificate[]::new));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.store(out, password);
        return out.toByteArray();
    }

    /** A wrong password throws {@code IOException}, its cause an {@code UnrecoverableKeyException}. */
    public static KeyStore load(byte[] pkcs12, char[] password) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(pkcs12), password);
        return store;
    }

    /** Certificates only, for a client that trusts these roots and nothing else. */
    public static KeyStore trustStore(List<X509Certificate> roots) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        for (int i = 0; i < roots.size(); i++) {
            store.setCertificateEntry("root-" + i, roots.get(i));
        }
        return store;
    }
}
