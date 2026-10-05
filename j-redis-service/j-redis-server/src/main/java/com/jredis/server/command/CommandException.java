package com.jredis.server.command;

/**
 * A command was used incorrectly (bad arguments, wrong type...). Carries the complete RESP error
 * text including its prefix, e.g. "WRONGTYPE Operation against...". Thrown only before a command
 * changes data (validate first, mutate second), so it never indicates a half-applied change.
 */
public final class CommandException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CommandException(String message) {
        super(message, null, false, false);
    }

    public static final CommandException WRONGTYPE =
            new CommandException("WRONGTYPE Operation against a key holding the wrong kind of value");
    public static final CommandException SYNTAX = new CommandException("ERR syntax error");
    public static final CommandException NOT_INTEGER = new CommandException("ERR value is not an integer or out of range");
    public static final CommandException NOT_FLOAT = new CommandException("ERR value is not a valid float");
    public static final CommandException NO_SUCH_KEY = new CommandException("ERR no such key");
    public static final CommandException INDEX_OUT_OF_RANGE = new CommandException("ERR index out of range");
    public static final CommandException OVERFLOW = new CommandException("ERR increment or decrement would overflow");
}
