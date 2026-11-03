using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>A players' build connects encrypted alone (D-104).</summary>
    public class EncryptedOnlyTests
    {
        private static readonly string[] Site = { "https://203.0.113.10" };

        [Test]
        public void EncryptedAddressesAccepted()
        {
            Assert.That(EncryptedOnly.Refusal(Site, "wss://203.0.113.10/lobby"), Is.Null);
            Assert.That(EncryptedOnly.Refusal(new[] { "https://203.0.113.10", "HTTPS://203.0.113.11:8443/" },
                "WSS://203.0.113.10/lobby"), Is.Null, "a scheme in capitals is the same scheme");
        }

        [TestCase("http://203.0.113.10", "wss://203.0.113.10/lobby", "http://203.0.113.10", Description = "the API plain")]
        [TestCase("https://203.0.113.10,http://203.0.113.11", "wss://203.0.113.10/lobby", "http://203.0.113.11",
            Description = "a plain endpoint after an encrypted one")]
        [TestCase("https://203.0.113.10", "ws://203.0.113.10/lobby", "ws://203.0.113.10/lobby", Description = "the lobby plain")]
        [TestCase("https://203.0.113.10", "https://203.0.113.10/lobby", "https://203.0.113.10/lobby",
            Description = "the lobby not a WebSocket")]
        [TestCase("203.0.113.10", "wss://203.0.113.10/lobby", "203.0.113.10", Description = "no scheme")]
        [TestCase("https://203.0.113.10", "", "the lobby", Description = "no lobby")]
        public void PlainOrUnreadableAddressRefused(string endpoints, string lobby, string named)
        {
            string refusal = EncryptedOnly.Refusal(endpoints.Split(','), lobby);
            Assert.That(refusal, Does.Contain(named));
            Assert.That(refusal, Does.Contain("D-104"));
        }

        [Test]
        public void NoEndpointRefused()
        {
            Assert.That(EncryptedOnly.Refusal(new string[0], "wss://203.0.113.10/lobby"), Does.Contain("no API endpoint"));
            Assert.That(EncryptedOnly.Refusal(null, "wss://203.0.113.10/lobby"), Does.Contain("no API endpoint"));
        }

        [Test]
        public void AGrantWithoutTlsRefused()
        {
            var grant = new MatchGrant { ArenaHost = "203.0.113.10", ArenaPort = 9001, TicketId = "t", TlsName = "a1.arena.internal" };
            grant.Tls = true;
            Assert.That(EncryptedOnly.Refusal(grant), Is.Null);
            grant.Tls = false;
            Assert.That(EncryptedOnly.Refusal(grant), Does.Contain("203.0.113.10:9001").And.Contain("without TLS"));
        }
    }
}
