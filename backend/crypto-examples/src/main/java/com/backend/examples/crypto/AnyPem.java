package com.backend.examples.crypto;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Provider;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;

/**
 * PEM in every form OpenSSL writes, with BouncyCastle's {@code bcpkix}: private keys as PKCS#8
 * ({@code PRIVATE KEY}), encrypted PKCS#8 ({@code ENCRYPTED PRIVATE KEY}), PKCS#1
 * ({@code RSA PRIVATE KEY}) or SEC1 ({@code EC PRIVATE KEY}); and writing any of BouncyCastle's or
 * Java's objects (a key, a certificate, a request) as PEM.
 *
 * <p>An old OpenSSL-encrypted {@code RSA PRIVATE KEY} (with {@code Proc-Type: 4,ENCRYPTED}) is not
 * read here: convert it once with {@code openssl pkcs8 -topk8}.
 */
public final class AnyPem {

    /**
     * PBKDF2's rounds when encrypting a key: OWASP's figure for PBKDF2-HMAC-SHA256. OpenSSL 3 still
     * writes 2 048 unless told otherwise ({@code -iter}), which a guessed password survives poorly.
     */
    private static final int ITERATIONS = 600_000;

    /**
     * BouncyCastle's own provider, for the encrypted keys alone: its PKCS#8 code asks for
     * "AES/CBC/PKCS7Padding", a name Java's providers do not have. Given to those two calls, not
     * registered with {@code Security.addProvider}, which would change what every lookup in the
     * process finds.
     */
    private static final Provider BC = new BouncyCastleProvider();

    private AnyPem() {
    }

    /** {@code password}: only for an encrypted key; a wrong one throws. */
    public static PrivateKey privateKey(String pem, char[] password) throws IOException, GeneralSecurityException {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object read = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (read instanceof PKCS8EncryptedPrivateKeyInfo encrypted) {
                try {
                    return converter.getPrivateKey(encrypted.decryptPrivateKeyInfo(
                            new JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(BC).build(password)));
                } catch (OperatorCreationException | PKCSException wrongPassword) {
                    throw new GeneralSecurityException("cannot decrypt the key: " + wrongPassword.getMessage(), wrongPassword);
                }
            }
            if (read instanceof PEMKeyPair pair) {
                return converter.getKeyPair(pair).getPrivate();
            }
            if (read instanceof PrivateKeyInfo info) {
                return converter.getPrivateKey(info);
            }
            throw new IllegalArgumentException("not a private key: " + (read == null ? "nothing" : read.getClass().getSimpleName()));
        }
    }

    /** As {@code openssl pkcs8 -topk8 -v2 aes-256-cbc} writes it: AES-256-CBC, its key from the password by PBKDF2 with HMAC-SHA256. */
    public static String encrypted(PrivateKey key, char[] password) throws IOException, GeneralSecurityException {
        try {
            JceOpenSSLPKCS8EncryptorBuilder encryptor = new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC)
                    .setPRF(PKCS8Generator.PRF_HMACSHA256)
                    .setIterationCount(ITERATIONS)
                    .setProvider(BC)
                    .setPassword(password);
            return write(new JcaPKCS8Generator(key, encryptor.build()));
        } catch (OperatorCreationException e) {
            throw new GeneralSecurityException(e.getMessage(), e);
        }
    }

    /** A Java key or certificate, or one of BouncyCastle's objects (a request), as PEM. */
    public static String write(Object object) throws IOException {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(object);
        }
        return out.toString();
    }
}
