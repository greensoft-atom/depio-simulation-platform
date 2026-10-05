package com.backend.handoff;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * A single-use handoff from {@code platform} to an arena
 * (docs detailed-design/04-platform-services.md §3).
 *
 * The arena has no database connection and never gets one, so everything it needs about a
 * player has to arrive in here: identity and side; for a match the matcher made, which match
 * and its mode, the arena making the match's room from the first of its tickets (D-20); the
 * boost worn, as a bonus per stat (04 §8, D-37); and the skin worn, which changes nothing in the
 * simulation and is told with the tank's create to whoever sees it (D-70).
 *
 * The id is the credential — anyone holding it joins as this player — so it is 128 bits
 * from {@link SecureRandom}, not a counter and not a UUID of unstated strength.
 */
public record Ticket(String id, long playerId, String displayName, int team, String matchUid,
                     int mode, String bonus, int skin) {

    /** Stats a bonus can name: sim's {@code Stat.COUNT}, which the arena's tests hold equal. */
    public static final int BONUS_STATS = 8;
    /** The most a bonus gives one stat (04 §8, D-37). */
    public static final int MAX_BONUS = 25;

    /** Field names on the wire to j-redis. Both sides of the handoff read them from here. */
    static final String F_PLAYER_ID = "playerId";
    static final String F_NAME = "name";
    static final String F_TEAM = "team";
    static final String F_MATCH = "match";
    static final String F_MODE = "mode";
    static final String F_BONUS = "bonus";
    static final String F_SKIN = "skin";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    public Ticket {
        if (playerId <= 0) {
            throw new IllegalArgumentException("playerId must be positive");
        }
        if (displayName == null || displayName.isEmpty()) {
            throw new IllegalArgumentException("displayName must not be empty");
        }
        if (team < 0 || team > 255) {
            throw new IllegalArgumentException("team travels as a u8 in the snapshot: " + team);
        }
        // Both or neither: a match always has a mode, and an open join is mode 0 (FFA).
        if ((matchUid == null) != (mode == 0) || mode < 0 || mode > 255
                || (matchUid != null && matchUid.length() != 26)) {
            throw new IllegalArgumentException("a match is a 26-character id with a mode of 1 to 255");
        }
        if (bonus == null) {
            throw new IllegalArgumentException("bonus is empty, not null, when there is none");
        }
        decode(bonus);                                  // refuses one that does not read
        if (skin < 0 || skin > 255) {
            throw new IllegalArgumentException("a skin travels as a u8 in its event, 0 for none: " + skin);
        }
    }

    /** A ticket with no skin (D-70). */
    public Ticket(String id, long playerId, String displayName, int team, String matchUid, int mode, String bonus) {
        this(id, playerId, displayName, team, matchUid, mode, bonus, 0);
    }

    /** A seat in an open room: the public arena (D-15). */
    public static Ticket forPlayer(long playerId, String displayName, int team) {
        return forPlayer(playerId, displayName, team, "");
    }

    /** A seat in an open room, with what the player wears: {@link #bonusOf} (D-37). */
    public static Ticket forPlayer(long playerId, String displayName, int team, String bonus) {
        return new Ticket(newId(), playerId, displayName, team, null, 0, bonus);
    }

    /** A seat in an open room, with what the player wears and the skin they wear (D-37, D-70). */
    public static Ticket forPlayer(long playerId, String displayName, int team, String bonus, int skin) {
        return new Ticket(newId(), playerId, displayName, team, null, 0, bonus, skin);
    }

    /** A place in a match the matcher made (04 §4). */
    public static Ticket forMatch(long playerId, String displayName, int team, String matchUid,
                                  int mode) {
        return forMatch(playerId, displayName, team, matchUid, mode, "");
    }

    public static Ticket forMatch(long playerId, String displayName, int team, String matchUid,
                                  int mode, String bonus) {
        return new Ticket(newId(), playerId, displayName, team, matchUid, mode, bonus);
    }

    public static Ticket forMatch(long playerId, String displayName, int team, String matchUid,
                                  int mode, String bonus, int skin) {
        return new Ticket(newId(), playerId, displayName, team, matchUid, mode, bonus, skin);
    }

    /** The bonus a stat, a whole percent, indexed as sim's {@code Stat}: zeros when there is none. */
    public byte[] bonusPercents() {
        return decode(bonus);
    }

    /** Percents a stat as a ticket carries them: {@code stat:percent} pairs for the stats that have one. */
    public static String bonusOf(byte[] percents) {
        java.util.StringJoiner pairs = new java.util.StringJoiner(",");
        for (int stat = 0; stat < percents.length; stat++) {
            if (percents[stat] != 0) {
                pairs.add(stat + ":" + percents[stat]);
            }
        }
        return pairs.toString();
    }

    private static byte[] decode(String bonus) {
        byte[] out = new byte[BONUS_STATS];
        if (bonus.isEmpty()) {
            return out;
        }
        for (String pair : bonus.split(",", -1)) {            // -1: a trailing comma is not a pair
            int colon = pair.indexOf(':');
            int stat = colon < 0 ? -1 : Integer.parseInt(pair.substring(0, colon));
            int percent = colon < 0 ? 0 : Integer.parseInt(pair.substring(colon + 1));
            if (stat < 0 || stat >= BONUS_STATS || percent < 1 || percent > MAX_BONUS || out[stat] != 0) {
                throw new IllegalArgumentException("a bonus is stat:percent pairs, 1 to " + MAX_BONUS
                        + " each, a stat once: " + bonus);
            }
            out[stat] = (byte) percent;
        }
        return out;
    }

    /** Whether this is a place in a made match rather than a seat in an open room. */
    public boolean isMatch() {
        return matchUid != null;
    }

    /** 128 bits, url-safe, 22 characters. */
    public static String newId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    Map<String, String> fields() {
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put(F_PLAYER_ID, Long.toString(playerId));
        fields.put(F_NAME, displayName);
        fields.put(F_TEAM, Integer.toString(team));
        if (matchUid != null) {
            fields.put(F_MATCH, matchUid);
            fields.put(F_MODE, Integer.toString(mode));
        }
        if (!bonus.isEmpty()) {
            fields.put(F_BONUS, bonus);                 // none, no field: as a ticket was before (D-37)
        }
        if (skin != 0) {
            fields.put(F_SKIN, Integer.toString(skin)); // its own field: an arena a release behind ignores it (D-70)
        }
        return fields;
    }

    /**
     * @return the ticket, or null if the map is empty or malformed. A malformed ticket is
     *         treated exactly like a missing one: the arena refuses the join either way,
     *         and a ticket nobody can parse is not evidence the holder is who they claim.
     */
    static Ticket fromFields(String id, Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        try {
            // Absent from an open join's ticket, and from one written before matches existed.
            String mode = fields.get(F_MODE);
            String bonus = fields.get(F_BONUS);
            String skin = fields.get(F_SKIN);
            if (skin != null && Integer.parseInt(skin) < 1) {
                return null;                            // none is no field, never 0
            }
            return new Ticket(id,
                    Long.parseLong(fields.get(F_PLAYER_ID)),
                    fields.get(F_NAME),
                    Integer.parseInt(fields.get(F_TEAM)),
                    fields.get(F_MATCH),
                    mode == null ? 0 : Integer.parseInt(mode),
                    bonus == null ? "" : bonus,
                    skin == null ? 0 : Integer.parseInt(skin));
        } catch (IllegalArgumentException | NullPointerException e) {   // NumberFormat, blank name, missing field
            return null;
        }
    }

    /** Keeps the credential out of logs; the player is identified by id instead. */
    @Override
    public String toString() {
        return "Ticket[player=" + playerId + " name=" + displayName + " team=" + team
                + (matchUid == null ? "" : " match=" + matchUid + " mode=" + mode) + ']';
    }
}
