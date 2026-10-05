package com.jredis.client;

import java.util.ArrayList;
import java.util.List;

/** Options of SET (and the TTL options of J.CAS). Immutable builder. */
public final class SetArgs {

    private final List<Object> args;

    private SetArgs(List<Object> args) {
        this.args = args;
    }

    public static SetArgs none() {
        return new SetArgs(new ArrayList<>());
    }

    private SetArgs with(Object... more) {
        List<Object> next = new ArrayList<>(args);
        for (Object o : more) {
            next.add(o);
        }
        return new SetArgs(next);
    }

    public static SetArgs nx() { return none().with("NX"); }
    public static SetArgs xx() { return none().with("XX"); }
    public static SetArgs ex(long seconds) { return none().with("EX", seconds); }
    public static SetArgs px(long millis) { return none().with("PX", millis); }

    public SetArgs andNx() { return with("NX"); }
    public SetArgs andXx() { return with("XX"); }
    public SetArgs andEx(long seconds) { return with("EX", seconds); }
    public SetArgs andPx(long millis) { return with("PX", millis); }
    public SetArgs andExAt(long unixSeconds) { return with("EXAT", unixSeconds); }
    public SetArgs andPxAt(long unixMillis) { return with("PXAT", unixMillis); }
    public SetArgs andKeepTtl() { return with("KEEPTTL"); }

    List<Object> args() {
        return args;
    }

    boolean conditional() {
        return args.contains("NX") || args.contains("XX");
    }
}
