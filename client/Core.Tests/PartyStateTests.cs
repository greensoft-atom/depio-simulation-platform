using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    /// <summary>
    /// The party as the lobby tells it (04 §4, D-74, T-45): answers and pushes travel by different paths,
    /// and an older state arriving after a newer one is not applied.
    /// </summary>
    public class PartyStateTests
    {
        private static JsonValue Party(string id, long version, params long[] members)
        {
            var list = new System.Text.StringBuilder();
            foreach (long m in members) list.Append(list.Length == 0 ? "" : ",").Append($"{{\"playerId\":{m},\"name\":\"n{m}\"}}");
            return JsonValue.Parse($"{{\"partyId\":\"{id}\",\"leader\":{members[0]},\"members\":[{list}],\"version\":{version}}}");
        }

        private static JsonValue None(string was, long version) =>
            JsonValue.Parse($"{{\"partyId\":null,\"leader\":0,\"members\":[],\"was\":\"{was}\",\"version\":{version}}}");

        [Test]
        public void AnOlderStateOfTheSamePartyIsNotApplied()
        {
            var state = new PartyState();
            Assert.That(state.Apply(Party("p1", 1, 1)), Is.True);
            Assert.That(state.Apply(Party("p1", 3, 1, 2, 3)), Is.True, "the third's accept, pushed");
            Assert.That(state.Apply(Party("p1", 2, 1, 2)), Is.False, "the second's, late");
            Assert.That(state.Party.Members.Count, Is.EqualTo(3));
            Assert.That(state.Apply(Party("p1", 3, 1, 2, 3)), Is.False, "the same version again: nothing new");
            Assert.That(state.Apply(Party("p1", 4, 1, 3)), Is.True);
            Assert.That(state.Party.Members.Count, Is.EqualTo(2));
        }

        [Test]
        public void NoPartyIsKeptAgainstAnOlderStateOfTheOneItEnded()
        {
            var state = new PartyState();
            state.Apply(Party("p1", 2, 1, 2));
            Assert.That(state.Apply(None("p1", 3)), Is.True, "the party ended");
            Assert.That(state.Party, Is.Null);
            Assert.That(state.Apply(Party("p1", 2, 1, 2)), Is.False, "its older state, late");
            Assert.That(state.Party, Is.Null);
            Assert.That(state.Apply(None("p1", 2)), Is.False);
        }

        [Test]
        public void AnotherPartyOrNoneUnnamedIsApplied()
        {
            var state = new PartyState();
            state.Apply(Party("p1", 5, 1, 2));
            Assert.That(state.Apply(Party("p2", 1, 7, 1)), Is.True, "another party, whatever its version");
            Assert.That(state.Party.PartyId, Is.EqualTo("p2"));
            var unnamed = JsonValue.Parse("{\"partyId\":null,\"leader\":0,\"members\":[]}");
            Assert.That(state.Apply(unnamed), Is.True, "none, naming no party: the truth as asked for");
            Assert.That(state.Party, Is.Null);
            Assert.That(state.Apply(Party("p2", 1, 7, 1)), Is.False, "the party it was in before the truth said none, late");
            Assert.That(state.Apply(Party("p2", 2, 7, 1, 3)), Is.True, "a later state of it: joined again");
            Assert.That(state.Apply(Party("p9", 1, 9, 1)), Is.True, "and any party not seen before");
        }

        [Test]
        public void AStateOfAPartyLeftIsNotAppliedInTheNextOne()
        {
            var state = new PartyState();
            state.Apply(Party("p1", 2, 1, 7));
            state.Apply(None("p1", 3));                                 // left p1
            Assert.That(state.Apply(Party("p2", 4, 9, 7)), Is.True, "joined p2");
            Assert.That(state.Apply(Party("p1", 2, 1, 7)), Is.False, "p1's older state, late: not shown again");
            Assert.That(state.Apply(None("p1", 3)), Is.False, "nor p1's end, which would empty p2");
            Assert.That(state.Party.PartyId, Is.EqualTo("p2"));
        }

        [Test]
        public void APartyMovedOnFromWithoutItsEndIsStillKnownLeft()
        {
            var state = new PartyState();
            state.Apply(Party("p1", 2, 1, 7));
            Assert.That(state.Apply(Party("p2", 1, 7)), Is.True, "p2's state came first: its end of p1 not yet heard");
            Assert.That(state.Apply(None("p1", 3)), Is.False, "p1's end, after: p2 stays");
            Assert.That(state.Apply(Party("p1", 2, 1, 7)), Is.False);
            Assert.That(state.Party.PartyId, Is.EqualTo("p2"));
        }

        [Test]
        public void AStateWithNoVersionIsAppliedAsItComes()
        {
            var state = new PartyState();
            Assert.That(state.Apply(JsonValue.Parse("{\"partyId\":\"p1\",\"leader\":1,\"members\":[{\"playerId\":1,\"name\":\"a\"}]}")), Is.True);
            Assert.That(state.Apply(JsonValue.Parse("{\"partyId\":\"p1\",\"leader\":1,\"members\":[{\"playerId\":1,\"name\":\"a\"},"
                                                    + "{\"playerId\":2,\"name\":\"b\"}]}")), Is.True,
                "a platform before D-74 sends none: each state as it comes, as before");
            Assert.That(state.Party.Members.Count, Is.EqualTo(2));
        }
    }
}
