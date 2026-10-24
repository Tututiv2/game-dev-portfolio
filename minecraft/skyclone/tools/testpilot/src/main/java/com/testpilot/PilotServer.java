package com.testpilot;

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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The control server: HTTP + JSON on 127.0.0.1 only.
 *
 * <p>Request handling happens on a small pool of daemon threads; every game
 * action is marshalled onto the client thread by {@link Actions}. Long actions
 * (move, walkto, attackhold, wait) block their HTTP request until done, so a
 * second request (e.g. /state during /walkto) is served by another pool thread.
 */
public final class PilotServer {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static HttpServer server;

    private PilotServer() {
    }

    public static void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        } catch (IOException e) {
            TestPilot.LOG.error("[testpilot] could not bind 127.0.0.1:{} - control server NOT running ({})", port, e.toString());
            return;
        }
        AtomicInteger n = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(6, r -> {
            Thread t = new Thread(r, "testpilot-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", PilotServer::handle);
        server.start();
        TestPilot.LOG.info("[testpilot] control server listening on http://127.0.0.1:{}/", port);
    }

    private static void handle(HttpExchange ex) {
        JsonObject reply;
        int status = 200;
        try {
            String path = ex.getRequestURI().getPath();
            String endpoint = path.replaceAll("^/+|/+$", "").toLowerCase();
            JsonObject body = readBody(ex);
            reply = Actions.dispatch(endpoint, body);
            if (!reply.has("ok")) {
                reply.addProperty("ok", true);
            }
        } catch (PilotException e) {
            status = e.status;
            reply = error(e.getMessage());
        } catch (Throwable t) {
            status = 500;
            TestPilot.LOG.warn("[testpilot] request failed", t);
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

    private static JsonObject readBody(HttpExchange ex) throws IOException {
        JsonObject body = new JsonObject();
        String query = ex.getRequestURI().getRawQuery();
        if (query != null) {
            for (String kv : query.split("&")) {
                if (kv.isEmpty()) {
                    continue;
                }
                String[] p = kv.split("=", 2);
                String k = URLDecoder.decode(p[0], StandardCharsets.UTF_8);
                String v = p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "true";
                body.addProperty(k, v);
            }
        }
        byte[] raw = ex.getRequestBody().readAllBytes();
        String s = new String(raw, StandardCharsets.UTF_8).trim();
        if (!s.isEmpty()) {
            JsonElement el;
            try {
                el = JsonParser.parseString(s);
            } catch (RuntimeException e) {
                throw new PilotException(400, "body is not JSON: " + e.getMessage());
            }
            if (!el.isJsonObject()) {
                throw new PilotException(400, "body must be a JSON object");
            }
            for (var e : el.getAsJsonObject().entrySet()) {
                body.add(e.getKey(), e.getValue());
            }
        }
        return body;
    }

    public static JsonObject error(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", msg);
        return o;
    }
}
