using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;

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
    /// Calls the player API: <c>new BackendHttp("https://203.0.113.10", caPem)</c>, then
    /// <c>http.Post("/v1/sessions", json, answer => ...)</c>. Each call runs on a pool thread; its callback runs inside
    /// <see cref="Update"/>, so call that from a MonoBehaviour's Update and every callback is on Unity's main thread.
    /// </summary>
    public sealed class BackendHttp
    {
        /// <summary>The session's token, sent as <c>Authorization: Bearer</c> once set; null sends none.</summary>
        public string Token;
        /// <summary>The longest a call waits for its answer.</summary>
        public int TimeoutMs = 15000;

        private readonly string _baseUrl;
        private readonly TrustRoot _trust;
        private readonly ConcurrentQueue<Action> _done = new ConcurrentQueue<Action>();

        /// <param name="baseUrl">the site's address, <c>https://203.0.113.10</c>; <c>http://</c> in development</param>
        /// <param name="caPem">the backend's root (ca.pem's text), which an https:// address's certificate must come from</param>
        public BackendHttp(string baseUrl, string caPem = null)
        {
            _baseUrl = baseUrl.TrimEnd('/');
            _trust = caPem == null ? null : TrustRoot.FromPem(caPem);
        }

        public void Get(string path, Action<HttpAnswer> done) => Send("GET", path, null, done);

        /// <param name="json">the body; null sends none</param>
        public void Post(string path, string json, Action<HttpAnswer> done) => Send("POST", path, json, done);

        public void Put(string path, string json, Action<HttpAnswer> done) => Send("PUT", path, json, done);

        public void Delete(string path, string json, Action<HttpAnswer> done) => Send("DELETE", path, json, done);

        public void Send(string method, string path, string json, Action<HttpAnswer> done)
        {
            string token = Token;                       // read here, on the caller's thread
            ThreadPool.QueueUserWorkItem(_ =>
            {
                HttpAnswer answer = Exchange(method, path, json, token);
                _done.Enqueue(() => done?.Invoke(answer));
            });
        }

        /// <summary>Hands each finished call's answer to its callback. Call it every frame.</summary>
        public void Update()
        {
            Action callback;
            while (_done.TryDequeue(out callback)) callback();
        }

        private HttpAnswer Exchange(string method, string path, string json, string token)
        {
            var answer = new HttpAnswer();
            try
            {
                var request = (HttpWebRequest)WebRequest.Create(_baseUrl + path);
                request.Method = method;
                request.Timeout = request.ReadWriteTimeout = TimeoutMs;
                request.Accept = "application/json";
                if (token != null) request.Headers["Authorization"] = "Bearer " + token;
                // The backend's own root, for this request alone (D-108): ServicePointManager's would be the whole app's.
                if (_trust != null) request.ServerCertificateValidationCallback = _trust.CheckFor(request.RequestUri.Host);
                if (json != null || method != "GET")
                {
                    byte[] body = Encoding.UTF8.GetBytes(json ?? "");
                    request.ContentType = "application/json";
                    request.ContentLength = body.Length;
                    using (Stream s = request.GetRequestStream()) s.Write(body, 0, body.Length);
                }
                HttpWebResponse response;
                try
                {
                    response = (HttpWebResponse)request.GetResponse();
                }
                catch (WebException e) when (e.Response is HttpWebResponse)
                {
                    response = (HttpWebResponse)e.Response;      // 4xx and 5xx: an answer all the same, with its body
                }
                using (response)
                using (var reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8))
                {
                    answer.Status = (int)response.StatusCode;
                    answer.Body = reader.ReadToEnd();
                }
                if (!answer.Ok) answer.Error = Json.GetString(answer.Body, "code") ?? "HTTP " + answer.Status;
            }
            catch (Exception e)
            {
                answer.Status = 0;
                answer.Error = e.Message;
            }
            return answer;
        }
    }
}
