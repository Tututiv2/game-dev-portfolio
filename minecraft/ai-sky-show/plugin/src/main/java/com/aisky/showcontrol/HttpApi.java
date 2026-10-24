package com.aisky.showcontrol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CONTRACT §4: the show's HTTP API on 127.0.0.1:25642, JDK {@code com.sun.net.httpserver} only.
 *
 * <pre>
 *   GET  /events            SSE feed. Honors Last-Event-ID (header, or ?lastEventId=). Heartbeat comment.
 *   GET  /events/since?id=N JSON array of events with id &gt; N (ring buffer). &amp;limit= optional.
 *   GET  /players           JSON array, one row per player seen this run.
 *   GET  /state/&lt;name&gt;      the structured state of one player (never throws: offline = online:false).
 *   POST /cmd {"cmd":"..."} console command on the main thread -&gt; {"ok","output"}.
 *   GET  /health            {"ok":true,"episode":{...},"tps":..}
 * </pre>
 * HTTP threads never touch Bukkit: they read the bus and the snapshots; /cmd hops to the main thread.
 */
final class HttpApi {

    private final ShowControlPlugin plugin;
    private HttpServer server;
    private ExecutorService pool;
    private volatile boolean running;

    HttpApi(ShowControlPlugin plugin) { this.plugin = plugin; }

    void start() throws IOException {
        ShowConfig c = plugin.config();
        server = HttpServer.create(new InetSocketAddress(c.httpHost, c.httpPort), 64);
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ShowControl-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::route);
        running = true;
        server.start();
        plugin.getLogger().info("ShowControl HTTP on http://" + c.httpHost + ":" + c.httpPort);
    }

    void stop() {
        running = false;
        plugin.bus().wakeAll();
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod().toUpperCase();
            if (method.equals("OPTIONS")) { send(ex, 204, "text/plain", ""); return; }
            if (path.equals("/events") && method.equals("GET")) { sse(ex); return; }
            if (path.equals("/events/since") && method.equals("GET")) { since(ex); return; }
            if (path.equals("/players") && method.equals("GET")) { send(ex, 200, json(), plugin.snapshots().players()); return; }
            if (path.startsWith("/state/") && method.equals("GET")) {
                String name = URLDecoder.decode(path.substring("/state/".length()), StandardCharsets.UTF_8).trim();
                if (name.isEmpty()) { error(ex, 400, "missing player name"); return; }
                send(ex, 200, json(), plugin.snapshots().state(name));
                return;
            }
            if (path.equals("/cmd")) {
                if (!method.equals("POST")) { error(ex, 405, "POST {\"cmd\":\"...\"}"); return; }
                cmd(ex);
                return;
            }
            if (path.equals("/health") || path.equals("/")) { health(ex); return; }
            error(ex, 404, "no route " + path);
        } catch (Throwable t) {
            try { error(ex, 500, t.toString()); } catch (Throwable ignored) { }
        } finally {
            ex.close();
        }
    }

    private static String json() { return "application/json; charset=utf-8"; }

    // ---- SSE ------------------------------------------------------------------------------

    private void sse(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        long last = -1;
        String hdr = ex.getRequestHeaders().getFirst("Last-Event-ID");
        String qid = q.getOrDefault("lastEventId", q.get("since"));
        try {
            if (hdr != null && !hdr.isBlank()) last = Long.parseLong(hdr.trim());
            else if (qid != null) last = Long.parseLong(qid.trim());
        } catch (NumberFormatException ignored) { }
        // No id at all = only what happens from now on.
        if (last < 0) last = plugin.bus().lastId();

        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        write(out, "retry: 2000\n: ShowControl feed, last id " + plugin.bus().lastId() + "\n\n");
        long hb = plugin.config().heartbeatSeconds * 1000L;
        long lastWrite = System.currentTimeMillis();
        try {
            while (running) {
                List<EventBus.Entry> batch = plugin.bus().await(last, hb);
                if (!batch.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (EventBus.Entry e : batch) {
                        sb.append("id: ").append(e.id()).append('\n').append("data: ").append(e.json()).append("\n\n");
                        last = e.id();
                    }
                    write(out, sb.toString());
                    lastWrite = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastWrite >= hb - 50) {
                    write(out, ": hb " + System.currentTimeMillis() + "\n\n");
                    lastWrite = System.currentTimeMillis();
                }
            }
        } catch (IOException | InterruptedException closed) {
            // client went away / shutdown
        }
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void since(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex);
        long id;
        int limit;
        try {
            id = Long.parseLong(q.getOrDefault("id", "0"));
            limit = Integer.parseInt(q.getOrDefault("limit", "5000"));
        } catch (NumberFormatException e) { error(ex, 400, "id and limit must be numbers"); return; }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (EventBus.Entry e : plugin.bus().since(id, Math.max(1, limit))) {
            if (!first) sb.append(',');
            sb.append(e.json());
            first = false;
        }
        sb.append(']');
        send(ex, 200, json(), sb.toString());
    }

    // ---- cmd / health ---------------------------------------------------------------------

    private void cmd(HttpExchange ex) throws IOException {
        String body;
        try (InputStream in = ex.getRequestBody()) { body = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        String cmd;
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            cmd = o.has("cmd") ? o.get("cmd").getAsString() : null;
        } catch (RuntimeException e) { error(ex, 400, "body must be {\"cmd\":\"...\"}"); return; }
        if (cmd == null || cmd.isBlank()) { error(ex, 400, "empty cmd"); return; }
        CommandRunner.Result r = plugin.runner().runFromAnyThread(cmd, 15_000);
        JsonObject o = new JsonObject();
        o.addProperty("ok", r.ok());
        o.addProperty("output", r.output());
        send(ex, 200, json(), EventBus.GSON.toJson(o));
    }

    private void health(HttpExchange ex) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.add("episode", plugin.episode().json());
        o.addProperty("tps", Math.round(plugin.snapshots().tps() * 10) / 10.0);
        o.addProperty("players_online", plugin.snapshots().online());
        o.addProperty("last_event_id", plugin.bus().lastId());
        o.addProperty("camera", plugin.camera().account());
        o.addProperty("camera_shot", plugin.camera().status());
        send(ex, 200, json(), EventBus.GSON.toJson(o));
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String part : q.split("&")) {
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i);
            String v = i < 0 ? "" : part.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void error(HttpExchange ex, int code, String msg) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        send(ex, code, json(), EventBus.GSON.toJson(o));
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Last-Event-ID");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (code == 204) { ex.sendResponseHeaders(204, -1); return; }
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}
