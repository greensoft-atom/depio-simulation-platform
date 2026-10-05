using System;
using System.Threading.Tasks;
using Backend.Client.Core;
using UnityEngine;

namespace Backend.Client.Unity
{
    /// <summary>
    /// The one object that owns the core's objects (docs 08 §8): the account, the lobby and the match. Update pumps
    /// them on the main thread, the world's only writer (08 §2), and builds the scene the frame draws; the app's
    /// pause is the match's lifecycle (02 §10). Everything it does is the core's; this is the wiring.
    /// </summary>
    public sealed class BackendClient : MonoBehaviour
    {
        [Tooltip("The platform API's endpoints, tried in turn, as https://api.example.com")]
        [SerializeField] private string[] apiEndpoints = Array.Empty<string>();
        [Tooltip("The lobby's WebSocket, as wss://api.example.com/lobby")]
        [SerializeField] private string lobbyUrl = "";

        public ApiClient Api { get; private set; }
        public Session Session { get; private set; }
        public LobbyClient Lobby { get; private set; }
        public MatchConnection Match { get; private set; }
        /// <summary>How the last match ended, until the next starts.</summary>
        public MatchEnd? LastEnd { get; private set; }
        /// <summary>What went wrong last, for a screen to say: a refusal, nothing answering.</summary>
        public string Problem { get; private set; }
        public bool SigningIn => _signingIn != null;
        /// <summary>What this frame draws, built in Update; read in LateUpdate.</summary>
        public Scene Scene { get; } = new Scene();

        private readonly IClock _clock = new SystemClock();
        private ISecureStore _store;
        private Task<ApiResult<Session>> _signingIn;
        private ClientWorld _world;
        private RenderClock _render = new RenderClock();
        private long _applied = -1;

        private void Awake()
        {
            DontDestroyOnLoad(this);            // its object and everything under it, as it is a root's (08 §8)
            Api = new ApiClient(apiEndpoints);
            _store = SecureStores.ForThisDevice();
        }

        /// <summary>Signs in: the guest this device keeps, or a new one on its first launch (AccountKeeper).</summary>
        public void SignIn()
        {
            if (_signingIn == null) _signingIn = new AccountKeeper(Api, _store).SignIn();
        }

        /// <summary>A seat in the public arena.</summary>
        public void PlayNow() => Lobby?.RequestMatch();

        /// <summary>A queued mode: "duel", "tvt", "rffa", "coop", ...</summary>
        public void Queue(string mode) => Lobby?.JoinQueue(mode);

        private void Update()
        {
            if (_signingIn != null && _signingIn.IsCompleted)
            {
                Task<ApiResult<Session>> done = _signingIn;
                _signingIn = null;
                // A sign-in that threw is said, not read: .Result would throw every frame from here.
                ApiResult<Session> signedIn = done.Status == TaskStatus.RanToCompletion ? done.Result : null;
                if (signedIn == null)
                {
                    Problem = "signing in failed: " + (done.Exception?.GetBaseException().Message ?? "cancelled");
                }
                else if (signedIn.Ok)
                {
                    Session = signedIn.Value;
                    Problem = null;
                    Lobby?.Dispose();
                    Lobby = new LobbyClient(new Uri(lobbyUrl), _clock);
                    Lobby.Connect(Session.Token);
                }
                else
                {
                    Problem = signedIn.ToString();
                }
            }
            Lobby?.Poll();
            if (Lobby != null && Lobby.State == LobbyState.InvalidSession)
            {
                // Expired or revoked: signed out, so a screen offers to sign in again (08 §5).
                Problem = "the session ended: sign in again";
                Lobby.Dispose();
                Lobby = null;
                Session = null;
            }
            // A grant to join: a match found, or a seat asked for. Taken, so the lobby's queue is "none" again
            // for the next match once this one ends.
            if (Match == null && Lobby != null && Lobby.Grant != null)
            {
                MatchGrant grant = Lobby.TakeGrant();
                LastEnd = null;
                Match = new MatchConnection(new MatchSettings { Host = grant.ArenaHost, Port = grant.ArenaPort, Tls = grant.Tls }, _clock);
                Match.Join(grant.TicketId);
            }
            if (Match == null) return;

            Match.Poll();
            if (!ReferenceEquals(Match.World, _world))
            {
                _world = Match.World;           // a join or a resume: a world of its own, and its clock
                _render = new RenderClock();
                _applied = -1;
            }
            if (Match.SnapshotsApplied != _applied)
            {
                _applied = Match.SnapshotsApplied;
                _render.Frame(Match.World.ServerTick, _clock.NowMs);
            }
            double tick = _render.RenderTick(_clock.NowMs);
            if (!double.IsNaN(tick))
            {
                OwnTank own = Match.OwnTank;
                float x = 0, y = 0;
                if (own.Predicting) own.DrawPosition(_clock.NowMs, out x, out y);
                Scene.Build(Match.World, tick, own.Predicting, x, y, own.Angle);
            }
            if (Match.State == MatchState.Ended)
            {
                LastEnd = Match.End;
                Match.Dispose();
                Match = null;
            }
        }

        private void OnApplicationPause(bool paused) => Match?.SetBackgrounded(paused);

        private void OnDestroy()
        {
            Match?.Dispose();
            Lobby?.Dispose();
        }
    }
}
