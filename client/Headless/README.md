# Headless

The client's core driven without Unity against a running stack, as a player
would, scenario by scenario: each check printed `ok` or `FAIL`, and the exit
status 0 only if every check passed
([08 §6](../../docs/detailed-design/08-client.md#6-testing)). A tool for the
development machine, not part of the app, so it may use what .NET has and
Unity does not. .NET 8, C# 9; a project reference to `Core`.

`../headless-drill.sh` starts the stack from a release and runs it; see
[../README.md](../README.md#against-the-real-server) for the drill's modes and
ports.

## Command line

```
dotnet Headless/bin/Debug/net8.0/Backend.Client.Headless.dll <platform-url> [--lobby <ws-url>] [--trust <cert>] [--wrong-trust <cert>] [scenario ...]
```

| Argument | Meaning |
|---|---|
| `<platform-url>` | The platform API, first and required; the one endpoint `ApiClient` is given |
| `--lobby <ws-url>` | The gateway's lobby, as `ws://127.0.0.1:8094/lobby` |
| `--trust <cert>` | With TLS: the one certificate to trust in place of the device's store, as a test CA would be; the host name is still checked, and nothing is pinned |
| `--wrong-trust <cert>` | A certificate that is not the arena's, for `untrusted` |
| `scenario ...` | Run in the order given; none named runs `play resume badticket lifecycle` |

Without `--lobby`, every scenario that opens a lobby connection is dropped from
the list (`Program.NeedsLobby`): `lobby replaced badsession duel walkover party
decline rffa coop banned removed tournament teammatch teamcup team social rename
apply roundrobin milestone season achievements goals pass domination tag maze
sandbox notice`. `headless-drill.sh` always passes `--lobby`.

Exit status: 0 when every check passed (`ALL PASSED`), 1 when any failed
(`N CHECK(S) FAILED`), 2 without a platform URL.

Every scenario makes accounts of its own, faster than people do and all from one
address, past the 30 attempts a minute `platform` allows an address (04 §1). A
429 `too_many_attempts` on registering, logging in or a guest is waited out by its
`Retry-After` and asked again, twice at most, so a run takes a little longer on a
fast machine rather than failing (T-56).

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `BACKEND_ADMIN_URL` | none | The operator's API (platform's admin listener), for the scenarios an operator takes part in: `purchase tournament teamcup roundrobin stats notice removed banned season`. Without it they fail |
| `BACKEND_ADMIN_TOKEN` | none | Its bearer token |
| `BACKEND_DRILL_SQL` | none | A program that runs the SQL given as its only argument on the drill's database: how the fixtures are written (coins and gems through the ledger, ten rated matches, points, experience, stays). Without it the fixtures fail, and with them the checks after |

`headless-drill.sh` sets all three: `http://127.0.0.1:9196`,
`drill-admin-secret`, and a script it writes that runs `mysql` in UTC on the
primary of the moment.

## Scenarios

In the driver's order. **Needs**: L the lobby (`--lobby`), A the operator's API,
S the drill's SQL, C the drill's own shop items (`headless-drill.sh` adds a
barrel giving 25 % bullet speed and a coins boost, for 1 coin each, when one of
these is asked for), T TLS. Times are a run's own, roughly.

| Scenario | What it checks | Needs | Time |
|---|---|---|---|
| `play` | Joins the public arena; the class table platform serves is the one the Welcome names, begins with Basic and has the Guardian; moves right at the top speed the server's rule allows; 15 frames a second; a Pong; a class it cannot have yet refused silently; its bullets arrive as predicted creates at exactly 10 units a tick, radius 8; `Leave` ends it as left | | seconds |
| `prediction` | The own tank predicted (02 §9): a walk of twelve legs that turns, stops and goes diagonally, then the same firing; nearly every frame matched to a step, no jump, the error's median within 0.5 units and p95 within 4, the drawn tank ahead of the newest sample | | about 15 s |
| `equip` | Paid for a stay, buys the drill's barrel and wears it: 25 % bullet speed, the API says, and the next stay's bullets at exactly 12.5 units a tick | C | seconds |
| `boost` | Buys and activates a coins boost, once a key; the next stay pays double | C | seconds |
| `team` | A team made, joined by invitation, handed over, left and disbanded, each change pushed to the lobby (`evt.team.update`) | L | seconds |
| `tournament` | An operator's duel tournament of four, seeded; each round's matches pushed (`evt.tournament.match`) and won by walkover; the final; the prizes in each wallet; finished | L A S | about three minutes |
| `teammatch` | Two teams of three, each a party; one led by a member refused `not_allowed` until made a vice leader; queued for `teams`, asked, accepted, played to a draw, and each team counts it | L | five minutes |
| `teamcup` | A teams' tournament of two: each team's party its roster; the final pushed to all six and won by walkover; each member paid | L A S | about a minute |
| `rename` | A player renamed, and not again within 30 days; the team showing it; the team renamed by its leader, its other member told through the gateway | L | seconds |
| `apply` | A team found by its name and applied to; its leader told and accepting; the applicant told they are in | L | seconds |
| `roundrobin` | Three players, three rounds of one match, each sitting one out; walkovers; standings 6, 3 and 0, paid by place | L A S | minutes |
| `levels` | The drill's barrel bought and raised to level 2 for 500 coins, once a key; the next level refused for want of coins | S C | seconds |
| `gems` | 25 gems as a fixture; the release's hour boost bought for 20; the next refused, 5 short | S | seconds |
| `purchase` | Gems for money, the provider simulated: an order whose key, retried, is the same order; paid, with the first purchase's bonus; a second confirm final; one declined; a refund by the operator and its debt; no order while owed; the debt cleared; the next paid without a bonus | A | seconds |
| `pass` | The season pass: 245 points as a fixture, one stay reaches the first tier, 150 coins told by `evt.rewards`; premium bought for 500 gems pays 15 at once; a second tap answered as the first | L S | seconds |
| `skin` | The skin table; 150 gems as a fixture buy the crimson skin, worn in its own slot; the own tank told it, skin 1, with its create; another player, wearing none, told none | S | seconds |
| `milestone` | 50 000 experience as a fixture; a stay writes level 6, past milestone 5, which pays 20 gems, told by `evt.rewards` | L S | seconds |
| `season` | Two players and a team at the top of their boards as fixtures; an operator ends the season; the worker places, pays them in gems with an inbox item and a push, and resets every rating | L A S | a minute or two |
| `achievements` | 49 stays as a fixture; the fiftieth reaches an achievement: 10 gems, told by `evt.rewards` with its id, and listed as reached | L S | seconds |
| `goals` | A new player with a stays goal today, a stay short as a fixture: one stay meets it and the set, its coins and 3 gems, told and in the wallet | L S | seconds |
| `social` | A friend request pushed and in the inbox; accepted by asking back; presence online and offline; a block ending a friendship and silently dropping the next request; the inbox read | L | seconds |
| `guest` | A guest made, in again by its key, paid for a stay, upgraded, in by name as the same player with the same coins, and its key refused | | seconds |
| `stats` | The operator's figures: a new guest's stay counted active and new today, day 1 empty until tomorrow has ended; two new guests who made friends counted under `friend` | A | seconds |
| `domination` | Six queue alone, three a side; one drives to the middle line and sees a neutral dominator; nobody takes one: a draw, each paid | L | five minutes |
| `tag` | Six queue alone, three a side; nobody fires, so nobody goes over: a draw, each paid | L | five minutes |
| `maze` | Eight queue alone; one maze's seed in every Welcome, and each client makes its 65 walls from it; one fires at the nearest wall and its bullets end at the wall the client made | L | four minutes |
| `sandbox` | A party of two; the leader opens a sandbox for both, and both join; level 45 and a Guardian; both leave, the room ends a minute after it empties, and nothing is paid | L | about a minute |
| `resume` | The socket dropped without a word; resumed to the same tank with a new secret, frames flowing again | | seconds |
| `coldresume` | Killed mid-match, no `Leave`; a new connection resumes the stay with the kept secret; the spent secret refused as `BadTicket`; the resumed stay untouched | | seconds |
| `badticket` | A ticket nobody issued is `Kick(1)`, `BadTicket` | | seconds |
| `lifecycle` | Backgrounded, the frames stop; back in the foreground, they start again; the stay goes on | | seconds |
| `untrusted` | An arena whose certificate this client does not trust is not joined, and not retried: `Unreachable` | T, `--wrong-trust` | seconds |
| `lobby` | The whole path: log in, the lobby authenticated as that player, a match asked for over the lobby, joined, played past the reward's minimum, left; the worker's reward in the inventory; the lobby connected throughout | L | seconds |
| `replaced` | A second sign-in replaces the first lobby connection, which does not come back | L | seconds |
| `badsession` | A token nobody issued is `invalid_session`, and not retried | L | seconds |
| `duel` | Two queue for a duel, are asked and accept, are matched by the push and by `GET /v1/queue`, play the room made for it; the three minutes end it, `Kick(6)`, the result in MySQL | L | three minutes |
| `walkover` | Matched, but only one goes: the join window ends it as a walkover, unrated and unpaid | L | about 30 s |
| `party` | Six in the lobby, three of them a party: a member's queue refused `in_party`, the leader's for a duel `party_too_big`; queued for team-vs-team, a member leaving takes it out, queued again; three more alone; all six matched, the party one side; a draw at five minutes, each paid, the party still standing | L | five minutes |
| `decline` | A match found and declined: the other told it is queued again, nobody sent to a room, the one who declined refused `queue_locked` | L | seconds |
| `rffa` | Eight queue for ranked free-for-all, are asked, accept, play one room each for themselves, four minutes; each paid and counted | L | four minutes |
| `coop` | A party of two and one alone queue for co-op, one team against the waves; they drive toward them without firing; the wipe ends it, each paid | L | under a minute |
| `banned` | An operator bans a player in the lobby: every session ends, the lobby is told and does not come back, logging in is refused until the ban is lifted | L A | seconds |
| `notice` | An operator's notice, told word for word to two players in the lobby (`evt.notice`) | L A | seconds |
| `removed` | An operator takes a player out of the public arena, then closes another's room: each ends `Removed`, `Kick(7)`; a player removed but not banned plays again | A | seconds |
| `phrase` | The phrase list platform serves is the one the Welcome names; a phrase said comes back to its speaker by their own handle and name; a second at once dropped without a kick, one two seconds on heard | | seconds |

The drills' records, dated, are in [../README.md](../README.md#drill-records).
