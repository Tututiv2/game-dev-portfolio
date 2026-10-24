package com.skyclone.mod.storage;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Storage: ender chest pages, backpacks, the /storage overview, the Wardrobe, sacks (every family, the
 * Sack of Sacks, linings), the quiver, the personal vault and "Your Bags". Provides {@link Features.Storage}
 * and {@link Features.Sacks}. Owns the sack, backpack and storage-upgrade items.
 */
public final class StorageModule implements GameModule {

    private final Catalog catalog = new Catalog();
    private final Store store = new Store(this);
    private final Guard guard = new Guard(this);
    private final SackService sacks = new SackService(this);
    private final StorageMenus storageMenus = new StorageMenus(this);
    private final WardrobeMenu wardrobe = new WardrobeMenu(this);
    private final SackMenus sackMenus = new SackMenus(this);
    private final BagMenus bagMenus = new BagMenus(this);
    private final GetFromSacks gfs = new GetFromSacks(this);
    private final EquipBridge equipBridge = new EquipBridge();
    private final EquipWardrobeMenu equipWardrobe = new EquipWardrobeMenu(this);

    /** The storage menu each player has open, so a quit or shutdown can flush it before core saves. */
    record Tracked(Menu menu, String key, Runnable sync) {}
    private final Map<UUID, Tracked> open = new ConcurrentHashMap<>();

    int recipesRegistered;
    final List<String> recipesSkipped = new ArrayList<>();
    int itemsLoaded;
    int skinsLoaded;

    @Override public String id() { return "storage"; }

    // ---- accessors for the package ------------------------------------------------------------------
    Catalog catalog() { return catalog; }
    Store store() { return store; }
    Guard guard() { return guard; }
    SackService sacks() { return sacks; }
    StorageMenus storageMenus() { return storageMenus; }
    WardrobeMenu wardrobe() { return wardrobe; }
    SackMenus sackMenus() { return sackMenus; }
    BagMenus bagMenus() { return bagMenus; }
    GetFromSacks gfs() { return gfs; }
    EquipBridge equipBridge() { return equipBridge; }
    EquipWardrobeMenu equipWardrobe() { return equipWardrobe; }

    @Override
    public void enable() {
        StorageDials.init();
        try {
            catalog.loadSacks(resource("sacks.json"));
            catalog.loadBackpacks(resource("backpacks.json"));
        } catch (Exception e) {
            Sky.log().log(Level.SEVERE, "[storage] could not read the sack/backpack catalogue", e);
        }
        itemsLoaded = Sky.items().loadJson("storage", "items.json");
        skinsLoaded = Sky.items().loadJson("storage", "backpack_skins.json");   // wave 5: 29 backpack skins
        Sky.items().addLoreContributor("storage_sack", new SackLore());

        Sky.provide(Features.Storage.class, storageMenus);
        Sky.provide(Features.Sacks.class, sacks);

        Bukkit.getPluginManager().registerEvents(new StorageListener(this), Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { sacks.flushSummaries(); } catch (RuntimeException e) { Sky.log().log(Level.WARNING, "[storage] sack summary", e); }
        }, 40L, 20L);

        // Wave 5: core's periodic/join dupe scan also looks inside every storage container (logged, never deleted there).
        Sky.items().addDupeScanSource("storage", p -> StorageDials.DUPE_SCAN_SOURCE.get() ? store.uniqueStacks(p) : List.of());

        registerCommands();
        new Admin(this).register();
        new SelfTests(this).register();
        Sky.log().info("[storage] " + itemsLoaded + " items, " + catalog.families.size() + " sack families, "
                + catalog.sacks.size() + " sacks, " + catalog.backpackSlots.size() + " backpacks");
    }

    @Override
    public void postEnable() {
        fillRuneSack();
        // Every module has registered its items by now, so a recipe is only added when all its ingredients exist;
        // the crafting module's fallback table covers anything skipped here.
        try (InputStream in = resource("recipes.json")) {
            if (in == null) return;
            Recipe[] all;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) { all = new Gson().fromJson(r, Recipe[].class); }
            for (Recipe rc : all) {
                String missing = missingIngredient(rc);
                if (missing != null) { recipesSkipped.add(rc.result + " (needs " + missing + ")"); continue; }
                Sky.recipes().register(rc);
                recipesRegistered++;
            }
        } catch (Exception e) {
            Sky.log().log(Level.WARNING, "[storage] could not register recipes", e);
        }
        if (!recipesSkipped.isEmpty()) Sky.log().info("[storage] " + recipesSkipped.size() + " recipes skipped (ingredients not registered): " + recipesSkipped);
    }

    @Override
    public void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) flushOpen(p);
    }

    /** Runes registered by any module (tag {@code rune}, stackable) become the Rune family's contents. */
    int runesAdded;

    /**
     * Wave 5, Rune Sack grouping: rune item id -> "RUNE_KEY:tier" (skills writes {@code extra.rune_key} and
     * {@code extra.rune_tier} on every rune). Insertion order = the Rune family's order.
     */
    final Map<String, String> runeTiers = new java.util.LinkedHashMap<>();

    /** "KEY:tier" from a rune def's extra data, or null when it carries none. */
    static String runeTag(com.skyclone.api.item.ItemDef d) {
        try {
            if (d == null || d.extra == null) return null;
            JsonElement k = d.extra.get("rune_key"), t = d.extra.get("rune_tier");
            if (k == null || t == null || k.isJsonNull() || t.isJsonNull()) return null;
            return k.getAsString() + ":" + t.getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void fillRuneSack() {
        try {
            List<com.skyclone.api.item.ItemDef> runes = new ArrayList<>();
            for (com.skyclone.api.item.ItemDef d : Sky.items().all()) {
                if (d != null && d.id != null && d.has("rune") && !d.isUnique()) runes.add(d);
            }
            runes.sort(java.util.Comparator.comparing((com.skyclone.api.item.ItemDef d) -> Text.strip(d.name)).thenComparing(d -> d.id));
            List<String> ids = new ArrayList<>();
            runeTiers.clear();
            for (com.skyclone.api.item.ItemDef d : runes) {
                ids.add(d.id);
                String tag = runeTag(d);
                if (tag != null) runeTiers.put(d.id, tag);
            }
            runesAdded = catalog.extend("RUNE", ids);
            if (runesAdded > 0) Sky.log().info("[storage] Rune Sack holds " + runesAdded + " rune items");
        } catch (RuntimeException e) {
            Sky.log().log(Level.WARNING, "[storage] could not fill the Rune Sack", e);
        }
    }

    static InputStream resource(String file) { return Sky.plugin().getResource("data/storage/" + file); }

    /** Null when every cell exists, else the first missing id. Cells follow core's rule: a registered id means x1. */
    static String missingIngredient(Recipe rc) {
        if (rc == null || rc.result == null || !Sky.items().exists(rc.result)) return rc == null ? "?" : rc.result;
        List<String> cells = new ArrayList<>();
        if (rc.grid != null) for (String c : rc.grid) if (c != null && !c.isBlank()) cells.add(c);
        if (rc.inputs != null) cells.addAll(rc.inputs);
        for (String c : cells) {
            String id = cellId(c);
            if (id.equals("COINS")) continue;
            if (!Sky.items().exists(id)) return id;
        }
        return null;
    }

    static String cellId(String cell) {
        if (Sky.items().exists(cell)) return cell;
        int i = cell.lastIndexOf(':');
        return i > 0 ? cell.substring(0, i) : cell;
    }

    // ---- open-menu tracking ---------------------------------------------------------------------------

    void track(Player p, Menu m, String key, Runnable sync) { open.put(p.getUniqueId(), new Tracked(m, key, sync)); }

    /** Guard key of the storage container the player is looking at, or null. */
    String openKey(Player p) {
        Tracked t = open.get(p.getUniqueId());
        if (t == null) return null;
        Menu cur;
        try { cur = Sky.menus().current(p); } catch (RuntimeException e) { cur = null; }
        return cur == t.menu() ? t.key() : null;
    }

    void flushOpen(Player p) {
        Tracked t = open.get(p.getUniqueId());
        if (t == null) return;
        Menu cur;
        try { cur = Sky.menus().current(p); } catch (RuntimeException e) { cur = null; }
        if (cur != t.menu()) return;
        try { t.sync().run(); } catch (RuntimeException e) { Sky.log().log(Level.WARNING, "[storage] flushing an open menu for " + p.getName(), e); }
    }

    void forget(UUID id) {
        open.remove(id);
        guard.forget(id);
        sacks.forget(id);
    }

    // ---- commands ---------------------------------------------------------------------------------------

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("storage", "Open your Storage: ender chest pages and backpacks")
                .category("Storage").playerOnly().run((s, a) -> storageMenus.openStorage((Player) s)));
        Sky.commands().register(SkyCommand.of("ec", "Open an ender chest page").alias("enderchest", "echest")
                .usage("[page]").category("Storage").playerOnly()
                .run((s, a) -> storageMenus.openEnder((Player) s, a.length > 0 ? parse(a[0], 1) - 1 : 0)));
        Sky.commands().register(SkyCommand.of("bp", "Open a backpack by its storage slot").alias("backpack")
                .usage("[slot]").category("Storage").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (a.length == 0) {
                        int first = Store.ready(p) ? storageMenus.firstBackpack(p) : -1;
                        if (first < 0) storageMenus.openStorage(p); else storageMenus.openBackpack(p, first);
                    } else storageMenus.openBackpack(p, parse(a[0], 1) - 1);
                }));
        Sky.commands().register(SkyCommand.of("wardrobe", "Open your Wardrobe").alias("wd", "wardrobes")
                .usage("[page]").category("Storage").playerOnly()
                .run((s, a) -> wardrobe.open((Player) s, a.length > 0 ? parse(a[0], 1) - 1 : 0)));
        Sky.commands().register(SkyCommand.of("eqwardrobe", "Open your Equipment Wardrobe (necklace, cloak, belt and gloves sets)")
                .alias("equipmentwardrobe", "ewd").usage("[page]").category("Storage").playerOnly()
                .run((s, a) -> equipWardrobe.open((Player) s, a.length > 0 ? parse(a[0], 1) - 1 : 0)));
        Sky.commands().register(SkyCommand.of("sacks", "Open your Sacks, or one kind: /sacks mining").alias("sack")
                .usage("[kind]").category("Storage").playerOnly().run((s, a) -> openSacksCommand((Player) s, a))
                .tab((s, a) -> {
                    if (a.length != 1) return List.of();
                    List<String> out = new ArrayList<>();
                    String pre = a[0].toLowerCase(java.util.Locale.ROOT);
                    for (Catalog.Family f : catalog.families) {
                        String n = f.id().toLowerCase(java.util.Locale.ROOT);
                        if (n.startsWith(pre)) out.add(n);
                    }
                    return out;
                }));
        Sky.commands().register(SkyCommand.of("sackofsacks", "Open your Sack of Sacks").alias("sos")
                .category("Storage").playerOnly().run((s, a) -> sackMenus.openBag((Player) s)));
        Sky.commands().register(SkyCommand.of("quiver", "Open your Quiver")
                .category("Storage").playerOnly().run((s, a) -> bagMenus.openQuiver((Player) s)));
        Sky.commands().register(SkyCommand.of("vault", "Open your Personal Vault").alias("personalvault")
                .category("Storage").playerOnly().run((s, a) -> bagMenus.openVault((Player) s)));
        Sky.commands().register(SkyCommand.of("bags", "Open Your Bags (quiver, sacks, vault...)").alias("yourbags")
                .category("Storage").playerOnly().run((s, a) -> bagMenus.openBags((Player) s)));
        Sky.commands().register(SkyCommand.of("storagecounter", "Buy storage items for Bits").alias("bitsstorage")
                .category("Storage").playerOnly().run((s, a) -> bagMenus.openShop((Player) s)));
        gfs.register();
    }

    /** /sacks [kind]: the kind is a family id or name ("mining", "trophy fish", "enchanted_agronomy"). */
    private void openSacksCommand(Player p, String[] a) {
        if (a.length == 0) { sackMenus.openSacks(p, 0); return; }
        String q = StorageMath.norm(String.join(" ", a));
        Catalog.Family hit = null;
        for (Catalog.Family f : catalog.families) {
            if (StorageMath.norm(f.id()).equals(q) || StorageMath.norm(Text.strip(f.name())).equals(q)) { hit = f; break; }
        }
        if (hit == null) {
            for (Catalog.Family f : catalog.families) {
                if (StorageMath.norm(f.id()).contains(q) || StorageMath.norm(Text.strip(f.name())).contains(q)) { hit = f; break; }
            }
        }
        if (hit == null) { Msg.error(p, "No sack kind called \"" + String.join(" ", a) + "\". Try /sacks for the list."); return; }
        sackMenus.openFamily(p, hit.id(), 0);
    }

    static int parse(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    // ---- sack lore ---------------------------------------------------------------------------------------

    /** Linings and the lined capacity on a sack's tooltip. Pure: (def, data) + dials. */
    final class SackLore implements LoreContributor {
        @Override public Section section() { return Section.BEFORE_FOOTER; }

        @Override
        public List<String> lines(ItemDef def, ItemData data) {
            if (def == null || !def.has("storage_sack")) return List.of();
            JsonElement e = data == null ? null : data.extra.get(SackService.LININGS);
            int n = e == null ? 0 : e.getAsInt();
            if (n <= 0) return List.of();
            Catalog.Sack s = catalog.sacks.get(def.id);
            long cap = s == null ? 0 : StorageMath.sackCapacity(s.capacity(), n, StorageDials.LINING_MAX.get(), StorageDials.LINING_BONUS.get());
            return List.of("&7Linings: &c" + n + "&7/&c" + StorageDials.LINING_MAX.get(), "&7Lined capacity: &e" + Text.commasInt(cap), "");
        }
    }

    static void tell(org.bukkit.command.CommandSender s, String line) {
        if (s instanceof Player p) Msg.send(p, line); else s.sendMessage(Text.strip(line));
    }
}
