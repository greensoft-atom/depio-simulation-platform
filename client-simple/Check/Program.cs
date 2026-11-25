using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Backend.Client.Simple;
using Core = Backend.Client.Core;

namespace Backend.Client.Simple.Check
{
    /// <summary>
    /// client-simple's own check (D-111), run on Unity's Mono by check.sh; compiled with NO_TLS, client-simple-no-tls's
    /// (D-114), its TLS checks left out. Offline: its pieces against the core's,
    /// which the server's tests bind, and against certificates made by private-ca.sh. Live: the three clients against a
    /// running stack, driven as a Unity script drives them, Update() every 16 ms on one thread, every callback on it.
    ///
    ///   check.exe offline &lt;pki-dir&gt;
    ///   check.exe live &lt;api-url&gt; &lt;lobby-url&gt; [&lt;ca.pem&gt; [&lt;another root's ca.pem&gt;]]
    /// </summary>
    internal static class Program
    {
        private static int _passed, _failed;
        private static int _mainThread;

        private static int Main(string[] args)
        {
            _mainThread = Thread.CurrentThread.ManagedThreadId;
            try
            {
                if (args.Length == 2 && args[0] == "offline") Offline(args[1]);
                else if (args.Length >= 3 && args[0] == "live") Live(args[1], args[2], args.Length > 3 ? args[3] : null, args.Length > 4 ? args[4] : null);
                else
                {
                    Console.WriteLine("check.exe offline <pki-dir> | live <api-url> <lobby-url> [<ca.pem> [<another root's ca.pem>]]");
                    return 2;
                }
            }
            catch (Exception e)
            {
                Fail("stopped by " + e);
            }
            Console.WriteLine("  --- " + _passed + " passed, " + _failed + " failed");
            return _failed == 0 ? 0 : 1;
        }

        private static void Check(bool ok, string what)
        {
            if (ok) _passed++;
            else Fail(what);
        }

        private static void Fail(string what)
        {
            _failed++;
            Console.WriteLine("  FAIL " + what);
        }

        private static void Section(string what) => Console.WriteLine("--- " + what);

        // ------------------------------------------------------------------------------------------------- offline

        private static void Offline(string pki)
        {
            Section("Json: the top-level fields of an object");
            const string auth = "{\"t\":\"auth.ok\",\"id\":2,\"d\":{\"playerId\":6}}";
            Check(Json.GetString(auth, "t") == "auth.ok", "a string field");
            Check(Json.GetLong(auth, "id") == 2, "a number field");
            Check(Json.GetRaw(auth, "d") == "{\"playerId\":6}", "an object's text, as sent");
            Check(Json.GetRaw(auth, "x") == null && Json.GetString(auth, "x") == null && Json.GetLong(auth, "x", -1) == -1,
                "a field that is not there: null, or the fallback");
            const string notice = "{\"t\":\"evt.notice\",\"d\":{\"text\":\"a \\\"quoted\\\" {brace}, [x] \\\\ \\n \\u00e9\"}}";
            Check(Json.GetRaw(notice, "d") == "{\"text\":\"a \\\"quoted\\\" {brace}, [x] \\\\ \\n \\u00e9\"}",
                "an object whose string holds quotes, braces and escapes: whole");
            Check(Json.GetString(Json.GetRaw(notice, "d"), "text") == "a \"quoted\" {brace}, [x] \\ \n \u00e9",
                "a string's escapes undone");
            Check(Json.GetString("{\"d\":{\"t\":\"inner\"},\"t\":\"outer\"}", "t") == "outer", "a nested field is not the top's");
            Check(Json.GetLong("{ \"t\" : \"x\" , \"id\" : 3 }", "id") == 3, "spaces between the parts");
            const string party = "{\"members\":[{\"playerId\":1},{\"playerId\":2}],\"version\":2,\"tls\":true,\"tlsName\":null}";
            Check(Json.GetRaw(party, "members") == "[{\"playerId\":1},{\"playerId\":2}]" && Json.GetLong(party, "version") == 2,
                "an array, and a field after it");
            Check(Json.GetBool(party, "tls") && !Json.GetBool(party, "x") && Json.GetString(party, "tlsName") == null,
                "true, a missing bool, null");
            Check(Json.GetString("{not json", "t") == null && Json.GetString("", "t") == null && Json.GetString(null, "t") == null
                  && Json.GetString("<html>429</html>", "code") == null, "what is not JSON: null, never an exception");
            Check(Json.Quote("a\"b\\c\nd\u0001") == "\"a\\\"b\\\\c\\nd\\u0001\"", "a string quoted for JSON");
            Check(Json.GetString("{\"s\":" + Json.Quote("x\"y\\z\t\u00e9") + "}", "s") == "x\"y\\z\t\u00e9", "quoted, then read back");
            Check(Json.Quote(null) == "null", "null quoted: JSON's null");

            Section("the arena's messages, byte for byte the core's (02 §3)");
            var core = new Core.ClientMessages();
            Func<ArraySegment<byte>, byte[]> bytes = s => s.ToArray();
            Check(Same(ArenaMessages.Join("FOtirryJy3sDqQntfNTLlg", 0), bytes(core.Join("FOtirryJy3sDqQntfNTLlg", 0))), "Join");
            Check(Same(ArenaMessages.Join("t", 2), bytes(core.Join("t", 2))), "Join, the saver profile");
            Check(Same(ArenaMessages.Resume("rH3cZ8Lw0Qm1b9VxYd2KfA", 1), bytes(core.Resume("rH3cZ8Lw0Qm1b9VxYd2KfA", 1))), "Resume");
            foreach (float a in new[] { -3.1415927f, -1f, 0f, 0.5f, 1f, 3.1415f })
                Check(ArenaMessages.Aim(a) == Core.Wire.QuantiseAim(a), "an aim of " + a + " rad");
            Check(Same(ArenaMessages.Input(5, 1234, 9, ArenaMessages.Aim(1f), 1), bytes(core.Input(5, 1234, 9, Core.Wire.QuantiseAim(1f), 1))),
                "Input");
            Check(Same(ArenaMessages.Input(0x1000005, 300000, 15, 65535, 11), bytes(core.Input(0x1000005, 300000, 15, 65535, 11))),
                "Input, its seq past 2^24 and a long ack");
            Check(Same(ArenaMessages.Ping(123456789u), bytes(core.Ping(123456789u))), "Ping, its time big-endian");
            Check(Same(ArenaMessages.Lifecycle(true), bytes(core.Lifecycle(true))) && Same(ArenaMessages.Lifecycle(false), bytes(core.Lifecycle(false))),
                "Lifecycle");
            Check(Same(ArenaMessages.Leave(), bytes(core.Leave())) && Same(ArenaMessages.Respawn(), bytes(core.Respawn())), "Leave, Respawn");
            Check(Same(ArenaMessages.Phrase(3), bytes(core.Phrase(3))) && Same(ArenaMessages.UpgradeStat(2), bytes(core.UpgradeStat(2)))
                  && Same(ArenaMessages.ChooseClass(4), bytes(core.ChooseClass(4))), "Phrase, UpgradeStat, ChooseClass");
            Check(Throws(() => ArenaMessages.Join("", 0)) && Throws(() => ArenaMessages.Join(new string('a', 65), 0))
                  && Throws(() => ArenaMessages.Join("a b", 0)), "a ticket that is not 1 to 64 printable ASCII: refused");

            Section("the arena's answers, read as the core reads them");
            byte[] welcome = Frame(1, 0, 15, 0xF0, 0xAB, 0x01, 0xF0, 0xAB, 0x01, 0, 0x96, 0x01, 7, 42, 3, (byte)'a', (byte)'b', (byte)'c', 0xB9, 0x60);
            CompareWelcome(welcome, "a Welcome");
            CompareWelcome(Frame(1, 0, 10, 100, 100, 9, 1, 2, 3, 2, (byte)'x', (byte)'y'), "a Welcome without a maze seed");
            byte[] pong = Frame(3, 0x07, 0x5B, 0xCD, 0x15, 0xAC, 0x02);
            Core.Pong cp = Core.ServerMessages.ReadPong(pong);
            uint time; long tick;
            ArenaMessages.ReadPong(pong, out time, out tick);
            Check(time == cp.ClientTimeMs && tick == cp.ServerTick && time == 123456789u && tick == 300, "a Pong");
            Check(ArenaMessages.ReadKick(Frame(4, 2)) == 2 && ArenaMessages.ReadKick(Frame(4, 2)) == Core.ServerMessages.ReadKick(Frame(4, 2)), "a Kick");
            Check(ArenaMessages.SnapshotTickDelta(Frame(2, 0xAC, 0x02, 1, 0, 0)) == 300, "a snapshot's tickDelta");

            Section("frames: a varint length, then the message (02 §1)");
            var stream = new MemoryStream();
            var sizes = new[] { 1, 127, 128, 300, 8192 };
            foreach (int n in sizes) WriteFrame(stream, Enumerable.Repeat((byte)(n & 0xFF), n).ToArray());
            stream.Position = 0;
            Check(sizes.All(n => { byte[] f = ArenaMessages.ReadFrame(stream); return f.Length == n && f.All(b => b == (byte)(n & 0xFF)); }),
                "1, 127, 128, 300 and 8192 bytes, each whole");
            Check(Throws(() => ArenaMessages.ReadFrame(new MemoryStream(new byte[] { 0 }))), "an empty frame: refused, the stream is broken");
            Check(Throws(() => ArenaMessages.ReadFrame(new MemoryStream(new byte[] { 0x81, 0x80, 0x08 }))), "a frame over 64 KiB: refused");
            Check(Throws(() => ArenaMessages.ReadFrame(new MemoryStream(new byte[] { 5, 1, 2 }))), "a stream that ends inside a frame");
            Check(ArenaMessages.ReadFrame(new MemoryStream(new byte[0])) == null, "a stream that ends between frames: null");

            Section("the WebSocket's parts (RFC 6455)");
            Check(WebSocketConnection.AcceptKey("dGhlIHNhbXBsZSBub25jZQ==") == "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", "the handshake's answer, RFC 6455 §1.3");
            byte[] mask = { 0x37, 0xfa, 0x21, 0x3d };
            Check(Same(WebSocketConnection.ClientFrame(1, Encoding.ASCII.GetBytes("Hello"), mask),
                new byte[] { 0x81, 0x85, 0x37, 0xfa, 0x21, 0x3d, 0x7f, 0x9f, 0x4d, 0x51, 0x58 }), "a masked text frame, RFC 6455 §5.7");
            byte[] mid = WebSocketConnection.ClientFrame(1, new byte[300], mask);
            Check(mid.Length == 2 + 2 + 4 + 300 && mid[1] == (0x80 | 126) && mid[2] == 1 && mid[3] == 44, "300 bytes: a 16-bit length");
            byte[] big = WebSocketConnection.ClientFrame(1, new byte[70000], mask);
            Check(big.Length == 2 + 8 + 4 + 70000 && big[1] == (0x80 | 127) && big[7] == 1 && big[8] == 0x11 && big[9] == 0x70, "70000 bytes: a 64-bit length");

#if !NO_TLS
            Section("the certificate check, as the core's (D-108)");
            SimpleTrust(pki);
#endif

            Section("the API over HttpClient (D-112)");
            ApiCalls(pki);

#if !NO_TLS
            Section("TLS 1.2 asked for by name, which .NET Framework 4.5 does not give by default (D-116)");
            SecurityProtocolType had = ServicePointManager.SecurityProtocol;
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls;
            new BackendHttp("http://127.0.0.1:1");
            Check(ServicePointManager.SecurityProtocol == (SecurityProtocolType.Tls | SecurityProtocolType.Tls11 | SecurityProtocolType.Tls12),
                "the API's requests: TLS 1.2 and 1.1 added to what the process offers");
            ServicePointManager.SecurityProtocol = had;

            Section("the lobby and a match over TLS, each connection checked by the root (D-108)");
            LobbyAndMatchOverTls(pki);
#else
            Section("no TLS anywhere (D-114)");
            Check(Throws(() => new BackendHttp("https://127.0.0.1")) && !Throws(() => new BackendHttp("http://127.0.0.1")),
                "the API: an https:// address refused, an http:// one taken");
            Check(Throws(() => new LobbyClient("wss://127.0.0.1/lobby")) && !Throws(() => new LobbyClient("ws://127.0.0.1/lobby")),
                "the lobby: a wss:// address refused, a ws:// one taken");

            Section("a match through the site's 80, by its arena's name (D-115)");
            MatchThroughEighty();
#endif
        }

#if NO_TLS
        /// <summary>
        /// A match through the site's 80, against a stand-in for nginx: the request for the arena by name, then Join once
        /// the 101 is read; nginx's refusal said in OnClosed; an address not http://, or a grant without a name, refused.
        /// </summary>
        private static void MatchThroughEighty()
        {
            const string ticket = "FOtirryJy3sDqQntfNTLlg";
            string head = null;
            byte[] admission = null;
            int port;
            Thread nginx = StandIn("HTTP/1.1 101 Switching Protocols\r\nServer: nginx\r\nConnection: upgrade\r\nUpgrade: backend-match\r\n\r\n",
                (h, s) =>
                {
                    head = h;
                    byte[] join = ArenaMessages.Join(ticket, ArenaMessages.ProfileMobile);
                    admission = new byte[join.Length];
                    for (int n = 0, r; n < join.Length; n += r)
                        if ((r = s.Read(admission, n, join.Length - n)) <= 0) { admission = null; break; }
                }, out port);
            string closed = null;
            var arena = new ArenaClient { OnClosed = w => closed = w };
            arena.ConnectThroughSite("http://127.0.0.1:" + port, "a1.arena.internal", ticket);
            nginx.Join(10000);
            Check(head == "GET /match/a1.arena.internal HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nConnection: Upgrade\r\nUpgrade: backend-match\r\n\r\n",
                "the request: the arena's name, the site's address, the upgrade asked for (" + (head ?? "none").Replace("\r\n", " | ") + ")");
            Check(Same(admission, ArenaMessages.Join(ticket, ArenaMessages.ProfileMobile)), "then Join, after the 101");
            arena.Close();
            Pump(() => closed != null, 2000, arena.Update);

            closed = null;
            nginx = StandIn("HTTP/1.1 404 Not Found\r\nServer: nginx\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", (h, s) => { }, out port);
            arena = new ArenaClient { OnClosed = w => closed = w };
            arena.ConnectThroughSite("http://127.0.0.1:" + port, "nope.arena.internal", ticket);
            Pump(() => closed != null, 10000, arena.Update);
            Check(closed != null && closed.Contains("HTTP/1.1 404 Not Found"), "nginx's refusal, a name it has not: said in OnClosed (" + closed + ")");

            Check(Throws(() => new ArenaClient().ConnectThroughSite("https://127.0.0.1", "a1.arena.internal", ticket))
                  && Throws(() => new ArenaClient().ConnectThroughSite("http://127.0.0.1", null, ticket)),
                "an address not http://, and a grant without an arena's name: refused");
        }

        /// <summary>One connection on a loopback port: its request read to the blank line, the answer given, then what follows.</summary>
        private static Thread StandIn(string answer, Action<string, Stream> then, out int port)
        {
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            port = ((IPEndPoint)listener.LocalEndpoint).Port;
            var thread = new Thread(() =>
            {
                try
                {
                    using (TcpClient c = listener.AcceptTcpClient())
                    {
                        Stream s = c.GetStream();
                        var head = new StringBuilder();
                        while (!head.ToString().EndsWith("\r\n\r\n", StringComparison.Ordinal))
                        {
                            int b = s.ReadByte();
                            if (b < 0) return;
                            head.Append((char)b);
                        }
                        byte[] bytes = Encoding.ASCII.GetBytes(answer);
                        s.Write(bytes, 0, bytes.Length);
                        then(head.ToString(), s);
                    }
                }
                finally
                {
                    listener.Stop();
                }
            }) { IsBackground = true };
            thread.Start();
            return thread;
        }
#endif

        private static void ApiCalls(string pki)
        {
            // A server that takes the connection and never answers.
            var silent = new TcpListener(IPAddress.Loopback, 0);
            silent.Start();
            var clock = Stopwatch.StartNew();
            Task<HttpAnswer> call = new BackendHttp("http://127.0.0.1:" + ((IPEndPoint)silent.LocalEndpoint).Port).GetAsync("/v1/health");
            Check(!call.IsCompleted && clock.ElapsedMilliseconds < 1000, "a call returns at once, before its answer: the frame goes on");
            bool ended = call.Wait(30000);
            Check(ended && call.Result.Status == 0 && call.Result.Error == "no answer in 15 s" && clock.ElapsedMilliseconds >= 14000,
                "no answer: given up after 15 s, Status 0 (" + (ended ? call.Result.Error : "still waiting") + ", " + clock.ElapsedMilliseconds + " ms)");
            silent.Stop();
#if !NO_TLS
            string rootPem = File.ReadAllText(Path.Combine(pki, "root/ca.pem"));
            string otherPem = File.ReadAllText(Path.Combine(pki, "other/ca.pem"));
            X509Certificate2 web = FirstCertificate(Path.Combine(pki, "web/fullchain.pem"));      // the root's: 127.0.0.1, site.internal
            X509Certificate2 stranger = FirstCertificate(Path.Combine(pki, "other-leaf/fullchain.pem"));

            // The app's certificate check, called as Unity 2019's HttpClient calls it: the request as the sender.
            Func<string, HttpWebRequest> to = host => (HttpWebRequest)WebRequest.Create("https://" + host + "/");
            const SslPolicyErrors trusted = SslPolicyErrors.None, untrusted = SslPolicyErrors.RemoteCertificateChainErrors;
            ServicePointManager.ServerCertificateValidationCallback = null;
            new BackendHttp("https://127.0.0.1:1", rootPem);
            RemoteCertificateValidationCallback app = ServicePointManager.ServerCertificateValidationCallback;
            Check(app != null, "a BackendHttp with a root: the app's check set");
            if (app == null) return;
            Check(app(to("127.0.0.1"), web, null, untrusted), "the backend's host: the root's certificate taken, though the runtime knows no such root");
            Check(!app(to("127.0.0.1"), stranger, null, trusted), "the backend's host: another root's refused, even one the runtime trusts");
            Check(!app(to("site.internal"), web, null, untrusted), "another site: the runtime's verdict, not the root's, though the root issued its certificate");
            Check(app(to("site.internal"), web, null, trusted), "another site the runtime trusts: taken");
            Check(app(null, web, null, trusted) && !app(null, web, null, untrusted), "a sender that is not a request: the runtime's verdict");
            var asked = new List<string>();
            ServicePointManager.ServerCertificateValidationCallback = (s, c, ch, e) => { asked.Add(((HttpWebRequest)s).RequestUri.Host); return true; };
            new BackendHttp("https://127.0.0.1:1", rootPem);
            app = ServicePointManager.ServerCertificateValidationCallback;
            Check(app(to("site.internal"), web, null, untrusted) && asked.SequenceEqual(new[] { "site.internal" }),
                "another site, with a check set before: that check's verdict");
            Check(!app(to("127.0.0.1"), stranger, null, untrusted) && asked.Count == 1, "the backend's host, with a check set before: the root's alone");

            // A real round over TLS on Unity's Mono, through HttpClient.
            ServicePointManager.ServerCertificateValidationCallback = null;
            int port;
            Process server = TestServer(pki, out port);
            try
            {
                HttpAnswer a = Wait(new BackendHttp("https://127.0.0.1:" + port, rootPem).GetAsync("/"));
                Check(a.Status == 200, "over TLS: the root's certificate taken, 200 (" + a.Status + " " + a.Error + ")");
                a = Wait(new BackendHttp("https://127.0.0.1:" + port, otherPem).GetAsync("/"));
                Check(a.Status == 0 && !string.IsNullOrEmpty(a.Error), "over TLS: another root's refused, Status 0 (" + a.Error + ")");
            }
            finally
            {
                server.Kill();
                ServicePointManager.ServerCertificateValidationCallback = null;
            }
#endif
        }

#if !NO_TLS
        /// <summary>
        /// The lobby's WebSocket and a match's socket over TLS on Unity's Mono, each checking the certificate on its own
        /// connection (SslStream's callback): the same test server, which answers HTTP, so the lobby's opening is
        /// refused once TLS is through, and a match waits for frames that never come.
        /// </summary>
        private static void LobbyAndMatchOverTls(string pki)
        {
            string rootPem = File.ReadAllText(Path.Combine(pki, "root/ca.pem"));
            string otherPem = File.ReadAllText(Path.Combine(pki, "other/ca.pem"));
            int port;
            Process server = TestServer(pki, out port);
            try
            {
                string why = LobbyEnd(new LobbyClient("wss://127.0.0.1:" + port + "/lobby", rootPem));
                Check(why != null && why.StartsWith("the server refused the WebSocket: HTTP/1.0 200", StringComparison.Ordinal),
                    "the lobby over TLS: the root's certificate taken, the server's answer read (" + why + ")");
                why = LobbyEnd(new LobbyClient("wss://127.0.0.1:" + port + "/lobby", otherPem));
                Check(why != null && !why.StartsWith("the server refused", StringComparison.Ordinal),
                    "the lobby over TLS: another root's refused in the handshake (" + why + ")");

                string closed = null;
                var arena = new ArenaClient(rootPem) { OnClosed = w => closed = w };
                arena.Connect("127.0.0.1", port, true, "site.internal", "FOtirryJy3sDqQntfNTLlg");
                Pump(() => closed != null, 2000, arena.Update);
                Check(closed == null, "a match over TLS: the root's certificate taken for the grant's TLS name, the connection kept (" + closed + ")");
                arena.Close();
                Pump(() => closed != null, 2000, arena.Update);
                Check(MatchEnd(otherPem, port, "site.internal"), "a match over TLS: another root's refused");
                Check(MatchEnd(rootPem, port, "a2.arena.internal"), "a match over TLS: the root's certificate for another TLS name refused");
            }
            finally
            {
                server.Kill();
            }
        }

        private static string LobbyEnd(LobbyClient lobby)
        {
            string why = null;
            lobby.OnClosed = w => why = w;
            lobby.Connect(new string('A', 43));
            Pump(() => why != null, 15000, lobby.Update);
            return why;
        }

        /// <summary>Whether a match's connection ends by itself, in its TLS handshake, before 10 s.</summary>
        private static bool MatchEnd(string caPem, int port, string tlsName)
        {
            string closed = null;
            var arena = new ArenaClient(caPem) { OnClosed = w => closed = w };
            arena.Connect("127.0.0.1", port, true, tlsName, "FOtirryJy3sDqQntfNTLlg");
            Pump(() => closed != null, 10000, arena.Update);
            if (closed == null) arena.Close();
            Console.WriteLine("    (" + tlsName + ": " + (closed ?? "still open after 10 s") + ")");
            return closed != null && closed != "closed by the client";
        }

        /// <summary>
        /// openssl's test server at TLS 1.0, which Unity's Mono outside the editor speaks, with the root's certificate
        /// for 127.0.0.1 and site.internal. It keeps no session: Mono resumes one with the same server, which sends no
        /// certificate to check again.
        /// </summary>
        private static Process TestServer(string pki, out int port)
        {
            var free = new TcpListener(IPAddress.Loopback, 0);
            free.Start();
            port = ((IPEndPoint)free.LocalEndpoint).Port;
            free.Stop();
            Process server = Process.Start(new ProcessStartInfo("openssl",
                "s_server -accept 127.0.0.1:" + port + " -tls1 -cipher DEFAULT:@SECLEVEL=0 -no_cache -no_ticket -www -quiet -cert " + Path.Combine(pki, "web/fullchain.pem")
                + " -key " + Path.Combine(pki, "web/privkey.pem")) { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true });
            for (int i = 0; i < 50 && !Accepting(port); i++) Thread.Sleep(100);
            return server;
        }

        private static bool Accepting(int port)
        {
            try { using (new TcpClient("127.0.0.1", port)) return true; }
            catch (SocketException) { return false; }
        }
#endif

        private static void CompareWelcome(byte[] frame, string what)
        {
            Core.Welcome c = Core.ServerMessages.ReadWelcome(frame);
            ArenaWelcome s = ArenaMessages.ReadWelcome(frame);
            Check(s.SnapshotHz == c.SnapshotHz && s.MapWidth == c.MapWidth && s.MapHeight == c.MapHeight && s.Mode == c.Mode
                  && s.ContentVersion == c.ContentVersion && s.PhraseListVersion == c.PhraseListVersion
                  && s.SelfEntityId == c.SelfEntityId && s.ResumeSecret == c.ResumeSecret && s.MazeSeed == c.MazeSeed, what);
        }

#if !NO_TLS
        private static void SimpleTrust(string pki)
        {
            TrustRoot root = TrustRoot.FromPem(File.ReadAllText(Path.Combine(pki, "root/ca.pem")));
            Core.TrustRoot coreRoot = Core.TrustRoot.FromPem(File.ReadAllText(Path.Combine(pki, "root/ca.pem")));
            X509Certificate2 leaf = FirstCertificate(Path.Combine(pki, "leaf/fullchain.pem"));
            X509Certificate2 stranger = FirstCertificate(Path.Combine(pki, "other-leaf/fullchain.pem"));
            var cases = new[]
            {
                Tuple.Create(leaf, "127.0.0.1", true), Tuple.Create(leaf, "a1.arena.internal", true),
                Tuple.Create(leaf, "A1.Arena.Internal", true), Tuple.Create(leaf, "a2.arena.internal", false),
                Tuple.Create(leaf, "127.0.0.2", false), Tuple.Create(stranger, "127.0.0.1", false),
                Tuple.Create(stranger, "a1.arena.internal", false),
            };
            foreach (var c in cases)
                Check(root.Accepts(c.Item1, c.Item2) == c.Item3 && coreRoot.Accepts(c.Item1, c.Item2) == c.Item3,
                    (c.Item3 ? "accepted: " : "refused: ") + (c.Item1 == leaf ? "the root's" : "another root's") + " certificate for " + c.Item2);
            Check(ThrowsSaying(() => TrustRoot.FromPem(File.ReadAllText(Path.Combine(pki, "ec.pem"))), "is not RSA"),
                "an EC root: refused as not RSA, as Unity 2019 cannot check it");
            Check(Throws(() => TrustRoot.FromPem("nonsense")), "no PEM: refused");
        }
#endif

        // ---------------------------------------------------------------------------------------------------- live

        private static void Live(string api, string lobbyUrl, string caFile, string wrongFile)
        {
            string ca = caFile == null ? null : File.ReadAllText(caFile);
            string wrong = wrongFile == null ? null : File.ReadAllText(wrongFile);
            string run = DateTime.UtcNow.Ticks.ToString("x").Substring(6);

            Section("the API, " + api);
            BackendHttp http = NewHttp(api, ca);
            HttpAnswer a = Wait(http.GetAsync("/v1/health"));
            Check(a.Status == 200 && Json.GetString(a.Body, "status") == "ok", "GET /v1/health: 200 ok (" + a.Status + " " + a.Error + ")");
            string username = "simple_" + run;
            a = Wait(http.PostAsync("/v1/accounts",
                "{\"username\":" + Json.Quote(username) + ",\"password\":\"example-password-1\",\"displayName\":\"Simple\"}"));
            long playerId = Json.GetLong(a.Body, "playerId");
            Check(a.Status == 201 && playerId > 0, "register: 201 and a player id (" + a.Status + " " + a.Error + ")");
            a = Wait(http.PostAsync("/v1/accounts",
                "{\"username\":" + Json.Quote(username) + ",\"password\":\"example-password-1\"}"));
            Check(a.Status == 409 && a.Error == "username_taken" && !a.Ok, "the same name again: 409, its code read (" + a.Error + ")");
            a = Wait(http.PostAsync("/v1/sessions", "{\"username\":" + Json.Quote(username) + ",\"password\":\"example-password-1\"}"));
            string token = Json.GetString(a.Body, "token");
            Check(a.Status == 200 && token != null && token.Length == 43 && Json.GetLong(a.Body, "playerId") == playerId, "log in: a token");
            http.Token = token;
            a = Wait(http.GetAsync("/v1/inventory"));
            Check(a.Status == 200 && a.Ok, "GET /v1/inventory with the token: 200 (" + a.Status + " " + a.Error + ")");
            a = Wait(http.PutAsync("/v1/accounts/name", "{\"displayName\":\"Simple Two\"}"));
            Check(a.Status == 200 && Json.GetString(a.Body, "displayName") == "Simple Two", "PUT a display name: 200 (" + a.Status + " " + a.Error + ")");
            a = Wait(http.GetAsync("/v1/nothing"));
            Check(a.Status == 404 && a.Error == "no_such_route", "a route that is not there: 404 no_such_route (" + a.Status + " " + a.Error + ")");
            BackendHttp stranger = NewHttp(api, ca);
            stranger.Token = new string('A', 43);
            a = Wait(stranger.GetAsync("/v1/inventory"));
            Check(a.Status == 401 && a.Error == "invalid_session", "a token that is no session: 401 invalid_session (" + a.Error + ")");
            a = Wait(stranger.PostAsync("/v1/guests", null));
            string guestKey = Json.GetString(a.Body, "guestKey");
            Check(a.Status == 201 && guestKey != null, "POST /v1/guests, no body: 201 and a key (" + a.Status + " " + a.Error + ")");
            a = Wait(stranger.PostAsync("/v1/sessions", "{\"guestKey\":" + Json.Quote(guestKey) + "}"));
            Check(a.Status == 200 && Json.GetString(a.Body, "token") != null, "the guest logs in by its key");

            Section("the lobby, " + lobbyUrl);
            LobbyClient lobby = NewLobby(lobbyUrl, ca);
            long authenticated = 0;
            var pushes = new List<LobbyMessage>();
            string lobbyClosed = null;
            lobby.OnAuthenticated = id => { OnMain("OnAuthenticated"); authenticated = id; };
            lobby.OnPush = m => { OnMain("OnPush"); pushes.Add(m); };
            lobby.OnClosed = why => { OnMain("the lobby's OnClosed"); lobbyClosed = why; };
            lobby.Connect(token);
            Pump(() => authenticated != 0 || lobbyClosed != null, 20000, lobby.Update);
            Check(authenticated == playerId && lobby.IsAuthenticated, "connected and authenticated: auth.ok with the player id (" + lobbyClosed + ")");
            LobbyMessage r = Ask(lobby, "ping", null);
            Check(r != null && r.Type == "ping.ok", "ping: ping.ok");
            r = Ask(lobby, "dance", null);
            Check(r != null && r.IsError && r.ErrorCode == "unknown_type", "an unknown type: error unknown_type (" + (r == null ? "none" : r.Text) + ")");
            r = Ask(lobby, "party.say", "{\"phraseId\":99}");
            Check(r != null && r.IsError && r.ErrorCode == "unknown_phrase", "a refusal of the platform's keeps its code: unknown_phrase");
            r = Ask(lobby, "match.request", null);
            DateTime lastRequest = DateTime.UtcNow;
            Check(r != null && r.Type == "match.request.ok" && r.GetString("ticketId") != null && r.GetLong("arenaPort") > 0,
                "match.request: a grant (" + (r == null ? "none" : r.Text) + ")");
            if (r == null || r.GetString("ticketId") == null) return;
            string host = r.GetString("arenaHost"), tlsName = r.GetString("tlsName"), ticket = r.GetString("ticketId");
            int port = (int)r.GetLong("arenaPort");
            bool tls = r.GetBool("tls");

#if NO_TLS
            Section("a match: " + (tls ? "the site's, through " + api + "/match/" + tlsName + " (D-115)" : host + ":" + port + ", plain"));
#else
            Section("a match: " + host + ":" + port + (tls ? " over TLS, " + (tlsName ?? host) : ", plain"));
            if (wrong != null && tls)
            {
                var fooled = new ArenaClient(wrong);
                string why = null;
                bool welcomed = false;
                fooled.OnWelcome = w => welcomed = true;
                fooled.OnClosed = s => why = s;
                fooled.Connect(host, port, true, tlsName, ticket);
                Pump(() => why != null, 20000, fooled.Update);
                Check(why != null && !welcomed, "trusting another root: closed before a Welcome (" + why + ")");
            }
#endif
            ArenaClient arena = NewArena(ca);
            ArenaWelcome welcome = null;
            int welcomes = 0, snapshots = 0, kicked = -1, rtt = -1;
            long lastTick = -1, firstResumed = -1;
            DateTime firstResumedAt = DateTime.MinValue;
            bool ticksRise = true;
            string arenaClosed = null;
            arena.OnWelcome = w => { OnMain("OnWelcome"); welcome = w; welcomes++; lastTick = -1; };
            arena.OnSnapshot = (frame, t) =>
            {
                OnMain("OnSnapshot");
                if (frame[0] != ArenaMessages.MsgSnapshot || t <= lastTick) ticksRise = false;
                if (welcomes == 2 && firstResumed < 0) { firstResumed = t; firstResumedAt = DateTime.UtcNow; }
                lastTick = t;
                snapshots++;
            };
            arena.OnPong = ms => { OnMain("OnPong"); rtt = ms; };
            arena.OnKick = reason => { OnMain("OnKick"); kicked = reason; };
            arena.OnClosed = why => { OnMain("the arena's OnClosed"); arenaClosed = why; };
            Join(arena, api, host, port, tls, tlsName, ticket);
            Pump(() => welcome != null || arenaClosed != null, 20000, arena.Update, lobby.Update);
            Check(welcome != null && welcome.Mode == 0 && welcome.MapWidth > 0 && !string.IsNullOrEmpty(welcome.ResumeSecret),
                "Join: a Welcome to the public arena, with a resume secret (" + arenaClosed + " " + kicked + ")");
            if (welcome == null) return;
            Drive(arena, 3000, lobby);
            Check(snapshots >= 15 && ticksRise && arena.ServerTick == lastTick, "inputs sent ten a second for 3 s: " + snapshots + " snapshots, each a later tick");
            Pump(() => rtt >= 0, 5000, arena.Update, lobby.Update);
            Check(rtt >= 0 && rtt < 5000, "the first Ping, by Update after the Welcome: its Pong, a round trip of " + rtt + " ms");
            // Inputs go on throughout: they acknowledge the snapshots, and without them the arena holds snapshots back.
            arena.SetBackground(true);
            Drive(arena, 700, lobby);                                    // what was on its way
            int parked = snapshots;
            Drive(arena, 1500, lobby);
            Check(snapshots - parked <= 1, "SetBackground(true): the arena stops sending, inputs or not (" + (snapshots - parked) + " in 1.5 s)");
            arena.SetBackground(false);
            Drive(arena, 1500, lobby);
            Check(snapshots > parked + 5, "SetBackground(false): snapshots again (" + (snapshots - parked) + ")");
            long tickBeforeClose = lastTick;
            DateTime closedAt = DateTime.UtcNow;
            arena.Close();
            Pump(() => arenaClosed != null, 5000, arena.Update, lobby.Update);
            Check(arenaClosed != null && arena.CanResume, "the connection lost without Leave: closed, and it can resume (" + arenaClosed + ")");
            arenaClosed = null;
            int before = snapshots;
            arena.Resume();
            Pump(() => welcomes == 2 || arenaClosed != null, 20000, arena.Update, lobby.Update);
            Drive(arena, 1500, lobby);
            Check(welcomes == 2 && snapshots > before + 5 && arenaClosed == null, "Resume: the same stay, a second Welcome, snapshots again (" + arenaClosed + ")");
            // A fresh view counts its ticks from 0 again: carried on from the last, every Input would acknowledge a
            // tick far ahead of the server's. The room runs 25 ticks a second.
            long most = firstResumed < 0 ? 0 : tickBeforeClose + (long)((firstResumedAt - closedAt).TotalSeconds * 25) + 50;
            if (tickBeforeClose > 300)
                Check(firstResumed > tickBeforeClose && firstResumed < most,
                    "after Resume the ticks go on from " + tickBeforeClose + ": " + firstResumed + ", at most " + most);
            else Console.WriteLine("  (the room's tick, " + tickBeforeClose + ", too young to tell a tick counted twice)");
            arena.Leave();
            Pump(() => arenaClosed != null, 10000, arena.Update, lobby.Update);
            Check(arenaClosed != null && !arena.CanResume && kicked == -1, "Leave: the arena closes the connection, no kick, nothing to resume (" + arenaClosed + ")");

            Section("the lobby's own ends");
            // 120 s without a frame closes a lobby connection: Update's ping every 25 s keeps it.
            Pump(() => lobbyClosed != null, (int)Math.Max(0, 125000 - (DateTime.UtcNow - lastRequest).TotalMilliseconds), lobby.Update);
            Check(lobbyClosed == null && lobby.IsAuthenticated,
                "125 s without a request of ours: still connected, its heartbeat sent (" + lobbyClosed + ")");
#if !NO_TLS
            if (wrong != null && lobbyUrl.StartsWith("wss://", StringComparison.Ordinal))
            {
                var fooled = new LobbyClient(lobbyUrl, wrong);
                string why = null;
                bool ok = false;
                fooled.OnAuthenticated = id => ok = true;
                fooled.OnClosed = s => why = s;
                fooled.Connect(token);
                Pump(() => why != null, 20000, fooled.Update);
                Check(why != null && !ok, "trusting another root: closed, never authenticated (" + why + ")");
            }
#endif
            LobbyClient second = NewLobby(lobbyUrl, ca);
            long secondId = 0;
            second.OnAuthenticated = id => secondId = id;
            second.Connect(token);
            Pump(() => lobbyClosed != null && secondId != 0, 20000, lobby.Update, second.Update);
            Check(secondId == playerId && pushes.Any(m => m.Type == "evt.session.replaced") && lobbyClosed != null && !lobby.IsAuthenticated,
                "the player connecting again: the first told evt.session.replaced, then closed (" + lobbyClosed + ")");
            string secondClosed = null;
            second.OnClosed = why => secondClosed = why;
            second.Close();
            Pump(() => secondClosed != null, 5000, second.Update);
            Check(secondClosed != null && !second.IsAuthenticated, "Close: closed (" + secondClosed + ")");
            Check(!second.Send("ping", null, m => { }), "sending once closed: not sent");

            Section("logging out");
            a = Wait(http.DeleteAsync("/v1/sessions"));
            Check(a.Status == 204 && a.Ok && a.Body == "", "DELETE /v1/sessions: 204, no body (" + a.Status + " " + a.Error + ")");
            a = Wait(http.GetAsync("/v1/inventory"));
            Check(a.Status == 401 && a.Error == "invalid_session", "the token afterwards: 401 invalid_session");
        }

        // Where the two clients differ: client-simple takes the backend's root, client-simple-no-tls has none (D-114).
#if NO_TLS
        private static BackendHttp NewHttp(string api, string ca) => new BackendHttp(api);
        private static LobbyClient NewLobby(string url, string ca) => new LobbyClient(url);
        private static ArenaClient NewArena(string ca) => new ArenaClient();
        // A grant asking for TLS is the site's: through its 80, by the arena's name (D-115).
        private static void Join(ArenaClient arena, string api, string host, int port, bool tls, string tlsName, string ticket)
        {
            if (tls) arena.ConnectThroughSite(api, tlsName, ticket);
            else arena.Connect(host, port, ticket);
        }
#else
        private static BackendHttp NewHttp(string api, string ca) => new BackendHttp(api, ca);
        private static LobbyClient NewLobby(string url, string ca) => new LobbyClient(url, ca);
        private static ArenaClient NewArena(string ca) => new ArenaClient(ca);
        private static void Join(ArenaClient arena, string api, string host, int port, bool tls, string tlsName, string ticket) => arena.Connect(host, port, tls, tlsName, ticket);
#endif

        /// <summary>Ten inputs a second, as a script's Update sends them, for the given time; the lobby kept too.</summary>
        private static void Drive(ArenaClient arena, int ms, LobbyClient lobby)
        {
            DateTime end = DateTime.UtcNow.AddMilliseconds(ms), next = DateTime.UtcNow;
            int i = 0;
            while (DateTime.UtcNow < end)
            {
                if (DateTime.UtcNow >= next)
                {
                    arena.SendInput(ArenaMessages.MoveRight, (i++ % 60) / 10f - 3f, i % 5 == 0 ? ArenaMessages.FlagFire : 0);
                    next = next.AddMilliseconds(100);
                }
                arena.Update();
                lobby.Update();
                Thread.Sleep(16);
            }
        }

        /// <summary>An API call's answer. A script awaits it instead; the check, with no frames to keep going, waits.</summary>
        private static HttpAnswer Wait(Task<HttpAnswer> call) => call.Wait(30000) ? call.Result : new HttpAnswer { Error = "no answer in 30 s" };

        private static LobbyMessage Ask(LobbyClient lobby, string type, string data)
        {
            LobbyMessage reply = null;
            bool sent = lobby.Send(type, data, m => { OnMain("a lobby reply"); reply = m; });
            if (!sent) return null;
            Pump(() => reply != null, 15000, lobby.Update);
            return reply;
        }

        /// <summary>Unity's frame loop: Update() every 16 ms until the condition holds or the time is up.</summary>
        private static void Pump(Func<bool> until, int ms, params Action[] updates)
        {
            DateTime end = DateTime.UtcNow.AddMilliseconds(ms);
            while (!until() && DateTime.UtcNow < end)
            {
                foreach (Action update in updates) update();
                Thread.Sleep(16);
            }
            foreach (Action update in updates) update();
        }

        private static void OnMain(string what)
        {
            if (Thread.CurrentThread.ManagedThreadId != _mainThread) Fail(what + " ran off the thread that called Update()");
        }

        // ------------------------------------------------------------------------------------------------- helpers

        /// <summary>A message as a frame carries it, its type first, without the length.</summary>
        private static byte[] Frame(params byte[] message) => message;

        private static void WriteFrame(Stream s, byte[] message)
        {
            for (uint n = (uint)message.Length; ; n >>= 7)
            {
                if (n < 0x80) { s.WriteByte((byte)n); break; }
                s.WriteByte((byte)(n & 0x7F | 0x80));
            }
            s.Write(message, 0, message.Length);
        }

        private static bool Same(byte[] a, byte[] b) => a != null && b != null && a.SequenceEqual(b);

        private static bool Throws(Action action)
        {
            try { action(); return false; }
            catch (Exception) { return true; }
        }

        private static bool ThrowsSaying(Action action, string text)
        {
            try { action(); return false; }
            catch (Exception e) { return e.Message.Contains(text); }
        }

        private static X509Certificate2 FirstCertificate(string pemFile)
        {
            string pem = File.ReadAllText(pemFile);
            int from = pem.IndexOf("-----BEGIN CERTIFICATE-----", StringComparison.Ordinal) + 27;
            int to = pem.IndexOf("-----END CERTIFICATE-----", from, StringComparison.Ordinal);
            return new X509Certificate2(Convert.FromBase64String(new string(pem.Substring(from, to - from).Where(c => !char.IsWhiteSpace(c)).ToArray())));
        }
    }
}
