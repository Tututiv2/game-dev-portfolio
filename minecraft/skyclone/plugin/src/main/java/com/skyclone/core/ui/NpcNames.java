package com.skyclone.core.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.Sky;
import com.skyclone.api.npc.NpcDef;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One name, one person. Every module picked its NPC names on its own, so by wave 4 the hub had three
 * different Brams (the blacksmith, the woodcutter, an auction agent) while a fourth was the Mayor, the
 * Forge was run by a Brann, and five people answered to Hollis. Four AIs play this on camera and talk to
 * each other about who to see; "go to Bram" has to mean one person.
 *
 * <p>The rule, pure so it is unit-tested ({@code NpcNamesTest}) as well as live ({@code ui.npc-names-unique}):
 * split every display name into words, drop job titles and plain nouns ({@link #COMMON}), and no remaining
 * word may belong to two different people. Two entries are the same person when their names are equal once
 * colour codes and a leading "Mayor " are removed (the mayor, the mayor's garden visitor and the mayor's
 * island NPC are one character). {@link #SAME_PERSON} lists the few words that are deliberately shared.
 *
 * <p>New NPC? Pick a name whose personal part no one else uses. If this fails on a JOB word ("Ranger",
 * "Keeper"), add it to {@link #COMMON}; if it fails on a NAME, rename one of them and update docs/names.
 */
public final class NpcNames {

    private NpcNames() {}

    /** Job titles, places and plain nouns that appear in names without being anyone's name. Lower case. */
    public static final Set<String> COMMON = Set.of(
            // jobs and titles
            "agent", "alchemist", "anvil", "archaeologist", "archer", "archivist", "archmage", "arcanist", "auctioneer",
            "axe", "axewright", "bagwright", "bait", "baker", "ballot", "banker", "barker", "barkeep", "beekeeper",
            "blacksmith", "blaster", "botanist", "brewmaster", "broker", "captain", "carver", "chief", "clerk",
            "coffer", "crier", "curator", "dealer", "delver", "dialer", "digger", "dockhand", "dozing", "duke",
            "elder", "emissary", "envoy", "essence", "farmer", "farmhand", "ferryman", "fisher", "fletcher", "forger",
            "forgemaster", "fossil", "frost", "frostbitten", "fur", "gardener", "gatekeeper", "gravedigger", "guard",
            "guide", "hedgewitch", "hiker", "hunter", "huntress", "keeper", "king", "lady", "lamplighter", "librarian",
            "lift", "lord", "master", "mayor", "merchant", "miner", "monger", "operator", "owl", "peddler",
            "pestwarden", "phone", "pipefitter", "plumber", "prince", "princess", "professor", "prospector",
            "provisioner", "quarryman", "quartermaster", "queen", "racer", "rancher", "range", "ranger", "resident",
            "riddler", "ringmaster", "royal", "sage", "saint", "sapwitch", "scout", "scrapper", "scribe", "seller",
            "sexton", "shady", "shaman", "shepherd", "sheriff", "shoveller", "shrine", "smith", "spirit",
            "stargazer", "starkeeper", "supplier", "supply", "surveyor", "tactician", "tailor", "thaumaturgist",
            "ticket", "token", "tower", "town", "trader", "trainer", "treasurer", "tribal", "vampire", "vault",
            "vaultkeeper", "vendor", "wanderer", "warden", "watchman", "weaponsmith", "witch", "woodcutter",
            "wrangler", "aquarist", "grocer", "old", "granny", "grandpa", "dr", "sir", "the", "and",
            // plain nouns and places that turn up in names
            "arch", "bazaar", "camp", "carnival", "catacombs", "cave", "class", "cold", "combat", "crystal",
            "diamond", "dread", "emerald", "enjoyer", "farm", "fish", "friendly", "gold", "hollows", "hungry",
            "ice", "iron", "lapis", "lumber", "mine", "mines", "mountain", "mushroom", "neighbor", "north", "party",
            "pearl", "pest", "pet", "rift", "root", "snooty", "board", "zoo", "glade", "desert", "monarch",
            "avatar", "redstone", "jasper", "amber", "amethyst", "sapphire", "topaz", "jade", "ruby", "opal");

    /** Words deliberately shared by entries that ARE one character (or one family). Lower case. */
    public static final Set<String> SAME_PERSON = Set.of(
            "gerald",     // Jerry: the island helper, Mayor Gerald, his winter self Saint Gerald and his season
            "orla",       // Mayor Orla runs her own Mythological Ritual as Huntress Orla
            "deepforge",  // the Deepforge family of the Dwarven Mines
            "hopsworth"); // Hopsworth the garden visitor runs Hopsworth's Hunt (chocolate)

    /** The person a display name stands for: colour codes, trailing punctuation and a leading "Mayor " removed. */
    public static String person(String name) {
        String s = name == null ? "" : name.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "").trim();
        if (s.startsWith("Mayor ")) s = s.substring(6).trim();
        return s;
    }

    /** The words of a name that could be somebody's name (capitalised, 3+ letters, not in {@link #COMMON}). */
    public static List<String> nameWords(String name) {
        List<String> out = new ArrayList<>();
        for (String w : person(name).split("[^\\p{L}']+")) {
            if (w.endsWith("'s")) w = w.substring(0, w.length() - 2);
            w = w.replace("'", "");
            if (w.length() < 3 || !Character.isUpperCase(w.charAt(0))) continue;
            String low = w.toLowerCase(Locale.ROOT);
            if (!COMMON.contains(low)) out.add(low);
        }
        return out;
    }

    /**
     * Clashes in a roster of display name -> where it comes from. Each entry of the result reads
     * {@code word: Name (source) | Name (source)}. Empty when every name word belongs to one person.
     */
    public static List<String> clashes(Map<String, String> roster) {
        Map<String, Map<String, String>> byWord = new TreeMap<>();   // word -> person -> "Name (source)"
        for (Map.Entry<String, String> e : roster.entrySet()) {
            String who = person(e.getKey());
            for (String w : nameWords(e.getKey())) {
                if (SAME_PERSON.contains(w)) continue;
                byWord.computeIfAbsent(w, k -> new TreeMap<>()).putIfAbsent(who, who + " (" + e.getValue() + ")");
            }
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : byWord.entrySet()) {
            if (e.getValue().size() > 1) out.add(e.getKey() + ": " + String.join(" | ", e.getValue().values()));
        }
        return out;
    }

    /** First names from a generated pool (contest ghosts, sellers) that are also a world NPC's name word. */
    public static List<String> poolClashes(Iterable<String> pool, Map<String, String> roster) {
        Map<String, String> words = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : roster.entrySet()) {
            for (String w : nameWords(e.getKey())) words.putIfAbsent(w, person(e.getKey()) + " (" + e.getValue() + ")");
        }
        Set<String> out = new TreeSet<>();
        for (String n : pool) {
            for (String w : nameWords(n)) {
                String hit = words.get(w);
                if (hit != null) out.add(n + " vs " + hit);
            }
        }
        return new ArrayList<>(out);
    }

    // ---- items named after somebody ------------------------------------------------------------
    //
    // Wave-5 playtest: the auction house listed "Bottle of Gerald's Wit" (reference Bottle of Jyrre) while Gerald
    // is the island helper and the Mayor - a player reads that as Gerald's bottle, and it is not his. The NPC
    // rule above never saw it because items are not NPCs. So: an item whose name says it is SOMEBODY'S ("X's")
    // must either name a word nobody on the roster answers to, or be declared that person's in OWNED_ITEMS.

    /**
     * Descriptive titles that read as "a kind of person", not as one person, when they own an item: "Diver's
     * Mask", "Fairy's Polo", "Pig's Ear", "Herbalist's Coupon". An NPC can carry one in its name ("Diver
     * Morwen", "Wren the Fairy") without every diving mask becoming hers. Lower case.
     */
    public static final Set<String> ITEM_TITLES = Set.of(
            "builder", "collector", "diver", "dragon", "fairy", "herbalist", "overseer", "pig", "puzzler",
            "spider", "tamer", "weaver");

    /** Items that really ARE the named roster character's (item id -> the name word they carry). */
    public static final Map<String, String> OWNED_ITEMS = Map.ofEntries(
            Map.entry("ANITA_TALISMAN", "harvesta"), Map.entry("ANITA_RING", "harvesta"), Map.entry("ANITA_ARTIFACT", "harvesta"),
            Map.entry("DANTE_TALISMAN", "tyrant"), Map.entry("DANTE_RING", "tyrant"), Map.entry("dante_goon", "tyrant"),
            Map.entry("KUUDRAS_HEART", "leviathan"), Map.entry("KUUDRAS_KIDNEY", "leviathan"), Map.entry("KUUDRAS_LUNG", "leviathan"),
            Map.entry("ODGERS_BRONZE_TOOTH", "gill"), Map.entry("ODGERS_SILVER_TOOTH", "gill"),
            Map.entry("ODGERS_GOLD_TOOTH", "gill"), Map.entry("ODGERS_DIAMOND_TOOTH", "gill"),
            Map.entry("BONZO_MASK", "grinko"), Map.entry("BONZO_MASK_JESTER", "grinko"),
            Map.entry("TERRY_SNOWGLOBE", "tobin"));                      // Tobin the garden visitor's snowglobe

    /** The owner words of a name: "Old Gill's Gold Tooth" -> [gill]; titles, COMMON words and short words dropped. */
    public static List<String> possessives(String name) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\p{L}+)'s(?!\\p{L})").matcher(person(name));
        while (m.find()) {
            String w = m.group(1);
            if (w.length() < 3 || !Character.isUpperCase(w.charAt(0))) continue;
            String low = w.toLowerCase(Locale.ROOT);
            if (!COMMON.contains(low) && !ITEM_TITLES.contains(low)) out.add(low);
        }
        return out;
    }

    /**
     * Items whose name says they belong to someone on the roster without being declared theirs.
     * {@code items} is display name -> "source id" (the id is the LAST space-separated token); each result reads
     * {@code Item Name (source) | owner word belongs to Person (source)}.
     */
    public static List<String> itemOwnerClashes(Map<String, String> items, Map<String, String> roster, Map<String, String> owned) {
        Map<String, String> words = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : roster.entrySet()) {
            for (String w : nameWords(e.getKey())) words.putIfAbsent(w, person(e.getKey()) + " (" + e.getValue() + ")");
        }
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, String> e : items.entrySet()) {
            String src = e.getValue() == null ? "" : e.getValue().trim();
            String id = src.substring(src.lastIndexOf(' ') + 1);
            for (String w : possessives(e.getKey())) {
                String who = words.get(w);
                if (who != null && !w.equals(owned.get(id))) out.add(person(e.getKey()) + " (" + src + ") | " + w + " belongs to " + who);
            }
        }
        return new ArrayList<>(out);
    }

    /** Self-test body for {@code ui.item-owner-names}: every REGISTERED item (the name players see) against the live roster. */
    static String itemOwnerSelfTest() {
        Map<String, String> roster;
        try {
            roster = liveRoster();
        } catch (Exception e) {
            return "could not read the roster: " + e;
        }
        Map<String, String> items = new LinkedHashMap<>();
        for (com.skyclone.api.item.ItemDef d : Sky.items().all()) {
            if (d != null && d.name != null && !d.name.isBlank()) items.putIfAbsent(d.name, "item " + d.id);
        }
        if (items.size() < 500) return "only " + items.size() + " registered items: the registry did not load";
        List<String> c = itemOwnerClashes(items, roster, OWNED_ITEMS);
        return c.isEmpty() ? null : c.size() + " item(s) named after a roster character they do not belong to"
                + " (rename the item, or add it to NpcNames.OWNED_ITEMS if it IS theirs): " + c;
    }

    /** Live roster: every registered NPC (garden visitors included) plus every mayor on the ballot. */
    static Map<String, String> liveRoster() throws Exception {
        Map<String, String> roster = new LinkedHashMap<>();
        for (NpcDef d : Sky.npcs().all()) {
            if (d != null && d.name != null && !d.name.isBlank()) roster.putIfAbsent(d.name, "npc " + d.id);
        }
        try (InputStream in = Sky.plugin().getResource("data/mayors/mayors.json")) {
            if (in != null) addMayors(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)), roster);
        }
        return roster;
    }

    /** Adds "Mayor &lt;name&gt;" for every mayor in a parsed mayors.json ({@code {"mayors": [...]}} or a bare array). */
    public static void addMayors(JsonElement root, Map<String, String> roster) {
        JsonArray mayors = root.isJsonObject() && root.getAsJsonObject().has("mayors")
                ? root.getAsJsonObject().getAsJsonArray("mayors") : root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
        for (JsonElement m : mayors) {
            if (!m.isJsonObject()) continue;
            JsonObject o = m.getAsJsonObject();
            if (o.has("name")) roster.putIfAbsent("Mayor " + o.get("name").getAsString(), "mayor " + (o.has("id") ? o.get("id").getAsString() : "?"));
        }
    }

    /** Self-test body for {@code ui.npc-names-unique}. */
    static String selfTest() {
        Map<String, String> roster;
        try {
            roster = liveRoster();
        } catch (Exception e) {
            return "could not read the roster: " + e;
        }
        List<String> c = clashes(roster);
        return c.isEmpty() ? null : c.size() + " name(s) shared by different people (rename one, update docs/names): " + c;
    }
}
