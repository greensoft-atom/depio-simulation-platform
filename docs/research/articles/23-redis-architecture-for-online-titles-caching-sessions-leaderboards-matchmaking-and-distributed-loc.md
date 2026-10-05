#23 – Redis Architecture for Online Titles: Caching, Sessions, Leaderboards, Matchmaking and Distributed Locks
administrator
administrator
Verified user account
16/08/2026 06:56
•
General Discussion
Redis Architecture for Online Titles: Caching, Sessions, Leaderboards, Matchmaking and Distributed Locks
Introduction
Redis is one of the most commonly used infrastructure components in modern online realtime backends.

It is fast, flexible, and well suited to workloads where a Match Server or Realtime Backend needs extremely low-latency access to temporary or frequently accessed data. In MMORPGs, Mobile Titles, Multiplayer titles, and live-service platforms, Redis is often used for player sessions, leaderboards, rate limiting, matchmaking queues, cache layers, presence systems, distributed coordination, temporary battle data, and real-time counters.

However, Redis is frequently misunderstood.

Some developers treat it as a universal replacement for a relational database. Others use Redis only as a basic key-value cache and miss its more powerful data structures. A poorly designed Redis layer can also create consistency problems, stale data, memory pressure, synchronization bugs, or a new Single Point of Failure.

From a professional Multiplayer development perspective, Redis should be treated as a specialized infrastructure layer with clearly defined responsibilities.

A typical architecture may look like:

Players
|
v
Load Balancer
|
+----------------------+
| |
v v
Realtime Backend A Realtime Backend B
| |
+----------+-----------+
|
v
Redis Layer
|
+-------+-------+
| |
v v
Database Match Servers
The persistent database remains responsible for authoritative long-term player data, while Redis provides fast access to frequently used or temporary state.

When analyzing Multiplayer source Code, developers should pay close attention to how Redis is used, what information is stored inside it, how keys are structured, how data expires, and what happens if Redis becomes unavailable.

At the forum, understanding these patterns is especially valuable when studying older or large-scale backend projects because Redis configuration often reveals how the original Studio handled scalability, caching, sessions, and distributed coordination.

This article explains how Redis fits into Realtime Backend architecture, including practical use cases, data structures, caching strategies, leaderboard design, session storage, matchmaking, distributed locks, failure handling, monitoring, and common mistakes.

Why Redis Is Useful in Realtime Backend Systems
Redis is primarily an in-memory data store.

Because data is kept in memory, many operations can be performed with very low latency compared with systems that depend heavily on disk access.

For titles, this is useful because many backend operations are extremely frequent.

Examples include:

Get player session
Update leaderboard score
Check rate limit
Find matchmaking queue
Read temporary event state
Update online presence
Retrieve cached player profile
Increment real-time counter
A Realtime Backend may process these operations thousands or even millions of times during active play.

Sending every operation directly to a relational database can create unnecessary load.

Redis can act as a high-speed layer between application services and persistent storage.

Conceptually:

Realtime Backend
|
+--> Redis
| |
| +--> Fast temporary / cached data
|
+--> Database
|
+--> Authoritative persistent data
The important design question is not:

Should we use Redis?

It is:

Which data belongs in Redis, and which data must remain authoritative elsewhere?

Redis Data Structures for Titles
Redis is more than a simple string cache.

Its native data structures make it useful for many Realtime Backend workloads.

Strings
Strings are useful for simple values.

Example:

player:1001:session -> "abc92f..."
Another example:

server:asia:online_count -> 18432
Strings can also hold serialized JSON or binary data, although very large serialized objects should be used carefully.

Hashes
Hashes can represent groups of fields.

Example:

player:1001:profile
with fields:

name = DragonKnight
level = 58
server_id = 12
vip_level = 4
This can be more convenient than storing the entire object as one serialized string.

Hashes are often useful for:

Player summaries
Session metadata
Server status
Temporary configuration
Match information
Sets
Sets store unique values.

Example:

guild:500:online_members
Values:

1001
1008
1097
1122
Sets can be useful for:

Online players
Guild membership snapshots
Unique event participants
Blocked player IDs
Feature flags
Sorted Sets
Sorted sets are one of the most useful Redis structures for online titles.

Each member has a score.

Example:

leaderboard:pvp:season_12
Data:

Player_1001 -> 18450
Player_1002 -> 17920
Player_1003 -> 17210
Redis can efficiently return players ordered by score.

This makes sorted sets excellent for:

PvP rankings
Power rankings
Event rankings
Guild rankings
Speedrun rankings
Damage rankings
Lists and Streams
Lists may be useful for relatively simple queues.

Redis Streams provide a more advanced mechanism for ordered event processing and consumer groups.

Possible use cases include:

Internal events
Task processing
Audit streams
Temporary match event pipelines
However, Redis should not automatically replace dedicated messaging systems such as Kafka, RabbitMQ, or cloud message queues when stronger durability, large event histories, or complex messaging semantics are required.

Redis as a Realtime Backend Cache
Caching is probably the most familiar Redis use case.

Suppose a player profile is stored in MySQL or PostgreSQL.

Without caching:

Client Request
|
v
Realtime Backend
|
v
Database
Every profile lookup creates database traffic.

With Redis:

Client Request
|
v
Realtime Backend
|
v
Redis
|
+--> Cache Hit
|
+--> Cache Miss
|
v
Database
The application first checks Redis.

If the value exists, it can return it immediately.

If the value does not exist, the backend loads it from the database and may populate the cache.

This pattern is commonly called cache-aside.

Cache-Aside Workflow
A simplified read workflow is:

1. Request player profile
2. Check Redis
3. If found:
   return cached profile
4. If not found:
   query database
   write result to Redis
   return profile
   Pseudo logic:

profile = redis.get(player_id)

if profile exists:
return profile

profile = database.load(player_id)

redis.set(player_id, profile, TTL)

return profile
This reduces repeated database reads.

However, cache design immediately creates another problem:

How do we keep Redis consistent with the database?

Cache Invalidation
Cache invalidation is one of the hardest parts of caching.

Suppose Redis contains:

player:1001:level = 50
The database is updated:

player.level = 51
If Redis is not updated or invalidated, the backend may continue returning level 50.

Possible strategies include:

Delete Cache After Database Update
Database update
|
v
Delete Redis cache
The next read reloads the latest data.

Update Database and Cache
Update database
Update Redis
This can be faster for subsequent reads, but partial failures require careful handling.

Expiration-Based Cache
Set a TTL:

TTL = 5 minutes
Even if invalidation fails, the stale entry eventually disappears.

In many Multiplayer development systems, these strategies are combined.

For example:

Database is authoritative
Redis is updated after successful DB transaction
Redis entry also has TTL
The right approach depends on how stale the data is allowed to become.

What Should Be Cached?
Good cache candidates are often:

Frequently read
Relatively expensive to query
Not modified every millisecond
Recoverable from another source
Examples:

Player profile summaries
Product configuration
Item templates
Guild information
Static event configuration
Social profile previews
Server lists
Feature configuration
Poor cache candidates may include values that must always be immediately authoritative.

For example:

Premium currency balance
Payment transaction state
Critical trade settlement
Final item ownership
You can still use Redis around these systems, but Redis should not casually become the only authority.

Redis for Player Sessions
A common Realtime Backend architecture stores login sessions in Redis.

Example:

session:8ab7fa91
Data:

account_id = 100821
player_id = 555982
server_id = 32
created_at = ...
The login workflow becomes:

Player
|
Login
|
v
Authentication Service
|
Create session
|
v
Redis
|
Return session token
Subsequent API requests:

Client
|
Session Token
|
v
Realtime Backend
|
v
Redis
|
Resolve Player Identity
This architecture has several advantages.

Backend servers can remain relatively stateless.

Players can send requests to different API instances without losing login state.

Session expiration can also be implemented using Redis TTL.

For example:

session:8ab7fa91
TTL = 3600 seconds
After the expiration period, Redis automatically removes the session.

Online Presence
Redis can also track whether players are currently online.

One basic design:

online:player:1001 = server_12
with a TTL.

The Match Server periodically refreshes the key.

If the server disappears and stops refreshing:

TTL expires
The player eventually becomes offline automatically.

Another architecture may use sets:

online:server:12
containing all online player IDs.

This can help systems such as:

Guild online list
Friend presence
Party invitations
Whisper routing
Cross-server social features
Presence data is usually temporary, making Redis a natural fit.

Redis Leaderboards
Redis sorted sets make leaderboard implementation straightforward.

Example:

ZADD leaderboard:pvp 15000 player_1001
ZADD leaderboard:pvp 18200 player_1002
ZADD leaderboard:pvp 17400 player_1003
The score becomes the ranking value.

Conceptually:

Rank Player Score
1 player_1002 18200
2 player_1003 17400
3 player_1001 15000
The backend can efficiently retrieve:

Top 100 players
Player's current rank
Players around a specific rank
Players inside a score range
This is extremely useful for competitive Mobile Title and MMORPG systems.

Seasonal Leaderboards
Many titles reset rankings periodically.

Instead of deleting one global leaderboard every season, use versioned keys.

Example:

leaderboard:pvp:season:31
leaderboard:pvp:season:32
leaderboard:pvp:season:33
This keeps seasons isolated.

When season 33 becomes active:

current_pvp_season = 33
Historical leaderboards may be retained for rewards or auditing.

After rewards are fully processed, older data can be archived or deleted.

This is much safer than overwriting important ranking data without a recovery path.

Leaderboard Reward Safety
A dangerous design is:

Read top 100
Grant rewards
Delete leaderboard
If the reward worker crashes halfway through:

Players 1–50 receive rewards
Players 51–100 do not
The system needs recovery.

A safer flow may be:

1. Freeze season
2. Snapshot ranking results
3. Store reward job state
4. Process rewards idempotently
5. Record completion
6. Archive leaderboard
   Each reward should use a unique identifier.

Example:

reward_id = PVP_SEASON_33_PLAYER_1001
If processing retries, the Realtime Backend recognizes the reward has already been granted.

Matchmaking Queues
Redis can also support matchmaking.

Suppose players enter a ranked queue.

A sorted set may use matchmaking rating as the score.

Example:

matchmaking:ranked:asia
Entries:

player_1001 -> 1510
player_1002 -> 1492
player_1003 -> 1605
A matchmaking worker searches for players within an acceptable MMR range.

Conceptually:

Player MMR = 1500

Initial search:
1450–1550

After waiting:
1400–1600

After longer wait:
1300–1700
This allows the matching range to expand over time.

Redis can support this kind of dynamic search efficiently.

Matchmaking State
A complete matchmaking system needs more than a queue.

The backend may track:

Queue state
Player MMR
Region
Match mode
Party ID
Entry timestamp
Matching status
Match ID
Possible state transitions:

IDLE
|
v
QUEUED
|
v
MATCHING
|
v
MATCH_CREATED
|
v
MATCH_SERVER_ASSIGNED
The system must prevent one player from appearing in multiple matches simultaneously.

This is where atomic operations and distributed coordination become important.

Atomic Operations
Redis operations are frequently used because many simple commands are atomic.

Suppose multiple Realtime Backend instances try to increment a global counter.

Using:

GET
modify locally
SET
can create race conditions.

Instead:

INCR
performs the modification atomically.

This is useful for:

Counters
Rate limits
Event participation
Sequence numbers
Concurrent access control
Similarly, conditional writes can help prevent duplicate operations.

Understanding Redis atomicity is important when multiple Match Server processes share the same data.

Redis and Distributed Locks
Distributed systems sometimes need a mechanism to ensure only one process performs a particular operation at a time.

Imagine two backend servers both receive:

Claim guild boss reward
at almost the same moment.

Without coordination:

Server A checks reward
Server B checks reward

Both see:
reward not claimed

Server A grants reward
Server B grants reward
A distributed lock can serialize access.

Conceptually:

Acquire:
lock:guild:500:boss_reward

If lock acquired:
process reward
else:
retry or reject
Redis can implement simple locks using conditional key creation with expiration.

However, distributed locking is subtle.

A lock must have an expiration so it does not remain forever if the process crashes.

The releasing process must also ensure it only releases a lock it actually owns.

A conceptual lock value might contain:

lock token = random unique value
Unlock should verify:

current lock token == my lock token
before deleting it.

Do Not Use Locks for Everything
Distributed locks add complexity and can reduce throughput.

Before adding a lock, Studios should consider whether the operation can instead be protected using:

Database unique constraints
Atomic database updates
Optimistic concurrency
Idempotency keys
Redis atomic commands
State-machine transitions
For example, a payment callback may be safer with a unique transaction ID in the database than with a temporary Redis lock.

Locks should solve a specific concurrency problem, not become the default solution for every transaction.

Redis Rate Limiting
Redis is commonly used to implement distributed rate limits.

Suppose a player can send no more than:

20 chat messages per 10 seconds
A key might look like:

rate:chat:player:1001
Each message increments the counter.

The key expires after the time window.

This provides rate-limit state shared across all Realtime Backend instances.

Without shared storage:

Backend A counter = 10
Backend B counter = 10
Backend C counter = 10
The player could potentially exceed the intended global limit by distributing requests across servers.

Redis allows the cluster to enforce one shared rule.

Cache Stampede Problems
A common caching failure occurs when many requests miss the same key simultaneously.

Suppose:

popular:event:config
expires at exactly 12:00.

Thousands of requests arrive immediately afterward.

Every backend instance sees:

Cache Miss
and queries the database.

Result:

Redis load ↓
Database load ↑↑↑
This is known as a cache stampede.

Possible mitigations include:

TTL jitter
Request coalescing
Distributed lock during refresh
Background refresh
Stale-while-revalidate patterns
TTL jitter means slightly randomizing expiration.

Instead of every key expiring at exactly:

300 seconds
use values such as:

287
301
319
294
This spreads reload traffic over time.

Cache Penetration
Another issue occurs when requests repeatedly ask for data that does not exist.

Example:

player_id = 999999999999
Redis does not contain it.

Database does not contain it.

Every request hits the database.

Attackers or buggy clients may generate huge numbers of such requests.

One solution is negative caching.

Example:

player:999999999999 -> NOT_FOUND
TTL = 60 seconds
Future requests are rejected from cache without repeatedly querying the database.

Other filtering techniques may also be appropriate depending on the application.

Hot Keys
A Redis key can become a bottleneck if an enormous amount of traffic targets one entry.

Examples:

global_online_count
global_event_state
world_boss_hp
global_leaderboard
If millions of operations hit one key, traffic may become concentrated.

Possible strategies include:

Local application cache
Read replicas
Sharded counters
Batch updates
Reduced refresh frequency
Hierarchical caching
The best approach depends on whether the workload is read-heavy or write-heavy.

Redis Cluster and Sharding
As Redis datasets and traffic grow, one server may become insufficient.

Redis Cluster can partition keys across multiple nodes.

Conceptually:

             Redis Cluster

        +---------+---------+
        |         |         |
        v         v         v
      Node A    Node B    Node C

Keys:
player:1 -> A
player:2 -> B
player:3 -> C
Sharding distributes memory usage and request load.

However, developers should understand that operations involving multiple keys can become more complicated when those keys live on different nodes.

This matters if application logic expects multi-key atomic operations.

Key naming and hash-tag strategy therefore become architectural concerns.

Redis High Availability
A production Realtime Backend should not assume one Redis process will always remain online.

Possible architectures may use:

Primary
|
+--> Replica 1
+--> Replica 2
with automated failover.

Another option is a managed Redis service provided by cloud infrastructure.

High Availability design should consider:

Node failure
Network partition
Replication lag
Failover timing
Client reconnect behavior
Data persistence configuration
The application should also define what happens if Redis becomes temporarily unavailable.

Graceful Redis Failure
Not every Redis failure should completely shut down the title.

Suppose Redis is used for:

Leaderboard
Friend presence
Profile cache
while authoritative player data remains in the database.

The Realtime Backend may temporarily degrade:

Leaderboard unavailable
Presence delayed
Profiles loaded directly from DB
while core play continues.

However, if Redis stores critical sessions:

Redis unavailable
|
v
Session validation unavailable
login and API access may fail.

This shows why studios should classify Redis workloads by importance.

Redis Persistence
Redis supports persistence mechanisms that can help recover data after restart.

However, developers should not assume this makes Redis identical to a transactional database.

For temporary or reconstructable cache data:

Persistence may be optional
For more valuable Redis-resident data:

Persistence configuration becomes important
The decision depends on acceptable data loss.

For example:

Presence:
Loss acceptable

Cached item templates:
Rebuildable

Live matchmaking queue:
Some loss may be acceptable

Premium currency:
Should not rely on Redis as the sole source
The authoritative system must be defined clearly.

Key Naming Strategy
Large Realtime Backend systems can contain millions of Redis keys.

Without consistent naming, operations become difficult.

A useful pattern might be:

environment:system:entity:id:field
Examples:

prod:session:player:1001
prod:profile:player:1001
prod:leaderboard:pvp:s33
prod:presence:server:12
prod:rate:login:ip:192.0.2.10
Names should be predictable and documented.

Avoid very long key names when unnecessary because key metadata also consumes memory.

At large scale, even small per-key overhead can become significant.

TTL Strategy
TTL should be designed intentionally.

Examples:

Login session:
1 hour

Presence heartbeat:
30–120 seconds

Profile cache:
5–30 minutes

Temporary rate counter:
10 seconds

Event configuration:
Several minutes
A key with no TTL may live forever.

Over time, forgotten temporary keys can create memory growth.

Studios should regularly examine:

Key count
Expired keys
Evicted keys
Memory usage
Large keys
TTL distribution
Memory Eviction
Redis can be configured with memory limits and eviction policies.

When memory becomes full, Redis may begin evicting keys depending on configuration.

This is dangerous if developers assume important data can never disappear.

For example:

Cache entries
Session entries
Critical temporary locks
may have very different importance.

Mixing unrelated workloads inside the same Redis deployment can therefore create unpredictable failure behavior.

Large Studios may separate Redis workloads.

Example:

Redis Cluster A
Sessions

Redis Cluster B
Caching

Redis Cluster C
Leaderboards

Redis Cluster D
Matchmaking
This creates workload isolation.

A cache explosion should not necessarily destroy login sessions.

Monitoring Redis in Production
Redis should be monitored like any critical Realtime Backend service.

Important metrics include:

Memory usage
CPU usage
Connected clients
Commands per second
Cache hit ratio
Keyspace hits
Keyspace misses
Expired keys
Evicted keys
Replication lag
Network bandwidth
Slow commands
Latency
Connection errors
Product-specific metrics are equally important.

For example:

active_sessions
players_in_matchmaking
leaderboard_updates_per_second
rate_limit_blocks
presence_keys
distributed_lock_failures
Infrastructure metrics tell you that Redis is under pressure.

Title metrics help explain why.

How to Analyze This in Multiplayer source Code
When studying a Match Server repository, Redis usage can reveal a large portion of the architecture.

At the forum, developers examining Multiplayer source Code can follow this workflow.

1. Find Redis Configuration
   Search for:

redis
jedis
lettuce
redisson
ioredis
StackExchange.Redis
hiredis
sentinel
cluster
Identify:

Host
Port
Database index
Cluster mode
Authentication
Connection pool
Timeout
Never reuse production credentials found in old source code.

2. Search Redis Key Prefixes
   Look for patterns such as:

session:
player:
rank:
guild:
match:
cache:
lock:
online:
These prefixes reveal what Redis is actually doing.

3. Identify Authoritative Data
   For each Redis key, ask:

Can this data be reconstructed?
If yes, Redis is probably functioning as cache or temporary storage.

If no, investigate carefully.

Important permanent state should usually have a reliable persistent source.

4. Find TTL Usage
   Search for:

expire
ttl
setex
pexpire
Temporary keys without expiration are a common cause of memory leaks.

5. Inspect Leaderboards
   Search for:

zadd
zrange
zrevrange
zscore
rank
leaderboard
Determine whether seasonal separation and reward processing are handled safely.

6. Inspect Locks
   Search for:

lock
setnx
mutex
redlock
unlock
Check whether locks have expiration and ownership validation.

7. Inspect Failure Handling
   Ask what happens when Redis throws:

Timeout
Connection refused
Cluster unavailable
Read-only replica error
Does the Realtime Backend:

Retry?
Fallback?
Fail request?
Crash?
Use database?
This tells you whether Redis is treated as optional infrastructure or a hard dependency.

Common Mistakes
Treating Redis as the Only Database
Redis can persist data, but many Realtime Backend workloads still need a durable transactional database.

Critical player ownership and payment data should not disappear because a cache node was lost.

No TTL on Temporary Keys
Temporary sessions, locks, and counters can accumulate indefinitely.

Storing Huge Objects
Very large serialized objects increase bandwidth, memory usage, and update cost.

Often it is better to cache smaller pieces of frequently accessed information.

Stale Cache After Database Updates
If database data changes but Redis is not invalidated, players may see incorrect state.

Using Distributed Locks for Everything
Locks can reduce scalability and create deadlock-like failure patterns if poorly implemented.

Ignoring Redis Failure
If every backend request assumes Redis is always available, a Redis outage may become a total title outage.

Mixing Critical and Non-Critical Workloads
A massive leaderboard or cache workload can consume memory needed by player sessions.

Workload separation becomes increasingly valuable at scale.

Best Practices
A practical Redis strategy for Multiplayer development should follow several principles.

Define the Source of Truth
For every Redis value, document whether it is:

Authoritative
Cached
Temporary
Derived
Use Consistent Key Naming
A predictable naming system makes debugging and monitoring easier.

Use TTL Deliberately
Temporary data should normally expire.

Keep Critical Transactions in Durable Systems
Premium currency, payments, trade ownership, and permanent inventory should use appropriate persistent transaction storage.

Prefer Atomic Operations
Avoid read-modify-write race conditions where Redis already provides atomic commands.

Design for Redis Failure
Know which features can degrade and which require failover.

Monitor Memory and Evictions
Do not wait until Redis reaches maximum memory before investigating growth.

Separate Workloads When Necessary
Sessions, cache, matchmaking, and leaderboards may eventually deserve separate Redis clusters.

Test Concurrency
Load-test:

Leaderboard updates
Matchmaking joins
Session refresh
Reward claims
Rate limiting
Distributed locks
Many bugs only appear when hundreds of Match Server instances operate simultaneously.

Conclusion
Redis is one of the most powerful supporting technologies in modern Realtime Backend architecture.

It can provide extremely fast infrastructure for:

Caching
Sessions
Leaderboards
Presence
Matchmaking
Rate limiting
Counters
Temporary state
Distributed coordination
But Redis should not be used without clear architectural boundaries.

The persistent database should generally remain the source of truth for valuable long-term player state, while Redis accelerates access and manages workloads that benefit from low latency.

A mature Redis architecture also requires careful consideration of:

Cache invalidation
TTL
Memory limits
Atomic operations
Distributed locks
Sharding
Replication
Failover
Monitoring
Workload isolation
When analyzing Multiplayer source Code, Redis often reveals how scalable the original Match Server architecture was intended to be. Key prefixes can expose session systems, matchmaking pipelines, leaderboard architecture, caching strategies, and cross-server coordination.

For developers exploring backend projects on the forum, understanding Redis is therefore not simply about learning another database technology. It helps explain how the entire online title infrastructure manages performance and concurrency.

In professional Multiplayer development, Redis works best when it is treated as a high-speed infrastructure component rather than an undefined place to store everything.

When the source of truth, expiration strategy, failure behavior, and consistency rules are clear, Redis can significantly improve Realtime Backend performance without compromising reliability.
