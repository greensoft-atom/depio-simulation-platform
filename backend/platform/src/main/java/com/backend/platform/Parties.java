package com.backend.platform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.backend.handoff.StoreUnavailableException;
import com.jredis.client.JRedisClient;
import com.jredis.client.LeasedConnection;
import com.jredis.client.SetArgs;
import com.jredis.common.Reply;

/**
 * Parties, in the store (docs detailed-design/04-platform-services.md §4, "Parties"; D-25):
 *
 * <pre>
 * HSET party:{partyId} leader, members, n:{playerId}, v   EX 3600, renewed on every change; v its version (D-74)
 * SET  partyOf:{playerId} partyId                      EX 3600
 * SET  pinv:{playerId}:{partyId} 1                     EX 60, one invitation per party
 * SET  rl:say:{playerId} 1                              NX PX 2000, a phrase's turn
 * </pre>
 *
 * A party is worth nothing once its players have gone, so nothing of it is in MySQL. Each
 * change reads what it depends on under WATCH and writes it in one transaction, retried if
 * any of it changed meanwhile: two invitations accepted at once cannot overfill a party, and a
 * player cannot join two.
 *
 * Blocking, for the request threads.
 */
public final class Parties {

    /** The most players a party holds: the largest team any queued mode has (04 §4). */
    public static final int MOST = 3;
    static final int TTL_SECONDS = 3600;
    static final int INVITATION_SECONDS = 60;

    /** A party: its members in the order they joined, the leader among them, and its version (D-74). */
    public record Party(String id, long leader, List<Long> members, Map<Long, String> names, long version) {
    }

    /** What one no longer in {@code before} is told: no members, at the version of the change that took them out (D-74). */
    public static Party ended(Party before) {
        return new Party(before.id(), 0, List.of(), Map.of(), before.version() + 1);
    }

    public enum Invited { INVITED, SELF, IN_PARTY, NOT_LEADER, PARTY_FULL }

    public enum Joined { JOINED, NO_INVITATION, IN_PARTY, PARTY_FULL, QUEUED }

    /** What an invitation did: the party it is to, made if the inviter had none. */
    public record Invitation(Invited result, String partyId) { }

    /** A player's leaving, or being removed: the party before and after, which is null if it is no more. */
    public record Change(Party before, Party after) { }

    private final JRedisClient store;
    private final Supplier<String> ids;

    public Parties(JRedisClient store, Supplier<String> ids) {
        this.store = store;
        this.ids = ids;
    }

    static String partyKey(String partyId) {
        return "party:" + partyId;
    }

    static String memberKey(long playerId) {
        return "partyOf:" + playerId;
    }

    static String invitationKey(long playerId, String partyId) {
        return "pinv:" + playerId + ":" + partyId;
    }

    /** How long a phrase said to the party holds its speaker's turn (01 §9): the arena's two seconds. */
    static final int SAY_GAP_MILLIS = 2_000;

    /** Takes the player's turn to say a phrase: false while the last one's has not lapsed. */
    public boolean takeTurnToSay(long playerId) {
        return store.sync().set("rl:say:" + playerId, "1", SetArgs.nx().andPx(SAY_GAP_MILLIS));
    }

    /** The player's party, or null. */
    public Party of(long playerId) {
        return store.withLeasedConnection(conn -> {
            String partyId = conn.sync().get(memberKey(playerId));
            return partyId == null ? null : read(conn, partyId);
        });
    }

    /** Invites {@code to} to {@code from}'s party, making it if {@code from} has none. */
    public Invitation invite(long from, String fromName, long to) {
        if (from == to) {
            return new Invitation(Invited.SELF, null);
        }
        return retried(conn -> {
            conn.sync().watch(memberKey(from), memberKey(to));
            if (conn.sync().get(memberKey(to)) != null) {
                conn.sync().unwatch();
                return new Invitation(Invited.IN_PARTY, null);
            }
            String partyId = conn.sync().get(memberKey(from));
            boolean fresh = partyId == null;
            if (fresh) {
                partyId = ids.get();
            } else {
                conn.sync().watch(partyKey(partyId));
                Party party = read(conn, partyId);
                if (party == null || party.leader() != from) {
                    conn.sync().unwatch();
                    return new Invitation(Invited.NOT_LEADER, null);
                }
                if (party.members().size() >= MOST) {
                    conn.sync().unwatch();
                    return new Invitation(Invited.PARTY_FULL, null);
                }
            }
            var tx = conn.multi();
            if (fresh) {
                tx.send("HSET", partyKey(partyId), "leader", Long.toString(from), "members", Long.toString(from),
                        "n:" + from, fromName, "v", "1");
                renew(tx, partyId, List.of(from));
            }
            tx.send("SET", invitationKey(to, partyId), "1", "EX", Integer.toString(INVITATION_SECONDS));
            return conn.sync().exec(tx) == null ? null : new Invitation(Invited.INVITED, partyId);
        });
    }

    /** Joins the party {@code partyId} was invited to, while the invitation lives, there is room, and it is not queued nor asked. */
    public Joined accept(long playerId, String name, String partyId) {
        return retried(conn -> {
            conn.sync().watch(memberKey(playerId), partyKey(partyId), invitationKey(playerId, partyId));
            if (conn.sync().get(memberKey(playerId)) != null) {
                conn.sync().unwatch();
                return Joined.IN_PARTY;
            }
            Party party = read(conn, partyId);
            if (party == null || conn.sync().get(invitationKey(playerId, partyId)) == null) {
                conn.sync().unwatch();
                return Joined.NO_INVITATION;
            }
            if (party.members().size() >= MOST) {
                conn.sync().unwatch();
                return Joined.PARTY_FULL;
            }
            // A party in the queue, or asked about a match, is queued as it is: the matcher would not
            // know the newcomer (T-32). Once matched, each member's match is their own.
            conn.sync().watch(MatchQueue.playerKey(party.leader()));
            String state = conn.sync().hget(MatchQueue.playerKey(party.leader()), "state");
            if (MatchQueue.QUEUED.equals(state) || MatchQueue.CONFIRMING.equals(state)) {
                conn.sync().unwatch();
                return Joined.QUEUED;
            }
            List<Long> members = new ArrayList<>(party.members());
            members.add(playerId);
            var tx = conn.multi()
                    .send("HSET", partyKey(partyId), "members", join(members), "n:" + playerId, name,
                            "v", Long.toString(party.version() + 1))
                    .send("DEL", invitationKey(playerId, partyId));
            renew(tx, partyId, members);
            return conn.sync().exec(tx) == null ? null : Joined.JOINED;
        });
    }

    /**
     * Takes the player out of their party. A leader leaving hands it to the longest member, and a
     * party of one is no party: its last member is out of it too.
     *
     * @return the party before and after, or null when the player was in none
     */
    public Change leave(long playerId) {
        return retried(conn -> {
            conn.sync().watch(memberKey(playerId));
            String partyId = conn.sync().get(memberKey(playerId));
            if (partyId == null) {
                conn.sync().unwatch();
                return new Change(null, null);
            }
            conn.sync().watch(partyKey(partyId));
            Party before = read(conn, partyId);
            if (before == null) {
                conn.sync().unwatch();
                conn.sync().del(memberKey(playerId));           // a stale pointer to a party that is gone
                return new Change(null, null);
            }
            return removed(conn, before, playerId);
        });
    }

    /**
     * The leader removes a member.
     *
     * @return the change, or null when {@code leader} leads no party that {@code member} is in
     */
    public Change kick(long leader, long member) {
        return retried(conn -> {
            conn.sync().watch(memberKey(leader));
            String partyId = conn.sync().get(memberKey(leader));
            if (partyId == null || leader == member) {
                conn.sync().unwatch();
                return new Change(null, null);
            }
            conn.sync().watch(partyKey(partyId));
            Party before = read(conn, partyId);
            if (before == null || before.leader() != leader || !before.members().contains(member)) {
                conn.sync().unwatch();
                return new Change(null, null);
            }
            return removed(conn, before, member);
        });
    }

    /** Writes the party without {@code playerId}; null when the write lost a race, to be retried. */
    private static Change removed(LeasedConnection conn, Party before, long playerId) {
        List<Long> rest = new ArrayList<>(before.members());
        rest.remove(playerId);
        var tx = conn.multi().send("DEL", memberKey(playerId));
        Party after = null;
        if (rest.size() <= 1) {
            tx.send("DEL", partyKey(before.id()));
            for (long left : rest) {
                tx.send("DEL", memberKey(left));
            }
        } else {
            long leader = before.leader() == playerId ? rest.get(0) : before.leader();
            tx.send("HSET", partyKey(before.id()), "leader", Long.toString(leader), "members", join(rest),
                            "v", Long.toString(before.version() + 1))
                    .send("HDEL", partyKey(before.id()), "n:" + playerId);
            renew(tx, before.id(), rest);
            Map<Long, String> names = new java.util.HashMap<>(before.names());
            names.remove(playerId);
            after = new Party(before.id(), leader, List.copyOf(rest), Map.copyOf(names), before.version() + 1);
        }
        return conn.sync().exec(tx) == null ? null : new Change(before, after);
    }

    /** The party and every member's pointer to it live another hour: they are in use. */
    private static void renew(com.jredis.client.Transaction tx, String partyId, List<Long> members) {
        tx.send("EXPIRE", partyKey(partyId), TTL_SECONDS);
        for (long m : members) {
            tx.send("SET", memberKey(m), partyId, "EX", Integer.toString(TTL_SECONDS));
        }
    }

    private static Party read(LeasedConnection conn, String partyId) {
        Map<String, String> f = conn.sync().hgetall(partyKey(partyId));
        if (f == null || f.get("leader") == null) {
            return null;
        }
        List<Long> members = Arrays.stream(f.get("members").split(",")).map(Long::parseLong).toList();
        Map<Long, String> names = new java.util.HashMap<>();
        for (long m : members) {
            names.put(m, f.getOrDefault("n:" + m, ""));
        }
        // A party made before versions has none: 0, and 1 at its next change.
        return new Party(partyId, Long.parseLong(f.get("leader")), members, Map.copyOf(names),
                f.get("v") == null ? 0 : Long.parseLong(f.get("v")));
    }

    private static String join(List<Long> members) {
        return String.join(",", members.stream().map(String::valueOf).toList());
    }

    /** Runs a watched change until it is not overtaken by another; five times at most. */
    private <T> T retried(java.util.function.Function<LeasedConnection, T> change) {
        for (int attempt = 0; attempt < 5; attempt++) {
            T done = store.withLeasedConnection(change::apply);
            if (done != null) {
                return done;
            }
        }
        throw new StoreUnavailableException(new IllegalStateException("the party kept changing"));
    }
}
