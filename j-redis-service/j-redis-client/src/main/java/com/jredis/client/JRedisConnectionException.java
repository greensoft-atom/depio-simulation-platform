package com.jredis.client;

/**
 * The connection was not available or was lost while the request was pending. For a write the
 * outcome is unknown: the server may or may not have executed it. The client never retries on its
 * own; callers retry only idempotent operations.
 */
public final class JRedisConnectionException extends JRedisException {
    private static final long serialVersionUID = 1L;

    public JRedisConnectionException(String message) {
        super(message);
    }

    public JRedisConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
