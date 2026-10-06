# 04 — Platform services

Everything a player does between matches: accounts, profiles, teams,
matchmaking, room allocation, tournaments, leaderboards, shop, inventory and
equipment.

`platform` is a **modular monolith**, not a cluster of services
([D-4](../architecture/03-decision-log.md#d-4--split-by-workload-shape-not-by-business-domain)).
Modules are plain classes sharing a `Context` (j-redis client, MySQL
repositories, configuration, scheduler). No dependency-injection container.
Each owns its tables and its j-redis key namespace, and exposes a small Java
interface.

```
platform
 ├── net          HTTP API behind this machine's nginx; the gateway calls it for match requests (03-gateway.md)
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
 └── notify       pushes via the gateway; inbox for offline players
```

**What is deliberately *not* here:** applying match results, granting rewards,
advancing tournament rounds, analytics. Those are throughput-bound and
retry-heavy, so they live in `worker`
([05-worker-and-events](05-worker-and-events.md)). The split is by workload
shape, not by domain — `tournament` appears in both because its API is
request/response and its clock is a scheduled job.

Threading: the JDK's HTTP server, with a virtual thread per request (they block
on MySQL, which is what they are for). Periodic work: the matcher's round, once
a second on a thread of its own, whichever platform holds the lease (§4).

**As built**, route by route with every answer and refusal: the
[platform README](../../backend/platform/README.md); the flows are drawn in
[diagrams/04-platform](../diagrams/04-platform.md). Migration numbers below
(V1 to V34) are the schema's history as each part was built; on 2026-10-04 they
were squashed into one baseline, `V1__schema.sql` and `V2__seed.sql`
([D-75](../architecture/03-decision-log.md#d-75--the-migrations-are-squashed-into-one-baseline-before-the-first-launch)).

## 1. Auth and sessions

- **Register**: username plus password, hashed with **Argon2id** (Bouncy
  Castle).
- **Guests** (designed 2026-09-30, plan item 22,
  [Q-22](../requirements/01-scope-and-nfrs.md#7-open-questions),
  [D-46](../architecture/03-decision-log.md#d-46--a-guests-credential-is-a-random-key-kept-as-its-sha-256-under-a-username-nobody-can-choose)):
  `POST /v1/guests` makes one → 201 `{playerId, guestKey, displayName}`, the
  name "Guest" and four digits, 429 throttled per address as registering is;
  `POST /v1/sessions` `{"guestKey"}` logs it in, answered as a login, 401
  `invalid_credentials`, 403 `banned`; `POST /v1/accounts/upgrade`
  `{"username", "password", "displayName"}` with the guest's session makes it a
  full account, the same player → 200; 400 `invalid_username`,
  `invalid_password`, `invalid_display_name`; 409 `username_taken`,
  `not_a_guest`. The key stops working at the upgrade.
  **(a) built 2026-09-30.** V15; `AccountRepository.createGuest`, `findGuest`
  and `upgrade` (the username, the hash and the key cleared in one statement,
  guarded by the key being there, so a second upgrade is `not_a_guest`);
  `AuthService.createGuest`, `loginGuest` (the key decoded, hashed and looked
  up, no slow hash; a malformed key is `invalid_credentials`) and `upgrade`
  (registering's rules checked before the password is hashed, in the hasher's
  line); the routes above. A guest's own username cannot be logged into: login
  refuses a name the rules forbid before any lookup, and its password hash is
  empty.
- **Login**: verify against `account`, then create a session in j-redis:
  `MULTI · HSET sess:{token} playerId … createdAt … · EXPIRE · EXEC`, with the
  day's TTL jittered by ±10 % (77 760 to 95 040 s, below), where the token is
  32 random bytes, base64url.
- **One active lobby connection per player**, enforced at the gateway
  ([03 §4](03-gateway.md#4-connection-lifecycle)).
- **Presence**: designed as `SET presence:{id} lobby|arena:{roomId} EX 60`,
  refreshed by heartbeat; built otherwise (§9, the social layer): a friend is
  online while their lobby connection is registered.

**Built** (§10, 2026-09-29): an operator's ban ends every session and the lobby
connection, and a kick takes the player out of every arena. As first designed: a ban sets
`account.status` and publishes a kick. The session is *not*
re-validated on every request — that would make a store blip a mass logout
([03 §9](03-gateway.md#9-failure-behaviour)) — so a ban takes effect via the
push, with the next login as the backstop.

**Implemented** (`platform/AuthService`, `PasswordHasher`, `DisplayName`,
`LoginThrottle`; `handoff/SessionStore`; `persistence/AccountRepository`). Eight details
that are not obvious from the sentences above, and that the code and its tests
now pin down:

**Argon2id costs 62–88 ms and that is the system's tightest CPU limit.**
Measured at m=19 MiB, t=2, p=1 (the OWASP minimum, not the 64 MiB desktop
profile) across repeated runs on the shared development VM; the spread is the
machine, not the code, and the capacity figure below uses the slow end. Argon2 is memory-hard on purpose, so concurrency is capped
at 8 — otherwise a login burst claims 19 MiB each and becomes an
`OutOfMemoryError` rather than a queue. That gives roughly **90 password logins
per second per `platform` process**, using 8 cores while it does. It is enough
because the common path is a session resume, which is one `HGET`; it is not
enough for 50 000 players logging in inside a minute. Re-measure on production
hardware before assuming otherwise ([Q-3](../requirements/01-scope-and-nfrs.md)).

**Session TTLs are jittered by ±10 %.** A fixed 24-hour TTL reproduces any
launch or restart window exactly one day later, when every session issued in it
expires together — and the path that herd lands on is the 88 ms one. Ten per
cent of a day smears it over about five hours.

**The stored hash carries its own parameters**
(`$argon2id$v=19$m=…,t=…,p=…$salt$hash`), so the cost can be raised later
without invalidating every password at once. **And a login rehashes it** when
its parameters are not the current ones (built 2026-09-26): after the password
has verified, and only if the stored hash is still the one that verified, so a
password changed meanwhile is never put back. Before, a raised cost protected
new passwords only. Expect it to show: after a raise, each account's next login
costs two hashes, so the hasher's line runs fuller until most players have
logged in once.

**An unknown username costs the same as a wrong password.** Login verifies
against a decoy hash when no account matches; otherwise the unknown case
returns in microseconds and the known case in tens of milliseconds, which is a
username oracle for anyone with a clock. Both answer `INVALID_CREDENTIALS`, and
so does a wrong password on a banned account. **It holds while every stored
hash is at the current cost.** After a cost raise, a wrong password on an
account not yet rehashed is checked at its old, cheaper cost, while an unknown
username is checked against the decoy at the new one, so the two can be told
apart by timing until those accounts have logged in once.

**A display name is checked, and refused rather than repaired.** It is the one
piece of player-written text every other player sees, in the kill feed and on a
public leaderboard. The rules are RFC 8266 (PRECIS, Nickname profile), with the
combining-mark checks of UTS #39 §5.4:

| Rule | Why |
|---|---|
| NFKC, spaces collapsed and trimmed | Two spellings that look the same are stored the same: a composed é and e plus an accent; fullwidth Ａ and A; combining marks typed in either order |
| Letters, marks and decimal digits of any script, plus space `_` `-` `.` | Players are international. Symbols and emoji are refused: they render differently per device |
| No control, format, private-use, unassigned or **default-ignorable** character | Direction overrides and zero-width characters are format characters. The Hangul filler and variation selectors are classed as a letter and as marks, render as nothing, and needed an explicit list: Java has no query for the property |
| An accent follows a letter; ≤ 4 nonspacing marks in a row; never the same one twice in a row | Stops a tower of accents covering the rows above and below. Hindi needs three on one consonant, so the first cut's limit of two refused real words |
| At most one of Latin, Cyrillic, Greek | A deliberate subset of TR39's confusable detection: stops a Cyrillic a (U+0430) posing as a Latin a, not every lookalike |
| 1–16 code points | 16 × 4 UTF-8 bytes = 64 = `Wire.MAX_NAME_BYTES`, so every accepted name fits the kill feed |
| A short reserved list, compared case- and width-folded with separators removed | "killed by System" is staff impersonation |

The check runs before the password is hashed, so a request that will be
refused never costs 88 ms. The HTTP answer is 400 `invalid_display_name`, with
a message naming the rule broken, so a client can tell the player what to
change. No display name falls back to the username, cut to 16 and checked the
same way, which means the username `admin` registers only with a display name
of its own.

**Registration and login are throttled, before Argon2 runs.** Two fixed-window
counters in j-redis, so they hold across all three `platform` processes:

| Limit | Counts | Stops |
|---|---|---|
| 30 a minute per source address (IPv6: per /64) | every registration and login | one machine spending the Argon2 budget, or guessing fast |
| 10 a quarter hour per account | every login naming it, existing or not | guessing one account from many addresses |

Beyond either: 429 `too_many_attempts` with `Retry-After`. The address is the
TCP peer's, or, when the peer is on the same machine (nginx), the **last**
entry of `X-Forwarded-For`, the only one nginx wrote. It is parsed as an IP
literal and never resolved. The store not answering refuses the attempt (503),
because a limit that fails open is off exactly when it is attacked hardest.
Measured across two instances sharing a store: sixteen guesses at one account
from sixteen addresses gave ten 401s at 110–160 ms and then six 429s at
16–27 ms, so a refusal costs no hash.

Why these numbers, and what they cost:

- **30 a minute, not the 5 first designed** ([03 §6](03-gateway.md#6-rate-limiting-and-abuse)).
  Mobile carriers put many subscribers behind one public IPv4 address, and a
  password login is rare anyway: resuming a session costs none. 30 a minute is
  still 43 000 a day from one address.
- **Every attempt counts, not only failures,** so the count is taken before the
  password is checked and concurrent guesses cannot all slip under it.
- **An account can be locked out on purpose:** ten wrong guesses a quarter hour
  keep its owner from logging in with the right password (the drill got 429,
  `Retry-After: 750`). A player already holding a session is unaffected. This
  is the accepted cost of a per-account limit; the alternative is unlimited
  guessing from a botnet.
- **Not stopped:** credential stuffing spread thinly over many addresses and
  accounts. That needs breached-password checks or a second factor.

**The line for the hasher is bounded, and a full line answers at once.** At
most 8 hashing and 160 waiting per process; beyond that, 503 `busy` with a
`Retry-After` of one to five seconds, spread so the turned-away do not return
together. Admission comes before the throttle counts, so an attempt turned away
for load never counts against the account. Without it, a mass re-login (every
session lost at once) collapses: once the line is longer than a client will
wait, every hash finishes for a client that has gone, and the retries queue
behind them. Measured on one process, 3 000 logins at once, clients retrying
after a 10 s timeout:

| | Unbounded | Bounded |
|---|---|---|
| logged in | 405, all in the first 10 s, then none for 60 s | all 3 000 by 47 s |
| clients that gave up | 13 803 | 0 |
| server CPU | 1 175 s, nearly all for clients already gone | 314 s, none wasted |
| accounts locked out by their own retries (at a 3 s timeout) | 251 of 600 | 0 |

**What a client must do**, and the Unity client must be written to it: allow
a login **10 seconds**; on 503 or 429 wait the `Retry-After` it was given; on a
timeout or a dropped connection wait one to three seconds, chosen at random,
before retrying. The line's depth and that timeout are a pair: against a 3 s
timeout the same line was too deep for the development VM.

**A username only names an account if registration would accept it.** Login
refuses anything else before the lookup. The lookup compares under
`utf8mb4_0900_ai_ci`, which is accent-insensitive: "ada" with an accented first
letter logged into `ada`, and would have given the per-account limit a fresh
count for every spelling.

### Routes matched as nginx limits them (designed 2026-10-02, plan item 60 (a))

The second audit found two ways past what nginx and the API mean
([S-17, S-18](../defects.md#5-security-and-input)):

- **An id in a path is at most 18 digits.** The routes took 19, and one above
  the largest long threw in the parse: a 500 and a logged trace, five routes,
  one without logging in. Every 18-digit number fits a long and every id this
  system makes is far shorter, so a longer one names no route: 404
  `no_such_route`, as any path that names nothing.
- **The routes nginx limits are matched exactly.** The JDK's server matches a
  context by prefix, so `POST /v1/sessions/` logged in past nginx's exact
  `location = /v1/sessions`. `/v1/sessions`, `/v1/accounts`, `/v1/guests` and
  `/v1/accounts/upgrade` now answer only their own path, 404 for anything after
  it, and nginx limits the last two as it does the first two
  (`deploy/nginx/backend.conf`).
- **An upgrade checks before it hashes**: that the account is a guest and the
  name free, in one read, then the password's hash, then the upgrade, which
  checks both again as it writes. A refusal no longer costs a hash.

### A store that fails is 503, however it was reached (designed 2026-10-02, plan item 60 (c))

A request the database or a store cannot serve is answered 503
`storage_unavailable`: nothing is wrong with the request, so the client comes
back rather than reports a fault. A store call made through the server's own
wait was answered so; one on a leased connection (a party's change, the
matcher's watched steps) throws the client's own exceptions, which were taken
for a bug, 500 `internal` ([O-12](../defects.md#6-operations)). Now the client's
connection failures, its timeouts, a client closed, and the two refusals a
store gives when it cannot take a write (`READONLY`, a primary demoted under the
request; `NOREPLICAS`, too few replicas acknowledging), alone or as the reason a
watched change was discarded (`EXECABORT`), are 503 by whichever path they came.
Any other refusal from the store is a request it could not run, a bug: 500
still.

### Renaming (designed 2026-10-02, plan item 63)

A player's display name is chosen once, at registration, or made for a guest
(`Guest` and four digits); a team's at its making. Neither could change.

| | |
|---|---|
| a player | `PUT /v1/accounts/name` `{"displayName"}`, bearer session → 200 `{"playerId", "displayName"}`; 400 `invalid_display_name` by the rules above; 429 `too_soon`, as an asking limit is |
| a team | `PUT /v1/teams/mine/name` `{"name"}`, by its leader → 200, the team; 400 `invalid_name`; 403 `not_allowed`; 409 `name_taken`; 429 `too_soon`; every member pushed `evt.team.update` (§2) |
| how often | **free, once in 30 days**, each (`player.name_changed_at`, `team.renamed_at`, V23); the first at once, so a guest's made name, or one regretted, changes the day it is chosen |

**Why free, and why 30 days** (a first cut; the owner made the economy's
balance Claude's to choose, Q-48). A name is how other players know someone on a
board and in the kill feed: a month keeps a board's names recognisable from one
look to the next, and makes a name used to pass as another, or to shed one's
record with others, slow to repeat. A price would be a sink for coins, but the
player who needs a rename most is the new one, whose name was made for them,
and has fewest coins. One constant each, `RENAME_EVERY`.

**What changes at once and what after.** The name in MySQL is the truth, and
everything read from it shows the new name from the rename on: friends, teams,
tournaments, the inbox; the rating boards within their thirty seconds' cache. The
score boards' names (`lb:name`) are written by the rename, and by `worker` from
the name MySQL holds when it applies a result, read as it locks the player, not
the name the result carries: a match that began before the rename would
otherwise write the old name back when it ends
([D-60](../architecture/03-decision-log.md#d-60--a-boards-name-is-the-one-the-database-holds-when-a-result-is-applied)).
Copies made before the rename keep the old name until they are made again: a
party's (until it next changes), a queued entry and a match under way (from the
next match). A rename and a result applied in the same instant can leave a board
with the old name until the player's next match.

## 2. Teams

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

Rules: at most two vice leaders, capacity 30, one team per player, name unique
case-insensitively, and a 24-hour cooldown after leaving before joining another
(anti-hopping). All configurable.

Every action is a single MySQL transaction that locks the team row first:

```sql
START TRANSACTION;
SELECT id, member_count, version FROM team WHERE id = ? FOR UPDATE;
-- check capacity, role and cooldown in code
INSERT INTO team_member (team_id, player_id, role) VALUES (?,?,?);
UPDATE team SET member_count = member_count + 1 WHERE id = ?;
UPDATE player SET team_id = ? WHERE id = ?;
COMMIT;
```

`team_member` is the source of truth; `player.team_id` is a denormalised
pointer maintained in the same transaction. Locking the team row makes
capacity a real constraint rather than a race — two simultaneous joins to a
team with one slot left cannot both succeed.

Team communication uses the **fixed phrase list**, like everything else
([D-14](../architecture/03-decision-log.md#d-14--communication-is-a-fixed-phrase-list-never-free-text)).
History is `LPUSH teamchat:{id}` capped at 200 entries in j-redis — phrase ids,
not text.

### The first slice (designed 2026-09-30, plan item 17)

On [Q-14](../requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation: the table above, less applications, tags and settings, and
invitations as the way in. **Built 2026-09-30**, and drilled from the release.

- **Create**: a player in no team, and not within 24 hours of leaving one,
  names a team and leads it. The name follows a display name's rules
  (`DisplayName.check`) and is unique by the column's collation, which ignores
  case and accents.
- **Invite**: a leader or vice leader names a player by their id, as a party
  invitation does: a player knows their own and sees others' on the boards,
  while the public code is shown by no API yet (found building the client).
  The invitation lasts seven days, one a player a team. **Accept**: the player, in
  no team and past the cooldown, joins as a member if the team is below its
  capacity; the invitation is spent. **Decline**: it is dropped.
- **Leave**: anyone but a leader with members; the cooldown starts. **Kick**: a
  leader removes anyone, a vice leader only members; the kicked one's cooldown
  starts too. **Promote** to vice leader and **demote**, by the leader, two
  vice leaders at most. **Transfer** the leadership to a member, the old
  leader becoming one. **Disband**, by the leader: every member out, cooldowns
  started, invitations dropped.

Every action is one transaction that locks the team row first, then the
players it changes (D-39), so capacity and the vice leaders' limit hold under
concurrent requests, and the lock order is one, never two.

**Only the players it changes** (plan item 51, defect D-32): disbanding clears
its members by their ids, read from `team_member` in ascending order, each
player's row locked by its key. It cleared them by `player.team_id`, which has
no index: a walk of the whole table that, under REPEATABLE READ, kept every
player's row locked to the commit, so no result was applied and nothing bought
while a team was disbanded.

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

Q-14 left the way in to invitations, "applications later". On
[Q-49](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation a
player may also ask a team to take them. Found designing: no API let a player
find a team, whose id showed only in a tournament's entries; applications come
with a search.

| | |
|---|---|
| `GET /v1/teams?name=<start>` | up to 20 teams whose name starts so, by the name's collation (case and accents ignored), by name → 200 `{"teams": [{"id", "name", "members", "rating"}]}`; 400 `invalid_name` for an empty start or one over 16 characters |
| `GET /v1/teams/{id}` | the same for one → 200; 404 `no_such_team` |
| `POST /v1/teams/{id}/applications` | by a player in no team and past the cooldown → 200; 404 `no_such_team`; 409 `in_team`, `cooling_down`, `already` (one a team, a declined one until it lapses), `team_full`, `too_many_applied` (5 out); 429 `too_soon` (20 an hour, counted apart from invitations and friend requests) |
| `DELETE /v1/teams/{id}/applications` | the applicant withdraws → 200; 404 `no_application` |
| `GET /v1/team-applications` | the player's own, unlapsed → 200 `{"applications": [{"teamId", "teamName", "expiresAt"}]}` |
| `GET /v1/teams/mine/applications` | by the leader or a vice leader: the 50 newest unlapsed and undeclined → 200 `{"applications": [{"playerId", "name", "expiresAt"}]}`; 403 `not_allowed` |
| `POST /v1/teams/mine/applications/{playerId}` `{"accept"}` | by the leader or a vice leader → 200, the team; 403 `not_allowed`; 404 `no_application`; 409 `in_team`, `cooling_down`, `team_full` |

An application lasts **seven days**, as an invitation does. The leader and each
vice leader are told in their inbox (`team_application`, its `ref` the
applicant) and by `evt.inbox`. **Accepted**, the applicant joins as a member, as
by an invitation: the team locked, then the player, the capacity and the
cooldown read under the locks; every other application of theirs is dropped,
they being in a team now, and the team is told `evt.team.update` as on any
join. **Declined**, it is kept, marked, until it lapses, and nobody is told: the
same player cannot apply to that team again for those days, which bounds what
one declined can send to a team that does not want them. A player the leader
has blocked is answered as if the application went in, and it is never shown,
as an invitation to one who blocked the inviter is (Q-20). Disbanding drops the
team's applications with its invitations; retention deletes the lapsed, as it
does invitations (06 §9).

| After | Pushed to | |
|---|---|---|
| an application | the leader and each vice leader | `evt.inbox` |
| an application accepted | every member, the new one included | `evt.team.update`, the team |

### Team events pushed (designed 2026-10-01, plan item 39)

On [Q-35](../requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation. **`evt.team.update`**, `{"team": …}`, the team exactly as
`GET /v1/teams/mine` answers it, or `null`:

| After | Pushed to | `team` |
|---|---|---|
| an invitation accepted | every member, the new one included | the team |
| leave | the one who left | `null` |
| | every member left | the team |
| kick | the one kicked | `null` |
| | every member left, the kicker included | the team |
| a role set, the leadership handed over | every member | the team |
| disband | every member it had | `null` |

The team is read after the change commits, and the members a disband or a
departure leaves out are read before it; a push is a notification, and a client
that missed one fetches the truth (03 §7). Not pushed: the team's rating after a
match, and team phrases, as the team's chat is not built.
`TeamService` sends them, as `PartyService` sends a party's.

**Built 2026-10-01** (`TeamService`: each action's pushes, and the team's JSON,
held equal to `GET /v1/teams/mine`'s by the test; the client's
`LobbyClient.Team`). A refused action changes nothing and tells nobody.

## 3. Arena registry, rooms and tickets

Arena processes register themselves and refresh:

```
HSET arena:{name} host,port,capacityRooms,capacityPlayers,version   EX 10   (every 3 s)
HSET room:{roomId} arena,mode,players,maxPlayers,state,matchUid     EX 10   (every 2 s)
SADD rooms:{mode} roomId
```

**The arena half is implemented** (`handoff/ArenaDirectory`,
`arena/ArenaAnnouncer`); the room-level registry is not, because the arena still
chooses its own room. **As built the arena's entry differs from the sketch
above:** `host, port, players, maxPlayers, tls, rooms, maxRooms`, where
`maxPlayers` is rooms × players per room and `rooms` of `maxRooms` are running,
and `roomList`, the arena's rooms as JSON for an operator (§10); there is no
`version`. For the public arena `platform` picks the arena with the most free
places (`ArenaDirectory.pick`), not the lowest ratio, which is the same choice
while every arena has the same capacity; for a made match, the arena with the
most free rooms less the rooms promised to it (`reserveForMatch`, §4). An arena announces itself rather than being registered by
an operator: configuration can say an arena exists, but only the arena can say
it is accepting connections, and it announces after binding for exactly that
reason.

**Liveness is the TTL, not a health check.** Nothing has to notice a failure or
agree that it happened — the entry simply stops being refreshed. Verified by
`SIGKILL`ing an arena: the entry was gone 11 seconds later with no cleanup
anywhere. The index keeps the name, which the next read removes, so a crash
loop cannot grow it without bound. The cost is bounded staleness: for up to 10
seconds a player can be sent to a dead arena, fail to connect and re-queue,
which is the cheapest failure available here.

`platform` was to command an arena over pub/sub (`PUBLISH arena:{name}:cmd`) and
wait for the `room:{roomId}` hash to appear. **Superseded by
[D-20](../architecture/03-decision-log.md#d-20--a-matchs-room-is-made-by-its-first-ticket-not-by-a-command)**:
a match's room is made by the first ticket that names it (§4). If an arena's key
expires its rooms are dropped from the registry and any tournament matches on it
are rescheduled.

**Allocation policy:**

1. **Public modes** (FFA, teams, tag, domination): pick an existing room of the
   mode with `players < maxPlayers − 5`, preferring the fewest players above a
   minimum, so rooms stay lively at roughly 40–70 % full. If none, create on
   the arena with the lowest `players / capacityPlayers` ratio. **As built**
   (the public arena): `platform` sends the player to the arena with the most
   free places, and the arena puts them in its **fullest** room below
   `maxPlayers − 5`, opening a room only when every room is that full, so rooms
   fill one after another ([operations/04 §2.1](../operations/04-scaling-and-performance.md#21-how-players-are-placed)).
2. **Ranked, duel, team-vs-team, tournament, co-op**: always a fresh room with
   a `matchUid` assigned by `platform`, a fixed roster, a join window (30 s in
   the first slice, §4), and a walkover if a side never arrives.
3. **Sandbox**: created on demand with a six-character code,
   `SET roomcode:{code} roomId EX 21600`.

**As built** (§4): only the public arena, `ffa`, is open, and the arena places
a player in its own open rooms. Every other mode (`duel`, `tvt`, `rffa`, `coop`,
`teams`, `domination`, `tag`, `maze`) is a made room for one match from the
queue or a tournament, and a sandbox is a made room opened by `POST
/v1/sandbox`, with no code.

**This allocator is the load balancer for match traffic** — not nginx
([D-5](../architecture/03-decision-log.md#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)).

### The join ticket

The ticket carries the **fully resolved loadout**, so the arena never reads
inventory, equipment or the item catalogue — it never touches MySQL at all:

```json
{ "playerId": "…", "name": "Ada", "colorId": 4, "teamHint": 2, "matchUid": "…",
  "startLevel": 1,
  "modifiers": [ { "stat": 5, "op": "ADD_PERCENT", "value": 0.08, "source": "eq:eq_barrel_steel" },
                 { "stat": 0, "op": "ADD_PERCENT", "value": 0.15, "source": "boost:boost_regen_1h" } ],
  "xpMultiplier": 2.0, "cosmetics": { "skin": 3 } }
```

`HSET ticket:{id} … EX 60`, single-use: the arena claims it with
`MULTI · HGETALL · DEL · EXEC`, so a second use finds nothing. The id is 128
bits from `SecureRandom` — it is a bearer credential, and anyone holding it
joins as that player.

**Implemented** (`handoff/Ticket`, `handoff/TicketStore`, claimed by
`arena/MatchFrameHandler`), with the fields the arena consumes: `playerId`,
`name`, `team`; for a made match `match`, its id, and `mode`; `bonus`, what the
player wears as `stat:percent` pairs (§8, D-37); and `skin`, the number of the
skin worn (§8, D-70). The other loadout fields above are not built: a ticket
field nothing consumes is a field nothing tests. The claim runs off the arena's event loop: it is a network round trip,
and waiting for it on a Netty thread would stall every other connection on that
loop.

A refused join gets `Kick` with a reason
([02 §3](02-networking.md#3-messages)) rather than a bare socket close, so the
client can tell "get a new ticket" from "re-queue" from "update the app".

~~Per-match consumable boosts are decremented in MySQL at ticket creation and
refunded by `worker` if the player never joined — detectable because no
`match_player` row appears for them.~~ **Not so** (2026-09-30,
[Q-23](../requirements/01-scope-and-nfrs.md#7-open-questions)): boosts were
built timed ([§8](#boosts-designed-2026-09-30-plan-item-16), Q-13, D-38), a
percent for some minutes, applied by `worker` at a match's end to whatever was
running. Nothing is taken at a ticket, so nothing is given back when a player
never joins.

## 4. Matchmaking

Runs every second on the scheduler thread.

- Queues in j-redis: `ZADD mmq:{mode}:{bucket} enqueuedAtMs partyId`, with party
  details in `HSET mmp:{partyId}`. Ranked modes bucket by rating band (±100
  initially, widening ±50 per 10 s waited); public modes use one bucket.
- Per mode: pull waiting parties sorted by wait time, greedily fill teams
  respecting party sizes, and emit a match when a full — or "good enough after
  30 s" — lineup exists. The complexity is irrelevant at hundreds in queue.
- Result: `rooms.allocate(mode, roster)` → tickets → `evt.match.found` pushed to
  each player with `{arenaHost, arenaPort, ticketId, tls}`, the same grant
  `POST /v1/match-requests` returns ([02 §1](02-networking.md#1-transport)).
- Players who do not confirm within 20 s are dropped (public modes) or the
  match is cancelled and re-queued (ranked).

**Superseded by the slices below:** one queue a mode with no rating buckets (the
window widens with each entry's wait instead), the public arena never queued
for, and every match found asked to confirm within 10 s, whatever its mode (the
third slice).

Matchmaking state lives **only** in j-redis, never MySQL. It is ephemeral by
nature: if the store is lost, everyone re-queues and nothing of value is gone.

### The first slice: a ranked duel, end to end (designed 2026-09-27, plan item 6)

The sketch above is the whole of matchmaking. The first slice builds every part
a structured mode needs, and proves them on one mode, **duel**: a queue, a
matcher, a room made for the match, a push that tells the player, a result that
moves a rating. Every later mode is a row and a rule on the same path. Duel
first because its roster is two, which a test can drive, and because the rating
it moves already has a column (`player.rating_duel`).

**The public arena does not change.** `POST /v1/match-requests` and the lobby's
`match.request` still grant a seat in an open room at once (D-15). The queue is
for timed matches only.

#### Modes

A mode is one definition, `handoff/MatchMode`, read by all three processes that
need it: `platform` for the roster, the arena for the rules, `worker` for how
the result is scored. One table in code, as the content tables are, so a
release carries one version of it.

| id | Mode | Lifecycle | Roster | Rules |
|---|---|---|---|---|
| 0 | `ffa` | open | none | the public arena, as now |
| 1 | `duel` | timed | 2 | map 2 000, 40 shapes, 3 minutes; the first to 3 kills wins, and at the whistle the one with more kills, else a draw; rated |
| 2 | `tvt` | timed | 6, teams of 3 | map 3 000, 80 shapes, 5 minutes; the first team to 10 kills wins, and at the whistle the team with more, else a draw; rated ([01 §8.4](01-arena.md#84-team-vs-team)). Queued alone or as a party |

The id is the `mode` the Welcome, the result and `matches.mode` already carry
(always 0 until now).

#### The queue

| Where | What |
|---|---|
| `POST /v1/queue` `{"mode": "duel"}` | joins; 200 `{"state": "queued"}`, 409 `already_queued` or `in_match`, 400 for a mode that has no queue. In a party (the second slice): the leader queues it whole; 409 `in_party` for a member, `party_changed` if it changed meanwhile; 400 `party_too_big` |
| `DELETE /v1/queue` | leaves; 200 whether or not it was queued |
| `GET /v1/queue` | `{"state": "none" \| "queued" \| "matched", "mode", "waitedSeconds", "grant"}`: `grant` when matched, the same object as `evt.match.found` carries |
| lobby `queue.join` `{"mode"}` / `queue.leave` | forwarded to the above, as `match.request` is; answered `queue.join.ok` / `queue.leave.ok` with the same body, or `error` with the same code |
| push `evt.match.found` | `{arenaHost, arenaPort, ticketId, tls, mode}` |
| push `evt.queue.update` | `{state, mode}`: to a party's members, whom the leader queued or someone took out (the second slice) |

In the store (the `session` instance, [D-7](../architecture/03-decision-log.md)):

```
ZADD mmq:{mode} enqueuedAtMs playerId                      the queue, oldest first
HSET mmp:{playerId} mode, since, rating, name, state [, grant…]  EX 900; EX 60 once matched
```

**A player is in one queue, or holds one match's grant, at a time**, which
`mmp:{playerId}` decides: a join finds it and is refused, `already_queued` or
`in_match`. Once matched the entry lives as long as the ticket, 60 s, so a
player can queue again when that match's ticket is spent or gone. Its expiry is
the backstop that returns a stuck player to "none". Leaving after being matched
forgets the grant: that player does not go, and the other wins by walkover.
The name and rating are read from MySQL once, at the join, so the matcher works
from the store alone.

**Being matched requires being in the lobby.** The matcher skips, and drops, a
queued player with no `conn:{playerId}` (03 §4): the push is how a match is
announced, and a player whose lobby has gone would hold an opponent in a room
for nothing. **Missing the push is not fatal**: `GET /v1/queue` answers
`matched` with the grant for as long as the ticket lives, which is what a client
asks after reconnecting (03 §7: what must not be lost is fetched, not pushed).

#### The matcher

One per deployment at a time, in whichever `platform` holds the lease:
`SET mm:leader {instance} NX PX 5000`, renewed every second by the holder, on a
thread of its own; `{instance}` names the platform's host, its address and port,
and its process. Each second, per queued mode:

1. Read the queue oldest first; drop anyone whose `mmp` is gone or not
   `queued`, or who has no lobby connection.
2. **Pair by rating**, oldest first: each player takes the closest-rated other
   player inside its window, ±100 widening by 50 for every 10 s waited, and the
   window of whichever of the two has waited longer applies. Unpaired players
   wait for the next second.
3. For a pair: take both out of the queue, one `ZREM` each in one transaction,
   so the matcher knows exactly whom it took. **If it took fewer than two**,
   another matcher took the rest (a lease that lapsed under a slow second): put
   back what this one took, and only that, and go on. A player is never in two
   matches.
4. Pick an arena with a free room (below), make a `matchUid` (a ULID, as the
   arena makes them), issue one ticket per player carrying the `matchUid`, the
   mode and a team (0 and 1), write `state matched` and the grant into `mmp`,
   and push `evt.match.found` to each.
5. **No arena with a free room**: put the pair back with their original times.
   They are first in line next second.

#### Rooms, made by the first ticket

**The arena creates a match's room when the first ticket naming its `matchUid`
is claimed**, and the second ticket finds it by that `matchUid`
([D-20](../architecture/03-decision-log.md#d-20--a-matchs-room-is-made-by-its-first-ticket-not-by-a-command)).
Nothing else tells the arena a match exists. The room is sized and ruled by the
mode, and it takes no one else: an open join never lands in it, and a ticket
for another match never does.

An arena's announcement gains `rooms` and `maxRooms`, and the matcher only picks
an arena with a free room. The directory is up to 3 s stale, so two matches can
be sent to the last room of one arena: the second one's first claim is refused
with `Kick(2)`, no room, and both its players re-queue, as for any full arena.
**Since 2026-09-30 a room is promised when its arena is chosen** ([D-42](../architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)):
the match's id in `rooms:promised:{arena}` for the ticket's 60 s, and a choice
counts an arena's promises against its free rooms, so matches chosen before the
arena announces again are not sent to the same last room. The arena drops a
promise in the write that first announces that match's room, which counts
itself from then on. The refusal above remains for a room taken otherwise, by an arena's own
open rooms, say, between an announcement and a claim.

**A match's room lives through three stages:**

| Stage | Until | What happens |
|---|---|---|
| waiting | the whole roster has joined, or 30 s since the room was made | tanks can move and shoot shapes; nothing counts |
| playing | a win, or 3 minutes | the world resets, both tanks at level 1; the clock and the tally start |
| over | — | the result is published; each player is sent `Kick(6)`, match over, and the room is closed |

**A walkover**: if the join window ends with one player in the room, that player
wins at once, **unrated**: a side that never arrived may have failed to connect
through no doing of its own, and a rating should not move on that. **And unpaid**:
its winner played no time, which the reward rules' five-second minimum already
pays nothing for, and paying it would pay two accounts for queueing each other
and one not going. **Nor counted**: since 2026-10-04 the same minimum keeps a
result out of the stats, achievements, daily goals and the pass as well
(`MatchResultRepository.COUNTED_FROM_SECONDS`, defect
[D-45](../defects.md#3-data-and-the-result-pipeline), the owner's
[D-76](../architecture/03-decision-log.md#d-76--a-result-under-five-seconds-counts-for-nothing-but-its-row-and-a-rated-matchs-rating-a-walkover-is-no-win)),
so a walkover is no win to farm either. It is still recorded, and decides a tournament's match. Nobody at
all is no result.

A player who loses the connection mid-match has the ordinary minute to resume
(02 §10); one who does not come back loses on the clock, as their kills say.
`Kick(6)` is new: a client returns to the lobby, as after `Leave`. It is not an
error, and it is not a reason to reconnect.

#### The result, and the rating

The result is the timed one (D-15), `kind` timed, `mode` 1: placement 1 for the
winner and 2 for the loser, both 1 for a draw. **A duel with one player is a
walkover** and moves no rating. Otherwise `worker` moves both ratings by Elo,
inside the transaction that already locks both players:

```
E = 1 / (1 + 10^((R_other − R_self) / 400))       S = 1 win, ½ draw, 0 loss
ΔR = round(K × (S − E))                           K = 32, then 16 after 30 rated duels
```

The count is a new column, `player.rated_duels` (V4). The ratings come from the
rows the transaction has locked, not from the result: the result was written
minutes before, and another duel may have moved either rating since.
`match_player.rating_delta` records what was applied.

#### As built (2026-09-27)

**Built so far: the push route, the queue and the matcher** (`handoff/LobbyPush`,
`MatchMode`, the match on `Ticket`, `ArenaDirectory.pickForMatch`, since
`reserveForMatch`, which promises the room (D-42);
`platform/MatchQueue`, `Matchmaker`, `QueueService`; the gateway's
`queue.join`/`queue.leave` and its push channel). The matcher runs on a thread
of its own in every `platform`, a round a second with a fixed delay between
rounds, and a failed round is logged and followed by the next. Metric
`backend_platform_matches_made_total{mode}`; the wait-time histogram of §11 is
built since (2026-09-29).

**The arena's side is built** (`arena/RoomRegistry.allocateMatch`, the `MADE`
lifecycle in `RoomThread`, [01 §8.1](01-arena.md#81-the-mode-decides-the-lifecycle)):
rooms made by the first ticket, the stages, the duel's rules, the walkover,
`Kick(6)`, and `rooms`/`maxRooms` in the announcement, which is what lets the
matcher send a match at all. An arena from before it announces neither, and is
never sent one.

**The rating is built** (`worker/DuelRating`, since renamed `EloRating` when it took a whole field; applied in
`persistence/MatchResultRepository` under the lock; V4). A walkover and a
redelivery move nothing; the deltas are worked out again on a redelivery, since
only the insert can tell one, and none is applied.

#### Not in this slice

Parties, and teams queueing together (§2 was not built then: parties came
with the second slice, teams' matches with the sixth). A confirm step: the
join is the confirmation, and the join window its timeout. Every other queued
mode. A penalty for not arriving. Tournaments.

### The second slice: parties and team-vs-team (designed 2026-09-28, plan item 6)

The plan's next is parties, and a party has nothing to do without a mode with
teams, a duel being one against one; so the slice is parties and the first team
mode together, **team-vs-team**, three against three (`tvt`, mode 2), and the
team rules the simulation has never had
([01 §8.3](01-arena.md#83-team-rules-designed-2026-09-28-plan-item-6),
[§8.4](01-arena.md#84-team-vs-team)). Clans (§2) are not parties and stay with
the meta layer; a confirm step and the other modes come after.

#### Parties

A **party** is a few players who queue together: at most three, the largest team
any queued mode has. It is made by an invitation and lives in the store, not in
MySQL ([D-25](../architecture/03-decision-log.md#d-25--a-party-lives-in-the-session-store-and-only-its-leader-queues)):
like the queue, it is worth nothing once its players have gone.

| Where | What |
|---|---|
| lobby `party.invite` `{"playerId"}` | invites a player in no party, pushed if they are in the lobby; the inviter's party is made if it has none, the inviter its leader. 409 `in_party` (the invitee has one), `party_full`, `not_leader`; 404 `not_in_lobby`, to a friend of theirs only (S-16); 429 `too_soon` past 60 invitations an hour |
| lobby `party.accept` `{"partyId"}` | joins, while the invitation lives (60 s) and the party is not full, queued nor asked about a match; 404 `no_invitation`, 409 `party_full` or `queued` |
| lobby `party.leave` | leaves; a leader leaving hands the party to the longest member, and a party of one is no party |
| lobby `party.kick` `{"playerId"}` | the leader removes a member |
| push `evt.party.invite` | `{partyId, from, fromName}`, to the invitee |
| push `evt.party.update` | `{partyId, leader, members: [{playerId, name}], version}`, to every member, on every change; for one in no party, `partyId` null, `members` empty and `was` the party it ended, with that change's `version` |
| lobby `party.say` `{"phraseId"}` | a phrase to the party ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)); 400 `unknown_phrase` for an id not in the list, 404 `no_party`, 429 `too_soon` within two seconds of the last. **Built 2026-09-29**, plan item 8's second slice |
| push `evt.party.said` | `{from, name, phraseId}`, to every member, the speaker included |

Each is forwarded by the gateway to `platform` (`POST /v1/party/invite`,
`/accept`, `/leave`, `/kick`, `/say`; `GET /v1/party`), as `queue.join` is. In the store:

```
HSET party:{partyId} leader, members, names, v     EX 3600, renewed on every change
SET  partyOf:{playerId} partyId                     EX 3600
SET  pinv:{playerId}:{partyId} 1                    EX 60, one invitation per party
SET  rl:say:{playerId} 1                            NX PX 2000, a phrase's turn
```

**A phrase to the party** is the lobby's half of fixed-phrase chat: members wait
together in the lobby and in the queue, where no match carries what they say.
The rate is the arena's, one every two seconds, kept by a key that lapses: a
turn is taken only by a phrase in the list said by a member of a party. Refused
with a code rather than dropped, since HTTP answers every request; the lobby
stays open. Its speaker hears it too, as in a match.

**Built 2026-09-29** (`platform/Parties`, `PartyService`, `/v1/party`; the
gateway's `party.*`): each change reads what it depends on under `WATCH` and
writes in one `MULTI`, retried up to five times if any of it changed meanwhile,
so two players accepting the last place at once leave one in and tell the other
`party_full`. An invitee must be connected to the lobby, since the invitation is
a push. A name is read from MySQL once, when its player invites or joins, and
kept with the party. The gateway sends `platform` only the field a message
takes, not the rest of `d`. The `queued` refusal comes with the queue of parties.

**A party's state is versioned** (designed 2026-10-04, plan item 78, T-45;
[D-74](../architecture/03-decision-log.md#d-74--a-partys-state-is-sent-whole-with-its-version-and-the-client-keeps-the-newest)).
Each state is sent whole, as an answer to the member who asked and as a push to
every member, and the two travel by different paths: the answer through the
gateway's call to `platform`, the push through the store. Two changes at once,
two players accepting, or a push overtaking an answer, could deliver an older
state after a newer one, and a member saw the party as it was until it changed
again. So `v` is the party's version: 1 when it is made, one more on each
change of its members or leader, in the change's own transaction. Every state
carries it, an answer, a push, `GET /v1/party`; a state of no party that a
change told carries the party it ended as `was`, with that change's version,
and one with no `was` (`GET /v1/party` for a player in none) is applied. A client
applies a state only when it names another party than the last it applied, or
a higher version of the same; one with no version, from a `platform` not yet
upgraded during a rolling deploy, as it comes, as before. An invitation changes no member, and no version.
**Not covered:** a state of a party left behind arriving after the state of the
next one joined; that needs a player to leave and join within a push's few
milliseconds, and the next change, or `GET /v1/party`, puts it right.

**Only the leader queues**, and queues the whole party; a member asking is
refused 409 `in_party`. **A party is one entry in the queue**: `mmq:{mode}` holds
the leader, whose `mmp` names every member with their rating and name, and each
member's `mmp` says `queued` and names the leader, so a member's own join is
refused as it would be alone. **Leaving is the party's**: a member leaving the
queue, or the party, takes the party out of the queue, and all its members are
told. A party bigger than a mode's team cannot queue for it: 400
`party_too_big` (a party of two for a duel).

In the store, the entry is the leader's, and a lone player is an entry of one:

```
ZADD mmq:{mode} since leaderId
HSET mmp:{leaderId} mode, since, state, rating, name                       a player alone
HSET mmp:{leaderId} mode, since, state, members, r:{id}, n:{id} …           a party's names every member
HSET mmp:{memberId} mode, since, state, leader                                     one per other member
```

`platform` reads each member's name and the mode's rating from MySQL when the
leader queues, as it does for a player alone. The join writes every member's
`mmp` and the queue in one transaction, watching every `mmp` and `party:`, and
checks inside it that the party is the one it read: a member leaving meanwhile
fails the join with 409 `party_changed`, rather than queueing a party that is not
one. Any member's `mmp` already queued or matched refuses it as
`already_queued` or `in_match`.

**Every member is told**, since only the leader asked: `evt.queue.update`
`{state, mode}` to each other member when the party is queued, and
`{"state": "none"}` to every member taken out, whoever asked: a member leaving the
queue, a member leaving or being removed from the party, or the matcher dropping
the party because a member has no lobby connection. `GET /v1/queue` answers each
member as it answers a player alone; it is the truth, and the push is a
notification ([03 §7](03-gateway.md#7-what-must-not-be-lost)).

Once matched, each member's match is their own: a member who leaves the party or
the queue then forgets their own match, as a player alone does, and the rest
still go. A member who leaves the party while its entry is asked about a match takes
the entry out of that match (the third slice, "Each step one transaction").

The join's watch on `party:` guards a narrower race of the same kind: a member
leaving between the join's reading the party and its writing. No test drives
that race; its mutation, the watch removed, survives, and the harm it would let
through is a member queued, and so matched, with the party they had just left,
able to leave that match as anyone can.

#### The matcher, with teams

A duel's pairing is a team-filling of teams of one, so the matcher becomes one
rule for every queued mode. Each second, per mode, oldest first: take the
oldest entry, a party or a player alone, and the entries whose mean rating is
inside its window (±100, widening 50 every 10 s the oldest has waited), at most
ten; **find two teams of the mode's team size**, the oldest entry in the first,
no party split, with the least difference between the teams' mean ratings. None
fills both: the entries wait. The search is over at most ten entries, a few
thousand ways, once a second. For a duel it takes the closest-rated other, as it
did.

The ten are the ten closest in mean rating to the oldest entry's, the older first
on a tie, and they are younger than it: an older entry that found no match this
round had its own turn, with a window as wide or wider. An entry that fills no
match leaves the next oldest its turn. Between two splits equally balanced, the
first found stands, which takes the closer-rated entries first.

The rest is the first slice's: take them out of the queue in one transaction,
put back what was taken if another matcher took any, pick an arena, and issue
the tickets, **team 1 and team 2** for a team mode (a duel's stay 0 and 1).

**Built 2026-09-29** (`Matchmaker.lineups`, `MatchQueue`, `QueueService`), with
the queue of parties above. The ten are chosen in one pass that keeps the best
so far: a first version sorted every entry in the window for each oldest, and a
queue of 5 000 took 5.3 s a round. Measured after the change, warm, on the
loaded development VM, so the figures vary between runs by a factor of about
two: 2 000 players alone for team-vs-team, 58 ms a round; 2 000 parties of two,
which fill nothing, 73 ms; 5 000 entries of one to three, 100 to 180 ms; a duel
queue of 2 000, 12 ms. The duel's matches are the ones its pairing made.

#### The rating

`player.rating_tvt` and `player.rated_tvts` (V5), as the duel's. **Each team is
rated as its players' mean**, and each player moves by their own K
([D-26](../architecture/03-decision-log.md#d-26--a-team-is-rated-by-its-players-mean-each-moved-by-their-own-k)):

```
E = 1 / (1 + 10^((mean_other − mean_own) / 400))    S = 1 win, ½ draw, 0 loss
ΔR_player = round(K_player × (S − E))                K = 32, then 16 after 30 rated matches
```

A walkover moves nothing and pays nothing, as a duel's.

**Built 2026-09-28** (V5; `MatchResultRepository`): the ratings and counts of both
kinds are read under the lock, a team match's deltas worked out from each team's
mean by the duel's rule with each player's own count, and written to the team
rating. A result with one team's players only is a walkover. `persistence` does
not depend on the mode table, so it names the two rated modes' ids itself, and a
test in `worker` holds that they agree.

#### Built in this order

Each part committed with its tests and mutation checks: (a) the team rules in
the simulation; (b) the mode, its room's rules and its tally; (c) the rating,
V5; (d) parties; (e) the queue of parties and the matcher with teams; (f) the
client core, and a live drill of six players, a party of three among them.
**All six built by 2026-09-29.**

### The third slice: a confirm step (designed 2026-09-29, plan item 6)

Until now the join has been the confirmation, and the join window its timeout:
a player matched but not there costs the others 30 s and then a walkover in a
duel, or a rated match played short-handed in team-vs-team. So a match found
now **asks every player first**, and makes a room only for those who answer
([D-27](../architecture/03-decision-log.md#d-27--a-match-found-asks-every-player-before-it-is-made);
whether to have the step, and its two numbers, is
[Q-5](../requirements/01-scope-and-nfrs.md#7-open-questions)).

| Where | What |
|---|---|
| push `evt.match.ready` | `{matchUid, mode, seconds}`: a match is found; accept within `seconds`, 10 |
| lobby `match.accept` / `match.decline` `{"matchUid"}` | forwarded to `POST /v1/queue/accept` / `/decline`; 409 `not_confirming` for a match the player is not asked about, or no longer |
| `GET /v1/queue` | `confirming`, with `matchUid` and `secondsLeft`, for a client that missed the push |
| `POST /v1/queue` | 409 `queue_locked` for a player locked out, or a party with one |

**Every player answers for themselves**, a party's members too. When all have
accepted, the matcher's next round (within a second) picks an arena and issues
the tickets, as it did at once before: `evt.match.found`. **When one declines,
or ten seconds pass without all accepting, the match is off.** Each entry with
a player who declined, or, once the ten seconds are up, did not answer, is out
of the queue, and that player, not their party-mates, may not queue for 60 s.
A player yet to answer when another declined was not silent, and goes back. Every other entry goes back to
the queue where it was, its waiting time kept, and its players are told
`evt.queue.update` `{"state": "queued"}`. Leaving the queue while asked is
declining. With every player accepted and no arena room free, the entries go
back first in line, as a match found without a room does now.

In the store:

```
HSET mmc:{matchUid} mode, deadline, lineup          EX 60
ZADD mmc deadline matchUid                         the matches waiting for answers
HSET mmp:{playerId} state confirming, matchUid, deadline [, answer]   EX 60; the rest as when queued
SET  mmlock:{playerId} 1                           EX 60
```

`lineup` is each entry of the match with its side, its time queued and its
members, so a match that is off is put back as it was taken. An answer is
written to the player's own `mmp`, under a watch on it alone, and only while
the player is `confirming` for that match: an answer to a match already off, or
one already made, is `not_confirming`. Asking clears any answer to an earlier
match. The matcher, which alone makes and ends
these under its lease, reads `mmc` first each round: made, off, or still
waiting.

#### Built in this order

(a) the store and the matcher: asking, answers, the match made or off, the
lock; (b) the API and the lobby; (c) the client core and a live drill: a duel
accepted by both, and one declined.

**(a) and (b) built 2026-09-29** (`MatchQueue.ask`, `answer`, `pending`,
`close`, `requeue`, `lock`, the last three since folded into `make` and `end`,
each one watched transaction, below; `Matchmaker.settle`; `POST /v1/queue/accept` and
`/decline`; the gateway's `match.accept` and `match.decline`). A round settles
the matches waiting for answers before it finds more. Whichever matcher removes
a match from `mmc` first ends its wait, so two under a lapsed lease cannot both
make it. The first version counted a player yet to answer as silent when
another declined; a test caught it, since their ten seconds were not up. And
the first version wrote the answers into `mmc:`, watched, which the live drill
of six found: answering together they overtook one another, and the sixth ran
out of retries and was answered 503
([T-16](../defects.md#4-concurrency)). Each answer is now the player's own.

#### Each step one transaction (designed 2026-10-02, plan item 56)

The second audit found the confirm step writing on what it had read a moment
before ([T-32, T-33, T-34](../defects.md#4-concurrency)): a player who left
between the matcher's read and its asking was asked all the same; one who
declined between its last read and its making was matched; a party that changed
while asked went back to the queue as it was; and a matcher that failed between
ending a match's wait and writing its tickets left its players asked for
fifteen minutes. **Each step that moves a player on is now one transaction**:
it reads the records it decides by under `WATCH`, and writes them all in one
`MULTI`, or nothing if one changed meanwhile
([D-55](../architecture/03-decision-log.md#d-55--each-step-of-the-confirm-step-is-one-watched-transaction)):

| Step | Reads, watched | Writes, all or none |
|---|---|---|
| asking | each member's `mmp`: still `queued`, since the time the round read | the entries out of `mmq`; the match's `mmc:` and its place in `mmc`; each member `confirming`, for the match's 60 s |
| making | each player's `mmp`: `confirming` this match, and `accept` | the match's wait ended; each player `matched`, with their grant, for a ticket's life |
| calling off, or no room | `mmc:{matchUid}`, still there; each player's answer as it is now | the match's wait ended; each entry with a player who declined, withdrew, or was silent at the deadline (and not called to a tournament, Q-44) forgotten, and those who declined or were silent locked out; the rest back in `mmq`, `queued`, for the queue's 900 s |
| joining | each member's `mmp` and `mmlock` | as before; a lock refuses it, `queue_locked` |

A step that loses writes nothing, and the next round, a second later, reads the
queue or the match again as it is then. Whichever matcher ends a match's wait
first is the one to act on it, as before.

**The tickets come before the match is made** (T-34). The room is promised and
every ticket written first, then the making's transaction; nobody is told a
ticket until it has committed, and then each player is told `evt.match.found`.
A failure before then, the store's or `platform`'s, leaves the match waiting and
its players asked, and the next round makes it with new tickets; those written
before are known to nobody, and lapse in 60 s. A making that loses to a change
gives the room's promise back.

**An asked player's record lasts as long as the match's wait**, 60 s from the
asking, as `mmc:` does, and the queue's 900 s again when its entry goes back. A
matcher that cannot finish leaves its players asked for a minute at most; then
they are in no queue, and may join again. They are not told: `GET /v1/queue`
answers `none`, and the push is a notification
([03 §7](03-gateway.md#7-what-must-not-be-lost)).

**A party that changes while asked is out of the match** (T-32). A member
leaving the party, or removed from it, has their answer written `withdrawn`,
unless they had declined; an accept or a decline cannot change it afterwards
(`not_confirming`). The match is off at the next round, as for a decline; the
entry is out, every one of its players told `none`, and nobody locked out for
it: leaving a party is not refusing a match. The other entries go back. A party
asked takes no newcomer, as a party queued takes none: `party.accept` answers
409 `queued` while its leader is `queued` or `confirming`. A party matched may
take one: each member's match is their own by then.

**Leaving while asked** is declining, unless withdrawn, written under a watch on
the player's own record: a leave that lands as the matcher asks is either a
leave of the queue before it or a decline after it, never lost between.
`confirms_total` gains `withdrawn`.

The join's watch on the locks guards a race no test drives, a call-off landing
between the join's reading and its writing; its mutation, the locks read but not
watched, survives, as the party's watch above does.

### The fourth slice: ranked free-for-all (designed 2026-09-29, plan item 6)

**`rffa`, mode 3**: eight players, each for themselves, rated. Its numbers are
the user's to settle ([Q-6](../requirements/01-scope-and-nfrs.md#7-open-questions));
it is built on these meanwhile:

| | |
|---|---|
| roster | 8, matched by rating as the duel is; no parties (a party is bigger than a team of one) |
| map, shapes | 3 500, 120 |
| length | 4 minutes; no kill ends it |
| placement | by score at the whistle, equal scores sharing a place (the tally already places a mode without a kill target so) |
| rating | pairwise ([D-28](../architecture/03-decision-log.md#d-28--a-free-for-all-is-rated-pairwise-each-player-against-each-other)): each player against each other by placement, K shared over the field |

**The matcher, for more than two sides.** A mode has `roster / teamSize` sides;
the duel and team-vs-team have two, and are matched as they are. With more, each
side is one player: the oldest entry and the seven closest younger ones inside
its window are the match, and fewer than seven there leaves it waiting. The
lineup becomes a list of sides, the confirm step asks all eight, and any of
them declining calls it off as it does for two.

**Teams in a mode without teams are 0**, every player's. A duel's two were 0
and 1, the side's index, which put the second in the left third at spawn, as
a team 1 is (01 §8.4); both are 0 now, and spawn anywhere, as in the public
arena.

**The rating.** `player.rating_rffa` and `player.rated_rffas` (V6). For a result
of N players placed, each player *i*:

```
S_ij = 1 placed above j, ½ equal, 0 below      E_ij = 1 / (1 + 10^((R_j − R_i) / 400))
ΔR_i = round(K_i × Σ_j≠i (S_ij − E_ij) / (N − 1))      K as the duel's: 32, then 16 after 30
```

With two players it is the duel's rule. A match only one came to is a walkover:
unrated and unpaid, as in the other modes. A player who left early is in the
result, placed by the score they had, and rated so.

#### Built in this order

(a) the mode and its rules, and teams 0; (b) the matcher for more than two
sides; (c) the rating, V6; (d) the client and a live drill of eight players.

**(a) to (c) built 2026-09-29.** The rule is one for every rated shape now:
`worker/EloRating` takes placements, not scores, and a duel is two players, a
team match two teams at their means, a free-for-all the whole field. A result
naming a player who no longer exists rates the rest. Counting a player against
themselves would add ½ − ½, so that mutant is equivalent and the check stays
for the formula's sake.

### The fifth slice: co-op waves (designed 2026-09-29, plan item 6)

**`coop`, mode 4**: three players, one team, against waves of the arena's own
tanks (the MvM row of [01 §8.2](01-arena.md#82-the-interface)). The last queued mode the plan names. Its
rules are a product's, and open with the user
([Q-7](../requirements/01-scope-and-nfrs.md#7-open-questions)); this first
slice is built on the smallest set that plays:

| | |
|---|---|
| players | one team of three: a party of up to three, filled from the queue in the order it waited; not rated, so not matched by rating |
| map, shapes, length | 3 000, 60, ten minutes at most |
| waves | ten; wave *w* is 2 + *w* tanks of level 5*w* (45 at most), classes as a grown bot takes them, in the right third; the players start in the left |
| the arena's tanks | **hunt**: each turns to the nearest player's tank, closes to 400 units, and fires within 600 |
| between waves | the next starts 5 s after the last tank of the one before dies, the first 5 s after the match starts |
| the dead | come back at the start of the next wave; no respawn on request in co-op |
| the end | the tenth wave cleared, every player dead at once (a wipe), or ten minutes |
| result | one team, every player placed first; unrated; paid by the rule every match is, the arena's tanks' experience in each killer's score. Waves cleared are logged, and reach the result when a reward is set on them |

**The matcher for one side** fills a team of three from the oldest entry and
the next ones, in the order they waited, no party split; an unrated mode has no
window. A player alone or a party of two waits for others; three may play at
once.

#### Built in this order

(a) the hunting tank in the simulation; (b) the mode and its waves in the
arena; (c) the matcher for one side, and queueing for an unrated mode; (d) the
client and a live drill: a party of two and one alone, and the waves.

**(a) to (c) built 2026-09-29.** For one side the matcher fills the oldest
entry's team from the next that fit, whole, skipping a party that would
overfill it; an unrated mode's players are queued at 0, so none is read from
MySQL, and every gap is 0. `MatchMode.COOP` is mode 4, not rated.

### The sixth slice: team matches (designed 2026-09-30, plan item 19)

On [Q-18](../requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation: two teams (§2), not two groups of whoever queued, play each
other, and the team is rated.

| | |
|---|---|
| mode | **`teams`, mode 5**: team-vs-team's rules (01 §8.4), three against three, the first side to ten kills, five minutes, 30 s to come; its map and shapes |
| who queues | a party of exactly three, every member in one team, queued by the party's leader, who is that team's leader or a vice leader |
| refused | 400 `party_too_small` (fewer than three, a player alone too); 409 `not_one_team` (a member in no team, or in another); 403 `not_allowed` (the party's leader is only a member). Checked when queueing: a member leaving the team afterwards is the result's to handle (below) |
| matched | each entry is a whole side, at the team's rating: every member is queued at it, so the entry's mean is the team's. The window is the matcher's. **Two entries of one team are never matched** |
| rating | `team.rating`, 1 200 to start: the duel's Elo between the two teams, K 32, then 16 after 30 rated team matches (`team.rated_matches`); a draw is half. Wins, losses and draws are counted on the team |
| the players | paid as in team-vs-team; their own ratings do not move, since `rating_tvt` is team-vs-team's, played with whoever |
| a walkover | one side only: nothing moves and nothing is paid, as in every mode |

**The entry carries its team's id**: `team` in the leader's `mmp`, which the
confirm step leaves in place, so an entry queued again after a match is
declined still has it, and the matcher can pass over a candidate of the oldest
entry's team. Nothing carries it further: the
ticket, the arena and the result name sides, and `worker` finds each side's
team when it applies the result
([D-43](../architecture/03-decision-log.md#d-43--a-team-matchs-sides-are-known-by-their-players-teams-when-the-result-is-applied)).
A side's team is the one its players are in then, passing over any who left or
were kicked, who are in no team for 24 hours; a side in two teams or in none, or
both sides in one, is applied for the players only.

**In the result's one transaction** (06 §5): the players' teams read, the two
teams locked, then the players, as D-39 orders team actions, and the teams read
again; the teams' ratings, counts and a `match_team` row each written. The
result is applied once, so the team is too.

`GET /v1/teams/mine` gains the team's `rating`, `wins`, `losses` and `draws`.

**Not in this slice:** streak trophies, team leaderboards, a team's match
history through the API, and matches of five a side.

#### Built in this order

(a) V12, the mode, the team's entry in the queue, and the matcher's rule
(`persistence`, `handoff`, `platform`); (b) the team's rating and record in the
result's transaction, retention, and the team's view (`persistence`,
`platform`); (c) the client and a live drill: two teams of three, each queued by
its leader, matched, and played out.

**(a) built 2026-09-30.** V12; `MatchMode.TEAMS`; `TeamRepository.sideOf`, one
query for the party's team, its rating and the queuer's role; `QueueService`'s
three refusals, and every member queued at the team's rating; the entry's
`team` in its leader's `mmp`, read back by the matcher, which passes over a
candidate of the oldest's team. A team match's result moves no player's rating
from this part on, the teams' coming with (b).

**(b) built 2026-09-30.** In the result's transaction (`MatchResultRepository`):
the players' teams read and those teams locked before the players, read again
under the locks, each side's team the one its players still share; the duel's
Elo between the two teams, a `match_team` row a side, whose key makes a
redelivery change nothing, and the team's rating and counts. A walkover, a
result cut short and so unrated, a side in no team or two, and one team on both
sides move nothing for the teams. Retention deletes `match_team` with the rest
of a match. `GET /v1/teams/mine` and `TeamRepository.teamOf` carry the rating
and the counts.

**(c) done 2026-09-30**, and drilled from the release (the drill's
`teammatch`): two teams, a party of each, the one led by a member refused until
promoted, one match, each team a side, a draw counted by both.

### The seventh slice: the sandbox (designed 2026-10-01, plan item 33)

On [Q-32](../requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation, and by
[D-49](../architecture/03-decision-log.md#d-49--a-sandbox-is-a-made-room-that-publishes-nothing-opened-by-a-request-its-powers-one-message):
a private room, made at once, never queued for.

| | |
|---|---|
| `POST /v1/sandbox` | opens one → 200, the grant: the same object as `evt.match.found` carries, `mode` `sandbox` |
| who goes | the caller, or, for a party's leader, the whole party; each is sent `evt.match.found`, the caller too, and recorded matched, so `GET /v1/queue` answers with the grant as after a missed push, its `waitedSeconds` counted from the opening |
| refused | 409 `in_party` for a member who is not the leader, as the queue does; 409 `already_queued` when the caller or a member is queued or being asked; 409 `in_match` when one is matched already; 409 `in_sandbox` when one holds a sandbox (below, D-54); 503 `no_room` when no arena has a room free |
| the tickets | one each, team 0, naming the match and `sandbox`, with what the player wears, as the queue's are |
| afterwards | nothing: the arena publishes no result (01 §8.10), so `worker` never hears of it |

A player matched is refused a queue, and a queue refused a sandbox, so a player
holds one of the two at a time. Being matched lasts as long as a ticket, after
which they may queue again from inside the sandbox, as from inside any match.

**Built 2026-10-01** in `QueueService.openSandbox`, which has the queue's rules
for a party already. `MatchQueue.matched` records the mode with the grant now:
a queued player's record had it from the queue, a sandbox's player has none, and
`GET /v1/queue` would have answered a sandbox's grant with no mode.

### One room at a time (designed 2026-10-01, plan item 52)

The second audit's S-13 and T-38 ([defects](../defects.md)): "a player holds one
of the two at a time" held for a queue and a made match's grant, and missed a
sandbox and a tournament's call.

**A sandbox is held until its room ends**
([D-54](../architecture/03-decision-log.md#d-54--a-sandbox-is-held-by-a-key-of-the-players-own-for-as-long-as-its-room)).
Being matched lasts as long as a ticket, and `DELETE /v1/queue` forgot even that;
so one session could open a sandbox, leave, and open another, each a room
promised for a minute, and with each ticket joined hold a room for twenty. Now:

| | |
|---|---|
| `sbx:{playerId}` | the match, set by `POST /v1/sandbox` for the caller and each member with `NX` for a ticket's life, 60 s; one already held refuses the request, 409 `in_sandbox`, and gives back any set for it |
| the arena | on a sandbox player's join sets it for the room's whole life and a minute, 1 260 s; deletes it when the player leaves on purpose, and for everyone still in the room when it ends. A player whose connection is lost keeps it: they may resume |
| leaving the queue's record | for a sandbox not yet joined, the player's ticket is revoked, and only if that took it (not yet claimed) is the hold deleted: so a ticket cannot be joined after its hold was given back |
| a room's promise | lapses with its minute, as an accepted match's whose players never come (D-49's cost) |

So a player holds one sandbox, joined or not, until it ends or they leave it;
the queue stays open from inside one, as from inside any match (D-49).

**A tournament's call is a match's grant**
([Q-44](../requirements/01-scope-and-nfrs.md#7-open-questions), recommended):

| | |
|---|---|
| `tcall:{playerId}` | the tournament, set with the grant by the scheduler for the ticket's life, 60 s |
| the queue and the sandbox | refuse a player called, 409 `in_match`, as one matched |
| the matcher | drops a queued entry with a member called, the party whole, as one without a lobby, and tells the rest; a player called while asked to confirm, and silent, is not locked out |
| one matched already, or playing | keeps that match and is called all the same: the tournament's clock decides, as for anyone who does not come |

The call is the worker's only part: the queue's rules stay in `platform`, which
reads `tcall:` where it reads `conn:`.

**Built** in two parts: (a) the sandbox's hold (2026-10-01), (b) the tournament's
call (2026-10-02).

## 5. Modes and rating

| Mode | Roster | Result handling |
|---|---|---|
| `duel` (1v1 ranked) | 2, rating-matched | Elo: `ΔR = K·(S − E)`, K = 32 falling to 16 after 30 matches |
| `rffa` (ranked free-for-all, 8) | rating-matched | pairwise Elo on placement ([D-28](../architecture/03-decision-log.md#d-28--a-free-for-all-is-rated-pairwise-each-player-against-each-other)) |
| `tvt` (3v3) | rating-matched, parties of up to three | each player by the teams' means ([D-26](../architecture/03-decision-log.md#d-26--a-team-is-rated-by-its-players-mean-each-moved-by-their-own-k)) |
| `teams` (3v3, two teams) | a party of three of one team each | the team's own rating, wins, losses and draws (the sixth slice of §4) |
| `coop` (one team of three against waves) | queued, a party or matched, in the order they waited | unrated; paid as every match is; waves cleared are logged (the fifth slice of §4) |
| `domination`, `tag` (3v3) | queued, alone or as a party, every entry at 0 so in the order they waited | unrated; paid as every match is (01 §8.7, §8.8) |
| `maze` (8, each for themselves) | queued alone, in the order they waited | unrated; placed by score (01 §8.9) |
| `sandbox` | the caller, or a party's leader for the party (§4, the seventh slice) | none: nothing is published |

All results flow through the same stream and the same applier. As built there
is no per-mode policy object: the result's mode id says whether, and how, it is
rated (`worker/EloRating`, for the rated modes `persistence` names), the reward
rules are one table for every mode (`worker/RewardRules`), and a tournament
reads its match's result from MySQL (§6).

## 6. Tournaments

The state machine lives on the tournament row and is driven by a scheduled job
in `worker` every 5 seconds:

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

Every transition is a conditional update carrying the expected state:

```sql
UPDATE tournament SET state = 'SEEDED', version = version + 1
 WHERE id = ? AND state = 'REGISTRATION' AND version = ?;
-- affected rows 0 means somebody else advanced it; do nothing
```

**`ROW_COUNT() = 0` is the concurrency control.** Two scheduler ticks, or a
restart mid-tick, cannot double-advance a bracket — the second update simply
matches no rows. This is the same shape as the idempotency rule in
[06 §5](06-persistence-mysql.md#5-idempotency): make the write itself the
check.

Seeding is by rating. Single elimination for v1, round robin for small groups
after that. Entries are players or teams; team entries need LEADER or
VICE_LEADER to register and a roster of the mode's size at match time. Rewards
are distributed by `worker` through the ledger at `FINISHED`, idempotent on
`tourney:{id}:{place}:{entityId}`.

### The first slice: duels (designed 2026-09-30, plan item 18)

On [Q-15](../requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation.

**Created by an operator**, `POST /admin/tournaments` (§10, audited): a name,
2 to 32 entries at most, the registration deadline, the start, minutes between
rounds (1 to 60), and prizes in coins for places 1, 2 and 3 (each semi-final's
loser). Mode duel, one player an entry, no fee.

**Registration**: `POST /v1/tournaments/{id}/entries` while `REGISTRATION` and
before the deadline, one entry a player; `DELETE` withdraws before the deadline.
`GET /v1/tournaments` lists those registering or running; `GET
/v1/tournaments/{id}` gives one, its entries and, once seeded, its bracket.

**Who may enter** (designed 2026-10-02, plan item 61,
[Q-43](../requirements/01-scope-and-nfrs.md#7-open-questions)): one who has
played. Any account could enter, a guest included, and a guest costs nothing but
the per-address limit: thirty-one throwaway guests and one real account filled a
32-place tournament, and the real one won every round by walkover and took the
first prize. A duel's entrant needs ten rated duels, the mark that lists a
player on the duel board (Q-40, `RatingBoards.MIN_RATED`). A team's entry needs
ten rated team matches of the team's own (`team.rated_matches`): a team match
rates the team, not its players, so no player has a count in that mode, and
Q-43's "each player on the roster" is read as the team's. Otherwise 409
`too_few_rated`, after `closed` and before `full`: the entrant's own reason
before the tournament's. The count is read in the registration's transaction,
under the tournament's lock; a count only grows, so nothing else is locked.

**Seeding**, at the deadline: fewer than two entries, `CANCELLED`. Otherwise
the entries are ordered by duel rating, then by who registered first, and
placed in a bracket of the next power of two, seed 1 against the lowest seed,
the standard order, so the top seeds meet last; a seed with no opponent has a
bye into round 2.

**A match**: at the start, and each round after the previous one ended and the
minutes between rounds passed, every match with both sides known is made as
the matchmaker makes a duel (§4): a ticket for each player naming the match,
the grant kept for the player (`GET /v1/tournaments/{id}/match`) and pushed
(`evt.tournament.match`). The arena plays it as any made duel (01 §8): it waits
at most 30 s for both, and one alone wins by walkover, published as any result.
The scheduler reads the result MySQL recorded for the match: its winner
advances, and a draw sends the higher seed on. **No result 270 s after the
tickets** (30 s to come, 180 s to play, 60 to spare) means nobody came, or the
arena was lost: the higher seed advances.

**The end**: the final's result makes the tournament `FINISHED`, and the
prizes are paid through the ledger, idempotent on `tourney:{id}:{place}:{playerId}`.
Tournament duels are rated as any duel.

The scheduler is `worker`'s, every 5 s, each transition a conditional update on
the tournament's `state` and `version` as above, so two workers, or a restart,
cannot advance a bracket twice.

**The scheduler in detail** (designed 2026-09-30, plan item 18 part (c)):
`worker/TournamentScheduler`, in every worker, with no lock
([D-41](../architecture/03-decision-log.md#d-41--every-worker-runs-the-tournament-clock-and-a-match-is-claimed-before-its-tickets-are-written)).
Each tick, for each tournament not finished or cancelled:

- **Registering**, the deadline reached: fewer than two entries, `CANCELLED`;
  otherwise seeded.
- **Seeded**, the start reached: `RUNNING`, round 1.
- **Running**, its current round:
  - Each pending match with both players is made: in round 1 at once, in a
    later one once the minutes between rounds have passed since the last
    ended. An arena with a free room is chosen and the room promised (none,
    and the match waits for the next tick); the match is claimed, `PENDING` to `READY` with a new match
    id; and only then a ticket is written for each player, the grant kept at
    `tgrant:{tournamentId}:{playerId}` for the ticket's 60 s, and
    `evt.tournament.match` pushed with it: `tournamentId`, `round`,
    `arenaHost`, `arenaPort`, `ticketId`, `tls` and `mode` (`duel`).
  - Each ready match is decided: a recorded result with one player first
    advances that player; a draw, the higher seed; no result 270 s after the
    tickets, the higher seed.
  - Every match of the round done: after the final, the prizes are paid and the
    tournament is `FINISHED`; after any other round, the round is ended.

**Prizes** go through the ledger as reason 3, `ref` the tournament's id, keyed
`tourney:{id}:{place}:{playerId}`: place 1 the final's winner, 2 its loser, 3
each semi-final's loser (none from a semi-final that was a bye). A prize of 0
is not paid. A tick that dies between prizes pays the rest next time, and none
twice.

The public API's routes, each needing a session but the first two:

| Route | Answer |
|---|---|
| `GET /v1/tournaments` | `{"tournaments":[…]}`, those registering, seeded or running, each with its entries → 200 |
| `GET /v1/tournaments/{id}` | the tournament: `state`, `maxEntries`, `registrationEnds`, `startsAt`, `roundMinutes`, `currentRound`, `prizes`, `entries` (`playerId`, `name`, `seed`) and `matches` (`round`, `slot`, `state`, `playerA`, `playerB`, `winner`) → 200; 404 `no_such_tournament` |
| `POST /v1/tournaments/{id}/entries` | registers → 200, the tournament; 401; 404 `no_such_tournament`; 409 `closed`, `too_few_rated`, `full`, `already` |
| `DELETE /v1/tournaments/{id}/entries` | withdraws → 200, the tournament; 401; 409 `not_registered` |
| `GET /v1/tournaments/{id}/match` | the player's grant for their match: `tournamentId`, `round`, `arenaHost`, `arenaPort`, `ticketId`, `tls`, `mode` → 200; 401; 404 `no_match` before there is one and after it expires |

**What the ticket does not carry:** the equipment bonus. Tournament duels are
on equal terms ([Q-16](../requirements/01-scope-and-nfrs.md#7-open-questions)).
**Each match's room is promised** as its arena is chosen ([D-42](../architecture/03-decision-log.md#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen)), so a round's
matches, chosen in one tick, are not all sent to one arena's last rooms: those
past the rooms free wait for a later tick. A scheduler whose claim was lost
gives its promise back. **An arena that turns the tickets away all the same**,
its rooms taken otherwise since it was chosen (D-20), gives no result, and the
270 s rule decides the match.

### Round robin (designed 2026-10-02, plan item 66)

Single elimination sends half the field home after one match. **Round robin**:
every entry meets every other once, and the standings decide. The operator
chooses, `POST /admin/tournaments` `"format"`: `elimination` (the default) or
`round_robin`, for duels or teams alike. The numbers are a first cut of the
balance the owner left to Claude (Q-48), each with its reason:

| | |
|---|---|
| entries | 2 to 8 (400 `invalid_tournament` otherwise): 8 make 7 rounds, already hours at the longest minutes between rounds |
| the schedule | written whole at seeding, by the circle method: seed 1 fixed, the others turning one place a round, so every pair meets once in n − 1 rounds; an odd field adds a "nobody", whose opponent sits that round out, n rounds, and a bye is no match and no points |
| a match | made and decided as an elimination's, round by round: a result with one first is a **win**; both first, a result cut short (not a finish, defect D-34), or **no result** 270 s after the tickets, a **draw**: nobody came, or the arena was lost, and nobody can be shown at fault, where elimination must send someone on and sends the higher seed |
| points | a win 3, a draw 1, a loss 0, football's: a win worth more than two draws, so playing for it pays |
| the standings | by points, then wins, then seed: ties settled by what was played before by who was rated better |
| prizes | places 1, 2 and 3 of the final standings, the third once (elimination pays each semi-final's loser) |

The tournament's view gains `format`, and for round robin `standings`, by
place: each entry's `playerId` (or `teamId`), `name`, `points`, `wins`, `draws`
and `losses`. In the bracket a draw is a match done with no `winner`.

### The second slice: teams' tournaments (designed 2026-09-30, plan item 20)

On [Q-19](../requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation, and [D-44](../architecture/03-decision-log.md#d-44--a-teams-tournament-entry-fixes-its-roster-and-the-bracket-holds-teams-ids-where-a-duels-holds-players). Everything of the first slice holds unless
said here.

| | |
|---|---|
| the mode | chosen by the operator: `POST /admin/tournaments` takes `"mode"`, `duel` (the default) or `teams` |
| an entry | a team, registered by its leader or a vice leader leading a party of exactly three of its members: 400 `party_too_small`, 409 `not_one_team`, 403 `not_allowed`, as a team queues (§4, the sixth slice); then `closed`, `too_few_rated` (the team's own), `full`, `already` as a duel's |
| the roster | those three, for the whole tournament; no substitutes. `DELETE …/entries` withdraws the team, by its leader or a vice leader |
| seeding | by team rating, then by who registered first |
| a match | a team match, mode 5: a ticket to each roster member still in the team, side 1 for the bracket's `player_a`, side 2 for `player_b`, the grant kept and pushed as a duel's, with `"mode": "teams"`. A member who left the team gets none: the side plays short |
| the result | the side placed first wins; a draw, or no result by 270 s, the higher seed. Rated as any team match (§4) |
| prizes | each roster member of a placed team is paid the place's prize, keyed per player as a duel's |

In the view, a teams' tournament has `"mode": "teams"`; its entries carry
`teamId`, `name`, `seed` and `roster` (each `playerId` and `name`); its matches
`teamA`, `teamB` and `winnerTeam` where a duel's have `playerA`, `playerB` and
`winner`.

#### Built in this order

(a) V13 and the repository: the mode, team entries and rosters, seeding by team
rating, the result by side (`persistence`); (b) the operator's mode, and
registering and withdrawing a team (`platform`); (c) the scheduler for teams:
rosters' tickets, the winner by side, prizes to rosters (`worker`); (d) the
client and a live drill.

**(a) to (c) built 2026-09-30.** V13; `TournamentRepository`'s team entries,
rosters (a roster player already on another fails the entry, none of it kept),
seeding by team rating, a disbanded team's entry kept and seeded last, and the
result by side; the operator's `mode`; a team's registration by its party's
leader, the team's leader or a vice leader, and its withdrawal; the view's teams
and rosters, and a bracket named `teamA`, `teamB`, `winnerTeam`; the scheduler's
seats, side 1 and side 2 from the rosters still in each team (not one who left,
and joined another since), the winner by the side placed first, a draw to the
higher seed, and prizes to each roster member, one who left too.

**(d) done 2026-09-30**, and drilled from the release (the drill's `teamcup`):
two teams' parties entered as rosters, the final pushed to all six, the lower
seed's roster winning by walkover, each roster member paid.

### Deciding by what happened (designed 2026-10-02, plan item 54)

The second audit's defects D-33, D-34, T-36 and T-37 ([defects](../defects.md)).

**The bracket is seeded from the entries the seeding locks** (T-36). The
entries were read in one transaction and seeded in another, and an entry or a
withdrawal committing between them left the bracket and the entries
disagreeing: a withdrawn player with no seed, on whom the scheduler threw at
every tick. Now `seed` takes the tournament's row first, as a registration and a
withdrawal do, and reads the entries in that transaction with a locking read.

**A match's tickets are all written before it is claimed** (T-37). They were
written one seat at a time after the claim, and a store failing part-way left
the match claimed with seats that never had one. Now the tickets come first;
the claim follows only when every seat has one, and a lost claim or a failure
before it revokes them and gives back the room's promise, the next tick trying
again. The grants and pushes, keyed by the player, stay with the claim's
winner: another worker may be making the same match.

**No result is not a late one, and a match cut short is not a finish** (defects
D-33, D-34;
[Q-45](../requirements/01-scope-and-nfrs.md#7-open-questions), recommended):

| | |
|---|---|
| nobody came | the arena publishes nothing; 270 s after the tickets, the higher seed, as before |
| somebody came | the arena marks the match, `marr:{matchUid}`, at the first arrival; with no result 270 s after the tickets, the scheduler waits for one up to 30 minutes, a result pipeline's tolerable outage (architecture/02), then the higher seed |
| a result | it decides, however late |
| cut short (an operator's close, a room that failed) | recorded with the result (`matches.cut_short`, V18); not a finish: the higher seed, as for an arena that died with the match |

A late result applied after the bracket moved on is still applied, rated and
paid; only the bracket's decision is the scheduler's.

Built in two parts: (a) the seeding and the tickets, (b) the decision.

## 7. Leaderboards

**Built** (`LeaderboardStore` in `handoff`), on one metric — the best score in
a single match — over three windows:

| Key | Holds | Expires |
|---|---|---|
| `lb:score:alltime` | best score ever | never |
| `lb:score:day:{yyyy-MM-dd}` | best score that UTC day | 3 days |
| `lb:score:week:{yyyy-Www}` | best score that ISO week | 10 days |
| `lb:name` | player id → display name | never |

All-time on its own would be a board no new player can ever enter: a few months
in, the top is held by whoever played most in the first weeks and it stops
being a reason to play. The short windows are where an ordinary player appears.
Both are UTC, because a local day would have to pick someone's local and then
that choice silently decides whose midnight resets the board.

### Why a maximum, not a total

**`worker` writes `ZADD … GT`, never `ZINCRBY`.** A best score is a maximum,
and a maximum is idempotent *and* commutative: the same match applied twice, or
two matches in either order, leaves the same board. That is not a nicety — the
queue feeding this is at-least-once by design, so redelivery is normal traffic
([05 §7](05-worker-and-events.md#7-the-one-place-exactly-once-nearly-leaked)), and
`ZINCRBY` would double-count a score every time a worker died between its
commit and its acknowledgement.

It is also cheaper than the alternative this document used to specify. Writing
an absolute total is idempotent too, but only if you first *read* the total
back out of `player_stat` — a SELECT per player per match. `GT` needs no read:
the store already holds the previous maximum and keeps whichever is larger.

Two consequences worth stating:

- The board update runs on **every** delivery, not only the first. Gating it on
  "this result is new" would make a crash between the MySQL commit and the
  board write permanently lose that ranking, because the redelivery would see
  the rows already present and skip. The repeat *is* the recovery.
- A board failure **does not** hold up the queue. The entry is acknowledged
  either way. This pipeline pays players; the board is a projection of what it
  already wrote, and the player's next match carries their best onto it again.
  Counted as `unranked` on the worker's shutdown line.

### Reading

`platform` only reads. Ranks are 0-based in the store and **1-based on the
wire** — a player is "#1", and converting once at the API boundary beats every
client doing it and one of them forgetting.

| | |
|---|---|
| `GET /v1/leaderboards/{board}?limit=N` | top N, best first. No session: a board is the thing players show each other, and needing a login to read one would keep it off the title screen. `limit` defaults to 50, clamped to 100 |
| `GET /v1/leaderboards/{board}/me` | rank, score and five rows either side. Needs a session. **404 `not_ranked`** when the player has no score there, so the client can say "play a match to be ranked" rather than render a rank of 0 |

`{board}` is `alltime`, `daily` or `weekly`, or a rating board, `duel`, `rffa`
or `tvt` (below); anything else is a 404 `unknown_board`.

Around-me is one command, `J.ZAROUND`, which returns the rank and the window
together. As `ZREVRANK` then `ZRANGE` it would be two round trips against a
board other players are writing to in between, and the rank could disagree with
the window it labels. Display names come from the `lb:name` hash, written by
`worker` from the name MySQL holds when it applies the result, and by a rename
(§1, D-60), so rendering a board is one extra round trip rather than N.

Players on the same score are ordered by member — the player id as a string —
so the order is arbitrary but identical on every read. A board that reshuffled
tied players on each refresh would look broken to the two people watching it.

### What "best" measures, exactly

A subtlety worth stating, because it will otherwise be reported as a bug. In
the public arena a player's stay is **checkpointed every ten minutes**, and a
checkpoint starts a fresh tally ([D-15](../architecture/03-decision-log.md#d-15--the-public-arena-runs-continuously-structured-modes-are-timed-matches),
`MatchTally.checkpointOpenMatch`). So the unit this board ranks is at most ten
minutes of play, and a player who stays three hours produces eighteen records
rather than one large one.

The board therefore measures **the best ten-minute stretch**, not the highest
score a tank ever carried. That is defensible — everyone is measured over the
same bounded unit, so it rewards how well you play rather than how long you can
sit there — but it is *not* what a player who peaked at 5 000 over an hour will
expect to see.

Ranking the peak live score instead would need the simulation to track a
per-life maximum and report it, which it does not. Decide it with a real player
in front of it, not here.

### Rating boards (designed 2026-10-01, plan item 45)

On [Q-40](../requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation. Three more boards, `duel`, `rffa` and `tvt`, each a player's
rating in that mode, read by the same two calls; a player is listed once they
have ten rated matches there (`player.rated_duels`, `rated_rffas`,
`rated_tvts`), ties ordered by more rated matches, then by player id.

**Read from MySQL, not projected into the store.** A rating is not a maximum: it
falls as well as rises, so `ZADD … GT` cannot hold it, and the worker knows the
change it applied, not the rating it left; a projection would need the rating
read back in the result's transaction, and a store loss rebuilt from the table
anyway. The table is the truth, indexed by rating (V17), and `platform` keeps
each board's top for thirty seconds, so a title screen read by everyone costs
the database one query a board every thirty seconds a platform. A player's own
place is read fresh: their rank is the count of players listed above them.

**Built 2026-10-01** (V17; `persistence/RatingBoards`, the rank and the window
read in one read-only transaction so they agree; `platform/RatingLeaderboards`,
the thirty seconds). Not listed here: a banned player is not taken off a board,
as on the score boards.

#### A board's place, measured, then cheaper (designed 2026-10-02, plan item 58)

The second audit found a player's own place walking every account
(defect [D-35](../defects.md#3-data-and-the-result-pipeline)): V17's indexes lead with
the rating, every account that never played a rated match sits in them at the
starting 1 200, and "listed" (ten rated matches) cannot narrow a range on the
rating. **Measured** with `tools/RankBenchmark` on this machine, quiet (load
1.8), 200 000 accounts of which 5 000 listed on the duel board, ratings spread
around 1 200:

| Player | Rank | A place, median (slowest of 21) | Rows read: count, window |
|---|---|---|---|
| top | 1 | 7.7 ms (22.6) | 1, 6 |
| middle | 2 501 | 187 ms (221) | 197 500, 197 506 |
| bottom | 5 000 | 178 ms (192) | 199 999, 200 000 |

Every place below the top read every account, twice: the count, and the window
by `LIMIT offset`. At a million accounts that is a second a call, read fresh.

**Cheaper** ([D-57](../architecture/03-decision-log.md#d-57--a-rating-board-is-indexed-by-its-listed-players-only-and-a-place-read-by-key-not-by-offset)):

- **An index of the listed only** (V20): each board a generated column, the
  rating for a player listed and NULL for anyone else (`board_duel`, …,
  virtual), indexed in the board's order. The unlisted are in no range a board
  reads; the top reads its rows and no more.
- **The count** reads the listed players above, by that index: a place costs
  its rank, not the accounts.
- **The window by key, not by offset**: the rows before read backwards from the
  player's own key, the rows after forwards from it, `each` of each.

**Measured after**, the same tool on the same machine, load 2.7:

| Player | Rank | A place, median (slowest of 21) | Index rows read |
|---|---|---|---|
| top | 1 | 13.5 ms (37.3) | 17 |
| middle | 2 501 | 14.4 ms (48.8) | 5 021 |
| bottom | 5 000 | 18.2 ms (28.2) | 10 016 |

and the top hundred 3.7 ms, 101 rows, as before. The tool counts the index rows
a call read on its connection (the session's handler reads), so before and
after are the same measure: before, 197 507 and 200 006 rows for the middle and
the bottom. About 12 ms of each place is its seven round trips on this machine,
whatever the rank. **MySQL does not read this index backwards** for the rows
before a player; it sorts the range above instead, so that read takes only keys
from the index and fetches whole just the rows shown (a deferred join): first
built reading whole rows, it took 36 ms at the bottom.

A place still costs about twice the players listed above it, from the index
alone: rank in a B-tree is counted, not looked up. A rank kept as a structure
(06 §1) is the next step if boards grow past where that is cheap: at a hundred
thousand listed, the bottom would read two hundred thousand index rows again.
V17's indexes stay until every `platform` reads by the new ones (expand, then
contract: 06 §8), and are dropped by the migration after.

**Rebuilt from MySQL after a store loss**, and safe to run on a healthy store:
`systemctl start backend-leaderboard-rebuild`, built 2026-09-26
([05 §8](05-worker-and-events.md#8-rebuilding-after-a-store-loss)).

### Seasons (designed 2026-10-03, plan item 71 (a))

On the owner's answer to Q-50. A rating board that never ends is won by whoever
got there first; a season gives every player a race they can finish, a place to
keep, and a reason to come back when the next starts
([D-63](../architecture/03-decision-log.md#d-63--a-season-ends-in-three-steps-each-safe-to-repeat-its-places-its-gems-then-its-reset)).
Its numbers are first cuts of the balance the owner left to Claude (Q-48), each
with its reason.

- **Two calendar months each**, from 00:00 UTC on the first of January, March,
  May, July, September and November. Long enough to place (ten rated matches)
  and to climb at the settled K of 16; short enough that a player who starts
  late still has a season ending within reach; months, because a date a player
  can remember is a date they come back for. Season 1 runs from the migration
  that makes it to the end of the two months it falls in. An operator can end
  the current one at once (`POST /admin/seasons/end`), for a drill or to bring
  the calendar into line; the next then runs to the next boundary.
- **The three player boards are the season's**: duel, ranked free-for-all and
  team-vs-team. Team matches' team ratings have no board yet (Q-40) and are
  left alone.
- **When a season ends**, `worker`'s season job closes it in three steps, each
  safe to run again after a crash or by a second worker:
  1. **Places.** Each board's listed players, in the board's order (rating, then
     rated matches, then id), are written to `season_place` with their place,
     rating and rated matches, in one transaction with the next season's row:
     the boards' final standings, as they stood when the job ran. A result
     applied after that counts towards the new season.
  2. **Gems**, by place on each board, through the ledger's one path (reason 6,
     a season's reward, keyed `season:{season}:{board}:{player}`), each with an
     inbox item (`SEASON_REWARD`) saying the season, the board and the place,
     and an `evt.inbox` push:

     | Place | Gems | Why |
     |---|---|---|
     | 1 | 100 | Five boosts: the season's one headline, worth fighting for |
     | 2 | 60 | Three boosts |
     | 3 | 40 | Two boosts |
     | 4 to 10 | 25 | A boost and a bit: the top ten is the board's first screen |
     | 11 to 100 | 10 | Half a boost: a place a player can aim for from the middle |
     | every other player listed | 5 | A quarter: for finishing the ten rated matches that list a player, a nudge, not a living |

     A player listed on all three boards is paid on each. Against item 68's
     sources: a tournament's winner 30, every milestone 20; a season's top ten
     is a tournament's second place, its winner more than three tournaments.
  3. **The reset.** Every rating halfway back to 1 200 (a 1 600 player starts
     the new season at 1 400, an 800 player at 1 000) and every rated count to
     0, so the board lists only those who have played the new season's ten. The
     rating keeps matchmaking's signal, the strong start higher, while the
     board is a new race; the count back to 0 also gives each player the new
     player's K of 32 for thirty matches, so the board sorts itself out fast.
     In batches of 1 000 players by id, up to the highest id when the season
     ended, each batch with the season's progress in one transaction, so a
     restart goes on where it stopped and halves nobody twice; an account made
     after the end is the new season's already.
- **What a player sees.** `GET /v1/seasons`: the current season, its number and
  when it ends, and the past ones. `GET /v1/leaderboards/{board}?season={n}` and
  `/me?season={n}`: a past season's final places, from `season_place`; without
  `season`, the board as it stands, which is the current season's. The reward
  arrives as an inbox item and a push.
- **Its job**: every worker looks each minute; one takes the store's
  `job:season` lock (`SET NX EX`, ten minutes), as the ledger check does, and the
  steps' own conditions make a second runner harmless.
- **The operator's calls** (§10, audited): `GET /admin/seasons` (GET only), every season and
  how far its close has gone (`placedAt`, `paidAt`, `resetAt`); `POST
  /admin/seasons/end` `{"reason"}`, the current season ends now → 200 `{"season",
  "endsAt"}`, 400 `no_reason`, 409 `already_ended` while the worker closes one.
- **The inbox item**: `season_reward`, its `ref` the season × 10 + the board's
  mode (1 duel, 2 team-vs-team, 3 ranked free-for-all); the client reads the
  place with `/me?season=`.

**Built 2026-10-03** (plan item 71 (a)): V27, `SeasonRepository`, the worker's
`SeasonKeeper`, the calls above and the client's. On the development data, a
season closed 54 s after an operator ended it: 42 places paid (695 gems) in
under 2 s, and 5 571 players reset in 21 batches in under 1 s.

### A board of teams, and its seasons (designed 2026-10-04, plan item 73)

On the owner's answer to Q-51. Q-40 left a team's rating, which team matches
move (D-43), without a board: a player board cannot show it
([D-65](../architecture/03-decision-log.md#d-65--a-teams-season-place-pays-each-member-who-played-for-it-that-season)).

- **The board** is the player boards' kind: a team listed once it has ten rated
  team matches, by rating, then more rated matches, then id; read from MySQL
  where the rating is, the top kept thirty seconds, a place read fresh (V28 adds
  the listed rating as a virtual column and its index, as V20 did for players).
  `GET /v1/leaderboards/teams` lists teams: each entry's `teamId` and the team's
  name, `score` its rating. `/me` is the caller's team's place: 404 `not_in_team`
  without one, `not_ranked` before its ten.
- **Its seasons are the players'.** When a season ends its first step also
  writes each listed team's place (`season_team_place`), and who it pays
  (`season_team_payee`): **each member at the end who played at least one of
  the team's rated team matches that season**. Each is paid the place's gems,
  by the players' table (100, 60, 40, 25 to the tenth, 10 to the hundredth, 5
  to every other team listed), keyed `season:{season}:5:{player}`, with a
  `season_reward` inbox item whose `ref` names the season and board 5. The same
  place pays the same, alone or together: a team is a way to play, not a way to
  be paid more. Who played, not who belongs: a player who joins a team the
  night a season ends is not paid its place, nor is one who never played for it.
- **The reset**: every team's rating halfway back to 1 200 and its rated
  matches 0, as players', in one statement up to the highest team id at the end
  (teams are a few percent of players), recorded in the season's row; a team's
  wins, losses and draws are its record, and are kept.
- **What a player sees**: `GET /v1/leaderboards/teams?season={n}`, a past
  season's teams; `/me?season={n}`, the place of the team that player was paid
  for, `not_ranked` for one who was not.

**Built 2026-10-04** (plan item 73): V28; `TeamBoards`, by the player boards' own
queries over the team table (`RatingBoards.Columns`); the season's team places,
payees and reset; the routes above and the client's `RankRow.TeamId`.

### Not built

- **Total-kills boards.** A totals board needs the read-back
  described above, and has no caller. Rating boards were built 2026-10-01
  (above), seasons 2026-10-03, the board of teams 2026-10-04.
- **Growth is unbounded.** `lb:score:alltime` and `lb:name` hold every player
  who has ever scored. At launch scale that is megabytes; trimming with
  `ZREMRANGEBYRANK` would cap it at the cost of silently having no rank for
  anyone outside the cap, which is the more valuable property. Revisit with a
  measurement, not a guess.

## 8. Economy, shop, inventory and equipment

- **Currencies**: `coins` (earned) and `gems` (rare: tournaments, milestones,
  seasons, achievements, daily goals; and, since the owner brought real money
  into scope on 2026-10-04, bought, the payment provider simulated for now:
  [Revenue](#revenue-designed-2026-10-04-plan-item-75)).
- **Every change goes through one path**: `economy.adjust(playerId, delta,
  reason, ref, idemKey)`, which writes the ledger row and the balance in one
  transaction ([06 §4](06-persistence-mysql.md#4-the-three-transactions-that-matter)).
  There is no second way to change a balance — that is what makes the nightly
  reconciliation meaningful.
- **Shop catalogue** in `shop.json`: `{ sku, itemId, price, stock, availableFrom/To,
  requiresLevel }`. Purchases are idempotent on the client's transaction id.
  Limited stock uses `DECR stock:{sku}` in j-redis before the transaction and
  `INCR` back on failure — a hot counter belongs in the store, its consequence
  in the database.
- **Items** in `items.json`, referenced by key; the database stores what is
  owned, the content tables define what it does:

```json
{ "id": "eq_barrel_steel", "type": "EQUIPMENT", "slot": "BARREL", "rarity": "RARE",
  "modifiers": [ { "stat": "BULLET_DAMAGE", "op": "ADD_PERCENT", "value": 0.08 } ],
  "maxLevel": 5, "upgradeCost": [100, 250, 500, 1000] }
{ "id": "boost_xp_2x_1h", "type": "BOOST", "durationSec": 3600, "xpMultiplier": 2.0 }
{ "id": "eff_adrenaline", "type": "EFFECT", "durationTicks": 250,
  "modifiers": [ { "stat": "RELOAD", "op": "MUL", "value": 0.8 } ], "trigger": "ON_KILL" }
```

- **Equipment**: one item per slot (`BARREL`, `ARMOR`, `CORE`, `TREADS`), item
  level scaling the modifier linearly. **Total equipment bonus is capped** at
  about +25 % on any one stat, so matches stay skill-driven rather than
  wallet-driven.
- **Boosts**: activating pushes `{boostId, expiresAt}` onto the player's active
  list and decrements inventory; expired entries are pruned on read.
- **Effects** are in-match only. The arena owns the logic; the ticket tells it
  which effects the loadout grants.

### The shop, as built (2026-09-26)

Built ahead of Phase 5, at the owner's request, when the rest of this section
(items, equipment, boosts, effects) was not; equipment, boosts, item levels,
gem prices and skins are built since (below), effects are not. What the bullets
above left open is decided here.

**The catalogue** is `shop.json` on `platform`'s classpath, so it ships in the
release and every instance sells the same things at the same prices; changing it
is a release. A list of offers:

```json
{ "sku": "barrel_steel", "itemId": "eq_barrel_steel", "price": 200,
  "requiresLevel": 5, "availableFrom": "2026-10-01T00:00:00Z", "availableTo": null }
```

It is checked at start, and a catalogue that breaks a rule stops `platform`
from starting (exit 2), rather than selling something wrong:

- `sku` and `itemId` are lowercase letters, digits and `_`; a `sku` at most 64
  characters and appears once; an `itemId` at most 40, the column's width.
- `price` is 1 to 1 000 000 000, in coins unless the offer says
  `"currency": "gems"` (plan item 68, below). When this was built gems had no
  source, and nothing was sold for them. A free offer is refused: free things
  are rewards, and a free offer could be taken once per key, which is without
  limit.
- `requiresLevel`, the account level (1 to 100), defaults to 1;
  `availableFrom` and `availableTo` are optional instants, the first before the
  second.
- **Not built: limited stock.** A catalogue that sets `stock` is refused, so
  nobody configures a limit believing it holds. What stands between the design
  and building it: after a loss of the store the counter must be rebuilt from
  the ledger, or the limit starts again from the top.

**The release shipped an empty catalogue** while an item did nothing: selling
one would have taken coins for nothing. It sells twelve offers now: each of the
five pieces of equipment for coins, and the two boosts and five skins for gems
(the catalogue below, plan items 68 and 75 (c)); their prices are Claude's
first cut of the balance the owner delegated (Q-48).

**The API**, with the session token as a bearer credential, as for match
requests:

| | |
|---|---|
| `GET /v1/shop` | the offers on sale now → 200. No session needed |
| `GET /v1/content/classes` | the class table a client draws by, `{"version","classes"}`, its version its `ETag` and the arena's `Welcome.contentVersion`; 304 to an `If-None-Match` that names it. No session needed ([D-24](../architecture/03-decision-log.md#d-24--the-class-table-reaches-the-device-from-platform-versioned-by-its-content)) |
| `GET /v1/content/phrases` | the phrase list, `{"version","phrases"}`, each `id`, `key` and English `text`; its version its `ETag` and the arena's `Welcome.phraseListVersion`; 304 as above. No session needed. **Built 2026-09-29** ([01 §9](01-arena.md#phrases-designed-2026-09-29-plan-item-8)) |
| `GET /v1/inventory` | coins, gems and the items held → 200, 401 |
| `POST /v1/purchases` `{"sku","key"}` | 200 `bought` or `already_bought`, with the item, the coin balance (and, since plan item 68, the gems) and how many of it the player now holds; 400 `invalid_body` or `invalid_key`, 401, 403 `level_required`, 404 `unknown_sku`, 409 `not_available` or `insufficient_funds` |

**The key** is the client's transaction id: made once per tap of Buy and sent
again, unchanged, with every retry of that tap. 16 to 48 lowercase letters,
digits and hyphens: a UUID, in lower case. Lower case because the ledger
compares keys without regard to case or accents (`utf8mb4_0900_ai_ci`), so `Ab`
and `aB` would be one key; at least 16 so that it is random, not a counter that
starts again at 1 when the app is reinstalled and finds its first purchases
"already bought".

**A retry is answered as the first attempt was**, even when the offer has since
ended, its price has changed or the balance has run short: the key is looked up
before anything else, and a key already used returns `already_bought` with the
item it bought, whatever `sku` the retry names. It is the rule 06 §4 applies to
the funds check, one step earlier.

Counted in `backend_platform_purchases_total{outcome}`, the "purchase failures
by cause" of §11.

### Equipment (designed 2026-09-30, plan item 15)

What the bullets above sketched, cut to what equipment needs; boosts and effects
come later (Q-12) and extend it. **Built 2026-09-30**, all of it, and drilled
from the release: a player earned coins in a stay, bought a barrel, wore it, and
fired bullets 25 % faster in the next.

**Items** are `items.json` on `platform`'s classpath, beside `shop.json`, and
checked at start the same way: a table that breaks a rule stops `platform`
(exit 2). Each item: an `id` (the `itemId` rules) and a `type`, `EQUIPMENT`,
`BOOST` (§8, boosts) or `SKIN` (revenue (c)). Equipment has a `slot`, `barrel`,
`armor`, `core` or `treads`, at most one of each worn, and one to three
`modifiers`, each a `stat` (one of the eight, by its name: `bullet_damage`) and
a `percent`, 1 to 25. An item gives its percent in that stat at level 1; levels
2 to 5 are built since (item levels, below). A shop offer must name an item in the
table, checked at start too. The table ships with a few first-cut items, and
the shop sells them (§8, the catalogue, plan item 68).

**The bonus** is "better by that much": for reload, shots that much more often;
for the rest, the stat that much higher. Everything worn is added per stat and
**capped at 25 %** a stat, so a match stays won by play (the design's "about
+25 %", made exact).

**The API**, bearer session as for inventory:

| | |
|---|---|
| `GET /v1/equipment` | `{"slots":{"barrel":"barrel_steel","armor":null,…},"bonus":{"bullet_damage":8}}`: each slot an item id or `null`, and the bonus a stat, capped, for the stats it touches → 200, 401. **Built 2026-09-30** |
| `PUT /v1/equipment/{slot}` `{"itemId"}` | wears it in that slot, replacing what was there → 200, as `GET`; 400 `invalid_slot` or `invalid_body`, 401, 404 `unknown_item`, 409 `wrong_slot` (the item is for another), 409 `not_owned` |
| `DELETE /v1/equipment/{slot}` | the slot emptied → 200, as `GET`; 400 `invalid_slot`, 401 |

Wearing the same thing twice is the same as once. The `equipment` table (V1)
holds a row a worn slot; nothing new in the schema.

**The loadout travels in the ticket.** When `platform` issues a ticket, for the
public arena or a made match, it reads what the player wears and owns and puts
the resolved bonus in it: the capped percent a stat, the stats that have one
([D-37](../architecture/03-decision-log.md#d-37--equipment-reaches-the-arena-as-a-capped-percentage-a-stat-in-the-ticket)).
An item worn but no longer owned gives nothing. The arena applies it to every
tank the player spawns in that stay (01 §3) and never reads inventory (FR-10).
A change of loadout counts from the next ticket. The skin worn travels beside
it, in a field of its own, `skin`, and `s:{id}` in a queue entry (plan item 75
(c), D-70).

For a made match it is read when the player queues, with the name and the
rating, and kept in the queue entry beside them (`b:{id}`), so the matcher
still writes tickets from the store alone and matchmaking does not stop when
MySQL does; a loadout changed while queued counts from the next queue. The
bonus travels as `stat:percent` pairs, `5:8,6:10`, in the ticket's `bonus`
field, absent when there is none; a ticket whose bonus does not read, or names a
percent outside 1 to 25, is refused as any malformed ticket is.

### Item levels (designed 2026-10-02, plan item 67)

Every item was level 1, as `inventory_item.item_level` (V1) recorded. Now a
player raises an equipment item they hold, a level at a time, by spending coins.
The numbers are a first cut of the balance the owner left to Claude (Q-48), each
with its reason:

| | |
|---|---|
| levels | 1 to 5 (`EconomyRepository.MAX_ITEM_LEVEL`) |
| what a level gives | each of the item's modifiers × (3 + level) / 4, rounded down: level 1 as before, level 5 twice it (an 8 % barrel 16 %). The cap a stat of everything worn, 25 %, stands: a level makes an item worth having, and a match is still won by play |
| what a level costs | from level L, 500 × 2^(L−1) coins: 500, 1 000, 2 000 and 4 000 to reach 2, 3, 4 and 5, 7 500 in all. A match pays 10 to 50 coins and one a ten of score, about 100 for a five-minute stay: the first level a few stays, the last some three hours' play, an item at its top six or so; one constant each, `LEVEL_BASE_COST` and the doubling |
| boosts | have no levels: only equipment is raised |

| | |
|---|---|
| `POST /v1/inventory/{itemId}/level` `{"key"}` | raises it a level → 200 `{"itemId", "level", "coins"}`; 400 `invalid_key` (16 to 48 lowercase letters, digits or hyphens, as a purchase's) or `not_equipment`; 401; 404 `not_held`; 409 `max_level`, `insufficient_funds`. A key used before answers what it did, as a purchase's does |

**One transaction, as a purchase**: the player locked first, the item's level
read under the lock, the coins moved by the ledger's one path (reason 4,
`level`, keyed `level:{playerId}:{key}`, `ref` the item and the level reached),
then the level raised from the level read. `GET /v1/inventory` gives each item's
`level`; the bonus a worn item gives, and so the ticket's, is its level's (D-37's
path unchanged).

### The catalogue, and gems (designed 2026-10-02, plan item 68)

What the shop sells, for how much, and where gems come from, a first cut of the
balance the owner left to Claude (Q-48). The scale it is set against: a match
pays 10 coins, 50 for a win, and one a ten of score, about 100 for a
five-minute stay, some 1 200 an hour of play; an item's levels cost 7 500.

**(a) The catalogue**, the release's `shop.json`: each piece of equipment, for
coins.

| Offer | Coins | Level | Why |
|---|---|---|---|
| `treads_light`, movement 8 % | 1 200 | 1 | the least decisive in a fight, the cheapest |
| `barrel_steel`, bullet damage 8 % | 1 500 | 1 | the first barrel |
| `armor_plate`, health 10 % | 1 500 | 1 | the first armour |
| `barrel_rifled`, bullet speed 10 %, penetration 5 % | 2 000 | 5 | two stats, and something to reach |
| `core_capacitor`, reload 8 % | 2 000 | 5 | reload is a weapon's every stat at once |

An item an hour or two of play, a slot each worth an evening; with levels, a
loadout a goal of weeks.

**(b) Gem prices**: an offer may be priced in gems, `"currency": "gems"` in
`shop.json` (coins by default). A purchase in gems takes them through the
ledger's one path as coins are taken (currency 1, reason 1), the player locked
first; `GET /v1/shop` gives each offer's `currency`, and a purchase's answer its
`gems` beside its `coins`. The boosts are sold for gems, 20 each: a boost
doubles what an hour of play pays, a convenience and never strength, and gems
are rare, so a boost is a podium's or a milestone's worth (part (c)).

**(c) Where gems come from**: two sources, both rare, both paid by the system
through the ledger's one path in gems (currency 1), each once whatever retries.

| Source | Gems | Why |
|---|---|---|
| a tournament's places | 1st 30, 2nd 15, 3rd 5 (each semi-final's loser in an elimination, the third of a round robin's standings), with 4 entries or more; each roster member of a team placed, as its coins are | a podium is worth a boost or more; two entrants agreeing to meet farm nothing, and Q-43's ten rated matches already gate who enters |
| an account level reached | 20 at level 5, then 20 at every tenth level, 10 to 100: 220 in all | a boost each. Level 5 is two or three days of casual play, where the dearer equipment opens too, so gems are met while a player is still deciding to stay; level 10 is a week, 30 a couple of months, 100 the long goal (`AccountLevels`) |

The tournament's gems are paid with its coin prizes, at the finish, keyed
`tourney:{id}:{place}:{player}:gems` (reason 3), and by the rule, whatever the
operator set in coins: a tournament with no coin prizes still pays its places
gems, and the inbox tells them so. A level's gems are paid in the result's own
transaction, as the level is written, for each milestone above the level stored
and up to the new one ([D-61](../architecture/03-decision-log.md#d-61--a-levels-gems-are-paid-for-the-milestones-between-the-level-stored-and-the-new-one)),
reason 5, `milestone`, keyed `milestone:{player}:{level}`: a redelivery pays
none, and a result that lifts a player past two milestones pays both.
`evt.rewards` gains the `gems` the result paid (05 §6). The nightly ledger check
reconciles gems as it does coins, in the same statement (05 §9): with gems paid
by the system and spent in the shop, a balance moved without its row is the same
fault in either.

### Achievements (designed 2026-10-03, plan item 71 (b))

On the owner's answer to Q-50: more to earn beyond the level milestones.
Achievements are goals a player can see coming, each a threshold on one of the
stats every result already counts (`player_stat`), paid in gems once, when a
result carries the stat across it
([D-64](../architecture/03-decision-log.md#d-64--an-achievement-is-paid-when-a-result-carries-its-stat-across-the-threshold)).
First cuts of the balance the owner left to Claude (Q-48), the table in one
place, `persistence/Achievements`:

| Stat | Thresholds, and gems | Why |
|---|---|---|
| kills | 100 → 10, 1 000 → 20, 10 000 → 50 | 100 is a few evenings; 10 000 the long goal of a player who stays |
| wins (a made match placed first; a draw places both first) | 10 → 10, 100 → 20, 1 000 → 50 | made matches only, so a stay in the public arena never counts; 10 is a first week of queueing |
| stays and matches played | 50 → 10, 500 → 20, 5 000 → 50 | showing up: 50 is a week of casual play |
| assists | 100 → 10, 1 000 → 20 | helping is play too (01 §7); two, as assists come slower than kills |
| best score in one stay | 10 000 → 10, 50 000 → 20, 100 000 → 50 | the score board's skill, without needing to top the board |
| time played | 10 hours → 10, 100 → 20, 1 000 → 50 | the patient, whatever their kills |

Seventeen, 430 gems in all, against milestones' 220: the long goals are where
most of them are, a boost or two a goal reached early, two and a half the
long ones.

- **Paid in the result's transaction**, as the stats are written: the stats are
  read in the locking read that already reads the player's level, and for each
  achievement whose threshold the stat was under before the result and is at or
  over after it, gems through the ledger's one path, reason 7,
  `achievement:{id}`, keyed `achievement:{player}:{id}`. A redelivery pays none;
  a result across two thresholds pays both.
- **Told by `evt.rewards`**: its `gems` count them with the milestones', and a
  new `achievements` names each one reached, for the client to show.
- **What a player sees**: `GET /v1/achievements`, every achievement, its stat,
  threshold and gems, the player's progress (the stat as it stands) and whether
  it is reached. Reached is the stat at or over the threshold: stats only grow.

**Built 2026-10-03** (plan item 71 (b)): `Achievements`, the locking read joined
to `player_stat`, `payAchievements` in the result's transaction, `evt.rewards`'
`achievements`, `GET /v1/achievements` and the client's `Achievements`.

### Daily goals (designed 2026-10-04, plan item 74)

On the owner's answer to Q-51: more for players to earn. Three goals a day,
met by play, each paid once; a new three at each UTC midnight
([D-66](../architecture/03-decision-log.md#d-66--a-days-goals-are-drawn-not-stored-and-paid-as-results-meet-them)).
First cuts of the balance the owner left to Claude (Q-48), the table in one
place, `persistence/DailyGoals`:

| Kind | Easy, coins | Hard, coins |
|---|---|---|
| stays and matches played | 3 → 100 | 6 → 200 |
| kills | 10 → 100 | 30 → 200 |
| wins (a made match placed first) | 1 → 150 | 3 → 300 |
| assists | 5 → 100 | 15 → 200 |
| score, summed over the day | 5 000 → 100 | 15 000 → 200 |
| time played | 20 minutes → 100 | 60 minutes → 200 |
| rated matches played | 1 → 150 | 3 → 300 |

**All three done: 3 gems**, a boost a week for a player who comes back each
day. Why these: a five-minute stay pays about 100 coins; three goals are twenty
minutes to an hour of play for 300 to 800 coins, a half to a whole again on top
of that time: worth aiming for, never a living without the play. Wins and rated
matches pay more, as they ask for a queue and a result.

- **The day is the result's**, its end's UTC date, as `player_day` counts it: a
  match ending after midnight counts for the new day. Midnight UTC is one reset
  for everyone, at a local hour that differs by country; the client shows the
  time left.
- **The three are drawn, not stored**: three different kinds, each easy or
  hard, chosen by a fixed function of the player and the day (SplitMix64 over
  both), so `worker` counting and `platform` listing agree without a write.
  Changing the pool changes the draw: ship it at a day's start.
- **Counted and paid in the result's transaction**, under the player's lock:
  each of the day's three moved by what the result counts, its progress kept in
  `daily_goal`; a goal reaching its target is paid its coins through the
  ledger's one path (reason 8, keyed `daily:{player}:{day}:{goal}`), and the
  result that completes the third pays the set's gems (`daily:{player}:{day}:set`).
- **Told by `evt.rewards`**: `goals` names each goal met, `goalCoins` their
  coins; the set's gems are in `gems`.
- **What a player sees**: `GET /v1/goals`, the day, when it ends, and its three
  goals, each its kind, target, coins, progress and whether done; and the set.
- **Kept a week**: retention deletes progress older than seven days.

**Built 2026-10-04** (plan item 74): `DailyGoals`, `DailyGoalRepository`, V29;
the draw injected into `MatchResultRepository` (`DailyGoals::of` in `worker`, no
goals where a test is about something else, whatever the day); the goals and the
set paid in the result's transaction; `evt.rewards`' `goals` and `goalCoins`;
retention's purge; `GET /v1/goals` and the client's `Goals`.

### Revenue (designed 2026-10-04, plan item 75)

On the owner's answer to Q-51: the economy enhanced, with a revenue stream; real
money comes into scope (requirements §6). The first rule is the one the shop has
kept since gems were priced (plan item 68): **money buys convenience and looks,
never strength**
([D-67](../architecture/03-decision-log.md#d-67--money-buys-convenience-and-looks-never-strength)).
Gems are what money buys; nothing sold for gems or money makes a tank stronger,
so coins, which buy equipment and its levels, are never sold. In three parts,
each designed whole at its turn:

- **(a) Gems for money**: packs bought through a payment provider, an order
  granted once it is paid, refunds taken back; **the provider is simulated**:
  the owner wants no third-party payment or sign-in integration for now
  (2026-10-04, Q-52)
  ([D-68](../architecture/03-decision-log.md#d-68--a-purchase-is-an-order-its-provider-confirms-granted-once-and-a-refund-is-taken-back)).
- **(b) A season pass**: points from play every season; a free track of
  rewards and a premium one, the premium bought with gems.
- **(c) Looks**: tank skins sold for gems, worn in a slot of their own with no
  modifier, carried to every client that sees the tank.

**(a) Gems for money.** The packs, a first cut (prices in US dollars, for the
simulated provider and for the store tiers a real one would use):

| Product | Gems | Price | Gems a dollar | Why |
|---|---|---|---|---|
| `gems_80` | 80 | 0.99 | 81 | four boosts: the impulse buy |
| `gems_500` | 500 | 4.99 | 100 | a season pass, a common first purchase |
| `gems_1100` | 1 100 | 9.99 | 110 | a pass and skins |
| `gems_2400` | 2 400 | 19.99 | 120 | |
| `gems_6500` | 6 500 | 49.99 | 130 | the most for those who choose to spend |

**A player's first purchase pays its gems twice**, once ever, keyed by the
player: the most common converter in the genre, and cheap, as it is once.

- **The flow is a real provider's, the provider simulated.** The client asks for
  a pack: `POST /v1/payments {"productId"}` makes an **order** (`pending`, its
  id, the pack, its price), the client's key making a retried ask the same
  order. The provider takes the payment and tells the server: here the
  simulated provider, `POST /v1/payments/{orderId}/simulate {"outcome": "paid" |
  "declined"}`, the player's own call standing in for the provider's page and its
  notification, which calls the one path a real provider's notification would:
  **confirm**. A paid order grants the pack's gems through the ledger's one
  path (reason 9, keyed `payment:{orderId}`), and the first purchase's bonus;
  `paid` and `declined` are final, a second confirm answers what the first did.
  `GET /v1/payments/{orderId}` says where an order is, `GET /v1/payments/packs`
  what is on sale. **A provider's whole part is one call, confirm**: a real one,
  when the owner chooses one, is a notification route that makes it, in place
  of the simulated route, and nothing after confirm changes. The simulated route
  is served only while the configured provider is the simulated one (the
  default, and the only one there is).
- **Refunds** are taken back: an operator refunds a paid order
  (`POST /admin/payments/{orderId}/refund`, audited; a real provider's refund
  notification would call the same path), once; its gems, its first-purchase
  bonus too, are taken back as far as the balance allows (reason 10), and **what
  could not be taken is a debt**, kept on the order: a player in debt cannot
  order until an operator clears it (`POST /admin/players/{id}/refund-debt`).
- **Orders left pending** a day are expired by retention; nothing was granted.
- **Off unless named** (D-68): the simulated provider grants gems to whoever
  asks, so it runs only where the operator names it,
  `BACKEND_PAYMENT_PROVIDER=simulated` (development and drills). Unset, every
  payment route answers 503 `payments_off`; any other name stops the start.

**The packs** are `packs.json` in the platform's release, read once at start
and checked as strictly as the shop's catalogue: a list of `{"productId",
"gems", "priceCents"}`, the id 1 to 64 lowercase letters, digits or `_`, gems
and cents whole numbers from 1 to 100 000, priced in US dollars; an unknown
field or a product listed twice stops the start.

| Route | Answer |
|---|---|
| `GET /v1/payments/packs` | `{"packs":[{"productId","gems","priceCents","currency"}]}` → 200 |
| `POST /v1/payments` `{"productId","key"}` | an order, pending; the same order for a key already used → 200 `{"order"}`; 400 `invalid_body`, `invalid_key` (16 to 36 lowercase letters, digits or hyphens: a UUID); 401; 404 `unknown_product`; 409 `refund_debt` |
| `GET /v1/payments/{orderId}` | → 200 `{"order"}`; 401; 404 `no_such_order` (none, or another player's) |
| `POST /v1/payments/{orderId}/simulate` `{"outcome"}` | the simulated provider's confirm, `paid` or `declined` → 200 `{"order","confirmed","gems"}`, `confirmed` false when the order was no longer pending, `gems` the balance after; 400 `invalid_outcome`; 401; 404 `no_such_order` |
| `POST /admin/payments/{orderId}/refund` `{"reason"}` | → 200 `{"orderId","playerId","taken","debt"}`; 404 `no_such_order`; 409 `not_paid` (pending, declined, expired, or refunded already); audited in the refund's transaction (D-30) |
| `POST /admin/players/{id}/refund-debt` `{"reason"}` | → 200 `{"playerId","cleared"}`, the gems of debt cleared; audited in its transaction |

Every payment route answers 503 `payments_off` while no provider is named. An
**order** is `{"orderId","productId","gems","bonus","priceCents","currency",
"state","createdAt"}`, its state `pending`, `paid`, `declined`, `refunded` or
`expired`, and `bonus` the first purchase's gems.

**(b) The season pass** (designed 2026-10-04,
[D-69](../architecture/03-decision-log.md#d-69--a-season-pass-pays-each-tier-as-its-points-cross-it-each-track-to-its-own-mark-and-its-premium-never-pays-coins)).
Every player holds each season's pass: **pass points** from play, a **tier**
every 250 of them, 40 tiers; a **free track** paying everyone at each tier, and
a **premium track** paying more to whoever bought it for the season, for 500
gems (the `gems_500` pack).

- **Points**, in the result's transaction, for the season being played when the
  result is applied (the newest not yet placed, as the boards count it): **10**
  for each result a player is paid (a redelivery earns nothing, as it pays
  nothing), and **50** for each daily goal the result meets. A day's three goals
  and ten stays are 250, a tier a day: the 40th in forty of the season's
  sixty-odd days; a player who comes back every other day gets about halfway.
  The goals carry most of it: the pass rewards coming back, not grinding.
- **The tiers**, first cuts (Q-48):

  | Tier | Free | Premium |
  |---|---|---|
  | each, but every fifth | 150 coins | 15 gems |
  | every fifth: 5, 10 … 40 | 5 gems | 15 gems and a boost: the hour's xp boost at 5, 15, 25 and 35, the hour's coins boost at 10, 20, 30 and 40 |

  The free track over a season: 4 800 coins and 40 gems, beside the daily
  goals' 450 or so coins a day a sixth more for the same play. The premium
  track: 600 gems and eight boosts (160 gems at the shop's price) for 500; a
  player who finishes it can buy the next season's and keep 100, the genre's
  loop, and a reason to play the season out. **Never coins on the premium
  track** (D-67): bought with gems, which money buys, coins there would sell
  equipment.
- **Paid on crossing**, as achievements (D-64): a player's pass row keeps its
  points and, for each track, the last tier paid. A result that raises the
  points pays each free tier between that mark and the tier reached, and with
  premium each premium tier; buying premium pays at once every premium tier
  already reached. Coins and gems through the ledger's one path (reason 11, a
  pass tier, keyed `pass:{season}:{free|premium}:{tier}:{player}`), a boost into
  the inventory, all in one transaction under the player's lock; the marks make
  each tier once.
- **Premium**: 500 gems through the ledger (reason 1, a purchase, keyed
  `pass:{season}:{player}`, so a second tap is answered as the first), for the
  season being played, and refused once its end has passed and it is being
  closed.
- **When a season closes** nothing is left to pay. The next season's pass starts
  at nothing, without premium. Pass rows are kept six seasons, a year, then
  deleted by retention.
- The tiers' boosts are items in `items.json`: a pass naming an item the table
  has not stops the platform's start, as the catalogue's offers do.

| Route | Answer |
|---|---|
| `GET /v1/pass` | the season being played's pass: `{"season","endsAt","points","tier","premium","premiumGems","tierPoints","tiers":[{"tier","free":{"coins","gems","itemId"},"premium":{"coins","gems","itemId"}}]}` (a premium tier's `coins` always 0), a tier paid once reached (premium: once bought too) → 200, 401 |
| `POST /v1/pass/premium` | buys the premium track for the season being played, paying the premium tiers reached → 200 `{"result":"bought" \| "already_bought","gems","pass"}`, `gems` the balance after; 401; 409 `insufficient_funds`, `season_ended` |

`evt.rewards` gains `pass`: `{"points","tier","coins","gems","items"}`, the
points this result earned, the tier after, and what the tiers it reached paid;
they are in neither `coins` nor `gems`.

**(c) Looks: tank skins** (designed 2026-10-04,
[D-70](../architecture/03-decision-log.md#d-70--a-tanks-skin-travels-in-its-own-ticket-field-and-is-told-by-an-event-beside-the-tanks-create)).
A **skin** is how a tank is drawn, seen by everyone whose view it enters, and
does nothing else.

- **An item**, type `SKIN` in `items.json`: `{"id","type":"SKIN","skin"}`, `skin`
  its number on the wire, 1 to 255, each once in the table. **Worn in a slot of
  its own, `skin`**, the fifth, with `PUT /v1/equipment/skin` `{"itemId"}` and
  taken off with `DELETE`, as equipment is; `GET /v1/equipment` lists the slot.
  No modifier, so no bonus, and no levels: raising one is 400 `not_equipment`.
- **Sold for gems** in the shop, first cuts (Q-48):

  | Skin | Item | Gems | Why |
  |---|---|---|---|
  | 1 | `skin_crimson` | 150 | a colour: under two dollars of the `gems_1100` pack, an impulse |
  | 2 | `skin_azure` | 150 | |
  | 3 | `skin_jade` | 150 | |
  | 4 | `skin_gold` | 400 | the one that is noticed: four boosts' worth, under a season pass |
  | 5 | `skin_carbon` | 400 | |

- **Carried to every client that sees the tank** (D-70): the number of the skin
  worn and held goes in the ticket, in a field of its own, `skin`, read when the
  bonus is, at a join or a queue; the arena keeps it on the tank, as it keeps the
  bonus, a resumed tank too; and each client a tank is created for is told by the
  event `Skin` (7), `u8 handle, u8 skin`, in the frame with the create, for a
  tank with a skin only ([02 §4](02-networking.md#event-types)). No new protocol
  version: an older client steps over the event. A skin worn mid-stay shows from
  the next join. Tournament matches show none: their tickets are the worker's,
  which has no item table (Q-16).
- **What each number is**: `GET /v1/content/skins`, `{"version","skins":[{"skin",
  "itemId"}]}`, the table a client draws by, versioned by its content as the class
  table is (D-24): an `ETag`, and 304 to an `If-None-Match` that names it. No
  session needed.

### Boosts (designed 2026-09-30, plan item 16)

On [Q-13](../requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation. **Built 2026-09-30**, and drilled from the release: a stay paid
10 coins, a coins boost was activated, and the next stay paid 20. **A boost item** is in `items.json` with `type` `BOOST`, a
`kind`, `xp` or `coins`, a `percent`, 1 to 100, and `minutes`, 1 to 1 440.
Held like any item; **activated**, one is taken from the inventory and the
boost runs.

**One a kind at a time.** Activating a boost of a kind already running adds its
minutes, if it is the same item; a different item of that kind is refused
until the first ends. Activating one of a kind not running starts it now. Each
run is kept, and a run's end only moves forward, so whether a boost ran when a
match ended does not change afterwards (D-38).

**Once per key**, as a purchase is (§8's shop): the client makes a key per tap
of Activate and sends it with every retry, and a key already used is answered as
its first attempt was, one item taken whatever the retries.

**What it does**: `worker`, applying a match's result, raises each player's
experience and coins by the boosts of theirs that were running when the match
ended: the percent added, rounded down. Never a rating, never anything inside
the match (D-38).

| | |
|---|---|
| `GET /v1/boosts` | the boosts running: `{"boosts":[{"kind":"xp","itemId","percent","endsAt"}]}` → 200, 401 |
| `POST /v1/boosts` `{"itemId","key"}` | activates one → 200 `activated` or `already_activated`, with the boosts running; 400 `invalid_body` or `invalid_key`, 401, 404 `unknown_item` (none such, or not a boost), 409 `not_owned`, 409 `other_running` (a different boost of that kind runs) |

## 9. Notifications

- **Pushes** go through the gateway ([03 §5](03-gateway.md#5-push-routing)), and
  are best-effort: a push says "look", it never carries the truth.
- **Offline players** get an inbox row in MySQL (TTL 30 days) for rewards and
  invites, which is what the client reads on reconnect.
- **Arena → platform** flows through streams, never direct calls
  ([05 §2](05-worker-and-events.md#2-streams)).
- **Platform → arena** is pub/sub: room create and close, kick a player,
  broadcast a notice, reload configuration.

### The social layer's first slice (designed 2026-09-30, plan item 21)

On [Q-20](../requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation: friends, blocking, presence and an inbox, the `social` and
`notify` modules of the tree above. Every change to friends or blocks is one
MySQL transaction locking both players, lowest id first
([D-45](../architecture/03-decision-log.md#d-45--a-friendship-is-two-rows-and-a-change-to-it-locks-both-players-lowest-id-first)).

| Route | Answer |
|---|---|
| `GET /v1/friends` | `friends` (each `playerId`, `name`, `online`), `requests` to the player (the 100 newest) and `asked` by them (each `playerId`, `name`, `expiresAt`, newest first) → 200 |
| `POST /v1/friends` `{"playerId"}` | asks; or, if they asked first, accepts → 200 `{"state": "asked" \| "friends"}`; 400 `yourself`; 404 `no_such_player`; 409 `already_friends`, `already_asked`, `friends_full` (either side at 100), `you_blocked` (unblock them first), `too_many_asked` (50 out); 429 `too_soon` (20 an hour) |
| `DELETE /v1/friends/{playerId}` | ends the friendship, both ways → 200; 404 `not_friends` |
| `DELETE /v1/friend-requests/{playerId}` | declines theirs, or withdraws one's own → 200; 404 `no_request` |
| `GET /v1/blocks` | `blocked` (each `playerId`, `name`) → 200 |
| `POST /v1/blocks` `{"playerId"}` | blocks: ends a friendship and any request either way → 200; 400 `yourself`; 404 `no_such_player`; 409 `blocks_full` |
| `DELETE /v1/blocks/{playerId}` | unblocks → 200; 404 `not_blocked` |
| `GET /v1/inbox` | `items`, newest first, each `id`, `kind` (`friend_request`, `friend_accepted`, `team_invite`, `tournament_prize`, `team_application`, `season_reward`), `ref` (the other player's, team's or tournament's id; an application's, the applicant), `at`, `read` → 200 |
| `POST /v1/inbox/read` `{"upTo"}` | marks read every item up to that id → 200 |

**A blocked player is not told.** Their friend request is kept as theirs, and
never shown to the one who blocked them (plan item 57, below); their party
invitation answers as sent and is not delivered; their team invitation likewise.
A block is not a signal to the one blocked.

**Presence** is read with the list: a friend is `online` while their lobby
connection is registered (`conn:{playerId}`, 03 §5), one store read a friend; no
push when it changes.

**Pushes**, to a player online: `evt.friend.request` `{playerId, name}` and
`evt.friend.accepted` `{playerId, name}`, and `evt.inbox` `{}` with each inbox
item, "look".

**The inbox** (`inbox`, V14): a row for what a player should learn on their
return, each a kind and a reference, never text, so the client makes every word
(FR-11): a friend request, a request accepted, a team invitation, a tournament
prize. One row a player, kind and reference, a repeat ignored; written in the
transaction of what it reports, or, for a prize, after the ledger's credit and
as idempotently. Kept 30 days, deleted by `worker`'s retention.

#### Built in this order

(a) V14 (friends, requests, blocks, the inbox); friends and blocks, presence in
the list, the pushes, and blocks honoured by party and team invitations
(`persistence`, `platform`); (b) the inbox: its writes from friends, team
invitations and tournament prizes, its API, retention (`persistence`,
`platform`, `worker`); (c) the client and a live drill.

**(a) built 2026-09-30.** V14; `FriendRepository` (both players locked, lowest
id first; a request lapsing after seven days; a request made both ways meeting
as an acceptance; the caps on either side; a blocked player's request
`IGNORED` inside and `asked` outside); `FriendService` with presence from
`LobbyPush.connected` and the two pushes; `/v1/friends`, `/v1/friend-requests`,
`/v1/blocks`; a blocked inviter's team invitation answered `OK` and not kept,
and their party invitation made but not pushed, so it lapses unseen.

**(b) built 2026-09-30.** `InboxRepository`; items written in the transaction of
what they report for a friend request, an acceptance and a team invitation, and
after the ledger's credit for a prize, whether that credit was the tick's own or
an earlier tick's, so a tick that died between the two is repaired; `evt.inbox`
with each new item; `GET /v1/inbox` and `POST /v1/inbox/read`; `worker`'s daily
retention deleting items past 30 days. A team invitation to one who has blocked
the inviter is `IGNORED` inside, like a friend request, so nothing is pushed.

**(c) done 2026-09-30**, and drilled from the release (the drill's `social`):
a request pushed and in the inbox, accepted, presence both ways, a block that
ends a friendship and swallows the next request, the inbox read.

### Blocks unseen, and limits on asking (designed 2026-10-02, plan item 57)

The second audit found three ways the social layer said more than it should, or
let one account say too much ([S-14, S-15, S-16](../defects.md#5-security-and-input)),
and building it found a fourth (defect [D-40](../defects.md#3-data-and-the-result-pipeline)).

**A blocked player's request is kept, and hidden** (S-14;
[D-56](../architecture/03-decision-log.md#d-56--a-blocked-players-friend-request-is-kept-and-hidden-from-the-one-who-blocked-them)).
Answered `asked` and not kept, it differed from a kept one three ways: missing
from the asker's `asked`, `asked` again rather than 409 `already_asked`, and 404
`no_request` to withdraw. Now it is written as any other, counted against the
asker's limits, and lapses in seven days; only the one who blocked them never
learns of it: no inbox item, no push, and their `requests` leave out anyone they
block. Unblocking drops it, unseen; nothing arrives late. Blocking drops requests
either way, as before.

**Limits on asking** (S-15), on
[Q-46](../requirements/01-scope-and-nfrs.md#7-open-questions)'s recommendation:

| | Limit | Refused |
|---|---|---|
| friend requests out, unlapsed | 50 a player | 409 `too_many_asked` |
| friend requests made | 20 an hour a player, every one counted, a request withdrawn and made again too | 429 `too_soon` |
| team invitations out, unlapsed | 20 a team | 409 `too_many_invited` |
| team invitations made | 20 an hour a player | 429 `too_soon` |
| team applications made (plan item 64) | 20 an hour a player | 429 `too_soon` |
| party invitations made | 60 an hour a player | 429 `too_soon` |
| requests to a player, as listed | the 100 newest | |
| invitations to a player, as listed | the 50 newest | |

The counts out are taken in the transaction that writes, under the players' (or
the team's) locks, so two at once cannot both slip under. The hourly counts are
fixed windows in the store, as the login throttle's (`rl:ask:{playerId}:{hour}`,
`rl:tinv:{playerId}:{hour}`, and `rl:tapp:{playerId}:{hour}` for applications),
taken before anything else is looked at and failing closed. A refusal is the asker's own: whom they asked is not told.

**Presence in one read**: the friends list asks the store about every friend in
one `MGET` of their `conn:` keys, not one read a friend.

**A party invitation tells presence only to a friend** (S-16). 404
`not_in_lobby` was answered to anyone, before the block was looked at: a reading
of presence, which Q-20 makes a friend's. Now a friend of the invitee is told it,
since they see the same in their list; anyone else is answered as sent, and the
invitation, kept for its 60 s, is pushed only if the invitee is in the lobby and
has not blocked the inviter.

**Lapsed requests and invitations are deleted** (defect D-40): `worker`'s daily
retention deletes friend requests and team invitations past their seven days, a
thousand at a time, by indexes on their expiry (V19).

## 10. Admin API

**Built** in the slices below; every call, its answers and its refusals are in the
[platform README](../../backend/platform/README.md#the-admin-api). The next two paragraphs are
the first design, kept for its reasons: the paths below are not the built ones, and the audit
went to MySQL, not a stream (D-30). The leaderboard rebuild is a unit,
`systemctl start backend-leaderboard-rebuild` (§7). As first designed:

`/api/admin/rooms`, `/api/admin/room/{id}/close`, `/api/admin/player/{id}/ban`,
`/api/admin/tournament/{id}/advance`, `/api/admin/reload-config`,
`/api/admin/rebuild-leaderboards`.

Bound to loopback, behind a shared secret, reached over SSH. Every call writes
to the `s:audit` stream with 90-day retention
([05 §2](05-worker-and-events.md#2-streams)) — admin actions are the ones most
worth being able to reconstruct later.

### The first slice (designed 2026-09-29, plan item 7)

FR-9 asks that an operator can inspect and intervene; the runbook's procedures
wait for these endpoints. The first slice is what `platform` can do alone:

| Call | What |
|---|---|
| `GET /admin/arenas` | the directory: each live arena, its players and rooms, and whether it speaks TLS |
| `POST /admin/players/{id}/ban` `{"until", "reason"}` | suspends until `until` (ISO-8601, in the future, else 400 `bad_until`), or bans for good without it; ends every session the player has, and their lobby connection with `evt.session.revoked`. If the sessions cannot be ended (the store failing), 503 `sessions_not_ended`: the ban is recorded, and calling again ends them |
| `POST /admin/players/{id}/unban` `{"reason"}` | lifts it |

**Its own listener**, `BACKEND_ADMIN_ADDR` (host:port, loopback), as the metrics
are, and only when named: a process given none takes no port. **A shared
secret**, `BACKEND_ADMIN_TOKEN_FILE`, delivered as the database password is
(`LoadCredential=`), sent as `Authorization: Bearer`, compared in constant time;
an address named without it, or one that is not loopback, refuses the whole
process's start (exit 2), and both listeners' settings, the metrics' and this
one's, are checked before the public API binds. **Every call is audited in MySQL**,
`admin_audit` (V7): when, the call, its target (cut to 128 characters), what it
asked (cut to 1 024), and what it did, refusals included, since a refused call is
one worth knowing about (not an unknown path, a wrong method, a bad `days`, nor a 500
or 503: found in writing the API reference, 2026-10-06, DOC-22). The stream
the design names needs j-redis streams (Phase 4); MySQL is here, durable, and
queryable by the same operator
([D-30](../architecture/03-decision-log.md#d-30--admin-calls-are-audited-in-mysql-until-there-are-streams)).

**Sessions are indexed by player**, `sess:of:{playerId}`, a set of tokens kept
with them, so a ban ends every one at once rather than at their next expiry;
the gateway closes the player's lobby connection on `evt.session.revoked`, as
it does on `evt.session.replaced`, and the client does not reconnect. A player
banned while in a match plays it out: closing a room and taking a player out
of one need a command channel to the arenas, the second slice, with
`/admin/rooms` and `/admin/room/{id}/close`. Reloading configuration and the
leaderboard rebuild stay what they are (a restart, and the rebuild's unit);
tournaments do not exist.

**Built 2026-09-29** (`platform/net/AdminServer`, `persistence/AdminRepository`,
V7, `SessionStore.revokeAll`, the gateway's close on `evt.session.revoked`, the
client's end on it; the runbook's [§3a](../operations/02-runbook.md#3a-the-admin-api)).
A reason is required, at most 200 characters. The index of a player's sessions
lives as long as the longest session, and a token already ended stays in it
until a ban: harmless, and not counted. Drilled live: a player in the lobby
banned over the admin API was told, stayed out, could not log in, and could
once unbanned.

### The second slice: rooms (designed 2026-09-29, plan item 7)

| Call | What |
|---|---|
| `GET /admin/rooms` | every live arena's rooms: its name, players, and a made match's id, mode and stage |
| `POST /admin/rooms/{arena}/{room}/close` `{"reason"}` | the room publishes what its players are owed and sends them back with `Kick(7)`, removed; a made match closed is cut short, so not rated (D-29) |
| `POST /admin/players/{id}/kick` `{"reason"}` | the player is taken out of any match they are in, `Kick(7)`, their stay published as a leave |

A ban now also kicks, so a banned player does not play their match out.

**Arenas are told on a channel of their own**, `arena-admin:{name}`, which each
subscribes to on the session store; `platform` publishes `{"cmd": "close",
"room"}` or `{"cmd": "kick", "player"}`. A kick goes to every live arena, since
a player is in at most one and `platform` does not know which. The calls are
answered 202, sent, with how many arenas heard: the arena acts on its room's
next tick, and what it did is in its log and the room's result. **Rooms are
seen through the directory**: each announcement carries the arena's rooms, a
field of its entry, so the list is as fresh as the three seconds between
announcements.

**`Kick(7)`, removed by an operator**, is a new reason, and an old client reads
a reason it does not know as the server's fault and goes back through the lobby,
which is as right as it can be; so the protocol's version stays 4.

#### Built in this order

(a) the arena's channel: rooms in the announcement, close, kick, `Kick(7)`; (b)
the admin calls, the ban's kick, the audit; (c) the client's `Removed`, and a
live drill.

**Built 2026-09-29** (`ArenaAnnouncer` hears the channel and announces the
rooms; `RoomRegistry.closeRoom`, `remove`; `RoomThread`'s removals and its
close's reason; `ArenaDirectory.command`, `listen`, `roomList`; the admin
calls). A player taken out ends their stay as a `Leave` does, so it is
published at once and cannot be resumed; a closed room leaves the registry at
once, so nobody is placed in it while it stops.

**Every stay, and the ticket not yet used** (designed 2026-10-02, plan item 55,
defect T-35). The kick looked only at the players connected: one whose socket
had just dropped was held for a resume, which needs its secret and no session,
and came back to play the match out; and one banned after `evt.match.found` and
before joining was let in, the kick having found them nowhere. Now a removal:

- **ends the player's stay waiting for a resume too**, as a leave, published
  and its secret forgotten;
- **for a ban, is remembered by every arena for a ticket's life, 60 s**, and a
  join with a ticket of that player in that time is refused with `Kick(7)`: no
  ticket issued before the ban outlives that minute, and the ban revoked the
  sessions that could ask for another. The ban's kick says so (`"ban": true`).
  A kick is not remembered: it takes a player out of their match, and they may
  play again at once.


### The third slice: a notice to the lobby (designed 2026-10-01, plan item 44)

On [Q-39](../requirements/01-scope-and-nfrs.md#7-open-questions)'s
recommendation: the one way to warn everyone before a stop.

| Call | What |
|---|---|
| `POST /admin/notice` `{"text", "reason"}` | every player in the lobby, on every gateway, is pushed `evt.notice` `{"text"}` → 202, with how many gateways heard; 400 `invalid_text` for none or past 200 characters |

**A channel every gateway listens on**, `push:all`, beside each one's own
`push:{gateway}`: `platform` publishes the notice once, and each gateway hands it
to every connection it holds through `Pushes` (03 §8), so a slow client's
backpressure holds for it too. The text is the operator's own, not a phrase:
D-14 is about what players say to each other. It is audited as every admin call
is, its text in the audit row.

**Built 2026-10-01** (`LobbyPush.broadcast` and `ALL_CHANNEL`; the gateway's
subscription and `ConnectionRegistry.broadcast`; `AdminServer.notice`). Tested
through the admin API and through a running gateway; mutation-checked 8 of 8.
Drilled from the release (`notice`): two players told one notice word for word.

### Whether players come back (designed 2026-09-30, plan item 25)

| Route | Answer |
|---|---|
| `GET /admin/stats?days=N` | by day, newest first, N from 1 to 60 (14 if absent), today so far included: `{"days":[{"day","active","newPlayers","d1","d7","d30"}]}`; a day-N figure is `null` until its day has ended → 200; 400 `invalid_days` |

| `GET /admin/stats/features?days=N` | the same days, each day's new players split by what they did that first day: `{"days":[{"day","newPlayers","features":{"queued":{"players","d1","d7","d30"},"bought":…,"boosted":…,"tournament":…,"friend":…,"team":…}}]}` → 200; 400 `invalid_days` |
| `GET /admin/stats/funnel?days=N` | by the day accounts were made, newest first, N from 1 to 60 (14 if absent): `{"days":[{"day","registered","guests","played","returned","level5","rated","bought","paid"}]}`, each step counted by now ([05 §11](05-worker-and-events.md#the-funnel-designed-2026-10-04-plan-item-76-c), plan item 76 (c)) → 200; 400 `invalid_days` |
| `GET /admin/stats/guests` | `{"players","guests","inactive"}`: the guests never upgraded, and those of them idle 90 days, the measure their deletion waits on ([05 §11](05-worker-and-events.md#guests-measured-designed-2026-10-04-plan-item-76-c), Q-22) → 200 |

What each figure counts, and how, is [05 §11](05-worker-and-events.md#11-analytics-designed-2026-09-30-plan-item-25)
(Q-24, Q-26, D-47). Audited, as every admin call is, reads included.

### Checked as the player API checks (designed 2026-10-02, plan item 60 (a))

[S-19](../defects.md#5-security-and-input): the admin API, behind loopback and its
token, checked less than the player API. Now:

- **Every body is read up to 4 KB**, a tournament's too; past that it is not
  read, and the call is refused as one without a reason (a notice as one without a
  text, since its text is checked first: `invalid_text`).
- **A tournament's name and a notice's text, shown to every client as they are,
  hold no control, format or line separator character** (Unicode's Cc, Cf, Zl
  and Zp: the direction marks and overrides are format characters): 400
  `invalid_tournament`, `invalid_text`.
- **A notice is audited, then sent**: a database failing at that moment sends
  nothing, and the operator is answered 503 `storage_unavailable`, rather than a
  notice going out that no row records (D-30). The row says `sent` for a notice the store then
  failed to send, which the operator's answer shows.
- **A call's method is checked as the player API's are**: a known path asked
  with another method is 405 with `Allow`; `/admin/seasons` is GET only, and
  audited as every call is, and `/admin/tournaments` is POST only.

## 11. What to measure

| Metric | Why |
|---|---|
| Matchmaking wait time p50/p95 per mode | The number players actually feel in the lobby |
| Queue depth per mode and bucket | A widening rating band with no matches means the population is too thin for that mode |
| Ticket claim rate | Unclaimed tickets mean clients are failing to reach arenas; for made matches, the no-shows a penalty would answer ([Q-38](../requirements/01-scope-and-nfrs.md#7-open-questions): none until they pass 5 % of a week's match tickets) |
| Room fill distribution | Shows how full the rooms run: as built they fill one after another, to 145 of 150 |
| Purchase failures by cause | Insufficient funds is normal; anything else is a bug |
| Request latency by module, p99 | Finds the module that will need splitting out first |

**Built** (`PlatformHttpServer.registerMetrics`): `backend_platform_responses_total{status}`,
`backend_platform_logins_total{outcome}` (ok, invalid_credentials, banned,
throttled, busy), `backend_platform_purchases_total{outcome}` (bought,
already_bought and each refusal: the purchase row above) and
`backend_platform_hasher_line` (168 is full, S-7). **And matchmaking's**
(2026-09-29, `Matchmaker`): `backend_platform_queue_wait_seconds{mode}`, a
histogram of each player's wait from queueing to a match found, whose p50 and
p95 the server works out (buckets from 1 s to 10 min); `backend_platform_queue_players{mode}`,
the players left waiting by the last round, which only the platform holding the
matcher's lease reports, the others 0; and
`backend_platform_confirms_total{outcome}`, how each confirm step ended (made,
declined, withdrawn, lapsed, no_room). The queue is not bucketed by rating, so depth is
per mode. **And request latency** (2026-09-29): `backend_platform_request_seconds{route}`,
a histogram per route, the API's path (`/v1/party`) rather than the one a
client sent, so the series are as many as the routes; 5 ms to 2.5 s. **And
ticket claims** (2026-09-29): `backend_platform_open_tickets_issued_total` and
`backend_platform_match_tickets_issued_total{mode}` here, and the arenas'
`backend_arena_joins_total{outcome}` (joined, bad_ticket, no_room,
claim_failed): tickets issued less joins with a ticket is what never arrived,
over an hour or so, since a ticket lives sixty seconds. Room fill is the
arena's `backend_arena_players` by room, already built.
