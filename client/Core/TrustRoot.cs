using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Security;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Backend.Client.Core
{
    /// <summary>
    /// The backend's own root (D-82): the site has no domain, so no public CA certifies it, and the app ships with
    /// the root's certificate and trusts it alone, for the API, the lobby and matches. A server's certificate is
    /// accepted when the root issued it and it names the host dialled; nothing is pinned, so the server's certificate
    /// is renewed without a release.
    ///
    /// Checked here, the same on every runtime (D-108): the runtime's own verdict is not asked, since it calls every
    /// private root a chain error, Unity's TLS may not read an IP address as a name, and Unity 2019's Mono can build
    /// no chain of an EC key at all. The certificate's signature (RSA PKCS#1, SHA-256) verifies with the root's key,
    /// which only a certificate the root issued can do, it is valid now, and its subjectAltName holds the host: an
    /// IP address or a DNS name. The root's key is RSA, which every runtime the app runs on can verify.
    /// </summary>
    public sealed class TrustRoot
    {
        private readonly RSA _key;

        public TrustRoot(X509Certificate2 root)
        {
            if (root == null) throw new ArgumentNullException(nameof(root));
            RSA key = null;
            try { key = root.GetRSAPublicKey(); }
            catch (CryptographicException) { }              // Unity 2019's Mono, on an EC key
            catch (NotImplementedException) { }
            _key = key ?? throw new ArgumentException(
                "the root's key is not RSA: the backend's root is RSA since D-108; make it again (scripts/private-ca.sh)",
                nameof(root));
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

        /// <summary>Whether a server's certificate is the backend's, for the host the client dialled.</summary>
        public bool Accepts(X509Certificate certificate, string host)
        {
            if (certificate == null || string.IsNullOrEmpty(host)) return false;
            Certificate c;
            try
            {
                c = Certificate.Read(certificate.GetRawCertData());
            }
            catch (FormatException)
            {
                return false;
            }
            if (!_key.VerifyData(c.Signed, c.Signature, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1)) return false;
            DateTime now = DateTime.UtcNow;
            if (now < c.NotBefore || now > c.NotAfter) return false;
            return IPAddress.TryParse(host, out IPAddress ip)
                ? c.Addresses.Any(a => a.SequenceEqual(ip.GetAddressBytes()))
                : c.Names.Any(n => string.Equals(n, host, StringComparison.OrdinalIgnoreCase));
        }

        /// <summary>The check for one connection: a match's under the grant's name, the lobby's under its host.</summary>
        public RemoteCertificateValidationCallback CheckFor(string host) =>
            (sender, certificate, chain, errors) => Accepts(certificate, host);

        /// <summary>For the API: the handler an ApiClient is made with, each request checked for its own host.</summary>
        public HttpMessageHandler HttpHandler() => new WebRequestTransport(this);

        /// <summary>The fields of a certificate this check reads, from its DER (RFC 5280 §4.1).</summary>
        private sealed class Certificate
        {
            public byte[] Signed, Signature;
            public DateTime NotBefore, NotAfter;
            public readonly List<string> Names = new List<string>();
            public readonly List<byte[]> Addresses = new List<byte[]>();

            public static Certificate Read(byte[] der)
            {
                var c = new Certificate();
                var top = new Der(der, 0, der?.Length ?? 0).Sequence();
                var tbs = top.Next(0x30);
                c.Signed = tbs.Whole();
                top.Next(0x30);                                    // the signature's algorithm: SHA-256's alone verifies
                byte[] bits = top.Next(0x03).Content();
                if (bits.Length < 1 || bits[0] != 0) throw new FormatException("a signature with unused bits");
                c.Signature = bits.Skip(1).ToArray();

                Der t = tbs.Inner();
                if (t.Peek() == 0xA0) t.Next(0xA0);               // the version
                t.Next(0x02);                                      // the serial number
                t.Next(0x30);                                      // the signature's algorithm, again
                t.Next(0x30);                                      // the issuer: the signature says who
                Der validity = t.Next(0x30).Inner();
                c.NotBefore = Time(validity.Next());
                c.NotAfter = Time(validity.Next());
                t.Next(0x30);                                      // the subject
                t.Next(0x30);                                      // the subject's key
                while (!t.Done)
                {
                    Der field = t.Next();
                    if (field.Tag != 0xA3) continue;              // the issuer's and subject's unique ids
                    Der extensions = field.Inner().Next(0x30).Inner();
                    while (!extensions.Done)
                    {
                        Der e = extensions.Next(0x30).Inner();
                        byte[] id = e.Next(0x06).Content();
                        if (e.Peek() == 0x01) e.Next(0x01);       // critical
                        Der value = e.Next(0x04);
                        // subjectAltName, 2.5.29.17: dNSName [2], iPAddress [7]
                        if (id.Length == 3 && id[0] == 0x55 && id[1] == 0x1D && id[2] == 0x11)
                        {
                            Der names = value.Inner().Next(0x30).Inner();
                            while (!names.Done)
                            {
                                Der n = names.Next();
                                if (n.Tag == 0x82) c.Names.Add(System.Text.Encoding.ASCII.GetString(n.Content()));
                                else if (n.Tag == 0x87) c.Addresses.Add(n.Content());
                            }
                        }
                    }
                }
                return c;
            }

            /// <summary>
            /// UTCTime (YYMMDDHHMMSSZ, its years 50 to 99 the 1900s) or GeneralizedTime (YYYYMMDDHHMMSSZ), as RFC 5280
            /// §4.1.2.5 has them: read digit by digit, as runtimes disagree on where two-digit years turn (Mono's 2029).
            /// </summary>
            private static DateTime Time(Der d)
            {
                string s = System.Text.Encoding.ASCII.GetString(d.Content());
                int digits = d.Tag == 0x17 ? 12 : d.Tag == 0x18 ? 14 : -1;
                if (digits < 0 || s.Length != digits + 1 || s[digits] != 'Z' || !s.Take(digits).All(char.IsDigit))
                    throw new FormatException("a time that is not RFC 5280's: " + s);
                Func<int, int, int> at = (from, n) => int.Parse(s.Substring(from, n), System.Globalization.CultureInfo.InvariantCulture);
                int y = digits == 12 ? at(0, 2) : at(0, 4), k = digits - 10;
                if (digits == 12) y += y < 50 ? 2000 : 1900;
                try
                {
                    return new DateTime(y, at(k, 2), at(k + 2, 2), at(k + 4, 2), at(k + 6, 2), at(k + 8, 2), DateTimeKind.Utc);
                }
                catch (ArgumentOutOfRangeException)
                {
                    throw new FormatException("a time that is not one: " + s);
                }
            }
        }

        /// <summary>DER's tag, length and value, read one after another in a span of an array; FormatException when broken.</summary>
        private struct Der
        {
            private readonly byte[] _bytes;
            private readonly int _end;
            private int _at;
            public readonly int Tag, Start, ContentStart;

            public Der(byte[] bytes, int from, int end) : this(bytes, from, end, -1, from, from) { }

            private Der(byte[] bytes, int from, int end, int tag, int start, int contentStart)
            {
                if (bytes == null) throw new FormatException("no bytes");
                _bytes = bytes; _at = from; _end = end; Tag = tag; Start = start; ContentStart = contentStart;
            }

            public bool Done => _at >= _end;

            public int Peek() => Done ? -1 : _bytes[_at];

            /// <summary>The next element, whatever its tag.</summary>
            public Der Next()
            {
                if (_end - _at < 2) throw new FormatException("ends inside a tag");
                int start = _at, tag = _bytes[_at++], length = _bytes[_at++];
                if ((tag & 0x1F) == 0x1F) throw new FormatException("a tag of more than one byte");
                if (length >= 0x80)
                {
                    int count = length & 0x7F;
                    if (count == 0 || count > 3 || _end - _at < count) throw new FormatException("a length DER does not write");
                    length = 0;
                    for (int i = 0; i < count; i++) length = (length << 8) | _bytes[_at++];
                }
                if (length > _end - _at) throw new FormatException("a length past the end");
                var element = new Der(_bytes, _at, _at + length, tag, start, _at);
                _at += length;
                return element;
            }

            public Der Next(int tag)
            {
                Der d = Next();
                if (d.Tag != tag) throw new FormatException($"tag 0x{d.Tag:X2} where 0x{tag:X2} was expected");
                return d;
            }

            /// <summary>The single SEQUENCE these bytes are, its content to read.</summary>
            public Der Sequence()
            {
                Der d = Next(0x30);
                if (!Done) throw new FormatException("bytes after the certificate");
                return d;
            }

            /// <summary>This element's content, as elements to read.</summary>
            public Der Inner() => new Der(_bytes, ContentStart, _end);

            public byte[] Content() => Copy(ContentStart, _end);

            /// <summary>This element, its tag and length too.</summary>
            public byte[] Whole() => Copy(Start, _end);

            private byte[] Copy(int from, int to)
            {
                var b = new byte[to - from];
                Buffer.BlockCopy(_bytes, from, b, 0, b.Length);
                return b;
            }
        }
    }
}
