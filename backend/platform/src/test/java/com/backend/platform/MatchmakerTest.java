package com.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.backend.common.Metrics;
import com.backend.handoff.ArenaDirectory;
import com.backend.handoff.LobbyPush;
import com.backend.handoff.MatchMode;
import com.backend.handoff.Ticket;
import com.backend.handoff.TicketStore;
import com.backend.handoff.TournamentGrants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jredis.client.JRedisClient;
import com.jredis.embedded.JRedisEmbedded;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The matcher (04 §4, "The matcher"), against a real store; rounds are run by hand. */
@Timeout(60)
class MatchmakerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1_800_000_000_000L;

    private static JRedisEmbedded server;
    private static JRedisClient store;
    private static JRedisClient gateway;
    private static final LinkedBlockingQueue<String> pushed = new LinkedBlockingQueue<>();

    private final AtomicLong now = new AtomicLong(T0);
    private MatchQueue queue;
    private TicketStore tickets;
    private Matchmaker matcher;

    @BeforeAll
    static void start() throws Exception {
        server = JRedisEmbedded.start();
        store = server.newClient();
        gateway = server.newClient();
        gateway.pubSub().subscribe("push:gw-1", (ch, msg) -> pushed.add(new String(msg, StandardCharsets.UTF_8)))
                .get(5, TimeUnit.SECONDS);
    }

    @AfterAll
    static void stop() {
        gateway.close();
        store.close();
        server.close();
    }

    @BeforeEach
    void fresh() {
        store.sync().send("FLUSHALL");
        pushed.clear();
        queue = new MatchQueue(store);
        tickets = new TicketStore(store);
        matcher = new Matchmaker(store, queue, new ArenaDirectory(store), tickets, new LobbyPush(store),
                new TournamentGrants(store), "test-1", now::get);
    }

    /** Queued at {@code at}, in the lobby at gateway gw-1. */
    private void queued(long playerId, int rating, long at) {
        queue.join(playerId, MatchMode.DUEL, rating, "p" + playerId, at);
        store.sync().set("conn:" + playerId, "gw-1#x-" + playerId);
    }

    private void arena(int rooms, int maxRooms) throws Exception {
        new ArenaDirectory(store).announce(new ArenaDirectory.Endpoint("arena-1", "10.0.0.7", 9001, 0, 600,
                true, rooms, maxRooms)).get(5, TimeUnit.SECONDS);
    }

    private static MatchQueue.Waiting w(long id, int rating, long since) {
        return new MatchQueue.Waiting(id, MatchMode.DUEL, since, rating, "p" + id);
    }

    /** An entry for team-vs-team: players {@code leader}, {@code leader + 1} … at these ratings. */
    private static MatchQueue.Waiting party(long leader, long since, int... ratings) {
        List<MatchQueue.Member> members = new ArrayList<>();
        for (int i = 0; i < ratings.length; i++) {
            members.add(new MatchQueue.Member(leader + i, ratings[i], "p" + (leader + i)));
        }
        return new MatchQueue.Waiting(leader, MatchMode.TVT, since, members);
    }

    /** Every one of them accepts the match they are asked about. */
    private void accept(long... players) {
        for (long p : players) {
            assertThat(queue.answer(p, queue.status(p).matchUid(), true)).isEqualTo(MatchQueue.Answer.RECORDED);
        }
    }

    /** Whether the player is locked out of the queue. */
    private static boolean locked(long playerId) {
        return store.sync().exists(MatchQueue.lockKey(playerId)) == 1;
    }

    /** The next {@code n} pushes, as (to, t): what each was told. */
    private static List<String> told(int n) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String raw = pushed.poll(5, TimeUnit.SECONDS);
            assertThat(raw).as("push " + (i + 1) + " of " + n).isNotNull();
            JsonNode push = JSON.readTree(raw);
            out.add(push.get("to").asLong() + " " + push.at("/msg/t").asText()
                    + (push.at("/msg/d/state").isMissingNode() ? "" : " " + push.at("/msg/d/state").asText()));
        }
        return out;
    }

    private static List<Long> players(List<MatchQueue.Waiting> team) {
        return team.stream().flatMap(e -> e.members().stream()).map(MatchQueue.Member::playerId).toList();
    }

    /** A party of three, {@code leader} to {@code leader + 2}, queued for team-vs-team at {@code at}, all in the lobby. */
    private void queuedParty(long leader, int rating, long at) {
        List<MatchQueue.Member> members = new ArrayList<>();
        for (long p = leader; p < leader + 3; p++) {
            members.add(new MatchQueue.Member(p, rating, "p" + p));
            store.sync().set("conn:" + p, "gw-1#x-" + p);
        }
        assertThat(queue.join(members, MatchMode.TVT, null, at)).isEqualTo(MatchQueue.JoinResult.QUEUED);
    }

    /** A team's entry of three, {@code leader} to {@code leader + 2}, queued for a team match at {@code at}, in the lobby. */
    private void queuedTeam(long leader, long team, int rating, long at) {
        List<MatchQueue.Member> members = new ArrayList<>();
        for (long p = leader; p < leader + 3; p++) {
            members.add(new MatchQueue.Member(p, rating, "p" + p));
            store.sync().set("conn:" + p, "gw-1#x-" + p);
        }
        assertThat(queue.join(members, MatchMode.TEAMS, null, team, at)).isEqualTo(MatchQueue.JoinResult.QUEUED);
    }

    private static MatchQueue.Waiting teamEntry(long leader, long team, long since, int rating) {
        List<MatchQueue.Member> members = new ArrayList<>();
        for (long p = leader; p < leader + 3; p++) {
            members.add(new MatchQueue.Member(p, rating, "p" + p));
        }
        return new MatchQueue.Waiting(leader, MatchMode.TEAMS, since, members, team);
    }

    // ---- pairing ------------------------------------------------------------------------

    @Test
    @DisplayName("pairs by the closest rating inside the window, and the older of two decides the window")
    void pairing() {
        // A at 1 200 waiting longest; B at 1 290 and C at 1 250 both inside ±100; C is closer.
        List<Matchmaker.Lineup> made = Matchmaker.lineups(List.of(
                w(1, 1_200, T0), w(2, 1_290, T0 + 1_000), w(3, 1_250, T0 + 2_000)), 1, T0 + 3_000);
        assertThat(made).hasSize(1);
        assertThat(made.get(0).entries()).extracting(MatchQueue.Waiting::playerId).containsExactly(1L, 3L);

        // Two as close, 50 above and 50 below: the older of them, which has waited longer.
        made = Matchmaker.lineups(List.of(w(1, 1_200, T0), w(2, 1_250, T0 + 1), w(3, 1_150, T0 + 2)), 1, T0 + 3);
        assertThat(made.get(0).entries()).extracting(MatchQueue.Waiting::playerId).containsExactly(1L, 2L);

        // 200 apart: not at first; after 20 s, the window is ±200.
        List<MatchQueue.Waiting> far = List.of(w(1, 1_200, T0), w(2, 1_400, T0));
        assertThat(Matchmaker.lineups(far, 1, T0 + 19_999)).isEmpty();
        assertThat(Matchmaker.lineups(far, 1, T0 + 20_000)).hasSize(1);
        assertThat(Matchmaker.window(0)).isEqualTo(100);
        assertThat(Matchmaker.window(10_000)).isEqualTo(150);

        // Four players, two pairs; a fifth waits.
        assertThat(Matchmaker.lineups(List.of(w(1, 1_000, T0), w(2, 1_500, T0), w(3, 1_020, T0),
                w(4, 1_480, T0), w(5, 3_000, T0)), 1, T0)).hasSize(2);
    }

    @Test
    @DisplayName("fills two teams: the oldest in the first, no party split, the least difference between them (04 §4)")
    void teamsAreFilled() {
        List<Matchmaker.Lineup> made = Matchmaker.lineups(List.of(party(10, T0, 1_200, 1_200, 1_200),
                party(20, T0 + 1, 1_210, 1_210, 1_210)), 3, T0 + 2);
        assertThat(made).as("a party against a party").hasSize(1);
        assertThat(players(made.get(0).first())).containsExactly(10L, 11L, 12L);
        assertThat(players(made.get(0).second())).containsExactly(20L, 21L, 22L);

        // A party of two, 1 000 and 1 400, and four alone. Teams: the party and one of them,
        // against the other three. 1 230 balances best (3 630 against 3 645), though 1 195 and
        // 1 180 are closer to the party's 1 200.
        made = Matchmaker.lineups(List.of(party(10, T0, 1_000, 1_400), party(1, T0 + 1, 1_180),
                party(2, T0 + 2, 1_195), party(3, T0 + 3, 1_230), party(4, T0 + 4, 1_270)), 3, T0 + 5);
        assertThat(made).hasSize(1);
        assertThat(players(made.get(0).first())).containsExactly(10L, 11L, 3L);
        assertThat(players(made.get(0).second())).containsExactlyInAnyOrder(1L, 2L, 4L);

        assertThat(Matchmaker.lineups(List.of(party(10, T0, 1_200, 1_200, 1_200), party(1, T0, 1_200),
                party(2, T0, 1_200)), 3, T0)).as("three against two is no match").isEmpty();
        assertThat(Matchmaker.lineups(List.of(party(10, T0, 1_200, 1_200), party(1, T0, 1_200),
                party(2, T0, 1_200), party(3, T0, 1_200)), 3, T0)).as("a party of two is not split").isEmpty();

        // 1 100, the oldest, sees only 1 200 in its window: no match. 1 200 sees the four at 1 290,
        // and not 1 100, which is older: its own window decided for it, and 1 290 is outside that.
        assertThat(Matchmaker.lineups(List.of(party(1, T0, 1_100), party(2, T0 + 1, 1_200), party(3, T0 + 2, 1_290),
                party(4, T0 + 3, 1_290), party(5, T0 + 4, 1_290), party(6, T0 + 5, 1_290)), 3, T0 + 6))
                .as("an older entry is not taken into a younger one's match").isEmpty();

        // The oldest fills no match: the next oldest has its turn.
        List<MatchQueue.Waiting> queued = new ArrayList<>(List.of(party(100, T0, 3_000)));
        for (int i = 1; i <= 6; i++) {
            queued.add(party(i, T0 + i, 1_200));
        }
        made = Matchmaker.lineups(queued, 3, T0 + 7);
        assertThat(made).hasSize(1);
        assertThat(players(made.get(0).entries())).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L);
    }

    @Test
    @DisplayName("more than two sides, each of one: the oldest and the seven closest younger inside its window; fewer wait (04 §4, the fourth slice)")
    void eightEachForThemselves() {
        List<MatchQueue.Waiting> queued = new ArrayList<>(List.of(w(1, 1_200, T0)));
        int[] ratings = {1_290, 1_210, 1_250, 1_190, 1_150, 1_230, 1_170, 1_280, 1_400};
        for (int i = 0; i < ratings.length; i++) {
            queued.add(w(i + 2, ratings[i], T0 + i + 1));
        }
        List<Matchmaker.Lineup> made = Matchmaker.lineups(queued, 1, 8, T0 + 20);
        assertThat(made).hasSize(1);
        assertThat(made.get(0).sides()).hasSize(8).allSatisfy(side -> assertThat(side).hasSize(1));
        assertThat(made.get(0).sides().get(0).get(0).playerId()).as("the oldest first").isEqualTo(1);
        assertThat(made.get(0).entries()).extracting(MatchQueue.Waiting::playerId)
                .as("1 290 is the farthest inside the window and left over; 1 400 is outside it")
                .containsExactlyInAnyOrder(1L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);

        assertThat(Matchmaker.lineups(queued.subList(0, 7), 1, 8, T0 + 20)).as("seven: no match").isEmpty();

        List<MatchQueue.Waiting> fifteen = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            fifteen.add(w(i, 1_200, T0 + i));
        }
        assertThat(Matchmaker.lineups(fifteen, 1, 8, T0 + 20)).as("eight in one, and seven left: no one in two")
                .hasSize(1);
    }

    @Test
    @DisplayName("one side, co-op's: a team filled from the oldest in the order they waited, no party split (04 §4, the fifth slice)")
    void oneSideFilledInOrder() {
        List<MatchQueue.Waiting> queued = List.of(party(10, T0, 0, 0), party(1, T0 + 1, 0), party(2, T0 + 2, 0),
                party(20, T0 + 3, 0, 0));
        List<Matchmaker.Lineup> made = Matchmaker.lineups(queued, 3, 1, T0 + 5);
        assertThat(made).hasSize(2);
        assertThat(made.get(0).sides()).hasSize(1);
        assertThat(players(made.get(0).sides().get(0))).as("the pair and the next alone").containsExactly(10L, 11L, 1L);
        assertThat(players(made.get(1).sides().get(0))).as("then the next alone and the next pair, not split")
                .containsExactly(2L, 20L, 21L);
        assertThat(Matchmaker.lineups(List.of(party(10, T0, 0, 0)), 3, 1, T0)).as("two wait for a third").isEmpty();
        assertThat(Matchmaker.lineups(List.of(party(10, T0, 0, 0, 0)), 3, 1, T0)).as("three play at once").hasSize(1);
        made = Matchmaker.lineups(List.of(party(10, T0, 0, 0), party(20, T0 + 1, 0, 0), party(1, T0 + 2, 0)), 3, 1, T0 + 3);
        assertThat(made).hasSize(1);
        assertThat(players(made.get(0).sides().get(0))).as("a pair does not fit beside a pair: the next alone does")
                .containsExactly(10L, 11L, 1L);
        assertThat(Matchmaker.lineups(List.of(party(10, T0, 0, 0), party(1, T0 + 1, 0), party(2, T0 + 2, 0),
                party(3, T0 + 3, 0)), 3, 1, T0 + 4)).as("one taken is not taken again: two left over wait").hasSize(1);
    }

    @Test
    @DisplayName("a co-op round: a pair and one alone asked, and made into one team, team 1")
    void aCoopRound() throws Exception {
        arena(1, 4);
        assertThat(queue.join(List.of(m(40, 0), m(41, 0)), MatchMode.COOP, null, T0)).isEqualTo(MatchQueue.JoinResult.QUEUED);
        assertThat(queue.join(42, MatchMode.COOP, 0, "p42", T0 + 1)).isEqualTo(MatchQueue.JoinResult.QUEUED);
        for (long p = 40; p <= 42; p++) {
            store.sync().set("conn:" + p, "gw-1#x-" + p);
        }
        matcher.lead();
        assertThat(matcher.match(MatchMode.COOP)).isEqualTo(1);
        accept(40, 41, 42);
        assertThat(matcher.settle()).isEqualTo(1);
        for (long p = 40; p <= 42; p++) {
            Ticket t = tickets.claim(queue.status(p).grant().ticketId()).get(5, TimeUnit.SECONDS);
            assertThat(t.mode()).isEqualTo(MatchMode.COOP.id);
            assertThat(t.team()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("every queued mode's other sides fit among the candidates the oldest is matched with")
    void everyModeFits() {
        for (MatchMode mode : MatchMode.values()) {
            if (mode.queued()) {
                assertThat(mode.roster / mode.teamSize - 1).as(mode.key).isLessThanOrEqualTo(Matchmaker.CANDIDATES);
            }
        }
    }

    @Test
    @DisplayName("the oldest is matched among the ten closest to it, and no more")
    void amongTheTenClosest() {
        // A party of two, 1 000 and 1 400; ten alone at 1 150, and an eleventh at 1 260. With
        // the eleventh the teams would be 3 550 against 3 560; among the ten, 3 550 against 3 450.
        List<MatchQueue.Waiting> queued = new ArrayList<>(List.of(party(100, T0, 1_000, 1_400)));
        for (int i = 1; i <= 10; i++) {
            queued.add(party(i, T0 + i, 1_150));
        }
        queued.add(party(11, T0 + 11, 1_260));
        List<Matchmaker.Lineup> made = Matchmaker.lineups(queued, 3, T0 + 12);
        assertThat(players(made.get(0).first())).as("the oldest's match").startsWith(100L, 101L);
        assertThat(players(made.get(0).entries())).doesNotContain(11L).hasSize(6);
    }

    // ---- a round -------------------------------------------------------------------------

    @Test
    @DisplayName("a match found asks both; once both accept, the round makes it: tickets naming it, a grant to fetch, and the push")
    void aRoundMakesAMatch() throws Exception {
        arena(1, 4);
        queued(11, 1_200, T0);
        queued(12, 1_230, T0 + 500);
        now.set(T0 + 1_000);

        assertThat(matcher.lead()).isTrue();
        assertThat(matcher.match(MatchMode.DUEL)).isEqualTo(1);
        assertThat(store.sync().zcard("mmq:duel")).as("both out of the queue").isZero();
        MatchQueue.Status asked = queue.status(11);
        assertThat(asked.state()).isEqualTo("confirming");
        assertThat(asked.matchUid()).hasSize(26).isEqualTo(queue.status(12).matchUid());
        assertThat(asked.deadline()).isEqualTo(T0 + 1_000 + Matchmaker.CONFIRM_MILLIS);
        for (int i = 0; i < 2; i++) {
            JsonNode ready = JSON.readTree(pushed.poll(5, TimeUnit.SECONDS));
            assertThat(ready.at("/msg/t").asText()).isEqualTo("evt.match.ready");
            assertThat(ready.at("/msg/d/matchUid").asText()).isEqualTo(asked.matchUid());
            assertThat(ready.at("/msg/d/mode").asText()).isEqualTo("duel");
            assertThat(ready.at("/msg/d/seconds").asInt()).isEqualTo(10);
        }
        assertThat(tickets.claim("nothing").get(5, TimeUnit.SECONDS)).isNull();

        accept(11);
        assertThat(matcher.settle()).as("one of two has accepted: it waits").isZero();
        assertThat(queue.status(12).state()).isEqualTo("confirming");
        accept(12);
        assertThat(matcher.settle()).isEqualTo(1);
        assertThat(store.sync().zcard("mmc")).as("no longer waiting for answers").isZero();
        assertThat(matcher.ticketsIssued().get("duel")).as("a ticket each (04 §11)").isEqualTo(2);
        assertThat(store.sync().exists("mmc:" + asked.matchUid())).isZero();
        assertThat(queue.answer(11, asked.matchUid(), false)).as("made: too late to answer").isEqualTo(MatchQueue.Answer.NOT_CONFIRMING);
        assertThat(store.sync().exists("mmc:" + asked.matchUid())).as("and nothing written for it").isZero();
        MatchQueue.Status a = queue.status(11);
        MatchQueue.Status b = queue.status(12);
        assertThat(a.state()).isEqualTo("matched");
        assertThat(a.mode()).as("recorded with the match, as a sandbox's must be").isEqualTo(MatchMode.DUEL);
        assertThat(a.grant().arenaHost()).isEqualTo("10.0.0.7");
        assertThat(a.grant().tls()).isTrue();
        assertThat(a.grant().matchUid()).isEqualTo(b.grant().matchUid()).hasSize(26);
        assertThat(store.sync().zscore("rooms:promised:arena-1", a.grant().matchUid()))
                .as("its room promised to it (D-42)").isNotNull();

        Ticket ta = tickets.claim(a.grant().ticketId()).get(5, TimeUnit.SECONDS);
        Ticket tb = tickets.claim(b.grant().ticketId()).get(5, TimeUnit.SECONDS);
        assertThat(ta.matchUid()).isEqualTo(a.grant().matchUid());
        assertThat(ta.mode()).isEqualTo(MatchMode.DUEL.id);
        assertThat(List.of(ta.team(), tb.team())).as("a mode without teams: 0, every player").containsExactly(0, 0);
        assertThat(ta.displayName()).isEqualTo("p11");

        JsonNode first = JSON.readTree(pushed.poll(5, TimeUnit.SECONDS));
        JsonNode second = JSON.readTree(pushed.poll(5, TimeUnit.SECONDS));
        assertThat(List.of(first.get("to").asLong(), second.get("to").asLong())).containsExactlyInAnyOrder(11L, 12L);
        JsonNode msg = first.get("msg");
        assertThat(msg.get("t").asText()).isEqualTo("evt.match.found");
        assertThat(msg.path("d").get("mode").asText()).isEqualTo("duel");
        assertThat(msg.path("d").get("arenaPort").asInt()).isEqualTo(9001);
    }

    @Test
    @DisplayName("what a player wears, read when they queued, reaches their match's ticket, alone or in a party (D-37)")
    void theBonusReachesTheTicket() throws Exception {
        arena(1, 4);
        assertThat(queue.join(List.of(new MatchQueue.Member(11, 1_200, "p11", "5:8")), MatchMode.DUEL, null, T0))
                .isEqualTo(MatchQueue.JoinResult.QUEUED);
        store.sync().set("conn:11", "gw-1#x-11");
        queued(12, 1_230, T0 + 500);
        assertThat(store.sync().hget("mmp:11", "bonus")).isEqualTo("5:8");
        assertThat(store.sync().hexists("mmp:12", "bonus")).as("none: no field").isFalse();
        now.set(T0 + 1_000);
        matcher.lead();
        assertThat(matcher.match(MatchMode.DUEL)).isEqualTo(1);
        accept(11, 12);
        assertThat(matcher.settle()).isEqualTo(1);
        assertThat(tickets.claim(queue.status(11).grant().ticketId()).get(5, TimeUnit.SECONDS).bonus()).isEqualTo("5:8");
        assertThat(tickets.claim(queue.status(12).grant().ticketId()).get(5, TimeUnit.SECONDS).bonus()).isEmpty();

        List<MatchQueue.Member> party = List.of(new MatchQueue.Member(30, 1_200, "p30"),
                new MatchQueue.Member(31, 1_200, "p31", "6:10"), new MatchQueue.Member(32, 1_200, "p32"));
        for (long p = 30; p < 33; p++) {
            store.sync().set("conn:" + p, "gw-1#x-" + p);
        }
        assertThat(queue.join(party, MatchMode.TVT, null, T0)).isEqualTo(MatchQueue.JoinResult.QUEUED);
        assertThat(store.sync().hget("mmp:30", "b:31")).isEqualTo("6:10");
        queuedParty(40, 1_210, T0 + 500);
        matcher.match(MatchMode.TVT);
        accept(30, 31, 32, 40, 41, 42);
        assertThat(matcher.settle()).isEqualTo(1);
        for (long p : List.of(30L, 31L, 32L, 40L)) {
            Ticket t = tickets.claim(queue.status(p).grant().ticketId()).get(5, TimeUnit.SECONDS);
            assertThat(t.bonus()).as("player " + p).isEqualTo(p == 31 ? "6:10" : "");
        }
    }

    @Test
    @DisplayName("the skin a player wears, read when they queued, reaches their match's ticket, alone or in a party (D-70)")
    void theSkinReachesTheTicket() throws Exception {
        arena(1, 4);
        assertThat(queue.join(List.of(new MatchQueue.Member(11, 1_200, "p11", "", 3)), MatchMode.DUEL, null, T0))
                .isEqualTo(MatchQueue.JoinResult.QUEUED);
        store.sync().set("conn:11", "gw-1#x-11");
        queued(12, 1_230, T0 + 500);
        assertThat(store.sync().hget("mmp:11", "skin")).isEqualTo("3");
        assertThat(store.sync().hexists("mmp:12", "skin")).as("none: no field").isFalse();
        now.set(T0 + 1_000);
        matcher.lead();
        assertThat(matcher.match(MatchMode.DUEL)).isEqualTo(1);
        accept(11, 12);
        assertThat(matcher.settle()).isEqualTo(1);
        assertThat(tickets.claim(queue.status(11).grant().ticketId()).get(5, TimeUnit.SECONDS).skin()).isEqualTo(3);
        assertThat(tickets.claim(queue.status(12).grant().ticketId()).get(5, TimeUnit.SECONDS).skin()).isZero();

        List<MatchQueue.Member> party = List.of(new MatchQueue.Member(30, 1_200, "p30"),
                new MatchQueue.Member(31, 1_200, "p31", "6:10", 5), new MatchQueue.Member(32, 1_200, "p32"));
        for (long p = 30; p < 33; p++) {
            store.sync().set("conn:" + p, "gw-1#x-" + p);
        }
        assertThat(queue.join(party, MatchMode.TVT, null, T0)).isEqualTo(MatchQueue.JoinResult.QUEUED);
        assertThat(store.sync().hget("mmp:30", "s:31")).isEqualTo("5");
        queuedParty(40, 1_210, T0 + 500);
        matcher.match(MatchMode.TVT);
        accept(30, 31, 32, 40, 41, 42);
        assertThat(matcher.settle()).isEqualTo(1);
        for (long p : List.of(30L, 31L, 32L, 40L)) {
            Ticket t = tickets.claim(queue.status(p).grant().ticketId()).get(5, TimeUnit.SECONDS);
            assertThat(List.of(t.skin(), t.bonus())).as("player " + p).containsExactly(p == 31 ? 5 : 0, p == 31 ? "6:10" : "");
        }
    }

    @Test
    @DisplayName("a team round: tickets for teams 1 and 2, a grant for each player, and each told")
    void aTeamRoundMakesAMatch() throws Exception {
        arena(1, 4);
        queuedParty(10, 1_200, T0);
        queuedParty(20, 1_210, T0 + 500);
        assertThat(store.sync().zcard("mmq:tvt")).as("a party is one entry").isEqualTo(2);
        now.set(T0 + 1_000);
        matcher.lead();
        assertThat(matcher.match(MatchMode.TVT)).isEqualTo(1);
        assertThat(store.sync().zcard("mmq:tvt")).isZero();
        assertThat(matcher.waits().count("tvt")).as("each player's wait, not each party's").isEqualTo(6);
        assertThat(told(6)).allMatch(t -> t.endsWith("evt.match.ready"));
        accept(10, 11, 12, 20, 21, 22);
        assertThat(matcher.settle()).isEqualTo(1);

        Map<Long, Integer> teams = new HashMap<>();
        Set<String> matches = new HashSet<>();
        for (long p : List.of(10L, 11L, 12L, 20L, 21L, 22L)) {
            MatchQueue.Status s = queue.status(p);
            assertThat(s.state()).isEqualTo("matched");
            Ticket t = tickets.claim(s.grant().ticketId()).get(5, TimeUnit.SECONDS);
            assertThat(t.mode()).isEqualTo(MatchMode.TVT.id);
            assertThat(t.displayName()).isEqualTo("p" + p);
            teams.put(p, t.team());
            matches.add(t.matchUid());
        }
        assertThat(matches).hasSize(1);
        assertThat(teams).containsEntry(10L, 1).containsEntry(11L, 1).containsEntry(12L, 1)
                .containsEntry(20L, 2).containsEntry(21L, 2).containsEntry(22L, 2);
        Set<Long> told = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            JsonNode push = JSON.readTree(pushed.poll(5, TimeUnit.SECONDS));
            assertThat(push.at("/msg/t").asText()).isEqualTo("evt.match.found");
            told.add(push.get("to").asLong());
        }
        assertThat(told).containsExactlyInAnyOrder(10L, 11L, 12L, 20L, 21L, 22L);
    }

    @Test
    @DisplayName("a ranked free-for-all round: eight asked, eight accept, eight tickets for one match, every player team 0")
    void aFreeForAllRound() throws Exception {
        arena(1, 4);
        for (long p = 31; p <= 38; p++) {
            queue.join(p, MatchMode.RFFA, 1_200, "p" + p, T0 + p);
            store.sync().set("conn:" + p, "gw-1#x-" + p);
        }
        matcher.lead();
        assertThat(matcher.match(MatchMode.RFFA)).isEqualTo(1);
        accept(31, 32, 33, 34, 35, 36, 37, 38);
        assertThat(matcher.settle()).isEqualTo(1);
        Set<String> matches = new HashSet<>();
        for (long p = 31; p <= 38; p++) {
            Ticket t = tickets.claim(queue.status(p).grant().ticketId()).get(5, TimeUnit.SECONDS);
            assertThat(t.mode()).isEqualTo(MatchMode.RFFA.id);
            assertThat(t.team()).isZero();
            matches.add(t.matchUid());
        }
        assertThat(matches).hasSize(1);
    }

    @Test
    @DisplayName("measured (04 §11): each player's wait when their match is found, the players left waiting, and how each confirm step ended")
    void theQueueIsMeasured() throws Exception {
        arena(1, 4);
        queued(11, 1_200, T0);
        queued(12, 1_230, T0 + 500);
        queued(13, 3_000, T0 + 600);                                // nobody near it
        now.set(T0 + 4_000);
        matcher.lead();
        assertThat(matcher.match(MatchMode.DUEL)).isEqualTo(1);
        assertThat(matcher.waits().count("duel")).isEqualTo(2);
        assertThat(matcher.waiting()).containsEntry("duel", 1.0).containsEntry("tvt", 0.0);
        Metrics m = new Metrics();
        matcher.registerMetrics(m);
        assertThat(m.render()).contains("backend_platform_queue_wait_seconds_sum{mode=\"duel\"} 7.5")
                .contains("backend_platform_queue_wait_seconds_bucket{mode=\"duel\",le=\"5\"} 2")
                .contains("backend_platform_queue_players{mode=\"duel\"} 1");
        accept(11, 12);
        matcher.settle();
        assertThat(matcher.confirms().get("made")).isEqualTo(1);
        assertThat(m.render()).contains("backend_platform_confirms_total{outcome=\"made\"} 1");
    }

    @Test
    @DisplayName("a party with a member not in the lobby is dropped whole, and those still there are told")
    void aPartyWithAnAbsentMemberIsDropped() throws Exception {
        arena(0, 4);
        queuedParty(10, 1_200, T0);
        store.sync().del("conn:11");
        matcher.lead();
        assertThat(matcher.match(MatchMode.TVT)).isZero();
        assertThat(store.sync().zcard("mmq:tvt")).isZero();
        for (long p : List.of(10L, 11L, 12L)) {
            assertThat(queue.status(p).state()).isEqualTo("none");
        }
        Set<Long> told = new HashSet<>();
        for (int i = 0; i < 2; i++) {
            JsonNode push = JSON.readTree(pushed.poll(5, TimeUnit.SECONDS));
            assertThat(push.at("/msg/t").asText()).isEqualTo("evt.queue.update");
            assertThat(push.at("/msg/d/state").asText()).isEqualTo("none");
            told.add(push.get("to").asLong());
        }
        assertThat(told).containsExactlyInAnyOrder(10L, 12L);
        assertThat(pushed.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("a party with a member called to a tournament match is dropped whole, and all of it told; nobody locked (Q-44)")
    void aPartyWithACalledMemberIsDropped() throws Exception {
        arena(0, 4);
        queuedParty(10, 1_200, T0);
        new TournamentGrants(store).put(9, 11, "{}").get(5, TimeUnit.SECONDS);
        matcher.lead();
        assertThat(matcher.match(MatchMode.TVT)).isZero();
        assertThat(store.sync().zcard("mmq:tvt")).isZero();
        Set<Long> told = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            JsonNode push = JSON.readTree(pushed.poll(5, TimeUnit.SECONDS));
            assertThat(push.at("/msg/t").asText()).isEqualTo("evt.queue.update");
            assertThat(push.at("/msg/d/state").asText()).isEqualTo("none");
            told.add(push.get("to").asLong());
        }
        assertThat(told).as("the called one is in the lobby too").containsExactlyInAnyOrder(10L, 11L, 12L);
        assertThat(locked(10) || locked(11) || locked(12)).isFalse();
    }

    @Test
    @DisplayName("accepted, with no arena room free: the pair goes back, first in line, times intact, and is told")
    void noRoomPutsThePairBack() throws Exception {
        arena(4, 4);
        queued(11, 1_200, T0);
        queued(12, 1_200, T0 + 500);
        matcher.lead();
        assertThat(matcher.match(MatchMode.DUEL)).isEqualTo(1);
        accept(11, 12);
        assertThat(matcher.settle()).isZero();
        assertThat(matcher.confirms().get("no_room")).isEqualTo(1);
        assertThat(store.sync().zscore("mmq:duel", "11")).isEqualTo((double) T0);
        assertThat(store.sync().zscore("mmq:duel", "12")).isEqualTo((double) (T0 + 500));
        assertThat(queue.status(11).state()).isEqualTo("queued");
        assertThat(queue.status(11).matchUid()).isNull();
        assertThat(store.sync().zcard("mmc")).isZero();
        assertThat(queue.waiting(MatchMode.DUEL, new ArrayList<>())).as("queued again, as the matcher reads it").hasSize(2);
        assertThat(told(4)).containsExactlyInAnyOrder("11 evt.match.ready", "12 evt.match.ready",
                "11 evt.queue.update queued", "12 evt.queue.update queued");

        arena(3, 4);
        assertThat(matcher.match(MatchMode.DUEL)).as("next round, once there is room").isEqualTo(1);
        accept(11, 12);
        assertThat(matcher.settle()).isEqualTo(1);
    }

    // ---- the confirm step (04 §4, the third slice) -------------------------------------------

    @Test
    @DisplayName("one decline calls it off: the decliner is out and locked for a minute, the other back where they were and told")
    void aDeclineCallsItOff() throws Exception {
        arena(1, 4);
        queued(11, 1_200, T0);
        queued(12, 1_200, T0 + 500);
        matcher.lead();
        matcher.match(MatchMode.DUEL);
        String uid = queue.status(11).matchUid();
        accept(12);
        accept(11);
        assertThat(queue.answer(11, uid, false)).as("a mind changed in time").isEqualTo(MatchQueue.Answer.RECORDED);
        assertThat(matcher.settle()).isZero();

        assertThat(queue.status(11).state()).isEqualTo("none");
        assertThat(locked(11)).isTrue();
        assertThat(store.sync().ttl("mmlock:11")).isBetween(1L, (long) Matchmaker.LOCK_SECONDS);
        assertThat(locked(12)).isFalse();
        assertThat(queue.status(12).state()).isEqualTo("queued");
        assertThat(store.sync().zscore("mmq:duel", "12")).as("its waiting time kept").isEqualTo((double) (T0 + 500));
        assertThat(store.sync().zscore("mmq:duel", "11")).isNull();
        assertThat(store.sync().zcard("mmc")).isZero();
        assertThat(told(4)).containsExactlyInAnyOrder("11 evt.match.ready", "12 evt.match.ready",
                "11 evt.queue.update none", "12 evt.queue.update queued");
        assertThat(matcher.confirms().get("declined")).isEqualTo(1);
        assertThat(queue.answer(12, uid, true)).as("an answer to a match that is off").isEqualTo(MatchQueue.Answer.NOT_CONFIRMING);

        // 12 accepted that one; the next match found is asked afresh.
        queued(13, 1_200, T0 + 900);
        matcher.match(MatchMode.DUEL);
        accept(13);
        assertThat(matcher.settle()).as("12 has not answered this one").isZero();
        assertThat(queue.status(12).state()).isEqualTo("confirming");
    }

    @Test
    @DisplayName("silence calls it off at the deadline, and not before: the silent one is out and locked")
    void silenceCallsItOff() throws Exception {
        arena(1, 4);
        queued(11, 1_200, T0);
        queued(12, 1_200, T0 + 500);
        now.set(T0 + 1_000);
        matcher.lead();
        matcher.match(MatchMode.DUEL);
        accept(12);
        now.set(T0 + 1_000 + Matchmaker.CONFIRM_MILLIS - 1);
        assertThat(matcher.settle()).isZero();
        assertThat(queue.status(11).state()).as("still asked").isEqualTo("confirming");
        now.set(T0 + 1_000 + Matchmaker.CONFIRM_MILLIS);
        assertThat(matcher.settle()).isZero();
        assertThat(matcher.confirms().get("lapsed")).isEqualTo(1);
        assertThat(matcher.confirms().get("declined")).isZero();
        assertThat(queue.status(11).state()).isEqualTo("none");
        assertThat(locked(11)).isTrue();
        assertThat(queue.status(12).state()).isEqualTo("queued");
        assertThat(locked(12)).isFalse();
    }

    @Test
    @DisplayName("a player called to a tournament match while asked, and silent, is not locked out for it, and leaves the queue (Q-44)")
    void aCalledSilenceIsNotLocked() throws Exception {
        arena(1, 4);
        queued(11, 1_200, T0);
        queued(12, 1_200, T0 + 500);
        now.set(T0 + 1_000);
        matcher.lead();
        matcher.match(MatchMode.DUEL);
        accept(12);
        new TournamentGrants(store).put(9, 11, "{}").get(5, TimeUnit.SECONDS);
        now.set(T0 + 1_000 + Matchmaker.CONFIRM_MILLIS);
        assertThat(matcher.settle()).isZero();
        assertThat(matcher.confirms().get("lapsed")).isEqualTo(1);
        assertThat(locked(11)).as("called, not refusing").isFalse();
        matcher.match(MatchMode.DUEL);
        assertThat(queue.status(11).state()).as("out at the next round").isEqualTo("none");
        assertThat(queue.status(12).state()).isEqualTo("queued");
    }

    @Test
    @DisplayName("a party goes with its decliner, and only the decliner is locked; the other party goes back")
    void aPartyGoesWithItsDecliner() throws Exception {
        arena(1, 4);
        queuedParty(10, 1_200, T0);
        queuedParty(20, 1_210, T0 + 500);
        matcher.lead();
        matcher.match(MatchMode.TVT);
        accept(10, 12, 20, 21, 22);
        assertThat(queue.answer(11, queue.status(11).matchUid(), false)).isEqualTo(MatchQueue.Answer.RECORDED);
        matcher.settle();
        for (long p = 10; p <= 12; p++) {
            assertThat(queue.status(p).state()).isEqualTo("none");
            assertThat(locked(p)).isEqualTo(p == 11);
        }
        for (long p = 20; p <= 22; p++) {
            assertThat(queue.status(p).state()).isEqualTo("queued");
        }
        List<MatchQueue.Waiting> back = queue.waiting(MatchMode.TVT, new ArrayList<>());
        assertThat(back).as("the other party, whole, as it was").containsExactly(
                new MatchQueue.Waiting(20, MatchMode.TVT, T0 + 500, List.of(new MatchQueue.Member(20, 1_210, "p20"),
                        new MatchQueue.Member(21, 1_210, "p21"), new MatchQueue.Member(22, 1_210, "p22"))));
    }

    @Test
    @DisplayName("a team match pairs two teams' entries, and never two entries of one team (Q-18)")
    void aTeamNeverMeetsItself() {
        MatchQueue.Waiting first = teamEntry(10, 7, T0, 1_200);
        MatchQueue.Waiting again = teamEntry(20, 7, T0 + 1, 1_200);
        MatchQueue.Waiting other = teamEntry(30, 9, T0 + 2, 1_250);
        List<Matchmaker.Lineup> made = Matchmaker.lineups(List.of(first, again, other), 3, T0 + 3);
        assertThat(made).hasSize(1);
        assertThat(players(made.get(0).first())).containsExactly(10L, 11L, 12L);
        assertThat(players(made.get(0).second())).as("the other team, though further in rating").containsExactly(30L, 31L, 32L);
        assertThat(Matchmaker.lineups(List.of(first, again), 3, T0 + 3)).as("one team's two entries").isEmpty();
        assertThat(Matchmaker.lineups(List.of(party(10, T0, 1_200, 1_200, 1_200), party(20, T0 + 1, 1_200, 1_200, 1_200)),
                3, T0 + 3)).as("entries of no team meet as ever").hasSize(1);
    }

    @Test
    @DisplayName("a team's entry keeps its team in the queue, and queued again after a decline; made, team 1 against team 2")
    void aTeamMatchIsMade() throws Exception {
        arena(1, 4);
        queuedTeam(10, 7, 1_200, T0);
        queuedTeam(20, 7, 1_200, T0 + 1);
        queuedTeam(30, 9, 1_200, T0 + 2);
        assertThat(queue.waiting(MatchMode.TEAMS, new ArrayList<>())).extracting(MatchQueue.Waiting::team)
                .containsExactly(7L, 7L, 9L);
        now.set(T0 + 1_000);
        matcher.lead();

        assertThat(matcher.match(MatchMode.TEAMS)).isEqualTo(1);
        assertThat(queue.status(20).state()).as("the team's other entry waits").isEqualTo("queued");
        accept(10, 11, 12, 30, 32);
        assertThat(queue.answer(31, queue.status(31).matchUid(), false)).isEqualTo(MatchQueue.Answer.RECORDED);
        matcher.settle();
        assertThat(queue.waiting(MatchMode.TEAMS, new ArrayList<>())).as("queued again, its team kept")
                .extracting(MatchQueue.Waiting::playerId, MatchQueue.Waiting::team)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(10L, 7L), org.assertj.core.groups.Tuple.tuple(20L, 7L));

        queuedTeam(40, 9, 1_200, T0 + 3);
        pushed.clear();
        assertThat(matcher.match(MatchMode.TEAMS)).isEqualTo(1);
        accept(10, 11, 12, 40, 41, 42);
        assertThat(matcher.settle()).isEqualTo(1);
        Ticket leader = tickets.claim(queue.status(10).grant().ticketId()).get(5, TimeUnit.SECONDS);
        Ticket other = tickets.claim(queue.status(41).grant().ticketId()).get(5, TimeUnit.SECONDS);
        assertThat(List.of(leader.mode(), leader.team(), other.team())).containsExactly(MatchMode.TEAMS.id, 1, 2);
    }

    @Test
    @DisplayName("an answer counts only from a player asked, about the match they were asked about, while it waits")
    void anAnswerIsForItsMatch() {
        queued(11, 1_200, T0);
        queued(12, 1_200, T0);
        queued(13, 1_200, T0 + 1);
        matcher.lead();
        matcher.match(MatchMode.DUEL);
        String uid = queue.status(11).matchUid();
        assertThat(queue.answer(11, "01JC0000000000000000000000", true)).isEqualTo(MatchQueue.Answer.NOT_CONFIRMING);
        assertThat(queue.answer(13, uid, true)).as("queued, not asked").isEqualTo(MatchQueue.Answer.NOT_CONFIRMING);
        assertThat(queue.answer(99, uid, true)).isEqualTo(MatchQueue.Answer.NOT_CONFIRMING);
        assertThat(store.sync().hget("mmp:13", "answer")).isNull();
    }

    @Test
    @DisplayName("leaving the queue while asked is declining")
    void leavingWhileAskedDeclines() throws Exception {
        arena(1, 4);
        queued(11, 1_200, T0);
        queued(12, 1_200, T0 + 500);
        matcher.lead();
        matcher.match(MatchMode.DUEL);
        assertThat(queue.leave(11)).as("nobody is out until the round").isEmpty();
        assertThat(matcher.settle()).isZero();
        assertThat(locked(11)).isTrue();
        assertThat(queue.status(12).state()).as("yet to answer, not silent: its time was not up").isEqualTo("queued");
        assertThat(locked(12)).isFalse();
    }

    @Test
    @DisplayName("six answering at once are all recorded: an answer is the player's own, and waits on nobody else's (T-16)")
    void answersDoNotContend() throws Exception {
        queuedParty(10, 1_200, T0);
        queuedParty(20, 1_200, T0);
        matcher.lead();
        matcher.match(MatchMode.TVT);
        String uid = queue.status(10).matchUid();
        List<Long> six = List.of(10L, 11L, 12L, 20L, 21L, 22L);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            for (int round = 0; round < 20; round++) {
                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                List<java.util.concurrent.Future<MatchQueue.Answer>> answers = new ArrayList<>();
                for (long p : six) {
                    answers.add(pool.submit(() -> {
                        go.await();
                        return queue.answer(p, uid, true);
                    }));
                }
                go.countDown();
                for (java.util.concurrent.Future<MatchQueue.Answer> a : answers) {
                    assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(MatchQueue.Answer.RECORDED);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(queue.pending().get(0).accepted()).containsExactlyInAnyOrderElementsOf(six);
    }

    @Test
    @DisplayName("a round settles the matches asked about before it finds more: one asks, the next makes")
    void aRoundSettlesFirst() throws Exception {
        arena(1, 4);
        queued(11, 1_200, T0);
        queued(12, 1_200, T0);
        matcher.round();
        assertThat(queue.status(11).state()).isEqualTo("confirming");
        accept(11, 12);
        matcher.round();
        assertThat(queue.status(11).state()).isEqualTo("matched");
        assertThat(queue.status(12).state()).isEqualTo("matched");
    }

    @Test
    @DisplayName("a match's wait is ended once, by whichever matcher gets there first; an asked player's record lasts as long as the wait, and the queue's again once back (T-34)")
    void aWaitEndsOnce() {
        queued(11, 1_200, T0);
        queued(12, 1_200, T0 + 500);
        matcher.lead();
        matcher.match(MatchMode.DUEL);
        String uid = queue.status(11).matchUid();
        assertThat(store.sync().ttl("mmp:11")).as("as long as the wait").isBetween(1L, (long) MatchQueue.PENDING_TTL_SECONDS);
        assertThat(store.sync().ttl("mmc:" + uid)).isBetween(1L, (long) MatchQueue.PENDING_TTL_SECONDS);
        MatchQueue.Pending read = queue.pending().get(0);
        assertThat(read.matchUid()).isEqualTo(uid);
        assertThat(queue.end(read, false, Set.of(), Matchmaker.LOCK_SECONDS)).isNotNull();
        assertThat(queue.end(read, false, Set.of(), Matchmaker.LOCK_SECONDS)).as("another matcher's, now").isNull();
        assertThat(queue.pending()).isEmpty();
        assertThat(store.sync().zscore("mmq:duel", "12")).isEqualTo((double) (T0 + 500));
        assertThat(store.sync().ttl("mmp:12")).as("back in the queue, for the queue's time")
                .isGreaterThan((long) MatchQueue.PENDING_TTL_SECONDS);
    }

    @Test
    @DisplayName("a match is made once: the second making finds its players matched already")
    void aMatchIsMadeOnce() {
        queued(11, 1_200, T0);
        queued(12, 1_200, T0);
        matcher.lead();
        matcher.match(MatchMode.DUEL);
        accept(11, 12);
        MatchQueue.Pending read = queue.pending().get(0);
        Map<Long, MatchQueue.Grant> grants = Map.of(11L, new MatchQueue.Grant("h", 1, "t11", false, read.matchUid()),
                12L, new MatchQueue.Grant("h", 1, "t12", false, read.matchUid()));
        assertThat(queue.make(read, grants, 60)).isTrue();
        assertThat(queue.make(read, grants, 60)).isFalse();
        assertThat(queue.status(12).grant().ticketId()).isEqualTo("t12");
        assertThat(store.sync().ttl("mmp:12")).as("as long as its ticket").isBetween(1L, 60L);
        assertThat(queue.pending()).isEmpty();
    }

    // ---- each step one transaction (04 §4, plan item 56) ------------------------------------

    @Test
    @DisplayName("a lineup is asked about only as the round read it: one who left, or left and came back, is not asked, nor anyone with them (T-33)")
    void aChangedLineupIsNotAsked() {
        queued(11, 1_200, T0);
        queued(12, 1_200, T0 + 500);
        List<MatchQueue.Waiting> read = queue.waiting(MatchMode.DUEL, new ArrayList<>());
        List<List<MatchQueue.Waiting>> sides = List.of(List.of(read.get(0)), List.of(read.get(1)));
        queue.leave(12);
        assertThat(queue.ask(MatchMode.DUEL, "01JC0000000000000000000001", sides, T0 + 10_000)).isFalse();
        assertThat(store.sync().exists("mmp:12")).as("not written asked, a record with no mode").isZero();
        assertThat(queue.status(11).state()).isEqualTo("queued");
        assertThat(store.sync().zscore("mmq:duel", "11")).as("still in line").isEqualTo((double) T0);
        assertThat(store.sync().zcard("mmc")).isZero();

        queued(12, 1_250, T0 + 2_000);
        assertThat(queue.ask(MatchMode.DUEL, "01JC0000000000000000000001", sides, T0 + 10_000))
                .as("back, but not the entry read").isFalse();
        assertThat(queue.status(12).state()).isEqualTo("queued");

        List<MatchQueue.Waiting> again = queue.waiting(MatchMode.DUEL, new ArrayList<>());
        List<List<MatchQueue.Waiting>> now = List.of(List.of(again.get(0)), List.of(again.get(1)));
        assertThat(queue.ask(MatchMode.DUEL, "01JC0000000000000000000002", now, T0 + 10_000)).isTrue();
        assertThat(store.sync().zcard("mmq:duel")).as("out of the queue as they are asked").isZero();
        assertThat(queue.ask(MatchMode.DUEL, "01JC0000000000000000000003", now, T0 + 10_000))
                .as("asked already, by another matcher").isFalse();
        assertThat(queue.status(11).matchUid()).isEqualTo("01JC0000000000000000000002");
        assertThat(queue.pending()).hasSize(1);
    }

    @Test
    @DisplayName("a mind changed after the round read the answers: the match is not made, its room is given back, and the next round calls it off (T-33)")
    void aLateDeclineIsNotMade() throws Exception {
        arena(1, 4);
        queued(11, 1_200, T0);
        queued(12, 1_200, T0 + 500);
        matcher.lead();
        matcher.match(MatchMode.DUEL);
        accept(11, 12);
        MatchQueue.Pending read = queue.pending().get(0);
        assertThat(queue.leave(12)).as("leaving is declining").isEmpty();
        assertThat(matcher.make(read)).isFalse();
        assertThat(queue.status(11).state()).isEqualTo("confirming");
        assertThat(store.sync().zcard("rooms:promised:arena-1")).as("its room given back").isZero();
        assertThat(matcher.ticketsIssued().get("duel")).as("none issued to anyone").isZero();

        assertThat(matcher.settle()).isZero();
        assertThat(matcher.confirms().get("declined")).isEqualTo(1);
        assertThat(queue.status(12).state()).isEqualTo("none");
        assertThat(locked(12)).isTrue();
        assertThat(queue.status(11).state()).isEqualTo("queued");
        assertThat(told(4)).containsExactlyInAnyOrder("11 evt.match.ready", "12 evt.match.ready",
                "11 evt.queue.update queued", "12 evt.queue.update none");
    }

    @Test
    @DisplayName("no room, and a mind changed since the read: the decliner is out and locked, not queued again (T-33)")
    void noRoomAfterALateDecline() throws Exception {
        arena(4, 4);
        queued(11, 1_200, T0);
        queued(12, 1_200, T0 + 500);
        matcher.lead();
        matcher.match(MatchMode.DUEL);
        accept(11, 12);
        MatchQueue.Pending read = queue.pending().get(0);
        queue.leave(12);
        assertThat(matcher.make(read)).isFalse();
        assertThat(matcher.confirms().get("no_room")).isEqualTo(1);
        assertThat(queue.status(12).state()).isEqualTo("none");
        assertThat(locked(12)).isTrue();
        assertThat(queue.status(11).state()).isEqualTo("queued");
        assertThat(store.sync().zscore("mmq:duel", "11")).isEqualTo((double) T0);
    }

    @Test
    @DisplayName("calling off reads the answers as they are, not as the round read them: one who accepted and then left is out and locked (T-33)")
    void aCallOffReadsTheAnswersAgain() {
        queued(11, 1_200, T0);
        queued(12, 1_200, T0 + 500);
        matcher.lead();
        matcher.match(MatchMode.DUEL);
        String uid = queue.status(11).matchUid();
        queue.answer(11, uid, false);
        accept(12);
        MatchQueue.Pending read = queue.pending().get(0);
        queue.leave(12);
        MatchQueue.Ended ended = queue.end(read, false, Set.of(), Matchmaker.LOCK_SECONDS);
        assertThat(ended.out()).containsExactlyInAnyOrder(11L, 12L);
        assertThat(ended.back()).isEmpty();
        assertThat(ended.declined()).isTrue();
        assertThat(locked(11)).isTrue();
        assertThat(locked(12)).isTrue();
        assertThat(queue.status(12).state()).isEqualTo("none");
        assertThat(store.sync().zcard("mmq:duel")).isZero();
    }

    @Test
    @DisplayName("a lockout is read with the records a join writes: the join itself refuses a locked player (T-33)")
    void aLockIsReadByTheJoin() {
        store.sync().set(MatchQueue.lockKey(12), "1");
        assertThat(queue.join(List.of(m(11, 1_200), m(12, 1_200)), MatchMode.TVT, null, T0))
                .isEqualTo(MatchQueue.JoinResult.LOCKED);
        assertThat(store.sync().exists("mmp:11")).isZero();
        assertThat(store.sync().zcard("mmq:tvt")).isZero();
        assertThat(queue.join(11, MatchMode.DUEL, 1_200, "p11", T0)).isEqualTo(MatchQueue.JoinResult.QUEUED);
    }

    @Test
    @DisplayName("a party that changes while asked is out of the match, all of it told and none of it locked; the other goes back (T-32)")
    void aPartyChangedWhileAskedIsOut() throws Exception {
        arena(1, 4);
        queuedParty(10, 1_200, T0);
        queuedParty(20, 1_210, T0 + 500);
        matcher.lead();
        matcher.match(MatchMode.TVT);
        String uid = queue.status(10).matchUid();
        accept(10, 11, 12, 20, 21, 22);
        assertThat(queue.unqueue(11)).as("11 left the party: nobody is out until the round").isEmpty();
        assertThat(queue.answer(11, uid, true)).as("withdrawn, and not undone").isEqualTo(MatchQueue.Answer.NOT_CONFIRMING);
        assertThat(queue.leave(11)).isEmpty();
        assertThat(matcher.settle()).isZero();
        assertThat(matcher.confirms().get("withdrawn")).isEqualTo(1);
        for (long p = 10; p <= 12; p++) {
            assertThat(queue.status(p).state()).isEqualTo("none");
            assertThat(locked(p)).as("leaving a party is not refusing a match").isFalse();
        }
        for (long p = 20; p <= 22; p++) {
            assertThat(queue.status(p).state()).isEqualTo("queued");
        }
        assertThat(told(6)).allMatch(t -> t.endsWith("evt.match.ready"));
        assertThat(told(6)).containsExactlyInAnyOrder("10 evt.queue.update none", "11 evt.queue.update none",
                "12 evt.queue.update none", "20 evt.queue.update queued", "21 evt.queue.update queued",
                "22 evt.queue.update queued");
    }

    @Test
    @DisplayName("one who declined and then left the party stays locked out: withdrawing does not undo a decline (T-32)")
    void aDeclineOutlastsAPartyChange() throws Exception {
        arena(1, 4);
        queuedParty(10, 1_200, T0);
        queuedParty(20, 1_210, T0 + 500);
        matcher.lead();
        matcher.match(MatchMode.TVT);
        queue.answer(21, queue.status(21).matchUid(), false);
        queue.unqueue(21);
        matcher.settle();
        assertThat(locked(21)).isTrue();
        assertThat(matcher.confirms().get("declined")).isEqualTo(1);
    }

    @Test
    @DisplayName("a matcher that fails while writing the tickets leaves the match waiting and its players asked; the next round makes it (T-34)")
    void aFailedMakingIsMadeNextRound() throws Exception {
        arena(1, 4);
        queued(11, 1_200, T0);
        queued(12, 1_200, T0 + 500);
        matcher.lead();
        matcher.match(MatchMode.DUEL);
        accept(11, 12);
        int deadPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        JRedisClient down = JRedisClient.builder().address("127.0.0.1", deadPort).clientName("down").build().start();
        try {
            Matchmaker failing = new Matchmaker(store, queue, new ArenaDirectory(store), new TicketStore(down),
                    new LobbyPush(store), new TournamentGrants(store), "test-1", now::get);
            org.assertj.core.api.Assertions.assertThatThrownBy(failing::settle).isInstanceOf(RuntimeException.class);
        } finally {
            down.close();
        }
        assertThat(queue.status(11).state()).as("still asked").isEqualTo("confirming");
        assertThat(queue.pending()).as("still waiting").hasSize(1);
        assertThat(matcher.settle()).isEqualTo(1);
        assertThat(queue.status(11).state()).isEqualTo("matched");
        assertThat(queue.status(12).state()).isEqualTo("matched");
    }

    @Test
    @DisplayName("a player with no lobby connection is dropped and forgotten, and one who left is tidied away")
    void theAbsentAreDropped() throws Exception {
        arena(0, 4);
        queued(11, 1_200, T0);
        queue.join(12, MatchMode.DUEL, 1_200, "p12", T0);        // queued, but no lobby connection
        queued(13, 1_200, T0);
        store.sync().del("mmp:13");                              // left, or expired, while listed
        matcher.lead();
        assertThat(matcher.match(MatchMode.DUEL)).isZero();
        assertThat(store.sync().zcard("mmq:duel")).as("only 11 is still waiting").isEqualTo(1);
        assertThat(queue.status(12).state()).isEqualTo("none");
        assertThat(pushed.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("one matcher leads at a time, and another takes over when its lease lapses")
    void oneLeader() {
        Matchmaker other = new Matchmaker(store, queue, new ArenaDirectory(store), tickets, new LobbyPush(store),
                new TournamentGrants(store), "test-2", now::get);
        assertThat(matcher.lead()).isTrue();
        assertThat(other.lead()).isFalse();
        assertThat(matcher.lead()).as("renewed by its holder").isTrue();
        assertThat(store.sync().pttl(Matchmaker.LEADER_KEY)).isBetween(1L, Matchmaker.LEASE_MILLIS);
        store.sync().del(Matchmaker.LEADER_KEY);                 // the holder died, and the lease ran out
        assertThat(other.lead()).isTrue();
        assertThat(matcher.lead()).isFalse();

        queued(11, 1_200, T0);
        matcher.match(MatchMode.DUEL);
        assertThat(matcher.waiting()).containsEntry("duel", 1.0);
        matcher.round();                                         // not leading: its count is stale
        assertThat(matcher.waiting()).as("the leader's gauge is the one to read").containsEntry("duel", 0.0);
    }

    @Test
    @DisplayName("joining twice queues once, and a matched player cannot queue until the match is gone")
    void oneQueueOneMatch() {
        assertThat(queue.join(11, MatchMode.DUEL, 1_234, "p11", T0)).isEqualTo(MatchQueue.JoinResult.QUEUED);
        assertThat(queue.waiting(MatchMode.DUEL, new ArrayList<>())).as("read back as it was queued")
                .containsExactly(new MatchQueue.Waiting(11, MatchMode.DUEL, T0, 1_234, "p11"));
        assertThat(queue.join(11, MatchMode.DUEL, 1_200, "p11", T0 + 1)).isEqualTo(MatchQueue.JoinResult.ALREADY_QUEUED);
        assertThat(store.sync().zscore("mmq:duel", "11")).isEqualTo((double) T0);
        assertThat(store.sync().ttl("mmp:11")).isBetween(1L, (long) MatchQueue.QUEUED_TTL_SECONDS);

        queue.matched(11, MatchMode.DUEL, new MatchQueue.Grant("h", 1, "t", false, "01JC0000000000000000000000"), 60, System.currentTimeMillis());
        assertThat(queue.join(11, MatchMode.DUEL, 1_200, "p11", T0 + 2)).isEqualTo(MatchQueue.JoinResult.IN_MATCH);
        assertThat(store.sync().ttl("mmp:11")).as("as long as its ticket").isBetween(1L, 60L);
        queue.leave(11);
        assertThat(queue.join(11, MatchMode.DUEL, 1_200, "p11", T0 + 3)).isEqualTo(MatchQueue.JoinResult.QUEUED);
    }

    // ---- a party in the queue ---------------------------------------------------------------

    private static MatchQueue.Member m(long id, int rating) {
        return new MatchQueue.Member(id, rating, "p" + id);
    }

    @Test
    @DisplayName("a party is one entry, under its leader, each member's rating and name kept; a member leaving the queue takes it all out")
    void aPartyIsOneEntry() {
        assertThat(queue.join(List.of(m(10, 1_100), m(11, 1_200), m(12, 1_330)), MatchMode.TVT, null, T0))
                .isEqualTo(MatchQueue.JoinResult.QUEUED);
        assertThat(store.sync().zrange("mmq:tvt", 0, -1)).containsExactly("10");
        for (long p : List.of(10L, 11L, 12L)) {
            assertThat(queue.status(p).state()).isEqualTo("queued");
            assertThat(queue.status(p).mode()).isEqualTo(MatchMode.TVT);
            assertThat(queue.status(p).since()).isEqualTo(T0);
            assertThat(store.sync().ttl("mmp:" + p)).isBetween(1L, (long) MatchQueue.QUEUED_TTL_SECONDS);
        }
        List<MatchQueue.Waiting> waiting = queue.waiting(MatchMode.TVT, new ArrayList<>());
        assertThat(waiting).hasSize(1);
        assertThat(waiting.get(0).playerId()).isEqualTo(10);
        assertThat(waiting.get(0).members()).containsExactly(m(10, 1_100), m(11, 1_200), m(12, 1_330));
        assertThat(waiting.get(0).rating()).as("the mean").isEqualTo(1_210);

        assertThat(queue.join(11, MatchMode.DUEL, 1_200, "p11", T0)).as("a member, queued with the party")
                .isEqualTo(MatchQueue.JoinResult.ALREADY_QUEUED);
        assertThat(queue.join(List.of(m(30, 1_200), m(12, 1_200)), MatchMode.TVT, null, T0))
                .isEqualTo(MatchQueue.JoinResult.ALREADY_QUEUED);
        assertThat(store.sync().exists("mmp:30")).as("nothing written for the rest").isZero();

        assertThat(queue.leave(12)).containsExactlyInAnyOrder(10L, 11L, 12L);
        assertThat(store.sync().zcard("mmq:tvt")).isZero();
        for (long p : List.of(10L, 11L, 12L)) {
            assertThat(queue.status(p).state()).isEqualTo("none");
        }
        queuedParty(10, 1_200, T0);
        assertThat(queue.unqueue(10)).as("the leader's too").containsExactlyInAnyOrder(10L, 11L, 12L);
        assertThat(queue.unqueue(10)).as("in no queue").isEmpty();
    }

    @Test
    @DisplayName("once matched, a member's match is their own: leaving forgets theirs only, and a party change leaves it be")
    void aMatchedMemberIsAlone() {
        queuedParty(10, 1_200, T0);
        store.sync().zrem("mmq:tvt", "10");
        for (long p = 10; p <= 12; p++) {
            queue.matched(p, MatchMode.TVT, new MatchQueue.Grant("h", 1, "t" + p, false, "01JC0000000000000000000000"), 60, System.currentTimeMillis());
        }
        assertThat(queue.unqueue(11)).isEmpty();
        assertThat(queue.status(11).state()).isEqualTo("matched");
        assertThat(queue.leave(11)).containsExactly(11L);
        assertThat(queue.status(11).state()).isEqualTo("none");
        assertThat(queue.status(10).state()).isEqualTo("matched");
        assertThat(queue.status(12).state()).isEqualTo("matched");

        // Caught half matched, 20 and 21 given theirs and 22 not yet: 22 leaving takes 22 alone.
        queuedParty(20, 1_200, T0);
        store.sync().zrem("mmq:tvt", "20");
        for (long p = 20; p <= 21; p++) {
            queue.matched(p, MatchMode.TVT, new MatchQueue.Grant("h", 1, "t" + p, false, "01JC0000000000000000000000"), 60, System.currentTimeMillis());
        }
        assertThat(queue.leave(22)).containsExactly(22L);
        assertThat(queue.status(20).state()).isEqualTo("matched");
        assertThat(queue.status(21).state()).isEqualTo("matched");
    }

    @Test
    @DisplayName("a party whose members are not those read is not queued")
    void aChangedPartyIsNotQueued() {
        store.sync().hset(Parties.partyKey("p-1"), Map.of("leader", "10", "members", "10,11"));
        assertThat(queue.join(List.of(m(10, 1_200), m(11, 1_200), m(12, 1_200)), MatchMode.TVT, "p-1", T0))
                .isEqualTo(MatchQueue.JoinResult.PARTY_CHANGED);
        assertThat(store.sync().zcard("mmq:tvt")).isZero();
        assertThat(store.sync().exists("mmp:10")).isZero();
        assertThat(queue.join(List.of(m(10, 1_200), m(11, 1_200)), MatchMode.TVT, "p-1", T0))
                .isEqualTo(MatchQueue.JoinResult.QUEUED);
    }
}
