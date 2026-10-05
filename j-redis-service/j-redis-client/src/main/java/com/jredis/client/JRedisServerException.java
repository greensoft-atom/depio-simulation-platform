package com.jredis.client;

/** The server answered with an error reply. {@link #prefix()} is its first word (ERR, WRONGTYPE, OOM...). */
public final class JRedisServerException extends JRedisException {
    private static final long serialVersionUID = 1L;

    private final String prefix;

    public JRedisServerException(String message) {
        super(message);
        int sp = message.indexOf(' ');
        this.prefix = sp < 0 ? message : message.substring(0, sp);
    }

    public String prefix() {
        return prefix;
    }
}
