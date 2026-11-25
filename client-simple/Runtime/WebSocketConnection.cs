using System;
using System.IO;
using System.Net.Security;
using System.Security.Authentication;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;

namespace Backend.Client.Simple
{
    /// <summary>
    /// The client's side of a WebSocket (RFC 6455), text messages alone, over TCP or TLS: what <see cref="LobbyClient"/>
    /// talks through. Its own, because .NET Framework 4.5's ClientWebSocket cannot be told to trust the backend's root
    /// (D-108). Blocking: <see cref="Open"/> and <see cref="Receive"/> run on LobbyClient's thread, <see cref="Send"/>
    /// on the caller's; one reader and one writer at a time.
    /// </summary>
    public sealed class WebSocketConnection : IDisposable
    {
        private const string Guid = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        private const int MaxMessage = 1 << 20;

        private readonly Uri _uri;
        private readonly TrustRoot _trust;
        private readonly object _writing = new object();
        private readonly RandomNumberGenerator _random = RandomNumberGenerator.Create();
        private TcpClient _tcp;
        private Stream _stream;

        /// <summary>The reason the server gave when it closed, as "1000 Bye".</summary>
        public string CloseReason { get; private set; }

        /// <param name="url"><c>wss://203.0.113.10/lobby</c>, or <c>ws://</c> in development</param>
        /// <param name="trust">the root a wss:// server's certificate must come from; null, the runtime's own check</param>
        public WebSocketConnection(string url, TrustRoot trust)
        {
            _uri = new Uri(url);
            if (_uri.Scheme != "ws" && _uri.Scheme != "wss") throw new ArgumentException("not a ws:// or wss:// address: " + url);
            _trust = trust;
        }

        /// <summary>Connects: TCP, then TLS for wss://, then the WebSocket's opening handshake.</summary>
        public void Open(int timeoutMs = 10000)
        {
            bool secure = _uri.Scheme == "wss";
            _tcp = new TcpClient { NoDelay = true };
            IAsyncResult connecting = _tcp.BeginConnect(_uri.Host, _uri.Port, null, null);
            if (!connecting.AsyncWaitHandle.WaitOne(timeoutMs)) throw new TimeoutException("no connection to " + _uri.Authority + " in " + timeoutMs / 1000 + " s");
            _tcp.EndConnect(connecting);
            _tcp.ReceiveTimeout = 60000;               // the lobby answers our ping every 25 s: a minute of nothing is a dead line
            Stream stream = _tcp.GetStream();
            if (secure)
            {
                var tls = new SslStream(stream, false, _trust?.CheckFor(_uri.Host));
                // TLS 1.2 by name: built for .NET Framework 4.5, a socket offers 1.0 alone by default (D-116).
                tls.AuthenticateAsClient(_uri.Host, null, SslProtocols.Tls | SslProtocols.Tls11 | SslProtocols.Tls12, false);
                stream = tls;
            }
            _stream = stream;

            var nonce = new byte[16];
            _random.GetBytes(nonce);
            string key = Convert.ToBase64String(nonce);
            Write(Encoding.ASCII.GetBytes("GET " + _uri.PathAndQuery + " HTTP/1.1\r\nHost: " + _uri.Authority
                + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n"));
            // The answer's head, a byte at a time: what follows it is already the first message.
            var head = new StringBuilder();
            while (!head.ToString().EndsWith("\r\n\r\n", StringComparison.Ordinal))
            {
                int b = _stream.ReadByte();
                if (b < 0 || head.Length > 8192) throw new IOException("the server's answer to the WebSocket's opening ended early");
                head.Append((char)b);
            }
            string answer = head.ToString();
            if (!answer.StartsWith("HTTP/1.1 101", StringComparison.Ordinal))
                throw new IOException("the server refused the WebSocket: " + answer.Split('\r')[0]);
            bool accepted = false;
            foreach (string line in answer.Split(new[] { "\r\n" }, StringSplitOptions.RemoveEmptyEntries))
            {
                int colon = line.IndexOf(':');
                if (colon > 0 && line.Substring(0, colon).Trim().Equals("Sec-WebSocket-Accept", StringComparison.OrdinalIgnoreCase))
                    accepted = line.Substring(colon + 1).Trim() == AcceptKey(key);
            }
            if (!accepted) throw new IOException("the server's answer is not a WebSocket's");
        }

        /// <summary>The next text message, waiting for it; null once the server closed (see <see cref="CloseReason"/>).</summary>
        public string Receive()
        {
            var message = new MemoryStream();
            var head = new byte[8];
            while (true)
            {
                ReadExactly(head, 2);
                bool fin = (head[0] & 0x80) != 0;
                int opcode = head[0] & 0x0F;
                long length = head[1] & 0x7F;
                if (length == 126)
                {
                    ReadExactly(head, 2);
                    length = head[0] << 8 | head[1];
                }
                else if (length == 127)
                {
                    ReadExactly(head, 8);
                    length = 0;
                    for (int i = 0; i < 8; i++) length = length << 8 | head[i];
                }
                if (length < 0 || message.Length + length > MaxMessage) throw new IOException("a message over " + MaxMessage + " bytes");
                var payload = new byte[length];
                ReadExactly(payload, payload.Length);

                if (opcode == 8)                       // close: answer it, then the connection is over
                {
                    int code = payload.Length >= 2 ? payload[0] << 8 | payload[1] : 1005;
                    CloseReason = code + (payload.Length > 2 ? " " + Encoding.UTF8.GetString(payload, 2, payload.Length - 2) : "");
                    try { Write(ClientFrame(8, payload.Length >= 2 ? new[] { payload[0], payload[1] } : new byte[0], NewMask())); }
                    catch (Exception) { }
                    return null;
                }
                if (opcode == 9) { Write(ClientFrame(10, payload, NewMask())); continue; }   // ping: pong
                if (opcode == 10) continue;                                                     // pong
                message.Write(payload, 0, payload.Length);                                      // text, or its next piece
                if (fin) return Encoding.UTF8.GetString(message.ToArray());
            }
        }

        public void Send(string text) => Write(ClientFrame(1, Encoding.UTF8.GetBytes(text), NewMask()));

        /// <summary>Says goodbye (close 1000) and closes; a Receive waiting on it ends.</summary>
        public void Close()
        {
            try { Write(ClientFrame(8, new byte[] { 0x03, 0xE8 }, NewMask())); }
            catch (Exception) { }
            Dispose();
        }

        public void Dispose()
        {
            try { _stream?.Dispose(); } catch (Exception) { }
            try { _tcp?.Close(); } catch (Exception) { }
        }

        /// <summary>Sec-WebSocket-Accept for a key: what the server must answer (RFC 6455 §4.2.2).</summary>
        public static string AcceptKey(string key)
        {
            using (SHA1 sha = SHA1.Create())
                return Convert.ToBase64String(sha.ComputeHash(Encoding.ASCII.GetBytes(key + Guid)));
        }

        /// <summary>One whole frame from a client, masked as RFC 6455 §5.3 requires.</summary>
        public static byte[] ClientFrame(int opcode, byte[] payload, byte[] mask)
        {
            int extra = payload.Length < 126 ? 0 : payload.Length <= 0xFFFF ? 2 : 8;
            var frame = new byte[2 + extra + 4 + payload.Length];
            frame[0] = (byte)(0x80 | opcode);
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
            Buffer.BlockCopy(mask, 0, frame, 2 + extra, 4);
            for (int i = 0; i < payload.Length; i++) frame[2 + extra + 4 + i] = (byte)(payload[i] ^ mask[i & 3]);
            return frame;
        }

        private byte[] NewMask()
        {
            var mask = new byte[4];
            _random.GetBytes(mask);
            return mask;
        }

        private void Write(byte[] bytes)
        {
            lock (_writing)
            {
                _stream.Write(bytes, 0, bytes.Length);
                _stream.Flush();
            }
        }

        private void ReadExactly(byte[] buffer, int count)
        {
            for (int got = 0; got < count;)
            {
                int n = _stream.Read(buffer, got, count - got);
                if (n <= 0) throw new EndOfStreamException("the connection ended");
                got += n;
            }
        }
    }
}
