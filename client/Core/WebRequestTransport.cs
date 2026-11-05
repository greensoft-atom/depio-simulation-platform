using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace Backend.Client.Core
{
    /// <summary>
    /// The API's requests through <see cref="HttpWebRequest"/>, each checked by the backend's root for its own host
    /// (D-108): Unity 2019's Mono does not implement <c>HttpClientHandler.ServerCertificateCustomValidationCallback</c>,
    /// and <c>ServicePointManager</c>'s callback would be the whole app's. HttpClient above it is as before: its
    /// timeout, and the request and the answer as HttpClient has them.
    /// </summary>
    internal sealed class WebRequestTransport : HttpMessageHandler
    {
        private readonly TrustRoot _trust;

        public WebRequestTransport(TrustRoot trust)
        {
            _trust = trust;
        }

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancel)
        {
            var web = (HttpWebRequest)WebRequest.Create(request.RequestUri);
            web.Method = request.Method.Method;
            web.ServerCertificateValidationCallback = _trust.CheckFor(request.RequestUri.Host);
            foreach (var h in request.Headers)
            {
                if (h.Key == "Accept") web.Accept = string.Join(", ", h.Value);
                else if (!WebHeaderCollection.IsRestricted(h.Key)) web.Headers.Add(h.Key, string.Join(", ", h.Value));
            }
            using (cancel.Register(web.Abort))
            {
                try
                {
                    if (request.Content != null)
                    {
                        byte[] body = await request.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                        web.ContentType = request.Content.Headers.ContentType?.ToString();
                        web.ContentLength = body.Length;
                        using (Stream s = await web.GetRequestStreamAsync().ConfigureAwait(false))
                            await s.WriteAsync(body, 0, body.Length, cancel).ConfigureAwait(false);
                    }
                    else if (web.Method == "POST" || web.Method == "PUT")
                    {
                        web.ContentLength = 0;
                    }
                    HttpWebResponse response;
                    try
                    {
                        response = (HttpWebResponse)await web.GetResponseAsync().ConfigureAwait(false);
                    }
                    catch (WebException e) when (e.Response is HttpWebResponse answered)
                    {
                        response = answered;                    // a 4xx or a 5xx: an answer, as HttpClient has it
                    }
                    using (response)
                        return await Answer(request, response).ConfigureAwait(false);
                }
                catch (WebException e)
                {
                    cancel.ThrowIfCancellationRequested();
                    // No answer: refused, unreachable, or a certificate the root did not issue. HttpClient's own kind.
                    throw new HttpRequestException(e.Message, e);
                }
            }
        }

        private static async Task<HttpResponseMessage> Answer(HttpRequestMessage request, HttpWebResponse response)
        {
            var body = new MemoryStream();
            using (Stream s = response.GetResponseStream())
                if (s != null) await s.CopyToAsync(body).ConfigureAwait(false);
            var answer = new HttpResponseMessage(response.StatusCode)
            {
                RequestMessage = request,
                Content = new ByteArrayContent(body.ToArray()),
            };
            foreach (string name in response.Headers.AllKeys)
            {
                string value = response.Headers[name];
                if (!answer.Headers.TryAddWithoutValidation(name, value))
                    answer.Content.Headers.TryAddWithoutValidation(name, value);
            }
            return answer;
        }
    }
}
