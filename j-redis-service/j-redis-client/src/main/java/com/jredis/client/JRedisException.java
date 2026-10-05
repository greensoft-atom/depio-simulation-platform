package com.jredis.client;

/** Base of every exception thrown by the client. Unchecked. */
public class JRedisException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JRedisException(String message) {
        super(message);
    }

    public JRedisException(String message, Throwable cause) {
        super(message, cause);
    }
}
