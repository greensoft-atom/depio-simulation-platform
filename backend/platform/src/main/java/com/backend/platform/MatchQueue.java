package com.backend.platform;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import com.backend.handoff.MatchMode;
import com.backend.handoff.StoreUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jredis.client.JRedisClient;
import com.jredis.client.ScoredMember;

/**
 * The queues and each player's place in them, in the store (docs detailed-design/
 * 04-platform-services.md §4, "The queue"):
 *
 * <pre>
 * ZADD mmq:{mode} enqueuedAtMs leaderId                             the queue, oldest first
 * HSET mmp:{leaderId} mode, since, state, rating, name [, grant]         a player alone
 * HSET mmp:{leaderId} mode, since, state, members, r:{id}, n:{id} … [, grant]   a party's leader
 * HSET mmp:{memberId} mode, since, state, leader [, grant]          a party's other members
 * </pre>
 *
 * An entry is a player alone, or a party under its leader, and is queued and taken out whole
 * (04 §4, "Parties"). A match found waits for its players' answers (04 §4, the third slice):
 *
 * <pre>
 * HSET mmc:{matchUid} mode, deadline, lineup                              the match
 * ZADD mmc deadline matchUid                                               the matches waiting
 * HSET mmp:{playerId} state confirming, matchUid, deadline [, answer]  EX 60  each player asked, and their answer
 * SET  mmlock:{playerId} 1 EX 60                                           a player who declined
 * </pre>
 *
 * An answer is written to the player's own {@code mmp}, never to the match's: players answering
 * at once then never overtake one another (T-16). Every step that moves a player on, joining,
 * asking, making and calling off, reads what it decides by under a watch and writes in one
 * transaction, or nothing if any of it changed (D-55).
 * {@code mmp} decides whether a player is queued, matched or neither. It
 * expires, which is the backstop that returns a player stuck by a crash to "none". Nothing here
 * is in MySQL: lose the store and everyone re-queues, and nothing of value is gone.
 *
 * Blocking, for the request threads and the matcher's own thread.
 */
public final class MatchQueue {

    /** How long a queued player stays queued without being matched: the backstop, not a limit anyone waits out. */
    public static final int QUEUED_TTL_SECONDS = 900;

    static final String QUEUED = "queued";
    static final String MATCHED = "matched";
    static final String CONFIRMING = "confirming";
    /** The answer of a player whose party changed while asked: that entry is out of the match (T-32). */
    static final String WITHDRAWN = "withdrawn";

    /** The matches waiting for answers, by deadline. */
    static final String PENDING_KEY = "mmc";
    /** How long a match waiting for answers is kept, well past its deadline: the backstop. */
    static final int PENDING_TTL_SECONDS = 60;

    private static final ObjectMapper JSON = new ObjectMapper();

    public enum JoinResult { QUEUED, ALREADY_QUEUED, IN_MATCH, PARTY_CHANGED, LOCKED }

    public enum Answer { RECORDED, NOT_CONFIRMING }

    /**
     * A player in an entry, with their rating in its mode; {@code bonus} and {@code skin}: what the player wears, as a
     * ticket carries it, read when they queued (D-37, D-70).
     */
    public record Member(long playerId, int rating, String name, String bonus, int skin) {
        public Member(long playerId, int rating, String name, String bonus) {
            this(playerId, rating, name, bonus, 0);
        }

        public Member(long playerId, int rating, String name) {
            this(playerId, rating, name, "");
        }
    }

    /**
     * An entry waiting: a player alone, or a party, which {@code playerId} leads; in a team
     * match, {@code team} is the team it is a side of, and 0 otherwise.
     */
    public record Waiting(long playerId, MatchMode mode, long since, List<Member> members, long team) {

        public Waiting(long playerId, MatchMode mode, long since, List<Member> members) {
            this(playerId, mode, since, members, 0);
        }

        /** A player alone. */
        public Waiting(long playerId, MatchMode mode, long since, int rating, String name) {
            this(playerId, mode, since, List.of(new Member(playerId, rating, name)));
        }

        /** Its members' ratings added up. */
        public long ratings() {
            long sum = 0;
            for (Member m : members) {
                sum += m.rating();
            }
            return sum;
        }

        /** Its members' mean rating: what the window is measured by. */
        public int rating() {
            return (int) Math.round((double) ratings() / members.size());
        }
    }

    /** Where a matched player is to go: what {@code evt.match.found} carries. */
    public record Grant(String arenaHost, int arenaPort, String ticketId, boolean tls,
                        String matchUid) { }

    /**
     * What {@code GET /v1/queue} answers. {@code mode} and {@code since} are null and 0 for none;
     * {@code matchUid} and {@code deadline} are the match a {@code confirming} player is asked about.
     */
    public record Status(String state, MatchMode mode, long since, Grant grant, String matchUid, long deadline) {
        static final Status NONE = new Status("none", null, 0, null, null, 0);
    }

    /** A match found and waiting for its players' answers: its sides of entries, and who said what. */
    public record Pending(String matchUid, MatchMode mode, long deadline, List<List<Waiting>> sides,
                          Set<Long> accepted, Set<Long> declined, Set<Long> withdrawn) {

        public List<Waiting> entries() {
            return sides.stream().flatMap(List::stream).toList();
        }

        public List<Long> players() {
            return entries().stream().flatMap(e -> e.members().stream()).map(Member::playerId).toList();
        }
    }

    private final JRedisClient store;

    public MatchQueue(JRedisClient store) {
        this.store = store;
    }

    static String queueKey(MatchMode mode) {
        return "mmq:" + mode.key;
    }

    static String playerKey(long playerId) {
        return "mmp:" + playerId;
    }

    static String pendingKey(String matchUid) {
        return "mmc:" + matchUid;
    }

    static String lockKey(long playerId) {
        return "mmlock:" + playerId;
    }

    /** Queues a player alone: {@link #join(List, MatchMode, String, long)} with no party. */
    public JoinResult join(long playerId, MatchMode mode, int rating, String name, long nowMillis) {
        return join(List.of(new Member(playerId, rating, name)), mode, null, nowMillis);
    }

    /** Queues an entry of no team: {@link #join(List, MatchMode, String, long, long)}. */
    public JoinResult join(List<Member> members, MatchMode mode, String partyId, long nowMillis) {
        return join(members, mode, partyId, 0, nowMillis);
    }

    /**
     * Queues an entry, its leader first, unless any of its members is queued or matched already,
     * or locked out. Every member's {@code mmp} and the queue are written together, and only if
     * none of them, nor their locks, nor the party {@code partyId} names, changed since they were
     * read: two joins at once queue it once, a lock written as it joins is not missed (T-33), and
     * a party whose members are no longer these is not queued as they were. A team match's entry
     * names its {@code team}, 0 for none.
     */
    public JoinResult join(List<Member> members, MatchMode mode, String partyId, long team, long nowMillis) {
        long leader = members.get(0).playerId();
        String since = Long.toString(nowMillis);
        List<String> keys = members.stream().map(m -> playerKey(m.playerId())).toList();
        String[] locks = members.stream().map(m -> lockKey(m.playerId())).toArray(String[]::new);
        List<String> watched = new ArrayList<>(keys);
        watched.addAll(List.of(locks));
        if (partyId != null) {
            watched.add(Parties.partyKey(partyId));
        }
        return retried(conn -> {
            conn.sync().watch(watched.toArray(String[]::new));
            for (String key : keys) {
                String state = conn.sync().hget(key, "state");
                if (state != null) {
                    conn.sync().unwatch();
                    return MATCHED.equals(state) ? JoinResult.IN_MATCH : JoinResult.ALREADY_QUEUED;
                }
            }
            if (conn.sync().exists(locks) > 0) {
                conn.sync().unwatch();
                return JoinResult.LOCKED;                // one of them declined a match a moment ago (D-27)
            }
            if (partyId != null && !ids(members).equals(conn.sync().hget(Parties.partyKey(partyId), "members"))) {
                conn.sync().unwatch();
                return JoinResult.PARTY_CHANGED;
            }
            List<Object> entry = new ArrayList<>(List.of("HSET", keys.get(0), "mode", mode.key, "since", since,
                    "state", QUEUED));
            if (team != 0) {
                entry.addAll(List.of("team", Long.toString(team)));
            }
            if (members.size() == 1) {
                entry.addAll(List.of("rating", Integer.toString(members.get(0).rating()), "name", members.get(0).name()));
                if (!members.get(0).bonus().isEmpty()) {
                    entry.addAll(List.of("bonus", members.get(0).bonus()));
                }
                if (members.get(0).skin() != 0) {
                    entry.addAll(List.of("skin", Integer.toString(members.get(0).skin())));      // D-70
                }
            } else {
                entry.addAll(List.of("members", ids(members)));
                for (Member m : members) {
                    entry.addAll(List.of("r:" + m.playerId(), Integer.toString(m.rating()), "n:" + m.playerId(), m.name()));
                    if (!m.bonus().isEmpty()) {
                        entry.addAll(List.of("b:" + m.playerId(), m.bonus()));
                    }
                    if (m.skin() != 0) {
                        entry.addAll(List.of("s:" + m.playerId(), Integer.toString(m.skin())));
                    }
                }
            }
            var tx = conn.multi().send(entry.toArray());
            for (int i = 1; i < members.size(); i++) {
                tx.send("HSET", keys.get(i), "mode", mode.key, "since", since, "state", QUEUED,
                        "leader", Long.toString(leader));
            }
            for (String key : keys) {
                tx.send("EXPIRE", key, QUEUED_TTL_SECONDS);
            }
            tx.send("ZADD", queueKey(mode), since, Long.toString(leader));
            return conn.sync().exec(tx) == null ? null : JoinResult.QUEUED;     // null: changed meanwhile
        });
    }

    /**
     * Takes the player out: out of the queue with their whole entry, the party they were queued
     * with too, or, once matched, out of their own match only: a player who leaves after being
     * matched does not go, and the other side plays without them. Asked about a match, leaving is
     * declining it: the matcher takes them out at its next round.
     *
     * @return the players taken out now
     */
    public List<Long> leave(long playerId) {
        return out(playerId, true);
    }

    /**
     * For a party that changed: the entry the player was queued in is out of the queue if it is
     * queued, and out of the match it is asked about if it is asked (T-32); a match made is left be.
     */
    public List<Long> unqueue(long playerId) {
        return out(playerId, false);
    }

    private List<Long> out(long playerId, boolean evenMatched) {
        String key = playerKey(playerId);
        return retried(conn -> {
            conn.sync().watch(key);
            Map<String, String> f = conn.sync().hgetall(key);
            if (f == null || f.get("state") == null || (MATCHED.equals(f.get("state")) && !evenMatched)) {
                conn.sync().unwatch();
                return List.<Long>of();
            }
            if (MATCHED.equals(f.get("state"))) {
                return conn.sync().exec(conn.multi().send("DEL", key)) == null ? null : List.of(playerId);
            }
            if (CONFIRMING.equals(f.get("state"))) {
                // Leaving declines; a party's change withdraws the entry, and is not refusing. Neither
                // undoes a decline, nor a leave a withdrawal.
                if ("decline".equals(f.get("answer")) || WITHDRAWN.equals(f.get("answer"))) {
                    conn.sync().unwatch();
                    return List.<Long>of();
                }
                return conn.sync().exec(conn.multi().send("HSET", key, "answer", evenMatched ? "decline" : WITHDRAWN))
                        == null ? null : List.<Long>of();
            }
            long leader = f.get("leader") == null ? playerId : Long.parseLong(f.get("leader"));
            Map<String, String> entry = f;
            if (leader != playerId) {
                conn.sync().watch(playerKey(leader));
                entry = conn.sync().hgetall(playerKey(leader));
            }
            // Its members as the entry names them, those still queued: one matched keeps its match.
            List<Long> members = entry == null || entry.get("members") == null ? List.of(leader, playerId)
                    : Arrays.stream(entry.get("members").split(",")).map(Long::parseLong).toList();
            List<Long> out = new ArrayList<>();
            for (long m : members.stream().distinct().toList()) {
                conn.sync().watch(playerKey(m));
                if (QUEUED.equals(conn.sync().hget(playerKey(m), "state"))) {
                    out.add(m);
                }
            }
            var tx = conn.multi().send("ZREM", queueKey(MatchMode.ofKey(f.get("mode"))), Long.toString(leader));
            for (long m : out) {
                tx.send("DEL", playerKey(m));
            }
            return conn.sync().exec(tx) == null ? null : out;
        });
    }

    public Status status(long playerId) {
        Map<String, String> f = await(store.hgetall(playerKey(playerId)));
        if (f == null || f.isEmpty() || f.get("state") == null) {
            return Status.NONE;
        }
        MatchMode mode = MatchMode.ofKey(f.get("mode"));
        long since = parseLong(f.get("since"));
        if (MATCHED.equals(f.get("state"))) {
            return new Status(MATCHED, mode, since, new Grant(f.get("arenaHost"),
                    (int) parseLong(f.get("arenaPort")), f.get("ticketId"), "1".equals(f.get("tls")),
                    f.get("matchUid")), null, 0);
        }
        if (CONFIRMING.equals(f.get("state"))) {
            return new Status(CONFIRMING, mode, since, null, f.get("matchUid"), parseLong(f.get("deadline")));
        }
        return new Status(QUEUED, mode, since, null, null, 0);
    }

    // ---- the confirm step (04 §4, the third slice) -------------------------------------------

    /**
     * Takes a lineup out of the queue and asks its players: it waits for their answers until
     * {@code deadline}, and each of them is {@code confirming} it meanwhile, for as long as the
     * match waits. Only if every member is still queued in the entry the round read, since the
     * time it read: one who left or came back meanwhile, or was asked by another matcher, is not
     * asked, nor anyone with them, and the next round reads the queue again (T-33). All or nothing,
     * so a failure leaves nobody out of the queue unasked (T-34).
     *
     * @return false when the lineup was not as read
     */
    public boolean ask(MatchMode mode, String matchUid, List<List<Waiting>> sides, long deadline) {
        List<Waiting> entries = sides.stream().flatMap(List::stream).toList();
        String[] keys = entries.stream().flatMap(e -> e.members().stream()).map(m -> playerKey(m.playerId()))
                .toArray(String[]::new);
        return retried(conn -> {
            conn.sync().watch(keys);
            for (Waiting e : entries) {
                for (Member m : e.members()) {
                    List<String> f = conn.sync().hmget(playerKey(m.playerId()), "state", "since");
                    if (!QUEUED.equals(f.get(0)) || !Long.toString(e.since()).equals(f.get(1))) {
                        conn.sync().unwatch();
                        return false;
                    }
                }
            }
            var tx = conn.multi();
            for (Waiting e : entries) {
                tx.send("ZREM", queueKey(mode), Long.toString(e.playerId()));
            }
            tx.send("HSET", pendingKey(matchUid), "mode", mode.key, "deadline", Long.toString(deadline),
                            "lineup", lineup(sides))
                    .send("EXPIRE", pendingKey(matchUid), PENDING_TTL_SECONDS)
                    .send("ZADD", PENDING_KEY, Long.toString(deadline), matchUid);
            for (String key : keys) {
                tx.send("HSET", key, "state", CONFIRMING, "matchUid", matchUid, "deadline", Long.toString(deadline));
                tx.send("HDEL", key, "answer");
                tx.send("EXPIRE", key, PENDING_TTL_SECONDS);
            }
            return conn.sync().exec(tx) == null ? null : true;
        });
    }

    /** How a match that is off ended: whose entries are out, which went back, and why. */
    public record Ended(List<Long> out, List<Waiting> back, boolean declined, boolean withdrawn) { }

    /** Said by {@link #end}'s change when the match was not its to end: null is a race to run again. */
    private static final Ended NOT_OURS = new Ended(List.of(), List.of(), false, false);

    /**
     * Makes a match its players have accepted: each recorded {@code matched}, with their grant,
     * for {@code ttlSeconds}, and the match's wait ended, in one transaction, and only if every
     * player is still asked about it and accepting. One who has declined or withdrawn since the
     * round read the answers, or a match another matcher has made or ended, leaves it unmade
     * (T-33); the tickets are written before this, so a failure on the way leaves it waiting
     * (T-34).
     *
     * @return false when it was not made
     */
    public boolean make(Pending p, Map<Long, Grant> grants, int ttlSeconds) {
        List<Long> players = p.players();
        String[] keys = players.stream().map(MatchQueue::playerKey).toArray(String[]::new);
        return retried(conn -> {
            conn.sync().watch(keys);
            for (String key : keys) {
                List<String> f = conn.sync().hmget(key, "state", "matchUid", "answer");
                if (!CONFIRMING.equals(f.get(0)) || !p.matchUid().equals(f.get(1)) || !"accept".equals(f.get(2))) {
                    conn.sync().unwatch();
                    return false;
                }
            }
            var tx = conn.multi().send("ZREM", PENDING_KEY, p.matchUid()).send("DEL", pendingKey(p.matchUid()));
            for (long player : players) {
                Grant grant = grants.get(player);
                tx.send("HSET", playerKey(player), "state", MATCHED, "mode", p.mode().key,
                                "arenaHost", grant.arenaHost(), "arenaPort", Integer.toString(grant.arenaPort()),
                                "ticketId", grant.ticketId(), "tls", grant.tls() ? "1" : "0", "matchUid", grant.matchUid())
                        .send("EXPIRE", playerKey(player), ttlSeconds);
            }
            return conn.sync().exec(tx) == null ? null : true;
        });
    }

    /**
     * Ends the wait of a match that is off, or has no room, by the answers as they are now, not
     * as the round read them (T-33): each entry with a player who declined, withdrew, or, once
     * {@code timeUp}, said nothing (unless {@code excused}) is out and forgotten, and those who
     * declined or said nothing are locked out for {@code lockSeconds}; every other entry goes back
     * in the queue where it was, queued for the queue's time again. In one transaction, with the
     * lock and the forgetting together, so a join sees both or neither.
     *
     * @return how it ended, or null when it was not this call's: another matcher ended it first
     */
    public Ended end(Pending p, boolean timeUp, Set<Long> excused, int lockSeconds) {
        List<Long> players = p.players();
        List<String> watched = new ArrayList<>(List.of(pendingKey(p.matchUid())));
        players.forEach(player -> watched.add(playerKey(player)));
        Ended ended = retried(conn -> {
            conn.sync().watch(watched.toArray(String[]::new));
            if (conn.sync().exists(pendingKey(p.matchUid())) == 0) {
                conn.sync().unwatch();
                return NOT_OURS;
            }
            Set<Long> declined = new HashSet<>();
            Set<Long> withdrawn = new HashSet<>();
            Set<Long> silent = new HashSet<>();
            for (long player : players) {
                List<String> f = conn.sync().hmget(playerKey(player), "state", "matchUid", "answer");
                String answer = f.get(2);
                if (!CONFIRMING.equals(f.get(0)) || !p.matchUid().equals(f.get(1)) || WITHDRAWN.equals(answer)) {
                    withdrawn.add(player);
                } else if ("decline".equals(answer)) {
                    declined.add(player);
                } else if (answer == null && timeUp && !excused.contains(player)) {
                    silent.add(player);
                }
            }
            List<Long> out = new ArrayList<>();
            List<Waiting> back = new ArrayList<>();
            for (Waiting entry : p.entries()) {
                List<Long> members = entry.members().stream().map(Member::playerId).toList();
                if (members.stream().anyMatch(m -> declined.contains(m) || withdrawn.contains(m) || silent.contains(m))) {
                    out.addAll(members);
                } else {
                    back.add(entry);
                }
            }
            var tx = conn.multi().send("ZREM", PENDING_KEY, p.matchUid()).send("DEL", pendingKey(p.matchUid()));
            for (long player : out) {
                tx.send("DEL", playerKey(player));
            }
            for (long player : java.util.stream.Stream.concat(declined.stream(), silent.stream()).toList()) {
                tx.send("SET", lockKey(player), "1", "EX", Integer.toString(lockSeconds));
            }
            for (Waiting entry : back) {
                tx.send("ZADD", queueKey(p.mode()), Long.toString(entry.since()), Long.toString(entry.playerId()));
                for (Member m : entry.members()) {
                    tx.send("HSET", playerKey(m.playerId()), "state", QUEUED);
                    tx.send("EXPIRE", playerKey(m.playerId()), QUEUED_TTL_SECONDS);
                }
            }
            return conn.sync().exec(tx) == null ? null
                    : new Ended(out, back, !declined.isEmpty(), !withdrawn.isEmpty());
        });
        return ended == NOT_OURS ? null : ended;
    }

    /**
     * Records a player's answer to the match they are asked about, in their own {@code mmp}, while
     * they are asked. An answer from a player not asked, about another match, or once the match is
     * made or off, or from one whose party's change withdrew them (T-32), is not one.
     */
    public Answer answer(long playerId, String matchUid, boolean accept) {
        if (matchUid == null) {
            return Answer.NOT_CONFIRMING;
        }
        String key = playerKey(playerId);
        return retried(conn -> {
            conn.sync().watch(key);
            List<String> f = conn.sync().hmget(key, "state", "matchUid", "answer");
            if (!CONFIRMING.equals(f.get(0)) || !matchUid.equals(f.get(1)) || WITHDRAWN.equals(f.get(2))) {
                conn.sync().unwatch();
                return Answer.NOT_CONFIRMING;
            }
            return conn.sync().exec(conn.multi().send("HSET", key, "answer", accept ? "accept" : "decline")) == null
                    ? null : Answer.RECORDED;
        });
    }

    /** The matches waiting for answers. One whose key has gone is dropped from the list. */
    public List<Pending> pending() {
        List<String> uids = await(store.zrange(PENDING_KEY, 0, -1));
        List<Pending> pending = new ArrayList<>(uids.size());
        for (String uid : uids) {
            Map<String, String> f = await(store.hgetall(pendingKey(uid)));
            if (f == null || f.get("lineup") == null) {
                await(store.zrem(PENDING_KEY, uid));
                continue;
            }
            MatchMode mode = MatchMode.ofKey(f.get("mode"));
            Pending asked = new Pending(uid, mode, parseLong(f.get("deadline")), sides(mode, f.get("lineup")),
                    new HashSet<>(), new HashSet<>(), new HashSet<>());
            List<Long> players = asked.players();
            // Each player's own answer: asking cleared any to an earlier match.
            List<CompletableFuture<String>> answers = new ArrayList<>(players.size());
            for (long p : players) {
                answers.add(store.hget(playerKey(p), "answer"));
            }
            for (int i = 0; i < players.size(); i++) {
                String a = await(answers.get(i));
                if ("accept".equals(a)) {
                    asked.accepted().add(players.get(i));
                } else if ("decline".equals(a)) {
                    asked.declined().add(players.get(i));
                } else if (WITHDRAWN.equals(a)) {
                    asked.withdrawn().add(players.get(i));
                }
            }
            pending.add(asked);
        }
        return pending;
    }

    private static String lineup(List<List<Waiting>> of) {
        ArrayNode sides = JSON.createArrayNode();
        for (List<Waiting> side : of) {
            ArrayNode entries = sides.addArray();
            for (Waiting e : side) {
                ObjectNode entry = entries.addObject().put("leader", e.playerId()).put("since", e.since());
                ArrayNode members = entry.putArray("members");
                for (Member m : e.members()) {
                    ObjectNode member = members.addObject().put("id", m.playerId()).put("rating", m.rating())
                            .put("name", m.name());
                    if (!m.bonus().isEmpty()) {
                        member.put("bonus", m.bonus());      // through the confirm step to the ticket (D-37)
                    }
                    if (m.skin() != 0) {
                        member.put("skin", m.skin());        // and the skin (D-70)
                    }
                }
            }
        }
        return sides.toString();
    }

    private static List<List<Waiting>> sides(MatchMode mode, String lineup) {
        try {
            List<List<Waiting>> sides = new ArrayList<>();
            for (JsonNode side : JSON.readTree(lineup)) {
                List<Waiting> entries = new ArrayList<>();
                for (JsonNode e : side) {
                    List<Member> members = new ArrayList<>();
                    for (JsonNode m : e.get("members")) {
                        members.add(new Member(m.get("id").asLong(), m.get("rating").asInt(), m.get("name").asText(),
                                m.path("bonus").asText(""), m.path("skin").asInt(0)));
                    }
                    entries.add(new Waiting(e.get("leader").asLong(), mode, e.get("since").asLong(), members));
                }
                sides.add(entries);
            }
            return sides;
        } catch (IOException e) {
            throw new IllegalStateException("a waiting match's lineup is not what this build writes", e);
        }
    }

    /**
     * Everyone in the mode's queue, oldest first, as their entries say. A member whose entry is
     * gone, or no longer queued for this mode, is added to {@code stale} instead, for the
     * caller to {@link #drop}.
     */
    public List<Waiting> waiting(MatchMode mode, List<Long> stale) {
        List<ScoredMember> members = await(store.zrangeWithScores(queueKey(mode), 0, -1));
        List<CompletableFuture<Map<String, String>>> entries = new ArrayList<>(members.size());
        for (ScoredMember m : members) {
            entries.add(store.hgetall(playerKey(Long.parseLong(m.member))));
        }
        List<Waiting> waiting = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            long playerId = Long.parseLong(members.get(i).member);
            Map<String, String> f = await(entries.get(i));
            if (f == null || !QUEUED.equals(f.get("state")) || !mode.key.equals(f.get("mode"))) {
                stale.add(playerId);
                continue;
            }
            waiting.add(new Waiting(playerId, mode, (long) members.get(i).score, entry(playerId, f),
                    f.get("team") == null ? 0 : Long.parseLong(f.get("team"))));
        }
        return waiting;
    }

    /** An entry's members, as its leader's {@code mmp} names them. */
    private static List<Member> entry(long leader, Map<String, String> f) {
        if (f.get("members") == null) {
            return List.of(new Member(leader, (int) parseLong(f.get("rating")), f.get("name"),
                    f.getOrDefault("bonus", ""), Integer.parseInt(f.getOrDefault("skin", "0"))));
        }
        List<Member> members = new ArrayList<>();
        for (String id : f.get("members").split(",")) {
            members.add(new Member(Long.parseLong(id), (int) parseLong(f.get("r:" + id)), f.get("n:" + id),
                    f.getOrDefault("b:" + id, ""), Integer.parseInt(f.getOrDefault("s:" + id, "0"))));
        }
        return members;
    }

    private static String ids(List<Member> members) {
        return String.join(",", members.stream().map(m -> Long.toString(m.playerId())).toList());
    }

    /** Removes players from the queue; with {@code forget}, their entries too. */
    public void drop(MatchMode mode, List<Long> players, boolean forget) {
        if (players.isEmpty()) {
            return;
        }
        var tx = store.multi();
        for (long p : players) {
            tx.send("ZREM", queueKey(mode), Long.toString(p));
            if (forget) {
                tx.send("DEL", playerKey(p));
            }
        }
        await(tx.exec());
    }

    /**
     * Records the match, and its mode, for the player to fetch, for as long as its ticket lives: a
     * client that missed the push asks {@code GET /v1/queue} and finds it here (03 §7). The mode
     * too, since a sandbox's player was never queued for one (04 §4, the seventh slice). {@code sinceMillis}: when it
     * was opened, what its status's wait counts from; without it the wait was counted from 1970.
     */
    public void matched(long playerId, MatchMode mode, Grant grant, int ttlSeconds, long sinceMillis) {
        String key = playerKey(playerId);
        await(store.multi()
                .send("HSET", key, "state", MATCHED, "mode", mode.key, "since", Long.toString(sinceMillis),
                        "arenaHost", grant.arenaHost(),
                        "arenaPort", Integer.toString(grant.arenaPort()), "ticketId", grant.ticketId(),
                        "tls", grant.tls() ? "1" : "0", "matchUid", grant.matchUid())
                .send("EXPIRE", key, ttlSeconds)
                .exec());
    }

    /** Runs a watched change until it is not overtaken by another; five times at most. */
    private <T> T retried(java.util.function.Function<com.jredis.client.LeasedConnection, T> change) {
        for (int attempt = 0; attempt < 5; attempt++) {
            T done = store.withLeasedConnection(change::apply);
            if (done != null) {
                return done;
            }
        }
        // Five races lost in a row on a few players' own entries: the store is not behaving.
        throw new StoreUnavailableException(new IllegalStateException("the queue entry kept changing"));
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
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
