package com.skyclone.mod.materials;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.item.Recipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shipped materials data run through the pure chain math, with no server: every recipe cell
 * resolves, every ENCHANTED_ item is craftable, the research numbers come out of the real JSON,
 * and no compression sells for more than its inputs.
 */
class MaterialsDataTest {

    static final Map<String, JsonObject> ITEMS = new LinkedHashMap<>();
    static final List<Recipe> RECIPES = new ArrayList<>();
    static final Map<String, List<String>> CHAINS = new LinkedHashMap<>();
    static final Map<String, String> PREV = new HashMap<>();
    static final Set<String> VANILLA_TYPES = new HashSet<>();

    static Path dataDir() {
        Path p = Path.of("src/main/resources/data/materials");
        if (Files.isDirectory(p)) return p;
        return Path.of("plugin/src/main/resources/data/materials");
    }

    @BeforeAll
    static void load() throws IOException {
        Path dir = dataDir();
        Gson gson = new Gson();
        for (String fam : MaterialsCatalogue.FAMILIES) {
            JsonArray a = JsonParser.parseString(Files.readString(dir.resolve("items_" + fam + ".json"), StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement e : a) ITEMS.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        }
        for (JsonElement e : JsonParser.parseString(Files.readString(dir.resolve("recipes.json"), StandardCharsets.UTF_8)).getAsJsonArray()) {
            RECIPES.add(gson.fromJson(e, Recipe.class));
        }
        JsonObject cat = JsonParser.parseString(Files.readString(dir.resolve("catalogue.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : cat.getAsJsonObject("chains").entrySet()) {
            List<String> l = new ArrayList<>();
            for (JsonElement x : e.getValue().getAsJsonArray()) l.add(x.getAsString());
            CHAINS.put(e.getKey(), l);
            for (String x : l) PREV.putIfAbsent(x, e.getKey());
        }
        Path types = dir.resolve("../../../../../../tools/gen/materials/item_types.txt").normalize();
        if (Files.exists(types)) for (String l : Files.readAllLines(types)) if (!l.isBlank()) VANILLA_TYPES.add(l.trim());
    }

    static final Predicate<String> KNOWN = id -> ITEMS.containsKey(id) || VANILLA_TYPES.contains(id);

    static double price(String id) {
        JsonObject o = ITEMS.get(id);
        return o != null && o.has("npcSell") ? o.get("npcSell").getAsDouble() : 0;
    }

    static final Function<String, Recipe> CHAIN_RECIPE = x -> {
        String prev = PREV.get(x);
        if (prev == null) return null;
        for (Recipe r : RECIPES) if (x.equals(r.result) && prev.equals(ChainMath.mainInput(r, KNOWN))) return r;
        return null;
    };

    // ---- pure math ---------------------------------------------------------------------------

    @Test
    void cellsParseLikeCore() {
        Predicate<String> known = Set.of("INK_SACK:3", "LOG", "LOG:1")::contains;
        assertEquals(new ChainMath.Cell("INK_SACK:3", 16), ChainMath.cell("INK_SACK:3:16", known));
        assertEquals(new ChainMath.Cell("INK_SACK:3", 1), ChainMath.cell("INK_SACK:3", known));
        assertEquals(new ChainMath.Cell("LOG:1", 1), ChainMath.cell("LOG:1", known), "a cell that IS an id means one of it");
        assertEquals(new ChainMath.Cell("LOG", 32), ChainMath.cell("LOG:32", known));
        assertNull(ChainMath.cell("", known));
        assertNull(ChainMath.cell(null, known));
    }

    @Test
    void romanAndUnlockKeys() {
        assertEquals(4, ChainMath.roman("IV"));
        assertEquals(9, ChainMath.roman("IX"));
        assertEquals(11, ChainMath.roman("XI"));
        assertEquals(0, ChainMath.roman("Q"));
        assertArrayEquals(new String[]{"collection", "INK_SACK:3", "6"}, ChainMath.splitUnlock("collection:INK_SACK:3:6"));
        assertArrayEquals(new String[]{"collection", "DIAMOND", "4"}, ChainMath.splitUnlock("collection:DIAMOND:4"));
        assertArrayEquals(new String[]{"slayer", "zombie", "3"}, ChainMath.splitUnlock("slayer:zombie:3"));
    }

    @Test
    void timesCraftableAndPrinter() {
        Recipe r = new Recipe();
        r.result = "ENCHANTED_X";
        r.grid = new String[]{"", "X:32", "", "X:32", "X:32", "X:32", "", "X:32", ""};
        Predicate<String> k = s -> s.equals("X");
        assertEquals(160, ChainMath.totalInputs(r, k));
        assertEquals(2, ChainMath.timesCraftable(r, k, id -> 350, 64));
        assertEquals(1, ChainMath.timesCraftable(r, k, id -> 350, 1));
        assertEquals(0, ChainMath.timesCraftable(r, k, id -> 159, 64));
        Map<String, Double> p = Map.of("X", 2.0, "ENCHANTED_X", 320.0);
        assertFalse(ChainMath.isCoinPrinter(r, k, p::get));
        Map<String, Double> p2 = Map.of("X", 2.0, "ENCHANTED_X", 321.0);
        assertTrue(ChainMath.isCoinPrinter(r, k, p2::get));
    }

    // ---- the shipped data ------------------------------------------------------------------

    @Test
    void catalogueIsBroad() {
        assertTrue(ITEMS.size() >= 1300, "catalogue items: " + ITEMS.size());
        assertTrue(RECIPES.size() >= 500, "recipes: " + RECIPES.size());
        assertTrue(CHAINS.size() >= 100, "chains: " + CHAINS.size());
    }

    @Test
    void everyRecipeCellResolves() {
        assertFalse(VANILLA_TYPES.isEmpty(), "item_types.txt not found");
        List<String> bad = new ArrayList<>();
        for (Recipe r : RECIPES) {
            if (!ITEMS.containsKey(r.result)) bad.add(r.id + " result " + r.result);
            for (String id : ChainMath.ingredients(r, KNOWN).keySet()) if (!KNOWN.test(id)) bad.add(r.id + " needs " + id);
        }
        assertTrue(bad.isEmpty(), bad.toString());
    }

    @Test
    void everyEnchantedItemHasARecipe() {
        Set<String> made = new HashSet<>();
        for (Recipe r : RECIPES) made.add(r.result);
        List<String> none = new ArrayList<>();
        for (String id : ITEMS.keySet()) if (id.startsWith("ENCHANTED_") && !made.contains(id)) none.add(id);
        assertTrue(none.isEmpty(), none.toString());
    }

    @Test
    void researchNumbersComeOutOfTheData() {
        for (Object[] row : MaterialsSelfTests.RESEARCH) {
            String result = (String) row[0], base = (String) row[1];
            double want = (Double) row[2];
            Map<String, Double> raw = ChainMath.rawCost(result, CHAIN_RECIPE, KNOWN, Set.of(base));
            assertEquals(want, raw.getOrDefault(base, -1.0), 1e-6, result + " in " + base + ": " + raw);
        }
        assertEquals(1280.0, price("ENCHANTED_DIAMOND"), 1e-9);
        assertEquals(8.0, price("DIAMOND"), 1e-9);
    }

    @Test
    void noCompressionPrintsCoins() {
        List<String> bad = new ArrayList<>();
        for (Recipe r : RECIPES) {
            if (ChainMath.isCoinPrinter(r, KNOWN, MaterialsDataTest::price)) bad.add(r.id);
        }
        assertTrue(bad.isEmpty(), bad.toString());
    }

    @Test
    void unlockKeysAreWellFormed() {
        for (Recipe r : RECIPES) {
            if (r.unlock == null) continue;
            String[] p = ChainMath.splitUnlock(r.unlock);
            assertNotNull(p, r.unlock);
            assertTrue(Set.of("collection", "slayer", "skill").contains(p[0]), r.unlock);
            assertTrue(Integer.parseInt(p[2]) >= 1, r.unlock);
            if (p[0].equals("collection")) assertTrue(ITEMS.containsKey(p[1]), r.unlock + " names an item outside the catalogue");
        }
    }

    @Test
    void namesAreCleanAndRenamed() {
        List<String> bad = new ArrayList<>();
        for (JsonObject o : ITEMS.values()) {
            String n = o.get("name").getAsString();
            if (n.contains("§") || n.contains("%%") || n.contains("�")) bad.add(o.get("id").getAsString() + " dirty: " + n);
            for (String proper : List.of("Kuudra", "Jerry", "Arachne", "Revenant", "Tarantula", "Griffin", "Jacob", "Gabagool", "Hypixel", "Divan", "Bonzo")) {
                if (n.matches(".*\\b" + proper + "\\b.*")) bad.add(o.get("id").getAsString() + " keeps proper noun: " + n);
            }
        }
        assertTrue(bad.isEmpty(), bad.toString());
    }

    /** Core's vanillaId(): a vanilla-tagged def, else the first alias a module registered, else the name. */
    static String vanillaId(String material, Map<String, List<String>> aliases) {
        for (JsonObject o : ITEMS.values()) {
            if (o.has("tags") && o.getAsJsonArray("tags").toString().contains("\"vanilla\"")
                    && material.equals(o.get("material").getAsString())) return o.get("id").getAsString();
        }
        for (String a : aliases.getOrDefault(material, List.of())) if (ITEMS.containsKey(a)) return a;
        return material;
    }

    @Test
    void vanillaPickupsBecomeTheReferenceIds() throws IOException {
        Path f = dataDir().resolve("../core-items/vanilla_aliases.json").normalize();
        assertTrue(Files.exists(f), "core alias file missing: " + f);
        Map<String, List<String>> aliases = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject().entrySet()) {
            List<String> l = new ArrayList<>();
            for (JsonElement x : e.getValue().getAsJsonArray()) l.add(x.getAsString());
            aliases.put(e.getKey(), l);
        }
        String[][] want = {{"OAK_LOG", "LOG"}, {"COCOA_BEANS", "INK_SACK:3"}, {"GUNPOWDER", "SULPHUR"},
                {"MELON_SLICE", "MELON"}, {"MELON", "MELON_BLOCK"}, {"WHEAT_SEEDS", "SEEDS"}, {"CARROT", "CARROT_ITEM"},
                {"DIAMOND", "DIAMOND"}, {"LAPIS_LAZULI", "INK_SACK:4"}, {"POPPY", "RED_ROSE"}, {"COD", "RAW_FISH"},
                {"PUMPKIN", "PUMPKIN"}};
        for (String[] w : want) assertEquals(w[1], vanillaId(w[0], aliases), "pickup of " + w[0]);
        // whatever a vanilla material resolves to must LOOK like that material (no Lotus on every poppy)
        List<String> bad = new ArrayList<>();
        for (String m : VANILLA_TYPES) {
            String id = vanillaId(m, aliases);
            JsonObject o = ITEMS.get(id);
            if (o != null && !m.equals(o.get("material").getAsString()) && !m.equals("CARVED_PUMPKIN")) bad.add(m + " -> " + id);
        }
        assertTrue(bad.isEmpty(), bad.toString());
    }

    /** Playtest 2026-10-03: 304 vanilla items (Stick, Bread, potions, buckets, music discs...) all read
     *  "A common building block." because the lore fallback was keyed on the family alone. */
    @Test
    void vanillaItemsDescribeWhatTheyAre() {
        String generic = "&7A common building block.";
        // items that are plainly not building blocks must never carry that line
        for (String id : List.of("STICK", "BOOK", "BREAD", "GLASS_BOTTLE", "BREWING_STAND_ITEM", "POTION", "BUCKET",
                "WATER_BUCKET", "MILK_BUCKET", "RECORD_11", "GOLD_RECORD", "COMPASS", "COOKED_BEEF", "PAPER", "BOWL")) {
            JsonObject o = ITEMS.get(id);
            assertNotNull(o, "missing catalogue item " + id);
            String lore = String.join(" ", lore(o));
            assertFalse(lore.isBlank(), id + " has no description");
            assertFalse(lore.contains(generic), id + " is described as a building block: " + lore);
        }
        // and by pattern over the whole catalogue: anything whose material is food, a disc, a bucket,
        // a dye, seeds, a boat, a minecart or horse armour is not "a building block"
        java.util.regex.Pattern notBlock = java.util.regex.Pattern.compile(
                "^(MUSIC_DISC_.*|.*_BUCKET|BUCKET|COOKED_.*|.*_DYE|.*_SEEDS|.*_BOAT|.*MINECART|.*_HORSE_ARMOR|POTION|STICK|BOOK|PAPER|BREAD)$");
        List<String> bad = new ArrayList<>();
        for (JsonObject o : ITEMS.values()) {
            String mat = o.get("material").getAsString();
            if (notBlock.matcher(mat).matches() && lore(o).contains(generic)) bad.add(o.get("id").getAsString());
        }
        assertTrue(bad.isEmpty(), "non-blocks described as building blocks: " + bad);
        // no single description line may blanket the catalogue the way the old fallback did
        Map<String, Integer> uses = new HashMap<>();
        for (JsonObject o : ITEMS.values()) {
            List<String> l = lore(o);
            if (l.size() == 1) uses.merge(l.get(0), 1, Integer::sum);
        }
        assertTrue(uses.getOrDefault(generic, 0) <= 120, "'" + generic + "' used " + uses.get(generic) + " times");
    }

    /**
     * finish wave 2026-10-03: 422 misc items all read "A crafting material.". The generator now says what kind of
     * thing each one is (fragment, fossil, food, lamp, fuel...) and what it is used to craft.
     */
    @Test
    void miscItemsSayWhatTheyAre() {
        String generic = "&7A crafting material.";
        int misc = 0, plain = 0;
        for (JsonObject o : ITEMS.values()) {
            if (!o.has("extra") || !"misc".equals(o.getAsJsonObject("extra").get("family").getAsString())) continue;
            misc++;
            List<String> l = lore(o);
            if (l.size() == 1 && l.get(0).equals(generic)) plain++;
        }
        assertTrue(misc > 300, "misc family shrank to " + misc);
        assertTrue(plain * 10 <= misc, plain + " of " + misc + " misc items still say only '" + generic + "'");
        Map<String, String> spot = Map.of(
                "BONZO_FRAGMENT", "boss fragment", "HELIX", "fossil", "LAMP_BROWN", "lamp",
                "COTTON_CANDY", "eat", "FUEL_TANK", "Fuel", "ORGANIC_WHEAT", "Organic");
        for (Map.Entry<String, String> e : spot.entrySet()) {
            JsonObject o = ITEMS.get(e.getKey());
            assertNotNull(o, "missing " + e.getKey());
            assertTrue(String.join(" ", lore(o)).contains(e.getValue()), e.getKey() + ": " + lore(o));
        }
        // "Used to craft" must name real things in OUR words: no raw ID;N, no reference proper noun
        java.util.regex.Pattern proper = java.util.regex.Pattern.compile(
                "\\b(Kuudra|Jerry|Arachne|Griffin|Jacob|Gabagool|Hypixel|Divan|Bonzo|Gunther|Necron|Livid)\\b|;\\d");
        List<String> bad = new ArrayList<>();
        int withUses = 0;
        for (JsonObject o : ITEMS.values()) {
            String t = String.join(" ", lore(o));
            int at = t.indexOf("Used to craft");
            if (at < 0) continue;
            withUses++;
            if (proper.matcher(t.substring(at)).find()) bad.add(o.get("id").getAsString() + ": " + t.substring(at));
        }
        assertTrue(withUses > 100, "only " + withUses + " items say what they are used for");
        assertTrue(bad.isEmpty(), "uses lines leak reference names: " + bad);
    }

    static List<String> lore(JsonObject o) {
        List<String> l = new ArrayList<>();
        if (o.has("lore")) for (JsonElement e : o.getAsJsonArray("lore")) l.add(e.getAsString());
        return l;
    }

    /**
     * playtest-loops 2026-10-03: a lore regen of gen_materials.py deleted every items_*.json first, which took
     * items_integration.json (written by gen_integration.py) with it - X/Y/Z, Glacial Fragment, Stretching
     * Sticks... vanished, 11 live self-tests and accessories' SellCapTest went red. Pin the file and its ids.
     */
    @Test
    void integrationFallbacksShip() throws IOException {
        Path f = dataDir().resolve("items_integration.json");
        assertTrue(Files.exists(f), "items_integration.json is missing - rerun py -3 tools/gen/materials/gen_materials.py");
        Map<String, JsonObject> integ = new LinkedHashMap<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            assertNull(integ.put(id, o), "duplicate integration id " + id);
            assertFalse(ITEMS.containsKey(id), id + " is in a family file AND the integration file");
            assertTrue(o.has("name") && o.has("rarity") && o.has("material"), id + " is incomplete");
            if (!VANILLA_TYPES.isEmpty()) assertTrue(VANILLA_TYPES.contains(o.get("material").getAsString()),
                    id + " uses material " + o.get("material") + " which is not a 26.1.2 item type");
        }
        // ingredients other modules' recipes, shops, trades and visitors need, that nobody else registers
        for (String id : List.of("X", "Y", "Z", "STRETCHING_STICKS", "ENCHANTED_BONE_MEAL", "CONDENSED_FERMENTO",
                "GLACIAL_FRAGMENT", "WOOD_SINGULARITY", "FRESHLY_MINTED_COINS", "AVARICIOUS_CHALICE", "LUSHLILAC",
                "GEOMETRIC_ODDITY", "RECORD_13", "LUCKY_CLOVER", "HILT_OF_REVELATIONS", "NECRON_HANDLE", "LOTUS_DIAMOND",
                "BLOOD_STAINED_COINS", "BLOOD_SOAKED_COINS")) {
            assertTrue(integ.containsKey(id) || ITEMS.containsKey(id), "no materials def for " + id);
        }
        assertTrue(integ.get("ENCHANTED_BONE_MEAL").getAsJsonArray("tags").toString().contains("no_recipe"));
    }

    // ---- wave 5 (2026-10-06) --------------------------------------------------------------------

    /** Ingredients other modules' recipes/shops/sacks name and nobody registered (cross-reference of every data file). */
    @Test
    void wave5FallbacksShip() throws IOException {
        Map<String, JsonObject> integ = new LinkedHashMap<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(dataDir().resolve("items_integration.json"),
                StandardCharsets.UTF_8)).getAsJsonArray()) integ.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        for (String id : MaterialsWave5Tests.WAVE5_FALLBACKS) {
            JsonObject o = integ.get(id);
            assertNotNull(o, "no fallback def for " + id);
            assertFalse(lore(o).isEmpty(), id + " has no lore");
            assertFalse(o.get("name").getAsString().contains("%%"), id + " kept a reference colour code in its name");
        }
        for (String id : List.of("PLASMA_NUCLEUS", "OMEGA_EGG", "COLOSSAL_EXP_BOTTLE_UPGRADE")) {
            assertEquals(1, integ.get(id).get("maxStack").getAsInt(), id + " is one per slot in the reference");
        }
        // the older fallbacks keep stacking: other modules' cells may ask for several of them in one slot
        assertFalse(integ.get("CONDENSED_FERMENTO").has("maxStack"));
    }

    /**
     * Audit 2026-10-06 integrator follow-ups: the Delver's O's cereals (Scatha / glacite corpse loot), the Dusty
     * Travel Scroll (Leviathan T5 chest) and the Summoning Ring (F6 chest) were named by loot and registered by nobody;
     * the Shredded Sinew fallback took the reference's new name.
     */
    @Test
    void auditFollowUpFallbacksShip() throws IOException {
        Map<String, JsonObject> integ = new LinkedHashMap<>();
        for (JsonElement e : JsonParser.parseString(Files.readString(dataDir().resolve("items_integration.json"),
                StandardCharsets.UTF_8)).getAsJsonArray()) integ.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        for (Object[] c : Consumables.CEREALS) {
            JsonObject o = integ.get((String) c[0]);
            assertNotNull(o, "no cereal " + c[0]);
            assertTrue(o.getAsJsonArray("abilities").toString().contains("\"" + c[1] + "\""), c[0] + " lacks " + c[1]);
            assertFalse(o.get("name").getAsString().contains("Dwarven O"), c[0] + " kept the reference brand name");
        }
        assertEquals("TRAVEL_SCROLL", integ.get("NETHER_FORTRESS_BOSS_TRAVEL_SCROLL").get("category").getAsString());
        assertFalse(integ.get("NETHER_FORTRESS_BOSS_TRAVEL_SCROLL").get("name").getAsString().contains("Kuudra"));
        assertEquals(1, integ.get("SUMMONING_RING").get("maxStack").getAsInt());
        assertEquals("Shredded Tendon", ITEMS.get("SHARD_OF_THE_SHREDDED").get("name").getAsString());
        assertNull(MaterialsWave5Tests.pureConsumables());
    }

    /** Player Head drew as bamboo; Wither/Dragon heads and the enchanted apple used the plain item; 1.8 block names. */
    @Test
    void looksAndNamesMatchTheBlockYouGet() {
        for (String[] l : MaterialsWave5Tests.LOOKS) {
            JsonObject o = ITEMS.get(l[0]);
            assertNotNull(o, "missing " + l[0]);
            assertEquals(l[1], o.get("material").getAsString(), l[0] + " look");
            assertEquals(l[2], o.get("name").getAsString(), l[0] + " name");
            assertFalse(o.has("skull"), l[0] + " must not carry a head texture");
        }
        // renamed to what other modules already ship
        assertEquals("Fermenta", ITEMS.get("FERMENTO").get("name").getAsString());
        assertEquals("Tamer's Anvil", ITEMS.get("ZOG_ANVIL").get("name").getAsString());
    }

    /** Soulflow sold for 1 coin from 160 unsellable Raw Soulflow; the pure cap rule, then the shipped data. */
    @Test
    void unsellableInputsMakeAnUnsellableCompression() {
        assertNull(MaterialsWave5Tests.pureCapRule());
        assertEquals(0.0, price("SOULFLOW"), 1e-9);
        assertEquals(1.0, ITEMS.get("SOULFLOW").getAsJsonObject("extra").get("referencePrice").getAsDouble(), 1e-9);
        List<String> bad = new ArrayList<>();
        for (Recipe r : RECIPES) {
            Map<String, Integer> ing = ChainMath.ingredients(r, KNOWN);
            if (ing.size() != 1 || ChainMath.totalInputs(r, KNOWN) < 4) continue;   // compressions only
            double cap = ChainMath.cappedPrice(r, KNOWN, MaterialsDataTest::price);
            if (cap >= 0 && cap < price(r.result) - 1e-9) bad.add(r.id + " " + price(r.result) + " > " + cap);
        }
        assertTrue(bad.isEmpty(), "compressions priced over their inputs: " + bad);
    }

    /** Crafting files recipe ids starting "vanilla:" in the Vanilla book; only vanilla-from-vanilla crafts qualify. */
    @Test
    void vanillaCraftsAreFiledAsVanilla() {
        int van = 0;
        Set<String> ids = new HashSet<>();
        for (Recipe r : RECIPES) {
            assertTrue(ids.add(r.id), "duplicate recipe id " + r.id);
            if (!r.id.startsWith("vanilla:")) {
                assertTrue(r.id.startsWith("materials:"), r.id);
                continue;
            }
            van++;
            assertFalse(r.result.startsWith("ENCHANTED_"), r.id);
            for (String i : ChainMath.ingredients(r, KNOWN).keySet()) assertFalse(i.startsWith("ENCHANTED_"), r.id + " uses " + i);
        }
        assertTrue(van > 250, "only " + van + " vanilla crafts");
        assertTrue(RECIPES.stream().anyMatch(r -> r.id.startsWith("vanilla:materials/DIAMOND_BLOCK#")), "Block of Diamond is a vanilla craft");
        assertTrue(RECIPES.stream().anyMatch(r -> r.id.startsWith("materials:ENCHANTED_DIAMOND#")), "Enchanted Diamond is a SkyBlock craft");
    }

    /** The four consumables materials wires carry their ability (and the filet its HOTM gate) in the data. */
    @Test
    void consumablesCarryTheirAbilities() {
        for (String[] w : Consumables.WIRED) {
            JsonObject o = ITEMS.get(w[0]);
            assertNotNull(o, "missing " + w[0]);
            assertTrue(o.has("abilities") && o.getAsJsonArray("abilities").toString().contains("\"" + w[1] + "\""), w[0] + " lacks " + w[1]);
        }
        assertTrue(ITEMS.get("FILET_O_FORTUNE").getAsJsonArray("requirements").toString().contains("hotm:4"));
        assertTrue(ITEMS.get("SAVING_GRACE").getAsJsonArray("abilities").toString().contains("PASSIVE"));
        assertNull(MaterialsWave5Tests.pureConsumables());
    }

    /** No misc item is left with only the generic line (32 were, until wave 5). */
    @Test
    void noMiscItemIsGeneric() {
        List<String> plain = new ArrayList<>();
        for (JsonObject o : ITEMS.values()) {
            if (lore(o).contains("&7A crafting material.")) plain.add(o.get("id").getAsString());
        }
        assertTrue(plain.isEmpty(), "still generic: " + plain);
    }

    @Test
    void lowerTierChainsStepUp() {
        // Enchanted Diamond Block = 160 Enchanted Diamond = 25,600 Diamond (research 02 §5.2)
        List<String> chain = ChainMath.chainUp("DIAMOND", x -> CHAINS.getOrDefault(x, List.of()), 8);
        assertEquals(List.of("DIAMOND", "ENCHANTED_DIAMOND", "ENCHANTED_DIAMOND_BLOCK"), chain);
    }

    /** Follow-up 2026-10-06: the Summoning Ring runs on weapons' Raise Souls; the Dusty scroll names crimson's warp. */
    @Test
    void integrationRingAndScrollAreWired() throws IOException {
        JsonArray a = JsonParser.parseString(Files.readString(dataDir().resolve("items_integration.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        Map<String, JsonObject> by = new HashMap<>();
        for (JsonElement e : a) by.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        JsonObject ring = by.get("SUMMONING_RING");
        assertNotNull(ring);
        assertEquals("weapons", ring.getAsJsonObject("extra").get("src").getAsString());
        assertEquals(2, ring.getAsJsonObject("extra").getAsJsonObject("w").get("souls").getAsInt());
        assertEquals("weapons_raise_souls", ring.getAsJsonArray("abilities").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("crimson_skull", by.get("NETHER_FORTRESS_BOSS_TRAVEL_SCROLL").getAsJsonObject("extra").get("warp").getAsString());
    }
}
