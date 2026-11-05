using System;
using System.Collections.Concurrent;
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
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>
    /// The tests' certificates, files Pki/make.sh made as scripts/private-ca.sh makes the site's, RSA (D-108): .NET
    /// Framework 4.7.1 and Unity 2019 have no CertificateRequest to make them here (D-109).
    /// </summary>
    internal static class TestPki
    {
        private static string Path(string file) =>
            System.IO.Path.Combine(TestContext.CurrentContext.TestDirectory, "Pki", file);

        /// <summary>A certificate: root, other-root, twin-root (our root's name, another key), ec-root, leaf, and the rest.</summary>
        public static X509Certificate2 Cert(string name) => new X509Certificate2(File.ReadAllBytes(Path(name + ".pem")));

        public static string Pem(string name) => File.ReadAllText(Path(name + ".pem"));

        /// <summary>A test server's certificate with its key: server-arena, server-local, other-local.</summary>
        public static X509Certificate2 Server(string name) => new X509Certificate2(Path(name + ".p12"), "test");
    }

    /// <summary>
    /// The app's trust in the backend's own root (D-82), checked by the client itself (D-108): what it accepts and
    /// refuses, and each connection checked for its own host: a match under the grant's name, the API and the lobby
    /// under theirs.
    /// </summary>
    public class TrustRootTests
    {
        private static TrustRoot Trust() => new TrustRoot(TestPki.Cert("root"));

        [Test]
        public void ACertificateItsRootSignedIsAcceptedForEachNameItHolds()
        {
            TrustRoot trust = Trust();
            X509Certificate2 leaf = TestPki.Cert("leaf");
            Assert.That(trust.Accepts(leaf, "a1.arena.internal"), Is.True, "a DNS name");
            Assert.That(trust.Accepts(leaf, "A1.Arena.Internal"), Is.True, "a DNS name, in any case");
            Assert.That(trust.Accepts(leaf, "203.0.113.10"), Is.True, "an IPv4 address");
            Assert.That(trust.Accepts(leaf, "2001:db8::7"), Is.True, "an IPv6 address");
        }

        [Test]
        public void AnythingElseIsRefused()
        {
            TrustRoot trust = Trust();
            X509Certificate2 ours = TestPki.Cert("leaf");
            Assert.That(trust.Accepts(TestPki.Cert("other-leaf"), "a1.arena.internal"), Is.False, "another root's");
            Assert.That(trust.Accepts(TestPki.Cert("twin-leaf"), "a1.arena.internal"), Is.False,
                "another root of the same name: its key signed nothing of ours");
            Assert.That(trust.Accepts(ours, "a2.arena.internal"), Is.False, "ours, for another name");
            Assert.That(trust.Accepts(ours, "203.0.113.11"), Is.False, "ours, for another address");
            Assert.That(trust.Accepts(ours, "arena.internal"), Is.False, "ours, for a part of a name");
            Assert.That(trust.Accepts(TestPki.Cert("expired"), "a1.arena.internal"), Is.False, "ours, expired");
            Assert.That(trust.Accepts(TestPki.Cert("not-yet"), "a1.arena.internal"), Is.False, "ours, not yet valid");
            Assert.That(trust.Accepts(TestPki.Cert("sha384"), "a1.arena.internal"), Is.False, "ours, signed with SHA-384");
            byte[] altered = ours.RawData;
            altered[altered.Length - 1] ^= 1;                   // the signature's last byte
            Assert.That(trust.Accepts(new X509Certificate2(altered), "a1.arena.internal"), Is.False, "ours, altered");
            Assert.That(trust.Accepts(null, "a1.arena.internal"), Is.False, "none");
            Assert.That(trust.Accepts(ours, null), Is.False, "no host");
        }

        [Test]
        public void YearsPastTwentyTwentyNineAreReadAsWritten()
        {
            // UTCTime until 2049, GeneralizedTime from 2050 (RFC 5280); Mono reads two-digit years up to 2029 alone.
            TrustRoot trust = Trust();
            Assert.That(trust.Accepts(TestPki.Cert("until-2035"), "a1.arena.internal"), Is.True, "until 2035, as UTCTime 35");
            Assert.That(trust.Accepts(TestPki.Cert("until-2051"), "a1.arena.internal"), Is.True, "until 2051, as GeneralizedTime");
        }

        [Test]
        public void ARootOfAnotherKeyIsRefusedSayingWhy()
        {
            var e = Assert.Throws<ArgumentException>(() => new TrustRoot(TestPki.Cert("ec-root")));
            Assert.That(e.Message, Does.Contain("not RSA"));
        }

        [Test]
        public void TheRootIsReadFromItsPem()
        {
            TrustRoot read = TrustRoot.FromPem(TestPki.Pem("root"));
            Assert.That(read.Accepts(TestPki.Cert("leaf"), "a1.arena.internal"), Is.True);
            Assert.Throws<ArgumentException>(() => TrustRoot.FromPem("not a certificate"));
        }

        [Test]
        public void AMatchDialsTheHostAndGivesTheGrantsNameInItsHandshake()
        {
            using (var front = new TlsFront(TestPki.Server("server-arena"), TlsFront.ReadSome))
            {
                var clock = new ManualClock();
                using (var m = new MatchConnection(new MatchSettings
                       {
                           Host = "127.0.0.1", Port = front.Port, Tls = true, TlsName = "a1.arena.internal", Trust = Trust(),
                       }, clock))
                {
                    m.Join("TicketNumberOne");
                    // Sent from Poll, on the main thread, as a frame loop would.
                    string read = null;
                    var waited = System.Diagnostics.Stopwatch.StartNew();
                    while (read == null && waited.ElapsedMilliseconds < 5_000)
                    {
                        m.Poll();
                        front.Received.TryTake(out read, 20);
                    }
                    Assert.That(read, Is.Not.Null.And.Not.Empty, "the join, through TLS, checked for the grant's name");
#if NETCOREAPP
                    // What the handshake named, which nginx routes by: a server sees it on .NET's own API alone.
                    Assert.That(front.Names.TryTake(out string name, 5_000), Is.True, "no handshake reached the front");
                    Assert.That(name, Is.EqualTo("a1.arena.internal"), "the name nginx routes by");
#endif
                }

                // Without the name it is checked against the address dialled, which the certificate does not name.
                using (var m = new MatchConnection(new MatchSettings
                       {
                           Host = "127.0.0.1", Port = front.Port, Tls = true, Trust = Trust(),
                       }, clock))
                {
                    m.Join("TicketNumberOne");
                    var waited = System.Diagnostics.Stopwatch.StartNew();
                    while (m.State != MatchState.Ended && waited.ElapsedMilliseconds < 5_000)
                    {
                        m.Poll();
                        Thread.Sleep(20);
                    }
                    Assert.That((m.State, m.End), Is.EqualTo((MatchState.Ended, MatchEnd.Unreachable)));
                    Assert.That(front.Received.TryTake(out string after, 200), Is.False,
                        "nothing sent to a server it does not trust: its connection ends with none");
                }
            }
        }

        [Test]
        public void TheApiIsCheckedForItsOwnHost()
        {
            // The handler a players' build makes the ApiClient with: its requests through HttpWebRequest (D-108).
            using (var front = new TlsFront(TestPki.Server("server-local"), TlsFront.AnswerHttp))
            {
                ApiResult<System.Collections.Generic.List<Offer>> shop =
                    new ApiClient(new[] { "https://127.0.0.1:" + front.Port }, Trust().HttpHandler()).Shop().Result;
                Assert.That((shop.Ok, shop.Status), Is.EqualTo((true, 200)), shop.Message);
                Assert.That(front.Received.TryTake(out string request, 1_000) && request.StartsWith("GET /v1/shop HTTP/1.1"), Is.True);
            }
            using (var other = new TlsFront(TestPki.Server("other-local"), TlsFront.AnswerHttp))
            {
                ApiResult<System.Collections.Generic.List<Offer>> refused =
                    new ApiClient(new[] { "https://127.0.0.1:" + other.Port }, Trust().HttpHandler()).Shop().Result;
                Assert.That(refused.Unreachable, Is.True, "another root's certificate: no answer taken");
                Assert.That(other.Received.TryTake(out string _, 200), Is.False, "nothing sent to it");
            }
        }

        [Test]
        public void TheLobbyOverTlsIsCheckedForItsOwnHost()
        {
            var clock = new ManualClock();
            using (var front = new TlsFront(TestPki.Server("server-local"), TlsFront.AnswerWebSocket))
            using (var lobby = new LobbyClient(new Uri("wss://127.0.0.1:" + front.Port + "/lobby"), clock, Trust()))
            {
                lobby.Connect("a-token");
                Assert.That(front.Received.TryTake(out string first, 5_000), Is.True, "no message reached the lobby");
                Assert.That(JsonValue.Parse(first)["t"].AsString, Is.EqualTo("auth"), "the token, over wss://");
            }
            using (var other = new TlsFront(TestPki.Server("other-local"), TlsFront.AnswerWebSocket))
            using (var lobby = new LobbyClient(new Uri("wss://127.0.0.1:" + other.Port + "/lobby"), clock, Trust()))
            {
                lobby.Connect("a-token");
                Assert.That(other.Received.TryTake(out string _, 1_000), Is.False, "nothing sent to a lobby it does not trust");
            }
        }

        /// <summary>The front's nginx, as far as a handshake goes: then what arrived through TLS, as text.</summary>
        private sealed class TlsFront : IDisposable
        {
            private readonly TcpListener _listener = new TcpListener(IPAddress.Loopback, 0);
            private readonly X509Certificate2 _certificate;
            private readonly Action<SslStream, BlockingCollection<string>> _serve;
            public readonly BlockingCollection<string> Names = new BlockingCollection<string>();
            public readonly BlockingCollection<string> Received = new BlockingCollection<string>();

            public TlsFront(X509Certificate2 certificate, Action<SslStream, BlockingCollection<string>> serve)
            {
                _certificate = certificate;
                _serve = serve;
                _listener.Start();
                new Thread(Accept) { IsBackground = true }.Start();
            }

            public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

            /// <summary>A match's bytes, as they came.</summary>
            public static void ReadSome(SslStream tls, BlockingCollection<string> received)
            {
                var buffer = new byte[4096];
                int n = tls.Read(buffer, 0, buffer.Length);
                if (n > 0) received.Add(Convert.ToBase64String(buffer, 0, n));
            }

            /// <summary>One HTTP request's head, answered with an empty shop.</summary>
            public static void AnswerHttp(SslStream tls, BlockingCollection<string> received)
            {
                // A client that refused the certificate closes after the server's handshake ended: no request.
                string head = Head(tls);
                if (head.Length == 0) return;
                received.Add(head);
                byte[] answer = Encoding.ASCII.GetBytes(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 13\r\nConnection: close\r\n\r\n{\"offers\":[]}");
                tls.Write(answer, 0, answer.Length);
            }

            /// <summary>A WebSocket opened as the gateway does, and its first message, one masked frame of text.</summary>
            public static void AnswerWebSocket(SslStream tls, BlockingCollection<string> received)
            {
                string head = Head(tls);
                if (head.Length == 0) return;
                received.Add(WebSocketServer.Open(tls, head));
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
                catch (Exception) { }       // stopped
            }

            private void Serve(TcpClient c)
            {
                try
                {
                    using (var tls = new SslStream(c.GetStream(), false))
                    {
#if NETCOREAPP
                        tls.AuthenticateAsServer(new SslServerAuthenticationOptions
                        {
                            ServerCertificateSelectionCallback = (sender, name) =>
                            {
                                Names.Add(name ?? "");
                                return _certificate;
                            },
                        });
#else
                        // What each runtime speaks: .NET Framework TLS 1.2, Mono outside Unity TLS 1.0.
                        tls.AuthenticateAsServer(_certificate, false, SslProtocols.Tls | SslProtocols.Tls11 | SslProtocols.Tls12, false);
#endif
                        _serve(tls, Received);
                    }
                }
                catch (Exception) { }       // a client that refused the certificate
            }

            public void Dispose() => _listener.Stop();
        }
    }

    /// <summary>
    /// A lobby's side of a WebSocket, the tests' own (D-109): .NET Framework and Mono have no WebSocket server but
    /// Windows' HttpListener. The handshake answered, then frames read, a client's masked.
    /// </summary>
    internal static class WebSocketServer
    {
        public const string Guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        public static string Accept(string key)
        {
            using (SHA1 sha = SHA1.Create())
                return Convert.ToBase64String(sha.ComputeHash(Encoding.ASCII.GetBytes(key + Guid)));
        }

        public static string KeyOf(string head)
        {
            foreach (string line in head.Split(new[] { "\r\n" }, StringSplitOptions.None))
                if (line.StartsWith("Sec-WebSocket-Key:", StringComparison.OrdinalIgnoreCase)) return line.Substring(18).Trim();
            return null;
        }

        /// <summary>Answers the opening with the given accept, then reads the client's first text message.</summary>
        public static string Open(Stream s, string head, Func<string, string> accept = null)
        {
            string key = KeyOf(head);
            byte[] answer = Encoding.ASCII.GetBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + (accept ?? Accept)(key) + "\r\n\r\n");
            s.Write(answer, 0, answer.Length);
            int op;
            byte[] payload = ReadFrame(s, out op);
            return Encoding.UTF8.GetString(payload);
        }

        /// <summary>One frame from a client, unmasked; a client's frames are masked (RFC 6455).</summary>
        public static byte[] ReadFrame(Stream s, out int op)
        {
            var h = new byte[2];
            Exactly(s, h, 2);
            op = h[0] & 0x0F;
            Assert.That(h[1] & 0x80, Is.EqualTo(0x80), "a client's frame is masked");
            long length = h[1] & 0x7F;
            if (length == 126)
            {
                var b = new byte[2];
                Exactly(s, b, 2);
                length = (b[0] << 8) | b[1];
            }
            else if (length == 127)
            {
                var b = new byte[8];
                Exactly(s, b, 8);
                length = 0;
                for (int i = 0; i < 8; i++) length = (length << 8) | b[i];
            }
            var mask = new byte[4];
            Exactly(s, mask, 4);
            var payload = new byte[length];
            Exactly(s, payload, payload.Length);
            for (int i = 0; i < payload.Length; i++) payload[i] ^= mask[i & 3];
            return payload;
        }

        public static void Exactly(Stream s, byte[] b, int n)
        {
            for (int got = 0; got < n;)
            {
                int r = s.Read(b, got, n - got);
                if (r <= 0) throw new EndOfStreamException("the client's connection ended");
                got += r;
            }
        }
    }
}
