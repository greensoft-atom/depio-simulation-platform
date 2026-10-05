# crypto-examples

Examples of the cryptography the backend may need, a scenario per class, each one proved by its
tests and checked against the `openssl` command line in both directions. They are for copying
into the module that needs one: no module depends on this one, and the release does not carry
it. When to use which, the rules and the traps: [docs/development/04-crypto.md](../../docs/development/04-crypto.md).

Dependencies: `bcprov-jdk18on` and `bcpkix-jdk18on` 1.86 (the parent pins them; both are in
`java21-offline/`). The classes that need nothing but Java 21 are marked "Java" below.

## Layout

`com.backend.examples.crypto`:

| Class | What it shows | Needs |
|---|---|---|
| `Hashes` | SHA-256 of bytes and of a stream; hex; comparing secrets in constant time | Java |
| `Hmacs` | HMAC-SHA256: a key, a tag, its check | Java |
| `SignedTokens` | A token with a payload and an expiry under an HMAC, checked without a lookup | Java |
| `RandomTokens` | 256-bit URL-safe tokens and random bytes from one shared `SecureRandom` | Java |
| `Aead` | AES-256-GCM and ChaCha20-Poly1305: a fresh nonce each time, the associated data bound | Java |
| `Signatures` | RSA (PKCS#1 v1.5 and PSS), ECDSA P-256 (DER and raw), Ed25519: keys, sign, verify, a stream | Java |
| `RsaEncryption` | RSA-OAEP with SHA-256 for both digests; `seal` and `open` for anything larger | Java |
| `SharedSecrets` | X25519 and ECDH, HKDF-SHA256, encrypting to a public key with Java 21's KEM | Java, and `bcprov` for HKDF |
| `Pem` | `PUBLIC KEY` and `PRIVATE KEY` (PKCS#8) read and written | Java |
| `AnyPem` | Every private-key form OpenSSL writes, encrypted ones too; anything written as PEM | `bcpkix` |
| `Certificates` | Reading X.509 (PEM or DER), DNS names, fingerprint, chain validation, the system's roots | Java |
| `CertificateAuthority` | A root, a server's request (CSR), a certificate issued for it | `bcpkix` |
| `KeyStores` | PKCS#12: a key and its chain, a trust store | Java |
| `Tls` | A server's and a client's `SSLContext`; a connection that checks the server's name | Java |
| `RuntimeProbe` | Runs the Java-only examples on whatever runtime starts it: the release's (docs/development/04 §5) | Java |

## Tests

35, run with the module (`mvn -o -q -pl crypto-examples test` from `backend/`):

| Class | What it proves |
|---|---|
| `HashesAndHmacsTest` | FIPS 180-4's SHA-256 of "abc"; a 3 MB file digests as its bytes; RFC 4231's HMAC; signed tokens refuse a change, another key, expiry and garbage; random tokens' form and uniqueness |
| `EncryptionTest` | Both AEADs: the round trip, a fresh nonce, every changed byte refused, another key and other associated data refused; RSA-OAEP and `seal` (1 MB); OpenSSL's OAEP ciphertext decrypts, and Java's default for that name does not; key agreement on both curves; RFC 5869's HKDF; KEM refuses another context or recipient |
| `SignaturesTest` | Each of five algorithms: verifies, and not for changed data, another key, a damaged or malformed signature; ECDSA's two encodings; OpenSSL's PSS, ECDSA and Ed25519 signatures verify |
| `PemTest` | Keys of three kinds round-trip; OpenSSL's PKCS#8, PKCS#1, encrypted PKCS#8 (and a wrong password) and SEC1 are read and are the right keys |
| `CertificatesTest` | OpenSSL's certificate: names, key, fingerprint; the CA issues what was asked and the chain validates; refused under another root, after expiry, through a leaf posing as a CA; forged and nameless requests refused |
| `KeyStoresAndTlsTest` | PKCS#12 round trip and a wrong password; a TLS 1.3 exchange on loopback, refused under another root or for another name |
| `OpensslReadsOursTest` | `openssl` verifies the CA's certificate, the request, PSS, ECDSA and Ed25519 signatures, reads the key store and the encrypted key, decrypts OAEP. Skipped without `openssl` |
| `RuntimeProbeTest` | Every check of the probe holds on the JDK |

`src/test/resources/openssl/` holds keys, signatures, a ciphertext and a certificate made by
its `make.sh` with `openssl` 3.0.13. **Test keys, made for this and public: never use them.**
