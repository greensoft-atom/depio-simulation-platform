#53 – Distributed Locks in Realtime Backends: Preventing Duplicate Rewards, Race Conditions, and Multi-Server Data Conflicts
administrator
administrator
Verified user account
01/09/2026 17:40
•
General Discussion
Distributed Locks in Realtime Backends: Preventing Duplicate Rewards, Race Conditions, and Multi-Server Data Conflicts
Introduction
Distributed Realtime Backend systems create a problem that does not exist in the same form inside a single-process application: multiple Match Server instances may attempt to modify the same player data at nearly the same time.

Consider a player who owns:

Gold: 10,000
Two requests arrive almost simultaneously:

Request A → Upgrade weapon for 8,000 gold
Request B → Purchase armor for 7,000 gold
If two Match Server processes independently read the same balance, both may see:

Gold = 10,000
and both may approve their transaction.

The final result could become inconsistent:

Weapon upgraded
Armor purchased
Gold = -5,000
or, depending on poorly designed database logic, the final gold balance might even become incorrect in another direction.

Similar race conditions can affect:

reward claiming;

inventory updates;

marketplace purchases;

guild operations;

player matchmaking;

item enhancement;

auction systems;

premium currency;

daily login rewards;

payment callbacks.

A common way to coordinate these operations is through distributed locking.

A distributed lock allows multiple Match Server instances to agree that only one process may perform a protected operation for a particular resource at a particular moment.

Conceptually:

Match Server A
|
| Acquire Lock: player:1001
v
Distributed Lock System
|
+--> LOCK GRANTED

Match Server B
|
| Acquire Lock: player:1001
v
Distributed Lock System
|
+--> LOCK BUSY
Distributed locks can be extremely useful, but they are also frequently misunderstood.

A lock does not automatically make a Realtime Backend safe.

Incorrect expiration settings, lock ownership bugs, network partitions, retry logic, database transaction mistakes, and long-running critical sections can still produce serious failures.

This article explains how distributed locks work in real Multiplayer development environments, when they should be used, when they should be avoided, and how developers can identify locking strategies when analyzing Multiplayer source Code.

Why Race Conditions Appear in Realtime Backends
A race condition happens when multiple operations depend on shared state and the final result depends on execution timing.

Suppose a player can claim a daily reward once.

The backend stores:

reward_claimed = false
Two requests arrive at nearly the same moment:

Server A → Check reward_claimed
Server B → Check reward_claimed
Both see:

false
Server A grants:

+500 Gems
Server B also grants:

+500 Gems
Only afterward do both servers update:

reward_claimed = true
The database finally looks correct.

But the player received the reward twice.

This is a classic check-then-act race condition.

The important lesson is:

Checking a condition and modifying the corresponding state must often happen atomically or inside a correctly protected consistency boundary.

Why Single-Process Locks Are Not Enough
Inside one server process, developers can use:

mutex
synchronized
lock
critical section
semaphore
depending on the programming language.

For example:

lock(playerId)
{
claimReward();
}
This may work if every request for that player is processed by the same process.

But consider a distributed deployment:

Match Server A
Match Server B
Match Server C
Match Server D
Each process has its own memory.

A mutex on Server A does not prevent Server B from running the same operation.

Conceptually:

Server A Memory
Player Lock = LOCKED

Server B Memory
Player Lock = FREE
Both processes believe they control the resource.

A distributed lock stores coordination state somewhere visible to all participating servers.

Basic Distributed Lock Architecture
A simplified architecture may look like:

Match Server A ----\
Match Server B -----\
Match Server C ------> Lock Coordinator
Match Server D -----/
|
v
Shared Lock State
The lock system may be implemented using infrastructure such as:

Redis;

a relational database;

etcd;

ZooKeeper;

Consul;

another coordination service.

The exact choice depends on requirements.

The Realtime Backend might generate keys such as:

lock:player:1001
lock:guild:528
lock:auction:9182
lock:reward:1001:daily_20260901
Only one lock owner should be able to hold a particular key at a time.

Locking a Player Operation
Suppose the backend needs to safely upgrade equipment.

The workflow might be:

Upgrade Request
|
v
Acquire player lock
|
v
Load latest player data
|
v
Validate resources
|
v
Deduct currency
|
v
Upgrade equipment
|
v
Commit database transaction
|
v
Release lock
The most important detail is the ordering.

The system should normally load consistency-sensitive state after acquiring the lock.

Bad workflow:

Load Player
|
Acquire Lock
|
Use Old Player State
Another server may have changed the player between the read and the lock acquisition.

Safer:

Acquire Lock
|
Load Latest State
|
Validate
|
Modify
Lock Granularity
One of the most important design decisions is deciding exactly what should be locked.

Global Lock
A terrible approach would usually be:

lock:title
Every protected operation across the entire title waits for one lock.

This would destroy concurrency.

Player-Level Lock
A more reasonable key might be:

lock:player:1001
Only operations affecting Player 1001 block each other.

Meanwhile:

Player 1002
Player 1003
Player 1004
can proceed independently.

Resource-Level Lock
Sometimes even a player-level lock is too broad.

For example:

lock:wallet:1001
lock:inventory:1001
lock:mail:1001
This allows unrelated systems to operate simultaneously.

However, finer-grained locking introduces another challenge: operations that affect multiple resources may require multiple locks.

Multi-Resource Operations
Consider player-to-player trading.

The backend may need to modify:

Player A Inventory
Player A Currency
Player B Inventory
Player B Currency
A possible lock design is:

lock:player:1001
lock:player:2007
But acquiring multiple locks creates deadlock risk.

Server A:

Lock Player 1001
Wait for Player 2007
Server B:

Lock Player 2007
Wait for Player 1001
Neither can continue.

A common strategy is deterministic lock ordering.

For example:

Always lock smaller player ID first.
So both servers would acquire:

1001
then
2007
This significantly reduces deadlock scenarios.

For complex Realtime Backend workflows, lock ordering rules should be clearly documented and consistently implemented.

Redis as a Distributed Lock Coordinator
Redis is frequently used in Multiplayer development because it is already present in many infrastructures for:

caching;

sessions;

matchmaking state;

counters;

queues;

temporary mappings.

A basic Redis lock concept is:

SET lock:player:1001 unique_token NX PX 5000
Conceptually:

NX → set only if key does not already exist
PX → expiration time
If the operation succeeds, the caller owns the lock temporarily.

The stored value should normally contain a unique ownership token.

For example:

lock:player:1001
value = serverA-request-918273
This token becomes important during release.

Why Lock Ownership Tokens Matter
Imagine Server A acquires:

lock:player:1001
TTL = 5 seconds
But its operation unexpectedly takes 7 seconds.

At 5 seconds:

Lock expires.
Server B acquires the same lock.

At 7 seconds, Server A finishes and executes:

DELETE lock:player:1001
It accidentally deletes Server B's lock.

Now Server C can acquire the resource while Server B is still working.

This creates overlapping critical sections.

A safer release mechanism checks ownership:

if lock_value == my_unique_token:
delete lock
The comparison and deletion must be atomic.

This is often implemented using a small server-side script or another atomic mechanism supported by the coordination system.

Lock Expiration
Locks need expiration because Match Server processes can crash.

Without expiration:

Server A acquires lock
Server A crashes
Lock remains forever
The player could become permanently unable to perform the protected action.

Expiration solves this:

Lock TTL = 5 seconds
If the owner disappears, the lock eventually becomes available.

However, TTL introduces another risk.

If the critical operation lasts longer than the lock timeout, another server can acquire the same resource while the first server still operates.

Therefore:

Lock TTL > expected critical operation duration
with a reasonable safety margin.

Long-running operations may require lock renewal.

Lock Renewal and Watchdogs
Suppose a large operation can take:

2–30 seconds
Using a fixed 60-second lock might work, but failed requests could leave resources unnecessarily blocked for a long time.

Instead, some systems use a watchdog.

Conceptually:

Acquire Lock
TTL = 5 sec
|
Operation running
|
Every 2 sec:
Renew TTL
|
Operation finished
|
Release Lock
If the server crashes, renewal stops.

The lock eventually expires.

This can improve availability, but lock renewal must itself be reliable.

A server that is paused by long garbage collection, CPU starvation, or network isolation may lose the lock without realizing it.

This is one reason distributed locking requires careful failure modeling.

Redis Locks Are Not the Same as Database Transactions
A common mistake is believing that a Redis lock automatically protects database consistency.

Consider:

Acquire Redis Lock
|
Update MySQL
|
Redis connection fails
|
Lock expires
The database and lock system are separate components.

Distributed locking can coordinate access, but durable data integrity should still rely heavily on database guarantees where possible.

For example:

BEGIN TRANSACTION;

SELECT ...
UPDATE wallet ...
INSERT purchase ...

COMMIT;
Unique constraints can also protect important invariants.

For duplicate rewards:

UNIQUE(player_id, reward_id)
may be stronger and simpler than relying only on an external lock.

In many production architectures, locks and database constraints complement each other rather than replacing one another.

Atomic Database Updates as an Alternative
Not every race condition requires a distributed lock.

Suppose a player has 10,000 gold and wants to spend 8,000.

Instead of:

SELECT gold
if gold >= 8000:
UPDATE gold = gold - 8000
the database might execute:

UPDATE player_wallet
SET gold = gold - 8000
WHERE player_id = 1001
AND gold >= 8000;
Then inspect the number of affected rows.

If:

affected_rows = 1
the purchase succeeded.

If:

affected_rows = 0
the player lacked sufficient currency or the state had changed.

This is often simpler, faster, and more reliable than implementing a distributed lock.

A good Realtime Backend engineer should therefore ask:

Can the database enforce this invariant atomically?

before adding distributed coordination.

Unique Constraints for Duplicate Rewards
Consider:

daily_reward
Each player should claim it once per day.

Instead of relying only on:

if not claimed:
giveReward()
the system could insert:

player_id
reward_date
into a claim table.

A unique constraint:

UNIQUE(player_id, reward_date)
ensures that only one claim record can exist.

One request wins.

The other fails.

This can provide stronger protection than a temporary lock because the database permanently records the uniqueness rule.

Distributed locks may still reduce duplicated processing, but persistent constraints should protect important invariants whenever practical.

Optimistic Concurrency
Another alternative is optimistic concurrency control.

Suppose player data contains:

version = 52
A server loads version 52.

When updating:

UPDATE player
SET gold = 2000,
version = 53
WHERE player_id = 1001
AND version = 52;
If another server already changed the player:

version = 53
the update affects zero rows.

The caller reloads and retries or rejects the operation.

This works especially well when conflicts are uncommon.

Distributed locks are generally more pessimistic:

Prevent competing access before it occurs.
Optimistic concurrency instead says:

Allow concurrent work, but reject stale writes.
Both approaches have legitimate uses.

Duplicate Reward Protection
Reward systems are particularly vulnerable to concurrency bugs.

Examples include:

Daily Login Reward
Quest Completion Reward
Battle Pass Reward
Achievement Reward
Event Reward
Mail Attachment
Promo Code
First Purchase Bonus
A safe reward flow may use multiple protections:

Request ID

- Distributed Lock
- Database Transaction
- Unique Claim Record
  For example:

Acquire lock:reward:player1001:event55
|
v
Check persistent claim
|
v
Insert claim record
|
v
Grant currency/items
|
v
Commit
|
v
Release lock
The lock reduces concurrent processing.

The database prevents permanent duplication.

The request identifier supports idempotency.

This defense-in-depth approach is especially appropriate for premium or economically valuable rewards.

Marketplace and Auction Systems
Marketplaces create even more difficult concurrency problems.

Suppose one item is listed:

Legendary Sword
Quantity: 1
Price: 5,000 Gems
Two players click Buy simultaneously.

Only one should receive the sword.

A simple process might require locking:

lock:market_listing:91827
Then:

Verify listing exists
Verify not sold
Verify buyer funds
Mark listing sold
Deduct buyer currency
Transfer item
Credit seller
Commit transaction
This workflow may also require database transactions and idempotency protection.

The distributed lock prevents two independent Match Server instances from processing the listing concurrently.

But the database should still enforce the final ownership transition.

Guild Race Conditions
Guild systems contain many shared resources.

Examples:

Guild Capacity
Guild Treasury
Guild Technology
Guild Boss State
Guild Leadership
Guild Applications
Imagine a guild allows:

50 members maximum
Current size:

49
Two invitation requests arrive simultaneously.

Both see:

49 < 50
Both add a player.

Guild size becomes:

51
Possible protections include:

lock:guild:123
or an atomic database constraint/model that prevents exceeding capacity.

The best design depends on how guild membership is represented.

Matchmaking and Room Creation
Distributed locks can also prevent duplicated room creation.

Suppose multiple matchmaking workers see the same queued players.

Worker A creates:

Match 5001
Worker B also attempts to create a match using overlapping players.

Locks may protect:

player matchmaking ownership
or:

match group reservation
However, queues and atomic reservation primitives are often better solutions than locking entire matchmaking systems.

This highlights an important point:

Distributed locks are coordination tools, not universal concurrency tools.

Performance Costs
Every distributed lock adds network operations.

A typical flow may involve:

Acquire
Business Operation
Release
That means at least additional communication with the lock system.

Under heavy load:

100,000 operations/sec
poor lock design can become a significant bottleneck.

Highly contested locks are especially dangerous.

For example:

lock:global_ranking
may serialize thousands of unrelated requests.

Lock keys should be designed to maximize concurrency while still protecting the required invariant.

Lock Contention
Useful metrics include:

Lock acquisition rate
Lock acquisition latency
Lock timeout rate
Lock failure rate
Lock hold duration
Lock renewal count
Contended key count
Suppose:

lock:guild:100
has an average wait time of 900 milliseconds while most other guild locks are below 5 milliseconds.

That may indicate one guild operation is performing expensive work while holding the lock.

Monitoring can reveal architectural bottlenecks that would otherwise appear only as random Match Server latency.

Keep Critical Sections Short
A lock should generally cover only the operation that needs exclusive ownership.

Bad:

Acquire player lock
|
Call external payment provider
|
Wait 5 seconds
|
Generate analytics
|
Send email
|
Update database
|
Release lock
Better:

Perform slow external preparation if safe
|
Acquire lock
|
Validate latest state
|
Perform atomic persistence
|
Release lock
|
Send analytics / notification
Long critical sections reduce throughput and increase lock expiration risk.

Failure Scenarios
Distributed locking should be tested against realistic failures.

Server Crash
Acquire lock
Modify partial state
Crash
The database transaction should ideally roll back.

The lock should eventually expire.

Lock Service Timeout
The Match Server cannot determine whether a lock request succeeded.

This is dangerous because ambiguous ownership may exist.

Network Partition
The server may lose communication with the lock coordinator while still processing the operation.

Long Garbage Collection Pause
The application stops running temporarily.

Its lock expires.

Another server acquires the resource.

The original server resumes believing it still owns the lock.

Database Commit Timeout
The Match Server does not know whether the database committed.

Retrying without idempotency may duplicate the operation.

These edge cases demonstrate why distributed systems should never assume that:

timeout = failure
or:

no response = nothing happened
Fencing Tokens
For high-integrity systems, fencing tokens can provide stronger protection.

Each successful lock acquisition receives an increasing number:

Lock Owner A → Token 500
Lock Owner B → Token 501
Lock Owner C → Token 502
A protected storage system rejects operations using older tokens.

Suppose Server A pauses after receiving:

500
Its lock expires.

Server B acquires:

501
If Server A resumes and tries to write with token 500, the storage layer rejects the stale operation.

This can protect against the dangerous case where an old lock owner continues working after ownership has moved.

However, fencing requires cooperation from the resource being protected.

It is not automatically provided by simply creating a Redis key.

Security Considerations
Distributed lock keys should be controlled by backend services.

Clients should never directly acquire or release internal locks.

Otherwise, a malicious client might intentionally create:

lock:player:target_player
to interfere with play.

The lock infrastructure should normally live inside protected backend networks.

Access controls should restrict:

Who can acquire locks
Who can delete lock keys
Which namespaces services can access
Administrative tools should also be careful when manually deleting locks during incidents.

Removing a valid lock while its owner is still processing can create overlapping operations.

Monitoring and Observability
A production Realtime Backend should make lock behavior visible.

Useful dashboards might show:

Locks acquired per second
Average lock acquisition time
P95 lock wait time
P99 lock wait time
Lock acquisition failures
Expired locks
Manual lock removals
Renewal failures
Longest-held locks
Top contended resources
Logs should include identifiers such as:

lock_key
request_id
player_id
server_id
lock_token
acquisition_time
release_time
This makes debugging considerably easier.

If a player reports duplicated items, engineers can trace whether multiple servers processed the same operation concurrently.

How to Analyze This in Multiplayer source Code
When examining Multiplayer source Code, search for terms such as:

RedisLock
DistributedLock
Mutex
LockManager
AcquireLock
ReleaseLock
RedLock
Semaphore
Version
CompareAndSet
SELECT FOR UPDATE
Transaction
Idempotency
Then choose a high-risk operation.

For example:

ClaimReward()
PurchaseItem()
UpgradeEquipment()
JoinGuild()
BuyMarketplaceItem()
Trace its full workflow.

Example:

Client Request
|
v
Protocol Handler
|
v
Authentication
|
v
Acquire Lock?
|
v
Load Player
|
v
Validation
|
v
Database Transaction
|
v
Update Cache
|
v
Release Lock
Ask several questions.

Does the lock have an expiration?

Does each lock owner have a unique token?

Is lock release ownership-safe?

What happens if the operation exceeds the TTL?

Does the system have a database constraint as a final safeguard?

Can the request safely be retried?

Are multiple locks acquired in deterministic order?

Does the code hold a lock while performing slow network calls?

These questions are particularly important when analyzing large Multiplayer source Code projects on the forum because race conditions may remain invisible during single-user local testing and appear only after the server operates under real concurrency.

Common Mistakes
Using Distributed Locks for Everything
Locks add complexity and latency.

Use atomic database operations or uniqueness constraints when they solve the problem more naturally.

No Expiration
A crashed server can permanently block a resource.

Expiration Too Short
The lock may expire while valid work is still running.

Blind Lock Deletion
A server may delete another server's lock.

Always verify ownership.

Reading Data Before Lock Acquisition
The state may already be stale when the protected operation begins.

Holding Locks During Slow External Calls
This increases contention and expiration risk.

No Database Constraints
A distributed lock should not be the only defense for high-value invariants when durable constraints are possible.

Uncontrolled Retries
Retries can convert temporary failures into duplicate rewards or purchases.

Locking Too Broadly
A global or oversized lock can destroy Match Server scalability.

Locking Too Narrowly
If related state is protected by different keys, race conditions may remain.

Best Practices
Define the invariant before choosing the locking strategy.

Ask:

What exactly must never happen concurrently?
Prefer atomic database operations when they are sufficient.

Use unique constraints for permanently unique operations.

Use optimistic concurrency when conflicts are rare.

Use distributed locks when multiple Match Server instances truly need exclusive access to a shared resource.

Use unique ownership tokens.

Set expiration carefully.

Implement safe lock renewal when operations can exceed the normal TTL.

Keep critical sections short.

Use deterministic ordering when acquiring multiple locks.

Protect valuable operations with idempotency identifiers.

Combine locks with database transactions and constraints where appropriate.

Monitor contention, expiration, and acquisition latency.

Test server crashes, network interruption, process pauses, and retry behavior.

For Multiplayer development teams studying backend architecture through the forum, it is especially useful to identify whether the source uses locks as a convenience or whether concurrency guarantees are actually enforced at every important persistence boundary.

Conclusion
Distributed locks are powerful tools for coordinating Realtime Backend operations across multiple servers.

They can prevent situations such as:

Duplicate rewards
Double purchases
Concurrent inventory updates
Marketplace double sales
Guild capacity races
Conflicting player modifications
But distributed locking is not a magic solution.

Reliable concurrency control usually combines several techniques:

Distributed Locks
Database Transactions
Unique Constraints
Optimistic Concurrency
Idempotency
Atomic Updates
Careful Retry Logic
The correct choice depends on the invariant being protected.

A simple currency deduction may be best handled through an atomic SQL update.

A daily reward may be best protected by a unique database constraint.

A multi-step operation affecting shared resources across multiple Match Server instances may genuinely require a distributed lock.

A marketplace transaction may use several protections together.

When analyzing Multiplayer source Code, developers should never stop after finding a lock() function.

They should investigate lock ownership, expiration, database guarantees, retries, and failure behavior.

In real Multiplayer development environments, concurrency bugs are often among the most expensive problems because they can damage virtual economies, duplicate premium items, corrupt progression, and create difficult support incidents.

Designing locks correctly therefore means more than preventing two threads from running at once.

It means defining exactly who owns shared state, how long ownership remains valid, what happens when a server disappears, and which permanent database rules guarantee correctness after every temporary lock has vanished.
