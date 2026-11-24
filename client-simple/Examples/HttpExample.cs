using UnityEngine;

namespace Backend.Client.Simple.Examples
{
    /// <summary>
    /// The API, step by step: is it up; a guest account made once and kept on the device; logged in; the shop and the
    /// inventory read. Each step is a call whose callback starts the next. Add it to an object in a scene, set the
    /// address (and the root for https://), press Play, and read the Console.
    /// </summary>
    public class HttpExample : MonoBehaviour
    {
        [Tooltip("https://<the site's address>; http://<address> in development")]
        public string apiUrl = "http://127.0.0.1";

        [Tooltip("The backend's root, ca.pem renamed ca.pem.txt (a TextAsset); none for http://")]
        public TextAsset rootCertificate;

        private BackendHttp _http;

        private void Start()
        {
            // A player's build talks to the backend encrypted alone (D-104); a development build may use http://.
            if (!Debug.isDebugBuild && !apiUrl.StartsWith("https://"))
            {
                Debug.LogError("A player's build calls the API over https:// alone, not " + apiUrl);
                return;
            }
            _http = new BackendHttp(apiUrl, rootCertificate != null ? rootCertificate.text : null);
            _http.Get("/v1/health", OnHealth);
        }

        // Every callback of BackendHttp runs in here, on the main thread.
        private void Update() => _http?.Update();

        private void OnHealth(HttpAnswer answer)
        {
            if (!answer.Ok)
            {
                Debug.LogError("The API does not answer: " + answer.Error);
                return;
            }
            string key = PlayerPrefs.GetString("guestKey", "");
            if (key != "") LogInAsGuest(key);
            else _http.Post("/v1/guests", null, OnGuestMade);
        }

        private void OnGuestMade(HttpAnswer answer)
        {
            if (!answer.Ok)
            {
                Debug.LogError("No guest made: " + answer.Error);
                return;
            }
            Guest guest = JsonUtility.FromJson<Guest>(answer.Body);
            PlayerPrefs.SetString("guestKey", guest.guestKey);      // the key is the account: lose it, lose the account
            PlayerPrefs.Save();
            Debug.Log("A new guest, " + guest.displayName + ", player " + guest.playerId);
            LogInAsGuest(guest.guestKey);
        }

        private void LogInAsGuest(string guestKey)
        {
            _http.Post("/v1/sessions", JsonUtility.ToJson(new GuestLogin { guestKey = guestKey }), OnLoggedIn);
        }

        private void OnLoggedIn(HttpAnswer answer)
        {
            if (answer.Error == "invalid_credentials")
            {
                // A guest nobody played for 180 days is erased: make a new one.
                PlayerPrefs.DeleteKey("guestKey");
                _http.Post("/v1/guests", null, OnGuestMade);
                return;
            }
            if (!answer.Ok)
            {
                Debug.LogError("Not logged in: " + answer.Error);     // "banned", "too_many_attempts"...
                return;
            }
            Session session = JsonUtility.FromJson<Session>(answer.Body);
            _http.Token = session.token;                              // every call from here on is the player's
            Debug.Log("Logged in as player " + session.playerId + ", for " + session.expiresInSeconds / 3600 + " hours");
            _http.Get("/v1/shop", OnShop);
            _http.Get("/v1/inventory", OnInventory);
        }

        private void OnShop(HttpAnswer answer)
        {
            if (!answer.Ok)
            {
                Debug.LogError("No shop: " + answer.Error);
                return;
            }
            Shop shop = JsonUtility.FromJson<Shop>(answer.Body);
            foreach (Offer offer in shop.offers)
                Debug.Log("On sale: " + offer.itemId + " for " + offer.price + " " + offer.currency);
        }

        private void OnInventory(HttpAnswer answer)
        {
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

        /// <summary>An account with a name and password instead of a guest: registered once, then logged in.</summary>
        public void Register(string username, string password, string displayName)
        {
            var registration = new Registration { username = username, password = password, displayName = displayName };
            _http.Post("/v1/accounts", JsonUtility.ToJson(registration), answer =>
            {
                if (answer.Error == "username_taken") Debug.LogWarning("That name is taken");
                else if (!answer.Ok) Debug.LogError("Not registered: " + answer.Error);
                else _http.Post("/v1/sessions", JsonUtility.ToJson(new Credentials { username = username, password = password }), OnLoggedIn);
            });
        }

        public void LogOut()
        {
            _http.Delete("/v1/sessions", null, answer =>
            {
                _http.Token = null;
                Debug.Log("Logged out (" + answer.Status + ")");
            });
        }
    }
}
