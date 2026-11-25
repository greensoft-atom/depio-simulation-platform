# 04 — Platform services

This page describes `platform`, the process that serves everything a player does between matches: accounts and
sessions, teams, matchmaking, room allocation, tournaments, leaderboards, the shop, inventory, equipment, payments,
the social layer, the admin API and the watch (the backend's own monitoring). It is for engineers who work on
`platform`, or on a client of its API. Words used here are defined in the [glossary](../glossary.md).

**How to read it.** Most parts were designed and built in steps, called slices. A heading that says "designed
<date>, plan item n" is the design of one slice, from [plan.md](../plan.md). "**Built** <date>" says when its code
landed. Text marked "first design", "superseded" or struck through is kept for its reasons; the text after it says
what is true now. Decisions are D-_n_ in the [decision log](../architecture/03-decision-log.md); defects are in the
[defect register](../defects.md); questions (Q-_n_) are in
[requirements §7](../requirements/01-scope-and-nfrs.md#7-open-questions).

**Where the details are.** Every route as built, with every answer and refusal, is in the
[platform README](../../backend/platform/README.md). The API reference is [docs/api](../api/01-http-api.md). The
flows are drawn in [diagrams/04-platform](../diagrams/04-platform.md).

`platform` is a **modular monolith**, not a cluster of services
([D-4](../architecture/03-decision-log.md#d-4--split-by-workload-shape-not-by-business-domain)):

- A module is plain classes. `PlatformMain` builds each one and passes it, through its constructor, what it uses: the
  store client, its repositories, its settings. There is no dependency-injection container.
- Each module owns its tables and its j-redis key namespace, and exposes a small Java interface.
- The names in the tree below are areas, not packages. As built, `net` and `watch` are packages; each other area is a
  few classes in `com.backend.platform` and their repositories in `persistence`
  ([the README's list](../../backend/platform/README.md#classes-by-domain)).

```
platform
 ├── net          HTTP API behind the front's nginx (D-82); the gateway calls it for match requests (03-gateway.md)
 ├── auth         register, login, sessions, bans
 ├── player       profile, settings, progression, cache-aside
 ├── team         create/invite/apply/roles/kick/transfer/disband
 ├── rooms        arena registry, room registry, allocation, join tickets
 ├── matchmaking  queues per mode, parties, rating buckets
 ├── tournament   registration and entry (the clock runs in worker)
 ├── leaderboard  reads and around-me; writes come from worker
 ├── economy      wallet, shop, purchases, ledger
 ├── items        catalogue, inventory, equipment, boosts → modifiers
 ├── social       friends, block list, presence
 ├── notify       pushes via the gateway; inbox for offline players
 └── watch        monitoring and alerts (§12)
```

**What is deliberately *not* here:** applying match results, granting rewards, advancing tournament rounds, and
analytics. That work is limited by throughput and needs many retries, so it lives in `worker`
([05-worker-and-events](05-worker-and-events.md)). The split is by the shape of the workload, not by domain. That is
why `tournament` appears in both processes: its API is request and response, and its clock is a scheduled job.

**Threads.**

| Work | Where it runs |
|---|---|
| each HTTP request | the JDK's HTTP server, with a virtual thread per request. The requests block on MySQL, which is what virtual threads are for. The admin API's listener is the same |
| the matcher's round | once a second, on a thread of its own, in whichever `platform` holds the matcher's lease (§4) |
| the watch's round | every 15 s, on a thread of its own (§12) |

**Migration numbers.** The numbers below (V1 to V34) are the schema's history, as each part was built. On 2026-10-04
they were squashed into one baseline, `V1__schema.sql` and `V2__seed.sql`
([D-75](../architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)).
A number below says when a table or column came, not a file that exists now.

## 1. Auth and sessions

This section covers accounts, guests, logging in, sessions, the limits on password attempts, display names,
renaming, and deleting an account.

- **Register**: a username and a password. The password is hashed with **Argon2id** (Bouncy Castle).
- **Guests** (designed 2026-09-30, plan item 22,
  [Q-22](../requirements/01-scope-and-nfrs.md#7-open-questions),
  [D-46](../architecture/03-decision-log.md#d-46--a-guests-credential-is-a-random-key-kept-as-its-sha-256-under-a-username-nobody-can-choose)).
  A guest plays without choosing a username or a password. Its credential is a random key that the device keeps:

  | Route | What it does | Answers |
  |---|---|---|
  | `POST /v1/guests` | makes a guest, named "Guest" and four digits | 201 `{playerId, guestKey, displayName}`; 429 when throttled per address, as registering is |
  | `POST /v1/sessions` `{"guestKey"}` | logs the guest in, answered as a login | 401 `invalid_credentials`, 403 `banned` |
  | `POST /v1/accounts/upgrade` `{"username", "password", "displayName"}`, with the guest's session | makes it a full account: the same player | 200; 400 `invalid_username`, `invalid_password`, `invalid_display_name`; 409 `username_taken`, `not_a_guest` |

  The key stops working at the upgrade. **(a) built 2026-09-30:**
  - V15.
  - `AccountRepository.createGuest`, `findGuest` and `upgrade`. The upgrade sets the username and the hash and clears
    the key in one statement, guarded by the key being there, so a second upgrade is `not_a_guest`.
  - `AuthService.createGuest`, `loginGuest` and `upgrade`. A guest login decodes the key, hashes it (SHA-256) and
    looks it up, with no slow hash; a malformed key is `invalid_credentials`. The upgrade checks registering's rules
    before the password is hashed, in the hasher's line.
  - The routes above.
  - A guest's own username cannot be logged into. Login refuses a name the rules forbid before any lookup, and a
    guest's password hash is empty.
- **Login**: the password is verified against `account`. Then a session is made in j-redis, in one transaction:
  `MULTI · HSET sess:{token} playerId … createdAt … · EXPIRE · SADD sess:of:{playerId} {token} · EXPIRE · EXEC`. The
  token is 32 random bytes, base64url (43 characters). The TTL is a day, jittered by ±10 %: 77 760 to 95 040 s
  (below). `sess:of:{playerId}` is the player's index of sessions, which a ban uses (§10).
- **One active lobby connection per player**, enforced at the gateway
  ([03 §4](03-gateway.md#4-connection-lifecycle)).
- **Presence**: designed as `SET presence:{id} lobby|arena:{roomId} EX 60`, refreshed by a heartbeat. Built otherwise
  (§9, the social layer): a friend is online while their lobby connection is registered.

**Bans.** As first designed, a ban sets `account.status` and publishes a kick. The session is *not* checked again on
every request, because that would turn a short store outage into a mass logout
([03 §9](03-gateway.md#9-failure-behaviour)). So a ban takes effect through the push, with the next login as the
backstop. **Built** (§10, 2026-09-29): an operator's ban ends every session and the lobby connection, and a kick
takes the player out of every arena.

**What is built:** `platform/AuthService`, `PasswordHasher`, `DisplayName`, `LoginThrottle`;
`handoff/SessionStore`; `persistence/AccountRepository`. The details below are not obvious from the list above. The
code and its tests pin each one down.

**Argon2id costs 62 to 88 ms, and that is the system's tightest CPU limit.**

- It was measured at m=19 MiB, t=2, p=1 (the OWASP minimum, not the 64 MiB desktop profile), across repeated runs on
  the shared development VM. The spread comes from the machine, not the code. The capacity figure below uses the slow
  end.
- Argon2 is memory-hard on purpose, so at most 8 hashes run at once. Otherwise a burst of logins would claim 19 MiB
  each and end in an `OutOfMemoryError` rather than a queue.
- That gives about **90 password logins a second per `platform` process**, using 8 cores while it runs. It is enough,
  because the common path is resuming a session, which is one `HGET`. It is not enough for 50 000 players logging in
  within one minute. Measure again on production hardware before assuming otherwise
  ([Q-3](../requirements/01-scope-and-nfrs.md)).

**Session TTLs are jittered by ±10 %.** With a fixed 24-hour TTL, every session issued in a launch or restart window
would expire together exactly one day later. That herd would land on the 88 ms path. Ten per cent of a day spreads it
over about five hours.

**The stored hash carries its own parameters** (`$argon2id$v=19$m=…,t=…,p=…$salt$hash`). So the cost can be raised
later without making every password invalid at once.

- **A login rehashes the password** when its parameters are not the current ones (built 2026-09-26). It does so
  after the password has verified, and only if the stored hash is still the one that verified. So a password changed
  in the meantime is never put back.
- Before this, a raised cost protected new passwords only.
- Expect it to show: after a raise, each account's next login costs two hashes, so the hasher's line runs fuller
  until most players have logged in once.

**An unknown username costs the same as a wrong password.** When no account matches, login verifies against a decoy
hash. Without the decoy, the unknown case returns in microseconds and the known case in tens of milliseconds: anyone
with a clock could tell which usernames exist. Both answer `INVALID_CREDENTIALS`, and so does a wrong password on a
banned account.

- **This holds only while every stored hash is at the current cost.** After a cost raise, a wrong password on an
  account not yet rehashed is checked at its old, cheaper cost. An unknown username is checked against the decoy at
  the new cost. Timing can tell the two apart until those accounts have logged in once.

**A display name is checked, and refused rather than repaired.** It is the one piece of text a player writes that
every other player sees: in the kill feed, and on a public leaderboard. The rules are RFC 8266 (PRECIS, the Nickname
profile), with the combining-mark checks of UTS #39 §5.4:

| Rule | Why |
|---|---|
| NFKC, spaces collapsed and trimmed | two spellings that look the same are stored the same: a composed é, and e plus an accent; fullwidth Ａ and A; combining marks typed in either order |
| Letters, marks and decimal digits of any script, plus space `_` `-` `.` | players are international. Symbols and emoji are refused: they render differently per device |
| No control, format, private-use, unassigned or **default-ignorable** character | direction overrides and zero-width characters are format characters. The Hangul filler and the variation selectors are classed as a letter and as marks but render as nothing, so they needed an explicit list: Java has no query for the property |
| An accent follows a letter; at most 4 nonspacing marks in a row; never the same one twice in a row | stops a tower of accents covering the rows above and below. Hindi needs three on one consonant, so the first cut's limit of two refused real words |
| At most one of Latin, Cyrillic, Greek | a deliberate subset of TR39's confusable detection: it stops a Cyrillic a (U+0430) posing as a Latin a, not every lookalike |
| 1 to 16 code points | 16 × 4 UTF-8 bytes = 64 = `Wire.MAX_NAME_BYTES`, so every accepted name fits the kill feed |
| A short reserved list, compared with case and width folded and separators removed | "killed by System" is staff impersonation |

- The check runs before the password is hashed, so a request that will be refused never costs 88 ms.
- The HTTP answer is 400 `invalid_display_name`, with a message that names the rule broken, so a client can tell the
  player what to change.
- With no display name, the username is used, cut to 16 and checked the same way. So the username `admin` registers
  only with a display name of its own.

**Registration and login are throttled before Argon2 runs.** Two fixed-window counters in j-redis hold the limits, so
they hold across all three `platform` processes:

| Limit | Counts | Stops |
|---|---|---|
| 30 a minute per source address (IPv6: per /64) | every registration and login | one machine spending the Argon2 budget, or guessing fast |
| 10 a quarter hour per account | every login that names it, whether it exists or not | guessing one account from many addresses |

- **Beyond either limit**: 429 `too_many_attempts`, with `Retry-After`.
- **The source address** is the TCP peer's. When the peer is nginx, it is the **last** entry of `X-Forwarded-For`,
  the only one nginx wrote. nginx is believed when it is a peer on the same machine, or, since
  [D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name),
  when its address is in `BACKEND_TRUSTED_PROXIES`. That matters because the front's nginx reaches every platform over
  the private network; without the setting, every player would count as the front.
- **`BACKEND_TRUSTED_PROXIES`** is a comma-separated list of IP literals: the three machines' addresses. A name, or
  anything else, in it refuses the start (exit 2), since the setting must never reach DNS. The header itself is parsed
  as an IP literal and never resolved. The gateway names a lobby connection's client in its logs by the same rule.
- **When the store does not answer**, the attempt is refused (503). A limit that fails open is off exactly when it is
  attacked hardest.
- **Measured** across two instances sharing a store: sixteen guesses at one account from sixteen addresses gave ten
  401s at 110 to 160 ms, then six 429s at 16 to 27 ms. So a refusal costs no hash.

Why these numbers, and what they cost:

- **30 a minute, not the 5 first designed** ([03 §6](03-gateway.md#6-rate-limiting-and-abuse)). Mobile carriers put
  many subscribers behind one public IPv4 address, and a password login is rare anyway: resuming a session costs
  none. 30 a minute is still 43 000 a day from one address.
- **Every attempt counts, not only failures.** So the count is taken before the password is checked, and concurrent
  guesses cannot all slip under it.
- **An account can be locked out on purpose.** Ten wrong guesses in a quarter hour keep its owner from logging in
  with the right password (the drill got 429, `Retry-After: 750`). A player who already holds a session is not
  affected. This is the accepted cost of a per-account limit; the alternative is unlimited guessing from a botnet.
- **Not stopped:** credential stuffing spread thinly over many addresses and accounts. That needs checks against
  breached passwords, or a second factor.

**The hasher's line is bounded, and a full line answers at once.** Per process, at most 8 passwords are hashed and
160 wait. Beyond that, the answer is 503 `busy`, with a `Retry-After` of one to five seconds, spread so the clients
turned away do not return together. Admission comes before the throttle counts, so an attempt turned away for load
never counts against the account.

Without the bound, a mass re-login (every session lost at once) collapses. Once the line is longer than a client will
wait, every hash finishes for a client that has gone, and the retries queue behind them. Measured on one process:
3 000 logins at once, with clients retrying after a 10 s timeout:

| | Unbounded | Bounded |
|---|---|---|
| logged in | 405, all in the first 10 s, then none for 60 s | all 3 000 by 47 s |
| clients that gave up | 13 803 | 0 |
| server CPU | 1 175 s, nearly all for clients already gone | 314 s, none wasted |
| accounts locked out by their own retries (at a 3 s timeout) | 251 of 600 | 0 |

**What a client must do** (the Unity client must be written to it):

- allow a login **10 seconds**;
- on 503 or 429, wait the `Retry-After` it was given;
- on a timeout or a dropped connection, wait one to three seconds, chosen at random, before retrying.

The line's depth and that timeout are a pair: against a 3 s timeout, the same line was too deep for the development
VM.

**A username names an account only if registration would accept it.** Login refuses anything else before the lookup.
The reason: the lookup compares under `utf8mb4_0900_ai_ci`, which ignores accents. "ada" with an accented first
letter logged into `ada`, and would have given the per-account limit a fresh count for every spelling.

### Routes matched as nginx limits them (designed 2026-10-02, plan item 60 (a))

The second audit found two ways around what nginx and the API mean ([S-17, S-18](../defects.md#5-security-and-input)),
and one more cost:

- **An id in a path is at most 18 digits.** The routes took 19 digits, and a number above the largest `long` threw in
  the parse: a 500 and a logged stack trace, on five routes, one of them without logging in. Every 18-digit number
  fits a `long`, and every id this system makes is far shorter. So a longer id names no route: 404 `no_such_route`, as
  any path that names nothing.
- **The routes nginx limits are matched exactly.** The JDK's server matches a context by its prefix, so
  `POST /v1/sessions/` logged in past nginx's exact `location = /v1/sessions`. Now `/v1/sessions`, `/v1/accounts`,
  `/v1/guests` and `/v1/accounts/upgrade` answer only their own path, and 404 for anything after it. nginx limits all
  four the same way (`deploy/nginx/backend.conf`: `limit_req`, 2 requests a second per address, a burst of 30, on 443
  and on 80 alike). **Since 2026-11-05 every route is matched so**
  ([D-107](../architecture/03-decision-log.md#d-107--developments-80-serves-the-api-and-the-lobby-itself-the-apis-health-under-v1-every-route-checked-through-nginx)):
  a route answers its own path alone, and a path that no route has is the API's JSON 404 `no_such_route`, not the
  server's HTML page. `GET /v1/health` answers as `/health` does, without the database, so a probe through nginx can
  ask whether the API answers.
- **An upgrade checks before it hashes.** It reads, in one query, that the account is a guest and that the name is
  free. Then it hashes the password, then it upgrades, checking both again as it writes. A refusal no longer costs a
  hash.

### A store that fails is 503, however it was reached (designed 2026-10-02, plan item 60 (c))

A request that the database or a store cannot serve is answered 503 `storage_unavailable`. Nothing is wrong with the
request, so the client should come back later rather than report a fault.

**The defect** ([O-12](../defects.md#6-operations)). A store call made through the server's own wait was already
answered so. A call on a leased connection (a party's change, the matcher's watched steps) threw the store client's
own exceptions instead. Those were taken for a bug: 500 `internal`.

**Now these are 503, by whichever path they came:**

- the store client's connection failures, its timeouts, and a client that was closed;
- the two refusals a store gives when it cannot take a write: `READONLY` (a primary demoted under the request) and
  `NOREPLICAS` (too few replicas acknowledging);
- either of those as the reason a watched change was discarded (`EXECABORT`).

Any other refusal from the store means a request it could not run, which is a bug: still 500.

### Renaming (designed 2026-10-02, plan item 63)

A player's display name is chosen once, at registration, or made for a guest (`Guest` and four digits). A team's name
is chosen when the team is made. Before this slice, neither could change.

| | |
|---|---|
| a player | `PUT /v1/accounts/name` `{"displayName"}`, bearer session → 200 `{"playerId", "displayName"}`; 400 `invalid_display_name` by the rules above; 429 `too_soon`, as an asking limit is |
| a team | `PUT /v1/teams/mine/name` `{"name"}`, by its leader → 200, the team; 400 `invalid_name`; 403 `not_allowed`; 409 `name_taken`; 429 `too_soon`; every member is pushed `evt.team.update` (§2) |
| how often | **free, once in 30 days**, for each (`player.name_changed_at`, `team.renamed_at`, V23). The first rename may be at once, so a guest's made name, or a name regretted, can change the day it is chosen |

**Why free, and why 30 days** (a first cut: the owner made the economy's balance Claude's to choose, Q-48).

- A name is how other players know someone on a board and in the kill feed. A month keeps a board's names
  recognisable from one look to the next. It also makes it slow to repeat a name used to pass as someone else, or to
  shed one's record with others.
- A price would be a sink for coins. But the player who needs a rename most is the new one, whose name was made for
  them, and who has the fewest coins.
- One constant each, `RENAME_EVERY`.

**What changes at once, and what changes later.** The name in MySQL is the truth.

- Everything read from MySQL shows the new name from the rename on: friends, teams, tournaments, the inbox. The rating
  boards show it within their thirty seconds' cache.
- The score boards' names (`lb:name`) are written by the rename. `worker` writes them too, when it applies a result:
  it uses the name MySQL holds, read as it locks the player, not the name the result carries. Otherwise a match that
  began before the rename would write the old name back when it ends
  ([D-60](../architecture/03-decision-log.md#d-60--a-boards-name-is-the-one-the-database-holds-when-a-result-is-applied)).
- Copies made before the rename keep the old name until they are made again: a party's (until it next changes), a
  queued entry, and a match under way (from the next match).
- A rename and a result applied in the same instant can leave a board with the old name until the player's next
  match.

### Deleting an account (designed 2026-10-24, plan item 97)

`DELETE /v1/accounts` `{"password"}`, with the bearer session, erases the player's own account in place
([D-84](../architecture/03-decision-log.md#d-84--a-player-deletes-their-own-account-erased-in-place-what-others-history-needs-kept-under-a-bare-id)).
A guest sends no password.

| Answer | When |
|---|---|
| 204 | erased, or already erased |
| 400 `invalid_body` | a registered account sent no password |
| 401 `no_token`, `invalid_session` | no session |
| 403 `wrong_password` | the password is not the account's; it is counted as a failed login |
| 409 `leader_with_members` | the player leads a team with others in it: they must hand it over first (§2) |
| 429 `too_many_attempts`, 503 `busy` | the login's limits and the hasher's line, as at a login |
| 503 `storage_unavailable` | the store did not answer. Before the erasure, nothing is erased. After it, the same call with the same session finishes it |

**The steps, in this order** (`AccountDeletion`), as an operator's ban orders its own (§10):

1. Find the session's player, and check the password. A guest has none, and an account already erased asks none.
2. Take the player out of the queue and the party, and out of the team, each as its own call does. A store that fails
   here refuses the call before anything is erased.
3. Erase, in one MySQL transaction (`AccountRepository.erase`): the account's username, hash, guest key and last
   login; the player's display name and rated counts; the rows of `friend`, `friend_request` and `block`, in either
   direction; and `inbox`, `team_invite` and `team_application`.
4. The store's part: take the player off the score boards (`lb:score:*` for every period still kept, and `lb:name`),
   and end every session (`revokeAll`). A store that fails here answers 503 while the session still works, and the
   same call finishes the job: steps 2 and 3 are done, and an erased account asks no password.
5. Tell every device's lobby `evt.session.revoked` `{"reason": "deleted"}`, which closes it.

**To everything else, a deleted account is no account.** A login finds no such name and no such guest key. The admin
API answers 404. The boards' rebuild leaves it out. The app forgets its session, and a guest's key.

### An idle guest erased (designed 2026-10-26, plan item 105)

The worker's retention erases a guest that nobody plays for 180 days
([D-87](../architecture/03-decision-log.md#d-87--a-guest-nobody-plays-for-180-days-is-erased-by-retention-a-deletion-is-at-once),
Q-61). A guest is idle when all three hold:

- it was made more than 180 days ago;
- nobody has logged into it since;
- it has no day in `player_day` since. `player_day` is kept 90 days. This second test guards the first against a
  session that outlives a day by one day.

Only a guest is erased this way: a registered account is its player's to delete.

**How** (`worker/IdleGuests`): the same erasure as above, done by the worker, one guest at a time, in this order:

1. off the boards first, in the store. A store that fails stops the step with nothing erased, and the next day's run
   finds the guest still a guest;
2. out of its team, whatever its role (§2);
3. `AccountRepository.erase`.

There is no session to end, since a session lives a day, and no lobby to tell. The app says that guests are erased
where a guest signs in, and so does the API guide at `POST /v1/guests`. `backend_worker_guests_erased_total` counts the
guests erased. `GET /admin/stats/guests` reports `inactive`, the guests idle 90 days: the measure of what the next 90
days will erase.

## 2. Teams

A **team** is a lasting group of players with a leader, up to two vice leaders and members. (A **party**, §4, is a
few players who queue together for a while; the two are different things.) This section covers the roles, the
rules, the transaction every action uses, invitations, applications, and the pushes a team's members get.

| Action | LEADER | VICE_LEADER | MEMBER |
|---|---|---|---|
| invite / accept application | ✓ | ✓ | |
| kick member | ✓ | ✓ (members only) | |
| promote to VICE / demote | ✓ | | |
| transfer leadership | ✓ | | |
| edit name/tag/settings | ✓ | ✓ (settings only) | |
| start team battle / register for a tournament | ✓ | ✓ | |
| leave | ✓ (must transfer first unless alone) | ✓ | ✓ |
| disband | ✓ | | |

As built, tags and settings do not exist. A rename is the leader's alone (§1). "Start team battle" is queueing for a
team match (§4, the sixth slice).

**Rules:**

| Rule | Value |
|---|---|
| vice leaders | at most two |
| capacity | 30 members |
| teams per player | one |
| name | unique, ignoring case (and accents: the column's collation) |
| cooldown after leaving | 24 hours before joining another team, against team-hopping |

The constants are in code (`TeamRepository`; the capacity in `PlatformMain`). Changing one is a release.

**Every action is a single MySQL transaction that locks the team row first:**

```sql
START TRANSACTION;
SELECT id, member_count, version FROM team WHERE id = ? FOR UPDATE;
-- check capacity, role and cooldown in code
INSERT INTO team_member (team_id, player_id, role) VALUES (?,?,?);
UPDATE team SET member_count = member_count + 1 WHERE id = ?;
UPDATE player SET team_id = ? WHERE id = ?;
COMMIT;
```

`team_member` is the source of truth. `player.team_id` is a denormalised pointer, kept up to date in the same
transaction. Locking the team row makes the capacity a real constraint rather than a race: two joins at once to a team
with one place left cannot both succeed.

**Team chat** was designed on the **fixed phrase list**, like all communication
([D-14](../architecture/03-decision-log.md#d-14--communication-is-a-fixed-phrase-list-never-free-text)): history
as `LPUSH teamchat:{id}`, capped at 200 entries in j-redis, holding phrase ids, not text. **Not built**: a team has no
chat (see "Team events pushed", below). A party has phrases (§4, the second slice).

### The first slice (designed 2026-09-30, plan item 17)

On [Q-14](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation: the table above, without
applications, tags and settings, and with invitations as the way in. **Built 2026-09-30**, and drilled from the
release.

| Action | Who, and what happens |
|---|---|
| **Create** | a player in no team, and not within 24 hours of leaving one, names a team and leads it. The name follows a display name's rules (`DisplayName.check`) and is unique by the column's collation, which ignores case and accents |
| **Invite** | a leader or vice leader names a player by their id, as a party invitation does. A player knows their own id and sees others' on the boards. The public code is still shown by no API (found while building the client). An invitation lasts seven days; one per player per team |
| **Accept** | the invited player, in no team and past the cooldown, joins as a member if the team is below its capacity. The invitation is spent |
| **Decline** | the invitation is dropped |
| **Leave** | anyone but a leader with members. The cooldown starts |
| **Kick** | a leader removes anyone, a vice leader only members. The kicked player's cooldown starts too |
| **Promote** to vice leader, and **demote** | by the leader; two vice leaders at most |
| **Transfer** | the leader hands the leadership to a member and becomes a member |
| **Disband** | by the leader: every member out, cooldowns started, invitations dropped |
| **Erased as an idle guest** | by retention ([D-87](../architecture/03-decision-log.md#d-87--a-guest-nobody-plays-for-180-days-is-erased-by-retention-a-deletion-is-at-once)): out, whatever the role. A leader with members first hands the team to the longest-serving vice leader, or else the longest-serving member (by `joined_at`, then id), then leaves. A leader alone ends the team. The members are not pushed the change; they see it at their next read |

**Locks.** Every action is one transaction that locks the team row first, then the players it changes (D-39). So the
capacity and the vice leaders' limit hold under concurrent requests, and there is one lock order, never two.

**Only the players it changes are locked** (plan item 51, defect D-32). Disbanding clears its members by their ids,
read from `team_member` in ascending order, each player's row locked by its key. It used to clear them by
`player.team_id`, which has no index. That walked the whole table, and under REPEATABLE READ it kept every player's
row locked until the commit: while a team was disbanded, no result was applied and nothing was bought.

| | |
|---|---|
| `POST /v1/teams` `{"name"}` | creates it → 200, the team; 400 `invalid_name`, 409 `name_taken`, `in_team`, `cooling_down` |
| `GET /v1/teams/mine` | the player's team, its members and their roles → 200, or 404 `no_team` |
| `POST /v1/teams/mine/invites` `{"playerId"}` | → 200; 403 `not_allowed`, 404 `no_such_player`, 409 `in_team` (theirs), `too_many_invited` (20 out); 429 `too_soon` (20 an hour) |
| `GET /v1/team-invites` | the player's invitations, the 50 newest → 200 |
| `POST /v1/team-invites/{teamId}` `{"accept"}` | accept → 200, the team; decline → 200, the remaining invitations `{"invites"}`; 404 `no_invite`, 409 `in_team`, `cooling_down`, `team_full` |
| `POST /v1/teams/mine/leave` | → 200 `{}`; 404 `no_team`, 409 `leader_with_members` |
| `DELETE /v1/teams/mine/members/{playerId}` | kick → 200; 403 `not_allowed`, 404 `not_a_member` |
| `POST /v1/teams/mine/members/{playerId}/role` `{"role"}` | `vice_leader` or `member` → 200; 403, 404, 409 `too_many_vices` |
| `POST /v1/teams/mine/leader` `{"playerId"}` | transfer → 200; 403, 404 |
| `DELETE /v1/teams/mine` | disband → 200 `{}`; 403 `not_allowed`, 404 `no_team` |
| `PUT /v1/teams/mine/name` `{"name"}` | rename, by the leader, once in 30 days → 200, the team; 400 `invalid_name`, 403 `not_allowed`, 409 `name_taken`, 429 `too_soon` (§1, renaming) |

### Applications (designed 2026-10-02, plan item 64)

Q-14 left invitations as the way in, with "applications later". On
[Q-49](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation, a player may now also ask a team to
take them. Found while designing: no API let a player find a team, whose id showed only in a tournament's entries. So
applications come with a search.

| | |
|---|---|
| `GET /v1/teams?name=<start>` | up to 20 teams whose name starts with `<start>`, by the name's collation (case and accents ignored), ordered by name → 200 `{"teams": [{"id", "name", "members", "rating"}]}`; 400 `invalid_name` for an empty start or one over 16 characters |
| `GET /v1/teams/{id}` | the same for one team → 200; 404 `no_such_team` |
| `POST /v1/teams/{id}/applications` | by a player in no team and past the cooldown → 200; 404 `no_such_team`; 409 `in_team`, `cooling_down`, `already` (one per team; a declined one counts until it lapses), `team_full`, `too_many_applied` (5 out); 429 `too_soon` (20 an hour, counted apart from invitations and friend requests, with the message `twenty applications an hour at most`; until 2026-10-06 it gave the invitations' message, P-56) |
| `DELETE /v1/teams/{id}/applications` | the applicant withdraws → 200; 404 `no_application` |
| `GET /v1/team-applications` | the player's own, not lapsed → 200 `{"applications": [{"teamId", "teamName", "expiresAt"}]}` |
| `GET /v1/teams/mine/applications` | by the leader or a vice leader: the 50 newest, not lapsed and not declined → 200 `{"applications": [{"playerId", "name", "expiresAt"}]}`; 403 `not_allowed` for a member; 404 `no_team` for a player in no team, as on every other `/mine` route (P-58) |
| `POST /v1/teams/mine/applications/{playerId}` `{"accept"}` | by the leader or a vice leader → 200, the team; 403 `not_allowed`; 404 `no_application`; 409 `in_team`, `cooling_down`, `team_full` |

**How an application goes:**

- It lasts **seven days**, as an invitation does.
- The leader and each vice leader are told in their inbox (`team_application`, its `ref` the applicant) and by
  `evt.inbox`.
- **Accepted**: the applicant joins as a member, as by an invitation. The team is locked, then the player, and the
  capacity and the cooldown are read under the locks. Every other application of theirs is dropped, since they are in
  a team now. The team is told `evt.team.update`, as on any join.
- **Declined**: it is kept, marked, until it lapses, and nobody is told. The same player cannot apply to that team
  again for those days. That bounds what one declined player can send to a team that does not want them.
- **Blocked**: a player whom the leader has blocked is answered as if the application went in, and it is never shown.
  An invitation to a player who blocked the inviter is treated the same way (Q-20).
- Disbanding drops the team's applications with its invitations. Retention deletes the lapsed ones, as it does
  invitations (06 §9).

| After | Pushed to | |
|---|---|---|
| an application | the leader and each vice leader | `evt.inbox` |
| an application accepted | every member, the new one included | `evt.team.update`, the team |

### Team events pushed (designed 2026-10-01, plan item 39)

On [Q-35](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation. The push is
**`evt.team.update`**, `{"team": …}`. `team` is the team exactly as `GET /v1/teams/mine` answers it, or `null`:

| After | Pushed to | `team` |
|---|---|---|
| an invitation accepted | every member, the new one included | the team |
| leave | the one who left | `null` |
| | every member left | the team |
| kick | the one kicked | `null` |
| | every member left, the kicker included | the team |
| a role set, the leadership handed over | every member | the team |
| disband | every member it had | `null` |

- The team is read after the change commits. The members that a disband or a departure leaves out are read before
  it.
- A push is a notification. A client that missed one fetches the truth (03 §7).
- Not pushed: the team's rating after a match, and team phrases, since the team's chat is not built.
- `TeamService` sends these pushes, as `PartyService` sends a party's.

**Built 2026-10-01** (`TeamService`: each action's pushes, and the team's JSON, which the test holds equal to
`GET /v1/teams/mine`'s; the client's `LobbyClient.Team`). A refused action changes nothing and tells nobody.

## 3. Arena registry, rooms and tickets

This section covers how `platform` knows which arenas are alive, how it chooses one for a player or a match, and the
**join ticket** that lets a player into an arena. A **grant** is what a player is given to connect: the arena's
host, its port, the ticket's id, whether to use TLS, and the TLS name (see the [glossary](../glossary.md#6-on-the-wire)).

Arena processes register themselves and refresh their entries. The first design:

```
HSET arena:{name} host,port,capacityRooms,capacityPlayers,version   EX 10   (every 3 s)
HSET room:{roomId} arena,mode,players,maxPlayers,state,matchUid     EX 10   (every 2 s)
SADD rooms:{mode} roomId
```

**The arena half is built** (`handoff/ArenaDirectory`, `arena/ArenaAnnouncer`). The room-level registry is not,
because the arena still chooses its own room. Each arena writes its entry every 3 s, with a 10 s expiry, and adds its
name to the index `arenas`.

**As built, the arena's entry differs from the sketch above:**

| Field | Meaning |
|---|---|
| `host`, `port` | where players connect. `port` is `BACKEND_ADVERTISE_PORT` when set: behind the front's nginx, the public 9001 (D-82). Otherwise it is the port the arena listens on |
| `players`, `maxPlayers` | players now; `maxPlayers` is rooms × players per room |
| `tls` | 1 when clients must use TLS |
| `tlsName` | set from `BACKEND_ADVERTISE_TLS_NAME`, empty for none: the name a client sends in its TLS handshake and checks the certificate against. The front's nginx routes by it. With it, `tls` is 1, though the arena itself speaks plaintext |
| `rooms`, `maxRooms` | rooms running, of how many it may run |
| `roomList` | the arena's rooms as JSON, for an operator (§10) |

There is no `version`. Every grant carries `tlsName` beside `arenaHost` and `arenaPort`. A client without it uses the
host, as before. A client without TLS takes the same grant and reaches the arena through the site's port 80, asking
for `GET /match/<tlsName>`
([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included));
the grant does not change for it.

**How an arena is chosen:**

- **For the public arena**, `platform` picks the arena with the most free places, less the seats promised to it
  (`ArenaDirectory.pick`; "Seats promised", below). The design said the lowest ratio of players to capacity, which is
  the same choice while every arena has the same capacity.
- **For a made match**, it picks the arena with the most free rooms, less the rooms promised to it
  (`reserveForMatch`, §4).

**An arena announces itself rather than being registered by an operator.** Configuration can say that an arena
exists, but only the arena can say that it is accepting connections. That is why it announces only after it has bound
its port.

**Liveness is the TTL, not a health check.** Nothing has to notice a failure or agree that it happened: the entry
simply stops being refreshed. Verified by killing an arena with `SIGKILL`: its entry was gone 11 seconds later, with
no cleanup anywhere. The index keeps the name, and the next read removes it, so a crash loop cannot grow the index
without bound. The cost is bounded staleness: for up to 10 seconds a player can be sent to a dead arena, fail to
connect and queue again. That is the cheapest failure available here.

**Rooms by command: superseded.** `platform` was to command an arena over pub/sub (`PUBLISH arena:{name}:cmd`) and
wait for the `room:{roomId}` hash to appear. **Superseded by
[D-20](../architecture/03-decision-log.md#d-20--a-matchs-room-is-made-by-its-first-ticket-not-by-a-command)**: a
match's room is made by the first ticket that names it (§4). The design also said that when an arena's key expires,
its rooms are dropped from the registry and its tournament matches are rescheduled. As built there is no room
registry, and a tournament match on a lost arena gets no result, so the scheduler's 270 s rule decides it (§6).

**Allocation policy:**

1. **Public modes** (free-for-all, teams, tag, domination). First design: pick an existing room of the mode with
   `players < maxPlayers − 5`, preferring the fewest players above a minimum, so rooms stay lively at roughly 40 to
   70 % full; if none, create one on the arena with the lowest `players / capacityPlayers` ratio. **As built** (the
   public arena): `platform` sends the player to the arena with the most free places. The arena puts them in its
   **fullest** room below `maxPlayers − 5`, and opens a room only when every room is that full. So rooms fill one after
   another ([operations/04 §2.1](../operations/04-scaling-and-performance.md#21-how-players-are-placed)).
2. **Ranked, duel, team-vs-team, tournament, co-op**: always a fresh room, with a `matchUid` assigned by `platform`, a
   fixed roster, a join window (30 s in the first slice, §4), and a walkover if a side never arrives.
3. **Sandbox**: first designed as created on demand with a six-character code, `SET roomcode:{code} roomId EX 21600`.

**As built** (§4): only the public arena, mode `ffa`, is open, and the arena places a player in its own open rooms.
Every other mode (`duel`, `tvt`, `rffa`, `coop`, `teams`, `domination`, `tag`, `maze`) is a **made room** for one
match, from the queue or a tournament. A sandbox is a made room opened by `POST /v1/sandbox`, with no code.

**This allocator is the load balancer for match traffic**, not nginx
([D-5](../architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)). The front's
nginx only passes each connection to the arena its grant names.

### Seats promised (designed 2026-10-06, plan item 85)

**The problem** ([T-59](../defects.md#4-concurrency)). An arena's announcement, every 3 s, counts only the players in
its rooms. A ticket given out since then was counted nowhere. So a burst of requests all saw the same counts, and all
went to the same arena: 400 bots were sent to one arena of 300 places, 100 were refused at the door, and a second
arena stayed empty.

**The fix.** Now a seat is promised, as a match's room is (§4,
[D-79](../architecture/03-decision-log.md#d-79--a-public-seat-is-promised-in-the-store-when-its-arena-is-chosen)):

```
platform, per request    pick: for each live arena, free = maxPlayers − players − ZCOUNT seats:promised:{arena} now +inf
                         the most free wins; none above 0 → 503 no_arena
                         MULTI · ZREMRANGEBYSCORE seats:promised:{arena} -inf now
                               · ZADD seats:promised:{arena} now+60s {playerId} · PEXPIRE … 60s · EXEC
                         then the ticket, as before
arena, each claim        a place sought: reserved, or none to be had → note the player's id
arena, each 3 s          take the notes → count players + places reserved → one write:
                         HSET arena:{name} … players … · EXPIRE · SADD · ZREM seats:promised:{name} {the notes}
```

- **A player is never counted by neither side.** The arena makes a note after the place is reserved, and takes the
  notes before it counts; the count includes reserved places. So a player whose promise is dropped is in the same
  write's `players`, or holds nothing here (refused for want of room, or gone). A note made between the take and the
  count is counted twice until the next announcement. That is the safe way to be wrong.
- **Promises lapse with the ticket**, after 60 s. A player who never comes holds a place that long. So does a player
  turned away before a place is sought (taken out by an operator, or gone while the claim ran).
- **Not guarded by a transaction**, unlike D-42's last room. Two requests at once can be sent to one arena's last
  place, and one player refused at the door, as before. A refused seat is asked for again; a refused match is not.
- **Keyed by player id**, not ticket id. The ticket's id is a credential. And a player who asks twice holds one place
  on each arena chosen, not two.

### The join ticket

**The ticket carries the fully resolved loadout.** So the arena never reads inventory, equipment or the item
catalogue: it never touches MySQL at all. The first design of its fields:

```json
{ "playerId": "…", "name": "Ada", "colorId": 4, "teamHint": 2, "matchUid": "…",
  "startLevel": 1,
  "modifiers": [ { "stat": 5, "op": "ADD_PERCENT", "value": 0.08, "source": "eq:eq_barrel_steel" },
                 { "stat": 0, "op": "ADD_PERCENT", "value": 0.15, "source": "boost:boost_regen_1h" } ],
  "xpMultiplier": 2.0, "cosmetics": { "skin": 3 } }
```

**How it is stored:** `HSET ticket:{id} … EX 60`, single-use. The arena claims it with
`MULTI · HGETALL · DEL · EXEC`, so a second use finds nothing. The id is 128 bits from `SecureRandom`. It is a bearer
credential: anyone who holds it joins as that player.

**What is built** (`handoff/Ticket`, `handoff/TicketStore`, claimed by `arena/MatchFrameHandler`): the fields the
arena consumes.

| Field | Meaning |
|---|---|
| `playerId`, `name`, `team` | who, the name shown, and the team in the room |
| `match`, `mode` | for a made match: its id and its mode |
| `bonus` | what the player wears, as `stat:percent` pairs (§8, D-37); absent when there is none |
| `skin` | the number of the skin worn (§8, D-70) |

The other loadout fields in the sketch are not built: a ticket field that nothing consumes is a field that nothing
tests. The claim runs off the arena's event loop. It is a network round trip, and waiting for it on a Netty thread
would stall every other connection on that loop.

**A refused join gets `Kick` with a reason** ([02 §3](02-networking.md#3-messages)) rather than a bare socket close.
So the client can tell "get a new ticket" from "queue again" from "update the app".

~~Per-match consumable boosts are decremented in MySQL at ticket creation and refunded by `worker` if the player
never joined — detectable because no `match_player` row appears for them.~~ **Not so** (2026-09-30,
[Q-23](../requirements/01-scope-and-nfrs.md#7-open-questions)). Boosts were built timed
([§8](#boosts-designed-2026-09-30-plan-item-16), Q-13, D-38): a percent for some minutes, applied by `worker` at a
match's end to whatever was running. Nothing is taken at a ticket, so nothing is given back when a player never joins.

## 4. Matchmaking

Matchmaking turns players who ask for a timed match into a **made match**: a room for one match, with a fixed roster
(see the [glossary](../glossary.md#2-play)). This section starts with the first sketch, then gives each slice as it was
designed and built: the duel, parties and team-vs-team, the confirm step, ranked free-for-all, co-op, team matches, the
sandbox, and the rule that a player holds one room at a time.

**The first sketch.** It runs every second on the scheduler thread.

- Queues in j-redis: `ZADD mmq:{mode}:{bucket} enqueuedAtMs partyId`, with the party's details in
  `HSET mmp:{partyId}`. Ranked modes bucket players by rating band (±100 at first, widening by ±50 for every 10 s
  waited); public modes use one bucket.
- Per mode: take the waiting parties in order of wait time, fill teams greedily while respecting party sizes, and emit
  a match when a full lineup exists, or one "good enough after 30 s". The algorithm's complexity does not matter at a
  few hundred in the queue.
- The result: `rooms.allocate(mode, roster)` → tickets → `evt.match.found` pushed to each player, with
  `{arenaHost, arenaPort, ticketId, tls}`. That is the same grant that `POST /v1/match-requests` returns
  ([02 §1](02-networking.md#1-transport)). As built, the grant also carries `mode`, and `tlsName` behind the front
  (D-82).
- Players who do not confirm within 20 s are dropped (public modes), or the match is cancelled and its players queued
  again (ranked).

**Superseded by the slices below:**

- there is one queue per mode, with no rating buckets: the rating window widens with each entry's wait instead;
- the public arena is never queued for;
- every match found asks its players to confirm within 10 s, whatever its mode (the third slice).

**Matchmaking state lives only in j-redis, never in MySQL.** It is short-lived by nature: if the store is lost,
everyone queues again and nothing of value is gone.

### The first slice: a ranked duel, end to end (designed 2026-09-27, plan item 6)

The sketch above is the whole of matchmaking. The first slice builds every part that a structured mode needs, and
proves them on one mode, the **duel**: a queue, a matcher, a room made for the match, a push that tells the player,
and a result that moves a rating. Every later mode is a row in a table and a rule on the same path. The duel comes
first because its roster is two, which a test can drive, and because the rating it moves already had a column
(`player.rating_duel`).

**The public arena does not change.** `POST /v1/match-requests` and the lobby's `match.request` still grant a seat
in an open room at once (D-15). The queue is for timed matches only.

#### Modes

A mode is one definition, `handoff/MatchMode`, read by all three processes that need it: `platform` for the roster,
the arena for the rules, and `worker` for how the result is scored. It is one table in code, as the content tables
are, so a release carries one version of it.

| id | Mode | Lifecycle | Roster | Rules |
|---|---|---|---|---|
| 0 | `ffa` | open | none | the public arena, as now |
| 1 | `duel` | timed | 2 | map 2 000, 40 shapes, 3 minutes; the first to 3 kills wins; at the whistle, the one with more kills, else a draw; rated |
| 2 | `tvt` | timed | 6, teams of 3 | map 3 000, 80 shapes, 5 minutes; the first team to 10 kills wins; at the whistle, the team with more, else a draw; rated ([01 §8.4](01-arena.md#84-team-vs-team)). Queued alone or as a party |

The id is the `mode` that the Welcome message, the result and `matches.mode` already carry (always 0 until then). The
later slices add modes 3 to 9 (§5 lists them all).

#### The queue

| Where | What |
|---|---|
| `POST /v1/queue` `{"mode": "duel"}` | joins → 200 `{"state": "queued"}`; 409 `already_queued` or `in_match`; 400 for a mode that has no queue. In a party (the second slice), the leader queues the whole party; 409 `in_party` for a member, `party_changed` if the party changed meanwhile; 400 `party_too_big` |
| `DELETE /v1/queue` | leaves → 200, whether or not the player was queued |
| `GET /v1/queue` | `{"state": "none" \| "queued" \| "matched", "mode", "waitedSeconds", "grant"}`. `grant` is there when matched: the same object `evt.match.found` carries. The third slice adds `confirming` |
| lobby `queue.join` `{"mode"}` / `queue.leave` | forwarded to the routes above, as `match.request` is; answered `queue.join.ok` / `queue.leave.ok` with the same body, or `error` with the same code |
| push `evt.match.found` | `{arenaHost, arenaPort, ticketId, tls, mode}`, and `tlsName` behind the front (D-82) |
| push `evt.queue.update` | `{state, mode}`: to a party's members, when the leader queued them or someone took them out (the second slice) |

In the store (the `session` instance, [D-7](../architecture/03-decision-log.md)):

```
ZADD mmq:{mode} enqueuedAtMs playerId                      the queue, oldest first
HSET mmp:{playerId} mode, since, rating, name, state [, grant…]  EX 900; EX 60 once matched
```

**A player is in one queue, or holds one match's grant, at a time.** `mmp:{playerId}` decides this: a join finds it
and is refused, `already_queued` or `in_match`.

- Once matched, the entry lives as long as the ticket, 60 s. So a player can queue again when that match's ticket is
  spent or gone.
- The entry's expiry is the backstop that returns a stuck player to "none".
- Leaving after being matched forgets the grant: that player does not go, and the other wins by walkover.
- The name and rating are read from MySQL once, at the join, so the matcher works from the store alone.

**Being matched requires being in the lobby.** The matcher skips, and drops, a queued player who has no
`conn:{playerId}` (03 §4). The push is how a match is announced, and a player whose lobby has gone would hold an
opponent in a room for nothing. **Missing the push is not fatal**: `GET /v1/queue` answers `matched` with the grant for
as long as the ticket lives. That is what a client asks after reconnecting (03 §7: what must not be lost is fetched,
not pushed).

#### The matcher

**One matcher runs per deployment at a time**, in whichever `platform` holds the lease:
`SET mm:leader {instance} NX PX 5000`, renewed every second by the holder, on a thread of its own. `{instance}` names
the platform's host, its address and port, and its
process. Each second, for each queued mode:

1. Read the queue, oldest first. Drop anyone whose `mmp` is gone or not `queued`, or who has no lobby connection.
2. **Pair by rating**, oldest first. Each player takes the closest-rated other player inside its window: ±100,
   widening by 50 for every 10 s waited. The window of whichever of the two has waited longer applies. Unpaired
   players wait for the next second.
3. For a pair: take both out of the queue, with one `ZREM` each in one transaction, so the matcher knows exactly whom
   it took. **If it took fewer than two**, another matcher took the rest (a lease that lapsed during a slow second).
   It puts back what it took, and only that, and goes on. A player is never in two matches.
4. Pick an arena with a free room (below). Make a `matchUid` (a ULID, as the arena makes them). Issue one ticket per
   player, carrying the `matchUid`, the mode and a team (0 and 1). Write `state matched` and the grant into `mmp`. Push
   `evt.match.found` to each player.
5. **If no arena has a free room**, put the pair back with their original times. They are first in line the next
   second.

#### Rooms, made by the first ticket

**The arena creates a match's room when the first ticket that names its `matchUid` is claimed.** The second ticket
finds the room by that `matchUid`
([D-20](../architecture/03-decision-log.md#d-20--a-matchs-room-is-made-by-its-first-ticket-not-by-a-command)).
Nothing else tells the arena that a match exists. The mode sets the room's size and rules, and the room takes no one
else: an open join never lands in it, and neither does a ticket for another match.

**Choosing the arena.** An arena's announcement gains `rooms` and `maxRooms`, and the matcher picks only an arena
with a free room.

- The directory can be 3 s stale, so two matches can be sent to the last room of one arena. Then the second match's
  first claim is refused with `Kick(2)`, no room, and both its players queue again, as for any full arena.
- **Since 2026-09-30 a room is promised when its arena is chosen**
  ([D-42](../architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)).
  The match's id goes into `rooms:promised:{arena}` for the ticket's 60 s, and each choice counts an arena's promises
  against its free rooms. So matches chosen before the arena announces again are not sent to the same last room.
- The arena drops a promise in the write that first announces that match's room; from then on the room counts itself.
- The `Kick(2)` refusal remains for a room taken some other way, for example by the arena's own open rooms between an
  announcement and a claim.

**A match's room goes through three stages:**

| Stage | Until | What happens |
|---|---|---|
| waiting | the whole roster has joined, or 30 s have passed since the room was made | tanks can move and shoot shapes; nothing counts |
| playing | a win, or 3 minutes | the world resets, both tanks at level 1; the clock and the tally start |
| over | — | the result is published; each player is sent `Kick(6)`, match over, and the room is closed |

**A walkover**: if the join window ends with one player in the room, that player wins at once.

- **Unrated**: a side that never arrived may have failed to connect through no fault of its own, and a rating should
  not move on that.
- **Unpaid**: its winner played no time, which the reward rules' five-second minimum already pays nothing for. Paying
  it would pay two accounts for queueing against each other and one not going.
- **Not counted**: since 2026-10-04 the same minimum keeps a result out of the stats, achievements, daily goals and the
  pass as well (`MatchResultRepository.COUNTED_FROM_SECONDS`, defect
  [D-45](../defects.md#3-data-and-the-result-pipeline), the owner's
  [D-76](../architecture/03-decision-log.md#d-76--a-result-under-five-seconds-counts-for-nothing-but-its-row-and-a-rated-matchs-rating-a-walkover-is-no-win)).
  So a walkover is no win to farm either.
- It is still recorded, and it decides a tournament's match. If nobody arrives at all, there is no result.

A player who loses the connection mid-match has the ordinary minute to resume (02 §10). One who does not come back
loses on the clock, as their kills say. `Kick(6)` is new: a client returns to the lobby, as after `Leave`. It is not an
error, and it is not a reason to reconnect.

#### The result, and the rating

The result is the timed one (D-15): `kind` timed, `mode` 1, placement 1 for the winner and 2 for the loser, both 1
for a draw. **A duel with one player is a walkover** and moves no rating. Otherwise `worker` moves both ratings by Elo,
inside the transaction that already locks both players:

```
E = 1 / (1 + 10^((R_other − R_self) / 400))       S = 1 win, ½ draw, 0 loss
ΔR = round(K × (S − E))                           K = 32, then 16 after 30 rated duels
```

- The count of rated duels is a new column, `player.rated_duels` (V4).
- The ratings come from the rows the transaction has locked, not from the result. The result was written minutes
  before, and another duel may have moved either rating since.
- `match_player.rating_delta` records what was applied.

#### As built (2026-09-27)

**Built so far: the push route, the queue and the matcher.** `handoff/LobbyPush`, `MatchMode`, the match on `Ticket`,
and `ArenaDirectory.pickForMatch` (since replaced by `reserveForMatch`, which promises the room, D-42);
`platform/MatchQueue`, `Matchmaker` and `QueueService`; the gateway's `queue.join` and `queue.leave`, and its push
channel.

- The matcher runs on a thread of its own in every `platform`: a round a second, with a fixed delay between rounds. A
  round that fails is logged, and the next round follows.
- Metric: `backend_platform_matches_made_total{mode}`. The wait-time histogram of §11 was built later (2026-09-29).

**The arena's side is built** (`arena/RoomRegistry.allocateMatch`, the `MADE` lifecycle in `RoomThread`,
[01 §8.1](01-arena.md#81-the-mode-decides-the-lifecycle)): rooms made by the first ticket, the stages, the duel's
rules, the walkover, `Kick(6)`, and `rooms`/`maxRooms` in the announcement. That last part is what lets the matcher
send a match at all. An arena from before it announces neither field, and is never sent a match.

**The rating is built** (`worker/DuelRating`, since renamed `EloRating` when it took a whole field; applied in
`persistence/MatchResultRepository` under the lock; V4). A walkover and a redelivery move nothing. On a redelivery the
deltas are worked out again, since only the insert can tell a redelivery, and none is applied.

#### Not in this slice

- Parties, and teams queueing together. (§2 was not built then: parties came with the second slice, and teams'
  matches with the sixth.)
- A confirm step: in this slice the join is the confirmation, and the join window its timeout.
- Every other queued mode.
- A penalty for not arriving.
- Tournaments.

### The second slice: parties and team-vs-team (designed 2026-09-28, plan item 6)

The plan's next step is parties. A party has nothing to do without a mode with teams, since a duel is one against
one. So this slice builds parties and the first team mode together: **team-vs-team**, three against three (`tvt`,
mode 2), with team rules the simulation had never had
([01 §8.3](01-arena.md#83-team-rules-designed-2026-09-28-plan-item-6), [§8.4](01-arena.md#84-team-vs-team)). Teams
(§2) are not parties, and stay with the meta layer. A confirm step and the other modes come after.

#### Parties

A **party** is a few players who queue together: at most three, the largest team any queued mode has. It is made by
an invitation, and it lives in the store, not in MySQL
([D-25](../architecture/03-decision-log.md#d-25--a-party-lives-in-the-session-store-and-only-its-leader-queues)).
Like the queue, it is worth nothing once its players have gone.

| Where | What |
|---|---|
| lobby `party.invite` `{"playerId"}` | invites a player who is in no party, pushed if they are in the lobby. If the inviter has no party, one is made, with the inviter as its leader. 409 `in_party` (the invitee has one), `party_full`, `not_leader`; 404 `not_in_lobby`, to a friend of the invitee only (S-16); 429 `too_soon` past 60 invitations an hour |
| lobby `party.accept` `{"partyId"}` | joins, while the invitation lives (60 s) and the party is not full, not queued and not asked about a match; 404 `no_invitation`, 409 `party_full` or `queued` |
| lobby `party.leave` | leaves. A leader who leaves hands the party to the member who has been in it longest, and a party of one is no party |
| lobby `party.kick` `{"playerId"}` | the leader removes a member |
| push `evt.party.invite` | `{partyId, from, fromName}`, to the invitee |
| push `evt.party.update` | `{partyId, leader, members: [{playerId, name}], version}`, to every member, on every change. For a player now in no party: `partyId` null, `members` empty, and `was` the party it ended, with that change's `version` |
| lobby `party.say` `{"phraseId"}` | a phrase to the party ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)); 400 `unknown_phrase` for an id not in the list, 404 `no_party`, 429 `too_soon` within two seconds of the last. **Built 2026-09-29**, plan item 8's second slice |
| push `evt.party.said` | `{from, name, phraseId}`, to every member, the speaker included |

The gateway forwards each of these to `platform` (`POST /v1/party/invite`, `/accept`, `/leave`, `/kick`, `/say`;
`GET /v1/party`), as it forwards `queue.join`. In the store:

```
HSET party:{partyId} leader, members, n:{playerId}…, v     EX 3600, renewed on every change
SET  partyOf:{playerId} partyId                     EX 3600
SET  pinv:{playerId}:{partyId} 1                    EX 60, one invitation per party
SET  rl:say:{playerId} 1                            NX PX 2000, a phrase's turn
```

**A phrase to the party** is the lobby's half of fixed-phrase chat. Members wait together in the lobby and in the
queue, where no match carries what they say.

- The rate is the arena's: one phrase every two seconds, kept by a key that lapses.
- Only a phrase in the list, said by a member of a party, takes a turn.
- A refusal comes with a code rather than being dropped, since HTTP answers every request. The lobby stays open.
- The speaker hears the phrase too, as in a match.

**Built 2026-09-29** (`platform/Parties`, `PartyService`, `/v1/party`; the gateway's `party.*`).

- Each change reads what it depends on under `WATCH` and writes in one `MULTI`. If any of it changed meanwhile, it
  retries, up to five times. So when two players accept the last place at once, one gets in and the other is told
  `party_full`.
- An invitee must be connected to the lobby, since the invitation is a push.
- A name is read from MySQL once, when its player invites or joins, and kept with the party.
- The gateway sends `platform` only the field a message takes, not the rest of `d`.
- The `queued` refusal came with the queue of parties (below).

**A party's state is versioned** (designed 2026-10-04, plan item 78, T-45;
[D-74](../architecture/03-decision-log.md#d-74--a-partys-state-is-sent-whole-with-its-version-and-the-client-keeps-the-newest)).

- **The problem.** Each state is sent whole: as an answer to the member who asked, and as a push to every member. The
  two travel by different paths: the answer through the gateway's call to `platform`, the push through the store. Two
  changes at once (two players accepting), or a push overtaking an answer, could deliver an older state after a newer
  one. A member then saw the party as it had been, until it changed again.
- **The version.** `v` is the party's version: 1 when it is made, and one more on each change of its members or
  leader, written in the change's own transaction. Every state carries it: an answer, a push, `GET /v1/party`.
- **A state of no party.** When a change ends the party for a player, the state it sends carries the ended party as
  `was`, with that change's version. A state with no `was` (`GET /v1/party` for a player in no party) is always
  applied.
- **The client's rule.** A client applies a state only when it names another party than the last one it applied, or a
  higher version of the same party. A state with no version, from a `platform` not yet upgraded during a rolling
  deploy, is applied as it comes, as before.
- An invitation changes no member, and so no version.
- **Not covered:** the state of a party left behind arriving after the state of the next party joined. That needs a
  player to leave and join within a push's few milliseconds, and the next change, or `GET /v1/party`, puts it right.

**Only the leader queues**, and queues the whole party. A member who asks is refused 409 `in_party`.

- **A party is one entry in the queue.** `mmq:{mode}` holds the leader. The leader's `mmp` names every member with
  their rating and name. Each member's `mmp` says `queued` and names the leader, so a member's own join is refused as
  it would be for a player alone.
- **Leaving is the party's.** A member who leaves the queue, or the party, takes the party out of the queue, and all
  its members are told.
- A party bigger than a mode's team cannot queue for it: 400 `party_too_big` (for example, a party of two for a duel).

In the store, the entry is the leader's, and a player alone is an entry of one:

```
ZADD mmq:{mode} since leaderId
HSET mmp:{leaderId} mode, since, state, rating, name                       a player alone
HSET mmp:{leaderId} mode, since, state, members, r:{id}, n:{id} …           a party's names every member
HSET mmp:{memberId} mode, since, state, leader                                     one per other member
```

**The join.** When the leader queues, `platform` reads each member's name and the mode's rating from MySQL, as it does
for a player alone. The join writes every member's `mmp` and the queue in one transaction, watching every `mmp` and
`party:`. Inside it, the join checks that the party is still the one it read. A member who left meanwhile fails the
join with 409 `party_changed`, rather than queueing a party that is no longer one. Any member whose `mmp` is already
queued or matched refuses it, as `already_queued` or `in_match`.

**Every member is told**, since only the leader asked:

- `evt.queue.update` `{state, mode}` to each other member, when the party is queued;
- `{"state": "none"}` to every member taken out, whoever caused it: a member leaving the queue, a member leaving or
  being removed from the party, or the matcher dropping the party because a member has no lobby connection.

`GET /v1/queue` answers each member as it answers a player alone. It is the truth, and the push is a notification
([03 §7](03-gateway.md#7-what-must-not-be-lost)).

**Once matched, each member's match is their own.** A member who then leaves the party or the queue forgets their own
match, as a player alone does, and the rest still go. A member who leaves the party while its entry is being asked
about a match takes the entry out of that match (the third slice, "Each step one transaction").

**An untested race.** The join's watch on `party:` guards a narrower race of the same kind: a member leaving between
the join's reading of the party and its writing. No test drives that race. Its mutation, the watch removed, survives.
The harm it would let through: a member queued, and so matched, with the party they had just left, and able to leave
that match as anyone can.

#### The matcher, with teams

A duel's pairing is team-filling with teams of one, so the matcher becomes one rule for every queued mode. Each
second, for each mode, oldest first:

1. Take the oldest entry (a party or a player alone), and up to ten entries whose mean rating is inside its window
   (±100, widening by 50 for every 10 s the oldest has waited).
2. **Find two teams of the mode's team size**: the oldest entry in the first team, no party split, with the least
   difference between the teams' mean ratings. If nothing fills both teams, the entries wait.
3. The search is over at most ten entries: a few thousand ways, once a second. For a duel it takes the closest-rated
   other player, as before.

**Which ten.** They are the ten closest in mean rating to the oldest entry's, the older first on a tie, and all younger
than it. An older entry that found no match this round has had its own turn, with a window as wide or wider. An entry
that fills no match leaves its turn to the next oldest. Between two splits that are equally balanced, the first found
stands, which takes the closer-rated entries first.

The rest is the first slice's: take the entries out of the queue in one transaction, put back what was taken if
another matcher took any, pick an arena, and issue the tickets: **team 1 and team 2** for a team mode (a duel's stay 0
and 1).

**Built 2026-09-29** (`Matchmaker.lineups`, `MatchQueue`, `QueueService`), with the queue of parties above. The ten are
chosen in one pass that keeps the best so far. A first version sorted every entry in the window for each oldest
entry, and a queue of 5 000 took 5.3 s a round. Measured after the change, warm, on the loaded development VM (the
figures vary between runs by a factor of about two):

| Queue | A round |
|---|---|
| 2 000 players alone, team-vs-team | 58 ms |
| 2 000 parties of two, which fill nothing | 73 ms |
| 5 000 entries of one to three | 100 to 180 ms |
| a duel queue of 2 000 | 12 ms |

The duel's matches are the ones its pairing made before.

#### The rating

`player.rating_tvt` and `player.rated_tvts` (V5), as for the duel. **Each team is rated as its players' mean**, and
each player moves by their own K
([D-26](../architecture/03-decision-log.md#d-26--a-team-is-rated-by-its-players-mean-each-moved-by-their-own-k)):

```
E = 1 / (1 + 10^((mean_other − mean_own) / 400))    S = 1 win, ½ draw, 0 loss
ΔR_player = round(K_player × (S − E))                K = 32, then 16 after 30 rated matches
```

A walkover moves nothing and pays nothing, as a duel's does.

**Built 2026-09-28** (V5; `MatchResultRepository`). The ratings and counts of both kinds are read under the lock. A
team match's deltas are worked out from each team's mean by the duel's rule, with each player's own count, and written
to the team-vs-team rating. A result with one team's players only is a walkover. `persistence` does not depend on the
mode table, so it names the two rated modes' ids itself, and a test in `worker` holds that they agree.

#### Built in this order

Each part was committed with its tests and mutation checks:

- (a) the team rules in the simulation;
- (b) the mode, its room's rules and its tally;
- (c) the rating, V5;
- (d) parties;
- (e) the queue of parties, and the matcher with teams;
- (f) the client core, and a live drill of six players, a party of three among them.

**All six were built by 2026-09-29.**

### The third slice: a confirm step (designed 2026-09-29, plan item 6)

Until this slice, the join was the confirmation, and the join window its timeout. A player who was matched but did
not come cost the others 30 s, and then a walkover in a duel, or a rated match played short-handed in team-vs-team.
So a match found now **asks every player first**, and makes a room only for those who answer
([D-27](../architecture/03-decision-log.md#d-27--a-match-found-asks-every-player-before-it-is-made); whether to have
the step at all, and its two numbers, is [Q-5](../requirements/01-scope-and-nfrs.md#7-open-questions)).

| Where | What |
|---|---|
| push `evt.match.ready` | `{matchUid, mode, seconds}`: a match is found; accept within `seconds`, which is 10 |
| lobby `match.accept` / `match.decline` `{"matchUid"}` | forwarded to `POST /v1/queue/accept` / `/decline`; 409 `not_confirming` for a match the player is not asked about, or no longer |
| `GET /v1/queue` | `confirming`, with `matchUid` and `secondsLeft`, for a client that missed the push |
| `POST /v1/queue` | 409 `queue_locked` for a player who is locked out, or a party with one |

**Every player answers for themselves**, a party's members too.

- **When all have accepted**, the matcher's next round (within a second) picks an arena and issues the tickets, as it
  did at once before: `evt.match.found`.
- **When one declines, or ten seconds pass without all accepting, the match is off.** Each entry with a player who
  declined, or who had not answered when the ten seconds were up, is out of the queue. That player, not their party
  members, may not queue for 60 s.
- A player who had not yet answered when another declined was not silent, and goes back to the queue.
- Every other entry goes back to the queue where it was, with its waiting time kept, and its players are told
  `evt.queue.update` `{"state": "queued"}`.
- Leaving the queue while asked counts as declining.
- With every player accepted but no arena room free, the entries go back first in line, as a match found without a
  room did before.

In the store:

```
HSET mmc:{matchUid} mode, deadline, lineup          EX 60
ZADD mmc deadline matchUid                         the matches waiting for answers
HSET mmp:{playerId} state confirming, matchUid, deadline [, answer]   EX 60; the rest as when queued
SET  mmlock:{playerId} 1                           EX 60
```

- `lineup` is each entry of the match with its side, its time queued and its members. So a match that is off is put
  back as it was taken.
- An answer is written to the player's own `mmp`, under a watch on that key alone, and only while the player is
  `confirming` for that match. An answer to a match already off, or already made, is `not_confirming`.
- Asking clears any answer to an earlier match.
- The matcher alone makes and ends these records, under its lease. It reads `mmc` first in each round: each match is
  made, off, or still waiting.

#### Built in this order

- (a) the store and the matcher: asking, answers, the match made or off, the lock;
- (b) the API and the lobby;
- (c) the client core, and a live drill: a duel accepted by both players, and one declined.

**(a) and (b) built 2026-09-29.** `MatchQueue.ask`, `answer`, `pending`, `close`, `requeue` and `lock` (the last
three since folded into `make` and `end`, each one watched transaction, below); `Matchmaker.settle`;
`POST /v1/queue/accept` and `/decline`; the gateway's `match.accept` and `match.decline`.

- A round settles the matches waiting for answers before it finds more.
- Whichever matcher removes a match from `mmc` first ends its wait. So two matchers under a lapsed lease cannot both
  make it.
- The first version counted a player who had not yet answered as silent when another declined. A test caught it,
  since their ten seconds were not up.
- The first version also wrote the answers into `mmc:`, watched. The live drill of six found the flaw: answering
  together, the players overtook one another, and the sixth ran out of retries and was answered 503
  ([T-16](../defects.md#4-concurrency)). Each answer is now the player's own.

#### Each step one transaction (designed 2026-10-02, plan item 56)

**The problem.** The second audit found the confirm step writing on what it had read a moment before
([T-32, T-33, T-34](../defects.md#4-concurrency)):

- a player who left between the matcher's read and its asking was asked all the same;
- a player who declined between the matcher's last read and its making was matched;
- a party that changed while asked went back to the queue as it had been;
- a matcher that failed between ending a match's wait and writing its tickets left its players asked for fifteen
  minutes.

**The fix: each step that moves a player on is one transaction.** It reads the records it decides by under `WATCH`,
and writes them all in one `MULTI`, or writes nothing if one of them changed meanwhile
([D-55](../architecture/03-decision-log.md#d-55--each-step-of-the-confirm-step-is-one-watched-transaction)):

| Step | Reads, watched | Writes, all or none |
|---|---|---|
| asking | each member's `mmp`: still `queued`, since the time the round read it | the entries out of `mmq`; the match's `mmc:` and its place in `mmc`; each member `confirming`, for the match's 60 s |
| making | each player's `mmp`: `confirming` this match, and `accept` | the match's wait ended; each player `matched`, with their grant, for a ticket's life |
| calling off, or no room | `mmc:{matchUid}`, still there; each player's answer as it is now | the match's wait ended; each entry with a player who declined, withdrew, or was silent at the deadline (and not called to a tournament, Q-44) forgotten, and those who declined or were silent locked out; the rest back in `mmq`, `queued`, for the queue's 900 s |
| joining | each member's `mmp` and `mmlock` | as before; a lock refuses it, `queue_locked` |

A step that loses writes nothing. The next round, a second later, reads the queue or the match again as it is then.
Whichever matcher ends a match's wait first is the one to act on it, as before.

**The tickets come before the match is made** (T-34).

1. The room is promised and every ticket is written first.
2. Then the making's transaction runs.
3. Nobody is told a ticket until that has committed. Then each player is told `evt.match.found`.

A failure before then, in the store or in `platform`, leaves the match waiting and its players asked. The next round
makes it with new tickets. The tickets written before are known to nobody, and lapse in 60 s. A making that loses to
a change gives the room's promise back.

**An asked player's record lasts as long as the match's wait**: 60 s from the asking, as `mmc:` does, and the queue's
900 s again when its entry goes back. So a matcher that cannot finish leaves its players asked for a minute at most.
Then they are in no queue, and may join again. They are not told: `GET /v1/queue` answers `none`, and the push is a
notification ([03 §7](03-gateway.md#7-what-must-not-be-lost)).

**A party that changes while asked is out of the match** (T-32).

- A member who leaves the party, or is removed from it, has their answer written `withdrawn`, unless they had
  declined. An accept or a decline cannot change it afterwards (`not_confirming`).
- The match is off at the next round, as for a decline. The entry is out, every one of its players is told `none`,
  and nobody is locked out for it: leaving a party is not refusing a match. The other entries go back.
- A party being asked takes no newcomer, as a queued party takes none: `party.accept` answers 409 `queued` while its
  leader is `queued` or `confirming`. A matched party may take one, since each member's match is their own by then.

**Leaving while asked** counts as declining, unless the answer is already `withdrawn`. The leave is written under a
watch on the player's own record. So a leave that lands as the matcher asks is either a leave of the queue before the
asking or a decline after it, never lost between the two. `confirms_total` gains the outcome `withdrawn`.

**An untested race.** The join's watch on the locks guards a race that no test drives: a call-off landing between the
join's reading and its writing. Its mutation (the locks read but not watched) survives, as the party's watch above
does.

### The fourth slice: ranked free-for-all (designed 2026-09-29, plan item 6)

**`rffa`, mode 3**: eight players, each for themselves, rated. Its numbers are the user's to settle
([Q-6](../requirements/01-scope-and-nfrs.md#7-open-questions)); meanwhile it is built on these:

| | |
|---|---|
| roster | 8, matched by rating as the duel is; no parties (a party is bigger than a team of one) |
| map, shapes | 3 500, 120 |
| length | 4 minutes; no kill ends it |
| placement | by score at the whistle, equal scores sharing a place (the tally already places a mode without a kill target this way) |
| rating | pairwise ([D-28](../architecture/03-decision-log.md#d-28--a-free-for-all-is-rated-pairwise-each-player-against-each-other)): each player against each other by placement, with K shared over the field |

**The matcher, for more than two sides.** A mode has `roster / teamSize` sides. The duel and team-vs-team have two,
and are matched as before. With more sides, each side is one player: the oldest entry and the seven closest younger
ones inside its window are the match. With fewer than seven there, it waits. The lineup becomes a list of sides. The
confirm step asks all eight, and any one of them declining calls the match off, as it does for two.

**Teams in a mode without teams are 0**, for every player. A duel's two teams used to be 0 and 1, the side's index,
which put the second player in the left third at spawn, as a team 1 is placed (01 §8.4). Both are 0 now, and spawn
anywhere, as in the public arena.

**The rating.** `player.rating_rffa` and `player.rated_rffas` (V6). For a result of N players placed, each player
*i* moves by:

```
S_ij = 1 placed above j, ½ equal, 0 below      E_ij = 1 / (1 + 10^((R_j − R_i) / 400))
ΔR_i = round(K_i × Σ_j≠i (S_ij − E_ij) / (N − 1))      K as the duel's: 32, then 16 after 30
```

With two players it is the duel's rule. A match that only one player came to is a walkover: unrated and unpaid, as in
the other modes. A player who left early is in the result, placed by the score they had, and rated so.

#### Built in this order

- (a) the mode and its rules, and teams 0;
- (b) the matcher for more than two sides;
- (c) the rating, V6;
- (d) the client, and a live drill of eight players.

**(a) to (c) built 2026-09-29.** There is one rule for every rated shape now: `worker/EloRating` takes placements, not
scores. A duel is two players, a team match two teams at their means, a free-for-all the whole field. A result naming
a player who no longer exists rates the rest. Counting a player against themselves would add ½ − ½, so that mutant is
equivalent, and the check stays for the formula's sake.

### The fifth slice: co-op waves (designed 2026-09-29, plan item 6)

**`coop`, mode 4**: three players, one team, against waves of the arena's own tanks (the MvM row of
[01 §8.2](01-arena.md#82-the-interface)). It is the last queued mode the plan names. Its rules are a product's, and
open with the user ([Q-7](../requirements/01-scope-and-nfrs.md#7-open-questions)). This first slice is built on the
smallest set that plays:

| | |
|---|---|
| players | one team of three: a party of up to three, filled from the queue in the order it waited; not rated, so not matched by rating |
| map, shapes, length | 3 000, 60, ten minutes at most |
| waves | ten. Wave *w* is 2 + *w* tanks of level 5*w* (45 at most), classes as a grown bot takes them, in the right third; the players start in the left |
| the arena's tanks | **hunt**: each turns to the nearest player's tank, closes to 400 units, and fires within 600 |
| between waves | the next wave starts 5 s after the last tank of the one before dies; the first, 5 s after the match starts |
| the dead | come back at the start of the next wave; no respawn on request in co-op |
| the end | the tenth wave cleared, every player dead at once (a wipe), or ten minutes |
| result | one team, every player placed first; unrated. Paid by the rule every match is, with the arena's tanks' experience in each killer's score. Waves cleared are logged, and will reach the result when a reward is set on them |

**The matcher for one side** fills a team of three from the oldest entry and the next ones, in the order they waited,
with no party split. An unrated mode has no window. A player alone, or a party of two, waits for others; a party of
three may play at once.

#### Built in this order

- (a) the hunting tank in the simulation;
- (b) the mode and its waves in the arena;
- (c) the matcher for one side, and queueing for an unrated mode;
- (d) the client, and a live drill: a party of two and one player alone, and the waves.

**(a) to (c) built 2026-09-29.** For one side, the matcher fills the oldest entry's team from the next entries that
fit, whole, and skips a party that would overfill it. An unrated mode's players are queued at rating 0, so no rating
is read from MySQL, and every gap is 0. `MatchMode.COOP` is mode 4, not rated.

### The sixth slice: team matches (designed 2026-09-30, plan item 19)

On [Q-18](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation: two **teams** (§2), not two groups
of whoever queued, play each other, and the team is rated.

| | |
|---|---|
| mode | **`teams`, mode 5**: team-vs-team's rules (01 §8.4): three against three, the first side to ten kills, five minutes, 30 s to come; team-vs-team's map and shapes |
| who queues | a party of exactly three, every member in one team, queued by the party's leader, who must be that team's leader or a vice leader |
| refused | 400 `party_too_small` (fewer than three, a player alone too); 409 `not_one_team` (a member in no team, or in another); 403 `not_allowed` (the party's leader is only a member). Checked when queueing: a member who leaves the team afterwards is the result's to handle (below) |
| matched | each entry is a whole side, at the team's rating: every member is queued at it, so the entry's mean is the team's. The window is the matcher's. **Two entries of one team are never matched** |
| rating | `team.rating`, 1 200 to start: the duel's Elo between the two teams, K 32, then 16 after 30 rated team matches (`team.rated_matches`); a draw is half. Wins, losses and draws are counted on the team |
| the players | paid as in team-vs-team. Their own ratings do not move, since `rating_tvt` is team-vs-team's, played with whoever |
| a walkover | one side only: nothing moves and nothing is paid, as in every mode |

**The entry carries its team's id**: `team`, in the leader's `mmp`. The confirm step leaves it in place, so an entry
queued again after a match is declined still has it, and the matcher can pass over a candidate from the oldest entry's
team. Nothing carries it further: the ticket, the arena and the result name sides, and `worker` finds each side's
team when it applies the result
([D-43](../architecture/03-decision-log.md#d-43--a-team-matchs-sides-are-known-by-their-players-teams-when-the-result-is-applied)).

- A side's team is the one its players are in at that moment, passing over any who left or were kicked (who are in no
  team for 24 hours).
- A side in two teams or in none, or both sides in one team, is applied for the players only.

**In the result's one transaction** (06 §5): the players' teams are read, the two teams locked, then the players (the
order D-39 sets for team actions), and the teams read again. The teams' ratings, counts and a `match_team` row each
are written. The result is applied once, so the team's part is too.

`GET /v1/teams/mine` gains the team's `rating`, `wins`, `losses` and `draws`.

**Not in this slice:** streak trophies, team leaderboards (built later: §7, the board of teams), a team's match
history through the API, and matches of five a side.

#### Built in this order

- (a) V12, the mode, the team's entry in the queue, and the matcher's rule (`persistence`, `handoff`, `platform`);
- (b) the team's rating and record in the result's transaction, retention, and the team's view (`persistence`,
  `platform`);
- (c) the client, and a live drill: two teams of three, each queued by its leader, matched, and played out.

**(a) built 2026-09-30:**

- V12 and `MatchMode.TEAMS`.
- `TeamRepository.sideOf`: one query for the party's team, its rating and the queuer's role.
- `QueueService`'s three refusals, and every member queued at the team's rating.
- The entry's `team` in its leader's `mmp`, read back by the matcher, which passes over a candidate from the oldest
  entry's team.
- From this part on, a team match's result moves no player's rating; the teams' ratings came with (b).

**(b) built 2026-09-30.** In the result's transaction (`MatchResultRepository`): the players' teams are read and those
teams locked before the players, then read again under the locks; each side's team is the one its players still
share. Then the duel's Elo between the two teams; a `match_team` row per side, whose key makes a redelivery change
nothing; and the team's rating and counts. These move nothing for the teams: a walkover, a result cut short (and so
unrated), a side in no team or in two, and one team on both sides. Retention deletes `match_team` with the rest of a
match. `GET /v1/teams/mine` and `TeamRepository.teamOf` carry the rating and the counts.

**(c) done 2026-09-30**, and drilled from the release (the drill's `teammatch` scenario): two teams, a party for each;
the party led by a member refused until that member was promoted; one match, each team a side; a draw, counted by
both.

### The seventh slice: the sandbox (designed 2026-10-01, plan item 33)

On [Q-32](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation, and by
[D-49](../architecture/03-decision-log.md#d-49--a-sandbox-is-a-made-room-that-publishes-nothing-opened-by-a-request-its-powers-one-message):
a **sandbox** is a private room, made at once, never queued for.

| | |
|---|---|
| `POST /v1/sandbox` | opens one → 200, the grant: the same object `evt.match.found` carries, with `mode` `sandbox` |
| who goes | the caller; or, for a party's leader, the whole party. Each is sent `evt.match.found`, the caller too, and recorded as matched, so `GET /v1/queue` answers with the grant, as after a missed push, its `waitedSeconds` counted from the opening |
| refused | 409 `in_party` for a member who is not the leader, as the queue does; 409 `already_queued` when the caller or a member is queued or being asked; 409 `in_match` when one is matched already; 409 `in_sandbox` when one holds a sandbox (below, D-54); 503 `no_room` when no arena has a room free |
| the tickets | one each, team 0, naming the match and `sandbox`, with what the player wears, as the queue's tickets do |
| afterwards | nothing: the arena publishes no result (01 §8.10), so `worker` never hears of it |

A matched player is refused a queue, and a queued player is refused a sandbox, so a player holds one of the two at a
time. Being matched lasts as long as a ticket. After that, a player may queue again from inside the sandbox, as from
inside any match.

**Built 2026-10-01** in `QueueService.openSandbox`, which already had the queue's rules for a party.
`MatchQueue.matched` now records the mode with the grant. A queued player's record had the mode from the queue; a
sandbox's player has none, and `GET /v1/queue` would have answered a sandbox's grant with no mode.

### One room at a time (designed 2026-10-01, plan item 52)

The second audit found S-13 and T-38 ([defects](../defects.md)). "A player holds one of the two at a time" held for a
queue and a made match's grant, but missed a sandbox and a tournament's call.

**A sandbox is held until its room ends**
([D-54](../architecture/03-decision-log.md#d-54--a-sandbox-is-held-by-a-key-of-the-players-own-for-as-long-as-its-room)).
Being matched lasted only as long as a ticket, and `DELETE /v1/queue` forgot even that. So one session could open a
sandbox, leave, and open another, each a room promised for a minute; and with each ticket joined, hold a room for
twenty minutes. Now:

| | |
|---|---|
| `sbx:{playerId}` | the match. `POST /v1/sandbox` sets it for the caller and each member with `NX`, for a ticket's life, 60 s. One already held refuses the request, 409 `in_sandbox`, and gives back any set for it |
| the arena | when a sandbox player joins, sets it for the room's whole life and a minute, 1 260 s. Deletes it when the player leaves on purpose, and for everyone still in the room when it ends. A player whose connection is lost keeps it: they may resume |
| leaving the queue's record | for a sandbox not yet joined, the player's ticket is revoked. Only if that took it (the ticket was not yet claimed) is the hold deleted. So a ticket cannot be joined after its hold was given back |
| a room's promise | lapses with its minute, as an accepted match's does when its players never come (D-49's cost) |

So a player holds one sandbox, joined or not, until it ends or they leave it. The queue stays open from inside one, as
from inside any match (D-49).

**A tournament's call is a match's grant**
([Q-44](../requirements/01-scope-and-nfrs.md#7-open-questions), recommended):

| | |
|---|---|
| `tcall:{playerId}` | the tournament, set with the grant by the scheduler, for the ticket's life, 60 s |
| the queue and the sandbox | refuse a player who is called, 409 `in_match`, as for one matched |
| the matcher | drops a queued entry with a called member (the whole party), as one without a lobby, and tells the rest. A player called while asked to confirm, and silent, is not locked out |
| a player matched already, or playing | keeps that match and is called all the same: the tournament's clock decides, as for anyone who does not come |

The call is the only part that is the worker's. The queue's rules stay in `platform`, which reads `tcall:` where it
reads `conn:`.

**Built** in two parts: (a) the sandbox's hold (2026-10-01), (b) the tournament's call (2026-10-02).

## 5. Modes and rating

Every mode, who plays it, and how its result is handled. The ids are `handoff/MatchMode`'s, the `mode` that a result
and `matches.mode` carry.

| id | Mode | Roster | Result handling |
|---|---|---|---|
| 0 | `ffa` (the public arena) | open: anyone, at any time | not rated; paid as every match is |
| 1 | `duel` (1v1, ranked) | 2, matched by rating | Elo: `ΔR = K·(S − E)`, K = 32, falling to 16 after 30 matches |
| 3 | `rffa` (ranked free-for-all, 8) | matched by rating | pairwise Elo on placement ([D-28](../architecture/03-decision-log.md#d-28--a-free-for-all-is-rated-pairwise-each-player-against-each-other)) |
| 2 | `tvt` (3v3) | matched by rating, parties of up to three | each player by the teams' means ([D-26](../architecture/03-decision-log.md#d-26--a-team-is-rated-by-its-players-mean-each-moved-by-their-own-k)) |
| 5 | `teams` (3v3, two teams) | a party of three of one team each | the team's own rating, wins, losses and draws (the sixth slice of §4) |
| 4 | `coop` (one team of three against waves) | queued alone or as a party, matched in the order they waited | unrated; paid as every match is; waves cleared are logged (the fifth slice of §4) |
| 6, 7 | `domination`, `tag` (3v3) | queued alone or as a party; every entry at 0, so matched in the order they waited | unrated; paid as every match is (01 §8.7, §8.8) |
| 8 | `maze` (8, each for themselves) | queued alone, in the order they waited | unrated; placed by score (01 §8.9) |
| 9 | `sandbox` | the caller, or a party's leader for the party (§4, the seventh slice) | none: nothing is published |

**All results flow through the same stream and the same applier.** As built there is no per-mode policy object:

- the result's mode id says whether, and how, it is rated (`worker/EloRating`, for the rated modes that `persistence`
  names);
- the reward rules are one table for every mode (`worker/RewardRules`);
- a tournament reads its match's result from MySQL (§6).

## 6. Tournaments

A **tournament** is a bracket (or, since plan item 66, a round robin) of entries, made by an operator, that players
or teams register for. This section covers the state machine, the first slice (duels), round robin, teams'
tournaments, and how a match is decided when things go wrong.

**The state machine** (first design). It lives on the tournament row, and a scheduled job in `worker` drives it every
5 seconds:

```
REGISTRATION ──(deadline reached, entries ≥ min)──► SEEDED ──(startsAt)──► RUNNING ──► FINISHED
      │                                                                      │
      └──(entries < min)──► CANCELLED (refund fees)                          └── per round:
                                                                                  for each PENDING match with both sides known:
                                                                                     create room → READY, deadline = now + 60 s
                                                                                     both joined → RUNNING
                                                                                     result → DONE, winner advances
                                                                                     deadline passed, one side absent → WALKOVER
                                                                                  all DONE → currentRound++ after roundIntervalSec
```

**As built**, the tournament's states are as drawn. The rest differs (the slices below):

- there are no fees, so there is nothing to refund; the minimum is two entries;
- a match is `PENDING`, `READY` or `DONE`. There is no `RUNNING` or `WALKOVER` state for a match: the arena plays a
  walkover as any made match does, and the scheduler decides a match from the result MySQL recorded, or from the
  270 s rule;
- the minutes between rounds are the operator's `roundMinutes`.

**Every transition is a conditional update that carries the expected state:**

```sql
UPDATE tournament SET state = 'SEEDED', version = version + 1
 WHERE id = ? AND state = 'REGISTRATION' AND version = ?;
-- affected rows 0 means somebody else advanced it; do nothing
```

**`ROW_COUNT() = 0` is the concurrency control.** Two scheduler ticks, or a restart in the middle of a tick, cannot
advance a bracket twice: the second update simply matches no rows. This is the same shape as the idempotency rule in
[06 §5](06-persistence-mysql.md#5-idempotency): make the write itself the check.

**More of the first design, and what became of it:**

| First design | As built |
|---|---|
| seeding by rating | as designed: by the mode's rating, then by who registered first |
| single elimination for v1, round robin for small groups after that | both: round robin since 2026-10-02, for 2 to 8 entries (below) |
| entries are players or teams; a team's entry needs a LEADER or VICE_LEADER to register, and a roster of the mode's size at match time | a team registers as a party of exactly three of its members, and that roster is fixed for the whole tournament (D-44) |
| rewards paid by `worker` through the ledger at `FINISHED`, idempotent on `tourney:{id}:{place}:{entityId}` | as designed, keyed per player: `tourney:{id}:{place}:{playerId}`, and each roster member of a team is paid |

### The first slice: duels (designed 2026-09-30, plan item 18)

On [Q-15](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation.

**Created by an operator**, with `POST /admin/tournaments` (§10, audited). It takes:

- a name;
- the most entries, 2 to 32;
- the registration deadline, and the start;
- the minutes between rounds, 1 to 60;
- prizes in coins for places 1, 2 and 3 (place 3 is each semi-final's loser).

The first slice's tournament is duels, one player per entry, with no fee.

**Registration.** `POST /v1/tournaments/{id}/entries` registers, while the tournament is in `REGISTRATION` and before
the deadline; one entry per player. `DELETE` on the same path withdraws, before the deadline. `GET /v1/tournaments`
lists those registering, seeded or running. `GET /v1/tournaments/{id}` gives one tournament, its entries, and once
seeded, its bracket.

**Who may enter** (designed 2026-10-02, plan item 61,
[Q-43](../requirements/01-scope-and-nfrs.md#7-open-questions)): only a player who has played.

- **The problem.** Any account could enter, a guest included, and a guest costs nothing but the per-address limit.
  Thirty-one throwaway guests and one real account filled a 32-place tournament, and the real account won every round
  by walkover and took the first prize.
- **A duel's entrant needs ten rated duels**: the mark that lists a player on the duel board (Q-40,
  `RatingBoards.MIN_RATED`).
- **A team's entry needs ten rated team matches of the team's own** (`team.rated_matches`). A team match rates the
  team, not its players, so no player has a count in that mode. Q-43's "each player on the roster" is read as the
  team's count.
- Otherwise: 409 `too_few_rated`, checked after `closed` and before `full`: the entrant's own reason comes before the
  tournament's.
- The count is read in the registration's transaction, under the tournament's lock. A count only grows, so nothing
  else is locked.

**Seeding**, at the deadline. With fewer than two entries, the tournament is `CANCELLED`. Otherwise the entries are
ordered by duel rating, then by who registered first. They are placed in a bracket of the next power of two, seed 1
against the lowest seed, in the standard order, so the top seeds meet last. A seed with no opponent has a bye into
round 2.

**A match.** At the start, and in each later round once the previous round has ended and the minutes between rounds
have passed, every match with both sides known is made, as the matchmaker makes a duel (§4):

- a ticket for each player, naming the match;
- the grant kept for the player (`GET /v1/tournaments/{id}/match`) and pushed (`evt.tournament.match`).

The arena plays it as any made duel (01 §8): it waits at most 30 s for both players, and one alone wins by walkover,
published as any result. The scheduler reads the result MySQL recorded for the match. Its winner advances; a draw
sends the higher seed on. **No result 270 s after the tickets** (30 s to come, 180 s to play, 60 to spare) means that
nobody came, or that the arena was lost: the higher seed advances.

**The end.** The final's result makes the tournament `FINISHED`, and the prizes are paid through the ledger,
idempotent on `tourney:{id}:{place}:{playerId}`. Tournament duels are rated as any duel.

The scheduler is `worker`'s and runs every 5 s. Each transition is a conditional update on the tournament's `state`
and `version`, as above, so two workers, or a restart, cannot advance a bracket twice.

**The scheduler in detail** (designed 2026-09-30, plan item 18 part (c)): `worker/TournamentScheduler`, in every
worker, with no lock
([D-41](../architecture/03-decision-log.md#d-41--every-worker-runs-the-tournament-clock-and-a-match-is-claimed-before-its-tickets-are-written)).
Each tick, for each tournament that is not finished or cancelled:

- **Registering**, with the deadline reached: fewer than two entries, `CANCELLED`; otherwise seeded.
- **Seeded**, with the start reached: `RUNNING`, round 1.
- **Running**, its current round:
  - **Each pending match with both players is made**: in round 1 at once; in a later round once the minutes between
    rounds have passed since the last round ended. An arena with a free room is chosen and the room promised (if
    there is none, the match waits for the next tick). The match is claimed, `PENDING` to `READY`, with a new match id.
    Only then is a ticket written for each player, the grant kept at `tgrant:{tournamentId}:{playerId}` for the
    ticket's 60 s, and `evt.tournament.match` pushed with it: `tournamentId`, `round`, `arenaHost`, `arenaPort`,
    `ticketId`, `tls`, `mode` (`duel`), and `tlsName` behind the front (D-82). (Since plan item 54 the tickets are
    written before the claim: "Deciding by what happened", below.)
  - **Each ready match is decided.** A recorded result with one player first advances that player; a draw, the
    higher seed; no result 270 s after the tickets, the higher seed.
  - **When every match of the round is done**: after the final, the prizes are paid and the tournament is `FINISHED`;
    after any other round, the round is ended.

**Prizes** go through the ledger as reason 3, `ref` the tournament's id, keyed `tourney:{id}:{place}:{playerId}`:

- place 1 is the final's winner, place 2 its loser, place 3 each semi-final's loser (none from a semi-final that was a
  bye);
- a prize of 0 is not paid;
- a tick that dies between prizes pays the rest next time, and none twice;
- since plan item 68 the places are also paid gems, by a rule of their own (§8, the catalogue and gems).

The public API's routes. Each needs a session, except the first two:

| Route | Answer |
|---|---|
| `GET /v1/tournaments` | `{"tournaments":[…]}`: those registering, seeded or running, each with its entries → 200 |
| `GET /v1/tournaments/{id}` | the tournament: `state`, `maxEntries`, `registrationEnds`, `startsAt`, `roundMinutes`, `currentRound`, `prizes`, `entries` (`playerId`, `name`, `seed`) and `matches` (`round`, `slot`, `state`, `playerA`, `playerB`, `winner`) → 200; 404 `no_such_tournament` |
| `POST /v1/tournaments/{id}/entries` | registers → 200, the tournament; 401; 404 `no_such_tournament`; 409 `closed`, `too_few_rated`, `already`, `full`: a player or team already entered is told `already` before `full` (P-59) |
| `DELETE /v1/tournaments/{id}/entries` | withdraws → 200, the tournament; 401; 404 `no_such_tournament`, as the `POST` (P-57); 409 `not_registered` |
| `GET /v1/tournaments/{id}/match` | the player's grant for their match: `tournamentId`, `round`, `arenaHost`, `arenaPort`, `ticketId`, `tls`, `mode`, and `tlsName` behind the front → 200; 401; 404 `no_match` before there is one and after it expires |

**What the ticket does not carry:** the equipment bonus. Tournament duels are on equal terms
([Q-16](../requirements/01-scope-and-nfrs.md#7-open-questions)).

**Each match's room is promised** as its arena is chosen
([D-42](../architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)).
So a round's matches, chosen in one tick, are not all sent to one arena's last rooms: those beyond the rooms free wait
for a later tick. A scheduler whose claim was lost gives its promise back. **An arena that turns the tickets away all
the same**, its rooms taken some other way since it was chosen (D-20), gives no result, and the 270 s rule decides the
match.

### Round robin (designed 2026-10-02, plan item 66)

Single elimination sends half the field home after one match. In a **round robin**, every entry meets every other
entry once, and the standings decide. The operator chooses with `POST /admin/tournaments` `"format"`: `elimination`
(the default) or `round_robin`, for duels or teams alike. The numbers are a first cut of the balance the owner left to
Claude (Q-48), each with its reason:

| | |
|---|---|
| entries | 2 to 8 (400 `invalid_tournament` otherwise): 8 make 7 rounds, already hours at the longest minutes between rounds |
| the schedule | written whole at seeding, by the circle method: seed 1 fixed, the others turning one place a round, so every pair meets once in n − 1 rounds. An odd field adds a "nobody"; whoever meets it sits that round out, over n rounds. A bye is no match and no points |
| a match | made and decided as an elimination's, round by round. A result with one side first is a **win**. Both first, a result cut short (not a finish, defect D-34), or **no result** 270 s after the tickets is a **draw**: nobody came, or the arena was lost, and nobody can be shown to be at fault. (Elimination must send someone on, and sends the higher seed) |
| points | a win 3, a draw 1, a loss 0, as in football: a win is worth more than two draws, so playing for it pays |
| the standings | by points, then wins, then seed: ties are settled by what was played, then by who was rated better before |
| prizes | places 1, 2 and 3 of the final standings, the third paid once (elimination pays each semi-final's loser) |

The tournament's view gains `format`, and for a round robin `standings`, by place: each entry's `playerId` (or
`teamId`), `name`, `points`, `wins`, `draws` and `losses`. In the bracket, a draw is a match done with no `winner`.

### The second slice: teams' tournaments (designed 2026-09-30, plan item 20)

On [Q-19](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation, and
[D-44](../architecture/03-decision-log.md#d-44--a-teams-tournament-entry-fixes-its-roster-and-the-bracket-holds-teams-ids-where-a-duels-holds-players).
Everything in the first slice holds unless it is changed here.

| | |
|---|---|
| the mode | chosen by the operator: `POST /admin/tournaments` takes `"mode"`, `duel` (the default) or `teams` |
| an entry | a team, registered by its leader or a vice leader who leads a party of exactly three of its members: 400 `party_too_small`, 409 `not_one_team`, 403 `not_allowed`, as a team queues (§4, the sixth slice); then `closed`, `too_few_rated` (the team's own count), `full`, `already`, as a duel's |
| the roster | those three, for the whole tournament; no substitutes. `DELETE …/entries` withdraws the team, by its leader or a vice leader |
| seeding | by team rating, then by who registered first |
| a match | a team match, mode 5: a ticket to each roster member still in the team, side 1 for the bracket's `player_a`, side 2 for `player_b`; the grant kept and pushed as a duel's, with `"mode": "teams"`. A member who left the team gets none, and the side plays short |
| the result | the side placed first wins; a draw, or no result by 270 s, sends the higher seed on. Rated as any team match (§4) |
| prizes | each roster member of a placed team is paid the place's prize, keyed per player as a duel's |

In the view, a teams' tournament has `"mode": "teams"`. Its entries carry `teamId`, `name`, `seed` and `roster` (each
`playerId` and `name`). Its matches have `teamA`, `teamB` and `winnerTeam` where a duel's have `playerA`, `playerB`
and `winner`.

#### Built in this order

- (a) V13 and the repository: the mode, team entries and rosters, seeding by team rating, the result by side
  (`persistence`);
- (b) the operator's mode, and registering and withdrawing a team (`platform`);
- (c) the scheduler for teams: rosters' tickets, the winner by side, prizes to rosters (`worker`);
- (d) the client, and a live drill.

**(a) to (c) built 2026-09-30:**

- V13; `TournamentRepository`'s team entries and rosters. A roster player already on another roster fails the entry,
  and none of it is kept.
- Seeding by team rating. A disbanded team's entry is kept and seeded last.
- The result by side; the operator's `mode`.
- A team's registration by its party's leader, who is the team's leader or a vice leader, and its withdrawal.
- The view's teams and rosters, and a bracket named `teamA`, `teamB`, `winnerTeam`.
- The scheduler's seats: side 1 and side 2 from the roster members still in each team (not one who left and joined
  another team since); the winner by the side placed first; a draw to the higher seed; and prizes to each roster
  member, one who left included.

**(d) done 2026-09-30**, and drilled from the release (the drill's `teamcup` scenario): two teams' parties entered as
rosters, the final pushed to all six players, the lower seed's roster winning by walkover, each roster member paid.

### Deciding by what happened (designed 2026-10-02, plan item 54)

The second audit found defects D-33, D-34, T-36 and T-37 ([defects](../defects.md)).

**The bracket is seeded from the entries that the seeding locks** (T-36). The entries were read in one transaction and
seeded in another. An entry or a withdrawal that committed between the two left the bracket and the entries
disagreeing: a withdrawn player with no seed, on whom the scheduler threw an error at every tick. Now `seed` takes the
tournament's row first, as a registration and a withdrawal do, and reads the entries in the same transaction with a
locking read.

**A match's tickets are all written before it is claimed** (T-37). They used to be written one seat at a time after
the claim, and a store failing part-way left the match claimed with seats that never had a ticket. Now the tickets
come first. The claim follows only when every seat has one. A lost claim, or a failure before it, revokes the tickets
and gives back the room's promise, and the next tick tries again. The grants and pushes, which are keyed by the
player, stay with the claim's winner, since another worker may be making the same match.

**No result is not a late result, and a match cut short is not a finish** (defects D-33, D-34;
[Q-45](../requirements/01-scope-and-nfrs.md#7-open-questions), recommended):

| | |
|---|---|
| nobody came | the arena publishes nothing; 270 s after the tickets, the higher seed advances, as before |
| somebody came | the arena marks the match, `marr:{matchUid}`, at the first arrival. With no result 270 s after the tickets, the scheduler waits for one for up to 30 minutes (a result pipeline's tolerable outage, architecture/02), then the higher seed advances |
| a result | it decides, however late |
| cut short (an operator's close, a room that failed) | recorded with the result (`matches.cut_short`, V18). It is not a finish: the higher seed advances, as for an arena that died with the match |

A late result applied after the bracket moved on is still applied, rated and paid. Only the bracket's decision is the
scheduler's.

Built in two parts: (a) the seeding and the tickets, (b) the decision.

## 7. Leaderboards

A **board** ranks players, or teams, by one number. There are two kinds. **Score boards** rank the best score in one
match, and live in j-redis. **Rating boards** rank a rating, and are read from MySQL. This section covers both,
seasons, and the board of teams.

**The score boards are built** (`LeaderboardStore` in `handoff`), on one metric, the best score in a single match,
over three windows:

| Key | Holds | Expires |
|---|---|---|
| `lb:score:alltime` | best score ever | never |
| `lb:score:day:{yyyy-MM-dd}` | best score that UTC day | after 3 days |
| `lb:score:week:{yyyy-Www}` | best score that ISO week | after 10 days |
| `lb:name` | player id → display name | never |

**Why three windows.** An all-time board on its own is one that no new player can ever enter. A few months in, its top
is held by whoever played most in the first weeks, and it stops being a reason to play. The short windows are where
an ordinary player appears. All windows are UTC: a local day would have to pick someone's local time, and that choice
would silently decide whose midnight resets the board.

### Why a maximum, not a total

**`worker` writes `ZADD … GT`, never `ZINCRBY`.** A best score is a maximum, and a maximum is idempotent *and*
commutative: the same match applied twice, or two matches applied in either order, leave the same board. That is not
a nicety. The queue that feeds the board is at-least-once by design, so redelivery is normal traffic
([05 §7](05-worker-and-events.md#7-the-one-place-exactly-once-nearly-leaked)). `ZINCRBY` would count a score twice
every time a worker died between its commit and its acknowledgement.

It is also cheaper than the alternative this document used to specify. Writing an absolute total is idempotent too,
but only if you first *read* the total back out of `player_stat`: a SELECT per player per match. `GT` needs no read.
The store already holds the previous maximum, and keeps whichever is larger.

Two consequences worth stating:

- **The board update runs on every delivery, not only the first.** If it ran only when "this result is new", a crash
  between the MySQL commit and the board write would lose that ranking for good: the redelivery would see the rows
  already present and skip. The repeat *is* the recovery.
- **A board failure does not hold up the queue.** The entry is acknowledged either way. This pipeline pays players;
  the board is a projection of what it has already written, and the player's next match carries their best onto it
  again. A failure is counted as `unranked` on the worker's shutdown line.

### Reading

**`platform` only reads the boards.** Ranks are 0-based in the store and **1-based on the wire**: a player is "#1".
Converting once, at the API boundary, is better than every client doing it and one of them forgetting.

| | |
|---|---|
| `GET /v1/leaderboards/{board}?limit=N` | the top N, best first. No session needed: a board is the thing players show each other, and needing a login to read one would keep it off the title screen. `limit` defaults to 50, and is clamped to 100 |
| `GET /v1/leaderboards/{board}/me` | the player's rank, score, and five rows on either side. Needs a session. **404 `not_ranked`** when the player has no score there, so the client can say "play a match to be ranked" rather than show a rank of 0 |

`{board}` is `alltime`, `daily` or `weekly`; a rating board, `duel`, `rffa` or `tvt`; or `teams` (below). Anything
else is 404 `unknown_board`.

**Around-me is one command**, `J.ZAROUND`, which returns the rank and the window together. As `ZREVRANK` then
`ZRANGE`, it would be two round trips against a board that other players write to in between, and the rank could
disagree with the window it labels.

**Display names** come from the `lb:name` hash. `worker` writes it from the name MySQL holds when it applies the
result, and a rename writes it too (§1, D-60). So showing a board costs one extra round trip, not N.

**Players on the same score are ordered by member**, the player id as a string. So the order is arbitrary, but the
same on every read. A board that reshuffled tied players on each refresh would look broken to the two people watching
it.

### What "best" measures, exactly

A subtlety worth stating, because it will otherwise be reported as a bug. In the public arena a player's stay is
**checkpointed every ten minutes**, and a checkpoint starts a fresh tally
([D-15](../architecture/03-decision-log.md#d-15--the-public-arena-runs-continuously-structured-modes-are-timed-matches),
`MatchTally.checkpointOpenMatch`). So the unit this board ranks is at most ten minutes of play. A player who stays
three hours produces eighteen records, not one large one.

The board therefore measures **the best ten-minute stretch**, not the highest score a tank ever carried. That is
defensible: everyone is measured over the same bounded unit, so it rewards how well you play rather than how long you
can stay. But it is *not* what a player who peaked at 5 000 over an hour will expect to see.

Ranking the peak live score instead would need the simulation to track and report a maximum per life, which it does
not. Decide it with a real player in front of it, not here.

### Rating boards (designed 2026-10-01, plan item 45)

On [Q-40](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation. Three more boards, `duel`, `rffa`
and `tvt`, each ranking a player's rating in that mode, read by the same two calls.

- A player is listed once they have ten rated matches in that mode (`player.rated_duels`, `rated_rffas`,
  `rated_tvts`; `RatingBoards.MIN_RATED`).
- Ties are ordered by more rated matches, then by player id.

**Read from MySQL, not projected into the store.** A rating is not a maximum: it falls as well as rises, so
`ZADD … GT` cannot hold it. The worker knows the change it applied, not the rating it left. A projection would need
the rating read back in the result's transaction, and a store loss rebuilt from the table anyway. So the table is the
truth, indexed by rating (V17).

- `platform` keeps each board's top for thirty seconds. So a title screen that everyone reads costs the database one
  query per board every thirty seconds, per platform.
- A player's own place is read fresh: their rank is the count of listed players above them.

**Built 2026-10-01** (V17; `persistence/RatingBoards`, which reads the rank and the window in one read-only
transaction so they agree; `platform/RatingLeaderboards`, with the thirty seconds). Not done: a banned player is not
taken off a board, as on the score boards.

#### A board's place, measured, then cheaper (designed 2026-10-02, plan item 58)

**The problem** (defect [D-35](../defects.md#3-data-and-the-result-pipeline)). The second audit found that a player's
own place walked every account. V17's indexes led with the rating. Every account that never played a rated match sat
in them at the starting 1 200, and "listed" (ten rated matches) cannot narrow a range on the rating.

**Measured** with `tools/RankBenchmark` on this machine, quiet (load 1.8): 200 000 accounts, of which 5 000 listed on
the duel board, ratings spread around 1 200.

| Player | Rank | A place, median (slowest of 21) | Rows read: count, window |
|---|---|---|---|
| top | 1 | 7.7 ms (22.6) | 1, 6 |
| middle | 2 501 | 187 ms (221) | 197 500, 197 506 |
| bottom | 5 000 | 178 ms (192) | 199 999, 200 000 |

Every place below the top read every account, twice: once for the count, and once for the window by `LIMIT offset`.
At a million accounts that is a second a call, read fresh.

**Cheaper** ([D-57](../architecture/03-decision-log.md#d-57--a-rating-board-is-indexed-by-its-listed-players-only-and-a-place-read-by-key-not-by-offset)):

- **An index of the listed players only** (V20). Each board is a generated column (`board_duel`, …, virtual): the
  rating for a listed player, and NULL for anyone else. It is indexed in the board's order. The unlisted are in no
  range a board reads, and the top reads its own rows and no more.
- **The count** reads the listed players above, by that index. A place costs its rank, not the number of accounts.
- **The window is read by key, not by offset.** The rows before are read backwards from the player's own key, and the
  rows after forwards from it, `each` of each.

**Measured after**, with the same tool on the same machine, load 2.7:

| Player | Rank | A place, median (slowest of 21) | Index rows read |
|---|---|---|---|
| top | 1 | 13.5 ms (37.3) | 17 |
| middle | 2 501 | 14.4 ms (48.8) | 5 021 |
| bottom | 5 000 | 18.2 ms (28.2) | 10 016 |

The top hundred took 3.7 ms and 101 rows, as before.

- The tool counts the index rows a call read on its connection (the session's handler reads), so before and after are
  the same measure: before, 197 507 and 200 006 rows for the middle and the bottom.
- About 12 ms of each place is its seven round trips on this machine, whatever the rank.
- **MySQL does not read this index backwards** for the rows before a player. It sorts the range above instead. So that
  read takes only keys from the index, and fetches whole rows only for those shown (a deferred join). Built first
  reading whole rows, it took 36 ms at the bottom.

**What is left.** A place still costs about twice the number of players listed above it, from the index alone: rank
in a B-tree is counted, not looked up. A rank kept as a structure (06 §1) is the next step if boards grow past where
that is cheap. At a hundred thousand listed players, the bottom would read two hundred thousand index rows again.

V17's indexes were kept until every `platform` read by the new ones (expand, then contract: 06 §8), and were then to
be dropped by the next migration. The baseline that replaced the migrations (V1, D-75) has only the new indexes,
`idx_player_listed_duel`, `idx_player_listed_rffa` and `idx_player_listed_tvt`.

**Rebuilt from MySQL after a store loss**, and safe to run on a healthy store:
`systemctl start backend-leaderboard-rebuild`, built 2026-09-26 ([05 §8](05-worker-and-events.md#8-rebuilding-after-a-store-loss)).

### Seasons (designed 2026-10-03, plan item 71 (a))

On the owner's answer to Q-50. A rating board that never ends is won by whoever got there first. A **season** gives
every player a race they can finish, a place to keep, and a reason to come back when the next one starts
([D-63](../architecture/03-decision-log.md#d-63--a-season-ends-in-three-steps-each-safe-to-repeat-its-places-its-gems-then-its-reset)).
Its numbers are first cuts of the balance the owner left to Claude (Q-48), each with its reason.

**How long.** **Two calendar months each**, from 00:00 UTC on the first of January, March, May, July, September and
November.

- Long enough to be placed (ten rated matches), and to climb at the settled K of 16.
- Short enough that a player who starts late still has a season end within reach.
- Months, because a date a player can remember is a date they come back for.
- Season 1 runs from the migration that makes it to the end of the two months it falls in.
- An operator can end the current season at once (`POST /admin/seasons/end`), for a drill, or to bring the calendar
  into line. The next season then runs to the next boundary.

**Which boards.** The three player boards are the season's: duel, ranked free-for-all and team-vs-team. The board of
teams, and its seasons, came with plan item 73 (below).

**When a season ends**, `worker`'s season job closes it in three steps. Each step is safe to run again, after a crash
or by a second worker:

1. **Places.** Each board's listed players, in the board's order (rating, then rated matches, then id), are written to
   `season_place` with their place, rating and rated matches. This is one transaction, with the next season's row. It
   records the boards' final standings as they stood when the job ran. A result applied after that counts towards the
   new season.
2. **Gems**, by place on each board, through the ledger's one path (reason 6, a season's reward, keyed
   `season:{season}:{board}:{player}`). Each comes with an inbox item (`SEASON_REWARD`) that says the season, the board
   and the place, and an `evt.inbox` push:

   | Place | Gems | Why |
   |---|---|---|
   | 1 | 100 | five boosts: the season's one headline, worth fighting for |
   | 2 | 60 | three boosts |
   | 3 | 40 | two boosts |
   | 4 to 10 | 25 | a boost and a bit: the top ten is the board's first screen |
   | 11 to 100 | 10 | half a boost: a place a player can aim for from the middle |
   | every other player listed | 5 | a quarter of a boost: for finishing the ten rated matches that list a player. A nudge, not a living |

   A player listed on all three boards is paid on each. Compared with plan item 68's sources (a tournament's winner
   30 gems, every milestone 20): a season's top ten earns a tournament's second place, and its winner more than three
   tournaments.
3. **The reset.** Every rating goes halfway back to 1 200: a 1 600 player starts the new season at 1 400, an 800 player
   at 1 000. Every rated count goes to 0, so the board lists only those who have played the new season's ten.
   - The rating keeps matchmaking's signal: the strong start higher, while the board is a new race.
   - The count back at 0 also gives each player the new player's K of 32 for thirty matches, so the board sorts itself
     out fast.
   - It runs in batches of 1 000 players by id, up to the highest id when the season ended. Each batch is one
     transaction with the season's progress, so a restart goes on where it stopped, and halves nobody twice. An
     account made after the end is already the new season's.

**What a player sees.**

- `GET /v1/seasons`: the current season, its number and when it ends, and the past seasons.
- `GET /v1/leaderboards/{board}?season={n}` and `/me?season={n}`: a past season's final places, from `season_place`.
  Without `season`, the board as it stands, which is the current season's.
- The reward arrives as an inbox item and a push.
- **The inbox item** is `season_reward`. Its `ref` is the season × 10 + the board's mode (1 duel, 2 team-vs-team,
  3 ranked free-for-all). The client reads the place with `/me?season=`.

**The job.** Every worker looks each minute. One takes the store's `job:season` lock (`SET NX EX`, ten minutes), as
the ledger check does. The steps' own conditions make a second runner harmless.

**The operator's calls** (§10, audited):

- `GET /admin/seasons` (GET only): every season, and how far its close has gone (`placedAt`, `paidAt`, `resetAt`);
- `POST /admin/seasons/end` `{"reason"}`: the current season ends now → 200 `{"season", "endsAt"}`; 400 `no_reason`;
  409 `already_ended` while the worker is closing one.

**Built 2026-10-03** (plan item 71 (a)): V27, `SeasonRepository`, the worker's `SeasonKeeper`, the calls above and the
client's. On the development data, a season closed 54 s after an operator ended it: 42 places paid (695 gems) in under
2 s, and 5 571 players reset in 21 batches in under 1 s.

### A board of teams, and its seasons (designed 2026-10-04, plan item 73)

On the owner's answer to Q-51. Q-40 left a team's rating, which team matches move (D-43), without a board, and a
player board cannot show it
([D-65](../architecture/03-decision-log.md#d-65--a-teams-season-place-pays-each-member-who-played-for-it-that-season)).

- **The board** is the same kind as the player boards. A team is listed once it has ten rated team matches, ordered by
  rating, then more rated matches, then id. It is read from MySQL, where the rating is; the top is kept thirty
  seconds, and a place is read fresh. V28 adds the listed rating as a virtual column and its index, as V20 did for
  players.
  - `GET /v1/leaderboards/teams` lists teams: each entry's `teamId` and the team's name, with `score` its rating.
  - `/me` is the caller's team's place: 404 `not_in_team` without a team, `not_ranked` before its ten.
- **Its seasons are the players' seasons.** When a season ends, its first step also writes each listed team's place
  (`season_team_place`), and who it pays (`season_team_payee`): **each member at the end who played at least one of
  the team's rated team matches that season.**
  - Each is paid the place's gems, by the players' table (100, 60, 40; 25 to the tenth; 10 to the hundredth; 5 to
    every other team listed), keyed `season:{season}:5:{player}`, with a `season_reward` inbox item whose `ref` names
    the season and board 5.
  - The same place pays the same, alone or together: a team is a way to play, not a way to be paid more.
  - Who played, not who belongs: a player who joins a team the night a season ends is not paid its place, and neither
    is one who never played for it.
- **The reset.** Every team's rating goes halfway back to 1 200, and its rated matches to 0, as for players. It is one
  statement up to the highest team id at the end (teams are a few percent of players), recorded in the season's row. A
  team's wins, losses and draws are its record, and are kept.
- **What a player sees**: `GET /v1/leaderboards/teams?season={n}`, a past season's teams; `/me?season={n}`, the place
  of the team the player was paid for, or `not_ranked` for a player who was not paid.

**Built 2026-10-04** (plan item 73): V28; `TeamBoards`, using the player boards' own queries over the team table
(`RatingBoards.Columns`); the season's team places, payees and reset; the routes above, and the client's
`RankRow.TeamId`.

### Not built

- **Total-kills boards.** A totals board needs the read-back described above, and nothing asks for one. Rating boards
  were built on 2026-10-01 (above), seasons on 2026-10-03, and the board of teams on 2026-10-04.
- **A bound on growth.** `lb:score:alltime` and `lb:name` hold every player who has ever scored, less the accounts
  erased (§1). At launch scale that is megabytes. Trimming with `ZREMRANGEBYRANK` would cap it, at the cost of
  silently giving no rank to anyone outside the cap, which is the more valuable property. Revisit with a measurement,
  not a guess.

## 8. Economy, shop, inventory and equipment

This section covers the two currencies, the ledger, the shop and its catalogue, items, equipment and item levels,
gems and where they come from, achievements, daily goals, revenue (gems for money, the season pass, skins) and boosts.
Every number here is a first cut of the balance the owner left to Claude (Q-48), and each comes with its reason.

**The first design.** Its bullets are kept for their reasons; the subsections below say what was built.

- **Currencies**: `coins`, earned by play, and `gems`, which are rare: they come from tournaments, milestones, seasons,
  achievements and daily goals. Since the owner brought real money into scope on 2026-10-04, gems are also bought,
  with the payment provider simulated for now ([Revenue](#revenue-designed-2026-10-04-plan-item-75)).
- **Every change goes through one path**: `economy.adjust(playerId, delta, reason, ref, idemKey)`, which writes the
  ledger row and the balance in one transaction
  ([06 §4](06-persistence-mysql.md#4-the-three-transactions-that-matter)). There is no second way to change a balance,
  and that is what makes the nightly reconciliation meaningful. **As built**, the one path is
  `EconomyRepository.move(connection, playerId, currency, delta, reason, ref, idemKey)`, called inside the caller's
  transaction. It locks the player's row, answers "already applied" for a key already used, refuses to go below zero,
  then writes the ledger row and the balance.
- **Shop catalogue** in `shop.json`: `{ sku, itemId, price, stock, availableFrom/To, requiresLevel }`. Purchases are
  idempotent on the client's transaction id. Limited stock would use `DECR stock:{sku}` in j-redis before the
  transaction, and `INCR` back on failure: a hot counter belongs in the store, and its consequence in the database.
  **Limited stock is not built** (below).
- **Items** in `items.json`, referenced by key. The database stores what is owned; the content tables define what it
  does. The first sketch of the items:

```json
{ "id": "eq_barrel_steel", "type": "EQUIPMENT", "slot": "BARREL", "rarity": "RARE",
  "modifiers": [ { "stat": "BULLET_DAMAGE", "op": "ADD_PERCENT", "value": 0.08 } ],
  "maxLevel": 5, "upgradeCost": [100, 250, 500, 1000] }
{ "id": "boost_xp_2x_1h", "type": "BOOST", "durationSec": 3600, "xpMultiplier": 2.0 }
{ "id": "eff_adrenaline", "type": "EFFECT", "durationTicks": 250,
  "modifiers": [ { "stat": "RELOAD", "op": "MUL", "value": 0.8 } ], "trigger": "ON_KILL" }
```

As built, an item is simpler: a `slot` in lower case, each modifier a `stat` and a whole `percent`, and levels and
their costs by one rule for every item (Equipment and Item levels, below).

- **Equipment**: one item per slot (`BARREL`, `ARMOR`, `CORE`, `TREADS`), with the item's level scaling its modifier
  linearly. **The total equipment bonus is capped** at about +25 % on any one stat, so matches are decided by skill,
  not by wallet. As built: the cap is exactly 25 %, and a fifth slot holds a skin.
- **Boosts**: activating one pushes `{boostId, expiresAt}` onto the player's active list and takes one from the
  inventory; expired entries are pruned on read. As built, boosts are timed runs, one per kind (Boosts, below).
- **Effects** are in-match only. The arena owns the logic; the ticket tells it which effects the loadout grants.
  **Not built.**

### The shop, as built (2026-09-26)

The shop was built ahead of Phase 5, at the owner's request, when the rest of this section (items, equipment, boosts,
effects) was not. Equipment, boosts, item levels, gem prices and skins have been built since (below); effects have
not. This subsection decides what the bullets above left open.

**The catalogue** is `shop.json` on `platform`'s classpath. So it ships in the release, and every instance sells the
same things at the same prices. Changing it is a release. It is a list of offers. An example offer, with every
optional field:

```json
{ "sku": "barrel_steel", "itemId": "barrel_steel", "price": 1500,
  "requiresLevel": 5, "availableFrom": "2026-10-01T00:00:00Z", "availableTo": null }
```

(The release's own `barrel_steel` offer has no level and no dates: the catalogue is listed in the subsection on gems,
below.) The catalogue is checked at start. A catalogue that breaks a rule stops `platform` from starting (exit 2),
rather than selling something wrong:

| Field | Rule |
|---|---|
| `sku`, `itemId` | lowercase letters, digits and `_`. A `sku` is at most 64 characters and appears once. An `itemId` is at most 40 characters, the column's width |
| `price` | 1 to 1 000 000 000, in coins unless the offer says `"currency": "gems"` (plan item 68, below). When the shop was built gems had no source, and nothing was sold for them. A free offer is refused: free things are rewards, and a free offer could be taken once per key, which is without limit |
| `requiresLevel` | the account level, 1 to 100; defaults to 1 |
| `availableFrom`, `availableTo` | optional instants; the first must be before the second |
| `stock` | **not built: limited stock.** A catalogue that sets `stock` is refused, so nobody configures a limit believing that it holds. What stands between the design and building it: after a loss of the store, the counter must be rebuilt from the ledger, or the limit starts again from the top |

**The release shipped an empty catalogue** while items did nothing, since selling one would have taken coins for
nothing. It sells twelve offers now: each of the five pieces of equipment for coins, and the two boosts and five skins
for gems (the catalogue below; plan items 68 and 75 (c)). Their prices are Claude's first cut of the balance the owner
delegated (Q-48).

**The API**, with the session token as a bearer credential, as for match requests:

| | |
|---|---|
| `GET /v1/shop` | the offers on sale now → 200. No session needed |
| `GET /v1/content/classes` | the class table a client draws by, `{"version","classes"}`. Its version is its `ETag` and the arena's `Welcome.contentVersion`; 304 to an `If-None-Match` that names it. No session needed ([D-24](../architecture/03-decision-log.md#d-24--the-class-table-reaches-the-device-from-platform-versioned-by-its-content)) |
| `GET /v1/content/phrases` | the phrase list, `{"version","phrases"}`, each with `id`, `key` and English `text`. Its version is its `ETag` and the arena's `Welcome.phraseListVersion`; 304 as above. No session needed. **Built 2026-09-29** ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)) |
| `GET /v1/inventory` | coins, gems and the items held → 200, 401 |
| `POST /v1/purchases` `{"sku","key"}` | 200 `bought` or `already_bought`, with the item, the coin balance (and, since plan item 68, the gems), and how many of the item the player now holds; 400 `invalid_body` or `invalid_key`, 401, 403 `level_required`, 404 `unknown_sku`, 409 `not_available` or `insufficient_funds` |

**The key** is the client's transaction id. The client makes one per tap of Buy, and sends it again, unchanged, with
every retry of that tap.

- It is 16 to 48 lowercase letters, digits and hyphens: a UUID, in lower case.
- Lower case, because the ledger compares keys without regard to case or accents (`utf8mb4_0900_ai_ci`), so `Ab` and
  `aB` would be one key.
- At least 16 characters, so that it is random, not a counter that starts again at 1 when the app is reinstalled and
  then finds its first purchases "already bought".

**A retry is answered as the first attempt was**, even when the offer has since ended, its price has changed, or the
balance has run short. The key is looked up before anything else. A key already used returns `already_bought` with the
item it bought, whatever `sku` the retry names. It is the rule that 06 §4 applies to the funds check, one step
earlier.

Purchases are counted in `backend_platform_purchases_total{outcome}`, the "purchase failures by cause" of §11.

### Equipment (designed 2026-09-30, plan item 15)

What the bullets above sketched, cut to what equipment needs. Boosts and effects come later (Q-12) and extend it.
**Built 2026-09-30**, all of it, and drilled from the release: a player earned coins in a stay, bought a barrel, wore
it, and fired bullets 25 % faster in the next stay.

**Items** are `items.json` on `platform`'s classpath, beside `shop.json`, and are checked at start in the same way: a
table that breaks a rule stops `platform` (exit 2).

| Field | Rule |
|---|---|
| `id` | the `itemId` rules |
| `type` | `EQUIPMENT`, `BOOST` (§8, boosts) or `SKIN` (revenue (c)) |
| `slot` (equipment) | `barrel`, `armor`, `core` or `treads`; at most one of each is worn |
| `modifiers` (equipment) | one to three, each a `stat` (one of the eight, by its name, such as `bullet_damage`) and a `percent`, 1 to 25 |

An item gives its percent in that stat at level 1; levels 2 to 5 were built since (item levels, below). A shop offer
must name an item in the table, and that is checked at start too. The table ships with a few first-cut items, and the
shop sells them (§8, the catalogue, plan item 68).

**The bonus** means "better by that much": for reload, shots that much more often; for the other stats, the stat that
much higher. Everything worn is added up per stat and **capped at 25 %** a stat, so a match stays won by play (the
design's "about +25 %", made exact).

**The API**, with a bearer session, as for the inventory:

| | |
|---|---|
| `GET /v1/equipment` | `{"slots":{"barrel":"barrel_steel","armor":null,…},"bonus":{"bullet_damage":8}}`: each slot an item id or `null`, and the bonus per stat, capped, for the stats it touches → 200, 401. **Built 2026-09-30** |
| `PUT /v1/equipment/{slot}` `{"itemId"}` | wears it in that slot, replacing what was there → 200, as `GET`; 400 `invalid_slot` or `invalid_body`, 401, 404 `unknown_item`, 409 `wrong_slot` (the item is for another slot), 409 `not_owned` |
| `DELETE /v1/equipment/{slot}` | empties the slot → 200, as `GET`; 400 `invalid_slot`, 401 |

Wearing the same thing twice is the same as wearing it once. The `equipment` table (V1) holds a row per worn slot;
nothing new was needed in the schema.

**The loadout travels in the ticket.** When `platform` issues a ticket, for the public arena or a made match, it reads
what the player wears and owns, and puts the resolved bonus in the ticket: the capped percent for each stat that has
one ([D-37](../architecture/03-decision-log.md#d-37--equipment-reaches-the-arena-as-a-capped-percentage-a-stat-in-the-ticket)).

- An item that is worn but no longer owned gives nothing.
- The arena applies the bonus to every tank the player spawns in that stay (01 §3), and never reads inventory (FR-10).
- A change of loadout counts from the next ticket.
- The skin worn travels beside the bonus, in a field of its own, `skin`, and as `s:{id}` in a queue entry (plan item
  75 (c), D-70).

**For a made match** the bonus is read when the player queues, with the name and the rating, and kept in the queue
entry beside them (`b:{id}`). So the matcher still writes tickets from the store alone, and matchmaking does not stop
when MySQL does. A loadout changed while queued counts from the next queue.

**On the wire** the bonus travels as `stat:percent` pairs, such as `5:8,6:10`, in the ticket's `bonus` field, which is
absent when there is no bonus. A ticket whose bonus does not parse, or names a percent outside 1 to 25, is refused, as
any malformed ticket is.

### Item levels (designed 2026-10-02, plan item 67)

Every item was level 1, as `inventory_item.item_level` (V1) recorded. Now a player raises an equipment item they hold,
one level at a time, by spending coins. The numbers are a first cut of the balance the owner left to Claude (Q-48),
each with its reason:

| | |
|---|---|
| levels | 1 to 5 (`EconomyRepository.MAX_ITEM_LEVEL`) |
| what a level gives | each of the item's modifiers × (3 + level) / 4, rounded down: level 1 as before, level 5 twice that (an 8 % barrel gives 16 %). The cap of 25 % per stat over everything worn stands: a level makes an item worth having, and a match is still won by play |
| what a level costs | from level L, 500 × 2^(L−1) coins: 500, 1 000, 2 000 and 4 000 to reach levels 2, 3, 4 and 5, 7 500 in all. A match pays 10 to 50 coins, plus one per ten points of score: about 100 for a five-minute stay. So the first level is a few stays, the last some three hours of play, and an item at its top about six hours. One constant each: `LEVEL_BASE_COST`, and the doubling |
| boosts | have no levels: only equipment is raised |

| | |
|---|---|
| `POST /v1/inventory/{itemId}/level` `{"key"}` | raises it one level → 200 `{"itemId", "level", "coins"}`; 400 `invalid_key` (16 to 48 lowercase letters, digits or hyphens, as a purchase's) or `not_equipment`; 401; 404 `not_held`; 409 `max_level`, `insufficient_funds`. A key used before answers what it did, as a purchase's does |

**One transaction, as a purchase is:**

1. the player is locked first;
2. the item's level is read under the lock;
3. the coins move by the ledger's one path (reason 4, `level`, keyed `level:{playerId}:{key}`, its `ref` the item and
   the level reached);
4. the level is raised from the level read.

`GET /v1/inventory` gives each item's `level`. The bonus a worn item gives, and so the ticket's, is its level's
(D-37's path, unchanged).

### The catalogue, and gems (designed 2026-10-02, plan item 68)

What the shop sells, for how much, and where gems come from: a first cut of the balance the owner left to Claude
(Q-48). The scale it is set against: a match pays 10 coins, 50 for a win, plus one per ten points of score. That is
about 100 for a five-minute stay, and some 1 200 for an hour of play. An item's levels cost 7 500.

**(a) The catalogue**, the release's `shop.json`: each piece of equipment, for coins.

| Offer | Coins | Level | Why |
|---|---|---|---|
| `treads_light`, movement 8 % | 1 200 | 1 | the least decisive in a fight, so the cheapest |
| `barrel_steel`, bullet damage 8 % | 1 500 | 1 | the first barrel |
| `armor_plate`, health 10 % | 1 500 | 1 | the first armour |
| `barrel_rifled`, bullet speed 10 %, penetration 5 % | 2 000 | 5 | two stats, and something to reach |
| `core_capacitor`, reload 8 % | 2 000 | 5 | a faster reload improves everything a weapon does at once |

An item costs an hour or two of play, and each slot is worth an evening. With levels, a whole loadout is a goal of
weeks.

**(b) Gem prices.** An offer may be priced in gems: `"currency": "gems"` in `shop.json` (coins by default).

- A purchase in gems takes them through the ledger's one path, as coins are taken (currency 1, reason 1), with the
  player locked first.
- `GET /v1/shop` gives each offer's `currency`, and a purchase's answer gives its `gems` beside its `coins`.
- The boosts are sold for gems, 20 each. A boost doubles what an hour of play pays: a convenience, never strength. Gems
  are rare, so a boost is worth a podium place or a milestone (part (c)).

**(c) Where gems come from.** Two sources, both rare, both paid by the system through the ledger's one path in gems
(currency 1), each once, whatever the retries:

| Source | Gems | Why |
|---|---|---|
| a tournament's places | 1st 30, 2nd 15, 3rd 5 (each semi-final's loser in an elimination; the third of a round robin's standings), with 4 entries or more; each roster member of a placed team is paid, as its coins are | a podium is worth a boost or more. Two entrants agreeing to meet farm nothing, and Q-43's ten rated matches already gate who enters |
| an account level reached | 20 at level 5, then 20 at every tenth level from 10 to 100: 220 in all | a boost each. Level 5 is two or three days of casual play, where the dearer equipment opens too, so gems are met while a player is still deciding to stay. Level 10 is a week, 30 a couple of months, 100 the long goal (`AccountLevels`) |

- **A tournament's gems** are paid with its coin prizes, at the finish, keyed `tourney:{id}:{place}:{player}:gems`
  (reason 3). They are paid by the rule, whatever the operator set in coins: a tournament with no coin prizes still
  pays its places gems, and the inbox tells the players so.
- **A level's gems** are paid in the result's own transaction, as the level is written, for each milestone above the
  level stored and up to the new one
  ([D-61](../architecture/03-decision-log.md#d-61--a-levels-gems-are-paid-for-the-milestones-between-the-level-stored-and-the-new-one)):
  reason 5, `milestone`, keyed `milestone:{player}:{level}`. A redelivery pays none, and a result that lifts a player
  past two milestones pays both.
- `evt.rewards` gains the `gems` that the result paid (05 §6).
- **The nightly ledger check** reconciles gems as it does coins, in the same statement (05 §9). With gems paid by the
  system and spent in the shop, a balance moved without its ledger row is the same fault in either currency.

### Achievements (designed 2026-10-03, plan item 71 (b))

On the owner's answer to Q-50: more to earn beyond the level milestones. **Achievements** are goals a player can see
coming. Each is a threshold on one of the stats that every result already counts (`player_stat`), paid in gems once,
when a result carries the stat across it
([D-64](../architecture/03-decision-log.md#d-64--an-achievement-is-paid-when-a-result-carries-its-stat-across-the-threshold)).
They are first cuts of the balance the owner left to Claude (Q-48), with the table in one place,
`persistence/Achievements`:

| Stat | Thresholds, and gems | Why |
|---|---|---|
| kills | 100 → 10, 1 000 → 20, 10 000 → 50 | 100 is a few evenings; 10 000 is the long goal of a player who stays |
| wins (a made match placed first; a draw places both first) | 10 → 10, 100 → 20, 1 000 → 50 | made matches only, so a stay in the public arena never counts; 10 is a first week of queueing |
| stays and matches played | 50 → 10, 500 → 20, 5 000 → 50 | showing up: 50 is a week of casual play |
| assists | 100 → 10, 1 000 → 20 | helping is play too (01 §7); only two, as assists come more slowly than kills |
| best score in one stay | 10 000 → 10, 50 000 → 20, 100 000 → 50 | the score board's skill, without needing to top the board |
| time played | 10 hours → 10, 100 → 20, 1 000 → 50 | for the patient, whatever their kills |

Seventeen achievements, 430 gems in all, against the milestones' 220. Most of the gems are in the long goals: a goal
reached early is worth a boost or two, a long one two and a half.

- **Paid in the result's transaction**, as the stats are written. The stats are read in the locking read that already
  reads the player's level. For each achievement whose threshold the stat was under before the result, and is at or
  over after it, gems go through the ledger's one path: reason 7, `ref` `achievement:{id}`, keyed
  `achievement:{player}:{id}`. A redelivery pays none; a result across two thresholds pays both.
- **Told by `evt.rewards`**: its `gems` count them with the milestones' gems, and a new field, `achievements`, names
  each one reached, for the client to show.
- **What a player sees**: `GET /v1/achievements`: every achievement, its stat, threshold and gems, the player's
  progress (the stat as it stands) and whether it is reached. Reached means the stat is at or over the threshold;
  stats only grow.

**Built 2026-10-03** (plan item 71 (b)): `Achievements`; the locking read joined to `player_stat`; `payAchievements` in
the result's transaction; `evt.rewards`' `achievements`; `GET /v1/achievements`; and the client's `Achievements`.

### Daily goals (designed 2026-10-04, plan item 74)

On the owner's answer to Q-51: more for players to earn. **Three goals a day**, met by play, each paid once, with a new
three at each UTC midnight
([D-66](../architecture/03-decision-log.md#d-66--a-days-goals-are-drawn-not-stored-and-paid-as-results-meet-them)).
They are first cuts of the balance the owner left to Claude (Q-48), with the table in one place,
`persistence/DailyGoals`:

| Kind | Easy, coins | Hard, coins |
|---|---|---|
| stays and matches played | 3 → 100 | 6 → 200 |
| kills | 10 → 100 | 30 → 200 |
| wins (a made match placed first) | 1 → 150 | 3 → 300 |
| assists | 5 → 100 | 15 → 200 |
| score, summed over the day | 5 000 → 100 | 15 000 → 200 |
| time played | 20 minutes → 100 | 60 minutes → 200 |
| rated matches played | 1 → 150 | 3 → 300 |

**All three done: 3 gems**, which is a boost a week for a player who comes back every day.

**Why these numbers.** A five-minute stay pays about 100 coins. Three goals are twenty minutes to an hour of play for
300 to 800 coins: a half to a whole again on top of what that time pays. That is worth aiming for, but never a living
without the play. Wins and rated matches pay more, since they need a queue and a result.

- **The day is the result's**: its end's UTC date, as `player_day` counts it. A match that ends after midnight counts
  for the new day. Midnight UTC is one reset for everyone, at a local hour that differs by country; the client shows
  the time left.
- **The three are drawn, not stored.** They are three different kinds, each easy or hard, chosen by a fixed function of
  the player and the day (SplitMix64 over both). So `worker`, which counts, and `platform`, which lists, agree without
  a write. Changing the pool changes the draw, so ship a change at the start of a day.
- **Counted and paid in the result's transaction**, under the player's lock. Each of the day's three goals is moved by
  what the result counts, and its progress is kept in `daily_goal`. A goal that reaches its target is paid its coins
  through the ledger's one path (reason 8, keyed `daily:{player}:{day}:{goal}`). The result that completes the third
  goal pays the set's gems (`daily:{player}:{day}:set`).
- **Told by `evt.rewards`**: `goals` names each goal met, and `goalCoins` gives their coins. The set's gems are in
  `gems`.
- **What a player sees**: `GET /v1/goals`: the day, when it ends, and its three goals, each with its kind, target,
  coins, progress and whether it is done; and the set.
- **Kept a week**: retention deletes progress older than seven days.

**Built 2026-10-04** (plan item 74):

- `DailyGoals`, `DailyGoalRepository`, V29.
- The draw is injected into `MatchResultRepository`: `DailyGoals::of` in `worker`, and no goals where a test is about
  something else, whatever the day.
- The goals and the set paid in the result's transaction; `evt.rewards`' `goals` and `goalCoins`.
- Retention's purge; `GET /v1/goals`; and the client's `Goals`.

### Revenue (designed 2026-10-04, plan item 75)

On the owner's answer to Q-51: the economy enhanced with a revenue stream, so real money comes into scope
(requirements §6). The first rule is the one the shop has kept since gems were priced (plan item 68): **money buys
convenience and looks, never strength**
([D-67](../architecture/03-decision-log.md#d-67--money-buys-convenience-and-looks-never-strength)). Gems are what
money buys. Nothing sold for gems or money makes a tank stronger. So coins, which buy equipment and its levels, are
never sold. Revenue comes in three parts, each designed whole at its turn:

- **(a) Gems for money**: packs bought through a payment provider; an order granted once it is paid; refunds taken
  back. **The provider is simulated**: the owner wants no third-party payment or sign-in integration for now
  (2026-10-04, Q-52)
  ([D-68](../architecture/03-decision-log.md#d-68--a-purchase-is-an-order-its-provider-confirms-granted-once-and-a-refund-is-taken-back)).
- **(b) A season pass**: points from play every season; a free track of rewards, and a premium one bought with gems.
- **(c) Looks**: tank skins sold for gems, worn in a slot of their own with no modifier, and carried to every client
  that sees the tank.

**(a) Gems for money.** The packs, a first cut. Prices are in US dollars, for the simulated provider and for the store
tiers a real provider would use:

| Product | Gems | Price | Gems a dollar | Why |
|---|---|---|---|---|
| `gems_80` | 80 | 0.99 | 81 | four boosts: the impulse buy |
| `gems_500` | 500 | 4.99 | 100 | a season pass, a common first purchase |
| `gems_1100` | 1 100 | 9.99 | 110 | a pass and skins |
| `gems_2400` | 2 400 | 19.99 | 120 | |
| `gems_6500` | 6 500 | 49.99 | 130 | the most for those who choose to spend |

**A player's first purchase pays its gems twice**, once ever, keyed by the player (`payment:first:{playerId}`). It is
the most common converter in this kind of app, and cheap, since it happens once.

- **The flow is a real provider's; the provider is simulated.**
  1. The client asks for a pack: `POST /v1/payments {"productId"}` makes an **order** (`pending`, with its id, the pack
     and its price). The client's key makes a retried request the same order.
  2. The provider takes the payment and tells the server. Here, the simulated provider is
     `POST /v1/payments/{orderId}/simulate {"outcome": "paid" | "declined"}`: the player's own call, standing in for
     the provider's page and its notification. It calls the one path that a real provider's notification would call:
     **confirm**.
  3. A paid order grants the pack's gems through the ledger's one path (reason 9, keyed `payment:{orderId}`), and the
     first purchase's bonus. `paid` and `declined` are final; a second confirm answers what the first did.
  - `GET /v1/payments/{orderId}` says where an order is, and `GET /v1/payments/packs` what is on sale.
  - **A provider's whole part is one call, confirm.** A real provider, when the owner chooses one, will be a
    notification route that calls it, in place of the simulated route, and nothing after confirm changes. The
    simulated route is served only while the configured provider is the simulated one (the default, and the only one
    there is).
- **Refunds** are taken back. An operator refunds a paid order (`POST /admin/payments/{orderId}/refund`, audited; a
  real provider's refund notification would call the same path), once. Its gems, and its first-purchase bonus too,
  are taken back as far as the balance allows (reason 10). **What could not be taken is a debt**, kept on the order. A
  player in debt cannot order until an operator clears it (`POST /admin/players/{id}/refund-debt`).
- **Orders left pending** for a day are expired by retention; nothing was granted.
- **Off unless named** (D-68). The simulated provider grants gems to whoever asks, so it runs only where the operator
  names it: `BACKEND_PAYMENT_PROVIDER=simulated` (development and drills). Unset, every payment route answers 503
  `payments_off`. Any other name stops the start.

**The packs** are `packs.json` in the platform's release, read once at start and checked as strictly as the shop's
catalogue. It is a list of `{"productId", "gems", "priceCents"}`. The id is 1 to 64 lowercase letters, digits or `_`;
gems and cents are whole numbers from 1 to 100 000, priced in US dollars. An unknown field, or a product listed twice,
stops the start.

| Route | Answer |
|---|---|
| `GET /v1/payments/packs` | `{"packs":[{"productId","gems","priceCents","currency"}]}` → 200 |
| `POST /v1/payments` `{"productId","key"}` | an order, pending; the same order for a key already used → 200 `{"order"}`; 400 `invalid_body`, `invalid_key` (16 to 36 lowercase letters, digits or hyphens: a UUID); 401; 404 `unknown_product`; 409 `refund_debt` |
| `GET /v1/payments/{orderId}` | → 200 `{"order"}`; 401; 404 `no_such_order` (none, or another player's) |
| `POST /v1/payments/{orderId}/simulate` `{"outcome"}` | the simulated provider's confirm, `paid` or `declined` → 200 `{"order","confirmed","gems"}`: `confirmed` is false when the order was no longer pending, and `gems` is the balance after; 400 `invalid_outcome`; 401; 404 `no_such_order` |
| `POST /admin/payments/{orderId}/refund` `{"reason"}` | → 200 `{"orderId","playerId","taken","debt"}`; 404 `no_such_order`; 409 `not_paid` (pending, declined, expired, or already refunded); audited in the refund's transaction (D-30) |
| `POST /admin/players/{id}/refund-debt` `{"reason"}` | → 200 `{"playerId","cleared"}`, the gems of debt cleared; audited in its transaction |

Every payment route answers 503 `payments_off` while no provider is named.

**An order** is `{"orderId","productId","gems","bonus","priceCents","currency","state","createdAt"}`.

- `state` is `pending`, `paid`, `declined`, `refunded` or `expired`.
- `bonus` is the first purchase's gems.
- `createdAt` is the time to the millisecond, cut there before it is written, so that MySQL's `DATETIME(3)` keeps it as
  it was answered. Written whole, MySQL rounded it, and the order's creation and its later reads differed by a
  millisecond (P-55; `SeasonRepository.endNow` already did this).

**(b) The season pass** (designed 2026-10-04,
[D-69](../architecture/03-decision-log.md#d-69--a-season-pass-pays-each-tier-as-its-points-cross-it-each-track-to-its-own-mark-and-its-premium-never-pays-coins)).
Every player holds each season's pass:

- **pass points** from play, and a **tier** for every 250 of them, 40 tiers;
- a **free track**, which pays everyone at each tier;
- a **premium track**, which pays more to whoever bought it for the season, for 500 gems (the `gems_500` pack).

The rules:

- **Points** are counted in the result's transaction. They go to the season being played when the result is applied
  (the newest season not yet placed, as the boards count it). A player gets **10** for each result they are paid for
  (a redelivery earns nothing, as it pays nothing), and **50** for each daily goal the result meets. A day's three
  goals and ten stays make 250: a tier a day.
  So the 40th tier comes in forty of the season's sixty-odd days, and a player who comes back every other day gets
  about halfway. The goals carry most of it: the pass rewards coming back, not grinding.
- **The tiers**, first cuts (Q-48):

  | Tier | Free | Premium |
  |---|---|---|
  | each, except every fifth | 150 coins | 15 gems |
  | every fifth: 5, 10 … 40 | 5 gems | 15 gems and a boost: the hour's xp boost at 5, 15, 25 and 35, the hour's coins boost at 10, 20, 30 and 40 |

  The free track over a season: 4 800 coins and 40 gems. Spread over the season's sixty-odd days, those coins add
  about a sixth to the daily goals' 450 or so coins a day, for the same play. The premium track: 600 gems and eight
  boosts (160 gems at the shop's price), for 500. A player who finishes it can buy the next season's and keep 100: the
  usual loop for a pass, and a reason to play the season out. **Never coins on the premium track** (D-67): it is bought with gems, which money buys, so coins there
  would sell equipment.
- **Paid on crossing**, as achievements are (D-64). A player's pass row keeps its points and, for each track, the last
  tier paid. A result that raises the points pays each free tier between that mark and the tier reached, and, with
  premium, each premium tier. Buying premium pays at once every premium tier already reached. Coins and gems go
  through the ledger's one path (reason 11, a pass tier, keyed `pass:{season}:{free|premium}:{tier}:{player}`), and a
  boost goes into the inventory, all in one transaction under the player's lock. The marks make each tier pay once.
- **Premium** costs 500 gems through the ledger (reason 1, a purchase, keyed `pass:{season}:{player}`, so a second tap
  is answered as the first). It is for the season being played, and it is refused once that season's end has passed
  and it is being closed.
- **When a season closes**, nothing is left to pay. The next season's pass starts at nothing, without premium. Pass
  rows are kept six seasons (a year), then deleted by retention.
- The tiers' boosts are items in `items.json`. A pass that names an item the table does not have stops the platform's
  start, as the catalogue's offers do.

| Route | Answer |
|---|---|
| `GET /v1/pass` | the pass of the season being played: `{"season","endsAt","points","tier","premium","premiumGems","tierPoints","tiers":[{"tier","free":{"coins","gems","itemId"},"premium":{"coins","gems","itemId"}}]}` (a premium tier's `coins` is always 0). A tier is paid once reached (premium: once bought too) → 200, 401 |
| `POST /v1/pass/premium` | buys the premium track for the season being played, paying the premium tiers already reached → 200 `{"result":"bought" \| "already_bought","gems","pass"}`, `gems` the balance after; 401; 409 `insufficient_funds`, `season_ended` |

`evt.rewards` gains `pass`: `{"points","tier","coins","gems","items"}`: the points this result earned, the tier after,
and what the tiers it reached paid. These are counted in neither `coins` nor `gems`.

**(c) Looks: tank skins** (designed 2026-10-04,
[D-70](../architecture/03-decision-log.md#d-70--a-tanks-skin-travels-in-its-own-ticket-field-and-is-told-by-an-event-beside-the-tanks-create)).
A **skin** is how a tank is drawn. Everyone whose view the tank enters sees it, and it does nothing else.

- **An item**, of type `SKIN` in `items.json`: `{"id","type":"SKIN","skin"}`. `skin` is its number on the wire, 1 to
  255, each used once in the table.
- **Worn in a slot of its own, `skin`**, the fifth slot: `PUT /v1/equipment/skin` `{"itemId"}` wears it, and `DELETE`
  takes it off, as for equipment. `GET /v1/equipment` lists the slot. A skin has no modifier, so no bonus, and no
  levels: raising one is 400 `not_equipment`.
- **Sold for gems** in the shop, first cuts (Q-48):

  | Skin | Item | Gems | Why |
  |---|---|---|---|
  | 1 | `skin_crimson` | 150 | a colour: under two dollars of the `gems_1100` pack, an impulse |
  | 2 | `skin_azure` | 150 | |
  | 3 | `skin_jade` | 150 | |
  | 4 | `skin_gold` | 400 | the one that is noticed: four boosts' worth, under the price of a season pass |
  | 5 | `skin_carbon` | 400 | |

- **Carried to every client that sees the tank** (D-70).
  - The number of the skin worn and held goes in the ticket, in a field of its own, `skin`. It is read when the bonus
    is: at a join, or at a queue.
  - The arena keeps it on the tank, as it keeps the bonus, a resumed tank too.
  - Each client that a tank is created for is told by the event `Skin` (7), `u8 handle, u8 skin`, in the frame with
    the create, and only for a tank with a skin ([02 §4](02-networking.md#event-types)).
  - No new protocol version: an older client steps over the event.
  - A skin worn mid-stay shows from the next join.
  - Tournament matches show none: their tickets are the worker's, and the worker has no item table (Q-16).
- **What each number is**: `GET /v1/content/skins`, `{"version","skins":[{"skin","itemId"}]}`, the table a client
  draws by. It is versioned by its content, as the class table is (D-24): an `ETag`, and 304 to an `If-None-Match` that
  names it. No session needed.

### Boosts (designed 2026-09-30, plan item 16)

On [Q-13](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation. **Built 2026-09-30**, and drilled
from the release: a stay paid 10 coins, a coins boost was activated, and the next stay paid 20.

**A boost item** is in `items.json` with `type` `BOOST`, a `kind` (`xp` or `coins`), a `percent` (1 to 100), and
`minutes` (1 to 1 440). It is held like any item. When it is **activated**, one is taken from the inventory and the
boost runs.

**One per kind at a time.**

- Activating a boost of a kind already running adds its minutes, if it is the same item. A different item of that kind
  is refused until the first ends.
- Activating a boost of a kind that is not running starts it now.
- Each run is kept, and a run's end only moves forward. So whether a boost was running when a match ended does not
  change afterwards (D-38).

**Once per key**, as a purchase is (§8's shop). The client makes a key per tap of Activate and sends it with every
retry. A key already used is answered as its first attempt was, and one item is taken, whatever the retries.

**What it does.** When `worker` applies a match's result, it raises each player's experience and coins by the boosts
of theirs that were running when the match ended: the percent is added, rounded down. A boost never touches a rating,
or anything inside the match (D-38).

| | |
|---|---|
| `GET /v1/boosts` | the boosts running: `{"boosts":[{"kind":"xp","itemId","percent","endsAt"}]}` → 200, 401 |
| `POST /v1/boosts` `{"itemId","key"}` | activates one → 200 `activated` or `already_activated`, with the boosts running; 400 `invalid_body` or `invalid_key`, 401, 404 `unknown_item` (no such item, or not a boost), 409 `not_owned`, 409 `other_running` (a different boost of that kind is running) |

## 9. Notifications

How players learn what happened, and how the processes tell each other. This section also covers the social layer:
friends, blocks, presence and the inbox.

- **Pushes** go through the gateway ([03 §5](03-gateway.md#5-push-routing)), and are best-effort: a push says "look";
  it never carries the truth.
- **Offline players** get an inbox row in MySQL (kept 30 days) for rewards and invitations. That is what the client
  reads on reconnect.
- **Arena → platform** flows through streams, never direct calls ([05 §2](05-worker-and-events.md#2-streams)).
- **Platform → arena** is pub/sub. The first design listed room create and close, kicking a player, broadcasting a
  notice, and reloading configuration. **As built**, the arena's channel `arena-admin:{name}` carries two commands,
  closing a room and kicking a player (§10, the second slice). A room is made by its first ticket (D-20). A notice goes
  to the lobby on `push:all`, not to the arenas (§10, the third slice). Reloading configuration is a restart.

### The social layer's first slice (designed 2026-09-30, plan item 21)

On [Q-20](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation: friends, blocking, presence and an
inbox, which are the `social` and `notify` modules of the tree at the top of this page. Every change to friends or
blocks is one MySQL transaction that locks both players, lowest id first
([D-45](../architecture/03-decision-log.md#d-45--a-friendship-is-two-rows-and-a-change-to-it-locks-both-players-lowest-id-first)).

| Route | Answer |
|---|---|
| `GET /v1/friends` | `friends` (each `playerId`, `name`, `online`); `requests` to the player (the 100 newest); and `asked`, the requests made by them (each `playerId`, `name`, `expiresAt`, newest first) → 200 |
| `POST /v1/friends` `{"playerId"}` | asks; or, if the other player asked first, accepts → 200 `{"state": "asked" \| "friends"}`; 400 `yourself`; 404 `no_such_player`; 409 `already_friends`, `already_asked`, `friends_full` (either side at 100 friends), `you_blocked` (unblock them first), `too_many_asked` (50 out); 429 `too_soon` (20 an hour) |
| `DELETE /v1/friends/{playerId}` | ends the friendship, both ways → 200; 404 `not_friends` |
| `DELETE /v1/friend-requests/{playerId}` | declines theirs, or withdraws one's own → 200; 404 `no_request` |
| `GET /v1/blocks` | `blocked` (each `playerId`, `name`) → 200 |
| `POST /v1/blocks` `{"playerId"}` | blocks; this ends a friendship and any request in either direction → 200; 400 `yourself`; 404 `no_such_player`; 409 `blocks_full` (100 blocked) |
| `DELETE /v1/blocks/{playerId}` | unblocks → 200; 404 `not_blocked` |
| `GET /v1/inbox` | `items`, the 50 newest, newest first, each with `id`, `kind` (`friend_request`, `friend_accepted`, `team_invite`, `tournament_prize`, `team_application`, `season_reward`), `ref` (the other player's, the team's or the tournament's id; for an application, the applicant), `at`, `read` → 200 |
| `POST /v1/inbox/read` `{"upTo"}` | marks read every item up to that id → 200 |

**A blocked player is not told.** Their friend request is kept as theirs, and never shown to the player who blocked
them (plan item 57, below). Their party invitation is answered as sent, and is not delivered. Their team invitation is
treated the same way. A block is not a signal to the one blocked.

**Presence** is read with the list. A friend is `online` while their lobby connection is registered (`conn:{playerId}`,
03 §5): one store read per friend at first, one read for all of them since plan item 57 (below). There is no push when
presence changes.

**Pushes** to a player online: `evt.friend.request` `{playerId, name}` and `evt.friend.accepted` `{playerId, name}`;
and `evt.inbox` `{}` with each inbox item, which means "look".

**The inbox** (`inbox`, V14) holds a row for each thing a player should learn on their return.

- Each row is a kind and a reference, never text, so the client makes every word (FR-11): a friend request, a request
  accepted, a team invitation, a tournament prize (and since then, an application and a season's reward).
- One row per player, kind and reference: a repeat is ignored.
- It is written in the transaction of what it reports. For a prize it is written after the ledger's credit, and just
  as idempotently.
- Kept 30 days, then deleted by `worker`'s retention.

#### Built in this order

- (a) V14 (friends, requests, blocks, the inbox); friends and blocks, presence in the list, the pushes, and blocks
  honoured by party and team invitations (`persistence`, `platform`);
- (b) the inbox: its writes from friends, team invitations and tournament prizes, its API, and retention
  (`persistence`, `platform`, `worker`);
- (c) the client, and a live drill.

**(a) built 2026-09-30:**

- V14.
- `FriendRepository`: both players locked, lowest id first; a request lapses after seven days; a request made both
  ways meets as an acceptance; the caps on either side; a blocked player's request is `IGNORED` inside and answered
  `asked` outside.
- `FriendService`, with presence from `LobbyPush.connected`, and the two pushes.
- `/v1/friends`, `/v1/friend-requests`, `/v1/blocks`.
- A blocked inviter's team invitation is answered `OK` and not kept. Their party invitation is made but not pushed, so
  it lapses unseen.

**(b) built 2026-09-30:**

- `InboxRepository`.
- Items are written in the transaction of what they report, for a friend request, an acceptance and a team
  invitation. For a prize, they are written after the ledger's credit, whether that credit was the tick's own or an
  earlier tick's, so a tick that died between the two is repaired.
- `evt.inbox` with each new item; `GET /v1/inbox` and `POST /v1/inbox/read`.
- `worker`'s daily retention deletes items past 30 days.
- A team invitation to a player who has blocked the inviter is `IGNORED` inside, like a friend request, so nothing is
  pushed.

**(c) done 2026-09-30**, and drilled from the release (the drill's `social` scenario): a request pushed and in the
inbox, accepted; presence both ways; a block that ends a friendship and swallows the next request; the inbox read.

### Blocks unseen, and limits on asking (designed 2026-10-02, plan item 57)

The second audit found three ways in which the social layer said more than it should, or let one account say too much
([S-14, S-15, S-16](../defects.md#5-security-and-input)). Building the fix found a fourth (defect
[D-40](../defects.md#3-data-and-the-result-pipeline)).

**A blocked player's request is kept, and hidden** (S-14;
[D-56](../architecture/03-decision-log.md#d-56--a-blocked-players-friend-request-is-kept-and-hidden-from-the-one-who-blocked-them)).

- **Before**: it was answered `asked` and not kept. That made it differ from a kept request in three ways: it was
  missing from the asker's `asked`; asking again answered `asked` rather than 409 `already_asked`; and withdrawing it
  answered 404 `no_request`.
- **Now** it is written like any other request, counted against the asker's limits, and lapses in seven days. Only
  the player who blocked the asker never learns of it: no inbox item, no push, and their `requests` leave out anyone
  they block.
- Unblocking drops it, unseen, so nothing arrives late. Blocking drops requests in either direction, as before.

**Limits on asking** (S-15), on [Q-46](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation:

| | Limit | Refused |
|---|---|---|
| friend requests out, not lapsed | 50 per player | 409 `too_many_asked` |
| friend requests made | 20 an hour per player; every one counts, a request withdrawn and made again too | 429 `too_soon` |
| team invitations out, not lapsed | 20 per team | 409 `too_many_invited` |
| team invitations made | 20 an hour per player | 429 `too_soon` |
| team applications made (plan item 64) | 20 an hour per player | 429 `too_soon` |
| party invitations made | 60 an hour per player | 429 `too_soon` |
| requests to a player, as listed | the 100 newest | |
| invitations to a player, as listed | the 50 newest | |

- **The counts of what is out** are taken in the transaction that writes, under the players' (or the team's) locks, so
  two requests at once cannot both slip under the limit.
- **The hourly counts** are fixed windows in the store, as the login throttle's are: `rl:ask:{playerId}:{hour}`,
  `rl:tinv:{playerId}:{hour}`, and `rl:tapp:{playerId}:{hour}` for applications. They are taken before anything else
  is looked at, and they fail closed.
- A refusal is the asker's own: the player they asked is not told.

**Presence in one read.** The friends list asks the store about every friend in one `MGET` of their `conn:` keys, not
one read per friend.

**A party invitation tells presence only to a friend** (S-16). 404 `not_in_lobby` used to be answered to anyone,
before the block was looked at. That was a reading of presence, which Q-20 gives only to a friend. Now a friend of the
invitee is told it, since they see the same in their list. Anyone else is answered as if the invitation was sent. The
invitation is kept for its 60 s, and is pushed only if the invitee is in the lobby and has not blocked the inviter.

**Lapsed requests and invitations are deleted** (defect D-40). `worker`'s daily retention deletes friend requests and
team invitations past their seven days, a thousand at a time, using indexes on their expiry (V19).

## 10. Admin API

The **admin API** is the operator's way to inspect the backend and intervene: bans, kicks, closing rooms, notices,
tournaments, seasons, refunds, statistics and alerts. It is **built**, in the slices below. Every call, its answers and
its refusals are in the [platform README](../../backend/platform/README.md#the-admin-api) and the API reference
([api/03](../api/03-admin-api.md)). The leaderboard rebuild is a systemd unit,
`systemctl start backend-leaderboard-rebuild` (§7).

**The first design**, kept for its reasons. Its paths are not the built ones, and its audit went to MySQL, not to a
stream (D-30). As first designed:

`/api/admin/rooms`, `/api/admin/room/{id}/close`, `/api/admin/player/{id}/ban`,
`/api/admin/tournament/{id}/advance`, `/api/admin/reload-config`, `/api/admin/rebuild-leaderboards`.

Bound to loopback, behind a shared secret, reached over SSH. Every call writes to the `s:audit` stream, with 90-day
retention ([05 §2](05-worker-and-events.md#2-streams)): admin actions are the ones most worth being able to
reconstruct later.

### The first slice (designed 2026-09-29, plan item 7)

FR-9 asks that an operator can inspect and intervene, and the runbook's procedures wait for these endpoints. The first
slice is what `platform` can do alone:

| Call | What |
|---|---|
| `GET /admin/arenas` | the directory: each live arena, its players and rooms, and whether it speaks TLS |
| `POST /admin/players/{id}/ban` `{"until", "reason"}` | suspends the player until `until` (ISO-8601, in the future, else 400 `bad_until`), or, without it, bans for good. Ends every session the player has, and their lobby connection, with `evt.session.revoked`. If the sessions cannot be ended (the store failing): 503 `sessions_not_ended`. The ban is recorded, and calling again ends them |
| `POST /admin/players/{id}/unban` `{"reason"}` | lifts it |

**A store that fails** (designed 2026-10-06, plan item 86, [O-36](../defects.md#6-operations)).

- A call that needs the `session` store and does not get it is answered 503 `storage_unavailable`, with the message
  `the store did not answer: call again`, as when the database fails, and as the player API does (§1). This covers the
  listings, a room's close, a kick and a notice.
- Each call is safe to make again: a kick or a close made twice is one. Only a notice that the store took without
  answering goes out twice.
- Until then such a call was 500 `internal`, with the exception's text.
- **A ban whose sessions are ended, but whose player cannot then be taken out of the arenas**, is 503
  `not_taken_out`. The message says the ban is recorded and the sessions ended, and that the same call made again
  takes the player out. It used to be 500, so an operator could not tell that the ban had worked.

**Its own listener.** `BACKEND_ADMIN_ADDR` (host:port, on loopback), as the metrics have, and only when named: a
process given none takes no port.

**A shared secret.** `BACKEND_ADMIN_TOKEN` (or `BACKEND_ADMIN_TOKEN_FILE`, naming a file that holds it), in the
platform's settings file, as the database password is (D-102). It is sent as `Authorization: Bearer`, and compared in
constant time. An address named without a token, or an address that is not loopback, refuses the whole process's
start (exit 2). Both listeners' settings, the metrics' and this one's, are checked before the public API binds.

**Every call is audited in MySQL**, in `admin_audit` (V7): when, the call, its target (cut to 128 characters), what it
asked (cut to 1 024), and what it did. Refusals are audited too, since a refused call is one worth knowing about.
These are not audited: an unknown path, a wrong method, a bad `days`, a 500, and a 503 for a call that did nothing
(found while writing the API reference, 2026-10-06, DOC-22). A ban's 503s are audited with the ban, and a notice is
audited `sent` before it goes. The stream the design names needed j-redis streams (Phase 4); MySQL was here, durable,
and queryable by the same operator
([D-30](../architecture/03-decision-log.md#d-30--admin-calls-are-audited-in-mysql-until-there-are-streams)).

**Sessions are indexed by player**, `sess:of:{playerId}`: a set of tokens kept with the sessions. So a ban ends every
session at once, rather than at their next expiry. The gateway closes the player's lobby connection on
`evt.session.revoked`, as it does on `evt.session.replaced`, and the client does not reconnect.

When this slice was built, a player banned while in a match played it out: closing a room and taking a player out of
one needed a command channel to the arenas, which came with the second slice (`/admin/rooms` and the close). Reloading
configuration and the leaderboard rebuild stay what they are: a restart, and the rebuild's unit. The design's
`/tournament/{id}/advance` was not built: tournaments did not exist then. Tournaments came the next day (§6); an
operator creates one with `POST /admin/tournaments`, and the worker's clock advances it.

**Built 2026-09-29** (`platform/net/AdminServer`, `persistence/AdminRepository`, V7, `SessionStore.revokeAll`, the
gateway's close on `evt.session.revoked`, the client's end on it; the runbook's
[§3a](../operations/02-runbook.md#3a-the-admin-api)).

- A reason is required, at most 200 characters.
- The index of a player's sessions lives as long as the longest session. A token already ended stays in it until a
  ban: harmless, and not counted.
- Drilled live: a player in the lobby, banned over the admin API, was told, stayed out, could not log in, and could log
  in again once unbanned.

### The second slice: rooms (designed 2026-09-29, plan item 7)

| Call | What |
|---|---|
| `GET /admin/rooms` | every live arena's rooms: its name, its players, and a made match's id, mode and stage |
| `POST /admin/rooms/{arena}/{room}/close` `{"reason"}` | the room publishes what its players are owed, and sends them back with `Kick(7)`, removed. A made match that is closed is cut short, so it is not rated (D-29) |
| `POST /admin/players/{id}/kick` `{"reason"}` | the player is taken out of any match they are in, with `Kick(7)`; their stay is published as a leave |

A ban now also kicks, so a banned player does not play their match out.

**Arenas are told on a channel of their own**, `arena-admin:{name}`.

- Each arena subscribes to it on the session store, before its first announcement. A store that is not up yet does not
  end the arena's start: the subscription is made once the store answers
  (O-62, [D-94](../architecture/03-decision-log.md#d-94--a-start-waits-for-its-database-in-the-same-process-for-up-to-a-minute)).
- `platform` publishes `{"cmd": "close", "room"}` or `{"cmd": "kick", "player"}`.
- A kick goes to every live arena, since a player is in at most one and `platform` does not know which.
- The calls are answered 202, sent, with how many arenas heard. The arena acts on its room's next tick, and what it
  did is in its log and in the room's result.

**Rooms are seen through the directory.** Each announcement carries the arena's rooms, as a field of its entry, so the
list is as fresh as the three seconds between announcements.

**`Kick(7)`, removed by an operator**, is a new reason. An old client reads a reason it does not know as the server's
fault, and goes back through the lobby, which is as right as it can be. So the protocol's version stays 4.

#### Built in this order

- (a) the arena's channel: rooms in the announcement, close, kick, `Kick(7)`;
- (b) the admin calls, the ban's kick, the audit;
- (c) the client's `Removed`, and a live drill.

**Built 2026-09-29** (`ArenaAnnouncer` hears the channel and announces the rooms; `RoomRegistry.closeRoom` and
`remove`; `RoomThread`'s removals and its close's reason; `ArenaDirectory.command`, `listen` and `roomList`; the admin
calls). A player taken out ends their stay as a `Leave` does, so it is published at once and cannot be resumed. A
closed room leaves the registry at once, so nobody is placed in it while it stops.

**Every stay, and the ticket not yet used** (designed 2026-10-02, plan item 55, defect T-35). The kick used to look
only at the players connected. So a player whose socket had just dropped was held for a resume, which needs its secret
and no session, and came back to play the match out. And a player banned after `evt.match.found`, but before joining,
was let in, since the kick had found them nowhere. Now a removal:

- **also ends a stay that is waiting for a resume**, as a leave: it is published and its secret forgotten;
- **for a ban, is remembered by every arena for a ticket's life, 60 s.** A join with a ticket of that player in that
  time is refused with `Kick(7)`. No ticket issued before the ban outlives that minute, and the ban revoked the
  sessions that could ask for another. The ban's kick says so (`"ban": true`).

A kick is not remembered: it takes a player out of their match, and they may play again at once.

### The third slice: a notice to the lobby (designed 2026-10-01, plan item 44)

On [Q-39](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation: the one way to warn everyone before
a stop.

| Call | What |
|---|---|
| `POST /admin/notice` `{"text", "reason"}` | every player in the lobby, on every gateway, is pushed `evt.notice` `{"text"}` → 202, with how many gateways heard; 400 `invalid_text` for no text, or more than 200 characters |

**A channel every gateway listens on**, `push:all`, beside each gateway's own `push:{gateway}`. `platform` publishes
the notice once. Each gateway hands it to every connection it holds through `Pushes` (03 §8), so a slow client's
backpressure holds for it too. The text is the operator's own, not a phrase: D-14 is about what players say to each
other. It is audited as every admin call is, with its text in the audit row.

**Built 2026-10-01** (`LobbyPush.broadcast` and `ALL_CHANNEL`; the gateway's subscription and
`ConnectionRegistry.broadcast`; `AdminServer.notice`). Tested through the admin API and through a running gateway;
mutation-checked, 8 of 8 caught. Drilled from the release (the `notice` scenario): two players were told one notice,
word for word.

### Whether players come back (designed 2026-09-30, plan item 25)

| Route | Answer |
|---|---|
| `GET /admin/stats?days=N` | by day, newest first, N from 1 to 60 (14 if absent), today so far included: `{"days":[{"day","active","newPlayers","d1","d7","d30"}]}`. A day-N figure is `null` until its day has ended → 200; 400 `invalid_days` |
| `GET /admin/stats/features?days=N` | the same days, each day's new players split by what they did on that first day: `{"days":[{"day","newPlayers","features":{"queued":{"players","d1","d7","d30"},"bought":…,"boosted":…,"tournament":…,"friend":…,"team":…}}]}` → 200; 400 `invalid_days` |
| `GET /admin/stats/funnel?days=N` | by the day accounts were made, newest first, N from 1 to 60 (14 if absent): `{"days":[{"day","registered","guests","played","returned","level5","rated","bought","paid"}]}`, each step counted as of now ([05 §11](05-worker-and-events.md#the-funnel-designed-2026-10-04-plan-item-76-c), plan item 76 (c)) → 200; 400 `invalid_days` |
| `GET /admin/stats/guests` | `{"players","guests","inactive"}`: the guests never upgraded, and those of them idle 90 days ([05 §11](05-worker-and-events.md#guests-measured-designed-2026-10-04-plan-item-76-c), Q-22). Since D-87, retention erases a guest idle 180 days (§1), so `inactive` measures what the next 90 days will erase → 200 |

What each figure counts, and how, is in [05 §11](05-worker-and-events.md#11-analytics-designed-2026-09-30-plan-item-25)
(Q-24, Q-26, D-47). These calls are audited, as every admin call is, reads included.

### Checked as the player API checks (designed 2026-10-02, plan item 60 (a))

[S-19](../defects.md#5-security-and-input): the admin API, behind loopback and its token, checked its input less than
the player API did. Now:

- **Every body is read up to 4 KB**, a tournament's too. Past that it is not read, and the call is refused as one
  without a reason (a notice as one without a text, since its text is checked first: `invalid_text`).
- **A tournament's name and a notice's text are shown to every client as they are**, so they may hold no control,
  format or line separator character (Unicode's Cc, Cf, Zl and Zp; the direction marks and overrides are format
  characters): 400 `invalid_tournament`, `invalid_text`.
- **A notice is audited, then sent.** If the database fails at that moment, nothing is sent, and the operator is
  answered 503 `storage_unavailable`, rather than a notice going out that no row records (D-30). The row says `sent`
  even for a notice that the store then failed to send; the operator's answer shows the failure.
- **A call's method is checked as the player API's are.** A known path asked with another method is 405, with
  `Allow`. `/admin/seasons` is GET only, and audited as every call is; `/admin/tournaments` is POST only.

## 11. What to measure

What `platform` should measure, why, and what is built. The watch (§12) reads these metrics.

| Metric | Why |
|---|---|
| Matchmaking wait time p50/p95 per mode | the number players actually feel in the lobby |
| Queue depth per mode and bucket | a widening rating band with no matches means the population is too thin for that mode |
| Ticket claim rate | unclaimed tickets mean clients are failing to reach arenas. For made matches, these are the no-shows a penalty would answer ([Q-38](../requirements/01-scope-and-nfrs.md#7-open-questions): no penalty until no-shows pass 5 % of a week's match tickets) |
| Room fill distribution | shows how full the rooms run: as built they fill one after another, to 145 of 150 |
| Purchase failures by cause | insufficient funds is normal; anything else is a bug |
| Request latency by module, p99 | finds the module that will need splitting out first |

**Built** (`PlatformHttpServer.registerMetrics`, and `Matchmaker` for the queue's):

| Metric | What it counts |
|---|---|
| `backend_platform_responses_total{status}` | answers, by HTTP status |
| `backend_platform_logins_total{outcome}` | logins: `ok`, `invalid_credentials`, `banned`, `throttled`, `busy` |
| `backend_platform_purchases_total{outcome}` | purchases: `bought`, `already_bought`, and each refusal (the purchase row above) |
| `backend_platform_hasher_line` | the hasher's line: 168 is full (S-7) |
| `backend_platform_queue_wait_seconds{mode}` | 2026-09-29: a histogram of each player's wait, from queueing to a match found; the server works out p50 and p95. Buckets from 1 s to 10 min |
| `backend_platform_queue_players{mode}` | 2026-09-29: the players left waiting after the last round. Only the platform that holds the matcher's lease reports it; the others report 0 |
| `backend_platform_confirms_total{outcome}` | 2026-09-29: how each confirm step ended: `made`, `declined`, `withdrawn`, `lapsed`, `no_room` |
| `backend_platform_matches_made_total{mode}` | matches made (§4) |
| `backend_platform_request_seconds{route}` | 2026-09-29: a latency histogram per route. `route` is the API's path (`/v1/party`), not the one a client sent, so there are as many series as routes. Buckets from 5 ms to 2.5 s |
| `backend_platform_open_tickets_issued_total`, `backend_platform_match_tickets_issued_total{mode}` | 2026-09-29: tickets issued, for the public arena and for made matches |
| `backend_platform_pushes_unheard_total` | pushes that no gateway heard (03 §5) |

- **The queue is not bucketed by rating**, so queue depth is per mode.
- **Ticket claims** are read against the arenas' `backend_arena_joins_total{outcome}` (`joined`, `bad_ticket`,
  `no_room`, `claim_failed`, and `removed` for a join refused after a ban, §10). Tickets issued, less joins with a
  ticket, is what never arrived, over an hour or so, since a ticket lives sixty seconds.
- **Room fill** has no metric per room. The arena's `backend_arena_players` and `backend_arena_rooms` give its players
  and rooms, so the mean fill. Each room's players are in the arena's `roomList`, which `GET /admin/rooms` shows (§10).

## 12. The watch: monitoring and alerts (designed 2026-10-24, plan item 100)

**The watch** is the backend's own monitoring
([D-86](../architecture/03-decision-log.md#d-86--monitoring-is-the-backends-own-a-watch-in-every-platform-the-runbooks-rules-in-code-alerts-in-the-store-sent-by-webhook)).
The owner refused third-party monitoring, such as Prometheus, even bundled. So the watch is a component of the
platform process, `platform/watch`. On every machine, the platform:

1. reads what its machine exposes;
2. evaluates the runbook's rules;
3. keeps the alerts in the store, where every machine can list them;
4. sends them to a webhook.

The operator's view is [operations/08](../operations/08-monitoring.md).

### What it reads, every 15 s

| Source | How | Setting |
|---|---|---|
| this machine's processes' metrics | `GET /metrics` on each loopback address, parsed from the text format (`common/MetricsText`). A target that does not answer within 2 s is a failed scrape, and `up` 0 | `BACKEND_WATCH_TARGETS`: `job=host:port` pairs, comma-separated; `arena` and `worker` may repeat; the platform names its own too. Unset: the watch is off, and says so at start |
| this machine's file systems | `FileStore` of each path: `machine_disk_free_bytes{mount}`, `machine_disk_size_bytes{mount}`, one series per distinct file system | `BACKEND_WATCH_DISKS`: paths; default `/,/var/lib,/var/log,/opt/backend` |
| this machine's memory and load | `OperatingSystemMXBean`: `machine_memory_free_bytes`, `machine_memory_size_bytes`, `machine_load_1m`, `machine_cores` | none |
| the other machines | `GET /health` on each peer's platform, within 2 s: `peer_up{peer}`, 1 or 0 | `BACKEND_WATCH_PEERS`: `name=host:port` pairs. On the one launch machine there are no peers (D-93) |

- The series that a rule needs are kept for an hour (240 samples each), in a ring per series. The rest are read and
  dropped.
- The machine's own name on every alert is `BACKEND_WATCH_MACHINE`, or else the host name.
- A setting that is not in its form (a pair without `=`, an address without a port) refuses the start.

### The rules

Each rule is one `Rule`, and `Rules` makes them all. A rule has a `name`, a `summary`, a `runbook` (the runbook
section to open) and its wait (`for`). Its `evaluate(view, now)` returns the labels of each series that fires. The
thresholds are those of [runbook §5](../operations/02-runbook.md#5-what-to-watch) and
[01 §7](../operations/01-deploy.md#7-installing-a-machine), and change there and here together:

| Alert | Condition | For |
|---|---|---|
| `TargetDown` | a scrape failed (`up` 0), job not a peer | 2 min |
| `PeerUnreachable` | a peer's `/health` failed | 2 min |
| `StoreConnectionDown`, `EventsStoreConnectionDown` | `backend_store_connected`, `backend_events_store_connected` 0 | 1 min |
| `StoreWithoutReplica`, `EventsStoreWithoutReplica` | `…_replicas` 0 | 1 min |
| `StoreReplicaSilent`, `EventsStoreReplicaSilent` | `…_replica_ack_seconds` > 30 | at once |
| `GatewaySubscriptionDown` | `backend_gateway_store_subscribed` 0 | 1 min |
| `PushesUnheard` | `backend_platform_pushes_unheard_total` rose in 10 min | at once |
| `ResultSetAside` | `backend_worker_dead_letter_depth` > 0 | at once |
| `ResultsBacklogGrowing` | `backend_worker_queue_depth` > 100 and higher than 10 min ago | 10 min |
| `WorkerFailing` | `backend_worker_failed_total` rising over 5 min | 5 min |
| `ResultTrimmedUnapplied` | `backend_worker_trimmed_unapplied_total` rose in the hour | at once |
| `ResultsUnreplicated` | `backend_arena_results_unreplicated_total` rising over 10 min | 10 min |
| `TournamentsStalling` | `backend_worker_tournament_failures_total` rose by more than 5 in 30 min | 5 min |
| `MysqlReplicaLagging`, `MysqlReplicaDown` | `backend_mysql_replica_lag_seconds` > 30; `backend_mysql_replica_up` 0 | 2 min |
| `LedgerMismatch`, `LedgerCheckStale` | `backend_worker_ledger_mismatches` > 0; the check older than 2 days | at once |
| `HasherLineFull` | `backend_platform_hasher_line` ≥ 168 | 1 min |
| `TickSlow` | `backend_arena_tick_p99_seconds` > 0.015 | 5 min |
| `ArenaCrashLoop` | `backend_process_uptime_seconds` of an arena fell twice in the hour | at once |
| `DiskFilling`, `DiskFillsWithinAWeek` | free under 20 % of size; a straight line through the hour's free bytes reaches 0 within 7 days | 5 min; 1 h |
| `MemoryLow` | free under 10 % of total | 10 min |
| `BackupDumpStale`, `BackupProofStale`, `BackupOffsiteStale`, `BackupFailed` | `backend_backup_succeeded_timestamp_seconds{kind}` older than 26 h, 8 days, 26 h; `backend_backup_failed` 1 | at once; 1 h |
| `RestoreTooSlow` | `backend_backup_restore_seconds` > 7 200 | at once |
| `TableTooBig`, `RetentionSlow` | `match_player` past 500 million rows; a retention run over 1 800 s | at once |
| `CertificateExpiring` | a certificate expires within 14 days: an arena's own (`backend_arena_tls_certificate_expiry_timestamp_seconds`), or the one this machine's nginx shows clients, as the gateway reads it (`backend_gateway_edge_certificate_expiry_timestamp_seconds`) | at once |

Each rule's test feeds it a sequence of scrapes, and expects it to fire at its threshold and its wait, and not before
(`RulesTest`), as `promtool test rules` would. A rule's threshold is a constant in its definition.

### Alerts: pending, firing, resolved, sent

- **State.** A rule's series that has fired for less than its wait is *pending*; for its wait or longer, *firing*. A
  firing alert whose condition stops holding is *resolved* on that round, and forgotten after the resolution is sent.
  The fingerprint is the alert's name and labels (`job`, `instance`, `mount`, `peer`, …). A target's series and the
  machine's own series carry `machine` as a label; a peer's series does not. So a `PeerUnreachable` for machine C is
  one alert, whichever machine raised it.
- **In the store.** Each firing alert is `alert:{fingerprint}`, a JSON string (`name`, `labels`, `summary`, `runbook`,
  `since`), set with a 60 s expiry each round. So a platform that dies takes its alerts away within a minute, while the
  others raise `PeerUnreachable` for it.
- **Listed.** `GET /admin/alerts` scans `alert:*` and returns every machine's firing alerts, with `silenced` where a
  silence covers one ([api/03](../api/03-admin-api.md)).
- **Silenced.** `POST /admin/silences {"alertname", "hours", "reason"}` writes `silence:{alertname}` with that expiry,
  audited (D-30). `GET /admin/silences` lists them; `DELETE /admin/silences/{alertname}` ends one. A silenced alert is
  listed and logged, but not sent.
- **Logged.** `ALERT firing {name} {labels}: {summary}` and `ALERT resolved …`, at WARN, in the platform's journal.
- **Sent.** When `BACKEND_ALERT_WEBHOOK_URL` (or `_FILE`) is set, the watch sends a `POST` of
  `{"status": "firing"|"resolved", "machine", "alerts": [{"name", "labels", "summary", "runbook", "since"}]}`:
  - for what changed this round;
  - and again every `BACKEND_ALERT_REPEAT_HOURS` (default 4, 1 to 24) for what still fires.

  The URL must be `http` or `https`, with a host, or the start is refused. The webhook has 10 s to answer, and is tried
  once more at once. A failure is logged and counted, and its claim is given back, so the next round (15 s later), or
  another machine, sends it.
- **Once for the site.** Before sending, the watch sets `SET alertsent:{fingerprint}:{status} NX PX <repeat>` (for a
  resolution, an hour). The platform that gets the key sends; the others count a `duplicate`. So a `PeerUnreachable`
  for C, raised by A and by B, is sent once.
- **Its own metrics**: `backend_watch_scrapes_total{job, outcome}`, `backend_watch_alerts_firing`,
  `backend_watch_notifications_total{outcome}` (`sent`, `failed`, `duplicate`, `silenced`), and
  `backend_watch_round_seconds`.

### What each machine last saw (designed 2026-10-24, plan item 102)

Each round, the watch also writes its view to the store, as `watch:{machine}`, a JSON object kept for a minute:

| Field | Holds |
|---|---|
| `at`, `machine`, `roundSeconds` | when, which machine, and how long the round took |
| `targets` | each `job`, `instance`, `up` |
| `peers` | each `peer`, `up` |
| `disks` | each `mount`, `freeBytes`, `sizeBytes` |
| `memory` | `freeBytes`, `sizeBytes` |
| `load1m`, `cores` | the machine's load and cores |
| `alertsFiring` | how many alerts fire |

`GET /admin/watch` lists every machine's view, newest first. So an operator reads the site's state from any machine,
with no metrics port to query. A machine whose view is older than a minute is absent, and the others' `peers` say so.

**Reads in parallel.** The targets and the peers are read on virtual threads, one each, and the round waits for all of
them. So a round is bounded by the 2 s timeout plus the rules, not by the number of targets. Read one after another,
C's six targets and two peers all down would take 16 s (eight reads at the 2 s timeout, derived), longer than the
15 s round.

### What it costs

A round is one HTTP request per target, on loopback, in parallel, and a few system calls: 0.15 s with four targets on
the drill's stack (08 §7). The series kept are the rules' alone, a few hundred doubles each. The watch's thread is its
own, scheduled with a fixed delay. A round that throws is logged, and the next round runs.
