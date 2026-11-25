using UnityEngine;

namespace Backend.Client.Simple.Examples
{
    /// <summary>
    /// A match, from a grant (LobbyExample hands it over): joined; the player's input sent ten times a second; each
    /// snapshot handed on; the app's pause told to the arena; a lost connection resumed; a kick read; left on request.
    /// Set moveMask, aimRadians and fire from your controls. Without TLS: a grant of the site's, which asks for TLS, goes
    /// through the site's 80 by its arena's name (D-115); one from a backend run plain, straight to the arena.
    /// </summary>
    public class ArenaExample : MonoBehaviour
    {
        // What the controls set: any of ArenaMessages.MoveUp, MoveDown, MoveLeft, MoveRight; the aim in radians,
        // 0 along +x; the trigger held.
        [HideInInspector] public int moveMask;
        [HideInInspector] public float aimRadians;
        [HideInInspector] public bool fire;

        private ArenaClient _arena;
        private float _nextInputAt, _resumeAt = -1, _logAt;
        private int _resumes, _snapshots;

        /// <param name="apiUrl">the API's address, http://..., which a grant of the site's is reached through</param>
        public void Play(Grant grant, string apiUrl)
        {
            _arena?.Close();
            _arena = new ArenaClient();
            _arena.OnWelcome = OnWelcome;
            _arena.OnSnapshot = OnSnapshot;
            _arena.OnPong = ms => Debug.Log("Round trip " + ms + " ms");
            _arena.OnKick = OnKick;
            _arena.OnClosed = OnClosed;
            _resumes = 0;
            if (grant.tls) _arena.ConnectThroughSite(apiUrl, grant.tlsName, grant.ticketId);
            else _arena.Connect(grant.arenaHost, grant.arenaPort, grant.ticketId);
        }

        /// <summary>Leaves for good; the arena closes the connection.</summary>
        public void LeaveMatch() => _arena?.Leave();

        // Every callback of ArenaClient runs in here, on the main thread.
        private void Update()
        {
            if (_arena == null) return;
            _arena.Update();
            if (_arena.Welcome != null && Time.unscaledTime >= _nextInputAt)
            {
                _nextInputAt = Time.unscaledTime + 0.1f;                 // ten inputs a second, moving or not
                _arena.SendInput(moveMask, aimRadians, fire ? ArenaMessages.FlagFire : 0);
            }
            if (_resumeAt >= 0 && Time.unscaledTime >= _resumeAt)
            {
                _resumeAt = -1;
                _arena.Resume();
            }
        }

        // Home button, a call: the arena keeps the stay a while; back, the inputs go on.
        private void OnApplicationPause(bool paused) => _arena?.SetBackground(paused);

        private void OnDestroy() => _arena?.Close();

        private void OnWelcome(ArenaWelcome welcome)
        {
            _resumes = 0;
            Debug.Log("In the match: mode " + welcome.Mode + ", a map of " + welcome.MapWidth + " by " + welcome.MapHeight
                      + ", the player's entity " + welcome.SelfEntityId);
        }

        private void OnSnapshot(byte[] snapshot, long serverTick)
        {
            // The world's changes since the last snapshot (02-networking §4): decode it here to draw the match;
            // client/Core's SnapshotReader and ClientWorld do it in full.
            _snapshots++;
            if (Time.unscaledTime >= _logAt)
            {
                _logAt = Time.unscaledTime + 5;
                Debug.Log(_snapshots + " snapshots, at tick " + serverTick);
            }
        }

        private void OnKick(int reason)
        {
            switch (reason)
            {
                case ArenaMessages.KickMatchOver: Debug.Log("The match is over: back to the lobby"); break;
                case ArenaMessages.KickRemoved: Debug.Log("Removed by an operator: back to the lobby"); break;
                case ArenaMessages.KickBadTicket: Debug.LogWarning("The ticket was not taken: ask the lobby again"); break;
                case ArenaMessages.KickRoomFull: Debug.LogWarning("No room had space: queue again"); break;
                case ArenaMessages.KickProtocolVersion: Debug.LogError("This app is too old: update it"); break;
                case ArenaMessages.KickRateLimit: Debug.LogError("Too many messages: a bug of this client's"); break;
                default: Debug.LogWarning("The arena failed (" + reason + "): back to the lobby, try again later"); break;
            }
        }

        private void OnClosed(string why)
        {
            Debug.Log("The match connection closed: " + why);
            // Lost without Leave or a kick: the stay waits a minute; try again after 1, 2, 4, 8 and 16 s.
            if (_arena.CanResume && why != "closed by the client" && _resumes < 5)
            {
                _resumeAt = Time.unscaledTime + (1 << _resumes++);
                return;
            }
            _arena = null;                                               // back to the lobby
        }
    }
}
