package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.backend.persistence.TeamRepository.Outcome;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Teams' first slice (docs 04 §2, D-39), against a real MySQL: capacity 3 here, 30 in production. */
class TeamRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final Instant T = Instant.parse("2026-10-01T12:00:00Z");

    private static Database db;
    private static TeamRepository teams;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 8);
        teams = new TeamRepository(db.dataSource(), 3);
    }

    @AfterAll
    static void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void freshSchema() {
        db.resetForTests();
    }

    private static long player(String name) throws SQLException {
        try (Connection c = db.dataSource().getConnection()) {
            long id;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO account (username, password_hash) VALUES (?, ?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setBytes(2, new byte[] {1, 2, 3});
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO player (id, public_code, display_name) VALUES (?,?,?)")) {
                ps.setLong(1, id);
                ps.setString(2, code(id));
                ps.setString(3, name);
                ps.executeUpdate();
            }
            return id;
        }
    }

    private static String code(long id) {
        return String.format("P%011d", id);
    }

    private static Long teamIdOf(long playerId) throws SQLException {
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT team_id FROM player WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    /** A team led by {@code leader}, with {@code members} invited and in. */
    private static long team(String name, long leader, long... members) throws SQLException {
        long id = teams.create(leader, name, T).teamId();
        for (long m : members) {
            assertThat(teams.invite(leader, m, T)).isEqualTo(Outcome.OK);
            assertThat(teams.answer(m, id, true, T)).isEqualTo(Outcome.OK);
        }
        return id;
    }

    private static int roleOf(long playerId) throws SQLException {
        return teams.teamOf(playerId).members().stream().filter(m -> m.playerId() == playerId)
                .findFirst().orElseThrow().role();
    }

    // ---- applications (plan item 64, Q-49) -------------------------------------------------

    private static List<Long> applicants(long byPlayer) throws SQLException {
        List<TeamRepository.Application> listed = teams.applicationsTo(byPlayer, T);
        return listed == null ? null : listed.stream().map(TeamRepository.Application::playerId).toList();
    }

    @Test
    @DisplayName("a player applies; the leader and vice leaders are told and see it; accepted, they join and their other applications go (Q-49)")
    void applying() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        long cyd = player("cyd");
        long dee = player("dee");
        long tanks = team("Tanks", ada, bob);
        assertThat(teams.setRole(ada, bob, TeamRepository.VICE)).isEqualTo(Outcome.OK);
        long rams = teams.create(dee, "Rams", T).teamId();

        TeamRepository.Applied applied = teams.apply(cyd, tanks, T);
        assertThat(applied.outcome()).isEqualTo(Outcome.OK);
        assertThat(applied.told()).as("the leader and the vice leader").containsExactlyInAnyOrder(ada, bob);
        assertThat(new InboxRepository(db.dataSource()).itemsOf(ada)).extracting(InboxRepository.Item::kind,
                InboxRepository.Item::ref).containsExactly(org.assertj.core.groups.Tuple.tuple(InboxRepository.TEAM_APPLICATION, cyd));
        assertThat(teams.apply(cyd, tanks, T).outcome()).isEqualTo(Outcome.ALREADY);
        assertThat(teams.apply(cyd, rams, T).outcome()).isEqualTo(Outcome.OK);
        assertThat(teams.apply(cyd, 999_999, T).outcome()).isEqualTo(Outcome.NO_SUCH_TEAM);
        assertThat(teams.applicationsOf(cyd, T)).extracting(TeamRepository.Applying::teamName).containsExactlyInAnyOrder("Tanks", "Rams");
        assertThat(applicants(bob)).as("a vice leader sees them").containsExactly(cyd);
        assertThat(applicants(cyd)).as("one in no team").isNull();
        long gus = player("gus");
        assertThat(teams.apply(gus, tanks, T).outcome()).isEqualTo(Outcome.OK);

        assertThat(teams.answerApplication(bob, cyd, true, T)).isEqualTo(Outcome.OK);
        assertThat(teams.answerApplication(ada, gus, true, T)).as("full since gus applied").isEqualTo(Outcome.TEAM_FULL);
        assertThat(teams.teamOf(cyd).id()).isEqualTo(tanks);
        assertThat(teams.applicationsOf(cyd, T)).as("their other application gone, in a team now").isEmpty();
        assertThat(applicants(dee)).isEmpty();
        assertThat(teams.answerApplication(ada, cyd, true, T)).as("spent").isEqualTo(Outcome.NO_APPLICATION);
        assertThat(teams.apply(player("eve"), tanks, T).outcome()).as("three of three").isEqualTo(Outcome.TEAM_FULL);
        long fay = player("fay");
        assertThat(teams.apply(fay, rams, T).outcome()).isEqualTo(Outcome.OK);
        assertThat(teams.answerApplication(ada, fay, true, T)).as("another team's").isEqualTo(Outcome.NO_APPLICATION);
        assertThat(teams.answerApplication(cyd, fay, true, T)).as("a member").isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(teams.leave(cyd, T)).isEqualTo(Outcome.OK);
        assertThat(teams.apply(cyd, rams, T).outcome()).as("left a day ago at most").isEqualTo(Outcome.COOLING_DOWN);
        assertThat(teams.apply(ada, rams, T).outcome()).isEqualTo(Outcome.IN_TEAM);
    }

    @Test
    @DisplayName("declined holds until it lapses; five out at most; one the leader blocked answered as sent and never seen; withdrawn; lapsed ones gone (Q-49)")
    void applicationsLimits() throws Exception {
        long[] leaders = new long[6];
        long[] teamIds = new long[6];
        for (int i = 0; i < 6; i++) {
            leaders[i] = player("lead" + i);
            teamIds[i] = teams.create(leaders[i], "Team" + i, T).teamId();
        }
        long ada = player("ada");
        assertThat(teams.apply(ada, teamIds[0], T).outcome()).isEqualTo(Outcome.OK);
        assertThat(teams.answerApplication(leaders[0], ada, false, T)).isEqualTo(Outcome.OK);
        assertThat(applicants(leaders[0])).as("declined: not listed").isEmpty();
        assertThat(teams.withdrawApplication(ada, teamIds[0])).as("nor shed by withdrawing it").isFalse();
        for (int i = 1; i < 6; i++) {
            assertThat(teams.apply(ada, teamIds[i], T).outcome()).as("a declined one is not out").isEqualTo(Outcome.OK);
        }
        assertThat(teams.apply(ada, teamIds[0], T.plusSeconds(60)).outcome()).as("declined, until it lapses")
                .isEqualTo(Outcome.ALREADY);
        assertThat(teams.apply(ada, teamIds[0], T.plus(Duration.ofDays(7))).outcome()).as("lapsed: again")
                .isEqualTo(Outcome.OK);
        for (int i = 1; i < 5; i++) {
            assertThat(teams.apply(ada, teamIds[i], T.plus(Duration.ofDays(7))).outcome()).isEqualTo(Outcome.OK);
        }
        assertThat(teams.apply(ada, teamIds[5], T.plus(Duration.ofDays(7))).outcome()).as("five out")
                .isEqualTo(Outcome.TOO_MANY_APPLIED);
        assertThat(teams.withdrawApplication(ada, teamIds[1])).isTrue();
        assertThat(teams.withdrawApplication(ada, teamIds[1])).isFalse();
        assertThat(teams.apply(ada, teamIds[5], T.plus(Duration.ofDays(7))).outcome()).as("one withdrawn").isEqualTo(Outcome.OK);

        long bob = player("bob");
        new FriendRepository(db.dataSource(), 100, 100).block(leaders[2], bob, T);
        TeamRepository.Applied ignored = teams.apply(bob, teamIds[2], T);
        assertThat(ignored.outcome()).as("answered as sent").isEqualTo(Outcome.IGNORED);
        assertThat(ignored.told()).isEmpty();
        assertThat(applicants(leaders[2])).doesNotContain(bob);

        assertThat(teams.disband(leaders[3], T)).as("its applications go with it").isEqualTo(Outcome.OK);
        assertThat(teams.applicationsOf(ada, T.plus(Duration.ofDays(7)))).hasSize(4);
        assertThat(teams.purgeLapsedApplications(T.plus(Duration.ofDays(14)), 1)).as("each lapsed, one at a time").isEqualTo(4);
        assertThat(teams.applicationsOf(ada, T)).isEmpty();
    }

    @Test
    @DisplayName("a team is found by its name's start, case and accents ignored, a wildcard taken as written, twenty at most (Q-49)")
    void findingTeams() throws Exception {
        long tanks = teams.create(player("p1"), "Tanks", T).teamId();
        teams.create(player("p2"), "Tankers", T);
        teams.create(player("p3"), "Ränks", T);
        teams.create(player("p4"), "a_b", T);
        teams.create(player("p5"), "axb", T);
        assertThat(teams.find("tan")).extracting(TeamRepository.Found::name).containsExactly("Tankers", "Tanks");
        assertThat(teams.find("RAN")).extracting(TeamRepository.Found::name).containsExactly("Ränks");
        assertThat(teams.find("a_")).as("an underscore is itself").extracting(TeamRepository.Found::name).containsExactly("a_b");
        assertThat(teams.find("%")).as("a percent sign too").isEmpty();
        TeamRepository.Found one = teams.find(tanks);
        assertThat(List.of(one.id(), one.name(), one.members(), one.rating())).containsExactly(tanks, "Tanks", 1, 1_200);
        assertThat(teams.find(999_999L)).isNull();
        for (int i = 0; i < 21; i++) {
            teams.create(player("z" + i), String.format("Zz%02d", i), T);
        }
        assertThat(teams.find("zz")).hasSize(TeamRepository.FOUND);
    }

    @Test
    @DisplayName("the leader renames the team, once in 30 days; a vice leader or member may not, nor take a name in use (04 §1, plan item 63)")
    void renamingATeam() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        long cyd = player("cyd");
        team("Tanks", ada, bob, cyd);
        teams.create(player("dee"), "Rams", T);
        assertThat(TeamRepository.RENAME_EVERY).isEqualTo(java.time.Duration.ofDays(30));
        assertThat(teams.rename(cyd, "Cyds", T)).as("a member").isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(teams.setRole(ada, bob, TeamRepository.VICE)).isEqualTo(Outcome.OK);
        assertThat(teams.rename(bob, "Bobs", T)).as("a vice leader").isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(teams.rename(ada, "rams", T)).as("taken, however cased").isEqualTo(Outcome.NAME_TAKEN);
        assertThat(teams.rename(ada, "Treads", T)).isEqualTo(Outcome.OK);
        assertThat(teams.teamOf(cyd).name()).isEqualTo("Treads");
        assertThat(teams.rename(ada, "Turrets", T.plus(TeamRepository.RENAME_EVERY).minusMillis(1))).isEqualTo(Outcome.RENAMED_RECENTLY);
        assertThat(teams.rename(ada, "Turrets", T.plus(TeamRepository.RENAME_EVERY))).isEqualTo(Outcome.OK);
        assertThat(teams.rename(player("eve"), "Mine", T)).isEqualTo(Outcome.NO_TEAM);
        assertThat(teams.create(player("fay"), "Tanks", T).outcome()).as("its first name free again").isEqualTo(Outcome.OK);
    }

    @Test
    @DisplayName("a team is created by one in none, who leads it; a name is taken however it is cased or accented")
    void creating() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        TeamRepository.Created made = teams.create(ada, "Tanks", T);
        assertThat(made.outcome()).isEqualTo(Outcome.OK);
        TeamRepository.Team t = teams.teamOf(ada);
        assertThat(t.name()).isEqualTo("Tanks");
        assertThat(t.members()).containsExactly(new TeamRepository.Member(ada, "ada", TeamRepository.LEADER));
        assertThat(teamIdOf(ada)).isEqualTo(made.teamId());
        assertThat(teams.create(ada, "Other", T).outcome()).isEqualTo(Outcome.IN_TEAM);
        assertThat(teams.create(bob, "tanks", T).outcome()).isEqualTo(Outcome.NAME_TAKEN);
        assertThat(teams.create(bob, "Tänks", T).outcome()).isEqualTo(Outcome.NAME_TAKEN);
        assertThat(teams.teamOf(bob)).isNull();
    }

    @Test
    @DisplayName("an invitation by a leader or vice leader is accepted once, within seven days, into a team not full")
    void invitingAndAccepting() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        long cy = player("cy");
        long di = player("di");
        long team = team("Tanks", ada, bob);
        assertThat(teams.invite(bob, cy, T)).as("a member").isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(teams.invite(ada, 999_999_999L, T)).isEqualTo(Outcome.NO_SUCH_PLAYER);
        assertThat(teams.invite(ada, bob, T)).as("already in one").isEqualTo(Outcome.IN_TEAM);
        assertThat(teams.invite(cy, di, T)).as("in no team").isEqualTo(Outcome.NO_TEAM);

        assertThat(teams.invite(ada, cy, T)).isEqualTo(Outcome.OK);
        assertThat(teams.invitesOf(cy, T)).extracting(TeamRepository.Invite::teamName).containsExactly("Tanks");
        assertThat(teams.invitesOf(cy, T.plus(Duration.ofDays(7)))).as("expired").isEmpty();
        assertThat(teams.answer(cy, team, true, T.plus(Duration.ofDays(7)))).isEqualTo(Outcome.NO_INVITE);
        assertThat(teams.answer(cy, team, true, T)).isEqualTo(Outcome.OK);
        assertThat(teams.answer(cy, team, true, T)).as("spent").isEqualTo(Outcome.NO_INVITE);
        assertThat(teams.teamOf(cy).members()).hasSize(3);

        assertThat(teams.invite(ada, di, T)).isEqualTo(Outcome.OK);
        assertThat(teams.answer(di, team, true, T)).as("three is full here").isEqualTo(Outcome.TEAM_FULL);
        assertThat(teams.teamOf(di)).isNull();
        assertThat(teams.leave(cy, T)).isEqualTo(Outcome.OK);
        assertThat(teams.answer(di, team, true, T)).as("a place freed by one leaving").isEqualTo(Outcome.OK);

        long ed = player("ed");
        teams.invite(ada, ed, T);
        assertThat(teams.answer(ed, team, false, T)).as("declined").isEqualTo(Outcome.OK);
        assertThat(teams.invitesOf(ed, T)).isEmpty();
        assertThat(teams.teamOf(ed)).isNull();
    }

    @Test
    @DisplayName("roles: two vice leaders at most; a vice leader kicks members only; the kicked wait a day to join again")
    void rolesAndKicks() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        long cy = player("cy");
        long di = player("di");
        TeamRepository big = new TeamRepository(db.dataSource(), 30);
        long team = big.create(ada, "Tanks", T).teamId();
        for (long m : List.of(bob, cy, di)) {
            big.invite(ada, m, T);
            big.answer(m, team, true, T);
        }
        assertThat(big.setRole(bob, cy, TeamRepository.VICE)).as("not the leader").isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(big.setRole(ada, bob, TeamRepository.VICE)).isEqualTo(Outcome.OK);
        assertThat(big.setRole(ada, cy, TeamRepository.VICE)).isEqualTo(Outcome.OK);
        assertThat(big.setRole(ada, di, TeamRepository.VICE)).isEqualTo(Outcome.TOO_MANY_VICES);
        assertThat(big.kick(bob, cy, T)).as("a vice leader kicks members only").isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(big.kick(di, bob, T)).isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(big.setRole(ada, cy, TeamRepository.MEMBER)).isEqualTo(Outcome.OK);
        assertThat(big.kick(bob, cy, T)).isEqualTo(Outcome.OK);
        assertThat(big.teamOf(cy)).isNull();
        assertThat(teamIdOf(cy)).isNull();
        assertThat(big.kick(ada, cy, T)).isEqualTo(Outcome.NOT_A_MEMBER);

        big.invite(ada, cy, T);
        assertThat(big.answer(cy, team, true, T.plus(Duration.ofHours(23)))).isEqualTo(Outcome.COOLING_DOWN);
        assertThat(big.create(cy, "Mine", T.plus(Duration.ofHours(23)))).extracting(TeamRepository.Created::outcome)
                .isEqualTo(Outcome.COOLING_DOWN);
        assertThat(big.answer(cy, team, true, T.plus(Duration.ofHours(24)))).isEqualTo(Outcome.OK);
    }

    @Test
    @DisplayName("a leader with members cannot leave, but hands over, or disbands: everyone out, the name free again")
    void leavingHandingOverDisbanding() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        long cy = player("cy");
        long team = team("Tanks", ada, bob);
        teams.invite(ada, cy, T);
        assertThat(teams.leave(ada, T)).isEqualTo(Outcome.LEADER_WITH_MEMBERS);
        assertThat(teams.transfer(bob, ada)).isEqualTo(Outcome.NOT_ALLOWED);
        assertThat(teams.transfer(ada, bob)).isEqualTo(Outcome.OK);
        assertThat(roleOf(bob)).isEqualTo(TeamRepository.LEADER);
        assertThat(roleOf(ada)).isEqualTo(TeamRepository.MEMBER);
        assertThat(teams.leave(ada, T)).isEqualTo(Outcome.OK);
        assertThat(teams.teamOf(bob).members()).hasSize(1);

        assertThat(teams.disband(ada, T)).as("in no team now").isEqualTo(Outcome.NO_TEAM);
        assertThat(teams.disband(bob, T)).isEqualTo(Outcome.OK);
        assertThat(teams.teamOf(bob)).isNull();
        assertThat(teamIdOf(bob)).isNull();
        assertThat(teams.invitesOf(cy, T)).as("its invitations gone with it").isEmpty();
        long cyTeam = teams.create(cy, "Tanks", T).teamId();
        assertThat(cyTeam).as("the name free again").isPositive().isNotEqualTo(team);

        assertThat(teams.leave(cy, T)).as("a leader alone: leaving ends the team").isEqualTo(Outcome.OK);
        assertThat(teams.teamOf(cy)).isNull();
    }

    @Test
    @Timeout(60)
    @DisplayName("disbanding locks its members' rows, not every player's: a player elsewhere being paid is not waited for (defect D-32)")
    void disbandingLocksItsMembersOnly() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        long eve = player("eve");                       // in no team; her row held as a result's transaction holds it
        team("Tanks", ada, bob);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection other = db.dataSource().getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement("SELECT id FROM player WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, eve);
                ps.executeQuery().close();
            }
            try {
                Future<Outcome> disbanded = pool.submit(() -> teams.disband(ada, T));
                assertThat(disbanded.get(5, TimeUnit.SECONDS)).isEqualTo(Outcome.OK);
            } finally {
                other.rollback();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(teamIdOf(ada)).isNull();
        assertThat(teamIdOf(bob)).isNull();
    }

    @Test
    @DisplayName("two accepting the last place at once: one is in, the other told the team is full")
    void lastPlace() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        long cy = player("cy");
        long di = player("di");
        long team = team("Tanks", ada, bob);
        teams.invite(ada, cy, T);
        teams.invite(ada, di, T);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> c = pool.submit(() -> {
                go.await();
                return teams.answer(cy, team, true, T);
            });
            Future<Outcome> d = pool.submit(() -> {
                go.await();
                return teams.answer(di, team, true, T);
            });
            go.countDown();
            assertThat(List.of(c.get(), d.get())).containsExactlyInAnyOrder(Outcome.OK, Outcome.TEAM_FULL);
            assertThat(teams.teamOf(ada).members()).hasSize(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a party's side in a team match: every player in one team, at its rating, with the first's role (Q-18)")
    void sides() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        long cyd = player("cyd");
        long di = player("dii");
        long ed = player("edd");
        long tanks = team("Tanks", ada, bob, cyd);
        team("Other", di);
        try (Connection c = db.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE team SET rating = 1350, wins = 4, losses = 2, draws = 1 WHERE id = ?")) {
            ps.setLong(1, tanks);
            ps.executeUpdate();
        }
        assertThat(teams.teamOf(bob)).extracting(TeamRepository.Team::rating, TeamRepository.Team::wins,
                TeamRepository.Team::losses, TeamRepository.Team::draws).as("its record, as a team").containsExactly(1_350, 4, 2, 1);

        assertThat(teams.sideOf(List.of(ada, bob, cyd))).isEqualTo(new TeamRepository.Side(tanks, 1_350, TeamRepository.LEADER));
        assertThat(teams.sideOf(List.of(bob, ada, cyd)).role()).as("the first's role").isEqualTo(TeamRepository.MEMBER);
        assertThat(teams.setRole(ada, bob, TeamRepository.VICE)).isEqualTo(Outcome.OK);
        assertThat(teams.sideOf(List.of(bob, ada, cyd)).role()).isEqualTo(TeamRepository.VICE);
        assertThat(teams.sideOf(List.of(ada, bob, di))).as("one of another team").isNull();
        assertThat(teams.sideOf(List.of(ada, bob, ed))).as("one in no team").isNull();
    }

    @Test
    @DisplayName("an invitation from a player the invitee has blocked is answered as sent, and not kept (Q-20)")
    void blockedInvitations() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        team("Tanks", ada);
        assertThat(new FriendRepository(db.dataSource(), 100, 100).block(bob, ada, T))
                .isEqualTo(FriendRepository.Blocked.BLOCKED);
        assertThat(teams.invite(ada, bob, T)).as("answered as sent: the service says OK").isEqualTo(Outcome.IGNORED);
        assertThat(teams.invitesOf(bob, T)).as("not delivered").isEmpty();
        assertThat(new InboxRepository(db.dataSource()).itemsOf(bob)).as("nor in the inbox").isEmpty();
        long cyd = player("cyd");
        long tanks = teams.teamOf(ada).id();
        assertThat(teams.invite(ada, cyd, T)).isEqualTo(Outcome.OK);
        assertThat(new InboxRepository(db.dataSource()).itemsOf(cyd)).extracting(InboxRepository.Item::kind,
                InboxRepository.Item::ref).containsExactly(org.assertj.core.groups.Tuple.tuple(InboxRepository.TEAM_INVITE, tanks));
    }

    @Test
    @DisplayName("a team has so many invitations out at once: answered or lapsed ones free a place, one not kept takes none (S-15, Q-46)")
    void invitationsOutAreCapped() throws Exception {
        TeamRepository two = new TeamRepository(db.dataSource(), 30, 2);
        long ada = player("ada");
        long bob = player("bob");
        long cy = player("cy");
        long di = player("di");
        long ed = player("ed");
        long team = two.create(ada, "Tanks", T).teamId();
        assertThat(two.invite(ada, bob, T)).isEqualTo(Outcome.OK);
        assertThat(two.invite(ada, cy, T)).isEqualTo(Outcome.OK);
        assertThat(two.invite(ada, di, T)).isEqualTo(Outcome.TOO_MANY_INVITED);
        assertThat(two.invite(ada, cy, T)).as("again to one invited already: renewed, not another").isEqualTo(Outcome.OK);
        assertThat(two.answer(cy, team, false, T)).isEqualTo(Outcome.OK);
        assertThat(two.invite(ada, di, T)).as("a declined one frees its place").isEqualTo(Outcome.OK);
        assertThat(two.invite(ada, ed, T.plus(TeamRepository.INVITE_LIFE))).as("lapsed ones free theirs")
                .isEqualTo(Outcome.OK);
    }

    @Test
    @DisplayName("a player's invitations are listed newest first, the fifty newest (S-15, Q-46)")
    void theNewestInvitationsAreListed() throws Exception {
        long target = player("target");
        for (int i = 0; i <= TeamRepository.LISTED; i++) {
            long leader = player("lead" + i);
            teams.create(leader, "Team" + i, T);
            teams.invite(leader, target, T.plusSeconds(i));
        }
        List<TeamRepository.Invite> listed = teams.invitesOf(target, T.plusSeconds(1_000));
        assertThat(listed).hasSize(TeamRepository.LISTED);
        assertThat(listed.get(0).teamName()).as("newest first").isEqualTo("Team" + TeamRepository.LISTED);
        assertThat(listed).extracting(TeamRepository.Invite::teamName).doesNotContain("Team0");
    }

    @Test
    @DisplayName("lapsed invitations are deleted by retention, a batch at a time; live ones stay (D-40)")
    void lapsedInvitationsArePurged() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        long cy = player("cy");
        long di = player("di");
        teams.create(ada, "Tanks", T);
        teams.invite(ada, bob, T);
        teams.invite(ada, cy, T.plusSeconds(1));
        teams.invite(ada, di, T.plus(Duration.ofDays(1)));
        Instant lapsed = T.plus(TeamRepository.INVITE_LIFE).plusSeconds(2);
        assertThat(teams.purgeLapsedInvites(lapsed, 1)).as("two lapsed, one at a time").isEqualTo(2);
        assertThat(teams.invitesOf(bob, T)).as("as read before it lapsed: gone").isEmpty();
        assertThat(teams.invitesOf(di, T)).hasSize(1);
        assertThat(teams.purgeLapsedInvites(lapsed, 1)).isZero();
    }
}
