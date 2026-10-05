package com.backend.handoff;

/**
 * The modes (docs detailed-design/04-platform-services.md §4, "Modes"): one definition, read
 * by every process that needs one. {@code platform} takes the roster from it, the arena the
 * rules, {@code worker} how a result is scored. In code, as the content tables are, so a
 * release carries one version of it.
 *
 * The id is the {@code mode} the Welcome, the result and {@code matches.mode} carry.
 */
public enum MatchMode {

    /** The public arena: open, no roster, played on the arena's own map (D-15). */
    FFA(0, "ffa", false, 0, 0f, 0, 0, 0, 0, false, 0),

    /**
     * Two players, rated. The first to three kills wins; at three minutes, the one with more,
     * else a draw. Numbers as unplayed as the rest of the content.
     */
    DUEL(1, "duel", true, 2, 2_000f, 40, 180, 3, 30, true, 1),

    /**
     * Three against three, rated (01 §8.4): the first team to ten kills of the other's tanks
     * wins; at five minutes, the team with more, else a draw.
     */
    TVT(2, "tvt", true, 6, 3_000f, 80, 300, 10, 30, true, 3),
    /**
     * Ranked free-for-all, eight each for themselves, rated pairwise (04 §4, the fourth slice;
     * D-28): four minutes, placed by score at the whistle, no kill ends it. Its numbers are open
     * with the user (Q-6).
     */
    RFFA(3, "rffa", true, 8, 3_500f, 120, 240, 0, 30, true, 1),
    /**
     * Co-op, one team of three against the arena's own tanks in waves (01 §8.5; 04 §4, the fifth
     * slice): ten waves, or a wipe, or ten minutes. Unrated. Its rules are open with the user (Q-7).
     */
    COOP(4, "coop", true, 3, 3_000f, 60, 600, 0, 30, false, 3),
    /**
     * A team match (04 §4, the sixth slice; Q-18): two teams, three of each, by team-vs-team's
     * rules. Each side is a party of three of one team; the teams are rated, not the players.
     */
    TEAMS(5, "teams", true, 6, 3_000f, 80, 300, 10, 30, true, 3),

    /**
     * Three against three over three dominators (01 §8.7, Q-29): a team holding all three for
     * 60 s wins; at five minutes, the team holding more, else a draw. No kills end it. Unrated.
     */
    DOMINATION(6, "domination", true, 6, 3_000f, 80, 300, 0, 30, false, 3),

    /**
     * Three against three, a kill converting its victim to the killer's team (01 §8.8, Q-30):
     * one team with every player ends it at once; at five minutes, the team with more, else a
     * draw. Each player placed by the team they started on. Unrated.
     */
    TAG(7, "tag", true, 6, 3_000f, 80, 300, 0, 30, false, 3),

    /**
     * Eight each for themselves in a maze (01 §8.9, Q-31): placed by score, four minutes, no kills
     * end it. Unrated.
     */
    MAZE(8, "maze", true, 8, 3_000f, 120, 240, 0, 30, false, 1),

    /**
     * A private room, opened by a player for themselves or by a party's leader for the party, and
     * never queued for (01 §8.10, Q-32): each for themselves, twenty minutes, nothing published.
     */
    SANDBOX(9, "sandbox", true, 0, 2_000f, 60, 1_200, 0, 0, false, 1);

    /** The most a sandbox holds: a party's most, as {@code platform}'s parties hold, held equal by its test. */
    private static final int SANDBOX_PLACES = 3;

    public final int id;
    /** How the API and the lobby name it. */
    public final String key;
    public final boolean timed;
    /** Players a match of it is made for; 0 for a mode nobody queues for. */
    public final int roster;
    public final float mapSize;
    public final int shapes;
    public final int durationSeconds;
    /** Kills that end the match at once; 0 for none. */
    public final int winKills;
    /** How long a match's room waits for its roster before playing with whoever came. */
    public final int joinWindowSeconds;
    public final boolean rated;
    /** Players on each side: a duel's are teams of one; 0 for a mode without sides. */
    public final int teamSize;

    MatchMode(int id, String key, boolean timed, int roster, float mapSize, int shapes,
              int durationSeconds, int winKills, int joinWindowSeconds, boolean rated, int teamSize) {
        this.id = id;
        this.key = key;
        this.timed = timed;
        this.roster = roster;
        this.mapSize = mapSize;
        this.shapes = shapes;
        this.durationSeconds = durationSeconds;
        this.winKills = winKills;
        this.joinWindowSeconds = joinWindowSeconds;
        this.rated = rated;
        this.teamSize = teamSize;
    }

    /** Whether its sides are teams of more than one: its kills and placings are the team's. */
    public boolean teams() {
        return teamSize > 1;
    }

    /** Whether players queue for it: a mode with a roster. */
    public boolean queued() {
        return roster > 0;
    }

    /** Whether its rooms are made for one match each, from its tickets (D-20): all but the public arena. */
    public boolean made() {
        return this != FFA;
    }

    /** The most players a room of it holds: its roster, or a party for a sandbox, which has none. */
    public int places() {
        return this == SANDBOX ? SANDBOX_PLACES : roster;
    }

    /** @return the mode, or null for an id this build does not know. */
    public static MatchMode ofId(int id) {
        for (MatchMode m : values()) {
            if (m.id == id) {
                return m;
            }
        }
        return null;
    }

    /** @return the mode, or null for a key this build does not know. */
    public static MatchMode ofKey(String key) {
        for (MatchMode m : values()) {
            if (m.key.equals(key)) {
                return m;
            }
        }
        return null;
    }
}
