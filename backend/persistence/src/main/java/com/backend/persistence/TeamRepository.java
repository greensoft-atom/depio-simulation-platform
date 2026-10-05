package com.backend.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

/**
 * Teams' first slice (docs detailed-design/04-platform-services.md §2, 06 §3, D-39): created,
 * joined by invitation, left, kicked from, ranked, handed over and disbanded. Every action is one
 * transaction that locks the team row first and then the players it changes, one order, so the
 * team's capacity and its vice leaders' limit hold under concurrent requests.
 */
public final class TeamRepository {

    public static final int MEMBER = 0;
    public static final int VICE = 1;
    public static final int LEADER = 2;
    public static final int MAX_VICES = 2;
    static final Duration COOLDOWN = Duration.ofHours(24);
    static final Duration INVITE_LIFE = Duration.ofDays(7);
    private static final int DUPLICATE_ENTRY = 1062;

    /** {@code IGNORED}: an invitation to a player who has blocked the inviter, answered as sent (Q-20). */
    public enum Outcome {
        OK, NAME_TAKEN, IN_TEAM, COOLING_DOWN, NO_TEAM, NOT_ALLOWED, NO_SUCH_PLAYER, NO_INVITE,
        TEAM_FULL, LEADER_WITH_MEMBERS, NOT_A_MEMBER, TOO_MANY_VICES, IGNORED, TOO_MANY_INVITED, RENAMED_RECENTLY, NO_SUCH_TEAM, ALREADY, TOO_MANY_APPLIED,
        NO_APPLICATION
    }

    /** An application made (Q-49): {@code told}, the leader and vice leaders given it in their inbox. */
    public record Applied(Outcome outcome, List<Long> told) { }

    /** An application as the team's leader and vice leaders see it. */
    public record Application(long playerId, String name, Instant expiresAt) { }

    /** An application as the applicant sees it. */
    public record Applying(long teamId, String teamName, Instant expiresAt) { }

    /** A team as anyone may see it, found by its name or its id. */
    public record Found(long id, String name, int members, int rating) { }

    static final Duration APPLICATION_LIFE = Duration.ofDays(7);
    /** Applications one player may have out at once, unanswered (Q-49). */
    public static final int MAX_APPLIED = 5;
    /** Teams a search finds at most (Q-49). */
    public static final int FOUND = 20;

    /**
     * Asks a team to take the player (Q-49): one in no team and past the cooldown, one application a
     * team, a declined one counting until it lapses, {@link #MAX_APPLIED} out at once. The team's
     * leader and vice leaders find it in their inbox; to a leader who blocked the player it is
     * {@code IGNORED}, answered as sent and never shown (Q-20).
     */
    public Applied apply(long playerId, long teamId, Instant now) throws SQLException {
        return tx.execute(c -> {
            if (!lockTeam(c, teamId)) {
                return new Applied(Outcome.NO_SUCH_TEAM, List.of());
            }
            Outcome free = freeToJoin(c, playerId, now);
            if (free != Outcome.OK) {
                return new Applied(free, List.of());
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM team_application WHERE team_id = ? AND player_id = ? AND expires_at > ?")) {
                ps.setLong(1, teamId);
                ps.setLong(2, playerId);
                ps.setTimestamp(3, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Applied(Outcome.ALREADY, List.of());
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM team_application"
                    + " WHERE player_id = ? AND expires_at > ? AND NOT declined")) {
                ps.setLong(1, playerId);
                ps.setTimestamp(2, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= MAX_APPLIED) {
                        return new Applied(Outcome.TOO_MANY_APPLIED, List.of());
                    }
                }
            }
            if (memberCount(c, teamId) >= capacity) {
                return new Applied(Outcome.TEAM_FULL, List.of());
            }
            List<Long> answerers = new ArrayList<>();
            long leader = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT player_id, role FROM team_member"
                    + " WHERE team_id = ? AND role <> ? ORDER BY player_id")) {
                ps.setLong(1, teamId);
                ps.setInt(2, MEMBER);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        answerers.add(rs.getLong(1));
                        if (rs.getInt(2) == LEADER) {
                            leader = rs.getLong(1);
                        }
                    }
                }
            }
            if (FriendRepository.blocks(c, leader, playerId)) {
                return new Applied(Outcome.IGNORED, List.of());   // answered as sent; a block is not a signal (Q-20)
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO team_application (team_id, player_id, expires_at)"
                    + " VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE expires_at = VALUES(expires_at), declined = FALSE")) {
                ps.setLong(1, teamId);                   // a row still here has lapsed: checked above
                ps.setLong(2, playerId);
                ps.setTimestamp(3, Timestamp.from(now.plus(APPLICATION_LIFE)));
                ps.executeUpdate();
            }
            for (long answerer : answerers) {
                InboxRepository.add(c, answerer, InboxRepository.TEAM_APPLICATION, playerId, now);
            }
            return new Applied(Outcome.OK, answerers);
        });
    }

    /** The applicant takes back an unanswered application; false if there is none. */
    public boolean withdrawApplication(long playerId, long teamId) throws SQLException {
        return tx.execute(c -> update(c, "DELETE FROM team_application WHERE team_id = ? AND player_id = ? AND NOT declined",
                teamId, playerId) == 1);
    }

    /**
     * The team's unanswered applications, the {@value #LISTED} newest, as its leader or a vice
     * leader sees them; null for a player in no team or a member.
     */
    public List<Application> applicationsTo(long byPlayer, Instant now) throws SQLException {
        Long teamId = unlockedTeamIdOf(byPlayer);
        if (teamId == null) {
            return null;
        }
        return tx.execute(c -> {
            int role = roleIn(c, teamId, byPlayer);
            if (role < 0 || role == MEMBER) {
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT a.player_id, p.display_name, a.expires_at"
                    + " FROM team_application a JOIN player p ON p.id = a.player_id WHERE a.team_id = ?"
                    + " AND a.expires_at > ? AND NOT a.declined ORDER BY a.expires_at DESC LIMIT " + LISTED)) {
                ps.setLong(1, teamId);
                ps.setTimestamp(2, Timestamp.from(now));
                List<Application> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Application(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()));
                    }
                }
                return out;
            }
        });
    }

    /** The player's own unanswered applications, the {@value #LISTED} newest. */
    public List<Applying> applicationsOf(long playerId, Instant now) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT a.team_id, t.name, a.expires_at FROM team_application a"
                    + " JOIN team t ON t.id = a.team_id WHERE a.player_id = ? AND a.expires_at > ? AND NOT a.declined"
                    + " ORDER BY a.expires_at DESC LIMIT " + LISTED)) {
                ps.setLong(1, playerId);
                ps.setTimestamp(2, Timestamp.from(now));
                List<Applying> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Applying(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()));
                    }
                }
                return out;
            }
        });
    }

    /**
     * The leader or a vice leader answers an application: accepted, the player joins as a member,
     * as by an invitation, and every other application of theirs goes; declined, it is kept, marked,
     * until it lapses, so the same player cannot send it again meanwhile.
     */
    public Outcome answerApplication(long byPlayer, long playerId, boolean accept, Instant now) throws SQLException {
        Long teamId = unlockedTeamIdOf(byPlayer);
        if (teamId == null) {
            return Outcome.NO_TEAM;
        }
        return tx.execute(c -> {
            if (!lockTeam(c, teamId)) {
                return Outcome.NO_TEAM;
            }
            int role = roleIn(c, teamId, byPlayer);
            if (role < 0) {
                return Outcome.NO_TEAM;
            }
            if (role == MEMBER) {
                return Outcome.NOT_ALLOWED;
            }
            lockPlayer(c, playerId);
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM team_application"
                    + " WHERE team_id = ? AND player_id = ? AND expires_at > ? AND NOT declined")) {
                ps.setLong(1, teamId);
                ps.setLong(2, playerId);
                ps.setTimestamp(3, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Outcome.NO_APPLICATION;
                    }
                }
            }
            if (!accept) {
                update(c, "UPDATE team_application SET declined = TRUE WHERE team_id = ? AND player_id = ?", teamId, playerId);
                return Outcome.OK;
            }
            Outcome free = freeToJoin(c, playerId, now);
            if (free != Outcome.OK) {
                return free;
            }
            if (memberCount(c, teamId) >= capacity) {
                return Outcome.TEAM_FULL;
            }
            addMember(c, teamId, playerId, MEMBER);
            update(c, "UPDATE team SET member_count = member_count + 1 WHERE id = ?", teamId);
            update(c, "DELETE FROM team_application WHERE player_id = ?", playerId);    // in a team now
            return Outcome.OK;
        });
    }

    /**
     * Teams whose name starts with {@code start}, by the name's collation, which ignores case and
     * accents; {@code %} and {@code _} taken as written. The {@value #FOUND} first by name.
     */
    public List<Found> find(String start) throws SQLException {
        String pattern = start.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, name, member_count, rating FROM team"
                    + " WHERE name LIKE ? ORDER BY name LIMIT " + FOUND)) {
                ps.setString(1, pattern);
                List<Found> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Found(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)));
                    }
                }
                return out;
            }
        });
    }

    /** One team as anyone may see it; null if there is none. */
    public Found find(long teamId) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, name, member_count, rating FROM team WHERE id = ?")) {
                ps.setLong(1, teamId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Found(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4)) : null;
                }
            }
        });
    }

    /** Deletes lapsed applications, declined ones included, {@code batch} a transaction (06 §9). */
    public int purgeLapsedApplications(Instant now, int batch) throws SQLException {
        int total = 0;
        while (true) {
            int removed = tx.execute(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM team_application WHERE expires_at <= ? LIMIT ?")) {
                    ps.setTimestamp(1, Timestamp.from(now));
                    ps.setInt(2, batch);
                    return ps.executeUpdate();
                }
            });
            total += removed;
            if (removed < batch) {
                return total;
            }
        }
    }

    /** A team's name changes once in this, by its leader, free; the first at once (04 §1, Q-48's balance). */
    public static final Duration RENAME_EVERY = Duration.ofDays(30);

    /** The leader renames the team, the name already checked, unless it was renamed within {@link #RENAME_EVERY}. */
    public Outcome rename(long byPlayer, String name, Instant now) throws SQLException {
        Long teamId = unlockedTeamIdOf(byPlayer);
        if (teamId == null) {
            return Outcome.NO_TEAM;
        }
        try {
            return tx.execute(c -> {
                Timestamp last;
                try (PreparedStatement ps = c.prepareStatement("SELECT renamed_at FROM team WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, teamId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Outcome.NO_TEAM;
                        }
                        last = rs.getTimestamp(1);
                    }
                }
                if (roleIn(c, teamId, byPlayer) != LEADER) {
                    return Outcome.NOT_ALLOWED;
                }
                if (last != null && now.isBefore(last.toInstant().plus(RENAME_EVERY))) {
                    return Outcome.RENAMED_RECENTLY;
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE team SET name = ?, renamed_at = ? WHERE id = ?")) {
                    ps.setString(1, name);
                    ps.setTimestamp(2, Timestamp.from(now));
                    ps.setLong(3, teamId);
                    ps.executeUpdate();
                }
                return Outcome.OK;
            });
        } catch (SQLException e) {
            if (e.getErrorCode() == DUPLICATE_ENTRY) {
                return Outcome.NAME_TAKEN;                 // unique by the column's collation, as at its making
            }
            throw e;
        }
    }

    /** Invitations one team may have out at once, unlapsed (Q-46). */
    public static final int MAX_INVITED = 20;
    /** The invitations to a player listed, the newest (Q-46). */
    public static final int LISTED = 50;

    public record Member(long playerId, String name, int role) { }

    /** A team: its members, leader first, and its record in team matches (Q-18). */
    public record Team(long id, String name, List<Member> members, int rating, int wins, int losses, int draws) { }

    public record Invite(long teamId, String teamName, Instant expiresAt) { }

    /** {@code teamId} for OK only. */
    public record Created(Outcome outcome, long teamId) { }

    /** A party's side in a team match (Q-18): its team, the team's rating, and the first player's role. */
    public record Side(long teamId, int rating, int role) { }

    private final Tx tx;
    private final int capacity;
    private final int maxInvited;

    public TeamRepository(DataSource dataSource, int capacity) {
        this(dataSource, capacity, MAX_INVITED);
    }

    /** With {@code maxInvited} invitations out at once a team. */
    public TeamRepository(DataSource dataSource, int capacity, int maxInvited) {
        this.tx = new Tx(dataSource);
        this.capacity = capacity;
        this.maxInvited = maxInvited;
    }

    /** Retention: deletes invitations lapsed by {@code now}, {@code batch} at a time (D-40). @return how many */
    public int purgeLapsedInvites(Instant now, int batch) throws SQLException {
        int total = 0;
        while (true) {
            int removed = tx.execute(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM team_invite WHERE expires_at <= ? LIMIT ?")) {
                    ps.setTimestamp(1, Timestamp.from(now));
                    ps.setInt(2, batch);
                    return ps.executeUpdate();
                }
            });
            total += removed;
            if (removed < batch) {
                return total;
            }
        }
    }

    /** A team named {@code name}, already checked by a display name's rules, led by the player. */
    public Created create(long playerId, String name, Instant now) throws SQLException {
        return tx.execute(c -> {
            Outcome free = freeToJoin(c, playerId, now);
            if (free != Outcome.OK) {
                return new Created(free, 0);
            }
            long teamId;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO team (name, member_count) VALUES (?, 1)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    teamId = keys.getLong(1);
                }
            } catch (SQLException e) {
                if (e.getErrorCode() == DUPLICATE_ENTRY) {
                    return new Created(Outcome.NAME_TAKEN, 0);
                }
                throw e;
            }
            addMember(c, teamId, playerId, LEADER);
            return new Created(Outcome.OK, teamId);
        });
    }

    /**
     * The team every one of {@code players} is in, at its rating, with the first player's role;
     * null if any is in no team, or in another. Read when a party queues for a team match (Q-18).
     */
    public Side sideOf(List<Long> players) throws SQLException {
        return tx.execute(c -> {
            StringBuilder marks = new StringBuilder();
            for (int i = 0; i < players.size(); i++) {
                marks.append(i == 0 ? "?" : ",?");
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT m.player_id, m.team_id, m.role, t.rating"
                    + " FROM team_member m JOIN team t ON t.id = m.team_id WHERE m.player_id IN (" + marks + ")")) {
                for (int i = 0; i < players.size(); i++) {
                    ps.setLong(i + 1, players.get(i));
                }
                Side side = null;
                int found = 0;
                long teamId = -1;
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        found++;
                        if (teamId != -1 && rs.getLong(2) != teamId) {
                            return null;
                        }
                        teamId = rs.getLong(2);
                        if (rs.getLong(1) == players.get(0)) {
                            side = new Side(teamId, rs.getInt(4), rs.getInt(3));
                        }
                    }
                }
                return found == players.size() ? side : null;
            }
        });
    }

    /** The player's team and its members, leader first; null if in none. */
    public Team teamOf(long playerId) throws SQLException {
        return tx.execute(c -> {
            Long teamId = teamIdOf(c, playerId);
            if (teamId == null) {
                return null;
            }
            String name;
            int[] record = new int[4];
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, rating, wins, losses, draws FROM team WHERE id = ?")) {
                ps.setLong(1, teamId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    name = rs.getString(1);
                    for (int i = 0; i < 4; i++) {
                        record[i] = rs.getInt(i + 2);
                    }
                }
            }
            List<Member> members = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT m.player_id, p.display_name, m.role"
                    + " FROM team_member m JOIN player p ON p.id = m.player_id WHERE m.team_id = ?"
                    + " ORDER BY m.role DESC, m.joined_at, m.player_id")) {
                ps.setLong(1, teamId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        members.add(new Member(rs.getLong(1), rs.getString(2), rs.getInt(3)));
                    }
                }
            }
            return new Team(teamId, name, List.copyOf(members), record[0], record[1], record[2], record[3]);
        });
    }

    /**
     * A leader or vice leader invites the player, for seven days; a team with {@link #MAX_INVITED}
     * others invited invites no more until one is answered or lapsed (Q-46).
     */
    public Outcome invite(long byPlayer, long target, Instant now) throws SQLException {
        Long teamId = unlockedTeamIdOf(byPlayer);
        if (teamId == null) {
            return Outcome.NO_TEAM;
        }
        return tx.execute(c -> {
            if (!lockTeam(c, teamId)) {
                return Outcome.NO_TEAM;
            }
            int role = roleIn(c, teamId, byPlayer);
            if (role < 0) {
                return Outcome.NO_TEAM;
            }
            if (role == MEMBER) {
                return Outcome.NOT_ALLOWED;
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT team_id FROM player WHERE id = ?")) {
                ps.setLong(1, target);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Outcome.NO_SUCH_PLAYER;
                    }
                    rs.getLong(1);
                    if (!rs.wasNull()) {
                        return Outcome.IN_TEAM;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM team_invite"
                    + " WHERE team_id = ? AND player_id <> ? AND expires_at > ?")) {
                ps.setLong(1, teamId);
                ps.setLong(2, target);
                ps.setTimestamp(3, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) >= maxInvited) {
                        return Outcome.TOO_MANY_INVITED;
                    }
                }
            }
            if (FriendRepository.blocks(c, target, byPlayer)) {
                return Outcome.IGNORED;                 // answered as sent; a block is not a signal (Q-20)
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO team_invite (team_id, player_id, invited_by,"
                    + " expires_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE invited_by = VALUES(invited_by),"
                    + " expires_at = VALUES(expires_at)")) {
                ps.setLong(1, teamId);
                ps.setLong(2, target);
                ps.setLong(3, byPlayer);
                ps.setTimestamp(4, Timestamp.from(now.plus(INVITE_LIFE)));
                ps.executeUpdate();
            }
            InboxRepository.add(c, target, InboxRepository.TEAM_INVITE, teamId, now);
            return Outcome.OK;
        });
    }

    /** The player's invitations not yet expired, newest first, the {@value #LISTED} newest. */
    public List<Invite> invitesOf(long playerId, Instant now) throws SQLException {
        return tx.execute(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT i.team_id, t.name, i.expires_at FROM team_invite i"
                    + " JOIN team t ON t.id = i.team_id WHERE i.player_id = ? AND i.expires_at > ?"
                    + " ORDER BY i.expires_at DESC LIMIT " + LISTED)) {
                ps.setLong(1, playerId);
                ps.setTimestamp(2, Timestamp.from(now));
                List<Invite> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Invite(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()));
                    }
                }
                return List.copyOf(out);
            }
        });
    }

    /** Accepts or declines the team's invitation. */
    public Outcome answer(long playerId, long teamId, boolean accept, Instant now) throws SQLException {
        return tx.execute(c -> {
            if (!lockTeam(c, teamId)) {
                return Outcome.NO_INVITE;
            }
            lockPlayer(c, playerId);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM team_invite WHERE team_id = ? AND player_id = ? AND expires_at > ?")) {
                ps.setLong(1, teamId);
                ps.setLong(2, playerId);
                ps.setTimestamp(3, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Outcome.NO_INVITE;
                    }
                }
            }
            if (accept) {
                Outcome free = freeToJoin(c, playerId, now);
                if (free != Outcome.OK) {
                    return free;
                }
                if (memberCount(c, teamId) >= capacity) {
                    return Outcome.TEAM_FULL;
                }
                addMember(c, teamId, playerId, MEMBER);
                update(c, "UPDATE team SET member_count = member_count + 1 WHERE id = ?", teamId);
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM team_invite WHERE team_id = ? AND player_id = ?")) {
                ps.setLong(1, teamId);
                ps.setLong(2, playerId);
                ps.executeUpdate();
            }
            return Outcome.OK;
        });
    }

    /** Leaves the team: a leader only when alone, which ends the team. */
    public Outcome leave(long playerId, Instant now) throws SQLException {
        Long teamId = unlockedTeamIdOf(playerId);
        if (teamId == null) {
            return Outcome.NO_TEAM;
        }
        return tx.execute(c -> {
            if (!lockTeam(c, teamId)) {
                return Outcome.NO_TEAM;
            }
            lockPlayer(c, playerId);
            int role = roleIn(c, teamId, playerId);
            if (role < 0) {
                return Outcome.NO_TEAM;
            }
            if (role == LEADER) {
                if (memberCount(c, teamId) > 1) {
                    return Outcome.LEADER_WITH_MEMBERS;
                }
                removeTeam(c, teamId, now);
                return Outcome.OK;
            }
            removeMember(c, teamId, playerId, now);
            return Outcome.OK;
        });
    }

    /** A leader removes anyone but themself; a vice leader, members only. */
    public Outcome kick(long byPlayer, long playerId, Instant now) throws SQLException {
        Long teamId = unlockedTeamIdOf(byPlayer);
        if (teamId == null) {
            return Outcome.NO_TEAM;
        }
        return tx.execute(c -> {
            if (!lockTeam(c, teamId)) {
                return Outcome.NO_TEAM;
            }
            lockPlayer(c, playerId);
            int by = roleIn(c, teamId, byPlayer);
            if (by < 0) {
                return Outcome.NO_TEAM;
            }
            int target = roleIn(c, teamId, playerId);
            if (target < 0) {
                return Outcome.NOT_A_MEMBER;
            }
            boolean allowed = by == LEADER ? playerId != byPlayer : by == VICE && target == MEMBER;
            if (!allowed) {
                return Outcome.NOT_ALLOWED;
            }
            removeMember(c, teamId, playerId, now);
            return Outcome.OK;
        });
    }

    /** The leader makes a member a vice leader, two at most, or a vice leader a member again. */
    public Outcome setRole(long byPlayer, long playerId, int role) throws SQLException {
        Long teamId = unlockedTeamIdOf(byPlayer);
        if (teamId == null) {
            return Outcome.NO_TEAM;
        }
        return tx.execute(c -> {
            if (!lockTeam(c, teamId)) {
                return Outcome.NO_TEAM;
            }
            if (roleIn(c, teamId, byPlayer) != LEADER || playerId == byPlayer || (role != VICE && role != MEMBER)) {
                return Outcome.NOT_ALLOWED;
            }
            int now = roleIn(c, teamId, playerId);
            if (now < 0) {
                return Outcome.NOT_A_MEMBER;
            }
            if (role == VICE && now != VICE) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM team_member WHERE team_id = ? AND role = ?")) {
                    ps.setLong(1, teamId);
                    ps.setInt(2, VICE);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getInt(1) >= MAX_VICES) {
                            return Outcome.TOO_MANY_VICES;
                        }
                    }
                }
            }
            setRoleOf(c, teamId, playerId, role);
            return Outcome.OK;
        });
    }

    /** The leader hands the team to a member, and becomes one. */
    public Outcome transfer(long byPlayer, long toPlayer) throws SQLException {
        Long teamId = unlockedTeamIdOf(byPlayer);
        if (teamId == null) {
            return Outcome.NO_TEAM;
        }
        return tx.execute(c -> {
            if (!lockTeam(c, teamId)) {
                return Outcome.NO_TEAM;
            }
            if (roleIn(c, teamId, byPlayer) != LEADER || toPlayer == byPlayer) {
                return Outcome.NOT_ALLOWED;
            }
            if (roleIn(c, teamId, toPlayer) < 0) {
                return Outcome.NOT_A_MEMBER;
            }
            setRoleOf(c, teamId, toPlayer, LEADER);
            setRoleOf(c, teamId, byPlayer, MEMBER);
            return Outcome.OK;
        });
    }

    /** The leader ends the team: everyone out, their cooldowns started, its invitations gone. */
    public Outcome disband(long byPlayer, Instant now) throws SQLException {
        Long teamId = unlockedTeamIdOf(byPlayer);
        if (teamId == null) {
            return Outcome.NO_TEAM;
        }
        return tx.execute(c -> {
            if (!lockTeam(c, teamId)) {
                return Outcome.NO_TEAM;
            }
            if (roleIn(c, teamId, byPlayer) != LEADER) {
                return Outcome.NOT_ALLOWED;
            }
            removeTeam(c, teamId, now);
            return Outcome.OK;
        });
    }

    // ---- inside a transaction ------------------------------------------------------------

    private Long unlockedTeamIdOf(long playerId) throws SQLException {
        return tx.execute(c -> teamIdOf(c, playerId));
    }

    private static Long teamIdOf(Connection c, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT team_id FROM player WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                long id = rs.getLong(1);
                return rs.wasNull() ? null : id;
            }
        }
    }

    private static boolean lockTeam(Connection c, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM team WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void lockPlayer(Connection c, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM player WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, playerId);
            ps.executeQuery().close();
        }
    }

    /** OK, IN_TEAM, or COOLING_DOWN within a day of leaving one; locks the player. */
    private static Outcome freeToJoin(Connection c, long playerId, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT team_id, team_left_at FROM player WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Outcome.NO_SUCH_PLAYER;
                }
                rs.getLong(1);
                if (!rs.wasNull()) {
                    return Outcome.IN_TEAM;
                }
                Timestamp left = rs.getTimestamp(2);
                if (left != null && left.toInstant().plus(COOLDOWN).isAfter(now)) {
                    return Outcome.COOLING_DOWN;
                }
                return Outcome.OK;
            }
        }
    }

    /** The player's role in the team, or -1. */
    private static int roleIn(Connection c, long teamId, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT role FROM team_member WHERE team_id = ? AND player_id = ?")) {
            ps.setLong(1, teamId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static int memberCount(Connection c, long teamId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT member_count FROM team WHERE id = ?")) {
            ps.setLong(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void addMember(Connection c, long teamId, long playerId, int role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO team_member (team_id, player_id, role) VALUES (?, ?, ?)")) {
            ps.setLong(1, teamId);
            ps.setLong(2, playerId);
            ps.setInt(3, role);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE player SET team_id = ? WHERE id = ?")) {
            ps.setLong(1, teamId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    private static void removeMember(Connection c, long teamId, long playerId, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM team_member WHERE team_id = ? AND player_id = ?")) {
            ps.setLong(1, teamId);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
        leftTeam(c, "UPDATE player SET team_id = NULL, team_left_at = ? WHERE id = ?", playerId, now);
        update(c, "UPDATE team SET member_count = member_count - 1 WHERE id = ?", teamId);
    }

    /**
     * Everyone out, cooldowns started, invitations and the team gone. The members by their ids,
     * read as they are under the team's lock, lowest first, each row locked by its key: by
     * {@code player.team_id}, which has no index, the update walked and locked every player's row
     * (defect D-32).
     */
    private static void removeTeam(Connection c, long teamId, Instant now) throws SQLException {
        List<Long> members = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_id FROM team_member WHERE team_id = ? ORDER BY player_id FOR UPDATE")) {
            ps.setLong(1, teamId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    members.add(rs.getLong(1));
                }
            }
        }
        for (long member : members) {
            leftTeam(c, "UPDATE player SET team_id = NULL, team_left_at = ? WHERE id = ?", member, now);
        }
        update(c, "DELETE FROM team_member WHERE team_id = ?", teamId);
        update(c, "DELETE FROM team_invite WHERE team_id = ?", teamId);
        update(c, "DELETE FROM team_application WHERE team_id = ?", teamId);
        update(c, "DELETE FROM team WHERE id = ?", teamId);
    }

    private static void leftTeam(Connection c, String sql, long id, Instant now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(now));
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private static void setRoleOf(Connection c, long teamId, long playerId, int role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE team_member SET role = ? WHERE team_id = ? AND player_id = ?")) {
            ps.setInt(1, role);
            ps.setLong(2, teamId);
            ps.setLong(3, playerId);
            ps.executeUpdate();
        }
    }

    private static int update(Connection c, String sql, long first, long second) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, first);
            ps.setLong(2, second);
            return ps.executeUpdate();
        }
    }

    private static void update(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }
}
