# Cryptography: what the backend has, what to add, and examples

This page is for a developer who needs to sign, verify, encrypt, decrypt or hash something in the backend. It says
what Java 21 and the BouncyCastle library already give, which rules to follow, and where the tested examples are.
It covers what is available (§1), the examples (§2), how to use one in a process (§3), the rules and the traps the
tests found (§4), how to run the examples (§5), and where the backend uses cryptography today (§6).

**The question it answers.** On 2026-10-05 the owner asked: can the backend sign, verify, encrypt, decrypt and hash,
with RSA, ECDSA and X.509, and what would it need for the rest? The answer:

- **Java 21 does almost all of it with nothing added**, on the release's own runtime too.
- **BouncyCastle's `bcprov`** (the provider library), in the account service since D-117 (the platform's until then),
  covers the algorithms Java lacks.
- **BouncyCastle's `bcpkix`** covers making certificates and certificate requests, and PEM in every form.

**The examples.** [`backend/crypto-examples`](../../backend/crypto-examples/README.md) shows each scenario in a
small class meant to be copied. Its tests prove each one in three ways:

- **Behaviour:** the round trip works, and anything changed is refused: a changed byte, another key, another
  context.
- **Published test vectors:** FIPS 180-4, RFC 4231 and RFC 5869.
- **Interoperability with the `openssl` command line, both ways:** the tests read OpenSSL's keys, signatures and
  ciphertexts, and OpenSSL checks what the tests write.

The module is built and tested with the rest of the backend. It is never in the release.

## 1. What is there

The table says which library gives each need. "Java 25 on" and "Java 24 on" mean the JDK gives it only from that
version; the backend runs on Java 21.

| Need | Java 21 (and the release's runtime) | BouncyCastle `bcprov` 1.86 (with the account service; the platform's until D-117) | `bcpkix` 1.86 (only in `crypto-examples` so far) |
|---|---|---|---|
| Digests: SHA-256, SHA-512, SHA3-256 | yes | | |
| HMAC-SHA256 | yes | | |
| Encryption with integrity: AES-256-GCM, ChaCha20-Poly1305 | yes | | |
| Signatures: RSA (PKCS#1 v1.5, PSS), ECDSA P-256 and P-384, Ed25519 | yes | | |
| RSA encryption (OAEP) | yes | | |
| Key agreement: ECDH, X25519; KEM (DHKEM) | yes | | |
| HKDF | Java 25 on | yes | |
| Passwords: Argon2id | | yes (the account service's `PasswordHasher`) | |
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

Every "yes" in the Java column was proved on the release's runtime as well as on the JDK, by `RuntimeProbe` (§5).

## 2. The examples

All the classes are in the package `com.backend.examples.crypto`. The "Uses" column names where the scenario fits
in this backend.

| Scenario | Class | Uses |
|---|---|---|
| A fingerprint of bytes or of a whole file; comparing secrets in constant time | `Hashes` | a release's checksum; a guest's key, kept as its SHA-256 (`AuthService`) |
| A message from someone who holds the same key, unchanged | `Hmacs` | a payment provider's callback, when there is one (Q-52) |
| A token the server checks without a lookup, with an expiry | `SignedTokens` | a download link, an invitation |
| Values nobody can guess | `RandomTokens` | session tokens and tickets (`SessionStore` and `Ticket` do this) |
| Encrypting data at rest or between services, bound to its row | `Aead` | a sensitive column, a backup's key |
| Signing and verifying: RSA, RSA-PSS, ECDSA (DER and raw), Ed25519 | `Signatures` | a signed release or manifest, a partner's signed message |
| Encrypting to an RSA public key; anything larger by a wrapped AES key | `RsaEncryption` | a partner that only takes RSA |
| Two parties reaching one key; encrypting to an X25519 public key (KEM) | `SharedSecrets` | data for a key kept offline |
| Keys as PEM, with Java alone | `Pem` | a public key handed to a partner |
| Every PEM form OpenSSL writes; an encrypted private key | `AnyPem` | a key made with `openssl`; a CA's key at rest |
| Reading certificates, their names and fingerprint; validating a chain | `Certificates` | the gateway's `EdgeCertificate` reads nginx's certificate this way, for its expiry |
| A private CA: a root, a server's request, the certificate issued for it | `CertificateAuthority` | MySQL's private CA (operations/01 §9, made with `openssl` there); mutual TLS |
| A key and its chain in one file; a trust store | `KeyStores` | the arena's keystore (`ArenaTls`) |
| A TLS server, and a client that trusts only our root and checks the name | `Tls` | a service of ours calling another |
| What the runtime it runs on can do | `RuntimeProbe` | before a process relies on an algorithm (§5) |

What using them looks like. Each line is what the tests do:

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

1. **Copy the class, or the part you need, into the module that needs it.** The examples module is not a library:
   no module depends on it, and the release does not carry it.
2. **Add the dependency, if any.**
   - **Java alone:** nothing to add.
   - **BouncyCastle:** add `bcprov-jdk18on` or `bcpkix-jdk18on` to the module's `pom.xml`, without a version (the
     parent `pom.xml` pins 1.86). Both are in `java21-offline/`, so the offline build finds them. `make-release.sh`
     copies each process's runtime dependencies into the release by itself.
   - **No copying by hand.** Since D-80 every build reads the bundle through `dev-env.sh`'s settings, with
     `work/m2` as Maven's local repository, so a library new to the backend is taken from the bundle the first
     time a build needs it. (Before D-80, the development VM's offline commands read `~/.m2`, and a new library
     had to be copied there from `java21-offline/repository` first:
     [02 §4](02-working-here.md#4-traps-each-met-at-least-once).)
3. **Keep keys and secrets out of the code and out of the repository.** Each process reads its own from its
   settings file on the machine, `/etc/backend/<process>.env`, readable by root alone (D-102,
   [operations/01 §2](../operations/01-deploy.md#secrets)). In the code, read one with `common`'s `Secrets`: the
   variable `NAME` holds the secret, or `NAME_FILE` names a file that holds it. The keys under
   `crypto-examples/src/test/resources/openssl/` were made for the tests, and are public.
4. **Test it as the examples do:**
   - the round trip;
   - each change refused: a byte, the key, the context;
   - a published vector, or a file made by the other side's tool;
   - then a mutation check: break the rule on purpose and see a test fail. In cryptography, the mutation that
     survives is usually a parameter that both sides of the test share. Only a file made by another tool catches it
     (§4, PSS).
5. **Run `RuntimeProbe` on the release's runtime**, with the new algorithm added to the probe (§5).

## 4. Rules, and the traps the tests show

- **Encryption is AEAD** (authenticated encryption with associated data): AES-256-GCM or ChaCha20-Poly1305.
  - Never ECB, and never CBC or CTR without a MAC: they do not notice a change.
  - Use a fresh random 12-byte nonce every time, and never reuse one with the same key: GCM then gives the key away.
  - Encrypt at most about 2^32 times with one key when nonces are random.
  - Put what the ciphertext belongs to (a row's ID) in the associated data.
- **RSA-OAEP must name both digests.** Java's `"RSA/ECB/OAEPWithSHA-256AndMGF1Padding"` keeps MGF1 on SHA-1;
  OpenSSL, WebCrypto and most others use SHA-256 for both. The tests decrypt OpenSSL's ciphertext with both
  settings, and only the one that names both digests works. Never use `PKCS1Padding` for encryption: it is open to
  padding-oracle attacks.
- **RSA-PSS's parameters are not in the key.** Both sides must name the same ones: SHA-256, MGF1 on SHA-256, a
  32-byte salt. A wrong setting survived the mutation check while both sides of the test shared it. It was caught
  only once an OpenSSL signature was added to the tests.
- **ECDSA has two encodings, and neither verifies as the other.**
  - DER (`SHA256withECDSA`, about 70 to 72 bytes) is what OpenSSL, X.509 and TLS use.
  - Raw r‖s (`SHA256withECDSAinP1363Format`, 64 bytes) is what JWT (ES256) and WebCrypto use.
- **Compare secrets (tags, tokens) with `MessageDigest.isEqual`, not `Arrays.equals`.** The difference is timing,
  which no functional test can see. So this mutation survives by nature, and review is what catches it.
- **A shared secret is not a key.** Pass it through HKDF, with an `info` that names the purpose, and make one key
  per purpose.
- **Randomness:**
  - Use one `SecureRandom`, shared.
  - Do not use `getInstanceStrong()`: it may block a server on `/dev/random`.
  - Never use `java.util.Random` for anything secret.
- **Passwords:** Argon2id (the account service's `PasswordHasher`), never a plain digest. A high-entropy value (a device key, a token)
  may be stored as its SHA-256: nobody can guess it to try.
- **Ed25519 holds the whole message in memory**, because it reads the message twice. To sign a large file, sign
  its SHA-256.
- **Pass BouncyCastle's provider to the call; do not register it.** Its PKCS#8 encryption asks for
  `AES/CBC/PKCS7Padding`, which Java's providers lack. The tests found this, and the fix gives that one call
  `new BouncyCastleProvider()`. `Security.addProvider` would change what every lookup in the process finds.
- **Certificates:**
  - A host is matched against the subject alternative names, not the CN (common name).
  - TLS's endpoint identification checks the name (`Tls.connect`); chain validation does not.
  - Revocation is off in a plain PKIX validation, and needs the network when it is on.
  - Serial numbers are random.
  - A private CA's root key is the whole CA: keep it offline and encrypted.
- **Sizes:** RSA at 3072 bits for new keys (2048 where a partner needs it); P-256 or Ed25519.
- **Never:** MD5, or SHA-1 in a signature; DES, 3DES or RC4; ECB.
- **PKCS#11** (keys in a hardware module) needs `jdk.crypto.cryptoki` added to the `modules` list in
  `make-release.sh`. The probe shows it missing from the release's runtime today. The tests pass on the full JDK
  either way, which is why the probe exists.

## 5. Running them

1. **The tests.** From `backend/`, in a shell that sourced `dev-env.sh`, with the JDK and Maven of
   [03 §2](03-build-and-run.md#2-build):

   ```bash
   mvn -q -pl crypto-examples test          # 35 tests; the ones calling openssl are skipped without it
   ```

   No `-o`: Maven's offline flag refuses the bundle, which is a `file://` repository (03 §2).
2. **`RuntimeProbe` on the release's runtime**, from the repository's root, after a build:

   ```bash
   backend/target/release/backend-*/runtime/bin/java -cp backend/crypto-examples/target/classes \
       com.backend.examples.crypto.RuntimeProbe
   ```

   On 2026-10-05 it printed `ok` for each of the 12 checks, then `lacks PKCS#11`, then `0 failed`.
3. **To make the OpenSSL files again**, run `crypto-examples/src/test/resources/openssl/make.sh`. The keys and
   signatures then change, and the tests read whatever is there.

**Verified on 2026-10-05:**
- 35 tests, none skipped (`openssl` 3.0.13 is installed on the development machine).
- A mutation check over 14 broken rules: every one caught, except the constant-time comparison (§4). The PSS
  mutant was caught only after the OpenSSL signature was added for it.
- The probe on the release's runtime.

**Not verified:** a hardware module, CMS, OCSP, and anything against a partner's real system.

## 6. Where the backend uses cryptography today

| What | How | Where |
|---|---|---|
| Passwords | Argon2id, 19 MiB of memory, t=2 (two passes) | the account service's `PasswordHasher` (`bcprov`); the platform's until D-117 |
| Access tokens and account events | JWTs signed ES256 (ECDSA P-256, SHA-256) by Nimbus JOSE + JWT over the JDK's provider, the keys published as a JWK set by RFC 7638 thumbprint ([10 §3.4](../detailed-design/10-accounts.md#34-the-tokens)) | the account service's `TokenIssuer` and `SigningKeys`; `platform`'s `AccountTokens` checks them |
| Refresh tokens | 32 bytes of `SecureRandom`, kept as their SHA-256 | the account service's `RefreshTokens` |
| Session tokens, tickets, IDs | `SecureRandom` | `handoff`'s `SessionStore`, `Ticket`, `Ulid` |
| A guest's key (random, the device's to keep) | kept as its SHA-256 | `platform`'s `AuthService` |
| The players' TLS for logins, purchases and the lobby | nginx, with OpenSSL 3.5 compiled in, ends TLS on 443 | [09 §5](../detailed-design/09-release-and-packaging.md) |
| The players' TLS for matches | on the site, nginx ends it on 9001 and passes the match to the arena in clear over the private network (D-82); an arena given a keystore of its own (`BACKEND_ARENA_TLS_KEYSTORE`, as in a drill with `TLS=1` until D-116, which refuses that mode now) speaks Java's TLS from a PKCS#12 store | nginx's `backend-stream.conf`; `arena`'s `ArenaTls` |
| The site's certificate | a CA of the backend's own: an RSA root of 3072 bits, a certificate of 2048, SHA-256 signatures (D-82, D-108), made with `openssl` | `backend/scripts/private-ca.sh`, [operations/06](../operations/06-certificates.md) |
| MySQL's TLS | a private CA made with `openssl` | [operations/01 §9](../operations/01-deploy.md) |
| nginx's certificate's expiry, as a metric | read as X.509 | `gateway`'s `EdgeCertificate` |
| The vendored downloads | SHA-256 checked before use | `vendor/update.sh` |

Without TLS, nothing is encrypted: a players' build may connect in clear through the site's 80 since D-115, and a
development run may be plain (`PLAIN=1`, D-101). Passwords, session tokens and join tickets then cross the network
readable.
