package com.aisky.showcontrol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.skyclone.api.Sky;
import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The show feed: every event in CONTRACT §3 shape, in a ring buffer the HTTP threads read.
 *
 * <p>Thread safety: {@link #emit} may be called from any thread (chat arrives async); everything is
 * guarded by this object's monitor, and SSE readers {@link #await} on it. Events are serialised to
 * JSON once, at emit time, so readers never touch live objects.
 *
 * <p>Ids: per-source sequence, seeded from the wall clock at boot so they keep increasing across a
 * server restart and a client's Last-Event-ID from the previous run never hides new events.
 */
final class EventBus {

    record Entry(long id, String json) {}

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeSpecialFloatingPointValues().create();

    private final ArrayDeque<Entry> ring = new ArrayDeque<>();
    private final int capacity;
    private long nextId;
    private long lastId;

    EventBus(int capacity) {
        this.capacity = capacity;
        this.nextId = System.currentTimeMillis();
        this.lastId = nextId - 1;
    }

    /** Start building an event of {@code type}. Nothing is published until {@link Ev#emit()}. */
    Ev ev(String type) { return new Ev(this, type); }

    synchronized long emit(JsonObject body) {
        long id = nextId++;
        JsonObject ordered = new JsonObject();
        ordered.addProperty("id", id);
        for (Map.Entry<String, JsonElement> e : body.entrySet()) ordered.add(e.getKey(), e.getValue());
        ring.addLast(new Entry(id, GSON.toJson(ordered)));
        while (ring.size() > capacity) ring.removeFirst();
        lastId = id;
        notifyAll();
        return id;
    }

    synchronized long lastId() { return lastId; }

    /** Events with id greater than {@code after}, oldest first, at most {@code limit}. */
    synchronized List<Entry> since(long after, int limit) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ring) {
            if (e.id() > after) {
                out.add(e);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /** Blocks up to {@code timeoutMs} for an event newer than {@code after}; returns whatever is new (maybe nothing). */
    synchronized List<Entry> await(long after, long timeoutMs) throws InterruptedException {
        if (lastId <= after) wait(timeoutMs);
        return since(after, Integer.MAX_VALUE);
    }

    /** Wake every SSE reader (shutdown). */
    synchronized void wakeAll() { notifyAll(); }

    // ---- builder ---------------------------------------------------------------------------

    static final class Ev {
        private final EventBus bus;
        private final JsonObject o = new JsonObject();
        private final JsonArray players = new JsonArray();
        private final JsonObject data = new JsonObject();
        private int drama = 1;
        private String text = "";
        private JsonObject loc;

        private Ev(EventBus bus, String type) {
            this.bus = bus;
            o.addProperty("type", type);
        }

        Ev player(String name) { if (name != null) players.add(name); return this; }

        Ev players(Iterable<String> names) { for (String n : names) player(n); return this; }

        Ev drama(int d) { drama = Math.max(0, Math.min(10, d)); return this; }

        Ev text(String t) { text = t == null ? "" : t; return this; }

        Ev loc(Location l) { loc = locJson(l); return this; }

        Ev loc(JsonObject l) { loc = l; return this; }

        Ev data(String k, String v) { data.add(k, v == null ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(v)); return this; }

        Ev data(String k, Number v) { data.add(k, v == null ? com.google.gson.JsonNull.INSTANCE : new JsonPrimitive(v)); return this; }

        Ev data(String k, boolean v) { data.addProperty(k, v); return this; }

        Ev data(String k, JsonElement v) { data.add(k, v); return this; }

        long emit() {
            JsonObject body = new JsonObject();
            body.addProperty("t", System.currentTimeMillis());
            body.addProperty("src", "server");
            body.add("type", o.get("type"));
            body.add("players", players);
            body.addProperty("drama", drama);
            body.addProperty("text", text);
            if (loc != null) body.add("loc", loc);
            body.add("data", data);
            return bus.emit(body);
        }
    }

    /** {"world": island id (or world name), "x","y","z"} with one decimal. Null-safe. */
    static JsonObject locJson(Location l) {
        if (l == null || l.getWorld() == null) return null;
        JsonObject j = new JsonObject();
        String world = l.getWorld().getName();
        try {
            String island = Sky.worlds().islandOf(l.getWorld());
            if (island != null) world = island;
        } catch (RuntimeException ignored) { }
        j.addProperty("world", world);
        j.addProperty("x", round1(l.getX()));
        j.addProperty("y", round1(l.getY()));
        j.addProperty("z", round1(l.getZ()));
        return j;
    }

    static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
