# crypto-examples

`crypto-examples` shows the cryptography the backend may need, one scenario per class. Each class is proved by
its tests, and checked against the `openssl` command line in both directions: OpenSSL reads what Java writes, and
Java reads what OpenSSL writes. The classes are for copying into the module that needs one. No module depends on
this one, and the release does not carry it. This page lists the classes, what each needs, and what the tests
prove. When to use which, the rules and the traps are in
[docs/development/04-crypto.md](../../docs/development/04-crypto.md).

Dependencies: `bcprov-jdk18on` and `bcpkix-jdk18on` (BouncyCastle) 1.86. The parent pom pins the version, and both
are in `java21-offline/`. The classes that need nothing but Java 21 are marked "Java" below.

## Layout

All classes are in the package `com.backend.examples.crypto`:

| Class | What it shows | Needs |
|---|---|---|
| `Hashes` | SHA-256 of bytes and of a stream; hex; comparing secrets in constant time | Java |
| `Hmacs` | HMAC-SHA256: a key, a tag, and checking the tag | Java |
| `SignedTokens` | A token that carries a payload and an expiry under an HMAC, so the server can check it without a lookup | Java |
| `RandomTokens` | 256-bit URL-safe tokens, and random bytes, from one shared `SecureRandom` | Java |
| `Aead` | AES-256-GCM and ChaCha20-Poly1305: a fresh nonce each time, and the associated data bound to the ciphertext | Java |
| `Signatures` | RSA (PKCS#1 v1.5 and PSS), ECDSA P-256 (DER and raw encodings) and Ed25519: making keys, signing, verifying, and signing a stream | Java |
| `RsaEncryption` | RSA-OAEP with SHA-256 for both digests; `seal` and `open` for data larger than one RSA block | Java |
| `SharedSecrets` | Key agreement with X25519 and ECDH; HKDF-SHA256; encrypting to a public key with Java 21's KEM (key encapsulation) | Java, and `bcprov` for HKDF |
| `Pem` | Reading and writing `PUBLIC KEY` and `PRIVATE KEY` (PKCS#8) PEM files | Java |
| `AnyPem` | Reading every private-key form that OpenSSL writes, encrypted ones too; writing anything as PEM | `bcpkix` |
| `Certificates` | Reading X.509 certificates (PEM or DER); their DNS names and fingerprint; validating a chain; the system's root certificates | Java |
| `CertificateAuthority` | A root certificate; a server's certificate signing request (CSR); a certificate issued for that request | `bcpkix` |
| `KeyStores` | PKCS#12 files: a key with its chain, and a trust store | Java |
| `Tls` | A server's and a client's `SSLContext`; a connection that checks the server's name | Java |
| `RuntimeProbe` | Runs the Java-only examples on the runtime that starts it, to check the release's own runtime ([docs/development/04 §5](../../docs/development/04-crypto.md#5-running-them)) | Java |

To run the probe on a release's runtime, from `backend/` after a build:

```bash
target/release/backend-*/runtime/bin/java -cp crypto-examples/target/classes com.backend.examples.crypto.RuntimeProbe
```

## Tests

The module has 35 test cases: 29 test methods, of which three are parameterized. Two run once for each of the two
AEADs, and one once for each of five signature algorithms. They run with the module. From `backend/`, in a shell
that sourced `../dev-env.sh`:

```bash
mvn -q -pl crypto-examples test   # 35 tests; those that call openssl are skipped when it is not installed
```

Do not add `-o`. Maven's offline flag also refuses the bundle, because the bundle is a `file://` repository
([docs/development/03 §2](../../docs/development/03-build-and-run.md#2-build)).

| Class | What it proves |
|---|---|
| `HashesAndHmacsTest` | SHA-256 of "abc" is FIPS 180-4's value; a 3 MB file digests to the same value as its bytes; HMAC matches RFC 4231; a signed token is refused when changed, under another key, after its expiry, and when it is garbage; random tokens have the right form and are unique |
| `EncryptionTest` | Both AEADs: the round trip, a fresh nonce, every changed byte refused, another key and other associated data refused. RSA-OAEP, and `seal` of 1 MB. OpenSSL's OAEP ciphertext decrypts, and Java's default for that algorithm name does not. Key agreement on both curves; HKDF matches RFC 5869; the KEM refuses another context or another recipient |
| `SignaturesTest` | Each of the five algorithms verifies, and refuses changed data, another key, and a damaged or malformed signature; ECDSA's two encodings; OpenSSL's PSS, ECDSA and Ed25519 signatures verify |
| `PemTest` | Keys of three kinds survive a round trip; OpenSSL's PKCS#8, PKCS#1, encrypted PKCS#8 (and a wrong password) and SEC1 files are read and give the right keys |
| `CertificatesTest` | OpenSSL's certificate: its names, key and fingerprint. The CA issues what was asked, and the chain validates. The chain is refused under another root, after expiry, and through a leaf posing as a CA. Forged requests and requests without a name are refused |
| `KeyStoresAndTlsTest` | A PKCS#12 round trip, and a wrong password refused; a TLS 1.3 exchange on loopback, refused under another root or for another name |
| `OpensslReadsOursTest` | `openssl` verifies the CA's certificate, the request, and the PSS, ECDSA and Ed25519 signatures; reads the key store and the encrypted key; decrypts OAEP. Skipped when `openssl` is not installed |
| `RuntimeProbeTest` | Every check of the probe holds on the JDK |

`src/test/resources/openssl/` holds keys, signatures, a ciphertext and a certificate that its `make.sh` made with
`openssl` 3.0.13. Running `make.sh` again makes new keys and signatures, and the tests read whatever is there.
**These are test keys, made for this purpose and public: never use them for anything else.**
