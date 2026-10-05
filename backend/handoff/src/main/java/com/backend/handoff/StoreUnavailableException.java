package com.backend.handoff;

/**
 * The store did not answer: not connected, timed out, or refused the call.
 *
 * One type, so that every caller of a blocking store call can be told apart from a bug. An
 * HTTP layer turns this into 503, which tells a client to come back; the
 * {@code IllegalStateException} it replaces became a 500, which tells a client the server
 * is broken. The j-redis client already bounds every call (2 s by default), so the failure
 * arrives promptly; what was wrong was only what it was called.
 */
public final class StoreUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StoreUnavailableException(Throwable cause) {
        super(cause);
    }
}
