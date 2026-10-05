package com.jredis.common;

/** Malformed RESP input. The connection that sent it cannot be trusted any more and is closed. */
public final class RespProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RespProtocolException(String message) {
        super(message);
    }
}
