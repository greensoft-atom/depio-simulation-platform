package com.jredis.server.command;

import com.jredis.server.config.ServerConfig;

/** Builds the command table: every supported command, minus the ones disabled in the config. */
public final class Commands {

    private Commands() {
    }

    public static CommandTable createTable(ServerConfig config) {
        CommandTable t = new CommandTable();
        ConnectionCommands.register(t);
        ServerCommands.register(t, config.enableDebugCommand());
        KeyCommands.register(t);
        StringCommands.register(t);
        HashCommands.register(t);
        ListCommands.register(t);
        SetCommands.register(t);
        ZSetCommands.register(t);
        StreamCommands.register(t);
        PubSubCommands.register(t);
        TransactionCommands.register(t);
        ExtensionCommands.register(t);
        ReplicationCommands.register(t);
        t.freeze(config.disabledCommands());
        return t;
    }
}
