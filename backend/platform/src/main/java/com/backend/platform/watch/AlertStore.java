package com.backend.platform.watch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jredis.client.JRedisClient;
import com.jredis.client.ScanResult;
import com.jredis.client.SetArgs;
import com.jredis.client.Transaction;

/**
 * The alerts in the session store (04 §12, D-86), where every machine's platform can read them: each firing
 * alert as {@code alert:{fingerprint}}, set anew each round and gone a minute after its watch stops; silences
 * as {@code silence:{alertname}}; and the claims by which a notification is sent once for the site,
 * {@code alertsent:{fingerprint}:{status}}, held for the repeat interval.
 */
public final class AlertStore {

    private static final String ALERT = "alert:";
    private static final String SENT = "alertsent:";
    private static final String SILENCE = "silence:";
    private static final String WATCH = "watch:";
    /** A firing alert is written each round (15 s); unwritten this long, its watch is gone, and so is it. */
    static final int LIVE_SECONDS = 60;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** An alert as stored, and whether a silence covers its name. */
    public record Stored(Alert alert, boolean silenced) { }

    public record Silence(String alertname, long untilMillis, String reason) { }

    /**
     * What one machine's watch saw in its last round (04 §12, plan item 102): each target's and peer's
     * {@code up}, the disks, the memory, the load and cores, how long the round took, and how many alerts it
     * has firing. Written each round for a minute, so a machine whose watch stopped drops out of the list.
     */
    public record View(String machine, long atMillis, double roundSeconds, List<TargetUp> targets, List<PeerUp> peers,
                       List<Disk> disks, long memoryFreeBytes, long memorySizeBytes, double load1m, int cores, int alertsFiring) {

        public record TargetUp(String job, String instance, boolean up) { }

        public record PeerUp(String peer, boolean up) { }

        public record Disk(String mount, long freeBytes, long sizeBytes) { }

        public View {
            targets = List.copyOf(targets);
            peers = List.copyOf(peers);
            disks = List.copyOf(disks);
        }
    }

    private final JRedisClient client;

    public AlertStore(JRedisClient client) {
        this.client = client;
    }

    /** What fires, written for a minute; what resolved, deleted now. */
    public void publish(Collection<Alert> firing, Collection<Alert> resolved) {
        if (firing.isEmpty() && resolved.isEmpty()) {
            return;
        }
        Transaction tx = client.multi();
        for (Alert a : firing) {
            tx.send("SET", ALERT + a.fingerprint(), json(a), "EX", Integer.toString(LIVE_SECONDS));
        }
        for (Alert a : resolved) {
            tx.send("DEL", ALERT + a.fingerprint());
        }
        tx.exec().join();
    }

    /** Every machine's firing alerts, by name then fingerprint. */
    public List<Stored> list() {
        Set<String> silenced = silencedNames();
        List<Stored> out = new ArrayList<>();
        for (String key : keys(ALERT + "*")) {
            String json = client.sync().get(key);
            Alert a = json == null ? null : alert(json);           // gone between the scan and the read: skipped
            if (a != null) {
                out.add(new Stored(a, silenced.contains(a.name())));
            }
        }
        out.sort(Comparator.comparing((Stored s) -> s.alert().name()).thenComparing(s -> s.alert().fingerprint()));
        return out;
    }

    /** This machine's view of its last round, kept a minute. */
    public void publishView(View view) {
        client.sync().set(WATCH + view.machine(), viewJson(view), SetArgs.ex(LIVE_SECONDS));
    }

    /** Every machine's view within the last minute, newest first. */
    public List<View> views() {
        List<View> out = new ArrayList<>();
        for (String key : keys(WATCH + "*")) {
            String json = client.sync().get(key);
            View v = json == null ? null : view(json);
            if (v != null) {
                out.add(v);
            }
        }
        out.sort(Comparator.comparingLong(View::atMillis).reversed().thenComparing(View::machine));
        return out;
    }

    /** @return whether this caller is the one to send: the claim was free and is now held for {@code ttlMillis} */
    public boolean claimSend(String fingerprint, String status, long ttlMillis) {
        return client.sync().set(SENT + fingerprint + ":" + status, "1", SetArgs.nx().andPx(ttlMillis));
    }

    /** The claim given up: a send that failed, or an alert that resolved. */
    public void releaseSend(String fingerprint, String status) {
        client.sync().del(SENT + fingerprint + ":" + status);
    }

    public void silence(String alertname, long hours, String reason, long nowMillis) {
        ObjectNode node = JSON.createObjectNode().put("alertname", alertname)
                .put("until", nowMillis + hours * 3_600_000L).put("reason", reason);
        client.sync().set(SILENCE + alertname, node.toString(), SetArgs.ex(hours * 3600));
    }

    /** @return whether there was one to lift */
    public boolean unsilence(String alertname) {
        return client.sync().del(SILENCE + alertname) == 1;
    }

    public List<Silence> silences() {
        List<Silence> out = new ArrayList<>();
        for (String key : keys(SILENCE + "*")) {
            String json = client.sync().get(key);
            if (json != null) {
                try {
                    JsonNode n = JSON.readTree(json);
                    out.add(new Silence(n.path("alertname").asText(), n.path("until").asLong(), n.path("reason").asText()));
                } catch (java.io.IOException e) {
                    // not ours: skipped
                }
            }
        }
        out.sort(Comparator.comparing(Silence::alertname));
        return out;
    }

    public Set<String> silencedNames() {
        Set<String> out = new HashSet<>();
        for (String key : keys(SILENCE + "*")) {
            out.add(key.substring(SILENCE.length()));
        }
        return out;
    }

    private List<String> keys(String match) {
        List<String> out = new ArrayList<>();
        String cursor = "0";
        ScanResult r;
        do {
            r = client.sync().scan(cursor, match, 100);
            out.addAll(r.keys);
            cursor = r.cursor;
        } while (!r.finished());
        return out;
    }

    static String json(Alert a) {
        ObjectNode node = JSON.createObjectNode().put("name", a.name());
        ObjectNode labels = node.putObject("labels");
        for (Map.Entry<String, String> e : a.labels().entrySet()) {
            labels.put(e.getKey(), e.getValue());
        }
        node.put("summary", a.summary()).put("runbook", a.runbook()).put("since", a.sinceMillis());
        return node.toString();
    }

    static String viewJson(View v) {
        ObjectNode n = JSON.createObjectNode().put("machine", v.machine()).put("at", v.atMillis()).put("roundSeconds", v.roundSeconds());
        var targets = n.putArray("targets");
        for (View.TargetUp t : v.targets()) {
            targets.addObject().put("job", t.job()).put("instance", t.instance()).put("up", t.up());
        }
        var peers = n.putArray("peers");
        for (View.PeerUp p : v.peers()) {
            peers.addObject().put("peer", p.peer()).put("up", p.up());
        }
        var disks = n.putArray("disks");
        for (View.Disk d : v.disks()) {
            disks.addObject().put("mount", d.mount()).put("freeBytes", d.freeBytes()).put("sizeBytes", d.sizeBytes());
        }
        n.putObject("memory").put("freeBytes", v.memoryFreeBytes()).put("sizeBytes", v.memorySizeBytes());
        n.put("load1m", v.load1m()).put("cores", v.cores()).put("alertsFiring", v.alertsFiring());
        return n.toString();
    }

    static View view(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            List<View.TargetUp> targets = new ArrayList<>();
            for (JsonNode t : n.path("targets")) {
                targets.add(new View.TargetUp(t.path("job").asText(), t.path("instance").asText(), t.path("up").asBoolean()));
            }
            List<View.PeerUp> peers = new ArrayList<>();
            for (JsonNode p : n.path("peers")) {
                peers.add(new View.PeerUp(p.path("peer").asText(), p.path("up").asBoolean()));
            }
            List<View.Disk> disks = new ArrayList<>();
            for (JsonNode d : n.path("disks")) {
                disks.add(new View.Disk(d.path("mount").asText(), d.path("freeBytes").asLong(), d.path("sizeBytes").asLong()));
            }
            return new View(n.path("machine").asText(), n.path("at").asLong(), n.path("roundSeconds").asDouble(), targets, peers, disks,
                    n.path("memory").path("freeBytes").asLong(), n.path("memory").path("sizeBytes").asLong(),
                    n.path("load1m").asDouble(), n.path("cores").asInt(), n.path("alertsFiring").asInt());
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    static Alert alert(String json) {
        try {
            JsonNode n = JSON.readTree(json);
            Map<String, String> labels = new TreeMap<>();
            n.path("labels").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
            return new Alert(n.path("name").asText(), labels, n.path("summary").asText(), n.path("runbook").asText(),
                    n.path("since").asLong());
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }
}
