using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Threading.Tasks;

namespace Backend.Client.Core
{
    /// <summary>
    /// What the account service answers a sign-up, a login, a refresh and a password change with (detailed-design 10
    /// §3.2): RFC 6749's token answer.
    /// </summary>
    public sealed class AccountTokens
    {
        /// <summary>
        /// A JWT good for 15 minutes: taken once by the backend for a session (<see cref="ApiClient.Exchange"/>), and the
        /// bearer of the account service's own routes. Never kept.
        /// </summary>
        public string AccessToken;
        /// <summary>What signs this device in again: kept in the secure store, spent by its use, a new one given each time.</summary>
        public string RefreshToken;
        /// <summary>The account's id at the account service, the access token's subject.</summary>
        public string AccountId;
        public int ExpiresInSeconds;
    }

    /// <summary>
    /// The account service (detailed-design 10 §3.2, D-117): an account signed up, logged in, refreshed and logged out,
    /// its password changed, and deleted. Its address is the site's with <c>/account</c>, <c>https://203.0.113.10/account</c>
    /// (10 §6); <c>http://127.0.0.1:8083</c> run by hand. Called as <see cref="ApiClient"/> calls the API, by its
    /// endpoints in turn, with its timeout and the backend's root as the certificate check. A refusal's RFC 6749
    /// <c>error</c> and <c>error_description</c> are its <see cref="ApiResult{T}.Code"/> and <see cref="ApiResult{T}.Message"/>:
    /// <c>username_taken</c>, <c>invalid_credentials</c>, <c>invalid_grant</c>, <c>too_many_attempts</c>, <c>busy</c>, …
    /// </summary>
    public sealed class AccountClient
    {
        private readonly ApiClient _calls;

        /// <param name="endpoints">https://203.0.113.10/account: the app's list, of one.</param>
        /// <param name="handler">Null for the platform's own; a test's may trust a test CA.</param>
        public AccountClient(IReadOnlyList<string> endpoints, HttpMessageHandler handler = null, TimeSpan? timeout = null)
        {
            _calls = new ApiClient(endpoints, handler, timeout);
        }

        /// <summary>The endpoint the last call reached, for the log.</summary>
        public string Current => _calls.Current;

        /// <summary>A new account, logged in at once: 201; 400 invalid_username or invalid_password, 409 username_taken.</summary>
        public Task<ApiResult<AccountTokens>> SignUp(string username, string password) =>
            _calls.Call(HttpMethod.Post, "/v1/accounts", Credentials(username, password), null, ReadTokens);

        /// <summary>A login by name and password: 401 invalid_credentials; 429 too_many_attempts and 503 busy with Retry-After.</summary>
        public Task<ApiResult<AccountTokens>> LogIn(string username, string password) =>
            _calls.Call(HttpMethod.Post, "/v1/sessions", Credentials(username, password), null, ReadTokens);

        /// <summary>
        /// A new access token and refresh token for the refresh token, which is spent. Sent again within 30 seconds, as a
        /// retry, it is taken again; 400 invalid_grant once it is spent, its family revoked, or past its time.
        /// </summary>
        public Task<ApiResult<AccountTokens>> Refresh(string refreshToken) =>
            _calls.Call(HttpMethod.Post, "/v1/sessions/refresh", JsonValue.NewObject().Set("refresh_token", refreshToken), null,
                ReadTokens);

        /// <summary>The refresh token's family spent: 204, whatever it was.</summary>
        public Task<ApiResult<bool>> LogOut(string refreshToken) =>
            _calls.Call(HttpMethod.Delete, "/v1/sessions", JsonValue.NewObject().Set("refresh_token", refreshToken), null, _ => true);

        /// <summary>
        /// The password changed, by the account's access token: a new family's tokens, every older family revoked, and the
        /// backend's sessions made before the change ended by its event. 401 invalid_credentials for a wrong current one.
        /// </summary>
        public Task<ApiResult<AccountTokens>> ChangePassword(string accessToken, string current, string next) =>
            _calls.Call(HttpMethod.Put, "/v1/accounts/me/password",
                JsonValue.NewObject().Set("current_password", current).Set("new_password", next), accessToken, ReadTokens);

        /// <summary>The account deleted, its password asked again: 204, and its event erases its player at the backend (D-84).</summary>
        public Task<ApiResult<bool>> DeleteAccount(string accessToken, string password) =>
            _calls.Call(HttpMethod.Delete, "/v1/accounts/me", JsonValue.NewObject().Set("password", password), accessToken, _ => true);

        private static JsonValue Credentials(string username, string password) =>
            JsonValue.NewObject().Set("username", username).Set("password", password);

        private static AccountTokens ReadTokens(JsonValue j) => new AccountTokens
        {
            AccessToken = j["access_token"].AsString, RefreshToken = j["refresh_token"].AsString,
            ExpiresInSeconds = j["expires_in"].AsInt, AccountId = j["account_id"].AsString,
        };
    }
}
