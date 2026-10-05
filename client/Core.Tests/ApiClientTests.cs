using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Backend.Client.Core;
using NUnit.Framework;

namespace Backend.Client.Core.Tests
{
    public class ApiClientTests
    {
        /// <summary>Answers by host: a host in <see cref="Down"/> cannot be reached.</summary>
        private sealed class Hosts : HttpMessageHandler
        {
            public readonly HashSet<string> Down = new HashSet<string>();
            public readonly List<string> Asked = new List<string>();
            public Func<HttpRequestMessage, HttpResponseMessage> Answer;

            protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancel)
            {
                Asked.Add(request.RequestUri.Host);
                if (Down.Contains(request.RequestUri.Host)) throw new HttpRequestException("connection refused");
                return Task.FromResult(Answer(request));
            }
        }

        private static HttpResponseMessage Json(HttpStatusCode status, string body) =>
            new HttpResponseMessage(status) { Content = new StringContent(body, Encoding.UTF8, "application/json") };

        private static readonly string[] Three = { "https://a.example.test", "https://b.example.test/", "https://c.example.test" };

        [Test]
        public void AnEndpointThatCannotBeReachedMovesTheCallOnAndTheOneThatAnsweredIsTriedFirstNext()
        {
            var hosts = new Hosts { Answer = _ => Json(HttpStatusCode.OK, "{\"token\":\"t\",\"playerId\":7,\"expiresInSeconds\":86400}") };
            hosts.Down.Add("a.example.test");
            var api = new ApiClient(Three, hosts);
            ApiResult<Session> login = api.Login("ada", "hunter2-hunter2").Result;
            Assert.That(login.Ok, Is.True);
            Assert.That(login.Value.PlayerId, Is.EqualTo(7));
            Assert.That(hosts.Asked, Is.EqualTo(new[] { "a.example.test", "b.example.test" }));
            Assert.That(api.Current, Is.EqualTo("https://b.example.test"));

            hosts.Asked.Clear();
            api.Shop().Wait();
            Assert.That(hosts.Asked, Is.EqualTo(new[] { "b.example.test" }), "the one that answered, first");
        }

        [Test]
        public void TheClassTableIsReadWholeWithItsVersion()
        {
            const string body = "{\"version\":3735928559,\"classes\":["
                + "{\"id\":0,\"name\":\"Basic\",\"opensAt\":1,\"parents\":[],\"fov\":1.0,\"reload\":1.0,\"maxDrones\":0,"
                + "\"caps\":[7,7,7,7,7,7,7,7],\"bodyDamage\":1.0,\"hidesAfter\":0,\"bodySize\":1.0,\"zoom\":0.0,\"barrels\":["
                + "{\"angle\":0.0,\"side\":0.0,\"delay\":0.0,\"speed\":1.0,\"damage\":1.0,\"penetration\":1.0,\"lifetime\":75,"
                + "\"spread\":0.0,\"size\":1.0,\"recoil\":0.0,\"kind\":0,\"arc\":0.0}]},"
                + "{\"id\":1,\"name\":\"Overseer\",\"opensAt\":30,\"parents\":[0,2],\"fov\":1.1,\"reload\":1.5,\"maxDrones\":8,"
                + "\"caps\":[10,10,10,0,0,0,0,10],\"bodyDamage\":1.25,\"hidesAfter\":50,\"bodySize\":1.3,\"zoom\":700.0,\"barrels\":["
                + "{\"angle\":1.5707964,\"side\":-10.0,\"delay\":0.5,\"speed\":0.5,\"damage\":0.7,\"penetration\":2.0,"
                + "\"lifetime\":2147483647,\"spread\":0.0,\"size\":1.25,\"recoil\":0.0,\"kind\":2,\"arc\":0.0}]}]}";
            var hosts = new Hosts { Answer = _ => Json(HttpStatusCode.OK, body) };
            ApiResult<ClassTableInfo> r = new ApiClient(Three, hosts).Classes().Result;
            Assert.That(r.Ok, Is.True);
            Assert.That(r.Value.Version, Is.EqualTo(3735928559L), "an unsigned 32 bits, as Welcome.ContentVersion is");
            Assert.That(r.Value.Classes, Has.Count.EqualTo(2));
            ClassInfo o = r.Value.Classes[1];
            Assert.That((o.Id, o.Name, o.OpensAt, o.MaxDrones, o.HidesAfterTicks), Is.EqualTo((1, "Overseer", 30, 8, 50)));
            Assert.That(o.Parents, Is.EqualTo(new[] { 0, 2 }));
            Assert.That(o.Caps, Is.EqualTo(new[] { 10, 10, 10, 0, 0, 0, 0, 10 }));
            Assert.That((o.Fov, o.Reload, o.BodyDamage, o.BodySize, o.Zoom), Is.EqualTo((1.1f, 1.5f, 1.25f, 1.3f, 700f)));
            BarrelInfo d = o.Barrels[0];
            Assert.That((d.Angle, d.Side, d.Delay, d.Size, d.Kind), Is.EqualTo((1.5707964f, -10f, 0.5f, 1.25f, 2)));
            Assert.That(d.Lifetime, Is.EqualTo(int.MaxValue), "a drone that lasts until used up");
            Assert.That(r.Value.Classes[0].Barrels[0].Lifetime, Is.EqualTo(75));
        }

        [Test]
        public void ThePhraseListIsReadWithItsVersion()
        {
            const string body = "{\"version\":3735928559,\"phrases\":["
                + "{\"id\":1,\"key\":\"hello\",\"text\":\"Hello!\"},{\"id\":3,\"key\":\"thanks\",\"text\":\"Thanks!\"}]}";
            var hosts = new Hosts { Answer = _ => Json(HttpStatusCode.OK, body) };
            ApiResult<PhraseListInfo> r = new ApiClient(Three, hosts).Phrases().Result;
            Assert.That(r.Ok, Is.True);
            Assert.That(r.Value.Version, Is.EqualTo(3735928559L), "as Welcome.PhraseListVersion is");
            Assert.That(r.Value.Phrases.Select(p => (p.Id, p.Key, p.Text)),
                Is.EqualTo(new[] { (1, "hello", "Hello!"), (3, "thanks", "Thanks!") }));
            Assert.That(hosts.Asked, Has.Count.EqualTo(1));
        }

        [Test]
        public void ARefusalIsAnAnswerNotAReasonToTryElsewhere()
        {
            var hosts = new Hosts
            {
                Answer = _ =>
                {
                    var r = Json((HttpStatusCode)429, "{\"code\":\"throttled\",\"message\":\"too many attempts\"}");
                    r.Headers.RetryAfter = new System.Net.Http.Headers.RetryConditionHeaderValue(TimeSpan.FromSeconds(17));
                    return r;
                },
            };
            var api = new ApiClient(Three, hosts);
            ApiResult<Session> login = api.Login("ada", "wrong").Result;
            Assert.That((login.Ok, login.Status, login.Code, login.RetryAfterSeconds), Is.EqualTo((false, 429, "throttled", 17)));
            Assert.That(hosts.Asked, Has.Count.EqualTo(1));
        }

        [Test]
        public void NoEndpointAnsweringIsUnreachable()
        {
            var hosts = new Hosts();
            foreach (string h in new[] { "a.example.test", "b.example.test", "c.example.test" }) hosts.Down.Add(h);
            ApiResult<List<Offer>> shop = new ApiClient(Three, hosts).Shop().Result;
            Assert.That(shop.Unreachable, Is.True);
            Assert.That(shop.Message, Does.Contain("c.example.test"));
            Assert.That(hosts.Asked, Has.Count.EqualTo(3));
        }

        [Test]
        public void AnOfferInGemsIsReadSoAndItsReceiptGivesTheGems()
        {
            var hosts = new Hosts
            {
                Answer = r => r.RequestUri.AbsolutePath == "/v1/shop"
                    ? Json(HttpStatusCode.OK, "{\"offers\":[{\"sku\":\"boost\",\"itemId\":\"boost_xp_hour\",\"price\":20,"
                        + "\"requiresLevel\":1,\"availableTo\":null,\"currency\":\"gems\"},{\"sku\":\"old\",\"itemId\":\"x\","
                        + "\"price\":5,\"requiresLevel\":1,\"availableTo\":null}]}")
                    : Json(HttpStatusCode.OK, "{\"result\":\"bought\",\"itemId\":\"boost_xp_hour\",\"coins\":7,\"held\":1,\"gems\":5}"),
            };
            var api = new ApiClient(Three, hosts);
            List<Offer> offers = api.Shop().Result.Value;
            Assert.That((offers[0].Currency, offers[1].Currency), Is.EqualTo(("gems", "coins")), "coins from a server before gems");
            Receipt bought = api.Purchase("tok", "boost", ApiClient.NewPurchaseKey()).Result.Value;
            Assert.That((bought.Coins, bought.Gems), Is.EqualTo((7L, 5L)));
        }

        [Test]
        public void APurchaseSendsItsKeyAndABearerAndReadsTheReceipt()
        {
            string sent = null, auth = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    sent = r.Content.ReadAsStringAsync().Result;
                    auth = r.Headers.Authorization?.ToString();
                    return Json(HttpStatusCode.OK, "{\"result\":\"already_bought\",\"itemId\":\"cosmetic_hat\",\"coins\":50,\"held\":2}");
                },
            };
            string key = ApiClient.NewPurchaseKey();
            Assert.That(key, Does.Match("^[a-z0-9-]{36}$"), "a lowercase UUID, as the API requires");
            ApiResult<Receipt> r = new ApiClient(Three, hosts).Purchase("tok", "hat", key).Result;
            Assert.That(JsonValue.Parse(sent)["key"].AsString, Is.EqualTo(key));
            Assert.That(auth, Is.EqualTo("Bearer tok"));
            Assert.That((r.Value.Result, r.Value.ItemId, r.Value.Coins, r.Value.Held), Is.EqualTo(("already_bought", "cosmetic_hat", 50L, 2)));
        }

        [Test]
        public void GemsForMoneyOrdersAPackAndTheSimulatedProviderAnswersIt()
        {
            var sent = new List<(string Method, string Path, string Body, string Auth)>();
            const string order = "{\"orderId\":\"0f1e2d3c-4b5a-4968-8776-655443322110\",\"productId\":\"gems_80\",\"gems\":80,"
                + "\"bonus\":0,\"priceCents\":99,\"currency\":\"USD\",\"state\":\"paid\",\"createdAt\":\"2026-10-04T12:00:00Z\"}";
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    sent.Add((r.Method.Method, r.RequestUri.AbsolutePath, r.Content?.ReadAsStringAsync().Result,
                        r.Headers.Authorization?.ToString()));
                    string path = r.RequestUri.AbsolutePath;
                    if (path == "/v1/payments/packs")
                        return Json(HttpStatusCode.OK, "{\"packs\":[{\"productId\":\"gems_80\",\"gems\":80,\"priceCents\":99,"
                            + "\"currency\":\"USD\"},{\"productId\":\"gems_500\",\"gems\":500,\"priceCents\":499,\"currency\":\"USD\"}]}");
                    if (path.EndsWith("/simulate"))
                        return Json(HttpStatusCode.OK, "{\"order\":" + order + ",\"confirmed\":true,\"gems\":160}");
                    return Json(HttpStatusCode.OK, "{\"order\":" + order + "}");
                },
            };
            var api = new ApiClient(Three, hosts);
            List<Pack> packs = api.Packs().Result.Value;
            Assert.That((packs.Count, packs[1].ProductId, packs[1].Gems, packs[1].PriceCents, packs[1].Currency),
                Is.EqualTo((2, "gems_500", 500, 499, "USD")));

            string key = ApiClient.NewPurchaseKey();
            PaymentOrder placed = api.PlaceOrder("tok", "gems_80", key).Result.Value;
            Assert.That((placed.OrderId, placed.ProductId, placed.Gems, placed.Bonus, placed.PriceCents, placed.Currency, placed.State,
                placed.CreatedAt), Is.EqualTo(("0f1e2d3c-4b5a-4968-8776-655443322110", "gems_80", 80, 0, 99, "USD", "paid",
                "2026-10-04T12:00:00Z")));
            PaymentResult paid = api.SimulatePayment("tok", placed.OrderId, true).Result.Value;
            Assert.That((paid.Confirmed, paid.Gems, paid.Order.State), Is.EqualTo((true, 160L, "paid")));
            api.SimulatePayment("tok", placed.OrderId, false).Wait();
            Assert.That(api.GetOrder("tok", placed.OrderId).Result.Value.OrderId, Is.EqualTo(placed.OrderId));

            Assert.That(sent[0], Is.EqualTo(("GET", "/v1/payments/packs", (string)null, (string)null)), "the packs need no session");
            Assert.That((sent[1].Method, sent[1].Path, sent[1].Auth), Is.EqualTo(("POST", "/v1/payments", "Bearer tok")));
            Assert.That((JsonValue.Parse(sent[1].Body)["productId"].AsString, JsonValue.Parse(sent[1].Body)["key"].AsString),
                Is.EqualTo(("gems_80", key)));
            Assert.That((sent[2].Method, sent[2].Path, JsonValue.Parse(sent[2].Body)["outcome"].AsString, sent[2].Auth),
                Is.EqualTo(("POST", "/v1/payments/0f1e2d3c-4b5a-4968-8776-655443322110/simulate", "paid", "Bearer tok")));
            Assert.That(JsonValue.Parse(sent[3].Body)["outcome"].AsString, Is.EqualTo("declined"));
            Assert.That((sent[4].Method, sent[4].Path, sent[4].Auth),
                Is.EqualTo(("GET", "/v1/payments/0f1e2d3c-4b5a-4968-8776-655443322110", "Bearer tok")));
        }

        [Test]
        public void TheSeasonPassIsReadAndItsPremiumBought()
        {
            var sent = new List<(string Method, string Path, string Auth)>();
            const string pass = "{\"season\":3,\"endsAt\":\"2027-01-01T00:00:00Z\",\"points\":1300,\"tier\":5,\"premium\":true,"
                + "\"premiumGems\":500,\"tierPoints\":250,\"tiers\":[{\"tier\":1,\"free\":{\"coins\":150,\"gems\":0,\"itemId\":null},"
                + "\"premium\":{\"coins\":0,\"gems\":15,\"itemId\":null}},{\"tier\":5,\"free\":{\"coins\":0,\"gems\":5,\"itemId\":null},"
                + "\"premium\":{\"coins\":0,\"gems\":15,\"itemId\":\"boost_xp_hour\"}}]}";
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    sent.Add((r.Method.Method, r.RequestUri.AbsolutePath, r.Headers.Authorization?.ToString()));
                    return r.RequestUri.AbsolutePath == "/v1/pass/premium"
                        ? Json(HttpStatusCode.OK, "{\"result\":\"bought\",\"gems\":75,\"pass\":" + pass + "}")
                        : Json(HttpStatusCode.OK, pass);
                },
            };
            var api = new ApiClient(Three, hosts);
            PassInfo p = api.SeasonPass("tok").Result.Value;
            Assert.That((p.Season, p.EndsAt, p.Points, p.Tier, p.Premium, p.PremiumGems, p.TierPoints, p.Tiers.Count),
                Is.EqualTo((3, "2027-01-01T00:00:00Z", 1300L, 5, true, 500, 250, 2)));
            Assert.That((p.Tiers[0].Tier, p.Tiers[0].Free.Coins, p.Tiers[0].Free.ItemId, p.Tiers[0].Premium.Gems),
                Is.EqualTo((1, 150L, (string)null, 15)));
            Assert.That((p.Tiers[1].Free.Gems, p.Tiers[1].Premium.ItemId), Is.EqualTo((5, "boost_xp_hour")));
            PremiumAnswer bought = api.BuyPremium("tok").Result.Value;
            Assert.That((bought.Result, bought.Gems, bought.Pass.Premium), Is.EqualTo(("bought", 75L, true)));
            Assert.That(sent, Is.EqualTo(new[] { ("GET", "/v1/pass", "Bearer tok"), ("POST", "/v1/pass/premium", "Bearer tok") }));
        }

        [Test]
        public void TheSkinsTableIsReadByNumber()
        {
            string path = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    path = r.RequestUri.AbsolutePath;
                    return Json(HttpStatusCode.OK, "{\"version\":42,\"skins\":[{\"skin\":1,\"itemId\":\"skin_crimson\"},"
                        + "{\"skin\":4,\"itemId\":\"skin_gold\"}]}");
                },
            };
            SkinTable table = new ApiClient(Three, hosts).Skins().Result.Value;
            Assert.That(path, Is.EqualTo("/v1/content/skins"));
            Assert.That((table.Version, table.ItemIdOf(4), table.ItemIdOf(1), table.ItemIdOf(9)),
                Is.EqualTo((42L, "skin_gold", "skin_crimson", (string)null)), "none for a number not in it");
        }

        [Test]
        public void TheFirstLaunchIsAGuestKeptAndEveryLaunchAfterSignsInByItsKey()
        {
            var asked = new List<string>();
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    asked.Add(r.RequestUri.AbsolutePath);
                    return r.RequestUri.AbsolutePath == "/v1/guests"
                        ? Json(HttpStatusCode.Created, "{\"playerId\":7,\"guestKey\":\"k-123\",\"displayName\":\"Guest 1234\"}")
                        : Json(HttpStatusCode.OK, "{\"token\":\"t-7\",\"playerId\":7,\"expiresInSeconds\":86400}");
                },
            };
            var store = new MemorySecureStore();
            var keeper = new AccountKeeper(new ApiClient(Three, hosts), store);
            ApiResult<Session> first = keeper.SignIn().Result;
            Assert.That((first.Ok, first.Value.Token, store.Get(AccountKeeper.GuestKeyName)), Is.EqualTo((true, "t-7", "k-123")));
            Assert.That(asked, Is.EqualTo(new[] { "/v1/guests", "/v1/sessions" }));

            asked.Clear();
            ApiResult<Session> next = new AccountKeeper(new ApiClient(Three, hosts), store).SignIn().Result;
            Assert.That((next.Ok, next.Value.PlayerId), Is.EqualTo((true, 7L)));
            Assert.That(asked, Is.EqualTo(new[] { "/v1/sessions" }), "by the key kept: no second guest");
        }

        /// <summary>One thread runs every continuation posted to it, as Unity's main thread does.</summary>
        private sealed class OneThread : SynchronizationContext
        {
            private readonly System.Collections.Concurrent.BlockingCollection<(SendOrPostCallback, object)> _work =
                new System.Collections.Concurrent.BlockingCollection<(SendOrPostCallback, object)>();

            public override void Post(SendOrPostCallback d, object state) => _work.Add((d, state));

            public T Run<T>(Func<Task<T>> start)
            {
                SynchronizationContext before = Current;
                SetSynchronizationContext(this);
                try
                {
                    Task<T> task = start();
                    task.ContinueWith(_ => _work.CompleteAdding(), TaskScheduler.Default);
                    foreach ((SendOrPostCallback d, object state) in _work.GetConsumingEnumerable()) d(state);
                    return task.GetAwaiter().GetResult();
                }
                finally
                {
                    SetSynchronizationContext(before);
                }
            }
        }

        /// <summary>A store that notes every call made off the thread that made it: Unity's PlayerPrefs refuse those.</summary>
        private sealed class OneThreadStore : ISecureStore
        {
            private readonly MemorySecureStore _inner = new MemorySecureStore();
            private readonly int _thread = Environment.CurrentManagedThreadId;
            public readonly List<string> Off = new List<string>();

            private void Check(string call)
            {
                if (Environment.CurrentManagedThreadId != _thread) Off.Add(call);
            }

            public string Get(string key) { Check("Get"); return _inner.Get(key); }
            public void Set(string key, string value) { Check("Set"); _inner.Set(key, value); }
            public void Delete(string key) { Check("Delete"); _inner.Delete(key); }
        }

        /// <summary>Answers on the thread pool, a little later, as a real answer arrives.</summary>
        private sealed class Later : HttpMessageHandler
        {
            public Func<HttpRequestMessage, HttpResponseMessage> Answer;

            protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancel)
            {
                await Task.Delay(20, cancel).ConfigureAwait(false);
                return Answer(request);
            }
        }

        [Test]
        public void TheKeyIsKeptAndForgottenOnTheThreadThatSignedIn()
        {
            var later = new Later
            {
                Answer = r => r.RequestUri.AbsolutePath == "/v1/guests"
                    ? Json(HttpStatusCode.Created, "{\"playerId\":7,\"guestKey\":\"k-123\",\"displayName\":\"Guest 1234\"}")
                    : Json(HttpStatusCode.OK, "{\"token\":\"t-7\",\"playerId\":7,\"expiresInSeconds\":86400}"),
            };
            var store = new OneThreadStore();
            ApiResult<Session> made = new OneThread().Run(() => new AccountKeeper(new ApiClient(Three, later), store).SignIn());
            Assert.That(made.Ok, Is.True);
            Assert.That(store.Get(AccountKeeper.GuestKeyName), Is.EqualTo("k-123"));

            var refused = new Later { Answer = _ => Json(HttpStatusCode.Unauthorized, "{\"code\":\"invalid_credentials\"}") };
            ApiResult<Session> gone = new OneThread().Run(() => new AccountKeeper(new ApiClient(Three, refused), store).SignIn());
            Assert.That((gone.Ok, store.Get(AccountKeeper.GuestKeyName)), Is.EqualTo((false, (string)null)));
            Assert.That(store.Off, Is.Empty, "the key kept and forgotten on the caller's thread, which Unity's stores require");
        }

        [Test]
        public void AKeyRefusedIsForgottenAndNoNewGuestMadeUnasked()
        {
            var asked = new List<string>();
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    asked.Add(r.RequestUri.AbsolutePath);
                    return Json(HttpStatusCode.Unauthorized, "{\"code\":\"invalid_credentials\",\"message\":\"upgraded\"}");
                },
            };
            var store = new MemorySecureStore();
            store.Set(AccountKeeper.GuestKeyName, "k-old");
            ApiResult<Session> refused = new AccountKeeper(new ApiClient(Three, hosts), store).SignIn().Result;
            Assert.That((refused.Ok, refused.Status), Is.EqualTo((false, 401)));
            Assert.That(store.Get(AccountKeeper.GuestKeyName), Is.Null, "an upgraded guest's key: sign in by name from here");
            Assert.That(asked, Is.EqualTo(new[] { "/v1/sessions" }), "a player who upgraded is not made a stranger");

            var down = new Hosts();
            foreach (string h in new[] { "a.example.test", "b.example.test", "c.example.test" }) down.Down.Add(h);
            ApiResult<Session> firstOffline = new AccountKeeper(new ApiClient(Three, down), store).SignIn().Result;
            Assert.That((firstOffline.Unreachable, store.Get(AccountKeeper.GuestKeyName)), Is.EqualTo((true, (string)null)),
                "a first launch with no answer keeps nothing");
            store.Set(AccountKeeper.GuestKeyName, "k-kept");
            ApiResult<Session> unreachable = new AccountKeeper(new ApiClient(Three, down), store).SignIn().Result;
            Assert.That((unreachable.Unreachable, store.Get(AccountKeeper.GuestKeyName)), Is.EqualTo((true, "k-kept")),
                "no answer is not a refusal: the key is kept");
            store.Delete(AccountKeeper.GuestKeyName);
            Assert.That(store.Get(AccountKeeper.GuestKeyName), Is.Null);
        }

        [Test]
        public void APostThatTimesOutIsNotSentAgainElsewhereButAReadIs()
        {
            var asked = new List<string>();
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    asked.Add(r.Method + " " + r.RequestUri.Host);
                    if (r.RequestUri.Host == "a.example.test") throw new TaskCanceledException("too slow");
                    return r.Method == HttpMethod.Post
                        ? Json(HttpStatusCode.Created, "{\"playerId\":7,\"guestKey\":\"k-123\",\"displayName\":\"Guest 1234\"}")
                        : Json(HttpStatusCode.OK, "{\"offers\":[]}");
                },
            };
            ApiResult<Guest> made = new ApiClient(Three, hosts).CreateGuest().Result;
            Assert.That((made.Ok, made.Code, made.Unreachable), Is.EqualTo((false, "timeout", true)));
            Assert.That(asked, Is.EqualTo(new[] { "POST a.example.test" }),
                "the first may have made it: sent again elsewhere, a second guest would be made");

            asked.Clear();
            Assert.That(new ApiClient(Three, hosts).Shop().Result.Ok, Is.True);
            Assert.That(asked, Is.EqualTo(new[] { "GET a.example.test", "GET b.example.test" }), "a read is asked again elsewhere");
        }

        [Test]
        public void ASuccessThatIsNotJsonIsNotTakenForOne()
        {
            var hosts = new Hosts { Answer = _ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("<html>a proxy's page</html>") } };
            ApiResult<Session> session = new ApiClient(Three, hosts).Login("ada", "hunter2-hunter2").Result;
            Assert.That((session.Ok, session.Status, session.Code), Is.EqualTo((false, 200, "bad_response")),
                "not a session with no token, which the lobby would then be refused for, again and again");
            var empty = new Hosts { Answer = _ => new HttpResponseMessage(HttpStatusCode.NoContent) };
            Assert.That(new ApiClient(Three, empty).Logout("t").Result.Ok, Is.True, "an empty answer is still one");
        }

        [Test]
        public void NoPaymentProviderIsA503ToShowNoStore()
        {
            var hosts = new Hosts
            {
                Answer = _ => Json(HttpStatusCode.ServiceUnavailable, "{\"code\":\"payments_off\",\"message\":\"nothing is sold here\"}"),
            };
            ApiResult<List<Pack>> packs = new ApiClient(Three, hosts).Packs().Result;
            Assert.That((packs.Ok, packs.Status, packs.Code), Is.EqualTo((false, 503, "payments_off")));
        }

        [Test]
        public void EquipmentIsReadWornAndTakenOffByItsSlot()
        {
            string sent = null, method = null, path = null, auth = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    method = r.Method.Method;
                    path = r.RequestUri.AbsolutePath;
                    auth = r.Headers.Authorization?.ToString();
                    sent = r.Content?.ReadAsStringAsync().Result;
                    return Json(HttpStatusCode.OK, "{\"slots\":{\"barrel\":\"barrel_steel\",\"armor\":null,"
                        + "\"core\":null,\"treads\":null},\"bonus\":{\"bullet_damage\":8}}");
                },
            };
            var api = new ApiClient(Three, hosts);
            Loadout worn = api.Wear("tok", "barrel", "barrel_steel").Result.Value;
            Assert.That((method, path, auth), Is.EqualTo(("PUT", "/v1/equipment/barrel", "Bearer tok")));
            Assert.That(JsonValue.Parse(sent)["itemId"].AsString, Is.EqualTo("barrel_steel"));
            Assert.That(worn.Slots["barrel"], Is.EqualTo("barrel_steel"));
            Assert.That(worn.Slots["armor"], Is.Null, "an empty slot");
            Assert.That(worn.Slots, Has.Count.EqualTo(4));
            Assert.That(worn.Bonus["bullet_damage"], Is.EqualTo(8));

            api.Equipment("tok").Wait();
            Assert.That((method, path), Is.EqualTo(("GET", "/v1/equipment")));
            api.TakeOff("tok", "barrel").Wait();
            Assert.That((method, path), Is.EqualTo(("DELETE", "/v1/equipment/barrel")));
        }

        [Test]
        public void ABoostIsActivatedWithItsKeyAndTheRunningOnesAreRead()
        {
            string sent = null, method = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    method = r.Method.Method;
                    sent = r.Content?.ReadAsStringAsync().Result;
                    return Json(HttpStatusCode.OK, "{\"result\":\"activated\",\"boosts\":[{\"kind\":\"coins\","
                        + "\"itemId\":\"boost_coins_hour\",\"percent\":100,\"endsAt\":\"2026-10-15T13:00:00Z\"}]}");
                },
            };
            var api = new ApiClient(Three, hosts);
            string key = ApiClient.NewPurchaseKey();
            BoostsAnswer a = api.ActivateBoost("tok", "boost_coins_hour", key).Result.Value;
            Assert.That(method, Is.EqualTo("POST"));
            Assert.That((JsonValue.Parse(sent)["itemId"].AsString, JsonValue.Parse(sent)["key"].AsString),
                Is.EqualTo(("boost_coins_hour", key)));
            Assert.That(a.Result, Is.EqualTo("activated"));
            Assert.That((a.Boosts[0].Kind, a.Boosts[0].ItemId, a.Boosts[0].Percent, a.Boosts[0].EndsAt),
                Is.EqualTo(("coins", "boost_coins_hour", 100, "2026-10-15T13:00:00Z")));
            api.Boosts("tok").Wait();
            Assert.That(method, Is.EqualTo("GET"));
        }

        [Test]
        public void TeamCallsGoToTheirRoutesAndTheTeamIsRead()
        {
            string sent = null, method = null, path = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    method = r.Method.Method;
                    path = r.RequestUri.AbsolutePath;
                    sent = r.Content?.ReadAsStringAsync().Result;
                    return path == "/v1/team-invites" || (path.StartsWith("/v1/team-invites/") && sent.Contains("false"))
                        ? Json(HttpStatusCode.OK, "{\"invites\":[{\"teamId\":7,\"teamName\":\"Tanks\",\"expiresAt\":\"2026-10-08T12:00:00Z\"}]}")
                        : Json(HttpStatusCode.OK, "{\"id\":7,\"name\":\"Tanks\",\"members\":[{\"playerId\":1,\"name\":\"ada\","
                            + "\"role\":\"leader\"},{\"playerId\":2,\"name\":\"bob\",\"role\":\"vice_leader\"}],"
                            + "\"rating\":1234,\"wins\":5,\"losses\":3,\"draws\":2}");
                },
            };
            var api = new ApiClient(Three, hosts);
            TeamInfo made = api.CreateTeam("tok", "Tanks").Result.Value;
            Assert.That((method, path, JsonValue.Parse(sent)["name"].AsString), Is.EqualTo(("POST", "/v1/teams", "Tanks")));
            Assert.That((made.Id, made.Name, made.Members[1].PlayerId, made.Members[1].Role), Is.EqualTo((7L, "Tanks", 2L, "vice_leader")));
            Assert.That((made.Rating, made.Wins, made.Losses, made.Draws), Is.EqualTo((1234, 5, 3, 2)), "its record in team matches");
            api.InviteToTeam("tok", 2).Wait();
            Assert.That((path, JsonValue.Parse(sent)["playerId"].AsLong), Is.EqualTo(("/v1/teams/mine/invites", 2L)));
            List<TeamInvite> invites = api.TeamInvites("tok").Result.Value;
            Assert.That((method, invites[0].TeamId, invites[0].TeamName), Is.EqualTo(("GET", 7L, "Tanks")));
            api.AcceptTeamInvite("tok", 7).Wait();
            Assert.That((path, JsonValue.Parse(sent)["accept"].AsBool), Is.EqualTo(("/v1/team-invites/7", true)));
            api.DeclineTeamInvite("tok", 7).Wait();
            Assert.That(JsonValue.Parse(sent)["accept"].AsBool, Is.False);
            api.SetTeamRole("tok", 2, "vice_leader").Wait();
            Assert.That((path, JsonValue.Parse(sent)["role"].AsString), Is.EqualTo(("/v1/teams/mine/members/2/role", "vice_leader")));
            api.KickFromTeam("tok", 2).Wait();
            Assert.That((method, path), Is.EqualTo(("DELETE", "/v1/teams/mine/members/2")));
            api.HandOverTeam("tok", 2).Wait();
            Assert.That((path, JsonValue.Parse(sent)["playerId"].AsLong), Is.EqualTo(("/v1/teams/mine/leader", 2L)));
            api.LeaveTeam("tok").Wait();
            Assert.That((method, path), Is.EqualTo(("POST", "/v1/teams/mine/leave")));
            api.DisbandTeam("tok").Wait();
            Assert.That((method, path), Is.EqualTo(("DELETE", "/v1/teams/mine")));
            api.MyTeam("tok").Wait();
            Assert.That((method, path), Is.EqualTo(("GET", "/v1/teams/mine")));
            TeamInfo renamed = api.RenameTeam("tok", "Treads").Result.Value;
            Assert.That((method, path, JsonValue.Parse(sent)["name"].AsString), Is.EqualTo(("PUT", "/v1/teams/mine/name", "Treads")));
            Assert.That(renamed.Id, Is.EqualTo(7L), "the team, read as any team answer is");
        }

        [Test]
        public void ApplicationCallsGoToTheirRoutesAndTheirListsAreRead()
        {
            string sent = null, method = null, path = null, query = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    method = r.Method.Method;
                    path = r.RequestUri.AbsolutePath;
                    query = r.RequestUri.Query;
                    sent = r.Content?.ReadAsStringAsync().Result;
                    return path == "/v1/teams"
                        ? Json(HttpStatusCode.OK, "{\"teams\":[{\"id\":7,\"name\":\"Tanks\",\"members\":2,\"rating\":1250}]}")
                        : path == "/v1/team-applications"
                        ? Json(HttpStatusCode.OK, "{\"applications\":[{\"teamId\":7,\"teamName\":\"Tanks\",\"expiresAt\":\"2026-10-09T12:00:00Z\"}]}")
                        : path == "/v1/teams/mine/applications"
                        ? Json(HttpStatusCode.OK, "{\"applications\":[{\"playerId\":3,\"name\":\"cyd\",\"expiresAt\":\"2026-10-09T12:00:00Z\"}]}")
                        : path.StartsWith("/v1/teams/mine/applications/")
                        ? Json(HttpStatusCode.OK, "{\"id\":7,\"name\":\"Tanks\",\"members\":[],\"rating\":1250,\"wins\":0,\"losses\":0,\"draws\":0}")
                        : Json(HttpStatusCode.OK, "{}");
                },
            };
            var api = new ApiClient(Three, hosts);
            List<TeamFound> found = api.FindTeams("tok", "tan k").Result.Value;
            Assert.That((method, path, query), Is.EqualTo(("GET", "/v1/teams", "?name=tan%20k")));
            Assert.That((found[0].Id, found[0].Name, found[0].Members, found[0].Rating), Is.EqualTo((7L, "Tanks", 2, 1250)));
            Assert.That(api.ApplyToTeam("tok", 7).Result.Ok, Is.True);
            Assert.That((method, path), Is.EqualTo(("POST", "/v1/teams/7/applications")));
            api.WithdrawApplication("tok", 7).Wait();
            Assert.That((method, path), Is.EqualTo(("DELETE", "/v1/teams/7/applications")));
            List<TeamInvite> mine = api.MyApplications("tok").Result.Value;
            Assert.That((path, mine[0].TeamId, mine[0].TeamName), Is.EqualTo(("/v1/team-applications", 7L, "Tanks")));
            List<TeamApplicant> applicants = api.TeamApplications("tok").Result.Value;
            Assert.That((path, applicants[0].PlayerId, applicants[0].Name), Is.EqualTo(("/v1/teams/mine/applications", 3L, "cyd")));
            TeamInfo team = api.AnswerApplication("tok", 3, true).Result.Value;
            Assert.That((method, path, JsonValue.Parse(sent)["accept"].AsBool), Is.EqualTo(("POST", "/v1/teams/mine/applications/3", true)));
            Assert.That(team.Id, Is.EqualTo(7L));
        }

        [Test]
        public void AnItemIsRaisedALevelOnceAKey()
        {
            string sent = null, method = null, path = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    method = r.Method.Method;
                    path = r.RequestUri.AbsolutePath;
                    sent = r.Content?.ReadAsStringAsync().Result;
                    return Json(HttpStatusCode.OK, "{\"itemId\":\"barrel_steel\",\"level\":2,\"coins\":1500}");
                },
            };
            var api = new ApiClient(Three, hosts);
            ItemLevel raised = api.RaiseItemLevel("tok", "barrel_steel", "k1").Result.Value;
            Assert.That((method, path, JsonValue.Parse(sent)["key"].AsString),
                Is.EqualTo(("POST", "/v1/inventory/barrel_steel/level", "k1")));
            Assert.That((raised.ItemId, raised.Level, raised.Coins), Is.EqualTo(("barrel_steel", 2, 1500L)));
        }

        [Test]
        public void ARenameIsPutAndTheNameStoredIsRead()
        {
            string sent = null, method = null, path = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    method = r.Method.Method;
                    path = r.RequestUri.AbsolutePath;
                    sent = r.Content?.ReadAsStringAsync().Result;
                    return Json(HttpStatusCode.OK, "{\"playerId\":7,\"displayName\":\"Ada Two\"}");
                },
            };
            var api = new ApiClient(Three, hosts);
            Renamed renamed = api.Rename("tok", "Ada  Two").Result.Value;
            Assert.That((method, path, JsonValue.Parse(sent)["displayName"].AsString),
                Is.EqualTo(("PUT", "/v1/accounts/name", "Ada  Two")));
            Assert.That((renamed.PlayerId, renamed.DisplayName), Is.EqualTo((7L, "Ada Two")), "the name as the server stored it");
        }

        [Test]
        public void GuestCallsGoToTheirRoutesAndTheirAnswersAreRead()
        {
            string method = null, path = null, sent = null, token = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    method = r.Method.Method;
                    path = r.RequestUri.AbsolutePath;
                    token = r.Headers.Authorization?.Parameter;
                    sent = r.Content?.ReadAsStringAsync().Result;
                    if (path == "/v1/guests")
                        return Json(HttpStatusCode.Created, "{\"playerId\":5,\"guestKey\":\"k-123\",\"displayName\":\"Guest0042\"}");
                    if (path == "/v1/sessions")
                        return Json(HttpStatusCode.OK, "{\"token\":\"t-5\",\"playerId\":5,\"expiresInSeconds\":86400}");
                    return Json(HttpStatusCode.OK, "{}");
                },
            };
            var api = new ApiClient(Three, hosts);

            Guest guest = api.CreateGuest().Result.Value;
            Assert.That((method, path, token), Is.EqualTo(("POST", "/v1/guests", (string)null)));
            Assert.That((guest.PlayerId, guest.GuestKey, guest.DisplayName), Is.EqualTo((5L, "k-123", "Guest0042")));
            Session session = api.LoginGuest("k-123").Result.Value;
            Assert.That((path, JsonValue.Parse(sent)["guestKey"].AsString), Is.EqualTo(("/v1/sessions", "k-123")));
            Assert.That(JsonValue.Parse(sent)["username"].IsNull, "a key, not a name");
            Assert.That((session.Token, session.PlayerId), Is.EqualTo(("t-5", 5L)));
            Assert.That(api.UpgradeAccount("t-5", "adaline", "hunter2-hunter2", "Adaline").Result.Ok, Is.True);
            JsonValue body = JsonValue.Parse(sent);
            Assert.That((method, path, token), Is.EqualTo(("POST", "/v1/accounts/upgrade", "t-5")));
            Assert.That((body["username"].AsString, body["password"].AsString, body["displayName"].AsString),
                Is.EqualTo(("adaline", "hunter2-hunter2", "Adaline")));
            api.UpgradeAccount("t-5", "bobby", "hunter2-hunter2", null).Wait();
            Assert.That(JsonValue.Parse(sent)["displayName"].IsNull, "kept when none is given");
        }

        [Test]
        public void AchievementsAndTheirProgress()
        {
            string path = null, token = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    path = r.RequestUri.AbsolutePath;
                    token = r.Headers.Authorization?.Parameter;
                    return Json(HttpStatusCode.OK, "{\"achievements\":[{\"id\":\"kills_100\",\"stat\":\"kills\",\"threshold\":100,"
                        + "\"gems\":10,\"progress\":37,\"reached\":false},{\"id\":\"matches_50\",\"stat\":\"matches\",\"threshold\":50,"
                        + "\"gems\":10,\"progress\":51,\"reached\":true}]}");
                },
            };
            List<AchievementInfo> all = new ApiClient(Three, hosts).Achievements("tok").Result.Value;
            Assert.That((path, token), Is.EqualTo(("/v1/achievements", "tok")));
            Assert.That((all[0].Id, all[0].Stat, all[0].Threshold, all[0].Gems, all[0].Progress, all[0].Reached),
                Is.EqualTo(("kills_100", "kills", 100L, 10, 37L, false)));
            Assert.That((all[1].Id, all[1].Reached), Is.EqualTo(("matches_50", true)));
        }

        [Test]
        public void TodaysGoals()
        {
            string path = null, token = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    path = r.RequestUri.AbsolutePath;
                    token = r.Headers.Authorization?.Parameter;
                    return Json(HttpStatusCode.OK, "{\"day\":\"2026-10-04\",\"resetsAt\":\"2026-10-05T00:00:00Z\",\"goals\":["
                        + "{\"id\":\"kills_10\",\"kind\":\"kills\",\"target\":10,\"coins\":100,\"progress\":4,\"done\":false},"
                        + "{\"id\":\"wins_1\",\"kind\":\"wins\",\"target\":1,\"coins\":150,\"progress\":1,\"done\":true}],"
                        + "\"setGems\":3,\"setDone\":false}");
                },
            };
            GoalsInfo today = new ApiClient(Three, hosts).Goals("tok").Result.Value;
            Assert.That((path, token), Is.EqualTo(("/v1/goals", "tok")));
            Assert.That((today.Day, today.ResetsAt, today.SetGems, today.SetDone), Is.EqualTo(("2026-10-04", "2026-10-05T00:00:00Z", 3, false)));
            Assert.That((today.Goals[0].Id, today.Goals[0].Kind, today.Goals[0].Target, today.Goals[0].Coins, today.Goals[0].Progress,
                today.Goals[0].Done), Is.EqualTo(("kills_10", "kills", 10L, 100, 4L, false)));
            Assert.That((today.Goals[1].Id, today.Goals[1].Done), Is.EqualTo(("wins_1", true)));
        }

        [Test]
        public void TheBoardOfTeamsNamesTeams()
        {
            var hosts = new Hosts
            {
                Answer = r => Json(HttpStatusCode.OK, "{\"board\":\"teams\",\"entries\":[{\"rank\":1,\"teamId\":42,"
                    + "\"name\":\"Alpha\",\"score\":1600}]}"),
            };
            List<RankRow> rows = new ApiClient(Three, hosts).Leaderboard("teams", 10).Result.Value;
            Assert.That((rows[0].Rank, rows[0].TeamId, rows[0].PlayerId, rows[0].Name, rows[0].Score),
                Is.EqualTo((1L, 42L, 0L, "Alpha", 1600L)), "a team's row: its team, no player");
        }

        [Test]
        public void SeasonsAndAPastSeasonsBoard()
        {
            string path = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    path = r.RequestUri.PathAndQuery;
                    if (r.RequestUri.AbsolutePath == "/v1/seasons")
                        return Json(HttpStatusCode.OK, "{\"current\":{\"id\":2,\"startsAt\":\"2026-11-01T00:00:00Z\",\"endsAt\":\"2027-01-01T00:00:00Z\"},"
                            + "\"past\":[{\"id\":1,\"startsAt\":\"2026-10-03T00:00:00Z\",\"endsAt\":\"2026-11-01T00:00:00Z\"}]}");
                    return Json(HttpStatusCode.OK, "{\"board\":\"duel\",\"entries\":[{\"rank\":1,\"playerId\":7,\"name\":\"ada\",\"score\":1600}]}");
                },
            };
            var api = new ApiClient(Three, hosts);
            SeasonsInfo seasons = api.Seasons().Result.Value;
            Assert.That((seasons.Current.Id, seasons.Current.EndsAt, seasons.Past.Count, seasons.Past[0].Id),
                Is.EqualTo((2, "2027-01-01T00:00:00Z", 1, 1)));
            List<RankRow> rows = api.Leaderboard("duel", 10, season: 1).Result.Value;
            Assert.That(path, Is.EqualTo("/v1/leaderboards/duel?limit=10&season=1"));
            Assert.That((rows[0].Rank, rows[0].Name, rows[0].Score), Is.EqualTo((1L, "ada", 1600L)));
            api.AroundMe("duel", "tok", season: 1).Wait();
            Assert.That(path, Is.EqualTo("/v1/leaderboards/duel/me?season=1"));
            api.Leaderboard("duel", 10).Wait();
            Assert.That(path, Is.EqualTo("/v1/leaderboards/duel?limit=10"), "without a season, the board as it stands");
        }

        [Test]
        public void SocialCallsGoToTheirRoutesAndTheirAnswersAreRead()
        {
            string method = null, path = null, sent = null, token = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    method = r.Method.Method;
                    path = r.RequestUri.AbsolutePath;
                    token = r.Headers.Authorization?.Parameter;
                    sent = r.Content?.ReadAsStringAsync().Result;
                    if (path == "/v1/friends" && method == "GET")
                        return Json(HttpStatusCode.OK, "{\"friends\":[{\"playerId\":2,\"name\":\"bob\",\"online\":true}],"
                            + "\"requests\":[{\"playerId\":3,\"name\":\"cyd\",\"expiresAt\":\"2026-10-08T12:00:00Z\"}],\"asked\":[]}");
                    if (path == "/v1/friends") return Json(HttpStatusCode.OK, "{\"state\":\"friends\"}");
                    if (path == "/v1/blocks" && method == "GET")
                        return Json(HttpStatusCode.OK, "{\"blocked\":[{\"playerId\":4,\"name\":\"dee\"}]}");
                    if (path == "/v1/inbox")
                        return Json(HttpStatusCode.OK, "{\"items\":[{\"id\":9,\"kind\":\"team_invite\",\"ref\":7,"
                            + "\"at\":\"2026-10-01T12:00:00Z\",\"read\":false}]}");
                    return Json(HttpStatusCode.OK, "{}");
                },
            };
            var api = new ApiClient(Three, hosts);

            FriendsInfo friends = api.Friends("tok").Result.Value;
            Assert.That((method, path, token), Is.EqualTo(("GET", "/v1/friends", "tok")));
            Assert.That((friends.Friends[0].PlayerId, friends.Friends[0].Name, friends.Friends[0].Online), Is.EqualTo((2L, "bob", true)));
            Assert.That((friends.Requests[0].PlayerId, friends.Requests[0].ExpiresAt), Is.EqualTo((3L, "2026-10-08T12:00:00Z")));
            Assert.That(friends.Asked, Is.Empty);
            Assert.That(api.AskFriend("tok", 3).Result.Value, Is.EqualTo("friends"));
            Assert.That((method, path, JsonValue.Parse(sent)["playerId"].AsLong), Is.EqualTo(("POST", "/v1/friends", 3L)));
            api.RemoveFriend("tok", 2).Wait();
            Assert.That((method, path), Is.EqualTo(("DELETE", "/v1/friends/2")));
            api.DropFriendRequest("tok", 3).Wait();
            Assert.That((method, path), Is.EqualTo(("DELETE", "/v1/friend-requests/3")));

            List<PlayerRef> blocked = api.Blocks("tok").Result.Value;
            Assert.That((method, path, blocked[0].PlayerId, blocked[0].Name), Is.EqualTo(("GET", "/v1/blocks", 4L, "dee")));
            api.Block("tok", 5).Wait();
            Assert.That((method, path, JsonValue.Parse(sent)["playerId"].AsLong), Is.EqualTo(("POST", "/v1/blocks", 5L)));
            api.Unblock("tok", 4).Wait();
            Assert.That((method, path), Is.EqualTo(("DELETE", "/v1/blocks/4")));

            List<InboxItem> items = api.Inbox("tok").Result.Value;
            Assert.That((method, path), Is.EqualTo(("GET", "/v1/inbox")));
            Assert.That((items[0].Id, items[0].Kind, items[0].Ref, items[0].At, items[0].Read),
                Is.EqualTo((9L, "team_invite", 7L, "2026-10-01T12:00:00Z", false)));
            api.ReadInbox("tok", 9).Wait();
            Assert.That((method, path, JsonValue.Parse(sent)["upTo"].AsLong), Is.EqualTo(("POST", "/v1/inbox/read", 9L)));
        }

        [Test]
        public void ATeamsTournamentIsReadWithItsTeamsRostersAndBracket()
        {
            const string cup = "{\"id\":8,\"name\":\"Team cup\",\"mode\":\"teams\",\"state\":\"running\",\"maxEntries\":4,"
                + "\"registrationEnds\":\"2026-10-01T12:00:00Z\",\"startsAt\":\"2026-10-01T12:01:00Z\",\"roundMinutes\":2,"
                + "\"currentRound\":1,\"prizes\":[300,200,100],"
                + "\"entries\":[{\"teamId\":40,\"name\":\"Tanks\",\"seed\":1,\"roster\":[{\"playerId\":1,\"name\":\"ada\"},"
                + "{\"playerId\":2,\"name\":\"bob\"},{\"playerId\":3,\"name\":\"cyd\"}]},"
                + "{\"teamId\":41,\"name\":\"Rams\",\"seed\":2,\"roster\":[{\"playerId\":4,\"name\":\"dee\"}]}],"
                + "\"matches\":[{\"round\":1,\"slot\":0,\"state\":\"done\",\"teamA\":40,\"teamB\":41,\"winnerTeam\":41}]}";
            var api = new ApiClient(Three, new Hosts { Answer = r => Json(HttpStatusCode.OK, cup) });
            TournamentInfo t = api.Tournament(8).Result.Value;
            Assert.That(t.Mode, Is.EqualTo("teams"));
            Assert.That((t.Entries[0].TeamId, t.Entries[0].PlayerId, t.Entries[0].Name, t.Entries[0].Seed),
                Is.EqualTo((40L, 0L, "Tanks", 1)), "a team's entry");
            Assert.That(t.Entries[0].Roster.ConvertAll(r => r.PlayerId), Is.EqualTo(new List<long> { 1, 2, 3 }));
            Assert.That(t.Entries[0].Roster[2].Name, Is.EqualTo("cyd"));
            BracketMatch m = t.Matches[0];
            Assert.That((m.TeamA, m.TeamB, m.WinnerTeam, m.PlayerA, m.Winner),
                Is.EqualTo(((long?)40, (long?)41, (long?)41, (long?)null, (long?)null)), "teams, not players");
        }

        [Test]
        public void ARoundRobinsFormatAndStandingsAreRead()
        {
            const string league = "{\"id\":8,\"name\":\"League\",\"mode\":\"duel\",\"format\":\"round_robin\",\"state\":\"running\","
                + "\"maxEntries\":4,\"registrationEnds\":\"2026-10-01T12:00:00Z\",\"startsAt\":\"2026-10-01T12:01:00Z\","
                + "\"roundMinutes\":2,\"currentRound\":2,\"prizes\":[300,200,100],\"entries\":[],\"matches\":[],"
                + "\"standings\":[{\"playerId\":3,\"name\":\"cyd\",\"points\":4,\"wins\":1,\"draws\":1,\"losses\":0},"
                + "{\"playerId\":1,\"name\":\"ada\",\"points\":1,\"wins\":0,\"draws\":1,\"losses\":1}]}";
            var hosts = new Hosts { Answer = r => r.RequestUri.AbsolutePath == "/v1/tournaments/8"
                ? Json(HttpStatusCode.OK, league)
                : Json(HttpStatusCode.OK, league.Replace("\"format\":\"round_robin\",", "").Replace("\"id\":8", "\"id\":9")) };
            var api = new ApiClient(Three, hosts);
            TournamentInfo t = api.Tournament(8).Result.Value;
            Assert.That(t.Format, Is.EqualTo("round_robin"));
            Assert.That(t.Standings.Count, Is.EqualTo(2));
            TournamentStanding top = t.Standings[0];
            Assert.That((top.PlayerId, top.Name, top.Points, top.Wins, top.Draws, top.Losses), Is.EqualTo((3L, "cyd", 4, 1, 1, 0)));
            Assert.That(api.Tournament(9).Result.Value.Format, Is.EqualTo("elimination"), "a server before formats");
        }

        [Test]
        public void TournamentCallsGoToTheirRoutesAndTheBracketIsRead()
        {
            string method = null, path = null, token = null;
            const string cup = "{\"id\":7,\"name\":\"Cup\",\"state\":\"running\",\"maxEntries\":4,"
                + "\"registrationEnds\":\"2026-10-01T12:00:00Z\",\"startsAt\":\"2026-10-01T12:01:00Z\",\"roundMinutes\":2,"
                + "\"currentRound\":1,\"prizes\":[300,200,100],"
                + "\"entries\":[{\"playerId\":1,\"name\":\"ada\",\"seed\":1},{\"playerId\":2,\"name\":\"bob\",\"seed\":2},"
                + "{\"playerId\":3,\"name\":\"cyd\",\"seed\":3}],"
                + "\"matches\":[{\"round\":1,\"slot\":0,\"state\":\"done\",\"playerA\":1,\"winner\":1},"
                + "{\"round\":1,\"slot\":1,\"state\":\"ready\",\"playerA\":2,\"playerB\":3},"
                + "{\"round\":2,\"slot\":0,\"state\":\"pending\",\"playerA\":1}]}";
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    method = r.Method.Method;
                    path = r.RequestUri.AbsolutePath;
                    token = r.Headers.Authorization?.Parameter;
                    if (path == "/v1/tournaments") return Json(HttpStatusCode.OK, "{\"tournaments\":[" + cup + "]}");
                    if (path.EndsWith("/match"))
                        return Json(HttpStatusCode.OK, "{\"tournamentId\":7,\"round\":1,\"arenaHost\":\"10.0.0.5\","
                            + "\"arenaPort\":9011,\"ticketId\":\"t1\",\"tls\":true,\"mode\":\"duel\"}");
                    return Json(HttpStatusCode.OK, cup);
                },
            };
            var api = new ApiClient(Three, hosts);

            List<TournamentInfo> open = api.Tournaments().Result.Value;
            Assert.That((method, path, token, open.Count, open[0].Id), Is.EqualTo(("GET", "/v1/tournaments", (string)null, 1, 7L)));
            TournamentInfo t = api.Tournament(7).Result.Value;
            Assert.That((method, path), Is.EqualTo(("GET", "/v1/tournaments/7")));
            Assert.That((t.Name, t.State, t.MaxEntries, t.RoundMinutes, t.CurrentRound),
                Is.EqualTo(("Cup", "running", 4, 2, 1)));
            Assert.That((t.RegistrationEnds, t.StartsAt), Is.EqualTo(("2026-10-01T12:00:00Z", "2026-10-01T12:01:00Z")));
            Assert.That(t.Prizes, Is.EqualTo(new long[] { 300, 200, 100 }));
            Assert.That((t.Entries.Count, t.Entries[2].PlayerId, t.Entries[2].Name, t.Entries[2].Seed), Is.EqualTo((3, 3L, "cyd", 3)));
            BracketMatch bye = t.Matches[0], ready = t.Matches[1], final = t.Matches[2];
            Assert.That((bye.Round, bye.Slot, bye.State, bye.PlayerA, bye.PlayerB, bye.Winner),
                Is.EqualTo((1, 0, "done", (long?)1, (long?)null, (long?)1)), "a bye: nobody against seed 1");
            Assert.That((ready.Slot, ready.State, ready.PlayerA, ready.PlayerB, ready.Winner),
                Is.EqualTo((1, "ready", (long?)2, (long?)3, (long?)null)));
            Assert.That((final.Round, final.PlayerA, final.PlayerB), Is.EqualTo((2, (long?)1, (long?)null)));

            api.EnterTournament("tok", 7).Wait();
            Assert.That((method, path, token), Is.EqualTo(("POST", "/v1/tournaments/7/entries", "tok")));
            api.WithdrawFromTournament("tok", 7).Wait();
            Assert.That((method, path, token), Is.EqualTo(("DELETE", "/v1/tournaments/7/entries", "tok")));
            TournamentGrant grant = api.TournamentMatch("tok", 7).Result.Value;
            Assert.That((method, path, token), Is.EqualTo(("GET", "/v1/tournaments/7/match", "tok")));
            Assert.That((grant.TournamentId, grant.Round, grant.ArenaHost, grant.ArenaPort, grant.TicketId, grant.Tls, grant.Mode),
                Is.EqualTo((7L, 1, "10.0.0.5", 9011, "t1", true, "duel")));
        }

        [Test]
        public void TheQueueIsJoinedWithItsModeAndAMatchMadeMeanwhileIsReadWithItsGrant()
        {
            string sent = null, method = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    method = r.Method.Method;
                    sent = r.Content?.ReadAsStringAsync().Result;
                    return r.Method == HttpMethod.Get
                        ? Json(HttpStatusCode.OK, "{\"state\":\"matched\",\"mode\":\"duel\",\"waitedSeconds\":12,"
                            + "\"grant\":{\"arenaHost\":\"10.0.0.7\",\"arenaPort\":9001,\"ticketId\":\"t\",\"tls\":true,\"mode\":\"duel\"}}")
                        : Json(HttpStatusCode.OK, "{\"state\":\"queued\",\"mode\":\"duel\",\"waitedSeconds\":0}");
                },
            };
            var api = new ApiClient(Three, hosts);
            ApiResult<QueueStatus> joined = api.JoinQueue("tok", "duel").Result;
            Assert.That((method, JsonValue.Parse(sent)["mode"].AsString), Is.EqualTo(("POST", "duel")));
            Assert.That((joined.Value.State, joined.Value.Grant), Is.EqualTo(("queued", (MatchGrant)null)));

            QueueStatus status = api.Queue("tok").Result.Value;
            Assert.That((status.State, status.WaitedSeconds), Is.EqualTo(("matched", 12L)));
            Assert.That((status.Grant.ArenaHost, status.Grant.ArenaPort, status.Grant.Tls, status.Grant.Mode),
                Is.EqualTo(("10.0.0.7", 9001, true, "duel")));

            api.LeaveQueue("tok").Wait();
            Assert.That(method, Is.EqualTo("DELETE"));
        }

        [Test]
        public void ASandboxIsOpenedWithAPostAndItsGrantRead()
        {
            string path = null, method = null, auth = null, sent = null;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    (path, method) = (r.RequestUri.AbsolutePath, r.Method.Method);
                    auth = r.Headers.Authorization?.ToString();
                    sent = r.Content?.ReadAsStringAsync().Result;
                    return Json(HttpStatusCode.OK,
                        "{\"arenaHost\":\"10.0.0.7\",\"arenaPort\":9001,\"ticketId\":\"t\",\"tls\":false,\"mode\":\"sandbox\"}");
                },
            };
            MatchGrant g = new ApiClient(Three, hosts).OpenSandbox("tok").Result.Value;
            Assert.That((method, path, auth, sent), Is.EqualTo(("POST", "/v1/sandbox", "Bearer tok", (string)null)));
            Assert.That((g.ArenaHost, g.ArenaPort, g.TicketId, g.Tls, g.Mode), Is.EqualTo(("10.0.0.7", 9001, "t", false, "sandbox")));

            hosts.Answer = r => Json((HttpStatusCode)409, "{\"code\":\"in_party\",\"message\":\"the party's leader opens it\"}");
            ApiResult<MatchGrant> refused = new ApiClient(Three, hosts).OpenSandbox("tok").Result;
            Assert.That((refused.Ok, refused.Status, refused.Code), Is.EqualTo((false, 409, "in_party")));
        }

        [Test]
        public void AMatchAskedAboutIsReadWithItsIdAndTheSecondsLeft()
        {
            var hosts = new Hosts
            {
                Answer = r => Json(HttpStatusCode.OK,
                    "{\"state\":\"confirming\",\"mode\":\"duel\",\"waitedSeconds\":14,\"matchUid\":\"01JC\",\"secondsLeft\":7}"),
            };
            QueueStatus q = new ApiClient(Three, hosts).Queue("tok").Result.Value;
            Assert.That((q.State, q.MatchUid, q.SecondsLeft, q.Grant), Is.EqualTo(("confirming", "01JC", 7L, (MatchGrant)null)));
        }

        [Test]
        public void APartyIsReadWithItsMembersInOrderAndNoneIsNull()
        {
            string path = null, auth = null;
            bool none = false;
            var hosts = new Hosts
            {
                Answer = r =>
                {
                    path = r.RequestUri.AbsolutePath;
                    auth = r.Headers.Authorization?.ToString();
                    return none
                        ? Json(HttpStatusCode.OK, "{\"partyId\":null,\"leader\":0,\"members\":[]}")
                        : Json(HttpStatusCode.OK, "{\"partyId\":\"01JC\",\"leader\":7,\"members\":["
                            + "{\"playerId\":7,\"name\":\"Ada\"},{\"playerId\":9,\"name\":\"Bo\"}]}");
                },
            };
            var api = new ApiClient(Three, hosts);
            ApiResult<PartyInfo> party = api.Party("tok").Result;
            Assert.That((path, auth), Is.EqualTo(("/v1/party", "Bearer tok")));
            Assert.That((party.Ok, party.Value.PartyId, party.Value.Leader), Is.EqualTo((true, "01JC", 7L)));
            Assert.That(party.Value.Members.Select(m => (m.PlayerId, m.Name)),
                Is.EqualTo(new[] { (7L, "Ada"), (9L, "Bo") }));

            none = true;
            ApiResult<PartyInfo> alone = api.Party("tok").Result;
            Assert.That((alone.Ok, alone.Value), Is.EqualTo((true, (PartyInfo)null)), "in no party: null, not an empty one");
        }
    }
}
