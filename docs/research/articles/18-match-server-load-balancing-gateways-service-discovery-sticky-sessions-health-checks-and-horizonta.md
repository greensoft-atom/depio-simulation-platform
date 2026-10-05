#18 – Match Server Load Balancing: Gateways, Service Discovery, Sticky Sessions, Health Checks and Horizontal Scaling
administrator
administrator 
Verified user account
15/08/2026 18:01
•
General Discussion
Match Server Load Balancing: Gateways, Service Discovery, Sticky Sessions, Health Checks and Horizontal Scaling
Introduction
A multiplayer title rarely stays on one Match Server forever.

During early Multiplayer development, a simple architecture may be enough:

Players
   |
   v
Match Server
This works for local testing, small private environments, and early-stage projects.

But as concurrency grows, a single server quickly becomes a bottleneck.

The Realtime Backend may need to handle:

thousands of simultaneous connections

login spikes after maintenance

matchmaking traffic

persistent TCP or WebSocket sessions

regional players

guild events

world bosses

seasonal launches

client updates

unexpected traffic bursts

Eventually, the architecture needs multiple server instances.

Players
   |
   v
Load Balancer
   |
   +--> Match Server A
   +--> Match Server B
   +--> Match Server C
At first glance, this looks simple.

Just add more Match Servers and distribute players between them.

In practice, Match Server load balancing is significantly more complicated than ordinary web traffic because multiplayer titles often maintain long-lived connections and stateful sessions.

A player connected to Server A may have active state stored in memory:

Character Position
Combat State
Current Match
Temporary Buffs
Session Information
Sending the player's next packet to Server B may not work at all.

For this reason, production Realtime Backend architecture frequently uses gateways, session routing, service discovery, health checks, sticky sessions, and explicit connection ownership.

At the forum, analyzing these components is useful when working with multiplayer source Code because many projects include several processes that appear unrelated until their load-balancing roles are understood.

This article explains how professional Multiplayer development teams design Match Server load balancing, including gateway architecture, session affinity, health checks, service discovery, horizontal scaling, failover, reconnect behavior, and monitoring.

Why Web Load Balancing Is Easier Than Match Server Load Balancing
Traditional HTTP applications are often designed to be stateless.

A browser may send:

Request 1 -> Server A
Request 2 -> Server C
Request 3 -> Server B
As long as every server has access to shared backend data, this can work correctly.

A multiplayer Match Server behaves differently.

Consider a persistent connection:

Client
   |
   | TCP / WebSocket
   |
   v
Match Server A
Server A may own:

socket connection
player session
map state
combat state
pending packets
temporary simulation state
The load balancer cannot casually move packet number 500 to Server B.

Server B may not know anything about the active player.

Therefore, Match Server load balancing usually happens at specific boundaries.

Examples include:

connection establishment

login

matchmaking

world selection

map transfer

session migration

Once a session is assigned, it may remain attached to one server until a controlled transition occurs.

Basic Load Balancer Architecture
A simple architecture looks like:

                Players
                   |
                   v
             Load Balancer
             /     |      \
            v      v       v
        Server A Server B Server C
The load balancer accepts incoming traffic and selects a backend server.

Possible routing strategies include:

round robin

least connections

weighted routing

consistent hashing

session affinity

geographic routing

Each strategy fits a different workload.

Round Robin
Round robin distributes new connections sequentially.

For example:

Player 1 -> Server A
Player 2 -> Server B
Player 3 -> Server C
Player 4 -> Server A
This is simple and can work when servers have similar capacity.

But it does not consider current load.

Imagine:

Server A -> 7,000 players
Server B -> 2,000 players
Server C -> 2,100 players
Sending the next connection to Server A simply because it is A's turn is inefficient.

For Match Server workloads, connection count or real server load may be more useful.

Least Connections
The least-connections strategy routes new players to the server with fewer active connections.

For example:

Server A -> 4,200 connections
Server B -> 3,100 connections
Server C -> 5,500 connections
The next player may go to:

Server B
This can work well for persistent multiplayer sessions.

However, connection count is not always equal to server load.

One server might contain:

3,000 idle players
while another contains:

2,000 players in CPU-heavy battles
The second server may actually be more overloaded.

More advanced systems therefore consider multiple metrics.

Load-Aware Routing
A Realtime Backend can route based on a calculated score.

For example:

Server Load Score =
CPU Weight
+ Memory Weight
+ Connection Weight
+ Active Match Weight
A simplified service report might look like:

Server A
CPU: 45%
Memory: 52%
Players: 3100

Server B
CPU: 72%
Memory: 65%
Players: 2900

Server C
CPU: 38%
Memory: 41%
Players: 2700
Even though Server B has fewer players than Server A, Server C may still be the best destination.

The exact algorithm depends on title architecture.

Gateway Architecture
Many production multiplayer titles do not expose internal Match Servers directly.

Instead, players connect to a gateway.

Client
   |
   v
Gateway
   |
   +--> Login Service
   +--> World Server
   +--> Match Server
   +--> Chat Service
The gateway becomes the public networking layer.

It can handle:

TCP or WebSocket connections

authentication

encryption

rate limiting

packet validation

routing

connection ownership

Internal Match Servers remain hidden behind private infrastructure.

This architecture provides several benefits.

Why Gateways Are Useful
Suppose a player is connected to:

Gateway 5
The gateway may route different packet types to different services.

Movement Packet
      |
      v
World Server

Chat Packet
      |
      v
Chat Service

Purchase Packet
      |
      v
Store Service
The client does not need separate public addresses for every backend service.

The architecture becomes:

Client
  |
  v
Gateway
  |
  +--> Internal Services
This simplifies security and internal topology changes.

Multiple Gateway Instances
One gateway also has limits.

Large titles distribute connections across many gateway servers.

                   Load Balancer
                        |
         +--------------+--------------+
         |              |              |
         v              v              v
     Gateway 1      Gateway 2      Gateway 3
A shared registry can store connection ownership.

For example:

player:1001 -> gateway:2
player:1002 -> gateway:1
player:1003 -> gateway:3
Redis is often useful for this type of short-lived routing metadata.

Then another Realtime Backend service can determine:

Where is Player 1001 connected?
and send an event to the correct gateway.

Sticky Sessions
Sticky sessions, also called session affinity, keep one client associated with the same backend instance.

For example:

Player A -> Server 3
remains:

Player A -> Server 3
for the duration of the session.

This is useful when state exists only inside the process memory of Server 3.

Possible affinity mechanisms include:

source IP

cookies

connection ownership

account ID hashing

session ID mapping

For title networking, explicit session routing is usually clearer than depending only on IP affinity.

Why IP-Based Sticky Sessions Can Be Problematic
Several players may share one public IP.

Examples include:

mobile carrier NAT

university networks

internet cafés

office networks

If routing relies entirely on:

client IP
many unrelated players may be mapped to the same Match Server.

IP addresses may also change.

Mobile users can switch:

Wi-Fi -> Cellular
and receive a different address.

Therefore, account ID, session ID, or server-issued routing identifiers are often better choices.

Consistent Hashing
Consistent hashing is useful when a stable key should map to a server.

For example:

hash(playerId) -> Match Server
Conceptually:

Player 1001 -> Server B
Player 1002 -> Server A
Player 1003 -> Server C
If one server disappears, only part of the keyspace needs to move.

This can be useful for:

chat routing

cache ownership

worker partitioning

some session routing systems

However, consistent hashing should not be used blindly for real-time play.

A player's current world or map state may require more explicit placement logic.

Match-Based Load Balancing
Session-based titles often create dedicated match servers.

For example:

Matchmaker
    |
    v
Select Match Server
    |
    v
Create Match
Suppose the infrastructure has:

Battle Server A
Battle Server B
Battle Server C
Battle Server D
The matchmaker can choose the least-loaded instance.

Once selected:

Match #5021 -> Battle Server C
all players in that match connect or route to Server C.

This is a natural load-balancing boundary.

The match remains on that instance until completion.

Dynamic Match Server Creation
Container-based infrastructure can create Match Server instances dynamically.

Conceptually:

Match Demand Increases
        |
        v
Start More Containers
        |
        v
Register New Servers
        |
        v
Matchmaker Uses Them
During lower traffic:

Match Demand Drops
        |
        v
Drain Old Servers
        |
        v
Terminate Instances
This provides horizontal scaling.

It is especially useful for:

FPS matches

MOBA battles

dungeon instances

battle royale servers

temporary PvP arenas

World Server Load Balancing
MMORPG systems behave differently from isolated match servers.

A world may contain long-lived state.

For example:

World 12
   |
   +--> Map Server A
   +--> Map Server B
   +--> Map Server C
Players may be distributed by map or region of the world.

For example:

Capital City -> Map Server A
Forest Zone  -> Map Server B
Dungeon Area -> Map Server C
When the player moves between zones:

Map Server A
      |
      v
Session Transfer
      |
      v
Map Server B
This is effectively load balancing through spatial partitioning.

Service Discovery
Once Realtime Backend services scale horizontally, addresses should not be hard-coded.

A poor configuration might contain:

match_server_1 = 10.0.0.10
match_server_2 = 10.0.0.11
match_server_3 = 10.0.0.12
This becomes difficult to maintain when instances are constantly created and destroyed.

Service discovery solves this problem.

A Match Server registers itself:

Service Name: battle-server
Address: 10.0.4.25
Port: 9001
Status: healthy
Region: asia
Other services query the registry.

Matchmaker
    |
    v
Service Discovery
    |
    v
Available Battle Servers
This allows the infrastructure to change dynamically.

Server Registration
When a Match Server starts:

Server Starts
    |
    v
Load Configuration
    |
    v
Connect Dependencies
    |
    v
Register Service
    |
    v
READY
The registry can store metadata such as:

serverId
serviceType
region
worldId
currentPlayers
capacity
version
For example:

battle-27
region=asia
players=120
capacity=500
version=2.7.3
This information can help routing decisions.

Heartbeats
A registered Match Server needs to prove that it is still alive.

A heartbeat flow might be:

Match Server
    |
every 5 seconds
    |
    v
Service Registry
If several heartbeats are missed:

Server -> unhealthy
The instance can be removed from new traffic.

However, heartbeat timeout values should not be too aggressive.

A temporary CPU spike should not immediately cause the infrastructure to treat a healthy server as dead.

Health Checks
Health checks are fundamental to load balancing.

A load balancer must know whether a backend can receive traffic.

A simple health endpoint might return:

200 OK
But process existence alone is not enough.

A server could technically be alive while:

Database disconnected
Redis disconnected
Product data not loaded
Worker threads deadlocked
A better design separates health concepts.

Liveness vs Readiness
Liveness
Answers:

Is the process functioning?
If not, the orchestrator may restart it.

Readiness
Answers:

Can this service safely receive new traffic?
A Match Server may be alive but not ready because it is still loading:

maps
configuration
scripts
item data
AI data
Only after initialization should it enter the load-balancing pool.

Draining Servers
A server being removed from production should not necessarily terminate immediately.

Instead:

ACTIVE
   |
   v
DRAINING
   |
   v
STOPPED
When draining:

new players -> rejected
existing players -> continue
This is useful during:

deployments

maintenance

autoscaling

hardware replacement

Match servers can wait until all current matches finish.

Gateway servers can stop accepting new sessions and wait for connected players to leave or reconnect elsewhere.

Failover
A load-balanced Realtime Backend should expect server failures.

Suppose:

Gateway 2 crashes
Players connected to it lose their network connection.

The client should reconnect:

Client
   |
   v
Load Balancer
   |
   v
Gateway 4
The new gateway then restores session context from shared backend state.

This is why session information stored only inside Gateway 2 is risky.

Important routing state may need to exist in:

Redis

session service

world server

persistent storage

depending on architecture.

Reconnection After Match Server Failure
Match Server failure is harder than gateway failure.

Suppose Battle Server C owns an active match.

If it crashes, there are several possible strategies.

Accept Match Loss
For low-value casual titles:

match canceled
players returned to lobby
may be acceptable.

Restore From Snapshot
The server periodically stores state:

Tick Snapshot
Player State
Match State
A replacement server restores the match.

This is more complex.

Replicated Simulation
Critical systems can replicate state to another node.

This is even more expensive and difficult.

The correct design depends on how damaging a lost match would be.

Horizontal Scaling
Horizontal scaling means adding more server instances instead of only increasing one machine's resources.

Vertical scaling:

8 CPU -> 16 CPU -> 32 CPU
Horizontal scaling:

3 servers -> 6 servers -> 12 servers
Horizontal scaling is the foundation of modern Realtime Backend architecture.

However, it works best when workloads can be partitioned cleanly.

Good candidates include:

API services

login services

gateways

matchmaking workers

battle servers

chat workers

Highly stateful world simulation may require more specialized partitioning.

Autoscaling
Autoscaling increases or decreases capacity automatically.

A policy might monitor:

CPU
active connections
match queue
memory
request rate
Example:

Average CPU > 70%
for 5 minutes
       |
       v
Add 3 Match Server instances
Another policy might monitor pending matches.

Waiting matches > 100
       |
       v
Scale battle servers
Choosing the correct metric matters.

CPU may remain low while connection count reaches an infrastructure limit.

For another service, CPU may be the primary constraint.

Scaling Before Traffic Arrives
Reactive autoscaling is not always fast enough.

Imagine a scheduled world event begins at:

20:00
and traffic instantly triples.

Waiting until servers overload before creating new instances may produce poor player experience.

The studio already knows the event schedule.

Capacity can be increased before:

19:50
This is called predictive or scheduled scaling.

It is especially useful for:

launches

maintenance completion

scheduled events

tournaments

daily reset

seasonal updates

Avoiding the Login Storm Problem
After maintenance ends, thousands of players may reconnect simultaneously.

Maintenance Ends
      |
      v
100,000 Login Requests
This can overload:

Login Service
Account Database
Redis
Gateway
World Server
A queue or controlled admission layer can help.

For example:

Players
   |
   v
Login Queue
   |
   v
Controlled Admission
   |
   v
Realtime Backend
This may be better than letting every request overload downstream systems.

Capacity planning should consider peak reconnect traffic, not only normal concurrency.

Load Balancing and Redis
Redis frequently supports load-balancing infrastructure.

Possible data includes:

player -> gateway mapping
server capacity
active player count
session state
matchmaking state
For example:

gateway:03:players = 4820
gateway:04:players = 3912
The router can use this information when selecting a gateway.

However, Redis should not become a single point of failure.

High-availability configuration and reconnect handling remain important.

Load Balancing and Databases
Adding more Match Servers can accidentally overload the database.

Suppose:

10 Match Servers
each use:

50 DB connections
Total:

500 connections
After horizontal scaling:

50 Match Servers
may create:

2500 database connections
The Match Server tier becomes healthier while the database becomes overloaded.

This illustrates an important principle:

horizontal scaling must consider downstream capacity.

Scaling one layer may move the bottleneck elsewhere.

Service Discovery and Versioning
During deployment, multiple versions may coexist.

For example:

battle-server v2.1
battle-server v2.2
Service discovery metadata can include version information.

A matchmaker might gradually route:

95% -> v2.1
5%  -> v2.2
for a canary release.

If v2.2 remains healthy:

50% -> v2.2
then:

100% -> v2.2
Load balancing and deployment architecture are therefore closely connected.

Regional Load Balancing
A global online title may operate several regions.

Players
   |
   v
Global Routing
   |
   +--> Asia
   +--> Europe
   +--> North America
The player should usually connect to a region with low latency.

Routing can consider:

geographical location

measured latency

region availability

player account region

Regional routing reduces network delay but introduces additional architecture for:

global accounts

cross-region matchmaking

global rankings

player migration

Sticky Sessions vs Stateless Services
Not every Realtime Backend service should be sticky.

For example:

Profile API
can often be stateless.

Any instance can serve the request.

Request
   |
   +--> API A
   +--> API B
   +--> API C
A real-time world session is different.

It may need:

Player -> World Server 7
for the duration of that play session.

A good architecture distinguishes:

Stateless Services
Stateful Services
rather than applying one load-balancing strategy everywhere.

Avoiding Central Router Bottlenecks
Adding one router can solve routing problems but create a new bottleneck.

For example:

All Player Packets
       |
       v
One Gateway
       |
       v
Everything Else
If that gateway fails, the entire title disconnects.

Production architecture should horizontally scale routing layers.

Players
   |
   v
Load Balancer
   |
   +--> Gateway A
   +--> Gateway B
   +--> Gateway C
Critical routing metadata should not depend on one process.

Capacity Limits
Every server type should define a safe capacity.

For example:

Gateway:
Max Connections = 15,000

Battle Server:
Max Matches = 200

World Worker:
Max Players = 3,000
The routing layer should avoid sending new work to instances approaching their limits.

A server can advertise:

capacity = 3000
current = 2780
The load balancer can prefer another server.

Capacity should be determined through load testing rather than guesswork.

Load Testing
A Match Server should be tested before production.

Useful tests include:

concurrent connection tests

packet throughput

login spikes

match creation spikes

database load

Redis load

gateway memory usage

reconnect storms

A test might simulate:

50,000 connected clients
20 packets/sec/client
This produces:

1,000,000 packets/sec
before considering responses.

These tests help determine actual server limits.

Monitoring Load-Balanced Match Servers
Monitoring should include both infrastructure and player-facing metrics.

Useful metrics include:

connections_per_gateway
players_per_server
matches_per_server
CPU
memory
network throughput
packet rate
routing failures
health_check_failures
server_registration_count
Routing imbalance is also important.

For example:

Server A -> 6,200 players
Server B -> 6,050 players
Server C -> 900 players
This may reveal a configuration problem.

Monitoring Session Distribution
A dashboard can show:

Gateway 01 -> 8,500 sessions
Gateway 02 -> 8,350 sessions
Gateway 03 -> 8,740 sessions
Gateway 04 -> 8,490 sessions
If one instance suddenly falls to:

0 sessions
operators can investigate:

gateway crash

health check failure

network routing

deployment issue

Good visibility is essential in horizontal Realtime Backend architecture.

Health Check Mistakes
A common mistake is defining health as:

process exists
A service can still be unusable.

A better health system may verify:

main loop responsive
dependencies reachable
product configuration loaded
server registered
critical workers functioning
But health checks should also remain lightweight.

Running expensive database queries every second from hundreds of instances can create unnecessary load.

How to Analyze This in Multiplayer source Code
When reviewing multiplayer source Code, search for terms such as:

gateway
proxy
router
balancer
serverlist
discovery
registry
heartbeat
health
session
connector
Configuration may contain:

gateway_host
gateway_port
server_id
max_players
center_server
registry_server
Startup scripts may reveal services such as:

login_server
gateway_server
world_server
battle_server
center_server
The center_server often plays an important coordination role.

It may track:

active servers

server IDs

player routing

cross-server communication

Look for registration logic such as:

RegisterServer
ServerHeartbeat
ServerOffline
UpdateLoad
These functions can reveal how the original Realtime Backend distributes traffic.

When analyzing Multiplayer source Code from the forum, drawing a routing diagram is often one of the fastest ways to understand deployment requirements.

For example:

Client
  |
Login Server
  |
Gateway
  |
World Server
  |
Battle Server
Then identify which services can run multiple instances.

Ask:

How does a new Match Server register?

Who chooses the target server?

Is session affinity required?

Where is player-to-server mapping stored?

What happens if the server crashes?

How are health checks performed?

Can instances be added without restarting the whole cluster?

Does the server advertise capacity?

Are draining states supported?

How are reconnecting players routed?

These questions reveal whether the project is designed for a single-server environment or a scalable production cluster.

Common Mistakes
Treating Stateful Match Servers Like Stateless Web Servers
Randomly routing packets between instances can break active sessions.

No Health Checks
Traffic continues reaching broken servers.

Health Check Only Tests Process Existence
A server may be alive but unable to serve players.

Hard-Coding Every Match Server Address
Dynamic scaling becomes difficult.

Relying Only on IP Sticky Sessions
Mobile networks and NAT can produce unreliable affinity.

No Draining State
Deployments disconnect active matches unnecessarily.

Scaling Match Servers Without Scaling Dependencies
Redis or databases become the next bottleneck.

No Capacity Limits
Load balancers continue sending players to overloaded servers.

One Central Gateway
The gateway becomes a single point of failure.

No Reconnect Routing
Players cannot restore sessions after gateway failure.

Autoscaling Only by CPU
Connection-heavy services may reach limits while CPU remains low.

Best Practices
Studios designing load-balanced Realtime Backend infrastructure should generally:

distinguish stateful and stateless services

place public connections behind scalable gateway layers

use explicit session ownership

use service discovery instead of hard-coded addresses

maintain server health and readiness checks

support graceful draining

advertise server capacity

choose routing algorithms based on workload

use least-connections or load-aware routing where appropriate

use sticky sessions only where state requires them

maintain shared session metadata for reconnects

design gateways for horizontal scaling

test server failure and recovery

monitor load distribution

consider downstream database and Redis capacity

pre-scale before predictable traffic spikes

perform realistic load testing

use version-aware routing during deployments

isolate regions where latency matters

The key principle is simple:

adding more servers only helps when the architecture knows how to route work between them safely.

Conclusion
Match Server load balancing is much more than placing a reverse proxy in front of several machines.

Multiplayer titles contain persistent connections, active sessions, real-time state, match ownership, and world simulation.

These workloads require deliberate routing.

A mature architecture may contain:

Global Load Balancer
        |
        v
Gateway Cluster
        |
        v
Service Discovery
        |
   +----+----+
   |         |
World     Battle
Servers   Servers
        |
        v
Redis / Database / Backend Services
Gateways provide a stable public networking layer.

Service discovery allows Match Servers to appear and disappear dynamically.

Sticky sessions keep stateful players attached to the correct server.

Health checks prevent new players from reaching broken instances.

Draining protects active matches during deployments.

Horizontal scaling allows the Realtime Backend to increase capacity as player traffic grows.

But scaling should always be considered as a complete system.

Adding 20 Match Servers may simply move the bottleneck to Redis, the database, matchmaking, or network infrastructure.

For developers analyzing Multiplayer source Code, load-balancing logic is often hidden inside gateways, center servers, registration systems, heartbeat handlers, and server-list configuration.

Projects available through the forum may include multiple Match Server components that only make sense once their routing and service-discovery relationships are mapped correctly.

Understanding this architecture is essential before attempting to scale a multiplayer project beyond a single machine.

The goal of load balancing is not merely to distribute traffic evenly.

The real goal is to route every player, session, and match to a healthy server that can own that workload safely while allowing the entire Realtime Backend to grow horizontally.