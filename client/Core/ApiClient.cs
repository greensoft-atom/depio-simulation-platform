using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Backend.Client.Core
{
    /// <summary>
    /// What an API call came back with. <see cref="Ok"/>, or the server's refusal with the code to
    /// branch on (the backend's API table), or <see cref="Unreachable"/> when no endpoint answered.
    /// </summary>
    public sealed class ApiResult<T>
    {
        public bool Ok;
        public T Value;
        /// <summary>The HTTP status, or 0 when nothing answered.</summary>
        public int Status;
        /// <summary>The server's code: invalid_credentials, too_many_attempts, busy, insufficient_funds, …; bad_response or timeout from this client.</summary>
        public string Code;
        public string Message;
        /// <summary>From Retry-After on a 429 or 503: how long to wait, which is not an error (08 §5).</summary>
        public int RetryAfterSeconds;
        public bool Unreachable => Status == 0;

        public override string ToString() => Ok ? $"ok {Value}" : Unreachable ? $"unreachable: {Message}" : $"{Status} {Code}: {Message}";
    }

    /// <summary>
    /// A guest (Q-22): the key to keep in the device's secure store, as a session token is, and
    /// the name it was given. The key logs it in again; lost, the guest is lost.
    /// </summary>
    public sealed class Guest
    {
        public long PlayerId;
        public string GuestKey, DisplayName;
    }

    public sealed class Session
    {
        public string Token;
        public long PlayerId;
        public int ExpiresInSeconds;
    }

    /// <summary>Where to play, and the single-use ticket to get in with.</summary>
    public sealed class MatchGrant
    {
        public string ArenaHost, TicketId;
        public int ArenaPort;
        public bool Tls;
        /// <summary>The mode of a match the matcher made ("duel"); null for a seat in the public arena.</summary>
        public string Mode;
    }

    /// <summary>Where the player stands in the queue for timed matches (04 §4).</summary>
    public sealed class QueueStatus
    {
        /// <summary>"none", "queued", "confirming" or "matched".</summary>
        public string State, Mode;
        public long WaitedSeconds;
        /// <summary>When confirming: the match asked about, and how long is left to answer it.</summary>
        public string MatchUid;
        public long SecondsLeft;
        /// <summary>When matched: where to go. What evt.match.found carried, for a client that missed it.</summary>
        public MatchGrant Grant;
    }

    /// <summary>A party (04 §4): up to three who queue together, its leader first among its members.</summary>
    public sealed class PartyInfo
    {
        public string PartyId;
        public long Leader;
        public List<PartyMember> Members = new List<PartyMember>();
    }

    public sealed class PartyMember
    {
        public long PlayerId;
        public string Name;
    }

    /// <summary>A match found, as evt.match.ready carries it: accept or decline it within its seconds (D-27).</summary>
    public sealed class MatchReady
    {
        public string MatchUid, Mode;
        public int Seconds;
    }

    /// <summary>An invitation to a party, as evt.party.invite carries it: accept it within 60 s.</summary>
    public sealed class PartyInvitation
    {
        public string PartyId, FromName;
        public long From;
    }

    /// <summary>A phrase a member said to the party (docs 01 §9), the words the phrase list's, by id.</summary>
    public sealed class PartyPhrase
    {
        public long From;
        public string Name;
        public int PhraseId;
    }

    /// <summary>
    /// What each skin number on the wire is (04 §8, D-70): the item it is, for the art drawn. Versioned by its
    /// content, as the class table is.
    /// </summary>
    public sealed class SkinTable
    {
        public long Version;
        public Dictionary<int, string> ItemIds = new Dictionary<int, string>();

        /// <summary>The item a skin number is, or null for one the table has not.</summary>
        public string ItemIdOf(int skin) => ItemIds.TryGetValue(skin, out string id) ? id : null;
    }

    /// <summary>
    /// The class table a client draws by, as platform serves it (docs 01 §4, D-24): versioned by a
    /// hash, the number a Welcome's ContentVersion names. Keep it until a Welcome names another.
    /// </summary>
    public sealed class ClassTableInfo
    {
        public long Version;
        public List<ClassInfo> Classes = new List<ClassInfo>();
    }

    /// <summary>
    /// What a player may say, as platform serves it (docs 01 §9): versioned by a hash, the number a
    /// Welcome's PhraseListVersion names. Keep it until a Welcome names another.
    /// </summary>
    public sealed class PhraseListInfo
    {
        public long Version;
        public List<PhraseInfo> Phrases = new List<PhraseInfo>();
    }

    /// <summary>One phrase: its id on the wire, the key a translation is looked up by, and the English.</summary>
    public sealed class PhraseInfo
    {
        public int Id;
        public string Key = "", Text = "";
    }

    /// <summary>One class: what a tank of it looks like, fires, and may become.</summary>
    public sealed class ClassInfo
    {
        public int Id, OpensAt, MaxDrones, HidesAfterTicks;
        public string Name;
        public int[] Parents, Caps;
        /// <summary>Multipliers: on the view, the reload, the body's damage and its size (30 units).</summary>
        public float Fov, Reload, BodyDamage, BodySize;
        /// <summary>How far ahead the view moves while zoom is held; 0 for none.</summary>
        public float Zoom;
        public List<BarrelInfo> Barrels = new List<BarrelInfo>();
    }

    /// <summary>One barrel; its Kind is the unit subtype of what it launches (0 a bullet).</summary>
    public sealed class BarrelInfo
    {
        /// <summary>Radians from the aim, units sideways, and a fraction of the reload.</summary>
        public float Angle, Side, Delay;
        public float Speed, Damage, Penetration, Spread, Size, Recoil;
        /// <summary>A turret's arc either side of its line; 0 for a barrel that fires along it.</summary>
        public float Arc;
        /// <summary>Ticks; int.MaxValue for a drone that lasts until used up.</summary>
        public int Lifetime, Kind;
    }

    public sealed class Offer
    {
        public string Sku, ItemId, AvailableTo;
        public long Price;
        public int RequiresLevel;
        /// <summary>"coins", or "gems" (04 §8, plan item 68).</summary>
        public string Currency;
    }

    public sealed class Holding
    {
        public string ItemId;
        public int Qty, Level;
    }

    public sealed class Holdings
    {
        public long Coins, Gems;
        public List<Holding> Items = new List<Holding>();
    }

    /// <summary>An item raised a level (04 §8): the level it reached, and the coins left.</summary>
    public sealed class ItemLevel
    {
        public string ItemId;
        public int Level;
        public long Coins;
    }

    public sealed class Receipt
    {
        /// <summary>"bought", or "already_bought" when the key had bought before: a retry.</summary>
        public string Result, ItemId;
        public long Coins, Gems;
        public int Held;
    }

    /// <summary>A pack of gems sold for money (04 §8, revenue): its price in cents of <see cref="Currency"/>, US dollars.</summary>
    public sealed class Pack
    {
        public string ProductId, Currency;
        public int Gems, PriceCents;
    }

    /// <summary>
    /// An order for a pack (04 §8, D-68): <see cref="State"/> pending, paid, declined, refunded or expired;
    /// <see cref="Bonus"/> the first purchase's gems.
    /// </summary>
    public sealed class PaymentOrder
    {
        public string OrderId, ProductId, Currency, State, CreatedAt;
        public int Gems, Bonus, PriceCents;
    }

    /// <summary>The provider's word on an order: <see cref="Confirmed"/> false when it was no longer pending; the gems after.</summary>
    public sealed class PaymentResult
    {
        public PaymentOrder Order;
        public bool Confirmed;
        public long Gems;
    }

    /// <summary>A season pass tier's reward on one track (04 §8, D-69): <see cref="ItemId"/> null when it gives none.</summary>
    public sealed class PassReward
    {
        public long Coins;
        public int Gems;
        public string ItemId;
    }

    public sealed class PassTier
    {
        public int Tier;
        public PassReward Free, Premium;
    }

    /// <summary>
    /// The season being played's pass: a tier is reached at <see cref="TierPoints"/> points each, and paid once
    /// reached; a premium tier once premium is bought too, for <see cref="PremiumGems"/>.
    /// </summary>
    public sealed class PassInfo
    {
        public int Season, Tier, PremiumGems, TierPoints;
        public string EndsAt;
        public long Points;
        public bool Premium;
        public List<PassTier> Tiers = new List<PassTier>();
    }

    /// <summary>Premium's answer: "bought" or "already_bought"; the gems after; the pass after.</summary>
    public sealed class PremiumAnswer
    {
        public string Result;
        public long Gems;
        public PassInfo Pass;
    }

    /// <summary>What is worn (04 §8): each slot's item id, or null; and the bonus a stat by its name, capped.</summary>
    public sealed class Loadout
    {
        public Dictionary<string, string> Slots = new Dictionary<string, string>();
        public Dictionary<string, int> Bonus = new Dictionary<string, int>();
    }

    /// <summary>A boost running (04 §8): its kind, xp or coins, item, percent and end, an ISO instant.</summary>
    public sealed class ActiveBoost
    {
        public string Kind, ItemId, EndsAt;
        public int Percent;
    }

    /// <summary>The boosts running; after an activation, its result too: "activated" or "already_activated".</summary>
    public sealed class BoostsAnswer
    {
        public string Result;
        public List<ActiveBoost> Boosts = new List<ActiveBoost>();
    }

    /// <summary>
    /// A team (04 §2): its members, leader first, each role "leader", "vice_leader" or "member";
    /// and its record in team matches (04 §4, the sixth slice).
    /// </summary>
    public sealed class TeamInfo
    {
        public long Id;
        public string Name;
        public List<TeamMemberInfo> Members = new List<TeamMemberInfo>();
        public int Rating, Wins, Losses, Draws;
    }

    /// <summary>A rename (04 §1): the name as the server stored it, its spaces collapsed.</summary>
    public sealed class Renamed
    {
        public long PlayerId;
        public string DisplayName;
    }

    public sealed class TeamMemberInfo
    {
        public long PlayerId;
        public string Name, Role;
    }

    /// <summary>A team as anyone may see it, found by its name (Q-49).</summary>
    public sealed class TeamFound
    {
        public long Id;
        public string Name;
        public int Members, Rating;
    }

    /// <summary>An application to the player's team, as its leader or a vice leader sees it (Q-49).</summary>
    public sealed class TeamApplicant
    {
        public long PlayerId;
        public string Name, ExpiresAt;
    }

    public sealed class TeamInvite
    {
        public long TeamId;
        public string TeamName, ExpiresAt;
    }

    /// <summary>
    /// A tournament (04 §6): "registration", "seeded", "running", "finished" or "cancelled"; its
    /// entries by seed once seeded, and its bracket, every round's matches, once seeded. Times are
    /// ISO instants; the prizes are coins for places 1, 2 and 3.
    /// </summary>
    public sealed class TournamentInfo
    {
        public long Id;
        /// <summary>"duel", or "teams": then its entries are teams, and its matches team matches (Q-19).</summary>
        public string Mode;
        public string Name, State, RegistrationEnds, StartsAt;
        public int MaxEntries, RoundMinutes, CurrentRound;
        public long[] Prizes = new long[3];
        public List<TournamentEntry> Entries = new List<TournamentEntry>();
        public List<BracketMatch> Matches = new List<BracketMatch>();
        /// <summary>"elimination", or "round_robin": then <see cref="Standings"/>, by place (04 §6).</summary>
        public string Format;
        public List<TournamentStanding> Standings = new List<TournamentStanding>();
    }

    /// <summary>An entry's place in a round robin: a player's, or a team's when the tournament is of teams.</summary>
    public sealed class TournamentStanding
    {
        public long PlayerId, TeamId;
        public string Name;
        public int Points, Wins, Draws, Losses;
    }

    /// <summary>
    /// An entry; <see cref="Seed"/> 0 before seeding. A duel's is a player's; a teams' tournament's a
    /// team's, <see cref="TeamId"/>, with the <see cref="Roster"/> that plays for it.
    /// </summary>
    public sealed class TournamentEntry
    {
        public long PlayerId, TeamId;
        public string Name;
        public int Seed;
        public List<RosterPlayer> Roster = new List<RosterPlayer>();
    }

    public sealed class RosterPlayer
    {
        public long PlayerId;
        public string Name;
    }

    /// <summary>A match in the bracket: "pending", "ready" or "done"; a side not known yet, or a bye's empty one, is null.</summary>
    public sealed class BracketMatch
    {
        public int Round, Slot;
        public string State;
        /// <summary>A duels' tournament's sides.</summary>
        public long? PlayerA, PlayerB, Winner;
        /// <summary>A teams' tournament's sides.</summary>
        public long? TeamA, TeamB, WinnerTeam;
    }

    /// <summary>Where to play a tournament match (evt.tournament.match, or asked for): joined as any grant.</summary>
    public sealed class TournamentGrant
    {
        public long TournamentId;
        public int Round, ArenaPort;
        public string ArenaHost, TicketId, Mode;
        public bool Tls;
    }

    /// <summary>Another player, by id and display name.</summary>
    public sealed class PlayerRef
    {
        public long PlayerId;
        public string Name;
    }

    /// <summary>A friend, and whether they are in the lobby now (04 §9).</summary>
    public sealed class FriendInfo
    {
        public long PlayerId;
        public string Name;
        public bool Online;
    }

    /// <summary>A friend request, to the player or by them, and when it lapses (an ISO instant).</summary>
    public sealed class FriendRequest
    {
        public long PlayerId;
        public string Name, ExpiresAt;
    }

    public sealed class FriendsInfo
    {
        public List<FriendInfo> Friends = new List<FriendInfo>();
        public List<FriendRequest> Requests = new List<FriendRequest>();
        public List<FriendRequest> Asked = new List<FriendRequest>();
    }

    /// <summary>
    /// An inbox item (04 §9): its kind, "friend_request", "friend_accepted", "team_invite",
    /// "tournament_prize", "team_application" or "season_reward", and the id it refers to, a player's,
    /// team's or tournament's (an applicant's, for an application; for a season's reward, the season
    /// times ten plus the board's mode, 1 duel, 2 team-vs-team, 3 ranked free-for-all, whose place
    /// <see cref="ApiClient.AroundMe"/> with that season gives). Never text: the client makes the words.
    /// </summary>
    public sealed class InboxItem
    {
        public long Id, Ref;
        public string Kind, At;
        public bool Read;
    }

    /// <summary>A board's row: a player's, or on the board of teams ("teams", 04 §7) a team's, its PlayerId 0.</summary>
    public sealed class RankRow
    {
        public long Rank, PlayerId, TeamId, Score;
        public string Name;
    }

    /// <summary>
    /// An achievement (04 §8): reached when its stat, kills, wins, matches, assists, bestScore or playtime
    /// (seconds), is at or over the threshold; paid its gems once, when a result carries the stat across it.
    /// </summary>
    public sealed class AchievementInfo
    {
        public string Id, Stat;
        public long Threshold, Progress;
        public int Gems;
        public bool Reached;
    }

    /// <summary>One of today's goals (04 §8): met when its kind's count reaches the target; play time in seconds.</summary>
    public sealed class GoalInfo
    {
        public string Id, Kind;
        public long Target, Progress;
        public int Coins;
        public bool Done;
    }

    /// <summary>Today's three goals, the UTC day and when it ends (ISO 8601), and the gems all three pay.</summary>
    public sealed class GoalsInfo
    {
        public string Day, ResetsAt;
        public readonly List<GoalInfo> Goals = new List<GoalInfo>();
        public int SetGems;
        public bool SetDone;
    }

    /// <summary>A season of the rating boards (04 §7): its number, and when it began and ends, ISO 8601 UTC.</summary>
    public sealed class SeasonInfo
    {
        public int Id;
        public string StartsAt, EndsAt;
    }

    /// <summary>The season being played, and those before it, newest first.</summary>
    public sealed class SeasonsInfo
    {
        public SeasonInfo Current;
        public readonly List<SeasonInfo> Past = new List<SeasonInfo>();
    }

    /// <summary>
    /// The platform API, over HTTPS (docs 04; the backend's API table), for the lobby screens.
    ///
    /// Three endpoints, tried in turn (D-13): one that cannot be reached, or whose certificate is
    /// refused, moves the call to the next, never to plain text. An HTTP answer is an answer,
    /// whatever its status, and moves nothing. The one that answered is tried first next time.
    /// Calls complete on the thread pool; the Unity layer hands results to the main thread.
    /// </summary>
    public sealed class ApiClient
    {
        private readonly string[] _endpoints;
        private readonly HttpClient _http;
        private int _preferred;

        /// <param name="endpoints">https://a.example.com and so on: the app's list.</param>
        /// <param name="handler">Null for the platform's own; a test's may trust a test CA.</param>
        public ApiClient(IReadOnlyList<string> endpoints, HttpMessageHandler handler = null, TimeSpan? timeout = null)
        {
            if (endpoints == null || endpoints.Count == 0) throw new ArgumentException("at least one endpoint");
            _endpoints = new string[endpoints.Count];
            for (int i = 0; i < endpoints.Count; i++) _endpoints[i] = endpoints[i].TrimEnd('/');
            _http = handler == null ? new HttpClient() : new HttpClient(handler);
            // A login may queue behind Argon2 for a while: 10 s is what a client allows (04 §1).
            _http.Timeout = timeout ?? TimeSpan.FromSeconds(10);
        }

        /// <summary>The endpoint the last call reached, for the log.</summary>
        public string Current => _endpoints[_preferred];

        public Task<ApiResult<long>> Register(string username, string displayName, string password)
        {
            var body = JsonValue.NewObject().Set("username", username).Set("password", password);
            if (!string.IsNullOrEmpty(displayName)) body.Set("displayName", displayName);
            return Call(HttpMethod.Post, "/v1/accounts", body, null, j => j["playerId"].AsLong);
        }

        public Task<ApiResult<Session>> Login(string username, string password) =>
            Call(HttpMethod.Post, "/v1/sessions", JsonValue.NewObject().Set("username", username).Set("password", password),
                null, j => new Session
                {
                    Token = j["token"].AsString,
                    PlayerId = j["playerId"].AsLong,
                    ExpiresInSeconds = j["expiresInSeconds"].AsInt,
                });

        /// <summary>A guest, made with nothing asked (04 §1).</summary>
        public Task<ApiResult<Guest>> CreateGuest() =>
            Call(HttpMethod.Post, "/v1/guests", null, null, j => new Guest
            {
                PlayerId = j["playerId"].AsLong, GuestKey = j["guestKey"].AsString, DisplayName = j["displayName"].AsString,
            });

        /// <summary>A guest's login by its key.</summary>
        public Task<ApiResult<Session>> LoginGuest(string guestKey) =>
            Call(HttpMethod.Post, "/v1/sessions", JsonValue.NewObject().Set("guestKey", guestKey), null, j => new Session
            {
                Token = j["token"].AsString,
                PlayerId = j["playerId"].AsLong,
                ExpiresInSeconds = j["expiresInSeconds"].AsInt,
            });

        /// <summary>The session's guest made a full account, the same player; its key stops working.</summary>
        public Task<ApiResult<bool>> UpgradeAccount(string token, string username, string password, string displayName)
        {
            var body = JsonValue.NewObject().Set("username", username).Set("password", password);
            if (!string.IsNullOrEmpty(displayName)) body.Set("displayName", displayName);
            return Call(HttpMethod.Post, "/v1/accounts/upgrade", body, token, _ => true);
        }

        public Task<ApiResult<bool>> Logout(string token) =>
            Call(HttpMethod.Delete, "/v1/sessions", null, token, _ => true);

        /// <summary>Straight to platform. The lobby's match.request is the other way in.</summary>
        public Task<ApiResult<MatchGrant>> RequestMatch(string token) =>
            Call(HttpMethod.Post, "/v1/match-requests", null, token, ReadGrant);

        /// <summary>Joins the queue for a timed match: "duel". The match found is pushed to the lobby.</summary>
        public Task<ApiResult<QueueStatus>> JoinQueue(string token, string mode) =>
            Call(HttpMethod.Post, "/v1/queue", JsonValue.NewObject().Set("mode", mode), token, ReadQueue);

        public Task<ApiResult<QueueStatus>> LeaveQueue(string token) =>
            Call(HttpMethod.Delete, "/v1/queue", null, token, ReadQueue);

        /// <summary>
        /// Opens a sandbox (04 §4, the seventh slice): for the caller, or the whole party when the
        /// caller leads one. Every member, the caller too, is also pushed evt.match.found.
        /// </summary>
        public Task<ApiResult<MatchGrant>> OpenSandbox(string token) =>
            Call(HttpMethod.Post, "/v1/sandbox", null, token, ReadGrant);

        /// <summary>What to ask after reconnecting the lobby: a match made meanwhile is here, grant and all.</summary>
        public Task<ApiResult<QueueStatus>> Queue(string token) =>
            Call(HttpMethod.Get, "/v1/queue", null, token, ReadQueue);

        /// <summary>The player's party, null for none: what to fetch after the lobby reconnects (04 §4).</summary>
        public Task<ApiResult<PartyInfo>> Party(string token) =>
            Call(HttpMethod.Get, "/v1/party", null, token, ReadParty);

        /// <summary>The skin table (D-70): what each skin number a tank is told with is.</summary>
        public Task<ApiResult<SkinTable>> Skins() =>
            Call(HttpMethod.Get, "/v1/content/skins", null, null, j =>
            {
                var table = new SkinTable { Version = j["version"].AsLong };
                JsonValue list = j["skins"];
                for (int i = 0; i < list.Count; i++) table.ItemIds[list[i]["skin"].AsInt] = list[i]["itemId"].AsString;
                return table;
            });

        /// <summary>The class table (D-24); fetch it when a Welcome's ContentVersion is not the one held.</summary>
        public Task<ApiResult<ClassTableInfo>> Classes() =>
            Call(HttpMethod.Get, "/v1/content/classes", null, null, j =>
            {
                var table = new ClassTableInfo { Version = j["version"].AsLong };
                JsonValue list = j["classes"];
                for (int i = 0; i < list.Count; i++)
                {
                    JsonValue c = list[i];
                    var info = new ClassInfo
                    {
                        Id = c["id"].AsInt, Name = c["name"].AsString, OpensAt = c["opensAt"].AsInt,
                        Parents = Ints(c["parents"]), Caps = Ints(c["caps"]),
                        Fov = c["fov"].AsFloat, Reload = c["reload"].AsFloat, MaxDrones = c["maxDrones"].AsInt,
                        BodyDamage = c["bodyDamage"].AsFloat, HidesAfterTicks = c["hidesAfter"].AsInt,
                        BodySize = c["bodySize"].AsFloat, Zoom = c["zoom"].AsFloat,
                    };
                    JsonValue barrels = c["barrels"];
                    for (int k = 0; k < barrels.Count; k++)
                    {
                        JsonValue b = barrels[k];
                        info.Barrels.Add(new BarrelInfo
                        {
                            Angle = b["angle"].AsFloat, Side = b["side"].AsFloat, Delay = b["delay"].AsFloat,
                            Speed = b["speed"].AsFloat, Damage = b["damage"].AsFloat,
                            Penetration = b["penetration"].AsFloat, Lifetime = b["lifetime"].AsInt,
                            Spread = b["spread"].AsFloat, Size = b["size"].AsFloat, Recoil = b["recoil"].AsFloat,
                            Kind = b["kind"].AsInt, Arc = b["arc"].AsFloat,
                        });
                    }
                    table.Classes.Add(info);
                }
                return table;
            });

        /// <summary>The phrase list; fetch it when a Welcome's PhraseListVersion is not the one held.</summary>
        public Task<ApiResult<PhraseListInfo>> Phrases() =>
            Call(HttpMethod.Get, "/v1/content/phrases", null, null, j =>
            {
                var list = new PhraseListInfo { Version = j["version"].AsLong };
                JsonValue phrases = j["phrases"];
                for (int i = 0; i < phrases.Count; i++)
                {
                    JsonValue p = phrases[i];
                    list.Phrases.Add(new PhraseInfo { Id = p["id"].AsInt, Key = p["key"].AsString, Text = p["text"].AsString });
                }
                return list;
            });

        private static int[] Ints(JsonValue list)
        {
            var values = new int[list.Count];
            for (int i = 0; i < values.Length; i++) values[i] = list[i].AsInt;
            return values;
        }

        public Task<ApiResult<List<Offer>>> Shop() =>
            Call(HttpMethod.Get, "/v1/shop", null, null, j =>
            {
                var offers = new List<Offer>();
                JsonValue list = j["offers"];
                for (int i = 0; i < list.Count; i++)
                {
                    JsonValue o = list[i];
                    offers.Add(new Offer
                    {
                        Sku = o["sku"].AsString, ItemId = o["itemId"].AsString, Price = o["price"].AsLong,
                        RequiresLevel = o["requiresLevel"].AsInt, AvailableTo = o["availableTo"].AsString,
                        Currency = o["currency"].IsNull ? "coins" : o["currency"].AsString,
                    });
                }
                return offers;
            });

        public Task<ApiResult<Holdings>> Inventory(string token) =>
            Call(HttpMethod.Get, "/v1/inventory", null, token, j =>
            {
                var h = new Holdings { Coins = j["coins"].AsLong, Gems = j["gems"].AsLong };
                JsonValue items = j["items"];
                for (int i = 0; i < items.Count; i++)
                    h.Items.Add(new Holding { ItemId = items[i]["itemId"].AsString, Qty = items[i]["qty"].AsInt, Level = items[i]["level"].AsInt });
                return h;
            });

        /// <summary>
        /// Raises an item held a level, once a key (made as a purchase's, <see cref="NewPurchaseKey"/>): 404 not_held,
        /// 409 max_level or insufficient_funds, 400 invalid_key.
        /// </summary>
        public Task<ApiResult<ItemLevel>> RaiseItemLevel(string token, string itemId, string key) =>
            Call(HttpMethod.Post, "/v1/inventory/" + Uri.EscapeDataString(itemId) + "/level", JsonValue.NewObject().Set("key", key),
                token, j => new ItemLevel { ItemId = j["itemId"].AsString, Level = j["level"].AsInt, Coins = j["coins"].AsLong });

        /// <summary>
        /// One tap of Buy. <paramref name="key"/> is made when the tap happens and sent unchanged
        /// with every retry of it, so the tap is charged once (04 §8); <see cref="NewPurchaseKey"/>.
        /// </summary>
        public Task<ApiResult<Receipt>> Purchase(string token, string sku, string key) =>
            Call(HttpMethod.Post, "/v1/purchases", JsonValue.NewObject().Set("sku", sku).Set("key", key), token, j => new Receipt
            {
                Result = j["result"].AsString, ItemId = j["itemId"].AsString, Coins = j["coins"].AsLong, Held = j["held"].AsInt,
                Gems = j["gems"].AsLong,
            });

        /// <summary>The packs of gems on sale for money; 503 payments_off where no provider is named: show no store.</summary>
        public Task<ApiResult<List<Pack>>> Packs() =>
            Call(HttpMethod.Get, "/v1/payments/packs", null, null, j =>
            {
                var packs = new List<Pack>();
                JsonValue list = j["packs"];
                for (int i = 0; i < list.Count; i++)
                    packs.Add(new Pack
                    {
                        ProductId = list[i]["productId"].AsString, Gems = list[i]["gems"].AsInt,
                        PriceCents = list[i]["priceCents"].AsInt, Currency = list[i]["currency"].AsString,
                    });
                return packs;
            });

        /// <summary>
        /// One tap of Buy on a pack: an order, pending. <paramref name="key"/> is made when the tap happens and sent
        /// unchanged with every retry, so the tap is one order (D-68); <see cref="NewPurchaseKey"/>. 409 refund_debt.
        /// </summary>
        public Task<ApiResult<PaymentOrder>> PlaceOrder(string token, string productId, string key) =>
            Call(HttpMethod.Post, "/v1/payments", JsonValue.NewObject().Set("productId", productId).Set("key", key), token,
                j => ReadOrder(j["order"]));

        /// <summary>Where the player's order is; 404 no_such_order.</summary>
        public Task<ApiResult<PaymentOrder>> GetOrder(string token, string orderId) =>
            Call(HttpMethod.Get, "/v1/payments/" + Uri.EscapeDataString(orderId), null, token, j => ReadOrder(j["order"]));

        /// <summary>
        /// The simulated provider's page (D-68): the player pays, or declines, and the order is confirmed so, once.
        /// A real provider, when there is one, takes this call's place.
        /// </summary>
        public Task<ApiResult<PaymentResult>> SimulatePayment(string token, string orderId, bool paid) =>
            Call(HttpMethod.Post, "/v1/payments/" + Uri.EscapeDataString(orderId) + "/simulate",
                JsonValue.NewObject().Set("outcome", paid ? "paid" : "declined"), token,
                j => new PaymentResult { Order = ReadOrder(j["order"]), Confirmed = j["confirmed"].AsBool, Gems = j["gems"].AsLong });

        private static PaymentOrder ReadOrder(JsonValue o) => new PaymentOrder
        {
            OrderId = o["orderId"].AsString, ProductId = o["productId"].AsString, Gems = o["gems"].AsInt, Bonus = o["bonus"].AsInt,
            PriceCents = o["priceCents"].AsInt, Currency = o["currency"].AsString, State = o["state"].AsString,
            CreatedAt = o["createdAt"].AsString,
        };

        /// <summary>The player's pass in the season being played, its forty tiers listed (04 §8, D-69).</summary>
        public Task<ApiResult<PassInfo>> SeasonPass(string token) =>
            Call(HttpMethod.Get, "/v1/pass", null, token, ReadPass);

        /// <summary>
        /// Buys the premium track for the season being played with gems, paying the premium tiers reached;
        /// a second tap is answered "already_bought". 409 insufficient_funds or season_ended.
        /// </summary>
        public Task<ApiResult<PremiumAnswer>> BuyPremium(string token) =>
            Call(HttpMethod.Post, "/v1/pass/premium", null, token,
                j => new PremiumAnswer { Result = j["result"].AsString, Gems = j["gems"].AsLong, Pass = ReadPass(j["pass"]) });

        private static PassInfo ReadPass(JsonValue j)
        {
            var pass = new PassInfo
            {
                Season = j["season"].AsInt, EndsAt = j["endsAt"].AsString, Points = j["points"].AsLong, Tier = j["tier"].AsInt,
                Premium = j["premium"].AsBool, PremiumGems = j["premiumGems"].AsInt, TierPoints = j["tierPoints"].AsInt,
            };
            JsonValue tiers = j["tiers"];
            for (int i = 0; i < tiers.Count; i++)
                pass.Tiers.Add(new PassTier
                {
                    Tier = tiers[i]["tier"].AsInt, Free = ReadReward(tiers[i]["free"]), Premium = ReadReward(tiers[i]["premium"]),
                });
            return pass;
        }

        private static PassReward ReadReward(JsonValue r) =>
            new PassReward { Coins = r["coins"].AsLong, Gems = r["gems"].AsInt, ItemId = r["itemId"].AsString };

        /// <summary>What is worn, and the bonus it gives.</summary>
        public Task<ApiResult<Loadout>> Equipment(string token) =>
            Call(HttpMethod.Get, "/v1/equipment", null, token, ReadLoadout);

        /// <summary>Wears an item held in its slot: barrel, armor, core, treads or skin.</summary>
        public Task<ApiResult<Loadout>> Wear(string token, string slot, string itemId) =>
            Call(HttpMethod.Put, "/v1/equipment/" + Uri.EscapeDataString(slot), JsonValue.NewObject().Set("itemId", itemId),
                token, ReadLoadout);

        public Task<ApiResult<Loadout>> TakeOff(string token, string slot) =>
            Call(HttpMethod.Delete, "/v1/equipment/" + Uri.EscapeDataString(slot), null, token, ReadLoadout);

        private static Loadout ReadLoadout(JsonValue j)
        {
            var loadout = new Loadout();
            foreach (string slot in j["slots"].Names) loadout.Slots[slot] = j["slots"][slot].AsString;
            foreach (string stat in j["bonus"].Names) loadout.Bonus[stat] = j["bonus"][stat].AsInt;
            return loadout;
        }

        /// <summary>The boosts running.</summary>
        public Task<ApiResult<BoostsAnswer>> Boosts(string token) =>
            Call(HttpMethod.Get, "/v1/boosts", null, token, ReadBoosts);

        /// <summary>
        /// One tap of Activate: <paramref name="key"/> made when the tap happens and sent unchanged with
        /// every retry of it, so one boost is taken (04 §8); <see cref="NewPurchaseKey"/> makes one.
        /// </summary>
        public Task<ApiResult<BoostsAnswer>> ActivateBoost(string token, string itemId, string key) =>
            Call(HttpMethod.Post, "/v1/boosts", JsonValue.NewObject().Set("itemId", itemId).Set("key", key), token, ReadBoosts);

        private static BoostsAnswer ReadBoosts(JsonValue j)
        {
            var answer = new BoostsAnswer { Result = j["result"].AsString };
            JsonValue list = j["boosts"];
            for (int i = 0; i < list.Count; i++)
                answer.Boosts.Add(new ActiveBoost
                {
                    Kind = list[i]["kind"].AsString, ItemId = list[i]["itemId"].AsString,
                    Percent = list[i]["percent"].AsInt, EndsAt = list[i]["endsAt"].AsString,
                });
            return answer;
        }

        /// <summary>Creates a team, named by a display name's rules, and leads it.</summary>
        public Task<ApiResult<TeamInfo>> CreateTeam(string token, string name) =>
            Call(HttpMethod.Post, "/v1/teams", JsonValue.NewObject().Set("name", name), token, ReadTeam);

        /// <summary>Renames the player, once in 30 days: 429 too_soon before (04 §1).</summary>
        public Task<ApiResult<Renamed>> Rename(string token, string displayName) =>
            Call(HttpMethod.Put, "/v1/accounts/name", JsonValue.NewObject().Set("displayName", displayName), token,
                j => new Renamed { PlayerId = j["playerId"].AsLong, DisplayName = j["displayName"].AsString });

        /// <summary>Renames the team, by its leader, once in 30 days (04 §1).</summary>
        public Task<ApiResult<TeamInfo>> RenameTeam(string token, string name) =>
            Call(HttpMethod.Put, "/v1/teams/mine/name", JsonValue.NewObject().Set("name", name), token, ReadTeam);

        /// <summary>The player's team; 404 no_team when in none.</summary>
        public Task<ApiResult<TeamInfo>> MyTeam(string token) =>
            Call(HttpMethod.Get, "/v1/teams/mine", null, token, ReadTeam);

        public Task<ApiResult<TeamInfo>> InviteToTeam(string token, long playerId) =>
            Call(HttpMethod.Post, "/v1/teams/mine/invites", JsonValue.NewObject().Set("playerId", playerId), token, ReadTeam);

        public Task<ApiResult<List<TeamInvite>>> TeamInvites(string token) =>
            Call(HttpMethod.Get, "/v1/team-invites", null, token, ReadInvites);

        public Task<ApiResult<TeamInfo>> AcceptTeamInvite(string token, long teamId) =>
            Call(HttpMethod.Post, "/v1/team-invites/" + teamId, JsonValue.NewObject().Set("accept", JsonValue.Of(true)),
                token, ReadTeam);

        public Task<ApiResult<List<TeamInvite>>> DeclineTeamInvite(string token, long teamId) =>
            Call(HttpMethod.Post, "/v1/team-invites/" + teamId, JsonValue.NewObject().Set("accept", JsonValue.Of(false)),
                token, ReadInvites);

        /// <summary>Teams whose name starts so, the 20 first by name (Q-49).</summary>
        public Task<ApiResult<List<TeamFound>>> FindTeams(string token, string start) =>
            Call(HttpMethod.Get, "/v1/teams?name=" + Uri.EscapeDataString(start), null, token, j =>
            {
                var found = new List<TeamFound>();
                JsonValue list = j["teams"];
                for (int i = 0; i < list.Count; i++)
                    found.Add(new TeamFound { Id = list[i]["id"].AsLong, Name = list[i]["name"].AsString,
                        Members = list[i]["members"].AsInt, Rating = list[i]["rating"].AsInt });
                return found;
            });

        /// <summary>Asks a team to take the player: 409 already, team_full, too_many_applied; 429 too_soon.</summary>
        public Task<ApiResult<bool>> ApplyToTeam(string token, long teamId) =>
            Call(HttpMethod.Post, "/v1/teams/" + teamId + "/applications", null, token, _ => true);

        public Task<ApiResult<bool>> WithdrawApplication(string token, long teamId) =>
            Call(HttpMethod.Delete, "/v1/teams/" + teamId + "/applications", null, token, _ => true);

        /// <summary>The player's own applications, read as invitations are: a team's id, name and expiry.</summary>
        public Task<ApiResult<List<TeamInvite>>> MyApplications(string token) =>
            Call(HttpMethod.Get, "/v1/team-applications", null, token, j => ReadInvites(j, "applications"));

        /// <summary>The applications to the player's team, for its leader or a vice leader.</summary>
        public Task<ApiResult<List<TeamApplicant>>> TeamApplications(string token) =>
            Call(HttpMethod.Get, "/v1/teams/mine/applications", null, token, j =>
            {
                var applicants = new List<TeamApplicant>();
                JsonValue list = j["applications"];
                for (int i = 0; i < list.Count; i++)
                    applicants.Add(new TeamApplicant { PlayerId = list[i]["playerId"].AsLong, Name = list[i]["name"].AsString,
                        ExpiresAt = list[i]["expiresAt"].AsString });
                return applicants;
            });

        public Task<ApiResult<TeamInfo>> AnswerApplication(string token, long playerId, bool accept) =>
            Call(HttpMethod.Post, "/v1/teams/mine/applications/" + playerId,
                JsonValue.NewObject().Set("accept", JsonValue.Of(accept)), token, ReadTeam);

        public Task<ApiResult<bool>> LeaveTeam(string token) =>
            Call(HttpMethod.Post, "/v1/teams/mine/leave", null, token, _ => true);

        public Task<ApiResult<TeamInfo>> KickFromTeam(string token, long playerId) =>
            Call(HttpMethod.Delete, "/v1/teams/mine/members/" + playerId, null, token, ReadTeam);

        /// <summary><paramref name="role"/>: "vice_leader" or "member".</summary>
        public Task<ApiResult<TeamInfo>> SetTeamRole(string token, long playerId, string role) =>
            Call(HttpMethod.Post, "/v1/teams/mine/members/" + playerId + "/role", JsonValue.NewObject().Set("role", role),
                token, ReadTeam);

        public Task<ApiResult<TeamInfo>> HandOverTeam(string token, long playerId) =>
            Call(HttpMethod.Post, "/v1/teams/mine/leader", JsonValue.NewObject().Set("playerId", playerId), token, ReadTeam);

        public Task<ApiResult<bool>> DisbandTeam(string token) =>
            Call(HttpMethod.Delete, "/v1/teams/mine", null, token, _ => true);

        internal static TeamInfo ReadTeam(JsonValue j)
        {
            var team = new TeamInfo
            {
                Id = j["id"].AsLong, Name = j["name"].AsString, Rating = j["rating"].AsInt, Wins = j["wins"].AsInt,
                Losses = j["losses"].AsInt, Draws = j["draws"].AsInt,
            };
            JsonValue members = j["members"];
            for (int i = 0; i < members.Count; i++)
                team.Members.Add(new TeamMemberInfo
                {
                    PlayerId = members[i]["playerId"].AsLong, Name = members[i]["name"].AsString, Role = members[i]["role"].AsString,
                });
            return team;
        }

        private static List<TeamInvite> ReadInvites(JsonValue j) => ReadInvites(j, "invites");

        private static List<TeamInvite> ReadInvites(JsonValue j, string field)
        {
            var invites = new List<TeamInvite>();
            JsonValue list = j[field];
            for (int i = 0; i < list.Count; i++)
                invites.Add(new TeamInvite
                {
                    TeamId = list[i]["teamId"].AsLong, TeamName = list[i]["teamName"].AsString, ExpiresAt = list[i]["expiresAt"].AsString,
                });
            return invites;
        }

        /// <summary>Those registering, seeded or running, each with its entries. No session needed.</summary>
        public Task<ApiResult<List<TournamentInfo>>> Tournaments() =>
            Call(HttpMethod.Get, "/v1/tournaments", null, null, j =>
            {
                var list = new List<TournamentInfo>();
                JsonValue all = j["tournaments"];
                for (int i = 0; i < all.Count; i++) list.Add(ReadTournament(all[i]));
                return list;
            });

        /// <summary>One, with its entries and bracket; 404 no_such_tournament. No session needed.</summary>
        public Task<ApiResult<TournamentInfo>> Tournament(long id) =>
            Call(HttpMethod.Get, "/v1/tournaments/" + id, null, null, ReadTournament);

        /// <summary>Registers, until the deadline; 409 closed, full or already.</summary>
        public Task<ApiResult<TournamentInfo>> EnterTournament(string token, long id) =>
            Call(HttpMethod.Post, "/v1/tournaments/" + id + "/entries", null, token, ReadTournament);

        /// <summary>Withdraws, until the deadline; 409 not_registered.</summary>
        public Task<ApiResult<TournamentInfo>> WithdrawFromTournament(string token, long id) =>
            Call(HttpMethod.Delete, "/v1/tournaments/" + id + "/entries", null, token, ReadTournament);

        /// <summary>
        /// The player's match in it, for a client that missed evt.tournament.match: while its ticket
        /// lasts, 60 s; 404 no_match before and after.
        /// </summary>
        public Task<ApiResult<TournamentGrant>> TournamentMatch(string token, long id) =>
            Call(HttpMethod.Get, "/v1/tournaments/" + id + "/match", null, token, ReadTournamentGrant);

        private static TournamentInfo ReadTournament(JsonValue j)
        {
            var t = new TournamentInfo
            {
                Id = j["id"].AsLong, Mode = j["mode"].AsString, Name = j["name"].AsString, State = j["state"].AsString,
                RegistrationEnds = j["registrationEnds"].AsString, StartsAt = j["startsAt"].AsString,
                MaxEntries = j["maxEntries"].AsInt, RoundMinutes = j["roundMinutes"].AsInt, CurrentRound = j["currentRound"].AsInt,
            };
            for (int i = 0; i < 3; i++) t.Prizes[i] = j["prizes"][i].AsLong;
            JsonValue entries = j["entries"];
            for (int i = 0; i < entries.Count; i++)
            {
                var entry = new TournamentEntry
                {
                    PlayerId = entries[i]["playerId"].AsLong, TeamId = entries[i]["teamId"].AsLong,
                    Name = entries[i]["name"].AsString, Seed = entries[i]["seed"].AsInt,
                };
                JsonValue roster = entries[i]["roster"];
                for (int k = 0; k < roster.Count; k++)
                    entry.Roster.Add(new RosterPlayer { PlayerId = roster[k]["playerId"].AsLong, Name = roster[k]["name"].AsString });
                t.Entries.Add(entry);
            }
            JsonValue matches = j["matches"];
            for (int i = 0; i < matches.Count; i++)
                t.Matches.Add(new BracketMatch
                {
                    Round = matches[i]["round"].AsInt, Slot = matches[i]["slot"].AsInt, State = matches[i]["state"].AsString,
                    PlayerA = OptionalLong(matches[i]["playerA"]), PlayerB = OptionalLong(matches[i]["playerB"]),
                    Winner = OptionalLong(matches[i]["winner"]),
                    TeamA = OptionalLong(matches[i]["teamA"]), TeamB = OptionalLong(matches[i]["teamB"]),
                    WinnerTeam = OptionalLong(matches[i]["winnerTeam"]),
                });
            t.Format = j["format"].IsNull ? "elimination" : j["format"].AsString;
            JsonValue standings = j["standings"];
            for (int i = 0; !standings.IsNull && i < standings.Count; i++)
                t.Standings.Add(new TournamentStanding
                {
                    PlayerId = standings[i]["playerId"].AsLong, TeamId = standings[i]["teamId"].AsLong,
                    Name = standings[i]["name"].AsString, Points = standings[i]["points"].AsInt, Wins = standings[i]["wins"].AsInt,
                    Draws = standings[i]["draws"].AsInt, Losses = standings[i]["losses"].AsInt,
                });
            return t;
        }

        private static long? OptionalLong(JsonValue v) => v.IsNull ? (long?)null : v.AsLong;

        internal static TournamentGrant ReadTournamentGrant(JsonValue j) => new TournamentGrant
        {
            TournamentId = j["tournamentId"].AsLong, Round = j["round"].AsInt, ArenaHost = j["arenaHost"].AsString,
            ArenaPort = j["arenaPort"].AsInt, TicketId = j["ticketId"].AsString, Tls = j["tls"].AsBool, Mode = j["mode"].AsString,
        };

        /// <summary>Friends, with presence, and the requests either way (04 §9).</summary>
        public Task<ApiResult<FriendsInfo>> Friends(string token) =>
            Call(HttpMethod.Get, "/v1/friends", null, token, j =>
            {
                var info = new FriendsInfo();
                JsonValue friends = j["friends"];
                for (int i = 0; i < friends.Count; i++)
                    info.Friends.Add(new FriendInfo
                    {
                        PlayerId = friends[i]["playerId"].AsLong, Name = friends[i]["name"].AsString, Online = friends[i]["online"].AsBool,
                    });
                ReadRequests(j["requests"], info.Requests);
                ReadRequests(j["asked"], info.Asked);
                return info;
            });

        private static void ReadRequests(JsonValue list, List<FriendRequest> into)
        {
            for (int i = 0; i < list.Count; i++)
                into.Add(new FriendRequest
                {
                    PlayerId = list[i]["playerId"].AsLong, Name = list[i]["name"].AsString, ExpiresAt = list[i]["expiresAt"].AsString,
                });
        }

        /// <summary>Asks, or accepts if they asked first: "asked" or "friends".</summary>
        public Task<ApiResult<string>> AskFriend(string token, long playerId) =>
            Call(HttpMethod.Post, "/v1/friends", JsonValue.NewObject().Set("playerId", playerId), token, j => j["state"].AsString);

        public Task<ApiResult<bool>> RemoveFriend(string token, long playerId) =>
            Call(HttpMethod.Delete, "/v1/friends/" + playerId, null, token, _ => true);

        /// <summary>Declines their request, or withdraws one's own.</summary>
        public Task<ApiResult<bool>> DropFriendRequest(string token, long playerId) =>
            Call(HttpMethod.Delete, "/v1/friend-requests/" + playerId, null, token, _ => true);

        public Task<ApiResult<List<PlayerRef>>> Blocks(string token) =>
            Call(HttpMethod.Get, "/v1/blocks", null, token, j =>
            {
                var list = new List<PlayerRef>();
                JsonValue blocked = j["blocked"];
                for (int i = 0; i < blocked.Count; i++)
                    list.Add(new PlayerRef { PlayerId = blocked[i]["playerId"].AsLong, Name = blocked[i]["name"].AsString });
                return list;
            });

        public Task<ApiResult<bool>> Block(string token, long playerId) =>
            Call(HttpMethod.Post, "/v1/blocks", JsonValue.NewObject().Set("playerId", playerId), token, _ => true);

        public Task<ApiResult<bool>> Unblock(string token, long playerId) =>
            Call(HttpMethod.Delete, "/v1/blocks/" + playerId, null, token, _ => true);

        /// <summary>The newest inbox items, newest first; evt.inbox says to read it again.</summary>
        public Task<ApiResult<List<InboxItem>>> Inbox(string token) =>
            Call(HttpMethod.Get, "/v1/inbox", null, token, j =>
            {
                var list = new List<InboxItem>();
                JsonValue items = j["items"];
                for (int i = 0; i < items.Count; i++)
                    list.Add(new InboxItem
                    {
                        Id = items[i]["id"].AsLong, Kind = items[i]["kind"].AsString, Ref = items[i]["ref"].AsLong,
                        At = items[i]["at"].AsString, Read = items[i]["read"].AsBool,
                    });
                return list;
            });

        /// <summary>Marks read every item up to <paramref name="upTo"/>.</summary>
        public Task<ApiResult<bool>> ReadInbox(string token, long upTo) =>
            Call(HttpMethod.Post, "/v1/inbox/read", JsonValue.NewObject().Set("upTo", upTo), token, _ => true);

        /// <summary>A purchase key: a lowercase UUID, as the API requires.</summary>
        public static string NewPurchaseKey() => Guid.NewGuid().ToString("D");

        /// <summary>
        /// The top of a board: alltime, daily or weekly by score; duel, rffa or tvt by rating. For a rating
        /// board, <paramref name="season"/> names a past season, its final places (04 §7); 0 for the board
        /// as it stands. 404 no_such_season for one that never was.
        /// </summary>
        public Task<ApiResult<List<RankRow>>> Leaderboard(string board, int limit, int season = 0) =>
            Call(HttpMethod.Get, $"/v1/leaderboards/{Uri.EscapeDataString(board)}?limit={limit}" + (season > 0 ? $"&season={season}" : ""),
                null, null, ReadRows);

        /// <summary>
        /// The player's own place, with the rows around it; 404 not_ranked before any score, or before ten
        /// rated matches this season. In a past <paramref name="season"/>, their final place.
        /// </summary>
        public Task<ApiResult<List<RankRow>>> AroundMe(string board, string token, int season = 0) =>
            Call(HttpMethod.Get, $"/v1/leaderboards/{Uri.EscapeDataString(board)}/me" + (season > 0 ? $"?season={season}" : ""),
                null, token, ReadRows);

        /// <summary>
        /// Every achievement (04 §8, D-64), its stat, threshold and gems, the player's progress (the stat as it
        /// stands; play time in seconds) and whether it is reached. 401 without a session.
        /// </summary>
        public Task<ApiResult<List<AchievementInfo>>> Achievements(string token) =>
            Call(HttpMethod.Get, "/v1/achievements", null, token, j =>
            {
                var list = new List<AchievementInfo>();
                JsonValue all = j["achievements"];
                for (int i = 0; i < all.Count; i++)
                    list.Add(new AchievementInfo
                    {
                        Id = all[i]["id"].AsString, Stat = all[i]["stat"].AsString, Threshold = all[i]["threshold"].AsLong,
                        Gems = all[i]["gems"].AsInt, Progress = all[i]["progress"].AsLong, Reached = all[i]["reached"].AsBool,
                    });
                return list;
            });

        /// <summary>
        /// Today's goals (04 §8, D-66): the UTC day and when it ends, its three goals, each its kind, target,
        /// coins, progress and whether met, and the gems for all three. 401 without a session.
        /// </summary>
        public Task<ApiResult<GoalsInfo>> Goals(string token) =>
            Call(HttpMethod.Get, "/v1/goals", null, token, j =>
            {
                var today = new GoalsInfo
                {
                    Day = j["day"].AsString, ResetsAt = j["resetsAt"].AsString, SetGems = j["setGems"].AsInt, SetDone = j["setDone"].AsBool,
                };
                JsonValue goals = j["goals"];
                for (int i = 0; i < goals.Count; i++)
                    today.Goals.Add(new GoalInfo
                    {
                        Id = goals[i]["id"].AsString, Kind = goals[i]["kind"].AsString, Target = goals[i]["target"].AsLong,
                        Coins = goals[i]["coins"].AsInt, Progress = goals[i]["progress"].AsLong, Done = goals[i]["done"].AsBool,
                    });
                return today;
            });

        /// <summary>The season being played, its number and end, and the seasons before it, newest first (04 §7).</summary>
        public Task<ApiResult<SeasonsInfo>> Seasons() =>
            Call(HttpMethod.Get, "/v1/seasons", null, null, j =>
            {
                var info = new SeasonsInfo { Current = j["current"].IsNull ? null : ReadSeason(j["current"]) };
                JsonValue past = j["past"];
                for (int i = 0; i < past.Count; i++) info.Past.Add(ReadSeason(past[i]));
                return info;
            });

        private static SeasonInfo ReadSeason(JsonValue j) => new SeasonInfo
        {
            Id = j["id"].AsInt, StartsAt = j["startsAt"].AsString, EndsAt = j["endsAt"].AsString,
        };

        internal static MatchGrant ReadGrant(JsonValue j) => new MatchGrant
        {
            ArenaHost = j["arenaHost"].AsString, ArenaPort = j["arenaPort"].AsInt, TicketId = j["ticketId"].AsString, Tls = j["tls"].AsBool,
            Mode = j["mode"].AsString,
        };

        /// <summary>A party as platform answers and pushes it; null when partyId is, which is none.</summary>
        internal static PartyInfo ReadParty(JsonValue j)
        {
            if (j["partyId"].IsNull) return null;
            var p = new PartyInfo { PartyId = j["partyId"].AsString, Leader = j["leader"].AsLong };
            JsonValue members = j["members"];
            for (int i = 0; i < members.Count; i++)
                p.Members.Add(new PartyMember { PlayerId = members[i]["playerId"].AsLong, Name = members[i]["name"].AsString });
            return p;
        }

        internal static QueueStatus ReadQueue(JsonValue j) => new QueueStatus
        {
            State = j["state"].AsString, Mode = j["mode"].AsString, WaitedSeconds = j["waitedSeconds"].AsLong,
            Grant = j["grant"].IsNull ? null : ReadGrant(j["grant"]),
            MatchUid = j["matchUid"].AsString, SecondsLeft = j["secondsLeft"].AsLong,
        };

        private static List<RankRow> ReadRows(JsonValue j)
        {
            var rows = new List<RankRow>();
            JsonValue entries = j["entries"];
            for (int i = 0; i < entries.Count; i++)
                rows.Add(new RankRow
                {
                    Rank = entries[i]["rank"].AsLong, PlayerId = entries[i]["playerId"].AsLong, TeamId = entries[i]["teamId"].AsLong,
                    Name = entries[i]["name"].AsString, Score = entries[i]["score"].AsLong,
                });
            return rows;
        }

        private async Task<ApiResult<T>> Call<T>(HttpMethod method, string path, JsonValue body, string token, Func<JsonValue, T> read)
        {
            string lastProblem = null;
            for (int tried = 0; tried < _endpoints.Length; tried++)
            {
                int index = (_preferred + tried) % _endpoints.Length;
                var request = new HttpRequestMessage(method, _endpoints[index] + path);
                if (body != null) request.Content = new StringContent(body.ToString(), Encoding.UTF8, "application/json");
                if (token != null) request.Headers.TryAddWithoutValidation("Authorization", "Bearer " + token);
                HttpResponseMessage response;
                try
                {
                    response = await _http.SendAsync(request).ConfigureAwait(false);
                }
                catch (Exception e) when (e is HttpRequestException || e is TaskCanceledException || e is OperationCanceledException)
                {
                    // Unreachable, refused, a certificate not trusted, or too slow: the next endpoint. But a POST that
                    // timed out may have been done (a guest made, a team applied to): it is not sent again elsewhere,
                    // and the caller hears so; a keyed one is safe to send again with its key.
                    lastProblem = $"{_endpoints[index]}: {e.GetBaseException().Message}";
                    if (method == HttpMethod.Post && !(e is HttpRequestException))
                        return new ApiResult<T> { Status = 0, Code = "timeout", Message = lastProblem };
                    continue;
                }
                _preferred = index;
                using (response)
                {
                    string text = response.Content == null ? "" : await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    var result = new ApiResult<T> { Status = (int)response.StatusCode };
                    if (response.Headers.RetryAfter?.Delta is TimeSpan wait) result.RetryAfterSeconds = (int)Math.Ceiling(wait.TotalSeconds);
                    JsonValue json = null;
                    if (text.Length > 0)
                    {
                        try { json = JsonValue.Parse(text); }
                        catch (FormatException) { json = null; }
                    }
                    if (response.IsSuccessStatusCode && text.Length > 0 && json == null)
                    {
                        // A success that is not JSON is not one: a proxy's page, say, read as a session with no token.
                        result.Code = "bad_response";
                        result.Message = text.Length > 200 ? text.Substring(0, 200) : text;
                    }
                    else if (response.IsSuccessStatusCode)
                    {
                        result.Ok = true;
                        result.Value = read(json ?? JsonValue.NewObject());
                    }
                    else
                    {
                        result.Code = json?["code"].AsString ?? "http_" + result.Status;
                        result.Message = json?["message"].AsString ?? text;
                    }
                    return result;
                }
            }
            return new ApiResult<T> { Status = 0, Code = "unreachable", Message = lastProblem };
        }
    }
}
