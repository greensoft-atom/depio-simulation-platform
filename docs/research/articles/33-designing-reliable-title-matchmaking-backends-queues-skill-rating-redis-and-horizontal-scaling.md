#33 – Designing Reliable Matchmaking Backends: Queues, Skill Rating, Redis, and Horizontal Scaling
administrator
administrator
Verified user account
18/08/2026 17:08
•
General Discussion
Designing Reliable Matchmaking Backends: Queues, Skill Rating, Redis, and Horizontal Scaling
Introduction
Matchmaking is one of the most visible Realtime Backend systems in any competitive or cooperative multiplayer title.

Players may not know how the matchmaking service is implemented, but they immediately feel the consequences when it performs poorly.

A weak matchmaking system can create:

Very long queue times

Unbalanced matches

High-latency opponents

Repeated matches against the same players

Incorrect team composition

Failed match creation

Players stuck in queue

Duplicate match assignments

Regional routing problems

Poor experience during peak traffic

For a Studio, matchmaking is not simply a function that searches for another player.

A production-grade matchmaking backend must coordinate player state, skill rating, region, latency, party information, queue rules, Match Server capacity, Redis or database state, failure recovery, and horizontal scaling.

The system must also continuously balance two conflicting goals:

Match Quality
VS
Queue Time
If the rules are too strict, players wait too long.

If the rules are too loose, matches become unfair or laggy.

This article explains how to design a reliable matchmaking architecture for online titles, including queue structures, player tickets, skill-based matching, region selection, party matching, Redis, Match Server allocation, scaling, monitoring, and failure recovery.

It is also useful for developers reviewing existing Multiplayer source Code and determining whether its multiplayer backend can scale beyond a small test environment.

What a Matchmaking Backend Actually Does
A matchmaking service typically receives requests from players who want to enter a match mode.

Example:

Player 1024
Mode: Ranked 5v5
Region: Asia
Rating: 1840
Party Size: 1
The matchmaking system must then find compatible players.

Compatibility may depend on:

Match mode
Skill rating
Region
Latency
Party size
Platform
Input method
Player level
Rank
Team role
Client version
Language
Queue duration
Once enough compatible players have been found, the backend must create a match and allocate a Match Server.

Conceptually:

Player
↓
Matchmaking API
↓
Queue
↓
Matchmaker Worker
↓
Match Created
↓
Match Server Allocation
↓
Players Receive Connection Info
Each stage can fail independently, so production systems need recovery mechanisms.

Core Matchmaking Architecture
A scalable matchmaking architecture may look like:

Clients
|
API Gateway
|
Matchmaking Service
|
+------------------------------+
| Queue / Redis |
| Matchmaker Workers |
| Rating Service |
| Party Service |
+------------------------------+
|
Match Server Allocator
|
Available Match Servers
|
Match Instance
This architecture separates three responsibilities.

Queue Management
Stores players waiting for matches.

Match Formation
Determines which players belong together.

Server Allocation
Finds or creates a Match Server capable of running the match.

Keeping these concepts separate makes Multiplayer development easier because matchmaking logic does not need to directly manage the entire server infrastructure.

Matchmaking Tickets
Players should normally be represented by matchmaking tickets rather than raw session connections.

A ticket may contain:

ticket_id
player_id
party_id
mode
rating
region
latency_map
rank
queue_time
client_version
platform
preferences
Example:

{
"ticket_id": "MM-882190",
"player_id": 1024,
"mode": "ranked_5v5",
"rating": 1840,
"region": "asia",
"party_size": 1,
"queue_time": 1723980000
}
The ticket becomes the authoritative representation of the player's queue request.

This helps prevent problems caused by repeated join requests.

For example, if the same player sends:

Join queue
Join queue
Join queue
the backend should not accidentally create three independent queue entries.

Matchmaking State Machine
One of the most useful architectural decisions is to model matchmaking as a state machine.

Example:

IDLE
↓
QUEUED
↓
MATCHING
↓
MATCH_FOUND
↓
SERVER_ALLOCATED
↓
CONNECTING
↓
IN_MATCH
Possible failure states include:

CANCELLED
FAILED
EXPIRED
Explicit states make it easier to handle reconnects and retries.

For example, if a client requests matchmaking while already in:

MATCH_FOUND
the backend should return the existing match rather than creating a new ticket.

Queue Architecture
The simplest queue is:

Player A
Player B
Player C
Player D
Player E
But real matchmaking queues are normally segmented.

For example:

ranked:asia:solo
ranked:asia:party
ranked:europe:solo
ranked:europe:party
casual:asia
casual:europe
This reduces the search space.

A player looking for an Asia ranked match should not need to scan millions of unrelated players.

The exact partitioning depends on the title.

Too few partitions create expensive searches.

Too many partitions fragment the player population and increase queue times.

Redis for Matchmaking Queues
Redis is frequently useful for matchmaking because queue operations often require:

Fast reads

Fast writes

Sorted ordering

Expiration

Counters

Temporary state

One useful structure is a sorted set.

Conceptually:

Key:
matchmaking:ranked:asia

Score:
queue timestamp

Member:
ticket ID
This allows the matchmaker to efficiently locate players who have waited the longest.

Another queue might organize tickets by rating.

Example:

Score = player rating
However, one structure rarely satisfies every matchmaking rule.

Production systems may combine:

Sorted sets
Hashes
Sets
Streams
Database records
In-memory indexes
depending on requirements.

Redis Is Not the Matchmaking Algorithm
Using Redis does not automatically create good matchmaking.

Redis solves state-storage and coordination problems.

The actual algorithm must still decide:

Who should play together?
This distinction matters when analyzing Multiplayer source Code.

Some projects claim to support advanced matchmaking because they use Redis, while the actual matching logic may still be simplistic.

The data structure and the matching policy are separate architectural concerns.

Skill-Based Matchmaking
Skill-based matchmaking attempts to create matches between players with similar expected performance.

A simplified system may assign each player a numerical rating.

For example:

Player A: 1810
Player B: 1780
Player C: 1855
Player D: 1825
The matchmaker defines an acceptable difference.

Initially:

±50 rating
If no suitable match appears after some time:

±100
Later:

±200
This technique is called expanding matchmaking criteria.

Expanding Search Windows
A common strategy is:

0-10 seconds:
rating difference <= 50

10-20 seconds:
rating difference <= 100

20-40 seconds:
rating difference <= 200

40+ seconds:
rating difference <= 350
The exact numbers depend entirely on player population and product design.

The important concept is that matchmaking becomes less strict as queue time increases.

This helps maintain a balance between:

Fairness
Latency
Queue Time
A large title can use tighter rules because it has more simultaneous players.

A smaller Match Server population may need wider matching tolerances.

Rating Systems
The backend can use several approaches for estimating player skill.

Examples include:

Elo-like rating

MMR systems

Rank divisions

Hidden skill score

Performance-based models

Team rating

The exact rating algorithm is a product-design decision.

The matchmaking architecture should not assume that visible rank is necessarily identical to internal skill.

For example:

Visible Rank:
Diamond III

Internal Matchmaking Rating:
1847
Separating presentation rank from matchmaking rating gives designers more flexibility.

Team-Based Matchmaking
Team titles introduce additional complexity.

Suppose the system needs to create:

Team A: 5 players
Team B: 5 players
Matching ten individual players is not enough.

The backend should attempt to balance total team strength.

Example:

Team A:
1820
1790
1850
1760
1810

Average = 1806
versus:

Team B:
1800
1830
1780
1815
1795

Average = 1804
This is significantly better than placing five high-rated players against five lower-rated players.

More advanced systems may also consider rating variance rather than only averages.

Party Matchmaking
Premade parties create another challenge.

Consider:

Party A:
3 players

Party B:
2 players

Solo Players:
5
The Realtime Backend needs to construct complete teams while keeping party structure reasonably fair.

A common fairness rule may avoid:

5-player premade
VS
5 solo players
unless queue time or population forces broader matching.

Party MMR also requires a policy.

Possible approaches include:

Average rating
Highest rating
Weighted average
Party adjustment factor
Each approach affects player experience differently.

The matchmaking service should treat these rules as configuration whenever possible instead of hard-coding them across multiple services.

Role-Based Matchmaking
Some titles require team roles.

Example:

Tank
Healer
Damage
Support
A valid match may require:

1 Tank
1 Healer
3 Damage
Now the matchmaker is solving more than skill compatibility.

It must simultaneously satisfy:

Team size
Role requirements
Skill balance
Party composition
Region
Latency
Queue time
Strict role requirements can dramatically increase queue times when one role has insufficient players.

Studios often need dynamic rules such as role incentives or wider role flexibility to maintain healthy queues.

Latency-Aware Matchmaking
Skill is not the only quality metric.

A perfectly balanced match is still bad if half the players have extremely high latency.

Clients can periodically measure latency to multiple regions.

Example:

Singapore: 32 ms
Tokyo: 82 ms
Frankfurt: 210 ms
Virginia: 245 ms
The ticket may include these measurements.

The matchmaker then tries to find a region acceptable to all players.

For example:

Selected Region:
Singapore

Player latencies:
32 ms
44 ms
55 ms
63 ms
70 ms
This is generally preferable to choosing a server region based only on one player's location.

Regional Matchmaking
Large global titles often separate matchmaking into regions.

Example:

Asia
Europe
North America
South America
Oceania
Regional partitioning improves latency but reduces the number of players available to each queue.

Some titles permit cross-region expansion after longer queue times.

For example:

0-30 seconds:
Asia only

30-60 seconds:
Asia + nearby region

60+ seconds:
broader region selection
Whether this is acceptable depends on the play.

Fast action titles usually have stricter latency requirements than slower strategy titles.

Match Creation Must Be Atomic
Suppose ten compatible players are selected.

The matchmaker must ensure that another worker does not simultaneously assign some of those same players to a different match.

This can happen when multiple matchmaking workers operate concurrently.

Conceptually:

Worker A selects:
Players 1-10

Worker B selects:
Players 6-15
Without proper coordination:

Players 6-10
could be assigned twice.

Possible solutions include:

Atomic ticket state transitions

Database transactions

Redis atomic operations

Distributed coordination

Partitioned queue ownership

The important invariant is:

One active ticket
→
One match assignment
Match Server Allocation
Finding players is only half of matchmaking.

The system still needs somewhere to run the match.

After creating a match:

Match ID: 882771
Mode: Ranked 5v5
Region: Asia
Players: 10
the Realtime Backend may request a server from a Match Server allocator.

Conceptually:

Matchmaker
↓
Server Allocator
↓
Find available instance
OR
Start new instance
↓
Reserve capacity
↓
Return address/token
The match should not be considered fully ready until server capacity is confirmed.

Dedicated Server Pools
A Studio may maintain pools of ready servers.

Example:

Asia:
Server 01: 6 available match slots
Server 02: 3 available match slots
Server 03: 8 available match slots
The allocator chooses one based on:

Region

Capacity

CPU load

Memory

Current match count

Match mode

Version

This can reduce match startup latency.

Another architecture creates dedicated server containers on demand.

That saves idle resources but increases startup complexity.

Kubernetes and Dynamic Match Server Scaling
Container orchestration can help large multiplayer infrastructure scale server capacity.

Conceptually:

Match Demand
↓
Match Server Allocator
↓
Container / Pod Creation
↓
Match Server Starts
↓
Health Check
↓
Match Assigned
The critical requirement is that matchmaking understands server readiness.

A server should not be given to players merely because the container creation request succeeded.

The backend should wait until the Match Server is actually healthy and ready to accept connections.

Handling Match Allocation Failure
Suppose the matchmaker selects ten players but no Match Server becomes available.

The system needs a recovery policy.

Possible actions include:

Retry allocation
Keep players reserved briefly
Return players to queue
Move players to another region
Cancel match
The worst outcome is leaving players permanently in:

MATCH_FOUND
without a usable server.

Every transition should therefore have timeouts.

Queue Cancellation
Players may cancel matchmaking.

Cancellation creates a race condition.

Imagine:

Player presses Cancel
at exactly the same moment that a matchmaker assigns them.

Two operations race:

Cancel Ticket
Assign Match
The backend needs an atomic state transition.

For example:

QUEUED → CANCELLED
or:

QUEUED → MATCH_FOUND
Only one should succeed.

The client then receives the actual final state.

Disconnections While Queued
Mobile players frequently lose connectivity temporarily.

The Realtime Backend must decide whether queue participation requires an active session.

Possible policy:

Connection lost
↓
Keep ticket for 10 seconds
↓
Player reconnects?
↓
YES → continue queue
NO → cancel ticket
A short grace period avoids punishing players for minor network interruptions.

However, long-lived orphaned tickets must eventually expire.

Matchmaking Ticket Expiration
Every queue entry should have a maximum lifetime.

For example:

ticket_expires_at
Expiration prevents abandoned tickets from remaining in Redis or databases forever.

Cleanup workers should remove stale tickets and repair inconsistent states.

This is especially important after Match Server crashes or deployment failures.

Horizontal Scaling
A production matchmaking system should support multiple workers.

Example:

Matchmaker Worker 01
Matchmaker Worker 02
Matchmaker Worker 03
Matchmaker Worker 04
The difficult question is:

Which worker owns which tickets?
Possible architectures include:

Queue Partitioning
Each worker owns specific partitions.

Example:

Worker 01:
Asia Ranked

Worker 02:
Europe Ranked

Worker 03:
Asia Casual
Hash Partitioning
Tickets are distributed based on mode, region, or another key.

Shared Queue with Atomic Claims
Workers inspect shared queue data and atomically claim selected tickets.

Each architecture has tradeoffs.

Partition ownership simplifies coordination but can create uneven load.

Shared queues improve flexibility but require stronger synchronization.

Avoid Process-Local Queue State
A matchmaking system may work during development using:

List<MatchmakingPlayer>
inside one process.

Then production adds:

Matchmaker01
Matchmaker02
Each server now has a different queue.

Players on separate instances cannot see one another.

When reviewing Multiplayer source Code, always determine whether queue state lives in:

Process memory
Redis
Database
Distributed queue
This detail often determines whether the Realtime Backend can actually scale horizontally.

Matchmaking Configuration
Rules should ideally be data-driven.

For example:

Mode: Ranked

Min Players: 10
Team Size: 5

Initial Rating Range: 50
Rating Expansion: 25 every 5 sec
Maximum Rating Range: 300

Preferred Ping: < 80 ms
Maximum Ping: 150 ms
This allows Product designers to tune matchmaking without recompiling the Match Server.

It also supports A/B testing and region-specific tuning.

Security Considerations
The client should never be able to submit authoritative values such as:

rating = 100
when its real server-side rating is:

rating = 2200
The backend should load authoritative data.

Similarly, the player should not be able to choose protected queue attributes such as:

rank
party size
ban status
competitive eligibility
unless these are validated server-side.

The matchmaking request should primarily describe intent.

Example:

Queue for Ranked 5v5
The Match Server builds the trusted ticket using server-owned account data.

Preventing Queue Abuse
Players or bots may abuse matchmaking APIs by repeatedly joining and leaving queues.

Useful protections include:

Join rate limits
Cancel cooldowns
Single active ticket
Authentication
Queue eligibility validation
Penalty tracking
Competitive titles may also track intentional match dodging or repeated failure to connect.

Such penalties should be enforced by the Realtime Backend rather than trusted to the client.

Monitoring Matchmaking
Matchmaking needs both infrastructure metrics and product-design metrics.

Important operational metrics include:

Queue size
Matchmaking requests/sec
Match creation rate
Worker CPU
Redis latency
Redis command failures
Match allocation failures
Ticket expiration count
Duplicate assignment attempts
Server allocation latency
Player-experience metrics include:

Median queue time
P95 queue time
Average skill difference
Average latency
Match cancellation rate
Failed connection rate
Rematch frequency
Party imbalance
These metrics should be segmented by:

Region
Match mode
Rank
Party size
Time of day
Platform
A global average may hide serious problems in smaller regions.

Queue Time Distribution Matters
Suppose the average queue time is:

18 seconds
That sounds healthy.

But the real distribution might be:

50%: 8 seconds
90%: 30 seconds
99%: 4 minutes
High-percentile metrics reveal players who experience unusually long queues.

Studios should monitor percentiles rather than relying only on averages.

Logging and Debugging
A matchmaking transaction should include traceable identifiers.

Useful log fields include:

ticket_id
player_id
party_id
match_id
region
match_mode
rating
queue_time
worker_id
server_id
match_result
A typical trace may show:

17:01:10 Ticket created
17:01:16 Candidate search started
17:01:17 Ticket reserved
17:01:17 Match created
17:01:18 Server allocated
17:01:18 Connection token issued
When a player reports being stuck in matchmaking, these logs make diagnosis much easier.

Failure Recovery
Distributed systems fail.

Redis can become unavailable.

A matchmaker can crash.

A Match Server may fail health checks.

A deployment may restart workers.

The matchmaking architecture needs repair mechanisms.

Examples include:

Stale ticket cleanup
Expired reservation recovery
Match allocation retry
Queue reconstruction
Server health monitoring
Idempotent match creation
A particularly important scenario is:

Worker reserves players
Worker crashes before creating match
Those players should eventually return to the queue automatically.

Reservation records therefore need expiration or recovery logic.

Database vs Redis for Matchmaking State
A database provides durability.

Redis provides speed.

Many architectures use both.

Example:

Database:
Player rating
Match history
Penalties
Persistent competitive state

Redis:
Active queue tickets
Temporary reservations
Server availability
Queue counters
This separation works well because active matchmaking state is highly dynamic, while historical data benefits from durable persistence.

The exact architecture depends on failure tolerance.

If losing Redis means losing all active queue entries, the Studio must decide whether players can simply requeue or whether active tickets need durable recovery.

Match History
Completed matches should usually be stored separately from temporary matchmaking tickets.

Example:

match_id
mode
region
server_id
team_a
team_b
created_at
started_at
finished_at
result
Match history supports:

Rating updates

Player profile history

Anti-abuse analysis

Customer support

Competitive integrity

Analytics

It can also help prevent immediate repeated matchmaking against exactly the same opponents where that matters.

How to Analyze This in Multiplayer source Code
When evaluating multiplayer source Code, locate the matchmaking-related modules.

Search for terms such as:

Matchmaking
MatchQueue
BattleQueue
RoomManager
Lobby
MatchService
MatchServerAllocator
MMR
Rank
Party
Then identify the complete workflow.

Ask:

Where is the queue stored?
If it exists only inside one Match Server process, horizontal scaling may be difficult.

Is there a ticket state machine?
Check whether the system distinguishes:

Queued
Reserved
Matched
Cancelled
Expired
Can duplicate queue entries exist?
The backend should normally enforce one active ticket per player or party.

How are concurrent matchmakers coordinated?
Multiple workers must not assign the same player twice.

How is a Match Server selected?
Determine whether the backend understands:

Region
Capacity
Health
Version
Load
What happens when allocation fails?
A production-ready system must recover players instead of leaving them stuck.

Are matchmaking rules configurable?
Hard-coded rating ranges and queue timers can become difficult to tune after launch.

Developers browsing Multiplayer source Code projects on the forum should examine these backend components carefully. A project can demonstrate successful multiplayer play with a few test clients while still having a matchmaking architecture that cannot support a large live-service population.

Common Mistakes
Keeping Queue State Only in Memory
This limits horizontal scaling and makes worker restarts dangerous.

Matching Only by Skill
Ignoring latency can produce technically fair but unplayable matches.

Matching Only by Queue Time
This may create extremely unbalanced titles.

No Atomic Ticket Claiming
Two workers may assign the same player to different matches.

No Ticket Expiration
Disconnected players remain permanently queued.

No Server Allocation Recovery
Players become stuck after the matchmaking algorithm succeeds but the Match Server fails.

Trusting Client-Supplied Rating
Competitive state should always be authoritative on the backend.

Over-Partitioning the Queue
Too many queue categories can fragment the player population and dramatically increase waiting time.

Hard-Coding Matchmaking Rules
Live titles require continuous tuning based on population and player behavior.

Best Practices
A reliable matchmaking Realtime Backend should follow several principles.

Represent queue requests as explicit tickets.

This simplifies state tracking, cancellation, retries, and debugging.

Use an explicit state machine.

Every ticket should have a clear lifecycle.

Keep queue operations atomic.

A player should never be assigned to multiple matches simultaneously.

Balance match quality and queue time.

Use expanding criteria where appropriate.

Include network quality.

Skill alone does not determine match quality.

Partition carefully.

Region and match mode segmentation can improve efficiency, but excessive partitioning reduces player availability.

Treat parties as first-class matchmaking entities.

Do not assume every queue entry represents one individual player.

Separate matchmaking from Match Server allocation.

The matchmaker chooses players; the allocator provides infrastructure.

Use timeouts everywhere.

Tickets, reservations, server allocation, and connection attempts should all have defined expiration behavior.

Monitor distributions rather than only averages.

P95 and P99 queue times often reveal issues that averages hide.

Design for worker failure.

Reserved tickets should eventually recover if a worker crashes.

Keep configuration externalized.

Rating windows, latency thresholds, queue expansion, and party rules should be tunable without large code changes.

Conclusion
Matchmaking is a coordination problem involving players, queues, ranking, latency, parties, Match Servers, and distributed backend state.

A reliable matchmaking system must do much more than find players with similar ratings.

It needs to answer several questions continuously:

Is this player already queued?

Which players are compatible?

How long have they waited?

Which region provides acceptable latency?

Are the teams balanced?

Has another worker already claimed this ticket?

Is a healthy Match Server available?

What happens if the server allocation fails?
Redis can provide fast temporary queue storage and atomic coordination.

Databases can maintain durable competitive data and match history.

Matchmaker workers can horizontally scale the selection process.

Match Server allocators can route matches to healthy infrastructure.

Monitoring provides the data required to improve both technical reliability and player experience.

For Studios building a new multiplayer backend, these systems should be designed together instead of treating matchmaking as a small lobby feature.

For developers analyzing existing Multiplayer source Code, matchmaking architecture is also a strong indicator of whether the project was designed only for local testing or for real production deployment.

A scalable Realtime Backend should be able to add matchmaking workers and Match Server capacity without corrupting queue state or assigning players twice.

When evaluating multiplayer projects on the forum, developers should therefore inspect queue ownership, Redis usage, ticket states, server allocation, failure recovery, skill logic, and latency handling rather than judging the system only by whether two clients can successfully enter the same match.

Good matchmaking is ultimately a combination of distributed systems engineering and product-design tuning.

When both parts are designed carefully, the result is shorter queue times, fairer titles, more reliable Match Server allocation, and a significantly better multiplayer experience.
