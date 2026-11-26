using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Threading;

namespace Backend.Client.Simple
{
    /// <summary>
    /// One message from the lobby (docs/api/02-lobby-websocket.md §2): a reply <c>{"t":"ping.ok","id":3,"d":{}}</c>,
    /// an error <c>{"t":"error","id":3,"d":{"code":...,"message":...}}</c> or a push <c>{"t":"evt.notice","d":{...}}</c>.
    /// </summary>
    public sealed class LobbyMessage
    {
        /// <summary>Its type: "auth.ok", "error", "evt.match.found"...</summary>
        public string Type;
        /// <summary>The id of the request it answers; 0 for a push.</summary>
        public int Id;
        /// <summary>Its <c>d</c> as JSON text, "{}" when it has none: read it with JsonUtility.FromJson, or GetString below.</summary>
        public string Data;
        /// <summary>The whole message as received, for a log.</summary>
        public string Text;

        public bool IsError => Type == "error";
        /// <summary>An error's code ("unknown_type", "invalid_session", "rate_limited"...); null for any other message.</summary>
        public string ErrorCode => IsError ? Json.GetString(Data, "code") : null;

        public string GetString(string field) => Json.GetString(Data, field);
        public long GetLong(string field) => Json.GetLong(Data, field);
        public bool GetBool(string field) => Json.GetBool(Data, field);

        public static LobbyMessage Parse(string text) => new LobbyMessage
        {
            Type = Json.GetString(text, "t"),
            Id = (int)Json.GetLong(text, "id"),
            Data = Json.GetRaw(text, "d") ?? "{}",
            Text = text,
        };
    }

    /// <summary>
    /// The lobby: <c>new LobbyClient("ws://192.168.1.20/lobby")</c>, set the callbacks, <c>Connect(token)</c>, and
    /// call <see cref="Update"/> every frame. It connects on a thread of its own, authenticates with the session's
    /// token, sends a ping every 25 s, and hands every reply and push to its callback inside Update, on Unity's main
    /// thread. Requests and their replies: <c>Send("queue.join", "{\"mode\":\"duel\"}", reply => ...)</c>.
    /// </summary>
    public sealed class LobbyClient
    {
        /// <summary>The lobby took the token: the player's id. Requests may be sent from here on.</summary>
        public Action<long> OnAuthenticated;
        /// <summary>A push (<c>evt.match.found</c>, <c>evt.party.update</c>, <c>evt.notice</c>...), or an error the lobby sent without an id.</summary>
        public Action<LobbyMessage> OnPush;
        /// <summary>The connection is over, and why. Called once per Connect, by Close() too.</summary>
        public Action<string> OnClosed;
        /// <summary>A ping this often keeps the connection; the lobby closes one silent for 120 s.</summary>
        public float HeartbeatSeconds = 25;

        public bool IsAuthenticated { get; private set; }

        private readonly string _url;
        private readonly ConcurrentQueue<Action> _events = new ConcurrentQueue<Action>();
        private readonly Dictionary<int, Action<LobbyMessage>> _waiting = new Dictionary<int, Action<LobbyMessage>>();
        private WebSocketConnection _socket;
        private int _nextId = 1;
        private DateTime _lastSent;
        private string _refused;

        /// <param name="url"><c>ws://192.168.1.20/lobby</c>: the site's 80, or a backend run plain (PLAIN=1); this client has no TLS</param>
        public LobbyClient(string url)
        {
            if (!url.StartsWith("ws://", StringComparison.Ordinal))
                throw new ArgumentException("a ws:// address: this client has no TLS (" + url + ")");
            _url = url;
        }

        /// <summary>Connects and authenticates with the token from POST /v1/sessions; OnAuthenticated or OnClosed follows.</summary>
        public void Connect(string token)
        {
            Close();
            var socket = new WebSocketConnection(_url);
            _socket = socket;
            new Thread(() => Run(socket, token)) { IsBackground = true, Name = "lobby" }.Start();
        }

        /// <summary>
        /// Sends <c>{"t": type, "id": n, "d": data}</c>; the reply (<c>type + ".ok"</c>, or an error) goes to onReply.
        /// False when the lobby is not connected: nothing was sent.
        /// </summary>
        /// <param name="data">the request's <c>d</c> as JSON text, or null</param>
        public bool Send(string type, string data, Action<LobbyMessage> onReply = null)
        {
            if (_socket == null) return false;
            int id = _nextId++;
            if (onReply != null) _waiting[id] = onReply;
            _lastSent = DateTime.UtcNow;
            try
            {
                _socket.Send("{\"t\":" + Json.Quote(type) + ",\"id\":" + id + (data != null ? ",\"d\":" + data : "") + "}");
            }
            catch (Exception)
            {
                // The connection broke: its reader says so, and OnClosed follows.
            }
            return true;
        }

        /// <summary>Hands what came in to the callbacks, and keeps the heartbeat. Call it every frame.</summary>
        public void Update()
        {
            Action e;
            while (_events.TryDequeue(out e)) e();
            if (IsAuthenticated && (DateTime.UtcNow - _lastSent).TotalSeconds >= HeartbeatSeconds) Send("ping", null);
        }

        /// <summary>Closes the connection at once; OnClosed follows in the next Update.</summary>
        public void Close()
        {
            WebSocketConnection socket = _socket;
            if (socket == null) return;
            Forget(socket, "closed by the client");
            socket.Close();
        }

        /// <summary>The connection's own thread: it connects, then reads until the connection ends.</summary>
        private void Run(WebSocketConnection socket, string token)
        {
            string why;
            try
            {
                socket.Open();
                // The first message must be auth, within 5 s; Send runs on the main thread, so it goes from there.
                _events.Enqueue(() =>
                {
                    if (_socket == socket) Send("auth", "{\"token\":" + Json.Quote(token) + "}", OnAuth);
                });
                while (true)
                {
                    string text = socket.Receive();
                    if (text == null)
                    {
                        why = "the lobby closed the connection: " + socket.CloseReason;
                        break;
                    }
                    LobbyMessage message = LobbyMessage.Parse(text);
                    _events.Enqueue(() => { if (_socket == socket) Dispatch(message); });
                }
            }
            catch (Exception e)
            {
                why = e.Message;
            }
            socket.Dispose();
            _events.Enqueue(() => { if (_socket == socket) Forget(socket, why); });
        }

        private void OnAuth(LobbyMessage reply)
        {
            if (reply.Type != "auth.ok")
            {
                _refused = reply.ErrorCode;             // invalid_session: the lobby closes, and OnClosed says why
                return;
            }
            IsAuthenticated = true;
            OnAuthenticated?.Invoke(reply.GetLong("playerId"));
        }

        private void Dispatch(LobbyMessage message)
        {
            Action<LobbyMessage> onReply;
            if (message.Id != 0 && _waiting.TryGetValue(message.Id, out onReply))
            {
                _waiting.Remove(message.Id);
                onReply(message);
            }
            else if (message.Id == 0)
            {
                OnPush?.Invoke(message);
            }
        }

        /// <summary>The connection's end, once: its waiting replies dropped, OnClosed told in Update.</summary>
        private void Forget(WebSocketConnection socket, string why)
        {
            if (_socket != socket) return;
            _socket = null;
            IsAuthenticated = false;
            _waiting.Clear();
            if (_refused != null) why = _refused + ": " + why;
            _refused = null;
            _events.Enqueue(() => OnClosed?.Invoke(why));
        }
    }
}
