package com.jredis.server.persist;

/** The data files cannot be loaded safely (corruption, missing manifest...). Exit code 3. */
public final class DataLoadException extends Exception {
    private static final long serialVersionUID = 1L;

    public DataLoadException(String message) {
        super(message);
    }

    public DataLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
