package com.auraqol.shadow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/**
 * The shared golden fixtures (PROTOCOL.md): every {@code .hex} must decode to
 * its {@code .json} fields and re-encode to the identical bytes. The server's
 * suite runs the same files; if either codec drifts by one byte, one of the two
 * suites fails before a player ever sees it.
 *
 * <p>JSON numbers are read as exact longs (one fixture is 2^53 + 1, which a
 * double would round).
 */
class CodecFixturesTest {

    private static final Path DIR = Path.of("src/test/resources/shadowlive");
    /** The server repo's own copy, if this checkout sits beside it. */
    private static final Path SERVER_DIR = Path.of("../Minecraft game im making/shadow-live/fixtures");

    private record Fixture(String name, String direction, int type, String msgName, boolean decodeOnly,
                           boolean expectError, JsonObject fields, byte[] bytes) {
    }

    private static List<Fixture> fixtures() throws IOException {
        List<Fixture> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path json : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".json")).sorted()::iterator) {
                String base = json.getFileName().toString().replace(".json", "");
                JsonObject o = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
                String hex = Files.readString(DIR.resolve(base + ".hex"), StandardCharsets.UTF_8).trim();
                out.add(new Fixture(base, o.get("direction").getAsString(), o.get("type").getAsInt(),
                        o.get("name").getAsString(),
                        o.has("decodeOnly") && o.get("decodeOnly").getAsBoolean(),
                        o.has("expectError") && o.get("expectError").getAsBoolean(),
                        o.getAsJsonObject("fields"), HexFormat.of().parseHex(hex)));
            }
        }
        return out;
    }

    @Test
    void thereAreFixtures() throws IOException {
        assertTrue(fixtures().size() >= 26, "fixtures missing from " + DIR.toAbsolutePath());
    }

    @Test
    void everyFixtureDecodesToItsFieldsAndReEncodesByteForByte() throws IOException {
        int checked = 0;
        for (Fixture f : fixtures()) {
            boolean s2c = f.direction().equals("s2c");
            if (f.expectError()) {
                assertThrows(Wire.DecodeException.class,
                        () -> decode(f.bytes(), s2c), f.name() + " must be a clean decode error");
                checked++;
                continue;
            }
            Msg got;
            try {
                got = decode(f.bytes(), s2c);
            } catch (Wire.DecodeException e) {
                fail(f.name() + " failed to decode: " + e.getMessage());
                return;
            }
            if (f.msgName().equals("UNKNOWN")) {
                Msg.Unknown u = assertInstanceOf(Msg.Unknown.class, got, f.name());
                assertEquals(f.type(), u.type(), f.name());
                checked++;
                continue;
            }
            assertEquals(f.type(), got.type(), f.name() + " type");
            Msg expected = build(f.msgName(), f.fields());
            assertEquals(expected, got, f.name() + " decoded fields");
            if (!f.decodeOnly()) {
                assertArrayEquals(f.bytes(), Codec.encode(got), f.name() + " re-encode (decoded)");
                assertArrayEquals(f.bytes(), Codec.encode(expected), f.name() + " re-encode (from json)");
            }
            checked++;
        }
        assertEquals(fixtures().size(), checked);
    }

    /** If the server repo is beside this one, its fixtures and ours must be the same bytes. */
    @Test
    void fixturesHaveNotDriftedFromTheServerCopy() throws IOException {
        if (!Files.isDirectory(SERVER_DIR)) {
            return; // not checked out side by side; nothing to compare
        }
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.list(SERVER_DIR)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                Path ours = DIR.resolve(p.getFileName().toString());
                if (!Files.exists(ours)) {
                    problems.add("missing here: " + p.getFileName());
                } else if (!java.util.Arrays.equals(Files.readAllBytes(p), Files.readAllBytes(ours))) {
                    problems.add("differs: " + p.getFileName());
                }
            }
        }
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile).sorted()::iterator) {
                if (!Files.exists(SERVER_DIR.resolve(p.getFileName().toString()))) {
                    problems.add("missing in the server repo: " + p.getFileName());
                }
            }
        }
        assertTrue(problems.isEmpty(), "fixtures drifted (copy shadow-live/fixtures into src/test/resources/shadowlive): "
                + problems);
    }

    @Test
    void unknownTypeIsIgnoredNotAnError() throws Wire.DecodeException {
        Msg m = Codec.decodeS2C(HexFormat.of().parseHex("6301010203"));
        assertInstanceOf(Msg.Unknown.class, m);
        assertFalse(m instanceof Msg.Area);
    }

    // ------------------------------------------------------------ json -> Msg

    private static Msg decode(byte[] b, boolean s2c) throws Wire.DecodeException {
        return s2c ? Codec.decodeS2C(b) : Codec.decodeC2S(b);
    }

    private static Msg build(String name, JsonObject f) {
        return switch (name) {
            case "WELCOME" -> new Msg.Welcome(l(f, "protocol"), f.get("supported").getAsBoolean(), l(f, "serverTimeMs"),
                    strings(f.getAsJsonArray("features")), l(f, "flushMs"), l(f, "lookupsPerMinute"));
            case "SNAPSHOT" -> {
                List<Msg> frames = new ArrayList<>();
                for (JsonElement e : f.getAsJsonArray("frames")) {
                    JsonObject o = e.getAsJsonObject();
                    frames.add(build(o.get("name").getAsString(), o.getAsJsonObject("fields")));
                }
                yield new Msg.Snapshot(List.copyOf(frames));
            }
            case "BREAKS" -> {
                List<Msg.BlockCount> per = new ArrayList<>();
                for (JsonElement e : f.getAsJsonArray("perBlock")) {
                    per.add(new Msg.BlockCount(s(e.getAsJsonObject(), "blockKey"), l(e.getAsJsonObject(), "count")));
                }
                yield new Msg.Breaks(l(f, "count"), l(f, "valueCents"), List.copyOf(per));
            }
            case "COMBAT" -> {
                List<Msg.MobCount> per = new ArrayList<>();
                for (JsonElement e : f.getAsJsonArray("perMob")) {
                    per.add(new Msg.MobCount(s(e.getAsJsonObject(), "mobKey"), l(e.getAsJsonObject(), "count")));
                }
                yield new Msg.Combat(l(f, "kills"), l(f, "valueCents"), List.copyOf(per));
            }
            case "INCOME" -> new Msg.Income(s(f, "sourceId"), s(f, "displayName"), l(f, "amountCents"),
                    l(f, "serverTimeMs"));
            case "BALANCE" -> {
                List<Msg.Extra> ex = new ArrayList<>();
                for (JsonElement e : f.getAsJsonArray("extras")) {
                    ex.add(new Msg.Extra(s(e.getAsJsonObject(), "key"), s(e.getAsJsonObject(), "display")));
                }
                yield new Msg.Balance(l(f, "balanceCents"), List.copyOf(ex));
            }
            case "PROGRESS" -> new Msg.Progress(s(f, "rankDisplay"), s(f, "nextRankDisplay"), l(f, "nextRankCostCents"),
                    l(f, "prestige"), l(f, "blocksMinedTotal"));
            case "BOOSTS" -> {
                List<Msg.Boost> bs = new ArrayList<>();
                for (JsonElement e : f.getAsJsonArray("boosts")) {
                    JsonObject o = e.getAsJsonObject();
                    bs.add(new Msg.Boost(s(o, "id"), s(o, "name"), s(o, "multiplierText"), l(o, "expiresAtMs")));
                }
                yield new Msg.Boosts(List.copyOf(bs));
            }
            case "SKILL" -> new Msg.Skill(s(f, "skillId"), s(f, "displayName"), l(f, "level"), l(f, "xpInLevel"),
                    l(f, "xpForLevel"));
            case "CONTRACT" -> new Msg.Contract(l(f, "state"), s(f, "target"), l(f, "tier"), l(f, "progressPermille"));
            case "AREA" -> new Msg.Area(s(f, "name"));
            case "RESPONSE" -> {
                long status = l(f, "status");
                if (status != Msg.Response.OK) {
                    yield Msg.Response.status(l(f, "reqId"), status, l(f, "retryAfterMs"));
                }
                List<Msg.Row> rows = new ArrayList<>();
                for (JsonElement e : f.getAsJsonArray("rows")) {
                    JsonObject o = e.getAsJsonObject();
                    rows.add(new Msg.Row(s(o, "group"), s(o, "label"), s(o, "value")));
                }
                yield new Msg.Response(l(f, "reqId"), status, l(f, "retryAfterMs"), s(f, "name"), s(f, "uuid"),
                        f.get("online").getAsBoolean(), List.copyOf(rows));
            }
            case "HELLO" -> new Msg.Hello(l(f, "protocol"), s(f, "modVersion"), strings(f.getAsJsonArray("wants")));
            case "REQUEST" -> new Msg.Request(l(f, "reqId"), l(f, "kind"), s(f, "query"));
            default -> throw new IllegalArgumentException("no builder for " + name);
        };
    }

    /** Exact: Gson keeps the number's source text, so 9007199254740993 stays 9007199254740993. */
    private static long l(JsonObject o, String k) {
        return Long.parseLong(o.get(k).getAsJsonPrimitive().getAsString());
    }

    private static String s(JsonObject o, String k) {
        return o.get(k).getAsString();
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) {
            out.add(e.getAsString());
        }
        return List.copyOf(out);
    }
}
