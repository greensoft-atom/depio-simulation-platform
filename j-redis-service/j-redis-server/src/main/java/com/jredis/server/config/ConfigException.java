package com.jredis.server.config;

/** Invalid configuration: a bad directive in the config file, on the command line, or in CONFIG SET. */
public final class ConfigException extends Exception {
    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }
}
