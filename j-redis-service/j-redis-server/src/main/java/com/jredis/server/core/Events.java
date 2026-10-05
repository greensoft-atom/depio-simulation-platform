package com.jredis.server.core;

/** Events delivered to the command thread through the {@link EventQueue}. */
public final class Events {

    private Events() {
    }

    /** A connection was accepted. */
    public static final class Connected {
        final ClientOutput output;

        public Connected(ClientOutput output) {
            this.output = output;
        }
    }

    /** A decoded request. */
    public static final class Command {
        final ClientOutput output;
        final byte[][] argv;

        public Command(ClientOutput output, byte[][] argv) {
            this.output = output;
            this.argv = argv;
        }
    }

    /** Malformed input: reply with the error after earlier replies, then close. */
    public static final class ProtocolError {
        final ClientOutput output;
        final String message;

        public ProtocolError(ClientOutput output, String message) {
            this.output = output;
            this.message = message;
        }
    }

    /** The client shut down its sending side: reply to what it sent, then close. */
    public static final class InputClosed {
        final ClientOutput output;

        public InputClosed(ClientOutput output) {
            this.output = output;
        }
    }

    /** The connection is gone. */
    public static final class Closed {
        final ClientOutput output;

        public Closed(ClientOutput output) {
            this.output = output;
        }
    }
}
