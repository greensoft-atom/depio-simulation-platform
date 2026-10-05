#46 – Match Server Quest System Architecture: Progress Tracking, Event Processing, Daily Quests and Reward Safety
administrator
administrator
Verified user account
20/08/2026 17:19
•
General Discussion
Match Server Quest System Architecture: Progress Tracking, Event Processing, Daily Quests and Reward Safety
Introduction
Quest systems are among the most common features in MDevelopment.

From the player's perspective, a quest may look extremely simple:

Defeat 20 Monsters
Progress: 13 / 20

Reward:
Gold × 10,000
Diamond × 50
Behind that UI, however, the Realtime Backend must solve several different problems.

How does the server know which match events should increase progress?

What happens when the same battle event is processed twice?

Can two Match Servers update the same quest simultaneously?

What happens when a daily reset occurs while the player is online?

Should a quest be recalculated from historical match data or updated incrementally?

Can a modified client submit 20 / 20 progress directly?

What happens if the reward transaction succeeds but the response to the client is lost?

These questions become increasingly important as a title introduces:

Main quests

Side quests

Daily quests

Weekly quests

Achievements

Battle pass missions

Guild quests

Seasonal objectives

Event missions

Account-wide progression

A production quest system therefore needs more than a table containing quest_id and progress.

It needs clear rules for authoritative event generation, progress persistence, concurrency, reset behavior, event processing, idempotency, and reward claiming.

For developers analyzing Multiplayer source Code on the forum, the quest system is also a valuable place to evaluate whether a project was built only for demonstration or designed for real Match Server workloads.

Separate Quest Definition From Player Quest State
A good starting point is separating static quest configuration from player-specific state.

Quest Definition
A quest definition might contain:

## quest_definition

quest_id
quest_type
objective_type
objective_target
required_amount
reward_definition
prerequisite_quest_id
reset_policy
start_at
end_at
Example:

quest_id: 4102

objective_type:
KILL_MONSTER

objective_target:
MONSTER_DRAGON

required_amount:
10

reward:
500 Gold
5 Upgrade Stones
This configuration describes what the quest means.

It does not describe one player's progress.

Player Quest State
Player-specific state could look like:

## player_quest

player_id
quest_id
progress
status
accepted_at
completed_at
claimed_at
quest_period_id
version
For example:

player_id = 10042
quest_id = 4102
progress = 7
status = ACTIVE
Separating these concepts allows thousands or millions of players to reference the same quest configuration without duplicating all static data.

It also makes quest configuration easier to change independently from player progress.

Define a Clear Quest State Machine
Using several unrelated Boolean fields can create invalid combinations.

For example:

accepted = true
completed = false
claimed = true
should normally be impossible.

A state machine is easier to reason about.

For example:

LOCKED
|
v
AVAILABLE
|
v
ACTIVE
|
v
COMPLETED
|
v
CLAIMED
Other titles may use:

EXPIRED
FAILED
CANCELLED
depending on their design.

The exact states are less important than making transitions explicit.

For example:

ACTIVE -> COMPLETED
should occur only after the authoritative Realtime Backend confirms that the objective has reached its required value.

Likewise:

COMPLETED -> CLAIMED
should occur only as part of a safe reward transaction.

Quest Progress Must Be Server-Authoritative
The client should not decide quest progress.

A dangerous request would be:

{
"questId": 4102,
"progress": 10,
"completed": true
}
A modified Client could simply submit arbitrary values.

A safer architecture is:

Client
|
| Play actions
v
Match Server
|
| Validates play
v
Match Event
|
v
Quest System
|
| Update authoritative progress
v
Database
The player may see:

Kill Dragon: 7 / 10
but the value should originate from trusted backend state.

This is particularly important for quests involving:

PvP victories

Boss kills

Purchases

Currency spending

Item acquisition

Character upgrades

Guild contributions

Competitive ranking

Rare rewards

A client can report input.

It should not define the economic consequence.

Event-Driven Quest Progress
A large quest system becomes difficult if every play subsystem knows about every quest.

Imagine this code inside combat:

if player.hasQuest(1001):
updateQuest1001()

if player.hasQuest(1002):
updateQuest1002()

if player.hasQuest(4102):
updateQuest4102()
As the title grows, combat becomes tightly coupled to quest definitions.

A cleaner architecture often uses match events.

For example:

Battle Server

Dragon defeated
|
v
Match Event

{
type: MONSTER_KILLED,
playerId: 10042,
monsterId: 9002,
battleId: 881291
}
|
v
Quest Processor
The quest processor then determines which active quests care about:

MONSTER_KILLED
and whether:

monsterId = 9002
matches their conditions.

This creates a useful separation:

Combat System
|
| produces play fact
v
Quest System
|
| interprets fact for quests
v
Quest Progress
The combat code does not need to know every quest ID.

Design Match Events Carefully
Quest events should represent validated facts rather than arbitrary client claims.

Useful event types might include:

MONSTER_KILLED
BATTLE_WON
ITEM_ACQUIRED
ITEM_CRAFTED
CHARACTER_UPGRADED
CURRENCY_SPENT
DUNGEON_COMPLETED
PVP_MATCH_COMPLETED
GUILD_DONATION_COMPLETED
A useful event may contain:

{
"eventId": "battle_881291_player_10042_kill_17",
"type": "MONSTER_KILLED",
"playerId": 10042,
"monsterId": 9002,
"mapId": 18,
"quantity": 1
}
The eventId becomes especially valuable when duplicate processing must be prevented.

Events should contain enough trusted context for the quest system to evaluate rules without blindly querying many other services for every update.

But they should not become enormous snapshots of the entire player account.

Duplicate Events Can Duplicate Progress
Suppose the quest is:

Kill 10 Dragons
and the player's progress is:

9 / 10
A Dragon kill produces event:

event_id = battle_881291_dragon_10
The quest processor handles it and increments progress:

10 / 10
But imagine the worker crashes before acknowledging the message.

The event is delivered again.

Without idempotency:

11 / 10
may be recorded, or the same completion workflow may execute twice.

This is why event-driven quest systems should assume that retry and redelivery are possible.

Redis Streams consumer groups, for example, explicitly maintain pending messages and require consumers to acknowledge successfully processed entries with XACK. Redis also supports reclaiming pending work when necessary. These capabilities are useful for reliable worker processing, but application-level business operations still need to tolerate retries.

A quest processor might therefore maintain:

## processed_quest_event

event_id
player_id
quest_id
processed_at
with an appropriate uniqueness rule.

Then:

event already processed?
|
┌───┴───┐
yes no
| |
ignore update quest
The exact implementation can vary, but duplicate match events must not silently become duplicate progression.

Concurrent Quest Updates
Suppose a player has:

progress = 8
Two valid monster kills are processed simultaneously.

Worker A reads:

8
Worker B also reads:

8
Worker A writes:

9
Worker B also writes:

9
The correct value should have been:

10
This is a lost-update problem.

Quest code should therefore avoid unsafe application-side patterns such as:

progress = SELECT progress

progress = progress + 1

UPDATE progress
without considering concurrent execution.

A database-side atomic mutation can be preferable for simple counters:

UPDATE player_quest
SET progress = progress + 1
WHERE player_id = ?
AND quest_id = ?
AND status = 'ACTIVE';
More complicated transitions may require transactions, row locks, optimistic versions, or stronger isolation depending on the business rules.

PostgreSQL documents that its default Read Committed isolation takes a new committed-data snapshot for each command, meaning two successive statements within the same transaction can observe changes committed by concurrent transactions between those statements. Higher isolation levels and explicit locking provide stronger coordination where required.

The correct solution depends on the operation.

The important point is that concurrent progress updates cannot be ignored.

Cap Quest Progress Deliberately
If a quest requires:

10 kills
the product may want:

progress = min(current + delta, 10)
rather than storing:

18 / 10
This depends on whether excess progress has meaning.

For a simple quest, capping progress usually makes state easier to interpret.

For cumulative achievement tracking, however, the underlying counter may intentionally continue growing:

total_monsters_killed = 124,283
while achievements query thresholds such as:

100
1,000
10,000
100,000
These are different models.

Do not automatically implement every quest as one mutable progress counter.

Derived Progress vs Incremental Progress
Some objectives can be derived from existing authoritative state.

For example:

Reach Character Level 50
You may not need to permanently increment:

quest_progress += 1
every time the character levels.

The quest system can evaluate:

character.level >= 50
Likewise:

Own 5 Heroes
Reach VIP Level 10
Upgrade Castle to Level 20
may already exist as durable player state.

Other quests are naturally incremental:

Kill 50 Zombies
Win 10 PvP Matches
Complete 20 Daily Missions
A mature quest engine should distinguish:

State-based objective
from:

Event-count objective
rather than forcing every quest into one model.

Quest Completion and Reward Claim Should Be Separate
Reaching the objective does not necessarily mean the reward has been granted.

A useful state distinction is:

ACTIVE
|
v
COMPLETED
|
v
CLAIMED
When progress reaches the target:

status = COMPLETED
The reward remains claimable.

This allows UI behavior such as:

Quest Complete!
[Claim Reward]
and enables the backend to track whether the reward has actually entered the inventory or economy.

Some titles automatically grant rewards on completion.

That is also valid, but completion and reward delivery still represent separate business concepts internally.

Make Reward Claims Transaction-Safe
Suppose a completed quest rewards:

Diamond ×100
Gold ×50,000
Legendary Chest ×1
Two Claim requests arrive simultaneously.

If both workers read:

claimed = false
and independently grant the attachments, the quest can become an item-duplication vulnerability.

A safer conceptual transaction is:

BEGIN

lock quest state

verify:
status = COMPLETED
claimed_at IS NULL

grant rewards

set:
status = CLAIMED
claimed_at = now

record reward transaction

COMMIT
The inventory and economy architecture from articles #44 and #45 applies directly here.

The quest system should not invent an independent unsafe reward mechanism.

Reward generation should use the same authoritative inventory and currency services used elsewhere in the Realtime Backend.

Idempotent Reward Claims
Network timeouts must also be considered.

For example:

Claim request
|
v
Database commits reward
|
X
Response lost
The Client retries.

The backend should return a consistent result rather than granting the reward again.

A stable business identifier could be:

quest_reward:
player_10042:
quest_4102:
period_20260820
A unique reward transaction prevents duplicate grants for the same logical completion.

This principle is especially important for:

Premium currency

Paid battle-pass missions

Seasonal rewards

Rare equipment

Limited event rewards

Daily Quest Reset Architecture
Daily quests create an additional dimension: time periods.

A naive system might run at midnight:

UPDATE every player's daily quest progress to zero
For millions of accounts, that can create an unnecessary write spike.

Another model uses a period identifier.

For example:

daily_period_id = 20260820
A player's quest state might be:

quest_id = 7001
period_id = 20260819
progress = 4
When the player logs in on the next reset period:

current_period = 20260820
the backend determines that yesterday's state is no longer the active daily quest state.

It can lazily create or initialize the new period.

Conceptually:

Player login
|
v
Read current daily period
|
v
Stored period matches?
/ \
 yes no
| |
use state initialize new daily state
This avoids rewriting every inactive account at one exact reset moment.

Reset Rules Need an Explicit Timezone
"Reset every day" is incomplete.

The Studio must define:

00:00 UTC?

05:00 UTC?

00:00 server-local time?

00:00 per region?
If a global title has separate regional servers, the reset policy may intentionally differ.

The Realtime Backend should use trusted server-side time and an explicit reset policy.

The client's clock should not determine whether a new daily quest period has started.

This is directly related to the Match Server time synchronization architecture discussed in article #41.

Weekly and Seasonal Quest Periods
The same model can extend to:

daily_period_id
weekly_period_id
season_id
battle_pass_season_id
For example:

quest_id = 8201
season_id = season_14
progress = 87
required = 100
When Season 15 begins, the backend does not need to reinterpret Season 14's record as current state.

Historical records may be retained for:

Support investigations

Analytics

Reward reconciliation

Progress history

Anti-cheat analysis

This is usually safer than destructively resetting the only existing row without preserving context.

Redis Streams for Quest Event Processing
For a title with multiple Match Servers, quest updates may be processed asynchronously.

For example:

Battle Servers
|
v
Redis Stream
|
v
Quest Consumer Group
/ | \
 Q1 Q2 Q3
Redis Streams are append-only stream structures that support consumer groups, explicit acknowledgments, pending-entry tracking, and multiple consumers processing subsets of the workload.

This can be useful when quest progression does not need to block the combat response.

However, one important ordering issue remains.

Redis documents that with multiple consumers processing one stream, messages relating to the same logical entity can finish out of order because different consumers may process at different speeds.

Therefore, if quest correctness depends on strict per-player ordering, the architecture must deliberately preserve that requirement rather than assuming a consumer group automatically does so.

Possible strategies include:

partition by player
use sequence numbers
make updates commutative where possible
use version checks
route one player's critical events consistently
The correct choice depends on scale and quest semantics.

Redis Transactions Are Useful but Not a SQL Replacement
Some quest counters may live temporarily in Redis.

Redis supports MULTI, EXEC, and WATCH. Commands inside a Redis transaction are serialized for execution, while WATCH provides optimistic check-and-set behavior by causing EXEC to abort if watched keys changed before execution.

That can support patterns such as:

read quest state
WATCH quest key
calculate update
MULTI
write progress
EXEC
with retries after conflicts.

But Redis documentation also makes an important distinction from relational transactions: Redis transactions do not provide rollback in the same manner as a traditional relational database transaction.

More importantly:

Redis transaction
does not automatically make:

Redis + PostgreSQL + Inventory Service
one atomic transaction.

Distributed quest architectures still need explicit failure and reconciliation strategies.

Quest Chains and Prerequisites
Quest systems frequently contain dependencies:

Quest A
|
v
Quest B
|
v
Quest C
A quest definition may include:

requires_quest_id = 1001
or more complex conditions:

Level >= 20
AND
Quest 1001 completed
AND
Region unlocked
Eligibility should be evaluated from authoritative state.

The backend must also decide when a new quest becomes available:

immediately after prerequisite completion
on next login
after a story trigger
after server reset
These rules should be configuration-driven where possible rather than hardcoded across multiple play services.

Quest Configuration Versioning
Live titles change.

A designer may change:

Kill 20 monsters
to:

Kill 10 monsters
while thousands of players already have progress.

What happens to a player at:

15 / 20
?

The backend needs a migration policy.

Possibilities include:

Existing quest keeps old definition

Existing progress uses new target

Quest version changes and player receives replacement

Only newly accepted quests use new configuration
The correct answer is a product decision.

The architecture should support the decision explicitly instead of accidentally changing live player state whenever a configuration file is replaced.

Useful fields might include:

quest_definition_version
accepted_definition_version
for quests where historical rules matter.

Monitoring Quest Processing
Quest systems need observability because progression bugs are often reported as:

"My quest stopped counting."
Useful metrics include:

quest_events_received
quest_events_processed
quest_event_duplicates
quest_processing_latency
quest_update_failures
quest_reward_claims
quest_reward_failures
stream_pending_count
consumer_lag
daily_reset_initializations
For Redis Streams, pending entries and consumer-group information can be inspected through mechanisms such as XPENDING and XINFO, which Redis documents as observability tools for stream processing.

Match-level monitoring is also valuable.

For example:

completion_rate_by_quest
average_completion_time
abnormally_high_progress_rate
reward_claim_failure_rate
A quest processor can be technically healthy while a configuration error makes a quest impossible to complete.

How to Analyze This in Multiplayer source Code
When reviewing Multiplayer source Code from the forum or another project, search for:

quest
mission
objective
achievement
progress
quest_event
daily_quest
weekly_quest
claim_reward
reset
season_id
Then trace the complete flow.

1. Find Who Generates Progress
   Determine whether progress originates from:

Client
Match Server
Battle Server
Quest Service
Client-controlled progress deserves immediate scrutiny.

2. Find the Quest State Model
   Look for:

ACTIVE
COMPLETED
CLAIMED
EXPIRED
or equivalent states.

Check whether invalid combinations are possible.

3. Inspect Progress Mutation
   Find code such as:

progress = progress + amount
and determine whether simultaneous updates can overwrite each other.

4. Inspect Duplicate Event Handling
   Search for:

event_id
battle_id
request_id
processed_event
idempotency
If asynchronous events are used, determine how replay is handled.

5. Inspect Reward Claims
   Trace:

quest completed
|
v
reward granted
|
v
quest marked claimed
and determine whether partial failure can duplicate or lose rewards.

6. Inspect Reset Logic
   Check whether daily resets depend on:

client clock
server clock
period ID
scheduled batch job
lazy initialization 7. Check Quest Configuration Versioning
Determine what happens to active players when LiveOps changes a quest requirement.

Common Mistakes
Trusting Client Progress
The client should never be authoritative for valuable quest completion.

Updating Progress With Unsafe Read-Modify-Write Logic
Concurrent events can overwrite each other's changes.

Assuming Queue Processing Happens Exactly Once
Retry and redelivery must be considered explicitly.

Granting Reward Without Idempotency
A timeout can cause a successful claim to be repeated.

Resetting Millions of Accounts Unnecessarily
Lazy period-based initialization may be a better fit for many daily quest systems.

Mixing Quest Configuration With Player State
Static definitions and mutable player progress serve different purposes.

Ignoring Configuration Changes
Changing quest targets during a live event can alter existing player progression unexpectedly.

Letting Quest Code Leak Into Every Match System
Event-driven boundaries or dedicated quest APIs usually scale better organizationally than hardcoding quest IDs throughout combat, inventory, guild, and matchmaking code.

Best Practices
A production quest architecture should follow several principles.

Keep progression server-authoritative.

Match events must come from trusted Match Server logic.

Separate quest definitions from player state.

Configuration and progression have different lifecycles.

Use explicit state transitions.

ACTIVE, COMPLETED, and CLAIMED should have clear meanings.

Make progress updates concurrency-safe.

Database transactions, atomic mutations, locks, optimistic versions, or appropriate isolation should protect shared state. PostgreSQL provides multiple transaction isolation levels, with Serializable offering the strongest isolation but requiring applications to be prepared for serialization retries.

Design asynchronous processing for redelivery.

Consumer acknowledgment does not remove the need for idempotent business logic.

Use stable period identifiers for recurring quests.

Daily, weekly, and seasonal state becomes easier to reason about when each belongs to an explicit period.

Make reward claims idempotent and transactional.

Quest rewards should reuse the same safe inventory and economy infrastructure as other valuable title transactions.

Monitor progression as both infrastructure and play.

Queue health and quest completion rates reveal different classes of problems.

Conclusion
Quest systems connect many parts of a Realtime Backend.

Combat generates events.

Inventory and progression systems provide state.

The quest engine evaluates objectives.

The database stores progress.

Redis or another event infrastructure may distribute match events.

The reward system transfers valuable assets.

Daily and seasonal scheduling determine when progress belongs to a new period.

Because these systems interact, a quest implementation that works perfectly in a local test can still fail under production concurrency.

The most important architectural rule is to keep the Match Server authoritative.

The client should not decide whether a monster kill counts, whether a mission has completed, or whether a reward can be granted.

Event-driven designs can reduce coupling between match systems and quest definitions, but event processing must account for retries, duplicates, pending work, and ordering. Redis Streams provides consumer groups, explicit acknowledgment, pending-entry tracking, and scalable consumption, but these mechanisms do not eliminate the need for idempotent quest updates.

Likewise, durable quest progress requires proper concurrency control. PostgreSQL's isolation model demonstrates why developers must understand what concurrent transactions can observe instead of assuming several reads and writes automatically behave as one serialized operation.

When analyzing a Multiplayer source Code package on the forum, developers should therefore ask:

Who owns quest progress?

Can the same match event be processed twice?

Can two Match Servers update one quest simultaneously?

How are daily resets represented?

Can reward claims be retried safely?

What happens when a quest definition changes?

Can pending events be recovered after a worker failure?

Can the studio investigate why a player's quest did not advance?
These questions reveal much more than whether the project has a quest UI.

They reveal whether its Multiplayer development architecture can maintain correct progression when real players, concurrent servers, asynchronous events, retries, LiveOps changes, and valuable rewards all interact at production scale.
