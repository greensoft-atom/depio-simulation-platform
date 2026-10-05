using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>A clock the test moves.</summary>
    internal sealed class ManualClock : IClock
    {
        public long NowMs { get; set; } = 1_000_000;
    }

    /// <summary>
    /// An arena that does what the test says, on a loopback socket: what the real arena cannot be
    /// made to do on demand (a kick of each reason, a broken frame, a close) is scripted here. The
    /// real arena is the headless client's job.
    /// </summary>
    internal sealed class FakeArena : IDisposable
    {
        private readonly TcpListener _listener = new TcpListener(IPAddress.Loopback, 0);
        private readonly List<Socket> _accepted = new List<Socket>();
        public readonly BlockingCollection<(int connection, byte[] frame)> Received = new BlockingCollection<(int, byte[])>();

        public FakeArena()
        {
            _listener.Start();
            new Thread(Accept) { IsBackground = true }.Start();
        }

        public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

        public int Connections
        {
            get { lock (_accepted) return _accepted.Count; }
        }

        private void Accept()
        {
            try
            {
                while (true)
                {
                    Socket s = _listener.AcceptSocket();
                    int index;
                    lock (_accepted)
                    {
                        _accepted.Add(s);
                        index = _accepted.Count - 1;
                    }
                    new Thread(() => Read(s, index)) { IsBackground = true }.Start();
                }
            }
            // Everything, not only the socket's own errors: a test that disposes this before the
            // thread has started makes AcceptSocket throw InvalidOperationException, and anything
            // unhandled on a background thread ends the whole test run, part-way, reported as a
            // pass of the tests that had run.
            catch (Exception) { }
        }

        private void Read(Socket s, int index)
        {
            var frames = new FrameReader();
            var buffer = new byte[4096];
            try
            {
                int n;
                while ((n = s.Receive(buffer)) > 0)
                    frames.Feed(new ReadOnlySpan<byte>(buffer, 0, n), f => Received.Add((index, f)));
            }
            catch (Exception) { }       // as above
        }

        /// <summary>The next frame the client sent, on any connection.</summary>
        public (int connection, byte[] frame) Next()
        {
            Assert.That(Received.TryTake(out var item, 5_000), Is.True, "the client sent nothing");
            return item;
        }

        /// <summary>Every frame sent so far, taken; waits a moment for stragglers.</summary>
        public List<byte[]> DrainAll()
        {
            Thread.Sleep(100);
            var all = new List<byte[]>();
            while (Received.TryTake(out var item)) all.Add(item.frame);
            return all;
        }

        /// <summary>Every frame sent so far, taken, keeping those of one type.</summary>
        public List<byte[]> Drain(int type) => DrainAll().Where(f => f[0] == type).ToList();

        public void Send(int connection, byte[] body)
        {
            var framed = new List<byte>();
            FrameBuilder.Varint(framed, body.Length);
            framed.AddRange(body);
            Socket s;
            lock (_accepted) s = _accepted[connection];
            s.Send(framed.ToArray());
        }

        public void Close(int connection)
        {
            lock (_accepted) _accepted[connection].Close();
        }

        public static byte[] Welcome(string secret, long selfEntityId = 77, long mazeSeed = 0)
        {
            var b = new List<byte> { Wire.MsgWelcome, Wire.SelfHandle, 15 };
            FrameBuilder.Varint(b, 5700); FrameBuilder.Varint(b, 5700);
            b.Add(0);
            FrameBuilder.Varint(b, 1); FrameBuilder.Varint(b, 1); FrameBuilder.Varint(b, selfEntityId);
            FrameBuilder.Varint(b, secret.Length);
            b.AddRange(System.Text.Encoding.ASCII.GetBytes(secret));
            if (mazeSeed != 0) FrameBuilder.Varint(b, mazeSeed);
            return b.ToArray();
        }

        public void Dispose()
        {
            _listener.Stop();
            lock (_accepted) foreach (var s in _accepted) s.Close();
        }
    }

    public class MatchConnectionTests
    {
        private static MatchConnection Connect(FakeArena arena, ManualClock clock) =>
            new MatchConnection(new MatchSettings { Host = "127.0.0.1", Port = arena.Port }, clock);

        /// <summary>Polls until the condition holds, as a frame loop would; the clock is the test's.</summary>
        private static void PollUntil(MatchConnection m, Func<bool> done, string what)
        {
            for (int i = 0; i < 500; i++)
            {
                m.Poll();
                if (done()) return;
                Thread.Sleep(10);
            }
            Assert.Fail($"never: {what} (state {m.State}, end {m.End}, {m.LastProblem})");
        }

        /// <summary>Joined, welcomed, and holding its own tank.</summary>
        private static MatchConnection InMatch(FakeArena arena, ManualClock clock, string secret = "first-secret")
        {
            var m = Connect(arena, clock);
            m.Join("TicketNumberOne");
            PollUntil(m, () => m.State == MatchState.Joining, "joining");
            var join = arena.Next();
            Assert.That(join.frame.Take(3), Is.EqualTo(new byte[] { Wire.MsgJoin, Wire.Version, 15 }));
            arena.Send(join.connection, FakeArena.Welcome(secret));
            arena.Send(join.connection, new FrameBuilder(5).Tank(Wire.SelfHandle, 0, 0, "Me").Build());
            PollUntil(m, () => m.Alive, "alive");
            return m;
        }

        [Test]
        public void AnArenaGoneSilentIsLostAndResumedNotWaitedOnForMinutes()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            clock.NowMs += MatchConnection.SilenceMs - 1;
            m.Poll();
            Assert.That(m.State, Is.EqualTo(MatchState.InMatch), "within its time");
            arena.Send(0, new FrameBuilder(6).Build());
            PollUntil(m, () => m.SnapshotsApplied == 2, "a frame");
            clock.NowMs += MatchConnection.SilenceMs - 1;
            m.Poll();
            Assert.That(m.State, Is.EqualTo(MatchState.InMatch), "a frame heard restarts the wait");
            clock.NowMs += 2;
            m.Poll();
            Assert.That(m.State, Is.EqualTo(MatchState.Lost), "silent though its socket is open: a machine gone, a path cut");
            Assert.That(m.LastProblem, Does.Contain("no frame"));
            clock.NowMs += 1_000;
            PollUntil(m, () => arena.Connections == 2, "dialled again, to resume");
        }

        /// <summary>The frame a death comes in: the tick moves, and the Death event; after it, events alone (P-53).</summary>
        private static byte[] DeathFrame() => new FrameBuilder(6).Event(Wire.EvtDeath, new byte[] { 5, 0 }).Build();

        [Test]
        public void ADeadPlayerSentNoFramesIsNotLostWhileItsPingsAreAnswered()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            arena.Send(0, DeathFrame());
            PollUntil(m, () => m.SnapshotsApplied == 2, "the death");
            clock.NowMs += MatchConnection.PingEveryMs;
            m.Poll();
            Assert.That(m.State, Is.EqualTo(MatchState.InMatch),
                "dead, the arena sends no frames: waiting to respawn, or for co-op's next wave, is not a loss");
            arena.Send(0, new byte[] { Wire.MsgPong, 0, 0, 0, 0, 9 });
            PollUntil(m, () => m.RoundTripMs >= 0, "a ping answered");
            clock.NowMs += MatchConnection.PingEveryMs + MatchConnection.SilenceMs - 1;
            m.Poll();
            Assert.That(m.State, Is.EqualTo(MatchState.InMatch), "an answer heard restarts the wait");
            clock.NowMs += 2;
            m.Poll();
            Assert.That(m.State, Is.EqualTo(MatchState.Lost), "its ping unanswered past its time: the path is gone");
        }

        [Test]
        public void ADeadPlayerIsNotAliveThoughItsTankIsNeverRemoved()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            arena.Send(0, DeathFrame());
            PollUntil(m, () => m.SnapshotsApplied == 2, "the death");
            Assert.That(m.World[Wire.SelfHandle].Alive, Is.True, "the arena sends no remove of the own tank, only the Death");
            Assert.That(m.Alive, Is.False, "dead: the HUD's Respawn shows, no input is sent, nothing is predicted (P-54)");
            arena.Send(0, new FrameBuilder(1).Tank(Wire.SelfHandle, 10, 10, "Me").Build());
            PollUntil(m, () => m.SnapshotsApplied == 3, "respawned");
            Assert.That(m.Alive, Is.True, "the world's ticks again: alive");
        }

        [Test]
        public void ARespawnedPlayerIsHeldToTheFramesAgain()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            arena.Send(0, DeathFrame());
            arena.Send(0, new FrameBuilder(0).Event(Wire.EvtDeath, new byte[] { 5, 0 }).Build());
            PollUntil(m, () => m.SnapshotsApplied == 3, "the death, then events alone");
            clock.NowMs += MatchConnection.SilenceMs + 1;
            m.Poll();
            Assert.That(m.State, Is.EqualTo(MatchState.InMatch), "still dead: a frame of events alone moves no tick");
            arena.Send(0, new FrameBuilder(1).Build());
            PollUntil(m, () => m.SnapshotsApplied == 4, "respawned: the world's ticks again");
            clock.NowMs += MatchConnection.SilenceMs + 1;
            m.Poll();
            Assert.That(m.State, Is.EqualTo(MatchState.Lost), "alive again, silent 3 s: lost");
        }

        [Test]
        public void ABackgroundedMatchIsNotLostForTheFramesItIsNotSent()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            m.SetBackgrounded(true);
            clock.NowMs += MatchConnection.SilenceMs * 10;
            m.Poll();
            Assert.That(m.State, Is.EqualTo(MatchState.InMatch));
            m.SetBackgrounded(false);
            m.Poll();
            Assert.That(m.State, Is.EqualTo(MatchState.InMatch), "back in front: its wait starts now");
            clock.NowMs += MatchConnection.SilenceMs + 1;
            m.Poll();
            Assert.That(m.State, Is.EqualTo(MatchState.Lost));
        }

        [Test]
        public void AHandshakeThatNeverEndsIsGivenUpOn()
        {
            var silent = new TcpListener(IPAddress.Loopback, 0);     // takes the connection, never says a word
            silent.Start();
            try
            {
                var clock = new ManualClock();
                using var m = new MatchConnection(new MatchSettings
                {
                    Host = "127.0.0.1", Port = ((IPEndPoint)silent.LocalEndpoint).Port, Tls = true,
                }, clock);
                m.Join("TicketNumberOne");
                Thread.Sleep(200);
                m.Poll();
                Assert.That(m.State, Is.EqualTo(MatchState.Connecting), "the TLS handshake waits on the server");
                clock.NowMs += MatchConnection.ConnectMs + 1;
                m.Poll();
                Assert.That((m.State, m.End), Is.EqualTo((MatchState.Ended, MatchEnd.Unreachable)));
            }
            finally
            {
                silent.Stop();
            }
        }

        [Test]
        public void AJoinNeverWelcomedIsGivenUpOn()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = Connect(arena, clock);
            m.Join("TicketNumberOne");
            PollUntil(m, () => m.State == MatchState.Joining, "joining");
            clock.NowMs += MatchConnection.JoinMs - 1;
            m.Poll();
            Assert.That(m.State, Is.EqualTo(MatchState.Joining));
            clock.NowMs += 2;
            m.Poll();
            Assert.That((m.State, m.End), Is.EqualTo((MatchState.Ended, MatchEnd.Unreachable)));
        }

        [Test]
        public void ATicketOrSecretThatCannotBeSentIsRefusedBeforeAnythingIsDialled()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            foreach (string bad in new[] { null, "", "has a space", new string('x', 65) })
            {
                using var m = Connect(arena, clock);
                Assert.Throws<ArgumentException>(() => m.Join(bad), $"join with '{bad}'");
                using var r = Connect(arena, clock);
                Assert.Throws<ArgumentException>(() => r.Resume(bad), $"resume with '{bad}'");
            }
            Thread.Sleep(100);
            Assert.That(arena.Connections, Is.Zero, "not dialled to fail on every Poll after");
        }

        [Test]
        public void AMazesWelcomeGivesTheWorldItsWalls()
        {
            using var arena = new FakeArena();
            using var m = Connect(arena, new ManualClock());
            m.Join("TicketNumberOne");
            PollUntil(m, () => m.State == MatchState.Joining, "joining");
            arena.Send(arena.Next().connection, FakeArena.Welcome("s", mazeSeed: 2026));
            PollUntil(m, () => m.State == MatchState.InMatch, "in the match");
            Assert.That(m.World.Walls, Is.EqualTo(Maze.Walls(2026)));
        }

        [TestCase(Wire.KickBadTicket, MatchEnd.BadTicket)]
        [TestCase(Wire.KickRoomFull, MatchEnd.RoomFull)]
        [TestCase(Wire.KickProtocolVersion, MatchEnd.ProtocolVersion)]
        [TestCase(Wire.KickRateLimit, MatchEnd.RateLimit)]
        [TestCase(Wire.KickInternal, MatchEnd.ServerFault)]
        [TestCase(Wire.KickMatchOver, MatchEnd.MatchOver)]
        [TestCase(Wire.KickRemoved, MatchEnd.Removed)]
        [TestCase(99, MatchEnd.ServerFault)]
        public void EachKickEndsTheConnectionWithItsOwnNextStep(int reason, MatchEnd end)
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            arena.Send(0, new byte[] { Wire.MsgKick, (byte)reason });
            PollUntil(m, () => m.State == MatchState.Ended, "ended");
            Assert.That(m.End, Is.EqualTo(end));
            Thread.Sleep(200);
            m.Poll();
            Assert.That(arena.Connections, Is.EqualTo(1), "a kick is never answered by dialling again");
        }

        /// <summary>A raw arena, reading only when the test says: the client joined and welcomed.</summary>
        private static (MatchConnection m, Socket arena) RawArenaInMatch()
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            var m = new MatchConnection(new MatchSettings { Host = "127.0.0.1", Port = ((IPEndPoint)listener.LocalEndpoint).Port },
                new ManualClock());
            m.Join("TicketNumberOne");
            Socket arena = listener.AcceptSocket();
            listener.Stop();
            arena.ReceiveTimeout = 5_000;
            PollUntil(m, () => m.State == MatchState.Joining, "joining");
            Assert.That(ReadUntil(arena, f => f[0] == Wire.MsgJoin), Is.True, "the Join");
            arena.Send(Framed(FakeArena.Welcome("s")));
            PollUntil(m, () => m.State == MatchState.InMatch, "in the match");
            return (m, arena);
        }

        private static byte[] Framed(byte[] body)
        {
            var framed = new List<byte>();
            FrameBuilder.Varint(framed, body.Length);
            framed.AddRange(body);
            return framed.ToArray();
        }

        /// <returns>whether a frame the test wants came before the connection ended</returns>
        private static bool ReadUntil(Socket arena, Func<byte[], bool> wanted)
        {
            var frames = new FrameReader();
            var buffer = new byte[16 * 1024];
            bool found = false;
            try
            {
                int n;
                while (!found && (n = arena.Receive(buffer)) > 0)
                    frames.Feed(new ReadOnlySpan<byte>(buffer, 0, n), f => found |= wanted(f));
            }
            catch (SocketException) { }
            return found;
        }

        /// <summary>
        /// An arena busy elsewhere, writing all the while and reading late, as a loaded one is. The
        /// client that leaves keeps its end open until the arena closes it: closed at once, with
        /// frames unread, it would be reset, and the arena, writing, would meet the reset before it
        /// read the Leave and keep the stay a minute for a resume (P-34).
        /// </summary>
        [Test]
        public void ALeaveWaitsForTheArenaToClose()
        {
            var (m, arena) = RawArenaInMatch();
            try
            {
                var stop = new CancellationTokenSource();
                Exception refused = null;
                byte[] noise = Framed(Enumerable.Repeat((byte)99, 1_000).ToArray());   // a type this client passes over
                var flood = new Thread(() =>
                {
                    try { while (!stop.IsCancellationRequested) arena.Send(noise); }
                    catch (Exception e) { refused = e; }
                });
                flood.Start();
                Thread.Sleep(50);                               // frames on their way, unread
                m.Leave();
                m.Dispose();                                    // at once, as a using does
                Assert.That((m.State, m.End), Is.EqualTo((MatchState.Ended, MatchEnd.Left)), "left, for the caller, at once");
                Thread.Sleep(300);                              // the arena busy, reading nothing
                stop.Cancel();
                flood.Join();
                Assert.That(refused, Is.Null, "the client's end stayed open while the arena was busy");
                Assert.That(ReadUntil(arena, f => f[0] == Wire.MsgLeave), Is.True, "and the arena reads the Leave");
                var closing = Stopwatch.StartNew();
                arena.Shutdown(SocketShutdown.Send);            // the arena's close, as the client sees it
                Assert.That(ReadUntil(arena, f => false), Is.False);
                Assert.That(closing.ElapsedMilliseconds, Is.LessThan(MatchConnection.LeaveLingerMs / 2),
                    "and the client closes as soon as the arena has, not at the end of its wait");
            }
            finally
            {
                arena.Close();
            }
        }

        [Test]
        public void ALeaveClosesAfterItsWaitIfTheArenaNeverDoes()
        {
            var (m, arena) = RawArenaInMatch();
            try
            {
                var clock = Stopwatch.StartNew();
                m.Leave();
                arena.ReceiveTimeout = MatchConnection.LeaveLingerMs + 3_000;
                Assert.That(ReadUntil(arena, f => false), Is.False, "the connection ends");
                Assert.That(clock.ElapsedMilliseconds, Is.InRange(MatchConnection.LeaveLingerMs - 100, MatchConnection.LeaveLingerMs + 2_000),
                    "after the wait, not at once and not never");
            }
            finally
            {
                arena.Close();
            }
        }

        [Test]
        public void AnArenaThatCannotBeReachedIsUnreachableNotRetried()
        {
            int deadPort;
            using (var probe = new FakeArena()) deadPort = probe.Port;       // closed again at once
            var m = new MatchConnection(new MatchSettings { Host = "127.0.0.1", Port = deadPort }, new ManualClock());
            m.Join("TicketNumberOne");
            PollUntil(m, () => m.State == MatchState.Ended, "ended");
            Assert.That(m.End, Is.EqualTo(MatchEnd.Unreachable));
        }

        [Test]
        public void ALostConnectionIsResumedWithTheLatestSecretAndAFreshWorld()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock, secret: "secret-from-welcome");
            ClientWorld before = m.World;
            arena.Close(0);
            PollUntil(m, () => m.State == MatchState.Lost, "lost");
            Assert.That(arena.Connections, Is.EqualTo(1), "not at once: after the first delay");

            clock.NowMs += 500;
            PollUntil(m, () => m.State == MatchState.Joining, "resuming");
            var resume = arena.Drain(Wire.MsgResume).Single();
            Assert.That(resume.Skip(3).Take(19), Is.EqualTo(System.Text.Encoding.ASCII.GetBytes("secret-from-welcome")));
            arena.Send(1, FakeArena.Welcome("a-new-secret"));
            PollUntil(m, () => m.State == MatchState.InMatch, "back");
            Assert.That(m.World, Is.Not.SameAs(before), "a resume starts a fresh view");
            Assert.That(m.Resumes, Is.EqualTo(1));
            Assert.That(m.Welcome.ResumeSecret, Is.EqualTo("a-new-secret"));
        }

        [Test]
        public void AColdResumeSendsTheKeptSecretInPlaceOfAJoin()
        {
            using var arena = new FakeArena();
            // A new connection, as after the app was killed and restarted, knowing only what it kept.
            using var m = Connect(arena, new ManualClock());
            m.Resume("kept-on-the-device");
            PollUntil(m, () => m.State == MatchState.Joining, "resuming");
            var first = arena.Next();
            Assert.That(first.frame.Take(2), Is.EqualTo(new byte[] { Wire.MsgResume, Wire.Version }), "a Resume, not a Join");
            Assert.That(first.frame.Skip(3).Take(18), Is.EqualTo(System.Text.Encoding.ASCII.GetBytes("kept-on-the-device")));
            arena.Send(first.connection, FakeArena.Welcome("the-next-one"));
            PollUntil(m, () => m.State == MatchState.InMatch, "back in its stay");
            Assert.That(m.Welcome.ResumeSecret, Is.EqualTo("the-next-one"), "the one to keep now");
            Assert.Throws<InvalidOperationException>(() => m.Resume("again"), "one start per connection");
        }

        [Test]
        public void AColdResumeOfAStayOverGoesBackToTheLobby()
        {
            using var arena = new FakeArena();
            using var m = Connect(arena, new ManualClock());
            m.Resume("spent");
            PollUntil(m, () => m.State == MatchState.Joining, "resuming");
            arena.Send(arena.Next().connection, new byte[] { Wire.MsgKick, Wire.KickBadTicket });
            PollUntil(m, () => m.State == MatchState.Ended, "ended");
            Assert.That(m.End, Is.EqualTo(MatchEnd.BadTicket));
        }

        [Test]
        public void AColdResumeThatFindsNoArenaKeepsTryingForTheMinute()
        {
            var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = new MatchConnection(new MatchSettings { Host = "127.0.0.1", Port = arena.Port }, clock);
            arena.Dispose();                        // gone, as a stopped arena is
            m.Resume("kept");
            PollUntil(m, () => m.State == MatchState.Lost, "lost, not ended: the stay may still be there");
            clock.NowMs += 60_000;
            PollUntil(m, () => m.State == MatchState.Ended, "given up");
            Assert.That(m.End, Is.EqualTo(MatchEnd.ResumeExpired));
        }

        [Test]
        public void AFrameThatCannotBeAppliedIsADesyncAndIsResumed()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            arena.Send(0, new FrameBuilder(1).Move(42, 1, 1).Build());      // a handle it does not hold
            PollUntil(m, () => m.State == MatchState.Lost, "lost");
            Assert.That(m.LastProblem, Does.StartWith("desync"));
            clock.NowMs += 500;
            PollUntil(m, () => arena.Connections == 2, "dialled again");
        }

        [Test]
        public void ResumesBackOffAndGiveUpAfterAMinute()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            arena.Dispose();                        // the arena is gone, and stays gone
            PollUntil(m, () => m.State == MatchState.Lost, "lost");
            long lostAt = clock.NowMs;
            var tries = new List<long>();
            while (m.State != MatchState.Ended && clock.NowMs - lostAt < 70_000)
            {
                MatchState was = m.State;
                m.Poll();
                if (was == MatchState.Lost && m.State == MatchState.Connecting)
                {
                    tries.Add(clock.NowMs - lostAt);
                    PollUntil(m, () => m.State != MatchState.Connecting, "the attempt fails");
                    continue;
                }
                clock.NowMs += 100;
            }
            Assert.That(m.End, Is.EqualTo(MatchEnd.ResumeExpired));
            Assert.That(tries.Take(5), Is.EqualTo(new long[] { 500, 1_500, 3_500, 7_500, 15_500 }),
                "each wait twice the last, from half a second");
            Assert.That(tries.Skip(4).Zip(tries.Skip(5), (a, b) => b - a), Is.All.EqualTo(8_000), "then every 8 s");
            Assert.That(clock.NowMs - lostAt, Is.InRange(60_000, 60_100), "and not past the minute a stay waits");
        }

        [Test]
        public void InputGoesOutTenTimesASecondWhileAliveCarryingTheAckAndTheLatchedFire()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            arena.DrainAll();

            // A tap between two sends: pressed and released before the next input goes out.
            m.SetInput(Wire.MoveUp, 1234, true, Wire.FlagAutofire);
            m.SetInput(Wire.MoveUp, 1234, false, Wire.FlagAutofire);
            for (int i = 0; i < 10; i++)
            {
                clock.NowMs += 100;
                m.Poll();
            }
            var inputs = arena.Drain(Wire.MsgInput);
            Assert.That(inputs, Has.Count.InRange(10, 11), "ten in a second");
            var r = new WireReader(inputs[0]);
            r.U8();
            r.Varint();                              // seq
            Assert.That(r.Varint(), Is.EqualTo(5), "the ack: the tick of the last frame applied");
            Assert.That(r.U8(), Is.EqualTo(Wire.MoveUp));
            Assert.That(r.U16(), Is.EqualTo(1234));
            Assert.That(r.U8(), Is.EqualTo(Wire.FlagAutofire | Wire.FlagFire), "the tap was not lost");
            Assert.That(inputs.Last()[inputs.Last().Length - 1], Is.EqualTo(Wire.FlagAutofire), "and was sent once");

            m.SetBackgrounded(true);
            for (int i = 0; i < 10; i++)
            {
                clock.NowMs += 100;
                m.Poll();
            }
            var sent = arena.DrainAll();
            Assert.That(sent.Where(f => f[0] == Wire.MsgLifecycle).Select(f => f[1]),
                Is.EqualTo(new byte[] { Wire.LifecycleBackground }));
            Assert.That(sent.Where(f => f[0] == Wire.MsgInput), Is.Empty, "none in the background");
        }

        [Test]
        public void TheOwnTankMovesAtOnceItsStepsStampedWithTheSeqThatCarriesThemAndTheInputSent()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);                 // its first input, seq 1, went out now
            Assert.That(m.OwnTank.Predicting, Is.False, "no rule yet: drawn where the server has it");
            arena.Send(0, new FrameBuilder(1).Move(Wire.SelfHandle, 8_000, 8_000).MotionRule(0.16f, 30f).Motion(0, 0, 0).Build());
            PollUntil(m, () => m.OwnTank.Predicting, "predicting");
            var start = new MotionState { X = 2_000f, Y = 2_000f };
            Assert.That((m.OwnTank.X, m.OwnTank.Y), Is.EqualTo((start.X, start.Y)), "from where the server put it");
            arena.DrainAll();

            // Steps 1 and 2 to the right, then a change of mind: step 3 down, and seq 2 goes out with it,
            // 100 ms after seq 1; steps 4 and 5 down, for seq 3.
            m.SetInput(Wire.MoveRight, 0, false, 0);
            for (int i = 1; i <= 5; i++)
            {
                clock.NowMs += OwnTank.StepMs;
                m.Poll();
                if (i == 2) m.SetInput(Wire.MoveDown, 0, false, 0);
            }
            Assert.That(m.OwnTank.X, Is.GreaterThan(2_000.2f), "moved at once, before any frame said so");
            Assert.That(m.World[Wire.SelfHandle].X / Wire.PosScale, Is.EqualTo(2_000f), "while the server's is where it was");
            var inputs = arena.Drain(Wire.MsgInput);
            var r = new WireReader(inputs.Single());
            r.U8();
            Assert.That(r.Varint(), Is.EqualTo(2), "one input in the 200 ms: seq 2");

            // The server applied seq 2, down, once by its frame's tick: the first step stamped 2.
            MotionState one = start;
            TankMotion.Step(ref one, Wire.MoveDown, 0.16f, 30f, 5_700f, 5_700f, null);
            int qy = (int)Math.Round(one.Y * Wire.PosScale), qvy = (int)Math.Round(one.Vy * Wire.VelocityScale);
            float error = float.NaN;
            m.OwnTank.OnReconciled = (e, c) => error = e;
            arena.Send(0, new FrameBuilder(2, seqDelta: 2).Move(Wire.SelfHandle, 0, qy - 8_000).Motion(1, 0, qvy).Build());
            PollUntil(m, () => m.OwnTank.Compared == 1, "the frame compared");
            var server = new MotionState { X = 2_000f, Y = qy / Wire.PosScale, Vy = qvy / Wire.VelocityScale };
            MotionState expected = server;
            for (int i = 2; i <= 5; i++) TankMotion.Step(ref expected, Wire.MoveDown, 0.16f, 30f, 5_700f, 5_700f, null);
            Assert.That((m.OwnTank.X, m.OwnTank.Y), Is.EqualTo((expected.X, expected.Y)),
                "steps 2 to 5 replayed down: seq 2's steps took the input it went out with");
            Assert.That(error, Is.EqualTo(0.29f).Within(0.02f), "step 1 was predicted to the right, a seventh of a unit");

            m.SetBackgrounded(true);
            clock.NowMs += OwnTank.StepMs;
            m.Poll();
            Assert.That(m.OwnTank.Predicting, Is.False, "parked: nothing predicted");
        }

        [Test]
        public void EveryFrameIsToldWithItsRemovalsNotOnlyTheLastPolled()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            arena.Send(0, new FrameBuilder(1).Bullet(7, 0, 0, 0, 10, 60).Bullet(8, 0, 0, 0, 10, 60).Build());
            PollUntil(m, () => m.SnapshotsApplied == 2, "two bullets");
            var removed = new List<int>();
            m.OnFrame = () => { foreach (Removal r in m.World.Removals) removed.Add(r.Handle); };
            arena.Send(0, new FrameBuilder(1).Remove(7).Build());
            arena.Send(0, new FrameBuilder(1).Remove(8).Build());
            Thread.Sleep(300);                          // both in the inbox before one poll
            m.Poll();
            Assert.That(removed, Is.EqualTo(new[] { 7, 8 }), "each frame's removals, while the world holds them (T-57)");
            Assert.That(m.World.Removals.Count, Is.EqualTo(1), "the world keeps the last frame's only, hence the callback");
        }

        [Test]
        public void EveryFramesEventsAreToldNotOnlyTheLastPolled()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            var heard = new List<(int, string)>();
            m.OnEvent = e => heard.Add((e.PhraseId, e.SpeakerName));
            byte[] Said(int id, string name)
            {
                var p = new List<byte> { 0 };
                FrameBuilder.Varint(p, id);
                p.Add((byte)name.Length);
                p.AddRange(System.Text.Encoding.UTF8.GetBytes(name));
                return new FrameBuilder(1).Event(Wire.EvtPhrase, p.ToArray()).Build();
            }
            arena.Send(0, Said(3, "ada"));
            arena.Send(0, Said(9, "bo"));
            Thread.Sleep(300);                          // both in the inbox before one poll (P-33)
            m.Poll();
            Assert.That(heard, Is.EqualTo(new[] { (3, "ada"), (9, "bo") }));
            Assert.That(m.World.EventCount, Is.EqualTo(1), "the world keeps the last frame's only, hence the callback");
        }

        [Test]
        public void APhraseSaidGoesOutAsItsId()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            arena.DrainAll();
            m.Say(200);
            Assert.That(arena.Drain(Wire.MsgPhrase), Is.EqualTo(new[] { new byte[] { Wire.MsgPhrase, 0xC8, 0x01 } }));
        }

        [Test]
        public void ASandboxsPowersGoOutAsAnActionAndAValue()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            arena.DrainAll();
            m.SetLevel(45);
            m.SummonGuardian();
            Assert.That(arena.Drain(Wire.MsgSandbox), Is.EqualTo(new[]
            {
                new byte[] { Wire.MsgSandbox, Wire.SandboxLevel, 45 },
                new byte[] { Wire.MsgSandbox, Wire.SandboxGuardian, 0 },
            }));
        }

        [Test]
        public void ASandboxsPowersAskedBeforeTheWelcomeAreNotSent()
        {
            using var arena = new FakeArena();
            using var m = Connect(arena, new ManualClock());
            m.Join("TicketNumberOne");
            PollUntil(m, () => m.State == MatchState.Joining, "joining");
            arena.Next();                                       // the Join
            m.SetLevel(45);                                     // the arena would keep it, and apply it late
            m.SummonGuardian();
            arena.Send(0, FakeArena.Welcome("s"));
            PollUntil(m, () => m.State == MatchState.InMatch, "in the match");
            m.Say(3);                                           // something sent after, to wait for
            byte[] f;
            while ((f = arena.Next().frame)[0] != Wire.MsgPhrase)
                Assert.That(f[0], Is.Not.EqualTo(Wire.MsgSandbox), "nothing asked before the Welcome went out");
        }

        [Test]
        public void APingGoesOutEveryTenSecondsInEveryStateAndMeasuresTheRoundTrip()
        {
            using var arena = new FakeArena();
            var clock = new ManualClock();
            using var m = InMatch(arena, clock);
            var first = arena.Drain(Wire.MsgPing);
            Assert.That(first, Has.Count.EqualTo(1), "one at once, on joining");
            arena.Send(0, new byte[] { Wire.MsgPong }.Concat(first[0].Skip(1).Take(4)).Concat(new byte[] { 9 }).ToArray());
            clock.NowMs += 40;
            PollUntil(m, () => m.RoundTripMs >= 0, "a round trip");
            Assert.That(m.RoundTripMs, Is.EqualTo(40));

            arena.Send(0, new FrameBuilder(1).Remove(Wire.SelfHandle).Build());   // dead: no input, still pings
            PollUntil(m, () => !m.Alive, "dead");
            // Ten seconds on, frames arriving as they do: ten silent seconds would be a connection lost.
            for (int i = 0; i < 5; i++)
            {
                long applied = m.SnapshotsApplied;
                clock.NowMs += 2_000;
                arena.Send(0, new FrameBuilder(2 + i).Build());
                PollUntil(m, () => m.SnapshotsApplied > applied, "a frame");
            }
            Assert.That(arena.Drain(Wire.MsgPing), Has.Count.EqualTo(1));
        }
    }
}
