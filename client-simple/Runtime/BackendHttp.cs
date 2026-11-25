using System;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Security;
using System.Text;
using System.Threading.Tasks;

namespace Backend.Client.Simple
{
    /// <summary>One answer of the API (docs/api/01-http-api.md).</summary>
    public sealed class HttpAnswer
    {
        /// <summary>The HTTP status: 200, 201, 204, 401...; 0 when no answer came (no network, a refused certificate, a timeout).</summary>
        public int Status;
        /// <summary>The answer's JSON text; "" when it has none (a 204).</summary>
        public string Body = "";
        /// <summary>
        /// Null when it went well. Otherwise the API's own code (<c>invalid_session</c>, <c>username_taken</c>...),
        /// "HTTP 429" for an answer without one, or what went wrong when no answer came.
        /// </summary>
        public string Error;

        public bool Ok => Status >= 200 && Status < 300;
    }

    /// <summary>
    /// Calls the player API with HttpClient: <c>var http = new BackendHttp("https://203.0.113.10", caPem)</c>, then, in
    /// an async method of a MonoBehaviour, <c>HttpAnswer answer = await http.PostAsync("/v1/sessions", json)</c>.
    /// In Unity the code after an await runs on the main thread again, so it may use the scene and the UI.
    /// A call never throws: what went wrong is in the answer. Await it; never wait on it (.Result, .Wait()).
    /// </summary>
    public sealed class BackendHttp
    {
        /// <summary>The session's token, sent as <c>Authorization: Bearer</c> once set; null sends none.</summary>
        public string Token;

        private readonly HttpClient _client = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        private readonly string _baseUrl;

        /// <param name="baseUrl">the site's address, <c>https://203.0.113.10</c>; <c>http://</c> in development</param>
        /// <param name="caPem">the backend's root (ca.pem's text), which an https:// address's certificate must come from</param>
        public BackendHttp(string baseUrl, string caPem = null)
        {
            _baseUrl = baseUrl.TrimEnd('/');
            if (caPem != null) CheckWithRoot(new Uri(_baseUrl).Host, TrustRoot.FromPem(caPem));
        }

        public Task<HttpAnswer> GetAsync(string path) => SendAsync(HttpMethod.Get, path, null);

        /// <param name="json">the body; null sends none</param>
        public Task<HttpAnswer> PostAsync(string path, string json) => SendAsync(HttpMethod.Post, path, json);

        public Task<HttpAnswer> PutAsync(string path, string json) => SendAsync(HttpMethod.Put, path, json);

        /// <param name="json">the body, which DELETE /v1/accounts takes; null sends none</param>
        public Task<HttpAnswer> DeleteAsync(string path, string json = null) => SendAsync(HttpMethod.Delete, path, json);

        public async Task<HttpAnswer> SendAsync(HttpMethod method, string path, string json)
        {
            var answer = new HttpAnswer();
            try
            {
                var request = new HttpRequestMessage(method, _baseUrl + path);
                if (Token != null) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", Token);
                if (json != null) request.Content = new StringContent(json, Encoding.UTF8, "application/json");
                using (HttpResponseMessage response = await _client.SendAsync(request))
                {
                    answer.Status = (int)response.StatusCode;
                    answer.Body = await response.Content.ReadAsStringAsync();      // 4xx and 5xx too: their body has the code
                }
                if (!answer.Ok) answer.Error = Json.GetString(answer.Body, "code") ?? "HTTP " + answer.Status;
            }
            catch (OperationCanceledException)
            {
                answer.Error = "no answer in " + _client.Timeout.TotalSeconds + " s";
            }
            catch (Exception e)
            {
                answer.Error = e.GetBaseException().Message;                       // no network, a refused certificate...
            }
            return answer;
        }

        /// <summary>
        /// The backend's root as the app's certificate check (D-112). Unity 2019's HttpClient calls
        /// ServicePointManager's, with the request as the sender, and has no check of its own to set
        /// (HttpClientHandler's throws NotImplementedException there). A request to the backend's host is held to its
        /// root alone; any other keeps the check it had: the one set before this, or the runtime's own verdict.
        /// </summary>
        private static void CheckWithRoot(string host, TrustRoot root)
        {
            RemoteCertificateValidationCallback before = ServicePointManager.ServerCertificateValidationCallback;
            ServicePointManager.ServerCertificateValidationCallback = (sender, certificate, chain, errors) =>
            {
                var request = sender as HttpWebRequest;
                if (request != null && request.RequestUri.Host == host) return root.Accepts(certificate, host);
                return before != null ? before(sender, certificate, chain, errors) : errors == SslPolicyErrors.None;
            };
        }
    }
}
