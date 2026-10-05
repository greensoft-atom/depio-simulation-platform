package com.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.backend.persistence.FriendRepository.Asked;
import com.backend.persistence.FriendRepository.Blocked;
import com.backend.persistence.FriendRepository.Person;
import com.backend.persistence.FriendRepository.Request;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Friends and blocks, the social layer's first slice (04 §9, Q-20, D-45), against a real MySQL. */
class FriendRepositoryTest {

    private static final String URL = System.getenv().getOrDefault("JDBC_URL",
            "jdbc:mysql://127.0.0.1:3306/backend_test?useSSL=false&allowPublicKeyRetrieval=true");
    private static final String USER = System.getenv().getOrDefault("DB_USER", "backend");
    private static final String PASSWORD = System.getenv().getOrDefault("DB_PASSWORD",
            "backend-dev-password");
    private static final Instant T = Instant.parse("2026-10-01T12:00:00Z");

    private static Database db;
    private static AccountRepository accounts;
    /** Caps of two friends and two blocks, so reaching them takes three players, not a hundred. */
    private static FriendRepository friends;

    @BeforeAll
    static void setUp() {
        db = new Database(URL, USER, PASSWORD, 4);
        accounts = new AccountRepository(db.dataSource());
        friends = new FriendRepository(db.dataSource(), 2, 2);
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @BeforeEach
    void fresh() {
        db.resetForTests();
    }

    private static long player(String name) throws SQLException {
        return accounts.register(name, name, "$argon2id$fake".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a request, then its acceptance by asking back: friends both ways, the requests gone")
    void askAndAccept() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        assertThat(friends.ask(ada, ada, T)).isEqualTo(Asked.YOURSELF);
        assertThat(friends.ask(ada, 999_999_999L, T)).isEqualTo(Asked.NO_SUCH_PLAYER);

        assertThat(friends.ask(ada, bob, T)).isEqualTo(Asked.ASKED);
        assertThat(friends.ask(ada, bob, T)).isEqualTo(Asked.ALREADY_ASKED);
        assertThat(friends.requestsTo(bob, T)).extracting(Request::playerId, Request::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ada, "ada"));
        assertThat(friends.requestsTo(bob, T).get(0).expiresAt()).isEqualTo(T.plus(Duration.ofDays(7)));
        assertThat(friends.askedBy(ada, T)).extracting(Request::playerId).containsExactly(bob);
        InboxRepository inbox = new InboxRepository(db.dataSource());
        assertThat(inbox.itemsOf(bob)).extracting(InboxRepository.Item::kind, InboxRepository.Item::ref)
                .as("in bob's inbox").containsExactly(org.assertj.core.groups.Tuple.tuple(InboxRepository.FRIEND_REQUEST, ada));

        assertThat(friends.ask(bob, ada, T.plusSeconds(60))).as("asking back accepts").isEqualTo(Asked.FRIENDS);
        assertThat(inbox.itemsOf(ada)).extracting(InboxRepository.Item::kind, InboxRepository.Item::ref)
                .as("ada told bob accepted").containsExactly(org.assertj.core.groups.Tuple.tuple(InboxRepository.FRIEND_ACCEPTED, bob));
        assertThat(friends.friendsOf(ada)).extracting(Person::playerId, Person::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(bob, "bob"));
        assertThat(friends.friendsOf(bob)).extracting(Person::playerId).containsExactly(ada);
        assertThat(friends.requestsTo(bob, T)).isEmpty();
        assertThat(friends.askedBy(ada, T)).isEmpty();
        assertThat(friends.ask(ada, bob, T)).isEqualTo(Asked.ALREADY_FRIENDS);

        assertThat(friends.remove(bob, ada)).isTrue();
        assertThat(friends.friendsOf(ada)).as("ended both ways").isEmpty();
        assertThat(friends.remove(bob, ada)).isFalse();
    }

    @Test
    @DisplayName("a request lapses after seven days, and may be asked again; declined or withdrawn, it is gone")
    void requestsLapseAndGo() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        friends.ask(ada, bob, T);
        Instant week = T.plus(Duration.ofDays(7));
        assertThat(friends.requestsTo(bob, week)).as("lapsed").isEmpty();
        assertThat(friends.ask(bob, ada, week)).as("a lapsed request accepts nothing").isEqualTo(Asked.ASKED);
        assertThat(friends.ask(ada, bob, week)).as("but a fresh one does").isEqualTo(Asked.FRIENDS);
        friends.remove(ada, bob);

        friends.ask(ada, bob, T);
        assertThat(friends.dropRequest(bob, ada)).as("declined").isTrue();
        assertThat(friends.requestsTo(bob, T)).isEmpty();
        friends.ask(ada, bob, T);
        assertThat(friends.dropRequest(ada, bob)).as("withdrawn").isTrue();
        assertThat(friends.askedBy(ada, T)).isEmpty();
        assertThat(friends.dropRequest(ada, bob)).isFalse();
    }

    @Test
    @DisplayName("friends are capped: a full player can neither ask nor be accepted")
    void friendsAreCapped() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        long cyd = player("cyd");
        long dee = player("dee");
        long eve = player("eve");
        friends.ask(ada, bob, T);
        friends.ask(bob, ada, T);
        assertThat(friends.ask(ada, eve, T)).as("asked while ada had room").isEqualTo(Asked.ASKED);
        friends.ask(ada, cyd, T);
        friends.ask(cyd, ada, T);
        assertThat(friends.ask(ada, dee, T)).as("ada at two").isEqualTo(Asked.FRIENDS_FULL);
        friends.ask(dee, ada, T);
        assertThat(friends.ask(ada, dee, T)).as("accepting would take ada past two").isEqualTo(Asked.FRIENDS_FULL);
        assertThat(friends.ask(eve, ada, T)).as("and so would being accepted").isEqualTo(Asked.FRIENDS_FULL);
        assertThat(friends.friendsOf(dee)).isEmpty();
        assertThat(friends.friendsOf(eve)).isEmpty();
    }

    @Test
    @DisplayName("blocking ends a friendship and every request either way; a blocked player's request is answered and never shown")
    void blocking() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        long cyd = player("cyd");
        long dee = player("dee");
        friends.ask(ada, bob, T);
        friends.ask(bob, ada, T);
        friends.ask(cyd, ada, T);
        assertThat(friends.block(ada, ada, T)).isEqualTo(Blocked.YOURSELF);
        assertThat(friends.block(ada, 999_999_999L, T)).isEqualTo(Blocked.NO_SUCH_PLAYER);

        assertThat(friends.block(ada, bob, T)).isEqualTo(Blocked.BLOCKED);
        assertThat(friends.block(ada, bob, T)).as("once is enough").isEqualTo(Blocked.BLOCKED);
        assertThat(friends.friendsOf(ada)).isEmpty();
        assertThat(friends.friendsOf(bob)).isEmpty();
        assertThat(friends.blocks(ada, bob)).isTrue();
        assertThat(friends.blocks(bob, ada)).as("one way").isFalse();
        assertThat(friends.ask(bob, ada, T)).as("ignored, which the API answers as asked").isEqualTo(Asked.IGNORED);
        assertThat(friends.requestsTo(ada, T)).extracting(Request::playerId).as("and not shown").containsExactly(cyd);
        assertThat(new InboxRepository(db.dataSource()).itemsOf(ada)).extracting(InboxRepository.Item::kind,
                InboxRepository.Item::ref).as("nor in ada's inbox")
                .doesNotContain(org.assertj.core.groups.Tuple.tuple(InboxRepository.FRIEND_REQUEST, bob));
        assertThat(friends.ask(ada, bob, T)).as("unblock first").isEqualTo(Asked.YOU_BLOCKED);

        assertThat(friends.block(ada, cyd, T)).isEqualTo(Blocked.BLOCKED);
        assertThat(friends.requestsTo(ada, T)).as("cyd's request dropped").isEmpty();
        assertThat(friends.block(ada, dee, T)).as("two at most").isEqualTo(Blocked.BLOCKS_FULL);
        assertThat(friends.blockedBy(ada)).extracting(Person::playerId).containsExactlyInAnyOrder(bob, cyd);

        assertThat(friends.unblock(ada, bob)).isTrue();
        assertThat(friends.unblock(ada, bob)).isFalse();
        assertThat(friends.blocks(ada, bob)).isFalse();
        assertThat(friends.ask(bob, ada, T)).isEqualTo(Asked.ASKED);
    }

    @Test
    @DisplayName("a blocked player's request is kept as theirs and hidden from the one who blocked them; unblocking drops it (S-14, D-56)")
    void aBlockedRequestIsKeptAndHidden() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        friends.block(bob, ada, T);
        assertThat(friends.ask(ada, bob, T)).as("answered as asked, and nobody told").isEqualTo(Asked.IGNORED);
        assertThat(friends.askedBy(ada, T)).extracting(Request::playerId).as("ada sees it").containsExactly(bob);
        assertThat(friends.requestsTo(bob, T)).as("bob never does").isEmpty();
        assertThat(new InboxRepository(db.dataSource()).itemsOf(bob)).isEmpty();
        assertThat(friends.ask(ada, bob, T)).as("as with any request").isEqualTo(Asked.ALREADY_ASKED);
        assertThat(friends.dropRequest(ada, bob)).as("withdrawn as any").isTrue();
        assertThat(friends.ask(ada, bob, T)).isEqualTo(Asked.IGNORED);
        assertThat(friends.unblock(bob, ada)).isTrue();
        assertThat(friends.requestsTo(bob, T)).as("unblocking delivers nothing late").isEmpty();
        assertThat(friends.askedBy(ada, T)).isEmpty();
    }

    @Test
    @DisplayName("a player has so many requests out at once: withdrawn, accepted or lapsed ones free a place, a hidden one takes one, and asking back is accepting (S-15, Q-46)")
    void requestsOutAreCapped() throws Exception {
        FriendRepository two = new FriendRepository(db.dataSource(), 100, 100, 2);
        long ada = player("ada");
        long bob = player("bob");
        long cyd = player("cyd");
        long dee = player("dee");
        long eve = player("eve");
        long fay = player("fay");
        assertThat(two.ask(ada, bob, T)).isEqualTo(Asked.ASKED);
        assertThat(two.ask(ada, cyd, T)).isEqualTo(Asked.ASKED);
        assertThat(two.ask(ada, dee, T)).isEqualTo(Asked.TOO_MANY_ASKED);
        assertThat(two.askedBy(ada, T)).hasSize(2);
        assertThat(two.ask(bob, ada, T)).as("bob asked back").isEqualTo(Asked.FRIENDS);
        assertThat(two.ask(ada, dee, T)).as("an accepted one frees its place").isEqualTo(Asked.ASKED);
        assertThat(two.dropRequest(ada, dee)).isTrue();
        two.block(eve, ada, T);
        assertThat(two.ask(ada, eve, T)).as("a withdrawn one frees its place").isEqualTo(Asked.IGNORED);
        assertThat(two.ask(ada, dee, T)).as("and a hidden one takes one").isEqualTo(Asked.TOO_MANY_ASKED);
        assertThat(two.ask(fay, ada, T)).isEqualTo(Asked.ASKED);
        assertThat(two.ask(ada, fay, T)).as("asking back is accepting, not asking").isEqualTo(Asked.FRIENDS);
        assertThat(two.ask(ada, dee, T.plus(Duration.ofDays(7)))).as("lapsed ones free theirs").isEqualTo(Asked.ASKED);
    }

    @Test
    @DisplayName("requests are listed newest first, and to a player the hundred newest (S-15, Q-46)")
    void theNewestRequestsAreListed() throws Exception {
        long ada = player("ada");
        for (int i = 0; i <= FriendRepository.LISTED; i++) {
            friends.ask(player("asker" + i), ada, T.plusSeconds(i));
        }
        List<Request> listed = friends.requestsTo(ada, T.plusSeconds(1_000));
        assertThat(listed).hasSize(FriendRepository.LISTED);
        assertThat(listed.get(0).name()).as("newest first").isEqualTo("asker" + FriendRepository.LISTED);
        assertThat(listed).extracting(Request::name).as("the oldest left out").doesNotContain("asker0");

        long bob = player("bob");
        long cyd = player("cyd");
        friends.ask(ada, bob, T);
        friends.ask(ada, cyd, T.plusSeconds(1));
        assertThat(friends.askedBy(ada, T.plusSeconds(2))).extracting(Request::playerId).containsExactly(cyd, bob);
    }

    @Test
    @DisplayName("lapsed requests are deleted by retention, a batch at a time; live ones stay (D-40)")
    void lapsedRequestsArePurged() throws Exception {
        long ada = player("ada");
        long bob = player("bob");
        long cyd = player("cyd");
        long dee = player("dee");
        friends.ask(ada, bob, T);
        friends.ask(ada, cyd, T.plusSeconds(1));
        friends.ask(ada, dee, T.plus(Duration.ofDays(1)));
        Instant lapsed = T.plus(FriendRepository.REQUEST_LIFE).plusSeconds(2);
        assertThat(friends.purgeLapsed(lapsed, 1)).as("two lapsed, one at a time").isEqualTo(2);
        assertThat(friends.askedBy(ada, T)).as("as read before they lapsed: gone").extracting(Request::playerId)
                .containsExactly(dee);
        assertThat(friends.purgeLapsed(lapsed, 1)).isZero();
    }
}
