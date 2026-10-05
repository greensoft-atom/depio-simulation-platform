using System.Collections.Generic;

namespace Backend.Client.Core
{
    /// <summary>
    /// The player's party as the lobby tells it (04 §4, D-74). Answers and pushes come by different paths,
    /// and two changes at once are told by two threads, so a state can arrive after a newer one: each carries
    /// its party's version, and one naming the party the last applied state named is applied only if newer.
    /// A party left, by its end or by another's state, is remembered with the last version seen, so a late
    /// state of it, its end included, is not applied in the next one. A state with no version, 0, from a
    /// platform before D-74, is applied as it comes.
    /// </summary>
    public sealed class PartyState
    {
        private const int Remembered = 16;

        private string _named;              // the party the last applied state named, or ended
        private long _version;
        private readonly Dictionary<string, long> _left = new Dictionary<string, long>();
        private readonly Queue<string> _leftOrder = new Queue<string>();

        /// <summary>The party: null for none.</summary>
        public PartyInfo Party { get; private set; }

        /// <summary>Applies a state as platform sends it, unless it is an older state of the same party, or a late one of a party left; true if applied.</summary>
        public bool Apply(JsonValue d)
        {
            string named = d["partyId"].AsString ?? d["was"].AsString;
            long version = d["version"].AsLong;
            bool none = d["partyId"].IsNull;
            if (version > 0)
            {
                if (named == _named && version <= _version) return false;
                if (named != null && _left.TryGetValue(named, out long last) && version <= last) return false;
                // The end of a party other than the one held: the player is in another now, and it would empty that.
                if (none && named != null && _named != null && named != _named && Party != null)
                {
                    Leave(named, version);
                    return false;
                }
            }
            if (_named != null && named != _named) Leave(_named, _version);
            Party = ApiClient.ReadParty(d);
            _named = named;
            _version = version;
            if (none && named != null) Leave(named, version);
            return true;
        }

        private void Leave(string partyId, long version)
        {
            if (_left.TryGetValue(partyId, out long known))
            {
                if (version > known) _left[partyId] = version;
                return;
            }
            _left[partyId] = version;
            _leftOrder.Enqueue(partyId);
            if (_leftOrder.Count > Remembered) _left.Remove(_leftOrder.Dequeue());
        }
    }
}
