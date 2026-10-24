package com.backend.platform;

import java.sql.SQLException;
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
 * A player's own account erased (D-84, 04 §1), in this order: out of the queue, the party and the team, each as its
 * own call does it, so a store that fails refuses before anything is erased; MySQL's erasure, one transaction; then
 * off the score boards and every session ended, which are the store's. A store that fails after the erasure leaves
 * the session working, and the same call with it finishes what is left: an erased account asks for no password.
 * Last, every device's lobby told, which closes it.
 */
public final class AccountDeletion {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletion.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Result { OK, NO_SESSION, LEADER_WITH_MEMBERS }

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

    /** A registered account's password; not a guest's, whose session is all it has, nor an erased one's, which has none. */
    public static boolean needsPassword(AccountRepository.Owner owner) {
        return !owner.guest() && owner.status() != AccountRepository.Status.DELETED;
    }

    /** Erases the account of {@code owner}, whose session {@code token} is and who has proved it if they must. */
    public Result erase(String token, AccountRepository.Owner owner) throws SQLException {
        long playerId = owner.playerId();
        if (owner.status() != AccountRepository.Status.DELETED) {
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
        await(boards.remove(playerId));
        await(sessions.revokeAll(playerId));
        try {
            push.send(playerId, "evt.session.revoked", JSON.createObjectNode().put("reason", "deleted")).join();
        } catch (RuntimeException e) {
            // Erased, and every session ended: a lobby left open finds its session gone at its next call.
            log.warn("player {} erased, the lobby not told: {}", playerId, e.toString());
        }
        return Result.OK;
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
