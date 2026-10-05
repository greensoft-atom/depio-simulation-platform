using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Net.WebSockets;
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
        private readonly HttpListener _listener = new HttpListener();
        public readonly Uri Uri;

        public ScriptedLobby()
        {
            var probe = new TcpListener(IPAddress.Loopback, 0);
            probe.Start();
            int port = ((IPEndPoint)probe.LocalEndpoint).Port;
            probe.Stop();
            _listener.Prefixes.Add($"http://127.0.0.1:{port}/");
            _listener.Start();
            Uri = new Uri($"ws://127.0.0.1:{port}/lobby");
        }

        /// <summary>The next connection, once the client has dialled.</summary>
        public WebSocket Accept()
        {
            Task<HttpListenerContext> context = _listener.GetContextAsync();
            Assert.That(context.Wait(5_000), Is.True, "the client dialled");
            return context.Result.AcceptWebSocketAsync(null).GetAwaiter().GetResult().WebSocket;
        }

        public static JsonValue Read(WebSocket socket)
        {
            var buffer = new byte[16 * 1024];
            var cancel = new CancellationTokenSource(5_000);
            WebSocketReceiveResult r = socket.ReceiveAsync(new ArraySegment<byte>(buffer), cancel.Token).GetAwaiter().GetResult();
            return JsonValue.Parse(Encoding.UTF8.GetString(buffer, 0, r.Count));
        }

        public static void Write(WebSocket socket, string text) =>
            socket.SendAsync(new ArraySegment<byte>(Encoding.UTF8.GetBytes(text)), WebSocketMessageType.Text, true, CancellationToken.None)
                .GetAwaiter().GetResult();

        /// <summary>Reads the auth and answers it: the client is Ready once polled.</summary>
        public static void Admit(WebSocket socket)
        {
            JsonValue auth = Read(socket);
            Assert.That(auth["t"].AsString, Is.EqualTo("auth"));
            Write(socket, "{\"t\":\"auth.ok\",\"id\":" + auth["id"].AsInt + ",\"d\":{\"playerId\":7}}");
        }

        public void Dispose() => _listener.Close();
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

        private static (ScriptedLobby, LobbyClient, WebSocket, ManualClock) Ready()
        {
            var server = new ScriptedLobby();
            var clock = new ManualClock();
            var lobby = new LobbyClient(server.Uri, clock);
            lobby.Connect("token");
            WebSocket socket = server.Accept();
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
                socket.SendAsync(new ArraySegment<byte>(bytes, 0, cut), WebSocketMessageType.Text, false, CancellationToken.None).Wait();
                Thread.Sleep(50);
                socket.SendAsync(new ArraySegment<byte>(bytes, cut, bytes.Length - cut), WebSocketMessageType.Text, true, CancellationToken.None).Wait();
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
            using var server = new ScriptedLobby();
            var clock = new ManualClock();
            using var lobby = new LobbyClient(server.Uri, clock);
            lobby.Connect("token");
            var waits = new List<long>();
            for (int i = 0; i < 6; i++)
            {
                WebSocket dropped = server.Accept();
                // A gateway going away: its Close sent, and the client gone without completing the handshake.
                try { dropped.CloseOutputAsync(WebSocketCloseStatus.EndpointUnavailable, "going away", CancellationToken.None).Wait(5_000); }
                catch (AggregateException) { }
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
