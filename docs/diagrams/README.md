# Diagrams

This folder draws the backend and its client whole, in [Mermaid](https://mermaid.js.org/). It is for
engineers and operators who want a picture before, or beside, the design documents. There are 69
diagrams in seven files. Each diagram is drawn from the code, and each file links the design
documents that give the reasons. GitHub, GitLab and most editors render the diagrams in place, so
there is no image to keep in step by hand.

**How current they are.** The diagrams were drawn from the code as of 2026-10-04. They were redrawn
for the single public address and the front's nginx
([D-82](../architecture/03-decision-log.md#d-82--one-public-address-for-the-site-nginx-in-front-ending-tls-routing-match-traffic-by-name))
in 01 and 07 on 2026-10-19, and for the match path in 02, 03, 04 and 06 on 2026-11-01. On 2026-11-25
they were checked again against the code and redrawn where it had moved on: port 80 in clear and a
match through it
([D-115](../architecture/03-decision-log.md#d-115--a-players-build-may-connect-without-tls-and-the-site-serves-it-on-80-matches-included))
in 01, 02, 03, 04, 06 and 07; the promotion scripts' refusals and MySQL's handover
([D-95](../architecture/03-decision-log.md#d-95--a-promotion-refuses-an-old-primary-it-can-neither-rule-out-nor-fence-and-a-handover-closes-mysqls-last))
in 07; the core's certificate check (D-108) and the three client trees (D-111 to D-115) in 06.

## The files

| File | What it draws |
|---|---|
| [01 — System](01-system.md) | Who uses the system; the processes and stores, with their protocols and ports; the Maven modules and their dependencies; a match from a login to its rewards; high availability |
| [02 — Arena and wire](02-arena-and-wire.md) | A room's lifecycle, the tick, a join and a resume, snapshot encoding, the collision broadphase, a made match, draining, and every message on the match connection |
| [03 — Lobby and store](03-lobby-and-store.md) | The lobby connection and its auth, pushes across gateways, tickets, a room reserved for a made match, the store's failover, slow clients, and who writes and reads each family of keys |
| [04 — Platform](04-platform.md) | Accounts and guests, the public arena's seat, the queue and its confirm step, parties, teams, tournaments and their scheduler, payments, the season pass, the shop, and the admin API |
| [05 — Data and worker](05-data-and-worker.md) | All 36 tables with their keys, the result pipeline and one result's transaction, retention, a season's close, the ledger check, and the replica's heartbeat |
| [06 — Client](06-client.md) | The core's types, its two connections' lifecycles, join and resume, the own tank's prediction, the render clock, sign-in, the party rule, the Unity layer's wiring, and the three client trees and their ways in |
| [07 — Deploy and operations](07-deploy-and-operations.md) | The launch's one machine, the three machines, nginx's routes, a release, a rolling deploy, the store's and MySQL's failovers, the backups and their timers, and a restore to a moment |

## By kind

The same diagrams, grouped by what kind of picture each one is. The type after each name is
Mermaid's.

### Architecture and structure

- [System context](01-system.md#1-system-context) · flowchart
- [Containers](01-system.md#2-containers) · flowchart
- [Maven modules](01-system.md#3-maven-modules) · flowchart
- [Primaries, replicas and promotion](01-system.md#primaries-replicas-and-promotion) · flowchart
- [Leases](01-system.md#leases) · flowchart
- [Who writes and reads each key family](03-lobby-and-store.md#who-writes-and-reads-each-key-family) · two flowcharts
- [The core's structure](06-client.md#1-the-cores-structure) · four classDiagrams
- [The Unity layer's wiring](06-client.md#9-the-unity-layers-wiring) · flowchart
- [The three client trees and their ways in](06-client.md#10-the-three-client-trees-and-their-ways-in) · flowchart

### Schema

- [The tables](05-data-and-worker.md#the-tables) · four erDiagrams

### Deployment and operations

- [The launch's one machine](07-deploy-and-operations.md#the-launchs-one-machine) · flowchart
- [The three machines](07-deploy-and-operations.md#the-three-machines) · flowchart
- [nginx's routes](07-deploy-and-operations.md#nginxs-routes) · flowchart
- [The release](07-deploy-and-operations.md#the-release) · flowchart
- [A rolling deploy](07-deploy-and-operations.md#a-rolling-deploy) · flowchart
- [A store's failover](07-deploy-and-operations.md#a-stores-failover) · sequenceDiagram
- [MySQL's failover](07-deploy-and-operations.md#mysqls-failover) · sequenceDiagram
- [The backups](07-deploy-and-operations.md#the-backups) · flowchart
- [A restore to a moment](07-deploy-and-operations.md#a-restore-to-a-moment) · flowchart

### Sequences: who calls whom, in order

- [From a login to the rewards](01-system.md#4-from-a-login-to-the-rewards) · sequenceDiagram
- [Join, with the ticket claim and the Welcome](02-arena-and-wire.md#join-with-the-ticket-claim-and-the-welcome) · sequenceDiagram
- [Resume after a lost connection](02-arena-and-wire.md#resume-after-a-lost-connection) · sequenceDiagram
- [A made match, from grant to published result](02-arena-and-wire.md#a-made-match-from-grant-to-published-result) · sequenceDiagram
- [Lobby connect and auth](03-lobby-and-store.md#lobby-connect-and-auth) · sequenceDiagram
- [Push routing across gateways](03-lobby-and-store.md#push-routing-across-gateways) · sequenceDiagram
- [Ticket issue and claim](03-lobby-and-store.md#ticket-issue-and-claim) · sequenceDiagram
- [Store failover, the subscriber following the primary](03-lobby-and-store.md#store-failover-the-subscriber-following-the-primary) · sequenceDiagram
- [Register and log in](04-platform.md#register-and-log-in) · sequenceDiagram
- [Guests and the upgrade](04-platform.md#guests-and-the-upgrade) · sequenceDiagram
- [A seat in the public arena](04-platform.md#a-seat-in-the-public-arena) · sequenceDiagram
- [The queue and the confirm step: a round](04-platform.md#the-queue-and-the-confirm-step-a-round) · sequenceDiagram
- [A party, its versions](04-platform.md#a-party-its-versions) · sequenceDiagram
- [A simulated payment order: the calls](04-platform.md#a-simulated-payment-order-the-calls) · sequenceDiagram
- [An operator's ban, kick and notice](04-platform.md#an-operators-ban-kick-and-notice) · sequenceDiagram
- [The result pipeline](05-data-and-worker.md#the-result-pipeline) · sequenceDiagram
- [The replica's heartbeat and lag](05-data-and-worker.md#the-replicas-heartbeat-and-lag) · sequenceDiagram
- [Join and resume, from the client's side](06-client.md#4-join-and-resume-from-the-clients-side) · sequenceDiagram

### States and lifecycles

- [Room lifecycle](02-arena-and-wire.md#room-lifecycle) · stateDiagram-v2
- [Slow-client backpressure](03-lobby-and-store.md#slow-client-backpressure) · stateDiagram-v2
- [The queue and the confirm step: a player's record](04-platform.md#the-queue-and-the-confirm-step-a-players-record) · stateDiagram-v2
- [A tournament](04-platform.md#a-tournament) · stateDiagram-v2
- [A tournament match](04-platform.md#a-tournament-match) · stateDiagram-v2
- [A simulated payment order: its states](04-platform.md#a-simulated-payment-order-its-states) · stateDiagram-v2
- [The season's close](05-data-and-worker.md#the-seasons-close) · stateDiagram-v2
- [MatchConnection's lifecycle](06-client.md#2-matchconnections-lifecycle) · stateDiagram-v2
- [LobbyClient's lifecycle](06-client.md#3-lobbyclients-lifecycle) · stateDiagram-v2

### Algorithms and flows

- [The tick loop](02-arena-and-wire.md#the-tick-loop) · flowchart
- [Snapshot encoding](02-arena-and-wire.md#snapshot-encoding) · flowchart
- [Collision broadphase](02-arena-and-wire.md#collision-broadphase) · flowchart
- [Draining an arena](02-arena-and-wire.md#draining-an-arena) · flowchart
- [Room reservation for a made match](03-lobby-and-store.md#room-reservation-for-a-made-match) · flowchart
- [A team action](04-platform.md#a-team-action) · flowchart
- [The scheduler's round](04-platform.md#the-schedulers-round) · flowchart
- [The season pass crossing tiers](04-platform.md#the-season-pass-crossing-tiers) · flowchart
- [Buying, wearing and boosting](04-platform.md#buying-wearing-and-boosting) · flowchart
- [One result's transaction](05-data-and-worker.md#one-results-transaction) · flowchart
- [Retention](05-data-and-worker.md#retention) · flowchart
- [The ledger check](05-data-and-worker.md#the-ledger-check) · flowchart
- [The own tank: prediction and reconciliation](06-client.md#5-the-own-tank-prediction-and-reconciliation) · flowchart
- [The render clock and the scene](06-client.md#6-the-render-clock-and-the-scene) · flowchart
- [AccountKeeper's sign-in](06-client.md#7-accountkeepers-sign-in) · flowchart
- [PartyState's rule](06-client.md#8-partystates-rule) · flowchart

## Where the words come from

The names are the [glossary](../glossary.md)'s and the code's. Each file opens with the design
documents it follows. Where a diagram and a design document disagree, the code decides, and the
disagreement is a defect for [the register](../defects.md) (DOC).

## Checking them

Every block, 67 at the time, was rendered with the Mermaid CLI (`mmdc` 11.16.0) after the review's
fixes, on 2026-10-04. The blocks added or redrawn since were not rendered by that tool here. To
render one file's diagrams to SVG:

```bash
npx -y @mermaid-js/mermaid-cli -i docs/diagrams/04-platform.md -o /tmp/platform.md
```

A block that does not parse fails the command, with the line where it stopped.
