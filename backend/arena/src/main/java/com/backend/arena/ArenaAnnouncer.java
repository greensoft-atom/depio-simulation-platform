package com.backend.arena;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.backend.handoff.ArenaDirectory;
import com.backend.protocol.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tells the directory this arena is alive and how full it is
 * (docs detailed-design/04-platform-services.md §3).
 *
 * The arena announces itself rather than being registered by an operator, because the fact
 * worth publishing — "I am accepting connections right now" — is only knowable here. A
 * configuration file can say an arena exists; only the arena can say it is working.
 *
 * A failed announcement is logged and otherwise ignored. The entry expires by itself, so
 * the failure mode is already correct: an arena that cannot reach the store stops being
 * offered players, which is exactly what should happen.
 */
public final class ArenaAnnouncer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ArenaAnnouncer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ArenaDirectory directory;
    private final String name;
    private final String host;
    private final int port;
    private final RoomRegistry registry;
    private final ScheduledExecutorService scheduler;

    private final boolean tls;

    public ArenaAnnouncer(ArenaDirectory directory, String name, String host, int port,
                          RoomRegistry registry) {
        this(directory, name, host, port, registry, false);
    }

    public ArenaAnnouncer(ArenaDirectory directory, String name, String host, int port,
                          RoomRegistry registry, boolean tls) {
        this.tls = tls;
        this.directory = directory;
        this.name = name;
        this.host = host;
        this.port = port;
        this.registry = registry;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "arena-announce");
            t.setDaemon(true);          // never the reason a process refuses to exit
            return t;
        });
    }

    public void start() {
        // An operator's commands (04 §10): heard before the first announcement names the rooms.
        directory.listen(name, this::command).join();
        announceOnce();                 // immediately, so the arena is usable without a first delay
        scheduler.scheduleAtFixedRate(this::announceOnce,
                ArenaDirectory.REFRESH_SECONDS, ArenaDirectory.REFRESH_SECONDS, TimeUnit.SECONDS);
        log.info("announcing as {} at {}:{} every {}s", name, host, port,
                ArenaDirectory.REFRESH_SECONDS);
    }

    private void announceOnce() {
        try {
            // Rooms too: a made match needs a room of its own (04 §4). The rooms are listed before
            // they are counted: one made in between is then counted and still promised, a room's
            // place held twice for a few seconds, never counted by neither (D-42).
            List<RoomThread.RoomView> views = registry.views();
            List<String> open = new ArrayList<>();
            for (RoomThread.RoomView v : views) {
                if (v.matchUid() != null) {
                    open.add(v.matchUid());
                }
            }
            directory.announce(new ArenaDirectory.Endpoint(name, host, port, registry.totalPlayers(),
                    registry.capacity(), tls, registry.roomCount(), registry.maxRooms()), roomList(views), open).join();
        } catch (RuntimeException e) {
            log.warn("could not announce: {}", e.toString());
        }
    }

    /** The rooms, for an operator's list (04 §10): each one's name, players, and a match's id, mode and stage. */
    private String roomList(List<RoomThread.RoomView> views) {
        ArrayNode rooms = JSON.createArrayNode();
        for (RoomThread.RoomView v : views) {
            ObjectNode room = rooms.addObject().put("room", v.room()).put("players", v.players())
                    .put("mode", v.mode()).put("stage", v.stage());
            if (v.matchUid() != null) {
                room.put("matchUid", v.matchUid());
            }
        }
        return rooms.toString();
    }

    /**
     * An operator's command (04 §10): close a room, or take a player out, both with {@code Kick(7)}.
     * Anything else is logged and ignored: the channel is the operator's, and a command this build
     * does not know is one from a newer platform.
     */
    void command(String text) {
        JsonNode cmd;
        try {
            cmd = JSON.readTree(text);
        } catch (java.io.IOException malformed) {
            log.warn("ignoring an operator's command that is not JSON");
            return;
        }
        switch (cmd == null ? "" : cmd.path("cmd").asText()) {
            case "close" -> {
                String room = cmd.path("room").asText();
                log.info("an operator closes {}: {}", room,
                        registry.closeRoom(room, Wire.KICK_REMOVED) ? "closing" : "no such room here");
            }
            case "kick" -> registry.remove(cmd.path("player").asLong(), cmd.path("ban").asBoolean());
            default -> log.warn("ignoring an operator's command this build does not know: {}", text);
        }
    }

    /**
     * Withdraws immediately rather than leaving players to be sent here for the TTL.
     *
     * After any announcement already under way, not before: the interrupt does not stop one
     * (a join is not interruptible), and one that reached the store after the withdrawal put
     * the entry back, so players were sent to a stopping arena until it expired.
     */
    @Override
    public void close() {
        directory.stopListening(name);
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("an announcement is still under way; withdrawing regardless");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            directory.withdraw(name).join();
        } catch (RuntimeException e) {
            log.warn("could not withdraw: {}", e.toString());
        }
    }
}
