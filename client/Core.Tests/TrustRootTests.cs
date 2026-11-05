using System;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>A root and the certificates it signs, as scripts/private-ca.sh makes them: RSA (D-82, D-108).</summary>
    internal static class TestPki
    {
        public static X509Certificate2 Root(string name = "backend test root", int years = 10)
        {
            using var key = RSA.Create(2048);
            return Ca(new CertificateRequest($"CN={name}", key, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1), years);
        }

        /// <summary>A root of another key, as the backend's was before D-108.</summary>
        public static X509Certificate2 EcRoot()
        {
            using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
            return Ca(new CertificateRequest("CN=backend test root", key, HashAlgorithmName.SHA256), 10);
        }

        private static X509Certificate2 Ca(CertificateRequest request, int years)
        {
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(true, true, 0, true));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(
                X509KeyUsageFlags.KeyCertSign | X509KeyUsageFlags.CrlSign, true));
            request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));
            return request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-10), DateTimeOffset.UtcNow.AddYears(years));
        }

        /// <summary>A server's certificate, with its key, naming those DNS names and IP addresses.</summary>
        public static X509Certificate2 Leaf(X509Certificate2 root, DateTimeOffset notBefore, DateTimeOffset notAfter,
            HashAlgorithmName hash, params string[] names)
        {
            using var key = RSA.Create(2048);
            var request = new CertificateRequest("CN=backend", key, hash, RSASignaturePadding.Pkcs1);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, true));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature, true));
            request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
                new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, false));
            var san = new SubjectAlternativeNameBuilder();
            foreach (string n in names)
            {
                if (IPAddress.TryParse(n, out IPAddress ip)) san.AddIpAddress(ip);
                else san.AddDnsName(n);
            }
            request.CertificateExtensions.Add(san.Build());
            byte[] serial = Guid.NewGuid().ToByteArray();
            using X509Certificate2 signed = request.Create(root, notBefore, notAfter, serial);
            // A key exported and read back: SslStream on Linux wants one it can use without the ephemeral handle.
            return new X509Certificate2(signed.CopyWithPrivateKey(key).Export(X509ContentType.Pkcs12));
        }

        public static X509Certificate2 Leaf(X509Certificate2 root, DateTimeOffset notAfter, params string[] names) =>
            Leaf(root, DateTimeOffset.UtcNow.AddDays(-2), notAfter, HashAlgorithmName.SHA256, names);

        public static X509Certificate2 Leaf(X509Certificate2 root, params string[] names) =>
            Leaf(root, DateTimeOffset.UtcNow.AddDays(30), names);

        public static string Pem(X509Certificate2 c) =>
            "-----BEGIN CERTIFICATE-----\n" + Convert.ToBase64String(c.RawData, Base64FormattingOptions.InsertLineBreaks)
            + "\n-----END CERTIFICATE-----\n";
    }

    /// <summary>
    /// The app's trust in the backend's own root (D-82), checked by the client itself (D-108): what it accepts and
    /// refuses, and each connection checked for its own host: a match under the grant's name, the API and the lobby
    /// under theirs.
    /// </summary>
    public class TrustRootTests
    {
        private static readonly X509Certificate2 Root = TestPki.Root();

        [Test]
        public void ACertificateItsRootSignedIsAcceptedForEachNameItHolds()
        {
            var trust = new TrustRoot(Root);
            X509Certificate2 leaf = TestPki.Leaf(Root, "a1.arena.internal", "203.0.113.10", "2001:db8::7");
            Assert.That(trust.Accepts(leaf, "a1.arena.internal"), Is.True, "a DNS name");
            Assert.That(trust.Accepts(leaf, "A1.Arena.Internal"), Is.True, "a DNS name, in any case");
            Assert.That(trust.Accepts(leaf, "203.0.113.10"), Is.True, "an IPv4 address");
            Assert.That(trust.Accepts(leaf, "2001:db8::7"), Is.True, "an IPv6 address");
        }

        [Test]
        public void AnythingElseIsRefused()
        {
            var trust = new TrustRoot(Root);
            X509Certificate2 ours = TestPki.Leaf(Root, "a1.arena.internal", "203.0.113.10");
            Assert.That(trust.Accepts(TestPki.Leaf(TestPki.Root("someone else"), "a1.arena.internal"), "a1.arena.internal"),
                Is.False, "another root's");
            Assert.That(trust.Accepts(TestPki.Leaf(TestPki.Root(), "a1.arena.internal"), "a1.arena.internal"),
                Is.False, "another root of the same name: its key signed nothing of ours");
            Assert.That(trust.Accepts(ours, "a2.arena.internal"), Is.False, "ours, for another name");
            Assert.That(trust.Accepts(ours, "203.0.113.11"), Is.False, "ours, for another address");
            Assert.That(trust.Accepts(ours, "arena.internal"), Is.False, "ours, for a part of a name");
            Assert.That(trust.Accepts(TestPki.Leaf(Root, DateTimeOffset.UtcNow.AddDays(-1), "a1.arena.internal"),
                "a1.arena.internal"), Is.False, "ours, expired");
            Assert.That(trust.Accepts(TestPki.Leaf(Root, DateTimeOffset.UtcNow.AddDays(1), DateTimeOffset.UtcNow.AddDays(30),
                HashAlgorithmName.SHA256, "a1.arena.internal"), "a1.arena.internal"), Is.False, "ours, not yet valid");
            Assert.That(trust.Accepts(TestPki.Leaf(Root, DateTimeOffset.UtcNow.AddDays(-2), DateTimeOffset.UtcNow.AddDays(30),
                HashAlgorithmName.SHA384, "a1.arena.internal"), "a1.arena.internal"), Is.False, "ours, signed with SHA-384");
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
            X509Certificate2 root = TestPki.Root(years: 40);
            var trust = new TrustRoot(root);
            Assert.That(trust.Accepts(TestPki.Leaf(root, new DateTimeOffset(2035, 1, 1, 0, 0, 0, TimeSpan.Zero), "a1.arena.internal"),
                "a1.arena.internal"), Is.True, "until 2035, as UTCTime 35");
            Assert.That(trust.Accepts(TestPki.Leaf(root, new DateTimeOffset(2051, 1, 1, 0, 0, 0, TimeSpan.Zero), "a1.arena.internal"),
                "a1.arena.internal"), Is.True, "until 2051, as GeneralizedTime");
        }

        [Test]
        public void ARootOfAnotherKeyIsRefusedSayingWhy()
        {
            var e = Assert.Throws<ArgumentException>(() => new TrustRoot(TestPki.EcRoot()));
            Assert.That(e.Message, Does.Contain("not RSA"));
        }

        [Test]
        public void TheRootIsReadFromItsPem()
        {
            TrustRoot read = TrustRoot.FromPem(TestPki.Pem(Root));
            Assert.That(read.Accepts(TestPki.Leaf(Root, "a1.arena.internal"), "a1.arena.internal"), Is.True);
            Assert.Throws<ArgumentException>(() => TrustRoot.FromPem("not a certificate"));
        }

        [Test]
        public void AMatchDialsTheHostAndGivesTheGrantsNameInItsHandshake()
        {
            using var front = new TlsFront(TestPki.Leaf(Root, "a1.arena.internal"), TlsFront.ReadSome);
            var clock = new ManualClock();
            using (var m = new MatchConnection(new MatchSettings
                   {
                       Host = "127.0.0.1", Port = front.Port, Tls = true, TlsName = "a1.arena.internal",
                       Trust = new TrustRoot(Root),
                   }, clock))
            {
                m.Join("TicketNumberOne");
                Assert.That(front.Names.TryTake(out string name, 5_000), Is.True, "no handshake reached the front");
                Assert.That(name, Is.EqualTo("a1.arena.internal"), "the name nginx routes by");
                // Sent from Poll, on the main thread, as a frame loop would.
                string read = null;
                var waited = System.Diagnostics.Stopwatch.StartNew();
                while (read == null && waited.ElapsedMilliseconds < 5_000)
                {
                    m.Poll();
                    front.Received.TryTake(out read, 20);
                }
                Assert.That(read, Is.Not.Null.And.Not.Empty, "the join, through TLS");
            }

            // Without the name it is checked against the address dialled, which the certificate does not name.
            using (var m = new MatchConnection(new MatchSettings
                   {
                       Host = "127.0.0.1", Port = front.Port, Tls = true, Trust = new TrustRoot(Root),
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

        [Test]
        public void TheApiIsCheckedForItsOwnHost()
        {
            // The handler a players' build makes the ApiClient with: its requests through HttpWebRequest (D-108).
            using var front = new TlsFront(TestPki.Leaf(Root, "127.0.0.1"), TlsFront.AnswerHttp);
            ApiResult<System.Collections.Generic.List<Offer>> shop =
                new ApiClient(new[] { $"https://127.0.0.1:{front.Port}" }, new TrustRoot(Root).HttpHandler()).Shop().Result;
            Assert.That((shop.Ok, shop.Status), Is.EqualTo((true, 200)), shop.Message);
            Assert.That(front.Received.TryTake(out string request, 1_000) && request.StartsWith("GET /v1/shop HTTP/1.1"), Is.True);

            using var other = new TlsFront(TestPki.Leaf(TestPki.Root("someone else"), "127.0.0.1"), TlsFront.AnswerHttp);
            ApiResult<System.Collections.Generic.List<Offer>> refused =
                new ApiClient(new[] { $"https://127.0.0.1:{other.Port}" }, new TrustRoot(Root).HttpHandler()).Shop().Result;
            Assert.That(refused.Unreachable, Is.True, "another root's certificate: no answer taken");
            Assert.That(other.Received.TryTake(out _, 200), Is.False, "nothing sent to it");
        }

        [Test]
        public void TheLobbyOverTlsIsCheckedForItsOwnHost()
        {
            using var front = new TlsFront(TestPki.Leaf(Root, "127.0.0.1"), TlsFront.AnswerWebSocket);
            var clock = new ManualClock();
            using (var lobby = new LobbyClient(new Uri($"wss://127.0.0.1:{front.Port}/lobby"), clock, new TrustRoot(Root)))
            {
                lobby.Connect("a-token");
                Assert.That(front.Received.TryTake(out string first, 5_000), Is.True, "no message reached the lobby");
                Assert.That(JsonValue.Parse(first)["t"].AsString, Is.EqualTo("auth"), "the token, over wss://");
            }

            using var other = new TlsFront(TestPki.Leaf(TestPki.Root("someone else"), "127.0.0.1"), TlsFront.AnswerWebSocket);
            using (var lobby = new LobbyClient(new Uri($"wss://127.0.0.1:{other.Port}/lobby"), clock, new TrustRoot(Root)))
            {
                lobby.Connect("a-token");
                Assert.That(other.Received.TryTake(out _, 1_000), Is.False, "nothing sent to a lobby it does not trust");
            }
        }

        /// <summary>The front's nginx, as far as a handshake goes: the name asked for, then what arrived through TLS.</summary>
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

            /// <summary>A WebSocket opened as the gateway does, and its first message.</summary>
            public static void AnswerWebSocket(SslStream tls, BlockingCollection<string> received)
            {
                string head = Head(tls);
                string key = null;
                foreach (string line in head.Split("\r\n"))
                    if (line.StartsWith("Sec-WebSocket-Key:", StringComparison.OrdinalIgnoreCase)) key = line.Substring(18).Trim();
                string accept = Convert.ToBase64String(SHA1.HashData(Encoding.ASCII.GetBytes(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
                byte[] answer = Encoding.ASCII.GetBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n");
                tls.Write(answer, 0, answer.Length);
                using WebSocket socket = WebSocket.CreateFromStream(tls, true, null, TimeSpan.FromSeconds(30));
                var buffer = new byte[4096];
                WebSocketReceiveResult r = socket.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None).Result;
                received.Add(Encoding.UTF8.GetString(buffer, 0, r.Count));
            }

            private static string Head(SslStream tls)
            {
                var head = new StringBuilder();
                while (!head.ToString().EndsWith("\r\n\r\n"))
                {
                    int b = tls.ReadByte();
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
                    using var tls = new SslStream(c.GetStream(), false);
                    tls.AuthenticateAsServer(new SslServerAuthenticationOptions
                    {
                        ServerCertificateSelectionCallback = (sender, name) =>
                        {
                            Names.Add(name ?? "");
                            return _certificate;
                        },
                    });
                    _serve(tls, Received);
                }
                catch (Exception) { }       // a client that refused the certificate
            }

            public void Dispose() => _listener.Stop();
        }
    }
}
