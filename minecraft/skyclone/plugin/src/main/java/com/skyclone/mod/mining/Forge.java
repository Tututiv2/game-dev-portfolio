package com.skyclone.mod.mining;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The Forge (research 2.16): timed recipes in up to 7 slots (2 at HOTM 2, +1 per tier to 7 at HOTM 7; Core of the
 * Mountain II's old extra slot is the dial mining.forge-core-slot, off), Quick Forge and the Molten Forge mayor perk
 * shorten the timer, nothing skips it.
 * Every FORGE recipe in the registry shows up here (ours plus any other module's).
 */
final class Forge {

    private final MiningModule m;
    private List<Recipe> own = new ArrayList<>();
    /**
     * Our recipe per result, for every recipe whose items all exist - registered by us OR deferred to an
     * identical-result recipe another module's reference data already registered. The Forge menu always
     * shows and runs OURS for those results: the other modules' copies were generated without the
     * Heart of the Mountain gate, so a Tier 1 player could otherwise start Divan armour or Diamonite.
     */
    private final Map<String, Recipe> ownByResult = new java.util.HashMap<>();
    /** Ours registered / result already registered by another module (we overlay it) / an item is missing. */
    int registered, deferred;
    final List<String> missing = new ArrayList<>();

    Forge(MiningModule m, Reader forgeJson) {
        this.m = m;
        List<Recipe> l = new Gson().fromJson(forgeJson, new TypeToken<List<Recipe>>() {}.getType());
        if (l != null) own = l;
    }

    /** Register our forge recipes once every module's items exist (called a tick after boot). */
    void registerRecipes() {
        registered = 0; deferred = 0; missing.clear(); ownByResult.clear();
        java.util.Set<String> already = new java.util.HashSet<>();
        for (Recipe r : Sky.recipes().byType(Recipe.Type.FORGE)) already.add(r.result);
        for (Recipe r : own) {
            String gap = firstMissing(r);
            if (gap != null) { missing.add(r.result + (gap.equals(r.result) ? " (result item)" : " (needs " + gap + ")")); continue; }
            ownByResult.put(r.result, r);
            if (already.contains(r.result)) { deferred++; continue; }
            Sky.recipes().register(r);
            registered++;
        }
        Sky.log().info("[mining] " + summary());
        if (!missing.isEmpty()) Sky.log().warning("[mining] forge recipes not offered, an item is not registered by any module: " + missing);
    }

    /** The first item id of a recipe (result first, then inputs) that no module registers, or null. */
    private static String firstMissing(Recipe r) {
        if (!MiningUtil.exists(r.result)) return r.result;
        for (String c : r.inputs) {
            String[] cc = MiningUtil.cell(c);
            if (cc == null) return String.valueOf(c);
            if (!MiningUtil.exists(cc[0])) return cc[0];
        }
        return null;
    }

    String summary() {
        return "forge recipes: " + own.size() + " in our data, " + registered + " registered by us, " + deferred
                + " already registered by another module (the Forge runs ours: same inputs, plus the HOTM gate), "
                + missing.size() + " not offered (missing items); the Forge lists " + recipes().size();
    }

    /** Admin report: the summary plus every recipe that is not offered and why. */
    List<String> report() {
        List<String> out = new ArrayList<>();
        out.add(summary());
        for (String s : missing) out.add(" not offered: " + s);
        return out;
    }

    List<Recipe> ownRecipes() { return own; }

    /** Every FORGE recipe in the registry, with our own version in place of any other module's copy of it. */
    List<Recipe> recipes() {
        List<Recipe> out = new ArrayList<>();
        java.util.Set<String> overlaid = new java.util.HashSet<>();
        for (Recipe r : Sky.recipes().byType(Recipe.Type.FORGE)) {
            Recipe mine = ownByResult.get(r.result);
            if (mine == null) out.add(r);
            else if (overlaid.add(r.result)) out.add(mine);
        }
        out.sort(Comparator.comparing((Recipe r) -> category(r)).thenComparingLong(r -> r.durationSeconds));
        return out;
    }

    static int category(Recipe r) {
        String id = r.result == null ? "" : r.result;
        if (id.startsWith("REFINED_") || id.endsWith("_PLATE") || id.equals("GOLDEN_PLATE")) return 0;
        if (id.endsWith("_GEM") || id.equals("GEMSTONE_MIXTURE") || id.equals("GLACITE_AMALGAMATION")) return 1;
        if (id.contains("DRILL_ENGINE") || id.contains("FUEL_TANK") || id.contains("OMELETTE") || id.equals("DRILL_ENGINE") || id.equals("FUEL_TANK")) return 2;
        if (id.contains("DRILL")) return 3;
        if (id.contains(";")) return 5;
        return 4;
    }

    /** Forge slots (wiki /The_Forge, audit 2026-10-06 M1): 2 at HOTM 2, +1 per tier, 7 at HOTM 7; none below HOTM 2. */
    int slots(Player p) { return slotsAt(m.hotm.tier(p), m.hotm.coreLevel(p)); }

    int slotsAt(int tier, int coreLevel) {
        // the menu has 7 slot cells, so never more than 7 whatever the dials say
        return Math.min(7, MiningMath.forgeSlots(tier, m.dials.forgeUnlockTier.get(), m.dials.forgeMaxSlots.get(), m.dials.forgeCoreSlot.get(), coreLevel));
    }

    /** The first HOTM tier that has {@code n} slots, or -1 if no tier does at these dial settings. */
    int tierForSlot(int n) {
        for (int t = 1; t <= m.hotm.tree.maxTier(); t++) if (slotsAt(t, 0) >= n) return t;
        return -1;
    }

    /** Slots a HOTM tier adds over the one before it (tier reward lore). */
    int slotsGained(int tier) {
        return slotsAt(tier, 0) - slotsAt(tier - 1, 0);
    }

    long duration(Player p, Recipe r) {
        double secs = r.durationSeconds * m.dials.forgeTimeMult.get();
        secs *= 1 - m.hotm.value(p, "quick_forge") / 100.0;
        try {
            String perk = m.dials.moltenForgePerk.get();
            if (perk != null && !perk.isBlank() && Sky.service(Features.Mayors.class).perkActive(perk)) secs *= 0.75;
        } catch (Throwable ignored) { }
        return Math.max(1, Math.round(secs));
    }

    // ---- inventory helpers --------------------------------------------------------------------

    static int have(Player p, String id) {
        return Sky.items().count(p, id) + Math.max(0, Sky.service(Features.Sacks.class).count(p, id));
    }

    /** Every input summed by id (a recipe may name the same id in two cells; each cell alone would pass). */
    static Map<String, Long> needs(Recipe r) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (String c : r.inputs) {
            String[] cc = MiningUtil.cell(c);
            if (cc == null) continue;
            long n;
            try { n = (long) Double.parseDouble(cc[1]); } catch (NumberFormatException e) { n = 1; }
            if (n > 0) out.merge(cc[0], n, Long::sum);
        }
        return out;
    }

    private boolean canAfford(Player p, Recipe r) {
        for (Map.Entry<String, Long> e : needs(r).entrySet()) {
            if ("COINS".equals(e.getKey())) { if (Sky.economy().purse(p) < e.getValue()) return false; }
            else if (have(p, e.getKey()) < e.getValue()) return false;
        }
        return true;
    }

    // ---- menu -------------------------------------------------------------------------------------

    void open(Player p) {
        Menu menu = new Menu("The Forge", 6);
        menu.chrome();
        menu.drawer(mn -> draw(p, mn)).refreshEvery(20);
        menu.open(p);
    }

    private void draw(Player p, Menu menu) {
        if (!MiningUtil.ready(p)) return;
        MiningData d = MiningUtil.data(p);
        int slots = slots(p);
        menu.set(4, Icons.of(Material.ANVIL, "&6The Forge",
                "&7Turn rare materials into refined", "&7metals, drill parts, drills and", "&7gear. Every recipe takes time.",
                "", "&7Slots: &a" + slots + "&7/" + Math.max(slots, Math.min(7, m.dials.forgeMaxSlots.get())), "&7Quick Forge: &a-" + Text.commas(m.hotm.value(p, "quick_forge")) + "%",
                "&7Items forged: &a" + Text.commas(d.forgeCrafted)));
        long now = System.currentTimeMillis();
        for (int i = 0; i < 7; i++) {
            int slotNo = i;
            int pos = 10 + i;
            MiningData.ForgeSlot fs = slotOf(d, i);
            if (i >= slots && fs == null) {
                // an item already forging in a slot the player no longer has (the slot rule changed) still shows and claims
                int need = tierForSlot(i + 1);
                String how = need > 0 ? "&7Reach &5Heart of the Mountain Tier " + need
                        : m.dials.forgeCoreSlot.get() && m.hotm.coreLevel(p) < 2 ? "&7Upgrade &5Core of the Mountain &7to II" : "&7Not available";
                menu.set(pos, Icons.of(Material.RED_STAINED_GLASS_PANE, "&cSlot " + (i + 1) + " &7- Locked", how, "&7to unlock this slot."));
            } else if (fs == null) {
                menu.set(pos, Icons.of(Material.FURNACE, "&aSlot " + (i + 1) + " &7- Empty", "&7Pick a recipe to start forging.", "", "&eClick to forge!"),
                        c -> browse(c.player(), slotNo, 0));
            } else {
                ItemStack icon;
                try { icon = Sky.items().create(fs.result, Math.max(1, Math.min(64, fs.amount))); } catch (Exception e) { icon = new ItemStack(Material.BARRIER); }
                List<String> lore = new ArrayList<>();
                lore.add("");
                if (now >= fs.end) {
                    lore.add("&a&lREADY!");
                    lore.add("&eClick to claim!");
                    menu.set(pos, Icons.glow(Icons.withLore(icon, lore)), c -> claim(c.player(), slotNo));
                } else {
                    double frac = (now - fs.start) / (double) Math.max(1, fs.end - fs.start);
                    lore.add("&7Forging: " + Text.bar(frac, 20));
                    lore.add("&7Time left: &e" + Text.duration((fs.end - now) / 1000));
                    menu.set(pos, Icons.withLore(icon, lore));
                }
            }
        }
        menu.set(30, Icons.of(Material.BOOK, "&aForge Recipes", "&7Browse every Forge recipe, what it", "&7needs and how long it takes.", "", "&eClick to browse!"),
                c -> browse(c.player(), -1, 0));
        menu.set(32, Icons.of(Material.IRON_PICKAXE, "&aPickaxe Smith", "&7Buy and upgrade mithril and", "&7titanium pickaxes.", "", "&eClick to shop!"),
                c -> m.menus.openShop(c.player(), "Pickaxe Smith"));
        menu.set(31, Icons.of(Material.PRISMARINE_SHARD, "&2Drill Mechanic", "&7Fit parts and fuel to the drill", "&7in your hand.", "", "&eClick to open!"),
                c -> m.drills.openMechanic(c.player()));
        menu.set(48, Icons.back("Mining"), c -> m.menus.openMain(c.player()));
    }

    private static MiningData.ForgeSlot slotOf(MiningData d, int i) {
        for (MiningData.ForgeSlot s : d.forge) if (s.slot == i) return s;
        return null;
    }

    void browse(Player p, int slot, int page) {
        List<Recipe> list = recipes();
        Sky.menus().paged("Forge Recipes", list, r -> recipeIcon(p, r), pc -> {
            Recipe r = pc.entry();
            if (slot < 0) { int free = firstFree(pc.click().player()); if (free < 0) { Msg.error(pc.click().player(), "All your forge slots are busy."); return; } confirmStart(pc.click().player(), free, r); }
            else confirmStart(pc.click().player(), slot, r);
        }, pg -> pg.set(48, Icons.back("The Forge"), c -> open(c.player())), page).open(p);
    }

    private int firstFree(Player p) {
        MiningData d = MiningUtil.data(p);
        for (int i = 0; i < slots(p); i++) if (slotOf(d, i) == null) return i;
        return -1;
    }

    private ItemStack recipeIcon(Player p, Recipe r) {
        ItemStack it;
        try { it = Sky.items().create(r.result, Math.max(1, Math.min(64, r.amount))); } catch (Exception e) { it = new ItemStack(Material.BARRIER); }
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Needs:");
        for (String c : r.inputs) {
            String[] cc = MiningUtil.cell(c);
            if (cc == null) continue;
            long n = (long) Double.parseDouble(cc[1]);
            if ("COINS".equals(cc[0])) lore.add((Sky.economy().purse(p) >= n ? "&a✔ " : "&c✖ ") + "&6" + Text.commas(n) + " Coins");
            else {
                int h = have(p, cc[0]);
                lore.add((h >= n ? "&a✔ " : "&c✖ ") + MiningUtil.itemName(cc[0]) + " &8x" + n + " &7(" + Text.commas(h) + ")");
            }
        }
        lore.add("");
        lore.add("&7Time: &e" + Text.duration(duration(p, r)));
        boolean unlocked = r.unlock == null || Sky.recipes().unlocked(p, r);
        if (!unlocked) { lore.add(""); lore.add("&cLocked: &7" + Sky.requirements().describe(r.unlock)); }
        else { lore.add(""); lore.add(canAfford(p, r) ? "&eClick to forge!" : "&cYou are missing materials."); }
        return Icons.withLore(it, lore);
    }

    private void confirmStart(Player p, int slot, Recipe r) {
        if (r.unlock != null && !Sky.recipes().unlocked(p, r)) { Sky.requirements().checkOrTell(p, r.unlock); return; }
        if (!canAfford(p, r)) { Msg.error(p, "You don't have everything this recipe needs."); return; }
        Sky.menus().confirm(p, "Start forging?", List.of("&7" + Text.strip(MiningUtil.itemName(r.result)), "&7Takes &e" + Text.duration(duration(p, r))),
                () -> { start(p, slot, r); open(p); }, () -> open(p));
    }

    static final String FAILED = "#FAILED#";

    /**
     * Take every input of a recipe (coins first, then items from inventory and sacks). Returns the
     * ItemData JSON of a unique input of the same category as the result (upgrades keep enchants,
     * reforge, gems...), null when there is none, or {@link #FAILED}.
     */
    String takeInputs(Player p, Recipe r) {
        if (!canAfford(p, r)) { Msg.error(p, "You don't have everything this needs."); return FAILED; }
        ItemDef resDef = Sky.items().def(r.result);
        String carry = null;
        Map<String, Long> need = needs(r);
        Long coins = need.remove("COINS");
        if (coins != null && coins > 0 && !Sky.economy().withdraw(p, coins, "mining: " + r.result)) {
            Msg.error(p, "You don't have enough coins.");
            return FAILED;
        }
        for (Map.Entry<String, Long> e : need.entrySet()) {
            String id = e.getKey();
            int n = (int) Math.min(Integer.MAX_VALUE, e.getValue());
            ItemDef in = Sky.items().def(id);
            if (in != null && in.isUnique() && resDef != null && resDef.isUnique() && carry == null && in.category == resDef.category) {
                String c = takeUnique(p, id);
                // only count it when it was really taken: otherwise the old tool would survive the upgrade
                if (c != null) { carry = c; n--; }
            }
            int took = n > 0 ? Sky.items().remove(p, id, n) : 0;
            if (took < n) took += Math.max(0, Sky.service(Features.Sacks.class).remove(p, id, n - took));
            if (took < n) Sky.log().warning("[mining] " + p.getName() + " was short " + (n - took) + "x " + id + " for " + r.result + " after the check passed");
        }
        return carry;
    }

    /** Create the result, applying carried instance data. */
    static ItemStack makeResult(String result, int amount, String carry) {
        ItemStack out = Sky.items().create(result, amount);
        if (carry != null) {
            try {
                ItemData c = new Gson().fromJson(carry, ItemData.class);
                Sky.items().update(out, x -> {
                    x.reforge = c.reforge;
                    x.enchants.putAll(c.enchants);
                    x.potatoBooks = c.potatoBooks;
                    x.recombobulated = c.recombobulated;
                    x.stars = c.stars;
                    x.gems.putAll(c.gems);
                    x.unlockedGemSlots.addAll(c.unlockedGemSlots);
                    for (Map.Entry<String, JsonElement> e : c.extra.entrySet()) if (!x.extra.has(e.getKey())) x.extra.add(e.getKey(), e.getValue());
                });
            } catch (Exception ex) { Sky.log().warning("[mining] carry-over failed for " + result + ": " + ex.getMessage()); }
        }
        return out;
    }

    /** Takes every input, then fills the slot. */
    boolean start(Player p, int slot, Recipe r) {
        MiningData d = MiningUtil.data(p);
        if (slot < 0 || slot >= slots(p) || slotOf(d, slot) != null) { Msg.error(p, "That forge slot is not free."); return false; }
        String carry = takeInputs(p, r);
        if (FAILED.equals(carry)) return false;
        MiningData.ForgeSlot fs = new MiningData.ForgeSlot();
        fs.slot = slot;
        fs.recipeId = r.id;
        fs.result = r.result;
        fs.amount = Math.max(1, r.amount);
        fs.start = System.currentTimeMillis();
        fs.end = fs.start + duration(p, r) * 1000L;
        fs.carry = carry;
        d.forge.add(fs);
        Sky.profiles().saveNow(p);
        Msg.success(p, "Started forging " + Text.strip(MiningUtil.itemName(r.result)) + "!");
        Msg.sound(p, Sound.BLOCK_ANVIL_USE, 0.8f);
        return true;
    }

    /** Remove one unique stack of this id from the inventory and return its ItemData JSON. */
    private String takeUnique(Player p, String id) {
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length; i++) {
            ItemStack s = inv[i];
            if (s != null && id.equals(Sky.items().idOf(s))) {
                ItemData data = Sky.items().data(s);
                p.getInventory().setItem(i, null);
                data.uuid = null;
                return new Gson().toJson(data);
            }
        }
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off != null && id.equals(Sky.items().idOf(off))) {
            ItemData data = Sky.items().data(off);
            p.getInventory().setItemInOffHand(null);
            data.uuid = null;
            return new Gson().toJson(data);
        }
        // not in the inventory (sacks never hold uniques): take nothing to carry
        return null;
    }

    void claim(Player p, int slot) {
        MiningData d = MiningUtil.data(p);
        MiningData.ForgeSlot fs = slotOf(d, slot);
        if (fs == null) return;
        if (System.currentTimeMillis() < fs.end) { Msg.error(p, "That item is not ready yet."); return; }
        if (!MiningUtil.exists(fs.result)) { Msg.error(p, "This item no longer exists; ask an admin."); return; }
        d.forge.remove(fs);
        ItemStack out = makeResult(fs.result, fs.amount, fs.carry);
        Sky.loot().give(p, out, LootSource.CRAFT);
        d.forgeCrafted++;
        Sky.profiles().saveNow(p);
        Msg.success(p, "You claimed " + Text.strip(MiningUtil.itemName(fs.result)) + (fs.amount > 1 ? " x" + fs.amount : "") + " from the Forge!");
        Msg.sound(p, Sound.BLOCK_SMITHING_TABLE_USE, 1.2f);
    }

    /** Admin: finish every slot of a player now. */
    int finishAll(Player p) {
        int n = 0;
        for (MiningData.ForgeSlot fs : MiningUtil.data(p).forge) { fs.end = System.currentTimeMillis(); n++; }
        return n;
    }

    int ready(Player p) {
        int n = 0;
        long now = System.currentTimeMillis();
        for (MiningData.ForgeSlot fs : MiningUtil.data(p).forge) if (now >= fs.end) n++;
        return n;
    }

    void scheduleRegistration() { Bukkit.getScheduler().runTask(Sky.plugin(), this::registerRecipes); }
}
