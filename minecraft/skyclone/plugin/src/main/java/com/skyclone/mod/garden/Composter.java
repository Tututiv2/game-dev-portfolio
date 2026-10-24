package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Composter (research 08 1.11): organic matter + fuel become Compost on a clock that runs even
 * while you are offline. It never ticks: its state is a pure function of the time since it was last
 * looked at ({@link GardenMath#compost}).
 */
final class Composter {

    private Composter() {}

    static int tier(GardenData d, String id) { return d.composter.upgrades.getOrDefault(id, 0); }

    /** Effect value of an upgrade at the profile's tier, or {@code base} at tier 0. */
    static double value(GardenData d, String id, double base) {
        GardenTables.CompUpgrade u = G.T.composterUpgrade(id);
        int t = tier(d, id);
        if (u == null || t <= 0) return base;
        return u.tiers.get(Math.min(t, u.tiers.size()) - 1).value;
    }

    static double interval(GardenData d) {
        GardenTables.ComposterTable c = G.T.garden.composter;
        double speed = value(d, "speed", 0);
        return GardenMath.composterInterval(c.intervalSeconds, speed) / Math.max(0.01, G.composterSpeedMult.get());
    }

    static double organicCap(GardenData d) { return value(d, "organic_matter_cap", G.T.garden.composter.organicCap); }

    static double fuelCap(GardenData d) { return value(d, "fuel_cap", G.T.garden.composter.fuelCap); }

    static double costFactor(GardenData d) { return 1 - value(d, "cost_reduction", 0) / 100.0; }

    static double organicPer(GardenData d) { return G.T.garden.composter.organicPer * costFactor(d); }

    static double fuelPer(GardenData d) { return G.T.garden.composter.fuelPer * costFactor(d); }

    /** Bring the composter up to date. */
    static void advance(GardenData d) {
        GardenData.ComposterState c = d.composter;
        long now = System.currentTimeMillis();
        if (c.last <= 0) { c.last = now; return; }
        double elapsed = (now - c.last) / 1000.0;
        c.last = now;
        int room = Math.max(0, G.composterMaxStored.get() - c.stored);
        GardenMath.CompostRun run = GardenMath.compost(c.organic, c.fuel, c.progress, elapsed, interval(d), organicPer(d), fuelPer(d), room);
        int extra = 0;
        double multi = value(d, "multi_drop", 0) / 100.0;
        for (int i = 0; i < run.made(); i++) if (ThreadLocalRandom.current().nextDouble() < multi) extra++;
        c.organic = run.organic();
        c.fuel = run.fuel();
        c.progress = run.progress();
        c.stored += run.made() + Math.min(extra, Math.max(0, room - run.made()));
        c.totalMade += run.made() + extra;
    }

    static void open(Player p) { build(p).open(p); }

    static Menu build(Player p) {
        GardenData d = G.data(p);
        advance(d);
        GardenData.ComposterState c = d.composter;
        Menu m = new Menu("Composter", 6).chrome();
        double omCap = organicCap(d), fCap = fuelCap(d);
        // gauges
        int[] omSlots = {37, 28, 19, 10};
        int[] fuelSlots = {43, 34, 25, 16};
        for (int i = 0; i < 4; i++) {
            boolean omOn = c.organic / omCap > i / 4.0;
            boolean fOn = c.fuel / fCap > i / 4.0;
            m.set(omSlots[i], Icons.of(omOn ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    "&aOrganic Matter", "&7" + Text.commasInt(c.organic) + "&8/&7" + Text.shortNum(omCap)));
            m.set(fuelSlots[i], Icons.of(fOn ? Material.ORANGE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                    "&2Fuel", "&7" + Text.commasInt(c.fuel) + "&8/&7" + Text.shortNum(fCap)));
        }
        double iv = interval(d);
        boolean working = c.organic >= organicPer(d) && c.fuel >= fuelPer(d) && c.stored < G.composterMaxStored.get();
        List<String> info = new ArrayList<>();
        info.add("&7Turns organic matter and fuel into");
        info.add("&aCompost&7, even while you are away.");
        info.add("");
        info.add("&7One compost every &a" + Text.duration((long) iv));
        info.add("&7Uses &a" + Text.commasInt(organicPer(d)) + " &7organic matter and &2" + Text.commasInt(fuelPer(d)) + " &7fuel.");
        info.add("");
        info.add(working ? "&7Next compost in &e" + Text.duration((long) Math.max(0, iv - c.progress)) : "&cNot working: " + whyIdle(d));
        info.add("&7Made so far: &a" + Text.commasInt(c.totalMade));
        m.set(4, Icons.of(Material.COMPOSTER, "&aComposter", info));
        m.set(20, Icons.of(Material.WHEAT, "&aAdd Organic Matter",
                "&7Feeds crops from your inventory", "&7into the composter, up to its cap.", "",
                "&7Crops count by how rich they are:", "&8Wheat 1, Carrot 0.29, Melon 0.2...", "&8Enchanted crops count 160x.", "",
                "&eClick to add from your inventory!", "&eRight-click to add from your sacks!"),
                c2 -> { insert(c2.player(), true, c2.right()); open(c2.player()); });
        m.set(24, Icons.of(Material.LIME_DYE, "&2Add Fuel",
                "&7Feeds fuel items into the composter.", "",
                "&7Biofuel &2+3,000&7, Oil Barrel &2+10,000&7,", "&7Volta &2+10,000&7, Sunflower Oil &2+20,000", "",
                "&eClick to add from your inventory!", "&eRight-click to add from your sacks!"),
                c2 -> { insert(c2.player(), false, c2.right()); open(c2.player()); });
        ItemStack out = c.stored > 0 ? Icons.amount(G.icon("COMPOST", 1), Math.min(64, c.stored)) : new ItemStack(Material.BARRIER);
        m.set(22, Icons.withLore(out.getType() == Material.BARRIER ? Icons.of(Material.BARRIER, "&cNo Compost yet") : out,
                List.of("", "&7Waiting: &a" + Text.commasInt(c.stored) + " Compost", "&7Holds up to &a" + G.composterMaxStored.get(),
                        "", c.stored > 0 ? "&eClick to collect!" : "&7Nothing to collect yet.")),
                c2 -> collect(c2.player()));
        m.set(31, Icons.of(Material.ANVIL, "&aComposter Upgrades", "&7Faster, bigger, cheaper.", "", "&eClick to view!"),
                c2 -> upgrades(c2.player()).open(c2.player()));
        m.back("The Desk", () -> Desk.build(p));
        return m;
    }

    private static String whyIdle(GardenData d) {
        GardenData.ComposterState c = d.composter;
        if (c.stored >= G.composterMaxStored.get()) return "full, collect your compost!";
        if (c.organic < organicPer(d)) return "needs organic matter";
        return "needs fuel";
    }

    static void collect(Player p) {
        GardenData d = G.data(p);
        advance(d);
        int n = d.composter.stored;
        if (n <= 0) { Msg.error(p, "There is no compost to collect yet."); return; }
        if (!G.exists("COMPOST")) { Msg.error(p, "Compost is not available right now."); return; }
        d.composter.stored = 0;
        G.give(p, "COMPOST", n, LootSource.GARDEN);
        Msg.success(p, "You collected &a" + n + " Compost&7!");
        Msg.sound(p, Sound.BLOCK_COMPOSTER_READY, 1f);
        open(p);
    }

    /** Move organic matter (crops) or fuel from the inventory, or from sacks, into the composter. */
    static void insert(Player p, boolean organic, boolean fromSacks) {
        GardenData d = G.data(p);
        advance(d);
        GardenData.ComposterState c = d.composter;
        Map<String, Double> table = organic ? G.T.garden.organic : G.T.garden.fuel;
        double cap = organic ? organicCap(d) : fuelCap(d);
        double room = cap - (organic ? c.organic : c.fuel);
        double added = 0;
        if (room <= 0) { Msg.error(p, "The composter can't hold any more " + (organic ? "organic matter" : "fuel") + "!"); return; }
        if (!fromSacks) {
            ItemStack[] inv = p.getInventory().getStorageContents();
            for (int i = 0; i < inv.length && room > 0; i++) {
                ItemStack it = inv[i];
                String id = it == null ? null : Sky.items().idOf(it);
                Double v = id == null ? null : table.get(id);
                if (v == null || v <= 0) continue;
                int fit = (int) Math.min(it.getAmount(), Math.floor(room / v));
                if (fit <= 0) continue;
                int got = Sky.items().remove(p, id, fit);
                added += got * v;
                room -= got * v;
            }
        } else {
            Features.Sacks sacks = Sky.service(Features.Sacks.class);
            for (Map.Entry<String, Double> e : table.entrySet()) {
                if (room <= 0) break;
                int have = sacks.count(p, e.getKey());
                int fit = (int) Math.min(have, Math.floor(room / e.getValue()));
                if (fit <= 0) continue;
                int got = sacks.remove(p, e.getKey(), fit);
                added += got * e.getValue();
                room -= got * e.getValue();
            }
        }
        if (added <= 0) { Msg.error(p, "You have nothing the composter wants" + (fromSacks ? " in your sacks." : ".")); return; }
        if (organic) c.organic += added; else c.fuel += added;
        Msg.success(p, "Added &a" + Text.commasInt(added) + " &7" + (organic ? "organic matter" : "fuel") + ".");
        Msg.sound(p, Sound.BLOCK_COMPOSTER_FILL_SUCCESS, 1f);
    }

    static Menu upgrades(Player p) {
        GardenData d = G.data(p);
        Menu m = new Menu("Composter Upgrades", 4).chrome();
        int slot = 11;
        Material[] icons = {Material.SUGAR, Material.HOPPER, Material.LAVA_BUCKET, Material.CHEST, Material.GOLD_NUGGET};
        int i = 0;
        for (GardenTables.CompUpgrade u : G.T.garden.composter.upgrades) {
            int t = tier(d, u.id);
            int max = u.tiers.size();
            List<String> lore = new ArrayList<>();
            double cur = t == 0 ? baseOf(u.id) : u.tiers.get(t - 1).value;
            lore.add(u.desc.replace("{}", fmt(cur)));
            lore.add("");
            lore.add("&7Tier: &a" + t + "&7/&a" + max);
            boolean locked = G.level(d) < u.level;
            if (t < max) {
                GardenTables.CompTier next = u.tiers.get(t);
                lore.add("&7Next: " + u.desc.replace("{}", fmt(next.value)));
                lore.add("");
                lore.add("&7Cost:");
                lore.add(" &c" + Text.commasInt(next.copper) + " Copper" + (d.copper >= next.copper ? " &a✔" : ""));
                for (Map.Entry<String, Integer> e : next.items.entrySet())
                    lore.add(" " + G.itemName(e.getKey()) + " &8x" + e.getValue() + (G.count(p, e.getKey()) >= e.getValue() ? " &a✔" : ""));
                lore.add("");
                lore.add(locked ? "&cRequires Garden " + GardenMath.roman(u.level) : "&eClick to upgrade!");
            } else lore.add("&aMaxed out!");
            final int nt = t;
            m.set(slot++, Icons.of(icons[i++ % icons.length], (locked ? "&c" : "&a") + u.name + " " + GardenMath.roman(Math.max(1, t)), lore),
                    c -> buyUpgrade(c.player(), u, nt));
        }
        m.back("Composter", () -> build(p));
        return m;
    }

    private static double baseOf(String id) {
        return switch (id) {
            case "fuel_cap" -> G.T.garden.composter.fuelCap;
            case "organic_matter_cap" -> G.T.garden.composter.organicCap;
            default -> 0;
        };
    }

    private static String fmt(double v) { return v >= 1000 ? Text.commasInt(v) : Text.commas(v); }

    static void buyUpgrade(Player p, GardenTables.CompUpgrade u, int seenTier) {
        GardenData d = G.data(p);
        int t = tier(d, u.id);
        if (t != seenTier || t >= u.tiers.size()) { upgrades(p).open(p); return; }
        if (G.level(d) < u.level) { Msg.error(p, "Requires Garden " + GardenMath.roman(u.level) + "!"); return; }
        GardenTables.CompTier next = u.tiers.get(t);
        if (d.copper < next.copper) { Msg.error(p, "You need " + Text.commasInt(next.copper) + " Copper!"); return; }
        for (String id : next.items.keySet()) if (!G.exists(id)) { Msg.error(p, "This upgrade needs an item that is not in the game yet."); return; }
        if (!G.has(p, next.items)) { Msg.error(p, "You don't have the items for this upgrade!"); return; }
        advance(d);
        if (!G.take(p, next.items)) return;
        d.copper -= next.copper;
        d.composter.upgrades.put(u.id, t + 1);
        Msg.success(p, u.name + " upgraded to &a" + GardenMath.roman(t + 1) + "&7!");
        G.award(p, "composter:" + u.id + ":" + (t + 1), 1, "Composter upgrade");
        Msg.sound(p, Sound.BLOCK_ANVIL_USE, 1.4f);
        upgrades(p).open(p);
    }
}
