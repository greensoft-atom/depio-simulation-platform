package com.jredis.client;

/** No reply within the command timeout. For a write, the outcome is unknown. */
public final class JRedisTimeoutException extends JRedisException {
    private static final long serialVersionUID = 1L;

    public JRedisTimeoutException(String message) {
        super(message);
    }
}
