using System;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Threading.Tasks;

namespace Backend.Client.Simple
{
    /// <summary>One answer of the API (docs/api/01-http-api.md).</summary>
    public sealed class HttpAnswer
    {
        /// <summary>The HTTP status: 200, 201, 204, 401...; 0 when no answer came (no network, a timeout).</summary>
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
    /// Calls the player API with HttpClient, without TLS: <c>var http = new BackendHttp("http://192.168.1.20")</c>,
    /// then, in an async method of a MonoBehaviour, <c>HttpAnswer answer = await http.PostAsync("/v1/sessions", json)</c>.
    /// In Unity the code after an await runs on the main thread again, so it may use the scene and the UI.
    /// A call never throws: what went wrong is in the answer. Await it; never wait on it (.Result, .Wait()).
    /// </summary>
    public sealed class BackendHttp
    {
        /// <summary>The session's token, sent as <c>Authorization: Bearer</c> once set; null sends none.</summary>
        public string Token;

        private readonly HttpClient _client = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        private readonly string _baseUrl;

        /// <param name="baseUrl">the API's address in clear, <c>http://192.168.1.20</c>: the site's 80, or a backend run plain (PLAIN=1)</param>
        public BackendHttp(string baseUrl)
        {
            if (!baseUrl.StartsWith("http://", StringComparison.Ordinal))
                throw new ArgumentException("an http:// address: this client has no TLS (" + baseUrl + ")");
            _baseUrl = baseUrl.TrimEnd('/');
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
                answer.Error = e.GetBaseException().Message;                       // no network, the address wrong...
            }
            return answer;
        }
    }
}
