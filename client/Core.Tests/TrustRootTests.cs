using System;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>A root and the certificates it signs, as scripts/private-ca.sh makes them (D-82).</summary>
    internal static class TestPki
    {
        public static X509Certificate2 Root(string name = "backend test root")
        {
            using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
            var request = new CertificateRequest($"CN={name}", key, HashAlgorithmName.SHA256);
            request.CertificateExtensions.Add(new X509BasicConstraintsExtension(true, true, 0, true));
            request.CertificateExtensions.Add(new X509KeyUsageExtension(
                X509KeyUsageFlags.KeyCertSign | X509KeyUsageFlags.CrlSign, true));
            request.CertificateExtensions.Add(new X509SubjectKeyIdentifierExtension(request.PublicKey, false));
            return request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-10), DateTimeOffset.UtcNow.AddYears(10));
        }

        /// <summary>A server's certificate, with its key, naming those DNS names and IP addresses.</summary>
        public static X509Certificate2 Leaf(X509Certificate2 root, DateTimeOffset notAfter, params string[] names)
        {
            using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
            var request = new CertificateRequest("CN=backend", key, HashAlgorithmName.SHA256);
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
            using X509Certificate2 signed = request.Create(root, DateTimeOffset.UtcNow.AddDays(-2), notAfter, serial);
            // A key exported and read back: SslStream on Linux wants one it can use without the ephemeral handle.
            return new X509Certificate2(signed.CopyWithPrivateKey(key).Export(X509ContentType.Pkcs12));
        }

        public static X509Certificate2 Leaf(X509Certificate2 root, params string[] names) =>
            Leaf(root, DateTimeOffset.UtcNow.AddDays(30), names);

        public static string Pem(X509Certificate2 c) =>
            "-----BEGIN CERTIFICATE-----\n" + Convert.ToBase64String(c.RawData, Base64FormattingOptions.InsertLineBreaks)
            + "\n-----END CERTIFICATE-----\n";
    }

    /// <summary>
    /// The app's trust in the backend's own root (D-82): what it accepts and refuses, and a match's TLS sent
    /// under the grant's name, as the front's nginx routes by it.
    /// </summary>
    public class TrustRootTests
    {
        private static readonly X509Certificate2 Root = TestPki.Root();

        [Test]
        public void ACertificateItsRootSignedIsAccepted()
        {
            var trust = new TrustRoot(Root);
            // What a handshake reports for a root the device does not know: chain errors, which are this check's to judge.
            Assert.That(trust.Accepts(TestPki.Leaf(Root, "a1.arena.internal", "203.0.113.10"),
                SslPolicyErrors.RemoteCertificateChainErrors), Is.True);
            Assert.That(trust.Accepts(TestPki.Leaf(Root, "203.0.113.10"), SslPolicyErrors.None), Is.True);
        }

        [Test]
        public void AnythingElseIsRefused()
        {
            var trust = new TrustRoot(Root);
            Assert.That(trust.Accepts(TestPki.Leaf(TestPki.Root("someone else"), "a1.arena.internal"),
                SslPolicyErrors.RemoteCertificateChainErrors), Is.False, "another root's");
            Assert.That(trust.Accepts(TestPki.Leaf(Root, "a1.arena.internal"),
                SslPolicyErrors.RemoteCertificateNameMismatch | SslPolicyErrors.RemoteCertificateChainErrors), Is.False,
                "ours, for another name");
            Assert.That(trust.Accepts(TestPki.Leaf(Root, DateTimeOffset.UtcNow.AddDays(-1), "a1.arena.internal"),
                SslPolicyErrors.RemoteCertificateChainErrors), Is.False, "ours, expired");
            Assert.That(trust.Accepts(null, SslPolicyErrors.RemoteCertificateNotAvailable), Is.False, "none");
        }

        [Test]
        public void TheRootIsReadFromItsPem()
        {
            TrustRoot read = TrustRoot.FromPem(TestPki.Pem(Root));
            Assert.That(read.Accepts(TestPki.Leaf(Root, "a1.arena.internal"), SslPolicyErrors.RemoteCertificateChainErrors),
                Is.True);
            Assert.Throws<ArgumentException>(() => TrustRoot.FromPem("not a certificate"));
        }

        [Test]
        public void AMatchDialsTheHostAndGivesTheGrantsNameInItsHandshake()
        {
            using var front = new TlsFront(TestPki.Leaf(Root, "a1.arena.internal"));
            var clock = new ManualClock();
            using (var m = new MatchConnection(new MatchSettings
                   {
                       Host = "127.0.0.1", Port = front.Port, Tls = true, TlsName = "a1.arena.internal",
                       CertificateCheck = new TrustRoot(Root).Check,
                   }, clock))
            {
                m.Join("TicketNumberOne");
                Assert.That(front.Names.TryTake(out string name, 5_000), Is.True, "no handshake reached the front");
                Assert.That(name, Is.EqualTo("a1.arena.internal"), "the name nginx routes by");
                // Sent from Poll, on the main thread, as a frame loop would.
                int read = 0;
                var waited = System.Diagnostics.Stopwatch.StartNew();
                while (read == 0 && waited.ElapsedMilliseconds < 5_000)
                {
                    m.Poll();
                    front.Bytes.TryTake(out read, 20);
                }
                Assert.That(read, Is.GreaterThan(0), "the join, through TLS");
            }

            // Without the name it is checked against the address dialled, which the certificate does not name.
            using (var m = new MatchConnection(new MatchSettings
                   {
                       Host = "127.0.0.1", Port = front.Port, Tls = true, CertificateCheck = new TrustRoot(Root).Check,
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
                Assert.That(front.Bytes.TryTake(out int after, 200) && after > 0, Is.False,
                    "nothing sent to a server it does not trust: its connection ends with none");
            }
        }

        /// <summary>The front's nginx, as far as a handshake goes: the name asked for, then what arrived through TLS.</summary>
        private sealed class TlsFront : IDisposable
        {
            private readonly TcpListener _listener = new TcpListener(IPAddress.Loopback, 0);
            private readonly X509Certificate2 _certificate;
            public readonly BlockingCollection<string> Names = new BlockingCollection<string>();
            public readonly BlockingCollection<int> Bytes = new BlockingCollection<int>();

            public TlsFront(X509Certificate2 certificate)
            {
                _certificate = certificate;
                _listener.Start();
                new Thread(Accept) { IsBackground = true }.Start();
            }

            public int Port => ((IPEndPoint)_listener.LocalEndpoint).Port;

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
                    var buffer = new byte[4096];
                    int n = tls.Read(buffer, 0, buffer.Length);
                    Bytes.Add(n);
                }
                catch (Exception) { }       // a client that refused the certificate
            }

            public void Dispose() => _listener.Stop();
        }
    }
}
