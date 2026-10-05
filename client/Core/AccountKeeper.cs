using System.Collections.Generic;
using System.Threading.Tasks;

namespace Backend.Client.Core
{
    /// <summary>
    /// Somewhere a string survives the app's restarts, and only the app reads it (08 §8): the device's keychain or
    /// keystore in the Unity layer, memory in the tests.
    /// </summary>
    public interface ISecureStore
    {
        /// <returns>the value, or null for none</returns>
        string Get(string key);
        void Set(string key, string value);
        void Delete(string key);
    }

    /// <summary>For tests, and nothing a player's device should keep a key in.</summary>
    public sealed class MemorySecureStore : ISecureStore
    {
        private readonly Dictionary<string, string> _values = new Dictionary<string, string>();

        public string Get(string key) => _values.TryGetValue(key, out string v) ? v : null;
        public void Set(string key, string value) => _values[key] = value;
        public void Delete(string key) => _values.Remove(key);
    }

    /// <summary>
    /// The account kept across launches (08 §8, plan item 77 (a)): the first launch is a guest, its key kept; every
    /// launch after signs in by it. A key refused is forgotten, the guest upgraded or gone, and a new guest is made
    /// only if asked, by <see cref="SignIn"/> again: a player who upgraded is not made a stranger.
    /// </summary>
    public sealed class AccountKeeper
    {
        public const string GuestKeyName = "backend.guestKey";

        private readonly ApiClient _api;
        private readonly ISecureStore _store;

        public AccountKeeper(ApiClient api, ISecureStore store)
        {
            _api = api;
            _store = store;
        }

        /// <summary>
        /// Signs in, by the kept key or a new guest. Its continuations run on the caller's context, Unity's main thread,
        /// where the store is touched: PlayerPrefs and the native stores refuse any other thread.
        /// </summary>
        public async Task<ApiResult<Session>> SignIn()
        {
            string key = _store.Get(GuestKeyName);
            if (key == null)
            {
                ApiResult<Guest> made = await _api.CreateGuest();
                if (!made.Ok)
                    return new ApiResult<Session>
                    {
                        Status = made.Status, Code = made.Code, Message = made.Message, RetryAfterSeconds = made.RetryAfterSeconds,
                    };
                key = made.Value.GuestKey;
                _store.Set(GuestKeyName, key);
            }
            ApiResult<Session> session = await _api.LoginGuest(key);
            if (!session.Ok && session.Status == 401) _store.Delete(GuestKeyName);
            return session;
        }
    }
}
