using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Net.Security;
using System.Security.Authentication;
using System.Net.Sockets;
using System.Threading;

namespace Backend.Client.Simple
{
    /// <summary>
    /// A match: a TCP connection to the arena a grant names (TLS when it says so), binary messages in frames
    /// (<see cref="ArenaMessages"/>). <c>Connect(grant...)</c> sends Join with the ticket; the arena answers a Welcome,
    /// then snapshots 10 to 15 a second. Send inputs ten times a second while playing, which acknowledge the snapshots;
    /// a Ping goes every 10 s by itself.
    /// Call <see cref="Update"/> every frame: every callback runs inside it, on Unity's main thread.
    /// </summary>
    public sealed class ArenaClient
    {
        /// <summary>Joined (or resumed): the match's map, mode and the player's entity.</summary>
        public Action<ArenaWelcome> OnWelcome;
        /// <summary>A snapshot, as its bytes (02-networking §4), and the server's tick it brings the world to.</summary>
        public Action<byte[], long> OnSnapshot;
        /// <summary>A Ping's answer: the round trip in ms.</summary>
        public Action<int> OnPong;
        /// <summary>The arena sent the player away: one of ArenaMessages.Kick... The connection closes next.</summary>
        public Action<int> OnKick;
        /// <summary>The connection is over, and why. Called once per Connect or Resume, by Close() too.</summary>
        public Action<string> OnClosed;
        /// <summary>The most this client wants sent: ArenaMessages.ProfileMobile, ProfileHigh or ProfileSaver.</summary>
        public int Profile = ArenaMessages.ProfileMobile;

        /// <summary>The last Welcome; null before the first.</summary>
        public ArenaWelcome Welcome { get; private set; }
        /// <summary>The server's tick of the last snapshot handed to OnSnapshot: each Input acknowledges it.</summary>
        public long ServerTick { get; private set; }
        /// <summary>Whether the stay can be taken up again after a lost connection: joined, and not left nor kicked.</summary>
        public bool CanResume => Welcome != null && !_over;

        private readonly TrustRoot _trust;
        private readonly ConcurrentQueue<Action> _events = new ConcurrentQueue<Action>();
        private readonly Stopwatch _clock = Stopwatch.StartNew();
        private readonly object _writing = new object();
        private string _host, _tlsName;
        private int _port;
        private bool _tls, _over;
        private TcpClient _tcp;
        private Stream _stream;
        private int _seq;
        private long _nextPingAt;

        /// <param name="caPem">the backend's root (ca.pem's text), which a TLS arena's certificate must come from</param>
        public ArenaClient(string caPem = null)
        {
            _trust = caPem == null ? null : TrustRoot.FromPem(caPem);
        }

        /// <summary>Joins the match a grant names: its arenaHost, arenaPort, tls, tlsName and ticketId.</summary>
        public void Connect(string host, int port, bool tls, string tlsName, string ticketId)
        {
            _host = host;
            _port = port;
            _tls = tls;
            _tlsName = tlsName;
            _over = false;
            Welcome = null;
            Start(ArenaMessages.Join(ticketId, Profile));
        }

        /// <summary>After a lost connection (OnClosed without Leave or a kick, within a minute): the same stay again.</summary>
        public void Resume()
        {
            if (!CanResume) throw new InvalidOperationException("nothing to resume: never joined, left, or kicked");
            Start(ArenaMessages.Resume(Welcome.ResumeSecret, Profile));
        }

        /// <summary>
        /// The player's input: any of ArenaMessages.Move..., the aim in radians (0 along +x), any of ArenaMessages.Flag....
        /// Send it ten times a second, moving or not: it acknowledges the snapshots, and the arena holds them back
        /// when none has been acknowledged for a second.
        /// </summary>
        public void SendInput(int moveMask, float aimRadians, int flags)
        {
            _seq = (_seq + 1) & 0xFFFFFF;
            Write(ArenaMessages.Input(_seq, ServerTick, moveMask, ArenaMessages.Aim(aimRadians), flags));
        }

        public void UpgradeStat(int stat) => Write(ArenaMessages.UpgradeStat(stat));
        public void ChooseClass(int classId) => Write(ArenaMessages.ChooseClass(classId));
        public void Respawn() => Write(ArenaMessages.Respawn());
        /// <summary>A phrase of GET /v1/content/phrases, at most one every 2 s.</summary>
        public void SayPhrase(int phraseId) => Write(ArenaMessages.Phrase(phraseId));

        /// <summary>From OnApplicationPause: the arena keeps the stay while the app is away.</summary>
        public void SetBackground(bool background) => Write(ArenaMessages.Lifecycle(background));

        /// <summary>Leaves the match for good; the arena closes the connection, and OnClosed follows.</summary>
        public void Leave()
        {
            _over = true;
            Write(ArenaMessages.Leave());
        }

        /// <summary>Hands what came in to the callbacks, and pings every 10 s. Call it every frame.</summary>
        public void Update()
        {
            Action e;
            while (_events.TryDequeue(out e)) e();
            if (_stream != null && Welcome != null && _clock.ElapsedMilliseconds >= _nextPingAt) Ping();
        }

        /// <summary>Drops the connection at once, without Leave: the stay waits a minute for a Resume. OnClosed follows in Update.</summary>
        public void Close()
        {
            TcpClient tcp = _tcp;
            if (tcp == null) return;
            End(tcp, "closed by the client");
            try { tcp.Close(); } catch (Exception) { }
        }

        private void Start(byte[] admission)
        {
            Close();
            var tcp = new TcpClient { NoDelay = true };
            _tcp = tcp;
            new Thread(() => Run(tcp, admission)) { IsBackground = true, Name = "arena" }.Start();
        }

        /// <summary>The connection's own thread: it connects, sends Join or Resume, then reads frames until the end.</summary>
        private void Run(TcpClient tcp, byte[] admission)
        {
            string why;
            try
            {
                IAsyncResult connecting = tcp.BeginConnect(_host, _port, null, null);
                if (!connecting.AsyncWaitHandle.WaitOne(10000)) throw new TimeoutException("no connection to " + _host + ":" + _port + " in 10 s");
                tcp.EndConnect(connecting);
                tcp.ReceiveTimeout = 30000;            // snapshots come many times a second: 30 s of nothing is a dead line
                Stream stream = tcp.GetStream();
                if (_tls)
                {
                    // The grant's tlsName: what the handshake names, what the front routes by, what the certificate must name.
                    string name = string.IsNullOrEmpty(_tlsName) ? _host : _tlsName;
                    var tls = new SslStream(stream, false, _trust?.CheckFor(name));
                    // TLS 1.2 by name: built for .NET Framework 4.5, a socket offers 1.0 alone by default (D-116).
                    tls.AuthenticateAsClient(name, null, SslProtocols.Tls | SslProtocols.Tls11 | SslProtocols.Tls12, false);
                    stream = tls;
                }
                stream.Write(admission, 0, admission.Length);
                stream.Flush();
                _events.Enqueue(() => { if (_tcp == tcp) _stream = stream; });
                while (true)
                {
                    byte[] message = ArenaMessages.ReadFrame(stream);
                    if (message == null)
                    {
                        why = "the arena closed the connection";
                        break;
                    }
                    _events.Enqueue(() => { if (_tcp == tcp) Dispatch(message); });
                }
            }
            catch (Exception e)
            {
                why = e.Message;
            }
            try { tcp.Close(); } catch (Exception) { }
            _events.Enqueue(() => End(tcp, why));
        }

        private void Dispatch(byte[] message)
        {
            switch (message[0])
            {
                case ArenaMessages.MsgWelcome:
                    Welcome = ArenaMessages.ReadWelcome(message);
                    ServerTick = 0;                     // a fresh view: the first snapshot's delta counts from 0
                    OnWelcome?.Invoke(Welcome);
                    break;
                case ArenaMessages.MsgSnapshot:
                    ServerTick += ArenaMessages.SnapshotTickDelta(message);
                    OnSnapshot?.Invoke(message, ServerTick);
                    break;
                case ArenaMessages.MsgPong:
                    uint sent;
                    long tick;
                    ArenaMessages.ReadPong(message, out sent, out tick);
                    OnPong?.Invoke((int)((uint)_clock.ElapsedMilliseconds - sent));
                    break;
                case ArenaMessages.MsgKick:
                    _over = true;
                    OnKick?.Invoke(ArenaMessages.ReadKick(message));
                    break;
            }
        }

        private void Ping()
        {
            _nextPingAt = _clock.ElapsedMilliseconds + 10000;
            Write(ArenaMessages.Ping((uint)_clock.ElapsedMilliseconds));
        }

        private void Write(byte[] frame)
        {
            Stream stream = _stream;
            if (stream == null) return;                 // not connected (yet, or any more): dropped
            try
            {
                lock (_writing)
                {
                    stream.Write(frame, 0, frame.Length);
                    stream.Flush();
                }
            }
            catch (Exception)
            {
                // The connection broke: its reader says so, and OnClosed follows.
            }
        }

        /// <summary>The connection's end, once: OnClosed told in Update.</summary>
        private void End(TcpClient tcp, string why)
        {
            if (_tcp != tcp) return;
            _tcp = null;
            _stream = null;
            _events.Enqueue(() => OnClosed?.Invoke(why));
        }
    }
}
