package com.skyclone.mod.mining;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drills: fuel instead of durability, three part sockets (fuel tank, engine, upgrade module), the powder
 * coating, and the Drill Mechanic menu. Research 2.15. Also hosts the per-instance counters of the
 * Promising pickaxe (Stored Potential) and the Stratopick 2000 (uses).
 */
final class Drills {

    static final String FUEL = "drill_fuel", TANK = "drill_tank", ENGINE = "drill_engine", MODULE = "drill_module", COAT = "drill_coating";
    static final String STORED = "stored_blocks", USES = "pick_uses";

    /** Fuel items and how much fuel each adds (VOLTA/OIL/BIOFUEL from research; coal family TUNE). */
    static final Map<String, Integer> FUELS = new LinkedHashMap<>();
    static {
        FUELS.put("VOLTA", 10000);
        FUELS.put("OIL_BARREL", 10000);
        FUELS.put("BIOFUEL", 3000);
        FUELS.put("ENCHANTED_COAL_BLOCK", 20000);   // TUNE
        FUELS.put("ENCHANTED_COAL", 3200);          // research "(?)"
        FUELS.put("ENCHANTED_CHARCOAL", 1600);      // TUNE
        FUELS.put("COAL", 50);                      // research "(?)"
        FUELS.put("CHARCOAL", 25);                  // TUNE
        FUELS.put("GOBLIN_EGG", 100);               // TUNE
    }

    private final MiningModule m;

    Drills(MiningModule m) {
        this.m = m;
        Sky.items().addStatContributor("mining_drill", this::contribute);
        Sky.items().addLoreContributor("mining_drill", new LoreContributor() {
            @Override public Section section() { return Section.AFTER_STATS; }
            @Override public List<String> lines(ItemDef def, ItemData data) { return lore(def, data); }
        });
    }

    // ---- reading ----------------------------------------------------------------------------

    static boolean isDrill(ItemDef def) { return def != null && def.extra != null && def.extra.has("drill"); }

    static String str(JsonObject o, String k) { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null; }

    static int num(JsonObject o, String k, int def) {
        try { return o != null && o.has(k) ? o.get(k).getAsInt() : def; } catch (Exception e) { return def; }
    }

    static JsonObject partExtra(String id) {
        ItemDef d = id == null ? null : Sky.items().def(id);
        return d == null ? null : d.extra;
    }

    static int maxFuel(ItemDef def, ItemData data) {
        JsonObject tank = partExtra(str(data.extra, TANK));
        if (tank != null) return num(tank, "capacity", 3000);
        return num(def.extra, "baseFuel", 3000);
    }

    static int fuel(ItemDef def, ItemData data) {
        int max = maxFuel(def, data);
        return Math.min(max, num(data.extra, FUEL, max));
    }

    ItemStack heldDrill(Player p) {
        ItemStack h = p.getInventory().getItemInMainHand();
        if (h == null || h.getType().isAir()) return null;
        ItemDef d = Sky.items().def(Sky.items().idOf(h) == null ? "" : Sky.items().idOf(h));
        return isDrill(d) ? h : null;
    }

    private record ModuleAt(int tick, String module) {}
    private final Map<java.util.UUID, ModuleAt> moduleCache = new java.util.HashMap<>();

    /**
     * The held drill's upgrade module. Asked by every HOTM perk lookup (Blue Cheese adds a level), i.e.
     * dozens of times per stat recompute: cached for the current tick so the item JSON is read once.
     */
    private String module(Player p) {
        int now = org.bukkit.Bukkit.getCurrentTick();
        ModuleAt c = moduleCache.get(p.getUniqueId());
        if (c != null && c.tick == now) return c.module;
        ItemStack h = heldDrill(p);
        String mod = h == null ? null : str(Sky.items().data(h).extra, MODULE);
        if (moduleCache.size() > 512) moduleCache.clear();
        moduleCache.put(p.getUniqueId(), new ModuleAt(now, mod));
        return mod;
    }

    boolean blueCheese(Player p) { return "GOBLIN_OMELETTE_BLUE_CHEESE".equals(module(p)); }

    double powderBonus(Player p) { return "GOBLIN_OMELETTE_SPICY".equals(module(p)) ? 20 : 0; }

    /** Pickaxe-ability cooldown cut from the fuel tank, in percent. */
    double cooldownCut(Player p) {
        ItemStack h = heldDrill(p);
        if (h == null) return 0;
        JsonObject tank = partExtra(str(Sky.items().data(h).extra, TANK));
        return tank == null ? 0 : num(tank, "cooldownCut", 0);
    }

    /** Extra mithril / gemstone powder % and gem speed of the held drill's base type. */
    double drillPowderPct(Player p, String powderType) {
        ItemStack h = heldDrill(p);
        if (h == null) return 0;
        ItemDef d = Sky.items().def(Sky.items().idOf(h));
        if ("MITHRIL".equals(powderType)) return num(d.extra, "mithrilPowder", 0);
        if ("GEMSTONE".equals(powderType)) return num(d.extra, "gemPowder", 0);
        return 0;
    }

    double drillGemSpeed(Player p) {
        ItemStack h = heldDrill(p);
        if (h == null) return 0;
        return num(Sky.items().def(Sky.items().idOf(h)).extra, "gemSpeed", 0);
    }

    /** Starfall Seasoning is doubled near a Fallen Star: the extra half, applied at break time. */
    double[] seasoningNearStar(Player p) {
        if (!"STARFALL_SEASONING".equals(module(p))) return new double[]{0, 0};
        if (!m.events.nearStar(p.getLocation(), 20)) return new double[]{0, 0};
        return new double[]{25, 10};
    }

    // ---- fuel -------------------------------------------------------------------------------

    /** Burn fuel for one block. False when the drill is empty (and the block must not break). */
    boolean hasFuel(Player p) {
        ItemStack h = heldDrill(p);
        if (h == null) return true;
        ItemDef d = Sky.items().def(Sky.items().idOf(h));
        return fuel(d, Sky.items().data(h)) > 0;
    }

    void burn(Player p, int perBlock) {
        ItemStack h = heldDrill(p);
        if (h == null || perBlock <= 0) return;
        ItemDef d = Sky.items().def(Sky.items().idOf(h));
        ItemData data = Sky.items().data(h);
        String mod = str(data.extra, MODULE);
        JsonObject me = partExtra(mod);
        double save = me == null ? 0 : (me.has("fuelSave") ? me.get("fuelSave").getAsDouble() : 0);
        if (save > 0 && MiningUtil.rnd().nextDouble() < save) return;
        int mult = me == null ? 1 : num(me, "fuelMult", 1);
        int cost = perBlock * mult;
        int before = fuel(d, data);
        int after = Math.max(0, before - cost);
        Sky.items().update(h, x -> x.extra.addProperty(FUEL, after));
        if ("TUNGSTEN_KEYCHAIN".equals(mod) && before / 500 != after / 500) {
            m.hollows.cool(p, 1);
            m.tunnels.warm(p, 1);
        }
        if (after == 0) Msg.error(p, "Your drill is out of fuel! Refuel it at the Drill Mechanic (/drill).");
    }

    String fuelSegment(Player p) {
        ItemStack h = heldDrill(p);
        if (h == null) return null;
        ItemDef d = Sky.items().def(Sky.items().idOf(h));
        ItemData data = Sky.items().data(h);
        int f = fuel(d, data), max = maxFuel(d, data);
        String c = f <= max / 10 ? "&c" : "&2";
        return c + "⛽ " + Text.commas(f) + "&8/" + Text.shortNum(max) + " Fuel";
    }

    // ---- stats + lore -----------------------------------------------------------------------

    private void contribute(ItemDef def, ItemData data, com.skyclone.api.item.Rarity rarity, ItemStatSheet sheet) {
        if (def == null || data == null) return;
        if (isDrill(def)) {
            JsonObject eng = partExtra(str(data.extra, ENGINE));
            if (eng != null) {
                sheet.add(Stat.MINING_SPEED, num(eng, "speed", 0), ItemStatSheet.Source.OTHER);
                sheet.add(Stat.MINING_FORTUNE, num(eng, "fortune", 0), ItemStatSheet.Source.OTHER);
            }
            JsonObject mod = partExtra(str(data.extra, MODULE));
            if (mod != null) {
                sheet.add(Stat.MINING_FORTUNE, num(mod, "fortune", 0), ItemStatSheet.Source.OTHER);
                sheet.add(Stat.MINING_SPEED, num(mod, "speed", 0), ItemStatSheet.Source.OTHER);
            }
            if (data.extra.has(COAT)) sheet.add(Stat.MINING_SPEED, 500, ItemStatSheet.Source.OTHER);
        }
        if (def.extra != null && def.extra.has("storedPotential")) {
            JsonObject sp = def.extra.getAsJsonObject("storedPotential");
            int blocks = num(data.extra, STORED, 0);
            double v = Math.min(num(sp, "max", 250), Math.floor(blocks / (double) num(sp, "perBlocks", 100)) * num(sp, "speed", 10));
            sheet.add(Stat.MINING_SPEED, v, ItemStatSheet.Source.OTHER);
        }
    }

    private List<String> lore(ItemDef def, ItemData data) {
        List<String> out = new ArrayList<>();
        if (def == null || data == null) return out;
        if (isDrill(def)) {
            out.add(partLine("Fuel Tank", str(data.extra, TANK), "Raises the fuel the drill can hold."));
            out.add(partLine("Drill Engine", str(data.extra, ENGINE), "Adds Mining Speed and Fortune."));
            out.add(partLine("Upgrade Module", str(data.extra, MODULE), "A passive upgrade."));
            if (data.extra.has(COAT)) out.add("&7Coating: &6Varnas Powder Coating");
            out.add("");
            int f = fuel(def, data), max = maxFuel(def, data);
            out.add("&7Fuel: &2" + Text.commas(f) + "&8/" + Text.shortNum(max));
            out.add("&8Fit parts at the Drill Mechanic (/drill).");
        }
        if (def.extra != null && def.extra.has("storedPotential")) out.add("&7Blocks Mined: &a" + Text.commas(num(data.extra, STORED, 0)));
        if (def.extra != null && def.extra.has("uses")) {
            int left = num(data.extra, USES, num(def.extra, "uses", 2000));
            out.add("&7Uses left: &a" + Text.commas(left));
        }
        return out;
    }

    private static String partLine(String slot, String id, String what) {
        if (id == null) return "&7" + slot + ": &cNot Installed";
        return "&7" + slot + ": " + MiningUtil.itemName(id);
    }

    /** Count a block on the Promising pickaxe and spend a use of the Stratopick. */
    void onToolBlock(Player p) {
        ItemStack h = p.getInventory().getItemInMainHand();
        if (h == null || h.getType().isAir()) return;
        String id = Sky.items().idOf(h);
        ItemDef d = id == null ? null : Sky.items().def(id);
        if (d == null || d.extra == null) return;
        if (d.extra.has("storedPotential")) {
            int cur = num(Sky.items().data(h).extra, STORED, 0);
            if (cur < 2500) {               // +10 per 100 blocks caps at +250, i.e. 2,500 blocks
                int next = cur + 1;
                Sky.items().update(h, x -> x.extra.addProperty(STORED, next));
                if (next % 100 == 0) Sky.stats().invalidate(p);
            }
        }
        if (d.extra.has("uses")) {
            int left = num(Sky.items().data(h).extra, USES, num(d.extra, "uses", 2000)) - 1;
            if (left <= 0) {
                p.getInventory().setItemInMainHand(null);
                Msg.send(p, "&cYour " + MiningUtil.itemName(id) + " &cshattered!");
                Msg.sound(p, Sound.ENTITY_ITEM_BREAK, 1f);
            } else Sky.items().update(h, x -> x.extra.addProperty(USES, left));
        }
    }

    // ---- the Drill Mechanic -----------------------------------------------------------------

    void openMechanic(Player p) {
        ItemStack h = heldDrill(p);
        Menu menu = new Menu("Drill Mechanic", 6).chrome();
        menu.set(48, Icons.back("Mining"), c -> m.menus.openMain(c.player()));
        if (h == null) {
            menu.set(22, Icons.of(Material.PRISMARINE_SHARD, "&cNo Drill", "&7Hold a drill in your main hand,", "&7then talk to me again."));
            menu.set(31, Icons.of(Material.BOOK, "&aAbout Drills",
                    "&7Drills burn &2Fuel &7instead of wearing", "&7out. Fit three parts to boost them:",
                    "&8• &aFuel Tank &7- holds more fuel", "&8• &aDrill Engine &7- speed and fortune",
                    "&8• &aUpgrade Module &7- a special perk", "", "&7Drills are forged at the &6Forge&7."));
            menu.open(p);
            return;
        }
        String uuid = Sky.items().data(h).uuid;
        ItemDef def = Sky.items().def(Sky.items().idOf(h));
        ItemData data = Sky.items().data(h);
        menu.set(13, h.clone());
        menu.set(29, slotIcon("Fuel Tank", TANK, data, Material.CAULDRON), c -> partClick(c.player(), uuid, TANK, "tank", c.right()));
        menu.set(31, slotIcon("Drill Engine", ENGINE, data, Material.PISTON), c -> partClick(c.player(), uuid, ENGINE, "engine", c.right()));
        menu.set(33, slotIcon("Upgrade Module", MODULE, data, Material.PUMPKIN_PIE), c -> partClick(c.player(), uuid, MODULE, "module", c.right()));
        int f = fuel(def, data), max = maxFuel(def, data);
        menu.set(22, Icons.of(Material.COAL_BLOCK, "&2Refuel",
                "&7Fuel: &2" + Text.commas(f) + "&8/" + Text.commas(max), "",
                "&7Click to pick fuel from your", "&7inventory. Shift-click fills it up", "&7with the best fuel you carry.", "",
                "&eClick to refuel!"), c -> {
            if (c.shift()) autoFuel(c.player(), uuid); else fuelPicker(c.player(), uuid);
        });
        boolean coated = data.extra.has(COAT);
        menu.set(40, Icons.of(Material.GLOWSTONE_DUST, coated ? "&6Coated" : "&6Powder Coating",
                coated ? new String[]{"&7This drill already carries a", "&6Varnas Powder Coating&7: &6+500 ⸕ Mining Speed&7."}
                        : new String[]{"&7Apply a &6Varnas Powder Coating", "&7for &6+500 ⸕ Mining Speed&7.", "", "&eClick to apply one from your inventory!"}),
                coated ? null : c -> applyCoating(c.player(), uuid));
        menu.open(p);
    }

    private ItemStack slotIcon(String title, String key, ItemData data, Material empty) {
        String id = str(data.extra, key);
        if (id == null) return Icons.of(empty, "&c" + title + ": Empty", "&7Click to fit a part from your", "&7inventory.", "", "&eClick to install!");
        ItemStack it;
        try { it = Sky.items().create(id); } catch (Exception e) { it = new ItemStack(empty); }
        return Icons.withLore(it, List.of("", "&a" + title + " installed.", "&eClick to swap, right-click to remove!"));
    }

    private ItemStack verify(Player p, String uuid) {
        ItemStack h = heldDrill(p);
        if (h == null || uuid == null || !uuid.equals(Sky.items().data(h).uuid)) {
            Msg.error(p, "Keep the same drill in your hand.");
            p.closeInventory();
            return null;
        }
        return h;
    }

    private void partClick(Player p, String uuid, String key, String kind, boolean remove) {
        ItemStack h = verify(p, uuid);
        if (h == null) return;
        if (remove) {
            String cur = str(Sky.items().data(h).extra, key);
            if (cur == null) return;
            Sky.items().update(h, x -> x.extra.remove(key));
            clampFuel(h);
            Sky.loot().give(p, cur, 1, LootSource.OTHER);
            Msg.success(p, "Removed " + Text.strip(MiningUtil.itemName(cur)) + ".");
            openMechanic(p);
            return;
        }
        List<String> parts = new ArrayList<>();
        for (ItemStack s : p.getInventory().getStorageContents()) {
            String id = s == null ? null : Sky.items().idOf(s);
            JsonObject ex = partExtra(id);
            if (ex != null && kind.equals(str(ex, "drillPart")) && !parts.contains(id)) parts.add(id);
        }
        if (parts.isEmpty()) { Msg.error(p, "You have no " + kind + " parts in your inventory."); return; }
        Sky.menus().paged("Choose a " + Text.titleCase(kind), parts, id -> Icons.withLore(Sky.items().create(id), List.of("", "&eClick to install!")),
                pc -> install(pc.click().player(), uuid, key, pc.entry()),
                page -> page.set(48, Icons.back("Drill Mechanic"), c -> openMechanic(c.player())), 0).open(p);
    }

    private void install(Player p, String uuid, String key, String partId) {
        ItemStack h = verify(p, uuid);
        if (h == null) return;
        ItemDef pd = Sky.items().def(partId);
        if (pd != null) for (String r : pd.requirements) if (!Sky.requirements().checkOrTell(p, r)) return;
        if (Sky.items().remove(p, partId, 1) < 1) { Msg.error(p, "That part is no longer in your inventory."); return; }
        String old = str(Sky.items().data(h).extra, key);
        Sky.items().update(h, x -> x.extra.addProperty(key, partId));
        clampFuel(h);
        if (old != null) Sky.loot().give(p, old, 1, LootSource.OTHER);
        Sky.stats().invalidate(p);
        Msg.success(p, "Installed " + Text.strip(MiningUtil.itemName(partId)) + "!");
        Msg.sound(p, Sound.BLOCK_ANVIL_USE, 1.4f);
        openMechanic(p);
    }

    private void clampFuel(ItemStack h) {
        ItemDef d = Sky.items().def(Sky.items().idOf(h));
        ItemData data = Sky.items().data(h);
        int max = maxFuel(d, data);
        if (num(data.extra, FUEL, max) > max) Sky.items().update(h, x -> x.extra.addProperty(FUEL, max));
    }

    private void applyCoating(Player p, String uuid) {
        ItemStack h = verify(p, uuid);
        if (h == null) return;
        if (Sky.items().remove(p, "DIVAN_POWDER_COATING", 1) < 1) { Msg.error(p, "You need a Varnas Powder Coating."); return; }
        Sky.items().update(h, x -> x.extra.addProperty(COAT, true));
        Sky.stats().invalidate(p);
        Msg.success(p, "Your drill gleams: +500 Mining Speed!");
        openMechanic(p);
    }

    private void fuelPicker(Player p, String uuid) {
        List<String> have = new ArrayList<>();
        for (String id : FUELS.keySet()) if (Sky.items().count(p, id) > 0) have.add(id);
        if (have.isEmpty()) { Msg.error(p, "You carry no fuel. Coal, Biofuel, Oil Barrels and Charge Cells all work."); return; }
        Sky.menus().paged("Choose Fuel", have, id -> Icons.withLore(Icons.amount(Sky.items().create(id), Sky.items().count(p, id)),
                        List.of("", "&7Adds &2+" + Text.commas(FUELS.get(id)) + " Fuel &7each.", "&eClick to add one, shift-click to fill!")),
                pc -> addFuel(pc.click().player(), uuid, pc.entry(), pc.click().shift()),
                page -> page.set(48, Icons.back("Drill Mechanic"), c -> openMechanic(c.player())), 0).open(p);
    }

    private void autoFuel(Player p, String uuid) {
        for (String id : FUELS.keySet()) if (Sky.items().count(p, id) > 0) { addFuel(p, uuid, id, true); return; }
        Msg.error(p, "You carry no fuel.");
    }

    private void addFuel(Player p, String uuid, String id, boolean fill) {
        ItemStack h = verify(p, uuid);
        if (h == null) return;
        ItemDef d = Sky.items().def(Sky.items().idOf(h));
        int per = FUELS.getOrDefault(id, 0);
        int added = 0;
        while (true) {
            ItemData data = Sky.items().data(h);
            int cur = fuel(d, data), max = maxFuel(d, data);
            if (cur >= max) break;
            if (Sky.items().remove(p, id, 1) < 1) break;
            int next = Math.min(max, cur + per);
            Sky.items().update(h, x -> x.extra.addProperty(FUEL, next));
            added += next - cur;
            if (!fill) break;
        }
        if (added > 0) { Msg.success(p, "Added " + Text.commas(added) + " Fuel."); Msg.sound(p, Sound.ITEM_BUCKET_FILL_LAVA, 1f); }
        else Msg.error(p, "The tank is already full.");
        openMechanic(p);
    }
}
