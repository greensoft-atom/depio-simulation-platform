#31 – Concurrency Control in Realtime Backends: Preventing Duplicate Rewards, Double Spending, and Race Conditions
administrator
administrator
Verified user account
18/08/2026 17:00
•
General Discussion
Concurrency Control in Realtime Backends: Preventing Duplicate Rewards, Double Spending, and Race Conditions
Introduction
Modern online titles process thousands or even millions of operations that modify player state: purchasing items, claiming rewards, upgrading equipment, spending currencies, joining guilds, completing quests, opening loot boxes, trading items, and receiving payments.

The problem is that these operations do not always arrive one at a time.

A player may tap a button twice. A mobile client may retry a request because of a temporary network failure. Two Match Server instances may attempt to update the same account simultaneously. A payment provider may deliver the same callback more than once. A scheduled job may run at the same time as an active player action.

Without proper concurrency control, a Realtime Backend can create serious bugs such as:

Duplicate rewards

Negative currency balances

Double item purchases

Lost inventory updates

Duplicate payment delivery

Multiple ownership of unique items

Incorrect leaderboard scores

Guild state corruption

These problems are especially dangerous because they may not appear during normal development or small-scale testing. They often become visible only after a title reaches significant concurrency.

For Multiplayer development teams analyzing existing Multiplayer source Code, understanding how the backend handles concurrent state changes is therefore just as important as understanding match logic.

This article explains practical concurrency-control strategies for Match Server and Realtime Backend systems, including database transactions, optimistic locking, pessimistic locking, distributed locks, idempotency, atomic operations, and event-based processing.

Why Race Conditions Happen in Online Titles
A race condition occurs when the final state of a system depends on the timing or ordering of multiple operations.

Consider a player with:

Gold = 1,000
Two requests arrive almost simultaneously:

Request A: Buy Sword for 700 Gold
Request B: Buy Armor for 600 Gold
Both services read the same balance:

Gold = 1,000
Request A checks:

1000 >= 700
Request B checks:

1000 >= 600
Both operations appear valid.

If the backend does not synchronize the updates properly, both purchases may succeed even though the player only had enough currency for one.

Depending on the implementation, the final balance could become:

300
or:

400
while the player receives both items.

This is a classic read-modify-write race condition.

Common Sources of Concurrency in a Realtime Backend
Concurrency comes from many places.

Multiple Client Requests
Mobile players may tap buttons repeatedly or reconnect after temporary network interruptions.

The client can accidentally send requests such as:

POST /claim_reward
POST /claim_reward
within milliseconds.

The backend must assume that duplicate requests are possible.

Multiple Match Server Instances
Large titles rarely run on a single server.

A production architecture may contain:

Players
|
Load Balancer
|
API Gateway
|
+-------------------------------+
| Match Server 01 |
| Match Server 02 |
| Match Server 03 |
| Match Server 04 |
+-------------------------------+
|
Backend
|
+-------------------------------+
| Database |
| Redis |
| Message Queue |
+-------------------------------+
Two different Match Server instances can therefore modify the same logical entity.

Application-level synchronization inside one process is not sufficient in this architecture.

Background Workers
Realtime Backend systems often contain workers responsible for:

Mail rewards

Event settlement

Ranking rewards

Match results

Payment processing

Daily resets

Guild calculations

Auction settlement

These workers may operate simultaneously with player requests.

External Payment Providers
Payment notifications should generally be treated as potentially duplicated.

A provider may retry a callback if it does not receive the expected response.

Without idempotent payment processing, the same transaction could grant premium currency multiple times.

Understand the Critical State First
Not every title operation requires strong concurrency protection.

Studios should identify which data has strong consistency requirements.

Common examples include:

Player currency
Premium currency
Inventory
Equipment ownership
Marketplace transactions
Payments
Reward claims
Guild ownership
Unique items
Quest completion
Energy/stamina
Match results
Less critical data may tolerate eventual consistency.

For example:

Analytics events
Online presence
Activity history
Telemetry
Non-critical counters
Trying to strongly synchronize every piece of data can unnecessarily reduce Match Server throughput.

The architecture should apply stronger consistency guarantees only where they are actually required.

Database Transactions
Database transactions are one of the most important tools for protecting simulation state.

Consider an item purchase.

The operation may require:

Check player balance.

Deduct currency.

Add item.

Record transaction history.

These operations should often succeed or fail as one logical unit.

Conceptually:

BEGIN;

-- verify and deduct currency

-- insert inventory item

-- write transaction history

COMMIT;
If adding the item fails, the currency deduction should not remain committed independently.

Transactions help preserve consistency between related records.

However, using a transaction alone does not automatically solve every concurrency problem. The Realtime Backend must also consider transaction isolation and how conflicting writes are detected or serialized.

Prefer Atomic Database Operations When Possible
An excellent way to avoid many race conditions is to move validation and mutation into a single atomic database statement.

Instead of:

SELECT gold
IF gold >= 700
UPDATE gold = gold - 700
the backend can use an update pattern conceptually similar to:

UPDATE player_wallet
SET gold = gold - 700
WHERE player_id = ?
AND gold >= 700;
The application then checks whether the update actually affected a row.

This avoids a dangerous gap between checking the balance and modifying it.

For high-frequency Realtime Backend operations, atomic conditional updates are often simpler and faster than introducing distributed locking everywhere.

Optimistic Locking
Optimistic locking assumes conflicts are relatively uncommon.

Each record contains a version.

Example:

player_id: 1024
gold: 1000
version: 47
The Match Server reads version 47 and attempts:

UPDATE player_wallet
SET gold = 300,
version = 48
WHERE player_id = 1024
AND version = 47;
If another request has already modified the record, its version may now be 48.

The update affects zero rows.

The backend knows that a concurrency conflict occurred.

It can then:

Reload the latest state

Revalidate the operation

Retry safely

Return a conflict response

Optimistic locking works particularly well when reads are frequent but simultaneous writes to exactly the same entity are relatively uncommon.

Pessimistic Locking
Pessimistic locking assumes conflicts are important enough to prevent concurrent access before modifying data.

For example, a transaction might lock a player's wallet row while processing a purchase.

Conceptually:

BEGIN;

SELECT gold
FROM player_wallet
WHERE player_id = ?
FOR UPDATE;

-- validate purchase
-- deduct currency
-- add item

COMMIT;
Other transactions attempting to acquire a conflicting lock may wait until the first transaction finishes.

This can provide strong protection but must be used carefully.

Long transactions can create:

Lock contention

Increased latency

Reduced throughput

Deadlocks

Cascading performance problems

Match Server code should avoid performing slow external operations while holding database locks.

For example, do not hold a database transaction open while waiting several seconds for an external HTTP API whenever the workflow can be redesigned to avoid it.

Optimistic vs Pessimistic Locking
Neither strategy is universally better.

Optimistic locking works well when:
Conflicts are relatively rare

High throughput matters

Operations can safely retry

Entities are distributed across many players

Pessimistic locking is useful when:
Conflicting writes are expected

Operations are difficult to retry

Strong serialization is required

The critical section is short

A practical Realtime Backend may use both approaches for different systems.

For example:

Player profile -> optimistic locking
Auction settlement -> transactional/pessimistic control
Payment transaction -> unique constraint + idempotency
Leaderboard updates -> atomic operations
Distributed Locks
Database locking controls operations inside the database, but distributed Match Server architectures sometimes require coordination at the application level.

Imagine:

Match Server A
Match Server B
Worker C
All three may attempt to process the same player operation.

A distributed lock can coordinate access across these processes.

Conceptually:

Acquire lock:
player:1024:inventory

Perform critical operation

Release lock
Redis is frequently used as part of distributed coordination systems because it provides atomic operations and expiration capabilities.

However, distributed locking should not automatically replace database constraints and transactions.

A safer design usually combines multiple layers:

Application coordination +
Database transaction +
Unique constraints +
Idempotency
The database should remain capable of rejecting logically invalid final states whenever practical.

Lock Granularity Matters
A Studio should avoid locking more data than necessary.

A lock such as:

global-match-lock
would serialize nearly the entire backend.

That destroys scalability.

Instead, locks should usually be scoped to the smallest logical resource requiring synchronization.

Examples:

player:1024:wallet
player:1024:inventory
guild:8801
auction:55002
trade:77591
Fine-grained locks improve concurrency because unrelated players can continue performing actions independently.

Distributed Locks Must Have Expiration
A dangerous lock implementation is:

Acquire lock
Process crashes
Lock remains forever
Future operations may never succeed.

Distributed locks should therefore generally include a lease or expiration.

However, expiration introduces another challenge.

Suppose a task runs longer than expected.

The lock expires.

Another Match Server acquires the same lock while the first process is still working.

Now both processes may operate concurrently.

This is why critical data protection should not rely solely on a simple distributed lock.

Database constraints, transactions, version checks, or fencing mechanisms should protect the final state.

Idempotency: Essential for Realtime Backend APIs
Idempotency means repeating the same logical request does not apply its side effects multiple times.

This is essential for systems such as:

Payment delivery
Reward claims
Purchase requests
Mail attachments
Match settlement
Marketplace transactions
Suppose the client sends:

POST /purchase
transaction_id = 882731
The Realtime Backend processes it successfully.

The network connection disappears before the client receives the response.

The client retries:

POST /purchase
transaction_id = 882731
Without idempotency, the purchase might run again.

A safer design stores the transaction identifier.

For example:

transaction_id
player_id
operation_type
status
result
created_at
When the same transaction ID appears again, the backend returns the previous result rather than executing the operation again.

Database Unique Constraints as the Final Defense
Application code can contain bugs.

Multiple Match Server instances may also bypass application-level checks under unusual conditions.

Database constraints provide an additional protection layer.

For example, if each external payment transaction must only be processed once:

provider_transaction_id UNIQUE
Two Realtime Backend workers may attempt to insert the same transaction.

Only one should succeed.

This is often much safer than:

SELECT transaction
IF not found:
INSERT transaction
because the check and insertion can race.

For critical invariants, the database should enforce uniqueness whenever possible.

Inventory Concurrency
Inventory systems are especially vulnerable to concurrency problems.

Consider a stackable item:

Item: Potion
Quantity: 10
Two Match Servers simultaneously process:

Consume 3
Consume 4
Both read:

Quantity = 10
Server A writes:

Quantity = 7
Server B writes:

Quantity = 6
The correct result should have been:

Quantity = 3
One update has been lost.

Possible solutions include:

Atomic decrement operations

Optimistic version checking

Row-level database locking

Serialized player commands

Authoritative inventory services

Which solution is appropriate depends on the title's architecture and traffic pattern.

Currency and Double-Spending Protection
Virtual currency should be treated similarly to financial state even when it has no direct cash value.

A robust wallet service should typically ensure:

Validate request
↓
Verify idempotency
↓
Check account state
↓
Apply atomic balance change
↓
Record ledger entry
↓
Commit transaction
↓
Return result
Maintaining a transaction ledger can be valuable.

Instead of only storing:

gold = 12500
the backend may record changes such as:

+500 quest_reward
-200 item_purchase
-100 enhancement
+1000 event_reward
This provides much better debugging and auditing capabilities.

When players report missing or duplicated currency, operations teams can inspect the history rather than relying only on the current balance.

Serialize Commands by Player or Entity
Another architecture is to route all mutations for the same player to a single logical processing stream.

For example:

Player Commands
|
Message Queue
|
Partition by Player ID
|
Title Worker
|
Database
Commands for:

player_id = 1024
are processed sequentially.

Commands for different players can still execute in parallel.

This model can dramatically reduce concurrency complexity.

It is particularly useful in systems built around actors, entity workers, or partitioned message processing.

However, serialization does not remove the need for idempotency and durable state protection.

Messages may still be delivered more than once depending on the messaging architecture.

Never Trust Client-Side State
Concurrency protection is also a security requirement.

The client should not be authoritative for operations such as:

new_gold = 50000
new_item_quantity = 100
reward_claimed = false
Instead, the client should request an action.

For example:

Claim reward 771
The Match Server determines:

Does reward exist?
Is player eligible?
Was reward already claimed?
What items should be granted?
Can inventory accept them?
Then the server performs the state transition.

This server-authoritative approach helps prevent both accidental inconsistencies and intentional manipulation.

Cache Consistency and Redis
Redis is commonly used in Realtime Backend architectures for:

Sessions

Cached player data

Counters

Matchmaking state

Presence

Rate limits

Temporary locks

But developers must clearly define which system owns the authoritative state.

A dangerous architecture is:

Database says Gold = 1000
Redis says Gold = 1500
Match Server memory says Gold = 800
without a defined synchronization strategy.

For important persistent data, the Studio should define:

Source of truth
Cache ownership
Cache invalidation
Write ordering
Failure recovery
Persistence strategy
Concurrency becomes much harder when multiple systems can independently modify the same state.

Monitoring Concurrency Problems
Race conditions can be extremely difficult to reproduce.

Production monitoring should therefore include indicators that reveal abnormal behavior.

Useful metrics include:

Transaction conflicts
Database lock wait time
Deadlock count
Optimistic-lock retry rate
Distributed-lock acquisition failures
Duplicate transaction detection
Payment duplicate callbacks
Inventory validation failures
Negative balance attempts
Request retry rates
Database transaction latency
Logs should include identifiers such as:

request_id
transaction_id
player_id
server_id
operation
previous_version
new_version
result
This makes it possible to reconstruct concurrent events during incident investigation.

Distributed tracing can also help identify which Match Server, service, worker, and database operation participated in a transaction.

Scaling Concurrency Control
A common mistake is designing locking mechanisms that work on one Match Server but collapse after horizontal scaling.

Suppose a title initially runs:

Matchserver.exe
and uses:

mutex[player_id]
This works while every request is handled inside one process.

After scaling:

Matchserver01
Matchserver02
Matchserver03
each process has its own mutex.

The locks no longer coordinate with each other.

When reviewing Multiplayer source Code intended for production deployment, always determine whether synchronization mechanisms are:

Process-local
Machine-local
Database-backed
Distributed
The answer affects whether the architecture remains correct after horizontal scaling.

Deployment and Version Compatibility
Concurrency problems can also appear during deployments.

Imagine changing the meaning of an inventory field while some servers still run the older code.

During a rolling deployment:

Server A -> Version 3
Server B -> Version 3
Server C -> Version 4
Server D -> Version 4
both application versions may access the same database.

Database migrations and API changes should therefore be backward-compatible during the transition whenever rolling deployments are used.

A safer sequence is often:

1. Introduce compatible schema changes
2. Deploy code capable of handling both formats
3. Migrate data if necessary
4. Switch application behavior
5. Remove obsolete schema later
   Studios should test concurrent operations during deployment scenarios, not only under a single application version.

How to Analyze This in Multiplayer source Code
When evaluating Multiplayer source Code from a marketplace, repository, or legacy project, search for every place where persistent player state changes.

Important modules include:

InventoryService
WalletService
PaymentService
RewardService
MailService
ShopService
GuildService
TradeService
AuctionService
QuestService
Then inspect how each operation handles concurrent requests.

Questions to ask include:

Is validation separated from the write?
Look for patterns similar to:

Read balance
Validate
Perform unrelated work
Update balance
Large gaps between validation and mutation can create race conditions.

Are database transactions used?
Check whether related state changes commit together.

For example:

Deduct currency
Grant item
Write purchase record
should not normally leave half-completed results.

Are unique constraints present?
Inspect the database schema for critical identifiers such as:

payment_transaction_id
order_id
reward_claim_id
trade_id
Is there idempotency?
Look for:

request_id
operation_id
transaction_id
idempotency_key
and determine whether retries are safe.

Is locking local or distributed?
A simple language-level mutex may work only inside one Match Server process.

This becomes especially important when adapting older Multiplayer source Code for Kubernetes, containers, or horizontally scaled infrastructure.

Developers using the forum to evaluate Multiplayer development projects should inspect these backend details before assuming that a project designed for a single server will automatically support multi-server production traffic.

Common Mistakes
Using SELECT Then UPDATE Without Protection
Reading a value, checking it in application code, and writing it later can create race conditions.

Prefer atomic conditional updates or explicit concurrency control where appropriate.

Using Redis Locks for Everything
Distributed locks add operational complexity.

Many problems can be solved more reliably using:

Atomic database updates

Unique constraints

Transactions

Optimistic locking

Partitioned processing

Use distributed locks when there is a genuine cross-process coordination requirement.

Trusting the Client to Prevent Duplicate Requests
Disabling a button after the first click improves UX but is not a security or consistency mechanism.

The Realtime Backend must remain safe even when the same request arrives multiple times.

Ignoring Retries
Network failures are normal.

Design APIs assuming requests may be retried.

Holding Locks Too Long
Long critical sections reduce scalability and can cause severe latency under load.

No Transaction History
Storing only the current balance makes production debugging much harder.

For important economies, maintain sufficient transaction history to explain state changes.

Best Practices
A production Realtime Backend should follow several principles.

Protect invariants at the database level whenever possible.

Unique constraints, conditional updates, and transactions provide strong final-state protection.

Make important commands idempotent.

Payments, purchases, reward claims, and settlement operations should safely handle retries.

Use the smallest practical lock scope.

Avoid global locks.

Partition by player, guild, match, auction, or another logical entity.

Keep transactions short.

Do not perform unnecessary network calls or expensive computations while holding database locks.

Define one authoritative owner for important state.

Be clear whether persistent state belongs to the database, an authoritative Match Server, or another service.

Monitor conflicts instead of hiding them.

Retrying optimistic conflicts is useful, but track how frequently they occur.

A suddenly rising conflict rate can indicate architecture or abuse problems.

Test with real concurrency.

Load tests should deliberately send simultaneous requests against the same player account.

For example:

50 simultaneous reward claims
20 simultaneous purchases
10 concurrent inventory mutations
Repeated payment callbacks
Reconnect + retry storms
Many concurrency bugs will never appear in ordinary single-user QA.

Conclusion
Concurrency control is one of the less visible but most important parts of scalable Multiplayer development.

A title can have excellent graphics, smooth combat, and well-designed play while still suffering severe production problems if its Realtime Backend allows duplicate rewards, double spending, lost inventory updates, or repeated payment processing.

The solution is not a single locking technology.

Reliable Match Server architectures usually combine several techniques:

Database transactions
Atomic updates
Optimistic or pessimistic locking
Unique constraints
Idempotency
Distributed coordination
Command serialization
Server-authoritative validation
Monitoring and transaction history
The exact combination depends on the workload.

Player wallets may benefit from atomic conditional updates and a transaction ledger. Payments require strong idempotency and unique transaction identifiers. Inventory operations may use version checks or serialized player commands. Complex marketplace transactions may require stronger transactional protection.

For developers analyzing Multiplayer source Code on the forum, concurrency behavior should be treated as a core backend architecture concern rather than an implementation detail. A project that works perfectly with five test accounts can behave very differently when thousands of players generate simultaneous operations across multiple Match Server instances.

Understanding these patterns makes it significantly easier to transform existing Multiplayer source Code into a reliable, scalable, and production-ready online title infrastructure.
