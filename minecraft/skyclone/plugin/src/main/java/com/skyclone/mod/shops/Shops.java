package com.skyclone.mod.shops;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.shops.ShopData.Entry;
import com.skyclone.mod.shops.ShopData.ItemCost;
import com.skyclone.mod.shops.ShopData.Shop;
import com.skyclone.mod.shops.ShopData.Townsfolk;
import org.bukkit.Material;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The loaded merchant data, where each merchant stands, and turning data ids into registered item ids. */
final class Shops {

    private Shops() {}

    enum Status {
        /** Our NPC is registered at its anchor. */
        STATIONED,
        /** Another module already put an NPC on that anchor: it runs that spot, we stand down. */
        YIELDED,
        /** Secondary merchant whose anchor is not in the island layout (yet). Traded from /merchants. */
        PENDING,
        /** NPCs switched off by the dial. */
        DISABLED
    }

    static final Map<String, Shop> BY_ID = new LinkedHashMap<>();
    static final List<Townsfolk> TOWNSFOLK = new ArrayList<>();
    /** Merchants other modules run with their own menus, listed in the directory (data/shops/external.json). */
    static final List<ShopData.External> EXTERNAL = new ArrayList<>();
    static final Map<String, Status> STATUS = new HashMap<>();
    static final Map<String, String> YIELDED_TO = new HashMap<>();
    static final Map<String, Status> TOWN_STATUS = new HashMap<>();

    /** Island display order for the directory. */
    static final List<String> ISLAND_ORDER = List.of("hub", "barn", "mushroom_desert", "park", "gold_mine", "deep_caverns",
            "dwarven_mines", "glacite_tunnels", "crystal_hollows", "spiders_den", "the_end", "crimson_isle",
            "backwater_bayou", "jerrys_workshop", "dungeon_hub");

    static void load() {
        BY_ID.clear();
        TOWNSFOLK.clear();
        for (Shop s : ShopData.readShops(resource("shops.json"))) BY_ID.put(s.id, s);
        TOWNSFOLK.addAll(ShopData.readTownsfolk(resource("townsfolk.json")));
        EXTERNAL.clear();
        EXTERNAL.addAll(ShopData.readExternal(resource("external.json")));
    }

    /** True when the module running this merchant is loaded (its player command is registered). */
    static boolean externalLive(ShopData.External x) {
        if (x == null || x.command == null) return false;
        try { return Sky.commands().exists(x.command); } catch (Throwable t) { return false; }
    }

    /** The name of the NPC that holds a yielded merchant's spot (its owner's NPC), else our own name. */
    static String yieldedName(Shop s) {
        String owner = YIELDED_TO.get(s.id);
        if (owner != null) {
            try {
                com.skyclone.api.npc.NpcDef d = Sky.npcs().def(owner);
                if (d != null && d.name != null && !d.name.isBlank()) return d.name;
            } catch (Throwable ignored) { }
        }
        return s.name;
    }

    private static InputStream resource(String file) {
        return Sky.plugin().getResource("data/shops/" + file);
    }

    static Shop get(String id) { return id == null ? null : BY_ID.get(id.toLowerCase(Locale.ROOT)); }

    static Collection<Shop> all() { return BY_ID.values(); }

    static String npcId(Shop s) { return "shops_" + s.id; }

    static Status status(Shop s) { return STATUS.getOrDefault(s.id, Status.PENDING); }

    // ---- where things are ---------------------------------------------------------------------------

    static String islandName(String island) {
        if (island == null) return "?";
        try {
            IslandDef d = Sky.worlds().island(island);
            if (d != null && d.displayName != null && !d.displayName.isBlank()) return d.displayName;
        } catch (Throwable ignored) { }
        return Text.titleCase(island);
    }

    static boolean islandRegistered(String island) {
        try { return Sky.worlds().island(island) != null; } catch (Throwable t) { return false; }
    }

    static Layout layout(String island) {
        try { return Sky.worlds().layout(island); } catch (Throwable t) { return null; }
    }

    /**
     * True when a player can walk up to this merchant: our NPC is registered, its island exists and has been
     * laid out. A yielded merchant counts as stationed too (another module's NPC stands there).
     */
    static boolean isStationed(Shop s) {
        Status st = status(s);
        if (st != Status.STATIONED && st != Status.YIELDED) return false;
        return islandRegistered(s.island) && layout(s.island) != null;
    }

    // ---- id resolution ------------------------------------------------------------------------------

    private static final Map<String, String> RESOLVED = new HashMap<>();

    /**
     * The registered item id for a data id (+ alternates), or null when no module registers it (yet).
     * "@MATERIAL" means core's sky id for that vanilla material ({@code vanillaId}), which keeps shop stock
     * stacking with what players pick up. Positive answers are cached (registration only grows).
     */
    static String resolve(String id, List<String> alts) {
        if (id == null) return null;
        String cached = RESOLVED.get(id);
        if (cached != null) return cached;
        String r = resolveOne(id);
        if (r == null && alts != null) for (String a : alts) { r = resolveOne(a); if (r != null) break; }
        if (r != null) RESOLVED.put(id, r);
        return r;
    }

    static String resolve(Entry e) { return resolve(e.item, e.alts); }

    static String resolve(ItemCost c) { return resolve(c.item, c.alts); }

    private static String resolveOne(String id) {
        if (id == null || id.isBlank()) return null;
        if (id.startsWith("@")) {
            Material m = Material.matchMaterial(id.substring(1));
            if (m == null || !m.isItem() || m.isAir()) return null;
            try {
                String v = Sky.items().vanillaId(m);
                if (v != null && Sky.items().exists(v) && isPlainBlock(v, m)) return v;
                // core's alias for this material is a DIFFERENT item (Carved Pumpkin -> Pumpkin, Blue Orchid ->
                // the Moonflower collection item): sell the block the line names, never a look-alike.
                return Sky.items().exists(m.name()) ? m.name() : null;
            } catch (Throwable t) { return null; }
        }
        return Sky.items().exists(id) ? id : null;
    }

    /**
     * True when {@code id} is the plain sky item for {@code m}: it is made of that material AND it is a vanilla
     * spelling of it (core's automatic def, the material's own name, or flagged "vanilla" by its owner), not a
     * custom item that merely borrows the texture.
     */
    static boolean isPlainBlock(String id, Material m) {
        ItemDef d = def(id);
        if (d == null || d.material == null) return false;
        if (Material.matchMaterial(d.material) != m) return false;
        if (id.equals(m.name()) || d.has("vanilla")) return true;
        if (d.extra != null && d.extra.has("vanilla")) return true;
        try { return Sky.items().isAutoVanilla(id); } catch (Throwable t) { return false; }
    }

    /** "Cut Sandstone" for a "@CUT_SANDSTONE" line (the block it stands for); null for any other line. */
    static String blockName(Entry e) {
        if (e == null || e.item == null || !e.item.startsWith("@") || e.item.length() < 2) return null;
        return Text.titleCase(e.item.substring(1));
    }

    /**
     * Lines of one view whose item names a player could not tell apart (the legacy "Smooth Sandstone" is the modern
     * Cut Sandstone, next to the real Smooth Sandstone). The menus label those by the block they stand for.
     */
    static java.util.Set<Entry> ambiguousLines(List<Entry> view) {
        List<String> labels = new ArrayList<>(view.size());
        for (Entry e : view) labels.add(Text.strip(displayName(resolve(e))));
        java.util.Set<Entry> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int i : ShopData.ambiguous(labels)) out.add(view.get(i));
        return out;
    }

    /**
     * The name a stock line shows: the item's own (books carry their enchant, see {@link #displayName}); for an
     * ambiguous block line the block it stands for; for any other ambiguous line its rarity, which is all that
     * separates "Farming Exp Boost" Common from Rare at the Pet Supplies stall.
     */
    static String lineName(Entry e, boolean ambiguous) {
        String id = resolve(e);
        ItemDef d = def(id);
        String colour = d == null || d.rarity == null ? "&f" : d.rarity.code();
        String block = ambiguous ? blockName(e) : null;
        if (block != null) return colour + block;
        if (ambiguous && d != null && d.rarity != null && variant(d) == null)
            return colour + ShopData.withVariant(d.name == null ? Text.titleCase(d.id) : d.name, Text.titleCase(d.rarity.display()));
        return displayName(id);
    }

    static ItemDef def(String resolvedId) { return resolvedId == null ? null : Sky.items().def(resolvedId); }

    /**
     * "&9Enchanted Iron" - the item's own (renamed) display name in its rarity colour, plus what tells it apart
     * when the name alone cannot: "&fEnchanted Book (Sharpness I)". Used for stock titles, costs and every
     * bought / sold / buyback message, so a book is never just "Enchanted Book" anywhere in a shop.
     */
    static String displayName(String resolvedId) {
        ItemDef d = def(resolvedId);
        if (d == null) return "&f" + Text.titleCase(resolvedId == null ? "?" : resolvedId);
        String n = d.name == null ? Text.titleCase(d.id) : d.name;
        return (d.rarity == null ? "&f" : d.rarity.code()) + ShopData.withVariant(n, variant(d));
    }

    /**
     * What one item of a family is when its name is shared by the whole family: an enchanted book's enchant and
     * level ("Sharpness I", read from the book def's {@code extra.enchant}/{@code extra.level}, which is how the
     * enchanting module registers every level). Null for anything else.
     */
    static String variant(ItemDef d) {
        if (d == null || d.extra == null || !d.extra.has("enchant") || !d.extra.has("level")) return null;
        String ench;
        int level;
        try {
            ench = d.extra.get("enchant").getAsString();
            level = d.extra.get("level").getAsInt();
        } catch (RuntimeException ex) { return null; }
        if (ench == null || ench.isBlank()) return null;
        String name = null;
        try {
            com.skyclone.api.item.EnchantDef ed = Sky.items().enchant(ench);
            if (ed != null) name = ed.name;
        } catch (Throwable ignored) { }
        return ShopData.enchantLabel(name == null || name.isBlank() ? Text.titleCase(ench) : name, level);
    }

    /** True when a stock line's title must be set by us (the stack's own name would not say what it is). */
    static boolean renamed(Entry e, boolean ambiguous) {
        return ambiguous || variant(def(resolve(e))) != null;
    }

    // ---- currencies that belong to other modules -----------------------------------------------------

    static String currencyName(String id) {
        return switch (id) {
            case "pelt" -> "&5Pelts";
            case "north_star" -> "&dNorth Stars";
            case "copper" -> "&cCopper";
            case "mote" -> "&5Motes";
            case "pest" -> "&2Pests";
            case "kernel" -> "&eKernels";
            case "bingo_point" -> "&6Bingo Points";
            case "carnival_point" -> "&eCarnival Tokens";
            case "bronze_medal" -> "&cBronze Medals";
            case "silver_medal" -> "&fSilver Medals";
            case "gold_medal" -> "&6Gold Medals";
            default -> "&f" + Text.titleCase(id);
        };
    }

    /**
     * The registered ITEM a currency is carried as, or null when it is a number in another module's data that
     * nobody exposes. North Stars are a real item (mayors gives {@code NORTH_STAR} through loot and spends it the
     * same way at its own emporium), so the Stargazer takes them from the inventory and sacks like any item cost.
     * Any other currency whose upper-cased id is a registered item works the same way the day its owner ships one.
     */
    static String currencyItem(String id) {
        if (id == null || id.isBlank()) return null;
        String item = switch (id) {
            case "north_star" -> "NORTH_STAR";
            default -> id.toUpperCase(Locale.ROOT);
        };
        try { return Sky.items().exists(item) ? item : null; } catch (Throwable t) { return null; }
    }

    /**
     * Coins and bits are core; a currency is spendable when it is carried as an item ({@link #currencyItem}) or a
     * service spends it ({@link Currencies}: Motes through Features.Rift, anything through Features.Currencies).
     */
    static boolean currencySupported(String id) { return Currencies.supported(id); }

    /** "WITHER" for {@code ESSENCE_WITHER}: essence lives in the reforges pouch as well as in the inventory. */
    static String essenceType(String itemId) {
        if (itemId == null || !itemId.startsWith("ESSENCE_") || itemId.length() <= 8) return null;
        return itemId.substring(8);
    }

    // ---- requirements -------------------------------------------------------------------------------

    /** Everything a player must meet to buy this line here (shop gate + line gate), or null. */
    static String requirement(Shop s, Entry e) {
        if (!reqsOn()) return null;
        return ShopData.requirement(s == null ? null : s.req, e == null ? null : e.req);
    }

    private static boolean reqsOn() {
        try { return ShopDials.REQUIREMENTS == null || ShopDials.REQUIREMENTS.get(); } catch (Throwable t) { return true; }
    }

    /** Human text of one requirement key, never throwing. */
    static String describeReq(String key) {
        try {
            String d = Sky.requirements().describe(key);
            return d == null || d.isBlank() ? key : d;
        } catch (Throwable t) { return key; }
    }

    static boolean meets(org.bukkit.entity.Player p, String key) {
        if (key == null || key.isBlank()) return true;
        if (p == null) return false;
        try { return Sky.requirements().check(p, key); } catch (Throwable t) { return false; }
    }

    /**
     * Lore for a gated line: one row per key, ticked or crossed for this viewer ({@code p} null = no marks).
     * Empty when the line has no requirement.
     */
    static List<String> requirementLines(org.bukkit.entity.Player p, Shop s, Entry e) {
        List<String> out = new ArrayList<>();
        if (!reqsOn()) return out;
        for (String k : ShopData.requirementKeys(s == null ? null : s.req, e == null ? null : e.req)) {
            boolean ok = p != null && meets(p, k);
            out.add((p == null ? "&7" : ok ? "&a✔ &7" : "&c✖ &7") + "Requires " + describeReq(k));
        }
        return out;
    }

    // ---- counts -------------------------------------------------------------------------------------

    /** Entries whose result resolves, i.e. what a player can actually see in the shop. */
    static List<Entry> available(Shop s, String group) {
        List<Entry> out = new ArrayList<>();
        Map<String, String> seen = new HashMap<>();
        for (Entry e : s.entries) {
            if (group != null && !group.equals(e.group)) continue;
            if (shown(e, seen)) out.add(e);
        }
        return out;
    }

    /**
     * Resolves, and is not a second data id collapsing onto an item an earlier line of the same tab already sells
     * (two lines, one item, is a duplicate to the player). The same id listed twice on purpose (other amount) stays.
     */
    private static boolean shown(Entry e, Map<String, String> seen) {
        String r = resolve(e);
        if (r == null) return false;
        String key = e.group + "|" + r;
        String first = seen.putIfAbsent(key, e.item);
        return first == null || first.equals(e.item);
    }

    static int availableCount(Shop s) {
        int n = 0;
        Map<String, String> seen = new HashMap<>();
        for (Entry e : s.entries) if (shown(e, seen)) n++;
        return n;
    }
}
