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
  (`evt.session.revoked`, reason `signed_out`): a player has one lobby connection, and it may be an older session's,
  whose device must not go on receiving the player's pushes, a match's ticket among them. The gateway closes it if
  its own session is no longer in the store, and drops the push if it is: the device that changed the password may be
  connected again already on its new session, which the event leaves (T-71). Otherwise that device opens its lobby
  again with its new session.

### 4.4 The schema, `V3`

`account` gains `subject` (unique, NULL for a guest) and loses `username`, its key and `password_hash`: nothing has
launched, and no password is kept (the owner). `erased_subject (subject PK, erased_at)` is new. A guest is an account
with a key and no subject; a linked guest has a subject and no key; an erased one neither.

### 4.5 What goes

`AuthService`'s registration, password login and upgrade, `PasswordHasher` and the hasher's line, the per-account
login limit, and `DisplayName.fromUsername`'s use at registration (it names a first player instead). The per-address
limit stays for making guests.

## 5. The clients

All three clients change together (the rule since D-111). **Designed and built 2026-11-26, plan item 142 (e).**

### 5.1 The core

- **`AccountClient`**: the account service's routes (§3.2) on `ApiClient`'s own way of calling (its endpoints tried
  in turn, its timeout, the backend's root as the certificate check): `SignUp`, `LogIn`, `Refresh`, `LogOut`,
  `ChangePassword` and `DeleteAccount`. Its endpoint is the account service's base: `https://203.0.113.10/account`
  behind the site (§6), `http://127.0.0.1:8083` run by hand. A token answer is `AccountTokens` (`AccessToken`,
  `RefreshToken`, `ExpiresInSeconds`, `AccountId`). A refusal's RFC 6749 `error` and `error_description` are read
  into the `Code` and `Message` the backend's `code` and `message` fill, so `ApiResult` is one type for both.
- **`ApiClient`**: `Exchange(accessToken)` (`POST /v1/sessions`), whose `Session` gains `NewPlayer`;
  `LinkAccount(session, accessToken)`; `DeleteAccount(session)`, a guest's, with no body. `Register`, `Login` and
  `UpgradeAccount` go.
- **`AccountKeeper`** holds what signs the device in, in its secure store: an account's refresh token
  (`backend.refreshToken`) or a guest's key (`backend.guestKey`), one of the two, never a password. One player a
  device:
  - `SignIn()`: by the refresh token, refreshed, the new one kept before anything else, its access token exchanged;
    else by the guest's key; else a new guest, its key kept. A refresh token refused (`invalid_grant`: logged out, its
    password changed, a year old, its account deleted) is forgotten and the refusal answered, so the app asks for a
    login; `SignIn` again makes a new guest, so nobody is made a stranger unasked. A guest's key refused (401) is
    forgotten the same way.
  - `SignUp(username, password)` and `LogIn(username, password)`: the account's tokens, then the device's guest, if
    it holds one, linked to the account: the guest logged in by its key, its session linked, the same player, its
    progress kept, and that session the answer. A link refused `account_in_use`, the account having a player from
    another device, signs that player in by the exchange and leaves the guest, its key forgotten: the app says so
    first when the guest has anything to lose, and a guest left is erased once nobody has played it for 180 days
    (D-87). Any other failure keeps nothing new: the guest stays the device's, and an account signed up is entered
    by logging in later. The refresh token is kept once the device is in, not before.
  - `SignOut(session)`: the backend's session ended, and an account's refresh token spent at the account service and
    forgotten. A guest's key is kept, since forgetting it loses the guest: `SignIn` after is the device's guest
    again.
  - `ChangePassword(current, new)`: an access token by the refresh token, the change, the new family's refresh token
    kept, its access token exchanged: the answer is a session made after the change, which its event leaves (§4.3).
  - `DeleteAccount(session, password)`: an account's at the account service, by an access token from the refresh
    token and the password asked again, its event erasing the player (§4.3); a guest's at the backend, by its session
    alone. Either way what the keeper held is forgotten.
  - Its continuations run on the caller's context, Unity's main thread, where the store is touched (08 §2).

### 5.2 The Unity layer

`BackendClient` gains the account service's endpoints (`accountEndpoints`), one `AccountKeeper`, and `SignUp`,
`LogIn`, `SignOut`, `ChangePassword` and `DeleteAccount(password)` beside `SignIn`. A result that is a session
replaces the one held and connects the lobby again with it. `LobbyScreen` gains a name, a password and the buttons;
the app's own screens are its designers' (08 §8).

### 5.3 The simple clients

`client-simple` and `client-simple-no-tls`: `HttpExample` signs up or logs in at the account service, exchanges the
access token for a session, and refreshes and logs out; `ApiModels` gains the account service's bodies in RFC 6749's
names (`access_token`, `refresh_token`); `BackendHttp` reads a refusal's `error` where there is no `code`. The live
check takes the account service's address after the API's.

### 5.4 The headless driver, the tools and the drills

- **`--account <url>`.** A player is an account signed up at the account service, its access token exchanged; without
  `--account`, a guest, and the scenarios that need an account (`account`, `erase`, `guest`, `banned`) are said to
  be skipped, as those that need the lobby are without `--lobby`.
- **`account`**, a new scenario: the exchange, `newPlayer` the first time alone; a refresh, and the spent token again
  within its 30 s overlap, taken, as an app's retry is; a logout, after which the family refreshes no more; two
  devices' sessions and a lobby on one, the password changed from a third: both sessions ended, the lobby told
  `signed_out` and closed, the new session kept, and a lobby on it left open; the old password refused, the new
  taken; a link refused `account_in_use` for an account with a player, and `not_a_guest` for an account's session.
- **`erase`**: an account's player refused at the backend (`delete_at_account_service`); at the account service a
  wrong password refused, then the account deleted and the player erased by its event: the other device's lobby told
  `deleted`, its session ended, the name's login refused, the friend's list without it, and the name signed up again
  a new player. A guest deleted by its session alone.
- **`guest`**, through `AccountKeeper` and a memory store: a guest, paid for a stay, signed up and linked: the same
  player, with what it earned, its key refused; a second keeper on the same store signs in by the refresh token as
  the same player.
- **`banned`**: an access token exchanged while the player is banned is 403 `banned`; after the ban is lifted, taken.
- **The tools**: `LobbyClient.signIn(accountUrl, username, password)` logs in at the account service, or signs up on
  a 401, and exchanges the access token; `BotClient` takes the account service's address as its fifth argument
  (default `http://127.0.0.1:8083`). Every bot is an account, as a player is, and its Argon2 is the account
  service's.
- **The drills**: `headless-drill.sh` starts the account service from the release's `account/` (§6.1) on 8097
  (`account_dev`, a key of its own in the drill's directory, the issuer `http://127.0.0.1:8097`, its events to the
  drill's platform), points the platform at it, and gives its address to the driver, both simple clients' checks,
  the collection and the bots.
  Through each `FRONT=` it is reached through nginx's `/account`, as a phone does (§6.2); with `MYSQL_FAILOVER`, its
  database stays on the development MySQL. `dev-backend.sh test` and `bots` sign the bots up at the account service
  run by hand, which `run-local.sh` starts after j-redis and before the backend, and `build-offline.sh` builds.

## 6. The site

**Designed for building 2026-11-26, plan item 142 (f)**, built in three commits: the release and nginx; MySQL, its
backups, the watch and the install guide; and the two machines.

### 6.1 The release

- **`account/lib/`**: the service's jar and its libraries, built by `make-release.sh` from `account-service/` as it
  builds `j-redis-service/`, offline, before the backend. The runtime's module check (jdeps, 09 §4.1) reads its jar
  too; it needs nothing beyond the runtime's list.
- **`backend-account.service`**: user `backend-account`, `EnvironmentFile=/etc/backend/account.env`, the platform's
  sandbox, restarted on failure but not after exit 2 (a refused setting). Its heap 1 GiB, fixed: the hasher's line
  at its full eight holds 8 × 19 MiB, about 150 MiB, live; the HTTP server's threads and the pool are small; ZGC
  wants the heap two to three times what is live.
- **`account.env.example`**: the settings of §3.1 for machine A, `ACCOUNT_BIND` the private address and
  `ACCOUNT_PORT` 8070; the issuer the site's, `https://203.0.113.10/account`; the events to every machine's platform,
  this machine's first; nginx's three addresses trusted; metrics on `127.0.0.1:9104`. Its secrets at its end, the
  file root's alone (D-102): `ACCOUNT_DB_PASSWORD`, `ACCOUNT_SIGNING_KEY`.
- **`scripts/account-key.sh`** in the release's `scripts/`: the install writes its line into `account.env`, and the
  rotation (§3.4) uses it again.
- **The platform's unit**: its heap note loses Argon2, which is the account service's now.
- **The drills** run the account service from the release's `account/`, and from its build only for a release made
  before it.

### 6.2 nginx

- **`upstream backend_account`**: each machine's 8070 on its private address, as `backend_platform`, with its
  keepalive pool and timeout (the JDK's server, as the platform's).
- **`location /account/v1/`** on 443 and 80: limited as the logins (`backend_auth`, 2 a second and 30 at once per
  address), to `backend_account`'s `/v1/`, its read timeout the platform's 15 s (a login may queue for Argon2). Its
  `/.well-known/jwks.json` is not routed: `location /` answers 404. The account service takes the last
  `X-Forwarded-For` entry from nginx, by `ACCOUNT_TRUSTED_PROXIES`.
- **`dev-nginx.sh`** maps `backend_account` to `ACCOUNT_PORT`, and its `test` asks `/account/v1/health` through
  nginx and finds the key set refused. **The drills** through `FRONT=` reach the account service through nginx's
  `/account`, as a phone does.

### 6.3 MySQL, the backups and the watch

- **The database `account`** beside `backend` on the same server (and its replica), and the user `account`, with the
  backend user's grants on its own database alone, over TLS. The install makes both; a password of its own.
- **The backups**: `backup-mysql.sh` takes the databases as a list (`backend,account`, the default) and dumps each
  to its own file in one run, recording one outcome, good only if every dump was; `restore-proof.sh` proves each, its
  own scratch database, and records one outcome the same way. So the alerts stay one series, and a database that
  failed stops the time they read. Retention keeps each database's own; the binlogs are the server's, both
  databases' in one series, pruned before the earliest file any kept dump needs.
- **The runbook's restore** makes both databases, each from its own dump and the binlogs filtered to it.
- **`promote-mysql.sh`** changes nothing: the account service follows the primary itself, as the backend does
  (`PrimaryDataSource`).
- **The watch** scrapes the account service as `account=127.0.0.1:9104`, so `TargetDown` covers it, and
  `HasherLineFull` reads its `account_hasher_line`.

### 6.4 The two machines

- **On A and on B** (operations/10), as the platform: B's account service connects to A's MySQL, the URL naming
  both, and follows B's when it is promoted; both have the same signing key, so the platforms take either's tokens,
  and each machine's platform reads its own machine's key set.
- **Ports**: 8070 on the private address (production), its metrics 9104 on loopback; 8083 run by hand, its debugger
  5010; 8097 in the drills.

### 6.5 The checks

`check-release-el9.sh`, `check-units-el9.sh`, `check-certificates.sh` (the key script on OpenSSL 1.1.1 and 3.x),
`check-ports-el9.sh`, `check-install-guide-el9.sh`, `check-two-machines-el9.sh` and `check-release-guest-el9.sh`
each install or start the account service with the rest, and each sign-up they make goes through it.

## 7. Testing

- The account service's own tests on `account_test`: each route and answer, the limits, the hasher's line, rotation,
  reuse and its overlap, the outbox and its delivery, the key set, a rotated key.
- The backend's: every rule of §4.2 broken on purpose (`none`, HS256 with the public key as its secret, another
  `typ`, issuer, audience, an expired token, an unknown `kid` then fetched); the exchange, the link, the events.
- Mutants for each new rule; the drills with the account service running, `account` among the scenarios; the install
  guide, the ports, the units, the release and the two machines' checks with it installed.
