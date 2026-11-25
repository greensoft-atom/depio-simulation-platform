using UnityEngine;

namespace Backend.Client.Simple.Examples
{
    /// <summary>
    /// The lobby: log in (a guest, as HttpExample), connect, and act on what the lobby pushes. A seat in the public
    /// arena is asked for at once; a duel goes through the queue, where the lobby asks to accept and then sends the
    /// grant. A grant goes to ArenaExample. A connection lost is made again after a pause; one replaced by another
    /// device, or revoked, is not.
    /// </summary>
    public class LobbyExample : MonoBehaviour
    {
        [Tooltip("http://<address>: the site's 80, or a backend run plain (PLAIN=1); this client has no TLS")]
        public string apiUrl = "http://127.0.0.1";

        [Tooltip("ws://<address>/lobby: the same backend")]
        public string lobbyUrl = "ws://127.0.0.1/lobby";

        [Tooltip("Where a grant goes; none, it is only logged")]
        public ArenaExample arena;

        [Tooltip("Ask for a seat in the public arena as soon as the lobby is connected")]
        public bool playOnConnect = true;

        private BackendHttp _http;
        private LobbyClient _lobby;
        private string _token;
        private bool _stayClosed;
        private int _retries;
        private float _reconnectAt = -1;

        private void Start()
        {
            _http = new BackendHttp(apiUrl);
            _lobby = new LobbyClient(lobbyUrl);
            _lobby.OnAuthenticated = OnAuthenticated;
            _lobby.OnPush = OnPush;
            _lobby.OnClosed = OnClosed;
            LogIn();
        }

        // Every callback of LobbyClient runs in here, on the main thread.
        private void Update()
        {
            _lobby?.Update();
            if (_reconnectAt >= 0 && Time.unscaledTime >= _reconnectAt)
            {
                _reconnectAt = -1;
                _lobby.Connect(_token);
            }
        }

        private void OnDestroy() => _lobby?.Close();

        // A guest, as HttpExample logs in, then the lobby with its token. Unity runs the code after each await on the main thread.
        private async void LogIn()
        {
            string key = PlayerPrefs.GetString("guestKey", "");
            if (key == "")
            {
                HttpAnswer made = await _http.PostAsync("/v1/guests", null);
                if (!made.Ok) { Debug.LogError("No guest made: " + made.Error); return; }
                key = JsonUtility.FromJson<Guest>(made.Body).guestKey;
                PlayerPrefs.SetString("guestKey", key);
                PlayerPrefs.Save();
            }
            HttpAnswer answer = await _http.PostAsync("/v1/sessions", JsonUtility.ToJson(new GuestLogin { guestKey = key }));
            if (!answer.Ok) { Debug.LogError("Not logged in: " + answer.Error); return; }
            _token = JsonUtility.FromJson<Session>(answer.Body).token;
            _lobby.Connect(_token);
        }

        private void OnAuthenticated(long playerId)
        {
            Debug.Log("In the lobby as player " + playerId);
            _retries = 0;
            if (playOnConnect) PlayPublicArena();
        }

        /// <summary>A seat in the public arena: the reply is the grant.</summary>
        public void PlayPublicArena()
        {
            _lobby.Send("match.request", null, reply =>
            {
                if (reply.IsError) Debug.LogWarning("No seat: " + reply.ErrorCode);
                else StartMatch(JsonUtility.FromJson<Grant>(reply.Data));
            });
        }

        /// <summary>A duel: queued now; evt.match.ready, then evt.match.found follow as pushes.</summary>
        public void QueueDuel()
        {
            _lobby.Send("queue.join", JsonUtility.ToJson(new QueueRequest { mode = "duel" }), reply =>
                Debug.Log(reply.IsError ? "Not queued: " + reply.ErrorCode : "Queued for a duel"));
        }

        public void LeaveQueue() => _lobby.Send("queue.leave", null);

        private void OnPush(LobbyMessage push)
        {
            switch (push.Type)
            {
                case "evt.match.ready":                     // the matcher asks: accept within the seconds given
                    MatchReady ready = JsonUtility.FromJson<MatchReady>(push.Data);
                    _lobby.Send("match.accept", JsonUtility.ToJson(new MatchAnswer { matchUid = ready.matchUid }));
                    break;
                case "evt.match.found":                     // everyone accepted: the grant
                case "evt.tournament.match":
                    StartMatch(JsonUtility.FromJson<Grant>(push.Data));
                    break;
                case "evt.notice":
                    Debug.Log("Notice: " + JsonUtility.FromJson<Notice>(push.Data).text);
                    break;
                case "evt.session.replaced":                // this player connected again elsewhere
                case "evt.session.revoked":                 // banned, suspended or deleted
                    _stayClosed = true;
                    Debug.LogWarning("The lobby ends this connection: " + push.Type + " " + push.Data);
                    break;
                case "evt.resync":                          // pushes were lost: fetch what matters again
                    FetchQueue();
                    break;
                default:                                    // evt.party.*, evt.friend.*, evt.rewards, evt.inbox...
                    Debug.Log("Pushed: " + push.Text);
                    break;
            }
        }

        private async void FetchQueue()
        {
            HttpAnswer answer = await _http.GetAsync("/v1/queue");
            Debug.Log("The queue, fetched again: " + answer.Body);
        }

        private void OnClosed(string why)
        {
            Debug.Log("The lobby closed: " + why);
            if (_stayClosed || why == "closed by the client") return;
            if (why.StartsWith("invalid_session"))
            {
                LogIn();                                    // the session ended: a new token, then connect
                return;
            }
            _reconnectAt = Time.unscaledTime + Mathf.Min(30, 2 << _retries++);   // 2, 4, 8, 16, 30 s
        }

        private void StartMatch(Grant grant)
        {
            Debug.Log("A seat at " + grant.arenaHost + ":" + grant.arenaPort);
            if (arena != null) arena.Play(grant, apiUrl);
        }
    }
}
