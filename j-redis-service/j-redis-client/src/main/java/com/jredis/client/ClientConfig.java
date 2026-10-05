package com.jredis.client;

import io.netty.channel.EventLoopGroup;

import java.util.concurrent.Executor;

/** Immutable client settings; built with {@link JRedisClient#builder()}. */
public final class ClientConfig {

    final String host;
    final int port;
    final String localName;
    final String password;
    final String clientName;
    final EventLoopGroup eventLoopGroup;
    final int ioThreads;
    final long connectTimeoutMillis;
    final long commandTimeoutMillis;
    final long reconnectMinMillis;
    final long reconnectMaxMillis;
    final long pubsubPingMillis;
    final int commandConnections;
    final int leasePoolMax;
    final Executor callbackExecutor;
    /** Several servers to choose the primary from, or null for one address. */
    final java.util.List<java.net.InetSocketAddress> addresses;

    ClientConfig(JRedisClient.Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.localName = b.localName;
        this.password = b.password;
        this.clientName = b.clientName;
        this.eventLoopGroup = b.eventLoopGroup;
        this.ioThreads = b.ioThreads;
        this.connectTimeoutMillis = b.connectTimeoutMillis;
        this.commandTimeoutMillis = b.commandTimeoutMillis;
        this.reconnectMinMillis = b.reconnectMinMillis;
        this.reconnectMaxMillis = b.reconnectMaxMillis;
        this.pubsubPingMillis = b.pubsubPingMillis;
        this.commandConnections = b.commandConnections;
        this.leasePoolMax = b.leasePoolMax;
        this.callbackExecutor = b.callbackExecutor;
        this.addresses = b.addresses;
    }

    String address() {
        if (addresses != null) {
            StringBuilder sb = new StringBuilder("the primary of ");
            for (int i = 0; i < addresses.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(addresses.get(i).getHostString()).append(':').append(addresses.get(i).getPort());
            }
            return sb.toString();
        }
        return localName != null ? "local:" + localName : host + ":" + port;
    }
}
