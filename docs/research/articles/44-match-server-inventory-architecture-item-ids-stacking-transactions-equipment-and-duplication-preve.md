#44 – Match Server Inventory Architecture: Item IDs, Stacking, Transactions, Equipment and Duplication Prevention
administrator
administrator
Verified user account
20/08/2026 09:04
•
General Discussion
Match Server Inventory Architecture: Item IDs, Stacking, Transactions, Equipment and Duplication Prevention
Introduction
An inventory system appears simple when viewed from the Client.

A player sees:

Sword ×1
Potion ×25
Gold ×12,500
Upgrade Stone ×83
Behind that interface, however, the Realtime Backend must answer far more difficult questions.

What happens when two Match Servers attempt to modify the same inventory simultaneously?

What happens if a player opens a reward chest at exactly the same moment as an item is consumed?

Can two identical swords share one database row?

How should equipment be represented?

What happens if a network request succeeds but the response never reaches the client?

How do you prevent the same trade, mail attachment, purchase, or dungeon reward from being applied twice?

What happens if the service crashes halfway through moving an item?

These are not theoretical edge cases. Inventory systems sit directly beside some of the highest-value state in an MMORPG or Mobile Title:

Premium currency

Equipment

Enhancement materials

Rare drops

Paid items

Tradeable assets

Event rewards

Mail attachments

Crafting ingredients

Character progression resources

A duplication bug can therefore become both an economy problem and a security problem.

When reviewing Multiplayer source Code on the forum, inventory architecture is one of the most useful areas to inspect because it exposes how the project handles transactions, concurrency, database consistency, identifiers, caching, networking, retries, and authoritative Match Server logic.

This article explains how a production inventory system can be designed safely.

Inventory Data Is Authoritative Server State
The first rule should be straightforward:

The client displays inventory state. The Match Server or Realtime Backend owns inventory state.

A dangerous workflow would be:

Client:
"I consumed item 501.
My remaining quantity is 17."

Server:
quantity = 17
The server is trusting a result calculated by an untrusted client.

A safer flow is:

Client
|
| Use item 501
v
Match Server
|
| Authenticate player
| Validate ownership
| Validate quantity
| Validate item rules
| Apply inventory transaction
v
Database
|
v
Server returns updated state
The client requests an action.

The backend calculates its effect.

This principle applies to:

use_item
sell_item
equip_item
unequip_item
craft_item
upgrade_item
claim_reward
trade_item
delete_item
move_item
The Client should not decide the authoritative resulting quantity, currency balance, equipment ownership, or item statistics.

Separate Item Definition From Item Instance
A common inventory design mistake is treating every item as the same kind of database object.

In practice, title inventories often need at least two concepts.

Item Definition
An item definition describes what an item type is.

For example:

## item_definition

item_id: 501
name: Health Potion
max_stack: 99
item_type: consumable
tradeable: true
Another:

item_id: 9001
name: Dragon Sword
item_type: equipment
tradeable: false
This configuration is shared across many players.

Item Instance
Some objects need unique instance state.

For example:

## item_instance

instance_id
owner_player_id
item_definition_id
enhancement_level
durability
random_stat_1
random_stat_2
created_at
Two players may both own item definition 9001, but their swords can have completely different instance data.

Conceptually:

Dragon Sword
Item Definition: 9001

Instance A:
+7 enhancement
92 durability

Instance B:
+12 enhancement
71 durability
Critical +4.2%
These should not be represented merely as:

item_id = 9001
quantity = 2
because the individual objects are no longer identical.

Stackable and Non-Stackable Items
Stackable items are usually appropriate when every unit is functionally interchangeable.

Examples:

Potion ×50
Ore ×200
Event Token ×17
A database model might contain:

## player_inventory_stack

player_id
item_definition_id
quantity
Non-stackable equipment may instead use:

## player_item_instance

instance_id
player_id
item_definition_id
enhancement_level
durability
binding_state
This distinction is important for both database design and networking.

Sending:

{
"itemId": 501,
"quantity": 50
}
works well for a stack of identical consumables.

Equipment may need:

{
"instanceId": "itm_8f21...",
"itemId": 9001,
"upgradeLevel": 12,
"durability": 71
}
The unique identifier becomes critical when the player wants to equip, upgrade, dismantle, trade, or lock one specific copy.

Why Unique Item Instance IDs Matter
Imagine a player owns three identical swords:

Dragon Sword +3
Dragon Sword +8
Dragon Sword +12
A request like:

upgrade item_id = 9001
is ambiguous.

The server needs to know which object is being changed.

A unique instance identifier solves this:

upgrade instance_id = item_7c932...
Instance IDs are useful for:

Equipment

Randomized items

Pets

Mounts

Cards with individual upgrades

Bound items

Items with durability

Items with sockets

Items with expiration dates

They also improve auditability.

If a suspicious item appears in the economy, the studio can potentially trace:

instance created
→ transferred
→ equipped
→ traded
→ mailed
→ sold
provided the backend retains appropriate history.

Inventory Transactions
Now consider a crafting operation.

The player wants:

10 Iron Ore

- # 2 Magic Crystals
  1 Iron Sword
  A dangerous implementation performs:

remove 10 ore

remove 2 crystals

add sword
as unrelated database operations.

What happens if the server crashes after removing the materials but before adding the sword?

The player loses resources.

What happens if the sword is added first and material removal fails?

The player receives a free sword.

These related changes should generally be treated as one logical transaction.

Conceptually:

BEGIN;

verify materials;

remove ore;
remove crystals;

create sword instance;

record crafting transaction;

COMMIT;
If the operation cannot complete correctly, it should not leave only half of the intended state committed.

PostgreSQL provides transaction isolation and concurrency-control mechanisms specifically for situations where multiple database sessions operate on shared data. Its documentation also notes that Serializable transactions may need to be retried when serialization failures occur.

A transaction does not automatically make every inventory architecture correct, but it gives the application a controlled atomic boundary for related durable changes.

The Classic Read-Modify-Write Race
Suppose a player has:

Potion quantity = 1
Two requests arrive nearly simultaneously.

Request A:

Read quantity -> 1
Request B:

Read quantity -> 1
Both conclude:

quantity > 0
Both consume the item.

If the application is careless, the player may perform two valid-looking actions using one item.

This is the classic problem with:

read
check
modify
write
when multiple workers can execute concurrently.

Inventory code must assume that concurrent requests are possible.

They may come from:

Multiple client requests

Retries

Different Match Server processes

Background jobs

Trade services

Mail services

Reward processors

Database Row Locking
One solution is to lock the relevant inventory row while processing the mutation.

Conceptually:

BEGIN;

SELECT quantity
FROM player_inventory
WHERE player_id = ?
AND item_id = ?
FOR UPDATE;

-- validate

UPDATE player_inventory
SET quantity = quantity - 1
WHERE ...;

COMMIT;
PostgreSQL supports explicit row-level locking through statements such as SELECT ... FOR UPDATE; its concurrency-control documentation describes row locking and transaction isolation as mechanisms for coordinating concurrent access to data.

The important idea is:

Request A locks inventory state
|
v
Request B cannot independently modify
the same protected state as if nothing happened
However, simply locking everything is not a universal performance solution.

Lock scope should be designed carefully.

Locking an entire player's inventory when only one independent item stack is being changed may reduce concurrency unnecessarily.

Atomic Conditional Updates
For simple quantity changes, the database can sometimes perform validation and mutation together.

Conceptually:

UPDATE player_inventory
SET quantity = quantity - 5
WHERE player_id = ?
AND item_id = ?
AND quantity >= 5;
Then the application verifies whether a row was actually updated.

This avoids:

read quantity
then separately write quantity
for some operations.

The exact approach depends on schema design and database semantics, but the general pattern is valuable:

Move important invariants as close as practical to the atomic mutation.

Examples:

quantity must never become negative

currency must be sufficient before spending

inventory slot must still be available

item must still belong to the player
Idempotency Prevents Duplicate Rewards
Transactions solve one category of problems.

Retries solve another.

Imagine:

Boss defeated
|
v
Reward service grants Legendary Chest
|
v
Database commits successfully
|
X
Network response lost
The caller does not know whether the operation succeeded.

It retries.

Without duplicate protection:

Legendary Chest +1
Legendary Chest +1
The player receives the reward twice.

A safer design associates the operation with a unique business identifier:

reward_event_id = boss_781229_player_10042
Before granting:

Has reward_event_id already been processed?

YES -> return previous result
NO -> perform transaction
This is idempotency.

Common identifiers include:

purchase_id
match_id
mail_claim_id
quest_reward_id
craft_request_id
trade_id
battle_reward_id
The database can reinforce this architecture with unique constraints so that two concurrent workers cannot both successfully create the same logical transaction record.

Item Duplication Through Trade Races
Trading systems are especially dangerous because ownership moves between players.

Suppose Player A trades a sword to Player B.

At nearly the same time, Player A sends the same sword through mail.

If both services independently observe:

owner = Player A
they may both attempt to transfer it.

A robust model should ensure that only one ownership transition can succeed.

Conceptually:

BEGIN

lock item instance

verify:
owner = Player A
state = available

change state / transfer ownership

record transaction

COMMIT
The second operation should then see that the item is no longer available.

A useful item lifecycle might include states such as:

IN_INVENTORY
EQUIPPED
TRADE_LOCKED
MAIL_ATTACHED
MARKET_LISTED
CONSUMED
DELETED
The exact states depend on the title.

The important rule is that one valuable instance should not simultaneously exist in contradictory ownership states.

Equipment Architecture
Equipping an item often changes more than one table or service.

A conceptual structure could be:

Player
|
+-- Inventory
|
+-- Equipment Slots
|
+-- Weapon -> instance 812
+-- Armor -> instance 441
+-- Ring -> instance 991
An equip request may need to validate:

item belongs to player
item is equipment
item fits requested slot
class can equip item
required level satisfied
item is not locked
item is not being traded
Then update both inventory state and equipped state consistently.

If equipping a sword removes another sword from the same slot, the operation should not leave:

two weapons equipped in one slot
because two database statements raced with each other.

Database constraints, locking, and transaction boundaries can all help preserve these invariants.

Inventory Slot Limits
Many titles impose capacity:

Inventory: 98 / 100
Now imagine two rewards arrive simultaneously while only one slot remains.

Both workers read:

98? no
99 / 100
one free slot
Both decide the reward fits.

Both add a new non-stackable item.

The player now has:

101 / 100
This is another concurrency problem.

Capacity checks must participate in the same consistency model as item insertion.

The implementation might:

Lock inventory capacity state.

Reserve slots.

Use a transaction that checks and inserts consistently.

Redirect overflow to a mailbox where product design permits it.

The correct choice depends on the product, but an independent pre-check is not enough under concurrency.

Redis in Inventory Systems
Redis can be valuable in Realtime Backend inventory architecture, but its role must be clearly defined.

Possible uses include:

inventory cache
short-lived locks
session state
request deduplication cache
temporary crafting state
market reservations
rate limiting
Redis transactions based on MULTI, EXEC, and WATCH can provide atomic grouped execution and optimistic locking behavior. With WATCH, EXEC proceeds only when watched keys have not changed since they were observed.

Redis Lua scripts also execute atomically from Redis's perspective; Redis documents that a script executes without interleaving other server activity during that execution.

For example, a Redis-backed temporary reservation might need to:

check available quantity
decrease available quantity
create reservation
as one atomic operation rather than multiple independent network round trips.

However:

Atomic inside Redis does not automatically make a Redis + SQL workflow atomic.

If the application modifies Redis and PostgreSQL separately:

Redis success
Database failure
the system can still become inconsistent.

Distributed state requires a deliberate consistency and recovery strategy.

Cache Is Not Automatically the Source of Truth
Suppose an inventory is cached:

Redis:
player:10001:inventory
and stored durably in PostgreSQL.

The architecture must define:

Which system is authoritative?

When is Redis updated?

What happens if Redis is unavailable?

How is stale cache detected?

Can inventory be rebuilt from the database?
A common safe model is:

Database transaction
|
v
Commit authoritative state
|
v
Invalidate or update cache
rather than assuming two independent writes will always succeed together.

Alternative designs exist, including event-driven architectures, but every design must explicitly handle failure between storage systems.

For valuable inventory, "eventually they will probably match" is not a sufficient specification.

Inventory Version Numbers
A useful Match Server technique is attaching a version to inventory state.

For example:

inventory_version = 812
After a successful mutation:

inventory_version = 813
The server response might return:

{
"inventoryVersion": 813,
"changes": [
{
"itemId": 501,
"quantity": 24
}
]
}
Version numbers help with:

Detecting stale client state

Incremental synchronization

Debugging

Cache validation

Ordering update messages

A version is not a replacement for database concurrency control, but it is useful at the networking boundary.

Sending Full Inventory vs Delta Updates
A beginner implementation may send the complete inventory after every change.

For a small title this can be acceptable.

For larger inventories, delta synchronization is often more efficient.

For example:

{
"inventoryVersion": 814,
"removed": [],
"updated": [
{
"itemId": 501,
"quantity": 23
}
],
"created": []
}
If the client detects that it missed versions:

client version = 810
server version = 814
the server may send a full resynchronization.

This produces a useful architecture:

Normal operation:
delta updates

Desynchronization:
full inventory snapshot
The exact protocol depends on the title, but the client should always be capable of recovering from missing or reordered network messages.

Audit Logs for Valuable Items
For high-value title economies, recording only current state may not be enough.

Suppose a player's rare weapon disappears.

Current inventory tells you:

weapon does not exist
It does not tell you why.

An audit log can record:

transaction_id
player_id
item_instance_id
operation
source
destination
before_state
after_state
created_at
Operations might include:

CREATE
CRAFT
UPGRADE
TRADE
MAIL
SELL
CONSUME
DESTROY
ADMIN_GRANT
Audit history is extremely useful for:

Customer support

Exploit investigations

Economy analysis

Fraud detection

Compensation

Incident recovery

Not every low-value resource requires permanent per-unit history, so retention policies should reflect business value.

How to Analyze This in Multiplayer source Code
When reviewing an inventory implementation on the forum or another Multiplayer source Code project, search for:

inventory
item
item_instance
bag
equipment
stack
quantity
trade
mail
reward
transaction
FOR UPDATE
version
lock
Then follow the complete mutation path.

1. Identify the Authority
   Ask:

Can the client submit final quantities?

Can the client create item IDs?

Can the client determine upgrade results?
If yes, inspect carefully.

2. Distinguish Definitions and Instances
   Check whether unique equipment can actually maintain individual state.

A schema using only:

player_id
item_id
quantity
may be insufficient for randomized or upgradable equipment.

3. Trace Every Item Creation Path
   Items may enter the economy through:

monster drops
quests
shops
payments
mail
crafting
admin tools
events
Every creation path should eventually reach authoritative inventory logic.

4. Trace Every Removal Path
   Likewise:

consume
sell
trade
craft
upgrade
expire
destroy
Removing an item should be just as controlled as creating one.

5. Inspect Transaction Boundaries
   Look for multi-step workflows.

For example:

remove materials
deduct gold
create equipment
write craft history
Determine whether partial completion is possible.

6. Inspect Retry Behavior
   Search for unique operation IDs.

Ask:

What happens if this request runs twice?
If the answer is "the player receives two items," the workflow needs stronger duplicate protection.

Common Mistakes
Trusting Client Inventory State
Clients should request mutations rather than dictate authoritative results.

Treating Every Item as Stackable
Unique equipment requires instance-level identity when individual state differs.

Check-Then-Write Without Concurrency Protection
Two workers may validate the same stale state.

Forgetting Retry Duplication
A successful operation whose response is lost may be executed again.

Using Redis and SQL as If They Were One Transaction
Atomic operations inside one system do not automatically cover another storage system.

Using Distributed Locks as the Only Defense
Locks can help coordinate workflows, but durable database invariants and idempotency are often still necessary.

Updating Inventory Before Payment Is Confirmed
External purchase flows need explicit transaction IDs and payment verification.

No Item Audit Trail
Without history, investigating duplication and lost-item incidents becomes much harder.

Best Practices
A production inventory system should establish several clear rules.

Keep inventory authoritative on the backend.

The Client requests actions; the Match Server validates and commits them.

Separate item definitions from unique item instances.

Do not force randomized equipment into the same model as identical potions.

Use transactions around related durable mutations.

PostgreSQL provides explicit concurrency-control and isolation mechanisms for coordinating concurrent database operations.

Design for retries.

Reward, purchase, mail, trade, and crafting operations should use stable transaction identifiers where duplicate execution would be harmful.

Protect ownership transitions.

An item should not simultaneously be tradeable, mailed, equipped, and sold.

Keep cache behavior explicit.

Know whether Redis contains authoritative state, derived state, reservations, or merely performance-oriented cache data.

Use inventory versions for synchronization.

Versions simplify stale-state detection and client recovery.

Maintain appropriate audit history.

Especially for premium currencies, rare equipment, purchases, trades, and administrative grants.

Conclusion
Inventory architecture is one of the most sensitive components in online Multiplayer development because it combines persistence, concurrency, security, networking, and virtual economy design.

The difficult part is not displaying:

Potion ×25
The difficult part is guaranteeing that the number remains correct when several services, requests, retries, trades, rewards, purchases, crashes, and deployments interact with that inventory simultaneously.

A robust Realtime Backend usually starts with server authority.

It then separates reusable item definitions from unique item instances, uses appropriate transaction boundaries for related database mutations, prevents stale read-modify-write races, designs important operations to be idempotent, and maintains clear ownership rules for equipment and tradeable objects.

PostgreSQL's transaction isolation and locking mechanisms provide tools for coordinating concurrent durable changes, while Redis offers mechanisms such as WATCH/MULTI/EXEC and atomic Lua execution for workloads that genuinely belong in Redis.

Neither technology removes the need for sound architecture.

When inspecting Multiplayer source Code on the forum, developers should therefore go beyond the inventory UI and examine what happens underneath it:

Who owns the state?

How is an item identified?

What prevents simultaneous mutation?

What happens when a request is retried?

Can a trade and mail operation move the same item?

Can Redis and the database become inconsistent?

Can the inventory be reconstructed after failure?

Is there enough history to investigate an exploit?
These questions reveal whether an inventory system was built merely to work during normal play or engineered to remain correct under production concurrency and failure.

For an MMORPG, Mobile Title, or multiplayer Match Server, that distinction can determine whether the virtual economy remains trustworthy as the title scales.
