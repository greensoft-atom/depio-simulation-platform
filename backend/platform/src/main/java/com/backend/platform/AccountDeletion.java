package com.backend.platform;

import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.backend.handoff.LeaderboardStore;
import com.backend.handoff.LobbyPush;
import com.backend.handoff.SessionStore;
import com.backend.handoff.StoreUnavailableException;
import com.backend.persistence.AccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A player erased (D-84, 04 §1), and what the account service's events ask of the backend (D-117, 10 §4.3).
 *
 * <p><b>A guest deletes itself here</b> ({@code DELETE /v1/accounts}), in this order: out of the queue, the party and
 * the team, each as its own call does it, so a store that fails refuses before anything is erased; MySQL's erasure, one
 * transaction; then off the score boards and every session ended, which are the store's. A store that fails after the
 * erasure leaves the session working, and the same call with it finishes what is left. Last, every device's lobby told,
 * which closes it. A linked player's account is the account service's to delete: deleted there, its event erases the
 * player here.
 *
 * <p><b>An account deleted at the account service</b> erases its player the same way, but out of the team whatever its
 * role, as the worker's retention does (D-87): there is nobody left to ask to hand the team over. Its subject is
 * recorded as erased, player or none, so a token of it exchanged late makes no new player. Taken again, it changes
 * nothing.
 *
 * <p><b>A password changed there</b> ends the player's sessions made before the change, to its millisecond, and leaves
 * those made after, which the device that changed it opened with its new token. When one ended, the lobby is told: a
 * player has one lobby connection, and it may be an older session's, whose device must hear no more of the player's
 * pushes; the device that changed the password connects again with its new session.
 */
public final class AccountDeletion {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletion.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Result { OK, NO_SESSION, LEADER_WITH_MEMBERS, AT_THE_ACCOUNT_SERVICE }

    private final AuthService auth;
    private final AccountRepository accounts;
    private final QueueService queues;
    private final PartyService parties;
    private final TeamService teams;
    private final LeaderboardStore boards;
    private final SessionStore sessions;
    private final LobbyPush push;

    public AccountDeletion(AuthService auth, AccountRepository accounts, QueueService queues, PartyService parties,
                           TeamService teams, LeaderboardStore boards, SessionStore sessions, LobbyPush push) {
        this.auth = auth;
        this.accounts = accounts;
        this.queues = queues;
        this.parties = parties;
        this.teams = teams;
        this.boards = boards;
        this.sessions = sessions;
        this.push = push;
    }

    /** @return the session's account, or null for no session */
    public AccountRepository.Owner owner(String token) throws SQLException {
        long playerId = auth.playerIdOf(token);
        return playerId < 0 ? null : accounts.owner(playerId);
    }

    /** Erases the guest of {@code owner}, whose session {@code token} is; a linked player's is the account service's to delete. */
    public Result erase(String token, AccountRepository.Owner owner) throws SQLException {
        long playerId = owner.playerId();
        if (owner.status() != AccountRepository.Status.DELETED) {
            if (owner.subject() != null) {
                return Result.AT_THE_ACCOUNT_SERVICE;
            }
            queues.leave(token);
            parties.leave(token);
            switch (teams.leave(token).result()) {
                case LEADER_WITH_MEMBERS -> {
                    return Result.LEADER_WITH_MEMBERS;
                }
                case NO_SESSION -> {
                    return Result.NO_SESSION;
                }
                default -> { }                               // left, or in none
            }
            accounts.erase(playerId);
            log.info("player {} erased their account", playerId);
        }
        ended(playerId, "deleted");
        return Result.OK;
    }

    /** The account service's {@code deleted}: its player erased, whatever its team role, its subject recorded. */
    public void deletedAtTheAccountService(String subject) throws SQLException {
        AccountRepository.Credentials c = accounts.findBySubject(subject);
        if (c == null) {
            accounts.recordErasedSubject(subject);           // a player never made, or erased already
            return;
        }
        long playerId = c.playerId();
        queues.leavePlayer(playerId);
        parties.leavePlayer(playerId);
        teams.erase(playerId);
        accounts.erase(playerId);
        log.info("player {} erased: their account was deleted at the account service", playerId);
        ended(playerId, "deleted");
    }

    /** The account service's {@code sessions-revoked}: the sessions made before {@code before} ended, and the lobby told. */
    public void sessionsRevokedAtTheAccountService(String subject, Instant before) throws SQLException {
        AccountRepository.Credentials c = accounts.findBySubject(subject);
        if (c == null) {
            return;
        }
        SessionStore.Revoked r = await(sessions.revokeBefore(c.playerId(), before));
        log.info("player {}: {} sessions ended for a password changed at the account service, {} kept",
                c.playerId(), r.ended(), r.remaining());
        if (r.ended() > 0) {
            tell(c.playerId(), "signed_out");
        }
    }

    /** Off the boards, every session ended, every device's lobby told: the store's part. */
    private void ended(long playerId, String reason) {
        await(boards.remove(playerId));
        await(sessions.revokeAll(playerId));
        tell(playerId, reason);
    }

    private void tell(long playerId, String reason) {
        try {
            push.send(playerId, "evt.session.revoked", JSON.createObjectNode().put("reason", reason)).join();
        } catch (RuntimeException e) {
            // Every session ended: a lobby left open finds its session gone at its next call.
            log.warn("player {}: the lobby not told of {}: {}", playerId, reason, e.toString());
        }
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
