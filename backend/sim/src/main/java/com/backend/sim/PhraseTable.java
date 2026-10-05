package com.backend.sim;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * What a player may say: a fixed list, sent as ids, never free text (D-14; 01 §9).
 *
 * Content, as the class table is, and reaching the device the same way (D-24): {@code platform}
 * serves {@link #json()}, and {@link #version()}, a hash of it, is both the ETag and the
 * Welcome's {@code phraseListVersion}. The arena needs only {@link #contains}: it relays an id,
 * and the words are the device's to show.
 *
 * <h2>Ids are never reused</h2>
 *
 * They start at 1 and only grow; a removed phrase leaves a gap. Reusing one would make a device
 * still holding the old list show the new phrase as the old one.
 */
public final class PhraseTable {

    /**
     * One phrase. {@code key} is what a device's translation table is keyed by; {@code text} is
     * the English.
     */
    public record Phrase(int id, String key, String text) {
    }

    private static final PhraseTable DEFAULTS = new PhraseTable(List.of(
            new Phrase(1, "hello", "Hello!"),
            new Phrase(2, "good_luck", "Good luck!"),
            new Phrase(3, "thanks", "Thanks!"),
            new Phrase(4, "sorry", "Sorry!"),
            new Phrase(5, "nice_shot", "Nice shot!"),
            new Phrase(6, "well_played", "Well played!"),
            new Phrase(7, "help", "Help!"),
            new Phrase(8, "follow_me", "Follow me!"),
            new Phrase(9, "attack", "Attack!"),
            new Phrase(10, "fall_back", "Fall back!"),
            new Phrase(11, "wait", "Wait!"),
            new Phrase(12, "on_my_way", "On my way!"),
            new Phrase(13, "yes", "Yes"),
            new Phrase(14, "no", "No"),
            new Phrase(15, "oops", "Oops!"),
            new Phrase(16, "bye", "Bye!")));

    private final List<Phrase> phrases;
    private final boolean[] known;
    private final String json;
    private final long version;

    public PhraseTable(List<Phrase> phrases) {
        if (phrases.isEmpty()) {
            throw new IllegalArgumentException("a phrase list with nothing to say");
        }
        Set<String> keys = new HashSet<>();
        int last = 0;
        for (Phrase p : phrases) {
            if (p.id() <= last) {
                throw new IllegalArgumentException("phrase ids start at 1 and ascend: " + p.id() + " after " + last);
            }
            last = p.id();
            if (!p.key().matches("[a-z_]+") || !keys.add(p.key())) {
                throw new IllegalArgumentException("a key is lower case and underscores, and once: " + p.key());
            }
            // Written into JSON by hand, so nothing it would have to escape.
            if (p.text().isBlank() || !p.text().chars().allMatch(c -> c >= ' ' && c != '"' && c != '\\')) {
                throw new IllegalArgumentException("phrase " + p.id() + ": words to show, nothing to escape");
            }
        }
        this.phrases = List.copyOf(phrases);
        this.known = new boolean[last + 1];
        for (Phrase p : phrases) {
            known[p.id()] = true;
        }
        this.json = toJson(this.phrases);
        CRC32 crc = new CRC32();
        crc.update(json.getBytes(StandardCharsets.UTF_8));
        this.version = crc.getValue();
    }

    /** The shipped list, Q-8's recommendation. One instance, shared. */
    public static PhraseTable defaults() {
        return DEFAULTS;
    }

    public List<Phrase> phrases() {
        return phrases;
    }

    public int size() {
        return phrases.size();
    }

    /** Whether a player may say this id: not 0, not past the end, not a removed one. */
    public boolean contains(int id) {
        return id > 0 && id < known.length && known[id];
    }

    /** The list as {@code platform} serves it, the same bytes for the same list. */
    public String json() {
        return json;
    }

    /** A CRC-32 of {@link #json()}: the Welcome's {@code phraseListVersion}, and {@code platform}'s ETag. */
    public long version() {
        return version;
    }

    private static String toJson(List<Phrase> phrases) {
        StringBuilder s = new StringBuilder("[");
        for (Phrase p : phrases) {
            if (s.length() > 1) {
                s.append(',');
            }
            s.append("{\"id\":").append(p.id())
                    .append(",\"key\":\"").append(p.key()).append('"')
                    .append(",\"text\":\"").append(p.text()).append("\"}");
        }
        return s.append(']').toString();
    }
}
