using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace Backend.Client.Simple
{
    /// <summary>
    /// A match: a TCP connection to the arena a grant names, without TLS, binary messages in frames
    /// (<see cref="ArenaMessages"/>): <c>Connect</c> for a grant with <c>tls</c> false, a backend run plain;
    /// <c>ConnectThroughSite</c> for the site's, which ask for TLS, through its 80. Either sends Join with the ticket; the arena answers a Welcome,
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

        private readonly ConcurrentQueue<Action> _events = new ConcurrentQueue<Action>();
        private readonly Stopwatch _clock = Stopwatch.StartNew();
        private readonly object _writing = new object();
        private string _host;
        private int _port;
        private string _request;                        // through the site's 80: the request for the arena by name
        private bool _over;
        private TcpClient _tcp;
        private Stream _stream;
        private int _seq;
        private long _nextPingAt;

        /// <summary>Joins the match a grant with tls false names, a backend run plain's: its arenaHost, arenaPort and ticketId.</summary>
        public void Connect(string host, int port, string ticketId)
        {
            Join(host, port, null, ticketId);
        }

        /// <summary>
        /// Joins the match a grant of the site's names, one with tls true and a tlsName, through the site's 80 (D-115):
        /// the API's address, <c>http://203.0.113.10</c>, and the grant's tlsName and ticketId. 9001 speaks TLS alone,
        /// so the connection asks nginx on 80 for the arena by its name, <c>GET /match/&lt;tlsName&gt;</c>, and the match
        /// goes on once nginx has answered 101.
        /// </summary>
        public void ConnectThroughSite(string apiUrl, string tlsName, string ticketId)
        {
            if (!apiUrl.StartsWith("http://", StringComparison.Ordinal))
                throw new ArgumentException("the API's http:// address: this client has no TLS (" + apiUrl + ")");
            if (string.IsNullOrEmpty(tlsName))
                throw new ArgumentException("the grant names no arena: an arena with TLS of its own, which this client cannot reach");
            var site = new Uri(apiUrl);
            Join(site.Host, site.Port, "GET /match/" + tlsName + " HTTP/1.1\r\nHost: " + site.Authority
                + "\r\nConnection: Upgrade\r\nUpgrade: backend-match\r\n\r\n", ticketId);
        }

        private void Join(string host, int port, string request, string ticketId)
        {
            _host = host;
            _port = port;
            _request = request;
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
            string request = _request;
            new Thread(() => Run(tcp, request, admission)) { IsBackground = true, Name = "arena" }.Start();
        }

        /// <summary>The connection's own thread: it connects, asks for the arena through the site, sends Join or Resume, then reads frames until the end.</summary>
        private void Run(TcpClient tcp, string request, byte[] admission)
        {
            string why;
            try
            {
                IAsyncResult connecting = tcp.BeginConnect(_host, _port, null, null);
                if (!connecting.AsyncWaitHandle.WaitOne(10000)) throw new TimeoutException("no connection to " + _host + ":" + _port + " in 10 s");
                tcp.EndConnect(connecting);
                tcp.ReceiveTimeout = 30000;            // snapshots come many times a second: 30 s of nothing is a dead line
                Stream stream = tcp.GetStream();
                if (request != null) AskForArena(stream, request);
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

        /// <summary>Through the site's 80: the request for the arena, and nginx's answer read to its blank line, 101 or the refusal.</summary>
        private static void AskForArena(Stream stream, string request)
        {
            byte[] bytes = Encoding.ASCII.GetBytes(request);
            stream.Write(bytes, 0, bytes.Length);
            var answer = new StringBuilder();
            while (!answer.ToString().EndsWith("\r\n\r\n", StringComparison.Ordinal))
            {
                int b = stream.ReadByte();
                if (b < 0) throw new IOException("the site closed the connection before answering");
                if (answer.Length == 4096) throw new IOException("the site's answer is too long");
                answer.Append((char)b);
            }
            string status = answer.ToString(0, answer.ToString().IndexOf('\r'));
            if (!status.StartsWith("HTTP/1.1 101 ", StringComparison.Ordinal)) throw new IOException("the site refused the match: " + status);
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
