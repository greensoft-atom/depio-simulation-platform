using System.Threading.Tasks;
using UnityEngine;

namespace Backend.Client.Simple.Examples
{
    /// <summary>
    /// The API, step by step: is it up; the device's account signed in again by its refresh token, or else a guest made once
    /// and kept on the device, logged in; the shop and the inventory read; SignUp and LogIn at the account service. Each step
    /// awaits the one before. Add it to an object in a scene, set the addresses of a backend run plain or of the site's
    /// 80, press Play, and read the Console.
    /// </summary>
    public class HttpExample : MonoBehaviour
    {
        [Tooltip("http://<address>: the site's 80, or a backend run plain (PLAIN=1); this client has no TLS")]
        public string apiUrl = "http://127.0.0.1";

        [Tooltip("The account service: http://<address>/account, the site's 80; http://127.0.0.1:8083 run by hand")]
        public string accountUrl = "http://127.0.0.1:8083";

        private BackendHttp _http, _accounts;
        /// <summary>The guest signed in on this device, which an account signed up or logged in takes over; null for none.</summary>
        private Session _guest;

        // Unity runs the code after each await on the main thread again: it may use the scene and the UI.
        private async void Start()
        {
            _http = new BackendHttp(apiUrl);
            _accounts = new BackendHttp(accountUrl);

            HttpAnswer health = await _http.GetAsync("/v1/health");
            if (!health.Ok)
            {
                Debug.LogError("The API does not answer: " + health.Error);
                return;
            }

            Session session = PlayerPrefs.HasKey("refreshToken") ? await SignInByRefreshToken() : await LogInAsGuest();
            if (session == null) return;
            _http.Token = session.token;                              // every call from here on is the player's
            Debug.Log("Logged in as player " + session.playerId + ", for " + session.expiresInSeconds / 3600 + " hours");

            HttpAnswer answer = await _http.GetAsync("/v1/shop");
            if (!answer.Ok)
            {
                Debug.LogError("No shop: " + answer.Error);
                return;
            }
            foreach (Offer offer in JsonUtility.FromJson<Shop>(answer.Body).offers)
                Debug.Log("On sale: " + offer.itemId + " for " + offer.price + " " + offer.currency);

            answer = await _http.GetAsync("/v1/inventory");
            if (answer.Error == "invalid_session")
            {
                Debug.LogWarning("The session ended (a day, or logged out): log in again");
                return;
            }
            if (!answer.Ok)
            {
                Debug.LogError("No inventory: " + answer.Error);
                return;
            }
            Inventory inventory = JsonUtility.FromJson<Inventory>(answer.Body);
            Debug.Log("Coins " + inventory.coins + ", gems " + inventory.gems + ", " + inventory.items.Length + " items");
        }

        /// <summary>The device's guest logged in: made on the first run, its key kept; null when it failed.</summary>
        private async Task<Session> LogInAsGuest()
        {
            string key = PlayerPrefs.GetString("guestKey", "");
            HttpAnswer answer = key == "" ? null : await _http.PostAsync("/v1/sessions", JsonUtility.ToJson(new GuestLogin { guestKey = key }));
            if (answer == null || answer.Error == "invalid_credentials")
            {
                // No guest on this device yet, or one nobody played for 180 days, which is erased: a new one.
                HttpAnswer made = await _http.PostAsync("/v1/guests", null);
                if (!made.Ok)
                {
                    Debug.LogError("No guest made: " + made.Error);
                    return null;
                }
                Guest guest = JsonUtility.FromJson<Guest>(made.Body);
                PlayerPrefs.SetString("guestKey", guest.guestKey);      // the key is the account: lose it, lose the account
                PlayerPrefs.Save();
                Debug.Log("A new guest, " + guest.displayName + ", player " + guest.playerId);
                answer = await _http.PostAsync("/v1/sessions", JsonUtility.ToJson(new GuestLogin { guestKey = guest.guestKey }));
            }
            if (!answer.Ok)
            {
                Debug.LogError("Not logged in: " + answer.Error);     // "banned", "too_many_attempts"...
                return null;
            }
            _guest = JsonUtility.FromJson<Session>(answer.Body);
            return _guest;
        }

        /// <summary>
        /// The device's account signed in again: its refresh token for new tokens at the account service (the new one kept
        /// at once, as the old is spent), the access token then taken to the API for a session; null when it failed.
        /// </summary>
        private async Task<Session> SignInByRefreshToken()
        {
            var refresh = new RefreshRequest { refresh_token = PlayerPrefs.GetString("refreshToken") };
            HttpAnswer answer = await _accounts.PostAsync("/v1/sessions/refresh", JsonUtility.ToJson(refresh));
            if (answer.Error == "invalid_grant")
            {
                // Logged out, its password changed, a year old, or the account deleted: the player logs in again.
                PlayerPrefs.DeleteKey("refreshToken");
                Debug.LogWarning("Signed out: log in again");
                return null;
            }
            if (!answer.Ok)
            {
                Debug.LogError("Not refreshed: " + answer.Error);      // "busy", or no answer: try again
                return null;
            }
            return await TakeTokens(answer);
        }

        /// <summary>
        /// A new account at the account service, logged in at once. A guest signed in on this device is linked to it, so its
        /// progress is the account's.
        /// </summary>
        public async void SignUp(string username, string password)
        {
            HttpAnswer answer = await _accounts.PostAsync("/v1/accounts", JsonUtility.ToJson(new Credentials { username = username, password = password }));
            if (answer.Error == "username_taken")
            {
                Debug.LogWarning("That name is taken");
                return;
            }
            if (!answer.Ok)
            {
                Debug.LogError("Not signed up: " + answer.Error);     // "invalid_username", "invalid_password", "too_many_attempts"...
                return;
            }
            Session session = await TakeTokens(answer);
            if (session != null) Debug.Log("Signed up as " + username + ", player " + session.playerId);
        }

        /// <summary>An account logged in at the account service: a guest signed in here is linked when the account has no player yet.</summary>
        public async void LogIn(string username, string password)
        {
            HttpAnswer answer = await _accounts.PostAsync("/v1/sessions", JsonUtility.ToJson(new Credentials { username = username, password = password }));
            if (answer.Error == "invalid_credentials")
            {
                Debug.LogWarning("Wrong name or password");
                return;
            }
            if (!answer.Ok)
            {
                Debug.LogError("Not logged in: " + answer.Error);
                return;
            }
            Session session = await TakeTokens(answer);
            if (session != null) Debug.Log("Logged in as " + username + ", player " + session.playerId);
        }

        /// <summary>
        /// The account service's tokens taken: the guest signed in here linked to the account, the same player, or else the
        /// access token taken to the API once for the account's own session; then the refresh token kept on the device.
        /// </summary>
        private async Task<Session> TakeTokens(HttpAnswer tokens)
        {
            AccountTokens t = JsonUtility.FromJson<AccountTokens>(tokens.Body);
            Session session = null;
            if (_guest != null)
            {
                HttpAnswer linked = await _http.PostAsync("/v1/accounts/link", JsonUtility.ToJson(new LinkRequest { accessToken = t.access_token }));
                if (linked.Ok) session = _guest;                      // the guest's session goes on, now the account's player's
                else if (linked.Error != "account_in_use")
                {
                    Debug.LogError("Not linked: " + linked.Error);    // the guest stays; logging in again links it
                    return null;
                }
                // account_in_use: the account has a player of its own, from another device, and the guest is left.
            }
            if (session == null)
            {
                HttpAnswer answer = await _http.PostAsync("/v1/sessions", JsonUtility.ToJson(new TokenLogin { accessToken = t.access_token }));
                if (!answer.Ok)
                {
                    Debug.LogError("No session: " + answer.Error);    // "banned"...
                    return null;
                }
                session = JsonUtility.FromJson<Session>(answer.Body);
            }
            // One player a device: the account's refresh token kept, the guest's key, linked or left, forgotten. Here in
            // PlayerPrefs for brevity; the app keeps both in the device's secure store (the full client's SecureStores).
            PlayerPrefs.SetString("refreshToken", t.refresh_token);
            PlayerPrefs.DeleteKey("guestKey");
            PlayerPrefs.Save();
            _guest = null;
            _http.Token = session.token;
            return session;
        }

        /// <summary>The session ended, and an account's refresh token spent at the account service and forgotten.</summary>
        public async void LogOut()
        {
            HttpAnswer answer = await _http.DeleteAsync("/v1/sessions");
            _http.Token = null;
            if (PlayerPrefs.HasKey("refreshToken"))
            {
                var refresh = new RefreshRequest { refresh_token = PlayerPrefs.GetString("refreshToken") };
                PlayerPrefs.DeleteKey("refreshToken");
                PlayerPrefs.Save();
                await _accounts.DeleteAsync("/v1/sessions", JsonUtility.ToJson(refresh));
            }
            Debug.Log("Logged out (" + answer.Status + ")");
        }
    }
}
