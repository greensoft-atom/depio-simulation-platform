#28 – Match Server State Synchronization: Snapshot Replication, Delta Compression, Prediction, Interpolation and Reconciliation
administrator
administrator
Verified user account
16/08/2026 07:12
•
General Discussion
Match Server State Synchronization: Snapshot Replication, Delta Compression, Prediction, Interpolation and Reconciliation
Introduction
Real-time multiplayer titles have a fundamental problem: every player sees the title through a network connection that introduces latency, packet loss, jitter, and bandwidth limits.

The Match Server may know the authoritative world state at this exact moment, but the player cannot receive that state instantly.

Consider a simple movement action:

Player presses Forward
|
v
Client sends input
|
Internet
|
v
Match Server processes input
|
v
Server sends updated state
|
Internet
|
v
Client receives result
If the round-trip latency is 120 milliseconds, waiting for this complete process before displaying movement would make controls feel noticeably delayed.

This is why modern multiplayer development uses a collection of synchronization techniques rather than simply sending the complete world state and waiting for server responses.

Common techniques include:

Authoritative server simulation
Snapshot replication
Delta compression
Interest management
Sequence numbers
Client-side prediction
Server reconciliation
Interpolation
Extrapolation
Entity prioritization
Lag compensation
These systems work together to create the illusion that multiple players are experiencing one continuous shared world, even though each client is actually rendering a slightly different view of server history.

The architecture can be simplified as:

Client Input
|
v
Match Server Simulation
|
v
Authoritative World State
|
v
Snapshot Replication
|
v
Clients
|
+--> Prediction
+--> Interpolation
+--> Reconciliation
State synchronization is therefore one of the most important systems inside a real-time Match Server.

When examining Multiplayer source Code, developers should look for snapshot structures, entity replication logic, timestamps, sequence numbers, prediction buffers, interpolation buffers, acknowledgement systems, and correction code.

For developers studying online projects through the forum, understanding these systems is especially valuable because synchronization architecture frequently explains why the client and server contain apparently duplicated movement, combat, and physics logic.

This article examines how professional Studios design real-time state synchronization, including authoritative simulation, snapshots, delta compression, prediction, interpolation, reconciliation, bandwidth optimization, and debugging.

The Authoritative Match Server Model
The foundation of most secure real-time multiplayer architectures is the authoritative server.

The client does not permanently decide the world state.

Instead, the client sends player intent.

For example:

Client:

MoveForward
Jump
UseSkill(17)
FireWeapon
The Match Server processes those actions according to authoritative match rules.

Match Server:

Validate input
Apply movement
Check cooldown
Check collision
Simulate physics
Update world state
The resulting state becomes authoritative.

Conceptually:

Client Input
|
v
Match Server
|
v
Authoritative State
|
v
Replicate to Clients
This architecture protects important play decisions from client manipulation.

A compromised client may claim:

My position is X = 9000
but the Match Server can reject the state if the movement is impossible.

The same principle applies to:

Health
Damage
Position
Skills
Cooldowns
Projectiles
Inventory
Match score
State synchronization is therefore closely connected to anti-cheat architecture.

What Is a Simulation state Snapshot?
A snapshot is a representation of the world at a particular point in simulation time.

Imagine the server runs at:

30 simulation ticks per second
At tick 15,000, the world may contain:

Player A:
Position = (10.5, 4.2, 8.0)
HP = 820

Player B:
Position = (18.1, 3.9, 7.3)
HP = 650

Monster 14:
Position = (15.0, 5.1, 9.2)
HP = 2300
A snapshot may encode some or all of this information.

Conceptually:

Snapshot #15000
|
+--> Player A
+--> Player B
+--> Monster 14
+--> Projectile 231
+--> Objective State
The server then sends relevant snapshot information to connected clients.

The client uses those snapshots to construct its visible version of the world.

Simulation Tick vs Snapshot Rate
A Match Server does not necessarily send one snapshot for every simulation tick.

For example:

Simulation:
60 ticks per second

Snapshot replication:
20 snapshots per second
The server internally calculates play every:

~16.7 ms
but transmits state approximately every:

50 ms
This significantly reduces network bandwidth.

The client fills the visual gap using interpolation and prediction.

This separation is important because higher simulation frequency does not automatically require higher network frequency.

A Studio must balance:

Simulation accuracy
CPU cost
Bandwidth
Latency
Visual smoothness
Full Snapshot Replication
The simplest synchronization model is sending the complete relevant state every update.

Example:

Snapshot:

Player 1001
x = 20.5
y = 8.2
rotation = 92
hp = 900
mp = 330
animation = RUN
weapon = 18
Every snapshot contains every field.

This architecture is easy to understand and robust against missing previous packets.

If snapshot #100 disappears:

Snapshot #99
Snapshot #100 LOST
Snapshot #101
snapshot #101 still contains a complete current representation.

However, full snapshots can consume substantial bandwidth.

If hundreds of entities are updated many times per second, repeatedly transmitting unchanged fields becomes inefficient.

This leads to delta compression.

Delta Compression
Delta compression sends only what changed relative to a known previous state.

Suppose the previous snapshot contains:

Player 1001

x = 20.5
y = 8.2
rotation = 92
hp = 900
weapon = 18
The player moves but everything else stays unchanged.

Instead of resending the complete object:

Player 1001

x = 20.8
y = 8.4
rotation = 94
hp = 900
weapon = 18
the server may send only:

Player 1001

x = 20.8
y = 8.4
rotation = 94
The client reconstructs the new state from:

Previous Snapshot +
Delta
=
New Snapshot
This can reduce bandwidth significantly.

Baseline Snapshots
Delta compression requires both sides to agree on the reference state.

Suppose the server generates:

Snapshot 100
Snapshot 101
Snapshot 102
Snapshot 102 may be encoded relative to Snapshot 100.

The server must know whether the client actually received the baseline.

A common workflow is:

Server -> Snapshot 100
Client -> ACK 100

Server knows:
Client has Snapshot 100

Server generates delta:
Snapshot 105 relative to Snapshot 100
If the client does not have the expected baseline, the delta cannot be reconstructed correctly.

The server may then send a new full snapshot.

Snapshot Acknowledgements
Clients can acknowledge the latest valid snapshot they processed.

For example:

Server:
Snapshot 5001

Client:
ACK 5001
Later:

Server:
Delta Snapshot 5005
Baseline = 5001
This allows the Match Server to choose a safe reference.

Acknowledgement architecture becomes especially important over unreliable transport.

The server should not assume that simply sending a packet means the client received it.

Entity State Masks
Another optimization is using bit masks to indicate which fields changed.

Imagine an entity contains:

Position
Rotation
Velocity
HP
Animation
Weapon
Assign one bit to each field:

Bit 0 = Position
Bit 1 = Rotation
Bit 2 = Velocity
Bit 3 = HP
Bit 4 = Animation
Bit 5 = Weapon
If only position and rotation changed:

Change Mask:

000011
The packet then includes only those fields.

This avoids sending field names and unnecessary values.

Binary replication systems often use variations of this approach.

Quantization
Network values do not always require full floating-point precision.

Suppose world rotation ranges from:

0° to 360°
A 32-bit floating-point value may be unnecessary.

If 256 discrete values provide enough precision, rotation can be encoded using one byte.

Similarly, world positions may be quantized.

Instead of transmitting:

x = 125.38293457
the title may transmit:

x = 125.38
or a fixed-point representation.

Quantization reduces packet size.

However, excessive quantization can produce visible jitter or inaccurate simulation.

The correct precision depends on play.

Interest Management
Snapshot compression is useless if the server sends players thousands of irrelevant entities.

Consider a large MMORPG world containing:

10,000 online entities
Player A might only need information about:

30 nearby players
40 nearby NPCs
10 projectiles
15 world objects
The server should determine an Area of Interest.

Conceptually:

Full World
+----------------------------------+
| |
| Player A |
| +-------------+ |
| | Relevant | |
| | Entities | |
| +-------------+ |
| |
+----------------------------------+
Only relevant entities are replicated at high frequency.

Interest management can use:

Grid partitioning
Spatial hashing
Quadtree
Octree
Zones
Rooms
Visibility systems
Distance thresholds
This is often more important than low-level packet compression.

Sending fewer entities produces larger savings than shaving a few bytes from every entity.

Replication Priority
Not every visible entity needs the same update frequency.

Suppose the player sees:

Enemy 3 meters away
NPC 15 meters away
Decorative creature 80 meters away
The nearby enemy may require frequent updates.

The distant creature may only require occasional updates.

A priority system might calculate:

priority =
distance factor

- play importance
- visibility
- time since last update
  Then networking bandwidth is allocated to the highest-priority entities.

Example:

Nearby enemy:
20 updates/sec

Party member:
15 updates/sec

Distant NPC:
3 updates/sec
This allows large worlds to scale better.

Client-Side Prediction
Even optimized snapshots still arrive after network delay.

If a local player waits for authoritative movement updates, controls can feel unresponsive.

Client-side prediction solves this.

Suppose the player presses forward.

The client immediately simulates:

Input #401
MoveForward
and displays movement.

At the same time:

Client -> Match Server

Input #401
The server independently processes the same input.

Conceptually:

                Client
                  |

Input #401 -------+-----> Predict locally
|
+-----> Send to Server
|
v
Authoritative Simulation
The local player moves immediately while server authority is preserved.

Input Sequence Numbers
Prediction requires the client and Match Server to identify inputs.

Example:

Input #501 = Forward
Input #502 = Forward
Input #503 = Jump
Input #504 = Forward
The client stores these inputs temporarily.

The server processes them and returns:

LastProcessedInput = 502
Now the client knows:

501 processed
502 processed
503 pending
504 pending
This information is essential for reconciliation.

Server Reconciliation
Eventually the client receives the authoritative position.

Client prediction may say:

Position = 100.5
Server says:

Position = 99.8
LastProcessedInput = 502
The client should not simply trust its prediction forever.

Instead:

1. Set state to authoritative server state
2. Remove acknowledged inputs
3. Replay remaining local inputs
   Example:

Server State:
Position 99.8

Pending:
Input 503
Input 504

Replay:
99.8

- Input 503
- Input 504
  =
  New predicted position
  This is server reconciliation.

It keeps the local player responsive while gradually correcting differences.

Why Prediction Diverges
Client and server simulations can disagree because of:

Floating-point differences
Collision timing
Server-only events
Packet loss
Different frame rates
Physics differences
Other players
NPC interaction
Authoritative corrections
For example, the client predicts an empty path.

Meanwhile the server knows another player blocked that path.

The local prediction therefore becomes invalid.

Some error is normal.

The challenge is correcting it without producing visually distracting movement.

Correction Strategies
If prediction error is very small:

0.03 meters
the client may gradually smooth the correction.

If error is large:

10 meters
the client may need to snap immediately.

A simple policy might be:

Error < 0.1m
Ignore

Error 0.1–1m
Smooth correction

Error > 1m
Strong correction / snap
The actual values depend completely on the title.

Competitive titles generally require stricter authority than casual social experiences.

Interpolation for Remote Players
Client-side prediction is primarily useful for the local player's own actions.

Remote entities are usually handled differently.

Suppose snapshots arrive:

Snapshot A
Time = 10.00
Position = 20

Snapshot B
Time = 10.05
Position = 21

Snapshot C
Time = 10.10
Position = 22
If the client renders each position immediately on arrival, network jitter can create visible movement irregularity.

Instead, the client maintains an interpolation buffer.

The Interpolation Buffer
The client intentionally renders slightly behind the newest server state.

For example:

Latest server state:
10.10

Render time:
10.00
The client now has both:

Snapshot A
Snapshot B
and can interpolate safely.

Conceptually:

A ---------------- B
^
|
Render Position
The position between A and B is calculated smoothly.

This introduces a small delay but produces more stable remote movement.

Interpolation Delay
Suppose snapshots are sent every:

50 ms
and typical network jitter is moderate.

The client might render remote objects:

100 ms behind
This creates enough historical data to interpolate across temporary packet timing variations.

Larger buffers improve smoothness under jitter but increase visual delay.

Smaller buffers reduce delay but may require extrapolation more often.

The Studio must balance:

Responsiveness
Smoothness
Network quality
Competitive fairness
Interpolation Methods
The simplest interpolation is linear interpolation.

For positions:

Position =
A + (B - A) \* t
where:

t = 0 -> State A
t = 1 -> State B
Rotation may require specialized interpolation methods.

More advanced movement systems may incorporate:

Velocity
Acceleration
Animation state
Movement mode
Spline data
The appropriate method depends on the movement model.

Extrapolation
Sometimes the client does not have a future snapshot.

For example:

Latest snapshot:
Position = 20
Velocity = 5 m/s
The next snapshot is delayed.

The client may predict:

Position after 100 ms
≈ 20.5
This is extrapolation.

It can hide short packet delays.

But prediction becomes increasingly unreliable over time.

If the remote player suddenly:

Stops
Turns
Jumps
Uses dash skill
Collides
the extrapolation may be wrong.

For this reason, extrapolation usually has a maximum duration.

Snapshot Buffer Management
A robust client stores several snapshots.

Example:

Snapshot Buffer

#5001
#5002
#5003
#5004
#5005
Each may contain:

Server tick
Timestamp
Entity states
Sequence number
The rendering system determines which two snapshots surround the desired render time.

Old snapshots are discarded once no longer needed.

A broken snapshot buffer can cause:

Teleporting
Jitter
Entities moving backward
Animation desynchronization
Incorrect projectile positions
This is why snapshot handling is usually separate from rendering logic.

Time Synchronization
Client and server clocks are not inherently identical.

If synchronization logic depends directly on local system time:

Client:
12:00:01.100

Server:
12:00:01.420
comparisons become unreliable.

Many multiplayer systems estimate:

ServerTimeOffset
using network messages.

Conceptually:

EstimatedServerTime =
ClientTime + Offset
This allows the client to interpret snapshot timestamps consistently.

However, network delay is asymmetric and variable, so synchronization is always an estimate.

Match Server Tick Numbers
Instead of depending entirely on wall-clock timestamps, many systems use server ticks.

Example:

Server Tick 85000
Server Tick 85001
Server Tick 85002
Packets can include:

tick = 85002
The client then knows the logical simulation ordering.

Tick numbers are especially helpful for:

Replay
Prediction
Reconciliation
Snapshot ordering
Lag compensation
Debugging
Handling Packet Loss
Suppose snapshots arrive:

100
101
102
104
105
Snapshot 103 is missing.

If snapshots are complete:

Ignore the missing snapshot
Continue with 104
If snapshot 104 depends on 103 as a delta baseline:

Client cannot decode 104 correctly
The protocol must account for this.

Possible strategies include:

Use acknowledged baselines
Periodic full snapshots
Reliable baseline updates
Fallback refresh
State replication design must reflect the characteristics of the transport layer.

Periodic Full Snapshots
Even a delta-compressed system may periodically send a full state refresh.

For example:

Snapshot 1000 = Full
Snapshot 1001 = Delta
Snapshot 1002 = Delta
...
Snapshot 1100 = Full
This provides recovery if replication state becomes corrupted.

However, full snapshots may be expensive.

Another strategy is sending full state only when:

Client reconnects
Baseline missing
Entity newly enters interest range
Replication error detected
Entity Spawn and Despawn
State synchronization is not only about updating existing entities.

The client must also know when entities appear and disappear.

Example:

ENTITY_SPAWN
entity_id = 8102
type = OrcWarrior
position = ...
Later:

ENTITY_DESPAWN
entity_id = 8102
A replication system therefore handles:

Creation
Updates
Removal
If a despawn packet is lost in an unreliable protocol, the client may display a ghost entity.

The architecture must determine which lifecycle messages require reliability.

Snapshot Replication for MMORPGs
MMORPG synchronization is often different from small-match PvP.

An MMORPG may contain:

Hundreds of nearby players
NPCs
Monsters
Pets
Mounts
Projectiles
World objects
The server cannot broadcast everything at maximum frequency.

A practical MMO architecture may combine:

Spatial zones
Interest management
Update priority
Delta compression
Lower frequency for distant entities
Reliable state for important events
For example:

Nearby PvP opponent:
High update priority

Party member:
Medium-high

NPC across town:
Not replicated

Guild member on another map:
Presence only
This prioritization is essential for scalable Realtime Backend infrastructure.

Snapshot Replication for Mobile Titles
Mobile networking creates additional constraints:

Variable signal strength
Network switching
Higher packet loss
Limited bandwidth
Battery usage
Background suspension
A Mobile Title synchronization system should avoid excessive network traffic.

Strategies may include:

Lower snapshot rate
Aggressive interest management
Compact binary data
Reduced precision
Batching
Reconnect recovery
A title that performs well over office Wi-Fi may behave very differently on mobile networks.

Testing real network conditions is essential.

Bandwidth Budgeting
Studios should estimate a network budget.

Suppose each player receives:

15 KB/sec
At:

100,000 concurrent players
the outgoing traffic becomes:

1.5 GB/sec
before considering additional protocol overhead.

Reducing traffic to:

10 KB/sec
can produce substantial infrastructure savings at scale.

Bandwidth optimization should therefore be measurable.

Useful metrics include:

Bytes sent per player
Bytes received per player
Snapshots per second
Average snapshot size
Delta compression ratio
Entities replicated per client
Reliable retransmissions
CPU Cost of Replication
Bandwidth optimization can increase CPU cost.

For every client, the Match Server may need to:

Determine interest set
Compare entity state
Generate delta
Serialize packet
Compress data
Encrypt traffic
For:

100 players
in one instance, this may happen thousands of times per second.

Therefore replication performance matters.

Studios should profile:

Snapshot generation time
Serialization time
Interest calculations
Delta comparisons
Compression cost
Packet construction
A very compact protocol is not useful if snapshot generation consumes all server CPU.

Multi-Threaded Replication
Large Match Servers may separate simulation from networking work.

For example:

Simulation Thread
|
v
Authoritative Snapshot
|
v
Replication Workers
|
+--> Client A packet
+--> Client B packet
+--> Client C packet
This can reduce load on the core play simulation.

However, multi-threaded architecture introduces synchronization complexity.

Replication code must not read partially modified simulation state.

Common strategies include:

Immutable snapshots
Double buffering
Read-only replication state
Task queues
Debugging Desynchronization
Desynchronization bugs can be extremely difficult to reproduce.

Useful debugging information includes:

Player ID
Server tick
Client tick
Input sequence
Snapshot sequence
Authoritative position
Predicted position
Correction magnitude
Ping
Packet loss
Example log:

Player: 1001
ServerTick: 92881
Snapshot: 5502
LastInput: 7701
ServerPos: (81.2, 11.7)
ClientPos: (82.9, 11.5)
Error: 1.71m
Ping: 126ms
Without this context, reports such as:

"My character teleported."
are difficult to diagnose.

Monitoring Synchronization Quality
Infrastructure monitoring should include play synchronization metrics.

Useful metrics include:

Average correction distance
Large corrections per minute
Snapshot loss
Snapshot decode failures
Average snapshot size
Interpolation buffer underruns
Extrapolation frequency
Input acknowledgement delay
Entities replicated per player
For example:

Interpolation Buffer Underrun
means the client reached its render time without receiving a suitable next snapshot.

Frequent underruns may indicate:

High jitter
Insufficient buffer delay
Server update instability
Packet loss
This provides more useful information than raw ping alone.

How to Analyze This in Multiplayer source Code
When analyzing Multiplayer source Code from the forum, state synchronization code may be distributed across both client and server projects.

1. Find Snapshot Structures
   Search for:

snapshot
state
worldstate
frame
sync
replication
Look for fields such as:

tick
sequence
timestamp
entity_id 2. Find Entity Replication
Search for:

replicate
broadcast
syncEntity
sendState
serializeState
Determine how the Match Server chooses which entities each player receives.

3. Find Delta Logic
   Search for:

delta
dirty
changed
mask
baseline
A dirty flag often means:

This property changed and should be replicated. 4. Find Snapshot ACKs
Search for:

ack
snapshotAck
lastSnapshot
baselineId
This can reveal how delta compression handles packet loss.

5. Find Prediction Buffers
   On the client side, search for:

prediction
inputBuffer
pendingInputs
inputSequence
Determine whether client movement is simulated before server confirmation.

6. Find Reconciliation
   Search for:

reconcile
correction
serverPosition
authoritativeState
Trace how server corrections are applied.

7. Find Interpolation Buffers
   Search for:

interpolate
snapshotBuffer
renderTime
lerp
This typically controls remote entity smoothing.

8. Find Interest Management
   Search for:

AOI
interest
visible
nearby
grid
zone
spatial
This is particularly important in MMORPG Multiplayer source Code.

9. Trace One Entity End to End
   Follow:

Server Simulation
|
Entity State
|
Replication
|
Serialization
|
Network Packet
|
Client Decode
|
Snapshot Buffer
|
Rendering
This workflow helps reveal the complete synchronization architecture.

Common Mistakes
Sending Every Entity to Every Player
Large worlds require interest management.

Broadcasting the entire world wastes bandwidth and CPU.

Predicting Authoritative Economy State
Client-side prediction is appropriate for visual responsiveness, not permanent currency or inventory ownership.

No Input Sequence Numbers
Without input identity, reconciliation becomes much harder.

Delta Compression Without Baseline Recovery
If the client misses the baseline, later snapshots may become unusable.

Rendering Remote Players Directly From Latest Packet
This creates visible jitter.

Use interpolation.

Excessive Extrapolation
Long extrapolation creates increasingly incorrect positions.

Limit it.

Correcting Every Tiny Error
Very small corrections can create unnecessary visual jitter.

Use tolerances and smoothing.

Ignoring Mobile Network Conditions
High jitter and temporary disconnects are normal on mobile networks.

Optimizing Packet Size Without Profiling
Complex compression may increase CPU cost more than it reduces network cost.

Measure both.

Best Practices
A practical state synchronization strategy should follow several principles.

Keep the Server Authoritative
Permanent play outcomes should originate from server simulation.

Predict Local Actions Carefully
Prediction improves responsiveness but must remain correctable.

Interpolate Remote Entities
Do not treat network packets as rendering frames.

Use Stable Sequence Numbers
Track inputs and snapshots explicitly.

Separate Simulation Rate From Network Rate
Do not send every simulation tick unless play truly requires it.

Prioritize Important Entities
Nearby combat entities deserve more bandwidth than distant background objects.

Design for Packet Loss
Replication should recover automatically from missing snapshots.

Monitor Correction Quality
Large reconciliation errors often reveal deeper play or networking problems.

Test Realistic Network Conditions
Test under:

Latency
Jitter
Loss
Bandwidth limits
Temporary disconnects
Mobile network switching
Profile Replication CPU
Synchronization can become one of the most expensive Match Server systems at scale.

Conclusion
Real-time multiplayer synchronization is fundamentally an exercise in managing incomplete and delayed information.

The Match Server owns the authoritative state, but clients cannot wait for every authoritative response before rendering play.

Professional synchronization architecture solves this tension through several complementary techniques:

Authoritative simulation
Snapshots
Delta compression
Interest management
Entity prioritization
Client prediction
Input sequencing
Server reconciliation
Interpolation
Extrapolation
Time synchronization
Snapshot replication gives clients a consistent view of server state.

Delta compression reduces unnecessary bandwidth.

Interest management prevents irrelevant entities from consuming network resources.

Client-side prediction makes local controls responsive.

Server reconciliation keeps prediction aligned with authority.

Interpolation makes remote players appear smooth despite network jitter.

Together, these systems allow players separated by hundreds or thousands of kilometers to experience what appears to be one shared real-time world.

For developers studying Multiplayer source Code, synchronization logic is often one of the best indicators of how sophisticated the Multiplayer architecture is.

A simple project may broadcast complete states directly.

A mature MMORPG or competitive Match Server may contain snapshot baselines, interest management, dirty-state tracking, input buffers, interpolation timelines, and correction thresholds.

For developers examining projects on the forum, understanding these components makes it much easier to modify movement systems, change network protocols, optimize bandwidth, or investigate synchronization bugs without accidentally breaking the relationship between client and server.

In professional Multiplayer development, synchronization should never be viewed as only a networking problem.

It is a combined problem involving simulation, networking, security, rendering, performance, and player experience.

The best architecture is not the one that transmits the most data.

It is the one that sends the right state, to the right player, at the right time, with enough information for the client to reconstruct a smooth and trustworthy version of the authoritative world.
