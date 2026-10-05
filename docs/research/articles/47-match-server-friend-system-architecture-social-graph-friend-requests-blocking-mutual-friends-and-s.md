#47 – Match Server Friend System Architecture: Social Graph, Friend Requests, Blocking, Mutual Friends and Scaling
administrator
administrator
Verified user account
20/08/2026 17:25
•
General Discussion
Match Server Friend System Architecture: Social Graph, Friend Requests, Blocking, Mutual Friends and Scaling
Introduction
A friend system is one of those Multiplayer development features that looks extremely simple in the client:

Friends: 87 / 200

Alice Online
Bob In Match
Charlie Offline

[Add Friend]
[Remove]
[Block]
Behind that interface, however, a production Realtime Backend may need to maintain millions of relationships while handling concurrent friend requests, blocking rules, privacy, mutual-friend queries, online-state integration, notifications, caching, and abuse prevention.

The difficult part is not drawing a friend list.

The difficult part is maintaining a correct social graph when two players modify the same relationship at nearly the same moment.

Consider:

Player A sends request to Player B

At the same moment:

Player B sends request to Player A
Should the backend create two requests?

Should it automatically create a friendship?

What if Player B blocks Player A before one request finishes?

What happens if two Match Servers process the same Accept request?

Can the database accidentally create two friendship records?

These are concurrency and data-modeling problems rather than UI problems.

For developers reviewing Multiplayer source Code on the forum, the social subsystem can reveal whether a project has a properly designed Match Server architecture or simply stores comma-separated friend IDs inside a player record.

This article explains a practical architecture for friend requests, friendships, block lists, mutual friends, Redis caching, database consistency, security, scaling, and monitoring.

A Friendship Is a Relationship, Not a Player Property
A beginner schema may attempt:

## player

player_id
friend_ids
where friend_ids contains something like:

1002,1005,1038,1102
This becomes difficult to query, constrain, update, paginate, and audit.

A friendship is better modeled as a relationship between players.

For example:

## friendship

player_low_id
player_high_id
created_at
The pair:

player_low_id = min(A, B)
player_high_id = max(A, B)
creates a canonical representation.

Therefore:

A = 100
B = 200
and:

A = 200
B = 100
both map to:

(100, 200)
A database uniqueness constraint can then enforce that the pair occurs only once.

PostgreSQL documents that a UNIQUE constraint can cover multiple columns and guarantees uniqueness for their combination. PostgreSQL also automatically creates a unique B-tree index for the constrained columns.

Conceptually:

CREATE TABLE friendship (
player_low_id BIGINT NOT NULL,
player_high_id BIGINT NOT NULL,
created_at TIMESTAMPTZ NOT NULL,
UNIQUE (player_low_id, player_high_id)
);
Application validation still matters, but the database now reinforces an important invariant:

# One friendship pair

One logical relationship
Alternative: Directed Friend Edges
Another design stores two directional edges:

## friend_edge

player_id
friend_player_id
created_at
A friendship between A and B creates:

A -> B
B -> A
This makes querying one player's friend list straightforward:

SELECT friend_player_id
FROM friend_edge
WHERE player_id = ?;
However, both records should be created and removed consistently.

Conceptually:

BEGIN

insert A -> B
insert B -> A

COMMIT
Otherwise a partial failure could produce:

A thinks B is a friend
B does not think A is a friend
The canonical one-row relationship and two-edge model each have tradeoffs.

A Studio should choose based on query patterns, database design, sharding strategy, and operational complexity rather than assuming one representation is universally correct.

Friend Requests Are Different From Friendships
Pending requests should not automatically be represented as friendships.

A possible schema is:

## friend_request

request_id
sender_player_id
receiver_player_id
status
created_at
expires_at
with states such as:

PENDING
ACCEPTED
DECLINED
CANCELLED
EXPIRED
The application should explicitly define valid transitions.

For example:

PENDING
|
+----> ACCEPTED
|
+----> DECLINED
|
+----> CANCELLED
|
+----> EXPIRED
An accepted request may then create the actual friendship relationship.

Separating these concepts makes the backend easier to reason about:

# Friend Request

an invitation

# Friendship

an established social relationship
Keep Friend Operations Server-Authoritative
The Client should request changes.

It should not dictate final social state.

A normal request might be:

{
"targetPlayerId": 10082
}
meaning:

Send a friend request to player 10082.
The Realtime Backend then checks:

Does the target exist?

Is the target the same player?

Are they already friends?

Is there already a pending request?

Has either player blocked the other?

Has either player reached the friend limit?

Is the sender allowed to send more requests?

Does the target's privacy policy permit this request?
The client should not be able to submit:

{
"targetPlayerId": 10082,
"friendshipStatus": "FRIENDS"
}
and make the backend trust it.

Social relationships are authoritative Match Server state.

Handling Simultaneous Friend Requests
Consider:

Player A -> friend request -> Player B

Player B -> friend request -> Player A
Both requests arrive at nearly the same time.

If the application independently checks:

No friendship exists
No request exists
both workers may attempt to create a request.

The Studio must define the desired behavior.

Possible product behavior might be:

A sends request to B
B sends request to A
|
v
Automatically accept friendship
or:

Keep only one canonical pending request
The exact rule is a product-design decision.

Whatever rule is chosen, database constraints and transaction boundaries should prevent contradictory states.

PostgreSQL's transaction isolation documentation explains that concurrent transactions can interact in different ways depending on the selected isolation level, and Serializable provides the strongest isolation semantics while requiring applications to be prepared for serialization failures and retries.

This is why social logic should not assume:

I checked five milliseconds ago,
therefore nobody else changed the relationship.
Accepting a Friend Request Safely
An Accept operation may perform several related changes:

1. Verify request is pending
2. Verify no block exists
3. Verify friend limits
4. Create friendship
5. Mark request accepted
6. Generate notification/event
   The durable state transition should be protected appropriately.

Conceptually:

BEGIN

lock or validate pending request

verify relationship rules

create friendship

mark request accepted

COMMIT
The friendship's unique database constraint provides another defense against duplicate creation.

If two Accept requests are processed simultaneously, both should not produce two logical friendships.

This illustrates a general Realtime Backend principle:

business invariants should not exist only as assumptions inside application code when the database can enforce them as well.

Friend Limits Also Have Concurrency Problems
Suppose the title allows:

Maximum friends = 200
A player currently has:

199 friends
Two different friend requests are accepted simultaneously.

Worker A checks:

199 < 200
Worker B checks:

199 < 200
Both create relationships.

Now:

Friends = 201
If the maximum must be strict, friend-count validation needs to participate in the concurrency design.

Possible approaches include:

Locking appropriate player social state during acceptance.

Maintaining a concurrency-safe counter.

Serializing relationship mutations for a player.

Using another transaction strategy suitable for the database model.

Simply checking COUNT(\*) before an unrelated insert does not guarantee that another transaction cannot modify the count concurrently.

Blocking Must Override Normal Social Actions
Block lists are not just another friend-list category.

A block may affect:

friend requests
private messages
party invitations
guild invitations
profile visibility
match invitations
social notifications
presence visibility
A possible table is:

## player_block

blocker_player_id
blocked_player_id
created_at
with:

UNIQUE(blocker_player_id, blocked_player_id)
If Player A blocks Player B, the backend must define what happens to an existing friendship.

Common possibilities include:

Block automatically removes friendship
or:

Block prevents interaction while preserving some internal relationship history
The product rule should be explicit.

Security-sensitive checks should happen server-side.

A modified client should not be able to bypass a block simply because it still displays an old cached friend record.

Avoid Privacy Leaks Through Social APIs
Suppose Player A has blocked Player B.

Player B asks:

Is Player A online?
If the presence API still returns:

ONLINE
Current Map: Dungeon 31
Party Size: 4
the block system may be leaking information the product intended to hide.

Therefore social visibility rules should be applied before returning:

Presence

Last-online timestamps

Current server

Current map

Party state

Guild state

Match status

This means a Friend Service may integrate with the Session or Presence Service while still enforcing its own privacy rules.

The architecture should not blindly expose everything one backend service knows to every Client.

Redis Sets for Friend Cache
A durable relational database can remain the authoritative source while Redis accelerates common social queries.

Redis Sets are unordered collections of unique strings. Redis documents that adding, removing, and testing membership can be performed with set operations, and Redis Sets naturally maintain uniqueness of members.

For example:

friends:player:1001

{
1002,
1008,
1041,
1209
}
Adding a cached friendship:

SADD friends:player:1001 1002
Redis returns whether the member was newly added, and duplicate members do not create duplicate Set entries.

Membership checks can then answer questions such as:

Are players 1001 and 1002 friends?
without repeatedly querying the relational database.

However, caching introduces another question:

What happens if the database commits
but the Redis cache update fails?
The architecture needs a recovery strategy.

A common approach is to treat the database as authoritative and invalidate or rebuild stale cache state rather than treating two independent writes as magically atomic.

Mutual Friends
A useful social feature is:

12 Mutual Friends
If Redis maintains friend sets:

friends:A
friends:B
Redis provides SINTER, which returns the intersection of multiple Sets.

Conceptually:

SINTER friends:A friends:B
might return:

{
PlayerC,
PlayerD,
PlayerE
}
Those are mutual friends.

This makes Redis attractive for some social-graph queries.

However, a Studio should not assume that intersecting very large sets is free. Redis categorizes SINTER as a command whose cost depends on the sets involved rather than a constant-time membership lookup.

Therefore:

Show 3 mutual friends for normal players
and:

Calculate full intersections for celebrity accounts
with millions of relationships
are very different workloads.

Query design should match the expected graph size.

Do Not Fetch an Entire Huge Friend Set Unnecessarily
Redis SMEMBERS returns all members of a Set and is categorized differently from constant-time membership operations.

That means APIs should be designed carefully as social graphs grow.

A Client generally needs:

20 or 50 friends per page
not:

every social relationship ever created
For persistent database storage, pagination should be designed around stable indexed columns.

The backend may return:

{
"friends": [...],
"nextCursor": "..."
}
rather than an unlimited array.

The implementation should also distinguish:

friend count
from:

friend list
because calculating or transferring an entire list merely to display 87 / 200 is wasteful.

Friend Profiles Should Be Lightweight
A friend list often displays:

avatar
nickname
level
online state
last active
guild
A naive implementation may request the complete Player Profile Service independently for every friend:

Friend 1 -> profile query
Friend 2 -> profile query
Friend 3 -> profile query
...
Friend 100 -> profile query
This creates an N+1 service-call pattern.

A better social read model may cache or batch lightweight public profile data:

player_id
display_name
avatar_id
level
public_status
The Friend Service then combines:

relationship data

- public profile summary
- allowed presence information
  before returning a compact result to the Client.

Sensitive player fields should not be included merely because they happen to exist in the same database.

Events and Notifications
Friendship changes often need to notify other backend systems.

For example:

FRIEND_REQUEST_CREATED
FRIEND_REQUEST_ACCEPTED
FRIEND_REMOVED
PLAYER_BLOCKED
Consumers might include:

Notification Service
Achievement Service
Analytics
Chat Service
Recommendation System
A useful architecture is:

Friend Service
|
| authoritative transaction
v
Database
|
v
Social Event
|
┌──┼──────────────┐
v v v
Push Notification Chat Analytics
The core friendship transaction should not depend on every optional downstream consumer being available.

For example, a friendship should not fail simply because an analytics consumer is temporarily offline.

If events can be retried, downstream consumers should also handle duplicate delivery safely.

Friend Recommendations
A recommendation system may use signals such as:

mutual friends
same guild
recent party members
same server
similar progression
previous cooperative matches
But recommendation data should not bypass block or privacy rules.

For example:

A blocked B
should normally prevent:

"You may know Player A"
from being shown to B if that would violate the title's intended block semantics.

Recommendation quality is secondary to relationship correctness and privacy.

Rate Limiting Friend Requests
Social systems are natural targets for spam.

An attacker might attempt:

send 10,000 friend requests per minute
or repeatedly:

request
cancel
request
cancel
to generate notifications.

The Realtime Backend should apply limits appropriate to the title.

Possible dimensions include:

requests per account
requests per target
requests per time window
pending request limit
recently rejected cooldown
The limits should be enforced server-side.

Client button cooldowns improve UX but are not security controls because modified clients can call APIs directly.

Scaling the Social Graph
As the player population grows, social relationships can become a major data set.

Suppose:

20 million accounts
average 80 friends
The total number of stored edges can become very large.

A Studio may eventually partition social data by:

player_id
region
account shard
But undirected relationships introduce an interesting problem:

Player A belongs to shard 1
Player B belongs to shard 9
A relationship connects both.

This is one reason some architectures use a dedicated Social Service that owns the relationship graph rather than allowing every Match Server shard to independently manage friendship tables.

Conceptually:

Match Server A ──┐
Match Server B ──┼──> Social Service
Match Server C ──┘ |
v
Social DB / Cache
Match Servers request social operations through a stable API.

The Social Service owns consistency rules.

Cache Invalidation
Suppose A removes B.

The database correctly deletes the friendship, but:

Redis friends:A still contains B
The UI may continue showing the relationship.

A cache strategy therefore needs:

update
invalidate
rebuild
rules.

Possible workflow:

Database transaction succeeds
|
v
Invalidate A friend cache
Invalidate B friend cache
|
v
Next read rebuilds from source of truth
Another system may update the cached sets immediately.

Neither strategy removes the possibility of a cache operation failing.

Therefore the application needs to tolerate stale cache and provide eventual correction.

For highly valuable or privacy-sensitive decisions such as block enforcement, the Studio should carefully decide whether stale cache is acceptable.

How to Analyze This in Multiplayer source Code
When examining social features in Multiplayer source Code from the forum or another repository, search for:

friend
friend_request
social
block
blacklist
mutual_friend
friend_list
add_friend
remove_friend
accept_friend
Then trace the full relationship lifecycle.

1. Find the Source of Truth
   Determine whether friendships live in:

SQL database
Redis
player blob
local Match Server memory
Understand what happens after a server restart or cache loss.

2. Check Relationship Uniqueness
   Ask:

Can A and B accidentally have two friendship rows?
Database-level uniqueness is stronger than relying solely on an application pre-check. PostgreSQL supports multi-column unique constraints specifically for this type of invariant.

3. Check Simultaneous Requests
   Test:

A sends request to B
B sends request to A
and:

Accept clicked twice
The resulting state should remain deterministic.

4. Inspect Block Behavior
   Trace what blocking does to:

friendship
friend requests
chat
party invites
presence
profile visibility 5. Inspect Cache Semantics
If Redis contains friend Sets, determine:

Is Redis authoritative?

How is cache rebuilt?

What happens after cache update failure? 6. Check API Pagination
A social API should not assume friend lists remain tiny forever.

7. Inspect Abuse Protection
   Search for:

rate_limit
request_limit
cooldown
spam
block
privacy
Common Mistakes
Storing Friends as a Comma-Separated Player Field
This makes constraints, indexing, pagination, and concurrent updates unnecessarily difficult.

Creating Two Friendship Rows Accidentally
Without canonical representation or a proper directed-edge invariant, A-B and B-A can become duplicated logical relationships.

Relying Only on "Check Before Insert"
Concurrent requests can pass the same check. Use database constraints where appropriate.

Ignoring Friend-Limit Races
Two simultaneous accepts can exceed the configured maximum if the count is not protected.

Treating Block as UI-Only
Backend APIs must enforce block and privacy rules.

Returning Full Friend Lists for Every Request
Large graph reads should use pagination and compact public-profile data.

Treating Redis Cache as Automatically Consistent With SQL
Two separate systems require explicit synchronization and recovery logic.

Sending Unlimited Friend Requests
Social spam can affect both players and backend infrastructure.

Best Practices
A production Friend Service should follow several principles.

Keep relationships server-authoritative.

Clients request social actions; the Realtime Backend validates them.

Model friendships explicitly.

Use relationship tables rather than embedding uncontrolled friend arrays inside player records.

Enforce uniqueness in durable storage.

PostgreSQL multi-column unique constraints can guarantee that a relationship identifier or canonical player pair does not appear twice.

Design for concurrent operations.

Friend requests, acceptance, removal, blocking, and friend limits can all race.

Use Redis where it fits.

Redis Sets naturally support unique membership and set operations such as intersection, making them useful for cached friend membership and some mutual-friend workloads.

Keep the database/cache ownership model clear.

Know which system can reconstruct the other.

Apply privacy before returning social information.

Presence and profile data should respect relationship and block rules.

Rate-limit social mutations.

Friend requests and invitations are externally triggerable Match Server operations and should be protected from abuse.

Monitor graph consistency.

Detect duplicated relationships, asymmetric edges where symmetry is required, cache discrepancies, failed accept operations, and unusual request volumes.

Conclusion
A friend system is a social graph hidden behind a simple Client interface.

The visible feature might contain only:

Add Friend
Accept
Remove
Block
but every button changes shared Realtime Backend state involving at least two accounts.

That makes concurrency and data modeling central to the architecture.

A reliable implementation separates pending requests from established friendships, represents relationships with enforceable database invariants, handles simultaneous operations safely, treats blocks as authoritative privacy rules, and avoids trusting the client to define social state.

PostgreSQL unique constraints can enforce multi-column uniqueness directly in durable storage, helping ensure that duplicate logical relationships cannot simply be inserted because two Match Servers raced.

Redis Sets can complement the database by caching unique friend membership and supporting operations such as intersections for mutual-friend queries. Redis documentation confirms that Sets maintain unique members and provide native set-intersection functionality through SINTER.

But the cache should not obscure the most important architectural question:

Which system owns the truth?
When reviewing Multiplayer source Code on the forum, developers should examine whether the project can correctly handle:

two simultaneous friend requests

duplicate Accept requests

friend-limit races

blocking during an active friendship

stale Redis data

millions of relationships

mutual-friend queries

spam and privacy rules
Those details reveal whether the social feature is simply a UI list or a production-oriented Match Server subsystem.

As an MMORPG, Mobile Title, or multiplayer title grows, the social graph becomes shared infrastructure used by chat, guilds, parties, recommendations, presence, matchmaking, and LiveOps systems.

Designing it correctly early can prevent a surprisingly large class of consistency, privacy, and scaling problems later in the title's lifecycle.
