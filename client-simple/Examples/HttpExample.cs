using System.Threading.Tasks;
using UnityEngine;

namespace Backend.Client.Simple.Examples
{
    /// <summary>
    /// The API, step by step: is it up; a guest account made once and kept on the device; logged in; the shop and the
    /// inventory read. Each step awaits the one before. Add it to an object in a scene, set the address (and the root
    /// for https://), press Play, and read the Console.
    /// </summary>
    public class HttpExample : MonoBehaviour
    {
        [Tooltip("https://<the site's address>; http://<address> in development")]
        public string apiUrl = "http://127.0.0.1";

        [Tooltip("The backend's root, ca.pem renamed ca.pem.txt (a TextAsset); none for http://")]
        public TextAsset rootCertificate;

        private BackendHttp _http;

        // Unity runs the code after each await on the main thread again: it may use the scene and the UI.
        private async void Start()
        {
            // A player's build talks to the backend encrypted alone (D-104); a development build may use http://.
            if (!Debug.isDebugBuild && !apiUrl.StartsWith("https://"))
            {
                Debug.LogError("A player's build calls the API over https:// alone, not " + apiUrl);
                return;
            }
            _http = new BackendHttp(apiUrl, rootCertificate != null ? rootCertificate.text : null);

            HttpAnswer health = await _http.GetAsync("/v1/health");
            if (!health.Ok)
            {
                Debug.LogError("The API does not answer: " + health.Error);
                return;
            }

            Session session = await LogInAsGuest();
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
            return JsonUtility.FromJson<Session>(answer.Body);
        }

        /// <summary>An account with a name and password instead of a guest: registered once, then logged in.</summary>
        public async void Register(string username, string password, string displayName)
        {
            var registration = new Registration { username = username, password = password, displayName = displayName };
            HttpAnswer answer = await _http.PostAsync("/v1/accounts", JsonUtility.ToJson(registration));
            if (answer.Error == "username_taken")
            {
                Debug.LogWarning("That name is taken");
                return;
            }
            if (!answer.Ok)
            {
                Debug.LogError("Not registered: " + answer.Error);
                return;
            }
            answer = await _http.PostAsync("/v1/sessions", JsonUtility.ToJson(new Credentials { username = username, password = password }));
            if (!answer.Ok)
            {
                Debug.LogError("Not logged in: " + answer.Error);
                return;
            }
            _http.Token = JsonUtility.FromJson<Session>(answer.Body).token;
            Debug.Log("Registered and logged in as " + username);
        }

        public async void LogOut()
        {
            HttpAnswer answer = await _http.DeleteAsync("/v1/sessions");
            _http.Token = null;
            Debug.Log("Logged out (" + answer.Status + ")");
        }
    }
}
