package com.jredis.server.command;

import com.jredis.server.core.CommandContext;

/** Executes one command. Implementations are static methods registered in {@link CommandTable}. */
@FunctionalInterface
public interface CommandHandler {
    void execute(CommandContext ctx);
}
