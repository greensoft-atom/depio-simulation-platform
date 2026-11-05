using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Backend.Client.Core
{
    /// <summary>
    /// The lobby's WebSocket, the client's side of RFC 6455, over a TCP stream or <see cref="SslStream"/> (D-108):
    /// the TLS check is then the backend root's, for the lobby's host, on every runtime, where .NET Standard 2.0's
    /// <c>ClientWebSocket</c> has no callback of its own and Unity 2019's checks through the whole app's
    /// <c>ServicePointManager</c>. Text messages alone: the handshake, masked frames out, fragments joined in, a
    /// ping answered, a close. One reader and one writer at a time, as the lobby's two tasks are; a pong the reader
    /// sends waits for the writer's frame.
    /// </summary>
    internal sealed class WebSocketLink : IDisposable
    {
        private const string Guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        /// <summary>Far above any lobby message; a longer one is a broken stream.</summary>
        private const int MaxMessage = 1 << 20;

        private readonly TcpClient _tcp;
        private readonly Stream _stream;
        private readonly SemaphoreSlim _write = new SemaphoreSlim(1, 1);
        private readonly RandomNumberGenerator _random = RandomNumberGenerator.Create();

        /// <summary>The lobby's reason when it closed, its text as sent ("" for none).</summary>
        public string CloseReason { get; private set; }

        private WebSocketLink(TcpClient tcp, Stream stream)
        {
            _tcp = tcp;
            _stream = stream;
        }

        /// <param name="trust">the root a <c>wss://</c> lobby's certificate must chain to; null, the runtime's own check</param>
        public static async Task<WebSocketLink> ConnectAsync(Uri uri, TrustRoot trust, CancellationToken cancel)
        {
            bool secure = uri.Scheme == "wss";
            if (!secure && uri.Scheme != "ws") throw new ArgumentException("not a ws:// or wss:// address: " + uri, nameof(uri));
            int port = uri.Port > 0 ? uri.Port : secure ? 443 : 80;
            var tcp = new TcpClient { NoDelay = true };
            try
            {
                using (cancel.Register(tcp.Close))
                {
                    await tcp.ConnectAsync(uri.Host, port).ConfigureAwait(false);
                    Stream stream = tcp.GetStream();
                    if (secure)
                    {
                        var tls = new SslStream(stream, false, trust?.CheckFor(uri.Host));
                        await tls.AuthenticateAsClientAsync(uri.Host).ConfigureAwait(false);
                        stream = tls;
                    }
                    var link = new WebSocketLink(tcp, stream);
                    await link.Handshake(uri, cancel).ConfigureAwait(false);
                    return link;
                }
            }
            catch
            {
                tcp.Close();
                cancel.ThrowIfCancellationRequested();
                throw;
            }
        }

        private async Task Handshake(Uri uri, CancellationToken cancel)
        {
            var nonce = new byte[16];
            _random.GetBytes(nonce);
            string key = Convert.ToBase64String(nonce);
            string request = "GET " + uri.PathAndQuery + " HTTP/1.1\r\nHost: " + uri.Authority + "\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
            byte[] bytes = Encoding.ASCII.GetBytes(request);
            await _stream.WriteAsync(bytes, 0, bytes.Length, cancel).ConfigureAwait(false);
            await _stream.FlushAsync(cancel).ConfigureAwait(false);

            // The answer's head, byte by byte: what follows it is the lobby's first frame.
            var head = new List<byte>();
            var one = new byte[1];
            while (head.Count < 4 || head[head.Count - 4] != '\r' || head[head.Count - 3] != '\n'
                   || head[head.Count - 2] != '\r' || head[head.Count - 1] != '\n')
            {
                if (head.Count > 8192) throw new IOException("the lobby's answer to the WebSocket's opening has no end");
                await ReadExactly(one, 1, cancel).ConfigureAwait(false);
                head.Add(one[0]);
            }
            string[] lines = Encoding.ASCII.GetString(head.ToArray()).Split(new[] { "\r\n" }, StringSplitOptions.RemoveEmptyEntries);
            if (lines.Length == 0 || !lines[0].StartsWith("HTTP/1.1 101", StringComparison.Ordinal))
                throw new IOException("the lobby refused the WebSocket: " + (lines.Length == 0 ? "no answer" : lines[0]));
            string accept = null, upgrade = null;
            foreach (string line in lines)
            {
                int colon = line.IndexOf(':');
                if (colon < 0) continue;
                string name = line.Substring(0, colon).Trim(), value = line.Substring(colon + 1).Trim();
                if (name.Equals("Sec-WebSocket-Accept", StringComparison.OrdinalIgnoreCase)) accept = value;
                else if (name.Equals("Upgrade", StringComparison.OrdinalIgnoreCase)) upgrade = value;
            }
            string expected;
            using (SHA1 sha = SHA1.Create())
                expected = Convert.ToBase64String(sha.ComputeHash(Encoding.ASCII.GetBytes(key + Guid)));
            if (!"websocket".Equals(upgrade, StringComparison.OrdinalIgnoreCase) || accept != expected)
                throw new IOException("the lobby's answer is not a WebSocket's: its Sec-WebSocket-Accept does not match");
        }

        /// <summary>The next text message; null once the lobby closed, its reason in <see cref="CloseReason"/>.</summary>
        public async Task<string> ReceiveAsync(CancellationToken cancel)
        {
            var message = new MemoryStream();
            bool inMessage = false;
            var two = new byte[8];
            while (true)
            {
                await ReadExactly(two, 2, cancel).ConfigureAwait(false);
                bool fin = (two[0] & 0x80) != 0;
                int op = two[0] & 0x0F;
                if ((two[0] & 0x70) != 0) throw new IOException("a frame with reserved bits set: no extension was agreed");
                if ((two[1] & 0x80) != 0) throw new IOException("a masked frame from the lobby, which RFC 6455 refuses");
                long length = two[1] & 0x7F;
                if (length == 126)
                {
                    await ReadExactly(two, 2, cancel).ConfigureAwait(false);
                    length = (two[0] << 8) | two[1];
                }
                else if (length == 127)
                {
                    await ReadExactly(two, 8, cancel).ConfigureAwait(false);
                    length = 0;
                    for (int i = 0; i < 8; i++) length = (length << 8) | two[i];
                }
                if (length < 0 || length + message.Length > MaxMessage)
                    throw new IOException("a message over " + MaxMessage + " bytes");
                var payload = new byte[length];
                await ReadExactly(payload, payload.Length, cancel).ConfigureAwait(false);

                if (op >= 8)
                {
                    if (!fin || length > 125) throw new IOException("a control frame in pieces, or over 125 bytes");
                    if (op == 8)
                    {
                        CloseReason = payload.Length > 2 ? Encoding.UTF8.GetString(payload, 2, payload.Length - 2) : "";
                        try { await Send(0x8, payload.Length >= 2 ? new[] { payload[0], payload[1] } : new byte[0], cancel).ConfigureAwait(false); }
                        catch (Exception) { /* closing either way */ }
                        return null;
                    }
                    if (op == 9) await Send(0xA, payload, cancel).ConfigureAwait(false);
                    continue;                                   // a pong: nothing to do
                }
                if (op == 1 && !inMessage) inMessage = true;
                else if (op != 0 || !inMessage) throw new IOException("frame " + op + " out of place: the lobby sends text alone");
                message.Write(payload, 0, payload.Length);
                if (!fin) continue;                             // a message in fragments, joined
                // Bytes, decoded once whole: a fragment can end inside a character.
                return Encoding.UTF8.GetString(message.GetBuffer(), 0, (int)message.Length);
            }
        }

        public Task SendAsync(string text, CancellationToken cancel) => Send(0x1, Encoding.UTF8.GetBytes(text), cancel);

        /// <summary>One frame, whole and masked, as a client's must be.</summary>
        private async Task Send(int op, byte[] payload, CancellationToken cancel)
        {
            int extra = payload.Length < 126 ? 0 : payload.Length <= 0xFFFF ? 2 : 8;
            var frame = new byte[2 + extra + 4 + payload.Length];
            frame[0] = (byte)(0x80 | op);
            if (extra == 0) frame[1] = (byte)(0x80 | payload.Length);
            else if (extra == 2)
            {
                frame[1] = 0x80 | 126;
                frame[2] = (byte)(payload.Length >> 8);
                frame[3] = (byte)payload.Length;
            }
            else
            {
                frame[1] = 0x80 | 127;
                for (int i = 0; i < 8; i++) frame[2 + i] = (byte)((long)payload.Length >> (56 - 8 * i));
            }
            var mask = new byte[4];
            _random.GetBytes(mask);
            Buffer.BlockCopy(mask, 0, frame, 2 + extra, 4);
            for (int i = 0; i < payload.Length; i++) frame[2 + extra + 4 + i] = (byte)(payload[i] ^ mask[i & 3]);
            await _write.WaitAsync(cancel).ConfigureAwait(false);
            try
            {
                await _stream.WriteAsync(frame, 0, frame.Length, cancel).ConfigureAwait(false);
                await _stream.FlushAsync(cancel).ConfigureAwait(false);
            }
            finally
            {
                _write.Release();
            }
        }

        private async Task ReadExactly(byte[] buffer, int count, CancellationToken cancel)
        {
            for (int got = 0; got < count;)
            {
                int n = await _stream.ReadAsync(buffer, got, count - got, cancel).ConfigureAwait(false);
                if (n <= 0) throw new EndOfStreamException("the lobby's connection ended");
                got += n;
            }
        }

        /// <summary>Closes the connection at once, a read or a write waiting on it included.</summary>
        public void Abort()
        {
            try { _stream.Dispose(); } catch (Exception) { }
            try { _tcp.Close(); } catch (Exception) { }
        }

        public void Dispose() => Abort();
    }
}
