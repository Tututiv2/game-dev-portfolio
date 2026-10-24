package com.skyclone.mod.hub;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.skill.SkillType;
import com.skyclone.mod.hub.gen.HubAnchors;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Townsfolk errands: the shipped data hangs together, and the hand-in rule is all-or-nothing. */
class ErrandsTest {

    static <T> T read(String res, TypeToken<T> type) throws IOException {
        try (InputStream in = ErrandsTest.class.getClassLoader().getResourceAsStream(res)) {
            assertNotNull(in, res + " missing");
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type.getType());
        }
    }

    /** Every item id registered by any module's data/<module>/*.json array of item defs. */
    static Set<String> allItemIds() throws IOException {
        Set<String> ids = new HashSet<>();
        Path data = Path.of("src/main/resources/data");
        try (Stream<Path> files = Files.walk(data)) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.getFileName().toString().startsWith("items") && x.toString().endsWith(".json"))::iterator) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    JsonElement el = JsonParser.parseReader(r);
                    if (!el.isJsonArray()) continue;
                    JsonArray arr = el.getAsJsonArray();
                    for (JsonElement e : arr)
                        if (e.isJsonObject() && e.getAsJsonObject().has("id")) ids.add(e.getAsJsonObject().get("id").getAsString());
                } catch (RuntimeException ignored) { }
            }
        }
        return ids;
    }

    @Test
    void shippedErrandsValidate() throws IOException {
        List<Errands.Errand> all = read("data/hub/errands.json", new TypeToken<List<Errands.Errand>>() {});
        List<HubNpcs.Spec> specs = read("data/hub/npcs.json", new TypeToken<List<HubNpcs.Spec>>() {});
        Set<String> npcIds = new HashSet<>();
        for (HubNpcs.Spec s : specs) npcIds.add(s.id);
        Set<String> skills = new HashSet<>();
        for (SkillType t : SkillType.values()) skills.add(t.name());
        Set<String> items = allItemIds();
        assertTrue(items.contains("ROGUE_SWORD") && items.contains("COAL"), "item scan found " + items.size() + " ids");
        assertNull(Errands.validate(all, npcIds, items, skills));
        assertTrue(all.size() >= 10, "errands: " + all.size());
        Set<String> placed = new HashSet<>(HubAnchors.OWN);
        placed.addAll(HubAnchors.RESIDENTS);
        placed.addAll(HubAnchors.CONTRACT);
        placed.addAll(HubAnchors.REQUESTED);
        for (Errands.Errand e : all) {
            HubNpcs.Spec g = specs.stream().filter(s -> s.id.equals(e.npc)).findFirst().orElseThrow();
            assertTrue(HubAnchors.OWN.contains(g.anchor) || HubAnchors.RESIDENTS.contains(g.anchor), e.id + " giver stands at " + g.anchor);
            for (Errands.Step s : e.steps)
                if (Errands.kind(s.trigger) == Errands.Kind.TALK)
                    assertTrue(placed.contains(s.trigger.substring(5)), e.id + ": talk anchor " + s.trigger + " is not one the hub builds");
        }
        // every hub resident (npc.resident.N) has a hub NPC with an errand; the other two residents belong to skills / chocolate
        for (String a : HubAnchors.RESIDENTS) {
            if (!a.startsWith("npc.resident.")) continue;
            HubNpcs.Spec s = specs.stream().filter(x -> x.anchor.equals(a)).findFirst().orElse(null);
            assertNotNull(s, "nobody lives at " + a);
            assertTrue(all.stream().anyMatch(e -> e.npc.equals(s.id)), s.id + " has no errand (a resident with no purpose)");
        }
        // no hub NPC of ours is description-only any more: each gives an errand or opens something
        // (hub_lumber_jack is only a stand-in: foraging's Woodcutter, with the 20-log quest, takes npc.lumber_jack)
        for (HubNpcs.Spec s : specs)
            assertTrue(!"none".equals(s.then) || s.id.equals("hub_lumber_jack") || all.stream().anyMatch(e -> e.npc.equals(s.id)), s.id + " only talks");
    }

    @Test
    void handInIsAllOrNothing() {
        Errands.Errand e = new Errands.Errand();
        e.id = "t";
        e.steps.add(step("bring:COAL:10"));
        e.steps.add(step("bring:COAL:5"));
        e.steps.add(step("bring:WHEAT:3"));
        Map<String, Integer> have = new HashMap<>(Map.of("COAL", 14, "WHEAT", 9));
        assertNull(Errands.takePlan(e, have), "14 coal cannot cover two steps needing 15 together");
        have.put("COAL", 15);
        assertEquals(Map.of("COAL", 15, "WHEAT", 3), Errands.takePlan(e, have));
        have.remove("WHEAT");
        assertNull(Errands.takePlan(e, have));
    }

    @Test
    void triggersParse() {
        assertEquals(Errands.Kind.KILL, Errands.kind("kill:hub_wolf:5"));
        assertEquals(5, Errands.need(step("kill:hub_wolf:5")));
        assertEquals(10, Errands.need(step("bring:COAL:10")));
        assertEquals("COAL", Errands.bringItem(step("bring:COAL:10")));
        assertEquals("skill:combat:3", Errands.arg(step("req:skill:combat:3")));
        assertEquals(Errands.Kind.BAD, Errands.kind("dance:5"));
        assertTrue(Errands.Kind.TALK.counted() && Errands.Kind.PAD.counted() && !Errands.Kind.BRING.counted() && !Errands.Kind.PET.counted());
        // event steps reuse the guide matcher; "kill:hub_wolf" must not count the Old Wolf
        assertTrue(Errands.matches(step("kill:hub_wolf:5"), "kill:hub_wolf"));
        assertFalse(Errands.matches(step("kill:hub_wolf:5"), "kill:hub_old_wolf"));
        assertTrue(Errands.matches(step("gather:MINING:COAL:20"), "gather:MINING:COAL"));
        assertFalse(Errands.matches(step("bring:COAL:10"), "gather:MINING:COAL"), "a hand-in step never counts events");
        assertTrue(Errands.matches(step("talk:npc.mourner"), "talk:npc.mourner"));
    }

    @Test
    void petAndReforgeChecks() {
        assertFalse(Errands.petOk(step("pet:*:1"), null, 0), "no pet summoned");
        assertTrue(Errands.petOk(step("pet:*:1"), "BEE", 1));
        assertFalse(Errands.petOk(step("pet:*:10"), "BEE", 9));
        assertTrue(Errands.petOk(step("pet:bee:10"), "BEE", 12));
        assertFalse(Errands.petOk(step("pet:WOLF:1"), "BEE", 50));
        assertTrue(Errands.reforgeOk(step("reforge:*"), "sharp"));
        assertFalse(Errands.reforgeOk(step("reforge:*"), null));
        assertFalse(Errands.reforgeOk(step("reforge:spicy"), "sharp"));
    }

    @Test
    void validateCatchesBrokenData() {
        Errands.Errand e = new Errands.Errand();
        e.id = "x"; e.npc = "nobody"; e.name = "X";
        e.steps.add(step("dance:3"));
        e.steps.add(step("bring:NOT_AN_ITEM:2"));
        String bad = Errands.validate(List.of(e), Set.of("hub_andrew"), Set.of("COAL"), Set.of("COMBAT"));
        assertNotNull(bad);
        for (String want : List.of("no hub NPC", "bad trigger", "bring item", "no offer", "no reward")) assertTrue(bad.contains(want), want + " in " + bad);
    }

    static Errands.Step step(String trigger) {
        Errands.Step s = new Errands.Step();
        s.trigger = trigger; s.text = trigger;
        return s;
    }
}
