# Cryptography: what the backend has, what to add, and examples

Asked by the owner on 2026-10-05: can the backend sign, verify, encrypt, decrypt and hash, with
RSA, ECDSA and X.509, and what would it need for the rest? The answer:
- **Java 21 does almost all of it with nothing added**, on the release's own runtime too.
- **BouncyCastle's `bcprov`**, already in the platform, covers what Java lacks among the
  algorithms.
- **Its `bcpkix`** covers making certificates and requests, and PEM in every form.

[`backend/crypto-examples`](../../backend/crypto-examples/README.md) shows each scenario in a
small class meant to be copied, and its tests prove each one:
- **Behaviour:** the round trip, the refusal of anything changed, of another key or another
  context.
- **Published test vectors:** FIPS 180-4, RFC 4231, RFC 5869.
- **Interoperability with the `openssl` command line, both ways:** OpenSSL's keys, signatures and
  ciphertexts are read here, and OpenSSL checks what is written here.

The module is built and tested with the rest of the backend, and is never in the release.

## 1. What is there

| Need | Java 21 (and the release's runtime) | BouncyCastle `bcprov` 1.86 (in the release, with `platform`) | `bcpkix` 1.86 (only in `crypto-examples` so far) |
|---|---|---|---|
| Digests: SHA-256, SHA-512, SHA3-256 | yes | | |
| HMAC-SHA256 | yes | | |
| Encryption with integrity: AES-256-GCM, ChaCha20-Poly1305 | yes | | |
| Signatures: RSA (PKCS#1 v1.5, PSS), ECDSA P-256 and P-384, Ed25519 | yes | | |
| RSA encryption (OAEP) | yes | | |
| Key agreement: ECDH, X25519; KEM (DHKEM) | yes | | |
| HKDF | Java 25 on | yes | |
| Passwords: Argon2id | | yes (the backend's `PasswordHasher`) | |
| PBKDF2 | yes | yes | |
| scrypt, bcrypt, BLAKE2 and BLAKE3, secp256k1 (left Java in JDK 16) | | yes | |
| Post-quantum: ML-KEM, ML-DSA | Java 24 on | yes | |
| X.509: read, validate a chain, TLS | yes | | |
| X.509: make certificates, requests (CSR), CRLs | | | yes |
| PEM: `PUBLIC KEY`, `PRIVATE KEY` (PKCS#8), certificates | yes | | |
| PEM: `RSA PRIVATE KEY`, `EC PRIVATE KEY`, `ENCRYPTED PRIVATE KEY` | | | yes |
| CMS (PKCS#7), OCSP, timestamps | | | yes |
| PKCS#12 key stores | yes | | |
| Keys in a hardware module (PKCS#11) | the `jdk.crypto.cryptoki` module, **not in the release's runtime** (§4) | | |

"Yes" in the Java column was proved on the release's runtime as well as on the JDK
(`RuntimeProbe`, §5).

## 2. The examples

All in `com.backend.examples.crypto`. The "uses" column names where in this backend the
scenario fits.

| Scenario | Class | Uses |
|---|---|---|
| A fingerprint of bytes or of a whole file; comparing secrets in constant time | `Hashes` | a release's checksum; a guest's key kept as its SHA-256 (`AuthService`) |
| A message from someone holding the same key, unchanged | `Hmacs` | a payment provider's callback, when there is one (Q-52) |
| A token the server checks without a lookup, with an expiry | `SignedTokens` | a download link, an invitation |
| Values nobody can guess | `RandomTokens` | session tokens and tickets (`SessionStore`, `Ticket` do this) |
| Encrypting data at rest or between services, bound to its row | `Aead` | a sensitive column, a backup's key |
| Signing and verifying: RSA, RSA-PSS, ECDSA (DER and raw), Ed25519 | `Signatures` | a signed release or manifest, a partner's signed message |
| Encrypting to an RSA public key; anything larger by a wrapped AES key | `RsaEncryption` | a partner that only takes RSA |
| Two parties reaching one key; encrypting to an X25519 public key (KEM) | `SharedSecrets` | data for a key kept offline |
| Keys as PEM, with Java alone | `Pem` | a public key handed to a partner |
| Every PEM form OpenSSL writes; an encrypted private key | `AnyPem` | a key made with `openssl`; a CA's key at rest |
| Reading certificates, their names and fingerprint; validating a chain | `Certificates` | the gateway's `EdgeCertificate` reads nginx's certificate so, for its expiry |
| A private CA: a root, a server's request, the certificate issued for it | `CertificateAuthority` | MySQL's private CA (operations/01 §9, made with `openssl` there); mutual TLS |
| A key and its chain in one file; a trust store | `KeyStores` | the arena's keystore (`ArenaTls`) |
| A TLS server and a client that trusts only our root and checks the name | `Tls` | a service of ours calling another |
| What the runtime it runs on can do | `RuntimeProbe` | before a process relies on an algorithm (§5) |

What using them looks like (each line is what the tests do):

```java
// Encrypt a column, bound to its row: the ciphertext moved to another row will not decrypt.
SecretKey key = Aead.newAesKey();
byte[] sealed = Aead.encrypt(Aead.AES_GCM, key, email.getBytes(UTF_8), "accounts.id=7".getBytes(UTF_8));
byte[] opened = Aead.decrypt(Aead.AES_GCM, key, sealed, "accounts.id=7".getBytes(UTF_8));   // AEADBadTagException if changed

// Sign and verify.
KeyPair keys = Signatures.ed25519();
byte[] signature = Signatures.sign(Signatures.ED25519, keys.getPrivate(), manifest);
boolean genuine = Signatures.verify(Signatures.ED25519, keys.getPublic(), manifest, signature);

// A partner's RSA public key from PEM, and a secret encrypted to it as OpenSSL would decrypt it.
PublicKey partner = Pem.readPublicKey(pemText, "RSA");
byte[] forPartner = RsaEncryption.seal(partner, document);

// A token for a link that expires in five minutes.
String token = SignedTokens.issue(linkKey, "player:42 may download report 7", Instant.now().plusSeconds(300));
Optional<String> grant = SignedTokens.open(linkKey, token, Instant.now());   // empty if changed, foreign or expired

// A private CA issuing a server's certificate, then a client that trusts that CA alone.
CertificateAuthority ca = CertificateAuthority.create("backend internal root", Signatures.ecP256(), Duration.ofDays(3650));
KeyPair server = Signatures.ecP256();
X509Certificate leaf = ca.issue(CertificateAuthority.request(server, "db.internal", List.of("db.internal")), Duration.ofDays(90));
SSLContext client = Tls.client(KeyStores.trustStore(List.of(ca.certificate())));
```

## 3. Using one in a process

- **Copy the class, or the part needed, into the module that needs it.** The examples
  module is not a library: no module depends on it, and the release does not carry it.
- **Dependencies:**
  - **Java alone:** nothing to add.
  - **BouncyCastle:** `bcprov-jdk18on` or `bcpkix-jdk18on` in the module's `pom.xml`, without a
    version (the parent pins 1.86). Both are in `java21-offline/`, so the offline build reaches them.
    `make-release.sh` copies a process's runtime dependencies itself.
  - **On this development machine**, whose `~/.m2` the commands in `CLAUDE.md` read offline: a
    library new to the backend is copied into `~/.m2/repository` from `java21-offline/repository`
    first (02, the traps).
- **Keys and secrets never in the code or the repository.** They are files the unit gives the
  process (`LoadCredential`, operations/01 §2), read with `common`'s `Secrets` (`NAME_FILE`).
  The keys under `crypto-examples/src/test/resources/openssl/` were made for the tests, and are
  public.
- **Test it as the examples do:**
  - the round trip;
  - each change refused: a byte, the key, the context;
  - a published vector, or a file from the other side's tool;
  - then a mutation check: break the rule on purpose and see a test fail. For crypto, the
    mutation that survives is usually a parameter both sides of the test share, which only another
    tool's file catches (§4, PSS).
- **Run `RuntimeProbe` on the release's runtime** with the new algorithm added to it (§5).

## 4. Rules, and the traps the tests show

- **Encryption is AEAD:** AES-256-GCM or ChaCha20-Poly1305.
  - Never ECB, and never CBC or CTR without a MAC: they do not notice a change.
  - A fresh random 12-byte nonce every time, never reused with a key (GCM then gives the key away).
  - At most about 2^32 encryptions per key with random nonces.
  - What the ciphertext belongs to (a row's ID) goes in the associated data.
- **RSA-OAEP names both digests.** Java's `"RSA/ECB/OAEPWithSHA-256AndMGF1Padding"` keeps MGF1 on
  SHA-1; OpenSSL, WebCrypto and most others use SHA-256 for both. The tests decrypt OpenSSL's
  ciphertext with both settings: only the named one works. Never `PKCS1Padding` for encryption
  (padding-oracle attacks).
- **RSA-PSS's parameters are not in the key.** Both sides must name the same: SHA-256, MGF1 on
  SHA-256, a 32-byte salt. A wrong setting survived the mutation check while both sides of the
  test shared it, and was caught only once an OpenSSL signature was added.
- **ECDSA has two encodings.**
  - DER (`SHA256withECDSA`, about 70 to 72 bytes) is what OpenSSL, X.509 and TLS use.
  - Raw r‖s (`SHA256withECDSAinP1363Format`, 64 bytes) is what JWT (ES256) and WebCrypto use.
  - Neither verifies as the other.
- **Comparing secrets** (tags, tokens) with `MessageDigest.isEqual`, not `Arrays.equals`. No
  functional test can see the difference, which is timing: the mutation survives by nature, and
  review is what catches it.
- **A shared secret is not a key.** It goes through HKDF, with an `info` naming the purpose, one key
  per purpose.
- **Randomness:**
  - One `SecureRandom`, shared.
  - Not `getInstanceStrong()`: it may block a server on `/dev/random`.
  - Never `java.util.Random` for anything secret.
- **Passwords:** Argon2id (`PasswordHasher`), never a digest. A high-entropy value (a device key, a
  token) may be stored as its SHA-256: nobody can guess it to try.
- **Ed25519 holds the whole message in memory**, since it reads it twice. To sign a large file,
  sign its SHA-256.
- **BouncyCastle's provider is passed, not registered.** Its PKCS#8 encryption asks for
  `AES/CBC/PKCS7Padding`, which Java's providers lack: found by the tests, and fixed by giving that
  call `new BouncyCastleProvider()`. `Security.addProvider` would change what every lookup in the
  process finds.
- **Certificates:**
  - A host is matched against the subject alternative names, not the CN.
  - The name is checked by TLS's endpoint identification (`Tls.connect`), not by chain validation.
  - Revocation is off in a plain PKIX validation and needs the network when on.
  - Serial numbers are random.
  - A private CA's root key is the whole CA: kept offline and encrypted.
- **Sizes:** RSA at 3072 bits for new keys (2048 where a partner needs it); P-256 or Ed25519.
- **Never:** MD5, or SHA-1 in a signature; DES, 3DES, RC4; ECB.
- **PKCS#11** (keys in a hardware module) needs `jdk.crypto.cryptoki` added to `make-release.sh`'s
  `modules`. The probe shows it missing from the release's runtime today. The tests pass on the
  full JDK either way, which is why the probe exists.

## 5. Running them

From `backend/`, with the JDK and Maven of [03 §2](03-build-and-run.md#2-build):

```bash
mvn -o -q -pl crypto-examples test          # 35 tests; the ones calling openssl are skipped without it
```

`RuntimeProbe` on the release's runtime, from the repository's root, after a build:

```bash
backend/target/release/backend-*/runtime/bin/java -cp backend/crypto-examples/target/classes \
    com.backend.examples.crypto.RuntimeProbe
```

On 2026-10-05 it printed `ok` for each of the 12 checks, `lacks PKCS#11`, and `0 failed`. To
make the OpenSSL files again: `crypto-examples/src/test/resources/openssl/make.sh`. The keys and
signatures then change, and the tests read whatever is there.

**Verified 2026-10-05:**
- 35 tests, none skipped (`openssl` 3.0.13 installed here).
- A mutation check over 14 broken rules: every one caught, except the constant-time comparison
  (§4). The PSS mutant was caught only after the OpenSSL signature was added for it.
- The probe on the release's runtime.

**Not verified:** a hardware module, CMS, OCSP, and anything against a partner's real system.

## 6. Where the backend uses cryptography today

| What | How | Where |
|---|---|---|
| Passwords | Argon2id, 19 MiB, t=2 | `platform`'s `PasswordHasher` (`bcprov`) |
| Session tokens, tickets, IDs | `SecureRandom` | `handoff`'s `SessionStore`, `Ticket`, `Ulid` |
| A guest's key (random, the device's to keep) | kept as its SHA-256 | `platform`'s `AuthService` |
| The players' TLS: logins, purchases, the lobby | nginx with OpenSSL 3.5 compiled in | [09 §5](../detailed-design/09-release-and-packaging.md) |
| The players' TLS: matches | Java's TLS from a PKCS#12 store | `arena`'s `ArenaTls` |
| MySQL's TLS | a private CA made with `openssl` | [operations/01 §9](../operations/01-deploy.md) |
| nginx's certificate's expiry, as a metric | read as X.509 | `gateway`'s `EdgeCertificate` |
| The vendored downloads | SHA-256 checked before use | `vendor/update.sh` |
