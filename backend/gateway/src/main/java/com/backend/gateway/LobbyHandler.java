package com.backend.gateway;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.backend.common.Metrics;
import com.backend.handoff.SessionStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One lobby connection (docs detailed-design/03-gateway.md §3–§4).
 *
 * <h2>Shape of the protocol</h2>
 *
 * JSON, in contrast to the hand-packed binary of the match protocol, and for the opposite
 * reason: this traffic is low volume and changes constantly, so legibility and painless
 * evolution beat bytes. A request carries an {@code id} that its reply echoes; a message
 * without one is a server push.
 *
 * <h2>Why unknown types are answered</h2>
 *
 * An unrecognised {@code t} gets an error rather than silence, so a newer client talking to
 * an older gateway sees a clear failure instead of a request that never completes.
 *
 * Whole messages arrive here: fragments are joined in front of it, and {@link FrameLimit}
 * has already counted every frame.
 */
final class LobbyHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger log = LoggerFactory.getLogger(LobbyHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * An unauthenticated connection costs memory and a file descriptor and can be opened by
     * anyone, so it does not get to sit there indefinitely (03 §4).
     */
    static final int AUTH_DEADLINE_SECONDS = 5;

    private final SessionStore sessions;
    private final PlatformClient platform;
    private final ConnectionRegistry registry;

    private String sessionToken;
    private long playerId = -1;

    /**
     * A session lookup is in flight. Event loop only, like every field here. Without it the
     * "already authenticated?" check ran before the lookup and the answer after, so two
     * auth frames both passed and one connection registered as two players (T-2).
     */
    private boolean authPending;
    private ScheduledFuture<?> authDeadline;

    /** Every error sent, by code; and every successful auth. Shared by the gateway's handlers. */
    private final Metrics.LabeledCounter errors;
    private final java.util.concurrent.atomic.LongAdder authenticated;

    LobbyHandler(SessionStore sessions, PlatformClient platform, ConnectionRegistry registry) {
        this(sessions, platform, registry, new Metrics.LabeledCounter(),
                new java.util.concurrent.atomic.LongAdder());
    }

    LobbyHandler(SessionStore sessions, PlatformClient platform, ConnectionRegistry registry,
                 Metrics.LabeledCounter errors, java.util.concurrent.atomic.LongAdder authenticated) {
        this.errors = errors;
        this.authenticated = authenticated;
        this.sessions = sessions;
        this.platform = platform;
        this.registry = registry;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
            // Silent for the idle limit: gone, or a client that does not ping (03 §3). The idle
            // handler only raises this, and nothing acted on it, so a socket connected straight
            // to a gateway was never closed. Told why, so a client can tell it from a fault.
            log.debug("closing a lobby connection silent for its idle limit");
            ctx.writeAndFlush(new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE.code(), "idle"))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            authDeadline = ctx.executor().schedule(() -> {
                if (playerId < 0) {
                    log.debug("no auth within {}s, closing", AUTH_DEADLINE_SECONDS);
                    fail(ctx, 0, "auth_timeout", "authenticate within "
                            + AUTH_DEADLINE_SECONDS + " seconds");
                    ctx.close();
                }
            }, AUTH_DEADLINE_SECONDS, TimeUnit.SECONDS);
        }
        super.userEventTriggered(ctx, event);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof TextWebSocketFrame text)) {
            fail(ctx, 0, "text_only", "the lobby speaks JSON in text frames");
            return;
        }
        JsonNode message;
        try {
            message = MAPPER.readTree(text.text());
        } catch (Exception malformed) {
            fail(ctx, 0, "bad_json", "that was not a JSON object");
            return;
        }
        JsonNode typeNode = message.get("t");
        if (typeNode == null) {
            fail(ctx, id(message), "no_type", "every message needs a \"t\"");
            return;
        }
        String type = typeNode.asText();

        if (!"auth".equals(type) && playerId < 0) {
            fail(ctx, id(message), "not_authenticated", "send auth first");
            return;
        }
        switch (type) {
            case "auth" -> handleAuth(ctx, message);
            case "match.request" -> forward(ctx, message, platform.requestMatch(sessionToken),
                    "match.request.ok");
            // The queue for timed matches (04 §4); the match found is pushed, evt.match.found.
            case "queue.join" -> forward(ctx, message,
                    platform.joinQueue(sessionToken, message.path("d").path("mode").asText(null)),
                    "queue.join.ok");
            case "queue.leave" -> forward(ctx, message, platform.leaveQueue(sessionToken),
                    "queue.leave.ok");
            // Parties (04 §4): the invitation and the party's changes are pushed, evt.party.*.
            case "party.invite" -> party(ctx, message, "invite", "playerId");
            case "party.accept" -> party(ctx, message, "accept", "partyId");
            case "party.leave" -> party(ctx, message, "leave", null);
            case "party.kick" -> party(ctx, message, "kick", "playerId");
            case "party.say" -> party(ctx, message, "say", "phraseId");         // 01 §9
            // The answer to evt.match.ready (04 §4, the third slice).
            case "match.accept" -> post(ctx, message, "/v1/queue/accept", "matchUid");
            case "match.decline" -> post(ctx, message, "/v1/queue/decline", "matchUid");
            case "ping" -> reply(ctx, "ping.ok", id(message), MAPPER.createObjectNode());
            default -> fail(ctx, id(message), "unknown_type", "no such message: " + type);
        }
    }

    // ---- messages ------------------------------------------------------------------------

    private void party(ChannelHandlerContext ctx, JsonNode message, String action, String field) {
        post(ctx, message, "/v1/party/" + action, field);
    }

    private void post(ChannelHandlerContext ctx, JsonNode message, String path, String field) {
        JsonNode value = field == null ? null : message.path("d").get(field);
        forward(ctx, message, platform.post(sessionToken, path, value, field), message.get("t").asText() + ".ok");
    }

    private void handleAuth(ChannelHandlerContext ctx, JsonNode message) {
        if (playerId >= 0) {
            fail(ctx, id(message), "already_authenticated", "this connection is already a player");
            return;
        }
        if (authPending) {
            fail(ctx, id(message), "auth_in_progress", "wait for the answer to the first auth");
            return;
        }
        JsonNode token = message.path("d").get("token");
        if (token == null || token.asText().isEmpty()) {
            fail(ctx, id(message), "no_token", "auth needs d.token");
            return;
        }
        String candidate = token.asText();
        int requestId = id(message);

        // Straight to the store, not through platform: a session lookup is one HGET, and
        // routing it through another process would add a hop to every connection for nothing.
        authPending = true;
        sessions.playerIdOf(candidate).whenComplete((resolved, error) -> ctx.executor().execute(() -> {
            authPending = false;
            if (!ctx.channel().isActive()) {
                // Closed while the lookup was in flight, and its cleanup has already run and
                // found no player. Registering now would leave a dead channel in the map, and
                // a conn: entry routing this player's pushes to it for a minute.
                return;
            }
            if (error != null) {
                log.warn("session lookup failed: {}", error.toString());
                fail(ctx, requestId, "internal", "try again shortly");
                return;
            }
            if (resolved == null || resolved < 0) {
                fail(ctx, requestId, "invalid_session", "log in again");
                ctx.close();
                return;
            }
            playerId = resolved;
            sessionToken = candidate;
            if (authDeadline != null) {
                authDeadline.cancel(false);
            }
            // auth.ok once the store has the registration, so a push sent the moment it arrives
            // finds the player (T-39); without it nothing could reach them: closed, and they come back.
            long registered = playerId;
            registry.register(registered, ctx.channel()).whenComplete((done, failed) -> ctx.executor().execute(() -> {
                if (failed != null) {
                    log.warn("registering player {} failed: {}", registered, failed.toString());
                    fail(ctx, requestId, "internal", "try again shortly");
                    ctx.close();
                    return;
                }
                ObjectNode data = MAPPER.createObjectNode();
                data.put("playerId", registered);
                reply(ctx, "auth.ok", requestId, data);
                authenticated.increment();
                log.debug("player {} authenticated", registered);
            }));
        }));
    }

    /** Answers a request with what platform said to it: its body on success, its code if not. */
    private void forward(ChannelHandlerContext ctx, JsonNode message,
                         java.util.concurrent.CompletableFuture<PlatformClient.Reply> call, String okType) {
        int requestId = id(message);
        call.whenComplete((reply, error) ->
                ctx.executor().execute(() -> {
                    if (error != null || reply == null) {
                        fail(ctx, requestId, "internal", "try again shortly");
                        return;
                    }
                    if (reply.ok()) {
                        reply(ctx, okType, requestId, reply.body());
                        return;
                    }
                    // Passed through rather than reinterpreted: platform decides what a
                    // refusal means, and a gateway that rewrote it would be making a product
                    // decision it has no business making.
                    fail(ctx, requestId, reply.code(), "platform refused the request");
                    if (reply.status() == 401) {
                        ctx.close();                // the session died under us
                    }
                }));
    }

    // ---- plumbing ------------------------------------------------------------------------

    private static int id(JsonNode message) {
        JsonNode node = message.get("id");
        return node == null ? 0 : node.asInt();
    }

    private static void reply(ChannelHandlerContext ctx, String type, int id, JsonNode data) {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("t", type);
        if (id != 0) {
            frame.put("id", id);
        }
        frame.set("d", data == null ? MAPPER.createObjectNode() : data);
        ctx.writeAndFlush(new TextWebSocketFrame(frame.toString()));
    }

    private void fail(ChannelHandlerContext ctx, int id, String code, String message) {
        errors.increment(code);
        ObjectNode data = MAPPER.createObjectNode();
        data.put("code", code);
        data.put("message", message);
        reply(ctx, "error", id, data);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (authDeadline != null) {
            authDeadline.cancel(false);
        }
        if (playerId >= 0) {
            registry.unregister(playerId, ctx.channel());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("closing {} after {}", FrameLimit.clientOf(ctx.channel()), cause.toString());
        ctx.close();
    }
}
