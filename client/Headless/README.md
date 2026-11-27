# Headless

The headless driver runs the client's core without Unity against a running backend, as a player would, one
scenario after another ([08 §6](../../docs/detailed-design/08-client.md#6-testing)). It is for developers who check
the core, or a change to the backend, against the real servers. This page covers its command line, its environment
and its scenarios.

**What it prints.** Each check prints `ok` or `FAIL`. The exit status is 0 only if every check passed.

**What it is.** A tool for the development machine, not part of the app. It is C# 7.3 on .NET Framework 4.5 alone
(D-116), with a project reference to `Core`, and System.ValueTuple 4.5.0 for its tuples.

- Visual Studio 2019 builds it, and a Windows machine runs it.
- Here the drills build it for each run with Unity 2019.4's compiler (`build_headless`, in `../unity-env.sh`), and run
  it on Unity's Mono 5.11 with `float32`, as Unity's Mono has it; the own tank's step gives the room's bits without
  it too (D-118). Outside the editor, Mono's own TLS stops at
  1.0, and nginx and an arena's own TLS speak 1.2 and 1.3, so the drills drive it in clear (D-116).
- The `maze` scenario's shot, `MazeShot.cs`, is the one file of it the core's tests compile in (`MazeShotTests`, T-66).

**How it is run.** `../headless-drill.sh` starts the backend from a release and runs this driver against it. Its
modes and ports are in [../README.md](../README.md#against-the-real-server).

## Command line

From `client/`, in a shell that sourced `unity-env.sh` (it fetches Unity's editor once into `work/unity`):

```
build_headless ../work/headless
MONO_ENV_OPTIONS=--optimize=float32 MONO_PATH=$MONO_LIB "$MONO" ../work/headless/Backend.Client.Headless.exe <platform-url> [--account <url>] [--lobby <ws-url>] [--trust <cert>] [--wrong-trust <cert>] [scenario ...]
```

| Argument | Meaning |
|---|---|
| `<platform-url>` | The platform API. It comes first and is required. It is the one endpoint that `ApiClient` is given |
| `--account <url>` | The account service (D-117), for example `http://127.0.0.1:8097`: every player the driver makes is an account signed up there, its access token exchanged for a session. Without it, every player is a guest |
| `--lobby <ws-url>` | The gateway's lobby, for example `ws://127.0.0.1:8094/lobby` |
| `--trust <cert>` | The one root certificate to trust in place of the device's store, for the API, the lobby and matches, as the app trusts the backend's own root (`TrustRoot`, D-82): a test CA's, or `ca.pem` from `private-ca.sh`. The name is still checked (the address dialled, or a grant's `tlsName`), and nothing is pinned. The drills give none: they run in clear (D-116) |
| `--wrong-trust <cert>` | A root that is not the arena's, for the `untrusted` scenario, which the drills cannot run (D-116) |
| `scenario ...` | The scenarios to run, in the order given. With none named, it runs `play resume badticket lifecycle`. An unknown name is a failed check |

**Without `--lobby`**, the driver drops from its list the scenarios that need the lobby, as `Program.NeedsLobby`
names them, and says which (`--- skipped, no --lobby: …`): `lobby replaced badsession duel walkover party decline rffa
coop banned tournament teammatch teamcup team social rename apply roundrobin milestone season achievements goals pass
domination tag maze sandbox notice erase account`. `removed` needs only the admin API (T-69). **Without `--account`**,
it drops those that sign in at the account service, as `Program.NeedsAccount` names them, the same way (`--- skipped, no
--account: …`): `account erase guest banned`. `headless-drill.sh` always passes both.

**Exit status:**

| Status | When | Last line |
|---|---|---|
| 0 | every check passed | `ALL PASSED` |
| 1 | any check failed, or a scenario threw | `N CHECK(S) FAILED` |
| 2 | no platform URL was given | the usage line |

**Login limits.** Every scenario makes accounts of its own, faster than people do and all from one address. That
goes past the 30 attempts a minute that the account service allows one address (10 §3.3), and `platform` for making
guests and their logins (04 §1). So when signing up, logging in or making a guest is answered 429
`too_many_attempts`, the driver waits for its `Retry-After` and asks again, at most twice. A run then takes a little
longer on a fast machine, instead of failing (T-56).

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_ADMIN_URL` | none | The operator's API (platform's admin listener), for the scenarios an operator takes part in: `purchase tournament teamcup roundrobin stats notice removed banned season`. Without it they fail |
| `BACKEND_ADMIN_TOKEN` | none | The operator API's bearer token |
| `BACKEND_DRILL_SQL` | none | A program that runs the SQL given as its only argument on the drill's database. The driver writes the fixtures with it: coins and gems through the ledger, ten rated matches, points, experience, stays. Without it the fixtures fail, and so do the checks after them |

`headless-drill.sh` sets all three: `http://127.0.0.1:9196`, `drill-admin-secret`, and a script it writes, which runs
`mysql` in UTC on whichever server is the primary at the time.

## Scenarios

The scenarios, in the driver's order. The **Needs** column uses these letters:

| Letter | Needs |
|---|---|
| L | the lobby (`--lobby`) |
| A | the operator's API (`BACKEND_ADMIN_URL`, `BACKEND_ADMIN_TOKEN`) |
| S | the drill's SQL (`BACKEND_DRILL_SQL`) |
| C | the drill's own shop items: when one of these scenarios is asked for, `headless-drill.sh` adds a barrel giving 25 % bullet speed and a coins boost to the item table, and their offers, 1 coin each, by the admin API (D-119); it removes the offers when it ends |
| T | TLS |
| U | an account at the account service (`--account`) |

The times are a run's own, roughly.

| Scenario | What it checks | Needs | Time |
|---|---|---|---|
| `play` | Joins the public arena. The class table that platform serves is the one the Welcome names, begins with Basic and has the Guardian. Moves right at the top speed that the server's rule allows; 15 frames a second; a Pong. A class it cannot have yet is refused silently. Its bullets arrive as predicted creates at exactly 10 units a tick, radius 8. `Leave` ends it as left | | seconds |
| `prediction` | The own tank predicted (02 §9): a walk of twelve legs that turns, stops and goes diagonally, then the same while firing. Nearly every frame is matched to a step, with no jump; the error's median is within 0.5 units and its 95th percentile within 4; the drawn tank is ahead of the newest sample | | about 15 s |
| `equip` | Paid for a stay, buys the drill's barrel and wears it: the API says 25 % bullet speed, and the next stay's bullets move at exactly 12.5 units a tick | C | seconds |
| `boost` | Buys and activates a coins boost, once for each key; the next stay pays double | C | seconds |
| `team` | A team made, joined by invitation, handed over, left and disbanded; each change pushed to the lobby (`evt.team.update`) | L | seconds |
| `tournament` | An operator's duel tournament of four, seeded. Each round's matches are pushed (`evt.tournament.match`) and won by walkover; then the final; the prizes in each wallet; finished | L A S | about three minutes |
| `teammatch` | Two teams of three, each a party. A party led by a member is refused `not_allowed` until that member is made a vice leader. Queued for `teams`, asked, accepted, played to a draw, and each team counts it | L | five minutes |
| `teamcup` | A teams' tournament of two: each team's party is its roster. The final is pushed to all six and won by walkover; each member is paid | L A S | about a minute |
| `rename` | A player renamed, and refused a second time within 30 days; the team shows the new name; the team renamed by its leader, and its other member told through the gateway | L | seconds |
| `apply` | A team found by its name and applied to; its leader told, and accepting; the applicant told they are in | L | seconds |
| `roundrobin` | Three players, three rounds of one match, each player sitting one out; walkovers; standings 6, 3 and 0, paid by place | L A S | minutes |
| `levels` | The drill's barrel bought and raised to level 2 for 500 coins, once for each key; the next level refused for want of coins | S C | seconds |
| `gems` | 25 gems as a fixture; the release's hour boost bought for 20; the next refused, 5 gems short | S | seconds |
| `purchase` | Gems for money, with the payment provider simulated: an order whose key, sent again, is the same order; paid, with the first purchase's bonus; a second confirm final; one declined; a refund by the operator and its debt; no order while gems are owed; the debt cleared; the next paid without a bonus | A | seconds |
| `pass` | The season pass: 245 points as a fixture; one stay reaches the first tier, and 150 coins are told by `evt.rewards`. Premium, bought for 500 gems, pays 15 at once; a second tap is answered as the first | L S | seconds |
| `skin` | The skin table; 150 gems as a fixture buy the crimson skin, and a second purchase of it is refused, `already_owned` (D-53); it is worn in its own slot. The own tank is told it (skin 1) with its create; another player, wearing none, is told none | S | seconds |
| `stock` | Limited stock (D-121): an offer of one blue skin made by the operator's API, listed limited with one left; one player buys it, a second is refused `sold_out`, and the offer stays listed with none left. The first player's purchases, read with `ApiClient.Purchases`, have the skin newest; the second's are none. The offer removed | A, S | seconds |
| `milestone` | 50 000 experience as a fixture; a stay writes level 6, past milestone 5, which pays 20 gems, told by `evt.rewards` | L S | seconds |
| `season` | Two players and a team at the top of their boards, as fixtures. An operator ends the season; the worker places them, pays them in gems with an inbox item and a push, and resets every rating | L A S | a minute or two |
| `achievements` | 49 stays as a fixture; the fiftieth reaches an achievement: 10 gems, told by `evt.rewards` with its id, and listed as reached | L S | seconds |
| `goals` | A new player with a stays goal today, one stay short as a fixture: one stay meets it and the set; its coins and 3 gems, told and in the wallet | L S | seconds |
| `social` | A friend request pushed and in the inbox; accepted by asking back; presence online and offline; a block ending a friendship and silently dropping the next request; the inbox read | L | seconds |
| `guest` | Through the app's `AccountKeeper` (10 §5.1): a guest made on the first launch, in again by its key, paid for a stay; signed up, the guest linked, the same player; the refresh token kept and the key forgotten; a second launch in by the refresh token as that player, with its coins; the key refused; another device in by the name | U | seconds |
| `stats` | The operator's figures: a new guest's stay counted as active and new today; day 1 empty until tomorrow has ended; two new guests who made friends counted under `friend` | A | seconds |
| `domination` | Six queue alone, three a side. One drives to the middle line and sees a neutral dominator. Nobody takes one: a draw, each paid | L | five minutes |
| `tag` | Six queue alone, three a side. Nobody fires, so nobody goes over to the other side: a draw, each paid | L | five minutes |
| `maze` | Eight queue alone. One maze's seed is in every Welcome, and each client makes its 65 walls from it. The first of the eight with a wall in reach (100 to 700 units, its way clear) fires at it, its trigger held and aimed again whenever its tank is placed anew, as the match's start places every tank (`MazeShot.cs`, T-66); its bullets end at the wall the client made | L | four minutes |
| `sandbox` | A party of two. The leader opens a sandbox for both, and both join; level 45 and a Guardian. Both leave; the room ends a minute after it empties, and nothing is paid | L | about a minute |
| `resume` | The socket dropped without a word; resumed to the same tank with a new secret, and frames flowing again | | seconds |
| `coldresume` | Killed mid-match, no `Leave`. A new connection resumes the stay with the kept secret; the spent secret is refused as `BadTicket`; the resumed stay is untouched | | seconds |
| `badticket` | A ticket nobody issued gets `Kick(1)`, `BadTicket` | | seconds |
| `lifecycle` | In the background, the frames stop; back in the foreground, they start again; the stay goes on | | seconds |
| `untrusted` | An arena whose certificate this client does not trust is not joined, and not tried again: `Unreachable` | T, `--wrong-trust` | seconds |
| `lobby` | The whole path: log in; the lobby authenticated as that player; a match asked for over the lobby, joined, played past the reward's minimum, and left; the worker's reward in the inventory; the lobby connected throughout | L | seconds |
| `replaced` | A second sign-in replaces the first lobby connection, which does not come back | L | seconds |
| `badsession` | A token nobody issued gets `invalid_session`, and is not tried again | L | seconds |
| `duel` | Two queue for a duel, are asked and accept, are matched by the push and by `GET /v1/queue`, and play in the room made for it. The three minutes end it: `Kick(6)`, and the result in MySQL | L | three minutes |
| `walkover` | Matched, but only one goes: the join window ends it as a walkover, unrated and unpaid | L | about 30 s |
| `party` | Six in the lobby, three of them a party. A member's queue join is refused `in_party`, and the leader's for a duel `party_too_big`. Queued for team-vs-team; a member leaving takes the party out; queued again; three more alone. All six matched, the party on one side; a draw at five minutes, each paid, the party still standing | L | five minutes |
| `decline` | A match found and declined: the other player told it is queued again, nobody sent to a room, and the one who declined refused `queue_locked` | L | seconds |
| `rffa` | Eight queue for ranked free-for-all, are asked, accept, and play one room, each for themselves, for four minutes; each paid and counted | L | four minutes |
| `coop` | A party of two and one alone queue for co-op: one team against the waves. They drive toward the waves without firing; the wipe ends it, each paid | L | under a minute |
| `banned` | An operator bans a player who is in the lobby: every session ends, the lobby is told and does not come back, and logging in is refused, a fresh access token exchanged answered 403 `banned`, until the ban is lifted | L A U | seconds |
| `notice` | An operator's notice, told word for word to two players in the lobby (`evt.notice`) | L A | seconds |
| `erase` | A player deletes their own account (D-84, D-117): refused at the backend, `delete_at_account_service`; at the account service a wrong password refused, then deleted, and its event erases the player: the other device's lobby told why, every session ended, the name's login refused, its access token still in its minutes making no new player, gone from a friend's list, the name signed up again a new player; a guest deleted by its session alone, its key logging in no more | L U | seconds |
| `account` | The account service and the backend (D-117): the exchange, `newPlayer` the first time alone; a refresh, and the spent token again within its 30 s, taken; a logout, after which the family refreshes no more; a password changed from a third device: the sessions made before it ended, the lobby on one told `signed_out` and closed, the one made after kept and a lobby on it left open (T-71); the old password refused, the new taken; a link refused `account_in_use` and `not_a_guest` | L U | seconds |
| `removed` | An operator takes a player out of the public arena, then closes another player's room: each ends `Removed`, `Kick(7)`; a player removed but not banned plays again | A | seconds |
| `phrase` | The phrase list that platform serves is the one the Welcome names; a phrase said comes back to its speaker by their own handle and name; a second one at once is dropped without a kick, and one two seconds later is heard | | seconds |

`untrusted` needs a match over TLS and `--wrong-trust`. It can no longer be driven here: the driver runs on Unity's
Mono, whose own TLS stops at 1.0, so `headless-drill.sh` refuses it, with `TLS=1` and `FRONT=https` (D-116). The
driver keeps it, for a runtime that speaks TLS 1.2.

The drills' dated records are in [../README.md](../README.md#drill-records).
