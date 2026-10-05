#24 – Database Sharding for MMORPG and Mobile Titles: Player Partitioning, Cross-Shard Queries and Data Migration
administrator
administrator
Verified user account
16/08/2026 06:59
•
General Discussion
Database Sharding for MMORPG and Mobile Titles: Player Partitioning, Cross-Shard Queries and Data Migration
Introduction
A database architecture that works perfectly for 20,000 players may become a serious bottleneck when an online title grows to millions of accounts.

In many MMORPG, Mobile Title, and large Multiplayer systems, the database eventually has to handle enormous amounts of persistent data:

Accounts
Characters
Inventories
Equipment
Guilds
Mail
Quests
Achievements
Currencies
Friends
Marketplace transactions
Event progress
Battle history
Purchase records
At smaller scale, a single relational database cluster may be enough.

As traffic grows, studios can improve capacity through:

Index optimization
Query optimization
Caching
Read replicas
Connection pooling
Hardware upgrades
Table partitioning
But eventually a database may reach limits related to storage, write throughput, connection count, maintenance windows, replication overhead, or operational risk.

At that point, database sharding becomes an important scaling technique.

Sharding divides data across multiple independent database groups instead of forcing every player to use the same database.

Conceptually:

Players
|
v
Realtime Backend
|
v
Shard Router
|
+--> Database Shard 1
|
+--> Database Shard 2
|
+--> Database Shard 3
|
+--> Database Shard 4
Each shard stores only part of the total dataset.

This can dramatically increase overall database capacity because writes and storage are distributed across multiple systems.

However, sharding is not a free performance upgrade.

Once data is distributed, previously simple operations become more difficult:

Cross-player queries
Global leaderboards
Guild membership
Friends across shards
Marketplace searches
Data migrations
Rebalancing
Transactions
Operational debugging
From a practical Multiplayer development perspective, database sharding should therefore be introduced only when the scale and workload justify the additional complexity.

When analyzing Multiplayer source Code, developers should understand how player IDs map to database servers, which tables are shard-local, which data is global, and how cross-shard systems communicate.

For developers studying backend projects on the forum, shard routing logic can reveal a great deal about the original Match Server architecture and expected production scale.

What Is Database Sharding?
Database sharding is a form of horizontal data distribution.

Instead of keeping every player record inside one logical database:

Single Database

Player 1
Player 2
Player 3
Player 4
...
Player 10,000,000
the system distributes players across several database shards.

Example:

Shard A
Player 1
Player 5
Player 9
...

Shard B
Player 2
Player 6
Player 10
...

Shard C
Player 3
Player 7
Player 11
...

Shard D
Player 4
Player 8
Player 12
...
Each shard may itself be a highly available database cluster.

For example:

Shard 1
|
+--> Primary
+--> Replica
+--> Replica

Shard 2
|
+--> Primary
+--> Replica
+--> Replica
This distinction is important.

Sharding distributes datasets.

Replication creates additional copies of a dataset.

They solve different problems.

A production title frequently uses both.

Why Online Titles Eventually Need Sharding
Large titles generate unusually high write volumes.

A typical player session may modify:

Character experience
Inventory
Quest state
Currencies
Equipment
Guild contribution
Daily missions
Energy
Event progress
Mail
Battle results
Multiply these operations by hundreds of thousands of concurrent players and database traffic becomes significant.

Even if Redis absorbs many reads, persistent changes still need to reach durable storage.

A single primary database may eventually become a bottleneck.

For example:

200,000 concurrent players
|
v
Multiple Match Servers
|
v
One Database Primary
Application servers can scale horizontally:

Backend 1
Backend 2
Backend 3
...
Backend 50
but all writes still converge on:

One Database Primary
This produces a scaling imbalance.

Sharding allows writes to be distributed.

Realtime Backend
|
+--> Shard 1
+--> Shard 2
+--> Shard 3
+--> Shard 4
Now no individual primary database needs to process the entire player population.

Sharding vs Table Partitioning
Developers sometimes confuse sharding with database table partitioning.

Table Partitioning
Partitioning generally happens inside one database system.

For example:

battle_log

Partition 2026_01
Partition 2026_02
Partition 2026_03
The database engine still manages the partitions as part of the same logical system.

Sharding
Sharding spreads data across independent databases.

Shard A Database Server
Shard B Database Server
Shard C Database Server
The application or database middleware usually needs to know where data is located.

Partitioning can simplify large tables.

Sharding increases total distributed capacity.

Large Realtime Backend systems may use both.

Choosing a Shard Key
The most important design decision in sharding is the shard key.

The shard key determines where data lives.

For player-centric titles, a common choice is:

player_id
For example:

shard_id = player_id % shard_count
With four shards:

Player 1001 -> Shard 1
Player 1002 -> Shard 2
Player 1003 -> Shard 3
Player 1004 -> Shard 0
This approach distributes player IDs fairly evenly if IDs are sufficiently uniform.

However, simple modulo routing has an important problem.

If the number of shards changes:

4 shards -> 5 shards
the routing result for many players changes.

That can require moving enormous amounts of data.

For this reason, production systems may use alternatives such as:

Fixed logical shard ranges
Virtual shards
Consistent hashing
Shard mapping tables
Directory-based routing
The choice significantly affects future scaling.

Range-Based Player Sharding
One simple strategy is assigning player ID ranges.

Example:

Shard 1:
Player IDs 1–10,000,000

Shard 2:
10,000,001–20,000,000

Shard 3:
20,000,001–30,000,000
Routing is straightforward.

player_id = 17,405,222
|
v
Shard 2
This approach is easy to understand and debug.

However, newer shards can become much hotter than older ones if all new players are assigned sequentially.

For example:

Shard 1 = mostly inactive historical accounts
Shard 5 = mostly new active players
The storage distribution may appear balanced while traffic distribution is not.

This demonstrates an important principle:

A good shard strategy must distribute workload, not just row count.

Hash-Based Sharding
Hash-based sharding distributes players using a hash of the shard key.

Conceptually:

hash(player_id)
|
v
Shard selection
This generally provides a more even distribution.

Example:

Player 1001 -> Shard 3
Player 1002 -> Shard 1
Player 1003 -> Shard 4
Player 1004 -> Shard 2
The disadvantage is that related players do not naturally live together.

A guild containing 100 players may have members spread across many shards.

This creates challenges for social and guild systems.

Server-Based Sharding
Many MMORPG and Mobile Systems already have a concept of logical match servers.

For example:

Server 1
Server 2
Server 3
...
Server 500
A natural database strategy is:

Server 1–20 -> Database Group A
Server 21–40 -> Database Group B
Server 41–60 -> Database Group C
This architecture can simplify player data because most interactions occur inside the same match server.

For older MMORPG architectures, you may even see:

One Match Server
|
v
One Player Database
repeated many times.

This design works well when server boundaries are strong.

But modern titles increasingly support:

Cross-server PvP
Cross-server guilds
Global chat
Global marketplace
Server merges
Cross-server matchmaking
Those features weaken the benefits of strict server-local data.

Shard Routing Architecture
Once sharding exists, the application must determine where a player belongs.

One architecture is:

Client
|
v
API Gateway
|
v
Realtime Backend
|
v
Shard Router
|
+--> DB Shard A
+--> DB Shard B
+--> DB Shard C
The router might use:

player_id
account_id
server_id
region_id
to choose the correct shard.

Pseudo logic:

shard = shardMap.getShard(playerId)

connection = shardPool.getConnection(shard)

player = connection.loadPlayer(playerId)
The routing logic should normally be centralized inside an infrastructure layer rather than duplicated throughout match code.

Bad architecture:

InventoryService calculates shard
GuildService calculates shard differently
MailService uses another rule
PaymentService hardcodes database host
Eventually those implementations diverge.

A better design provides one authoritative shard-routing mechanism.

Directory-Based Sharding
One flexible strategy is a shard directory.

Example:

player_shard_map

player_id shard_id
100001 12
100002 7
100003 12
100004 31
The backend first determines:

player_id -> shard_id
and then routes the request.

This adds an additional lookup but provides major operational flexibility.

A player can be migrated from:

Shard 12
to:

Shard 25
by moving the data and updating the directory mapping.

The application does not need a global shard-count change.

Large systems may cache this directory information in Redis to avoid querying the mapping database for every request.

Logical Shards and Physical Shards
A powerful design is to separate logical shards from physical databases.

For example, create 1,024 logical partitions:

Logical Shard 0
Logical Shard 1
...
Logical Shard 1023
Initially distribute them across four database clusters:

Physical DB A:
0–255

Physical DB B:
256–511

Physical DB C:
512–767

Physical DB D:
768–1023
Later, when DB A becomes overloaded, some logical shards can be moved to a new physical cluster.

Before:

DB A:
0–255

After:

DB A:
0–127

DB E:
128–255
This makes future rebalancing significantly easier than changing a simple modulo formula across the entire player population.

Keeping Player Data Together
Sharding works best when data commonly accessed together resides on the same shard.

Suppose Player 1001 lives on Shard 4.

Related tables might include:

player
inventory
equipment
quest
currency
mail
achievement
character_stats
Ideally:

Player 1001
Inventory 1001
Quests 1001
Currency 1001
all reside on Shard 4.

This allows normal local transactions.

Example:

Purchase item
|
+--> Deduct currency
+--> Add inventory item
If both tables are inside the same database shard, a regular database transaction can protect the operation.

If currency lives on Shard A and inventory lives on Shard B, the same transaction becomes a distributed systems problem.

For that reason, shard boundaries should follow business transaction boundaries whenever possible.

Global Data vs Sharded Data
Not everything should necessarily be sharded by player.

A Realtime Backend often contains several categories of data.

Player-Local Data
Examples:

Inventory
Character stats
Quest progress
Currencies
Equipment
Personal mail
Good candidates for player sharding.

Shared Global Data
Examples:

Item templates
Product configuration
Server configuration
Event definitions
Localization metadata
These may live in a shared database or configuration system.

Global Dynamic Data
Examples:

Global rankings
Auction marketplace
Cross-server guilds
World events
Global chat
These require specialized architectures because they interact with players across many shards.

A realistic system might therefore look like:

                  Realtime Backend
                       |
        +--------------+-------------+
        |                            |
        v                            v

Player Shards Global Services
| |
+----+----+ +---+---+
| | | | |
DB1 DB2 DB3 Ranking DB Market DB
Not every database problem needs to use the same sharding model.

The Cross-Shard Query Problem
Cross-shard queries are one of the biggest costs of database sharding.

Suppose an administrator asks:

Find the top 100 richest players globally.
Without sharding:

SELECT player_id, gold
FROM player_currency
ORDER BY gold DESC
LIMIT 100;
With 64 shards, there is no single table containing every player.

The application may need to:

Query top players from Shard 1
Query top players from Shard 2
...
Query top players from Shard 64

Merge results
Sort globally
Return top 100
Doing this live for every leaderboard request would be inefficient.

Instead, studios often build specialized systems.

For example:

Player Shards
|
Currency updates
|
v
Event Pipeline
|
v
Global Ranking Service
|
v
Redis Sorted Set / Ranking Database
The ranking service maintains a global derived view.

This avoids expensive fan-out queries.

Avoid Database Fan-Out in Play
Imagine a friend list containing 100 players.

If each friend may live on a different shard, loading the friend list could theoretically require queries to dozens of databases.

Bad pattern:

Player opens friend list
|
+--> Shard 1
+--> Shard 5
+--> Shard 8
+--> Shard 11
+--> Shard 24
...
This creates latency and failure amplification.

Better designs may maintain lightweight shared profile data.

For example:

player_public_profile

player_id
name
avatar
level
server
online_status
stored in Redis or a dedicated social/profile service.

Now the full authoritative player record remains on its shard, but commonly requested social information is available through a global service.

This pattern appears frequently in mature Realtime Backend architectures.

Guilds Across Database Shards
Guilds introduce another difficult decision.

Suppose guild members are distributed across shards:

Guild 500

Player A -> Shard 1
Player B -> Shard 7
Player C -> Shard 13
Player D -> Shard 29
Where should the guild itself live?

Possible strategies include:

Dedicated Guild Database
Guild sharded by guild_id
Guild stored on leader's shard
Guild metadata in global service
A dedicated Guild Service is often cleaner for large cross-server systems.

Match Server
|
v
Guild Service
|
v
Guild Database
Player shards then store only references or cached guild information.

This keeps guild operations independent from player database placement.

Cross-Shard Transactions
Cross-shard transactions are substantially more complex than transactions within one database.

Consider a player-to-player trade:

Player A -> Shard 3
Player B -> Shard 19
Trade:

Player A gives Sword
Player B gives 1,000 Gems
A traditional local transaction cannot atomically modify both shards.

A naïve implementation might do:

1. Remove Sword from A
2. Add Gems to A
3. Remove Gems from B
4. Add Sword to B
   What happens if step 3 fails?

The title can enter an inconsistent state.

Large distributed systems may solve this through patterns such as:

Transaction coordinator
Reservation workflow
Saga pattern
Escrow system
Durable transaction state
Idempotent operations
For a title marketplace, an escrow architecture may be simpler.

Seller
|
Transfer item
|
v
Marketplace Escrow
|
Buyer purchases
|
+--> Transfer currency
+--> Deliver item
The system becomes a controlled workflow rather than a direct cross-shard swap.

Data Duplication Can Be Useful
Developers are often taught to normalize relational databases heavily.

Distributed Realtime Backend systems sometimes intentionally duplicate selected data.

Suppose every shard frequently needs:

Item name
Item type
Base stats
Icon ID
Instead of querying a global configuration database for every inventory request, each application instance may load the same item configuration locally.

Likewise, a guild member summary might be duplicated:

player_id
nickname
level
avatar
power
inside the Guild Service.

The authoritative full player record still lives elsewhere.

This is a form of denormalization.

The trade-off is:

Faster local reads
vs
More synchronization complexity
For large online titles, this trade-off is often worthwhile.

Shard Hotspots
Even if players are evenly distributed by count, workload may not be.

Imagine:

Shard A:
1 million mostly inactive players

Shard B:
1 million highly active players
The row counts look identical.

Traffic does not.

Hotspots can also occur because of:

Popular regions
New server launches
Major guilds
Special events
Whale-heavy segments
Bot populations
Therefore shard monitoring should include:

Queries per second
Writes per second
CPU
Disk IOPS
Connection count
Buffer/cache usage
Replication lag
Slow queries
Active player count
Never rebalance based only on database size.

Rebalancing Database Shards
Eventually some shards become larger or hotter than others.

A studio may need to migrate players.

Example:

Shard 4
Players: 4 million
CPU: 85%

Shard 9
Players: 1 million
CPU: 30%
The system might move part of Shard 4's population.

A safe migration process often includes:

1. Select migration group
2. Mark players as migrating
3. Copy data to destination
4. Synchronize recent changes
5. Temporarily block or drain writes
6. Apply final delta
7. Update shard mapping
8. Validate destination
9. Re-enable traffic
10. Keep source copy temporarily
    The exact process depends on architecture and acceptable downtime.

Online Player Migration
Migrating active players is difficult.

Suppose a player continues playing while data is copied.

During migration:

Source:
level = 50

Player gains experience

Source:
level = 51

Destination copy:
level = 50
If routing is switched immediately, progress disappears.

Possible solutions include:

Maintenance Migration
Disconnect or temporarily block the player.

Simpler and safer.

Change Data Capture
Replicate database changes from source to destination until the destination catches up.

Dual Write
Temporarily write changes to both databases.

This can be complex because failures can leave the two copies inconsistent.

Migration Queue
Record updates occurring during the copy and replay them before cutover.

For many Studios, planned maintenance is preferable unless seamless migration is a real business requirement.

Server Merge Architecture
Mobile MMORPGs frequently launch many numbered servers and later merge populations.

For example:

S1 + S2 + S3 -> Combined Server
This is not just a play operation.

It can require database migration.

Problems may include:

Duplicate character names
Duplicate guild names
Conflicting primary keys
Server-specific rankings
Mail
Marketplace listings
Friend relationships
Event progress
A global ID strategy becomes extremely valuable.

Instead of:

player_id = 1001
existing independently on every server, IDs can incorporate a server or globally unique component.

For example:

global_player_id
that never conflicts across shards.

Without globally unique identifiers, server merge tools become significantly more complicated.

ID Generation in Sharded Systems
Auto-increment IDs can create problems if multiple shards generate the same values.

Example:

Shard A:
item_id = 5001

Shard B:
item_id = 5001
If those items later enter a global marketplace, the identifiers collide.

Solutions can include:

Database sequences with allocated ranges
UUIDs
Time-based distributed IDs
Snowflake-style IDs
Shard-prefixed numeric IDs
Central ID generation service
A distributed ID might conceptually encode:

Timestamp
Shard ID
Sequence
The goal is that each generated identifier remains unique across the entire system.

Database Connection Management
Sharding increases the number of database destinations.

Without sharding:

Backend -> 1 DB cluster
With sharding:

Backend -> 64 DB clusters
If every Realtime Backend instance opens large connection pools to every shard, total database connections can explode.

For example:

100 backend instances
x 64 shards
x 20 connections
=
128,000 connections
This architecture may be impractical.

Possible solutions include:

Smaller pools
Database proxies
Shard-aware backend assignment
Connection multiplexing
Regional services
Service-level ownership
Connection architecture must be considered before shard count grows dramatically.

Caching in a Sharded Database Architecture
Redis becomes especially valuable once databases are sharded.

For example:

Client
|
v
Realtime Backend
|
+--> Redis Cache
|
+--> Shard Router
|
+--> DB Shard 1
+--> DB Shard 2
+--> DB Shard 3
Frequently accessed public player data can be cached globally.

This reduces shard traffic and prevents repeated routing.

However, cache keys should include enough identity to avoid collisions.

For example:

player:global_id:profile
rather than relying on a local per-shard ID if those IDs are not globally unique.

Backup and Disaster Recovery
Sharding multiplies operational responsibilities.

Instead of backing up one database, the Studio may now have:

Shard 1 backup
Shard 2 backup
Shard 3 backup
...
Shard 64 backup
A reliable system should know:

Which backup belongs to which shard?
When was it created?
Is replication healthy?
Can it be restored?
Does the shard directory match the backup?
Recovery must also preserve shard mappings.

Restoring player data to the wrong shard while the routing directory still points elsewhere can make the data effectively invisible.

For this reason, shard metadata itself is critical production data.

Monitoring Sharded Databases
Monitoring should provide both per-shard and fleet-wide visibility.

Important per-shard metrics include:

CPU
Memory
Disk
Connections
Queries per second
Writes per second
Slow queries
Replication lag
Lock waits
Transaction rate
Storage growth
Product-level metrics should include:

Active players per shard
New accounts per shard
Inventory writes
Currency transactions
Guild operations
Mail volume
Marketplace activity
A useful operational dashboard should make hotspots immediately obvious.

For example:

Shard 01 CPU 42%
Shard 02 CPU 38%
Shard 03 CPU 91% WARNING
Shard 04 CPU 47%
Without shard-aware monitoring, the fleet may appear healthy while one player segment experiences severe latency.

How to Analyze This in Multiplayer source Code
When examining a large MMORPG or Mobile Realtime backend on the forum, sharding logic may appear in several places.

1. Search Database Configuration
   Look for:

db_1
db_2
database_list
shard
datasource
mysql
server_db
zone_db
Multiple database endpoints usually indicate some form of data distribution.

2. Find Shard Routing Logic
   Search for:

getShard
getDatabase
routePlayer
serverId
zoneId
dbIndex
hash
mod
You may find logic similar to:

dbIndex = playerId % DB_COUNT
or:

db = serverDatabaseMap[serverId]
This is one of the most important architectural discoveries in Multiplayer source Code.

3. Identify Local and Global Databases
   Look for database names such as:

account_db
player_db
global_db
guild_db
log_db
payment_db
The naming often reveals service boundaries.

4. Trace One Player Transaction
   Choose an operation such as:

Buy item
Claim reward
Upgrade equipment
Trace:

API
->
Realtime Backend
->
Shard routing
->
Database transaction
Determine how many databases participate.

5. Inspect Global Features
   Study:

Leaderboard
Guild
Chat
Marketplace
Cross-server battle
These systems often reveal how the project handles cross-shard data.

6. Search Migration Tools
   Look for:

merge_server
move_player
migrate
transfer
sync
copy_role
zone_merge
Older commercial Multiplayer source Code often contains scripts specifically designed for server merges or account transfers.

These tools can provide valuable clues about the production architecture.

Common Mistakes
Sharding Too Early
A poorly optimized single database is often easier to fix than a badly designed 32-shard architecture.

Before sharding, optimize:

Queries
Indexes
Caching
Connection pools
Read replicas
Schema
Choosing a Shard Key That Changes
Shard keys should be stable.

Using attributes such as current guild or current region can become painful if those values change frequently.

Cross-Shard Queries Everywhere
If normal play constantly needs every shard, the chosen boundaries are probably wrong.

Using Modulo Without Future Planning
player_id % shard_count is easy until shard count changes.

Plan for rebalancing before reaching capacity.

Assuming Equal Player Count Means Equal Load
Active-player distribution matters more than raw account count.

Cross-Shard Transactions for Normal Play
Frequent distributed transactions can dramatically increase complexity.

Try to keep commonly modified state together.

No Migration Strategy
If data cannot be moved safely, the architecture is only scalable until the first shard fills up.

Best Practices
A practical sharding strategy should follow several principles.

Delay Sharding Until Necessary
Sharding solves real scaling problems but adds permanent operational complexity.

Keep Player Transactions Local
Inventory, currency, quests, and character state should ideally share the same shard.

Use Globally Unique IDs
This simplifies cross-server systems, migration, logging, and server merges.

Separate Global Services
Leaderboards, guilds, matchmaking, and marketplace systems may deserve independent storage architectures.

Use a Flexible Shard Directory
A mapping layer or logical-shard architecture makes rebalancing easier.

Design Migration Before Production
Do not wait until the database reaches maximum capacity to decide how players will move.

Build Shard-Aware Monitoring
Track both infrastructure and player workload per shard.

Keep Backups Independent
A failure in one shard should not compromise every shard.

Minimize Cross-Shard Fan-Out
Create derived global views instead of querying dozens of databases during play.

Test Failure Scenarios
Test:

Shard unavailable
Replication failure
Wrong shard mapping
Partial migration
Duplicate migration
Cross-shard timeout
Cache pointing to old shard
Distributed data systems should be tested under abnormal conditions, not only normal play.

Conclusion
Database sharding is one of the most powerful scaling strategies available to large online titles, but it also changes the architecture fundamentally.

Before sharding, a Realtime Backend may think of the database as one system:

Realtime Backend
|
Database
After sharding, it becomes a distributed data platform:

Realtime Backend
|
Shard Router
|
+--> Player Shards
+--> Global Services
+--> Redis
+--> Ranking Storage
+--> Guild Storage
+--> Marketplace Storage
The main benefit is scalability.

Player data, writes, and storage can be distributed across many database clusters.

The main cost is complexity.

Studios must now solve:

Shard routing
Global IDs
Cross-shard queries
Cross-shard transactions
Migration
Rebalancing
Caching
Monitoring
Backups
Server merges
For developers studying Multiplayer source Code, database sharding is also an important indicator of how the original infrastructure was designed.

A small title may contain one MySQL connection string.

A large commercial MMORPG may contain dozens of database groups, server mappings, Redis caches, global services, migration scripts, and cross-server systems.

Understanding those relationships is essential before modifying or redeploying the backend.

For projects studied through the forum, analyzing database topology can often reveal more about the intended scale of a title than the client itself.

In professional Multiplayer development, the goal is not to create the largest possible number of shards.

The goal is to create clear data ownership boundaries so that the Match Server can scale while keeping transactions predictable, migrations manageable, and player data safe.
