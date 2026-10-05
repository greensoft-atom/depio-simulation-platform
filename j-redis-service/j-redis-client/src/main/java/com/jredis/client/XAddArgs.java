package com.jredis.client;

import java.util.ArrayList;
import java.util.List;

/** Options of XADD: a trim after the add, and NOMKSTREAM. Immutable builder, as {@link SetArgs}. */
public final class XAddArgs {

    private final List<Object> args;

    private XAddArgs(List<Object> args) {
        this.args = args;
    }

    public static XAddArgs none() {
        return new XAddArgs(new ArrayList<>());
    }

    private XAddArgs with(Object... more) {
        List<Object> next = new ArrayList<>(args);
        for (Object o : more) {
            next.add(o);
        }
        return new XAddArgs(next);
    }

    /** Keeps at most {@code n} entries once this one is in. */
    public static XAddArgs maxLen(long n) { return none().with("MAXLEN", n); }

    /** Drops the entries before {@code id} once this one is in: retention by age, with an ID made from a time. */
    public static XAddArgs minId(String id) { return none().with("MINID", id); }

    /** The trim as {@code ~}: Redis may trim less; j-redis trims exactly either way. */
    public XAddArgs approximately() {
        List<Object> next = new ArrayList<>(args);
        for (int i = 0; i < next.size(); i++) {
            if ("MAXLEN".equals(next.get(i)) || "MINID".equals(next.get(i))) {
                next.add(i + 1, "~");
                break;
            }
        }
        return new XAddArgs(next);
    }

    /** Adds nothing, and answers null, if the stream does not exist. */
    public XAddArgs noMkStream() { return with("NOMKSTREAM"); }

    List<Object> args() {
        return args;
    }
}
