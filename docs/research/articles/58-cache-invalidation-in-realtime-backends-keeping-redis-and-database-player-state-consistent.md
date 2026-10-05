#58 – Cache Invalidation in Realtime Backends: Keeping Redis and Database Player State Consistent
administrator
administrator
Verified user account
02/09/2026 06:37
•
General Discussion
Cache Invalidation in Realtime Backends: Keeping Redis and Database Player State Consistent
Introduction
Caching is one of the most effective ways to reduce database load in an online title.

A Match Server may repeatedly request the same information:

Player profile
Inventory summary
Guild information
Configuration data
Leaderboard entries
Session state
Matchmaking metadata
Reading all of this directly from a relational database for every request can become expensive.

A common Realtime Backend architecture therefore introduces Redis or another in-memory cache:

Client
|
v
Match Server
|
v
Redis Cache
|
+--> Cache Hit
|
+--> Cache Miss
|
v
Database
If the requested data already exists in Redis, the Match Server can return it without querying the database.

This reduces latency and database pressure.

However, caching introduces one of the hardest problems in distributed systems:

How do you make sure cached data remains consistent with the authoritative database?

Suppose the database contains:

Gold = 8,000
but Redis still contains:

Gold = 10,000
Which value should the Match Server trust?

If the wrong data is used for an economic transaction, the player may spend currency they no longer own.

Even non-economic inconsistencies can create confusing play:

Database:
Hero Level = 50

Cache:
Hero Level = 49
The player upgrades successfully, refreshes the interface, and suddenly sees the old level.

This is the cache invalidation problem.

For Multiplayer development teams building MMORPG, Mobile Title, and multiplayer systems, cache invalidation is not simply about deleting Redis keys. It requires careful coordination between database writes, cache reads, replication, retries, concurrent Match Server instances, and failure recovery.

This article explains the most important cache invalidation strategies, common race conditions, Redis patterns, TTL design, and how developers can analyze cache consistency when reviewing Multiplayer source Code.

Why Realtime Backends Use Redis
Relational databases are designed for durable storage and transactional consistency.

Redis is optimized for very fast in-memory access.

A Realtime Backend may store frequently requested data such as:

player:1001:profile
player:1001:session
guild:500:summary
leaderboard:season_12
config:item_table
matchmaking:queue
The benefit becomes clear when one piece of data is read frequently.

Suppose a player profile receives:

100 reads
during a session.

Without caching:

100 reads → Database
With caching:

1 read → Database
99 reads → Redis
This can dramatically reduce database traffic.

However, Redis usually should not become the only durable source for economically important player data unless the Realtime Backend is explicitly designed around that model.

A common architecture is:

Database = durable source of truth
Redis = performance layer
The challenge is making sure the performance layer does not serve incorrect state.

What Is Cache Invalidation?
Cache invalidation means removing or updating cached data when the underlying authoritative value changes.

Suppose Redis contains:

player:1001:profile
Level = 40
The Match Server updates the database:

Level = 41
The old cached value is now stale.

The backend has several choices.

It can:

Delete the cache entry
or:

Update the cache entry
or:

Allow it to expire later
Each strategy has different tradeoffs.

The most important principle is:

Once authoritative data changes, the Realtime Backend must have a predictable strategy for preventing outdated cache state from being trusted indefinitely.

Cache-Aside Pattern
One of the most common patterns in Realtime Backend development is cache-aside.

The read path looks like:

Request
|
v
Read Redis
|
+--> HIT
| |
| v
| Return
|
+--> MISS
|
v
Read DB
|
v
Write Cache
|
v
Return
Pseudo logic:

data = redis.get(key)

if data exists:
return data

data = database.load(id)

redis.set(key, data)

return data
This is simple and flexible.

The application controls caching behavior directly.

The write path is where things become more difficult.

Database Update Followed by Cache Delete
A common cache-aside write strategy is:

1. Update database
2. Delete cache
   Example:

UPDATE player_profile
SET level = 41
WHERE player_id = 1001;

DEL player:1001:profile
The next read experiences a cache miss and reloads the latest database value.

Conceptually:

Database updated
|
v
Cache deleted
|
v
Next read misses
|
v
Load newest database value
This is often safer than directly updating the cache because it avoids maintaining two independently written versions of the same object.

But even this pattern can fail under concurrency.

Why Delete-Then-Update Can Be Dangerous
Consider this ordering:

1. Delete cache
2. Update database
   Suppose the database currently has:

Level = 40
and the cache also has:

Level = 40
Server A begins an update.

Server A:
Delete cache
Before Server A updates the database, Server B reads the player.

Redis misses.

Server B reads the database:

Level = 40
and stores it back into Redis.

Then Server A updates the database:

Level = 41
Now:

Database = 41
Redis = 40
The cache may remain stale until expiration.

This is why the safer default for cache-aside systems is usually:

Update DB first
Then invalidate cache
rather than invalidating before the durable write.

Update Database Then Delete Cache
The workflow becomes:

Server A
|
v
Update Database
|
v
Delete Redis Key
If another reader arrives after the database commit but before deletion, it may briefly see stale cache data.

However, after the deletion, future requests reload fresh data.

The inconsistency window is usually small.

Still, distributed systems can produce more subtle races.

The Stale Cache Repopulation Race
Consider the following timeline.

Initial state:

Database = Level 40
Redis = empty
Server A begins reading:

T1: Redis miss
T2: Server A reads DB → Level 40
Before Server A writes the cache, Server B updates the player:

T3: Server B updates DB → Level 41
T4: Server B deletes Redis key
Then Server A resumes:

T5: Server A writes Level 40 into Redis
Final state:

Database = 41
Redis = 40
This is a classic cache repopulation race.

The update correctly deleted the cache, but an older in-flight reader recreated stale data afterward.

This is one reason cache invalidation becomes difficult under real concurrency.

Using TTL as a Safety Net
A Time To Live, or TTL, limits how long cached data survives.

Example:

SET player:1001:profile <data> EX 60
The cache expires after approximately 60 seconds.

TTL does not eliminate invalidation races, but it limits how long incorrect data can persist.

For example:

Database = Level 41
Redis = Level 40
TTL remaining = 12 seconds
The inconsistency eventually repairs itself.

Without TTL:

Redis = Level 40 forever
until some later operation deletes or replaces the key.

For many Realtime Backend systems, explicit invalidation plus TTL provides a useful defense-in-depth approach.

Choosing Cache TTL
There is no universal TTL.

Different data types have different freshness requirements.

Possible examples:

Product configuration
TTL: 5–60 minutes

Public player profile
TTL: 30–300 seconds

Guild summary
TTL: 10–60 seconds

Leaderboard page
TTL: 5–30 seconds

Current wallet
TTL: extremely short or not cached for authoritative decisions
The correct TTL depends on:

update frequency;

read frequency;

consistency requirements;

database cost;

acceptable stale duration.

A Studio should not use one global TTL for all cache keys.

Cache Authoritative Data Carefully
Not all product data should be treated equally.

Suppose Redis contains:

player:1001:gold = 10,000
The database contains:

gold = 8,000
If the Match Server uses Redis to validate:

Can the player spend 9,000 gold?
the answer becomes incorrect.

For sensitive operations such as:

Currency spending
Premium purchases
Reward claims
Marketplace transactions
Inventory ownership
Payment grants
the backend should clearly define the authoritative consistency path.

Redis may still participate, but it should not accidentally become an unchecked stale authority.

On the forum, when analyzing Multiplayer source Code, developers should pay particular attention to which cached values are merely display optimizations and which values participate in state-changing decisions.

Write-Through Caching
In a write-through strategy, the application writes through the cache layer.

Conceptually:

Match Server
|
v
Cache Layer
|
+--> Update Database
|
+--> Update Cache
The goal is to keep cache and database synchronized during writes.

This can provide fresh cached data immediately.

However, the backend must define what happens if one update succeeds and the other fails.

For example:

Database write succeeds
Cache update fails
or:

Cache update succeeds
Database write fails
If these components are not transactionally coordinated, inconsistent states remain possible.

For this reason, simple cache-aside invalidation is often easier to reason about.

Write-Behind Caching
Write-behind, sometimes called write-back caching, stores changes in cache first and persists them later.

Example:

Match Server
|
v
Redis
|
v
Async Persistence Worker
|
v
Database
This can provide extremely fast writes.

It may be useful for high-frequency non-critical state such as:

Temporary counters
Telemetry
Activity metrics
Low-value session state
But it is dangerous for critical player economy data unless carefully engineered.

If Redis fails before persistence:

Recent writes may disappear.
For important Realtime Backend transactions, durability requirements should be understood before using write-behind designs.

Cache Invalidation Across Multiple Match Servers
Modern Realtime Backend systems usually run many server instances.

Suppose:

Match Server A
Match Server B
Match Server C
Each process may also contain a local in-memory cache.

Now the system has multiple cache layers:

Local Cache
|
Redis
|
Database
If Match Server A updates the player, it may invalidate Redis.

But Match Server B may still hold:

Player Level = 40
inside its process memory.

This creates another consistency problem.

Solutions may include:

Pub/Sub invalidation
Event bus
Short local TTL
Version checks
Centralized cache only
For example:

PlayerUpdated event
|
+--> Server A clears local cache
+--> Server B clears local cache
+--> Server C clears local cache
Multi-layer caching can improve latency, but every additional layer increases invalidation complexity.

Event-Based Cache Invalidation
A distributed Realtime Backend can publish events when persistent state changes.

Example:

PlayerProfileUpdated
player_id = 1001
Consumers then invalidate related keys:

DEL player:1001:profile
DEL player:1001:summary
Architecture:

Player Service
|
| Commit DB
v
Event Bus
|
+--> Cache Invalidator
+--> Search Index
+--> Analytics
This is useful in microservice architectures because the service performing the write does not need direct knowledge of every downstream cache.

However, event delivery must be reliable.

If the database commit succeeds but the invalidation event disappears, stale cache data may survive.

Transactional Outbox for Cache Events
One way to improve reliability is the transactional outbox pattern.

Inside one database transaction:

Update player profile

- Insert PlayerProfileUpdated event into outbox
  Then:

COMMIT
A background worker later publishes the event.

Conceptually:

Database Transaction
├── Update Player
└── Insert Outbox Event

         |
         v

Outbox Publisher
|
v
Message Bus
|
v
Cache Invalidator
If publishing fails, the outbox worker retries.

Combined with idempotent consumers, this can make cache invalidation events much more reliable.

Versioned Cache Values
Another useful technique is attaching a version number.

Database:

player_id = 1001
level = 41
version = 92
Cache:

level = 40
version = 91
The Realtime Backend can detect that cached data is older.

For example:

if cached.version < required.version:
reload from database
Version numbers can come from:

Row version
Updated timestamp
Event sequence
Logical revision
This technique can reduce the risk of an old reader overwriting newer cache state.

Compare Before Cache Write
Return to the stale repopulation race.

Server A reads:

Version 91
while Server B updates the player to:

Version 92
Before Server A writes its result to Redis, it can verify whether a newer version already exists.

If Redis contains:

Version 92
the older value should not replace it.

Conceptually:

Write cache only if:
incoming_version >= cached_version
This requires atomic comparison logic.

Redis server-side scripts or another atomic mechanism may help.

This pattern is more complex but useful when stale overwrites are unacceptable.

Cache Keys and Dependency Invalidation
One database change may affect multiple cache keys.

Suppose the player changes their nickname.

Possible cached representations include:

player:1001:profile
player:1001:summary
guild:500:members
ranking:season12:page1
friend:2002:list
Changing one field may require several invalidations.

This creates dependency management problems.

A Realtime Backend should document which cached views depend on which authoritative entities.

Without this mapping, stale data may remain hidden inside derived caches.

Namespace Versioning
For large cache structures, deleting hundreds of keys can be expensive.

Namespace versioning provides another strategy.

Instead of:

player:1001:inventory
use:

player:1001:v42:inventory
When the cache should be invalidated:

player_cache_version = 43
New requests read:

player:1001:v43:inventory
Old version 42 keys are ignored and expire naturally.

This can be useful when one logical entity has many cache keys.

However, old values still consume memory until TTL cleanup.

Cache Stampede
Cache expiration introduces another Realtime Backend problem: cache stampede.

Suppose a popular guild summary expires.

At the same moment:

5,000 requests
attempt to read it.

All receive:

CACHE MISS
and all query the database.

Instead of Redis protecting the database, expiration creates a sudden spike.

A solution is request coalescing.

Conceptually:

First request:
Acquire refresh lock
Load DB
Refresh cache

Other requests:
Wait briefly
or use stale value
This is sometimes called single-flight behavior.

Randomized TTL
If thousands of cache keys all use:

TTL = exactly 60 seconds
and are populated at the same time, they may expire together.

This creates synchronized database traffic.

A simple mitigation is TTL jitter.

Instead of:

60 seconds
use values such as:

55–65 seconds
or another reasonable randomized window.

Expiration becomes distributed over time.

This reduces burst load.

Hot Key Problems
Some cache keys may receive enormous traffic.

Example:

global:event:status
or:

ranking:season12:top100
A single Redis node serving a very hot key may become a bottleneck.

Solutions may include:

local caching;

replicated reads;

sharding;

precomputed responses;

short local TTL.

But local caching again introduces invalidation complexity.

Performance improvements should therefore be evaluated together with consistency requirements.

Negative Caching
Suppose players repeatedly search for a nonexistent username.

Without negative caching:

Request
→ Redis miss
→ DB miss
every time.

The backend can temporarily cache:

NOT_FOUND
For example:

user:unknown_name = NOT_FOUND
TTL = 30 seconds
This reduces repeated database queries.

However, negative cache TTL should usually be short.

If the player creates that username immediately afterward, a long negative TTL could temporarily report that it still does not exist.

Cache Penetration
Attackers or buggy clients may repeatedly query IDs that do not exist:

player:999999991
player:999999992
player:999999993
Because none are cached, every request reaches the database.

Negative caching, input validation, Bloom filters, and rate limiting can help protect against this pattern.

For Match Server security and stability, caching should be designed with hostile traffic in mind, not only normal players.

Cache Eviction
Redis has finite memory.

When memory pressure becomes high, keys may be evicted depending on configuration.

Suppose a Realtime Backend assumes:

player session key will always exist
but Redis evicts it.

The application must handle the missing key safely.

Cache state should generally be reconstructable unless Redis is explicitly being used as authoritative temporary storage.

If losing a key permanently corrupts player data, that key may not actually be functioning as a normal cache.

Cache and Read Replicas
Combining Redis with database read replicas requires special care.

Consider:

1. Update Primary
2. Delete Redis
3. Another request misses Redis
4. Request reads lagging replica
5. Old value written back into Redis
   Now a temporary replica delay becomes a persistent cache inconsistency.

For recently modified data, the backend may need to:

Read primary after cache invalidation
or:

Wait until replica catches up
or:

Keep session affinity to primary temporarily
This demonstrates why database replication and cache invalidation cannot be designed independently.

Cache and Database Transactions
Cache updates should generally happen after the database transaction succeeds.

Bad sequence:

Update Redis
|
v
Database transaction fails
Now the cache represents a state that was never committed.

Safer:

BEGIN
Update Database
COMMIT
Delete / Update Cache
However, the cache operation itself can still fail afterward.

Therefore, cache invalidation should ideally be retryable and recoverable.

The database remains authoritative.

What If Cache Deletion Fails?
Suppose:

Database commit = SUCCESS
Redis DEL = FAILED
The cache remains stale.

Possible safeguards include:

Short TTL
Retry queue
Invalidation event
Version check
Background repair
For high-value cached state, relying on a single DEL command with no monitoring may be insufficient.

A production Realtime Backend should track invalidation failures.

Monitoring Cache Consistency
Useful cache metrics include:

Cache Hit Rate
Cache Miss Rate
Redis Latency
Evictions
Expired Keys
Memory Usage
Invalidation Failures
Cache Refresh Latency
Hot Keys
Connection Errors
Consistency-specific telemetry may also include:

Cache Version Mismatch
Stale Read Detection
Database Fallback Count
Refresh Lock Contention
Outbox Backlog
A high cache hit rate is not always good.

If the cache is returning stale data, a 99% hit rate may actually hide a serious correctness problem.

Performance and correctness must be monitored together.

Cache Warming
After deployment or Redis restart, the cache may be empty.

If millions of players immediately generate cache misses:

Redis Restart
|
v
Cache Empty
|
v
Mass DB Queries
the database can become overloaded.

Cache warming may pre-populate high-value keys such as:

Product configuration
Popular Rankings
Global Event Data
Common Static Metadata
Player-specific data is usually loaded on demand.

Rate-limited warm-up and request coalescing can help protect the database during recovery.

How to Analyze This in Multiplayer source Code
When reviewing Multiplayer source Code, search for:

Redis
CacheManager
CacheService
getCache
setCache
invalidate
evict
expire
TTL
DEL
PlayerCache
LocalCache
Then choose one player data type.

For example:

PlayerProfile
Inventory
Wallet
Guild
Trace the read path:

Request
|
v
Read Redis?
|
v
Read Database?
|
v
Populate Cache?
Then trace the write path:

Update Request
|
v
Database Transaction
|
v
Cache Delete / Update
Ask:

Does the database update happen before invalidation?

What happens if Redis is unavailable?

Is there a TTL?

Can an old reader repopulate stale data?

Is cached data used for authoritative transactions?

Are local process caches also invalidated?

Does a database replica participate in cache reload?

Are invalidations event-driven?

Are cache keys versioned?

Can the cache be rebuilt after Redis loss?

These questions help distinguish a safe caching layer from a fragile collection of Redis calls.

When reviewing projects on the forum, cache code should always be examined together with database access and transaction logic.

A Redis folder by itself tells very little about the actual consistency model.

Common Mistakes
Caching Authoritative Economy State Without Clear Rules
Currency and inventory decisions should not rely blindly on stale values.

Deleting Cache Before Database Update
This can allow an old database value to repopulate the cache.

No TTL
A failed invalidation may leave stale data indefinitely.

Using One TTL for Everything
Different product data has different freshness requirements.

Ignoring Concurrent Readers
An in-flight old read can recreate stale cache data after invalidation.

Updating Cache Before Database Commit
The cache may expose data that never became durable.

Ignoring Redis Failures
Cache invalidation commands can fail.

The system needs a fallback strategy.

Local Cache Without Distributed Invalidation
One Match Server may keep stale values after another server performs an update.

Reloading from a Lagging Replica
A short database replication delay can repopulate Redis with old data.

Cache Stampedes
Popular key expiration can create sudden database overload.

Best Practices
Treat the database as the authoritative source unless the architecture intentionally defines otherwise.

Use cache-aside for many read-heavy Realtime Backend workloads.

For typical persistent state:

Update database first
Then invalidate cache
Use TTL as a safety net.

Choose TTL according to freshness requirements.

Do not use stale cache values to approve sensitive economic transactions.

Use versioning when stale overwrites are a major risk.

Protect high-traffic cache refreshes with single-flight or refresh locks.

Add randomized TTL to avoid synchronized expiration.

Use reliable event delivery for cross-service invalidation.

Consider transactional outbox patterns for important invalidation events.

Design local caches carefully in multi-server environments.

Monitor Redis health together with database latency and replication lag.

Test failure scenarios such as:

Redis unavailable
Redis restarted
Cache delete fails
Concurrent read during update
Replica lag
Mass key expiration
Local cache stale
Database rollback
For Multiplayer development teams studying backend projects through the forum, the key question is not whether Redis exists.

The important question is whether every cached value has a clear lifecycle from creation to invalidation and recovery.

Conclusion
Caching can dramatically improve Match Server performance.

A well-designed Redis layer can reduce:

Database queries
Network latency
Connection usage
Repeated computation
and make large MMORPG or Mobile Realtime backends much easier to scale.

But every cached copy introduces another version of simulation state.

The architecture must continuously answer:

Is this cached value still valid?
A robust Realtime Backend usually combines several techniques:

Cache-Aside
Database-First Writes
Explicit Invalidation
TTL
Versioning
Reliable Events
Request Coalescing
Monitoring
The exact combination depends on the data.

Public profiles may tolerate seconds of staleness.

Leaderboard pages may tolerate even more.

Premium currency and marketplace ownership may require authoritative reads at transaction time.

The most dangerous cache bugs are often not obvious failures.

The server continues running.

Redis responds quickly.

The database remains healthy.

But different parts of the Realtime Backend quietly disagree about the player's current state.

That is why cache invalidation should be treated as a consistency problem, not merely a performance feature.

When analyzing Multiplayer source Code, developers should trace each important value through:

Database
Redis
Local Memory
Read Replicas
Platform Services
and understand how updates propagate between them.

For practical Multiplayer development architecture, the forum can be used to examine these patterns together with sharding, read replicas, connection pooling, transactions, and distributed coordination.

A fast cache improves a Match Server only when the value returned quickly is also the value the title is allowed to trust.
