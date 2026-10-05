#48 – Match Server Party and Team System Architecture: Invitations, Leadership, Ready Checks, Matchmaking and Disconnect Recovery
administrator
administrator
Verified user account
20/08/2026 17:28
•
General Discussion
Match Server Party and Team System Architecture: Invitations, Leadership, Ready Checks, Matchmaking and Disconnect Recovery
Introduction
A party system appears simple inside a Client:

Party

[Leader] PlayerA
PlayerB
PlayerC
PlayerD

[Ready Check]
[Find Match]
[Leave Party]
Behind this interface, however, a production Realtime Backend must coordinate state shared by several players who may be connected to different Match Servers.

Consider a four-player party.

At almost the same moment:

Player A starts matchmaking.

Player B leaves the party.

Player C disconnects.

Player D clicks Ready.

Player A transfers leadership to Player B.
Which operations should succeed?

Which state should matchmaking receive?

Can a player accidentally belong to two parties?

What happens when the leader disconnects?

Can an expired invitation still be accepted?

Can two players become party leader simultaneously?

These questions make party systems a distributed state-management problem rather than merely a social UI feature.

A robust architecture typically needs to manage:

Party creation

Invitations

Join requests

Membership

Party capacity

Leadership

Ready checks

Party settings

Matchmaking integration

Instance transitions

Disconnect and reconnect

Party chat integration

Expiration

Concurrency

Notifications

For developers inspecting Multiplayer source Code on the forum, the party subsystem is an excellent place to evaluate whether multiplayer state is handled authoritatively and safely.

Party State Should Be Server-Authoritative
The first rule is the same as with inventory, quests, mail, and social relationships:

The Client requests party operations; the Match Server decides the resulting party state.

A client might send:

{
"partyId": "party_8231",
"action": "LEAVE"
}
or:

{
"targetPlayerId": 10082
}
for an invitation.

A dangerous design would allow the client to send:

{
"partyId": "party_8231",
"members": [
10001,
10002,
10003,
10004
],
"leader": 10001
}
and then overwrite authoritative state.

The backend should instead validate:

Does this party exist?

Is the requesting player a member?

Does the requested action require leader permission?

Is the target already in another party?

Is the party full?

Is matchmaking currently active?

Has the invitation expired?

Is the target eligible to join?
The party state returned to clients should be the result of authoritative backend processing.

A Basic Party Data Model
A relational representation might separate the party from membership.

Party
party

---

party_id
leader_player_id
party_type
max_members
status
created_at
version
Possible statuses:

OPEN
READY_CHECK
MATCHMAKING
MATCH_FOUND
IN_INSTANCE
DISBANDING
Party Members
party_member

---

party_id
player_id
joined_at
member_role
ready_state
A membership uniqueness rule is important.

If the title permits only one active party per player, the data model should make it difficult for concurrent operations to produce:

Player 10001
|
+-- Party A
|
+-- Party B
Application checks alone are not always sufficient because two workers can perform the same check concurrently.

The exact database constraint depends on whether historical membership rows are retained, but the underlying invariant should be explicit.

Party Invitations Need Their Own Lifecycle
An invitation is not the same thing as party membership.

A useful model might contain:

## party_invitation

invitation_id
party_id
sender_player_id
target_player_id
status
created_at
expires_at
Possible states:

PENDING
ACCEPTED
DECLINED
CANCELLED
EXPIRED
The lifecycle could be:

             ┌──> ACCEPTED
             |

PENDING -----+──> DECLINED
|
+──> CANCELLED
|
└──> EXPIRED
Keeping invitation state separate allows the backend to answer:

Was the invitation still valid?

Who sent it?

Which party did it reference?

Was it already accepted?

Did it expire?
without treating a notification displayed in the Client as authoritative state.

Invitation Expiration
Party invitations should usually have a limited lifetime.

For example:

Invitation valid for 60 seconds
The backend can store:

expires_at
and validate:

server_time < expires_at
when the target attempts to accept.

Redis can also hold temporary invitation or notification state. Redis key expiration allows a TTL to be associated with a key, and the key is automatically destroyed after its TTL elapses. EXPIRE sets the expiration and TTL can report the remaining lifetime.

For example:

party_invite:target:10082
TTL = 60 seconds
could be useful as temporary cache or notification state.

However, if the invitation is important to audit or must survive cache loss, the durable invitation record should not depend exclusively on Redis expiration.

The "Accept Invite" Race Condition
Imagine Party A currently contains:

3 / 4 players
Two pending invitations exist:

Player X
Player Y
Both accept simultaneously.

Worker 1 checks:

party size = 3
Worker 2 also checks:

party size = 3
Both conclude:

space available
Both insert a member.

The party now has:

5 / 4
This is the same class of concurrency problem seen in inventory capacity and friend limits.

The operation:

check capacity
then insert member
must not be treated as automatically atomic.

Depending on the schema, possible approaches include:

Locking party state during membership changes

Maintaining a transactionally protected member count

Using stronger transaction isolation

Serializing membership mutations for one party

PostgreSQL documents that under its default Read Committed isolation, separate commands inside one transaction can observe different committed states if other transactions commit between them. It also provides stronger isolation levels and explicit locking mechanisms for cases where application invariants require greater coordination.

Party Leadership Is Shared Mutable State
A normal party has exactly one leader.

That sounds trivial until leadership changes concurrently.

Imagine:

Leader A transfers leadership to B.

At nearly the same time:
A disconnects and automatic leader reassignment selects C.
Without coordination, different services might observe:

leader = B
and:

leader = C
The party should therefore have one authoritative leadership field or equivalent invariant.

A transfer operation might conceptually perform:

BEGIN

lock party

verify requester == current leader
verify target is still a member

set leader = target

COMMIT
PostgreSQL row locks such as SELECT ... FOR UPDATE prevent conflicting row modifications until the transaction ends, making them one possible mechanism when a party row represents the shared mutation boundary.

The exact solution should fit the database model rather than blindly locking every party operation.

What Happens When the Leader Leaves?
The Studio needs a deterministic rule.

Possible policies include:

Oldest remaining member becomes leader
or:

Highest-level member becomes leader
or:

Leader explicitly nominates successor when leaving
A common deterministic approach is based on join order:

A joined 10:00
B joined 10:02
C joined 10:05

A leaves

New leader = B
The important property is predictability.

Avoid selecting:

first member returned by database
unless the query explicitly defines ordering.

Relational databases do not guarantee an application-specific row order simply because one happened to appear consistently during local testing.

Ready Check Architecture
A ready check represents temporary state associated with one particular party state.

For example:

Ready Check #9381

Player A: READY
Player B: READY
Player C: NOT_READY
Player D: PENDING
A useful record might contain:

ready_check_id
party_id
party_version
created_at
expires_at
status
with per-player state:

player_id
response
responded_at
Possible responses:

READY
NOT_READY
PENDING
Why Include Party Version?
Suppose a ready check begins with:

A
B
C
D
Then C leaves and E joins.

Should E inherit C's readiness?

Obviously not.

The backend should know that the party composition changed.

A party version can increment after structural changes:

party version 18
|
member leaves
v
party version 19
A ready check created for version 18 can then be invalidated when the current party version is 19.

This avoids applying old transient state to a different party composition.

Party Versioning
Version numbers are useful beyond ready checks.

For example:

{
"partyId": "party_8231",
"version": 42,
"leader": 10001,
"members": [...]
}
After Player C leaves:

version = 43
Clients receiving:

version 41
version 43
version 42
can recognize that 42 is stale after 43 has already been applied.

Versions can support:

Client synchronization

Cache validation

Ready-check invalidation

Matchmaking handoff

Debugging

Event ordering

A version does not replace transaction isolation or locking, but it is extremely useful at system boundaries.

Matchmaking Should Receive a Party Snapshot
When the leader clicks:

Find Match
the Matchmaking Service needs to know exactly which party entered the queue.

Consider:

Party version 51:
A, B, C, D
Matchmaking begins.

Immediately afterward:

D leaves
The backend must decide whether:

A, B, C remain queued
or:

the entire matchmaking request is cancelled
The product rule can vary.

But matchmaking should not operate against an ambiguous moving target.

A matchmaking request might contain:

{
"partyId": "party_8231",
"partyVersion": 51,
"members": [
10001,
10002,
10003,
10004
],
"queueType": "ranked_4v4"
}
Then if the Party Service reaches:

version 52
the matchmaking workflow knows the original request referred to a previous composition.

Locking Party Mutations During Matchmaking
Some titles prevent structural party changes after matchmaking starts.

For example:

OPEN
|
v
MATCHMAKING
During MATCHMAKING:

invite player -> rejected
kick player -> rejected
change mode -> rejected
but:

cancel matchmaking -> allowed
Other titles allow party members to leave, automatically cancelling matchmaking.

Both approaches are valid.

The important point is to represent this policy on the server.

Do not rely only on disabling UI buttons.

A modified Client can still call APIs directly.

Match Found Is Not the Same as Instance Joined
A useful distinction is:

MATCHMAKING
|
v
MATCH_FOUND
|
v
INSTANCE_ALLOCATED
|
v
PLAYERS_CONNECTING
|
v
IN_INSTANCE
Several failures can occur between these stages.

For example:

Match found
|
v
Match instance allocated
|
X
Player C disconnects
Should the match continue?

Should C receive a reconnect window?

Should the match be cancelled?

Should another player backfill?

These are product decisions that should be reflected explicitly in backend state.

Treating the entire process as:

party.matchId = 123
usually becomes insufficient as the multiplayer architecture grows.

Disconnect Does Not Necessarily Mean Leave Party
Networking disconnect and party membership are different concepts.

A player's connection may disappear because of:

Mobile network change

Wi-Fi interruption

Title crash

Match Server restart

Temporary routing failure

Client backgrounding

Immediately removing a player from the party after every lost TCP/WebSocket connection can produce poor play behavior.

Instead:

CONNECTED
|
v
DISCONNECTED
|
| reconnect grace period
|
+----> RECONNECTED
|
└----> timeout -> removal policy
The Party Service may keep membership while the Session or Presence Service reports temporary disconnection.

This separation is important:

Connection state
!=
Party membership state
Reconnect Tokens and Party Recovery
Suppose the player reconnects to another gateway or Match Server.

The new server should retrieve authoritative party state rather than relying on the previous process's memory.

Conceptually:

Client reconnects
|
v
Authentication Service
|
v
Session restored
|
v
Party Service lookup
|
v
party_id = party_8231
|
v
Send latest party snapshot
This is one reason important party membership should not exist only inside one Match Server's process memory.

If the process crashes, authoritative social state must remain recoverable.

Redis for Fast Party State
Redis can be useful for active-party state because parties are small, frequently accessed objects.

A Redis Hash could conceptually store:

party:8231

leader 10001
status OPEN
version 42
max_members 4
queue_type ranked
Redis documents Hashes as field-value collections suitable for representing simple objects.

Membership might use a Set:

party:8231:members

{
10001,
10002,
10003,
10004
}
Redis Sets maintain unique members and support constant-time add, remove, and membership tests in typical operations.

This naturally prevents the same player ID from appearing twice inside one cached member Set.

However, it does not automatically guarantee:

player belongs to only one party globally
because that invariant spans multiple keys or storage records.

The overall data model still needs an authoritative ownership rule.

Temporary Party Data and TTL
Some temporary state is a natural fit for expiration.

Examples include:

party invitations
ready checks
join requests
match acceptance windows
temporary reconnect metadata
Redis TTL can remove such temporary keys automatically after their deadline.

For example:

party:8231:readycheck:9381
TTL = 30 seconds
But a key disappearing is not the same as executing all business consequences associated with expiration.

If expiration must trigger:

cancel matchmaking
send notification
record penalty
the architecture needs an actual scheduled/event-processing mechanism as well.

TTL is excellent for lifecycle cleanup.

It should not be confused with a reliable business workflow engine.

Party Events
Other services often need to know that party state changed.

Useful events include:

PARTY_CREATED
PARTY_MEMBER_JOINED
PARTY_MEMBER_LEFT
PARTY_LEADER_CHANGED
PARTY_READY_CHECK_STARTED
PARTY_MATCHMAKING_STARTED
PARTY_MATCHMAKING_CANCELLED
PARTY_DISBANDED
Consumers might include:

Notification Service
Chat Service
Matchmaking Service
Analytics
Presence Service
Match Instance Service
An event-driven architecture can decouple the authoritative Party Service from optional consumers.

For example:

Party Service
|
v
Party State Database
|
v
Party Event Stream
/ | \
 v v v
Chat Presence Analytics
Redis Streams for Asynchronous Party Events
Redis Streams can support event-processing workflows.

Redis describes Streams as append-only log structures and supports consumer groups where workers receive subsets of work. Messages processed through a consumer group can remain pending until explicitly acknowledged with XACK, and unprocessed pending entries can be reclaimed.

This makes Streams more appropriate than transient fire-and-forget messaging for workflows where recovery matters.

However, applications should still design consumers for retry behavior.

For example:

PARTY_MEMBER_JOINED
might be processed twice by a downstream notification workflow after failure recovery.

The notification consumer should decide whether duplicate execution is acceptable or whether it needs an event ID for idempotency.

A message system should not be assumed to provide magical end-to-end exactly-once business execution.

Party Chat Integration
Party chat should normally reference:

party_id
rather than trusting a client-supplied recipient list.

A client may send:

{
"partyId": "party_8231",
"message": "Ready?"
}
The Chat Service should validate that the sender is currently authorized for that party.

Otherwise a modified client could potentially reuse an old party identifier or submit arbitrary member IDs.

The chat subsystem may subscribe to:

PARTY_MEMBER_JOINED
PARTY_MEMBER_LEFT
PARTY_DISBANDED
so room membership follows authoritative Party Service state.

Disbanding a Party
Disbanding can mean more than deleting one row.

The backend may need to:

cancel invitations
cancel ready check
cancel matchmaking
close party chat
invalidate cache
notify members
release matchmaking reservation
record analytics
A clean state transition can help:

OPEN
|
v
DISBANDING
|
v
DISBANDED
The operation should also be idempotent.

Calling:

disbandParty(party_8231)
twice should not produce contradictory downstream behavior.

Party Creation Races
Suppose a solo player rapidly presses:

Create Party
twice.

Two requests reach different Match Servers.

Both check:

player has no party
Both create a party.

Now:

Player 10001
-> Party 91
-> Party 92
This demonstrates why:

check player party
then create party
requires concurrency protection.

The database, a player-to-party ownership record, or a single-writer Party Service can enforce the invariant.

If the application uses Serializable PostgreSQL transactions for a relevant workflow, PostgreSQL guarantees behavior equivalent to some serial execution for successfully committed Serializable transactions; applications must also be prepared to retry serialization failures.

Serializable isolation can be powerful, but it should be selected deliberately rather than applied indiscriminately to every party query.

Cross-Server Party Architecture
Large MMORPG systems may have players connected to different world processes:

Player A -> World Server 1
Player B -> World Server 4
Player C -> World Server 7
If party state lives only inside World Server 1:

World Server 1 crashes
the whole party may disappear.

A dedicated Party Service can instead provide shared authority:

World Server 1 ──┐
World Server 4 ──┼──> Party Service
World Server 7 ──┘
|
┌─────┴─────┐
v v
Database Redis
World Servers subscribe to or query state relevant to their connected players.

This architecture also helps when matchmaking relocates the entire team to a separate Battle Server.

Transferring a Party to a Battle Server
A party entering an instance may require:

Party Service
|
v
Matchmaking
|
v
Instance Allocator
|
v
Battle Server
The Battle Server should receive an immutable-enough admission snapshot:

match_id
party_id
party_version
member IDs
team assignment
authentication tickets
The Battle Server should not trust clients to tell it:

"I belong to Party 8231."
Admission should derive from backend-generated match or instance credentials.

This prevents players from fabricating team membership during connection to a battle instance.

Monitoring Party Systems
Useful infrastructure metrics include:

party_create_total
party_join_total
party_leave_total
party_invite_total
party_invite_expired_total

party_accept_conflict_total
party_capacity_rejection_total

ready_check_total
ready_check_timeout_total

matchmaking_start_total
matchmaking_cancel_total

party_disconnect_total
party_reconnect_total

party_event_processing_latency
Useful consistency checks include:

player in multiple active parties

party with zero members

party leader not in party

party member count > max_members

active matchmaking party with stale version

expired invitation still accepted
These invariants can be monitored periodically because production bugs may not always surface through request error rates.

How to Analyze This in Multiplayer source Code
When examining Multiplayer source Code on the forum or another project, search for:

party
team
party_member
party_invite
invite_player
join_party
leave_party
kick_member
party_leader
ready_check
matchmaking
Then trace the authoritative state.

1. Find Where Party Membership Lives
   Determine whether membership exists in:

Match Server memory
SQL database
Redis
dedicated Party Service
Ask what happens after a process restart.

2. Check One-Party-Per-Player Rules
   If the title permits only one party, determine what prevents:

Player A -> Party 1
Player A -> Party 2
during concurrent requests.

3. Inspect Invitation Expiration
   Check whether the Match Server validates expiration rather than relying on the client hiding expired invitations.

4. Inspect Leadership Changes
   Search:

changeLeader
transferLeader
assignLeader
and determine what happens when leadership transfer races with disconnect or leave operations.

5. Inspect Ready Checks
   Determine whether ready state belongs to a specific party composition or can become stale after membership changes.

6. Trace Matchmaking Handoff
   Find the exact transition:

party
→ matchmaking queue
→ match
→ battle instance
and determine how party composition is frozen or versioned.

7. Inspect Disconnect Recovery
   Check whether disconnect immediately destroys membership or whether a reconnect window exists.

Common Mistakes
Keeping Party State Only in One Match Server's Memory
This makes failover, reconnect, and cross-server parties difficult.

Trusting Client Party Composition
The backend must own membership and leadership.

Check-Then-Join Without Concurrency Protection
Two invitations can overfill a party.

No One-Party-Per-Player Invariant
Concurrent party creation or invitation acceptance can place one player in multiple parties.

Treating Disconnect as Immediate Leave
Temporary network loss and social membership are different concepts.

Reusing Ready State After Membership Changes
A ready check should correspond to a known party composition.

Sending Mutable Party State to Matchmaking Without Versioning
The matchmaking request may no longer represent the actual party.

Using TTL as a Business Workflow
Expiration can clean up temporary data but does not automatically execute every consequence associated with timeout.

Best Practices
A production Realtime Backend should follow several principles.

Keep Party Service state server-authoritative.

The client requests party operations rather than defining membership.

Separate party, membership, and invitations.

They have different lifecycles and consistency rules.

Protect shared invariants against concurrency.

Party capacity, leadership, and one-party-per-player rules cannot rely solely on stale pre-checks. PostgreSQL provides transaction isolation and explicit locking mechanisms for coordinating concurrent mutations where appropriate.

Version party composition.

Version numbers help ready checks, matchmaking, clients, and caches detect stale state.

Separate connection state from membership.

Temporary disconnection should not automatically imply social departure unless the title explicitly requires it.

Use Redis for workloads that fit Redis.

Hashes can represent active party objects, Sets can represent unique membership, TTL can handle temporary states, and Streams can distribute asynchronous events.

Design matchmaking as an explicit state transition.

A party entering the queue should produce a known party snapshot or version.

Make operations idempotent where retries are possible.

Repeated leave, disband, ready-response, and asynchronous event operations should not corrupt state.

Conclusion
A party system is one of the clearest examples of shared mutable state in multiplayer development.

One party may simultaneously involve several Clients, different Match Servers, a Party Service, Redis, a relational database, Matchmaking, Chat, Presence, and eventually a Battle Server.

The architecture must therefore answer several questions precisely:

Who owns party state?

Can one player join two parties?

What happens when invitations race?

Who becomes leader after disconnect?

Does a ready check survive membership changes?

Which party version entered matchmaking?

What happens when a player reconnects?

Can an asynchronous event be processed twice?
A reliable Match Server architecture treats these as backend consistency problems instead of UI behavior.

PostgreSQL's concurrency-control model is relevant because simple multi-step SELECT and UPDATE workflows do not automatically become serialized when several requests execute concurrently. The database provides stronger isolation and explicit row locking when an application's invariants require them.

Redis can complement durable storage with fast active-party data structures, temporary TTL-managed state, and asynchronous Streams processing. Redis Sets maintain unique members, Hashes provide object-like field storage, key expiration manages temporary lifecycle, and Streams support consumer groups with explicit acknowledgments and pending-work recovery.

When developers inspect Party or Team Multiplayer source Code on the forum, they should therefore look beyond functions such as:

createParty()
invitePlayer()
joinParty()
The important question is how those operations behave when several servers execute them at the same time or when one of the participating systems fails.

A multiplayer party implementation designed around authority, explicit state transitions, versioning, concurrency control, reconnect recovery, and reliable service boundaries can continue working as an MMORPG or Mobile Title grows from one server process into a distributed production Realtime Backend.

That is the difference between a party feature that works during a demo and a Party Service that remains correct under real multiplayer load.
