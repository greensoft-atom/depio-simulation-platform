using System;
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
    /// What signs this device in, kept across launches (detailed-design 10 §5.1, 08 §8): an account's refresh token or a
    /// guest's key, one of the two, never a password; one player a device. The first launch is a guest, its key kept.
    /// Signing up or logging in links the device's guest to the account when the account has no player yet, so its
    /// progress is the account's; an account with a player of its own is signed in and the guest left. A refresh token
    /// or a key refused is forgotten and the refusal answered: a new guest is made only by <see cref="SignIn"/> again, so
    /// nobody is made a stranger unasked.
    ///
    /// Its continuations run on the caller's context, Unity's main thread, where the store is touched: PlayerPrefs and
    /// the native stores refuse any other thread.
    /// </summary>
    public sealed class AccountKeeper
    {
        public const string GuestKeyName = "backend.guestKey";
        public const string RefreshTokenName = "backend.refreshToken";

        private readonly ApiClient _api;
        private readonly AccountClient _accounts;
        private readonly ISecureStore _store;

        public AccountKeeper(ApiClient api, AccountClient accounts, ISecureStore store)
        {
            _api = api;
            _accounts = accounts;
            _store = store;
        }

        /// <summary>Whether the device holds an account, not a guest: its deletion and a password change ask the password.</summary>
        public bool HoldsAccount => _store.Get(RefreshTokenName) != null;

        /// <summary>
        /// Signs in: by the refresh token, the new one kept before anything else and its access token exchanged; else by the
        /// guest's key; else as a new guest, its key kept. A refresh token refused (invalid_grant: logged out, its password
        /// changed, a year old, its account deleted) is forgotten, and the app asks for a login.
        /// </summary>
        public async Task<ApiResult<Session>> SignIn()
        {
            if (HoldsAccount)
            {
                ApiResult<string> access = await Access();
                return access.Ok ? await _api.Exchange(access.Value) : Failed<Session, string>(access);
            }
            string key = _store.Get(GuestKeyName);
            if (key == null)
            {
                ApiResult<Guest> made = await _api.CreateGuest();
                if (!made.Ok) return Failed<Session, Guest>(made);
                key = made.Value.GuestKey;
                _store.Set(GuestKeyName, key);
            }
            ApiResult<Session> session = await _api.LoginGuest(key);
            if (!session.Ok && session.Status == 401) _store.Delete(GuestKeyName);
            return session;
        }

        /// <summary>A new account, and the device's guest linked to it: the same player, its progress kept (10 §5.1).</summary>
        public async Task<ApiResult<Session>> SignUp(string username, string password) =>
            await Enter(await _accounts.SignUp(username, password));

        /// <summary>
        /// An account logged in, and the device's guest linked to it; an account that has a player already, from another
        /// device, is that player, and the guest is left, its key forgotten: say so first when the guest has anything to lose.
        /// </summary>
        public async Task<ApiResult<Session>> LogIn(string username, string password) =>
            await Enter(await _accounts.LogIn(username, password));

        /// <summary>
        /// Into the account the tokens are: the guest linked, or the account's own player by the exchange. Nothing new is
        /// kept until the device is in, so a failure leaves the guest the device's, linked by logging in again.
        /// </summary>
        private async Task<ApiResult<Session>> Enter(ApiResult<AccountTokens> tokens)
        {
            if (!tokens.Ok) return Failed<Session, AccountTokens>(tokens);
            string key = _store.Get(GuestKeyName);
            if (key != null)
            {
                ApiResult<Session> guest = await _api.LoginGuest(key);
                if (guest.Ok)
                {
                    ApiResult<long> linked = await _api.LinkAccount(guest.Value.Token, tokens.Value.AccessToken);
                    if (linked.Ok)
                    {
                        Keep(tokens.Value.RefreshToken);
                        return guest;                         // the guest's session, now the account's player's
                    }
                    if (linked.Status != 409 || linked.Code != "account_in_use") return Failed<Session, long>(linked);
                }
                else if (guest.Status != 401)
                {
                    return guest;
                }
            }
            ApiResult<Session> session = await _api.Exchange(tokens.Value.AccessToken);
            if (session.Ok) Keep(tokens.Value.RefreshToken);
            return session;
        }

        /// <summary>The account's refresh token kept, and a guest's key, linked or left, forgotten: one player a device.</summary>
        private void Keep(string refreshToken)
        {
            _store.Set(RefreshTokenName, refreshToken);
            _store.Delete(GuestKeyName);
        }

        /// <summary>
        /// The backend's session ended, and an account's refresh token forgotten and its family spent. A guest's key is
        /// kept: forgotten, the guest would be lost, so <see cref="SignIn"/> after is the device's guest again.
        /// </summary>
        public async Task<ApiResult<bool>> SignOut(string session)
        {
            string refresh = _store.Get(RefreshTokenName);
            if (refresh != null) _store.Delete(RefreshTokenName);      // forgotten first: signed out even if nothing answers
            ApiResult<bool> ended = await _api.Logout(session);
            if (refresh != null) await _accounts.LogOut(refresh);
            return ended;
        }

        /// <summary>
        /// The account's password changed: the new family's refresh token kept, and its access token exchanged for a session
        /// made after the change, which the change's event leaves while it ends every older one (10 §4.3). A guest has none.
        /// </summary>
        public async Task<ApiResult<Session>> ChangePassword(string current, string next)
        {
            if (!HoldsAccount) throw new InvalidOperationException("this device holds a guest, which has no password");
            ApiResult<string> access = await Access();
            if (!access.Ok) return Failed<Session, string>(access);
            ApiResult<AccountTokens> changed = await _accounts.ChangePassword(access.Value, current, next);
            if (!changed.Ok) return Failed<Session, AccountTokens>(changed);
            _store.Set(RefreshTokenName, changed.Value.RefreshToken);
            return await _api.Exchange(changed.Value.AccessToken);
        }

        /// <summary>
        /// The device's player erased (D-84): an account's at the account service, its password asked again, its event
        /// erasing the player; a guest's at the backend, by its <paramref name="session"/> alone. What was kept, forgotten.
        /// </summary>
        public async Task<ApiResult<bool>> DeleteAccount(string session, string password)
        {
            if (!HoldsAccount)
            {
                ApiResult<bool> erased = await _api.DeleteAccount(session);
                if (erased.Ok) _store.Delete(GuestKeyName);
                return erased;
            }
            ApiResult<string> access = await Access();
            if (!access.Ok) return Failed<bool, string>(access);
            ApiResult<bool> deleted = await _accounts.DeleteAccount(access.Value, password);
            if (deleted.Ok) _store.Delete(RefreshTokenName);
            return deleted;
        }

        /// <summary>An access token by the refresh token held, the new refresh token kept at once; one refused, forgotten.</summary>
        private async Task<ApiResult<string>> Access()
        {
            ApiResult<AccountTokens> t = await _accounts.Refresh(_store.Get(RefreshTokenName));
            if (t.Ok)
            {
                _store.Set(RefreshTokenName, t.Value.RefreshToken);
                return new ApiResult<string> { Ok = true, Value = t.Value.AccessToken, Status = t.Status };
            }
            if (t.Status == 400 && t.Code == "invalid_grant") _store.Delete(RefreshTokenName);
            return Failed<string, AccountTokens>(t);
        }

        private static ApiResult<T> Failed<T, TFrom>(ApiResult<TFrom> r) => new ApiResult<T>
        {
            Status = r.Status, Code = r.Code, Message = r.Message, RetryAfterSeconds = r.RetryAfterSeconds,
        };
    }
}
