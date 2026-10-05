package com.jredis.server.persist;

/** Another process owns the data directory. Exit code 2. */
public final class DataDirLockedException extends Exception {
    private static final long serialVersionUID = 1L;

    public DataDirLockedException(String message) {
        super(message);
    }
}
