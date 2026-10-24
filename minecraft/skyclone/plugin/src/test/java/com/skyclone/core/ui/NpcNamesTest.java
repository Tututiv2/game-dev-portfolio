package com.skyclone.core.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One name, one person (wave-4 playtest: three Brams in the hub, a Mayor Bram, a Forgemaster Brann and five
 * Hollises). The live check {@code ui.npc-names-unique} reads the real registry; this one reads the source
 * and data files, so a clash fails the build before anyone boots a server.
 */
class NpcNamesTest {

    static Path main() {
        Path p = Path.of("src/main");
        return Files.isDirectory(p) ? p : Path.of("plugin/src/main");
    }

    // ---- the rule ------------------------------------------------------------------------------

    @Test void differentPeopleSharingANameClash() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("&fSmith Bram", "reforges");
        r.put("Mayor Bram", "mayors");
        r.put("&eAgent Bram", "auction");
        List<String> c = NpcNames.clashes(r);
        assertEquals(1, c.size(), c.toString());
        assertTrue(c.get(0).startsWith("bram: "), c.toString());
    }

    @Test void theSamePersonInTwoPlacesIsNotAClash() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("Mayor Bram", "mayors.json");
        r.put("&aMayor Bram", "garden visitor");
        r.put("&fBram", "island");                       // "Mayor X" and "X" are one character
        r.put("&fAlchemist Wendel", "alchemy");
        r.put("&9Alchemist Wendel", "garden visitor");
        assertEquals(List.of(), NpcNames.clashes(r));
    }

    @Test void sharedJobTitlesAreNotNames() {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("&fRanger Ellery", "a");
        r.put("&fRanger Ravensby", "b");
        r.put("&fTailor Fennimore", "c");
        r.put("&fTailor Penrose", "d");
        r.put("&6Keeper of Gold", "e");
        r.put("&fGold Forger", "f");
        assertEquals(List.of(), NpcNames.clashes(r));
    }

    @Test void nameWordsDropColourTitlesAndPossessives() {
        assertEquals(List.of("ellery"), NpcNames.nameWords("&f&lRanger Ellery"));
        assertEquals(List.of("lupo"), NpcNames.nameWords("Old Lupo's Cave"));
        assertEquals(List.of("velka"), NpcNames.nameWords("&fOld Vel'ka"));
        assertEquals("Bram", NpcNames.person("&6Mayor Bram"));
    }

    @Test void poolNamesMayNotBeAnNpcsName() {
        Map<String, String> r = Map.of("&fRange Master Jory", "hub");
        assertEquals(1, NpcNames.poolClashes(List.of("Jory", "Ada"), r).size());
        assertEquals(List.of(), NpcNames.poolClashes(List.of("Ada"), r));
    }

    // ---- the shipping roster -------------------------------------------------------------------

    /** Every NPC, visitor, mayor and named persona the data and source files declare. */
    static Map<String, String> roster() throws Exception {
        Map<String, String> r = new LinkedHashMap<>();
        Path data = main().resolve("resources/data");
        try (Stream<Path> files = Files.walk(data)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonElement root;
                try (Reader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    root = JsonParser.parseReader(in);
                }
                String rel = data.relativize(f).toString().replace('\\', '/');
                if (rel.equals("mayors/mayors.json")) NpcNames.addMayors(root, r);
                else if (rel.equals("garden/visitors.json")) {
                    for (JsonElement v : root.getAsJsonObject().getAsJsonArray("visitors"))
                        r.putIfAbsent(v.getAsJsonObject().get("name").getAsString(), "visitor " + v.getAsJsonObject().get("id").getAsString());
                } else if (rel.equals("auction/agents.json")) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet())
                        if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("name"))
                            r.putIfAbsent(e.getValue().getAsJsonObject().get("name").getAsString(), rel + " " + e.getKey());
                } else walk(root, rel, r);
            }
        }
        // Java registrations: NpcDef.of(id, "Name"...), the npc()/reg()/regular() helpers, foraging quests and NAME constants
        Pattern[] ps = {
                Pattern.compile("NpcDef\\.of\\(\\s*[^,]+,\\s*\"([^\"]+)\""),
                Pattern.compile("\\b(?:npc|reg|regular)\\(\\s*\"[a-z0-9_]+\"\\s*,\\s*\"([^\"]+)\""),
                Pattern.compile("new Quest\\(\\s*\"[^\"]+\"\\s*,\\s*\"[^\"]+\"\\s*,\\s*\"([^\"]+)\""),
                Pattern.compile("static final String \\w*NAME\\w* = \"([^\"]+)\""),
                Pattern.compile("dials\\(\\)\\.text\\(\"[a-z.-]*-name\",\\s*\"([^\"]+)\"")};   // e.g. pets.kat-name
        Path mods = main().resolve("java/com/skyclone/mod");
        try (Stream<Path> files = Files.walk(mods)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                for (Pattern p : ps) {
                    Matcher m = p.matcher(src);
                    while (m.find()) r.putIfAbsent(m.group(1), mods.relativize(f).toString().replace('\\', '/'));
                }
            }
        }
        // names built in code: the Rift's four clerks
        for (String n : pool("com.skyclone.mod.rift.RiftNpcs", "CLERK_NAMES")) r.putIfAbsent(n, "rift/RiftNpcs CLERK_NAMES");
        return r;
    }

    static List<String> pool(String cls, String field) throws Exception {
        Field f = Class.forName(cls).getDeclaredField(field);
        f.setAccessible(true);
        return Arrays.asList((String[]) f.get(null));
    }

    /** JSON NPCs: any object with both a name and an anchor (hub, shops, townsfolk, crimson, fishing, kuudra, bank), plus npcName. */
    static void walk(JsonElement e, String file, Map<String, String> r) {
        if (e.isJsonArray()) for (JsonElement c : e.getAsJsonArray()) walk(c, file, r);
        else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("name") && o.get("name").isJsonPrimitive() && o.has("anchor"))
                r.putIfAbsent(o.get("name").getAsString(), file + (o.has("id") ? " " + o.get("id").getAsString() : ""));
            if (o.has("npcName") && o.get("npcName").isJsonPrimitive()) r.putIfAbsent(o.get("npcName").getAsString(), file);
            for (Map.Entry<String, JsonElement> c : o.entrySet()) walk(c.getValue(), file, r);
        }
    }

    @Test void theRosterIsBigEnoughToMeanSomething() throws Exception {
        Map<String, String> r = roster();
        assertTrue(r.size() > 250, "roster only found " + r.size() + " names: the extraction broke");
        for (String must : List.of("&fTown Crier Hollis", "&fSmith Ottmar", "Mayor Bram", "&6Forgemaster Kettrick", "&fRanger Ravensby"))
            assertTrue(r.containsKey(must), "roster is missing " + must);
    }

    @Test void noTwoPeopleShareAName() throws Exception {
        List<String> c = NpcNames.clashes(roster());
        assertEquals(List.of(), c, c.size() + " clash(es) - rename one side and update docs/names/<module>.md");
    }

    @Test void contestGhostsAreNotNpcs() throws Exception {
        assertEquals(List.of(), NpcNames.poolClashes(pool("com.skyclone.mod.farming.ContestMath", "FIRST"), roster()));
    }

    @Test void dungeonStrangersAreNotNpcs() throws Exception {
        assertEquals(List.of(), NpcNames.poolClashes(pool("com.skyclone.mod.dungeons.puzzle.Strangers", "NAMES"), roster()));
    }

    // ---- items named after somebody (wave-5: "Bottle of Gerald's Wit" on the AH was not Gerald's) ---

    @Test void anItemNamedAfterSomeoneElsesNameIsCaught() {
        Map<String, String> roster = Map.of("&fGerald", "island", "&fOld Gill", "fishing");
        Map<String, String> items = new LinkedHashMap<>();
        items.put("Bottle of Gerald's Wit", "alchemy/items.json NEW_BOTTLE_OF_JYRRE");
        items.put("Old Gill's Gold Tooth", "accessories/items.json ODGERS_GOLD_TOOTH");
        List<String> c = NpcNames.itemOwnerClashes(items, roster, Map.of("ODGERS_GOLD_TOOTH", "gill"));
        assertEquals(1, c.size(), c.toString());
        assertTrue(c.get(0).startsWith("Bottle of Gerald's Wit") && c.get(0).contains("gerald belongs to Gerald"), c.toString());
    }

    @Test void possessivesSkipTitlesAndPlainNouns() {
        assertEquals(List.of("gill"), NpcNames.possessives("&6Old Gill's Silver Tooth"));
        assertEquals(List.of(), NpcNames.possessives("Diver's Mask"));            // a kind of person, not one
        assertEquals(List.of(), NpcNames.possessives("Spider Queen's Belt"));     // a title
        assertEquals(List.of(), NpcNames.possessives("Bottle of Midnight"));
    }

    /** Every item in the data files: objects with an id, a name and a rarity or material (NPCs have anchors). */
    static void items(JsonElement e, String file, Map<String, String> out) {
        if (e.isJsonArray()) for (JsonElement c : e.getAsJsonArray()) items(c, file, out);
        else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("id") && o.get("id").isJsonPrimitive() && o.has("name") && o.get("name").isJsonPrimitive()
                    && (o.has("rarity") || o.has("material")) && !o.has("anchor"))
                out.putIfAbsent(o.get("name").getAsString(), file + " " + o.get("id").getAsString());
            for (Map.Entry<String, JsonElement> c : o.entrySet()) items(c.getValue(), file, out);
        }
    }

    @Test void noShippingItemIsNamedAfterACharacterItDoesNotBelongTo() throws Exception {
        Map<String, String> items = new LinkedHashMap<>();
        Path data = main().resolve("resources/data");
        try (Stream<Path> files = Files.walk(data)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                try (Reader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    items(JsonParser.parseReader(in), data.relativize(f).toString().replace('\\', '/'), items);
                }
            }
        }
        assertTrue(items.size() > 3000, "only " + items.size() + " items found: the extraction broke");
        List<String> c = NpcNames.itemOwnerClashes(items, roster(), NpcNames.OWNED_ITEMS);
        assertEquals(List.of(), c, c.size() + " item(s) named after a character they are not - rename the item"
                + " (docs/names/<module>.md) or, if it IS theirs, add it to NpcNames.OWNED_ITEMS");
        for (String id : NpcNames.OWNED_ITEMS.keySet())             // a stale allowance would hide the next clash
            assertTrue(items.values().stream().anyMatch(v -> v.endsWith(" " + id)), "OWNED_ITEMS names an item no data file has: " + id);
    }

    @Test void auctionSellerPersonasAreNotNpcs() throws Exception {
        JsonArray sellers;
        try (Reader in = Files.newBufferedReader(main().resolve("resources/data/auction/agents.json"), StandardCharsets.UTF_8)) {
            sellers = JsonParser.parseReader(in).getAsJsonObject().getAsJsonArray("sellers");
        }
        List<String> names = new ArrayList<>();
        for (JsonElement s : sellers) names.add(s.getAsString());
        assertEquals(List.of(), NpcNames.poolClashes(names, roster()));
    }
}
