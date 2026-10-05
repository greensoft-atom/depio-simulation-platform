package com.backend.platform;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.LongAdder;

import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.StoreUnavailableException;
import com.backend.handoff.Ticket;
import com.backend.handoff.TicketStore;
import com.backend.persistence.AccountRepository;
import com.backend.persistence.AccountRepository.Profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a session into permission to enter a specific arena
 * (docs detailed-design/04-platform-services.md §3).
 *
 * This is the only place the two halves of the system meet: it authenticates against
 * j-redis, reads the player from MySQL, and writes a ticket the arena can consume without
 * touching either. Everything the arena needs goes into the ticket precisely so that it
 * needs no database connection of its own.
 *
 * Blocking, and meant for a virtual thread.
 */
public final class JoinService {

    private static final Logger log = LoggerFactory.getLogger(JoinService.class);

    public enum Outcome { OK, NO_SESSION, NO_PROFILE, NO_ARENA }

    /** {@code tls}: whether the client must open this arena's connection with TLS. */
    public record Grant(Outcome outcome, String arenaHost, int arenaPort, String ticketId,
                        boolean tls) {
        static Grant failed(Outcome outcome) {
            return new Grant(outcome, null, 0, null, false);
        }
    }

    private final AuthService auth;
    /** Tickets issued for the public arena: against the arenas' joins, what never arrived (04 §11). */
    private final LongAdder issued = new LongAdder();
    private final AccountRepository accounts;
    private final ArenaDirectory arenas;
    private final TicketStore tickets;
    private final Loadouts loadouts;

    public JoinService(AuthService auth, AccountRepository accounts, ArenaDirectory arenas,
                       TicketStore tickets, Loadouts loadouts) {
        this.auth = auth;
        this.accounts = accounts;
        this.arenas = arenas;
        this.tickets = tickets;
        this.loadouts = loadouts;
    }

    public Grant requestJoin(String sessionToken) throws SQLException {
        long playerId = auth.playerIdOf(sessionToken);
        if (playerId < 0) {
            return Grant.failed(Outcome.NO_SESSION);
        }
        Profile profile = accounts.findProfile(playerId);
        if (profile == null) {
            // The session named a player that is not there. Treat it as no session rather
            // than an error: it means the account was removed under a live token.
            log.warn("session for player {} has no profile", playerId);
            return Grant.failed(Outcome.NO_PROFILE);
        }

        // Picked before the ticket is written. A ticket is single-use, so issuing one for an
        // arena that turns out to be full spends it for nothing and the player must re-queue.
        ArenaDirectory.Endpoint arena = arenas.pick();
        if (arena == null) {
            return Grant.failed(Outcome.NO_ARENA);
        }

        // Team 0 until there are modes that have sides; free-for-all is the only one the
        // simulation implements, and a team the simulation ignores would be a field that
        // looks meaningful and is not.
        Ticket ticket = Ticket.forPlayer(playerId, profile.displayName(), 0,
                Ticket.bonusOf(loadouts.bonusOf(playerId)), loadouts.skinOf(playerId));   // what they wear (D-37, D-70)
        await(tickets.issue(ticket));
        issued.increment();

        log.info("player {} → arena {} ({}:{})", playerId, arena.name(), arena.host(), arena.port());
        return new Grant(Outcome.OK, arena.host(), arena.port(), ticket.id(), arena.tls());
    }

    public long issued() {
        return issued.sum();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException(e);
        } catch (ExecutionException e) {
            throw new StoreUnavailableException(e.getCause());
        }
    }
}
