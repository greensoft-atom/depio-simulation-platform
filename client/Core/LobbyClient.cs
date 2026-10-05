using System;
using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Backend.Client.Core
{
    public enum LobbyState
    {
        Connecting,
        /// <summary>Authenticated: requests may be made.</summary>
        Ready,
        /// <summary>Lost; trying again after a wait.</summary>
        Reconnecting,
        /// <summary>The player signed in elsewhere. Not reconnected: that would replace the other device (03 §4).</summary>
        Replaced,
        /// <summary>The token was refused, or an operator revoked it (evt.session.revoked): log in again.</summary>
        InvalidSession,
        Closed,
    }

    /// <summary>
    /// The lobby connection (docs 03 §3–§4; 08 §5): a WebSocket to /lobby, authenticated with the
    /// session token within the gateway's five seconds, a ping at least every 30 s, and requests
    /// answered by id.
    ///
    /// The socket is served by tasks of its own, one reading and one writing, so the main thread
    /// never waits on it; replies and pushes are handed to <see cref="Poll"/>. Lost, it reconnects
    /// after 1 s, doubling to 30 s, except when the server said the session is gone or has moved.
    /// </summary>
    public sealed class LobbyClient : IDisposable
    {
        public const int PingEveryMs = 30_000;       // silent for 120 s is closed (03 §3)
        /// <summary>A ping with nothing heard for this long after it: the link is gone, though no socket said so.</summary>
        public const int PingAnswerMs = 10_000;
        private const int MaxBackoffMs = 30_000;

        private readonly Uri _uri;
        private readonly IClock _clock;
        private readonly Action<ClientWebSocketOptions> _configure;
        private string _token;
        private Link _link;
        private int _nextId = 1, _authId;
        private long _nextPingAt, _reconnectAt;
        private long _pingSentAt, _heardAt;
        private readonly Random _jitter = new Random();
        private int _backoffMs = 1_000;

        /// <param name="lobby">wss://a.example.com/lobby</param>
        /// <param name="configure">A test's hook, to trust a test CA; players' devices use their own store.</param>
        public LobbyClient(Uri lobby, IClock clock, Action<ClientWebSocketOptions> configure = null)
        {
            _uri = lobby;
            _clock = clock;
            _configure = configure;
        }

        public LobbyState State { get; private set; } = LobbyState.Closed;
        public long PlayerId { get; private set; } = -1;
        /// <summary>The last error the gateway sent, and what went wrong with the socket.</summary>
        public string LastError { get; private set; }

        /// <summary>A match request's answer, once it has come: a grant, or the refusal's code.</summary>
        public MatchGrant Grant { get; private set; }
        public string GrantRefusal { get; private set; }
        private int _matchRequestId;

        /// <summary>
        /// The queue for timed matches (04 §4): "none", "queued", "confirming" or "matched". Confirming,
        /// <see cref="Ready"/> is the match found, to accept or decline; matched, <see cref="Grant"/>
        /// says where to go. A refused join leaves <see cref="QueueRefusal"/>, the server's code.
        /// </summary>
        public string QueueState { get; private set; } = "none";
        public string QueueRefusal { get; private set; }
        private int _queueJoinId;

        /// <summary>The last match found and asked about (evt.match.ready); an answer refused leaves <see cref="AnswerRefusal"/>.</summary>
        public MatchReady Ready { get; private set; }
        public string AnswerRefusal { get; private set; }
        private int _answerId;

        /// <summary>
        /// The player's party (04 §4), as the newest answer or evt.party.update gave it: null for none.
        /// An older state arriving after a newer one is not applied (D-74): read this, not the push's data.
        /// A refused party request leaves <see cref="PartyRefusal"/>, the server's code; the last
        /// invitation pushed is <see cref="Invitation"/>. After a reconnect, fetch the truth:
        /// <see cref="ApiClient.Party"/>, since a push missed while away is not sent again.
        /// </summary>
        public PartyInfo Party => _party.Party;
        private readonly PartyState _party = new PartyState();
        /// <summary>
        /// The player's team (04 §2), as the last evt.team.update gave it: null for none, or before
        /// any came. Fetch it with <see cref="ApiClient.MyTeam"/> when shown, and after a reconnect.
        /// </summary>
        public TeamInfo Team { get; private set; }
        public PartyInvitation Invitation { get; private set; }
        public string PartyRefusal { get; private set; }
        private int _partyRequestId;

        /// <summary>
        /// The last tournament match pushed (evt.tournament.match, 04 §6): where to play it. Missed
        /// while away, it is asked for: <see cref="ApiClient.TournamentMatch"/>.
        /// </summary>
        public TournamentGrant TournamentMatch { get; private set; }

        /// <summary>Called on the main thread, from Poll, for every push: evt.* messages.</summary>
        public event Action<string, JsonValue> OnPush;

        /// <summary>A phrase said to the party, evt.party.said, the player's own too; one each, from Poll.</summary>
        public event Action<PartyPhrase> OnPartySaid;

        public void Connect(string sessionToken)
        {
            _token = sessionToken;
            Dial();
        }

        /// <summary>Asks for a match; the answer lands in <see cref="Grant"/> or <see cref="GrantRefusal"/>.</summary>
        public void RequestMatch()
        {
            if (State != LobbyState.Ready) throw new InvalidOperationException("the lobby is not ready");
            Grant = null;
            GrantRefusal = null;
            _matchRequestId = Send("match.request", null);
        }

        /// <summary>Joins the queue: "duel". The match is pushed, and lands in <see cref="Grant"/>.</summary>
        public void JoinQueue(string mode)
        {
            if (State != LobbyState.Ready) throw new InvalidOperationException("the lobby is not ready");
            Grant = null;
            QueueRefusal = null;
            // A queue of its own: a match before it, played or not, is over.
            QueueState = "none";
            Ready = null;
            _queueJoinId = Send("queue.join", JsonValue.NewObject().Set("mode", mode));
        }

        /// <summary>
        /// The grant, taken to play it: the queue is the player's again, "none", and nothing is left to answer. The layer
        /// above calls this as it joins; until then <see cref="Grant"/> holds it.
        /// </summary>
        public MatchGrant TakeGrant()
        {
            MatchGrant grant = Grant;
            if (grant == null) return null;
            Grant = null;
            Ready = null;
            if (QueueState == "matched") QueueState = "none";
            return grant;
        }

        public void LeaveQueue()
        {
            if (State != LobbyState.Ready) throw new InvalidOperationException("the lobby is not ready");
            QueueState = "none";
            Send("queue.leave", null);
        }

        /// <summary>Accepts the match found (<see cref="Ready"/>); once all have, it arrives as evt.match.found.</summary>
        public void AcceptMatch() => Answer("match.accept");

        /// <summary>Declines it: out of the queue, and locked out of it for a minute (D-27).</summary>
        public void DeclineMatch() => Answer("match.decline");

        private void Answer(string type)
        {
            if (State != LobbyState.Ready) throw new InvalidOperationException("the lobby is not ready");
            if (Ready == null) throw new InvalidOperationException("no match found to answer");
            AnswerRefusal = null;
            _answerId = Send(type, JsonValue.NewObject().Set("matchUid", Ready.MatchUid));
        }

        /// <summary>Invites a player in the lobby to the party, made if there is none; its leader only.</summary>
        public void InviteToParty(long playerId) => PartyRequest("party.invite", JsonValue.NewObject().Set("playerId", playerId));

        public void AcceptInvitation(string partyId) => PartyRequest("party.accept", JsonValue.NewObject().Set("partyId", partyId));

        /// <summary>Leaves the party; a queued party is out of the queue for all its members.</summary>
        public void LeaveParty() => PartyRequest("party.leave", null);

        public void KickFromParty(long playerId) => PartyRequest("party.kick", JsonValue.NewObject().Set("playerId", playerId));

        /// <summary>
        /// Says a phrase to the party (docs 01 §9). One every two seconds: one sooner is refused,
        /// <see cref="PartyRefusal"/> "too_soon", as is one not in the list, "unknown_phrase".
        /// </summary>
        public void SayToParty(int phraseId) => PartyRequest("party.say", JsonValue.NewObject().Set("phraseId", phraseId));

        private void PartyRequest(string type, JsonValue data)
        {
            if (State != LobbyState.Ready) throw new InvalidOperationException("the lobby is not ready");
            PartyRefusal = null;
            _partyRequestId = Send(type, data);
        }

        public void Poll()
        {
            long now = _clock.NowMs;
            Link link = _link;
            while (link != null && link.Inbox.TryDequeue(out string text))
            {
                _heardAt = now;
                Handle(text, now);
            }

            if (link != null && link.Closed && link.Inbox.IsEmpty
                && (State == LobbyState.Connecting || State == LobbyState.Ready))
                Lost(link.Failure ?? LastError ?? "the lobby connection closed", now);
            // A link gone silently, a machine lost or a path cut, keeps its socket open for minutes: a ping
            // with nothing heard after it says so first.
            if (State == LobbyState.Ready && _pingSentAt > _heardAt && now - _pingSentAt > PingAnswerMs)
                Lost("no answer to a ping in " + PingAnswerMs / 1000 + " s", now);
            if (State == LobbyState.Reconnecting && now >= _reconnectAt) Dial();

            if (State == LobbyState.Ready && now >= _nextPingAt)
            {
                Send("ping", null);
                if (_pingSentAt <= _heardAt) _pingSentAt = now;      // the first unanswered one is the one timed
                _nextPingAt = now + PingEveryMs;
            }
        }

        /// <summary>Into Reconnecting, after a wait that doubles to 30 s, each spread ±50 % so a gateway's clients do not return at once.</summary>
        private void Lost(string why, long now)
        {
            LastError = why;
            _link?.Close();
            State = LobbyState.Reconnecting;
            _reconnectAt = now + _backoffMs / 2 + _jitter.Next(_backoffMs + 1);
            _backoffMs = Math.Min(_backoffMs * 2, MaxBackoffMs);
        }

        private void Handle(string text, long now)
        {
            JsonValue m;
            try { m = JsonValue.Parse(text); }
            catch (FormatException) { return; }            // not ours to guess at
            string t = m["t"].AsString ?? "";
            int id = m["id"].AsInt;
            JsonValue d = m["d"];
            if (t == "auth.ok" && id == _authId)
            {
                PlayerId = d["playerId"].AsLong;
                State = LobbyState.Ready;
                _pingSentAt = 0;
                _backoffMs = 1_000;
                _nextPingAt = now + PingEveryMs;
            }
            else if (t == "match.request.ok" && id == _matchRequestId && id != 0)
            {
                Grant = ApiClient.ReadGrant(d);
            }
            else if (t == "queue.join.ok" && id == _queueJoinId && id != 0)
            {
                // Unless the match was pushed first: the reply and the push race, and matched wins.
                if (QueueState != "matched") QueueState = d["state"].AsString ?? "queued";
            }
            else if (t == "evt.match.found")
            {
                Grant = ApiClient.ReadGrant(d);
                QueueState = "matched";
                Ready = null;                                   // found: nothing left to answer
                OnPush?.Invoke(t, d);
            }
            else if (t == "evt.match.ready")
            {
                Ready = new MatchReady { MatchUid = d["matchUid"].AsString, Mode = d["mode"].AsString, Seconds = d["seconds"].AsInt };
                if (QueueState != "matched") QueueState = "confirming";
                OnPush?.Invoke(t, d);
            }
            else if (t == "match.decline.ok" && id == _answerId && id != 0)
            {
                Ready = null;                                   // declined: out of the queue, nothing to answer
            }
            else if (t.StartsWith("party.", StringComparison.Ordinal) && t.EndsWith(".ok", StringComparison.Ordinal)
                     && id == _partyRequestId && id != 0)
            {
                _party.Apply(d);
            }
            else if (t == "evt.party.update")
            {
                _party.Apply(d);
                OnPush?.Invoke(t, d);
            }
            else if (t == "evt.team.update")
            {
                Team = d["team"].IsNull ? null : ApiClient.ReadTeam(d["team"]);
                OnPush?.Invoke(t, d);
            }
            else if (t == "evt.party.invite")
            {
                Invitation = new PartyInvitation { PartyId = d["partyId"].AsString, From = d["from"].AsLong, FromName = d["fromName"].AsString };
                OnPush?.Invoke(t, d);
            }
            else if (t == "evt.party.said")
            {
                OnPartySaid?.Invoke(new PartyPhrase { From = d["from"].AsLong, Name = d["name"].AsString, PhraseId = d["phraseId"].AsInt });
                OnPush?.Invoke(t, d);
            }
            else if (t == "evt.tournament.match")
            {
                TournamentMatch = ApiClient.ReadTournamentGrant(d);
                OnPush?.Invoke(t, d);
            }
            else if (t == "evt.queue.update")
            {
                // The leader queued the party, or someone took it out. Matched wins, as for the reply:
                // a match is the member's own from then on, whatever the queue says after.
                if (QueueState != "matched") QueueState = d["state"].AsString ?? "none";
                Ready = null;                                   // the match asked about was called off, or found
                OnPush?.Invoke(t, d);
            }
            else if (t == "error")
            {
                string code = d["code"].AsString;
                LastError = code + ": " + d["message"].AsString;
                if (id != 0 && id == _authId && code == "invalid_session") Finish(LobbyState.InvalidSession);
                else if (id != 0 && id == _matchRequestId) GrantRefusal = code;
                else if (id != 0 && id == _queueJoinId) QueueRefusal = code;
                else if (id != 0 && id == _partyRequestId) PartyRefusal = code;
                else if (id != 0 && id == _answerId) AnswerRefusal = code;
            }
            else if (t == "evt.session.replaced")
            {
                Finish(LobbyState.Replaced);
            }
            else if (t == "evt.session.revoked")
            {
                // Every session ended by an operator: a ban or a suspension (04 §10). Not retried:
                // logging in again is what to do, and it says whether the account may play.
                LastError = "session revoked: " + d["reason"].AsString;
                Finish(LobbyState.InvalidSession);
            }
            else if (t.StartsWith("evt.", StringComparison.Ordinal))
            {
                OnPush?.Invoke(t, d);
            }
        }

        private void Dial()
        {
            _link?.Close();
            State = LobbyState.Connecting;
            _link = new Link(_uri, _configure);
            // Queued before the socket is open: the writer sends it first, inside the five seconds.
            _authId = Send("auth", JsonValue.NewObject().Set("token", _token));
        }

        private int Send(string type, JsonValue data)
        {
            int id = _nextId++;
            var m = JsonValue.NewObject().Set("t", type).Set("id", id);
            if (data != null) m.Set("d", data);
            _link?.Outbox.Add(m.ToString());
            return id;
        }

        private void Finish(LobbyState end)
        {
            State = end;
            _link?.Close();
        }

        public void Dispose() => Finish(LobbyState.Closed);

        /// <summary>One WebSocket, with a task reading it and a task writing it.</summary>
        private sealed class Link
        {
            public readonly ConcurrentQueue<string> Inbox = new ConcurrentQueue<string>();
            public readonly BlockingCollection<string> Outbox = new BlockingCollection<string>();
            private readonly ClientWebSocket _socket = new ClientWebSocket();
            private readonly CancellationTokenSource _stop = new CancellationTokenSource();
            private volatile bool _closed;
            public volatile string Failure;

            public Link(Uri uri, Action<ClientWebSocketOptions> configure)
            {
                configure?.Invoke(_socket.Options);
                Task.Run(() => Run(uri));
            }

            public bool Closed => _closed;

            private async Task Run(Uri uri)
            {
                try
                {
                    await _socket.ConnectAsync(uri, _stop.Token).ConfigureAwait(false);
                    Task writer = Task.Run(Write);
                    var buffer = new byte[8 * 1024];
                    // Bytes, decoded once whole: a fragment can end inside a character, and decoded alone each half
                    // is a replacement character.
                    var message = new System.IO.MemoryStream();
                    while (_socket.State == WebSocketState.Open)
                    {
                        WebSocketReceiveResult r = await _socket.ReceiveAsync(new ArraySegment<byte>(buffer), _stop.Token).ConfigureAwait(false);
                        if (r.MessageType == WebSocketMessageType.Close)
                        {
                            Failure = "closed by the lobby: " + r.CloseStatusDescription;
                            break;
                        }
                        message.Write(buffer, 0, r.Count);
                        if (!r.EndOfMessage) continue;           // a message in fragments, joined
                        Inbox.Enqueue(Encoding.UTF8.GetString(message.GetBuffer(), 0, (int)message.Length));
                        message.SetLength(0);
                    }
                }
                catch (Exception e)
                {
                    if (!_closed) Failure = e.GetBaseException().Message;
                }
                Close();
            }

            private void Write()
            {
                try
                {
                    foreach (string text in Outbox.GetConsumingEnumerable(_stop.Token))
                    {
                        byte[] bytes = Encoding.UTF8.GetBytes(text);
                        _socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, _stop.Token)
                            .GetAwaiter().GetResult();
                    }
                }
                catch (Exception e)
                {
                    if (!_closed) Failure = e.GetBaseException().Message;
                    Close();
                }
            }

            public void Close()
            {
                if (_closed) return;
                _closed = true;
                try { _stop.Cancel(); } catch (ObjectDisposedException) { }
                try { _socket.Abort(); } catch (Exception) { /* closing, whatever state it was in */ }
            }
        }
    }
}
