#56 – Database Sharding for MMORPG and Mobile Titles: Scaling Player Data Across Multiple Database Nodes
administrator
administrator
Verified user account
02/09/2026 06:31
•
General Discussion
Database Sharding for MMORPG and Mobile Titles: Scaling Player Data Across Multiple Database Nodes
Introduction
A small online title can often store all persistent player data inside one relational database.

The architecture may begin as:

Client
|
v
Match Server
|
v
Primary Database
For an early-stage Mobile Title or MMORPG, this design is simple and effective.

The database may contain:

player_account
player_profile
player_inventory
player_wallet
player_quests
player_mail
guild_members
transactions
As the title grows, however, the database can become one of the most difficult components to scale.

Imagine millions of registered players continuously performing actions such as:

Login
Load character
Claim reward
Upgrade equipment
Purchase items
Save progression
Open mail
Join events
Trade resources
A single database server eventually faces limits in:

CPU;

memory;

disk I/O;

storage capacity;

transaction throughput;

connection count;

index size;

backup duration;

recovery time.

Vertical scaling can postpone the problem.

A studio may move from:

16 CPU cores
64 GB RAM
to:

64 CPU cores
512 GB RAM
but vertical scaling is not unlimited.

At some point, the Realtime Backend may need to distribute data across multiple database nodes.

This process is commonly called database sharding.

Instead of storing every player on one database:

All Players
|
v
Database 01
the Realtime Backend distributes players:

Players 1–1,000,000
|
v
Shard 01

Players 1,000,001–2,000,000
|
v
Shard 02

Players 2,000,001–3,000,000
|
v
Shard 03
Sharding can dramatically increase storage and write capacity.

But it also makes Multiplayer development much more complex.

Developers must decide:

which shard owns each player;

how services locate the correct shard;

how guild and marketplace data are handled;

what happens when one shard becomes overloaded;

how players are moved between shards;

how global rankings query data from multiple databases;

how backups and failover work.

This article explains how database sharding works in practical Match Server architecture, the major strategies used for player data, common mistakes, and how developers can identify sharding logic when analyzing Multiplayer source Code.

What Is Database Sharding?
Database sharding is horizontal partitioning across multiple independent database nodes.

Instead of placing every row in one database:

Database
├── Player 1001
├── Player 1002
├── Player 1003
├── Player 1004
└── ...
the system separates them.

For example:

Shard A
├── Player 1001
├── Player 1004
└── Player 1007

Shard B
├── Player 1002
├── Player 1005
└── Player 1008

Shard C
├── Player 1003
├── Player 1006
└── Player 1009
Each shard contains only part of the total dataset.

The Realtime Backend determines which database should receive each query.

This decision is based on a shard key.

Typical shard keys include:

player_id
account_id
server_id
region_id
guild_id
The quality of this shard key has a major impact on scalability and operational complexity.

Why Realtime Backends Need Sharding
Titles often generate extremely write-heavy workloads.

A player may update persistent state many times during one session.

Examples include:

Experience gain
Currency changes
Inventory changes
Quest progress
Hero upgrades
Mail claims
Event progress
Guild contributions
If millions of players generate these updates, one database may become a bottleneck.

Sharding allows workload to be distributed.

For example:

Shard 01 → 12,000 writes/sec
Shard 02 → 11,500 writes/sec
Shard 03 → 12,300 writes/sec
Shard 04 → 11,800 writes/sec
Instead of one database processing:

47,600 writes/sec
four nodes share the load.

The Realtime Backend also gains additional storage capacity because each shard stores only part of the total player population.

Sharding vs Replication
Sharding and replication solve different problems.

Replication creates multiple copies of the same data.

Example:

Primary Database
|
+--> Replica A
+--> Replica B
This is useful for:

high availability;

read scaling;

backup architecture;

failover.

Sharding distributes different data across different nodes.

Example:

Shard 01 → Players A
Shard 02 → Players B
Shard 03 → Players C
Large Realtime Backend systems may use both.

For example:

Shard 01 Primary
├── Replica 01A
└── Replica 01B

Shard 02 Primary
├── Replica 02A
└── Replica 02B
This provides horizontal partitioning together with redundancy.

Player ID Modulo Sharding
One of the simplest strategies is modulo sharding.

Suppose there are four shards.

The backend calculates:

shard = player_id % 4
For example:

Player 1001 → Shard 1
Player 1002 → Shard 2
Player 1003 → Shard 3
Player 1004 → Shard 0
The advantage is simplicity.

The routing logic is extremely fast.

No separate lookup table is required.

However, modulo sharding has a serious weakness.

If the number of shards changes from:

4
to:

5
many players suddenly map to different shards.

This can require massive data migration.

Therefore, fixed modulo schemes should be used carefully.

Range-Based Sharding
Another strategy assigns ID ranges.

Example:

Shard 01:
player_id 1 – 10,000,000

Shard 02:
player_id 10,000,001 – 20,000,000

Shard 03:
player_id 20,000,001 – 30,000,000
Routing becomes:

Player 15,700,000
|
v
Shard 02
This is easy to understand and operationally convenient.

New shards can be created for future players.

For example:

Shard 04:
player_id 30,000,001 – 40,000,000
The disadvantage is that traffic may not be evenly distributed.

Older players may become inactive while the newest shard contains most active users.

This can create:

Shard 01 → 10% load
Shard 02 → 20% load
Shard 03 → 70% load
even if row counts are similar.

For titles, player activity matters more than raw account count.

Hash-Based Sharding
Hash-based sharding distributes IDs using a hash function.

Conceptually:

hash(player_id)
|
v
Shard Selection
This generally spreads players more evenly.

For example:

Player 1001 → Shard C
Player 1002 → Shard A
Player 1003 → Shard D
Player 1004 → Shard B
This is useful when the Realtime Backend wants to avoid hot ranges.

However, changing the shard count remains difficult if the mapping directly depends on the number of nodes.

Consistent hashing or a separate shard mapping layer can provide more flexibility.

Directory-Based Sharding
A more flexible architecture stores a mapping:

player_id → shard_id
For example:

1001 → shard_03
1002 → shard_01
1003 → shard_05
1004 → shard_03
A routing service or metadata database maintains this information.

The architecture becomes:

Match Server
|
v
Shard Directory
|
| player 1001 = shard_03
v
Shard 03
The major advantage is flexibility.

A specific player can be migrated from:

Shard 03
to:

Shard 07
by moving the data and updating the directory.

The disadvantage is another dependency.

The routing directory must be:

fast;

highly available;

strongly controlled;

correctly cached.

If the directory fails, the Realtime Backend may no longer know where player data lives.

Sharding by Match Server or Realm
Many MMORPG architectures naturally divide players into realms or worlds.

For example:

Realm 1 → Database Shard 1
Realm 2 → Database Shard 2
Realm 3 → Database Shard 3
This can be very effective because players already belong to logical server groups.

A traditional MMORPG may have:

S1
S2
S3
S4
Each server maintains its own player population.

The database architecture can mirror this structure.

For example:

Match Server S1
|
v
Database S1

Match Server S2
|
v
Database S2
This provides strong isolation.

If S2 becomes overloaded, S1 is not necessarily affected.

However, cross-server play becomes harder.

Features such as:

Cross-server PvP
Global ranking
Inter-server marketplace
Global guild wars
must query or synchronize data across multiple shards.

Regional Sharding
Global titles may distribute data geographically.

Example:

SEA Players
|
v
Singapore Database

European Players
|
v
Frankfurt Database

North American Players
|
v
Virginia Database
Regional sharding provides several benefits.

Player requests travel shorter distances.

Data can remain closer to regional Match Server clusters.

Infrastructure can scale independently.

However, cross-region features become more complex.

A player moving from one region to another may require data migration.

Data residency requirements may also influence the architecture.

Choosing a Shard Key
The shard key should distribute workload while keeping related data together.

For player-centric Multiplayer development, a common choice is:

player_id
All player-owned tables can then use the same routing rule:

player_profile
player_wallet
player_inventory
player_quests
player_mail
For example:

Player 1001
|
v
Shard 7

Shard 7 contains:

- profile
- wallet
- inventory
- quests
- mail
  This reduces cross-shard transactions.

A poor shard design might place:

Wallet → Shard A
Inventory → Shard B
Quest Data → Shard C
for the same player.

Every play operation would then require coordination across multiple databases.

Keeping related transactional state on the same shard is usually much easier.

Cross-Shard Transactions
One of the hardest problems appears when one operation modifies multiple shards.

Consider player trading.

Player A → Shard 01
Player B → Shard 07
The trade needs to:

Remove item from Player A
Add item to Player B
Transfer currency
Record transaction
A single local database transaction cannot automatically cover both independent shards.

The backend needs a distributed workflow.

Possible strategies include:

distributed transactions;

saga patterns;

escrow systems;

asynchronous settlement;

central marketplace services.

For titles, a common practical solution is to avoid direct cross-shard atomic operations where possible.

A marketplace may become its own service with its own authoritative database.

Separating Global Data from Player Data
Not every table should be sharded by player.

Global systems may include:

Product configuration
Marketplace
Guild Directory
Global Ranking
Announcements
Payment Records
Server Metadata
A Realtime Backend may use different databases for different domains.

Example:

Player Shards
├── Player DB 01
├── Player DB 02
├── Player DB 03
└── Player DB 04

Global Services
├── Guild Database
├── Marketplace Database
├── Ranking Database
└── Payment Database
This is often more practical than forcing every domain into one sharding strategy.

On the forum, developers examining Multiplayer source Code should therefore identify database ownership by service rather than assuming one database contains the entire title.

Guild Data and Sharding
Guild systems create special challenges because many players share one object.

Suppose guild members belong to several player shards:

Guild 500

Player A → Shard 1
Player B → Shard 2
Player C → Shard 3
Where should the guild itself live?

One strategy is to create a dedicated Guild Database.

Guild Service
|
v
Guild Database
Player shards may store only:

guild_id
The Guild Service manages:

Guild Name
Members
Roles
Treasury
Research
Guild Events
This avoids copying complete guild state into every player shard.

It also provides one authoritative location for guild operations.

Global Rankings
Leaderboards are another feature that should not query every shard for every player request.

Bad architecture:

Player opens ranking
|
v
Query Shard 1
Query Shard 2
Query Shard 3
Query Shard 4
Query Shard 5
...
This becomes slow and expensive.

A better architecture extracts ranking updates into a specialized system.

For example:

Player Shards
|
| Score Updated Events
v
Ranking Service
|
v
Redis / Ranking Database
The ranking service maintains a global projection.

The Client queries this system directly through the Realtime Backend.

This separates transaction storage from leaderboard workloads.

Shard Routing Layer
Every service accessing player data needs a way to find the correct shard.

A common architecture is:

Request
|
v
Player Service
|
v
Shard Router
|
+--> Shard 01
+--> Shard 02
+--> Shard 03
The routing logic should ideally be centralized inside a shared library or service.

Bad design:

Inventory Service implements its own routing
Quest Service implements different routing
Mail Service implements another routing
If those implementations disagree, data corruption can occur.

The shard routing algorithm should have one authoritative definition.

Connection Pools Per Shard
Sharding also affects database connection pooling.

Suppose one Match Server can access:

16 shards
If it creates:

20 connections per shard
the process may open:

16 × 20 = 320 connections
One hundred application instances could theoretically create:

32,000 connections
across the database cluster.

This means sharding and connection pool design must be considered together.

Possible optimizations include:

smaller per-shard pools;

lazy connection creation;

service-local shard access;

database proxies;

routing requests closer to shard ownership.

The previous connection pooling concepts remain important after sharding; they simply operate across more database nodes.

Hot Shards
Even a theoretically balanced sharding algorithm can produce uneven real workloads.

Suppose one shard contains players from a popular region or event.

Metrics might show:

Shard 01 CPU: 35%
Shard 02 CPU: 41%
Shard 03 CPU: 92%
Shard 04 CPU: 37%
Shard 03 is a hot shard.

Possible causes include:

High-value guilds
Popular marketplace sellers
Active whales
Event concentration
Regional traffic spikes
Uneven account age
Monitoring should therefore measure:

queries per shard;

writes per shard;

CPU;

storage I/O;

connection count;

transaction latency;

table size.

Balancing row count alone is not enough.

Resharding
Eventually, the studio may need to change shard boundaries.

This is called resharding.

Suppose:

Shard 01 contains 20 million players
and becomes too large.

The team may split it:

Shard 01A
Shard 01B
The migration process might be:

Identify players to move
|
v
Copy historical data
|
v
Synchronize new writes
|
v
Pause or lock player briefly
|
v
Copy final changes
|
v
Update shard mapping
|
v
Route traffic to new shard
Live resharding is complex because players may continue changing data while migration occurs.

A poorly designed process can lose or duplicate updates.

For this reason, studios should consider future shard expansion before the title reaches database limits.

Player Migration Between Servers
Many titles allow server transfers.

Suppose:

Player 1001
Realm 10
Shard 10
moves to:

Realm 25
Shard 25
The migration may need to copy:

Profile
Inventory
Wallet
Heroes
Quests
Mail
Achievements
Purchase Entitlements
The system must also handle relationships such as:

Guild membership
Friends
Marketplace listings
Leaderboard state
A robust transfer workflow often requires temporary account locking.

Conceptually:

Mark Player MIGRATING
|
v
Stop new state-changing requests
|
v
Export data
|
v
Import into target shard
|
v
Validate
|
v
Update shard directory
|
v
Re-enable account
Server transfer functionality is therefore closely related to sharding architecture.

Backups
One benefit of sharding is that backups can be smaller per database.

Instead of backing up:

10 TB database
one operation may handle:

Shard 1 → 2 TB
Shard 2 → 2 TB
Shard 3 → 2 TB
Shard 4 → 2 TB
Shard 5 → 2 TB
These backups can potentially run in parallel.

However, restoring the complete Realtime Backend becomes operationally more complex.

Teams must know:

Which backup belongs to which shard?
What timestamp does each restore represent?
How is shard metadata restored?
How are global services synchronized?
A backup is useful only if the recovery procedure is tested.

High Availability Per Shard
Each shard can become its own failure domain.

For example:

Shard 01
├── Primary
└── Replica

Shard 02
├── Primary
└── Replica
If Shard 02 fails:

Players on Shard 01 → Normal
Players on Shard 02 → Affected
This is better than losing the entire player population.

However, localized failures may be confusing for operations teams because only certain players report problems.

Monitoring must clearly identify which shard each failed request belongs to.

Monitoring Sharded Databases
A good dashboard should show both cluster-wide and per-shard metrics.

Important metrics include:

Query latency by shard
Transaction latency
CPU utilization
Disk IOPS
Storage capacity
Replication lag
Connection count
Lock wait time
Deadlocks
Error rate
Slow query count
Player count
Active player count
Averages can hide problems.

Suppose overall database CPU is:

55%
but one shard is:

98%
Players on that shard may experience severe latency while overall infrastructure appears healthy.

Per-shard observability is mandatory.

Security and Data Isolation
Sharding should not weaken backend authorization.

The Client should never be allowed to specify:

database_shard = 7
and directly choose where the request runs.

The server should derive the shard from trusted account information.

For example:

Authenticated Player ID
|
v
Trusted Shard Router
|
v
Database
Otherwise, malicious clients might try to manipulate routing and access unrelated data.

Database credentials can also be separated by service or shard where operationally appropriate.

How to Analyze This in Multiplayer source Code
When reviewing Multiplayer source Code, search for terms such as:

Shard
ShardManager
DBRouter
DatabaseRouter
ServerId
RealmId
ZoneId
PlayerDB
DataSourceManager
DBGroup
Partition
Inspect configuration files.

You may find structures like:

db_01:
host: 10.0.1.20

db_02:
host: 10.0.2.20

db_03:
host: 10.0.3.20
Then search for routing logic.

For example:

getShard(playerId)
or:

database = serverIdToDatabase(serverId)
Trace one player request:

Login
|
v
Resolve Player ID
|
v
Resolve Shard
|
v
Open DB Connection
|
v
Load Player Data
Then ask:

Is the shard mapping fixed or dynamic?

What field is used as the shard key?

Can shards be added?

Can players move between shards?

Are guilds stored separately?

How are global rankings built?

Are cross-shard operations supported?

What happens if one shard is offline?

Is shard ID trusted from the client?

Are database connection pools configured per shard?

When examining projects on the forum, this can help distinguish a simple multi-database configuration from a genuinely scalable sharding architecture.

Common Mistakes
Sharding Too Early
Sharding introduces major complexity.

If one properly optimized database can handle the workload, sharding may not yet be necessary.

Sharding Too Late
Waiting until the primary database is already at its limit makes migration more dangerous.

Poor Shard Key Selection
An uneven shard key creates hot nodes.

Cross-Shard Transactions Everywhere
Frequent multi-shard operations can erase many of the benefits of sharding.

Hardcoding Shard Count
A system built permanently around:

player_id % 4
may become difficult to expand.

Ignoring Global Features
Guilds, rankings, marketplaces, and cross-server events require special architecture.

No Resharding Plan
Data distribution eventually changes.

The infrastructure should have a migration strategy.

Monitoring Only Cluster Averages
One overloaded shard can affect thousands of players while overall metrics remain normal.

Uncontrolled Connection Pools
Many shards multiplied by many Match Server instances can create enormous connection counts.

Best Practices
Start with a clear understanding of why sharding is required.

Optimize:

Queries
Indexes
Caching
Read Replicas
Connection Pools
before introducing unnecessary architectural complexity.

Choose a shard key that keeps strongly related player data together.

Separate global systems when appropriate.

Use a centralized and deterministic routing mechanism.

Design shard metadata so new nodes can be added safely.

Avoid cross-shard transactions when a service boundary can solve the problem more cleanly.

Plan player migration and resharding before the database reaches critical capacity.

Monitor each shard independently.

Use replication and backups per shard.

Test shard failures.

Consider connection pool multiplication across all Match Server instances.

Keep shard identity inside trusted backend infrastructure.

For teams studying scalable Multiplayer development architecture on the forum, sharding should be evaluated as part of the complete data model, not simply as a method of creating more database servers.

Conclusion
Database sharding allows an MMORPG or Mobile Realtime backend to move beyond the limits of one database node.

Instead of forcing every player and every transaction through:

One Database
the Realtime Backend distributes workload:

Shard 01
Shard 02
Shard 03
Shard 04
...
This can increase:

storage capacity;

write throughput;

failure isolation;

operational scalability.

But the tradeoff is complexity.

A sharded architecture must solve:

Shard Routing
Cross-Shard Operations
Guild Data
Global Rankings
Player Migration
Resharding
Connection Management
Monitoring
Backup
Failover
The most important design decision is usually not how many shards exist.

It is how data ownership is defined.

If all important player data can remain within one predictable shard, the architecture remains manageable.

If every play action must coordinate across many shards, the system becomes expensive and fragile.

When analyzing Multiplayer source Code, developers should therefore examine how player IDs, realm IDs, routing rules, and database ownership interact.

A scalable Match Server does not simply connect to more databases.

It must know exactly which database owns every piece of authoritative simulation state, how that ownership changes over time, and how the rest of the Realtime Backend continues operating when the number of players grows far beyond the capacity of a single node.

For developers researching large-scale Multiplayer development and backend architecture, the forum can be a useful place to compare these concepts with real project structures and identify whether a codebase was designed for single-database deployment or long-term horizontal scaling.
