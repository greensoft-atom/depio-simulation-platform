using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Threading;

namespace Backend.Client.Core
{
    /// <summary>Milliseconds on a clock that never goes backwards. A test's can be moved by hand.</summary>
    public interface IClock
    {
        long NowMs { get; }
    }

    public sealed class SystemClock : IClock
    {
        private readonly Stopwatch _watch = Stopwatch.StartNew();
        public long NowMs => _watch.ElapsedMilliseconds;
    }

    public enum MatchState { Connecting, Joining, InMatch, Lost, Ended }

    /// <summary>Why a connection ended: each has its own next step (docs 08 §3).</summary>
    public enum MatchEnd
    {
        None,
        /// <summary>The player left, on purpose.</summary>
        Left,
        /// <summary>Kick(1): the ticket, or the stay a resume named, is unknown. Back to the lobby.</summary>
        BadTicket,
        /// <summary>Kick(2): no room had space. Ask for another ticket after 2 s.</summary>
        RoomFull,
        /// <summary>Kick(3): this app is too old or too new. Never retry.</summary>
        ProtocolVersion,
        /// <summary>Kick(4): this client sent too much. A bug; do not loop.</summary>
        RateLimit,
        /// <summary>Kick(5): the server's fault. Back through the lobby, with backoff.</summary>
        ServerFault,
        /// <summary>The first connection could not be made. Back to the lobby.</summary>
        Unreachable,
        /// <summary>A minute passed without a resume getting through. Back to the lobby.</summary>
        ResumeExpired,
        /// <summary>Kick(6): the match was over. Back to the lobby, where its result lands; not an error.</summary>
        MatchOver,
        /// <summary>Kick(7): an operator closed the room or took the player out. Back to the lobby; nothing to reconnect to.</summary>
        Removed,
    }

    /// <summary>Where and how to reach one arena, from a match grant.</summary>
    public sealed class MatchSettings
    {
        public string Host;
        public int Port;
        /// <summary>The grant says whether this arena speaks TLS; the client obeys.</summary>
        public bool Tls;
        /// <summary>
        /// Null: the device's trust store and the grant's host name, which is what players get.
        /// A test sets its own, to trust a test CA; nothing is pinned either way (03 §2).
        /// </summary>
        public RemoteCertificateValidationCallback CertificateCheck;
        /// <summary>The most this client wants sent (02 §8), or -1 for the server's default.</summary>
        public int Profile = -1;
    }

    /// <summary>
    /// One player's connection to one arena: join, play, lose it, resume it, leave (02 §1, §3, §9,
    /// §10; 08 §3).
    ///
    /// Sockets are read on a thread of their own, one per attempt, which only frames bytes and
    /// queues them. Everything else happens in <see cref="Poll"/>, on the main thread: frames are
    /// applied to the world there, timers run there, and messages are sent from there. A send is
    /// a few bytes against a kernel buffer that a dead link takes minutes to fill, so it does not
    /// block the frame it is sent from.
    /// </summary>
    public sealed class MatchConnection : IDisposable
    {
        public const int PingEveryMs = 10_000;       // in every state: 30 s of silence is closed (02 §1)
        public const int InputEveryMs = 100;         // ten a second, alive and in the foreground (02 §9)
        public const int ResumeForMs = 60_000;       // a stay waits a minute for its player (02 §10)
        /// <summary>
        /// No frame for this long, alive in a match and in the foreground: lost, though the socket says nothing. Dead, a
        /// ping's period longer, the arena answering pings only.
        /// </summary>
        public const int SilenceMs = 3_000;
        /// <summary>The longest a dial and its TLS handshake may take.</summary>
        public const int ConnectMs = 5_000;
        /// <summary>The longest a Join or Resume waits for its Welcome: the arena's own deadline (02 §1).</summary>
        public const int JoinMs = 10_000;
        private static readonly int[] ResumeDelaysMs = { 500, 1_000, 2_000, 4_000, 8_000 };

        private readonly MatchSettings _settings;
        private readonly IClock _clock;
        private readonly ClientMessages _messages = new ClientMessages();

        private Attempt _attempt;
        private string _ticketId;
        private long _lostAt = -1, _nextResumeAt;
        private int _resumeTries;
        private long _nextPingAt, _nextInputAt;
        private long _stateSince, _heardAt;
        private bool _dead;

        private int _seq;
        private int _move, _aim, _flags;
        private bool _fireLatched, _backgrounded;

        public MatchConnection(MatchSettings settings, IClock clock)
        {
            _settings = settings ?? throw new ArgumentNullException(nameof(settings));
            _clock = clock ?? throw new ArgumentNullException(nameof(clock));
        }

        public MatchState State { get; private set; } = MatchState.Connecting;
        public MatchEnd End { get; private set; }
        /// <summary>A fresh world with every Welcome: a resume starts a fresh view (02 §10).</summary>
        public ClientWorld World { get; private set; } = new ClientWorld();
        /// <summary>The own tank, predicted (02 §9, 08 §4): what the layer above draws it by.</summary>
        public OwnTank OwnTank { get; } = new OwnTank();
        public Welcome Welcome { get; private set; }
        public long SnapshotsApplied { get; private set; }
        public int Resumes { get; private set; }
        /// <summary>The last round trip a Pong measured, or -1.</summary>
        public long RoundTripMs { get; private set; } = -1;
        /// <summary>What went wrong last, for the log: a desync's message, a socket's error.</summary>
        public string LastProblem { get; private set; }

        /// <summary>
        /// Whether the own tank is in the world: inputs go out only then (02 §9). Not from the Death until a respawn:
        /// the arena sends the dead no remove of their tank, only the Death (P-54).
        /// </summary>
        public bool Alive => State == MatchState.InMatch && !_dead && World[Wire.SelfHandle].Alive;

        /// <summary>
        /// Every event of every frame, as the frame is applied: deaths, stats, phrases. World keeps
        /// only the last frame's, and one Poll can apply several, after any stall (P-33). The event
        /// is reused by the next frame: copy what is kept. Main thread, inside Poll.
        /// </summary>
        public Action<MatchEvent> OnEvent;

        /// <summary>
        /// After each frame is applied, while <see cref="World"/> holds that frame's removals and events: a
        /// <see cref="Poll"/> applies every frame waiting, and the world keeps the last one's only (T-57).
        /// </summary>
        public Action OnFrame;

        /// <summary>Dials the arena and joins with a single-use ticket from a match grant.</summary>
        public void Join(string ticketId)
        {
            if (_attempt != null) throw new InvalidOperationException("already started");
            // Here, not when Poll sends it: thrown there, every Poll after threw again (client review, 2026-10-04).
            ClientMessages.CheckCredential(ticketId);
            _ticketId = ticketId;
            Dial();
        }

        /// <summary>
        /// A cold resume (02 §10, D-51): after the app was killed and restarted, dials the arena of the
        /// stay it kept, the settings carrying the grant's address, and resumes with the secret of the
        /// latest Welcome it saw. It goes on as after any lost connection: tried again for the minute a
        /// stay waits, and a stay already over is <see cref="MatchEnd.BadTicket"/>, back to the lobby.
        /// </summary>
        public void Resume(string resumeSecret)
        {
            if (_attempt != null) throw new InvalidOperationException("already started");
            ClientMessages.CheckCredential(resumeSecret);
            Welcome = new Welcome { ResumeSecret = resumeSecret };
            Dial();
        }

        /// <summary>
        /// The player's current input, every frame. Move and aim are the latest; a fire press is
        /// latched until the next input goes out, so a tap between two sends is not lost.
        /// </summary>
        public void SetInput(int moveMask, int aim, bool fire, int flags)
        {
            _move = moveMask;
            _aim = aim;
            _flags = flags & ~Wire.FlagFire;
            _fireLatched |= fire;
        }

        /// <summary>The app went to the background or came back (02 §10).</summary>
        public void SetBackgrounded(bool backgrounded)
        {
            if (_backgrounded == backgrounded) return;
            _backgrounded = backgrounded;
            if (!backgrounded) _heardAt = _clock.NowMs;   // nothing was sent while away: the wait starts now
            if (State == MatchState.InMatch) Send(_messages.Lifecycle(backgrounded));
        }

        public void Respawn()
        {
            if (State == MatchState.InMatch) Send(_messages.Respawn());
        }

        public void UpgradeStat(int statIndex)
        {
            if (State == MatchState.InMatch) Send(_messages.UpgradeStat(statIndex));
        }

        /// <summary>A class the tank cannot have yet is refused silently; the create or update says what it is.</summary>
        public void ChooseClass(int classId)
        {
            if (State == MatchState.InMatch) Send(_messages.ChooseClass(classId));
        }

        /// <summary>
        /// Says a phrase from the list (docs 01 §9). One every two seconds is heard; one sooner, or an
        /// id the server's list lacks, is dropped without a word, so a repeated tap costs nothing.
        /// </summary>
        public void Say(int phraseId)
        {
            if (State == MatchState.InMatch) Send(_messages.Phrase(phraseId));
        }

        /// <summary>
        /// In a sandbox (01 §8.10): rebuilds the own tank at a level from 1 to 45, Basic with every
        /// point unspent. Anywhere else, or out of range, the arena drops it.
        /// </summary>
        public void SetLevel(int level)
        {
            if (State == MatchState.InMatch) Send(_messages.Sandbox(Wire.SandboxLevel, level));
        }

        /// <summary>In a sandbox: summons a Guardian, co-op's boss, unless one is alive already.</summary>
        public void SummonGuardian()
        {
            if (State == MatchState.InMatch) Send(_messages.Sandbox(Wire.SandboxGuardian, 0));
        }

        /// <summary>Ends the stay at once. Without it the tank waits a minute for a resume.</summary>
        public void Leave()
        {
            if (State == MatchState.Ended) return;
            if (State != MatchState.InMatch)
            {
                Finish(MatchEnd.Left);
                return;
            }
            Send(_messages.Leave());
            End = MatchEnd.Left;
            State = MatchState.Ended;
            // Closed once the arena has closed, which it does on reading the Leave: closed at once,
            // with frames unread, the socket would be reset, and the arena, writing, would meet the
            // reset before it read the Leave, and keep the stay a minute for a resume (P-34).
            _attempt?.CloseWhenPeerDoes(LeaveLingerMs);
        }

        /// <summary>How long a Leave waits for the arena to close before closing anyway.</summary>
        public const int LeaveLingerMs = 2_000;

        /// <summary>
        /// The main thread's turn: applies what has arrived, then pings, sends input and retries a
        /// lost connection as due. Call once a frame.
        /// </summary>
        public void Poll()
        {
            if (State == MatchState.Ended) return;
            long now = _clock.NowMs;
            Attempt a = _attempt;

            if (a != null && State == MatchState.Connecting && a.Connected)
            {
                Send(_ticketId != null && Welcome.ResumeSecret == null
                    ? _messages.Join(_ticketId, _settings.Profile)
                    : _messages.Resume(Welcome.ResumeSecret, _settings.Profile));
                State = MatchState.Joining;
                _stateSince = now;
                _nextPingAt = now;                      // the first at once: a round trip is useful early
            }

            while (a != null && State != MatchState.Ended && a.Inbox.TryDequeue(out byte[] frame))
            {
                _heardAt = now;
                if (!Handle(frame, now)) break;
            }

            if (a != null && a.Closed && a.Inbox.IsEmpty && (State == MatchState.Connecting
                    || State == MatchState.Joining || State == MatchState.InMatch))
            {
                LastProblem = a.Failure?.Message ?? "the connection closed";
                if (State == MatchState.InMatch || Welcome.ResumeSecret != null) Lose(now);
                else Finish(MatchEnd.Unreachable);        // never joined: nothing to resume
            }

            // What no socket reports: a machine gone or a path cut leaves the socket open for minutes, and a dial or
            // a handshake to one waits as long (08 §3). Lost, and resumed, within the minute a stay is kept. A dead
            // tank's player is sent no frames, by design, only its events and the answers to its pings: then a ping's
            // period and its answer, or the wait for a respawn or co-op's next wave is taken for a loss, and the
            // resume respawns it (P-53).
            long silence = _dead ? PingEveryMs + SilenceMs : SilenceMs;
            if (State == MatchState.InMatch && !_backgrounded && now - _heardAt > silence)
                GiveUp("no frame for " + silence / 1000 + " s", now);
            else if (State == MatchState.Connecting && now - _stateSince > ConnectMs)
                GiveUp("not connected in " + ConnectMs / 1000 + " s", now);
            else if (State == MatchState.Joining && now - _stateSince > JoinMs)
                GiveUp("no Welcome in " + JoinMs / 1000 + " s", now);

            if (State == MatchState.Lost) TryResume(now);

            if ((State == MatchState.Joining || State == MatchState.InMatch) && now >= _nextPingAt)
            {
                Send(_messages.Ping((uint)now));
                _nextPingAt = now + PingEveryMs;
            }

            // The own tank's steps due, stamped with the seq the input held will go out under (D-62).
            if (Alive && !_backgrounded) OwnTank.Advance(now, _move, _aim, (_seq + 1) & Wire.InputSeqMask);
            else OwnTank.Stop();

            if (Alive && !_backgrounded && now >= _nextInputAt)
            {
                _seq = (_seq + 1) & Wire.InputSeqMask;
                int flags = _flags | (_fireLatched ? Wire.FlagFire : 0);
                // The acknowledgement rides on the input: the tick of the last frame applied.
                Send(_messages.Input(_seq, World.ServerTick, _move, _aim, flags));
                OwnTank.Sent(_seq, _move);
                _fireLatched = false;
                _nextInputAt = now + InputEveryMs;
            }
        }

        /// <returns>false if the frame ended this connection's reading</returns>
        private bool Handle(byte[] frame, long now)
        {
            try
            {
                switch (ServerMessages.TypeOf(frame))
                {
                    case Wire.MsgWelcome:
                        Welcome = ServerMessages.ReadWelcome(frame);
                        World = new ClientWorld(Welcome.MazeSeed);
                        if (_lostAt >= 0) Resumes++;
                        _lostAt = -1;
                        _resumeTries = 0;
                        State = MatchState.InMatch;
                        _heardAt = now;
                        if (_backgrounded) Send(_messages.Lifecycle(true));
                        return true;
                    case Wire.MsgSnapshot:
                        long tick = World.ServerTick;
                        World.Apply(frame);
                        // Dead from the Death event until the world's ticks come again: the arena sends those only to a
                        // player with a tank, and a dead one its events alone. The own entity is not removed (P-53).
                        if (World.ServerTick != tick) _dead = false;
                        for (int i = 0; i < World.EventCount; i++)
                            if (World.EventAt(i).Type == Wire.EvtDeath) _dead = true;
                        SnapshotsApplied++;
                        OwnTank.OnFrame(World, Welcome.MapWidth, Welcome.MapHeight, now);
                        if (OnEvent != null)
                            for (int i = 0; i < World.EventCount; i++) OnEvent(World.EventAt(i));
                        OnFrame?.Invoke();
                        return true;
                    case Wire.MsgPong:
                        RoundTripMs = now - ServerMessages.ReadPong(frame).ClientTimeMs;
                        return true;
                    case Wire.MsgKick:
                        Finish(KickEnd(ServerMessages.ReadKick(frame)));
                        return false;
                    default:
                        return true;                     // a later server's message: not ours to read
                }
            }
            catch (ProtocolException e)
            {
                // Not survivable by guessing: drop this connection and its world; a resume starts
                // a fresh view (08 §3).
                LastProblem = "desync: " + e.Message;
                Lose(now);
                return false;
            }
        }

        private static MatchEnd KickEnd(int reason)
        {
            switch (reason)
            {
                case Wire.KickBadTicket: return MatchEnd.BadTicket;
                case Wire.KickRoomFull: return MatchEnd.RoomFull;
                case Wire.KickProtocolVersion: return MatchEnd.ProtocolVersion;
                case Wire.KickRateLimit: return MatchEnd.RateLimit;
                case Wire.KickMatchOver: return MatchEnd.MatchOver;
                case Wire.KickRemoved: return MatchEnd.Removed;
                default: return MatchEnd.ServerFault;   // 5, and any reason this client does not know
            }
        }

        private void GiveUp(string why, long now)
        {
            LastProblem = why;
            if (State == MatchState.InMatch || Welcome.ResumeSecret != null) Lose(now);
            else Finish(MatchEnd.Unreachable);           // never joined: nothing to resume
        }

        private void Lose(long now)
        {
            _attempt?.Close();
            if (Welcome.ResumeSecret == null)
            {
                Finish(MatchEnd.Unreachable);
                return;
            }
            if (_lostAt < 0) _lostAt = now;
            State = MatchState.Lost;
            _nextResumeAt = now + ResumeDelaysMs[Math.Min(_resumeTries, ResumeDelaysMs.Length - 1)];
        }

        private void TryResume(long now)
        {
            if (now - _lostAt >= ResumeForMs)
            {
                Finish(MatchEnd.ResumeExpired);
                return;
            }
            if (now < _nextResumeAt) return;
            _resumeTries++;
            Dial();
        }

        private void Dial()
        {
            _attempt?.Close();
            _attempt = new Attempt(_settings);
            State = MatchState.Connecting;
            _stateSince = _clock.NowMs;
        }

        private void Send(ArraySegment<byte> message)
        {
            Attempt a = _attempt;
            if (a == null || !a.Connected || a.Closed) return;
            try
            {
                a.Stream.Write(message.Array, message.Offset, message.Count);
            }
            catch (Exception e) when (e is IOException || e is ObjectDisposedException || e is SocketException)
            {
                a.Fail(e);                              // Poll sees it closed and resumes
            }
        }

        private void Finish(MatchEnd end)
        {
            End = end;
            State = MatchState.Ended;
            _attempt?.Close();
        }

        /// <summary>
        /// Tests only: drops the socket without a word, as a phone walking out of wifi does. The
        /// server sees nothing until its idle limit; this client resumes.
        /// </summary>
        public void DropForTest() => _attempt?.Close();

        /// <summary>Closes, but lets a Leave's wait for the arena's close run out (P-34).</summary>
        public void Dispose() => _attempt?.CloseUnlessLeaving();

        /// <summary>
        /// One socket, from dialling to closing. Its thread connects, shakes hands, then reads until
        /// the socket ends; the main thread sees only its flags and its inbox, so a stale attempt
        /// cannot speak for the next one.
        /// </summary>
        private sealed class Attempt
        {
            public readonly ConcurrentQueue<byte[]> Inbox = new ConcurrentQueue<byte[]>();
            private readonly MatchSettings _settings;
            private readonly FrameReader _frames = new FrameReader();
            // Made here, before the thread: a Close that comes before the thread has dialled closes it too, where
            // one made on the thread was left open (client review, 2026-10-04).
            private readonly TcpClient _tcp = new TcpClient { NoDelay = true };
            private volatile bool _connected, _closed, _leaving;
            private Timer _linger;
            public Stream Stream;
            public Exception Failure;

            public Attempt(MatchSettings settings)
            {
                _settings = settings;
                var thread = new Thread(Run) { IsBackground = true, Name = "match-connection" };
                thread.Start();
            }

            public bool Connected => _connected;
            public bool Closed => _closed;

            private void Run()
            {
                try
                {
                    _tcp.Connect(_settings.Host, _settings.Port);
                    Stream stream = _tcp.GetStream();
                    if (_settings.Tls)
                    {
                        var tls = new SslStream(stream, false, _settings.CertificateCheck);
                        // Checked against the host the grant names, whatever address was dialled.
                        tls.AuthenticateAsClient(_settings.Host);
                        stream = tls;
                    }
                    Stream = stream;
                    _connected = true;
                    var buffer = new byte[16 * 1024];
                    while (!_closed)
                    {
                        int n = stream.Read(buffer, 0, buffer.Length);
                        if (n <= 0) break;
                        _frames.Feed(new ReadOnlySpan<byte>(buffer, 0, n), Inbox.Enqueue);
                    }
                }
                catch (Exception e)
                {
                    if (!_closed) Failure = e;
                }
                if (_leaving) Close();                  // the arena has closed: now this side may
                _closed = true;
            }

            /// <summary>
            /// Reads on until the arena closes, then closes; or closes after
            /// <paramref name="millis"/> if it does not (P-34).
            /// </summary>
            public void CloseWhenPeerDoes(int millis)
            {
                _leaving = true;
                _linger = new Timer(_ => Close(), null, millis, Timeout.Infinite);
            }

            public void CloseUnlessLeaving()
            {
                if (!_leaving) Close();
            }

            public void Fail(Exception e)
            {
                Failure = e;
                Close();
            }

            public void Close()
            {
                _closed = true;
                _linger?.Dispose();
                try { _tcp.Close(); } catch (Exception) { /* closing, whatever state it was in */ }
            }
        }
    }
}
