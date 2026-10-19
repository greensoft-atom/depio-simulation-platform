using System;
using System.Linq;
using System.Net.Http;
using System.Net.Security;
using System.Net.WebSockets;
using System.Security.Cryptography.X509Certificates;

namespace Backend.Client.Core
{
    /// <summary>
    /// The backend's own root (D-82): the site has no domain, so no public CA certifies it, and the app ships with
    /// the root's certificate and trusts it alone, for the API, the lobby and matches. A server's certificate is
    /// accepted when its chain ends at that root and it names the host or the grant's TLS name; nothing is pinned,
    /// so the server's certificate is renewed without a release.
    ///
    /// Written for netstandard2.1, which Unity runs: the chain is built with the root as an extra certificate and
    /// unknown authorities allowed, and then must end at that root, the same certificate byte for byte.
    /// </summary>
    public sealed class TrustRoot
    {
        private readonly X509Certificate2 _root;

        public TrustRoot(X509Certificate2 root)
        {
            _root = root ?? throw new ArgumentNullException(nameof(root));
        }

        /// <summary>The root from its PEM text, as the app carries it (ca.pem from scripts/private-ca.sh).</summary>
        public static TrustRoot FromPem(string pem)
        {
            const string begin = "-----BEGIN CERTIFICATE-----", end = "-----END CERTIFICATE-----";
            int from = pem?.IndexOf(begin, StringComparison.Ordinal) ?? -1;
            int to = from < 0 ? -1 : pem.IndexOf(end, from, StringComparison.Ordinal);
            if (from < 0 || to < 0) throw new ArgumentException("no PEM certificate in it", nameof(pem));
            string body = pem.Substring(from + begin.Length, to - from - begin.Length);
            byte[] der;
            try
            {
                der = Convert.FromBase64String(new string(body.Where(c => !char.IsWhiteSpace(c)).ToArray()));
            }
            catch (FormatException e)
            {
                throw new ArgumentException("the PEM certificate is not base64", nameof(pem), e);
            }
            return new TrustRoot(new X509Certificate2(der));
        }

        /// <summary>
        /// Whether a server's certificate is the backend's, given what the handshake found. The name was checked by
        /// the handshake, against the host or the grant's TLS name, and must have matched; the chain is checked here,
        /// which the device's store cannot do for a root it does not hold.
        /// </summary>
        public bool Accepts(X509Certificate certificate, SslPolicyErrors errors)
        {
            if (certificate == null) return false;
            if ((errors & (SslPolicyErrors.RemoteCertificateNameMismatch | SslPolicyErrors.RemoteCertificateNotAvailable)) != 0)
                return false;
            using (var leaf = new X509Certificate2(certificate))
            using (var chain = new X509Chain())
            {
                chain.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
                chain.ChainPolicy.VerificationFlags = X509VerificationFlags.AllowUnknownCertificateAuthority;
                chain.ChainPolicy.ExtraStore.Add(_root);
                if (!chain.Build(leaf)) return false;               // expired, a bad signature, not for a server...
                X509Certificate2 top = chain.ChainElements[chain.ChainElements.Count - 1].Certificate;
                return top.RawData.SequenceEqual(_root.RawData);    // and it ends at the backend's root, not another
            }
        }

        /// <summary>For a match's TLS (MatchSettings.CertificateCheck) and the lobby's WebSocket.</summary>
        public RemoteCertificateValidationCallback Check => (sender, certificate, chain, errors) => Accepts(certificate, errors);

        /// <summary>For the API: the handler an ApiClient is made with.</summary>
        public HttpMessageHandler HttpHandler() => new HttpClientHandler
        {
            ServerCertificateCustomValidationCallback = (request, certificate, chain, errors) => Accepts(certificate, errors),
        };

        /// <summary>For the lobby: a LobbyClient's configure.</summary>
        public void Configure(ClientWebSocketOptions options) => options.RemoteCertificateValidationCallback = Check;
    }
}
