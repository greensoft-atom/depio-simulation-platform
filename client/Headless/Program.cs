using System;
using System.Collections.Generic;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
using System.Threading;
using System.Threading.Tasks;
using Backend.Client.Core;

namespace Backend.Client.Headless
{
    /// <summary>
    /// Drives the client's core against a running stack, as a player would, and says whether
    /// each scenario did what docs 02 and 08 say it should. Exit status 0 only if all did.
    ///
    ///   Backend.Client.Headless &lt;platform-url&gt; [--lobby ws-url] [--trust cert] [--wrong-trust cert] [scenario ...]
    ///
    /// With none named: play, resume, badticket, lifecycle. Every scenario is a case of the switch below, each
    /// with one line in client/Headless/README.md. Those that need the lobby (<see cref="NeedsLobby"/>) are
    /// skipped without --lobby; untrusted needs --wrong-trust; several need the operator's API
    /// (BACKEND_ADMIN_URL, BACKEND_ADMIN_TOKEN) or the drill's database helper (BACKEND_DRILL_SQL), which
    /// headless-drill.sh sets. With TLS, --trust names the one certificate to trust in place of the device's
    /// store, as a test CA would be; the host name is still checked, and nothing is pinned.
    /// </summary>
    public static class Program
    {
        private static ApiClient Api;
        private static Uri _lobby;
        private static string _platform;
        private static int _failures;
        private static X509Certificate2 _trust, _wrongTrust;

        /// <summary>The scenarios that open a lobby connection: without --lobby they are skipped, not run to time out.</summary>
        private static readonly HashSet<string> NeedsLobby = new HashSet<string>
        {
            "lobby", "replaced", "badsession", "duel", "walkover", "party", "decline", "rffa", "coop", "banned", "removed",
            "tournament", "teammatch", "teamcup", "team", "social", "rename", "apply", "roundrobin", "milestone", "season",
            "achievements", "goals", "pass", "domination", "tag", "maze", "sandbox", "notice",
        };

        public static int Main(string[] args)
        {
            if (args.Length < 1)
            {
                Console.Error.WriteLine("usage: Backend.Client.Headless <platform-url> [--lobby ws-url] [--trust cert] [--wrong-trust cert] [scenario ...]");
                return 2;
            }
            _platform = args[0].TrimEnd('/');
            Api = new ApiClient(new[] { _platform });
            var scenarios = new List<string>();
            for (int i = 1; i < args.Length; i++)
            {
                if (args[i] == "--lobby") _lobby = new Uri(args[++i]);
                else if (args[i] == "--trust") _trust = new X509Certificate2(args[++i]);
                else if (args[i] == "--wrong-trust") _wrongTrust = new X509Certificate2(args[++i]);
                else scenarios.Add(args[i]);
            }
            if (scenarios.Count == 0) scenarios.AddRange(new[] { "play", "resume", "badticket", "lifecycle" });
            if (_lobby == null) scenarios.RemoveAll(NeedsLobby.Contains);
            foreach (string s in scenarios)
            {
                Console.WriteLine($"--- {s}");
                try
                {
                    switch (s)
                    {
                        case "play": Play(); break;
                        case "prediction": Prediction(); break;
                        case "equip": Equip(); break;
                        case "boost": Boost(); break;
                        case "team": Team(); break;
                        case "tournament": Tournament(); break;
                        case "teammatch": TeamMatch(); break;
                        case "teamcup": TeamCup(); break;
                        case "rename": Rename(); break;
                        case "apply": Apply(); break;
                        case "roundrobin": RoundRobin(); break;
                        case "levels": Levels(); break;
                        case "gems": Gems(); break;
                        case "purchase": PurchaseGems(); break;
                        case "pass": PassPlay(); break;
                        case "skin": SkinPlay(); break;
                        case "milestone": Milestone(); break;
                        case "season": Season(); break;
                        case "achievements": AchievementsPlay(); break;
                        case "goals": GoalsPlay(); break;
                        case "social": Social(); break;
                        case "guest": GuestPlay(); break;
                        case "stats": Stats(); break;
                        case "domination": DominationPlay(); break;
                        case "tag": TagPlay(); break;
                        case "maze": MazePlay(); break;
                        case "sandbox": SandboxPlay(); break;
                        case "resume": Resume(); break;
                        case "coldresume": ColdResume(); break;
                        case "badticket": BadTicket(); break;
                        case "lifecycle": Lifecycle(); break;
                        case "untrusted": Untrusted(); break;
                        case "lobby": LobbyToResult(); break;
                        case "replaced": Replaced(); break;
                        case "badsession": BadSession(); break;
                        case "duel": Duel(); break;
                        case "walkover": Walkover(); break;
                        case "party": PartyMatch(); break;
                        case "decline": Decline(); break;
                        case "rffa": RankedFreeForAll(); break;
                        case "coop": Coop(); break;
                        case "banned": Banned(); break;
                        case "notice": Notice(); break;
                        case "removed": Removed(); break;
                        case "phrase": Phrase(); break;
                        default: Fail($"no scenario called {s}"); break;
                    }
                }
                catch (Exception e)
                {
                    Fail($"{s} threw {e.GetType().Name}: {e.Message}");
                }
            }
            Console.WriteLine(_failures == 0 ? "ALL PASSED" : $"{_failures} CHECK(S) FAILED");
            return _failures == 0 ? 0 : 1;
        }

        // ---- scenarios ------------------------------------------------------------------------

        /// <summary>Join, move right firing, see the own tank move and the frames arrive, leave.</summary>
        private static void Play()
        {
            var (player, grant) = NewPlayerWithGrant();
            using var m = Connect(grant);
            m.Join(grant.TicketId);
            Check(RunUntil(m, 10_000, () => m.Alive), "joined, and the own tank is in the world");
            Check(m.Welcome.SelfHandle == Wire.SelfHandle, $"the Welcome names handle {Wire.SelfHandle}");
            // The table the arena runs, from platform, named by the Welcome (D-24): two processes agree.
            ApiResult<ClassTableInfo> classes = Api.Classes().Result;
            Check(classes.Ok && classes.Value.Version == m.Welcome.ContentVersion,
                $"the class table platform serves is the one the Welcome names ({m.Welcome.ContentVersion})");
            Check(classes.Ok && classes.Value.Classes.Count > 0 && classes.Value.Classes[0].Name == "Basic",
                "and it begins with Basic");
            Check(classes.Ok && classes.Value.Classes.Exists(c => c.Name == "Guardian" && c.OpensAt == int.MaxValue),
                "and has co-op's boss, which no level opens (01 §8.5)");
            int x0 = m.World[Wire.SelfHandle].X;
            long frames0 = m.SnapshotsApplied;
            m.SetInput(Wire.MoveRight, Wire.QuantiseAim(0f), true, Wire.FlagAutofire);
            RunFor(m, 3_000);
            int moved = m.World[Wire.SelfHandle].X - x0;
            long frames = m.SnapshotsApplied - frames0;
            Console.WriteLine($"    player {player}: moved {moved / Wire.PosScale:F0} units right in 3 s, {frames} frames, round trip {m.RoundTripMs} ms");
            // At level 1 the server's rule (sim.Room: accel 0.16, friction 0.9) tops out at 1.44 units
            // a tick, 36 a second: 3 s is at most 108, less the ramp to top speed and the frame
            // still in flight, about 93. Under 70 would mean inputs lost or late.
            Check(moved >= 70 * Wire.PosScale && moved <= 108 * Wire.PosScale,
                "the own tank moved right at its top speed: the server read every input");
            Check(frames >= 30 && frames <= 50, "15 frames a second, give or take");
            Check(m.RoundTripMs >= 0, "a Pong came back");

            // Twin, at level 1: refused, and nothing else happens (01 §4).
            m.ChooseClass(1);
            // Then still, and aimed at the middle of the map. Driving right on, a tank that spawned
            // near the right wall reached it, and every bullet it fired there left the map before a
            // snapshot could show it: 3 runs in 60 (P-32).
            Entity me = m.World[Wire.SelfHandle];
            double towardMiddle = Math.Atan2(m.Welcome.MapHeight / 2.0 - me.Y / (double)Wire.PosScale,
                                             m.Welcome.MapWidth / 2.0 - me.X / (double)Wire.PosScale);
            m.SetInput(0, Wire.QuantiseAim((float)towardMiddle), true, Wire.FlagAutofire);
            // Protocol 2 carries a bullet's speed exactly: at level 1 with no points, 10 units a
            // tick, 20 in half units. The protection that stops a new tank firing is over by now.
            int bullet = 0;
            RunUntil(m, 5_000, () => (bullet = OwnBullet(m)) != 0);
            if (bullet == 0) DescribeSurroundings(m);   // P-32: intermittent, so say what the tank saw
            Check(bullet != 0, "the own tank's bullets arrive as predicted creates");
            Check(bullet != 0 && m.World[bullet].Speed == 20, "at exactly 10 units a tick");
            Check(bullet != 0 && m.World[bullet].Radius == 8, "and a Basic's size, 8 units, as protocol 3 carries it");
            Check(m.World[Wire.SelfHandle].ClassId == 0, "still Basic");
            Check(State(m) == MatchState.InMatch, "still in the match");
            m.Leave();
            Check(m.End == MatchEnd.Left, "left on purpose");
        }

        /// <summary>
        /// The own tank, predicted (02 §9, 08 §4, D-62), against the real server: a walk that turns, stops
        /// and goes diagonally, each frame's prediction of where the server would have it compared with
        /// where it did; then the same walk firing, whose recoil is not predicted. Reports the error, the
        /// corrections, and how far ahead of the newest sample the drawn tank is: the lag it hides.
        /// </summary>
        private static void Prediction()
        {
            var (player, grant) = NewPlayerWithGrant();
            using var m = Connect(grant);
            m.Join(grant.TicketId);
            Check(RunUntil(m, 10_000, () => m.Alive), "joined, and the own tank is in the world");
            Check(RunUntil(m, 3_000, () => m.OwnTank.Predicting), "the own tank is predicted: the server told its rule");
            var walk = Walk(m, firing: false);
            var fire = Walk(m, firing: true);
            Report("walking", walk);
            Report("firing ", fire);
            Check(walk.Compared >= walk.Frames * 9 / 10, $"nearly every frame matched a step ({walk.Compared} of {walk.Frames})");
            Check(walk.Jumps == 0 && fire.Jumps == 0, "and none needed a jump");
            Check(Percentile(walk.Errors, 50) <= PredictionMedianLimit,
                $"walking, the median frame found the tank where it was predicted, within {PredictionMedianLimit} units");
            Check(Percentile(walk.Errors, 95) <= PredictionP95Limit,
                $"and 95 in 100 within {PredictionP95Limit}: what is off is knocks, which are not predicted");
            Check(Percentile(walk.Leads, 50) >= 1f, "the drawn tank is ahead of the newest sample: the lag is hidden");
            m.Leave();
        }

        private const float PredictionMedianLimit = 0.5f, PredictionP95Limit = 4f;

        private sealed class Walked
        {
            public readonly List<float> Errors = new List<float>(), Corrections = new List<float>(), Leads = new List<float>();
            public long Frames, Compared;
            public int Jumps;
        }

        /// <summary>Twelve legs of 600 ms: the four ways, the diagonals and stops, toward the map's middle first.</summary>
        private static Walked Walk(MatchConnection m, bool firing)
        {
            var w = new Walked();
            m.OwnTank.OnReconciled = (error, correction) =>
            {
                if (!float.IsNaN(error)) w.Errors.Add(error);
                w.Corrections.Add(correction);
            };
            long frames0 = m.SnapshotsApplied, compared0 = m.OwnTank.Compared;
            int jumps0 = m.OwnTank.Jumps;
            Entity me = m.World[Wire.SelfHandle];
            int towardMiddle = (me.X / Wire.PosScale < m.Welcome.MapWidth / 2 ? Wire.MoveRight : Wire.MoveLeft)
                             | (me.Y / Wire.PosScale < m.Welcome.MapHeight / 2 ? Wire.MoveDown : Wire.MoveUp);
            int[] legs = { towardMiddle, Wire.MoveRight, Wire.MoveRight | Wire.MoveDown, 0, Wire.MoveDown,
                           Wire.MoveLeft | Wire.MoveDown, Wire.MoveLeft, 0, Wire.MoveLeft | Wire.MoveUp, Wire.MoveUp,
                           Wire.MoveUp | Wire.MoveRight, 0 };
            foreach (int move in legs)
            {
                m.SetInput(move, Wire.QuantiseAim(0f), false, firing ? Wire.FlagAutofire : 0);
                var leg = System.Diagnostics.Stopwatch.StartNew();
                while (leg.ElapsedMilliseconds < 600)
                {
                    m.Poll();
                    Entity self = m.World[Wire.SelfHandle];
                    if (m.OwnTank.Predicting && self.Alive)
                    {
                        float dx = m.OwnTank.X - self.X / Wire.PosScale, dy = m.OwnTank.Y - self.Y / Wire.PosScale;
                        if (move != 0) w.Leads.Add((float)Math.Sqrt(dx * dx + dy * dy));
                    }
                    Thread.Sleep(16);
                }
            }
            m.SetInput(0, Wire.QuantiseAim(0f), false, 0);
            w.Frames = m.SnapshotsApplied - frames0;
            w.Compared = m.OwnTank.Compared - compared0;
            w.Jumps = m.OwnTank.Jumps - jumps0;
            m.OwnTank.OnReconciled = null;
            return w;
        }

        private static void Report(string what, Walked w)
        {
            Console.WriteLine($"    {what}: {w.Frames} frames, {w.Compared} compared, {w.Jumps} jumps; error p50 {Percentile(w.Errors, 50):F2} "
                + $"p95 {Percentile(w.Errors, 95):F2} max {Percentile(w.Errors, 100):F2}; correction p50 {Percentile(w.Corrections, 50):F2} "
                + $"p95 {Percentile(w.Corrections, 95):F2} max {Percentile(w.Corrections, 100):F2}; ahead of the newest sample p50 "
                + $"{Percentile(w.Leads, 50):F1} p95 {Percentile(w.Leads, 95):F1} units (world units)");
        }

        private static float Percentile(List<float> values, int p)
        {
            if (values.Count == 0) return float.NaN;
            var sorted = new List<float>(values);
            sorted.Sort();
            int rank = (int)Math.Ceiling(p / 100.0 * sorted.Count) - 1;
            return sorted[Math.Max(0, Math.Min(sorted.Count - 1, rank))];
        }

        /// <summary>
        /// Earn, buy, wear, play (04 §8, D-37): the barrel worn makes the tank's bullets faster, and the
        /// protocol carries a bullet's speed exactly. Needs the drill's shop and items, a barrel giving
        /// 25 % bullet speed for 1 coin: headless-drill.sh adds them when this scenario is asked for.
        /// </summary>
        private static void Equip()
        {
            var (session, _) = NewSession();
            Check(PaidStay(session) > 0, "paid for a stay first: nobody starts with coins");

            ApiResult<Receipt> bought = Api.Purchase(session.Token, "drill_barrel", ApiClient.NewPurchaseKey()).Result;
            Check(bought.Ok && bought.Value.Result == "bought", $"bought the barrel ({bought})");
            ApiResult<Loadout> worn = Api.Wear(session.Token, "barrel", "drill_barrel").Result;
            Check(worn.Ok && worn.Value.Slots["barrel"] == "drill_barrel", $"wore it ({worn})");
            Check(worn.Ok && worn.Value.Bonus.TryGetValue("bullet_speed", out int percent) && percent == 25,
                "and it gives 25 % bullet speed, the API says");

            // A new stay, wearing it: 10 units a tick at level 1, 12.5 with the barrel, 25 in half units.
            Grant second = GrantFor(session);
            using var worn2 = Connect(second);
            worn2.Join(second.TicketId);
            Check(RunUntil(worn2, 10_000, () => worn2.Alive), "joined again, wearing it");
            Entity me = worn2.World[Wire.SelfHandle];
            double towardMiddle = Math.Atan2(worn2.Welcome.MapHeight / 2.0 - me.Y / (double)Wire.PosScale,
                                             worn2.Welcome.MapWidth / 2.0 - me.X / (double)Wire.PosScale);
            worn2.SetInput(0, Wire.QuantiseAim((float)towardMiddle), true, Wire.FlagAutofire);
            int bullet = 0;
            RunUntil(worn2, 8_000, () => (bullet = OwnBullet(worn2)) != 0);   // spawn protection is 3 s
            if (bullet == 0) DescribeSurroundings(worn2);
            Check(bullet != 0, "its bullets arrive");
            int speed = bullet == 0 ? 0 : worn2.World[bullet].Speed;
            Check(speed == 25, $"at 12.5 units a tick, the barrel's 25 % on 10 (speed {speed} in half units)");
            worn2.Leave();
        }

        /// <summary>
        /// Earn, buy a coins boost, activate it, earn again (04 §8, D-38): the second stay pays double.
        /// Needs the drill's shop and items: headless-drill.sh adds them when this scenario is asked for.
        /// </summary>
        private static void Boost()
        {
            var (session, _) = NewSession();
            long unboosted = PaidStay(session);
            Console.WriteLine($"    a stay unboosted paid {unboosted}");
            Check(unboosted >= 10, "paid for a stay, unboosted");

            Check(Api.Purchase(session.Token, "drill_boost", ApiClient.NewPurchaseKey()).Result.Ok, "bought a coins boost");
            string key = ApiClient.NewPurchaseKey();
            ApiResult<BoostsAnswer> on = Api.ActivateBoost(session.Token, "drill_boost", key).Result;
            Check(on.Ok && on.Value.Result == "activated" && on.Value.Boosts.Count == 1
                && on.Value.Boosts[0].Kind == "coins" && on.Value.Boosts[0].Percent == 100, $"activated it ({on})");
            ApiResult<BoostsAnswer> again = Api.ActivateBoost(session.Token, "drill_boost", key).Result;
            Check(again.Ok && again.Value.Result == "already_activated", "the same tap again takes nothing more");

            long boosted = PaidStay(session);
            Console.WriteLine($"    a stay with the boost paid {boosted}");
            Check(boosted >= 20 && boosted % 2 == 0, "the next stay paid double: taking part's 10 and the score's share, doubled");
        }

        /// <summary>A team, its first slice (04 §2): created, joined by invitation, handed over, left, disbanded.</summary>
        private static void Team()
        {
            var (ada, _) = NewSession();
            var (bob, _) = NewSession();
            // Each in the lobby, where every change of the team is pushed (04 §2, Q-35).
            using var la = new LobbyClient(_lobby, new SystemClock());
            using var lb = new LobbyClient(_lobby, new SystemClock());
            la.Connect(ada.Token);
            lb.Connect(bob.Token);
            Check(RunLobbiesUntil(10_000, () => la.State == LobbyState.Ready && lb.State == LobbyState.Ready, la, lb),
                "both are in the lobby");
            string name = "t" + Guid.NewGuid().ToString("N").Substring(0, 10);
            ApiResult<TeamInfo> made = Api.CreateTeam(ada.Token, name).Result;
            Check(made.Ok && made.Value.Name == name && made.Value.Members[0].Role == "leader", $"ada made team {name} and leads it ({made})");
            Check(Api.InviteToTeam(ada.Token, bob.PlayerId).Result.Ok, "and invited bob");
            ApiResult<List<TeamInvite>> invites = Api.TeamInvites(bob.Token).Result;
            Check(invites.Ok && invites.Value.Exists(i => i.TeamName == name), "bob sees the invitation");
            ApiResult<TeamInfo> joined = Api.AcceptTeamInvite(bob.Token, made.Value.Id).Result;
            Check(joined.Ok && joined.Value.Members.Count == 2, "and accepts it: two in the team");
            Check(RunLobbiesUntil(5_000, () => la.Team?.Members.Count == 2 && lb.Team?.Members.Count == 2, la, lb),
                "both were pushed the team of two (evt.team.update)");
            ApiResult<TeamInfo> promoted = Api.SetTeamRole(ada.Token, bob.PlayerId, "vice_leader").Result;
            Check(promoted.Ok && promoted.Value.Members.Exists(m => m.PlayerId == bob.PlayerId && m.Role == "vice_leader"),
                "ada makes bob a vice leader");
            ApiResult<TeamInfo> handed = Api.HandOverTeam(ada.Token, bob.PlayerId).Result;
            Check(handed.Ok && handed.Value.Members[0].PlayerId == bob.PlayerId, "and hands him the team");
            Check(Api.LeaveTeam(ada.Token).Result.Ok, "ada leaves");
            Check(Api.MyTeam(bob.Token).Result.Value?.Members.Count == 1, "bob leads it alone");
            Check(RunLobbiesUntil(5_000, () => la.Team == null && lb.Team?.Members.Count == 1, la, lb),
                "ada was pushed no team, bob his team of one");
            Check(Api.DisbandTeam(bob.Token).Result.Ok, "and disbands it");
            Check(RunLobbiesUntil(5_000, () => lb.Team == null, la, lb), "and was pushed no team");
            ApiResult<TeamInfo> gone = Api.MyTeam(bob.Token).Result;
            Check(!gone.Ok && gone.Status == 404 && gone.Code == "no_team", "and is in no team");
        }

        /// <summary>
        /// A whole bracket of four (04 §6). An operator creates a duel tournament; four players enter,
        /// their ratings equal, so seeded in the order they entered. Each round's matches are pushed
        /// as evt.tournament.match, and one side of each goes: seed 4 beats seed 1, and seed 2 beats
        /// seed 3, by walkover. The drill's arena has one room for matches, so round 1's second
        /// match waits for it, promised to the first until its room is made and gone (D-42, T-21).
        /// A minute on, the final, seed 4 against seed 2, is seed 4's the same way. The prizes
        /// reach each wallet, and the tournament is finished.
        /// </summary>
        /// <summary>
        /// Gives fresh players or teams the ten rated matches a tournament's entrant needs (Q-43), as a
        /// test's fixture does: straight to the drill's database, by the script's BACKEND_DRILL_SQL.
        /// </summary>
        private static bool Played(string table, string column, long[] ids) =>
            DrillSql($"UPDATE {table} SET {column} = GREATEST({column}, 10) WHERE id IN ({string.Join(",", ids)})");

        /// <summary>
        /// Gives a fresh player coins, as a fixture, through the ledger as every balance moves (06 §4):
        /// the row and the balance together, so the nightly check finds them agreeing.
        /// </summary>
        private static bool Granted(long playerId, long coins) => Granted(playerId, coins, "coins", 0);

        /// <summary>As coins are given, gems (currency 1): the ledger row and the balance together.</summary>
        private static bool Granted(long playerId, long amount, string column, int currency) => DrillSql(
            "INSERT INTO ledger (player_id, currency, delta, balance_after, reason, ref, idem_key)"
            + $" SELECT id, {currency}, {amount}, {column} + {amount}, 2, 'drill fixture', CONCAT('drill:', id, ':', UUID())"
            + $" FROM player WHERE id = {playerId}; UPDATE player SET {column} = {column} + {amount} WHERE id = {playerId}");

        /// <summary>
        /// Gems (04 §8, plan item 68): 25 given through the ledger as a fixture; the release's boost
        /// bought for 20 of them; the next refused, 5 short.
        /// </summary>
        private static void Gems()
        {
            var (session, _) = NewSession();
            Check(Granted(session.PlayerId, 25, "gems", 1), "given 25 gems, as a fixture, through the ledger");
            ApiResult<List<Offer>> shop = Api.Shop().Result;
            Check(shop.Ok && shop.Value.Exists(o => o.Sku == "boost_xp_hour" && o.Currency == "gems" && o.Price == 20),
                "the release's shop sells the hour's boost for 20 gems");
            ApiResult<Receipt> bought = Api.Purchase(session.Token, "boost_xp_hour", ApiClient.NewPurchaseKey()).Result;
            Check(bought.Ok && bought.Value.Gems == 5 && bought.Value.Held == 1, $"bought with gems ({bought})");
            ApiResult<Receipt> again = Api.Purchase(session.Token, "boost_xp_hour", ApiClient.NewPurchaseKey()).Result;
            Check(!again.Ok && again.Code == "insufficient_funds", $"the next refused, 5 gems short ({again})");
        }

        /// <summary>
        /// Gems for money (04 §8, D-68), the provider simulated: the release's packs; an order for 80, its key
        /// retried into the same order, paid, its gems and the first purchase's as many again; a second
        /// confirm final; an order declined, nothing; 40 of the gems spent, the paid order refunded by the
        /// operator, 120 taken back and 40 owed; no order while owed; the debt cleared; the next order paid
        /// without a bonus.
        /// </summary>
        private static void PurchaseGems()
        {
            if (Environment.GetEnvironmentVariable("BACKEND_ADMIN_URL") == null)
            {
                Fail("purchase needs BACKEND_ADMIN_URL and BACKEND_ADMIN_TOKEN");
                return;
            }
            var (session, _) = NewSession();
            ApiResult<List<Pack>> packs = Api.Packs().Result;
            Check(packs.Ok && packs.Value.Count == 5 && packs.Value[0].ProductId == "gems_80" && packs.Value[0].PriceCents == 99,
                $"the release's five packs, from 80 gems for 0.99 ({packs})");

            string key = ApiClient.NewPurchaseKey();
            ApiResult<PaymentOrder> placed = Api.PlaceOrder(session.Token, "gems_80", key).Result;
            Check(placed.Ok && placed.Value.State == "pending", $"an order for 80 gems, pending ({placed})");
            string id = placed.Value?.OrderId;
            Check(Api.PlaceOrder(session.Token, "gems_80", key).Result.Value?.OrderId == id, "its key retried: the same order");
            ApiResult<PaymentResult> paid = Api.SimulatePayment(session.Token, id, true).Result;
            Check(paid.Ok && paid.Value.Confirmed && paid.Value.Gems == 160 && paid.Value.Order.Bonus == 80,
                $"paid: 80 gems and the first purchase's 80 ({paid})");
            ApiResult<PaymentResult> again = Api.SimulatePayment(session.Token, id, false).Result;
            Check(again.Ok && !again.Value.Confirmed && again.Value.Order.State == "paid", $"a second word is not heard ({again})");
            string declined = Api.PlaceOrder(session.Token, "gems_500", ApiClient.NewPurchaseKey()).Result.Value?.OrderId;
            ApiResult<PaymentResult> no = Api.SimulatePayment(session.Token, declined, false).Result;
            Check(no.Ok && no.Value.Order.State == "declined" && no.Value.Gems == 160, $"an order declined: nothing ({no})");

            Check(Api.Purchase(session.Token, "boost_xp_hour", ApiClient.NewPurchaseKey()).Result.Ok
                  && Api.Purchase(session.Token, "boost_xp_hour", ApiClient.NewPurchaseKey()).Result.Ok, "40 of them spent on boosts");
            var refunded = Admin("POST", $"/admin/payments/{id}/refund", "{\"reason\":\"drill: a chargeback\"}");
            JsonValue r = JsonValue.Parse(refunded.Content.ReadAsStringAsync().Result);
            Check((int)refunded.StatusCode == 200 && r["taken"].AsInt == 120 && r["debt"].AsInt == 40,
                $"the operator refunded it: 120 taken back, 40 owed ({(int)refunded.StatusCode} {r})");
            ApiResult<PaymentOrder> owed = Api.PlaceOrder(session.Token, "gems_80", ApiClient.NewPurchaseKey()).Result;
            Check(!owed.Ok && owed.Status == 409 && owed.Code == "refund_debt", $"no order while owed ({owed})");
            var cleared = Admin("POST", $"/admin/players/{session.PlayerId}/refund-debt", "{\"reason\":\"drill: support\"}");
            Check((int)cleared.StatusCode == 200 && JsonValue.Parse(cleared.Content.ReadAsStringAsync().Result)["cleared"].AsLong == 40,
                "support cleared the 40");
            string next = Api.PlaceOrder(session.Token, "gems_80", ApiClient.NewPurchaseKey()).Result.Value?.OrderId;
            ApiResult<PaymentResult> second = Api.SimulatePayment(session.Token, next, true).Result;
            Check(second.Ok && second.Value.Order.Bonus == 0 && second.Value.Gems == 80, $"the next order paid, no bonus ({second})");
            Check(Api.GetOrder(session.Token, id).Result.Value?.State == "refunded", "and the first is refunded");
        }

        /// <summary>
        /// Looks (04 §8, D-70): the release's skin table; 150 gems as a fixture buy the crimson skin, worn in its
        /// own slot, giving nothing; in the public arena the player's own tank is told it, skin 1, with its create;
        /// another player, wearing none, is told none.
        /// </summary>
        private static void SkinPlay()
        {
            var (session, _) = NewSession();
            ApiResult<SkinTable> table = Api.Skins().Result;
            Check(table.Ok && table.Value.ItemIds.Count == 5 && table.Value.ItemIdOf(1) == "skin_crimson",
                $"the release's five skins, crimson the first ({table})");
            Check(Granted(session.PlayerId, 150, "gems", 1), "given 150 gems, as a fixture, through the ledger");
            ApiResult<Receipt> bought = Api.Purchase(session.Token, "skin_crimson", ApiClient.NewPurchaseKey()).Result;
            Check(bought.Ok && bought.Value.Gems == 0, $"bought the crimson skin for 150 gems ({bought})");
            ApiResult<Loadout> worn = Api.Wear(session.Token, "skin", "skin_crimson").Result;
            Check(worn.Ok && worn.Value.Slots["skin"] == "skin_crimson" && worn.Value.Bonus.Count == 0,
                $"worn in its own slot, giving nothing ({worn})");

            MatchGrant grant = Api.RequestMatch(session.Token).Result.Value;
            var g = new Grant { Host = grant.ArenaHost, Port = grant.ArenaPort, TicketId = grant.TicketId, Tls = grant.Tls };
            using var m = Connect(g);
            m.Join(g.TicketId);
            Check(RunUntil(m, 10_000, () => m.Alive && m.World[Wire.SelfHandle].Skin != 0) && m.World[Wire.SelfHandle].Skin == 1,
                $"in the public arena, its own tank told as skin 1 ({m.World[Wire.SelfHandle].Skin})");

            var (other, _) = NewSession();
            MatchGrant plain = Api.RequestMatch(other.Token).Result.Value;
            var pg = new Grant { Host = plain.ArenaHost, Port = plain.ArenaPort, TicketId = plain.TicketId, Tls = plain.Tls };
            using var p = Connect(pg);
            p.Join(pg.TicketId);
            Check(RunUntil(p, 10_000, () => p.Alive) && p.World[Wire.SelfHandle].Skin == 0, "another, wearing none, told none");
        }

        /// <summary>
        /// The season pass (04 §8, D-69): a new player's pass, forty tiers, nothing yet; 245 points as a fixture;
        /// one stay crosses the first tier, its 150 coins told by evt.rewards; 500 gems as a fixture buy premium,
        /// which pays the first premium tier's 15 at once; a second tap is answered as the first.
        /// </summary>
        private static void PassPlay()
        {
            var (session, _) = NewSession();
            ApiResult<PassInfo> fresh = Api.SeasonPass(session.Token).Result;
            Check(fresh.Ok && fresh.Value.Points == 0 && !fresh.Value.Premium && fresh.Value.Tiers.Count == 40
                  && fresh.Value.Tiers[4].Premium.ItemId == "boost_xp_hour", $"a new pass: nothing yet, forty tiers ({fresh})");
            Check(DrillSql("INSERT INTO season_pass (player_id, season_id, points) SELECT " + session.PlayerId
                  + ", id, 245 FROM season WHERE placed_at IS NULL ORDER BY id DESC LIMIT 1"), "245 points, as a fixture");
            using var lobby = new LobbyClient(_lobby, new SystemClock());
            JsonValue paid = null;
            lobby.OnPush += (t, d) => { if (t == "evt.rewards") paid = d; };
            lobby.Connect(session.Token);
            Check(RunLobbiesUntil(10_000, () => lobby.State == LobbyState.Ready, lobby), "in the lobby");
            Check(PaidStay(session) > 0, "a stay, paid");
            Check(RunLobbiesUntil(10_000, () => paid != null, lobby) && paid["pass"]["points"].AsInt >= 10
                  && paid["pass"]["tier"].AsInt == 1 && paid["pass"]["coins"].AsLong == 150,
                $"told by evt.rewards: its points, the first tier, 150 coins ({paid?["pass"]})");
            Check(Api.SeasonPass(session.Token).Result.Value?.Tier == 1, "the pass at the first tier");

            Check(Granted(session.PlayerId, 500, "gems", 1), "given 500 gems, as a fixture, through the ledger");
            ApiResult<PremiumAnswer> bought = Api.BuyPremium(session.Token).Result;
            Check(bought.Ok && bought.Value.Result == "bought" && bought.Value.Gems == 15 && bought.Value.Pass.Premium,
                $"premium bought, the first premium tier's 15 gems paid at once ({bought})");
            ApiResult<PremiumAnswer> again = Api.BuyPremium(session.Token).Result;
            Check(again.Ok && again.Value.Result == "already_bought" && again.Value.Gems == 15, $"once ({again})");
        }

        /// <summary>
        /// A level's gems (04 §8, D-61): a fresh player given 50 000 xp, level 6 on the curve, with level 4
        /// stored, as a fixture; a stay writes level 6, past milestone 5, which pays 20 gems through the
        /// ledger, told by evt.rewards.
        /// </summary>
        private static void Milestone()
        {
            var (session, _) = NewSession();
            Check(DrillSql($"UPDATE player SET xp = 50000, level = 4 WHERE id = {session.PlayerId}"),
                "given 50 000 xp with level 4 stored, as a fixture");
            using var lobby = new LobbyClient(_lobby, new SystemClock());
            JsonValue paid = null;
            lobby.OnPush += (t, d) => { if (t == "evt.rewards") paid = d; };
            lobby.Connect(session.Token);
            Check(RunLobbiesUntil(10_000, () => lobby.State == LobbyState.Ready, lobby), "in the lobby");
            Check(PaidStay(session) > 0, "a stay, paid");
            Check(RunLobbiesUntil(10_000, () => paid != null, lobby) && paid["gems"].AsLong == 20,
                $"told by evt.rewards: 20 gems, level 5's milestone ({paid})");
            Check(Api.Inventory(session.Token).Result.Value.Gems == 20, "and the 20 are in the wallet");
        }

        /// <summary>
        /// A season's end (04 §7, D-63): two players at the top of the duel board, as a fixture; an operator
        /// ends the season now; within the minute the worker writes its places, pays them in gems with an
        /// inbox item and a push, and resets every rating, so neither is listed in the new season.
        /// </summary>
        private static void Season()
        {
            var (ada, _) = NewSession();
            var (bob, _) = NewSession();
            Check(DrillSql($"UPDATE player SET rating_duel = 2950, rated_duels = 10 WHERE id = {ada.PlayerId}")
                && DrillSql($"UPDATE player SET rating_duel = 2940, rated_duels = 10 WHERE id = {bob.PlayerId}"),
                "two at the top of the duel board, as a fixture");
            // And a team at the top of the board of teams (D-65): ada played a rated team match for it this
            // season, as a fixture; bob, a member who did not, is not paid its place.
            ApiResult<TeamInfo> team = Api.CreateTeam(ada.Token, "s" + Guid.NewGuid().ToString("N").Substring(0, 10)).Result;
            Check(team.Ok && Api.InviteToTeam(ada.Token, bob.PlayerId).Result.Ok
                && Api.AcceptTeamInvite(bob.Token, team.Value.Id).Result.Ok, $"ada's team, bob in it ({team})");
            if (!team.Ok) return;
            string uid = "01JD" + Guid.NewGuid().ToString("N").Substring(0, 22).ToUpperInvariant();
            Check(DrillSql($"UPDATE team SET rating = 2950, rated_matches = 10 WHERE id = {team.Value.Id}")
                && DrillSql($"INSERT INTO matches (match_uid, mode, kind, arena, started_at, ended_at)"
                    + $" VALUES ('{uid}', 5, 1, 'arena-drill', UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))")
                && DrillSql($"INSERT INTO match_team (match_id, side, team_id, placement, rating_delta)"
                    + $" SELECT id, 1, {team.Value.Id}, 1, 0 FROM matches WHERE match_uid = '{uid}'")
                && DrillSql($"INSERT INTO match_player (match_id, player_id, team, placement, kills, deaths, score, xp_gained)"
                    + $" SELECT id, {ada.PlayerId}, 1, 1, 0, 0, 0, 0 FROM matches WHERE match_uid = '{uid}'"),
                "the team at the top of the board of teams, and ada's team match for it, as a fixture");
            ApiResult<SeasonsInfo> before = Api.Seasons().Result;
            Check(before.Ok && before.Value.Current != null, $"a season is being played ({before})");
            if (!before.Ok || before.Value.Current == null) return;
            int season = before.Value.Current.Id;
            Console.WriteLine($"    season {season}, to end {before.Value.Current.EndsAt}");
            long gemsA = Api.Inventory(ada.Token).Result.Value.Gems, gemsB = Api.Inventory(bob.Token).Result.Value.Gems;
            using var lobby = new LobbyClient(_lobby, new SystemClock());
            bool told = false;
            lobby.OnPush += (t, d) => { if (t == "evt.inbox") told = true; };
            lobby.Connect(ada.Token);
            Check(RunLobbiesUntil(10_000, () => lobby.State == LobbyState.Ready, lobby), "ada is in the lobby");

            var ended = Admin("POST", "/admin/seasons/end", "{\"reason\":\"the season drill\"}");
            Check(ended.IsSuccessStatusCode, $"an operator ended season {season} now ({(int)ended.StatusCode})");
            // The worker's season job looks each minute, its first a minute after it started.
            var clock = System.Diagnostics.Stopwatch.StartNew();
            bool closed = false;
            long nextLook = 0;
            while (!closed && clock.ElapsedMilliseconds < 150_000)
            {
                lobby.Poll();
                if (clock.ElapsedMilliseconds >= nextLook)
                {
                    nextLook += 1_000;
                    closed = Api.Seasons().Result.Value?.Current?.Id == season + 1
                        && Api.Inventory(ada.Token).Result.Value.Gems >= gemsA + 100;
                }
                Thread.Sleep(50);
            }
            Console.WriteLine($"    closed {clock.ElapsedMilliseconds / 1000} s after the operator's call");
            Check(closed, $"the worker closed it: season {season + 1} begun, its first place paid");
            bool both = false;
            for (int i = 0; i < 20 && !both; i++)
            {
                both = Api.Inventory(ada.Token).Result.Value.Gems == gemsA + 200;
                if (!both) Thread.Sleep(250);
            }
            Check(both && Api.Inventory(bob.Token).Result.Value.Gems == gemsB + 60,
                "ada paid 200 gems, 100 for the duel board's first place and 100 for her team's; bob 60 for second, none for the team");
            Check(RunLobbiesUntil(5_000, () => told, lobby), "ada was told to look: evt.inbox");
            List<InboxItem> items = Api.Inbox(ada.Token).Result.Value;
            Check(items.Exists(i => i.Kind == "season_reward" && i.Ref == season * 10L + 1)
                && items.Exists(i => i.Kind == "season_reward" && i.Ref == season * 10L + 5),
                $"her inbox names season {season}'s duel board and its board of teams");
            ApiResult<List<RankRow>> teamPlace = Api.AroundMe("teams", ada.Token, season).Result;
            Check(teamPlace.Ok && teamPlace.Value.Exists(r => r.TeamId == team.Value.Id && r.Rank == 1 && r.Score == 2950),
                $"season {season}'s board of teams keeps her team first ({teamPlace})");
            Check(Api.AroundMe("teams", bob.Token, season).Result.Code == "not_ranked", "and bob, who did not play for it, has no place there");
            ApiResult<List<RankRow>> mine = Api.AroundMe("duel", ada.Token, season).Result;
            Check(mine.Ok && mine.Value.Exists(r => r.PlayerId == ada.PlayerId && r.Rank == 1 && r.Score == 2950),
                $"season {season}'s duel board keeps her first, at 2 950 ({mine})");
            bool reset = false;
            for (int i = 0; i < 20 && !reset; i++)
            {
                reset = Api.AroundMe("duel", ada.Token).Result.Code == "not_ranked";
                if (!reset) Thread.Sleep(500);
            }
            Check(reset, "and in the new season she is not listed: its ten rated matches are to play");
        }

        /// <summary>
        /// Achievements (04 §8, D-64): a player with 49 stays counted, as a fixture; a fiftieth, and it is paid,
        /// 10 gems, told by evt.rewards with the achievement's id, and listed as reached at 50.
        /// </summary>
        private static void AchievementsPlay()
        {
            var (session, _) = NewSession();
            Check(DrillSql($"UPDATE player_stat SET matches = 49 WHERE player_id = {session.PlayerId}"),
                "49 stays counted, as a fixture");
            ApiResult<List<AchievementInfo>> before = Api.Achievements(session.Token).Result;
            Check(before.Ok && before.Value.Count == 17
                && before.Value.Exists(a => a.Id == "matches_50" && a.Progress == 49 && !a.Reached),
                $"seventeen listed; the fiftieth stay not yet reached, at 49 ({before})");
            using var lobby = new LobbyClient(_lobby, new SystemClock());
            JsonValue paid = null;
            lobby.OnPush += (t, d) => { if (t == "evt.rewards") paid = d; };
            lobby.Connect(session.Token);
            Check(RunLobbiesUntil(10_000, () => lobby.State == LobbyState.Ready, lobby), "in the lobby");
            Check(PaidStay(session) > 0, "a stay, paid");
            Check(RunLobbiesUntil(10_000, () => paid != null, lobby) && paid["gems"].AsLong == 10
                && paid["achievements"].Count == 1 && paid["achievements"][0].AsString == "matches_50",
                $"told by evt.rewards: 10 gems, and matches_50 named ({paid})");
            Check(Api.Inventory(session.Token).Result.Value.Gems == 10, "and the 10 are in the wallet");
            ApiResult<List<AchievementInfo>> after = Api.Achievements(session.Token).Result;
            Check(after.Ok && after.Value.Exists(a => a.Id == "matches_50" && a.Reached && a.Progress == 50),
                "listed as reached, at 50");
        }

        /// <summary>
        /// Daily goals (04 §8, D-66): a new player whose day has a stays goal (accounts are made until one has);
        /// the other two met and the stays goal a stay short, as a fixture; one stay meets it, and with it the
        /// day's three: its coins and the set's 3 gems, told by evt.rewards and in the wallet.
        /// </summary>
        private static void GoalsPlay()
        {
            Session session = default;
            GoalsInfo today = null;
            GoalInfo stays = null;
            for (int i = 0; i < 15 && stays == null; i++)
            {
                (session, _) = NewSession();
                today = Api.Goals(session.Token).Result.Value;
                stays = today?.Goals.Find(g => g.Kind == "stays");
            }
            Check(stays != null, $"a new player whose day has a stays goal ({stays?.Id})");
            if (stays == null) return;
            Check(today.Goals.Count == 3 && !today.Goals.Exists(g => g.Done) && today.SetGems == 3 && !today.SetDone,
                $"three goals for {today.Day}, none met, all three worth 3 gems");
            bool set = true;
            foreach (GoalInfo g in today.Goals)
                set &= DrillSql($"INSERT INTO daily_goal (player_id, day, goal_id, progress) VALUES ({session.PlayerId},"
                    + $" '{today.Day}', '{g.Id}', {(g.Id == stays.Id ? g.Target - 1 : g.Target)})");
            Check(set, $"the other two met and {stays.Id} a stay short, as a fixture");
            var wallet = Api.Inventory(session.Token).Result.Value;
            using var lobby = new LobbyClient(_lobby, new SystemClock());
            JsonValue paid = null;
            lobby.OnPush += (t, d) => { if (t == "evt.rewards") paid = d; };
            lobby.Connect(session.Token);
            Check(RunLobbiesUntil(10_000, () => lobby.State == LobbyState.Ready, lobby), "in the lobby");
            Check(PaidStay(session) > 0, "a stay, paid");
            Check(RunLobbiesUntil(10_000, () => paid != null, lobby) && paid["goals"].Count == 1
                && paid["goals"][0].AsString == stays.Id && paid["goalCoins"].AsLong == stays.Coins && paid["gems"].AsLong == 3,
                $"told by evt.rewards: {stays.Id} met, its {stays.Coins} coins, and the set's 3 gems ({paid})");
            var after = Api.Inventory(session.Token).Result.Value;
            Check(paid != null && after.Coins == wallet.Coins + paid["coins"].AsLong + stays.Coins && after.Gems == wallet.Gems + 3,
                "and in the wallet: the match's coins and the goal's, and the 3 gems");
            GoalsInfo done = Api.Goals(session.Token).Result.Value;
            Check(done.Goals.TrueForAll(g => g.Done) && done.SetDone, "today's three listed as met, and the set");
        }

        private static bool DrillSql(string sql)
        {
            string run = Environment.GetEnvironmentVariable("BACKEND_DRILL_SQL");
            if (run == null) return false;
            var start = new System.Diagnostics.ProcessStartInfo(run) { RedirectStandardError = true };
            start.ArgumentList.Add(sql);
            using var p = System.Diagnostics.Process.Start(start);
            p.StandardError.ReadToEnd();
            p.WaitForExit();
            return p.ExitCode == 0;
        }

        /// <summary>
        /// Item levels (04 §8, plan item 67): the drill's barrel bought and raised to level 2 for 500
        /// coins, once a key, and the next level refused for want of coins.
        /// </summary>
        private static void Levels()
        {
            var (session, _) = NewSession();
            Check(Granted(session.PlayerId, 600), "given 600 coins, as a fixture, through the ledger");
            ApiResult<Receipt> bought = Api.Purchase(session.Token, "drill_barrel", ApiClient.NewPurchaseKey()).Result;
            Check(bought.Ok && bought.Value.Coins == 599, $"bought the drill's barrel for a coin ({bought})");
            ApiResult<ItemLevel> raised = Api.RaiseItemLevel(session.Token, "drill_barrel", "level-raise-0001").Result;
            Check(raised.Ok && raised.Value.Level == 2 && raised.Value.Coins == 99, $"raised to level 2 for 500 ({raised})");
            ApiResult<ItemLevel> again = Api.RaiseItemLevel(session.Token, "drill_barrel", "level-raise-0001").Result;
            Check(again.Ok && again.Value.Level == 2 && again.Value.Coins == 99, "the same key again: charged once");
            ApiResult<ItemLevel> poor = Api.RaiseItemLevel(session.Token, "drill_barrel", "level-raise-0002").Result;
            Check(!poor.Ok && poor.Status == 409 && poor.Code == "insufficient_funds", $"level 3, 1 000 coins, refused ({poor})");
            Check(Api.Inventory(session.Token).Result.Value.Items.Exists(i => i.ItemId == "drill_barrel" && i.Level == 2),
                "the inventory says level 2");
        }

        /// <summary>
        /// Renaming (04 §1, plan item 63): a player's display name, at once and then not again within 30
        /// days; their team showing it; the team renamed by its leader, its other member's lobby told
        /// through the gateway.
        /// </summary>
        private static void Rename()
        {
            var (lead, _) = NewSession();
            var (other, _) = NewSession();
            var lobby = new LobbyClient(_lobby, new SystemClock());
            lobby.Connect(other.Token);
            bool Until(int ms, Func<bool> done)
            {
                var clock = System.Diagnostics.Stopwatch.StartNew();
                while (clock.ElapsedMilliseconds < ms)
                {
                    lobby.Poll();
                    if (done()) return true;
                    Thread.Sleep(16);
                }
                return false;
            }
            try
            {
                string suffix = (lead.PlayerId % 100_000).ToString();
                string name = "Renamed" + suffix;
                ApiResult<Renamed> renamed = Api.Rename(lead.Token, name).Result;
                Check(renamed.Ok && renamed.Value.DisplayName == name, $"a player renamed ({renamed})");
                ApiResult<Renamed> again = Api.Rename(lead.Token, "Again" + suffix).Result;
                Check(!again.Ok && again.Status == 429 && again.Code == "too_soon", $"and not again within 30 days ({again})");
                Check(Until(10_000, () => lobby.State == LobbyState.Ready), "the other in the lobby");
                ApiResult<TeamInfo> made = Api.CreateTeam(lead.Token, "rn" + suffix).Result;
                Check(made.Ok, $"a team made ({made})");
                if (!made.Ok) return;
                Api.InviteToTeam(lead.Token, other.PlayerId).Wait();
                Api.AcceptTeamInvite(other.Token, made.Value.Id).Wait();
                Check(Until(5_000, () => lobby.Team != null && lobby.Team.Members.Count == 2), "the team of two, told to the other");
                Check(lobby.Team != null && lobby.Team.Members.Exists(m => m.PlayerId == lead.PlayerId && m.Name == name),
                    "showing the leader's new name");
                ApiResult<TeamInfo> teamRenamed = Api.RenameTeam(lead.Token, "rt" + suffix).Result;
                Check(teamRenamed.Ok && teamRenamed.Value.Name == "rt" + suffix, $"the team renamed by its leader ({teamRenamed})");
                Check(Until(5_000, () => lobby.Team?.Name == "rt" + suffix), "and its other member's lobby told, through the gateway");
            }
            finally
            {
                lobby.Dispose();
            }
        }

        /// <summary>
        /// Team applications (04 §2, plan item 64): a team found by its name and applied to, its leader
        /// told through the gateway and accepting; the applicant's lobby told they are in.
        /// </summary>
        private static void Apply()
        {
            var (lead, _) = NewSession();
            var (applicant, _) = NewSession();
            using var ll = new LobbyClient(_lobby, new SystemClock());
            using var la = new LobbyClient(_lobby, new SystemClock());
            var toLead = new List<string>();
            ll.OnPush += (t, d) => toLead.Add(t);
            ll.Connect(lead.Token);
            la.Connect(applicant.Token);
            Check(RunLobbiesUntil(10_000, () => ll.State == LobbyState.Ready && la.State == LobbyState.Ready, ll, la),
                "the leader-to-be and the applicant are in the lobby");
            string name = "ap" + (lead.PlayerId % 100_000);
            ApiResult<TeamInfo> made = Api.CreateTeam(lead.Token, name).Result;
            Check(made.Ok, $"a team made ({made})");
            if (!made.Ok) return;
            ApiResult<List<TeamFound>> found = Api.FindTeams(applicant.Token, name).Result;
            Check(found.Ok && found.Value.Exists(t => t.Id == made.Value.Id), $"found by its name ({found})");
            ApiResult<bool> applied = Api.ApplyToTeam(applicant.Token, made.Value.Id).Result;
            Check(applied.Ok, $"applied ({applied})");
            Check(RunLobbiesUntil(5_000, () => toLead.Contains("evt.inbox"), ll, la), "the leader told, through the gateway");
            ApiResult<List<TeamApplicant>> listed = Api.TeamApplications(lead.Token).Result;
            Check(listed.Ok && listed.Value.Exists(a => a.PlayerId == applicant.PlayerId), $"and sees it ({listed})");
            ApiResult<TeamInfo> accepted = Api.AnswerApplication(lead.Token, applicant.PlayerId, true).Result;
            Check(accepted.Ok && accepted.Value.Members.Count == 2, $"accepted: a team of two ({accepted})");
            Check(RunLobbiesUntil(5_000, () => la.Team?.Id == made.Value.Id, ll, la), "the applicant's lobby told they are in");
        }

        /// <summary>
        /// A round robin (04 §6, plan item 66): three players, three rounds of one match, each sitting
        /// one round out; the one who comes wins by walkover; the standings, 6, 3 and 0, pay by place.
        /// By the circle method, seed 2 meets 3, then 1 meets 3, then 1 meets 2.
        /// </summary>
        private static void RoundRobin()
        {
            string admin = Environment.GetEnvironmentVariable("BACKEND_ADMIN_URL");
            string secret = Environment.GetEnvironmentVariable("BACKEND_ADMIN_TOKEN");
            if (admin == null || secret == null)
            {
                Fail("roundrobin needs BACKEND_ADMIN_URL and BACKEND_ADMIN_TOKEN");
                return;
            }
            var seeds = new Session[3];
            var lobbies = new LobbyClient[3];
            for (int i = 0; i < 3; i++)
            {
                seeds[i] = NewSession().session;
                lobbies[i] = new LobbyClient(_lobby, new SystemClock());
                lobbies[i].Connect(seeds[i].Token);
            }
            var matches = new List<MatchConnection>();
            bool Until(int ms, Func<bool> done)
            {
                var clock = System.Diagnostics.Stopwatch.StartNew();
                while (clock.ElapsedMilliseconds < ms)
                {
                    foreach (var l in lobbies) l.Poll();
                    foreach (var m in matches) m.Poll();
                    if (done()) return true;
                    Thread.Sleep(16);
                }
                return false;
            }
            bool Walkover(LobbyClient lobby, int round)
            {
                TournamentGrant g = lobby.TournamentMatch;
                var m = Connect(new Grant { Host = g.ArenaHost, Port = g.ArenaPort, TicketId = g.TicketId, Tls = g.Tls });
                matches.Add(m);
                m.Join(g.TicketId);
                return Until(45_000, () => m.State == MatchState.Ended) && m.End == MatchEnd.MatchOver;
            }
            try
            {
                Check(Until(10_000, () => Array.TrueForAll(lobbies, l => l.State == LobbyState.Ready)), "three players are in the lobby");
                string At(double seconds) => DateTime.UtcNow.AddSeconds(seconds).ToString("yyyy-MM-ddTHH:mm:ss.fffZ");
                using var http = new System.Net.Http.HttpClient();
                var create = new System.Net.Http.HttpRequestMessage(System.Net.Http.HttpMethod.Post, admin + "/admin/tournaments")
                {
                    Content = new System.Net.Http.StringContent("{\"reason\":\"drill\",\"name\":\"Drill league\",\"maxEntries\":3,"
                        + $"\"registrationEnds\":\"{At(8)}\",\"startsAt\":\"{At(10)}\",\"roundMinutes\":1,\"prizes\":[300,200,100],"
                        + "\"format\":\"round_robin\"}"),
                };
                create.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", secret);
                var created = http.Send(create);
                Check((int) created.StatusCode == 200, $"the operator created a round robin ({(int) created.StatusCode})");
                long id = JsonValue.Parse(created.Content.ReadAsStringAsync().Result)["id"].AsLong;
                Check(Played("player", "rated_duels", Array.ConvertAll(seeds, s => s.PlayerId)),
                    "the three given ten rated duels, as a fixture (Q-43)");
                var before = new long[3];
                for (int i = 0; i < 3; i++)
                {
                    Check(Api.EnterTournament(seeds[i].Token, id).Result.Ok, $"player {i + 1} entered");
                    before[i] = CoinsBesideGoals(seeds[i]);
                }

                Check(Until(30_000, () => lobbies[1].TournamentMatch?.TournamentId == id && lobbies[2].TournamentMatch?.TournamentId == id),
                    "round 1: seeds 2 and 3 were pushed their match");
                Check(lobbies[0].TournamentMatch == null, "and seed 1 sits it out");
                Check(Walkover(lobbies[1], 1), "seed 2 came alone, and won by walkover");
                Check(Until(100_000, () => lobbies[0].TournamentMatch?.Round == 2 && lobbies[2].TournamentMatch?.Round == 2),
                    "round 2, a minute on: seeds 1 and 3");
                Check(Walkover(lobbies[0], 2), "seed 1 came alone, and won by walkover");
                Check(Until(100_000, () => lobbies[0].TournamentMatch?.Round == 3 && lobbies[1].TournamentMatch?.Round == 3),
                    "round 3: seeds 1 and 2");
                Check(Walkover(lobbies[0], 3), "seed 1 came alone again, and won");
                Check(Until(20_000, () => Api.Tournament(id).Result.Value.State == "finished"), "the round robin is finished");
                TournamentInfo done = Api.Tournament(id).Result.Value;
                Check(done.Format == "round_robin" && done.Standings.Count == 3, "its view says round robin, with standings");
                Check(done.Standings.Count == 3 && done.Standings[0].PlayerId == seeds[0].PlayerId && done.Standings[0].Points == 6
                      && done.Standings[1].PlayerId == seeds[1].PlayerId && done.Standings[1].Points == 3
                      && done.Standings[2].PlayerId == seeds[2].PlayerId && done.Standings[2].Points == 0,
                    "standings 6, 3 and 0: two wins, one, none");
                var prize = new long[] { 300, 200, 100 };
                for (int i = 0; i < 3; i++)
                {
                    long got = CoinsBesideGoals(seeds[i]) - before[i];
                    Check(got == prize[i], $"seed {i + 1} was paid {got} beside its goals, the prize {prize[i]} for its place");
                }
                Check(Array.TrueForAll(seeds, s => Api.Inventory(s.Token).Result.Value.Gems == 0),
                    "and no gems: three entries, and gems are paid from four (04 §8)");
            }
            finally
            {
                foreach (var m in matches) m.Dispose();
                foreach (var l in lobbies) l.Dispose();
            }
        }

        private static void Tournament()
        {
            string admin = Environment.GetEnvironmentVariable("BACKEND_ADMIN_URL");
            string secret = Environment.GetEnvironmentVariable("BACKEND_ADMIN_TOKEN");
            if (admin == null || secret == null)
            {
                Fail("tournament needs BACKEND_ADMIN_URL and BACKEND_ADMIN_TOKEN");
                return;
            }
            var seeds = new Session[4];
            var lobbies = new LobbyClient[4];
            for (int i = 0; i < 4; i++)
            {
                seeds[i] = NewSession().session;
                lobbies[i] = new LobbyClient(_lobby, new SystemClock());
                lobbies[i].Connect(seeds[i].Token);
            }
            var matches = new List<MatchConnection>();
            // Every lobby and match polled, as a frame loop would.
            bool Until(int ms, Func<bool> done)
            {
                var clock = System.Diagnostics.Stopwatch.StartNew();
                while (clock.ElapsedMilliseconds < ms)
                {
                    foreach (var l in lobbies) l.Poll();
                    foreach (var m in matches) m.Poll();
                    if (done()) return true;
                    Thread.Sleep(16);
                }
                return false;
            }
            MatchConnection Play(TournamentGrant g)
            {
                var m = Connect(new Grant { Host = g.ArenaHost, Port = g.ArenaPort, TicketId = g.TicketId, Tls = g.Tls });
                matches.Add(m);
                m.Join(g.TicketId);
                return m;
            }
            try
            {
                Check(Until(10_000, () => Array.TrueForAll(lobbies, l => l.State == LobbyState.Ready)), "four players are in the lobby");
                string At(double seconds) => DateTime.UtcNow.AddSeconds(seconds).ToString("yyyy-MM-ddTHH:mm:ss.fffZ");
                using var http = new System.Net.Http.HttpClient();
                var create = new System.Net.Http.HttpRequestMessage(System.Net.Http.HttpMethod.Post, admin + "/admin/tournaments")
                {
                    Content = new System.Net.Http.StringContent("{\"reason\":\"drill\",\"name\":\"Drill cup\",\"maxEntries\":4,"
                        + $"\"registrationEnds\":\"{At(8)}\",\"startsAt\":\"{At(10)}\",\"roundMinutes\":1,\"prizes\":[300,200,100]}}"),
                };
                create.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", secret);
                var created = http.Send(create);
                Check((int) created.StatusCode == 200, $"the operator created it ({(int) created.StatusCode})");
                long id = JsonValue.Parse(created.Content.ReadAsStringAsync().Result)["id"].AsLong;
                ApiResult<TournamentInfo> early = Api.EnterTournament(seeds[0].Token, id).Result;
                Check(!early.Ok && early.Code == "too_few_rated", $"a player with no rated duels is refused ({early})");
                Check(Played("player", "rated_duels", Array.ConvertAll(seeds, s => s.PlayerId)),
                    "the four given ten rated duels, as a fixture (Q-43)");
                var before = new long[4];
                for (int i = 0; i < 4; i++)
                {
                    Check(Api.EnterTournament(seeds[i].Token, id).Result.Ok, $"player {i + 1} entered");
                    before[i] = CoinsBesideGoals(seeds[i]);
                }
                Check(Api.Tournaments().Result.Value.Exists(t => t.Id == id && t.Entries.Count == 4), "listed, with four entries");

                // Round 1, at the start: 1 v 4 in the one room free, and 2 v 3 once it is free again.
                Check(Until(30_000, () => lobbies[0].TournamentMatch?.TournamentId == id && lobbies[3].TournamentMatch?.TournamentId == id),
                    "at the start, seeds 1 and 4 were pushed their match");
                Check(lobbies[1].TournamentMatch == null && lobbies[2].TournamentMatch == null,
                    "and 2 and 3 were not: their room is promised to that match");
                TournamentInfo bracket = Api.Tournament(id).Result.Value;
                Check(bracket.State == "running" && Array.TrueForAll(new[] { 0, 1, 2, 3 }, i => bracket.Entries[i].PlayerId == seeds[i].PlayerId),
                    "running, seeded in the order they entered");
                Check(bracket.Matches[0].PlayerA == seeds[0].PlayerId && bracket.Matches[0].PlayerB == seeds[3].PlayerId
                      && bracket.Matches[1].PlayerA == seeds[1].PlayerId && bracket.Matches[1].PlayerB == seeds[2].PlayerId,
                    "1 plays 4 and 2 plays 3");
                ApiResult<TournamentGrant> asked = Api.TournamentMatch(seeds[0].Token, id).Result;
                Check(asked.Ok && asked.Value.TicketId == lobbies[0].TournamentMatch.TicketId, "and the grant asked for is the one pushed");
                ApiResult<QueueStatus> queued = Api.JoinQueue(seeds[0].Token, "duel").Result;
                Check(!queued.Ok && queued.Code == "in_match", $"called, seed 1 may not queue meanwhile ({queued}; Q-44)");
                var clock = System.Diagnostics.Stopwatch.StartNew();
                MatchConnection four = Play(lobbies[3].TournamentMatch);
                Check(Until(45_000, () => four.State == MatchState.Ended) && four.End == MatchEnd.MatchOver,
                    $"seed 4 came alone, and won by walkover ({four.End})");
                Check(Until(30_000, () => lobbies[1].TournamentMatch?.TournamentId == id && lobbies[2].TournamentMatch?.TournamentId == id),
                    "then 2 and 3 were pushed theirs");
                Console.WriteLine($"    {clock.ElapsedMilliseconds / 1000} s after the first was joined");
                MatchConnection two = Play(lobbies[1].TournamentMatch);
                Check(Until(45_000, () => two.State == MatchState.Ended) && two.End == MatchEnd.MatchOver,
                    $"seed 2 came alone, was let in, and won by walkover ({two.End})");
                Check(Until(20_000, () =>
                    {
                        TournamentInfo t = Api.Tournament(id).Result.Value;
                        return t.CurrentRound == 2 && t.Matches[0].Winner == seeds[3].PlayerId && t.Matches[1].Winner == seeds[1].PlayerId;
                    }), "the walkovers were read: 4 and 2 through, the round over");

                // The final, a minute on: 4 against 2, and only 4 comes.
                clock.Restart();
                bool pushed = Until(90_000, () => lobbies[3].TournamentMatch.Round == 2 && lobbies[1].TournamentMatch.Round == 2);
                Check(pushed, "the final was pushed to both");
                if (!pushed) return;
                Console.WriteLine($"    {clock.ElapsedMilliseconds / 1000} s after the round ended");
                Check(clock.ElapsedMilliseconds >= 50_000, "a minute between rounds, give or take the ticks");
                MatchConnection final = Play(lobbies[3].TournamentMatch);
                Check(Until(45_000, () => final.State == MatchState.Ended) && final.End == MatchEnd.MatchOver,
                    $"seed 4 came alone, and the final ended a walkover ({final.End})");
                Check(Until(20_000, () => Api.Tournament(id).Result.Value.State == "finished"), "the tournament is finished");
                TournamentInfo done = Api.Tournament(id).Result.Value;
                Check(done.Matches[2].Winner == seeds[3].PlayerId, "won by seed 4");
                var prize = new long[] { 100, 200, 100, 300 };
                var gems = new long[] { 5, 15, 5, 30 };
                for (int i = 0; i < 4; i++)
                {
                    long got = CoinsBesideGoals(seeds[i]) - before[i];
                    Check(got == prize[i], $"seed {i + 1} was paid {got} beside its goals, the prize {prize[i]}");
                    long gemsGot = Api.Inventory(seeds[i].Token).Result.Value.Gems;
                    Check(gemsGot == gems[i], $"and {gemsGot} gems, its place's from four entries (04 §8)");
                }
            }
            finally
            {
                foreach (var m in matches) m.Dispose();
                foreach (var l in lobbies) l.Dispose();
            }
        }

        /// <summary>
        /// The wallet's coins less those the day's goals have paid (04 §8): a match played may meet one of a
        /// new player's goals, so a prize is checked beside them (T-44; a walkover counts toward none since D-45).
        /// </summary>
        private static long CoinsBesideGoals(Session s)
        {
            long coins = Api.Inventory(s.Token).Result.Value.Coins;
            foreach (GoalInfo g in Api.Goals(s.Token).Result.Value.Goals)
                if (g.Done) coins -= g.Coins;
            return coins;
        }

        /// <summary>A stay past the reward rules' 5 s minimum, not firing, and what it paid once applied.</summary>
        private static long PaidStay(Session s)
        {
            long before = Api.Inventory(s.Token).Result.Value.Coins;
            Grant g = GrantFor(s);
            using (var m = Connect(g))
            {
                m.Join(g.TicketId);
                Check(RunUntil(m, 10_000, () => m.Alive), "joined, to earn something");
                m.SetInput(Wire.MoveDown, Wire.QuantiseAim(1.57f), false, 0);
                RunFor(m, 6_500);
                m.Leave();
            }
            return WaitForCoins(s, before + 1) ? Api.Inventory(s.Token).Result.Value.Coins - before : 0;
        }

        private static Grant GrantFor(Session s)
        {
            ApiResult<MatchGrant> g = Api.RequestMatch(s.Token).Result;
            if (!g.Ok) throw new InvalidOperationException("match request: " + g);
            return new Grant { Host = g.Value.ArenaHost, Port = g.Value.ArenaPort, TicketId = g.Value.TicketId, Tls = g.Value.Tls };
        }

        /// <summary>Where the own tank is and what is around it, for a check that fails now and then (P-32).</summary>
        private static void DescribeSurroundings(MatchConnection m)
        {
            Entity self = m.World[Wire.SelfHandle];
            float scale = Wire.PosScale;
            Console.WriteLine($"    own tank: alive {self.Alive} at ({self.X / scale:F0}, {self.Y / scale:F0}) class {self.ClassId}"
                + $" hp {self.Hp} level {self.Level} flags {self.Flags}; frames {m.SnapshotsApplied}, round trip {m.RoundTripMs} ms");
            var kinds = new int[8];
            var near = new List<(double d, int h)>();
            for (int h = 1; h < Wire.MaxHandles; h++)
            {
                Entity e = m.World[h];
                if (!e.Alive || h == Wire.SelfHandle) continue;
                if (e.Kind >= 0 && e.Kind < kinds.Length) kinds[e.Kind]++;
                near.Add((Math.Sqrt(Math.Pow((e.X - self.X) / scale, 2) + Math.Pow((e.Y - self.Y) / scale, 2)), h));
                if (e.Kind == Wire.KindPredicted)
                    Console.WriteLine($"    predicted {h}: owner {e.OwnerHandle} at ({e.X / scale:F0}, {e.Y / scale:F0}) speed {e.Speed} radius {e.Radius}");
            }
            Console.WriteLine($"    seen by kind (tank, predicted, static, unit): {kinds[0]}, {kinds[1]}, {kinds[2]}, {kinds[3]}");
            near.Sort((a, b) => a.d.CompareTo(b.d));
            foreach (var (d, h) in near.GetRange(0, Math.Min(6, near.Count)))
            {
                Entity e = m.World[h];
                Console.WriteLine($"    near {h}: kind {e.Kind} at {d:F0} units, dx {(e.X - self.X) / scale:F0} dy {(e.Y - self.Y) / scale:F0}, radius {e.Radius}");
            }
        }

        private static int OwnBullet(MatchConnection m)
        {
            for (int h = 1; h < Wire.MaxHandles; h++)
                if (m.World[h].Alive && m.World[h].Kind == Wire.KindPredicted && m.World[h].OwnerHandle == Wire.SelfHandle) return h;
            return 0;
        }

        /// <summary>Lose the socket without a word; the client resumes the same tank (02 §10).</summary>
        private static void Resume()
        {
            var (_, grant) = NewPlayerWithGrant();
            using var m = Connect(grant);
            m.Join(grant.TicketId);
            Check(RunUntil(m, 10_000, () => m.Alive), "joined");
            long self = m.Welcome.SelfEntityId;
            string secret = m.Welcome.ResumeSecret;
            RunFor(m, 1_000);

            m.DropForTest();                              // a phone walking out of wifi
            Check(RunUntil(m, 10_000, () => m.Resumes == 1 && m.Alive), "resumed within the tank's 10 s grace");
            Console.WriteLine($"    resumed; last problem: {m.LastProblem}");
            Check(m.Welcome.SelfEntityId == self, "the same tank");
            Check(m.Welcome.ResumeSecret != secret, "a new secret: the old one is spent");
            long frames0 = m.SnapshotsApplied;
            RunFor(m, 1_000);
            Check(m.SnapshotsApplied - frames0 >= 10, "frames flow again on the fresh view");
            m.Leave();
        }

        /// <summary>
        /// A cold resume (02 §10, D-51): the app is killed mid-match and restarted; a new connection,
        /// knowing only the secret and the arena's address the app kept, comes back to the same stay,
        /// and the spent secret is refused after.
        /// </summary>
        private static void ColdResume()
        {
            var (_, grant) = NewPlayerWithGrant();
            var m = Connect(grant);
            m.Join(grant.TicketId);
            Check(RunUntil(m, 10_000, () => m.Alive), "joined");
            long self = m.Welcome.SelfEntityId;
            string kept = m.Welcome.ResumeSecret;          // what the app wrote down going to the background
            RunFor(m, 1_000);
            m.Dispose();                                   // killed: the socket closed, and no Leave

            using var again = Connect(grant);              // the restarted app's, from what it kept
            again.Resume(kept);
            Check(RunUntil(again, 10_000, () => again.Alive), "a new connection resumed the stay with the kept secret");
            Check(again.Welcome.SelfEntityId == self, "the same tank, within its 10 s grace");
            Check(again.Welcome.ResumeSecret != kept, "and a new secret to keep");

            using var stale = Connect(grant);
            stale.Resume(kept);
            Check(RunUntil(stale, 10_000, () => stale.State == MatchState.Ended) && stale.End == MatchEnd.BadTicket,
                $"the spent secret is refused: back to the lobby ({stale.End})");
            RunFor(again, 500);
            Check(again.Alive, "and the resumed stay is untouched by it");
            again.Leave();
        }

        /// <summary>A ticket nobody issued is Kick(1): back to the lobby, not a retry.</summary>
        private static void BadTicket()
        {
            var (_, grant) = NewPlayerWithGrant();
            using var m = Connect(grant);
            m.Join("NoSuchTicketAnywhere00");
            Check(RunUntil(m, 10_000, () => m.State == MatchState.Ended), "the arena answered");
            Check(m.End == MatchEnd.BadTicket, $"ended as BadTicket (it ended as {m.End})");
        }

        /// <summary>Backgrounded, the frames stop; back in the foreground, they start again (02 §10).</summary>
        private static void Lifecycle()
        {
            var (_, grant) = NewPlayerWithGrant();
            using var m = Connect(grant);
            m.Join(grant.TicketId);
            Check(RunUntil(m, 10_000, () => m.Alive), "joined");
            m.SetBackgrounded(true);
            RunFor(m, 500);                               // what was in flight arrives
            long frames0 = m.SnapshotsApplied;
            RunFor(m, 2_000);
            Check(m.SnapshotsApplied - frames0 <= 1, $"no frames in the background ({m.SnapshotsApplied - frames0})");
            m.SetBackgrounded(false);
            long frames1 = m.SnapshotsApplied;
            RunFor(m, 1_500);
            Check(m.SnapshotsApplied - frames1 >= 10, "frames again in the foreground");
            Check(State(m) == MatchState.InMatch, "the stay went on throughout");
            m.Leave();
        }

        /// <summary>An arena whose certificate this client does not trust is not joined, and not retried.</summary>
        private static void Untrusted()
        {
            var (_, grant) = NewPlayerWithGrant();
            Check(grant.Tls, "the grant says TLS");
            using var m = new MatchConnection(new MatchSettings
            {
                Host = grant.Host, Port = grant.Port, Tls = true, CertificateCheck = TrustOnly(_wrongTrust),
            }, new SystemClock());
            m.Join(grant.TicketId);
            Check(RunUntil(m, 10_000, () => m.State == MatchState.Ended), "the attempt ended");
            Check(m.End == MatchEnd.Unreachable, $"refused as unreachable (it ended as {m.End}): {m.LastProblem}");
        }

        /// <summary>
        /// The whole path, as a player takes it: log in over the API, ask the lobby for a match,
        /// play past the reward's minimum, leave, and see the worker's reward in the inventory.
        /// </summary>
        private static void LobbyToResult()
        {
            var (session, _) = NewSession();
            using var lobby = new LobbyClient(_lobby, new SystemClock());
            lobby.Connect(session.Token);
            Check(RunLobbyUntil(lobby, 10_000, () => lobby.State == LobbyState.Ready), "the lobby authenticated the session");
            Check(lobby.PlayerId == session.PlayerId, "as the player who logged in");
            lobby.RequestMatch();
            Check(RunLobbyUntil(lobby, 10_000, () => lobby.Grant != null || lobby.GrantRefusal != null), "the lobby answered the match request");
            MatchGrant g = lobby.Grant;
            Check(g != null, $"with a grant ({lobby.GrantRefusal})");
            if (g == null) return;

            using var m = Connect(new Grant { Host = g.ArenaHost, Port = g.ArenaPort, TicketId = g.TicketId, Tls = g.Tls });
            m.Join(g.TicketId);
            Check(RunUntil(m, 10_000, () => m.Alive), "joined the arena the lobby named");
            long coins0 = Api.Inventory(session.Token).Result.Value.Coins;
            m.SetInput(Wire.MoveDown, Wire.QuantiseAim(1.57f), true, Wire.FlagAutofire);
            RunFor(m, 6_500);                            // past the reward rules' 5 s minimum
            m.Leave();

            // Leaving publishes the stay's result; the worker applies it to MySQL.
            long coins = coins0;
            var clock = System.Diagnostics.Stopwatch.StartNew();
            while (clock.ElapsedMilliseconds < 20_000 && coins == coins0)
            {
                Thread.Sleep(250);
                lobby.Poll();
                coins = Api.Inventory(session.Token).Result.Value.Coins;
            }
            Console.WriteLine($"    coins {coins0} -> {coins} after {clock.ElapsedMilliseconds} ms");
            Check(coins >= coins0 + 10, "the taking-part reward arrived: arena, queue, worker, MySQL, API");
            Check(lobby.State == LobbyState.Ready, "the lobby stayed connected throughout");
            Check(Api.Shop().Result.Ok, "the shop answers");
        }

        /// <summary>Two players in the lobby, both queued for a duel and both matched by the push (04 §4).</summary>
        private static (Session, Session, LobbyClient, LobbyClient) MatchedPair()
        {
            var (a, _) = NewSession();
            var (b, _) = NewSession();
            var la = new LobbyClient(_lobby, new SystemClock());
            var lb = new LobbyClient(_lobby, new SystemClock());
            la.Connect(a.Token);
            lb.Connect(b.Token);
            Check(RunLobbiesUntil(10_000, () => la.State == LobbyState.Ready && lb.State == LobbyState.Ready, la, lb), "both players are in the lobby");
            la.JoinQueue("duel");
            lb.JoinQueue("duel");
            Check(RunLobbiesUntil(15_000, () => la.Ready != null && lb.Ready != null, la, lb),
                $"both were asked by evt.match.ready ({la.QueueRefusal}{lb.QueueRefusal})");
            if (la.Ready == null || lb.Ready == null) return (a, b, la, lb);
            Check(la.Ready.MatchUid == lb.Ready.MatchUid && la.Ready.Seconds == 10 && la.QueueState == "confirming",
                "about one match, with ten seconds to answer");
            la.AcceptMatch();
            lb.AcceptMatch();
            Check(RunLobbiesUntil(15_000, () => la.Grant != null && lb.Grant != null, la, lb),
                $"both accepted, and both were matched by evt.match.found ({la.AnswerRefusal}{lb.AnswerRefusal})");
            if (la.Grant != null && lb.Grant != null)
            {
                Check(la.Grant.ArenaPort == lb.Grant.ArenaPort && la.Grant.Mode == "duel", "to one arena, for a duel");
                QueueStatus q = Api.Queue(a.Token).Result.Value;
                Check(q.State == "matched" && q.Grant.TicketId == la.Grant.TicketId, "and the match can be fetched too, as after a missed push");
            }
            return (a, b, la, lb);
        }

        /// <summary>A duel played out: a room made for it, the clock ends it, a draw, Kick(6), the result in MySQL.</summary>
        private static void Duel()
        {
            var (a, b, la, lb) = MatchedPair();
            using (la) using (lb)
            {
                if (la.Grant == null || lb.Grant == null) return;
                JsonValue paidA = null, paidB = null;
                la.OnPush += (t, d) => { if (t == "evt.rewards") paidA = d; };
                lb.OnPush += (t, d) => { if (t == "evt.rewards") paidB = d; };
                long coinsA = Api.Inventory(a.Token).Result.Value.Coins, coinsB = Api.Inventory(b.Token).Result.Value.Coins;
                using var ma = Connect(new Grant { Host = la.Grant.ArenaHost, Port = la.Grant.ArenaPort, TicketId = la.Grant.TicketId, Tls = la.Grant.Tls });
                using var mb = Connect(new Grant { Host = lb.Grant.ArenaHost, Port = lb.Grant.ArenaPort, TicketId = lb.Grant.TicketId, Tls = lb.Grant.Tls });
                ma.Join(la.Grant.TicketId);
                mb.Join(lb.Grant.TicketId);
                Check(RunMatchesUntil(10_000, () => ma.Alive && mb.Alive, la, lb, ma, mb), "both joined the room made for the match");
                Check(ma.Welcome.Mode == 1, "and the Welcome says duel");
                // Nobody fires: the three minutes run out, and equal kills are a draw.
                var clock = System.Diagnostics.Stopwatch.StartNew();
                Check(RunMatchesUntil(200_000, () => ma.State == MatchState.Ended && mb.State == MatchState.Ended, la, lb, ma, mb),
                    "the match ended");
                Console.WriteLine($"    ended after {clock.ElapsedMilliseconds / 1000} s: {ma.End}, {mb.End}");
                Check(ma.End == MatchEnd.MatchOver && mb.End == MatchEnd.MatchOver, "both were told the match is over: Kick(6)");
                Check(WaitForCoins(a, coinsA + 50) && WaitForCoins(b, coinsB + 50), "the result reached MySQL: a draw, both placed first");
                Check(RunLobbiesUntil(10_000, () => paidA != null && paidB != null, la, lb)
                      && paidA["mode"].AsString == "duel" && paidA["coins"].AsLong == 50 && paidB["coins"].AsLong == 50
                      && paidA["matchUid"].AsString == paidB["matchUid"].AsString,
                    $"and each was told what it paid, by evt.rewards ({paidA}; {paidB})");
                // The duel board (04 §7, Q-40): read from MySQL, listing a player after ten rated duels.
                ApiResult<List<RankRow>> board = Api.Leaderboard("duel", 10).Result;
                Check(board.Ok, $"the duel's rating board answers ({board.Status})");
                ApiResult<List<RankRow>> mine = Api.AroundMe("duel", a.Token).Result;
                Check(!mine.Ok && mine.Status == 404 && mine.Code == "not_ranked", $"and one rated duel is not yet ten ({mine})");
                Check(la.State == LobbyState.Ready && lb.State == LobbyState.Ready, "both lobbies stayed connected throughout");
            }
        }

        /// <summary>
        /// Matched, but only one side goes: after the join window it is a walkover, unrated, and
        /// unpaid, since its winner played no time (04 §4). The result is in the database's view the
        /// drill prints; a client has no call that shows it.
        /// </summary>
        private static void Walkover()
        {
            var (_, _, la, lb) = MatchedPair();
            using (la) using (lb)
            {
                if (la.Grant == null) return;
                using var ma = Connect(new Grant { Host = la.Grant.ArenaHost, Port = la.Grant.ArenaPort, TicketId = la.Grant.TicketId, Tls = la.Grant.Tls });
                ma.Join(la.Grant.TicketId);
                var clock = System.Diagnostics.Stopwatch.StartNew();
                Check(RunMatchesUntil(45_000, () => ma.State == MatchState.Ended, la, lb, ma), "the match ended without the other side");
                Console.WriteLine($"    ended after {clock.ElapsedMilliseconds / 1000} s: {ma.End}");
                Check(ma.End == MatchEnd.MatchOver, "told the match is over: Kick(6)");
                Check(clock.ElapsedMilliseconds >= 25_000, "after the join window, not before");
            }
        }

        /// <summary>
        /// Six players: three make a party by invitation, and its leader queues it for team-vs-team;
        /// three more queue alone. One match of three against three, the party one side of it, in a
        /// room the arena makes for it; played to the clock, and the result in MySQL (04 §4).
        /// </summary>
        /// <summary>
        /// Domination (01 §8.7): one of the six drives to the middle line and sees a neutral
        /// dominator, a tank of the Dominator class; nobody takes one, so the five minutes are a draw.
        /// </summary>
        private static void DominationPlay() => Alone("domination", 6, 6, true, matches =>
        {
            MatchConnection scout = matches[0];
            float toward = scout.World[Wire.SelfHandle].X < scout.Welcome.MapWidth / 2 * Wire.PosScale ? 0f : (float)Math.PI;
            scout.SetInput(toward == 0f ? Wire.MoveRight : Wire.MoveLeft, Wire.QuantiseAim(toward), false, 0);
            bool Seen()
            {
                for (int h = 0; h < Wire.MaxHandles; h++)
                {
                    Entity e = scout.World[h];
                    if (e != null && e.Alive && e.Kind == Wire.KindTank && e.ClassId == 51 && e.Team == 0) return true;
                }
                return false;
            }
            return (Seen, "a neutral dominator came into view, of the Dominator's class",
                () => scout.SetInput(0, Wire.QuantiseAim(toward), false, 0));
        });

        /// <summary>Tag (01 §8.8): nobody fires, so nobody goes over, and the five minutes are a draw.</summary>
        private static void TagPlay() => Alone("tag", 7, 6, true, null);

        /// <summary>
        /// Maze (01 §8.9): the eight are sent one maze's seed and each makes the walls from it; one
        /// fires at the nearest wall at least 100 units off, and its bullets end at a wall the client
        /// made (D-48). Nobody else fires, so the four minutes run out with nobody ahead.
        /// </summary>
        private static void MazePlay() => Alone("maze", 8, 8, false, matches =>
        {
            long seed = matches[0].Welcome.MazeSeed;
            Check(seed != 0 && matches.TrueForAll(m => m.Welcome.MazeSeed == seed), $"one maze's seed for all eight ({seed})");
            Check(matches.TrueForAll(m => m.World.Walls.Count == 65), "and each made its 65 walls from it");
            MatchConnection scout = matches[0];
            Entity self = scout.World[Wire.SelfHandle];
            float sx = self.X / Wire.PosScale, sy = self.Y / Wire.PosScale;
            float nearX = 0, nearY = 0, near = float.MaxValue;
            foreach (Wall w in scout.World.Walls)
            {
                float px = Math.Clamp(sx, w.MinX, w.MaxX), py = Math.Clamp(sy, w.MinY, w.MaxY);
                float d = (float)Math.Sqrt((px - sx) * (px - sx) + (py - sy) * (py - sy));
                // A bullet is born 39 units out at level 1: one fired at a nearer wall ends in a tick,
                // before any snapshot carries it; so does one whose way there crosses another wall (T-57).
                if (d >= 100 && d < near && ClearTo(w, px, py, d)) (near, nearX, nearY) = (d, px, py);
            }
            bool ClearTo(Wall target, float px, float py, float d)
            {
                for (float t = 39; t < d - 10; t += 5)
                {
                    float x = sx + (px - sx) * t / d, y = sy + (py - sy) * t / d;
                    foreach (Wall other in scout.World.Walls)
                        if (!other.Equals(target) && other.Hits(x, y, 10)) return false;
                }
                return true;
            }
            int aim = Wire.QuantiseAim((float)Math.Atan2(nearY - sy, nearX - sx));
            scout.SetInput(0, aim, true, 0);
            int flying = 0, removed = 0;
            float closest = float.MaxValue;
            bool atAWall = false;
            // Every frame's removals, not the last polled frame's only: a poll applies every frame waiting (T-57).
            scout.OnFrame = () =>
            {
                // Nobody else fires: a bullet removed is the scout's, by its own rule or the server's.
                foreach (Removal r in scout.World.Removals)
                    if (r.Kind == Wire.KindPredicted)
                    {
                        removed++;
                        float rx = r.X / Wire.PosScale, ry = r.Y / Wire.PosScale;
                        foreach (Wall w in scout.World.Walls)
                        {
                            float px = Math.Clamp(rx, w.MinX, w.MaxX), py = Math.Clamp(ry, w.MinY, w.MaxY);
                            closest = Math.Min(closest, (float)Math.Sqrt((px - rx) * (px - rx) + (py - ry) * (py - ry)));
                            if (w.Hits(rx, ry, 20)) atAWall = true;
                        }
                    }
            };
            bool EndedAtAWall()
            {
                for (int h = 0; h < Wire.MaxHandles; h++)
                    if (scout.World[h].Alive && scout.World[h].Kind == Wire.KindPredicted) flying++;
                return atAWall;
            }
            return (EndedAtAWall, $"its bullets, fired at a wall {near:F0} away, ended at a wall the client made",
                () =>
                {
                    scout.SetInput(0, aim, false, 0);
                    scout.OnFrame = null;
                    Console.WriteLine($"    bullets seen in flight, summed over polls: {flying}; removed {removed}, the nearest to a wall {closest:F0}");
                });
        });

        /// <summary>
        /// The sandbox (01 §8.10): two in a party, the leader opens one for both, and each is sent
        /// there and joins; the leader rebuilds their tank at level 45 and summons a Guardian, which
        /// comes hunting; both leave, the room ends a minute after it empties, and nothing is paid.
        /// </summary>
        private static void SandboxPlay()
        {
            var (a, nameA) = NewSession();
            var (b, nameB) = NewSession();
            using var la = new LobbyClient(_lobby, new SystemClock());
            using var lb = new LobbyClient(_lobby, new SystemClock());
            la.Connect(a.Token);
            lb.Connect(b.Token);
            Check(RunLobbiesUntil(10_000, () => la.State == LobbyState.Ready && lb.State == LobbyState.Ready, la, lb),
                "both are in the lobby");
            la.InviteToParty(lb.PlayerId);
            Check(RunLobbiesUntil(5_000, () => lb.Invitation != null, la, lb), "the invitation was pushed");
            if (lb.Invitation == null) return;
            lb.AcceptInvitation(lb.Invitation.PartyId);
            Check(RunLobbiesUntil(5_000, () => la.Party != null && la.Party.Members.Count == 2, la, lb), "a party of two");

            long coinsA = Api.Inventory(a.Token).Result.Value.Coins, coinsB = Api.Inventory(b.Token).Result.Value.Coins;
            ApiResult<MatchGrant> member = Api.OpenSandbox(b.Token).Result;
            Check(!member.Ok && member.Code == "in_party", $"a member does not open one ({member})");
            ApiResult<MatchGrant> opened = Api.OpenSandbox(a.Token).Result;
            // A 503 is "not now", as a client takes it: a room freed by a match just over is in the
            // directory within seconds, and the drill's arena has two (T-27).
            for (int i = 0; i < 10 && !opened.Ok && opened.Status == 503; i++)
            {
                Thread.Sleep(1_000);
                opened = Api.OpenSandbox(a.Token).Result;
            }
            Check(opened.Ok && opened.Value.Mode == "sandbox", $"the leader opened a sandbox for the party ({opened})");
            if (!opened.Ok) return;
            Check(RunLobbiesUntil(10_000, () => la.Grant != null && lb.Grant != null, la, lb),
                "both were sent there by evt.match.found");
            if (la.Grant == null || lb.Grant == null) return;
            Check(la.Grant.Mode == "sandbox" && lb.Grant.Mode == "sandbox" && la.Grant.TicketId == opened.Value.TicketId,
                "for the sandbox, the leader's push and answer one ticket");

            using var ma = Connect(new Grant { Host = la.Grant.ArenaHost, Port = la.Grant.ArenaPort, TicketId = la.Grant.TicketId, Tls = la.Grant.Tls });
            using var mb = Connect(new Grant { Host = lb.Grant.ArenaHost, Port = lb.Grant.ArenaPort, TicketId = lb.Grant.TicketId, Tls = lb.Grant.Tls });
            ma.Join(la.Grant.TicketId);
            mb.Join(lb.Grant.TicketId);
            Check(RunMatchesUntil(10_000, () => ma.Alive && mb.Alive, la, lb, ma, mb), "both joined, at once");
            Check(ma.Welcome.Mode == 9 && mb.Welcome.Mode == 9, "and the Welcome says sandbox");
            // One sandbox a player until it ends (D-54): leaving the queue's record frees no second.
            Api.LeaveQueue(a.Token).Wait();
            Api.LeaveQueue(b.Token).Wait();
            ApiResult<MatchGrant> another = Api.OpenSandbox(a.Token).Result;
            Check(!another.Ok && another.Code == "in_sandbox", $"and no second while in it, the queue's record left or not ({another})");

            long level = 0;
            var toldA = new List<string>();
            var toldB = new List<string>();
            ma.OnEvent = e =>
            {
                if (e.Type == Wire.EvtStats) level = Math.Max(level, e.Level);
                if (e.Type == Wire.EvtKill) toldA.Add(e.Killer + ">" + e.Victim);
            };
            mb.OnEvent = e => { if (e.Type == Wire.EvtKill) toldB.Add(e.Killer + ">" + e.Victim); };
            ma.SetLevel(45);
            Check(RunMatchesUntil(10_000, () => level == 45, la, lb, ma, mb), $"the leader's tank rebuilt at level 45 ({level})");

            // The kill feed (01 §9, Q-34): the leader, its points in damage, penetration and reload,
            // goes after the member, who stands still; in a match both are told who killed whom.
            for (int i = 0; i < 7; i++)
            {
                ma.UpgradeStat(5);
                ma.UpgradeStat(4);
                ma.UpgradeStat(6);
            }
            string killing = nameA + ">" + nameB;
            string seenAs = null;
            bool Hunted()
            {
                Entity self = ma.World[Wire.SelfHandle];
                // Out of view, the leader drives to where the member's own client has it: the drill's knowledge,
                // not a player's. From the middle a view of 1 600 does not reach a member spawned near an
                // edge of the sandbox's 2 000, and the leader waited there for a tank it could never see (T-42).
                Entity member = mb.World[Wire.SelfHandle];
                float cx = (member.Alive ? member.X : ma.Welcome.MapWidth / 2f * Wire.PosScale) - self.X;
                float cy = (member.Alive ? member.Y : ma.Welcome.MapHeight / 2f * Wire.PosScale) - self.Y;
                ma.SetInput((Math.Abs(cx) > 50 * Wire.PosScale ? (cx > 0 ? Wire.MoveRight : Wire.MoveLeft) : 0)
                            | (Math.Abs(cy) > 50 * Wire.PosScale ? (cy > 0 ? Wire.MoveDown : Wire.MoveUp) : 0), 0, false, 0);
                for (int h = 0; h < Wire.MaxHandles; h++)
                {
                    // The one other tank: no bots here, and no Guardian yet.
                    Entity e = ma.World[h];
                    if (h == Wire.SelfHandle || !e.Alive || e.Kind != Wire.KindTank) continue;
                    seenAs = e.Name;
                    float dx = e.X - self.X, dy = e.Y - self.Y;
                    int move = Math.Sqrt(dx * dx + dy * dy) > 300 * Wire.PosScale
                        ? (dx > 0 ? Wire.MoveRight : Wire.MoveLeft) | (dy > 0 ? Wire.MoveDown : Wire.MoveUp) : 0;
                    ma.SetInput(move, Wire.QuantiseAim((float)Math.Atan2(dy, dx)), true, Wire.FlagAutofire);
                }
                return toldA.Contains(killing) && toldB.Contains(killing);
            }
            bool hunted = RunMatchesUntil(40_000, Hunted, la, lb, ma, mb);
            if (!hunted)
                Console.WriteLine($"    the leader at ({ma.World[Wire.SelfHandle].X / Wire.PosScale:F0}, {ma.World[Wire.SelfHandle].Y / Wire.PosScale:F0}),"
                    + $" the member at ({mb.World[Wire.SelfHandle].X / Wire.PosScale:F0}, {mb.World[Wire.SelfHandle].Y / Wire.PosScale:F0}),"
                    + $" the map {ma.Welcome.MapWidth} square");
            Check(hunted,
                $"a kill in the match told to both: {killing} (the leader heard {string.Join(", ", toldA)}; the member {string.Join(", ", toldB)})");
            Check(seenAs == nameB, $"and the member's tank was seen by its name ({seenAs}; D-52)");
            ma.SetInput(0, 0, false, 0);
            if (!mb.Alive) mb.Respawn();
            ma.SummonGuardian();
            bool Guardian(MatchConnection m)
            {
                for (int h = 0; h < Wire.MaxHandles; h++)
                {
                    Entity e = m.World[h];
                    if (e.Alive && e.Kind == Wire.KindTank && (e.ClassId == 49 || e.ClassId == 50)) return true;
                }
                return false;
            }
            Check(RunMatchesUntil(40_000, () => Guardian(ma) || Guardian(mb), la, lb, ma, mb), "a Guardian summoned came into view, hunting");

            ma.Leave();
            mb.Leave();
            Check(RunMatchesUntil(10_000, () => ma.State == MatchState.Ended && mb.State == MatchState.Ended, la, lb, ma, mb),
                "both left");
            RunLobbiesUntil(75_000, () => false, la, lb);           // the room ends a minute after it empties
            Check(Api.Inventory(a.Token).Result.Value.Coins == coinsA && Api.Inventory(b.Token).Result.Value.Coins == coinsB,
                "nothing paid: a sandbox publishes no result");
        }

        /// <summary>
        /// <paramref name="players"/> queue alone for a mode, in two teams or each for themselves, are
        /// asked, accept, are matched and join; <paramref name="during"/>, when given, starts
        /// something to see and says what it saw; then the clock runs out and each is paid for taking
        /// part.
        /// </summary>
        private static void Alone(string mode, int id, int players, bool twoTeams,
            Func<List<MatchConnection>, (Func<bool> seen, string what, Action after)> during)
        {
            var sessions = new Session[players];
            var lobbies = new LobbyClient[players];
            for (int i = 0; i < players; i++)
            {
                sessions[i] = NewSession().session;
                lobbies[i] = new LobbyClient(_lobby, new SystemClock());
                lobbies[i].Connect(sessions[i].Token);
            }
            var matches = new List<MatchConnection>();
            try
            {
                Check(RunLobbiesUntil(10_000, () => Array.TrueForAll(lobbies, l => l.State == LobbyState.Ready), lobbies),
                    $"{players} players are in the lobby");
                foreach (LobbyClient l in lobbies) l.JoinQueue(mode);
                Check(RunLobbiesUntil(20_000, () => Array.TrueForAll(lobbies, l => l.Ready != null), lobbies),
                    $"all {players} were asked by evt.match.ready");
                foreach (LobbyClient l in lobbies) if (l.Ready != null) l.AcceptMatch();
                Check(RunLobbiesUntil(20_000, () => Array.TrueForAll(lobbies, l => l.Grant != null), lobbies),
                    $"all {players} accepted, and were matched");
                if (!Array.TrueForAll(lobbies, l => l.Grant != null)) return;
                Check(Array.TrueForAll(lobbies, l => l.Grant.Mode == mode), $"for {mode}");
                foreach (LobbyClient l in lobbies)
                {
                    var m = Connect(new Grant { Host = l.Grant.ArenaHost, Port = l.Grant.ArenaPort, TicketId = l.Grant.TicketId, Tls = l.Grant.Tls });
                    matches.Add(m);
                    m.Join(l.Grant.TicketId);
                }
                Check(RunAllUntil(15_000, () => matches.TrueForAll(m => m.Alive), lobbies, matches), $"all {players} joined");
                if (!matches.TrueForAll(m => m.Alive)) return;
                Check(matches.TrueForAll(m => m.Welcome.Mode == id), $"and the Welcome says {mode}");
                if (twoTeams)
                {
                    int[] team = matches.ConvertAll(m => m.World[Wire.SelfHandle].Team).ToArray();
                    Check(Array.FindAll(team, t => t == 1).Length == players / 2 && Array.FindAll(team, t => t == 2).Length == players / 2,
                        $"{players / 2} a side ({string.Join(" ", team)})");
                }
                if (during != null)
                {
                    var (seen, what, after) = during(matches);
                    Check(RunAllUntil(40_000, seen, lobbies, matches), what);
                    after();
                }

                long[] coins = Array.ConvertAll(sessions, s => Api.Inventory(s.Token).Result.Value.Coins);
                var clock = System.Diagnostics.Stopwatch.StartNew();
                Check(RunAllUntil(330_000, () => matches.TrueForAll(m => m.State == MatchState.Ended), lobbies, matches),
                    "the match ended");
                Console.WriteLine($"    ended after {clock.ElapsedMilliseconds / 1000} s");
                Check(matches.TrueForAll(m => m.End == MatchEnd.MatchOver), $"all {players} were told the match is over: Kick(6)");
                bool paid = true;
                for (int i = 0; i < players; i++) paid &= WaitForCoins(sessions[i], coins[i] + 1);
                Check(paid, $"the result reached MySQL: each of the {players} paid for taking part");
            }
            finally
            {
                foreach (var m in matches) m.Dispose();
                foreach (var l in lobbies) l.Dispose();
            }
        }

        private static void PartyMatch()
        {
            var sessions = new Session[6];
            var lobbies = new LobbyClient[6];
            for (int i = 0; i < 6; i++)
            {
                sessions[i] = NewSession().session;
                lobbies[i] = new LobbyClient(_lobby, new SystemClock());
                lobbies[i].Connect(sessions[i].Token);
            }
            var matches = new List<MatchConnection>();
            try
            {
                LobbyClient lead = lobbies[0], b = lobbies[1], c = lobbies[2];
                Check(RunLobbiesUntil(10_000, () => Array.TrueForAll(lobbies, l => l.State == LobbyState.Ready), lobbies),
                    "six players are in the lobby");

                foreach (LobbyClient invited in new[] { b, c })
                {
                    lead.InviteToParty(invited.PlayerId);
                    Check(RunLobbiesUntil(5_000, () => invited.Invitation != null, lobbies), "the invitation was pushed");
                    if (invited.Invitation == null) return;
                    Check(invited.Invitation.From == lead.PlayerId, "from the leader");
                    invited.AcceptInvitation(invited.Invitation.PartyId);
                }
                bool three = RunLobbiesUntil(5_000, () => Array.TrueForAll(new[] { lead, b, c },
                    l => l.Party != null && l.Party.Members.Count == 3), lobbies);
                Check(three, "a party of three, each told");
                if (!three)
                    Console.WriteLine("    seen: " + string.Join(", ", Array.ConvertAll(new[] { lead, b, c },
                        l => l.Party == null ? "none" : l.Party.Members.Count + " members")));
                Check(lead.Party != null && lead.Party.Leader == lead.PlayerId
                      && b.Party?.PartyId == lead.Party.PartyId && c.Party?.PartyId == lead.Party.PartyId,
                    "one party, led by the one who invited");

                b.JoinQueue("tvt");
                Check(RunLobbiesUntil(5_000, () => b.QueueRefusal != null, lobbies) && b.QueueRefusal == "in_party",
                    $"a member does not queue ({b.QueueRefusal})");
                lead.JoinQueue("duel");
                Check(RunLobbiesUntil(5_000, () => lead.QueueRefusal != null, lobbies) && lead.QueueRefusal == "party_too_big",
                    $"nor a party of three for a duel ({lead.QueueRefusal})");

                lead.JoinQueue("tvt");
                Check(RunLobbiesUntil(5_000, () => b.QueueState == "queued" && c.QueueState == "queued", lobbies),
                    "the leader queued the party: both members were told");
                b.LeaveQueue();
                Check(RunLobbiesUntil(5_000, () => lead.QueueState == "none" && c.QueueState == "none", lobbies),
                    "a member left the queue: the party is out, and the others were told");
                lead.JoinQueue("tvt");
                Check(RunLobbiesUntil(5_000, () => b.QueueState == "queued" && c.QueueState == "queued", lobbies), "queued again");
                for (int i = 3; i < 6; i++) lobbies[i].JoinQueue("tvt");

                Check(RunLobbiesUntil(20_000, () => Array.TrueForAll(lobbies, l => l.Ready != null), lobbies),
                    "all six were asked by evt.match.ready");
                // A party that changes while asked is out of that match, nobody locked out; the rest go back (T-32).
                string firstAsk = lead.Ready?.MatchUid;
                PartyInvitation earlier = c.Invitation;
                c.LeaveParty();
                Check(RunLobbiesUntil(5_000, () => lead.QueueState == "none" && b.QueueState == "none" && c.QueueState == "none"
                        && lobbies[3].QueueState == "queued" && lobbies[4].QueueState == "queued" && lobbies[5].QueueState == "queued", lobbies),
                    "a member left the party while asked: the party is out of that match, the three alone queued again");
                lead.InviteToParty(c.PlayerId);
                Check(RunLobbiesUntil(5_000, () => c.Invitation != null && !ReferenceEquals(c.Invitation, earlier), lobbies), "invited back");
                if (c.Invitation == null || ReferenceEquals(c.Invitation, earlier)) return;
                c.AcceptInvitation(c.Invitation.PartyId);
                Check(RunLobbiesUntil(5_000, () => lead.Party != null && lead.Party.Members.Count == 3, lobbies), "a party of three again");
                lead.JoinQueue("tvt");
                Check(RunLobbiesUntil(20_000, () => Array.TrueForAll(lobbies, l => l.Ready != null && l.Ready.MatchUid != firstAsk), lobbies),
                    "queued again, not locked out: all six were asked about a new match");
                foreach (LobbyClient l in lobbies) if (l.Ready != null) l.AcceptMatch();
                Check(RunLobbiesUntil(20_000, () => Array.TrueForAll(lobbies, l => l.Grant != null), lobbies),
                    "all six accepted, and all six were matched by evt.match.found");
                if (!Array.TrueForAll(lobbies, l => l.Grant != null)) return;
                Check(Array.TrueForAll(lobbies, l => l.Grant.Mode == "tvt" && l.Grant.ArenaPort == lead.Grant.ArenaPort),
                    "to one arena, for team-vs-team");

                foreach (LobbyClient l in lobbies)
                {
                    var m = Connect(new Grant { Host = l.Grant.ArenaHost, Port = l.Grant.ArenaPort, TicketId = l.Grant.TicketId, Tls = l.Grant.Tls });
                    matches.Add(m);
                    m.Join(l.Grant.TicketId);
                }
                Check(RunAllUntil(15_000, () => matches.TrueForAll(m => m.Alive), lobbies, matches), "all six joined the room made for the match");
                if (!matches.TrueForAll(m => m.Alive)) return;
                Check(matches.TrueForAll(m => m.Welcome.Mode == 2), "and the Welcome says team-vs-team");
                int[] team = matches.ConvertAll(m => m.World[Wire.SelfHandle].Team).ToArray();
                Console.WriteLine($"    teams: {string.Join(" ", team)}");
                Check(team[0] == team[1] && team[1] == team[2], "the party is one team");
                Check(team[3] == team[4] && team[4] == team[5], "the three alone the other");
                Check(team[0] != team[3] && team[0] >= 1 && team[0] <= 2 && team[3] >= 1 && team[3] <= 2, "teams 1 and 2");

                long[] coins = Array.ConvertAll(sessions, s => Api.Inventory(s.Token).Result.Value.Coins);
                // Nobody fires: the five minutes run out, and equal kills are a draw.
                var clock = System.Diagnostics.Stopwatch.StartNew();
                Check(RunAllUntil(330_000, () => matches.TrueForAll(m => m.State == MatchState.Ended), lobbies, matches),
                    "the match ended");
                Console.WriteLine($"    ended after {clock.ElapsedMilliseconds / 1000} s");
                Check(matches.TrueForAll(m => m.End == MatchEnd.MatchOver), "all six were told the match is over: Kick(6)");
                bool paid = true;
                for (int i = 0; i < 6; i++) paid &= WaitForCoins(sessions[i], coins[i] + 10);
                Check(paid, "the result reached MySQL: each of the six paid for taking part");
                Check(Array.TrueForAll(lobbies, l => l.State == LobbyState.Ready), "the six lobbies stayed connected throughout");
                PartyInfo after = Api.Party(sessions[1].Token).Result.Value;
                Check(after != null && after.PartyId == lead.Party.PartyId && after.Members.Count == 3,
                    "the party outlives its match, as GET /v1/party says");
            }
            finally
            {
                foreach (var m in matches) m.Dispose();
                foreach (var l in lobbies) l.Dispose();
            }
        }

        /// <summary>
        /// A guest (04 §1, Q-22): made with nothing asked, in again by its key, playing and paid;
        /// upgraded to a full account, it logs in by name as the same player with what it earned,
        /// and its key no longer works.
        /// </summary>
        private static void GuestPlay()
        {
            ApiResult<Guest> made = Patiently(() => Api.CreateGuest());
            Check(made.Ok && made.Value.DisplayName.StartsWith("Guest"), $"a guest made, {made.Value?.DisplayName}");
            if (!made.Ok) return;
            ApiResult<Session> back = Patiently(() => Api.LoginGuest(made.Value.GuestKey));
            Check(back.Ok && back.Value.PlayerId == made.Value.PlayerId, "in again by its key, as after a restart");
            long earned = PaidStay(back.Value);
            Check(earned > 0, $"played, and paid {earned} coins");
            string name = "gu" + Guid.NewGuid().ToString("N").Substring(0, 10);
            Check(Patiently(() => Api.UpgradeAccount(back.Value.Token, name, "hunter2-hunter2", null)).Ok, "upgraded to a full account");
            ApiResult<Session> byName = Patiently(() => Api.Login(name, "hunter2-hunter2"));
            Check(byName.Ok && byName.Value.PlayerId == made.Value.PlayerId, "in by name, the same player");
            Check(Api.Inventory(byName.Value.Token).Result.Value.Coins == earned, "with what it earned");
            ApiResult<Session> refused = Patiently(() => Api.LoginGuest(made.Value.GuestKey));
            Check(!refused.Ok && refused.Status == 401, "and the key works no more");
        }

        /// <summary>
        /// The social layer (04 §9): ada asks bob, who is told by a push and finds it in his inbox,
        /// and asks back: friends, ada told. bob shows online, cyd, not in the lobby, offline. cyd
        /// blocks ada, which ends their friendship; ada's next request is answered and never reaches
        /// cyd. bob reads his inbox.
        /// </summary>
        private static void Social()
        {
            var (ada, _) = NewSession();
            var (bob, _) = NewSession();
            var (cyd, _) = NewSession();
            using var la = new LobbyClient(_lobby, new SystemClock());
            using var lb = new LobbyClient(_lobby, new SystemClock());
            var toAda = new List<string>();
            var toBob = new List<string>();
            la.OnPush += (t, d) => toAda.Add(t + ":" + d["playerId"].AsLong);
            lb.OnPush += (t, d) => toBob.Add(t + ":" + d["playerId"].AsLong);
            la.Connect(ada.Token);
            lb.Connect(bob.Token);
            Check(RunLobbiesUntil(10_000, () => la.State == LobbyState.Ready && lb.State == LobbyState.Ready, la, lb),
                "ada and bob are in the lobby; cyd is not");

            Check(Api.AskFriend(ada.Token, bob.PlayerId).Result.Value == "asked", "ada asked bob");
            Check(RunLobbiesUntil(5_000, () => toBob.Contains("evt.friend.request:" + ada.PlayerId) && toBob.Exists(p => p.StartsWith("evt.inbox")), la, lb),
                $"bob was told, and to look in his inbox ({string.Join(" ", toBob)})");
            List<InboxItem> items = Api.Inbox(bob.Token).Result.Value;
            Check(items.Count == 1 && items[0].Kind == "friend_request" && items[0].Ref == ada.PlayerId && !items[0].Read,
                "and there it is, unread");
            Check(Api.AskFriend(bob.Token, ada.PlayerId).Result.Value == "friends", "bob asked back: friends");
            Check(RunLobbiesUntil(5_000, () => toAda.Contains("evt.friend.accepted:" + bob.PlayerId), la, lb), "ada was told");

            Api.AskFriend(ada.Token, cyd.PlayerId).Wait();
            Check(Api.AskFriend(cyd.Token, ada.PlayerId).Result.Value == "friends", "ada and cyd are friends too");
            FriendsInfo mine = Api.Friends(ada.Token).Result.Value;
            Check(mine.Friends.Exists(f => f.PlayerId == bob.PlayerId && f.Online)
                  && mine.Friends.Exists(f => f.PlayerId == cyd.PlayerId && !f.Online), "bob online, cyd offline");

            Check(Api.Block(cyd.Token, ada.PlayerId).Result.Ok, "cyd blocked ada");
            Check(!Api.Friends(ada.Token).Result.Value.Friends.Exists(f => f.PlayerId == cyd.PlayerId), "which ended their friendship");
            Check(Api.AskFriend(ada.Token, cyd.PlayerId).Result.Value == "asked", "ada's next request is answered as any");
            Check(Api.Friends(cyd.Token).Result.Value.Requests.Count == 0, "and never reaches cyd");
            // Nor does anything ada can read differ from a request to anyone else (S-14, D-56).
            Check(Api.Friends(ada.Token).Result.Value.Asked.Exists(r => r.PlayerId == cyd.PlayerId), "ada sees it among hers");
            ApiResult<string> again = Api.AskFriend(ada.Token, cyd.PlayerId).Result;
            Check(again.Status == 409 && again.Code == "already_asked", $"and asking again is already_asked ({again.Code})");
            // Whether cyd is in the lobby is a friend's to know: ada, no longer one, is answered as sent (S-16).
            la.InviteToParty(cyd.PlayerId);
            Check(RunLobbiesUntil(5_000, () => la.Party != null, la, lb) && la.PartyRefusal == null,
                $"a party invitation to cyd, not in the lobby, is answered as sent ({la.PartyRefusal})");

            Check(Api.ReadInbox(bob.Token, items[0].Id).Result.Ok && Api.Inbox(bob.Token).Result.Value.TrueForAll(i => i.Read),
                "bob read his inbox");
        }

        /// <summary>
        /// A teams' tournament (04 §6, the second slice): an operator creates one; two teams of three
        /// register, each by its leader leading a party of the three, its roster. At the start the final
        /// is pushed to all six, a team a side; only the lower seed's roster comes, and wins by
        /// walkover. Each roster member is paid the team's prize, and the tournament is finished.
        /// </summary>
        private static void TeamCup()
        {
            string admin = Environment.GetEnvironmentVariable("BACKEND_ADMIN_URL");
            string secret = Environment.GetEnvironmentVariable("BACKEND_ADMIN_TOKEN");
            if (admin == null || secret == null)
            {
                Fail("teamcup needs BACKEND_ADMIN_URL and BACKEND_ADMIN_TOKEN");
                return;
            }
            var sessions = new Session[6];
            var lobbies = new LobbyClient[6];
            for (int i = 0; i < 6; i++)
            {
                sessions[i] = NewSession().session;
                lobbies[i] = new LobbyClient(_lobby, new SystemClock());
                lobbies[i].Connect(sessions[i].Token);
            }
            var matches = new List<MatchConnection>();
            bool Until(int ms, Func<bool> done)
            {
                var clock = System.Diagnostics.Stopwatch.StartNew();
                while (clock.ElapsedMilliseconds < ms)
                {
                    foreach (var l in lobbies) l.Poll();
                    foreach (var m in matches) m.Poll();
                    if (done()) return true;
                    Thread.Sleep(16);
                }
                return false;
            }
            try
            {
                Check(Until(10_000, () => Array.TrueForAll(lobbies, l => l.State == LobbyState.Ready)), "six players are in the lobby");
                string At(double seconds) => DateTime.UtcNow.AddSeconds(seconds).ToString("yyyy-MM-ddTHH:mm:ss.fffZ");
                using var http = new System.Net.Http.HttpClient();
                var create = new System.Net.Http.HttpRequestMessage(System.Net.Http.HttpMethod.Post, admin + "/admin/tournaments")
                {
                    Content = new System.Net.Http.StringContent("{\"reason\":\"drill\",\"name\":\"Drill team cup\",\"maxEntries\":2,"
                        + $"\"registrationEnds\":\"{At(15)}\",\"startsAt\":\"{At(17)}\",\"roundMinutes\":1,"
                        + "\"prizes\":[300,200,100],\"mode\":\"teams\"}"),
                };
                create.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", secret);
                var created = http.Send(create);
                Check((int) created.StatusCode == 200, $"the operator created a teams' tournament ({(int) created.StatusCode})");
                long id = JsonValue.Parse(created.Content.ReadAsStringAsync().Result)["id"].AsLong;

                string suffix = Guid.NewGuid().ToString("N").Substring(0, 6);
                var teams = new long[2];
                for (int t = 0; t < 2; t++)
                {
                    LobbyClient lead = lobbies[3 * t];
                    ApiResult<TeamInfo> made = Api.CreateTeam(sessions[3 * t].Token, "tc" + t + suffix).Result;
                    Check(made.Ok, $"team {t + 1} made");
                    if (!made.Ok) return;
                    teams[t] = made.Value.Id;
                    for (int k = 1; k < 3; k++)
                    {
                        LobbyClient member = lobbies[3 * t + k];
                        Api.InviteToTeam(sessions[3 * t].Token, sessions[3 * t + k].PlayerId).Wait();
                        Api.AcceptTeamInvite(sessions[3 * t + k].Token, teams[t]).Wait();
                        lead.InviteToParty(member.PlayerId);
                        Until(5_000, () => member.Invitation != null);
                        if (member.Invitation != null) member.AcceptInvitation(member.Invitation.PartyId);
                    }
                    Check(Until(5_000, () => lead.Party != null && lead.Party.Members.Count == 3), $"team {t + 1}'s party of three");
                    Check(Played("team", "rated_matches", new[] { teams[t] }), $"team {t + 1} given ten rated team matches, as a fixture (Q-43)");
                    ApiResult<TournamentInfo> entered = Api.EnterTournament(sessions[3 * t].Token, id).Result;
                    Check(entered.Ok, $"and its leader entered it ({entered})");
                }
                TournamentInfo listed = Api.Tournament(id).Result.Value;
                Check(listed.Mode == "teams" && listed.Entries.Count == 2 && listed.Entries.TrueForAll(e => e.Roster.Count == 3),
                    "two teams entered, each with its roster of three");

                Check(Until(40_000, () => Array.TrueForAll(lobbies, l => l.TournamentMatch?.TournamentId == id)),
                    "at the start, the final was pushed to all six");
                if (!Array.TrueForAll(lobbies, l => l.TournamentMatch != null)) return;
                Check(Array.TrueForAll(lobbies, l => l.TournamentMatch.Mode == "teams"), "a team match");
                TournamentInfo bracket = Api.Tournament(id).Result.Value;
                long higher = bracket.Matches[0].TeamA.Value, lower = bracket.Matches[0].TeamB.Value;
                int comes = lower == teams[0] ? 0 : 3;
                var before = Array.ConvertAll(sessions, CoinsBesideGoals);
                for (int k = 0; k < 3; k++)
                {
                    TournamentGrant g = lobbies[comes + k].TournamentMatch;
                    var m = Connect(new Grant { Host = g.ArenaHost, Port = g.ArenaPort, TicketId = g.TicketId, Tls = g.Tls });
                    matches.Add(m);
                    m.Join(g.TicketId);
                }
                Check(Until(45_000, () => matches.TrueForAll(m => m.State == MatchState.Ended))
                      && matches.TrueForAll(m => m.End == MatchEnd.MatchOver), "the lower seed's roster came alone, and won by walkover");
                Check(Until(20_000, () => Api.Tournament(id).Result.Value.State == "finished"), "the tournament is finished");
                Check(Api.Tournament(id).Result.Value.Matches[0].WinnerTeam == lower, "won by the lower seed");
                for (int i = 0; i < 6; i++)
                {
                    long prize = (i >= comes && i < comes + 3) ? 300 : 200;
                    long got = CoinsBesideGoals(sessions[i]) - before[i];
                    Check(got == prize, $"player {i + 1} paid {got} beside its goals, the team's prize {prize}");
                }
            }
            finally
            {
                foreach (var m in matches) m.Dispose();
                foreach (var l in lobbies) l.Dispose();
            }
        }

        /// <summary>
        /// A team match (04 §4, the sixth slice): two teams of three, made through the API, each a
        /// party in the lobby. The second's party is led by a member, refused not_allowed until its
        /// leader makes them a vice leader. Both queue, are asked, accept, and play one match, a team
        /// a side; nobody fires, the five minutes end it in a draw, and each team counts it.
        /// </summary>
        private static void TeamMatch()
        {
            var sessions = new Session[6];
            var lobbies = new LobbyClient[6];
            for (int i = 0; i < 6; i++)
            {
                sessions[i] = NewSession().session;
                lobbies[i] = new LobbyClient(_lobby, new SystemClock());
                lobbies[i].Connect(sessions[i].Token);
            }
            var matches = new List<MatchConnection>();
            try
            {
                Check(RunLobbiesUntil(10_000, () => Array.TrueForAll(lobbies, l => l.State == LobbyState.Ready), lobbies),
                    "six players are in the lobby");
                string suffix = Guid.NewGuid().ToString("N").Substring(0, 6);
                var teams = new long[2];
                for (int t = 0; t < 2; t++)
                {
                    Session lead = sessions[3 * t];
                    ApiResult<TeamInfo> made = Api.CreateTeam(lead.Token, "tm" + t + suffix).Result;
                    Check(made.Ok, $"team {t + 1} made ({made})");
                    if (!made.Ok) return;
                    teams[t] = made.Value.Id;
                    for (int k = 1; k < 3; k++)
                    {
                        Api.InviteToTeam(lead.Token, sessions[3 * t + k].PlayerId).Wait();
                        Check(Api.AcceptTeamInvite(sessions[3 * t + k].Token, teams[t]).Result.Ok, $"and joined by player {3 * t + k + 1}");
                    }
                }

                // A party of each team in the lobby: the first's led by its leader, the second's by a member.
                void Party(LobbyClient lead, params LobbyClient[] invited)
                {
                    foreach (LobbyClient l in invited)
                    {
                        lead.InviteToParty(l.PlayerId);
                        Check(RunLobbiesUntil(5_000, () => l.Invitation != null, lobbies), "an invitation");
                        if (l.Invitation != null) l.AcceptInvitation(l.Invitation.PartyId);
                    }
                    Check(RunLobbiesUntil(5_000, () => lead.Party != null && lead.Party.Members.Count == 3, lobbies), "a party of three");
                }
                Party(lobbies[0], lobbies[1], lobbies[2]);
                Party(lobbies[4], lobbies[3], lobbies[5]);

                lobbies[0].JoinQueue("teams");
                lobbies[4].JoinQueue("teams");
                Check(RunLobbiesUntil(5_000, () => lobbies[4].QueueRefusal != null, lobbies) && lobbies[4].QueueRefusal == "not_allowed",
                    $"a member does not queue the team ({lobbies[4].QueueRefusal})");
                Check(Api.SetTeamRole(sessions[3].Token, sessions[4].PlayerId, "vice_leader").Result.Ok, "its leader makes them a vice leader");
                lobbies[4].JoinQueue("teams");

                Check(RunLobbiesUntil(20_000, () => Array.TrueForAll(lobbies, l => l.Ready != null), lobbies),
                    "all six were asked by evt.match.ready");
                foreach (LobbyClient l in lobbies) if (l.Ready != null) l.AcceptMatch();
                Check(RunLobbiesUntil(20_000, () => Array.TrueForAll(lobbies, l => l.Grant != null), lobbies),
                    "all six accepted, and were matched by evt.match.found");
                if (!Array.TrueForAll(lobbies, l => l.Grant != null)) return;
                Check(Array.TrueForAll(lobbies, l => l.Grant.Mode == "teams"), "for a team match");

                foreach (LobbyClient l in lobbies)
                {
                    var m = Connect(new Grant { Host = l.Grant.ArenaHost, Port = l.Grant.ArenaPort, TicketId = l.Grant.TicketId, Tls = l.Grant.Tls });
                    matches.Add(m);
                    m.Join(l.Grant.TicketId);
                }
                Check(RunAllUntil(15_000, () => matches.TrueForAll(m => m.Alive), lobbies, matches), "all six joined the room made for it");
                if (!matches.TrueForAll(m => m.Alive)) return;
                Check(matches.TrueForAll(m => m.Welcome.Mode == 5), "and the Welcome says a team match");
                int[] side = matches.ConvertAll(m => m.World[Wire.SelfHandle].Team).ToArray();
                Console.WriteLine($"    sides: {string.Join(" ", side)}");
                Check(side[0] == side[1] && side[1] == side[2] && side[3] == side[4] && side[4] == side[5] && side[0] != side[3],
                    "each team one side");

                long[] coins = Array.ConvertAll(sessions, s => Api.Inventory(s.Token).Result.Value.Coins);
                var clock = System.Diagnostics.Stopwatch.StartNew();
                Check(RunAllUntil(330_000, () => matches.TrueForAll(m => m.State == MatchState.Ended), lobbies, matches),
                    "the match ended");
                Console.WriteLine($"    ended after {clock.ElapsedMilliseconds / 1000} s");
                Check(matches.TrueForAll(m => m.End == MatchEnd.MatchOver), "all six were told the match is over: Kick(6)");
                bool paid = true;
                for (int i = 0; i < 6; i++) paid &= WaitForCoins(sessions[i], coins[i] + 10);
                Check(paid, "each of the six paid for taking part");
                for (int t = 0; t < 2; t++)
                {
                    TeamInfo team = Api.MyTeam(sessions[3 * t].Token).Result.Value;
                    Check(team != null && team.Draws == 1 && team.Wins == 0 && team.Losses == 0 && team.Rating == 1200,
                        $"team {t + 1} counts a draw, its rating unmoved against an equal ({team?.Rating}, {team?.Wins}/{team?.Losses}/{team?.Draws})");
                }
            }
            finally
            {
                foreach (var m in matches) m.Dispose();
                foreach (var l in lobbies) l.Dispose();
            }
        }

        /// <summary>
        /// Ranked free-for-all (04 §4, the fourth slice): eight queue alone, are asked, accept, and
        /// are matched into one room, each for themselves; played to the clock, the result in MySQL.
        /// </summary>
        private static void RankedFreeForAll()
        {
            var sessions = new Session[8];
            var lobbies = new LobbyClient[8];
            for (int i = 0; i < 8; i++)
            {
                sessions[i] = NewSession().session;
                lobbies[i] = new LobbyClient(_lobby, new SystemClock());
                lobbies[i].Connect(sessions[i].Token);
            }
            var matches = new List<MatchConnection>();
            try
            {
                Check(RunLobbiesUntil(10_000, () => Array.TrueForAll(lobbies, l => l.State == LobbyState.Ready), lobbies),
                    "eight players are in the lobby");
                foreach (LobbyClient l in lobbies) l.JoinQueue("rffa");
                Check(RunLobbiesUntil(20_000, () => Array.TrueForAll(lobbies, l => l.Ready != null), lobbies),
                    "all eight were asked by evt.match.ready");
                if (!Array.TrueForAll(lobbies, l => l.Ready != null)) return;
                Check(Array.TrueForAll(lobbies, l => l.Ready.MatchUid == lobbies[0].Ready.MatchUid && l.Ready.Mode == "rffa"),
                    "about one match of ranked free-for-all");
                foreach (LobbyClient l in lobbies) l.AcceptMatch();
                Check(RunLobbiesUntil(20_000, () => Array.TrueForAll(lobbies, l => l.Grant != null), lobbies),
                    "all eight accepted, and all eight were matched by evt.match.found");
                if (!Array.TrueForAll(lobbies, l => l.Grant != null)) return;
                foreach (LobbyClient l in lobbies)
                {
                    var m = Connect(new Grant { Host = l.Grant.ArenaHost, Port = l.Grant.ArenaPort, TicketId = l.Grant.TicketId, Tls = l.Grant.Tls });
                    matches.Add(m);
                    m.Join(l.Grant.TicketId);
                }
                Check(RunAllUntil(15_000, () => matches.TrueForAll(m => m.Alive), lobbies, matches), "all eight joined the room made for the match");
                if (!matches.TrueForAll(m => m.Alive)) return;
                Check(matches.TrueForAll(m => m.Welcome.Mode == 3), "and the Welcome says ranked free-for-all");
                Check(matches.TrueForAll(m => m.World[Wire.SelfHandle].Team == 0), "each for themselves: team 0, every one");
                long[] coins = Array.ConvertAll(sessions, s => Api.Inventory(s.Token).Result.Value.Coins);
                // Nobody fires: the four minutes run out, all on the same score, all placed first.
                var clock = System.Diagnostics.Stopwatch.StartNew();
                Check(RunAllUntil(270_000, () => matches.TrueForAll(m => m.State == MatchState.Ended), lobbies, matches),
                    "the match ended");
                Console.WriteLine($"    ended after {clock.ElapsedMilliseconds / 1000} s");
                Check(matches.TrueForAll(m => m.End == MatchEnd.MatchOver), "all eight were told the match is over: Kick(6)");
                bool paid = true;
                for (int i = 0; i < 8; i++) paid &= WaitForCoins(sessions[i], coins[i] + 10);
                Check(paid, "the result reached MySQL: each of the eight paid for taking part");
            }
            finally
            {
                foreach (var m in matches) m.Dispose();
                foreach (var l in lobbies) l.Dispose();
            }
        }

        /// <summary>
        /// Whether players come back (05 §11): the operator reads today; a new guest plays a stay,
        /// and today counts one more player active and one more new, with day 1 empty until
        /// tomorrow has ended.
        /// </summary>
        private static void Stats()
        {
            if (Environment.GetEnvironmentVariable("BACKEND_ADMIN_URL") == null)
            {
                Fail("stats needs BACKEND_ADMIN_URL and BACKEND_ADMIN_TOKEN");
                return;
            }
            JsonValue Today() => JsonValue.Parse(Admin("GET", "/admin/stats?days=1", null).Content.ReadAsStringAsync().Result)["days"][0];
            JsonValue before = Today();
            // The funnel and the guests' measure (05 §11, plan item 76 (c)), read before the guest is made.
            JsonValue Funnel() => JsonValue.Parse(Admin("GET", "/admin/stats/funnel?days=1", null).Content.ReadAsStringAsync().Result)["days"][0];
            JsonValue GuestsNow() => JsonValue.Parse(Admin("GET", "/admin/stats/guests", null).Content.ReadAsStringAsync().Result);
            JsonValue funnelBefore = Funnel();
            long guestsBefore = GuestsNow()["guests"].AsLong;
            Guest guest = Patiently(() => Api.CreateGuest()).Value;
            Session session = Patiently(() => Api.LoginGuest(guest.GuestKey)).Value;
            Check(PaidStay(session) > 0, "a new guest played a stay, and was paid for it");
            JsonValue funnelAfter = Funnel();
            Check(funnelAfter["registered"].AsLong == funnelBefore["registered"].AsLong + 1
                  && funnelAfter["guests"].AsLong == funnelBefore["guests"].AsLong + 1
                  && funnelAfter["played"].AsLong == funnelBefore["played"].AsLong + 1,
                $"the funnel: one more made today, a guest, who played ({funnelBefore["played"].AsLong} → {funnelAfter["played"].AsLong})");
            Check(GuestsNow()["guests"].AsLong == guestsBefore + 1, "and one more guest measured");
            JsonValue after = Today();
            Check(after["active"].AsLong == before["active"].AsLong + 1,
                $"one more player active today ({before["active"].AsLong} → {after["active"].AsLong})");
            Check(after["newPlayers"].AsLong == before["newPlayers"].AsLong + 1,
                $"and one more new ({before["newPlayers"].AsLong} → {after["newPlayers"].AsLong})");
            Check(after["d1"].IsNull, "day 1 empty until tomorrow has ended");
            Check((int) Admin("GET", "/admin/stats?days=61", null).StatusCode == 400, "more than 60 days refused");
            JsonValue sixty = JsonValue.Parse(Admin("GET", "/admin/stats?days=60", null).Content.ReadAsStringAsync().Result)["days"];
            Check(sixty.Count == 60 && !sixty[59]["d30"].IsNull, "60 days, the oldest with its day 30");

            // By feature (Q-26): a second new guest plays, and the two make friends on their first day.
            JsonValue FeaturesToday() => JsonValue.Parse(Admin("GET", "/admin/stats/features?days=1", null)
                .Content.ReadAsStringAsync().Result)["days"][0]["features"];
            long friends = FeaturesToday()["friend"]["players"].AsLong;
            Guest otherGuest = Patiently(() => Api.CreateGuest()).Value;
            Session other = Patiently(() => Api.LoginGuest(otherGuest.GuestKey)).Value;
            Check(PaidStay(other) > 0, "a second new guest played a stay");
            Api.AskFriend(session.Token, other.PlayerId).Wait();
            Check(Api.AskFriend(other.Token, session.PlayerId).Result.Value == "friends", "and the two became friends");
            long made = FeaturesToday()["friend"]["players"].AsLong;
            Check(made == friends + 2, $"both counted as new players who made a friend today ({friends} → {made})");
        }

        /// <summary>
        /// An operator's notice (04 §10): two players in the lobby, one call, and each told the text by
        /// evt.notice, whichever gateway holds them.
        /// </summary>
        private static void Notice()
        {
            if (Environment.GetEnvironmentVariable("BACKEND_ADMIN_URL") == null)
            {
                Fail("notice needs BACKEND_ADMIN_URL and BACKEND_ADMIN_TOKEN");
                return;
            }
            Session a = NewSession().session, b = NewSession().session;
            using var la = new LobbyClient(_lobby, new SystemClock());
            using var lb = new LobbyClient(_lobby, new SystemClock());
            string toldA = null, toldB = null;
            la.OnPush += (t, d) => { if (t == "evt.notice") toldA = d["text"].AsString; };
            lb.OnPush += (t, d) => { if (t == "evt.notice") toldB = d["text"].AsString; };
            la.Connect(a.Token);
            lb.Connect(b.Token);
            Check(RunLobbiesUntil(10_000, () => la.State == LobbyState.Ready && lb.State == LobbyState.Ready, la, lb),
                "both are in the lobby");
            string text = "Back in five minutes: a drill " + Guid.NewGuid().ToString("N").Substring(0, 6);
            var sent = Admin("POST", "/admin/notice", "{\"text\":\"" + text + "\",\"reason\":\"drill\"}");
            Check((int)sent.StatusCode == 202, $"the operator sent a notice ({(int)sent.StatusCode} {sent.Content.ReadAsStringAsync().Result})");
            Check(RunLobbiesUntil(5_000, () => toldA == text && toldB == text, la, lb), "and both were told it, word for word");
        }

        /// <summary>The operator's API, as the drill runs it: a POST or GET with the secret.</summary>
        private static System.Net.Http.HttpResponseMessage Admin(string method, string path, string body)
        {
            string admin = Environment.GetEnvironmentVariable("BACKEND_ADMIN_URL");
            string secret = Environment.GetEnvironmentVariable("BACKEND_ADMIN_TOKEN");
            using var http = new System.Net.Http.HttpClient();
            var request = new System.Net.Http.HttpRequestMessage(new System.Net.Http.HttpMethod(method), admin + path);
            if (body != null) request.Content = new System.Net.Http.StringContent(body);
            request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", secret);
            var response = http.Send(request);
            response.Content.LoadIntoBufferAsync().Wait();
            return response;
        }

        /// <summary>
        /// An operator takes a player out of the public arena, then closes another's room (04 §10):
        /// each match ends Removed, Kick(7); a player removed but not banned plays again.
        /// </summary>
        private static void Removed()
        {
            if (Environment.GetEnvironmentVariable("BACKEND_ADMIN_URL") == null)
            {
                Fail("removed needs BACKEND_ADMIN_URL and BACKEND_ADMIN_TOKEN");
                return;
            }
            var (sa, _) = NewSession();
            MatchGrant first = Api.RequestMatch(sa.Token).Result.Value;
            var ga = new Grant { Host = first.ArenaHost, Port = first.ArenaPort, TicketId = first.TicketId, Tls = first.Tls };
            using var ma = Connect(ga);
            ma.Join(ga.TicketId);
            Check(RunUntil(ma, 10_000, () => ma.Alive), "a player in the public arena");
            Check((int) Admin("POST", $"/admin/players/{sa.PlayerId}/kick", "{\"reason\":\"drill\"}").StatusCode == 202,
                "the operator kicked them");
            Check(RunUntil(ma, 10_000, () => ma.State == MatchState.Ended), "and their match ended");
            Check(ma.End == MatchEnd.Removed, $"removed, Kick(7) ({ma.End})");

            var (_, gb) = NewPlayerWithGrant();
            using var mb = Connect(gb);
            mb.Join(gb.TicketId);
            Check(RunUntil(mb, 10_000, () => mb.Alive), "another player in the public arena");
            string room = null;
            string arena = null;
            for (int i = 0; i < 20 && room == null; i++)
            {
                RunFor(mb, 500);                              // the rooms, as the next announcement has them
                JsonValue rooms = JsonValue.Parse(Admin("GET", "/admin/rooms", null).Content.ReadAsStringAsync().Result);
                for (int r = 0; r < rooms.Count; r++)
                    if (rooms[r]["players"].AsInt > 0) { room = rooms[r]["room"].AsString; arena = rooms[r]["arena"].AsString; }
            }
            Check(room != null, $"the operator sees the room ({arena} {room})");
            if (room == null) return;
            Check((int) Admin("POST", $"/admin/rooms/{arena}/{room}/close", "{\"reason\":\"drill\"}").StatusCode == 202,
                "and closes it");
            Check(RunUntil(mb, 10_000, () => mb.State == MatchState.Ended) && mb.End == MatchEnd.Removed,
                $"its player was removed, Kick(7) ({mb.End})");

            ApiResult<MatchGrant> again = Api.RequestMatch(sa.Token).Result;
            Check(again.Ok, $"the player removed, not banned, may play again ({again})");
            if (!again.Ok) return;
            using var back = Connect(new Grant { Host = again.Value.ArenaHost, Port = again.Value.ArenaPort, TicketId = again.Value.TicketId, Tls = again.Value.Tls });
            back.Join(again.Value.TicketId);
            Check(RunUntil(back, 10_000, () => back.Alive), "and is back in the arena");
        }

        /// <summary>
        /// Fixed-phrase chat in the public arena (01 §9): the list platform serves is the one the
        /// Welcome names; a phrase said comes back to its speaker, by their own handle and name; a
        /// second at once is dropped without a kick, and one two seconds on is heard.
        /// </summary>
        private static void Phrase()
        {
            var (session, name) = NewSession();
            MatchGrant grant = Api.RequestMatch(session.Token).Result.Value;
            var g = new Grant { Host = grant.ArenaHost, Port = grant.ArenaPort, TicketId = grant.TicketId, Tls = grant.Tls };
            using var m = Connect(g);
            var heard = new List<(int, int, string)>();
            m.OnEvent = e => { if (e.Type == Wire.EvtPhrase) heard.Add((e.Speaker, e.PhraseId, e.SpeakerName)); };
            m.Join(g.TicketId);
            Check(RunUntil(m, 10_000, () => m.Alive), "a player in the public arena");
            ApiResult<PhraseListInfo> list = Api.Phrases().Result;
            Check(list.Ok && list.Value.Version == m.Welcome.PhraseListVersion && list.Value.Phrases.Count == 16,
                $"the phrase list platform serves is the one the Welcome names ({m.Welcome.PhraseListVersion}), sixteen phrases");
            if (!list.Ok) return;
            PhraseInfo hello = list.Value.Phrases[0];
            m.Say(hello.Id);
            Check(RunUntil(m, 5_000, () => heard.Count > 0) && heard[0] == (Wire.SelfHandle, hello.Id, name),
                $"said \"{hello.Text}\" and heard it back, by their own handle and name ({string.Join(" ", heard)})");
            m.Say(list.Value.Phrases[1].Id);
            RunFor(m, 1_000);
            Check(heard.Count == 1 && m.Alive, "a second at once is dropped, and the player stays");
            RunFor(m, 1_200);
            m.Say(list.Value.Phrases[2].Id);
            Check(RunUntil(m, 5_000, () => heard.Count == 2), $"two seconds on, heard again ({string.Join(" ", heard)})");
        }

        /// <summary>
        /// An operator bans a player in the lobby (04 §10): every session ends, the lobby is told
        /// and does not come back, and logging in is refused until the ban is lifted.
        /// </summary>
        private static void Banned()
        {
            string admin = Environment.GetEnvironmentVariable("BACKEND_ADMIN_URL");
            string secret = Environment.GetEnvironmentVariable("BACKEND_ADMIN_TOKEN");
            if (admin == null || secret == null)
            {
                Fail("banned needs BACKEND_ADMIN_URL and BACKEND_ADMIN_TOKEN");
                return;
            }
            var (session, name) = NewSession();
            using var lobby = new LobbyClient(_lobby, new SystemClock());
            lobby.Connect(session.Token);
            Check(RunLobbyUntil(lobby, 10_000, () => lobby.State == LobbyState.Ready), "in the lobby");
            using var http = new System.Net.Http.HttpClient();
            System.Net.Http.HttpResponseMessage Call(string action)
            {
                var request = new System.Net.Http.HttpRequestMessage(System.Net.Http.HttpMethod.Post,
                    $"{admin}/admin/players/{session.PlayerId}/{action}")
                {
                    Content = new System.Net.Http.StringContent("{\"reason\":\"drill\"}"),
                };
                request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", secret);
                return http.Send(request);
            }
            Check((int) Call("ban").StatusCode == 200, "the operator banned the player");
            Check(RunLobbyUntil(lobby, 5_000, () => lobby.State == LobbyState.InvalidSession),
                $"the lobby was told, and closed ({lobby.LastError})");
            var watch = System.Diagnostics.Stopwatch.StartNew();
            while (watch.ElapsedMilliseconds < 3_000) { lobby.Poll(); Thread.Sleep(50); }
            Check(lobby.State == LobbyState.InvalidSession, "and did not reconnect");
            Check(Api.Queue(session.Token).Result.Status == 401, "the session is gone");
            ApiResult<Session> refused = Patiently(() => Api.Login(name, "hunter2-hunter2"));
            Check(!refused.Ok && refused.Code == "banned", $"logging in is refused ({refused.Code})");
            Check((int) Call("unban").StatusCode == 200, "the operator lifted it");
            Check(Patiently(() => Api.Login(name, "hunter2-hunter2")).Ok, "and the player logs in again");
        }

        /// <summary>
        /// Co-op (01 §8.5): a party of two and one alone queue, are asked, accept, and are one team
        /// against the arena's waves; they drive toward them without firing, and the wipe ends it.
        /// </summary>
        private static void Coop()
        {
            var sessions = new Session[3];
            var names = new string[3];
            var lobbies = new LobbyClient[3];
            for (int i = 0; i < 3; i++)
            {
                (sessions[i], names[i]) = NewSession();
                lobbies[i] = new LobbyClient(_lobby, new SystemClock());
                lobbies[i].Connect(sessions[i].Token);
            }
            var matches = new List<MatchConnection>();
            try
            {
                Check(RunLobbiesUntil(10_000, () => Array.TrueForAll(lobbies, l => l.State == LobbyState.Ready), lobbies),
                    "three players are in the lobby");
                lobbies[0].InviteToParty(lobbies[1].PlayerId);
                Check(RunLobbiesUntil(5_000, () => lobbies[1].Invitation != null, lobbies), "an invitation");
                if (lobbies[1].Invitation == null) return;
                lobbies[1].AcceptInvitation(lobbies[1].Invitation.PartyId);
                Check(RunLobbiesUntil(5_000, () => lobbies[0].Party?.Members.Count == 2, lobbies), "a party of two");
                var saidToParty = new[] { new List<string>(), new List<string>(), new List<string>() };
                for (int i = 0; i < 3; i++)
                {
                    int k = i;
                    lobbies[k].OnPartySaid += p => saidToParty[k].Add(p.PhraseId + " " + p.Name + " " + p.From);
                }
                lobbies[0].SayToParty(2);                                  // "Good luck!", in the lobby (01 §9)
                string wished = "2 " + names[0] + " " + lobbies[0].PlayerId;
                Check(RunLobbiesUntil(5_000, () => saidToParty[0].Contains(wished) && saidToParty[1].Contains(wished), lobbies)
                      && saidToParty[2].Count == 0,
                    "a phrase said to the party in the lobby reached both members, and not the one outside it");
                lobbies[0].SayToParty(3);
                Check(RunLobbiesUntil(5_000, () => lobbies[0].PartyRefusal != null, lobbies) && lobbies[0].PartyRefusal == "too_soon",
                    $"a second at once is refused, too_soon ({lobbies[0].PartyRefusal})");
                lobbies[0].JoinQueue("coop");
                lobbies[2].JoinQueue("coop");
                Check(RunLobbiesUntil(20_000, () => Array.TrueForAll(lobbies, l => l.Ready != null), lobbies),
                    "all three were asked by evt.match.ready: the pair and the one alone, one team");
                if (!Array.TrueForAll(lobbies, l => l.Ready != null)) return;
                foreach (LobbyClient l in lobbies) l.AcceptMatch();
                Check(RunLobbiesUntil(20_000, () => Array.TrueForAll(lobbies, l => l.Grant != null), lobbies),
                    "all three accepted, and were matched by evt.match.found");
                if (!Array.TrueForAll(lobbies, l => l.Grant != null)) return;
                Check(Array.TrueForAll(lobbies, l => l.Grant.Mode == "coop" && l.Grant.ArenaPort == lobbies[0].Grant.ArenaPort),
                    "to one arena, for co-op");
                foreach (LobbyClient l in lobbies)
                {
                    var m = Connect(new Grant { Host = l.Grant.ArenaHost, Port = l.Grant.ArenaPort, TicketId = l.Grant.TicketId, Tls = l.Grant.Tls });
                    matches.Add(m);
                    m.Join(l.Grant.TicketId);
                }
                Check(RunAllUntil(15_000, () => matches.TrueForAll(m => m.Alive), lobbies, matches), "all three joined the room made for the match");
                if (!matches.TrueForAll(m => m.Alive)) return;
                Check(matches.TrueForAll(m => m.Welcome.Mode == 4), "and the Welcome says co-op");
                Check(matches.TrueForAll(m => m.World[Wire.SelfHandle].Team == 1), "one team, team 1");
                var heard = new[] { new List<string>(), new List<string>(), new List<string>() };
                for (int i = 0; i < 3; i++)
                {
                    int k = i;
                    matches[k].OnEvent = e => { if (e.Type == Wire.EvtPhrase) heard[k].Add(e.PhraseId + " " + e.SpeakerName); };
                }
                matches[0].Say(7);                                   // "Help!" (01 §9)
                string said = "7 " + names[0];
                Check(RunAllUntil(5_000, () => Array.TrueForAll(heard, h => h.Contains(said)), lobbies, matches),
                    "a phrase said is heard by the whole team, by name, wherever they are");
                long[] coins = Array.ConvertAll(sessions, s => Api.Inventory(s.Token).Result.Value.Coins);
                foreach (var m in matches) m.SetInput(Wire.MoveRight, Wire.QuantiseAim(0f), false, 0);
                Check(RunAllUntil(90_000, () => matches.Exists(m => SeesTeam(m, 2)), lobbies, matches),
                    "the first wave came: the arena's tanks, team 2, in view");
                var clock = System.Diagnostics.Stopwatch.StartNew();
                Check(RunAllUntil(240_000, () => matches.TrueForAll(m => m.State == MatchState.Ended), lobbies, matches),
                    "they hunted the team down, and the wipe ended the match");
                Console.WriteLine($"    ended {clock.ElapsedMilliseconds / 1000} s after the wave was seen");
                Check(matches.TrueForAll(m => m.End == MatchEnd.MatchOver), "all three were told the match is over: Kick(6)");
                bool paid = true;
                for (int i = 0; i < 3; i++) paid &= WaitForCoins(sessions[i], coins[i] + 10);
                Check(paid, "the result reached MySQL: each of the three paid for taking part");
            }
            finally
            {
                foreach (var m in matches) m.Dispose();
                foreach (var l in lobbies) l.Dispose();
            }
        }

        /// <summary>Whether a tank of {@code team} is in the connection's world.</summary>
        private static bool SeesTeam(MatchConnection m, int team)
        {
            for (int h = 1; h < Wire.MaxHandles; h++)
                if (m.World[h].Alive && m.World[h].Kind == Wire.KindTank && m.World[h].Team == team) return true;
            return false;
        }

        /// <summary>
        /// A match found and declined (D-27): the one who declined is out and locked out for a minute,
        /// the other back in the queue and told; nobody is sent to a room.
        /// </summary>
        private static void Decline()
        {
            var (a, _) = NewSession();
            var (b, _) = NewSession();
            using var la = new LobbyClient(_lobby, new SystemClock());
            using var lb = new LobbyClient(_lobby, new SystemClock());
            la.Connect(a.Token);
            lb.Connect(b.Token);
            Check(RunLobbiesUntil(10_000, () => la.State == LobbyState.Ready && lb.State == LobbyState.Ready, la, lb), "both players are in the lobby");
            la.JoinQueue("duel");
            lb.JoinQueue("duel");
            Check(RunLobbiesUntil(15_000, () => la.Ready != null && lb.Ready != null, la, lb), "both were asked by evt.match.ready");
            if (la.Ready == null || lb.Ready == null) return;
            QueueStatus asked = Api.Queue(b.Token).Result.Value;
            Check(asked.State == "confirming" && asked.MatchUid == lb.Ready.MatchUid && asked.SecondsLeft is > 0 and <= 10,
                $"and GET /v1/queue says so, with the time left ({asked.State}, {asked.SecondsLeft} s)");
            lb.AcceptMatch();
            la.DeclineMatch();
            Check(RunLobbiesUntil(5_000, () => lb.QueueState == "queued", la, lb), "one declined: the other is told it is queued again");
            Check(la.Grant == null && lb.Grant == null, "and nobody is sent to a room");
            Check(Api.Queue(a.Token).Result.Value.State == "none", "the one who declined is out of the queue");
            la.JoinQueue("duel");
            Check(RunLobbiesUntil(5_000, () => la.QueueRefusal != null, la, lb) && la.QueueRefusal == "queue_locked",
                $"and may not queue again for a while ({la.QueueRefusal})");
            lb.LeaveQueue();
        }

        private static bool RunAllUntil(int ms, Func<bool> done, LobbyClient[] lobbies, List<MatchConnection> matches)
        {
            var clock = System.Diagnostics.Stopwatch.StartNew();
            while (clock.ElapsedMilliseconds < ms)
            {
                foreach (var l in lobbies) l.Poll();
                foreach (var m in matches) m.Poll();
                if (done()) return true;
                Thread.Sleep(16);
            }
            foreach (var m in matches) Console.WriteLine($"    match {m.State} {m.End}, last problem: {m.LastProblem}");
            return false;
        }

        private static bool RunLobbiesUntil(int ms, Func<bool> done, params LobbyClient[] lobbies)
        {
            var clock = System.Diagnostics.Stopwatch.StartNew();
            while (clock.ElapsedMilliseconds < ms)
            {
                foreach (var l in lobbies) l.Poll();
                if (done()) return true;
                Thread.Sleep(16);
            }
            foreach (var l in lobbies) Console.WriteLine($"    lobby {l.State} queue {l.QueueState}, last error: {l.LastError}");
            return false;
        }

        private static bool RunMatchesUntil(int ms, Func<bool> done, LobbyClient la, LobbyClient lb, params MatchConnection[] matches)
        {
            var clock = System.Diagnostics.Stopwatch.StartNew();
            while (clock.ElapsedMilliseconds < ms)
            {
                la.Poll();
                lb.Poll();
                foreach (var m in matches) m.Poll();
                if (done()) return true;
                Thread.Sleep(16);
            }
            foreach (var m in matches) Console.WriteLine($"    match {m.State} {m.End}, last problem: {m.LastProblem}");
            return false;
        }

        private static bool WaitForCoins(Session s, long atLeast)
        {
            var clock = System.Diagnostics.Stopwatch.StartNew();
            long coins = 0;
            while (clock.ElapsedMilliseconds < 20_000)
            {
                coins = Api.Inventory(s.Token).Result.Value.Coins;
                if (coins >= atLeast) return true;
                Thread.Sleep(250);
            }
            Console.WriteLine($"    player {s.PlayerId}: {coins} coins, expected at least {atLeast}");
            return false;
        }

        /// <summary>A second sign-in replaces the first lobby connection, which must not come back (03 §4).</summary>
        private static void Replaced()
        {
            var (session, _) = NewSession();
            using var first = new LobbyClient(_lobby, new SystemClock());
            first.Connect(session.Token);
            Check(RunLobbyUntil(first, 10_000, () => first.State == LobbyState.Ready), "the first device is in the lobby");
            using var second = new LobbyClient(_lobby, new SystemClock());
            second.Connect(session.Token);
            Check(RunLobbyUntil(second, 10_000, () => second.State == LobbyState.Ready), "the second device is in the lobby");
            Check(RunLobbyUntil(first, 5_000, () => first.State == LobbyState.Replaced), "the first was told it was replaced");
            var watch = System.Diagnostics.Stopwatch.StartNew();
            while (watch.ElapsedMilliseconds < 3_000) { first.Poll(); second.Poll(); Thread.Sleep(50); }
            Check(first.State == LobbyState.Replaced, "and did not reconnect, which would replace the second in turn");
            Check(second.State == LobbyState.Ready, "the second is still in");
        }

        /// <summary>A token the store does not know is refused, and not retried: log in again.</summary>
        private static void BadSession()
        {
            using var lobby = new LobbyClient(_lobby, new SystemClock());
            lobby.Connect("not-a-session-token");
            Check(RunLobbyUntil(lobby, 10_000, () => lobby.State == LobbyState.InvalidSession), $"refused as an invalid session ({lobby.LastError})");
        }

        private static bool RunLobbyUntil(LobbyClient lobby, int ms, Func<bool> done)
        {
            var clock = System.Diagnostics.Stopwatch.StartNew();
            while (clock.ElapsedMilliseconds < ms)
            {
                lobby.Poll();
                if (done()) return true;
                Thread.Sleep(16);
            }
            Console.WriteLine($"    lobby state {lobby.State}, last error: {lobby.LastError}");
            return false;
        }

        // ---- plumbing -------------------------------------------------------------------------

        /// <summary>
        /// Trusts one certificate, as a root, in place of the device's store. The name check the
        /// handshake made against the grant's host still has to have passed.
        /// </summary>
        private static RemoteCertificateValidationCallback TrustOnly(X509Certificate2 root)
        {
            return (sender, certificate, chain, errors) =>
            {
                if ((errors & SslPolicyErrors.RemoteCertificateNameMismatch) != 0 || certificate == null) return false;
                using var build = new X509Chain();
                build.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
                build.ChainPolicy.TrustMode = X509ChainTrustMode.CustomRootTrust;
                build.ChainPolicy.CustomTrustStore.Add(root);
                return build.Build(new X509Certificate2(certificate));
            };
        }

        private sealed class Grant
        {
            public string Host, TicketId;
            public int Port;
            public bool Tls;
        }

        private static MatchState State(MatchConnection m) => m.State;

        private static MatchConnection Connect(Grant g) =>
            new MatchConnection(new MatchSettings
            {
                Host = g.Host, Port = g.Port, Tls = g.Tls,
                CertificateCheck = _trust == null ? null : TrustOnly(_trust),
            }, new SystemClock());

        /// <summary>Polls as a frame loop would, at 60 Hz, until the condition holds or time runs out.</summary>
        private static bool RunUntil(MatchConnection m, int ms, Func<bool> done, bool quiet = false)
        {
            var clock = System.Diagnostics.Stopwatch.StartNew();
            while (clock.ElapsedMilliseconds < ms)
            {
                m.Poll();
                if (done()) return true;
                if (m.State == MatchState.Ended) break;
                Thread.Sleep(16);
            }
            if (!quiet) Console.WriteLine($"    state {m.State}, end {m.End}, last problem: {m.LastProblem}");
            return false;
        }

        private static void RunFor(MatchConnection m, int ms) => RunUntil(m, ms, () => false, quiet: true);

        /// <summary>A new account, logged in, with a match grant, through the core's own API client.</summary>
        private static (long playerId, Grant grant) NewPlayerWithGrant()
        {
            var (session, _) = NewSession();
            ApiResult<MatchGrant> g = Api.RequestMatch(session.Token).Result;
            if (!g.Ok) throw new InvalidOperationException("match request: " + g);
            return (session.PlayerId, new Grant { Host = g.Value.ArenaHost, Port = g.Value.ArenaPort, TicketId = g.Value.TicketId, Tls = g.Value.Tls });
        }

        private static (Session session, string name) NewSession()
        {
            string name = "hl" + Guid.NewGuid().ToString("N").Substring(0, 12);
            ApiResult<long> reg = Patiently(() => Api.Register(name, null, "hunter2-hunter2"));
            if (!reg.Ok) throw new InvalidOperationException("register: " + reg);
            ApiResult<Session> login = Patiently(() => Api.Login(name, "hunter2-hunter2"));
            if (!login.Ok) throw new InvalidOperationException("login: " + login);
            return (login.Value, name);
        }

        /// <summary>
        /// A call the platform throttles by address: the drill makes accounts and sessions faster than people do, past
        /// the 30 a minute one address may try (04 §1), so a 429 waits its Retry-After and asks again, as a client
        /// would, twice at most (T-56). Without it, how many scenarios passed hung on how fast the machine ran them.
        /// </summary>
        private static ApiResult<T> Patiently<T>(Func<Task<ApiResult<T>>> call)
        {
            for (int tries = 0; ; tries++)
            {
                ApiResult<T> r = call().Result;
                if (r.Status != 429 || r.Code != "too_many_attempts" || tries == 2) return r;
                Thread.Sleep(Math.Max(1, r.RetryAfterSeconds) * 1000 + 500);
            }
        }

        private static void Check(bool ok, string what)
        {
            Console.WriteLine($"  {(ok ? "ok  " : "FAIL")} {what}");
            if (!ok) _failures++;
        }

        private static void Fail(string what) => Check(false, what);
    }
}
