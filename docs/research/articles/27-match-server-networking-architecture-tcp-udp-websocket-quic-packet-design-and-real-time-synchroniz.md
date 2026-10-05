#27 – Match Server Networking Architecture: TCP, UDP, WebSocket, QUIC, Packet Design and Real-Time Synchronization
administrator
administrator
Verified user account
16/08/2026 07:09
•
General Discussion
Match Server Networking Architecture: TCP, UDP, WebSocket, QUIC, Packet Design and Real-Time Synchronization
Introduction
Networking is one of the most important architectural decisions in any online title.

A single-player title can perform nearly every action locally. A Multiplayer title must continuously exchange information between the client and the Match Server.

Depending on the genre, those messages may include:

Login requests
Character movement
Skill activation
Damage results
Chat messages
Inventory updates
Matchmaking state
Player position
Projectiles
NPC state
Battle events
Ping / latency information
The networking requirements of an MMORPG are also very different from those of a turn-based Mobile Title or a competitive real-time shooter.

A turn-based title may tolerate hundreds of milliseconds of delay for many actions.

A real-time PvP title may become noticeably worse when latency, jitter, or packet loss increases.

This means the networking architecture must match the play.

A simplified online title architecture may look like:

Client
|
| Internet
|
v
Gateway / Edge
|
+--> Authentication
|
+--> Realtime Backend
|
+--> Matchmaking
|
v
Match Server
Different parts of this architecture may even use different protocols.

For example:

Login API -> HTTPS
Chat -> WebSocket
Battle Server -> UDP
Internal Services -> TCP / HTTP / RPC
There is no single protocol that is automatically best for every Multiplayer development workload.

When examining Multiplayer source Code, developers should therefore understand not only packet formats but also why a particular networking model was chosen.

At the forum, inspecting socket code, packet handlers, protocol definitions, sequence numbers, serialization logic, heartbeat systems, and reconnect workflows can reveal how the original Studio designed its multiplayer infrastructure.

This article explains the practical networking concepts behind TCP, UDP, WebSocket, QUIC, packet design, latency handling, synchronization, authoritative servers, prediction, interpolation, and bandwidth optimization.

Networking Requirements Depend on the Genre
Before choosing a protocol, define what the title actually needs.

Consider several different match types.

Turn-Based Mobile Title
Typical traffic:

Start battle
Select skill
Choose target
Submit turn
Receive battle result
Latency sensitivity is relatively low.

Reliability is usually more important than sending updates every few milliseconds.

TCP-based protocols may be perfectly reasonable.

MMORPG
Typical traffic:

Player movement
Chat
Inventory
Guild actions
NPC state
Combat
Quest updates
Marketplace
An MMORPG may use different reliability requirements for different message types.

For example:

Inventory update -> must be reliable
Chat message -> reliable
Player movement -> may tolerate some loss
Currency update -> must be reliable
Real-Time PvP Title
Traffic may include:

Position
Rotation
Velocity
Input
Shots
Skills
Hit information
Projectiles
Match state
Latency becomes extremely important.

For some messages, receiving the newest state is more useful than waiting for an old packet to be retransmitted.

That requirement often influences whether UDP-style networking is appropriate.

TCP for Match Servers
TCP provides a reliable, ordered byte stream between two endpoints.

Conceptually:

Packet A
Packet B
Packet C
is delivered to the application in order.

If some underlying network data is lost, TCP handles retransmission.

For many Realtime Backend workloads, this is exactly what developers want.

Common uses include:

Login
Account management
Inventory
Chat
Guild operations
Store
Payments
Turn-based play
Administrative tools
The application does not need to manually implement basic reliable delivery.

A typical TCP architecture may look like:

Client
|
TCP Connection
|
v
Gateway
|
v
Match Server
Long-lived TCP connections are also common in older MMORPG Multiplayer source Code.

The Limitation of Ordered Delivery
TCP guarantees ordered delivery.

That sounds entirely positive, but real-time titles can encounter an important side effect.

Imagine the application sends:

Position Update #100
Position Update #101
Position Update #102
Position Update #103
If data associated with update #101 is lost, later data may effectively wait until the missing portion has been recovered before the application receives the ordered stream.

For an inventory transaction, waiting is correct.

For a rapidly changing player position, the old update may already be useless.

By the time #101 arrives:

#102
#103
may represent newer state.

This is one reason latency-sensitive real-time titles often use UDP-based networking for some match traffic.

TCP Is a Stream, Not a Message Protocol
One of the most common mistakes in custom TCP Match Server implementations is assuming that one send() corresponds to one recv().

It does not.

Suppose the sender writes:

Packet A = 100 bytes
Packet B = 200 bytes
The receiver might observe:

50 bytes
250 bytes
or:

300 bytes
or another segmentation.

Therefore a TCP title protocol needs message framing.

A common frame design is:

+----------------+
| Packet Length |
+----------------+
| Message Type |
+----------------+
| Payload |
+----------------+
For example:

Length 2 or 4 bytes
Opcode 2 bytes
Payload N bytes
The receiver:

1. Read header
2. Determine total packet length
3. Wait until enough bytes arrive
4. Parse one complete packet
5. Continue with remaining buffer
   This logic is essential when analyzing legacy Multiplayer source Code.

UDP for Real-Time Play
UDP works differently.

It sends independent datagrams without TCP's built-in guarantees of:

Reliable delivery
Ordering
Automatic retransmission
A datagram may:

Arrive
Arrive late
Arrive out of order
Be duplicated
Never arrive
That sounds dangerous, but it gives a real-time Match Server more control.

Suppose movement updates arrive:

#500
#501
#503
Update #502 was lost.

If #503 contains the latest position, the client may not need #502 anymore.

The application can simply continue.

This is useful for rapidly changing information such as:

Position
Rotation
Velocity
Aim direction
Some animation state
Temporary entity state
Reliable Messages Over UDP
Using UDP does not mean everything must be unreliable.

Many custom Match Server protocols implement different channels.

For example:

Channel 1:
Unreliable position updates

Channel 2:
Reliable skill events

Channel 3:
Reliable inventory-related battle results
Reliable messages may use:

Sequence numbers
Acknowledgements
Retransmission
Timeouts
The application decides which messages require reliability.

Conceptually:

Client -> SkillActivate #842
Server -> ACK #842
If the acknowledgement is not received:

Client retransmits
Meanwhile movement packets can continue independently.

This provides more control than treating every message identically.

However, implementing a reliable protocol correctly is difficult.

Studios should avoid casually building complex transport protocols unless they have a real reason.

Sequence Numbers
Sequence numbers are extremely useful in real-time networking.

Imagine position packets:

Packet 400
Packet 401
Packet 399
Packet 399 arrived late.

Without sequence information, the client might incorrectly apply the older state.

With sequence numbers:

latest_received = 401

incoming = 399

399 < 401
=> discard
Sequence numbers help detect:

Out-of-order packets
Duplicate packets
Missing packets
Old state
They are common in UDP-based Match Server networking.

WebSocket for Browser and Persistent Connections
WebSocket provides persistent bidirectional communication between client and server over a connection established using the web protocol ecosystem.

It is commonly useful for:

Browser titles
Chat
Realtime dashboards
Notifications
Lobby systems
Turn-based multiplayer
Web-based clients
A normal HTTP workflow looks like:

Client -> Request
Server -> Response
Connection may end
WebSocket allows:

Client <------------> Server
with both sides sending messages over the established connection.

For Multiplayer development teams building browser-based or cross-platform services, this can be much simpler than implementing a completely custom transport.

When WebSocket Is a Good Fit
WebSocket can be useful when:

Traffic is moderate
Reliable ordered communication is acceptable
Browser compatibility matters
Infrastructure already understands HTTP-style routing
Examples:

Card titles
Strategy titles
Social titles
Chat systems
Lobby updates
Spectator dashboards
For extremely latency-sensitive real-time networking, studios may evaluate other transport options depending on the platform and requirements.

The important point is that WebSocket should be selected based on play characteristics rather than simply because it is easy to integrate.

QUIC and Modern Title Networking
QUIC is a modern transport protocol built over UDP with features such as encrypted connections and multiplexed streams.

One important architectural idea is that independent streams can avoid some of the cross-stream blocking characteristics that applications may encounter when multiplexing unrelated logical traffic over one ordered TCP stream.

A title architecture might conceptually separate:

Stream 1 -> Account messages
Stream 2 -> Chat
Stream 3 -> Match events
while using additional mechanisms for latency-sensitive data where supported by the protocol stack.

QUIC-based technologies are increasingly relevant to modern network applications, but adopting them for a title should still depend on:

Client platform support
Server libraries
Operational tooling
Latency requirements
Networking expertise
Infrastructure support
A Studio should not rewrite a stable TCP architecture solely because a newer transport exists.

Networking changes can affect nearly every online subsystem.

Designing a Title Packet
A well-designed packet format makes networking easier to maintain and debug.

A binary Match Server packet might contain:

+--------------------+
| Length |
+--------------------+
| Protocol Version |
+--------------------+
| Message Type |
+--------------------+
| Sequence Number |
+--------------------+
| Flags |
+--------------------+
| Payload |
+--------------------+
Possible fields include:

length
opcode
version
sequence
timestamp
flags
payload
Not every protocol needs every field.

Keep headers small, especially for high-frequency messages.

If the title sends:

20 packets / second
x 100,000 players
even a few unnecessary bytes can become significant.

Opcode-Based Protocols
Older MMORPG Multiplayer source Code frequently uses numeric opcodes.

Example:

0x0001 LOGIN_REQUEST
0x0002 LOGIN_RESPONSE
0x0101 PLAYER_MOVE
0x0102 PLAYER_ATTACK
0x0201 INVENTORY_UPDATE
The Match Server receives:

Opcode
|
v
Packet Handler
Conceptually:

switch opcode:
LOGIN_REQUEST -> handleLogin()
PLAYER_MOVE -> handleMove()
PLAYER_ATTACK -> handleAttack()
Large projects may use generated dispatch tables instead.

When analyzing Multiplayer source Code, finding the opcode definitions is often the fastest way to understand the network protocol.

Binary vs JSON Messages
JSON is convenient.

Example:

{
"type": "move",
"x": 100.5,
"y": 55.2
}
Advantages:

Readable
Easy to debug
Easy to integrate
Flexible
But it has overhead.

A compact binary packet may encode the same information in substantially fewer bytes.

Binary formats are often preferred for:

High-frequency play
Large entity updates
Mobile bandwidth optimization
Real-time combat
Text-based formats can remain perfectly reasonable for:

Admin APIs
Configuration
Internal tools
Low-frequency play
Many professional Realtime Backend systems use both.

Serialization
Title networking often uses serialization systems such as:

Protocol Buffers
FlatBuffers
MessagePack
Custom binary formats
The choice affects:

Packet size
CPU cost
Version compatibility
Ease of debugging
Cross-language support
Custom binary protocols can be extremely compact but may become difficult to maintain.

Generated schema-based formats provide stronger structure and compatibility tooling.

The best choice depends on the lifetime and scale of the project.

Bandwidth Optimization
Bandwidth matters particularly for Mobile Title networking.

Suppose each player sends:

5 KB/sec
At:

100,000 concurrent players
the aggregate traffic becomes substantial.

Optimization strategies may include:

Send only changed values
Reduce update frequency
Quantize numeric values
Use compact binary encoding
Compress appropriate messages
Aggregate multiple updates
Interest management
However, compression should not automatically be applied to every tiny packet.

Compression has CPU cost and may provide little benefit for small messages.

Measure before optimizing.

Delta Updates
Instead of sending a complete entity state:

player_id
x
y
z
rotation
hp
mp
animation
weapon
buffs
every update, the server can send only what changed.

Example:

player_id
x
y
rotation
This is a delta update.

If HP did not change, there is no reason to repeatedly transmit it.

Delta compression can significantly reduce bandwidth in large multiplayer environments.

Interest Management
An MMORPG world may contain thousands of entities.

A player does not need updates about every entity on the entire server.

Instead:

Player A
|
v
Area of Interest
Only nearby or relevant entities are replicated.

For example:

Visible players
Nearby monsters
Relevant projectiles
Party members
Important world objects
A simplified architecture:

World
|
+--> Zone A
| |
| +--> Player 1
| +--> Player 2
|
+--> Zone B
|
+--> Player 3
Player 1 normally does not need high-frequency movement updates from Player 3 if they are far apart.

Interest management is one of the most important bandwidth optimization techniques in MMORPG networking.

Server Tick Rate
A real-time Match Server usually updates simulation at a defined frequency.

For example:

20 ticks / second
means approximately:

50 ms per tick
At:

60 ticks / second
each tick is roughly:

16.7 ms
Higher tick rates can improve simulation granularity but increase:

CPU usage
Network updates
Bandwidth requirements
The correct value depends on play.

A strategy title may require a much lower rate than a competitive action title.

Tick rate should therefore be treated as an engineering trade-off rather than a marketing number.

Network Update Rate vs Simulation Rate
Simulation frequency and network transmission frequency do not have to be identical.

For example:

Simulation:
60 ticks/sec

Network state update:
20 packets/sec
The server can simulate frequently while sending snapshots less often.

Clients then smooth the visible movement.

This reduces bandwidth.

Client-Side Prediction
Network latency means the player cannot wait for the server before displaying every local movement.

Imagine:

Player presses forward
If the client waits:

Client -> Server -> Client
before moving the character, controls may feel delayed.

Client-side prediction solves this by applying the input locally.

Input
|
+--> Client predicts movement immediately
|
+--> Input sent to Match Server
The Match Server remains authoritative.

Later, the server returns the authoritative state.

If client and server agree:

No visible correction
If they disagree:

Client reconciles
This architecture is common in latency-sensitive multiplayer titles.

Server Reconciliation
Suppose the client predicts:

Position X = 100
but the authoritative server calculates:

Position X = 97
The client must correct the difference.

A sudden teleport may look bad.

Instead, correction may be applied gradually depending on the error.

However, large discrepancies should not always be hidden.

They may indicate:

Cheating
Packet loss
Client bug
Simulation divergence
Reconciliation is therefore both a networking and play design problem.

Interpolation
Remote players are another challenge.

If the client receives snapshots:

t0 -> position 10
t1 -> position 15
t2 -> position 20
rendering only when each packet arrives can produce visible movement jumps.

Interpolation deliberately renders slightly behind the newest known state.

Conceptually:

State A -------- State B
^
Render here
The client smoothly estimates positions between snapshots.

This trades a small amount of additional visual delay for smoother motion.

Extrapolation
If a packet is delayed, the client may estimate future movement.

Example:

Current position

- # Known velocity
  Predicted next position
  This is extrapolation.

It can hide short network gaps.

But long extrapolation becomes dangerous.

If the remote player suddenly changes direction, the prediction becomes increasingly incorrect.

Therefore extrapolation usually needs limits.

Lag Compensation
Consider a player shooting another player.

Because of network latency:

Shooter sees target at Position A
Server currently has target at Position B
A server may use historical state to evaluate what the shooter reasonably saw when the action occurred.

Conceptually:

Server history:

T-100ms -> Position A
T-50ms -> Position A2
Now -> Position B
The server can rewind relevant state to validate the shot.

Lag compensation must be implemented carefully because excessive compensation can create unfair results for other players.

Heartbeats and Connection Monitoring
Long-lived connections need a way to detect dead peers.

A simple heartbeat workflow:

Client -> PING
Server -> PONG
or:

Server -> Heartbeat
Client -> ACK
The server records:

last_seen
If no valid traffic arrives for a defined period:

Connection considered dead
Heartbeat design is important in Mobile Title networking because phones may:

Change networks
Enter background mode
Lose Wi-Fi
Switch to mobile data
Temporarily lose connectivity
Reconnection Architecture
Disconnects are normal.

A production title should define what happens when a player reconnects.

Possible workflow:

Client loses connection
|
v
Reconnect
|
v
Authenticate session
|
v
Find active match / world server
|
v
Restore state
For an MMORPG:

Reload current position
Reload nearby entities
Restore combat status
Restore buffs
Resume session
For a battle title:

Check whether match still exists
Reconnect to same battle server
Receive current snapshot
Resume control
This functionality should be designed explicitly rather than treated as an exceptional edge case.

Authoritative Match Server
One of the most important multiplayer security principles is:

Client sends input.
Server decides result.
Bad architecture:

Client:
"My new HP is 99999."
Better architecture:

Client:
"I used healing skill 17."

Server:
Validate skill
Validate cooldown
Validate mana
Calculate healing
Update HP
Broadcast result
Networking and anti-cheat architecture are closely connected.

The less authority the client controls, the harder it becomes to manipulate critical match state.

How to Analyze This in Multiplayer source Code
When examining a Multiplayer source Code project on the forum, networking code should be one of the first systems you map.

1. Find Socket Initialization
   Search for:

socket
connect
bind
listen
accept
udp
tcp
websocket
Determine which transport the client and Match Server use.

2. Find Packet Definitions
   Search for:

packet
message
opcode
protocol
command
request
response
Create a map such as:

Opcode 1001 -> Login
Opcode 1002 -> Enter World
Opcode 2001 -> Move
Opcode 2002 -> Attack 3. Find Serialization
Search for:

protobuf
serialize
deserialize
encode
decode
ByteBuffer
BinaryReader
BinaryWriter
This reveals how packets are represented.

4. Identify Packet Framing
   For TCP, determine how complete messages are separated.

Look for:

length
header
bodyLength
packetSize 5. Find Sequence and Reliability Logic
For UDP or custom protocols, search:

sequence
ack
reliable
resend
timeout
channel
Determine which packets may be dropped safely.

6. Locate Heartbeats
   Search for:

ping
pong
heartbeat
keepalive
lastSeen
Understand how dead sessions are removed.

7. Inspect Reconnect Logic
   Search for:

reconnect
resume
restoreSession
rejoin
This can reveal whether matches and world sessions survive temporary disconnections.

8. Trace One Play Action
   For example:

Client presses skill
|
Serialize packet
|
Network transport
|
Match Server receives
|
Packet handler
|
Play validation
|
State update
|
Response / broadcast
This end-to-end trace is extremely useful when learning unfamiliar Multiplayer source Code.

Common Mistakes
Using TCP Without Proper Framing
TCP is a byte stream.

Packets must be framed explicitly.

Treating UDP as Automatically Faster
UDP provides different semantics, but poor application logic can still create latency, packet storms, or synchronization problems.

Making Every Packet Reliable
Old movement updates often do not need retransmission.

Reliability should match the value of the data.

Sending Full World State
Interest management should limit updates to relevant entities.

Trusting Client Position
Movement should be validated server-side.

No Sequence Numbers
Without ordering information, late packets may overwrite newer state.

No Reconnect Design
Mobile and internet connections frequently drop.

Reconnect should be part of normal Multiplayer development planning.

Excessive Packet Frequency
Sending updates too often wastes bandwidth and CPU.

Measure how much frequency actually improves play.

Giant Network Messages
Large packets increase bandwidth and can interact poorly with network fragmentation and buffering.

Keep real-time packets focused and compact.

Best Practices
A practical Studio networking strategy should follow several principles.

Match the Protocol to the Message
Use reliability where it matters.

Do not force every data type into the same communication model.

Keep the Server Authoritative
The client should request actions, not dictate permanent results.

Use Sequence Numbers for Real-Time State
Reject stale updates.

Separate Simulation and Network Rates
The server does not necessarily need to transmit every simulation tick.

Implement Interest Management
Only replicate relevant entities.

Design Reconnection Early
Do not add it after launch.

Monitor Networking Metrics
Track:

Latency
Jitter
Packet loss
Bandwidth per player
Messages per second
Disconnect rate
Reconnect success rate
Packet size
Server send queue
Test Poor Networks
Title QA should simulate:

50 ms latency
150 ms latency
300 ms latency
Packet loss
Jitter
Out-of-order traffic
Temporary disconnect
Bandwidth limitation
A title that works perfectly on the studio LAN may behave very differently on real mobile networks.

Conclusion
Match Server networking is much more than opening a socket and sending packets.

A production Multiplayer architecture must balance:

Latency
Reliability
Ordering
Bandwidth
Security
CPU usage
Scalability
Player experience
TCP is an excellent choice for many reliable ordered Realtime Backend operations.

UDP provides greater control for latency-sensitive real-time traffic where some old state can be discarded.

WebSocket is particularly useful for browser-compatible and persistent bidirectional communication.

QUIC introduces modern transport capabilities that may be useful for appropriate Multiplayer development workloads, but adopting it should be driven by real requirements rather than novelty.

Beyond transport choice, a professional networking architecture also needs:

Packet framing
Serialization
Sequence numbers
Reliable channels
Interest management
Prediction
Interpolation
Reconciliation
Lag compensation
Heartbeats
Reconnect
Monitoring
For developers analyzing Multiplayer source Code, networking code often reveals the core structure of the entire project.

Once you understand how packets are encoded, routed, validated, synchronized, and broadcast, many other Match Server systems become easier to understand.

For projects examined through the forum, tracing the networking layer from client input to authoritative server response is one of the most effective ways to understand an unfamiliar MMORPG, Mobile Title, or Multiplayer backend.

In professional Multiplayer development, the goal is not to select the theoretically fastest transport.

The goal is to build a networking model that delivers the correct match data with the right combination of latency, reliability, bandwidth efficiency, security, and scalability.
