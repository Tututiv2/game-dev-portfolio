package com.aisky.showcam;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP + JSON on 127.0.0.1 only (CONTRACT section 11).
 *
 * <ul>
 *   <li>{@code POST /shot} - queue a shot; it is picked up on the next rendered frame.</li>
 *   <li>{@code GET /status} (also {@code /}) - current shot, progress, camera pose, target loaded.</li>
 *   <li>{@code GET /ping} - liveness.</li>
 * </ul>
 * Requests never touch game state directly: {@code /shot} only sets a volatile
 * field the render thread consumes, {@code /status} only reads an immutable
 * snapshot the render thread publishes. So nothing here can block a frame.
 */
public final class CamServer {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private CamServer() {
    }

    public static void start(int port) {
        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        } catch (IOException e) {
            ShowCam.LOG.error("[showcam] could not bind 127.0.0.1:{} - camera server NOT running ({})", port, e.toString());
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "showcam-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", CamServer::handle);
        server.start();
        ShowCam.LOG.info("[showcam] camera server listening on http://127.0.0.1:{}/", port);
    }

    private static void handle(HttpExchange ex) {
        JsonObject reply;
        int status = 200;
        try {
            String endpoint = ex.getRequestURI().getPath().replaceAll("^/+|/+$", "").toLowerCase();
            String method = ex.getRequestMethod();
            switch (endpoint) {
                case "", "status" -> reply = statusJson();
                case "ping" -> {
                    reply = new JsonObject();
                    reply.addProperty("ok", true);
                    reply.addProperty("mod", "showcam");
                    reply.addProperty("title", Flags.TITLE);
                }
                case "shot" -> {
                    if (!method.equalsIgnoreCase("POST")) {
                        throw new BadRequest(405, "POST a JSON body to /shot");
                    }
                    reply = shot(readBody(ex));
                }
                default -> throw new BadRequest(404, "unknown endpoint /" + endpoint + " (shot, status, ping)");
            }
        } catch (BadRequest e) {
            status = e.status;
            reply = error(e.getMessage());
        } catch (Throwable t) {
            status = 500;
            ShowCam.LOG.warn("[showcam] request failed", t);
            reply = error(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        try {
            byte[] bytes = GSON.toJson(reply).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {
            // client went away
        } finally {
            ex.close();
        }
    }

    // ------------------------------------------------------------------ /shot

    static JsonObject shot(JsonObject b) {
        ShotSpec.Type type = ShotSpec.Type.parse(str(b, "type"));
        if (type == null) {
            throw new BadRequest(400, "type must be one of orbit|pushin|follow|crane|dolly|static|stop");
        }
        String player = null;
        double x = Double.NaN;
        double y = Double.NaN;
        double z = Double.NaN;
        JsonElement t = b.get("target");
        if (t != null && !t.isJsonNull()) {
            if (t.isJsonPrimitive()) {
                player = t.getAsString();
            } else if (t.isJsonObject()) {
                JsonObject to = t.getAsJsonObject();
                player = str(to, "player");
                x = num(to, "x");
                y = num(to, "y");
                z = num(to, "z");
            } else {
                throw new BadRequest(400, "target must be {\"player\":name} or {\"x\",\"y\",\"z\"}");
            }
        }
        if (player != null && player.isBlank()) {
            player = null;
        }
        if (player != null && !CamDirector.isSafeName(player)) {
            throw new BadRequest(400, "target.player must be a Minecraft name (A-Z a-z 0-9 _, 1-16 chars)");
        }
        boolean point = !Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z);
        if (type != ShotSpec.Type.STOP && player == null && !point) {
            throw new BadRequest(400, "target required: {\"player\":name} or {\"x\":..,\"y\":..,\"z\":..}");
        }
        ShotSpec spec = ShotSpec.resolve(type, player, x, y, z, num(b, "seconds"), num(b, "radius"),
                num(b, "height"), num(b, "fov"), num(b, "speed"), bool(b, "cut", false), bool(b, "clip", true));
        CamDirector.submit(spec);

        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        JsonObject a = new JsonObject();
        a.addProperty("type", spec.type.id());
        a.addProperty("target", spec.targetText());
        if (spec.type != ShotSpec.Type.STOP) {
            a.addProperty("seconds", spec.seconds);
            a.addProperty("radius", spec.radius);
            a.addProperty("height", spec.height);
            a.addProperty("fov", spec.fov);
            a.addProperty("speed", spec.speed);
            a.addProperty("cut", spec.cut);
            a.addProperty("clip", spec.clip);
        }
        r.add("accepted", a);
        return r;
    }

    // ------------------------------------------------------------------ /status

    static JsonObject statusJson() {
        CamDirector.Status s = CamDirector.status();
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("active", s.active());
        o.addProperty("inWorld", s.inWorld());
        o.addProperty("queued", CamDirector.hasPending());
        o.addProperty("title", Flags.TITLE);
        if (s.active()) {
            JsonObject shot = new JsonObject();
            shot.addProperty("type", s.shot());
            shot.addProperty("target", s.target());
            shot.addProperty("seconds", s.seconds());
            o.add("shot", shot);
            o.addProperty("elapsed", round(s.elapsed()));
            o.addProperty("progress", round(s.progress()));
            o.addProperty("done", s.done());
        } else {
            o.add("shot", null);
        }
        if (!Double.isNaN(s.x())) {
            JsonObject cam = new JsonObject();
            cam.addProperty("x", round(s.x()));
            cam.addProperty("y", round(s.y()));
            cam.addProperty("z", round(s.z()));
            cam.addProperty("yaw", round(s.yaw()));
            cam.addProperty("pitch", round(s.pitch()));
            cam.addProperty("fov", round(s.fov()));
            o.add("camera", cam);
        } else {
            o.add("camera", null);
        }
        o.addProperty("targetLoaded", s.targetLoaded());
        if (s.targetLoaded() && !Double.isNaN(s.tx())) {
            JsonObject tp = new JsonObject();
            tp.addProperty("x", round(s.tx()));
            tp.addProperty("y", round(s.ty()));
            tp.addProperty("z", round(s.tz()));
            o.add("targetPos", tp);
        } else {
            o.add("targetPos", null);
        }
        o.addProperty("note", s.note());
        return o;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject readBody(HttpExchange ex) throws IOException {
        String s = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) {
            throw new BadRequest(400, "empty body; send a JSON object");
        }
        JsonElement el;
        try {
            el = JsonParser.parseString(s);
        } catch (RuntimeException e) {
            throw new BadRequest(400, "body is not JSON: " + e.getMessage());
        }
        if (!el.isJsonObject()) {
            throw new BadRequest(400, "body must be a JSON object");
        }
        return el.getAsJsonObject();
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) {
            return Double.NaN;
        }
        try {
            double v = e.getAsDouble();
            if (Double.isInfinite(v)) {
                throw new BadRequest(400, k + " must be finite");
            }
            return v;
        } catch (NumberFormatException | UnsupportedOperationException | IllegalStateException ex) {
            throw new BadRequest(400, k + " must be a number");
        }
    }

    private static boolean bool(JsonObject o, String k, boolean dflt) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) {
            return dflt;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        String s = e.getAsString();
        return s.equals("1") || s.equalsIgnoreCase("true");
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return o;
    }

    static final class BadRequest extends RuntimeException {
        final int status;

        BadRequest(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }
}
