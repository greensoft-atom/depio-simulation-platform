using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using Backend.Client.Core;

namespace Backend.Client.UnityRuntimeCheck
{
    /// <summary>
    /// The core on Unity 2019.4's own Mono (D-108), where the runtime differs from .NET 8's: the certificate check
    /// with certificates scripts/private-ca.sh makes, a root of another key refused, JSON's numbers under a locale
    /// that writes a decimal comma, and each connection's TLS check end to end, the API through HttpWebRequest, the
    /// lobby's wss:// and a match, against a TLS server here. Outside the editor Mono's TLS is its own, TLS 1.0, not
    /// Unity's: the handshake differs, the callbacks and the check are the same code. C# 7.3, as Unity 2019 compiles.
    ///
    ///   check.exe work-dir      as unity2019-check.sh makes it: private-ca.sh's root/, leaf/, other/ and other-leaf/,
    ///                           an EC root (ec.pem), and the two leaves with their keys (leaf.p12, other.p12, "check")
    /// Nothing is started from here: Mono outside the editor never sees a child's output end.
    /// </summary>
    internal static class Program
    {
        private static int _failed;

        private static int Main(string[] args)
        {
            string work = args[0];
            var trust = TrustRoot.FromPem(File.ReadAllText(work + "/root/ca.pem"));
            X509Certificate2 leaf = FirstOf(work + "/leaf/fullchain.pem"), otherLeaf = FirstOf(work + "/other-leaf/fullchain.pem");

            Console.WriteLine("  certificates from private-ca.sh:");
            Check(trust.Accepts(leaf, "a1.arena.internal"), "its own, for its DNS name");
            Check(trust.Accepts(leaf, "127.0.0.1"), "its own, for its IP address");
            Check(!trust.Accepts(leaf, "a2.arena.internal"), "its own, for another name: refused");
            Check(!trust.Accepts(otherLeaf, "a1.arena.internal"), "another root's: refused");
            string refusal = null;
            try { TrustRoot.FromPem(File.ReadAllText(work + "/ec.pem")); }
            catch (ArgumentException e) { refusal = e.Message; }
            Check(refusal != null && refusal.Contains("not RSA"), "an EC root refused, saying why: " + refusal);

            Console.WriteLine("  JSON under de-DE, which writes 1,5:");
            Thread.CurrentThread.CurrentCulture = new CultureInfo("de-DE");
            JsonValue parsed = JsonValue.Parse("{\"x\":1.5,\"n\":-3}");
            Check(Math.Abs(parsed["x"].AsFloat - 1.5f) < 1e-6 && parsed["n"].AsLong == -3, "read: " + parsed);
            Check(parsed.ToString().Contains("1.5"), "written with a point: " + parsed);
            Thread.CurrentThread.CurrentCulture = CultureInfo.InvariantCulture;

            var ours = new X509Certificate2(work + "/leaf.p12", "check");
            var theirs = new X509Certificate2(work + "/other.p12", "check");

            Console.WriteLine("  each connection's TLS, through a server of Mono's own TLS:");
            using (var front = new Front(ours))
            {
                var shop = new ApiClient(new[] { "https://127.0.0.1:" + front.Port }, trust.HttpHandler()).Shop().Result;
                Check(shop.Ok, "the API through HttpWebRequest, its certificate checked: " + shop.Status + " " + shop.Message);
                Check(front.Saw("GET /v1/shop HTTP/1.1"), "  the request arrived");

                using (var lobby = new LobbyClient(new Uri("wss://127.0.0.1:" + front.Port + "/lobby"), new SystemClock(), trust))
                {
                    lobby.Connect("a-token");
                    Check(front.Saw("\"t\":\"auth\""), "the lobby over wss://, its first message: the token");
                }

                using (var m = new MatchConnection(new MatchSettings
                {
                    Host = "127.0.0.1", Port = front.Port, Tls = true, TlsName = "a1.arena.internal", Trust = trust,
                }, new SystemClock()))
                {
                    m.Join("TicketNumberOne");
                    var waited = Stopwatch.StartNew();
                    bool joined = false;
                    while (!joined && waited.ElapsedMilliseconds < 10000)
                    {
                        m.Poll();
                        joined = front.Saw("TicketNumberOne", 20);
                    }
                    Check(joined, "a match over TLS as a1.arena.internal: the join arrived");
                }
            }
            using (var front = new Front(theirs))
            {
                var shop = new ApiClient(new[] { "https://127.0.0.1:" + front.Port }, trust.HttpHandler()).Shop().Result;
                Check(shop.Unreachable, "the API to another root's certificate: refused (" + shop.Message + ")");
                using (var lobby = new LobbyClient(new Uri("wss://127.0.0.1:" + front.Port + "/lobby"), new SystemClock(), trust))
                {
                    lobby.Connect("a-token");
                    Check(!front.Saw("\"t\":\"auth\"", 2000), "the lobby to another root's: nothing sent");
                }
            }

            Console.WriteLine(_failed == 0 ? "  every check passed" : "  " + _failed + " FAILED");
            return _failed == 0 ? 0 : 1;
        }

        private static void Check(bool ok, string what)
        {
            Console.WriteLine((ok ? "    ok   " : "    FAIL ") + what);
            if (!ok) _failed++;
        }

        private static X509Certificate2 FirstOf(string pem) =>
            new X509Certificate2(Encoding.ASCII.GetBytes(File.ReadAllText(pem).Split(new[] { "-----END CERTIFICATE-----" },
                StringSplitOptions.None)[0] + "-----END CERTIFICATE-----\n"));

        /// <summary>
        /// A TLS server of Mono's own: an HTTP request answered with an empty shop, a WebSocket opened and its first
        /// frame read, or a match's first bytes; what it read kept, as text.
        /// </summary>
        private sealed class Front : IDisposable
        {
            private readonly TcpListener _listener = new TcpListener(IPAddress.Loopback, 0);
            private readonly X509Certificate2 _certificate;
            private readonly StringBuilder _seen = new StringBuilder();

            public Front(X509Certificate2 certificate)
            {
                _certificate = certificate;
                _listener.Start();
                new Thread(Accept) { IsBackground = true }.Start();
            }

            public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

            public bool Saw(string text, int ms = 5000)
            {
                var waited = Stopwatch.StartNew();
                do
                {
                    lock (_seen) if (_seen.ToString().Contains(text)) return true;
                    Thread.Sleep(10);
                } while (waited.ElapsedMilliseconds < ms);
                return false;
            }

            private void Accept()
            {
                try
                {
                    while (true)
                    {
                        TcpClient c = _listener.AcceptTcpClient();
                        new Thread(() => Serve(c)) { IsBackground = true }.Start();
                    }
                }
                catch (Exception) { }                           // stopped
            }

            private void Serve(TcpClient c)
            {
                try
                {
                    using (var tls = new SslStream(c.GetStream(), false))
                    {
                        tls.AuthenticateAsServer(_certificate, false, SslProtocols.Tls | SslProtocols.Tls11 | SslProtocols.Tls12, false);
                        var first = new byte[1];
                        if (tls.Read(first, 0, 1) != 1) return;
                        if (first[0] != 'G')                     // a match: its join frame, the ticket in it
                        {
                            var rest = new byte[256];
                            int n = tls.Read(rest, 0, rest.Length);
                            lock (_seen) _seen.Append(Encoding.ASCII.GetString(rest, 0, n));
                            return;
                        }
                        string head = "G" + Head(tls);
                        lock (_seen) _seen.Append(head);
                        if (head.Contains("Upgrade: websocket")) WebSocket(tls, head);
                        else
                        {
                            byte[] answer = Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                + "Content-Length: 13\r\nConnection: close\r\n\r\n{\"offers\":[]}");
                            tls.Write(answer, 0, answer.Length);
                        }
                    }
                }
                catch (Exception) { }                           // a client that refused the certificate
            }

            private void WebSocket(SslStream tls, string head)
            {
                string key = null;
                foreach (string line in head.Split(new[] { "\r\n" }, StringSplitOptions.None))
                    if (line.StartsWith("Sec-WebSocket-Key:", StringComparison.OrdinalIgnoreCase)) key = line.Substring(18).Trim();
                string accept;
                using (SHA1 sha = SHA1.Create())
                    accept = Convert.ToBase64String(sha.ComputeHash(Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
                byte[] answer = Encoding.ASCII.GetBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n");
                tls.Write(answer, 0, answer.Length);
                // One client frame: masked, short.
                var h = new byte[2];
                Exactly(tls, h, 2);
                int length = h[1] & 0x7F;
                var mask = new byte[4];
                Exactly(tls, mask, 4);
                var payload = new byte[length];
                Exactly(tls, payload, length);
                for (int i = 0; i < length; i++) payload[i] ^= mask[i & 3];
                lock (_seen) _seen.Append(Encoding.UTF8.GetString(payload));
            }

            private static void Exactly(Stream s, byte[] b, int n)
            {
                for (int got = 0; got < n;)
                {
                    int r = s.Read(b, got, n - got);
                    if (r <= 0) throw new EndOfStreamException();
                    got += r;
                }
            }

            private static string Head(Stream s)
            {
                var head = new StringBuilder();
                while (!head.ToString().EndsWith("\r\n\r\n"))
                {
                    int b = s.ReadByte();
                    if (b < 0) break;
                    head.Append((char)b);
                }
                return head.ToString();
            }

            public void Dispose() => _listener.Stop();
        }
    }
}
