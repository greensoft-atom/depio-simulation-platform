package com.jredis.server.core;

import io.netty.buffer.ByteBuf;

/** The output of the AOF-replay client: every reply is discarded. */
final class LoadingOutput implements ClientOutput {

    private Client client;

    @Override
    public void write(ByteBuf replies) {
        replies.release();
    }

    @Override
    public void close() {
    }

    @Override
    public void abort() {
    }

    @Override
    public long pendingBytes() {
        return 0;
    }

    @Override
    public void commandDone(long bytes) {
    }

    @Override
    public String remoteAddress() {
        return "aof-loader";
    }

    @Override
    public String localAddress() {
        return "aof-loader";
    }

    @Override
    public Client client() {
        return client;
    }

    @Override
    public void client(Client c) {
        this.client = c;
    }
}
