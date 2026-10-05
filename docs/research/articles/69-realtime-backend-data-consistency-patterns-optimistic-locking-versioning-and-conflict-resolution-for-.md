#69 – Realtime Backend Data Consistency Patterns: Optimistic Locking, Versioning and Conflict Resolution for Concurrent Player Updates
administrator
administrator
Verified user account
02/09/2026 07:18
•
General Discussion
Realtime Backend Data Consistency Patterns: Optimistic Locking, Versioning and Conflict Resolution for Concurrent Player Updates
Introduction
Online titles constantly modify player data.

A single active player may generate changes to:

Inventory

Currency

Equipment

Quest progress

Achievements

Guild state

Mail

Ranking points

Match results

Battle-pass progression

Energy

Character statistics

In a simple Match Server architecture, one process may own the player and perform every update sequentially.

As a Realtime Backend grows, that assumption often disappears.

The same account may interact with:

Match Server
Inventory Service
Shop Service
Guild Service
Payment Service
Reward Worker
Admin Panel
at approximately the same time.

Now several services may attempt to modify related player data concurrently.

This creates one of the most important problems in distributed Multiplayer development:

How do we prevent concurrent updates from overwriting each other?

Consider a player with:

Gold = 10,000
Two operations begin simultaneously.

Operation A:

Quest reward:
+1,000 Gold
Operation B:

Shop purchase:
-2,000 Gold
Both services read:

Gold = 10,000
Service A calculates:

11,000
Service B calculates:

8,000
If Service A writes first and Service B writes afterward, the database may end with:

Gold = 8,000
The quest reward disappears.

If the order is reversed, the database may end with:

Gold = 11,000
and the purchase deduction disappears.

The correct result should be:

9,000 Gold
This is known as a lost update.

Problems like this become especially dangerous in systems involving premium currency, item ownership, payments, marketplaces, and competitive rankings.

Production Realtime Backend architecture therefore needs explicit concurrency-control strategies.

Common approaches include:

Database transactions

Optimistic locking

Version numbers

Pessimistic locking

Atomic updates

Idempotency

Compare-and-swap

Event sequencing

Conflict resolution

This article explains how these patterns apply to real Match Server architecture and how Studios can choose between them.

For developers studying Multiplayer source Code on the forum, understanding data-consistency mechanisms is essential because many serious play bugs do not appear in combat logic at all. They appear when two legitimate backend processes update the same player state simultaneously.

The Lost Update Problem
Consider a player profile table:

## player_id | gold

92831 | 10000
Two requests arrive.

Request A:

Claim Quest Reward
Request B:

Purchase Item
Both perform:

SELECT gold
FROM players
WHERE player_id = 92831;
Both receive:

10000
Request A calculates:

10000 + 1000 = 11000
Request B calculates:

10000 - 2000 = 8000
Then:

A writes 11000

B writes 8000
The final state becomes:

8000
even though both operations succeeded.

Nothing crashed.

No database query failed.

The bug came from incorrect concurrency control.

Prefer Atomic Database Operations Where Possible
Sometimes the simplest solution is to avoid reading the current value before changing it.

Instead of:

read gold
calculate new gold
write gold
perform:

UPDATE players
SET gold = gold + 1000
WHERE player_id = 92831;
and independently:

UPDATE players
SET gold = gold - 2000
WHERE player_id = 92831;
The database can serialize these updates correctly.

The result becomes:

9000
Atomic updates are especially useful for counters such as:

experience += X
currency += X
quest_count += 1
login_count += 1
However, many Realtime Backend operations involve more complicated validation.

For example:

if gold >= 2000:
deduct 2000
grant item
This requires transactional logic.

Database Transactions
A transaction groups related operations into one atomic unit.

For example:

BEGIN TRANSACTION

Check player gold

Deduct gold

Create item

Write purchase record

COMMIT
If anything fails:

ROLLBACK
This prevents partially completed operations.

A purchase should not result in:

currency deducted
but
item missing
Transactions are one of the strongest tools available when all authoritative data lives inside the same relational database.

Conditional Updates
A purchase can often be implemented with a conditional update.

Conceptually:

UPDATE players
SET gold = gold - 2000
WHERE player_id = 92831
AND gold >= 2000;
Then inspect:

affected_rows
If:

affected_rows = 1
the deduction succeeded.

If:

affected_rows = 0
the player did not have sufficient funds or the record was unavailable.

This avoids a separate read-check-write race.

What Is Optimistic Locking?
Optimistic locking assumes that conflicts are relatively uncommon.

Instead of locking the database row for the entire workflow, each record contains a version.

Example:

player_id = 92831
gold = 10000
version = 17
A service reads:

gold = 10000
version = 17
It calculates a new state.

Then it attempts:

UPDATE players
SET gold = 11000,
version = 18
WHERE player_id = 92831
AND version = 17;
If no other process modified the row, the update succeeds.

If another process already changed the version:

version = 18
the update affects zero rows.

The application knows:

The data I read is stale.

It can then retry or resolve the conflict.

Example of Optimistic Locking
Initial state:

Gold = 10,000
Version = 20
Service A reads:

Gold = 10,000
Version = 20
Service B reads the same state.

Service A performs:

Quest reward +1,000
and writes:

Gold = 11,000
Version = 21
Service B attempts:

Gold = 8,000
Version = 21
WHERE Version = 20
The database rejects the update because:

current version = 21
Service B now reloads:

Gold = 11,000
Version = 21
recalculates the purchase:

11,000 - 2,000 = 9,000
and writes:

Gold = 9,000
Version = 22
Correct result.

Why Optimistic Locking Fits Many Systems
Optimistic locking works well when:

Conflicts are possible

Conflicts are not constant

Long database locks are undesirable

Services can safely retry

Examples include:

Player profile updates
Quest progress
Character settings
Equipment configuration
Guild metadata
Marketplace listings
It becomes less attractive when hundreds of operations constantly contend for the exact same row.

In that case, repeated retries may waste significant resources.

Version Numbers
A common version field is:

version BIGINT
Each successful update increments it:

41
42
43
44
Advantages:

Simple

Fast

Easy to debug

Works well with SQL

Explicit conflict detection

A Match Server can log:

expected_version=43
actual_version=44
which makes concurrency problems visible.

Timestamps Are Not Always Good Versions
Some systems use:

updated_at
as the concurrency token.

For example:

WHERE updated_at = '...'
This can work, but numeric versions are often easier to reason about.

Timestamp precision and clock behavior can complicate comparisons.

A monotonically increasing integer clearly represents mutation order within one record.

Optimistic Lock Retry Strategy
When a conflict occurs, the application may retry.

Example:

Attempt 1
|
X version conflict
|
v
Reload state
|
v
Recalculate
|
v
Attempt 2
Retries should be bounded.

Bad:

while true:
retry
High contention could create an infinite retry storm.

Better:

maximum 3 attempts
then return an error or move the operation to another resolution path.

Not Every Conflict Can Be Retried Automatically
Suppose a player edits a character nickname.

Two devices simultaneously request:

Device A:
name = DragonKing

Device B:
name = ShadowKing
There is no mathematical merge.

The application must choose a conflict policy.

Possibilities include:

First write wins
Last write wins
Reject second request
Ask user to retry
For match state, automatic recalculation is often possible.

For user preference or content editing, explicit conflict semantics may be required.

Pessimistic Locking
Pessimistic locking assumes conflicts are likely enough that exclusive access should be obtained before modification.

Conceptually:

SELECT \*
FROM players
WHERE player_id = 92831
FOR UPDATE;
Other transactions attempting to lock the row must wait.

Architecture:

Transaction A
|
v
Lock Player Row
|
v
Modify
|
v
Commit
|
v
Release Lock
This prevents concurrent modification while the transaction is active.

When Pessimistic Locking Makes Sense
It can work well for:

High-value marketplace transactions

Limited stock purchases

Short wallet operations

Auction settlement

Critical ownership transfers

The important word is:

short.

Do not hold database locks while waiting for:

External payment providers

HTTP APIs

User input

Long background processing

Long locks reduce throughput and increase deadlock risk.

Optimistic vs Pessimistic Locking
A useful comparison:

## Optimistic Locking

No long row lock
Detect conflict during update
Retry if needed
Best when contention is low/moderate

## Pessimistic Locking

Acquire lock before modification
Other operations wait
Best for short critical sections
Neither is universally superior.

The correct choice depends on workload.

Player Inventory Concurrency
Inventory systems are particularly sensitive.

Suppose a player owns:

Sword x1
Two operations happen simultaneously.

Operation A:

Sell Sword
Operation B:

Equip Sword
If separate services operate without coordination, the player could potentially:

sell item

- still equip item
  A safer inventory model may represent each item with:

item_instance_id
owner_id
state
version
Example:

item_id = 918273
owner = player_92831
state = inventory
version = 14
Selling:

state:
inventory -> sold
Equipping:

state:
inventory -> equipped
Both require:

version = 14
Only one transition succeeds.

State Transitions Are Often Better Than Generic Updates
Instead of allowing:

UPDATE item SET state = anything
model valid transitions.

Example:

INVENTORY
|
+----> EQUIPPED
|
+----> SOLD
|
+----> DESTROYED
Then the database operation can require:

current_state = INVENTORY
This reduces impossible states.

Marketplace Example
Consider a marketplace listing:

listing_id = 5001
status = ACTIVE
version = 9
Two players attempt to buy it simultaneously.

Buyer A tries:

ACTIVE -> SOLD
version 9 -> 10
Buyer B tries the same.

Only one update should succeed.

Conceptually:

UPDATE listings
SET status = 'SOLD',
buyer_id = ?,
version = version + 1
WHERE listing_id = 5001
AND status = 'ACTIVE'
AND version = 9;
The winner receives:

affected_rows = 1
The loser receives:

affected_rows = 0
and should return:

Listing is no longer available.
This avoids selling the same item twice.

Idempotency and Concurrency Are Different
These concepts are often confused.

Optimistic locking answers:

Did someone else change this state after I read it?

Idempotency answers:

Have I already processed this logical request before?

Suppose a purchase request is retried because the client did not receive a response.

The same operation may arrive twice.

Use:

transaction_id
or:

idempotency_key
to ensure the business action only applies once.

Production Realtime Backend systems frequently need both concurrency control and idempotency.

Multi-Service Transactions
Things become more difficult when data is split across services.

Example:

Wallet Service
Inventory Service
Purchase Service
A purchase requires:

Deduct currency

- Grant item
  but those states exist in separate databases.

A traditional single database transaction may no longer be available.

Patterns may include:

Saga workflows

Transactional outbox

Compensating actions

Reservation models

Durable state machines

The architecture must explicitly manage partial failure.

Reservation Pattern
Instead of immediately completing every step:

Reserve currency
|
v
Reserve item
|
v
Commit purchase
If something fails:

release reservation
This is common when several services participate in one business transaction.

A reservation prevents another operation from consuming the same resource while the workflow is incomplete.

Saga-Style Workflow
A distributed purchase might look like:

Create Purchase
|
v
Reserve Currency
|
v
Grant Item
|
v
Commit Currency
|
v
Complete Purchase
If item grant fails:

Release Currency Reservation
This is a compensating action.

Such workflows should persist their progress so another worker can recover after a crash.

Do Not Depend on In-Memory Workflow State
Bad:

purchase_state
exists only inside one service process.

If the server crashes halfway through, the transaction becomes ambiguous.

Better:

purchase_id
status
current_step
version
persisted durably.

Another worker can inspect:

status = CURRENCY_RESERVED
and continue or compensate.

Redis and Concurrency
Redis supports atomic operations useful for some Realtime Backend workloads.

Examples include:

INCR
DECR
SET NX
Lua scripts
For counters and temporary coordination, this can be valuable.

However, teams should avoid creating separate authoritative copies of important player state in Redis and SQL without a clear synchronization strategy.

For example:

Redis gold = 12,000

SQL gold = 10,000
Which is authoritative?

The answer must be explicit.

Cache Invalidation
Concurrency issues also appear in caches.

Suppose:

Database:
level = 50

Redis cache:
level = 49
After updating the database, the application must ensure stale cached data does not overwrite newer values later.

A common safe strategy is:

Update database
|
v
Invalidate cache
rather than treating cached objects as independently authoritative.

Versioned Cache Entries
Caches may store the database version:

{
"player_id": 92831,
"level": 50,
"version": 118
}
If a message arrives containing:

version 117
it should not replace:

version 118
This prevents out-of-order events from rolling cache state backward.

Out-of-Order Events
Distributed Realtime Backend systems commonly use message queues.

Events may arrive in unexpected order.

Example:

Event A:
player level 49 -> 50

Event B:
player level 50 -> 51
A consumer might receive:

B
then
A
If it blindly applies both, state can go backward.

Including:

entity_version
allows consumers to detect stale events.

Event Sequencing
Example events:

player_id = 92831
version = 100

player_id = 92831
version = 101

player_id = 92831
version = 102
A consumer currently at:

version 102
should ignore an event with:

version 101
because it is stale.

This is especially useful for:

Search indexes

Analytics projections

Read models

Cache replication

Conflict Resolution
Not every distributed state needs strict serialization.

Some data can tolerate application-level merging.

For example:

Achievement A completed
Achievement B completed
If two services update independent achievement entries, the result can potentially be merged.

Other state cannot.

Premium currency should generally not use:

last write wins
because losing one financial mutation is unacceptable.

Conflict policy should match business importance.

Last Write Wins
Last-write-wins means the most recent write replaces previous state.

This may be acceptable for:

UI preference
Selected avatar frame
Last viewed tab
It is dangerous for:

currency
inventory ownership
payments
limited-item purchases
Do not use convenience-oriented conflict rules for economy-critical data.

Single-Writer Ownership
Another powerful architecture is to ensure that one service owns mutations for a particular entity.

For example:

Player 92831
-> Match Server 18
All authoritative play mutations route through Server 18.

This dramatically reduces concurrency.

The Match Server effectively serializes player commands.

However, other systems such as:

payment workers
GM tools
offline jobs
may still need to update player data.

Those updates should go through the same authoritative service or use a carefully designed transaction channel.

Actor-Style Match Server Architecture
Some Match Servers use an actor-like model.

Conceptually:

Player Actor 92831

Command Queue:

1. Claim reward
2. Purchase item
3. Equip sword
4. Update quest
   Commands execute sequentially.

This avoids many shared-memory races.

However, persistence still requires protection against:

Duplicate commands

Server restart

External writers

Message redelivery

Single-threaded logical ownership simplifies concurrency but does not eliminate distributed consistency concerns.

Sharding Player Ownership
Large titles can distribute player ownership across many Match Server instances.

Example:

hash(player_id) % N
or a dynamic session registry.

Players are routed consistently to an owner.

This allows:

Player A -> Server 1
Player B -> Server 2
Player C -> Server 3
while keeping each player's updates mostly serialized.

Ownership transfer must be coordinated carefully during migration or failover.

Compare-and-Swap
Optimistic locking is conceptually similar to compare-and-swap.

The operation means:

Change this value only if it still equals what I expect.

Example:

Expected:
status = ACTIVE

Set:
status = SOLD
If actual state is already:

SOLD
the operation fails.

This model is useful for state-machine transitions.

Conflict Metrics
Concurrency conflicts should be observable.

Useful metrics include:

optimistic_lock_conflicts
transaction_retries
deadlocks
reservation_failures
duplicate_request_detection
If a particular endpoint produces extremely high conflict rates, the architecture may need redesign.

For example:

guild_update conflict rate = 40%
may indicate that too much mutable guild state is stored in one row.

Hot Rows
A hot row is a database record updated so frequently that it becomes a contention point.

Examples:

global_counter
guild_summary
world_event_state
Thousands of Match Server processes competing for one row can reduce throughput.

Possible improvements include:

Sharded counters

Append-only events

Batched aggregation

Per-player records

Distributed worker ownership

Not every shared value should be updated synchronously on every player action.

Deadlocks
Pessimistic transactions can deadlock.

Example:

Transaction A:
locks Player
then Guild

Transaction B:
locks Guild
then Player
Both wait forever until the database detects the deadlock and aborts one.

A simple prevention strategy is consistent lock ordering.

For example:

Always lock:
Player
then Guild
rather than allowing arbitrary order.

Applications should also safely retry transactions aborted by deadlock detection.

How to Analyze This in Multiplayer source Code
When examining Multiplayer source Code, search for fields such as:

version
revision
row_version
update_version
sequence
Then inspect SQL patterns.

Look for:

WHERE version = ?
or:

FOR UPDATE
These often indicate explicit concurrency control.

Search service code for:

OptimisticLock
Conflict
RetryTransaction
CompareAndSwap
Reservation
Inventory and economy systems deserve special attention.

Trace workflows such as:

BuyItem()
SellItem()
GrantReward()
TransferCurrency()
TradeItem()
Ask:

Can two requests execute simultaneously?

What prevents lost updates?

Is there a transaction?

Is the request idempotent?
When analyzing Realtime Backend projects through the forum, also inspect database schemas.

A field such as:

version BIGINT
may appear unimportant until you realize it protects every concurrent update.

Likewise, tables such as:

wallet_transactions
item_transactions
purchase_orders
may provide stronger consistency than simply updating one balance column.

Check whether GM tools modify database rows directly.

If the Match Server expects exclusive ownership but the admin panel writes directly to SQL, that assumption may be broken.

Production-quality systems should define one consistent mutation strategy across play, admin operations, and background workers.

Common Mistakes

1. Read-Modify-Write Without Protection
   Two services can overwrite each other's changes.

Use atomic updates, transactions, or version checks.

2. Using Last Write Wins for Currency
   Economy operations cannot safely lose earlier updates.

Use transactional mutation records.

3. Retrying Forever
   Optimistic conflicts should have bounded retries.

High conflict rates require architectural investigation.

4. Holding Locks During External Calls
   Database locks should remain short.

Move network operations outside critical sections.

5. Assuming One Match Server Is the Only Writer
   Payments, GM tools, and workers may also modify player state.

Identify every writer.

6. Ignoring Message Ordering
   Older events can overwrite newer cache or projection state.

Use entity versions.

7. Treating Redis and SQL as Equal Authorities
   Define exactly which system owns the authoritative state.

8. No Idempotency
   Concurrency protection does not prevent duplicate request execution.

Use transaction identities.

9. One Giant Player Row
   Unrelated systems updating the same row can generate unnecessary contention.

Separate state according to access patterns.

10. No Conflict Monitoring
    Concurrency bugs can remain hidden until traffic grows.

Measure conflicts and retries.

Best Practices
A production Studio should follow several principles.

Use atomic database operations whenever possible.

Avoid unnecessary read-modify-write cycles.

Wrap related economy changes in transactions.

Currency and item state should remain consistent.

Use optimistic locking for low-to-moderate contention.

Version numbers provide clear conflict detection.

Use pessimistic locking only for short critical sections.

Do not hold locks during slow work.

Make important operations idempotent.

Duplicate delivery and concurrency are separate problems.

Model explicit state transitions.

ACTIVE → SOLD is safer than arbitrary state replacement.

Keep authoritative ownership clear.

Every data domain should have a defined writer.

Version asynchronous events.

Prevent stale messages from rolling state backward.

Persist distributed workflow state.

Multi-service transactions must survive crashes.

Monitor hot rows and conflict rates.

Concurrency architecture must evolve with scale.

Use durable transaction records for valuable virtual economy operations.

Balances alone provide poor forensic history.

Choose conflict policies based on business value.

A UI preference and premium currency should not use the same consistency strategy.

Conclusion
Data consistency is one of the most important foundations of reliable Realtime Backend architecture.

As Multiplayer development systems become distributed, the same player may be affected by many processes:

Match Server
Shop Service
Payment Worker
Reward Worker
Guild Service
Admin Tool
Without explicit concurrency control, perfectly legitimate operations can overwrite each other.

The result may be:

Lost currency
Duplicated items
Missing rewards
Incorrect inventory
Broken marketplace transactions
Corrupted progression
A mature architecture combines several techniques:

Atomic Updates

- Database Transactions
- Optimistic Locking
- Version Numbers
- Idempotency
- State Transitions
- Durable Workflows
- Conflict Monitoring
  Optimistic locking works particularly well when concurrent conflicts are possible but relatively uncommon.

Pessimistic locking can protect short, high-value critical sections.

Atomic SQL updates eliminate many simple race conditions entirely.

For distributed workflows spanning several microservices, reservations, durable state machines, transactional outbox patterns, and compensating actions may be necessary.

The key principle is simple:

Never assume data stayed unchanged merely because your service read it a few milliseconds ago.

In distributed Match Server environments, another process may already have changed it.

For developers examining Multiplayer source Code on the forum, concurrency-control code is one of the clearest indicators of backend maturity.

A project may run correctly with one player during testing yet fail under real concurrent traffic if lost updates and duplicate execution were never considered.

Reliable Realtime Backend engineering therefore requires more than storing player data.

It requires controlling exactly how that data changes when many legitimate operations compete to update it at the same time.
