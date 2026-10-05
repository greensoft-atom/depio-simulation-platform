package com.backend.handoff;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The envelope a match result travels in
 * (docs detailed-design/05-worker-and-events.md §2).
 *
 * <h2>Two kinds of "I do not understand this"</h2>
 *
 * A field the consumer has never heard of is ignored, because during a rolling deploy a
 * newer arena will be writing entries that an older worker reads, and dropping results over
 * a field nobody needed would be a self-inflicted outage.
 *
 * A {@code v} the consumer has never heard of is refused, because it means the payload's
 * meaning has changed and guessing produces silently wrong rewards. But refused is not the
 * same as discarded, and the two directions are different:
 *
 * <ul>
 * <li>A <em>newer</em> version is a result this build cannot read and a later one can. It is
 *     {@link FutureEntry}, and the consumer keeps it for a newer worker. Deploying arenas
 *     before workers used to dead-letter every result in the rollout window, onto a list
 *     nothing replays.</li>
 * <li>An <em>older</em> or absent version, a wrong type, or no payload, is
 *     {@link UnreadableEntry}: no build will ever read it, and it is set aside as evidence.</li>
 * </ul>
 */
public final class MatchResultCodec {

    public static final String TYPE = "match.result";
    public static final int VERSION = 1;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope(String id, String type, int v, long ts, MatchOutcome payload) { }

    /** Thrown when an entry cannot be turned into a result. The caller dead-letters it. */
    public static final class UnreadableEntry extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnreadableEntry(String message, Throwable cause) {
            super(message, cause);
        }

        UnreadableEntry(String message) {
            super(message);
        }
    }

    /**
     * An entry written by a newer producer than this consumer understands.
     *
     * Deliberately <em>not</em> a subclass of {@link UnreadableEntry}: a caller that catches
     * the general case to dead-letter it must not sweep this one up by accident.
     */
    public static final class FutureEntry extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int version;

        FutureEntry(int version) {
            super("payload version " + version + " is newer than this build's " + VERSION);
            this.version = version;
        }

        public int version() {
            return version;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static String encode(MatchOutcome outcome) {
        // The match id is the envelope id: it is already unique, and it is the same value
        // that becomes the idempotency key in MySQL, so a redelivery cannot be mistaken for
        // a different match.
        Envelope envelope = new Envelope(outcome.matchUid(), TYPE, VERSION,
                System.currentTimeMillis(), outcome);
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new UnreadableEntry("could not encode match " + outcome.matchUid(), e);
        }
    }

    public static MatchOutcome decode(String json) {
        Envelope envelope;
        try {
            envelope = MAPPER.readValue(json, Envelope.class);
        } catch (JsonProcessingException e) {
            throw new UnreadableEntry("malformed entry", e);
        }
        if (envelope.v() > VERSION) {
            throw new FutureEntry(envelope.v());
        }
        if (envelope.v() != VERSION) {
            throw new UnreadableEntry("unsupported payload version " + envelope.v()
                    + " (this build reads " + VERSION + ")");
        }
        if (!TYPE.equals(envelope.type())) {
            throw new UnreadableEntry("unexpected type " + envelope.type());
        }
        if (envelope.payload() == null || envelope.payload().matchUid() == null) {
            throw new UnreadableEntry("entry carries no result");
        }
        return envelope.payload();
    }

    private MatchResultCodec() {
    }
}
