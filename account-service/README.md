# account-service

The account service signs a person up and in, and issues the tokens that prove it. It is a system of its own beside the
backend, as an OAuth 2.0 authorization server is beside the services that trust it: the backend takes one of its
access tokens once, at a login, in exchange for a session of its own. It is written in **Java 21** and runs as one
Java process, on its own MySQL database.

This page is for engineers who build, run or change it. The design, with every reason, is
[docs/detailed-design/10-accounts.md](../docs/detailed-design/10-accounts.md); the decision is
[D-117](../docs/architecture/03-decision-log.md#d-117--accounts-in-an-account-service-of-their-own-which-issues-signed-tokens-the-backend-takes-once-for-a-session).

## At a glance

| | |
|---|---|
| What it owns | a username, an Argon2id hash of its password, the login limits, refresh tokens, its signing keys, the events it owes the backend. No player, display name, ban or guest: those are the backend's |
| Tokens | access tokens: JWTs signed ES256, RFC 9068's profile, 15 minutes. Refresh tokens: opaque, 32 random bytes, kept as their SHA-256, rotated at each use, a family revoked when a spent one comes back (RFC 9700) |
| Keys | an EC P-256 key signs (`ACCOUNT_SIGNING_KEY`); it and the retired ones are published at `/.well-known/jwks.json`, each named by its RFC 7638 thumbprint |
| Events | a password's change and a deletion, as Security Event Tokens (RFC 8417), written in the change's own transaction and pushed to the platform (RFC 8935) until taken |
| HTTP | the JDK's server on virtual threads; JSON, RFC 6749's field names; every route at its own path |
| Storage | MySQL 8.4, its own database (`account`; `account_dev` by hand, `account_test` for the tests), its schema by Flyway |
| Libraries | Nimbus JOSE + JWT, Bouncy Castle (Argon2id), Jackson, HikariCP, MySQL's driver, Flyway, SLF4J and Logback; every one from the offline bundle |
| Code | `com.backend.account`; it builds on nothing of the backend's, and carries its own copies of the few classes it shares with it, each saying so |

## Build and run

From the repository's root, in a shell that sourced `dev-env.sh` (the bundle as the only repository, everything
written in `work/`):

```bash
./dev-mysql.sh start                # the development MySQL: account_dev and account_test, user account
./dev-account.sh build              # compiled, its tests run on account_test, installed in work/m2 (SKIP_TESTS=1: none)
./dev-account.sh start              # on 127.0.0.1:8083, its signing key made once in work/account/
./dev-account.sh test               # its health, its key set, an unknown name's login refused
./dev-account.sh stop
```

The release carries it under `account/`, run by `backend-account.service` with its settings in
`/etc/backend/account.env`; a signing key is made by `scripts/account-key.sh`.

## The routes

| Route | Does |
|---|---|
| `POST /v1/accounts` `{"username", "password"}` | signs up: 201, a token answer |
| `POST /v1/sessions` `{"username", "password"}` | logs in: 200, a token answer |
| `POST /v1/sessions/refresh` `{"refresh_token"}` | a new pair for a refresh token, which is spent |
| `DELETE /v1/sessions` `{"refresh_token"}` | logs out: the token's family spent; 204 always |
| `PUT /v1/accounts/me/password` `{"current_password", "new_password"}`, bearer | a password changed: every family revoked, the backend told, a new pair for this device |
| `DELETE /v1/accounts/me` `{"password"}`, bearer | the account deleted: the name free again, the backend told to erase the player |
| `GET /.well-known/jwks.json` | the public keys, for the platform; nginx does not route it |
| `GET /v1/health` | 200, without the database |

A token answer is `{"access_token", "token_type": "Bearer", "expires_in": 900, "refresh_token", "account_id"}`; an
error `{"error", "error_description"}`. The answers and their reasons are [10 §3.2](../docs/detailed-design/10-accounts.md#32-the-routes).

## The source

| Package | Holds |
|---|---|
| `com.backend.account` | `AccountMain` (the process), `AccountSettings`, `AccountService` (each route's work, in its order), `EventDelivery`, `PasswordHasher` and `CredentialRules` (the platform's, moved), `Metrics`; copies of the backend's `Secrets`, `TrustedProxies`, `RefusedConfiguration`, `Logs` |
| `com.backend.account.token` | `SigningKeys`, `TokenIssuer` (access tokens and events), `BearerCheck` (an access token on its own routes) |
| `com.backend.account.db` | `Accounts`, `RefreshTokens`, `Throttle`, `Outbox`, `Housekeeping`; copies of the backend's `Database`, `DatabaseSettings`, `PrimaryDataSource` and `Tx` |
| `com.backend.account.http` | `AccountHttpServer` |
| `src/main/resources/db/account` | the schema, `V1__schema.sql` |
| `scripts/account-key.sh` | a signing key, made with the machine's `openssl` |

## The tests

`./dev-account.sh build` runs them; those that need MySQL use `account_test` on the development MySQL, which they
empty (`ACCOUNT_JDBC_URL` names another, whose name must end in `_test`). This table is held to the code by the
backend's `ReadmeTestListsTest` (DOC-29).

| Class | Tests | What it covers |
|---|---|---|
| `AccountMainTest` | 2 | arguments refused, never read; started from its settings alone on an empty database, migrated, serving its routes and its metrics, and stopped |
| `AccountServiceTest` | 9 | a sign-up answering as a login; a login whatever the name's case, a wrong password and an unknown name one refusal; an unknown name costing a hash; a rehash at the login that proves an old cost; the address's limit; the hasher's line full, busy and counted against nobody; refresh and logout; a password's change and a deletion, each revoking every family and writing its event |
| `AccountSettingsTest` | 3 | the development defaults; each setting as given, the key from a file; every malformed setting refused, naming its variable |
| `CredentialRulesTest` | 2 | the username's rules and its key; the password's, a lone half of a surrogate pair refused |
| `EventDeliveryTest` | 5 | an event posted as `application/secevent+jwt` and taken by a 202 alone; the next receiver tried, then again later and later; the backoff's doubling to five minutes; a receiver that cannot be reached |
| `PasswordHasherTest` | 9 | the platform's, moved with the hasher: round trips, salts, parameters carried in the hash, malformed and tampered hashes refused, the bounded line, the cost, concurrency, rehash |
| `AccountKeyScriptTest` | 1 | `scripts/account-key.sh`'s key taken by `SigningKeys`, its public half the one printed |
| `http/AccountHttpServerTest` | 5 | each route's answers and RFC 6749's names, `no-store`; the bearer routes and `WWW-Authenticate`; the key set, the health, exact routes, 405 with `Allow`, 413; nginx's last `X-Forwarded-For` counted, 429 with `Retry-After` |
| `token/BearerCheckTest` | 5 | its own token taken, a retired key's too; an event refused as a bearer; another issuer, an audience without it, an expired token, a missing `jti` or `client_id`; no signature, HMAC with the public key as its secret, another key |
| `token/TokenIssuerTest` | 3 | an access token's header and every claim; an event's; the ids |
| `token/SigningKeysTest` | 3 | the key read and its public half made, named by its thumbprint; the published set, public keys alone; every bad key refused |
| `db/AccountsTest` | 3 | an account made under its name, the name taken whatever its case; a login recorded, a guarded rehash, a change; a deletion freeing the name and keeping the subject |
| `db/RefreshTokensTest` | 5 | kept by its hash alone; rotation and auth_time; reuse within and after the overlap; both lifetimes; revocation by family and by account |
| `db/ThrottleTest` | 4 | the address's limit and its window; the account's; IPv6 by its /64; MySQL not answering refuses |
| `db/OutboxTest` | 2 | an event rolled back with its change; claimed for a lease, taken or put off |
| `db/HousekeepingTest` | 1 | what is purged and what is kept |
| `db/DatabaseSettingsTest` | 6 | the backend's, copied with its class: the password's sources, the defaults, the pool's size, the settings printed without the password |
| `db/PrimaryDataSourceTest` | 6 | the backend's, copied with its class: the primary by its epoch, never back to a lower one, against the development server |
