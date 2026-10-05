# Decision log

Every significant choice, why it was made, and what it costs. A decision here
is **accepted** unless marked otherwise; when one is replaced, the old entry
stays and says what replaced it.

Decisions about the data store itself live in
[j-redis-service/docs/14-decision-log.md](../../j-redis-service/docs/14-decision-log.md).

**D-1 to D-13 accepted 2026-09-22/23**, during the revision from "one machine,
browser client, MongoDB" to the current target.

**Format.** From D-14 each entry has **Decided** (when, and what for),
**Decision**, **Why** and **Cost**. The earliest are kept as written: D-5 and
D-12 record a **Consequence** in place of a cost, and D-7 neither. D-16 stands
after D-11, which it supersedes, and not in its number's place. A later
decision that changes an earlier one is recorded on the earlier entry as
**Superseded**, **Superseded in part** or **Revisited**, with a link.

---

### D-1 — Unity native mobile client, no browser build

**Decision.** The client is a Unity app for iOS and Android. There is no WebGL
build, now or planned.

**Why.** It removes the browser's restrictions from the protocol: no WebSocket
framing, no HTTP upgrade, and raw TCP or UDP are both available. WebGL has no
raw sockets at all, so keeping that option would have permanently fixed the
transport as WebSocket.

**Cost.** If a browser build is ever wanted, the transport decision reopens and
UDP is off the table for that client.

### D-2 — Java 21 with generational ZGC

**Decision.** All services target Java 21 and run with
`-XX:+UseZGC -XX:+ZGenerational`.

**Why.** A 40 ms tick budget cannot absorb a G1 pause. ZGC's pauses are
sub-millisecond regardless of heap size, which removes GC as a design driver.
The machines are RHEL 9, which ships OpenJDK 17 and 21 but **not** Java 8, so
the old constraint was also becoming an operational problem. Java 8 additionally
pinned every library to its final release with no security fixes.

**Cost, measured.** ZGC disables compressed oops, so every reference in a stored
object grows from 4 bytes to 8. In j-redis this made the same dataset estimate
25 % more per key, 40 % per hash field, 50 % per set member. Roughly 10 % of
throughput also goes to ZGC's barriers. Both are good trades for the pause
guarantee. Details in
[j-redis D-31](../../j-redis-service/docs/14-decision-log.md).

### D-3 — MySQL is the system of record; MongoDB is cancelled

**Decision.** MySQL 8.0 holds accounts, progression, inventory, economy,
teams, tournaments and the ledger.

**Why.** Every hard problem in the platform services is transactional: a purchase must
debit currency and grant an item atomically; a reward must apply exactly once.
With documents that becomes careful conditional-update choreography. With
`BEGIN … COMMIT` it becomes ordinary code. Idempotency keys as unique
constraints also fall out for free.

**Cost.** The persistence design was rewritten from scratch
([detailed-design/06](../detailed-design/06-persistence-mysql.md), 2026-09-23), and
schema migrations became a build artefact (Flyway).

### D-4 — Split by workload shape, not by business domain

**Decision.** Four process types — `arena`, `gateway`, `platform`, `worker` — each
run as N identical instances. Not one service per business domain.

**Why.** Microservices buy independent deployment, independent scaling and team
boundaries. With one developer and three machines, the first is marginal, the
second is served by running more instances of four types, and the third does
not exist. The costs — service discovery, network hops, distributed
transactions, distributed tracing — are permanent and paid daily.

A JVM already uses every core it is given, so decomposition is not how
multi-core utilisation is achieved; threads and multiple processes are. The four
types differ in the things that actually justify a process boundary: CPU versus
IO versus database-bound, and crash isolation.

**Cost.** `platform` is a modular monolith, so its module boundaries must be kept
strict by discipline rather than enforced by the network. If a module ever needs
its own scaling profile, splitting it out is the escape hatch.

### D-5 — Match traffic never passes through nginx or the gateway

**Decision.** The matchmaker hands the client a specific arena address and the
client connects to it directly, with TLS terminated in Netty. nginx handles
HTTPS, the lobby and the API only.

**Why.** At full occupancy match traffic is ~1.1 Gbit/s. Proxying it would double
internal network load and add a hop to the one path that cannot afford latency.
A proxy also cannot make the routing decision correctly: which arena a player
belongs to depends on room occupancy and match state, which only the allocator
knows.

**Consequence.** The **allocator is the load balancer** for match traffic. nginx is
not, and no amount of nginx configuration would make it one.

### D-6 — No custom message broker; use j-redis streams

**Decision.** Domain events, work queues, telemetry and audit go through
Redis-style streams in a dedicated j-redis instance. No Kafka, no RabbitMQ, and
no broker written from scratch.

**Why.** The requirement is Kafka's *semantics* — durability, consumer groups,
replay, dead-lettering — at none of Kafka's scale. At 50 000 players the event
rate is ~170 match-end events/s and a few thousand kill events/s; j-redis
already does 100 000+ operations/s. A dedicated log service would idle at a
fraction of a percent utilisation while costing months to make correct.

A log model was chosen over a queue model: consumer state is one offset per
group per partition rather than per-message acknowledgement state, replay comes
free, and per-key ordering (partition by player id) is what the domain actually
needs.

**Cost.** Streams do not exist in j-redis yet ([plan.md](../plan.md), 2.1), and
retention is bounded by RAM. If raw telemetry volume ever outgrows memory, a
disk-backed log service becomes justified — but that should be triggered by a
measurement, not an assumption.

**Explicitly not on the broker:** arena↔client traffic and platform→arena room
commands. Those need low latency and tolerate loss; they stay on direct TCP and
pub/sub.

**Revisited 2026-09-29 and 2026-10-01.** Streams exist since j-redis 2.1
(2026-09-29), and the result queue is one
([D-33](#d-33--the-result-queue-is-a-stream-read-by-one-group-the-list-stays-an-inbox)).
The audit is not on a stream: it stays in MySQL
([D-50](#d-50--the-admin-audit-stays-in-mysql-streams-or-not)).

### D-7 — Two j-redis instances, not one

**Decision.** Run the same binary twice: `session` (sessions, tickets,
presence, matchmaking queues, leaderboards) and `events` (durable streams),
with different ports and data directories.

**Why.** Zero extra code, but two separate failure domains and two separate
heaps. A backlog of unconsumed events can then never evict a session, and the
two can be promoted, restarted and sized independently. Their availability
tiers genuinely differ: losing `session` stops logins immediately, while losing
`events` is invisible for tens of minutes.

**Built 2026-09-26, and "zero extra code" was not quite true:** every process
opened one store. The arena and worker now open the events instance when
`BACKEND_EVENTS_STORE` names it, and put the result queue there; sessions,
tickets, presence and leaderboards stay on `session`. Unset, both clients are
one, so a single-instance deployment is unchanged. Verified with two live
stores: with the worker stopped, 20 results queued in `events` and nowhere
else; started, it drained them, and the leaderboards landed in `session`.

### D-8 — Failover is scripted, not automatic

**Decision.** Promotion of MySQL and the j-redis primaries is a documented,
scripted, human-triggered procedure. No automatic leader election for stateful
roles.

**Why.** Automatic failover requires consensus. A correct Raft implementation
is a bigger project than the rest of this system, and an incorrect one loses
data in exactly the situation it was built for. With manual promotion the
operator is the arbiter, which also removes split brain as a failure mode.

**What makes it safe.** Fencing tokens on locks, idempotency keys on every
write, and replication epochs, so a demoted primary cannot corrupt anything and
a partly-applied batch can be replayed. With those, automating promotion later
is a scheduling problem, not a correctness one.

**Cost.** Minutes of downtime on a stateful failure instead of seconds, and
somebody has to be reachable.

### D-9 — Deterministic entities are simulated by the client

**Decision.** Bullets and shapes are sent as **create and destroy events only**.
The client extrapolates them between those events. Tanks continue to be
streamed.

**Why.** This is the single largest bandwidth decision in the system. A client
sees roughly 12 tanks, 45 bullets and 45 shapes: tanks are 12 % of the entities
and were getting the same treatment as the other 88 %, which are deterministic
between events. A bullet that cost ~9 bytes × 15 ticks over its life now costs
about 10 bytes total — roughly **80 % of snapshot volume removed**, and the same
proportion of server encoding cost.

**Cost.** Entity classes that are *not* predictable (steering drones) need a
flag and keep receiving updates. Long-lived predictable entities need a
periodic correction so divergence cannot accumulate. A destroyed bullet can pop
back a few pixels on a bad connection, which is the same class of artefact as
interpolation delay.

**Verified, numerically (2026-09-23).** Simulated over a full bullet life at
three speeds and every firing angle: with a `u16` heading plus a `u8` speed
index, extrapolation drifts **0.3 px**. The error that matters is not
quantisation but **late destroy** — a bullet flies 8 px past its death at
100 ms RTT and 17 px at 200 ms, one to two orders of magnitude more. The
decision stands, and the mitigation is visual rather than numerical
([02-networking §6](../detailed-design/02-networking.md#6-client-side-simulation)).
The first draft of the protocol encoded velocity as `i8` per axis, which drifts
33 px — the spike paid for itself before any client code existed. The
perceptual half still needs a human in Unity.

### D-10 — Tanks drive the snapshot rate, at 15 Hz

**Decision.** Simulate at 25 Hz, send at 15 Hz, and let the tank updates
determine the rate. Bullet and shape events ride in the same packet.

**Why.** After D-9, tanks are the only thing still streamed, so they set the
rate. 10 Hz was considered and rejected: other players would interpolate
150–250 ms behind, which is felt when leading a shot. Since tanks are only 12 %
of visible entities, raising just them from 10 Hz to 15 Hz is nearly free.

**Cost.** Budgeted at 2.85 KB/s down per player ([02 §4](../detailed-design/02-networking.md#4-the-snapshot)); measured 2.24 KB/s on the wire at design density, about 11 MB/hour with input (2026-09-26, [plan](../plan.md#1-status)) — within NFR-2's 15.

### D-11 — Ack-based delta baseline, so the transport stays swappable

**Superseded by [D-16](#d-16--deltas-are-measured-against-the-last-frame-sent) on
2026-09-25.** Kept because the reasoning is still the reasoning a UDP move would
have to answer.

**Decision.** The client acknowledges a snapshot tick, and deltas are computed
against the last *acknowledged* snapshot rather than the last one written.

**Why.** Shipping on TCP is right for now — a custom reliability layer is a
classic multi-month sinkhole and should be driven by telemetry, not
speculation. But on cellular, 1–3 % packet loss turns TCP head-of-line blocking
into visible 200–600 ms freezes, so UDP may well be needed later. Deltas
against "the last snapshot I wrote" only work because TCP guarantees delivery;
that assumption is the entire difference between swapping the transport and
redesigning the snapshot format.

**Cost.** Four bytes upstream and one integer of per-client state, paid now
against a change that may never come.

### D-16 — Deltas are measured against the last frame sent

**Decided 2026-09-25.**

**Decision.** A position update carries the change since the last snapshot the
server **sent**, in **world space**, and the client accumulates it. A create
still carries a position relative to the view origin, so that it fits an `i16`
on any map size. Acknowledgement keeps one job and loses the other: a handle is
not reused until its removal is confirmed ([§5](../detailed-design/02-networking.md#5-entity-handles)),
but it plays no part in the delta baseline.

**Why.** [D-11](#d-11--ack-based-delta-baseline-so-the-transport-stays-swappable)
was implemented as half a design and the half that was missing made it
undecodable. The server computed deltas from the acknowledged frame while
sending **no indication of which frame that was**, and the client — reasonably —
accumulated onto its current value. The two agree only while exactly one
snapshot is in flight; at 15 Hz with any real latency there are two, so every
entity sat a snapshot's worth of travel away from where the server had it, and
the error grew for as long as an acknowledgement was outstanding. Nothing caught
it for months because nothing decoded what the encoder produced.

Ack-based deltas can be made to work — Quake III does it — but they need the
baseline tick on the wire and the baseline state kept per entity on the client.
That is real complexity, in the one component that does not exist yet and is
already the project's bottleneck, bought against a transport change that is not
scheduled. Match traffic is TCP ([D-5](#d-5--match-traffic-never-passes-through-nginx-or-the-gateway)):
every frame arrives exactly once and in order, so "last sent" and "last
received" are the same sequence.

World space rather than origin-relative for the delta, because an entity whose
*relative* position is unchanged has still moved if the camera moved — comparing
relative positions dropped that difference silently and permanently. It is also
cheaper: scenery that sits still now sends nothing at all, however far the
player travels past it.

**Cost.** A UDP transport would have to reinstate D-11 *properly* — baseline
tick on the wire, baseline state per entity — which is a bigger change than this
one was. Recorded here so that decision is made with its eyes open rather than
discovered.

**Also settled, since the same blind spot hid them.** The client's own tank is
included in its own snapshot at a guaranteed handle
([`Wire.SELF_HANDLE`](../../backend/protocol/src/main/java/com/backend/protocol/Wire.java));
it used to be excluded while the welcome promised "you are handle 1", which
pointed at the nearest opponent. Bullets are never knocked back, because a
predicted entity that can be shoved sideways is not predictable — measured, 31
of 299 bullets had their velocity changed in flight by a collision they
survived. And static entities do receive position updates after all: they are
knocked by bullets, and they cost nothing while they sit still.

### D-12 — Embedded libraries yes, installed daemons no

**Decision.** Any Java library that is a jar on the classpath is fair to use.
MySQL and nginx are the only daemons installed on the machines.

**Why.** The constraint was never "write everything yourself" — it was to avoid
operating a fleet of third-party services. A jar shares the process lifecycle
of the service that uses it; a daemon is another thing to install, monitor,
patch, back up and fail over.

**Consequence.** Cluster membership and leader election come from JGroups or
Apache Ratis rather than Consul or etcd. Connection pooling, caching, metrics
and migrations come from HikariCP, Caffeine, Micrometer and Flyway rather than
hand-written code. The list of things actually worth writing shrinks to what is
product-specific.

**Amended 2026-09-26.** Metrics are written by hand (`common/Metrics`,
[architecture/01 §6](01-system-topology.md#6-technology-choices)), not with
Micrometer: the Prometheus text format is a few lines. Caffeine, JGroups and
Ratis are in the offline bundle and not used yet.

### D-13 — The client holds an endpoint list; no VIP

**Decision.** The client ships with all three edge endpoints and retries the
next one on failure. No virtual IP, no keepalived, no VRRP.

**Why.** Simpler than VRRP, with nothing extra to install (D-12), and it fits
mobile: the client is already reconnecting constantly across wifi and cellular
handovers, so "try the next endpoint" is a path that is exercised continuously
rather than only during an incident.

**Cost.** Adding or moving an edge endpoint requires a client release, so the
list should be generous and stable. A server-provided endpoint list fetched at
login can supersede this later without changing the model.

### D-14 — Communication is a fixed phrase list, never free text

**Decided 2026-09-23**, with the scope of the product ([plan](../plan.md)).

**Decision.** Players communicate by picking from a predefined, versioned list
of phrases. The wire carries a phrase id, not a string. There is no free-text
chat anywhere — not in matches, teams, or tournaments.

**Why.** Free text would drag in an entire subsystem that has nothing to do
with the product: profanity filtering per language, a reporting and moderation
queue, human moderators, appeals, retention and disclosure obligations, and
abuse vectors aimed at minors. For one developer that is not a feature, it is a
second job.

A phrase list also suits the medium. A mobile player mid-match cannot type, and
a two-tap canned phrase is faster than a keyboard. Localisation becomes a
translation table rather than a runtime problem, because the set of possible
messages is finite and known at build time.

**Cost.** Expressiveness. Players will want to say things the list does not
cover, and the list becomes content that needs curating and extending. That is
a far smaller and more controllable cost than moderation.

**Consequences.**
- Chat costs ~2 bytes on the wire (a varint phrase id), so it is free within
  the NFR-2 budget and can ride in the existing snapshot packet.
- The phrase list is content, loaded from a table like tanks and items, and
  versioned so a client with an older list cannot mis-render a newer id.
- Rate limiting still applies: canned phrases can still be spammed.

### D-15 — The public arena runs continuously; structured modes are timed matches

**Decided 2026-09-23.**

**Decision.** There are two match lifecycles, not one.

- **Open match** — the public arena (FFA, tag, domination): the room never
  stops. Players join, fight, die, respawn and leave; the world is not reset
  underneath them. A player's result covers *their own stay in the room* and is
  emitted when they leave, not on a clock.
- **Timed match** — the structured modes (duel, ranked FFA, team-vs-team,
  co-op, tournament): a fixed roster, a start, a time limit or a win condition,
  and one result for everybody when it ends.

**Why.** They are different products sharing an engine. The public arena's
appeal is uninterrupted play and a tank you grow — resetting everyone every few
minutes throws that away for a scoreboard nobody asked for. Ranked play needs
the opposite: a defined field, a defined end, and a result that can move a
rating, which is impossible if players can leave and rejoin the same contest.

**Cost.** Two lifecycles in `RoomThread`, and two shapes of result. An open
match has no placement and no winner, so `match_player.placement` and `won` are
meaningless for it — they are set to 0 and false rather than invented.

**Not called a "session".** That word is already the authenticated lobby
session (`sess:{token}`), and one word for two unrelated lifetimes is how a
reader comes to believe that losing one ends the other. See the
[glossary](../glossary.md).

**Consequences.**
- `MatchMode` decides the lifecycle, alongside map size and rules.
- **The current implementation is the timed one, applied to FFA** — which under
  this decision was the wrong lifecycle for the only mode that exists.
  **Resolved 2026-09-23:** the public arena now runs open matches, and the timed
  path remains built and tested for the modes that will need it.
- An open room checkpoints each stay every ten minutes, so a player who never
  disconnects cleanly is still paid (built: `MatchRules.DEFAULT_CHECKPOINT_TICKS`).
- **Every recorded row says which lifecycle produced it** (`matches.kind`,
  V2). Both land in the same table, and without the column the only way to tell
  an open match from a timed one is to guess from the placement — a heuristic
  over data that can simply state the fact. Added while only one kind is being written,
  because a row can be labelled for free when it is created and only guessed at
  afterwards.

**Superseded in part, 2026-10-01:** tag and domination were built as queued,
timed modes, not in the public arena (Q-29, Q-30; `MatchMode.TAG` and
`DOMINATION` are timed). The public arena runs FFA only; every other mode is a
timed match.

### D-17 — A client is stepped down on queueing, not on round-trip time or writability

**Decided 2026-09-26, while building traffic profiles
([02 §8](../detailed-design/02-networking.md#8-traffic-profiles)).** The design
said: step a client down when its socket is unwritable for two ticks, or when
its round trip exceeds 300 ms. **Decision.** Step down on *queueing*: how long
the oldest unacknowledged snapshot has been out, above the least that
connection's link has ever needed. Writability stays, as a backstop.

**Why.** Both designed signals answer the wrong question. Writability arrives
late: Netty reports it only after the kernel's send buffer and 128 KiB of its
own have filled, and at three kilobytes a second that is tens of seconds of
snapshots already queued ahead of the player. The round trip measures
distance: a 600 ms path with room to spare would be sent less and gain nothing,
since fewer bytes do not shorten a path. What fewer bytes do cure is a queue,
the stream arriving faster than the link carries it, and acknowledgements
measure exactly that, end to end, including the buffers of a mobile network
that no server socket can see. Drilled with shaped links: behind 900 bytes a
second the old server let each player's view fall 20 s behind within a minute
without noticing; this keeps it under one second.

**Cost.** The client must acknowledge at a steady rate whenever it is alive and
in the foreground, idle or not
([02 §9](../detailed-design/02-networking.md#9-input-prediction-and-reconciliation)).
A client that stops is indistinguishable from a link that stopped, and is sent
less. That is now part of the contract the Unity client has to honour.

### D-18 — A lost connection waits in the arena's memory, not the store

**Decided 2026-09-26, while building resume
([02 §10](../detailed-design/02-networking.md#10-session-reconnect-and-app-lifecycle)).**
The design wrote `resume:{playerId}` into j-redis, and kept a stay 15 s.
**Decision.** The waiting stay and its secret stay in the room that holds the
tank, for a minute; a resume dials that arena directly, and every `Welcome`
carries a new secret. (Defect D-18 in the register is another thing: its ids
share the letter.)

**Why.** The tank lives in one arena's memory, so a resume has to reach that
arena anyway, and an arena that dies takes the tank with it; the store would
add a failure mode and no reach. A minute, not 15 s, because an ordinary lift
ride is longer than 15.

**Cost.** No cold resume: an app killed and restarted has lost its secret and
comes back through the lobby, which ends the waiting stay. A player who loses
their connection holds a place in the room for up to a minute, which the room's
capacity counts.

**Revisited 2026-10-01**
([D-51](#d-51--a-cold-resume-is-the-devices-the-app-keeps-its-stays-secret)):
the stay still waits in the arena's memory, and an app killed and restarted now
resumes it with the secret it kept on the device. "No cold resume" no longer
holds.

### D-19 — The client is an engine-free core and a thin Unity layer

**Decided 2026-09-27**, with who writes the client: Claude
([plan](../plan.md), item 3).

**Decision.** Everything that speaks to the server — the wire, the world, the
match connection, the lobby, the API, prediction — is a .NET Standard 2.1
library with no Unity in it. The Unity layer draws, reads touches, reports the
app's lifecycle and stores the token, and nothing else
([detailed-design/08](../detailed-design/08-client.md)).

**Why.** The costliest bug here is client and server disagreeing about a byte,
and the only defence is to run the client's own code against the real server.
The development machine has the server and a .NET SDK, and no Unity. An
engine-free core runs there, against the golden vectors and against the whole
stack, headless; what cannot run there is kept small enough to check by
reading.

**Cost.** A boundary to keep: nothing Unity may leak into the core, and the
Unity layer is verified only in the editor and on devices, later than the rest.


### D-20 — A match's room is made by its first ticket, not by a command

**Decided 2026-09-27**, designing matchmaking's first slice
([04 §4](../detailed-design/04-platform-services.md#4-matchmaking)).

**Decision.** When the matcher makes a match, it issues each player a ticket
that names the match (`matchUid`) and its mode, all for one arena. The arena
makes the match's room when the first of those tickets is claimed, and the
others find it by the `matchUid`. `platform` sends the arena nothing else.

**Why.** The sketch in 04 §3 had `platform` command the arena over pub/sub and
wait for the room's registry entry to appear. That is a second channel into the
arena, a subscriber on every arena, a wait on the matcher's thread, and a room
that exists before anyone has arrived, to be cleaned up when nobody does. The
ticket already travels from `platform` to the arena and is already
single-use and short-lived; carrying the match in it costs two fields. A room
made by a claim exists only because a player is standing in it.

**Cost.** The matcher chooses from a directory that is up to 3 s stale, and
cannot reserve the room it chose. Two matches sent to one arena's last free
room: the second is refused at its first claim, `Kick(2)`, and its players
re-queue. That is the failure the directory already accepts for a dead arena,
and it costs a player seconds. Should it become common, the arena can hold a
room back for a named match, and nothing about the ticket changes.

**Superseded in part, 2026-09-30, by
[D-42](#d-42--a-matchs-room-is-promised-in-the-store-when-its-arena-is-chosen):**
it became common with tournaments, whose rounds are made all at once, and a
room is now promised when its arena is chosen.

### D-21 — Drones go a fixed reach along the aim

**Decided 2026-09-27**, designing the second tier of tank classes
([01 §4](../detailed-design/01-arena.md#the-second-tier-sizes-recoil-traps-and-drones-designed-2026-09-27-plan-item-5)).

**Decision.** A drone attacks toward a point 300 units along its owner's aim,
not toward a point the player chooses.

**Why.** `Input` carries an aim direction, a `u16` angle, and no distance. On a
phone the aim comes from a stick, which gives a direction far more naturally
than a point; a point would need a second stick, a tap, or a drag, each a
control scheme of its own. The browser original steers drones to the cursor,
and that is the one thing about drones this does not reproduce.

**Cost.** A drone class plays less precisely than its original. If players
miss it, an aim distance can be added to `Input` as one byte, from the stick's
deflection or a tap, and the drones read it; nothing else changes.

### D-22 — The third tier adds kinds of projectile, not more of them

**Decided 2026-09-28**, designing the third tier of tank classes
([01 §4](../detailed-design/01-arena.md#the-third-tier-turrets-bodies-hiding-and-minions-designed-2026-09-28-plan-item-5)).

**Decision.** No class keeps more alive at full reload than the most any class
of the second tier does: 75 bullets and 120 traps. A class with more barrels
reloads slower. A test over the class table holds it.

**Why.** A tick's cost is what is alive, above all bullets, and the second
tier's worst case is already over NFR-1a on the development VM (Q-3). Copied at
the original's rates, an Octo Tank or a Spread Shot keeps twice a Quad Tank's
bullets in the air, and a room that drifts toward them costs twice as much for
no reason a player would name. The original mostly balances more barrels by
slower reloads or weaker bullets anyway; this picks the reload, because that is
the one that bounds the count.

**Cost.** The numbers are unplayed, and some classes will feel slower than
their originals. If play shows a class needs more, the budget is the thing to
argue about, with the measurement next to it, not a row that quietly exceeds it.

### D-23 — A hidden tank is not sent

**Decided 2026-09-28**, designing the third tier
([01 §4](../detailed-design/01-arena.md#the-third-tier-turrets-bodies-hiding-and-minions-designed-2026-09-28-plan-item-5)).

**Decision.** A tank that is hidden (a Stalker, Manager or Landmine standing
still) is left out of every other player's snapshot, as if it were out of their
view. Its own player is told by a flag.

**Why.** The alternative, sending it with a transparency for the client to
honour, sends every client the position of the tank it is not meant to see. A
modified client draws it. Hiding is only real if the position never leaves the
server, and the encoder already knows how to leave out what a client should not
have: it is what the view does.

**Cost.** Others' clients see a tank vanish and reappear, a remove and a create,
rather than fade; a fade is the client's to draw from those. Revealing costs a
create, about the size of a tank's, to each player in view.

### D-24 — The class table reaches the device from platform, versioned by its content

**Decided 2026-09-28**, designing the rest of the tank tree
([01 §4](../detailed-design/01-arena.md#the-rest-of-the-tree-designed-2026-09-28-plan-item-5)).

**Decision.** `platform` serves the class table as JSON at
`GET /v1/content/classes`. Its version is a hash of the table, the same number
the arena sends as `Welcome.contentVersion`, and the response's `ETag`. A
client keeps the table it has until a `Welcome` names another.

**Why.** A client needs the table to draw anything a class makes and to offer
its choices, and until now the only version it was given was a constant 1 that
three tiers of classes never moved. Built into the app, a table goes stale with
every balance change until the next store release; served, it is current when a
player connects. A hash cannot be forgotten, as a number someone must remember
to bump can. `platform` already answers the app over HTTPS and holds nothing
per player to serve it; the arena, which knows the table, speaks only the match
protocol.

**Cost.** `platform` depends on the simulation's content tables, a library with
no dependencies of its own. The arena and `platform` must run the same release
for the hashes to agree, which a release already means.

### D-25 — A party lives in the session store, and only its leader queues

**Decided 2026-09-28**, designing parties
([04 §4](../detailed-design/04-platform-services.md#the-second-slice-parties-and-team-vs-team-designed-2026-09-28-plan-item-6)).

**Decision.** A party is state in j-redis with an hour's expiry, renewed on
every change, made by an invitation and gone when its last player leaves or
goes idle. Only its leader puts it in a queue, as one entry, and any member
leaving takes the whole party out.

**Why.** A party is worth nothing once its players have gone: it has no history,
no purchases and no rating, so it has no business in MySQL, and the queue it
feeds already lives in the store for the same reason. One entry, queued by one
player, keeps the matcher's rule that it takes whole entries or none; a party
split across two matches, or queued twice by two members, is then not a case to
handle but one that cannot arise.

**Cost.** A party does not survive the store's loss; its players invite each
other again. A member cannot queue on their own while in a party; they leave it
first.

### D-26 — A team is rated by its players' mean, each moved by their own K

**Decided 2026-09-28**, designing team-vs-team
([04 §4](../detailed-design/04-platform-services.md#the-second-slice-parties-and-team-vs-team-designed-2026-09-28-plan-item-6)).

**Decision.** For a team match, each team's rating is its players' mean; the
expected score is Elo's on the two means; each player's rating moves by their
own K times the team's surprise.

**Why.** It is the smallest step from the duel's Elo that a team makes
necessary, and it treats a party and three strangers alike, which the matcher
already does when it balances teams by their means. A player's own K keeps a
new player's rating moving fast and a settled one's slow, as in a duel.

**Cost.** A strong player carried by weak ones, or the reverse, moves as their
team did, which over many matches evens out and over few does not. A per-player
share of the result (kills, damage) would be the next step, and is a balance
decision.

### D-27 — A match found asks every player before it is made

**Decided 2026-09-29**, designing the confirm step
([04 §4](../detailed-design/04-platform-services.md#the-third-slice-a-confirm-step-designed-2026-09-29-plan-item-6)).
Open with the user as [Q-5](../requirements/01-scope-and-nfrs.md#7-open-questions):
built on these defaults until they say otherwise.

**Decision.** A match the matcher finds is not made at once. Every player is
asked, and has ten seconds to accept; one decline, or silence, calls it off.
Whoever declined or did not answer is out of the queue with their party and
may not queue again for a minute; everyone else goes back to the queue where
they were.

**Why.** Before, the join was the confirmation: a player matched but not there
held the others for the 30-second join window, then left them a walkover in a
duel, or a rated match three against two in team-vs-team. The lobby connection
the matcher already checks says a device is there, not that its player is. A
question costs a present player one tap and at most ten seconds; the room, the
arena slot and the tickets are then made only for players who said they were
coming. The minute's lock makes declining cost the decliner something, and not
the others.

**Cost.** Every match starts up to ten seconds later, and a player must answer
a prompt. A player locked for a minute may have had a good reason. The two
numbers are balance, and the step itself is a product choice.

### D-28 — A free-for-all is rated pairwise, each player against each other

**Decided 2026-09-29**, designing ranked free-for-all
([04 §4](../detailed-design/04-platform-services.md#the-fourth-slice-ranked-free-for-all-designed-2026-09-29-plan-item-6)).
Its numbers are open with the user as
[Q-6](../requirements/01-scope-and-nfrs.md#7-open-questions).

**Decision.** A ranked free-for-all is scored as every pair of its players
playing a duel decided by their placements: each player's surprise against each
other is summed, divided by the field less one, and scaled by their own K.

**Why.** It is the duel's Elo, unchanged for two players, and it needs no new
constant: the strength of the field is already in each pair's expectation, so
placing fourth among strong players costs less than fourth among weak ones,
which is what "scaled by field strength" asked for. Dividing by the field keeps
a match's movement the size of one duel's, however many play.

**Cost.** Placements are all it sees: a close second and a distant second move
alike. Each result reads and writes eight ratings under the lock, as a team
match does six.

### D-29 — An arena drains before it stops, and a match cut short is not rated

**Decided 2026-09-29**, designing the drain
([01 §8.6](../detailed-design/01-arena.md#86-draining-an-arena-designed-2026-09-29-plan-item-7)).

**Decision.** Stopping an arena first withdraws it from the directory, sends its public rooms' players back to the lobby, and lets each made match run
to its end, for up to eleven minutes; then it stops as before. A match that is
still cut short is published as such, paid as any match, and not rated.

**Why.** A restart is weekly and a deploy more often; each used to cut every
match in progress, and publish its state at that moment as a result, so a
rated duel a minute in moved both ratings on nothing. Waiting for made matches
costs only the arena's time: its players see nothing. The public rooms have no
end to wait for, and already hand their players back with what they earned.
Rating a match that did not finish would punish or reward a restart.

**Cost.** Stopping an arena takes up to eleven minutes, which a deploy of three
arenas one at a time turns into half an hour. A match that runs out the drain
is still cut, and its players get their pay but no rating. A killed process
publishes nothing new: its spool still does, as it always has.

### D-30 — Admin calls are audited in MySQL until there are streams

**Decided 2026-09-29**, designing the admin API's first slice
([04 §10](../detailed-design/04-platform-services.md#the-first-slice-designed-2026-09-29-plan-item-7)).

**Decision.** Every admin call, allowed or refused, is a row in `admin_audit`:
when, which call, its target, what it asked and what came of it. Not the
`s:audit` stream the design names, which waits for j-redis streams.

**Why.** An admin action is the one most worth reconstructing, and a list in
j-redis without streams' retention would either grow without end or be
trimmed by hand. MySQL is already backed up, point-in-time, off its machine,
and is where the ban it records lives; one transaction writes both.

**Cost.** Admin calls reach the database even when refused, which a flood of
wrong secrets could turn into load; the listener is on loopback, reached
over SSH, so a flood already means a compromised machine. Moving to the
stream later is a second writer, then a migration of the table's rows.

**Revisited 2026-10-01** with streams built: it stays in MySQL
([D-50](#d-50--the-admin-audit-stays-in-mysql-streams-or-not)).


### D-31 — The store is not copied off its machine on a timer before replication

**Decided 2026-09-29**, taking up the last unbuilt backup in plan item 7
([operations/01 §6](../operations/01-deploy.md#6-still-to-be-written)).

**Decision.** No scheduled copy of j-redis leaves its machine until j-redis 2.2
replicates it. `backup-store.sh` stays, for a copy by hand before an upgrade.
The one thing in the store that is neither disposable nor rebuilt from MySQL,
the dead list, is copied out by the runbook's first step when it alerts
([runbook §3](../operations/02-runbook.md#3-per-component-procedures)).

**Why.** What the store holds, key by key ([glossary §7](../glossary.md#7-store-keys)):
sessions, tickets, the queues, parties and locks are disposable, costing a
login or a queue again; the boards and names come back from MySQL
(`backend-leaderboard-rebuild`). What remains is results in flight, published
and not yet applied, which are seconds old, and the dead list, which alerts on
its first entry. A copy on a timer holds neither: the results in flight are
younger than any copy, and a dead entry is younger than the copy until the one
after the alert. The copy would add a machine-to-machine credential and a job to
watch for no case it recovers. Replication recovers the results in flight too.

**Cost.** Until 2.2, losing the store's disk loses the results in flight (their
players' pay and rating for those matches) and any dead entry not yet copied
out. The arenas delete a result's spool file once the queue accepts it; keeping
the files a while longer would cover the first, and was not built because
replication is the answer the architecture already has.

**Revisited 2026-09-29** with j-redis 2.2: each store has a replica on another
machine
([D-34](#d-34--each-store-has-a-replica-every-process-knows-both-a-promotion-is-a-script)),
which is the copy off its machine this entry waited for. No copy on a timer was
added: `backup-store.sh` stays for a copy by hand before an upgrade, and the
dead list is still copied out when it alerts.

### D-32 — A phrase is heard by the speaker's team, or by those who see them

**Decided 2026-09-29**, designing fixed-phrase chat
([01 §9](../detailed-design/01-arena.md#phrases-designed-2026-09-29-plan-item-8)),
on recommendation, open with the user as
[Q-8](../requirements/01-scope-and-nfrs.md#7-open-questions).

**Decision.** In a mode with teams (team-vs-team, co-op), a phrase goes to the
speaker's team wherever they are, the speaker included, and to nobody else. In
a mode without, it goes to every player whose view holds the speaker's tank,
the speaker included; a speaker with no tank in the world is heard by nobody.

**Why.** A team's phrases are its plans: "Attack!" said to the other side is
information given away, and a team is small enough (three) that hearing all of
it is readable. Without teams, a phrase belongs to a tank on the screen, as a
bubble over it. Heard room-wide, 150 players at one phrase every two seconds
could send each client 75 a second, about 1 KB/s, a third of `mobile`'s
budget, and nobody could read them. The view is already computed for every
client every snapshot, so the audience costs a handle lookup per listener.

**Cost.** Nobody in the public arena can be spoken to out of sight, and in a
team mode the other side cannot be greeted, not even with "Well played!" at
the end. Either is a rule to widen later, not a format to change: the event
already names a speaker the listener cannot see.

### D-33 — The result queue is a stream read by one group; the list stays an inbox

**Decided 2026-09-29**, designing plan item 10
([05, "The move onto streams"](../detailed-design/05-worker-and-events.md#the-move-onto-streams-designed-2026-09-29-plan-item-10)).
(The j-redis log has its own D-33, about how it stores a stream.)

**Decision.** Finished matches go to the stream `s:match-result`, trimmed to 24
hours, read by the consumer group `rewards`, one consumer per worker. A worker
re-drives its own pending entries and claims any idle a minute. The dead and
deferred lists stay lists. The list `q:match-result` stays too, as an inbox
every worker drains into the stream.

**Why.** It closes the one gap the list design could not: entries in flight on
a worker that never comes back were stranded until a worker with its id
started. A group's pending list knows whose each entry is and for how long, so
any worker can take them over. It also gives the lag per group the design wanted
to alert on, and room for a second group (analytics) without a second queue.
Keeping the list as an inbox makes the upgrade order-free and keeps the
runbook's dead-entry procedure; keeping the dead and deferred lists keeps what
operators already know.

**Cost.** Two structures where there was one, until a later release stops
reading the inbox. A result pending longer than the 24 hours of retention is
trimmed away unapplied; it is logged and counted, not hidden. And the events
store must run j-redis 2.1 before the backend that needs it.

**Revisited 2026-10-01** (plan item 40): the inbox stays
([D-53](#d-53--the-result-inbox-stays-the-operators-way-to-put-an-entry-back)).

### D-34 — Each store has a replica; every process knows both; a promotion is a script

**Decided 2026-09-29**, with j-redis 2.2 released (plan items 11 and 12).

**Decision.** Each j-redis store runs as a primary with one replica on another
machine: `session` on A with its replica on B, `events` on B with its replica on
C ([operations/01 §1](../operations/01-deploy.md#1-what-runs-where)). Every
process is given both addresses of each store it uses and follows a promotion
by itself (the j-redis client's `addresses`, which uses the primary with the
highest epoch). A promotion is `promote-store.sh`, run by an operator on the
replica's machine: it refuses unless the old primary is down or it demotes it
first, then promotes, and edits the configuration so a restart keeps the role.

**Why.** D-8 made failover scripted and human-triggered; replication (j-redis
2.2) is what gives the script a replica to promote. Two placements on
different machines mean losing one machine costs at most one primary. Clients
that find the primary themselves mean the promotion is one command on one
machine, not a configuration change and a restart of every process; the epoch
keeps them off an old primary that comes back unfenced (j-redis D-37).

**Cost.** Twice the store memory across the fleet, and a replica's full sync
after each restart of either side (about a minute for a day of results at design
load). What a store held but had not yet sent its replica is lost with its
machine: for results, [Q-11](../requirements/01-scope-and-nfrs.md#7-open-questions)
decides how that window is watched; for sessions, it costs logins.

### D-35 — MySQL fails over as the stores do, by an epoch the clients follow

**Decided 2026-09-29** (plan item 13), with the stores' failover built (D-34).

**Decision.** MySQL's replica is on C, fed by GTID replication and read-only.
A one-row table, `ha_epoch`, holds an epoch that only a promotion raises, on the
new primary. Every backend process is given both hosts and opens connections
through a source that uses the writable host with the highest epoch, never one
below the highest it has seen. A promotion is `promote-mysql.sh`, which fences
the old primary or refuses, waits for the replica to apply what it received, and
makes it writable persistently before raising the epoch. The old primary returns
only as a new replica, rebuilt from a copy.

**Why.** The alternatives each leave a hole. A driver's own multi-host failover
connects to the first host that answers, so an old primary that comes back
writable takes the writes again. A virtual address moved by the script needs
infrastructure the three machines do not have, and repointing every process by
hand is minutes of outage and a step to forget. The epoch is what the stores use
(j-redis D-37), so one procedure and one argument cover all three stateful roles
(architecture/02 §3's "epoch numbers on replication"). Rebuilding the old
primary rather than re-attaching it avoids errant transactions: ones it
committed and never shipped, which GTIDs detect and which would otherwise stop
replication or come back as duplicates.

**Cost.** A migration (V8) and a probe per new connection, a few round trips,
paid only when the pool opens one. What the replica had not received when the
primary died is lost: at the replication lag, usually well under a second. A
process that never saw the promotion and can reach only the old primary still
writes to it: fencing stays the procedure's first step.

**Found by the drill (D-29).** In a planned handover the old primary is fenced,
not stopped, and made read-only alone it broke none of the pools' connections:
they kept writing to it, refused, and reading from it, stale. The fence is
`offline_mode` too, which closes them. Dropping a connection whose write is
refused for read-only was tried first and rejected: it fixed the writes, one
failed request per connection, and left the reads stale.


### D-36 — A MySQL replica is made, and an old primary remade, by clone

**Decided 2026-09-30** (plan item 13 part (d)), for D-35's replica and for step 5
of its runbook.

**Decision.** The replica is made from the primary with MySQL's clone plugin:
one `CLONE INSTANCE` on the recipient copies the data, the accounts and the GTID
history, and it then replicates by GTIDs from where the copy ends. An old
primary returns the same way, the copy throwing away whatever it held that the
new primary does not. The plugin is loaded on both servers, so either can give
or take. The recipient is read-only from its configuration and, for the copy
alone, closed to all but administrators.

**Why.** A logical dump and load is hours at production size, needs the GTID
history set by hand and the accounts carried separately; stopping the primary
to copy its files is an outage; a third-party physical backup tool is one more
thing to install and keep. Clone is in the server, runs with the primary
serving, and was rehearsed in the drill: 9 s at development size, every match
there, replicating at once.

**Cost.** One account with `BACKUP_ADMIN` on the primary. The copy checks the
donor's certificate against the CA but not its address, which replication does
check; the CA signs only these servers. The recipient's persisted settings stay
its own, which is why the fence does not persist `offline_mode` (06 §10).

### D-37 — Equipment reaches the arena as a capped percentage a stat, in the ticket

**Decided 2026-09-30** (plan item 15, Q-12).

**Decision.** `platform` resolves what a player wears into a bonus a stat, a
whole percent capped at 25, and puts the stats that have one in the join
ticket. The arena applies it to every tank the player spawns in that stay. Item
definitions live on `platform` alone, in `items.json`.

**Why.** FR-10: the arena never queries inventory, and it has no database to
query. Resolving on `platform` keeps the item table in one process, so a new
item or a changed number is a `platform` release, never an arena one. Whole
percents travel as integers, compare exactly, and cannot carry a value a float
parser reads differently. The cap applied before the ticket is written leaves
the arena nothing to check but the range.

**Cost.** A change of loadout counts from the next ticket, not in the middle of
a stay, and for a made match from the next queue: the matcher writes tickets
from the store alone, so the bonus is read with the rating when the player
queues. A ticket is a few bytes longer. An arena older than this ignores the
field, so a player's equipment does nothing there until it is upgraded.

### D-38 — A boost raises a match's rewards, in worker, if the match ended while it ran

**Decided 2026-09-30** (plan item 16, Q-13).

**Decision.** A boost raises a player's experience or coins from a match, never a
rating and never anything inside the match. `worker` applies it, reading the
player's boosts when it applies the result and counting those that were running
when the match ended. Every run of a boost is kept, and a run's end only moves
forward, so that answer never changes afterwards.

**Why.** Rewards are already worker's alone (`RewardRules`): a boost that the
arena applied would need the arena to know about boosts and be redeployed to
change them, and the ticket could not carry one that starts during a stay.
Judged at the match's end, a result applied late, or twice after a crash, gets
the same boost both times: a boost that ran out, or started, between the end and
the application does not change what the match was worth. Rewards only, one a
kind: a boost buys progress, never wins, and two cannot compound.

**Cost.** A read of the boosts a result, in the worker's transaction's path. A
boost started during a match counts for it if it was running at the end: the
match's end is the one moment both sides can agree on.

### D-39 — A team's invitations are MySQL rows, and every change locks the team, then the players

**Decided 2026-09-30** (plan item 17, Q-14).

**Decision.** Team invitations are rows in `team_invite`, beside the team they
name, not keys in the store as a party's are. Every team action is one MySQL
transaction that locks the team row first and then the player rows it changes,
in that order.

**Why.** A party lasts minutes and dies with the store, so its invitations are
store keys with a TTL (D-25). A team lasts months and lives in MySQL; an
invitation accepted must check the team's capacity and the player's cooldown
and write the membership in the same transaction, which a store key cannot join.
One lock order, team before players, is what keeps two actions on one team from
deadlocking, and the team row is what makes capacity and the vice leaders'
limit real constraints rather than races (04 §2).

**Cost.** Expired invitations stay until they are read or the team acts; they
are ignored once past `expires_at`, and dropped when the team is disbanded.

### D-40 — A tournament match is a made match, its winner read from what MySQL recorded

**Decided 2026-09-30** (plan item 18, Q-15).

**Decision.** `worker`'s scheduler makes a tournament match as the matchmaker
makes a duel: tickets naming a new match, a grant for each player, a push. The
arena plays it as any duel and publishes its result; `worker` applies it as any
result. The scheduler then reads the winner from the `matches` and
`match_player` rows that recorded that match, by its `match_uid`.

**Why.** The arena, the result stream and the worker's application already
carry a made match end to end, drilled, idempotent and rated; a tournament
that had its own path from arena to MySQL would duplicate all of it and fail
differently. Reading the winner from what MySQL recorded, rather than from the
stream, means the scheduler sees exactly what was paid and rated, and a result
redelivered twice cannot advance a player twice: the bracket's own write is a
conditional update, as every transition is (04 §6).

**Cost.** A round waits on the result's application, a second or so after the
match ends. A match with no result at all, nobody having come or its arena
lost, is known only by the time passing: 270 s after the tickets, the higher
seed advances.

### D-41 — Every worker runs the tournament clock, and a match is claimed before its tickets are written

**Decided 2026-09-30** (plan item 18).

**Decision.** The tournament tick runs in every worker every 5 s, with no lock.
Each of its writes is conditional on what it read: a tournament's state and
version, a match's state, a prize's ledger key. The one step with effects
outside MySQL, making a match, claims the match first (`PENDING` to `READY`
with a new match id), and writes the tickets, the grants and the pushes only if
that claim changed a row.

**Why.** 05 §9 designed each job as a singleton behind a lock with a fencing
token. Retention showed the cheaper shape: when every write is its own check,
two runs cost reads, not correctness. Every transition here had to be
conditional anyway (04 §6), and a paused lock holder would still need that; a
lock on top adds a lease to renew and a handover to wait out. The order of claim
and tickets is the one choice left. Tickets first, and two workers could each
write a set for one pairing, two matches for one pair. Claim first, and a worker
that dies between the two leaves a match nobody can join, which the 270 s rule
already decides.

**Cost.** Each worker reads the open tournaments every 5 s, one query, and a
running one's matches and entries, two more. A worker that dies between a claim
and its tickets costs that match: the higher seed goes through unplayed.

### D-42 — A match's room is promised in the store when its arena is chosen

**Decided 2026-09-30** (plan item 18 part (d), [T-21](../defects.md#4-concurrency)).

**Decision.** Choosing an arena for a match (`ArenaDirectory.reserveForMatch`)
writes a promise: the match's id in `rooms:promised:{arena}`, a sorted set
scored by when the promise lapses, the ticket's 60 s. Every choice counts the
promises still running against each arena's free rooms. The arena drops a
promise itself, in the same write as the announcement whose room count first
includes that match's room: from then on the room counts itself, and it is free
again when the match ends. The chooser writes its promise, counts again, and takes the promise
back if the arena is now over: two choosers racing for one last room both back
off, and their matches wait for the next round of the matcher or the next tick
rather than being refused at the door. The matchmaker and the tournament
scheduler both choose this way; a scheduler whose claim on the match was lost
gives its promise back.

**Why.** D-20 accepted that two matches could be sent to one arena's last room,
the second refused and re-queued at a cost of seconds. A tournament's round is
made in one tick: each of its matches chose the same arena from the same
announcement, and every match past that arena's free rooms was refused. A
refused tournament match is not re-queued. Nobody plays it, and 270 s later the
higher seed goes through unplayed. The drill found it with one room free and
two matches.

**Cost.** A room promised to players who never come is held for its 60 s.
Each choice reads each arena's promises. A process of an older release that
chooses without promising can still take a promised room, and an arena of an
older release does not drop promises, so each of its matches holds a second
room's place until the promise lapses.

**Tried first, and why not:** choosers dropped a promise when they saw its
match in the arena's room list. A match that began and ended between two
choices, a walkover, was never seen, and its promise held the room it had
already given back; the drill's co-op match found no room.

### D-43 — A team match's sides are known by their players' teams when the result is applied

**Decided 2026-09-30** (plan item 19, Q-18).

**Decision.** Nothing carries a team's id from the queue to the result: the
ticket, the arena and the result name sides, 1 and 2, as for any team mode.
When `worker` applies a team match's result, each side's team is the one its
players are in then. A player who left or was kicked since is in no team and is
passed over; the side's team is the one the rest share. A side whose players
are in two teams, or in none, or the same team on both sides, is applied for the
players and not for the teams.

**Why.** Carrying the team from the matcher to the worker would change the
ticket, the arena's result and its codec, three processes deployed apart, for
what the rules already make plain: a player out of a team cannot join another
for 24 hours (04 §2), so in the five minutes of a match a player's team is the
one queued with, or none. Kicking a member does not move a loss off the team,
since the rest still name it.

**Cost.** A team disbanded before its result is applied is not rated, a way to
escape one loss at the price of the team. The teams are read before they are
locked: the result's transaction reads the players' teams, locks those teams,
then the players, D-39's order, and reads the teams again under the players'
locks. A member who leaves in between counts as one who left. One who joins
another team in between, possible only as a 24-hour cooldown ends in that
instant, fails the application, which is retried, and the retry locks that team
too; no branch is kept for it.

**Superseded in part, 2026-10-02**
([D-59](#d-59--a-team-match-reads-its-teams-from-the-player-rows-it-locks-and-rates-them-once),
defect D-36): the second read of `team_member` under the players' locks returned
the first read's snapshot, so the teams are now read again from
`player.team_id`, in the statement that locks the players, and rated only by the
delivery that first applies the result.

### D-44 — A team's tournament entry fixes its roster, and the bracket holds teams' ids where a duel's holds players'

**Decided 2026-09-30** (plan item 20, Q-19).

**Decision.** A teams' tournament has entries of its own, `tournament_team_entry`,
and the three players who registered each team, `tournament_roster`. Its bracket
is the duel's `tournament_match`, whose `player_a`, `player_b` and `winner` hold
the teams' ids; the tournament's `mode` says which they are. At a match the
scheduler sends a ticket to each roster member still in the team: side 1 to
`player_a`'s, side 2 to `player_b`'s, and reads the winner by the side placed
first.

**Why.** `tournament_entry` keys a player, with a foreign key to one, and a team
entry is not a player; a second table keeps each entry's key honest. The bracket's
columns have no key and mean "an entry's id"; renaming them would be an
expand-and-contract migration for a name. The roster is what lets the scheduler
make a team's tickets as it makes a duel's, with no one choosing the players at
match time.

**Cost.** A roster member who is offline, or left the team, leaves the side a
player short; no substitute can be named. The bracket's column names say
`player` for a team's id; the API names them `teamA`, `teamB` and `winnerTeam`
for a teams' tournament.

### D-45 — A friendship is two rows, and a change to it locks both players, lowest id first

**Decided 2026-09-30** (plan item 21, Q-20).

**Decision.** A friendship is stored both ways, `friend (player_id, friend_id)`
and its mirror, written and removed together. Every change to friends or
blocks is one transaction that locks both players' rows, lowest id first,
before it reads what it changes. A request to someone who has requested you is
an acceptance.

**Why.** Both ways, a player's friends are one indexed range read, and the
count that the cap of 100 checks is a count of one player's rows. Locking both
players in one order is the order the result's transaction already takes for
players (06 §4), so the two cannot deadlock; it makes the cap and "a request
both ways at once" real constraints: two players asking each other at the same
instant are serialised, and the second finds the first's request and accepts.

**Cost.** Two rows a friendship; a change to friends waits on a match result
being applied for either player, milliseconds.

### D-46 — A guest's credential is a random key, kept as its SHA-256, under a username nobody can choose

**Decided 2026-09-30** (plan item 22, Q-22).

**Decision.** A guest account has a 256-bit random key in place of a password:
the device keeps the key, the account keeps its SHA-256 in `guest_key_hash`,
unique, and a guest logs in by sending the key. Its username is `~` and a ULID,
which the username rules forbid, so nobody can register it and no login by name
reaches it. Upgrading sets a chosen username and an Argon2 password hash and
clears the key's hash, in one statement.

**Why.** Argon2 is there to make guessing a human password expensive. A random
256-bit key cannot be guessed, so a slow hash buys nothing and would put every
guest login in the hasher's line, 88 ms each; one SHA-256 and an indexed lookup
suffice. A username the rules forbid keeps a guest out of the username
namespace without making the column nullable.

**Cost.** Whoever holds the key is the guest, as whoever holds a session token
is the session; both live in the device's secure store. A guest who loses the
device loses the account.


### D-47 — Activity is recorded in the result's own transaction, a row a player a day, and counted when it is read

**Decided 2026-09-30** (plan item 25, Q-24).

**Decision.** Applying a result writes, in its transaction, one row a player a
day to `player_day` (the day the match ended, UTC; a repeat ignored), and moves
the player's `first_played_on` to that day if it is earlier than the one kept.
The admin API counts the days it is asked for, at most 60, from those two
alone, when it is asked: no second consumer group on the result stream, no
table of daily totals, no scheduled job.

**Why.** The result transaction already knows the players and the day, and
already decides once whether a player's part is new; recording activity there
is one statement more, made idempotent by the same decision. A second group on
the stream, as 05 sketched, would read every result again, need an idempotency
of its own, and could disagree with what was paid. Counting on read, from a
key that starts with the day and an index on the first day, touches only the
rows of the days asked for, and a result that arrives late is counted with its
day the next time anyone asks, where a table of totals would need recomputing.
Counting from the match history alone needs no new table, since `matches` has
its index on `ended_at` (V3); but a read would join every match of each day,
public stays included, millions a day at 50 000 players against their hundreds
of thousands, and a player's first day must outlive the 90 days that history is
kept.

**Cost.** One more statement in every result's transaction, measured. A read at
production size counts each day's active players through the index, about as
many entries as players that day: seconds of MySQL time for 60 days at
hundreds of thousands a day. If operators read it often, the next step is a
daily table of the days that can no longer change.

### D-48 — A maze is sent as a seed, and a predicted bullet stops at its walls by one rule on both sides

**Decided 2026-10-01** (plan item 32, Q-31).

**Decision.** A maze's walls are never sent. The Welcome carries the maze's seed,
appended (0: no maze), and the client makes the same walls from it with the
same generator, a port of the server's, held to it by a golden vector of walls
for fixed seeds. A bullet, which the client simulates (D-9), ends at a wall by a
rule both sides apply to the same numbers: the first tick its centre is within
its radius of a wall's rectangle.

**Why.** Walls are static, so a seed carries them in a few bytes where entities
would cost a create each and handles the client's view could hold for nothing
better. A bullet the client predicts would otherwise fly through a wall until
the server's destroy arrived, the late destroy D-9 already counts as its
largest error, now in plain sight. Shapes and units are sent with updates when
they move, so their sliding along walls needs nothing on the client.

**Cost.** Two copies of one generator and one rule, in two languages, kept equal
by tests in both: the golden vector, written by the Java side and read by the
C# side, as the snapshot's vectors are.

### D-49 — A sandbox is a made room that publishes nothing, opened by a request, its powers one message

**Decided 2026-10-01** (plan item 33, Q-32).

**Decision.** The sandbox is a mode, `MatchMode.SANDBOX`, with no roster, so
nobody queues for it. `POST /v1/sandbox` makes one at once for the caller or the
caller's party: an arena's room promised, a ticket each naming the match, and
`evt.match.found`, as the matcher does after its confirm step, with no ask, since
the one asking is the one going and a party's leader speaks for the party as in
the queue. The arena makes the room from the first ticket (D-20), plays at once,
and ends it at its clock or once it has been empty for a minute, publishing no
result: the made room's end with no outcome. What a player does in it travels
in one new client message, `Sandbox`, an action and a value, which the arena
reads only in a sandbox.

**Why.** Everything a sandbox needs from the platform, the store and the arena
exists for made matches: tickets, the promise of a room, the push, a room
started from a ticket, `Kick(6)` at the end. A queue of one is not a queue, and
asking someone whether they accept what they asked for is noise. One message
with an action keeps the protocol's surface to one type for every power to
come.

**Cost.** A room per sandbox, held for up to twenty minutes by one player: the
same budget as matches, which wait when an arena has no room free. A request
made but never joined holds a promise until it lapses, as an accepted match whose
players never come does.

### D-50 — The admin audit stays in MySQL, streams or not

**Decided 2026-10-01** (Q-33), revisiting
[D-30](#d-30--admin-calls-are-audited-in-mysql-until-there-are-streams), which kept
the audit in MySQL "until there are streams". There are now (j-redis 2.1).

**Decision.** `admin_audit` stays the audit; the `s:audit` stream is not built.

**Why.** D-30's reasons were never about streams lacking: MySQL is backed up,
point-in-time and off the machine; it is where the ban an audit row records
lives, and one transaction writes both. A stream would keep 24 hours (Q-9) where
an audit is wanted for longer, and would need a consumer to put it somewhere
durable, which is MySQL again.

**Cost.** None new: a flood of refused calls reaching the database, as D-30
said, needs a compromised machine first.

### D-51 — A cold resume is the device's: the app keeps its stay's secret

**Decided 2026-10-01** (plan item 36, Q-33).

**Decision.** An app killed and restarted resumes its stay with the secret it
kept: the latest Welcome's, with the arena's address from the grant, written
where the session token is kept. The lobby hands nothing back, and no arena
writes a stay's secret into the store. The client core gains one call,
`MatchConnection.Resume(secret)`, which dials and sends `Resume` in place of
`Join`.

**Why.** The stay lives in one arena's memory (02 §10), and waits a minute for
its player. Within that minute the device that played it is the one coming
back, and it can keep 128 bits; the store would take a write at every Welcome
and a new API to hand the secret out, for a reach the device already has. The
arena treats the resume as any other: nothing changes there.

**Cost.** A credential on the device for a minute, kept as the token is. A
reinstall, or another device, cannot resume: it comes through the lobby, as a
player whose minute ran out does.

### D-52 — A tank's name travels in its create

**Decided 2026-10-01** (plan item 38, P-35).

**Decision.** A tank's create carries its player's display name in the field it
always had, empty until now. Nothing else sends names: not a roster at the join,
not an update.

**Why.** A client needs the name of a tank it sees, and only those: a create is
sent exactly when a tank comes into view, so the name comes with the tank and
not otherwise. A roster at each join would tell every player of a public room of
every arrival, a hundred and fifty names for each, most of them never seen. A
name does not change during a stay, so nothing needs updating.

**Cost.** A name's bytes, about eight, on each create of a player's tank; a
create is once per tank entering a view and once per respawn. Measured with
`TickBenchmark` (plan item 38).

### D-53 — The result inbox stays: the operator's way to put an entry back

**Decided 2026-10-01** (plan item 40, Q-33), revisiting
[D-33](#d-33--the-result-queue-is-a-stream-read-by-one-group-the-list-stays-an-inbox),
whose cost was "two structures where there was one, until a later release stops
reading the inbox".

**Decision.** `q:match-result` stays the inbox every worker drains into the
stream. It is not retired.

**Why.** Of its three uses one has gone: no arena of the list release runs
anywhere, since none was ever in production. The other two are the operator's
and the worker's. The runbook puts a dead entry back with one `LMOVE` from the
dead list to the inbox (§2), atomic, so an entry is on one list or the other and
never on neither; with no inbox it would be a pop and a stream add, with a
moment where a failure between them loses the entry. A worker returns deferred
entries the same way. Draining an empty inbox costs a worker one command a
poll.

**Cost.** Two structures, as D-33 said, kept on purpose now rather than until a
release that removes one.


### D-54 — A sandbox is held by a key of the player's own, for as long as its room

**Decided 2026-10-01** (plan item 52, defect S-13).

**Decision.** `sbx:{playerId}` names the sandbox a player holds. `platform` sets
it with `NX` when it opens one, for a ticket's life, and refuses a second while
it is there; the arena sets it again for the room's whole life when the player
joins, and deletes it when they leave on purpose or the room ends. Leaving the
queue's record of a sandbox not yet joined revokes the ticket, and gives the hold
back only if the revoke took it.

**Why.** The queue's record of a match, `mmp:{playerId}`, lasts as long as a
ticket by design, so that a player may queue again from inside any match (D-49
kept that for a sandbox), and leaving forgets it: as a record of the sandbox it
let one session hold every free room (S-13). A room lives longer than its ticket,
and only the arena knows when a player is in it and when it ends; the key is
set where each of those is known. A lost connection keeps the hold, since the
player may resume into the room.

**Cost.** Three store writes a sandbox player, and a key that outlives an arena
that dies by at most the room's twenty minutes and one, during which that player
may queue but not open another sandbox.

### D-55 — Each step of the confirm step is one watched transaction

**Decided 2026-10-02** (plan item 56, defects T-32, T-33, T-34).

**Decision.** Asking, making, calling off and joining each read the records
they decide by under `WATCH` and write everything in one `MULTI`: asking takes
the entries out of the queue in the same transaction that marks their players
asked; making writes every player `matched` only if each is still asked about
that match and accepting, after the room is promised and the tickets written;
calling off reads the answers again rather than trusting the round's read; and
joining checks the lockout with the records it writes. A step that loses writes
nothing and waits for the next round. An asked player's record expires with the
match's wait, 60 s, not the queue's 900 s. A party change while asked marks the
member `withdrawn`: that entry is out of the match, nobody locked out.

**Why.** Every one of the three defects is a write made on a read that another
request could change in between: a leave, a decline, a party change, a
lockout. The store's own transactions close those gaps where the rest of the
queue already uses them (a join, an answer, a party's change), with no new
mechanism. Writing the tickets first and the match's end last means a failure
anywhere before the end leaves the match where the next round finds it, rather
than in no list at all. The shorter lifetime bounds what is left when even that
fails, as when the store loses writes in a failover.

**Cost.** A watched step holds a leased connection for its reads and its
`MULTI`: for a free-for-all of eight, nine reads and one transaction, where the
step before was one transaction unwatched. A step that loses costs its match a
second. A failed making leaves unclaimed tickets, which nobody holds, for 60 s,
and a room promised for as long, unless the making itself saw the change.

### D-56 — A blocked player's friend request is kept, and hidden from the one who blocked them

**Decided 2026-10-02** (plan item 57, defect S-14).

**Decision.** A friend request from a player the other has blocked is written
as any other: the asker sees it in `asked`, asking again is `already_asked`,
withdrawing it works, it counts against the asker's limits, and it lapses in
seven days. The one who blocked them never sees it: no inbox item, no push, and
their list of requests leaves out every asker they block. Unblocking deletes it.

**Why.** "A block is not a signal to the one blocked" (04 §9). Answering
`asked` and keeping nothing was the first way to honour that, and it differed
from a kept request in three answers the asker could read. Keeping it makes all
three the same by construction, where faking each answer would need its own
memory of a request that does not exist. Dropping it on unblock means a block
never delivers anything late: what was asked while blocked was never seen.

**Cost.** A row a blocked asker can keep alive by asking again each week, within
the limits every asker has, and a `NOT EXISTS` on `block` in the read of a
player's requests, by its primary key.

### D-57 — A rating board is indexed by its listed players only, and a place read by key, not by offset

**Decided 2026-10-02** (plan item 58, defect D-35).

**Decision.** Each rating board has a generated column on `player`, the rating
when the player has ten rated matches in the mode and NULL otherwise, virtual,
and an index on it in the board's order (rating, rated matches, id). The top,
the count above a player and the window around them read by that index only;
the window by key, `each` rows backwards from the player's own and `each`
forwards, not by `LIMIT offset`.

**Why.** Measured, a place below the top read every account twice, 200 000 rows
for a board of 5 000, since the unlisted sit in the old index at the starting
rating and the listing threshold cannot narrow a range on it. A NULL key is in
no range, so the unlisted cost nothing; keyset reads cost the rows returned.
A virtual column costs no storage and is computed on write into its index only.

**Cost.** The threshold, ten, is written in the schema as well as in the code; a
test reads it back. Three more secondary indexes on `player` until V17's are
dropped. The count is still the players listed above: fine at thousands, to be
measured again at hundreds of thousands, where a rank structure (06 §1) would
take over.

### D-58 — A replica is measured by what it has applied: a heartbeat for MySQL, the primary's own account for the stores

**Decided 2026-10-02** (plan item 59, defect O-10).

**Decision.** MySQL's replica is measured by a one-row heartbeat the workers
write on the primary every second: its lag is the primary's last heartbeat less
the replica's, both stamped by the primary's clock. Each store's replicas are
measured from the primary's `INFO replication`: how many are connected, how far
the slowest has acknowledged, how long since it last did. The workers read both
at each scrape. `promote-mysql.sh` refuses a replica whose last heartbeat is more
than five minutes old, or cannot be read, unless told `--stale-ok`.

**Why.** What a promotion loses is what the replica has not applied, so that is
what is measured, end to end, through whatever stopped: a receiver that lost its
source, an applier stopped by an error, a network partition. A heartbeat needs no
privilege beyond the backend's, and no clock but the primary's. The store
primary already counts its replicas' acknowledgements for `WAIT`; reading them
adds nothing to the store.

**Cost.** One write a second a worker on the primary, carried by replication, and
a connection to each MySQL host and one `INFO` a store at each scrape. The lag's
resolution is the heartbeat's second. A promotion long after an outage needs the
override, which the runbook names.

### D-59 — A team match reads its teams from the player rows it locks, and rates them once

**Decided 2026-10-02** (plan item 60 (b), defect D-36).

**Decision.** Applying a team match's result reads each player's team a second
time from `player.team_id`, in the same statement that locks the players. A side
is rated only if its players share one team and that team was locked before the
players; otherwise it is not rated, and nothing fails. The teams are rated only
by the delivery that first applies the result: one that inserts a player's row.

**Why.** Under REPEATABLE READ a second plain read of `team_member` returns the
snapshot the first read made, before the teams were locked, so a team disbanded
in between was missing from the locked teams and the apply failed on a null. A
locking read of `team_member` (`FOR SHARE`) would see the current rows, but it
takes a new lock after the players' and can wait on a disband that is itself
waiting for one of those players: a deadlock. Every change of team writes the
player's row under its lock (joining, leaving, a disband), so the row the apply
already locks says the team as it is now, at no extra lock or statement. A
redelivery that rated teams could rate sides that did not resolve the first time,
on the teams as they are days later.

**Cost.** One more column in a read the apply already makes. A side whose team
changed between the two reads, in the milliseconds between them, is not rated.

### D-60 — A board's name is the one the database holds when a result is applied

**Decided 2026-10-02** (plan item 63, renaming).

**Decision.** `worker` writes a score board's name (`lb:name`) from the display
name MySQL holds when it applies the result, read in the statement that locks
the player, and no longer from the name the result carries. A rename writes it
too.

**Why.** The name in a result was copied into the ticket when the match was
joined. With renaming, a match that began before a rename ends after it, and
its result would write the old name back over the new one, to stay until the
player's next match: a rename that seemed not to work. The apply already locks
every player it pays; their name is one more column in that read, and it is the
name every other page shows.

**Cost.** One column in a read the apply makes anyway. A rename committed in the
instant between an apply's read and its board write can still lose to it, until
the player's next match.

### D-61 — A level's gems are paid for the milestones between the level stored and the new one

**Decided 2026-10-02** (plan item 68 (c), where gems come from).

**Decision.** `worker`, applying a result, pays a milestone's gems (04 §8) for
each milestone level above the account level the player row holds, read in the
statement that locks it, and up to the level the new xp is worth. Each is paid
through the ledger's one path in the same transaction, keyed
`milestone:{player}:{level}`.

**Why.** The stored level, not the level the old xp is worth, because a level is
never taken back (D-12): after a curve is made harsher, the xp before a result
can be worth less than the level held, and judging from it would pay a milestone
the player had passed already. The key makes the payment once a level whatever
happens to the column, and the range keeps the apply to the levels crossed, none
in nearly every result, rather than a key read for every milestone below the
level each time.

**Cost.** One column more in the read that locks the players. A player past a
milestone before it existed is not paid it: nobody is, before launch.

### D-62 — The own tank is predicted by the server's movement rule, from what two events carry

**Decided 2026-10-03** (plan item 70, Q-50: the owner brought the own tank's
prediction back).

**Decision.** The client moves its own tank by its own input at once, by a port
of the server's movement rule, and puts it right on every snapshot without a
jump. What the port needs and a snapshot does not carry comes in two events,
sent only to the player whose tank it is
([02 §4](../detailed-design/02-networking.md#4-the-snapshot)):

- `Motion` (type 5), in every frame while the tank lives: how many ticks the
  input the frame echoes has driven it, through the frame's tick, and its
  velocity after that tick.
- `MotionRule` (type 6), when the tank's acceleration or radius is not what the
  view was last told, and so in a view's first frame: both, as the server's own
  floats.

The rule ported is the tank's step in `Room`: velocity plus the input's
direction times the acceleration, times the friction, added to the position;
the map's edge; a maze's walls. A trajectory the server's own `Room` writes
pins it, bit for bit. **What is not predicted:** recoil, and every knock
(tanks, shapes, bullets). The server's velocity in the next frame brings them in.

**Reconciliation.** The client steps at the server's 25 Hz, and stamps each
step with the seq of the input that will carry it. A frame's tank is put
where the server says, with its velocity, at the step that matches the
server's tick: the first stamped with the echoed seq, plus the input's ticks
less one. The steps after it are replayed. What that moves the present by is
a correction, drawn away over about a tenth of a second, or taken at once
when it is too far to be one (a respawn, a resume).

**Why.** Events, not new header fields: no new protocol version, every golden
vector unchanged, and an older client steps over them. That is what an event's
byte length is for (02 §4). The price is two bytes of framing a frame.

The input's ticks, because the echo says which input, not where in its span
the server's tick fell. Inputs go at 10 Hz and ticks at 25, so an input drives
two or three ticks; a guess wrong by one is a tick of movement every frame,
a visible shiver.

The velocity sent, not worked out, because recoil and knocks are in it and
nothing else can tell the client.

Recoil not predicted, because when a tank fires is the server's: reload
ticks, volleys and their delays, drones kept, turrets' targets. Porting all of
that moves half the room into the client, for a push the next frame corrects.

**Cost.** About seven bytes a frame to a client whose tank lives, about 105 B/s
at 15 Hz: measured, 6.2 bytes a frame and 94 B/s a connection more (8 %), 150
bots back to back with the release before; and a few comparisons a view a frame
on the room thread, which did not move encoding's median. The client keeps two seconds of steps. A tank firing without
pause sits a few ticks of recoil ahead of the server's, a steady offset that
corrections absorb; a heavy class staggers late by a frame.

### D-63 — A season ends in three steps, each safe to repeat: its places, its gems, then its reset

**Decided 2026-10-03** (plan item 71 (a), Q-50: seasons for the rating boards).

**Decision.** The rating boards stay where they are: the ratings in `player`,
listed after ten rated matches (D-57). A season is a row in `season`, two
calendar months. When it ends, `worker`'s season job closes it
([04 §7](../detailed-design/04-platform-services.md#seasons-designed-2026-10-03-plan-item-71-a)):

1. Each board's listed players and their places are written to `season_place`,
   in one transaction with the next season's row.
2. Each is paid gems by place, keyed `season:{season}:{board}:{player}`, with an
   inbox item, and the season is marked paid.
3. Every rating is moved halfway back to 1 200, and every rated count is set
   to 0, in batches of 1 000 by id up to the highest id at the end. Each batch
   moves the season's progress in its own transaction.

Every worker looks each minute, and one takes the store's lock. A step that is
done says so in the season's row, so nothing is done twice whoever runs it.

**Why.** Places before the reset, because the reset destroys the standings, and
a past season's board must still be readable after it.

The places are written in one statement over the board's index and in one
transaction with the next season, so the standings are a single snapshot, and
there is never a moment with no open season.

Gems after the places, from `season_place` rather than from the live board.
That way a retry pays the same players the same amounts, whatever has been
played since.

The reset in batches with its progress in the season's row, not a column on
every player:
- One statement over every player would hold every row's lock while results
  wait.
- A batch's progress committed with the batch means a crash goes on where it
  stopped, and nobody is halved twice.

The reset stops at the highest id when the season ended, so an account made
since is the new season's already.

The ratings are kept, only halved, because matchmaking reads them. Starting
everyone at 1 200 would mismatch the strong against the new for a week.

**Cost.** A rated result applied after the places are written, but before its
player's batch, counts in neither season's ten: its rating change is halved
with the rest, and its count is reset. It is one result a player at most, at the
boundary.

Writing the places reads every listed player once, under the shared locks an
`INSERT … SELECT` takes. Results for those players wait for it: a second or
so at a hundred thousand listed, once in two months.

Team ratings are not reset: they have no board (Q-40).

### D-64 — An achievement is paid when a result carries its stat across the threshold

**Decided 2026-10-03** (plan item 71 (b), Q-50: achievements beyond the level
milestones).

**Decision.** An achievement is a threshold on one counter in `player_stat`,
with its gems, in a table in code (`persistence/Achievements`). `worker`,
applying a result, reads the player's stats in the locking read that already
reads their level. It pays every achievement the stat was under before the
result and is at or over after it, through the ledger's one path in the same
transaction, keyed `achievement:{player}:{id}`.

Whether an achievement is reached is not stored. It is the stat at or over the
threshold, since a stat only grows.

**Why.** Paying on a crossing, as D-61 does for milestones, keeps the work to
the thresholds crossed: none in nearly every result. Checking every
achievement's key on every result would be seventeen reads each time, for
nothing.

The stats are the ones a result already counts and writes. An achievement
needs no new counter, and cannot disagree with the stats a player sees.

A table in code, not content in the store, because `worker` pays by it and
`platform` lists it: two processes that must agree. The same release ships
both.

**Cost.** An achievement added after players have passed its threshold is not
paid to them by a result, since there is no crossing. Before launch nobody has.
After it, a new achievement ships with a one-off job that pays those already
past it, by the same key, so the job and a result can never both pay.

The locking read joins `player_stat`, and so locks its row too. The same
transaction updates that row anyway, so this changes no lock order.

### D-65 — A team's season place pays each member who played for it that season

**Decided 2026-10-04** (plan item 73, Q-51: a board of teams with seasons).

**Decision.** The board of teams is the player boards' kind (D-57): a virtual
column holding a team's rating once it has ten rated team matches, indexed by
the board's order. A season's first step also writes each listed team's place,
and its payees: each member, at the end, who played at least one of the team's
rated team matches in the season. Each payee is paid the place's gems by the
players' table. The reset halves every team's rating back to 1 200 and zeroes
its rated matches in one statement.

**Why.** The same place pays the same, whether alone or in a team, so neither
way of playing is the way to be paid. Played and present, because each guards
against its own abuse:
- **Belonging alone** would pay a player who joins a strong team the night a
  season ends. Up to thirty can, so the top team's place could be sold.
- **Having played alone** would pay one who left for a rival.

The rated team matches are already recorded per team (`match_team`, indexed by
team), so who played is a join, not a new counter.

One statement for the reset, not batches: teams are a small fraction of
players, so the lock it holds over them is short.

**Cost.** The payee read joins each listed team's members to its matches in the
season's window, once a season. A member who played and then left before the
end is not paid; one who joined late and played once is.

### D-66 — A day's goals are drawn, not stored, and paid as results meet them

**Decided 2026-10-04** (plan item 74, Q-51: more for players to earn).

**Decision.** A player's three goals for a UTC day are a fixed function of the
player and the day (SplitMix64 over both), drawn from a table in code: three
different kinds, each easy or hard. Only their progress is stored, a row per
goal per day. `worker`, applying a result, works out the day's three for each
player in it, adds what the result counts, and pays each goal that reaches its
target, and the set when the third does. All of this happens in the result's
transaction, under the player's lock, through the ledger's one path keyed by
day and goal. The day is the date of the result's end in UTC.

**Why.**
- **Drawn, not stored:** the list and the count agree without a draw being
  written, and without a nightly job to deal everyone new goals. A player who
  never plays that day costs nothing.
- **In the result's transaction:** the progress, the coins and the result are
  one commit, so a retry or redelivery counts and pays nothing twice (D-61 and
  D-64 do the same).
- **The result's day, not the clock's:** a redelivery an hour later counts for
  the day the match was played.

**Cost.**
- A change to the table changes everyone's draw that day, so a new pool ships
  at midnight.
- Progress toward a goal no longer drawn stays unused until retention deletes
  it after a week.
- Each result reads and upserts up to three rows per player, under a lock
  already held.

### D-67 — Money buys convenience and looks, never strength

**Decided 2026-10-04** (plan item 75, Q-51: the owner brought real money into
scope).

**Decision.** Gems are what money buys. Nothing sold for gems or money makes a
tank stronger: boosts (convenience), a season pass's premium track, and skins
(looks). Coins buy equipment and its levels, which are strength, so coins are
never sold for gems or money. Equipment is never sold except for coins earned
by play. The equipment cap of 25 % per stat (D-37) stands whatever is bought.

**Why.** A match is won by play, the promise the arena's design keeps (01 §3,
the cap). A player who pays and a player who does not meet on the same terms.
Players who think they are losing to spenders leave, and in a free-to-play
product the many who do not pay are what the few who do pay to play with.

**Cost.** The obvious sale, coins and so faster equipment, is not made. Revenue
must come from wanting to belong and to look the part: the pass, skins,
convenience.

### D-68 — A purchase is an order its provider confirms, granted once, and a refund is taken back

**Decided 2026-10-04** (plan item 75 (a); the owner: no third-party payment or
sign-in integration for now, the payment flow simulated).

**Decision.**
- **Order and confirm:** buying gems is an order made by `platform` (pending,
  the pack and its price) and confirmed by a payment provider: paid or
  declined, final, once. A provider's part is that one call. The provider
  built is simulated: the player's own call stands in for the provider's page
  and its notification, and makes the confirm a real provider's would.
- **Grant:** a paid order grants the pack's gems through the ledger's one path,
  keyed by the order. A player's first paid order pays its gems twice, keyed by
  the player.
- **Refund:** a refund (an operator's, as a provider's notification would)
  takes the order's gems and bonus back as far as the balance allows.
- **Debt:** what could not be taken back is a debt on the order, and a player
  in debt cannot order until an operator clears it.
- **Off unless named:** the simulated provider runs only where the operator
  names it (`BACKEND_PAYMENT_PROVIDER=simulated`). Unset, every payment route
  answers 503 `payments_off`, and nothing is sold.

**Why.**
- **Simulated, not left out:** the flow a real provider needs (an order, a
  confirmation from elsewhere, a grant once, a refund later) is built and
  tested end to end. Choosing a provider then adds its notification route in
  place of the simulated one, not a new economy around it.
- **Keyed by the order:** a confirm delivered twice, by a provider's retry or a
  player's double tap, grants once.
- **Debt, not a negative balance:** the ledger never goes below zero (06 §4),
  and a gem already spent cannot be taken back. Blocking the next order is the
  cheapest defence against buying, spending and refunding.

**Cost.**
- Nothing is really sold until a provider is chosen (Q-52).
- The simulated route must never run where players are: it grants gems to
  whoever asks. Off by default, it is switched on for development and drills,
  and a real provider replaces it.
- A player in debt for an honest reason needs support to clear it.

### D-69 — A season pass pays each tier as its points cross it, each track to its own mark, and its premium never pays coins

**Decided 2026-10-04** (plan item 75 (b); the economy's balance is Claude's to
choose, Q-48).

**Decision.**
- **Points from play**, counted in the result's transaction for the season
  being played when the result is applied, as the boards count it: 10 a result
  paid, 50 a daily goal it meets.
- **Paid on crossing:** a player's pass keeps its points and, for each track,
  the last tier paid. Whatever raises the points, or buys the premium track,
  pays every tier between that mark and the tier reached, and moves the mark,
  in one transaction under the player's lock. Coins and gems go through the
  ledger's one path, keyed by season, track, tier and player.
- **Premium** is bought with gems, for the season being played, once.
- **Never coins on the premium track**, and no tier gives strength: its
  rewards are gems and boosts, and later looks (D-67).

**Why.**
- **Paid on crossing, not claimed:** as achievements are (D-64), a reward is
  never left unclaimed when the season ends, and nothing is left for the
  season's close to do. The mark makes a boost, which the ledger does not key,
  once.
- **Goals carry most of the points:** the pass rewards coming back each day
  more than playing all day.
- **No coins for gems:** gems are sold for money, and coins buy equipment and
  its levels; a premium tier paying coins would sell strength.

**Cost.**
- A tier table changed in a season moves no mark: tiers already paid stay paid
  as they were. The table is changed between seasons.
- A result applied for the season about to close earns that season's points,
  paid at once, as the boards count it.

### D-70 — A tank's skin travels in its own ticket field, and is told by an event beside the tank's create

**Decided 2026-10-04** (plan item 75 (c)).

**Decision.**
- **Worn:** a skin is an item worn in a slot of its own, with no modifier.
- **Carried:** the number of the skin a player wears goes from `platform` to the
  arena in the ticket, in a field of its own beside the bonus. It is read when
  the bonus is (D-37), kept on the tank as the bonus is, and the same for the
  whole stay.
- **Told:** every client a tank is created for is told its skin by an event,
  `Skin`, written in the frame that carries the create, for a tank with a skin
  only.

**Why.**
- **An event, not a create field:** no new protocol version, every golden
  vector unchanged, and an older client steps over it (D-62's reasons). It costs
  4 bytes for each skinned tank a view meets, and nothing for any other.
- **A field of its own, not a pair in the bonus:** an arena or matcher a release
  behind ignores a ticket field it does not know, where it refuses a bonus pair
  it does not know, and with it the join. Deploys roll.

**Cost.**
- Tournament matches show no skins: their tickets are written by `worker`, which
  has no item table, as they carry no bonus (Q-16).
- A skin worn mid-stay shows from the next join, as equipment does.

### D-71 — Backups are proved by restoring them every week under live writes, copied off the site encrypted, and watched through MySQL

**Decided 2026-10-04** (plan item 76 (a); the owner's question, Q-51: "we
should have also backup approaches?").

**Decision.**
- **Proved weekly, automatically:** a timer on the backup machine restores the
  newest dump and the binlog copies onto a scratch server of its own. The checks
  hold while the primary keeps writing:
  - every table restored, and the migrations the source's;
  - nothing committed before the capture missing;
  - the ledger reconciled with every balance, coins and gems.
- **Recoverable to a moment:** a restore can stop at a given time, not only at
  the newest write, to undo a mistake made at a known minute.
- **Kept off the site:** after each dump, it and the binlog copies since are
  encrypted with a key kept off the machines and copied to a host elsewhere, by
  SSH. Where is the owner's choice (Q-53); until it is named, the copy is off.
- **Watched:** each step records its outcome in MySQL, `backup_run`. Every
  worker reports how long ago each kind last succeeded, and whether the last
  failed; the alerts read those.

**Why.**
- **A backup never restored is not a backup** (06 §10). The monthly drill was
  an operator's task, and the first three runs found silent failures. A timer
  does not forget, and checks that hold under writes let it run in production.
- **Off the site:** the backups are on the replica's machine, in the same place
  as the primary. A fire, a flood or a lost account takes all three machines.
- **Encrypted:** the copies are the whole database, accounts included, kept
  where the operator does not control the disks.
- **Through MySQL:** the processes' metrics are what the alerts read, and the
  worker already watches the ledger check the same way. A script that failed,
  or never ran, shows as an age that grows.

**Cost.**
- A scratch MySQL server on the backup machine, used one hour a week.
- A key that must be kept somewhere other than the machines, or the off-site
  copy cannot be read. It is written down in the runbook's first steps.
- The off-site host costs money, and is the owner's to choose (Q-53).

### D-72 — The tables' growth is watched against measured triggers, and a restore, not the purge, is what binds

**Decided 2026-10-04** (plan item 76 (b); the owner left the data work to
Claude's judgement, Q-51).

**Decision.**
- **No partitioning.** The tables that grow are pruned by retention's batched
  deletes. The documented plan to partition them could not have been followed
  (DOC-20), and the purge, measured, does not need it: 21 minutes a day at the
  design target, in the worst case, on the development VM.
- **A last-resort restore takes at most 4 hours.** Both database machines lost,
  or the data itself wrong, is when a restore from the backups is the way back.
  The weekly proof times every restore.
- **At 2 hours, physical backups.** When the proof's restore passes half the
  objective, the backups become physical copies by the clone plugin, with the
  binlog copies replayed after. Designed now (06 §9, §10), built at the trigger.
- **Watched:** the growing tables' rows, a retention run's time and the proof's
  restore time are reported by every worker, and the triggers are alerts.

**Why.**
- **Measured, not assumed.** The purge's rate, 11 600 rows a second for one
  player's results, puts the design target's day under the 30-minute trigger.
  A logical restore's rate, 24 000 rows a second, puts a year's ledger past any
  hour count that is a recovery.
- **The proof measures where it matters.** Production's disks are not this
  VM's (Q-3). The weekly proof runs there, every week, so the trigger fires on
  the real rate.
- **Four hours** is a morning: long enough for a restore no one else can do,
  short enough that players come back the same day.

**Cost.**
- On this VM the trigger would come within about 60 days of launch. If
  production is like it, physical backups are built in the first months.
- The ledger keeps every row for ever; its size is a cost in disk and in the
  copies off the site, not in correctness.

### D-73 — The Unity layer is thin scripts over the built core; what can be engine-free moves into the core first

**Decided 2026-10-04** (plan item 77; the owner's answer to Q-51: scripts for
the Unity tools, as many as can be).

**Decision.**
- **Into the core first:** what a Unity layer would otherwise compute is moved
  to the core and tested here: where each thing is drawn at a render tick, the
  sticks to an input, and the account kept across launches.
- **The package is thin:** a UPM package of scripts that pump the core, draw
  its scene, read touches and store the key. It takes the core as the built
  library, not as copied sources.
- **Compiled here against stubs:** the scripts are compiled against stubs of
  the Unity API they use, for the editor, iOS and Android, and nothing more is
  claimed for them.

**Why.**
- **What is tested here is what can be trusted here.** There is no Unity on
  this machine (§1); every line moved into the core is a line its tests and
  the drills hold.
- **The built library** keeps one source: the core the drills run is the core
  the app runs.
- **Stubs catch what they can:** a typo, a wrong type, the core misused. They
  cannot catch a wrong belief about Unity's own API, and the README says so.

**Cost.**
- The scripts have not been run. The owner's first opening of the package in
  the editor is their first test.
- The secure stores' native plugins are small, and unrun on a device.

### D-74 — A party's state is sent whole, with its version, and the client keeps the newest

**Decided 2026-10-04** (plan item 78, T-45: a member of a party of three was
left seeing two).

**Decision.**
- **A version a party:** 1 when it is made, one more on each change of its
  members or leader, written in the change's own watched transaction.
- **Carried by every state:** the answer to the member who asked, each push to
  a member, `GET /v1/party`. A state of no party that a change told names the
  party it ended, `was`, with that change's version.
- **The client keeps the newest:** a state is applied when it names another
  party than the last one applied, or a higher version of the same. One with
  no version, from a `platform` before this, is applied as it comes.

**Why.**
- **The paths cannot be ordered cheaply.** An answer comes back through the
  gateway's call to `platform`, a push through the store, and two changes at
  once are told by two request threads. Ordering them at the source would
  mean publishing inside the change's transaction, with each member's gateway
  looked up there too, and an answer could still be overtaken.
- **A version is local to the party:** read under the `WATCH` each change
  already holds, so no change waits on another party's.
- **Asking for the truth on every push** would make each change a round trip
  per member, and the answers to those could cross as well.

**Cost.**
- A field in the party's hash and in its state. A party made before the
  release reads as version 0 and is 1 at its next change.
- A state of a party left behind arriving after the state of the next one
  joined is still applied: it needs leaving and joining within a push's few
  milliseconds, and the next change or `GET /v1/party` puts it right.

### D-75 — The migrations are squashed into one baseline before the first launch

**Decided 2026-10-04** by the owner, on Claude's recommendation: the schema
complete for a first launch, its DDL and its first data in one place, not
thirty-four steps.

**Decision.**
- **Two migrations replace V1 to V34:** `V1__schema.sql`, every table as it
  stands, grouped by domain, each with what it holds; and `V2__seed.sql`, the
  rows a first launch needs: the failover epoch, the replica heartbeat and
  season 1.
- **Proved the same before the old ones went:** the two applied to an empty
  database and V1 to V34 to another, on a private MySQL server; every table's
  `SHOW CREATE TABLE` compared, and the seed rows. Two differences, on purpose:
  V17's three board indexes, which V20 replaced and said a later migration
  would drop, are not made (defect D-41: no query reads them, and
  each rating change wrote them); and `season_place.rated` is `INT UNSIGNED`,
  as wide as the rated counts it copies, not V27's `SMALLINT` (defect D-42).
- **The character set is named** on every table, `utf8mb4` with
  `utf8mb4_0900_ai_ci`, the server's default the migrations relied on: a
  team's name is unique by it, ignoring case and accents, whatever a server is
  configured with.
- **Forward-only again from V3:** an applied migration is never edited, as
  before (06 §8). The rule was set aside once, before any database that
  matters held them.

**Why.**
- **Nothing has launched.** The only databases that hold V1 to V34 are the
  development and test ones, made again by a migrate; after a launch a squash
  would mean a baseline per database and Flyway's history rewritten, so this
  is the last cheap moment.
- **One file reads as the schema.** The design doc (06 §3) shows each table
  whole; the migrations now do too, instead of a table's columns spread over
  as many as nine of them.
- **The backfills go:** V16 filled `player_day` from the match history there
  was; an empty database has none, and 06 §8 already rules that a backfill is
  a task, not a migration.

**Cost.**
- `backend_dev` and `backend_test` are dropped and migrated again: their
  history says V1 to V34, and Flyway refuses a history that differs from the
  files. The drills' data in `backend_dev` goes with it.
- The steps' reasons, each table's history, are in git (`git log` of the old
  files, to commit ce30aa7) and in the design docs, not in the tree.
- A test that migrated to V15 to check V16's backfill is removed with it.

### D-76 — A result under five seconds counts for nothing but its row and a rated match's rating; a walkover is no win

**Decided 2026-10-05** by the owner, on Claude's recommendation from the review
(plan item 79, defect D-45).

**Decision.**
- **Under five seconds of play** (`MatchResultRepository.COUNTED_FROM_SECONDS`,
  which `RewardRules`' minimum reads), a result keeps its `match_player` row and,
  in a rated mode, its rating change, and nothing else: no coins (as before), no
  stats, no achievement progress, no daily goal progress, no pass points, no
  `player_day`.
- **A walkover lasts no time**, so it is no win for stats, achievements or the
  daily goals, and it is not a rated match played (defect D-51). It is still
  recorded, and it still decides a tournament's match.

**Why.**
- **Farming.** Two accounts queueing for each other, one never joining, made a
  win for the other every round: a daily goal for wins, an achievement for wins,
  pass points, all without playing. Coins were already withheld for the same
  reason (04 §4); the rest was not.
- **A stay of a few seconds is not play.** Counting it moved the funnel's days
  played and the players' statistics with no play behind them.

**Cost.**
- A player whose opponent never comes gets the match, and the tournament round,
  and nothing toward goals or achievements for it. Before 2026-10-04 they did
  (defect T-44 records the drills that counted on it).

### D-77 — Everything the backend builds and runs with is in the repository, and in the release

**Decided 2026-10-05** by the owner (Q-56 and after): Java 21 only, MySQL 8.4
LTS; the JDK, Maven, MySQL and nginx packaged and deployed instead of installed
as operating-system packages; every component and binary needed to build and run
the backend committed; the client's toolchain not included. How it is done
is [09](../detailed-design/09-release-and-packaging.md).

**Decision.**
- **`vendor/` holds the binaries**, extracted and ready to use: the JDK (re-linked
  compressed, every module and tool, with `jmods/`), Maven, MySQL 8.4 (pruned,
  with the two libraries it takes from the system), nginx compiled for RHEL 9,
  and the sources nginx is compiled from. Plain git: every file is under GitHub's
  100 MB limit.
- **The release carries its own runtime** (made by `jlink`, about 60 MB), MySQL
  and nginx, and j-redis; units and scripts name them under `/opt/backend`.
- **Built for RHEL 9's glibc 2.34**, whatever the development machine: nginx in a
  Rocky Linux 9 container, and the release checked in UBI 9 with nothing installed.

**Why.**
- **The machines may have no network**, and an operating-system package is a
  version the vendor chooses and changes: the release now runs the versions it
  was tested with, on any RHEL 9.x.
- **One Java line**: a Java 8 build would be a second backend (Q-56).
- **MySQL 8.0 ended its support in April 2026**; 8.4 is supported to 2032.

**Cost.**
- About 0.5 GB more in the repository, and more in its history at each version
  bump.
- **Security fixes are ours to take**: the operating system's updates no longer
  reach the JDK, MySQL, nginx or the OpenSSL compiled into nginx. A fix is a new
  version in `vendor/` and a release; the runbook's routine says when to look.
- A build machine needs Docker only to compile nginx again, not to build the
  backend.
