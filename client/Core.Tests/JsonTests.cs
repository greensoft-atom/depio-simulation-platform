using System;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    public class JsonTests
    {
        [Test]
        public void TheApisAnswersAreRead()
        {
            // As platform answers a login and gateway a match request.
            var login = JsonValue.Parse("{\"token\":\"abc_-12\",\"playerId\":18303,\"expiresInSeconds\":86400}");
            Assert.That((login["token"].AsString, login["playerId"].AsLong, login["expiresInSeconds"].AsInt),
                Is.EqualTo(("abc_-12", 18303L, 86400)));
            var push = JsonValue.Parse(" { \"t\" : \"match.request.ok\", \"id\": 2, \"d\": {\"arenaHost\":\"10.0.0.7\",\"tls\":true,\"x\":null,\"list\":[1, [2], {}]} } ");
            Assert.That(push["d"]["arenaHost"].AsString, Is.EqualTo("10.0.0.7"));
            Assert.That(push["d"]["tls"].AsBool, Is.True);
            Assert.That(push["d"]["x"].IsNull, Is.True);
            Assert.That(push["d"]["absent"].IsNull, Is.True, "an absent member reads as null, not a throw");
            Assert.That(push["d"]["list"].Count, Is.EqualTo(3));
            Assert.That(push["d"]["list"][1][0].AsLong, Is.EqualTo(2));
            Assert.That(JsonValue.Parse("-0.5e3").Kind, Is.EqualTo(JsonValue.Kinds.Number));
        }

        [Test]
        public void EscapesAndNamesInAnyScriptSurviveARoundTrip()
        {
            var v = JsonValue.Parse("\"Zo\\u00eb \\ud83d\\ude00 \\\"q\\\" \\\\ \\/ \\n\"");
            Assert.That(v.AsString, Is.EqualTo("Zoë 😀 \"q\" \\ / \n"));
            var o = JsonValue.NewObject().Set("name", "Zoë\t\"x\"\u0001").Set("n", 42);
            var back = JsonValue.Parse(o.ToString());
            Assert.That(back["name"].AsString, Is.EqualTo("Zoë\t\"x\"\u0001"));
            Assert.That(back["n"].AsLong, Is.EqualTo(42));
            Assert.That(o.ToString(), Does.Contain("\\u0001"), "a control character is escaped, never raw");
        }

        [TestCase("{\"a\":1} x", Description = "trailing content")]
        [TestCase("01")]
        [TestCase("1.")]
        [TestCase(".5")]
        [TestCase("+1")]
        [TestCase("\"no end")]
        [TestCase("\"a\u0001b\"", Description = "a raw control character")]
        [TestCase("\"\\q\"", Description = "an unknown escape")]
        [TestCase("\"\\u12G4\"")]
        [TestCase("\"\\u 123\"")]
        [TestCase("\u0661", Description = "a digit of another script")]
        [TestCase("{\"a\" 1}")]
        [TestCase("[1,]")]
        [TestCase("tru")]
        [TestCase("")]
        public void WhatIsNotJsonIsRefused(string text)
        {
            Assert.Throws<FormatException>(() => JsonValue.Parse(text));
        }

        [Test]
        public void NestingIsBounded()
        {
            Assert.DoesNotThrow(() => JsonValue.Parse(new string('[', 30) + new string(']', 30)));
            Assert.Throws<FormatException>(() => JsonValue.Parse(new string('[', 100) + new string(']', 100)));
        }
    }
}
