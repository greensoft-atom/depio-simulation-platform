#60 – Redis Memory Optimization for Realtime Backends: Reducing Cache Cost, Evictions, and Out-of-Memory Failures
administrator
administrator
Verified user account
02/09/2026 06:45
•
General Discussion
Redis Memory Optimization for Realtime Backends: Reducing Cache Cost, Evictions, and Out-of-Memory Failures
Introduction
Redis is widely used in modern Realtime Backend architecture because it provides extremely low-latency access to data that would otherwise require repeated database queries.

A Match Server may use Redis for:

Player sessions
Cached profiles
Guild summaries
Matchmaking queues
Leaderboards
Rate limits
Temporary counters
Online player mappings
Event state
Authentication tokens
At first, Redis can appear almost unlimited.

A small title may consume only:

2 GB
of Redis memory.

As player population grows, however, the same infrastructure may expand to:

20 GB
50 GB
100 GB
or much more.

The problem is that Redis stores most working data in memory, and memory is considerably more expensive than disk-based storage.

Poor cache design can therefore create two serious problems.

The first is cost.

A Studio may continuously add larger Redis nodes because memory consumption keeps increasing.

The second is reliability.

When Redis approaches its configured memory limit, the server may begin evicting keys, rejecting writes, or suffering severe performance problems depending on its configuration.

A dangerous failure path might look like:

Redis memory reaches limit
|
v
Important cache entries evicted
|
v
Match Servers experience cache misses
|
v
Traffic moves to database
|
v
Database load spikes
|
v
Realtime Backend latency increases
In other situations, writes may fail completely.

This can affect:

matchmaking;

login sessions;

temporary player state;

leaderboards;

distributed locks;

rate limiting;

cached database objects.

Redis memory optimization therefore should not be treated as simply reducing infrastructure cost.

It is an important part of Match Server reliability.

This article explains how Redis memory is consumed, why key count alone can be misleading, how eviction policies work, how Realtime Backend teams can reduce cache waste, and how developers can identify memory problems when analyzing Multiplayer source Code.

Why Redis Memory Usage Grows So Quickly
Redis stores more than the raw application value.

Suppose a Match Server stores:

player:1001:level = 50
The visible value may contain only a few bytes.

Redis must also maintain metadata related to:

Key name
Data structure
Object metadata
Hash table entries
Expiration information
Allocator overhead
Pointers
Therefore:

Actual Redis memory

> Raw application payload
> The difference can be substantial when the backend stores millions of small keys.

For example:

10 million tiny keys
may consume much more memory than simply multiplying the visible value size by ten million.

This is why Multiplayer development teams should measure real Redis memory usage instead of estimating capacity only from serialized product data.

Key Count vs Memory Usage
Consider two Redis designs.

Design A
player:1001:name
player:1001:level
player:1001:class
player:1001:power
player:1001:region
Five keys per player.

With:

5 million players
the theoretical key count becomes:

25 million keys
Design B
Store related values together:

player:1001
containing fields:

name
level
class
power
region
The logical information is similar, but the Redis memory footprint can be very different.

Many extremely small independent keys create repeated metadata overhead.

This does not mean every piece of player data should always be placed into one giant Redis object.

It means cache structure should be designed with both access patterns and memory overhead in mind.

Choosing the Right Redis Data Structure
Redis provides several data structures:

String
Hash
List
Set
Sorted Set
Stream
Each has different memory and performance characteristics.

A Realtime Backend should choose structures according to actual workload rather than convenience alone.

For example, a player summary might naturally fit into a hash:

player:1001:summary

level 52
power 195000
class mage
region SEA
Instead of storing:

player:1001:level
player:1001:power
player:1001:class
player:1001:region
as separate keys.

This can reduce key metadata overhead while allowing individual fields to be accessed.

However, huge hashes can create their own operational problems.

A data structure containing millions of fields should not automatically replace proper partitioning.

Serialized Objects
Another common Multiplayer source Code pattern is serializing complete player objects.

For example:

{
"player_id": 1001,
"nickname": "Knight",
"level": 52,
"power": 195000,
"region": "SEA"
}
This may be stored as:

player:1001:profile
Serialization is simple, but developers should consider format overhead.

Possible formats include:

JSON
MessagePack
Protocol Buffers
Custom binary encoding
JSON is easy to debug but often larger than binary formats.

For high-volume Redis workloads, serialized representation can meaningfully affect total memory consumption.

However, smaller binary formats introduce additional implementation complexity.

The best choice depends on:

object size;

read frequency;

debugging requirements;

language interoperability;

network cost.

Studios should measure rather than optimize blindly.

Do Not Cache Everything
One of the most common Redis mistakes is treating the cache as a second copy of the entire database.

Suppose the main player database contains:

50 million accounts
but only:

800,000
are active during a normal day.

Caching every account permanently wastes enormous memory.

A better strategy may be:

Load active player data on demand
|
v
Cache temporarily
|
v
Expire inactive players
Redis should generally contain data that benefits from fast access.

Cold historical data often belongs only in persistent storage.

A useful question is:

If this key disappears from Redis, will it likely be needed again soon?

If the answer is no, storing it indefinitely may provide little value.

TTL as a Memory Management Tool
TTL is not only a consistency mechanism.

It is also one of the most important Redis memory controls.

Suppose player profile caches are stored without expiration:

player:1001
player:1002
player:1003
...
Every player who logs in adds more cached data.

Even after those players disappear for months, their cache entries remain.

Redis memory continuously grows.

With TTL:

player:1001
TTL = 30 minutes
inactive data naturally disappears.

The pattern becomes:

Player logs in
|
Cache created
|
Player active
|
TTL refreshed if appropriate
|
Player inactive
|
Cache expires
This keeps the cache closer to the active working set.

Avoid Refreshing TTL Forever
TTL can fail to control memory if every read automatically refreshes expiration.

Suppose:

TTL = 1 hour
but every request executes:

EXPIRE key 3600
A moderately popular key may never disappear.

This may be correct for active sessions.

It may be unnecessary for static cached content.

The team should decide whether TTL means:

Expire X minutes after creation
or:

Expire X minutes after last activity
These represent different cache policies.

TTL Jitter
Suppose one million player cache entries are created during a scheduled server opening.

All use:

TTL = 3600 seconds
Exactly one hour later, a huge number of keys may expire around the same time.

The Realtime Backend can suddenly experience a burst of database reads as players regenerate those caches.

Randomized TTL can spread expiration.

Instead of:

3600 seconds
use:

3300–3900 seconds
for appropriate non-critical data.

This reduces synchronized expiration while maintaining similar cache freshness.

Understanding maxmemory
Redis can be configured with a memory limit.

Conceptually:

maxmemory = 32 GB
When Redis reaches that threshold, its behavior depends on the configured eviction policy.

This setting should provide enough headroom for normal workload variation.

Running Redis continuously at:

99% memory utilization
is dangerous.

Traffic spikes, allocator behavior, replication, background operations, or temporary data growth can push the server into an unstable state.

Capacity planning should include safety margin.

Redis Eviction Policies
When Redis reaches its configured memory limit, it may evict keys according to policy.

Common categories include strategies that:

Evict keys with TTL
Evict keys based on usage
Evict randomly
Reject new writes
The exact behavior depends on configuration.

For a Realtime Backend, the important point is that eviction policy determines what happens under memory pressure.

Consider a cache-only Redis cluster.

Evicting old cache entries may be acceptable.

Now consider Redis containing:

Session tokens
Distributed locks
Matchmaking state
Cache data
on the same instance.

Unexpected eviction becomes far more dangerous.

The system may lose data that application logic incorrectly assumed would remain available.

Cache Data vs Operational State
A strong architecture distinguishes between data categories.

Reconstructable Cache
Examples:

Public profile cache
Guild summary
Item configuration cache
Leaderboard page cache
If lost, the Realtime Backend can rebuild them.

Important Ephemeral State
Examples:

Active sessions
Match reservations
Rate limit counters
Distributed coordination
These may not be easily reconstructed.

Mixing both categories in one Redis instance with a broad eviction policy can create risk.

A Studio may prefer different Redis clusters or logical deployments for different workloads.

For example:

Redis Cache Cluster
|
+--> Eviction allowed

Redis Session Cluster
|
+--> Carefully controlled memory
This creates stronger failure isolation.

LRU and LFU Concepts
Two common eviction concepts are:

Least Recently Used
Least Frequently Used
LRU
Keys that have not been accessed recently are preferred for eviction.

This is useful when recently accessed cache data is likely to be requested again.

LFU
Keys accessed less frequently are more likely to be removed.

This can work well when a small group of keys is consistently popular.

For title caches, access patterns matter.

A player profile used heavily during one login session but never afterward may suit recency-based behavior.

Global configuration read constantly by all Match Servers should obviously remain hot.

The best eviction behavior depends on the workload.

Why Evictions Can Hurt Databases
Suppose Redis normally provides:

95% cache hit rate
The database receives only:

5% of eligible reads
Then Redis starts evicting heavily.

Cache hit rate falls to:

65%
Suddenly the database receives seven times more cache-miss traffic.

Example:

Before:
100,000 cacheable requests/sec
5,000 → Database

After:
100,000 cacheable requests/sec
35,000 → Database
The database may not have capacity for this load.

Redis memory pressure can therefore cause backend-wide failure even if Redis itself continues responding.

This is why eviction rate should be monitored alongside database query volume.

Cache Hit Rate Is Not Enough
A Realtime Backend can have:

95% hit rate
and still waste enormous amounts of memory.

Imagine:

100 GB Redis
where:

60 GB
contains cold data that is almost never accessed.

The remaining hot working set may produce nearly all cache hits.

The system could potentially use much less memory without harming performance.

Teams should analyze:

Key count
Memory per key category
Access frequency
TTL distribution
Eviction behavior
not only hit rate.

Key Naming Overhead
Key names themselves consume memory.

Consider:

match_production_player_profile_cache_player_id_100000001
versus:

pp:100000001
At a few thousand keys, the difference is irrelevant.

At:

50 million keys
long repeated prefixes may consume meaningful memory.

That does not mean every Redis key should become unreadable.

Clear naming is valuable for debugging.

But extremely verbose names across millions of objects can create unnecessary overhead.

A reasonable namespace might be:

player:1001:profile
rather than a giant application-style identifier.

Avoid Duplicate Cached Representations
The same player may accidentally be cached in multiple formats.

For example:

player:1001
player_profile:1001
user_summary:1001
player_public:1001
Some duplication may be intentional because each representation serves a different endpoint.

But uncontrolled duplication wastes memory and increases invalidation complexity.

Multiplayer development teams should maintain an inventory of major cache models.

For every representation, ask:

Who writes this?
Who reads this?
How large is it?
How long does it live?
How is it invalidated?
Caches without clear ownership often become permanent memory waste.

Leaderboard Memory Usage
Redis sorted sets are extremely useful for rankings.

A seasonal leaderboard might contain:

10 million players
Each entry stores:

player_id
score
Multiple ranking dimensions can multiply memory consumption.

For example:

ranking:power
ranking:pvp
ranking:level
ranking:guild
ranking:event1
ranking:event2
If every player is included in every sorted set, memory usage can become substantial.

A Realtime Backend should determine whether it truly needs the entire population in Redis.

Maybe only:

Top 100,000
players need realtime ranking.

Lower-ranked data could remain in a database or analytical system.

The correct architecture depends on match mechanics.

Temporary Event Data
Limited-time events can create large amounts of temporary Redis data.

Example:

event:summer:player:1001
event:summer:player:1002
...
When the event ends, those keys may become useless.

If they do not have TTL or cleanup logic, Redis continues storing them indefinitely.

Every event system should define a lifecycle:

Event begins
|
Create temporary keys
|
Event runs
|
Event ends
|
Archive required results
|
Delete / expire temporary data
Seasonal Multiplayer source Code frequently accumulates memory leaks because temporary keys are never removed after old events.

Session Memory Optimization
Session objects often contain more data than necessary.

A badly designed session might store:

Complete player profile
Inventory
Quest state
Guild data
Authentication information
Device information
Feature flags
Large configuration payload
But the gateway may only need:

player_id
session_id
server_id
login_time
permissions
Large session objects multiply quickly with concurrency.

For:

1 million concurrent players
saving even:

1 KB per session
reduces memory by roughly:

1 GB
before additional internal overhead.

Session schemas should therefore be intentionally minimal.

Compression
Compression can reduce memory usage for large cached payloads.

For example:

JSON payload = 20 KB
Compressed = 5 KB
This may be worthwhile for large read-heavy objects.

However, compression introduces CPU cost.

The Match Server must:

Compress on write
Decompress on read
For very small objects, compression may cost more CPU than the memory saved.

Compression should be tested using realistic payload sizes and request rates.

Redis Memory Fragmentation
Application data may not account for all resident memory.

Memory allocators can experience fragmentation.

Conceptually:

Redis logical dataset:
20 GB

Process resident memory:
25 GB
The difference may come from:

Allocator fragmentation
Temporary buffers
Replication buffers
Client buffers
Internal structures
This is another reason to monitor real process memory rather than only dataset size.

If memory fragmentation grows unusually high, operators should investigate workload patterns and Redis configuration instead of assuming every byte belongs to useful cached values.

Large Commands and Temporary Memory
Some commands may temporarily require additional memory.

Examples include operations involving:

Large sorted sets
Large hashes
Large replies
Bulk serialization
A Realtime Backend running extremely close to its memory ceiling has little room for temporary allocations.

Even if the steady-state dataset technically fits, certain workloads may push the process into memory pressure.

Maintaining headroom is therefore important.

Big Keys
Big keys deserve special attention.

Examples:

guild:all_members
online_players
global_ranking
event:all_participants
A single huge data structure may consume hundreds of megabytes or more.

Big keys can cause:

memory concentration;

expensive network responses;

blocking or expensive operations;

difficult deletion;

failover overhead.

Large product data should often be partitioned.

For example:

online:region:asia
online:region:eu
online:region:na
or:

event:participants:00
event:participants:01
...
event:participants:63
Partitioning can improve both memory management and traffic distribution.

Hot Keys and Memory Optimization
The previous article discussed Redis hot keys.

Hot-key optimization and memory optimization sometimes conflict.

For example, adding local caches may reduce Redis request load but duplicate data across many Match Server processes.

Likewise, replicating a hot value across several Redis keys may distribute traffic but consume more memory.

Architecture decisions should therefore balance:

Memory
CPU
Network
Latency
Consistency
There is rarely one optimization that improves every dimension simultaneously.

Redis Cluster Memory Balance
In Redis Cluster, total memory may appear healthy while one node is nearly full.

Example:

Node A: 93%
Node B: 52%
Node C: 48%
Node D: 46%
The cluster-wide average looks safe.

But Node A may begin evicting keys soon.

Possible causes include:

Uneven key distribution
Large keys
Hot partitions
Hash tag usage
Different key sizes
Monitoring should therefore be per node.

Cluster capacity is limited by the most constrained node, not only by total RAM.

Memory and Replicas
Replication multiplies memory requirements.

Suppose one Redis primary stores:

32 GB
and has two replicas.

The architecture may consume roughly the dataset across:

Primary
Replica A
Replica B
plus overhead.

The redundancy is valuable for availability, but it means a 32 GB logical dataset may require much more than 32 GB of infrastructure memory.

This should be included in Realtime Backend cost planning.

Persistence Overhead
Some Redis deployments use persistence mechanisms for recovery.

Depending on configuration and workload, persistence can create additional:

Disk I/O
Fork-related memory pressure
Copy-on-write overhead
Operational teams should understand how snapshotting or append-only persistence interacts with available memory.

A Redis node operating near its RAM limit may have less safety margin during background persistence activity.

Critical Match Server systems should test persistence behavior under production-like memory usage rather than only during development.

Preventing Cache Memory Leaks
A cache memory leak occurs when the Realtime Backend continuously creates keys that never expire and are never deleted.

Example:

request:1000001
request:1000002
request:1000003
...
If every request creates a permanent key, memory grows forever.

Common sources include:

Old sessions
Expired matchmaking rooms
Finished events
Temporary locks
Request deduplication keys
Rate-limit records
Disconnected player mappings
Every temporary key should have a clear cleanup mechanism.

Usually:

TTL
is safer than depending exclusively on application cleanup.

If a Match Server crashes before calling DEL, TTL still removes the key later.

Idempotency Records in Redis
Recent articles discussed idempotency.

Short-term request deduplication may use Redis keys such as:

request:req_918271
If these keys never expire, traffic can produce enormous memory growth.

An API handling:

50,000 requests/sec
would generate:

4.32 billion request records/day
if every request created a permanent entry.

Obviously, these records need bounded retention.

For temporary network retry protection, a TTL may be sufficient.

Permanent high-value transaction history should be stored in a durable database instead of Redis forever.

Distributed Lock Cleanup
Distributed lock keys should also have expiration.

Without TTL:

Server acquires lock
Server crashes
Lock remains
This creates both correctness and memory problems.

Lock keys are usually small, but a backend generating millions of orphaned locks can still accumulate unnecessary data.

Correct expiration is therefore part of both lock safety and memory hygiene.

Monitoring Redis Memory
Important metrics include:

Used Memory
Resident Memory
Peak Memory
Memory Fragmentation
Key Count
Evictions
Expired Keys
Hit Rate
Miss Rate
Memory per Node
Memory Growth Rate
Product-specific metrics should add:

Player Cache Keys
Session Keys
Event Keys
Leaderboard Size
Matchmaking Keys
Temporary Lock Count
Request Deduplication Keys
This allows teams to answer:

Which feature is consuming memory?
instead of only knowing:

Redis is full.
Memory Growth Rate
Absolute memory is useful.

Growth rate is often more important.

Suppose:

Monday: 30 GB
Tuesday: 32 GB
Wednesday: 34 GB
Thursday: 36 GB
Traffic remained stable.

This strongly suggests unbounded key accumulation.

The Studio should investigate before the node reaches its limit.

Alerts should therefore include trends such as:

GB/day
Keys/day
Evictions/minute
not just static thresholds.

Memory Profiling by Key Namespace
A useful operational strategy is grouping keys by prefix.

For example:

player:_
guild:_
session:_
event:_
ranking:_
match:_
lock:\*
Then estimate:

Number of keys
Average size
Total size
TTL distribution
This can reveal something like:

player:_ → 35 GB
session:_ → 8 GB
event:_ → 22 GB
ranking:_ → 6 GB
If an expired event consumes 22 GB, optimization becomes obvious.

Without namespace analysis, teams may waste time changing unrelated systems.

How to Analyze This in Multiplayer source Code
When reviewing Multiplayer source Code, first locate Redis client initialization and cache services.

Search for:

Redis
CacheManager
RedisManager
CacheService
RedisClient
Jedis
Lettuce
StackExchange.Redis
Then search for key-building functions.

Examples:

getPlayerKey()
getSessionKey()
rankingKey()
eventKey()
lockKey()
Identify which keys include expiration.

Look for calls such as:

EXPIRE
SETEX
SET ... EX
TTL
PEXPIRE
Then look for permanent writes:

SET
HSET
ZADD
SADD
without obvious cleanup.

For each namespace, ask:

How many keys can exist?
How large can each value become?
Does it expire?
Who deletes it?
What happens if cleanup fails?
Trace temporary match systems carefully.

For example:

Create Match
|
v
Create Redis Room Data
|
v
Match Ends
|
v
Is Redis Data Deleted?
Or:

Player Logs In
|
v
Create Session
|
v
Disconnect
|
v
Does session expire?
When studying Multiplayer source Code on the forum, Redis memory analysis can reveal whether a backend was designed for long-running production use or only tested during short development sessions.

A memory leak may remain invisible during a two-hour local test but become critical after several months of live operation.

Common Mistakes
No TTL on Temporary Keys
Sessions, locks, matchmaking state, and request records can accumulate indefinitely.

Caching the Entire Database
Cold inactive player data consumes expensive memory without providing meaningful benefit.

One Key Per Tiny Field
Millions of tiny keys can create substantial metadata overhead.

Extremely Large Objects
Huge hashes, lists, or sorted sets can become difficult to manage.

Using Redis as Permanent Transaction History
Durable historical records usually belong in persistent databases.

Mixing Cache and Critical Ephemeral State
Eviction behavior that is safe for caches may be dangerous for sessions or distributed coordination.

Ignoring Memory Per Replica
Replication multiplies infrastructure memory requirements.

Running Near 100% Capacity
There is insufficient headroom for spikes and internal operations.

No Cleanup After Events
Seasonal data often becomes permanent accidental memory usage.

Monitoring Only Hit Rate
A high hit rate does not mean memory is being used efficiently.

Best Practices
Classify every Redis dataset by purpose:

Reconstructable Cache
Session State
Coordination
Leaderboard
Temporary Event Data
Use separate Redis deployments when workloads require different durability or eviction behavior.

Apply TTL to temporary data.

Use TTL jitter where synchronized expiration could create database spikes.

Cache only data that benefits from repeated fast access.

Keep session payloads small.

Avoid uncontrolled duplicate representations of the same player state.

Measure serialized object sizes.

Use appropriate Redis data structures.

Partition oversized collections.

Monitor key count and memory by namespace.

Track memory growth trends.

Maintain capacity headroom.

Configure eviction policy according to workload rather than using defaults blindly.

Monitor evictions together with:

Database traffic
Cache misses
Match Server latency
Test what happens when Redis reaches its memory limit.

For Multiplayer development teams working with the forum projects, always inspect whether temporary Redis keys have bounded lifetimes and whether cache growth is proportional to active players rather than all historical players.

Conclusion
Redis memory optimization is not just about reducing RAM bills.

It is about keeping the Realtime Backend predictable when player population, events, and cached data continue growing.

A healthy Redis architecture should contain a controlled working set:

Frequently used data
Active sessions
Current events
Useful temporary state
rather than becoming a permanent warehouse for every object ever created.

The most effective optimizations usually come from simple architectural questions:

Does this key need to exist?
Does it need to exist this long?
Does it need to contain this much data?
Can it be reconstructed?
Can related values share a more efficient structure?
Memory problems often originate in Multiplayer source Code rather than Redis itself.

A forgotten TTL, an oversized session object, an old event namespace, or a duplicated player cache can consume more memory than any low-level configuration optimization can recover.

For production Match Server infrastructure, teams should monitor:

Memory usage
Growth rate
Key count
Evictions
Expired keys
Fragmentation
Cache hit rate
Database fallback
together.

On the forum, developers analyzing Multiplayer development projects can use these principles to understand whether Redis is functioning as a controlled performance layer or slowly becoming an expensive and fragile secondary database.

A scalable Redis architecture is not the one that stores the most data in memory.

It is the one that stores only the data worth keeping there, for exactly as long as the Realtime Backend needs it.
