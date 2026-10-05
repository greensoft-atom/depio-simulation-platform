#49 – Match Server Auction House Architecture: Listings, Bidding, Buyout Transactions, Expiration and Economy Safety
administrator
administrator
Verified user account
20/08/2026 17:32
•
General Discussion
Match Server Auction House Architecture: Listings, Bidding, Buyout Transactions, Expiration and Economy Safety
Introduction
An auction house can become one of the most dangerous systems in an MMORPG or online Mobile Virtual economy.

From the player's perspective, the workflow looks simple:

Dragon Sword +12

Current Bid: 125,000 Gold
Buyout: 180,000 Gold
Time Left: 01:42:18

[Bid]
[Buy Now]
Behind that interface, the Realtime Backend must coordinate ownership, currency, inventory, concurrent bids, expiration, cancellation, database transactions, notifications, fees, and item delivery.

Several players may try to buy the same item at nearly the same moment.

A seller may attempt to cancel a listing while another player is bidding.

Two Match Servers may process competing buyout requests.

A payment transaction may commit while the response to the client is lost.

An auction may expire while a bid is being processed.

These situations can create:

Duplicated items

Lost items

Negative currency balances

Duplicate refunds

Incorrect auction winners

Economy inflation

Player-support incidents

For developers examining Multiplayer source Code on the forum, the Auction House is therefore one of the best places to inspect whether the project has proper Match Server transaction architecture.

A reliable implementation should treat the marketplace as part of the title's economy infrastructure rather than as a simple CRUD interface.

The Client Must Never Own Auction State
As with inventory, quest rewards, mail, and party state, marketplace operations must remain server-authoritative.

A valid Client request might be:

{
"listingId": "auction_928142",
"bidAmount": 130000
}
The backend decides whether that bid is valid.

A dangerous implementation would trust:

{
"listingId": "auction_928142",
"item": {
"itemId": 9001,
"upgradeLevel": 12
},
"sellerId": 10001,
"winnerId": 10082,
"finalPrice": 130000
}
The client should never define:

Item ownership

Seller identity

Current highest bid

Auction winner

Final price

Currency deduction

Item delivery

The authoritative Realtime Backend should load those values from trusted state.

A Basic Auction Listing Model
A marketplace listing might contain:

## auction_listing

listing_id
seller_player_id
item_instance_id
quantity
starting_price
buyout_price
current_price
current_bidder_id
status
created_at
expires_at
version
Possible statuses include:

ACTIVE
SOLD
EXPIRED
CANCELLED
SETTLING
For stackable commodities, the listing may represent:

Iron Ore ×500
For unique equipment, the listing should normally reference an immutable item instance:

item_instance_id = itm_91f8...
That distinction matters because unique equipment may contain:

upgrade_level
random_stats
durability
binding_state
sockets
which cannot safely be represented merely as:

item_id = 9001
quantity = 1
Listing an Item Requires Ownership Transfer or Reservation
One of the most important marketplace rules is:

A listed item should not remain freely usable by the seller.

Suppose the seller lists:

Dragon Sword +12
but the item remains available in their normal inventory.

The player might simultaneously:

equip sword
trade sword
mail sword
destroy sword
sell sword to NPC
while the Auction House still offers it to another player.

A safer item lifecycle is:

IN_INVENTORY
|
| create auction
v
MARKET_LISTED
|
+----> SOLD
|
+----> RETURNED
The listing transaction should verify ownership and move the item into marketplace-controlled state.

Conceptually:

BEGIN

lock item instance

verify:
owner = seller
state = IN_INVENTORY
tradeable = true

create auction listing

set item state = MARKET_LISTED

COMMIT
PostgreSQL row-level locks such as SELECT ... FOR UPDATE prevent conflicting writers from modifying the same locked row until the transaction ends.

This is one way to stop simultaneous marketplace, mail, and inventory operations from independently claiming the same item.

Escrow Is Safer Than Leaving Assets With Players
The marketplace should conceptually behave like escrow.

When an item is listed:

Seller Inventory
|
v
Auction Escrow
When it sells:

Auction Escrow
|
v
Buyer Inventory
If it expires:

Auction Escrow
|
v
Seller Return
The item should have one authoritative state during the listing.

This architecture makes it much harder for an item to exist logically in two locations simultaneously.

The same principle applies to currencies when bids reserve player funds.

Bidding Is a Concurrency Problem
Consider an auction:

Current Bid: 100,000
Player A bids:

110,000
Player B simultaneously bids:

120,000
A dangerous implementation performs:

read current bid

compare bid

write new bid
independently on both Match Servers.

Both workers may read:

100,000
before either commits.

The backend must guarantee that the final result reflects a valid serial ordering.

One possible transaction is:

BEGIN

lock auction listing

verify status = ACTIVE
verify current time < expires_at

verify new bid > current bid
verify bidder has enough currency

reserve new bidder currency

refund previous bidder reservation

update current bid

COMMIT
PostgreSQL's Serializable isolation level guarantees that successfully committed concurrent transactions behave as if they ran one at a time in some order. Applications using Serializable must also handle serialization failures by retrying transactions.

Explicit locking and Serializable transactions are different approaches; the correct choice depends on the schema, contention, performance requirements, and broader transaction design.

Never Deduct Currency With Unsafe Read-Modify-Write
Suppose the bidder has:

Gold = 150,000
At the same moment:

Auction Service wants 120,000
Shop Service wants 50,000
If both services independently read:

Gold = 150,000
both may approve the operation.

Total spending becomes:

170,000
even though only:

150,000
was available.

Currency is shared economy state.

Auction bidding should therefore use the same authoritative Economy Service or transaction rules as:

Shop purchases

Crafting

Trading

Guild donations

Upgrade costs

Mail fees

A conditional mutation might conceptually enforce:

UPDATE player_currency
SET gold = gold - :amount
WHERE player_id = :player
AND gold >= :amount;
with the application verifying that the deduction actually succeeded.

Complex workflows may require additional locking or ledger records.

Reserved Funds vs Immediate Charging
A bidding marketplace has to decide what happens to money while a player is the highest bidder.

A common model is reservation:

Wallet:
200,000

Available:
80,000

Reserved for auction:
120,000
The player still owns the funds, but cannot spend them elsewhere.

If outbid:

reserved amount -> available balance
If the player wins:

reserved amount -> seller settlement
This is often easier to reason about than repeatedly deducting and refunding currency without tracking why each balance transition occurred.

A durable reservation table might contain:

## currency_reservation

reservation_id
player_id
listing_id
amount
status
created_at
with states such as:

ACTIVE
RELEASED
CAPTURED
This gives the Economy Service an auditable representation of held funds.

Buyout Transactions Are Especially Sensitive
Suppose an item has:

Buyout Price: 180,000
Two players click Buy Now simultaneously.

Only one can win.

A safe logical transaction looks like:

BEGIN

lock listing

verify:
status = ACTIVE
not expired
buyout available

verify buyer funds

deduct or capture buyer currency

mark listing SOLD
set winner
set final price

transfer item ownership or create delivery record

create seller settlement

COMMIT
The second request should observe that the listing is no longer active.

This is exactly the type of shared-row mutation where PostgreSQL row-level locking can prevent two writers from concurrently modifying the same listing row.

The important point is not the specific SQL syntax.

The important invariant is:

# One listing

At most one successful buyer
Use Idempotency for Buyout Requests
Now consider a different failure.

Player A clicks Buy Now.

The server processes:

Gold -180,000
Item acquired
Listing SOLD
and commits successfully.

But the response is lost.

The Client retries.

Without idempotency, the second request might:

Deduct money again

Deliver another item

Generate duplicate seller payment

A stable request identifier can protect the workflow.

For example:

purchase_operation_id =
auction_928142_player_10082_buyout
The backend records that operation.

If the same logical request is retried:

already completed?
|
+-- YES -> return previous result
|
+-- NO -> process
For valuable economy operations, idempotency should exist alongside transactional consistency.

They solve different problems:

Transactions
-> concurrent partial-state safety

Idempotency
-> retry and duplicate-request safety
Auction Expiration Must Use Server Time
A listing might expire at:

expires_at = 2026-08-20 18:00:00 UTC
The Client may display:

Time Remaining: 08:23
but the client countdown should not determine whether bidding remains valid.

When processing a bid or buyout, the authoritative backend verifies:

server_time < expires_at
The client clock can be manipulated.

Server-side expiration is therefore mandatory for valuable auction state.

Expiration and Bidding Can Race
Suppose an auction expires at:

18:00:00
At exactly that boundary:

Expiration Worker:
marks listing expired

Bid Worker:
attempts new bid
The Realtime Backend must have one deterministic outcome.

A transaction might lock the listing and evaluate:

status
expires_at
before accepting the bid.

The expiration worker should use the same authoritative state transition rules.

This prevents impossible states such as:

listing = EXPIRED
current_bidder updated afterward
or:

winner calculated twice
Expiration Does Not Need Millions of Exact Timers
A naive system might create one application timer for every listing.

For a marketplace containing millions of auctions, this becomes operationally awkward.

Instead, listings can store explicit deadlines:

expires_at
and background workers can find expired active listings in batches.

Conceptually:

SELECT expired ACTIVE listings
LIMIT 500
Workers process them repeatedly.

PostgreSQL supports SKIP LOCKED with locking queries. Its documentation notes that SKIP LOCKED can be useful for multiple consumers accessing a queue-like table because workers can skip rows already locked by other workers. It is not suitable for general-purpose consistent reads because it intentionally gives an inconsistent view.

This pattern can be useful for marketplace settlement workers when designed carefully.

Redis Sorted Sets for Expiration Indexes
Redis Sorted Sets can also index listings by expiration time.

Redis Sorted Sets maintain unique members ordered by numeric scores. Updating an existing member's score with ZADD takes O(log N) time.

For example:

auction:expires

score = expiration Unix timestamp
member = listing ID
Conceptually:

ZADD auction:expires 1787220000 auction_928142
A worker can query listings whose scores are before the current time.

This can provide a fast scheduling index.

However:

Redis should not automatically become the only source of truth for valuable auction ownership.

If the Redis index is lost or temporarily stale, the durable database should still know:

listing status
item owner/state
expires_at
winner
settlement state
Redis can accelerate discovery.

The durable marketplace transaction model should determine business correctness.

Redis TTL Is Useful for Temporary State
Redis TTL reports the remaining key lifetime, while EXPIRE sets an expiration in seconds.

TTL can be useful for:

temporary search caches
rate-limit counters
short-lived reservation metadata
UI query caches
auction notification deduplication
For example:

auction:search:sword:page:1
TTL = 10 seconds
But allowing the authoritative listing itself to disappear solely because a cache key expired may be inappropriate when settlement, refund, or audit processing still needs that record.

Play expiration and physical data deletion are different concepts.

Search Architecture Should Be Separate From Settlement
Players may search auctions using:

item type
level
rarity
price
upgrade level
remaining time
seller region
These queries can become expensive.

A mature architecture often separates:

Auction Transaction System
from:

Auction Search Index
Conceptually:

Authoritative Auction DB
|
v
Auction Events
|
v
Search Index / Cache
|
v
Client Browse API
The search index can tolerate small delays more easily than the buyout transaction.

For example, a stale search result may temporarily show:

Dragon Sword
Buyout 180,000
after another player already bought it.

When the user clicks Buy Now, the authoritative transaction still responds:

Listing no longer available
That is much safer than allowing the search cache to decide ownership.

Auction Price Sorting With Redis
Redis Sorted Sets may also be useful for some marketplace indexes.

For example:

auction:item:9001:price
where:

member = listing ID
score = price
Redis maintains members ordered by their score and supports range queries.

This can accelerate:

show cheapest 50 listings
But several complexities remain:

Multiple filtering dimensions

Equal prices

Listing deletion

Expiration

Region filtering

Item attributes

Database/cache synchronization

For complex marketplace search, a dedicated search/index architecture may eventually be more suitable than building every query from Redis Sorted Sets.

Use data structures based on actual query patterns.

Seller Settlement
When an auction succeeds, the seller may receive:

## sale price

marketplace fee
For example:

Sale Price: 180,000
Tax 5%: 9,000
Seller Gets: 171,000
This should be calculated server-side.

A settlement record can preserve:

listing_id
seller_id
buyer_id
sale_price
fee
seller_proceeds
created_at
status
Settlement can either:

credit seller immediately
or:

send proceeds through mail
depending on product design.

If asynchronous delivery is used, the settlement should remain durable so that a temporary Mail Service failure does not cause the seller's proceeds to disappear.

Auction Cancellation
Cancellation should have explicit rules.

Possible rules:

Seller may cancel only when no bids exist
or:

Seller may cancel with a fee
or:

Bidding auctions cannot be cancelled
The backend must enforce these rules.

A disabled Cancel button in the client is not enough.

A cancellation transaction might:

lock listing

verify seller
verify ACTIVE
verify cancellation allowed

set CANCELLED

return item to seller delivery workflow
If there is an active currency reservation from a bidder, the cancellation path must release it correctly.

Item Return Should Be Reliable
When an auction expires without sale:

Marketplace
|
v
Seller
The return should not depend on the seller being online.

Possible strategies include:

Directly returning the item to inventory

Sending it through Title Mail

Moving it to marketplace retrieval storage

Mail-based return can be attractive because a full inventory does not necessarily block marketplace settlement.

However, the same transaction safety discussed in article #45 still applies.

An item should not simultaneously remain:

MARKET_LISTED
and appear in:

MAIL_ATTACHED
unless that intermediate transition is explicitly modeled.

Auction History and Audit Logs
Marketplaces should be highly auditable.

Useful records include:

listing created
item escrowed
bid placed
bidder outbid
currency reserved
reservation released
buyout completed
auction expired
item returned
seller paid
listing cancelled
For high-value equipment, the Studio should ideally be able to trace:

Item instance 91831

created by dungeon reward
-> Player A inventory
-> auction listing 928142
-> purchased by Player B
-> Player B inventory
This becomes invaluable for:

Duplication investigations

Customer support

Economy analysis

Fraud detection

Compensation

Administrative audits

Economy Abuse and Market Manipulation
A technically correct Auction House can still become an abuse vector.

The Realtime Backend may need to detect:

rapid repeated listings
unusual price transfers
same accounts repeatedly trading
extreme price deviations
bot-generated listings
suspicious currency movement
This does not mean automatically banning every unusual trade.

Legitimate markets can contain extreme prices.

Instead, analytics can generate risk signals for investigation.

Useful features may include:

median item price
price distribution
seller frequency
buyer/seller relationship
account age
currency movement velocity
The auction system is often one of the clearest windows into the virtual economy.

How to Analyze This in Multiplayer source Code
When reviewing Auction House Multiplayer source Code from the forum or another project, search for:

auction
market
listing
bid
buyout
market_price
seller
buyer
expires_at
escrow
reservation
settlement
Then trace the full transaction lifecycle.

1. Check Item Ownership During Listing
   Ask:

Can the seller still use the listed item?
If yes, duplication risk deserves investigation.

2. Inspect Concurrent Buyout
   Simulate:

Player A -> Buy Now
Player B -> Buy Now
at the same time.

Only one transaction should succeed.

3. Inspect Bidding
   Check whether the system safely handles:

current bid
new bidder
previous bidder refund
currency reservation
inside a consistent workflow.

4. Check Retry Safety
   Ask:

What happens if the buyout committed
but the client receives a timeout? 5. Inspect Expiration
Determine whether expiration uses trusted Match Server time and whether bids racing with expiration remain deterministic.

6. Inspect Seller Settlement
   Trace:

sale completes
-> fee calculated
-> seller receives proceeds
and determine how failed delivery is recovered.

7. Inspect Redis Usage
   Determine whether Redis contains:

search cache
price index
expiration index
authoritative ownership
and whether the design clearly distinguishes those responsibilities.

Common Mistakes
Leaving Listed Items Fully Available in Inventory
The seller may trade, mail, equip, or destroy an item that another player is purchasing.

Check-Then-Buy Without Locking or Serializable Protection
Two buyers can both believe the same listing is available.

Treating Search Cache as Authority
A stale cached listing must not be allowed to determine the final sale transaction.

Deducting Currency Without Shared Economy Concurrency Protection
Other Realtime Backend services may spend the same balance simultaneously.

No Idempotency for Buyout
Network retry can create duplicate deductions or deliveries.

Using Client Time for Expiration
Marketplace deadlines must be server-authoritative.

Deleting Expired Listings Before Settlement
Expired auctions may still need refunds, item returns, history, or auditing.

No Audit Trail
Marketplace duplication bugs become much harder to investigate without transaction history.

Best Practices
A production Auction House should follow several rules.

Move listed assets into controlled marketplace state.

An item should not remain freely usable by the seller while listed.

Keep purchases server-authoritative.

Clients request bids and buyouts; the Realtime Backend calculates all consequences.

Protect listing mutations against concurrency.

PostgreSQL row-level locks can prevent conflicting writers on a listing, while Serializable isolation can ensure committed transaction results are equivalent to some serial execution when used correctly. Serializable transactions must be retried after serialization failures.

Use an Economy Service or durable ledger for currency.

Auction bids should not invent a separate unsafe balance-update path.

Use idempotency for important operations.

Buyout, settlement, refund, and return workflows must tolerate retries.

Separate browsing from settlement.

Search data can be cached or indexed aggressively while final transactions always verify authoritative state.

Use Redis for suitable indexes, not magical consistency.

Sorted Sets can efficiently maintain score-ordered collections such as price or expiration indexes, and TTL is useful for temporary cache state.

Preserve transaction history.

High-value economy operations should remain traceable.

Conclusion
An Auction House is not simply a list of items with Buy and Bid buttons.

It is a distributed economy system where several valuable resources move simultaneously:

Item ownership

Buyer currency

Seller proceeds

Bid reservations

Marketplace fees
Every transition must remain correct under concurrency, retries, expiration, Match Server failure, and asynchronous delivery.

A robust architecture begins by moving listed assets into marketplace-controlled state so that the seller cannot independently manipulate them while they are for sale.

Bids and buyouts should operate against authoritative listing state using transaction and concurrency mechanisms appropriate to the database. PostgreSQL provides both row-level locking and Serializable transaction isolation for coordinating concurrent operations. FOR UPDATE prevents conflicting modification of locked rows, while successfully committed Serializable transactions are guaranteed to behave consistently with some serial execution order.

Redis can complement the durable marketplace database.

Sorted Sets can maintain ordered indexes such as item price or expiration time, with ZADD adding or updating members in O(log N) per item. Redis TTL functionality is also useful for temporary search caches and short-lived operational state.

But cache and indexing technology should never obscure the core rule:

The Auction House transaction system
owns the economic truth.
When developers inspect Multiplayer source Code on the forum, they should therefore test much more than whether listings appear correctly in the Client.

Ask:

Can two players buy the same item?

Can a seller use a listed item?

Can bidding create a negative balance?

What happens when a bid races expiration?

What happens after a successful buyout timeout?

Can expired items always return safely?

Can the studio trace where a rare item came from?

Can the economy recover after a worker failure?
Those questions reveal whether the marketplace was built merely as a Multiplayer development feature or engineered as a production Realtime Backend economy service.

In an MMORPG with valuable virtual assets, that difference is critical. A small transaction bug inside the Auction House can become an economy-wide exploit faster than almost any ordinary play bug.
