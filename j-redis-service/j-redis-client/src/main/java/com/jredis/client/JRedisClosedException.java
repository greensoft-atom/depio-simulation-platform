package com.jredis.client;

/** The client was closed. */
public final class JRedisClosedException extends JRedisException {
    private static final long serialVersionUID = 1L;

    public JRedisClosedException(String message) {
        super(message);
    }
}
