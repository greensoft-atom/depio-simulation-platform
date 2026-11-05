using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>
    /// A lobby that does what the test says, on a loopback WebSocket: the gateway's replies and pushes, written by
    /// hand, so the client's state can be driven where the real lobby cannot be made to go on demand.
    /// </summary>
    internal sealed class ScriptedLobby : IDisposable
    {
        // A server of the tests' own (D-109): neither .NET Framework off Windows nor Mono has a WebSocket server.
        private readonly TcpListener _listener = new TcpListener(IPAddress.Loopback, 0);
        public readonly Uri Uri;

        public ScriptedLobby()
        {
            _listener.Start();
            Uri = new Uri("ws://127.0.0.1:" + ((IPEndPoint)_listener.LocalEndpoint).Port + "/lobby");
        }

        /// <summary>The next connection, once the client has dialled, its opening answered.</summary>
        public ServerSocket Accept()
        {
            Task<TcpClient> client = _listener.AcceptTcpClientAsync();
            Assert.That(client.Wait(5_000), Is.True, "the client dialled");
            NetworkStream s = client.Result.GetStream();
            s.ReadTimeout = 5_000;
            var head = new StringBuilder();
            while (!head.ToString().EndsWith("\r\n\r\n")) head.Append((char)s.ReadByte());
            byte[] answer = Encoding.ASCII.GetBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + WebSocketServer.Accept(WebSocketServer.KeyOf(head.ToString())) + "\r\n\r\n");
            s.Write(answer, 0, answer.Length);
            return new ServerSocket(client.Result, s);
        }

        public static JsonValue Read(ServerSocket socket)
        {
            int op;
            byte[] payload = WebSocketServer.ReadFrame(socket.Stream, out op);
            return JsonValue.Parse(Encoding.UTF8.GetString(payload));
        }

        public static void Write(ServerSocket socket, string text)
        {
            byte[] bytes = Encoding.UTF8.GetBytes(text);
            socket.Send(bytes, 0, bytes.Length, true, true);
        }

        /// <summary>Reads the auth and answers it: the client is Ready once polled.</summary>
        public static void Admit(ServerSocket socket)
        {
            JsonValue auth = Read(socket);
            Assert.That(auth["t"].AsString, Is.EqualTo("auth"));
            Write(socket, "{\"t\":\"auth.ok\",\"id\":" + auth["id"].AsInt + ",\"d\":{\"playerId\":7}}");
        }

        public void Dispose() => _listener.Stop();
    }

    /// <summary>The lobby's end of one WebSocket: frames written as a server writes them, unmasked.</summary>
    internal sealed class ServerSocket
    {
        private readonly TcpClient _tcp;
        public readonly NetworkStream Stream;

        public ServerSocket(TcpClient tcp, NetworkStream stream)
        {
            _tcp = tcp;
            Stream = stream;
        }

        /// <summary>A text message's frame: the first of a message (text) or a continuation, and the last or not.</summary>
        public void Send(byte[] bytes, int offset, int count, bool first, bool fin) =>
            Frame((fin ? 0x80 : 0) | (first ? 0x1 : 0x0), bytes, offset, count);

        /// <summary>A close, its code and reason, as a gateway going away sends.</summary>
        public void Close(int code, string reason)
        {
            byte[] r = Encoding.UTF8.GetBytes(reason);
            var payload = new byte[2 + r.Length];
            payload[0] = (byte)(code >> 8);
            payload[1] = (byte)code;
            Buffer.BlockCopy(r, 0, payload, 2, r.Length);
            Frame(0x88, payload, 0, payload.Length);
        }

        private void Frame(int first, byte[] bytes, int offset, int count)
        {
            var head = new System.Collections.Generic.List<byte> { (byte)first };
            if (count < 126) head.Add((byte)count);
            else if (count <= 0xFFFF) { head.Add(126); head.Add((byte)(count >> 8)); head.Add((byte)count); }
            else { head.Add(127); for (int i = 7; i >= 0; i--) head.Add((byte)((long)count >> (8 * i))); }
            Stream.Write(head.ToArray(), 0, head.Count);
            Stream.Write(bytes, offset, count);
        }
    }

    public class LobbyClientTests
    {
        /// <summary>Polls until the condition holds: frames arrive on the socket's own task, in real time.</summary>
        private static bool PollUntil(LobbyClient lobby, Func<bool> done, int ms = 5_000)
        {
            var until = DateTime.UtcNow.AddMilliseconds(ms);
            while (DateTime.UtcNow < until)
            {
                lobby.Poll();
                if (done()) return true;
                Thread.Sleep(5);
            }
            return false;
        }

        /// <summary>A lobby by hand, on a raw socket: its answer to the opening as the test writes it, then frames.</summary>
        private static (TcpListener, Task<NetworkStream>) RawLobby(Func<string, string> accept)
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            Task<NetworkStream> opened = Task.Run(() =>
            {
                NetworkStream s = listener.AcceptTcpClient().GetStream();
                s.ReadTimeout = 5_000;                          // a frame that never comes fails the test, not hangs it
                var head = new StringBuilder();
                while (!head.ToString().EndsWith("\r\n\r\n")) head.Append((char)s.ReadByte());
                string key = WebSocketServer.KeyOf(head.ToString());
                byte[] answer = Encoding.ASCII.GetBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept(key) + "\r\n\r\n");
                s.Write(answer, 0, answer.Length);
                return s;
            });
            return (listener, opened);
        }

        private static string Accept(string key) => WebSocketServer.Accept(key);

        /// <summary>One frame from the client, unmasked: its opcode and payload. A client's frames are masked (RFC 6455).</summary>
        private static (int, byte[]) ClientFrame(NetworkStream s)
        {
            int op;
            byte[] payload = WebSocketServer.ReadFrame(s, out op);
            return (op, payload);
        }

        [Test]
        public void AnAnswerThatIsNoWebSocketsIsRefused()
        {
            var (listener, opened) = RawLobby(key => Accept(key + "x"));
            try
            {
                using (var lobby = new LobbyClient(new Uri($"ws://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}/lobby"),
                    new ManualClock()))
                {
                    lobby.Connect("token");
                    Assert.That(PollUntil(lobby, () => lobby.State == LobbyState.Reconnecting), Is.True);
                    Assert.That(lobby.LastError, Does.Contain("Sec-WebSocket-Accept"));
                }
            }
            finally { listener.Stop(); }
        }

        [Test]
        public void APingIsAnsweredWithItsPayload()
        {
            var (listener, opened) = RawLobby(Accept);
            try
            {
                using (var lobby = new LobbyClient(new Uri($"ws://127.0.0.1:{((IPEndPoint)listener.LocalEndpoint).Port}/lobby"),
                    new ManualClock()))
                {
                    lobby.Connect("token");
                    NetworkStream s = opened.Result;
                    Assert.That(ClientFrame(s).Item1, Is.EqualTo(1), "the token first, as text");
                    byte[] ping = { 0x89, 4, (byte)'p', (byte)'i', (byte)'n', (byte)'g' };
                    s.Write(ping, 0, ping.Length);
                    var (op, payload) = ClientFrame(s);
                    Assert.That((op, Encoding.ASCII.GetString(payload)), Is.EqualTo((0xA, "ping")), "a pong, with the ping's payload");
                }
            }
            finally { listener.Stop(); }
        }

        private static (ScriptedLobby, LobbyClient, ServerSocket, ManualClock) Ready()
        {
            var server = new ScriptedLobby();
            var clock = new ManualClock();
            var lobby = new LobbyClient(server.Uri, clock);
            lobby.Connect("token");
            ServerSocket socket = server.Accept();
            ScriptedLobby.Admit(socket);
            Assert.That(PollUntil(lobby, () => lobby.State == LobbyState.Ready), Is.True);
            return (server, lobby, socket, clock);
        }

        [Test]
        public void AfterAMatchTheQueueIsNoneAgainAndAJoinIsQueued()
        {
            var (server, lobby, socket, _) = Ready();
            using (server)
            using (lobby)
            {
                ScriptedLobby.Write(socket, "{\"t\":\"evt.match.ready\",\"d\":{\"matchUid\":\"m1\",\"mode\":\"duel\",\"seconds\":10}}");
                Assert.That(PollUntil(lobby, () => lobby.Ready != null), Is.True);
                Assert.That(lobby.QueueState, Is.EqualTo("confirming"));
                ScriptedLobby.Write(socket, "{\"t\":\"evt.match.found\",\"d\":{\"arenaHost\":\"a\",\"arenaPort\":9,\"ticketId\":\"t1\",\"tls\":false,\"mode\":\"duel\"}}");
                Assert.That(PollUntil(lobby, () => lobby.Grant != null), Is.True);
                Assert.That((lobby.QueueState, lobby.Ready), Is.EqualTo(("matched", (MatchReady)null)), "found: nothing left to answer");

                MatchGrant taken = lobby.TakeGrant();
                Assert.That((taken?.TicketId, lobby.Grant, lobby.QueueState), Is.EqualTo(("t1", (MatchGrant)null, "none")),
                    "the grant taken to play: the queue is the player's again");

                lobby.JoinQueue("duel");
                JsonValue join = ScriptedLobby.Read(socket);
                Assert.That(join["t"].AsString, Is.EqualTo("queue.join"));
                ScriptedLobby.Write(socket, "{\"t\":\"queue.join.ok\",\"id\":" + join["id"].AsInt + ",\"d\":{\"state\":\"queued\",\"mode\":\"duel\"}}");
                Assert.That(PollUntil(lobby, () => lobby.QueueState == "queued"), Is.True, "a second queue, not stuck at matched");
            }
        }

        /// <summary>
        /// T-61: the platform tells a party's members before it answers the leader, so a member's leave, and the
        /// leader's "none", can beat the leader's own answer; applied last, it showed the leader queued in no queue.
        /// </summary>
        [Test]
        public void AJoinRefusedAfterAQueuePushIsStillRefused()
        {
            // A decliner joins again while its called-off match is still being told (T-67): the push comes between the
            // join and its refusal, and the refusal is the join's all the same, as an answer the push outdates is not.
            var (server, lobby, socket, _) = Ready();
            using (server)
            using (lobby)
            {
                lobby.JoinQueue("duel");
                JsonValue join = ScriptedLobby.Read(socket);
                ScriptedLobby.Write(socket, "{\"t\":\"evt.queue.update\",\"d\":{\"state\":\"none\",\"mode\":null}}");
                ScriptedLobby.Write(socket, "{\"t\":\"error\",\"id\":" + join["id"].AsInt
                    + ",\"d\":{\"code\":\"queue_locked\",\"message\":\"platform refused the request\"}}");
                Assert.That(PollUntil(lobby, () => lobby.QueueRefusal != null), Is.True, "the refusal, though a push came first");
                Assert.That((lobby.QueueRefusal, lobby.QueueState), Is.EqualTo(("queue_locked", "none")));

                // A join left before its refusal came: moot, and not told as the queue's.
                lobby.JoinQueue("duel");
                join = ScriptedLobby.Read(socket);
                lobby.LeaveQueue();
                ScriptedLobby.Read(socket);
                ScriptedLobby.Write(socket, "{\"t\":\"error\",\"id\":" + join["id"].AsInt
                    + ",\"d\":{\"code\":\"queue_locked\",\"message\":\"platform refused the request\"}}");
                // Read in order: once this is seen, the refusal before it has been read too.
                ScriptedLobby.Write(socket, "{\"t\":\"evt.party.invite\",\"d\":{\"partyId\":\"p\",\"from\":8,\"fromName\":\"b\"}}");
                Assert.That(PollUntil(lobby, () => lobby.Invitation != null), Is.True);
                Assert.That(lobby.QueueRefusal, Is.Null, "a refusal of a join already left");
            }
        }

        [Test]
        public void AJoinsAnswerAfterAQueuePushIsLateAndDropped()
        {
            var (server, lobby, socket, _) = Ready();
            using (server)
            using (lobby)
            {
                lobby.JoinQueue("tvt");
                JsonValue join = ScriptedLobby.Read(socket);
                ScriptedLobby.Write(socket, "{\"t\":\"evt.queue.update\",\"d\":{\"state\":\"none\",\"mode\":null}}");
                ScriptedLobby.Write(socket, "{\"t\":\"queue.join.ok\",\"id\":" + join["id"].AsInt + ",\"d\":{\"state\":\"queued\",\"mode\":\"tvt\"}}");
                // Read in order: once this is seen, the answer before it has been read too.
                ScriptedLobby.Write(socket, "{\"t\":\"evt.party.invite\",\"d\":{\"partyId\":\"p\",\"from\":8,\"fromName\":\"b\"}}");
                Assert.That(PollUntil(lobby, () => lobby.Invitation != null), Is.True);
                Assert.That(lobby.QueueState, Is.EqualTo("none"), "the push came after the join: the answer is older");

                lobby.JoinQueue("tvt");
                join = ScriptedLobby.Read(socket);
                ScriptedLobby.Write(socket, "{\"t\":\"queue.join.ok\",\"id\":" + join["id"].AsInt + ",\"d\":{\"state\":\"queued\",\"mode\":\"tvt\"}}");
                Assert.That(PollUntil(lobby, () => lobby.QueueState == "queued"), Is.True, "a join answered with nothing between");
            }
        }

        [Test]
        public void AJoinsAnswerAfterALeaveIsLateAndDropped()
        {
            var (server, lobby, socket, _) = Ready();
            using (server)
            using (lobby)
            {
                lobby.JoinQueue("duel");
                JsonValue join = ScriptedLobby.Read(socket);
                lobby.LeaveQueue();
                JsonValue leave = ScriptedLobby.Read(socket);
                ScriptedLobby.Write(socket, "{\"t\":\"queue.join.ok\",\"id\":" + join["id"].AsInt + ",\"d\":{\"state\":\"queued\",\"mode\":\"duel\"}}");
                ScriptedLobby.Write(socket, "{\"t\":\"queue.leave.ok\",\"id\":" + leave["id"].AsInt + ",\"d\":{\"state\":\"none\"}}");
                ScriptedLobby.Write(socket, "{\"t\":\"evt.party.invite\",\"d\":{\"partyId\":\"p\",\"from\":8,\"fromName\":\"b\"}}");
                Assert.That(PollUntil(lobby, () => lobby.Invitation != null), Is.True);
                Assert.That(lobby.QueueState, Is.EqualTo("none"), "left after joining: the join's answer is older");
            }
        }

        [Test]
        public void ADeclinedMatchIsForgottenAndTheQueueSaysNone()
        {
            var (server, lobby, socket, _) = Ready();
            using (server)
            using (lobby)
            {
                ScriptedLobby.Write(socket, "{\"t\":\"evt.match.ready\",\"d\":{\"matchUid\":\"m1\",\"mode\":\"duel\",\"seconds\":10}}");
                Assert.That(PollUntil(lobby, () => lobby.Ready != null), Is.True);
                lobby.DeclineMatch();
                JsonValue decline = ScriptedLobby.Read(socket);
                ScriptedLobby.Write(socket, "{\"t\":\"match.decline.ok\",\"id\":" + decline["id"].AsInt + ",\"d\":{\"state\":\"none\"}}");
                Assert.That(PollUntil(lobby, () => lobby.Ready == null), Is.True, "declined: nothing to answer");
                ScriptedLobby.Write(socket, "{\"t\":\"evt.queue.update\",\"d\":{\"state\":\"none\",\"mode\":null}}");
                Assert.That(PollUntil(lobby, () => lobby.QueueState == "none"), Is.True);

                ScriptedLobby.Write(socket, "{\"t\":\"evt.match.ready\",\"d\":{\"matchUid\":\"m2\",\"mode\":\"duel\",\"seconds\":10}}");
                Assert.That(PollUntil(lobby, () => lobby.Ready?.MatchUid == "m2"), Is.True);
                ScriptedLobby.Write(socket, "{\"t\":\"evt.queue.update\",\"d\":{\"state\":\"queued\",\"mode\":\"duel\"}}");
                Assert.That(PollUntil(lobby, () => lobby.Ready == null && lobby.QueueState == "queued"), Is.True,
                    "called off by another's decline: queued again, and the old match not offered");
            }
        }

        [Test]
        public void AMessageSplitInsideACharacterIsReadWhole()
        {
            var (server, lobby, socket, _) = Ready();
            using (server)
            using (lobby)
            {
                string name = "Zoë 東京";
                byte[] bytes = Encoding.UTF8.GetBytes("{\"t\":\"evt.party.invite\",\"d\":{\"partyId\":\"p1\",\"from\":9,\"fromName\":\"" + name + "\"}}");
                int cut = Array.IndexOf(bytes, (byte)0xC3) + 1;           // inside ë's two bytes
                socket.Send(bytes, 0, cut, true, false);
                Thread.Sleep(50);
                socket.Send(bytes, cut, bytes.Length - cut, false, true);
                Assert.That(PollUntil(lobby, () => lobby.Invitation != null), Is.True);
                Assert.That(lobby.Invitation.FromName, Is.EqualTo(name));
            }
        }

        [Test]
        public void ALobbyThatStopsAnsweringIsLostAndDialledAgain()
        {
            var (server, lobby, socket, clock) = Ready();
            using (server)
            using (lobby)
            {
                clock.NowMs += LobbyClient.PingEveryMs;
                lobby.Poll();
                JsonValue ping = ScriptedLobby.Read(socket);
                Assert.That(ping["t"].AsString, Is.EqualTo("ping"));
                ScriptedLobby.Write(socket, "{\"t\":\"ping.ok\",\"id\":" + ping["id"].AsInt + ",\"d\":{}}");
                Assert.That(PollUntil(lobby, () => false, 200), Is.False);
                clock.NowMs += LobbyClient.PingAnswerMs + 1;
                lobby.Poll();
                Assert.That(lobby.State, Is.EqualTo(LobbyState.Ready), "answered: still there");

                clock.NowMs += LobbyClient.PingEveryMs;
                lobby.Poll();
                Assert.That(ScriptedLobby.Read(socket)["t"].AsString, Is.EqualTo("ping"));
                clock.NowMs += LobbyClient.PingAnswerMs - 1;
                lobby.Poll();
                Assert.That(lobby.State, Is.EqualTo(LobbyState.Ready), "within its time");
                clock.NowMs += 2;
                lobby.Poll();
                Assert.That(lobby.State, Is.EqualTo(LobbyState.Reconnecting), "no answer: a link that is gone, though no socket said so");
            }
        }

        [Test]
        public void ReconnectsAreSpreadNotInStep()
        {
            using (var server = new ScriptedLobby())
            {
                var clock = new ManualClock();
                using (var lobby = new LobbyClient(server.Uri, clock))
                {
                    lobby.Connect("token");
                    var waits = new List<long>();
                    for (int i = 0; i < 6; i++)
                    {
                        ServerSocket dropped = server.Accept();
                        // A gateway going away: its Close sent, and the client gone without completing the handshake.
                        try { dropped.Close(1001, "going away"); }
                        catch (System.IO.IOException) { }
                        Assert.That(PollUntil(lobby, () => lobby.State == LobbyState.Reconnecting), Is.True);
                        long lost = clock.NowMs;
                        long nominal = Math.Min(1_000L << i, 30_000);
                        while (lobby.State == LobbyState.Reconnecting)
                        {
                            clock.NowMs += 10;
                            lobby.Poll();
                        }
                        long waited = clock.NowMs - lost;
                        Assert.That(waited, Is.InRange(nominal / 2, nominal * 3 / 2 + 10), $"attempt {i}: half to one and a half of {nominal}");
                        waits.Add(waited - nominal);
                    }
                    Assert.That(waits.Exists(w => Math.Abs(w) > 20), Is.True, "jittered: a gateway's restart is not met by every client at once");
                }
            }
        }
    }
}
