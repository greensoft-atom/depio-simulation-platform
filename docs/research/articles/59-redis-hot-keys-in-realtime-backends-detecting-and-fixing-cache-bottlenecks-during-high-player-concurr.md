#59 – Redis Hot Keys in Realtime Backends: Detecting and Fixing Cache Bottlenecks During High Player Concurrency
administrator
administrator
Verified user account
02/09/2026 06:40
•
General Discussion
Redis Hot Keys in Realtime Backends: Detecting and Fixing Cache Bottlenecks During High Player Concurrency
Introduction
Redis is commonly used in modern Realtime Backend architecture because it provides extremely fast access to frequently requested data.

A Match Server may use Redis for:

Player sessions
Matchmaking state
Leaderboards
Guild summaries
Event configuration
Rate limits
Temporary counters
Cached profiles
Online player mappings
In a healthy system, traffic is distributed across many cache keys.

For example:

player:1001
player:1002
player:1003
player:1004
...
Each key receives a relatively small amount of traffic.

But some features naturally create extremely popular keys.

Imagine a global event configuration stored as:

event:summer_2026:status
Every connected Client may indirectly request this information.

Or a leaderboard endpoint may repeatedly access:

ranking:season_12:top100
With hundreds of thousands of concurrent players, one Redis key can receive a huge percentage of total cache traffic.

This is called a hot key.

A hot key can become a bottleneck even when the Redis cluster has plenty of total capacity.

For example:

Redis Cluster

Node A:
CPU 95%
120,000 requests/sec

Node B:
CPU 22%
18,000 requests/sec

Node C:
CPU 19%
17,000 requests/sec
The cluster is not evenly overloaded.

One key or a small group of keys may be concentrating traffic on Node A.

This creates an important Multiplayer development problem:

Horizontal scaling does not help if most requests continue reaching one logical cache key on one node.

Hot keys can cause:

latency spikes;

increased Redis CPU;

network saturation;

overloaded cluster nodes;

request timeouts;

Match Server thread backlog;

cascading database traffic;

unstable leaderboards;

slow global events.

For MMORPG and Mobile Systems with large concurrent populations, Redis hot-key analysis is therefore an important part of backend scalability.

This article explains how hot keys appear, how they differ from big keys, how to detect them, and how Match Server architecture can reduce concentrated cache traffic safely.

What Is a Redis Hot Key?
A hot key is a Redis key that receives disproportionately high request traffic.

For example:

Key A → 20 requests/sec
Key B → 35 requests/sec
Key C → 18 requests/sec
Key D → 90,000 requests/sec
Key D is clearly hot.

The issue is not necessarily the size of the value.

A key containing:

"ACTIVE"
may be only a few bytes.

But if hundreds of thousands of Match Server requests access it repeatedly, it can consume substantial CPU and network bandwidth.

A hot key is therefore defined primarily by access frequency, not value size.

Hot Keys vs Big Keys
These concepts are often confused.

Hot Key
A key receiving extremely high request volume.

Example:

global:event:status
Value:

"OPEN"
Size:

4 bytes
Traffic:

80,000 GET/sec
Big Key
A key containing a large amount of data.

Example:

guild:500:members
containing:

100,000 entries
A key can be:

hot but small
big but rarely accessed
both hot and big
The last situation is particularly dangerous.

A very large sorted set queried thousands of times per second may consume significant CPU, memory bandwidth, and network throughput.

Realtime Backend engineers should therefore monitor both access frequency and value size.

Why Titles Create Hot Keys Naturally
Online titles contain many shared systems.

Unlike player-specific data, these systems may be accessed by nearly everyone.

Examples include:

Global Event State
World Boss HP
Season Configuration
Server Population
Cross-Server Ranking
Featured Shop
Announcement Data
Guild Ranking
Matchmaking Queue Counters
Suppose 500,000 players are online.

Each client requests event state once every 10 seconds.

That creates roughly:

500,000 / 10
= 50,000 requests/sec
for one logical value.

If several Match Server processes independently query Redis for every client request, the traffic can become substantial.

A Typical Hot-Key Architecture
Consider:

Clients
|
v
Match Server Cluster
|
v
Redis Cluster
Suppose every Match Server repeatedly performs:

GET event:world_boss:hp
The backend may have:

100 Match Server instances
but every instance sends requests to the Redis node owning that key.

Conceptually:

Server 1 -----\
Server 2 ------\
Server 3 -------\
... > Node A → world_boss:hp
Server 100 -----/
Adding more Match Servers may increase traffic on that same Redis node.

This is why application horizontal scaling can sometimes make a cache bottleneck worse.

Redis Cluster Does Not Automatically Solve Hot Keys
Redis Cluster distributes keys across hash slots.

Different keys can live on different nodes.

For example:

player:1001 → Node A
player:1002 → Node C
player:1003 → Node B
This works well when traffic is spread across many keys.

But one key belongs to one hash slot.

A single key such as:

ranking:global
does not automatically become distributed across every Redis primary.

Therefore:

More Redis nodes
does not necessarily mean:

One hot key gets more capacity.
The data model often needs to change before the workload can scale horizontally.

Symptoms of a Hot Key
A Realtime Backend experiencing hot-key pressure may show several symptoms.

Uneven Redis Node CPU
Example:

Node A: 94%
Node B: 31%
Node C: 28%
Uneven Network Traffic
One node may transmit much more data than others.

Increased Redis Latency
Commands that normally complete in sub-millisecond time may become slower.

Match Server Latency Spikes
Backend services waiting for Redis start accumulating requests.

Database Traffic Increases
If cache operations fail or time out, application code may fall back to the database.

This can produce:

Redis overload
|
v
Cache misses/timeouts
|
v
Database fallback
|
v
Database overload
This is a dangerous cascading failure.

Detecting Hot Keys
Hot-key detection should combine Redis-level and application-level observability.

Useful signals include:

Commands per second
CPU per Redis node
Network bytes per node
Latency
Key access frequency
Slow operations
Cache hit rate
Application endpoint traffic
If one Redis node is consistently much hotter than others, inspect the keys mapped to that workload.

Match Server logs and tracing can also expose repeated calls such as:

GET ranking:season_12
GET ranking:season_12
GET ranking:season_12
...
Application instrumentation is especially useful because it can identify which play feature generates the traffic.

Command-Level Monitoring
Suppose Redis metrics show:

GET: 400,000 operations/sec
ZREVRANGE: 85,000 operations/sec
HGET: 20,000 operations/sec
The unusually high ZREVRANGE rate may point toward leaderboard traffic.

The Realtime Backend team can then inspect endpoints such as:

GET /ranking/top
GET /ranking/friends
GET /ranking/global
This narrows the investigation.

Infrastructure metrics tell engineers where pressure exists.

Application metrics explain why it exists.

Leaderboards as a Hot-Key Example
Leaderboards are a classic Realtime Backend use case for Redis sorted sets.

A global ranking may use:

ranking:season_12
with:

member = player_id
score = ranking_score
The system repeatedly queries:

Top 100 players
Player rank
Nearby players
This can work extremely well.

But if millions of players frequently request the same top-100 list, one sorted set becomes a hot key.

A simple solution is not necessarily to abandon Redis.

Instead, the backend can cache the result closer to the application.

Local In-Memory Caching
Suppose the top 100 ranking changes only every few seconds.

Instead of:

Every client request
→ Redis
each Match Server can maintain a short-lived local copy:

Match Server Local Cache
TTL = 2 seconds
Then the flow becomes:

Client Request
|
v
Match Server Local Memory
|
+--> HIT → Return
|
+--> MISS → Redis
If 100 Match Server processes each refresh once every two seconds:

100 / 2
= 50 Redis requests/sec
instead of tens of thousands per second.

This can dramatically reduce hot-key pressure.

Tradeoff: Local Cache Consistency
Local caching introduces another cache layer.

Suppose the leaderboard changes at:

12:00:00
One Match Server refreshes at:

12:00:00.2
another refreshes at:

12:00:01.7
Players may briefly see slightly different rankings.

For leaderboard display, this may be acceptable.

For:

Premium currency balance
it usually would not be.

Therefore, local caching should be used only when the data's consistency requirements allow it.

Precomputed Responses
Another useful technique is caching the final API response.

Instead of reconstructing:

Top 100

- Player Names
- Guild Names
- Avatars
- Power
  for every request, the backend can generate:

ranking:season12:top100:response
periodically.

The response might already contain the serialized structure needed by the Client.

This reduces:

Redis operations;

database lookups;

serialization work;

Match Server CPU.

A background worker can refresh the response every few seconds.

For display-heavy features, this is often more efficient than computing the result per user request.

Request Coalescing
Suppose a popular cache key expires.

Immediately:

10,000 Match Server requests
attempt to rebuild it.

Without protection:

10,000 Redis misses
|
v
10,000 DB queries
Request coalescing allows one worker to rebuild the cache.

Conceptually:

First Request
|
Acquire Refresh Lock
|
Load Data
|
Populate Cache

Other Requests
|
Wait briefly
or use stale value
This is sometimes called single-flight behavior.

It is particularly useful for hot keys because many requests can arrive during the same short window.

Serving Stale Data During Refresh
For non-critical data, a Realtime Backend can intentionally serve slightly stale information while one worker refreshes it.

For example:

Leaderboard Cache
Fresh TTL = 5 sec
Stale Window = 30 sec
When the fresh TTL expires:

Request 1
→ triggers background refresh

Requests 2–5000
→ continue receiving old value
After refresh:

New value replaces old value
This prevents a cache stampede and keeps latency stable.

This pattern should not be used for transactions that require the latest authoritative state.

Splitting One Hot Key into Multiple Keys
Sometimes the data itself can be partitioned.

Instead of:

ranking:global
the backend may use:

ranking:region:asia
ranking:region:europe
ranking:region:na
ranking:region:sa
Traffic is distributed because different keys may map to different Redis nodes.

Similarly:

guild_rank:all
may become:

guild_rank:server_1
guild_rank:server_2
guild_rank:server_3
This is effective when play naturally has regions, shards, or server groups.

Sharding Counters
A globally incremented counter can become hot.

Suppose every battle increments:

event:kills:global
At massive scale, one counter can receive huge write traffic.

A sharded counter approach may use:

event:kills:0
event:kills:1
event:kills:2
...
event:kills:31
Each Match Server selects a bucket.

The total becomes:

SUM(all buckets)
This distributes write load.

The tradeoff is that reads require aggregation.

For high-frequency writes and relatively infrequent reads, this can work well.

World Boss HP as a Hot Write Key
A realtime world boss may create another problem.

Suppose:

100,000 players
deal damage continuously.

Updating:

boss:1001:hp
for every hit would create enormous write traffic.

A better architecture may aggregate damage locally.

Example:

Battle Server 1:
Accumulate 35,000 damage

Battle Server 2:
Accumulate 42,000 damage

Battle Server 3:
Accumulate 31,000 damage
Every short interval:

Flush aggregated damage
instead of writing for every attack.

This changes:

100,000 writes/sec
into perhaps:

100–1,000 aggregated writes/sec
depending on the architecture.

For Multiplayer development, application-level aggregation is often more valuable than infrastructure-level scaling.

Pub/Sub for Shared State Updates
Instead of every Match Server polling Redis:

GET event:status
every second, the backend may publish updates.

Architecture:

Event Service
|
v
Publish EventStatusChanged
|
v
Match Servers
Each server maintains a local copy.

This transforms:

constant polling
into:

update only when state changes
If event status changes only once every several minutes, this can eliminate enormous unnecessary Redis traffic.

However, Pub/Sub delivery characteristics must be considered carefully.

Critical persistent state may require a more durable messaging mechanism.

Polling Frequency Matters
A common source of hot keys is excessive polling.

Suppose a Client requests:

/global-event/status
every second.

For:

300,000 clients
that creates enormous traffic.

But if event state changes every 10 minutes, one-second polling is unnecessary.

The Realtime Backend may instead:

increase polling interval;

push updates;

cache responses;

include event state in another periodic message.

Reducing unnecessary request frequency is often the simplest hot-key optimization.

Hot Keys Created by Match Server Code
Sometimes the problem is not player traffic.

A bug may cause every Match Server process to query the same Redis key in a tight loop.

Example:

while true:
redis.get("config:current_event")
Without sleep or local caching, one process may generate thousands of requests per second.

Multiplied by 100 server processes, this becomes a major Redis workload.

When analyzing Multiplayer source Code, repeated polling loops should therefore be examined carefully.

Cache Key Design
A poorly designed key can concentrate unrelated traffic.

Suppose the backend stores every player's server status in one large hash:

online_players
and executes many operations against this one key.

Even if the hash contains millions of fields, Redis Cluster still treats the entire hash as one key.

All operations go to one node.

A more distributed design might use:

online:region:asia
online:region:eu
online:region:na
or even finer partitions.

The optimal design depends on the access pattern.

Data modeling for Redis should consider both memory structure and traffic distribution.

Hash Tags and Accidental Concentration
Redis Cluster can use hash tags to deliberately place multiple keys in the same slot.

For example:

player:{1001}:profile
player:{1001}:inventory
player:{1001}:wallet
This can be useful when related keys need same-slot operations.

But overusing the same hash tag can accidentally concentrate traffic.

For example:

ranking:{global}:asia
ranking:{global}:europe
ranking:{global}:na
all share:

{global}
and therefore may map to the same slot.

The key names look distributed, but the cluster placement is not.

When reviewing Redis configuration in Multiplayer source Code, hash-tag usage should be inspected carefully.

Replicas for Read Scaling
Redis replicas can sometimes help distribute read-heavy workloads.

For data that tolerates replication delay, reads may be sent to replicas.

Example:

Primary
├── Replica A
├── Replica B
└── Replica C
However, this introduces eventual consistency.

A recently updated value may not immediately appear on every replica.

For:

leaderboard display
this may be acceptable.

For:

reward claim state
it may not be.

Read scaling should therefore be based on consistency requirements.

Hot-Key Failover Problem
Suppose a Redis primary hosting a hot key fails.

A replica is promoted.

Immediately, the new primary receives the entire workload:

100,000+ requests/sec
while also recovering from failover.

This can create another failure.

Hot keys therefore reduce failover safety.

Even if the cluster can technically promote replicas, the replacement node must have enough spare capacity to absorb concentrated traffic.

Capacity planning should include failure scenarios, not only normal operation.

Cascading Failure to Database
One dangerous architecture is:

Redis GET
|
Timeout
|
Fallback to MySQL
This seems resilient.

But if a hot key causes Redis overload, thousands of requests may simultaneously fall back to the database.

Suppose Redis normally handles:

80,000 reads/sec
for one leaderboard.

During Redis trouble:

80,000 reads/sec → Database
The database may collapse.

A safer fallback strategy may include:

stale local cache;

rate limiting;

temporary degraded response;

request coalescing;

feature disablement.

Not every cache failure should become a database query.

Rate Limiting Hot Features
Some features do not need to respond to unlimited request volume.

Suppose one client repeatedly requests:

Top 100 Ranking
100 times per second.

The backend can limit:

1 request every few seconds
per player or session.

This protects:

Match Server
Redis
Network bandwidth
from abusive or buggy clients.

Rate limiting is therefore not only a security feature.

It can also protect hot cache workloads.

Monitoring Hot-Key Risk
A useful Redis monitoring dashboard should include:

Operations/sec
Latency
CPU
Network In/Out
Memory
Evictions
Cache Hit Rate
Connection Count
Key Access Distribution
Product-specific metrics might include:

Leaderboard Requests/sec
World Boss Updates/sec
Event Status Reads/sec
Guild Ranking Reads/sec
Matchmaking Counter Updates/sec
Correlating the two layers makes diagnosis much faster.

For example:

Redis Node A CPU spike
|
v
ZREVRANGE spike
|
v
Leaderboard endpoint traffic spike
The root cause becomes obvious.

Tracing Hot Keys from Player Requests
Distributed tracing can identify the path:

Client
|
v
API Gateway
|
v
Ranking Service
|
v
Redis
A trace may show:

HTTP request: 180 ms

Ranking Service:
Redis ZREVRANGE = 145 ms
This reveals that Redis, rather than the Match Server business logic, dominates latency.

Tracing is particularly useful when backend services have several caching layers and database fallbacks.

How to Analyze This in Multiplayer source Code
When reviewing Multiplayer source Code, search for Redis key definitions.

Common patterns include:

RedisKey
CacheKey
RankingKey
GlobalKey
EventKey
GuildKey
SessionKey
Look for fixed global keys such as:

global_rank
world_boss
current_event
server_status
online_players
Then determine how frequently they are accessed.

Search for code such as:

redis.get(globalKey)
inside:

request handlers
timers
update loops
heartbeat loops
A useful workflow is:

Identify global key
|
v
Find every reader/writer
|
v
Estimate call frequency
|
v
Multiply by Match Server instance count
|
v
Estimate total Redis traffic
For example:

100 server instances
×
20 reads/sec
=
2,000 reads/sec
from a background poll alone.

Then add player-triggered requests.

When studying Multiplayer source Code through the forum, this type of analysis can reveal backend scalability problems that are invisible during a local test with only one Match Server.

Common Mistakes
Assuming Redis Is Too Fast to Become a Bottleneck
Redis is fast, but any system has finite CPU and network capacity.

Adding More Redis Nodes Without Changing the Key
One hot key still belongs to one primary slot.

Confusing Hot Keys with Big Keys
Traffic frequency and value size are different problems.

Polling Shared Data Too Frequently
If a value changes once per minute, checking it every 100 milliseconds is wasteful.

No Local Cache for Read-Only Global Data
Every request unnecessarily reaches Redis.

One Global Counter for Massive Write Traffic
High-frequency writes may need partitioning or aggregation.

Falling Back Directly to Database
Redis overload can become database overload.

Ignoring Cluster Hash Tags
Multiple logical keys may accidentally map to one slot.

Fixed TTL for Popular Keys
Simultaneous expiration can create cache stampedes.

No Product-level Metrics
Infrastructure metrics alone may not identify which feature creates the load.

Best Practices
Identify high-frequency shared keys before production traffic becomes large.

Monitor Redis traffic per node and correlate it with features.

Use short-lived local caches for display-oriented global data.

Prefer push-based updates over aggressive polling when state changes infrequently.

Precompute popular responses such as:

Top Rankings
Event Status
Featured Shop Data
Use request coalescing when rebuilding hot cache entries.

Use stale-while-refresh strategies for data that tolerates temporary staleness.

Partition naturally partitionable data by:

Region
Server
Season
Shard
Category
Use sharded counters for extremely high write rates.

Aggregate high-frequency match events before flushing them to Redis.

Use rate limiting to protect expensive shared features.

Avoid automatic database fallback during large cache failures unless the database has enough capacity.

Test Redis node failure while hot-key traffic is active.

Review cluster hash tags carefully.

Monitor not only average Redis latency, but also:

Per-node CPU
Per-node network
Command rates
Hot feature traffic
Failover capacity
For Multiplayer development teams reviewing projects on the forum, Redis scalability should be analyzed at the key-access-pattern level rather than only by looking at how many Redis servers are configured.

Conclusion
Redis can process enormous workloads, which makes it an excellent component for Match Server infrastructure.

But distributed capacity is useful only when traffic is also distributed.

A Realtime Backend may have:

10 Redis nodes
and still suffer from one overloaded node because millions of requests target:

one hot key
The solution is often not simply larger hardware.

The solution is to change how the title accesses shared state.

Effective strategies include:

Local Caching
Request Coalescing
Response Precomputation
Partitioned Keys
Sharded Counters
Event Aggregation
Push Updates
TTL Jitter
Rate Limiting
The correct approach depends on the play feature.

Global rankings may tolerate a few seconds of stale data.

World boss updates may require aggregation.

Event configuration may be pushed to Match Servers instead of polled.

Premium transactions may require a completely different consistency model.

When analyzing Multiplayer source Code, developers should therefore ask not only:

What is stored in Redis?
but also:

How often is this exact key accessed,
and how many Match Server instances access it?
That question becomes increasingly important as player concurrency grows.

For practical Realtime Backend engineering, the forum can be used to study Redis usage alongside database caching, sharding, read replicas, service discovery, and distributed coordination.

A cache architecture scales well when both data and traffic can be distributed.

If every player ultimately depends on the same Redis key, the backend may have created a single-server bottleneck inside an otherwise distributed system.
