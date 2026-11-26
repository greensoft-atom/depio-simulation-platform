# 10. Accounts: the account service, and the tokens the backend takes

This page is the design of the account service, which signs players up and in and issues tokens, and of what the
backend does with those tokens. It is for whoever builds, runs or calls either. The decision is
[D-117](../architecture/03-decision-log.md#d-117--accounts-in-an-account-service-of-their-own-which-issues-signed-tokens-the-backend-takes-once-for-a-session);
the backend's sessions, bans and display names are [04 §1](04-platform-services.md#1-auth-and-sessions).

**Designed 2026-11-26, plan item 142.** Not built until each part's entry in the plan says so.

## 1. Who owns what

| | The account service | The backend |
|---|---|---|
| a username and its password | yes: Argon2id, the login limits | no |
| the tokens | issues and signs them | checks them, once a login |
| a player: display name, progress, wallet, team | no | yes |
| a guest | no | yes (D-46) |
| a ban | no | yes: a banned player signs in, and the backend refuses a session |
| a session | no | yes: today's, in the store, a day |

The account's id, the tokens' `sub`, is what joins the two: the backend's `account.subject`.

## 2. The flows

```
 app                       account service (/account/v1/…)          backend (/v1/…)
  |  sign up or log in  -------->|
  |<----- access token (15 min), refresh token
  |  POST /v1/sessions {"accessToken"} --------------------------------->|  checks it by the published keys
  |<----------------------------------------------- session (a day) -----|  the player found or made
  |  … the lobby, the API, matches: the session, as before …             |
  |  the session ends (a day, a logout):
  |  refresh ------------------->|
  |<----- a new access token and refresh token (the old one spent)
  |  POST /v1/sessions {"accessToken"} --------------------------------->|
  |
  |  change password / delete --->|  the outbox, in the same transaction
  |                               |  --- a signed event, retried until taken -->|  sessions ended / player erased
```

- **Signing up** answers as a login does: the app is logged in at once.
- **A guest** plays as today, its key on the device. **Linking** an account: the guest signs up or logs in at the
  account service, then sends the access token with its own session to `POST /v1/accounts/link`. The guest becomes
  the account's player, and its key stops working.
- **Logging out** spends the refresh token's family at the account service, and the session at the backend.
- **The app keeps the refresh token**, in the device's secure store, as it keeps a guest's key today; never a
  password.

## 3. The account service

### 3.1 The process

- `account-service/`, its own Maven project (as `j-redis-service/` is), Java 21, every library from the offline
  bundle; package `com.backend.account`; one jar and its libraries, carried in the release under `account/`.
- The JDK's HTTP server on virtual threads, as the platform's; JSON by Jackson; MySQL by HikariCP, its schema by
  Flyway; Argon2id by Bouncy Castle; tokens by Nimbus JOSE + JWT.
- Its own database, `account` (development `account_dev`, tests `account_test`), its own MySQL user. With a replica,
  it follows the primary as the backend does (`PrimaryDataSource`, D-35, its own copy).
- One process a machine, as the platform; stateless but for MySQL, so the front's nginx can send to any.

**Settings** (`/etc/backend/account.env`, root 0600, D-102), each refused at the start (exit 2) when malformed:

| Variable | What | Default |
|---|---|---|
| `ACCOUNT_BIND`, `ACCOUNT_PORT` | where the API listens | `127.0.0.1`, `8070`; the development scripts give `8083` |
| `ACCOUNT_DB_URL`, `ACCOUNT_DB_USER`, `ACCOUNT_DB_PASSWORD` (or `_FILE`) | its database; the URL may name the primary and its replica, as the backend's | the development MySQL's `account_dev`, user `account` |
| `ACCOUNT_ISSUER` | the tokens' `iss`: in production the account service's public address, `https://<address>/account` | the development account service's, `http://127.0.0.1:8083` |
| `ACCOUNT_AUDIENCE` | the backend's name in `aud` | `backend` |
| `ACCOUNT_SIGNING_KEY` | the current key: an EC P-256 private key, PKCS #8, base64 on one line | none: refused without it; `dev-account.sh` makes one in `work/account/` |
| `ACCOUNT_RETIRED_KEYS` | public keys still published (SubjectPublicKeyInfo, base64, commas) | none |
| `ACCOUNT_EVENTS_URL` | where events go: the platforms' receivers, commas, tried in turn | the development platform's |
| `ACCOUNT_TRUSTED_PROXIES` | nginx's addresses, as `BACKEND_TRUSTED_PROXIES` | none |
| `ACCOUNT_METRICS_ADDR` | its metrics, for the watch | none |

### 3.2 The routes

JSON in and out, at most 4 KiB a body; each route answers its own path alone, and anything else is 404
`no_such_route` (as D-107). Every answer is `Cache-Control: no-store` (RFC 6749 §5.1). An error is
`{"error", "error_description"}`, RFC 6749's names.

| Route | Body | Answers |
|---|---|---|
| `POST /v1/accounts` | `{"username", "password"}` | 201, a token answer; 400 `invalid_username`, `invalid_password`; 409 `username_taken`; 429 `too_many_attempts`; 503 `busy` |
| `POST /v1/sessions` | `{"username", "password"}` | 200, a token answer; 401 `invalid_credentials`; 429; 503 `busy` |
| `POST /v1/sessions/refresh` | `{"refresh_token"}` | 200, a token answer; 400 `invalid_grant` (RFC 6749 §5.2) |
| `DELETE /v1/sessions` | `{"refresh_token"}` | 204, always: its family spent |
| `PUT /v1/accounts/me/password` | `{"current_password", "new_password"}`, bearer access token | 200, a token answer (a new family); 401 `invalid_token`, `invalid_credentials`; 400 `invalid_password`; 429; 503 `busy` |
| `DELETE /v1/accounts/me` | `{"password"}`, bearer access token | 204; 401 `invalid_token`, `invalid_credentials`; 429; 503 `busy` |
| `GET /.well-known/jwks.json` | | 200, the public keys, `Cache-Control: max-age=300`: the platform's to read, not routed by nginx |
| `GET /v1/health` | | 200, without the database |

**A token answer:** `{"access_token", "token_type": "Bearer", "expires_in": 900, "refresh_token", "account_id"}`.

- **A username**: 3 to 32 of `A`–`Z`, `a`–`z`, `0`–`9`, `_`, `-`; one account whatever its case. **A password**: 8
  to 128 characters, no lone half of a surrogate pair. The platform's rules, moved (04 §1).
- **A bearer access token** on the account service's own routes is checked as the backend checks one (§4.2), its own
  issuer as the audience: the token's `aud` names both.
- **Every login, sign-up, password change and deletion** passes the hasher's line and the limits first (§3.3).

### 3.3 Passwords and limits

As the platform's, moved: Argon2id m=19 MiB, t=2, p=1; at most 8 hashes at once and 160 waiting, then 503 `busy` with
a `Retry-After` of one to five seconds; a decoy hash for an unknown username; a login rehashes a password whose
parameters are old. The figures and their reasons stay in 04 §1.

The limits are the platform's, counted in MySQL (D-117): fixed windows, one row a key and window, raised by one
statement (`INSERT … ON DUPLICATE KEY UPDATE n = LAST_INSERT_ID(n + 1)`):

| Limit | Counts |
|---|---|
| 30 a minute per address (IPv6: per /64) | every sign-up, login, password change and deletion |
| 10 a quarter hour per username | every login that names it, whether it exists or not; a change or deletion of its account |

Beyond either, 429 `too_many_attempts` with `Retry-After`; MySQL not answering, 503: a limit that fails open is off
when it is attacked hardest. The address is nginx's last `X-Forwarded-For` entry when nginx is the peer, by
`ACCOUNT_TRUSTED_PROXIES`, as the platform reads it.

### 3.4 The tokens

**An access token** (RFC 9068; RFC 8725's practices):

| | |
|---|---|
| header | `alg` `ES256`, `typ` `at+jwt`, `kid` the key's RFC 7638 thumbprint |
| `iss` | `ACCOUNT_ISSUER` |
| `sub` | the account's id: 16 random bytes, base64url, 22 characters; never reused, kept after a deletion |
| `aud` | `[ACCOUNT_AUDIENCE, ACCOUNT_ISSUER]` |
| `client_id` | `app` |
| `iat`, `exp` | now, and 15 minutes on |
| `jti` | 16 random bytes, base64url |
| `auth_time` | when the password was last given: a refresh carries it on |
| `preferred_username` | the username, for the backend's first display name |

Fifteen minutes is enough for an app to take its session, and short enough that a token lost after its account is
deleted is soon worth nothing.

**A refresh token**: 32 random bytes, base64url (43 characters), kept as its SHA-256, never logged.

- **Rotated at each use**: the token is spent, and a new one issued in its family.
- **Spent twice**: within **30 seconds** of its first use, it is taken again (an app that timed out and retried); after
  that, the whole family is revoked and the answer is `invalid_grant` (RFC 9700 §4.14.2): one of the two holders was
  not the app.
- **Lives 60 days** from its issue, the family **a year** from its login: an app used once in two months stays signed
  in; a password is asked again once a year.
- **Revoked**: its family by a logout; every family of the account by a password change or a deletion.

**The keys.** One EC P-256 key signs; `ACCOUNT_RETIRED_KEYS` are published too, so tokens signed before a rotation
still check. To rotate: make a key (`scripts/account-key.sh`), put the old one's public half in the retired list, the
new one in `ACCOUNT_SIGNING_KEY`, restart; after 15 minutes, take the retired one out. The platform finds a new `kid`
by fetching the key set again (§4.2).

### 3.5 The events

A password change or a deletion writes its event to `event_outbox` in the transaction that makes the change, so no
change goes without its event. A loop delivers them:

| | |
|---|---|
| the event | a Security Event Token (RFC 8417): `typ` `secevent+jwt`, `iss`, `aud` `ACCOUNT_AUDIENCE`, `iat`, `jti`, `sub`, and `events` holding one of `urn:backend:account:deleted`, with an empty payload, or `urn:backend:account:sessions-revoked`, with `{"before_ms"}`: the change's moment in milliseconds since the epoch, since `iat` is whole seconds and a session made earlier in that second must end too (defect S-28) |
| delivered | `POST` to `ACCOUNT_EVENTS_URL`, `Content-Type: application/secevent+jwt` (RFC 8935); 202 taken |
| not taken | the next address in the list, then again after 1, 2, 4 … seconds, at most 5 minutes apart, until taken; a 400 is logged as an error each time, since it is a setting to fix, not a reason to drop a deletion |
| kept | a week after it was taken, then purged |

### 3.6 The schema

```sql
account (id BIGINT PK, subject CHAR(22) ascii UNIQUE, username VARCHAR(32) NULL, username_key AS LOWER(username) UNIQUE,
         password_hash VARBINARY(255) NULL, status (0 active, 1 deleted), created_at, last_login_at,
         password_changed_at, deleted_at)
refresh_token (token_hash BINARY(32) PK, family BINARY(16), account_id, issued_at, expires_at, family_expires_at,
               auth_time, used_at NULL, revoked_at NULL)
attempt (k VARCHAR(80), window_start BIGINT, n INT, PK (k, window_start))
event_outbox (id BIGINT PK, jti, token TEXT, created_at, next_attempt_at, attempts, taken_at NULL)
```

A deletion keeps the row, its subject and `deleted_at`, and clears the username and the hash: the name is free again,
and the subject is never given to anyone else. A housekeeping loop purges spent refresh tokens a day past their end,
windows an hour old, and events a week after they were taken.

### 3.7 What it does not do

No email: no address, no verification, no password reset (no mail service: the owner's rule). No sign-in with another
company's account (Q-52). No OAuth authorization code flow or browser login (D-117). No second factor.

## 4. The backend's side

### 4.1 Settings

| Variable | What | Default |
|---|---|---|
| `BACKEND_ACCOUNT_ISSUER` | the `iss` taken, exactly | the development account service's |
| `BACKEND_ACCOUNT_AUDIENCE` | this backend's name, which `aud` must hold | `backend` |
| `BACKEND_ACCOUNT_JWKS_URL` | the account service's key set: `http://` only to a loopback or private address written as an IP, never a name (`localhost` too), so DNS decides nothing; else `https://` | the development account service's |

### 4.2 Checking a token

`AccountTokens`, by Nimbus: a token is taken only if every one of these holds, and otherwise is 401 `invalid_token`,
its reason logged, never told:

1. a JWS signed `ES256`, nothing else (no `none`, no HMAC, no RSA): the algorithm is the key's, not the token's to
   choose (RFC 8725 §3.1);
2. its `typ` is `at+jwt` for an access token, `secevent+jwt` for an event: one cannot pass as the other;
3. its `kid` names a key in the account service's set. The set is fetched from `BACKEND_ACCOUNT_JWKS_URL`, kept 5
   minutes, fetched again ahead of its end, and at once for a `kid` it lacks, at most twice in 30 seconds (Nimbus's
   limit). While the account service cannot be reached, the set last fetched serves an hour; while no set can be had,
   a login is 503 `keys_unavailable`;
4. `iss` is the setting exactly, and `aud` holds this backend's name;
5. `exp` is to come and `iat` past, with 60 seconds' leeway for the clocks;
6. `sub` is 1 to 64 ASCII characters, and `jti` and `client_id` are there; an event names one event it knows, a
   `sessions-revoked` with its `before_ms`.

### 4.3 The routes that change

| Route | Before | Now |
|---|---|---|
| `POST /v1/sessions` | `{"username", "password"}` or `{"guestKey"}` | `{"accessToken"}` or `{"guestKey"}`; the answer gains `newPlayer` |
| `POST /v1/accounts` | registers | gone: 404 |
| `POST /v1/accounts/upgrade` | a guest's username and password | gone: 404 |
| `POST /v1/accounts/link` | | `{"accessToken"}`, a guest's session: 200 `{"playerId"}`; 409 `not_a_guest`, `account_in_use` |
| `DELETE /v1/accounts` | `{"password"}`, or none for a guest | a guest's alone; a linked player 409 `delete_at_account_service` |
| `POST /v1/account-events` | | the events (§3.5): 202, or 400 `{"err", "description"}` (RFC 8935); not routed by nginx |

**A token taken for a session:**
- the subject's player, or, the first time, a new one, named from `preferred_username` by the display name's rules
  (04 §1), or `Player` and four digits when the rules refuse it (`admin`);
- a subject whose account was deleted (`erased_subject`): 401 `invalid_token`, so a token exchanged late makes no
  player;
- a banned or suspended player: 403 `banned`, as before;
- then a session, as a login opened one.

**An event taken:**
- `deleted`: the player erased as D-84 erases it, but out of its team whatever its role, as the worker's retention
  does (D-87), since the account is gone and there is nobody to ask; the subject recorded in `erased_subject`; done
  once, and again changes nothing;
- `sessions-revoked`: every session of the player made before the event's `before_ms` ended; a session made after
  it stays, so a replayed event ends nothing new. When a session ended, the player's lobby is told
  (`evt.session.revoked`, reason `signed_out`) and closes: a player has one lobby connection, and it may be an older
  session's, whose device must not go on receiving the player's pushes, a match's ticket among them. The device that
  changed the password opens its lobby again with its new session.

### 4.4 The schema, `V3`

`account` gains `subject` (unique, NULL for a guest) and loses `username`, its key and `password_hash`: nothing has
launched, and no password is kept (the owner). `erased_subject (subject PK, erased_at)` is new. A guest is an account
with a key and no subject; a linked guest has a subject and no key; an erased one neither.

### 4.5 What goes

`AuthService`'s registration, password login and upgrade, `PasswordHasher` and the hasher's line, the per-account
login limit, and `DisplayName.fromUsername`'s use at registration (it names a first player instead). The per-address
limit stays for making guests.

## 5. The clients

All three clients change together (the rule since D-111).

- **The core**: `AccountClient` (sign up, log in, refresh, log out, change password, delete), `ApiClient.Exchange`
  and `LinkAccount` in place of `Register`, `Login` and `UpgradeAccount`; `AccountKeeper` keeps a refresh token or a
  guest's key, and signs in by whichever it holds.
- **The Unity layer**: `BackendClient` signs up, logs in, links and signs out through the keeper.
- **`client-simple` and `client-simple-no-tls`**: the account service's calls in `HttpExample`, their models in
  `ApiModels`.
- **The headless driver**: `--account <url>`; a player is an account signed up and exchanged, or a guest without it,
  and the scenarios that need an account say they are skipped. A new scenario, `account`: refresh, reuse, a password
  change ending the backend's sessions, a link, a deletion erasing the player.

## 6. The site

- **nginx**: `location /account/v1/` on 443 and 80, limited as the platform's logins (`backend_auth`), to
  `upstream backend_account`, each machine's 8070; `/.well-known/jwks.json` is not routed.
- **The release**: `account/lib/`; `backend-account.service`, user `backend-account`; `account.env.example`;
  `scripts/account-key.sh`, which makes a signing key with the machine's `openssl` (D-103).
- **MySQL**: the database `account` and its user; the backups dump and prove both databases, and the runbook's
  restore makes both.
- **Ports**: 8070 on the private address (production); 8083 run by hand, its debugger 5010; 8097 in the drills.
- **Two machines**: the account service is as the platform: on A, and on B for when A is lost, with the same key.

## 7. Testing

- The account service's own tests on `account_test`: each route and answer, the limits, the hasher's line, rotation,
  reuse and its overlap, the outbox and its delivery, the key set, a rotated key.
- The backend's: every rule of §4.2 broken on purpose (`none`, HS256 with the public key as its secret, another
  `typ`, issuer, audience, an expired token, an unknown `kid` then fetched); the exchange, the link, the events.
- Mutants for each new rule; the drills with the account service running, `account` among the scenarios; the install
  guide, the ports, the units, the release and the two machines' checks with it installed.
