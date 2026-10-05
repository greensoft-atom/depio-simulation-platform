package com.jredis.server.repl;

import com.jredis.server.core.Client;

/** A replica, as its primary sees it: its connection and where it is in the stream. */
public final class ReplicaSession {

    enum State {
        /** Asked for a full sync; waits for the next snapshot. */
        WAIT_SNAPSHOT,
        /** Its image is being sent; the effects since the image's instant wait in the backlog. */
        SENDING,
        /** Sent every chunk as the batches end. */
        ONLINE
    }

    final Client client;
    final int listeningPort;
    State state = State.WAIT_SNAPSHOT;
    /** The stream offset at its image's instant. */
    long syncOffset;
    /** The offset it last said it has applied (REPLCONF ACK), and when. */
    long ackOffset;
    long lastAckMillis;
    /** Set by the command thread when the connection goes; read by the image's writer. */
    volatile boolean dropped;
    /** Set by the image's writer when the connection stopped taking bytes. */
    volatile boolean failed;

    ReplicaSession(Client client, int listeningPort) {
        this.client = client;
        this.listeningPort = listeningPort;
    }

    /** The replica's host as its connection shows it. */
    String host() {
        String a = client.out.remoteAddress();
        int colon = a.lastIndexOf(':');
        return colon < 0 ? a : a.substring(0, colon);
    }
}
