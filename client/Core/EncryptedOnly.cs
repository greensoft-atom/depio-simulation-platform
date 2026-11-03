using System;
using System.Collections.Generic;

namespace Backend.Client.Core
{
    /// <summary>
    /// What a players' build connects with (D-104): <c>https://</c> for the API, <c>wss://</c> for the lobby, and a
    /// match grant with TLS. A development build, the drills and the headless driver may go plain (D-101): the Unity
    /// layer asks this only when its build is not a development one.
    /// </summary>
    public static class EncryptedOnly
    {
        private const string Rule = "a players' build connects encrypted alone (D-104): ";

        /// <returns>Why these addresses are refused, or null when each is encrypted.</returns>
        public static string Refusal(IReadOnlyList<string> apiEndpoints, string lobbyUrl)
        {
            if (apiEndpoints == null || apiEndpoints.Count == 0) return Rule + "no API endpoint";
            foreach (string endpoint in apiEndpoints)
            {
                if (!Is(endpoint, Uri.UriSchemeHttps)) return Rule + "the API endpoint " + endpoint + " is not https://";
            }
            if (!Is(lobbyUrl, "wss")) return Rule + "the lobby " + lobbyUrl + " is not wss://";
            return null;
        }

        /// <returns>Why this grant is refused, or null when its match is over TLS.</returns>
        public static string Refusal(MatchGrant grant) =>
            grant.Tls ? null : Rule + "the match at " + grant.ArenaHost + ":" + grant.ArenaPort + " was granted without TLS";

        // Uri lowers the scheme: HTTPS:// is https://.
        private static bool Is(string address, string scheme) =>
            Uri.TryCreate(address, UriKind.Absolute, out Uri uri) && uri.Scheme == scheme;
    }
}
