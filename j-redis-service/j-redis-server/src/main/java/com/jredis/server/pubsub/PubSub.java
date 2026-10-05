package com.jredis.server.pubsub;

import com.jredis.common.Glob;
import com.jredis.common.RespWriter;
import com.jredis.server.core.ByteKey;
import com.jredis.server.core.Client;
import com.jredis.server.core.Engine;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Channel and pattern subscriptions. Delivery is at-most-once and ordered: every frame for a
 * subscriber is appended by the command thread in PUBLISH order. Command-thread only.
 */
public final class PubSub {

    private static final byte[] MESSAGE = ascii("message");
    private static final byte[] PMESSAGE = ascii("pmessage");
    private static final byte[] SUBSCRIBE = ascii("subscribe");
    private static final byte[] UNSUBSCRIBE = ascii("unsubscribe");
    private static final byte[] PSUBSCRIBE = ascii("psubscribe");
    private static final byte[] PUNSUBSCRIBE = ascii("punsubscribe");

    private static final class PatternSub {
        final byte[] pattern;
        final Client client;

        PatternSub(byte[] pattern, Client client) {
            this.pattern = pattern;
            this.client = client;
        }
    }

    private final Engine engine;
    private final Map<ByteKey, ArrayList<Client>> channels = new HashMap<>();
    private final ArrayList<PatternSub> patterns = new ArrayList<>();

    public PubSub(Engine engine) {
        this.engine = engine;
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    public int channelCount() {
        return channels.size();
    }

    /** Distinct patterns, as Redis counts them (two clients on "news.*" are one pattern). */
    public int patternCount() {
        java.util.Set<ByteKey> distinct = new java.util.HashSet<>();
        for (PatternSub p : patterns) {
            distinct.add(new ByteKey(p.pattern));
        }
        return distinct.size();
    }

    private void confirm(Client c, byte[] kind, byte[] name) {
        ByteBuf out = engine.replyBuffer(c);
        RespWriter.arrayHeader(out, 3);
        RespWriter.bulk(out, kind);
        if (name == null) {
            RespWriter.nullBulk(out);
        } else {
            RespWriter.bulk(out, name);
        }
        RespWriter.integer(out, c.subscriptionCount());
    }

    public void subscribe(Client c, byte[] channel) {
        ByteKey k = new ByteKey(channel);
        if (c.channels.add(k)) {
            channels.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
        }
        confirm(c, SUBSCRIBE, channel);
    }

    public void unsubscribe(Client c, byte[] channel, boolean notify) {
        ByteKey k = new ByteKey(channel);
        if (c.channels.remove(k)) {
            ArrayList<Client> list = channels.get(k);
            if (list != null) {
                list.remove(c);
                if (list.isEmpty()) {
                    channels.remove(k);
                }
            }
        }
        if (notify) {
            confirm(c, UNSUBSCRIBE, channel);
        }
    }

    public void unsubscribeAll(Client c, boolean notify) {
        if (c.channels.isEmpty()) {
            if (notify) {
                confirm(c, UNSUBSCRIBE, null);
            }
            return;
        }
        for (ByteKey k : new ArrayList<>(c.channels)) {
            unsubscribe(c, k.bytes, notify);
        }
    }

    public void psubscribe(Client c, byte[] pattern) {
        if (c.patterns.add(new ByteKey(pattern))) {
            patterns.add(new PatternSub(pattern, c));
        }
        confirm(c, PSUBSCRIBE, pattern);
    }

    public void punsubscribe(Client c, byte[] pattern, boolean notify) {
        if (c.patterns.remove(new ByteKey(pattern))) {
            Iterator<PatternSub> it = patterns.iterator();
            while (it.hasNext()) {
                PatternSub p = it.next();
                if (p.client == c && java.util.Arrays.equals(p.pattern, pattern)) {
                    it.remove();
                    break;
                }
            }
        }
        if (notify) {
            confirm(c, PUNSUBSCRIBE, pattern);
        }
    }

    public void punsubscribeAll(Client c, boolean notify) {
        if (c.patterns.isEmpty()) {
            if (notify) {
                confirm(c, PUNSUBSCRIBE, null);
            }
            return;
        }
        for (ByteKey k : new ArrayList<>(c.patterns)) {
            punsubscribe(c, k.bytes, notify);
        }
    }

    /** Removes every subscription of a disconnecting client, without replies. */
    public void removeClient(Client c) {
        unsubscribeAll(c, false);
        punsubscribeAll(c, false);
    }

    /** @return the number of deliveries (a client matching by channel and pattern counts twice) */
    public int publish(byte[] channel, byte[] message) {
        int receivers = 0;
        ArrayList<Client> subs = channels.get(new ByteKey(channel));
        if (subs != null && !subs.isEmpty()) {
            ByteBuf frame = Unpooled.buffer(32 + channel.length + message.length);
            RespWriter.arrayHeader(frame, 3);
            RespWriter.bulk(frame, MESSAGE);
            RespWriter.bulk(frame, channel);
            RespWriter.bulk(frame, message);
            for (Client c : subs) {
                ByteBuf out = engine.replyBuffer(c);
                out.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
                receivers++;
            }
            frame.release();
        }
        for (PatternSub p : patterns) {
            if (Glob.match(p.pattern, channel)) {
                ByteBuf out = engine.replyBuffer(p.client);
                RespWriter.arrayHeader(out, 4);
                RespWriter.bulk(out, PMESSAGE);
                RespWriter.bulk(out, p.pattern);
                RespWriter.bulk(out, channel);
                RespWriter.bulk(out, message);
                receivers++;
            }
        }
        return receivers;
    }

    /** Active channels (with at least one subscriber) matching the pattern, or all if null. */
    public List<byte[]> channels(byte[] pattern) {
        List<byte[]> out = new ArrayList<>();
        for (ByteKey k : channels.keySet()) {
            if (pattern == null || Glob.match(pattern, k.bytes)) {
                out.add(k.bytes);
            }
        }
        return out;
    }

    public int numsub(byte[] channel) {
        ArrayList<Client> subs = channels.get(new ByteKey(channel));
        return subs == null ? 0 : subs.size();
    }
}
