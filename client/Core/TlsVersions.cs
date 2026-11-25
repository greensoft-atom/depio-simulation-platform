using System.Net;
using System.Security.Authentication;

namespace Backend.Client.Core
{
    /// <summary>
    /// The TLS versions the core asks for by name (D-116): an assembly built for .NET Framework 4.5 gets TLS 1.0 alone
    /// by default, on a socket and on a request, and nginx takes 1.2 and 1.3. nginx then chooses 1.2: 4.5 has no 1.3.
    /// </summary>
    public static class TlsVersions
    {
        /// <summary>What the match's and the lobby's sockets offer.</summary>
        public const SslProtocols Client = SslProtocols.Tls | SslProtocols.Tls11 | SslProtocols.Tls12;

        /// <summary>
        /// Adds TLS 1.2 and 1.1 to what this process's requests offer (<see cref="ServicePointManager"/>, which the
        /// API's requests go through); nothing is taken away.
        /// </summary>
        public static void ForRequests() =>
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls11 | SecurityProtocolType.Tls12;
    }
}
