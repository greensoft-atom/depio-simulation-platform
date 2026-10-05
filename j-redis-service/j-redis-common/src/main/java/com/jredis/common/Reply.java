package com.jredis.common;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A decoded RESP2 reply. Immutable. */
public abstract class Reply {

    public enum Type { SIMPLE, ERROR, INTEGER, BULK, ARRAY, NULL }

    public static final Reply OK = new SimpleReply("OK");
    public static final Reply NULL_BULK = new NullReply(false);
    public static final Reply NULL_ARRAY = new NullReply(true);
    public static final Reply EMPTY_ARRAY = new ArrayReply(Collections.<Reply>emptyList());

    public abstract Type type();

    public boolean isNull() {
        return false;
    }

    public boolean isError() {
        return false;
    }

    /** SIMPLE/BULK as UTF-8 text, INTEGER as decimal, NULL as null, ERROR as its message. */
    public String asString() {
        throw new IllegalStateException("reply of type " + type() + " is not a string");
    }

    public byte[] asBytes() {
        throw new IllegalStateException("reply of type " + type() + " is not a bulk string");
    }

    public long asLong() {
        throw new IllegalStateException("reply of type " + type() + " is not an integer");
    }

    public double asDouble() {
        return NumberCodec.parseDouble(asBytes());
    }

    /** ARRAY elements; NULL gives null. */
    public List<Reply> asList() {
        throw new IllegalStateException("reply of type " + type() + " is not an array");
    }

    public static Reply simple(String s) {
        return "OK".equals(s) ? OK : new SimpleReply(s);
    }

    public static Reply integer(long v) {
        return new IntegerReply(v);
    }

    public static Reply bulk(byte[] b) {
        return new BulkReply(b);
    }

    public static Reply error(String message) {
        return new ErrorReply(message);
    }

    public static Reply array(List<Reply> elements) {
        return new ArrayReply(elements);
    }

    // ------------------------------------------------------------------ implementations

    public static final class SimpleReply extends Reply {
        private final String value;

        SimpleReply(String value) {
            this.value = value;
        }

        @Override
        public Type type() {
            return Type.SIMPLE;
        }

        @Override
        public String asString() {
            return value;
        }

        @Override
        public byte[] asBytes() {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public long asLong() {
            return NumberCodec.parseLong(asBytes());
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public static final class ErrorReply extends Reply {
        private final String message;

        public ErrorReply(String message) {
            this.message = message;
        }

        @Override
        public Type type() {
            return Type.ERROR;
        }

        @Override
        public boolean isError() {
            return true;
        }

        @Override
        public String asString() {
            return message;
        }

        /** The first word of the error, e.g. ERR, WRONGTYPE, OOM. */
        public String prefix() {
            int sp = message.indexOf(' ');
            return sp < 0 ? message : message.substring(0, sp);
        }

        @Override
        public String toString() {
            return "(error) " + message;
        }
    }

    public static final class IntegerReply extends Reply {
        private final long value;

        IntegerReply(long value) {
            this.value = value;
        }

        @Override
        public Type type() {
            return Type.INTEGER;
        }

        @Override
        public long asLong() {
            return value;
        }

        @Override
        public String asString() {
            return Long.toString(value);
        }

        @Override
        public byte[] asBytes() {
            return NumberCodec.toBytes(value);
        }

        @Override
        public String toString() {
            return "(integer) " + value;
        }
    }

    public static final class BulkReply extends Reply {
        private final byte[] value;

        BulkReply(byte[] value) {
            this.value = value;
        }

        @Override
        public Type type() {
            return Type.BULK;
        }

        @Override
        public byte[] asBytes() {
            return value;
        }

        @Override
        public String asString() {
            return new String(value, StandardCharsets.UTF_8);
        }

        @Override
        public long asLong() {
            return NumberCodec.parseLong(value);
        }

        @Override
        public String toString() {
            return "\"" + Bytes.printable(value, 256) + "\"";
        }
    }

    public static final class ArrayReply extends Reply {
        private final List<Reply> elements;

        public ArrayReply(List<Reply> elements) {
            this.elements = Collections.unmodifiableList(elements);
        }

        @Override
        public Type type() {
            return Type.ARRAY;
        }

        @Override
        public List<Reply> asList() {
            return elements;
        }

        /** Convenience: every element as a string (nulls stay null). */
        public List<String> asStrings() {
            List<String> out = new ArrayList<>(elements.size());
            for (Reply r : elements) {
                out.add(r.isNull() ? null : r.asString());
            }
            return out;
        }

        @Override
        public String toString() {
            return elements.toString();
        }
    }

    public static final class NullReply extends Reply {
        private final boolean array;

        NullReply(boolean array) {
            this.array = array;
        }

        @Override
        public Type type() {
            return Type.NULL;
        }

        @Override
        public boolean isNull() {
            return true;
        }

        /** True for {@code *-1} (null array), false for {@code $-1} (null bulk). */
        public boolean isNullArray() {
            return array;
        }

        @Override
        public String asString() {
            return null;
        }

        @Override
        public byte[] asBytes() {
            return null;
        }

        @Override
        public List<Reply> asList() {
            return null;
        }

        @Override
        public String toString() {
            return "(nil)";
        }
    }
}
