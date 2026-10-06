package com.backend.arena;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import com.backend.common.Metrics;
import com.backend.handoff.MatchMode;
import com.backend.handoff.Ticket;
import com.backend.handoff.TicketStore;
import com.backend.protocol.ClientMessage;
import com.backend.protocol.SnapshotWriter;
import com.backend.protocol.Wire;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decodes client frames on a Netty thread and hands the result to the room.
 *
 * It does three things and touches no simulation state: validate, decode into the
 * connection's atomic slots, and enqueue joins, resumes and leaves. Anything that changes the
 * world happens on the room thread (docs detailed-design/07 §1).
 */
public final class MatchFrameHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(MatchFrameHandler.class);

    /**
     * A misbehaving client is disconnected rather than throttled: throttling still costs us.
     * Counted as a bucket that refills at this rate, not in fixed seconds: a phone whose
     * uplink stalls queues its ten Inputs a second in its socket and delivers them together
     * when the link returns, and a fixed second counted that backlog as a flood.
     */
    private static final int MAX_MESSAGES_PER_SECOND = 60;
    /**
     * What the bucket holds: the 30 s a connection may stay silent (02 §1), at ten Inputs a
     * second and a Ping every ten, is 303 frames; 330 leaves a tenth to spare.
     */
    private static final double MAX_BURST = 330;

    /** Ticket ids are 22 characters. The bound is here so a length prefix cannot allocate a heap. */
    private static final int MAX_TICKET_BYTES = 64;

    /**
     * How long a connection may go on doing nothing useful before the arena closes it
     * (02-networking §1 and §11).
     *
     * @param joinDeadlineMillis from connecting to sending {@code Join} or {@code Resume}, a
     *        TLS handshake included. Nothing that has not joined needs a socket for longer, and
     *        the idle rule alone would let a connection that pings and never joins keep one for
     *        ever.
     * @param readerIdleMillis without one whole frame. A client in the foreground sends 20
     *        inputs a second and every client pings every 10 s, so this is three missed
     *        pings: a phone that has gone, or one suspended in the background.
     * @param stallMillis unwritable throughout, so nothing sent to the client drained. Its
     *        snapshots were skipped all that time: the player was watching a frozen world.
     */
    record Limits(long joinDeadlineMillis, long readerIdleMillis, long stallMillis) {
        static final Limits DEFAULT = new Limits(10_000, 30_000, 5_000);
    }

    private final RoomRegistry registry;
    private final TicketStore tickets;
    private final Limits limits;
    /** Connections closed by the arena without the client asking, by reason. */
    private final Metrics.LabeledCounter dropped;
    /** How each join ended, for the tickets never claimed (04 §11). */
    private final Metrics.LabeledCounter joins;
    private Connection connection;

    /** Which unwritable spell a stall check belongs to; see {@link #channelWritabilityChanged}. */
    private int writabilityChanges;

    /** This handler closed the connection, and has counted why. */
    private boolean dropping;

    /**
     * The room this connection joined. Written when the claim completes and read in
     * {@link #channelInactive}; both run on this channel's event loop, so a disconnect that
     * races the claim is simply ordered before or after it.
     */
    private RoomThread room;

    /**
     * A Join or Resume has been accepted for processing. It stays set, so a second one closes
     * the connection. Netty thread only, so a plain field is enough.
     */
    private boolean joinPending;

    private long refilledAtNanos;
    private double tokens = MAX_BURST;

    public MatchFrameHandler(RoomRegistry registry, TicketStore tickets) {
        this(registry, tickets, Limits.DEFAULT, new Metrics.LabeledCounter(), new Metrics.LabeledCounter());
    }

    MatchFrameHandler(RoomRegistry registry, TicketStore tickets, Limits limits,
                      Metrics.LabeledCounter dropped, Metrics.LabeledCounter joins) {
        this.registry = registry;
        this.tickets = tickets;
        this.limits = limits;
        this.dropped = dropped;
        this.joins = joins;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        connection = new Connection(ctx.channel());
        refilledAtNanos = System.nanoTime();
        ctx.executor().schedule(() -> {
            if (!joinPending && ctx.channel().isActive()) {
                // Still handshaking is a handshake that took too long, which is what TLS's own
                // timeout would have called it had it fired first: the reason does not depend
                // on which of the two did.
                SslHandler tls = ctx.pipeline().get(SslHandler.class);
                boolean handshaking = tls != null && !tls.handshakeFuture().isDone();
                drop(ctx, handshaking ? "tls_handshake" : "join_deadline");
            }
        }, limits.joinDeadlineMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Closes a connection that has stayed unwritable for {@link Limits#stallMillis}. Each
     * change starts a new spell, so a check scheduled by an earlier one finds the count
     * moved on and does nothing: only an unbroken spell closes the connection.
     *
     * Closed without a kick: the kick would queue behind everything that is not draining.
     */
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        int spell = ++writabilityChanges;
        if (!ctx.channel().isWritable()) {
            ctx.executor().schedule(() -> {
                if (spell == writabilityChanges && !ctx.channel().isWritable()
                        && ctx.channel().isActive()) {
                    drop(ctx, "stalled");
                }
            }, limits.stallMillis(), TimeUnit.MILLISECONDS);
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
            drop(ctx, "idle");
        } else if (event instanceof SslHandshakeCompletionEvent handshake && !handshake.isSuccess()
                && !dropping) {
            // The TLS handler has closed the connection already; this only counts it. A
            // client that rejects the certificate - expired, or naming another host - shows
            // up here and nowhere else on the server. Not when this handler closed a connection
            // mid-handshake itself: that also ends here, and was counted when it was dropped.
            dropped.increment("tls_handshake");
            log.debug("TLS handshake with {} failed: {}", ctx.channel().remoteAddress(),
                    handshake.cause().toString());
        }
        ctx.fireUserEventTriggered(event);
    }

    /** Logged when it is a player's connection: those are the ones someone asks about. */
    private void drop(ChannelHandlerContext ctx, String reason) {
        dropping = true;
        dropped.increment(reason);
        Ticket who = connection == null ? null : connection.identity();
        if (who != null) {
            log.info("closing player {} ({}): {}", who.playerId(), ctx.channel().remoteAddress(), reason);
        } else {
            log.debug("closing {}: {}", ctx.channel().remoteAddress(), reason);
        }
        ctx.close();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
        if (!allow(ctx)) {
            return;
        }
        if (!frame.isReadable()) {
            ctx.close();
            return;
        }
        int type = frame.readUnsignedByte();
        switch (type) {
            case ClientMessage.JOIN -> handleJoin(ctx, frame);
            case ClientMessage.RESUME -> handleResume(ctx, frame);
            case ClientMessage.INPUT -> handleInput(frame);
            case ClientMessage.PING -> handlePing(ctx, frame);
            case ClientMessage.RESPAWN -> handleRespawn();
            case ClientMessage.LIFECYCLE -> handleLifecycle(frame);
            case ClientMessage.LEAVE -> {
                connection.endStay();          // on purpose: nothing waits for them (02 §10)
                ctx.close();
            }
            case ClientMessage.UPGRADE_STAT -> handleUpgradeStat(frame);
            case ClientMessage.CHOOSE_CLASS -> handleChooseClass(frame);
            case ClientMessage.PHRASE -> handlePhrase(frame);
            case ClientMessage.SANDBOX -> handleSandbox(frame);

            default -> {
                // A type outside the protocol is either a broken client or a probe, and
                // neither is worth keeping a socket open for.
                log.debug("unknown message type {}", type);
                ctx.close();
            }
        }
    }

    /**
     * Asks the room to spend a skill point.
     *
     * Validated on the room thread rather than here: whether a point is available depends on
     * the tank, and the tank belongs to that thread. This one only refuses what is not a
     * stat at all, and silently — a client whose UI is a release ahead of the server should
     * see a request that does nothing, not a closed socket.
     */
    private void handleUpgradeStat(ByteBuf frame) {
        if (!frame.isReadable() || connection.entityId() < 0) {
            return;
        }
        connection.requestUpgrade(frame.readUnsignedByte());
    }

    /** Asks the room to pass a phrase on; the room decides whether it may be said, and to whom (01 §9). */
    private void handlePhrase(ByteBuf frame) {
        if (connection == null || !frame.isReadable()) {
            return;
        }
        long phrase = readVarint(frame);
        // Wider than an int is no phrase: cast, it could wrap round to one.
        connection.requestPhrase(phrase <= Integer.MAX_VALUE ? (int) phrase : Connection.NO_PHRASE);
    }

    /** Asks the room for a sandbox's power; the room decides whether it is one, and what it allows (01 §8.10). */
    private void handleSandbox(ByteBuf frame) {
        if (connection == null || frame.readableBytes() < 2) {
            return;
        }
        int action = frame.readUnsignedByte();
        long value = readVarint(frame);
        // Wider than an int is no level: cast, it could wrap round to one.
        connection.requestSandbox(action, value >= 0 && value <= Integer.MAX_VALUE ? (int) value : -1);
    }

    /** Asks the room for a tank class; the room decides whether it may be had, as for a point. */
    private void handleChooseClass(ByteBuf frame) {
        if (!frame.isReadable() || connection.entityId() < 0) {
            return;
        }
        connection.requestClass(frame.readUnsignedByte());
    }

    /**
     * Starts the join. The ticket is claimed from j-redis, which is a network round trip, so
     * nothing here waits for it; blocking this thread would stall every other connection
     * sharing the event loop.
     *
     * The join continues back on this channel's event loop, not on the thread the store's
     * reply arrived on (T-1). That thread is the store client's only I/O thread, and the join
     * allocates a room, which can mean building one: 7-20 ms measured, during which every
     * other store call in the process — the announcement, result pushes, every other
     * player's claim — waited. Here, a build stalls one event loop instead, rarely, and all of
     * this handler's state stays on one thread.
     */
    private void handleJoin(ChannelHandlerContext ctx, ByteBuf frame) {
        if (joinPending || connection.entityId() >= 0) {
            ctx.close();                       // a second join on one connection is a bug or an attack
            return;
        }
        int version = frame.readUnsignedByte();
        if (version != Wire.VERSION) {
            // Reject explicitly rather than letting a mis-parse look like corruption.
            log.info("rejecting protocol version {} (expected {})", version, Wire.VERSION);
            Frames.kick(ctx.channel(), Wire.KICK_PROTOCOL_VERSION);
            return;
        }
        String ticketId = readTicketId(frame);
        if (ticketId == null) {
            Frames.kick(ctx.channel(), Wire.KICK_BAD_TICKET);
            return;
        }
        // Optional, after the ticket: the most this client wants sent (02 §8). Clients built
        // before it existed send nothing more, and get mobile.
        connection.setCeiling(TrafficProfile.fromWire(
                frame.isReadable() ? frame.readUnsignedByte() : Wire.PROFILE_MOBILE));
        joinPending = true;
        tickets.claim(ticketId).whenCompleteAsync(
                (ticket, error) -> onTicketClaimed(ctx, ticket, error), ctx.executor());
    }

    /**
     * A client coming back after a lost connection, with the secret its last Welcome gave it,
     * in place of a ticket (02 §10). Nothing to claim from the store: the stay is in the
     * memory of the room that holds it, and the registry says which room that is. Unknown or
     * ended is {@link Wire#KICK_BAD_TICKET}: back to the lobby for a ticket, as for any join
     * that cannot be honoured.
     */
    private void handleResume(ChannelHandlerContext ctx, ByteBuf frame) {
        if (joinPending || connection.entityId() >= 0) {
            ctx.close();
            return;
        }
        int version = frame.readUnsignedByte();
        if (version != Wire.VERSION) {
            log.info("rejecting protocol version {} (expected {})", version, Wire.VERSION);
            Frames.kick(ctx.channel(), Wire.KICK_PROTOCOL_VERSION);
            return;
        }
        String secret = readTicketId(frame);
        RoomThread target = secret == null ? null : registry.roomFor(secret);
        if (target == null) {
            log.info("refusing resume from {}: no such stay", ctx.channel().remoteAddress());
            Frames.kick(ctx.channel(), Wire.KICK_BAD_TICKET);
            return;
        }
        connection.setCeiling(TrafficProfile.fromWire(
                frame.isReadable() ? frame.readUnsignedByte() : Wire.PROFILE_MOBILE));
        joinPending = true;
        connection.setResuming(secret);
        if (!target.offerResume(connection)) {
            Frames.kick(ctx.channel(), Wire.KICK_INTERNAL);
            return;
        }
        // Only a room that took the resume is owed this connection's leave: a refused one would
        // be sent a leave it never drains. Both run on this event loop, so no close falls between.
        room = target;
    }

    /** Runs on this channel's event loop, like everything else in this handler. */
    private void onTicketClaimed(ChannelHandlerContext ctx, Ticket ticket, Throwable error) {
        if (error != null) {
            // The store being unreachable is our fault, not the client's, and it must not
            // look like a bad ticket: the player would be sent to get another one, which
            // also needs the store.
            log.warn("ticket claim failed: {}", error.toString());
            joins.increment("claim_failed");
            Frames.kick(ctx.channel(), Wire.KICK_INTERNAL);
            return;
        }
        if (ticket == null) {
            log.info("refusing join from {}: no such ticket", ctx.channel().remoteAddress());
            joins.increment("bad_ticket");
            Frames.kick(ctx.channel(), Wire.KICK_BAD_TICKET);
            return;
        }
        if (connection.isClosing() || !ctx.channel().isActive()) {
            // Gone while we were claiming. The ticket is spent, which is the right outcome:
            // it was used, and a client that reconnects gets a fresh one from the lobby.
            return;
        }

        if (registry.removed(ticket.playerId())) {
            log.info("refusing join from player {}: taken out by an operator a moment ago", ticket.playerId());
            joins.increment("removed");
            Frames.kick(ctx.channel(), Wire.KICK_REMOVED);
            return;
        }
        // A place in a made match goes to that match's room, made by its first ticket (D-20).
        RoomThread target = ticket.isMatch()
                ? registry.allocateMatch(ticket.matchUid(), MatchMode.ofId(ticket.mode()))
                : registry.allocate();
        // After the place is sought (D-79): reserved, the announcement that drops the player's seat promise counts
        // them; refused, they hold nothing here.
        registry.seated(ticket.playerId());
        if (target == null) {
            log.info("no room for player {}{}", ticket.playerId(),
                    ticket.isMatch() ? " in match " + ticket.matchUid() : ": every room is full");
            joins.increment("no_room");
            Frames.kick(ctx.channel(), Wire.KICK_ROOM_FULL);
            return;
        }
        connection.identify(ticket);
        room = target;                          // before the offer: see the field's comment
        if (!target.offerJoin(connection)) {
            target.releaseReservation();
            log.info("join queue full, refusing player {}", ticket.playerId());
            joins.increment("no_room");
            Frames.kick(ctx.channel(), Wire.KICK_ROOM_FULL);
            return;
        }
        joins.increment("joined");
    }

    /** @return the ticket id, or null if the frame does not carry a plausible one. */
    private static String readTicketId(ByteBuf frame) {
        long length = readVarint(frame);
        if (length <= 0 || length > MAX_TICKET_BYTES || length > frame.readableBytes()) {
            return null;
        }
        return frame.readCharSequence((int) length, StandardCharsets.US_ASCII).toString();
    }

    private void handleInput(ByteBuf frame) {
        if (connection.entityId() < 0) {
            return;                            // input before join: ignore, do not disconnect
        }
        int seq = (int) readVarint(frame);
        int ack = (int) readVarint(frame);
        int move = frame.readUnsignedByte();
        int aim = frame.readUnsignedShortLE();
        int flags = frame.readUnsignedByte();
        connection.offerInput(seq, aim, move, flags, ack);
    }

    /**
     * Answers immediately on this thread rather than going through the room.
     *
     * A ping measures the round trip, so queueing it behind a tick would measure the tick
     * instead. The tick it reports is read from an atomic the room publishes each step.
     */
    private void handlePing(ChannelHandlerContext ctx, ByteBuf frame) {
        if (frame.readableBytes() < 4) {
            return;
        }
        long clientTimeMs = frame.readUnsignedInt();
        RoomThread joined = room;
        SnapshotWriter w = new SnapshotWriter(16);
        w.u8(Wire.MSG_PONG);
        w.u32(clientTimeMs);
        w.varint(joined == null ? 0 : joined.currentTick());
        Frames.write(ctx.channel(), w.array(), w.length());
    }

    private void handleRespawn() {
        if (connection != null) {
            connection.requestRespawn();        // the room decides; a live tank is not replaced
        }
    }

    private void handleLifecycle(ByteBuf frame) {
        if (connection == null || !frame.isReadable()) {
            return;
        }
        int state = frame.readUnsignedByte();
        connection.setBackgrounded(state == ClientMessage.LIFECYCLE_BACKGROUND);
    }

    private static long readVarint(ByteBuf in) {
        long v = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return v;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalStateException("varint too long");
            }
        }
    }

    private boolean allow(ChannelHandlerContext ctx) {
        long now = System.nanoTime();
        tokens = Math.min(MAX_BURST, tokens + (now - refilledAtNanos) * (MAX_MESSAGES_PER_SECOND / 1e9));
        refilledAtNanos = now;
        if (--tokens < 0) {
            log.info("rate limit exceeded, closing {}", ctx.channel().remoteAddress());
            connection.endStay();
            Frames.kick(ctx.channel(), Wire.KICK_RATE_LIMIT);
            return false;
        }
        return true;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (connection != null) {
            // Order matters: marking first means a join still in the queue is dropped by the
            // room's drain, and reading the room after means a join that got there first is
            // still cleaned up.
            connection.markClosing();
            registry.connectionEnded(connection);       // its own rate, for NFR-2 (02 §13)
            RoomThread joined = room;
            if (joined != null) {
                joined.offerLeave(connection);    // null when the client never joined
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("closing {} after {}", ctx.channel().remoteAddress(), cause.toString());
        ctx.close();
    }
}
