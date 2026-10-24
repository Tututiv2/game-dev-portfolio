package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
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
import java.util.List;
import java.util.Map;

/** The Pests screens: overview, traps, pest kills, and the Pestwarden's shop. */
final class PestMenus {

    private PestMenus() {}

    static void open(Player p) { build(p).open(p); }

    static Menu build(Player p) {
        GardenData d = G.data(p);
        GardenMember mem = G.member(p);
        long now = System.currentTimeMillis();
        double bpc = Sky.stats().get(p, Stat.PEST_CHANCE);
        int pen = GardenMath.penalty(G.T.garden.pests.penalty, bpc, d.livePests());
        Menu m = new Menu("Pests", 6).chrome();
        m.set(4, Icons.of(Material.SILVERFISH_SPAWN_EGG, "&cPests",
                "&7Pests show up while you farm your", "&7Garden (from Garden V) and eat into", "&7your &6☘ Farming Fortune&7 while alive.", "",
                "&7Live pests: &c" + d.livePests() + (pen > 0 ? " &8(&c-" + pen + "% ☘&8)" : ""),
                "&7Next pests possible in: &e" + (d.pestCooldownUntil > now ? Text.duration((d.pestCooldownUntil - now) / 1000) : "now"),
                "&2ൠ Bonus Pest Chance: &a" + Text.commasInt(bpc),
                "&7Repellent: " + (mem.repellentUntil > now ? "&a" + mem.repellentFactor + "x &7for &e" + Text.duration((mem.repellentUntil - now) / 1000) : "&cnone"),
                "&7Pests killed: &a" + Text.commasInt(mem.pestsKilled),
                "&7Pests in your vacuum bag: &6" + Text.commasInt(mem.pestBag) + " &8(spend them at the Pestwarden)"));
        int slot = 10;
        for (Map.Entry<String, GardenData.PlotState> e : d.plots.entrySet()) {
            if (e.getValue().pests <= 0) continue;
            if (slot > 16) break;
            String plot = e.getKey();
            m.set(slot++, Icons.of(Material.COARSE_DIRT, "&c" + Text.strip(G.plotName(d, plot)), "&7Pests: &c" + e.getValue().pests, "", "&eClick to teleport!"),
                    c -> { c.player().closeInventory(); Desk.teleport(c.player(), "plot." + plot); });
        }
        if (slot == 10) m.set(13, Icons.of(Material.LIME_DYE, "&aNo infested plots!", "&7Your Garden is pest free."));
        m.set(29, Icons.of(Material.TRIPWIRE_HOOK, "&aPest Traps", "&7Catch pests while you are away.", "&7Up to &a3 &7traps.", "", "&eClick to view!"),
                c -> traps(c.player()).open(c.player()));
        m.set(31, Icons.of(Material.JUKEBOX, "&aPest Radio", "&7Play a vinyl to call one kind", "&7of pest in particular.", "",
                        "&7Playing: " + (d.vinyl == null ? "&cnothing" : G.itemName(d.vinyl)), "", "&eClick to choose!"),
                c -> GardenModule.pests.stereoMenu(c.player()));
        m.set(33, Icons.of(Material.IRON_HOE, "&aPestwarden's Rewards", "&7Spend the Pests in your vacuum", "&7bag, or hand them in for the", "&aPestwarden's Bonus&7.", "", "&eClick to view!"),
                c -> pestwarden(c.player()).open(c.player()));
        m.set(40, Icons.of(Material.BOOK, "&aPest Log", "&7Every pest kind, and how many", "&7of each you have caught.", "", "&eClick to view!"),
                c -> log(c.player()).open(c.player()));
        m.back("The Desk", () -> Desk.build(p));
        return m;
    }

    static Menu log(Player p) {
        GardenData d = G.data(p);
        GardenMember mem = G.member(p);
        Menu m = new Menu("Pest Log", 6).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28};
        int i = 0;
        for (GardenTables.Pest pest : G.T.pests()) {
            long k = mem.pestKills.getOrDefault(pest.id, 0L);
            List<String> lore = new ArrayList<>();
            lore.add("&7Appears from &aGarden " + GardenMath.roman(pest.level));
            if (!pest.crops.isEmpty()) {
                List<String> cn = new ArrayList<>();
                for (String c : pest.crops) cn.add(G.T.crop(c) == null ? c : G.T.crop(c).name);
                lore.add("&7Drawn to: &e" + String.join(", ", cn));
            } else lore.add("&7Drawn to: &eanything");
            List<String> bait = new ArrayList<>();
            for (String a : pest.attractants) bait.add(Text.strip(G.itemName(a)));
            lore.add("&7Spray / bait: &a" + String.join(", ", bait));
            lore.add("");
            lore.add("&7Signature drops:");
            for (String s : pest.signature) lore.add(" " + G.itemName(s));
            lore.add("");
            lore.add("&7Killed: &a" + Text.commasInt(k));
            Material egg = Material.matchMaterial(entityEgg(pest.id));
            m.set(slots[i++], Icons.amount(Icons.of(egg == null ? Material.SPIDER_EYE : egg, "&6" + pest.name, lore), (int) Math.max(1, Math.min(64, k))));
        }
        m.back("Pests", () -> build(p));
        return m;
    }

    static String entityEgg(String pest) {
        return switch (pest) {
            case "fly", "mosquito" -> "BEE_SPAWN_EGG";
            case "cricket", "earthworm" -> "SILVERFISH_SPAWN_EGG";
            case "locust", "mite", "beetle" -> "ENDERMITE_SPAWN_EGG";
            case "rat", "field_mouse" -> "RABBIT_SPAWN_EGG";
            case "moth", "lunar_moth" -> "BAT_SPAWN_EGG";
            case "slug" -> "SLIME_SPAWN_EGG";
            case "dragonfly" -> "PARROT_SPAWN_EGG";
            case "firefly" -> "ALLAY_SPAWN_EGG";
            default -> "FROG_SPAWN_EGG";
        };
    }

    // =====================================================================================
    // traps
    // =====================================================================================

    static final List<String> BAITS = List.of("COMPOST", "HONEY_JAR", "DUNG", "PLANT_MATTER", "CHEESE_FUEL", "JELLY", "MOONDEW", "SQUEAKY_TOY");

    static Menu traps(Player p) {
        GardenData d = G.data(p);
        GardenModule.pests.tickTraps(d, Effects.pet(p, "mosquito_bloodsucker_s_betrayal"));
        Menu m = new Menu("Pest Traps", 4).chrome();
        m.set(4, Icons.of(Material.BOOK, "&aPest Traps",
                "&7Set a trap, bait it, walk away.", "&7Every &a15 minutes &7a baited trap", "&7catches a pest (one bait each).", "",
                "&eClick a slot holding a trap item", "&eto set it."));
        int[] slots = {11, 13, 15};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            if (i < d.traps.size()) {
                GardenData.TrapState t = d.traps.get(i);
                GardenTables.Trap def = G.T.trap(t.item);
                List<String> lore = new ArrayList<>();
                lore.add("&7Bait: " + (t.bait == null ? "&cnone" : G.itemName(t.bait) + " &8x" + t.baitLeft));
                lore.add("&7Caught: &a" + t.caught.size() + "&7/&a" + (def == null ? "?" : def.capacity));
                for (String c : t.caught) lore.add(" &8- &6" + (G.T.pest(c) == null ? c : G.T.pest(c).name));
                lore.add("");
                lore.add(t.caught.isEmpty() ? "&7Nothing to collect yet." : "&eClick to empty the trap!");
                lore.add("&eRight-click to add bait!");
                lore.add("&eShift-click to pick the trap up!");
                m.set(slots[i], Icons.withLore(G.icon(t.item, 1), lore), c -> {
                    GardenData dd = G.data(c.player());
                    if (idx >= dd.traps.size()) return;
                    GardenData.TrapState tt = dd.traps.get(idx);
                    if (c.shift()) {
                        if (!tt.caught.isEmpty()) GardenModule.pests.collectTrap(c.player(), dd, tt);
                        dd.traps.remove(idx);
                        G.give(c.player(), tt.item, 1, LootSource.OTHER);
                        if (tt.bait != null && tt.baitLeft > 0) G.give(c.player(), tt.bait, tt.baitLeft, LootSource.OTHER);
                    } else if (c.right()) bait(c.player(), tt);
                    else GardenModule.pests.collectTrap(c.player(), dd, tt);
                    traps(c.player()).open(c.player());
                });
            } else {
                m.set(slots[i], Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Empty Trap Slot",
                        "&7Needs a Pest Trap, Mouse Trap", "&7or Vermin Trap from your inventory.", "", "&eClick to set one!"), c -> {
                    Player q = c.player();
                    GardenData dd = G.data(q);
                    if (G.level(dd) < 5) { Msg.error(q, "Pests appear from Garden V."); return; }
                    for (GardenTables.Trap tr : G.T.garden.pests.traps) {
                        if (Sky.items().count(q, tr.id) <= 0) continue;
                        if (Sky.items().remove(q, tr.id, 1) < 1) continue;
                        GardenData.TrapState ts = new GardenData.TrapState();
                        ts.item = tr.id;
                        ts.plot = dd.plots.isEmpty() ? null : dd.plots.keySet().iterator().next();
                        ts.last = System.currentTimeMillis();
                        dd.traps.add(ts);
                        Msg.success(q, "Trap set! Right-click it to add bait.");
                        traps(q).open(q);
                        return;
                    }
                    Msg.error(q, "You have no trap in your inventory! The Pestwarden sells them for Pests.");
                });
            }
        }
        m.back("Pests", () -> build(p));
        return m;
    }

    private static void bait(Player p, GardenData.TrapState t) {
        for (String b : BAITS) {
            if (t.bait != null && !t.bait.equals(b) && t.baitLeft > 0) continue;
            long have = G.count(p, b);
            if (have <= 0) continue;
            int n = (int) Math.min(have, 8 - Math.max(0, t.baitLeft));
            if (n <= 0) { Msg.error(p, "The trap is full of bait."); return; }
            if (!G.take(p, G.one(b, n))) return;
            t.bait = b;
            t.baitLeft += n;
            t.last = System.currentTimeMillis();
            Msg.success(p, "Added &a" + n + "x " + Text.strip(G.itemName(b)) + " &7as bait.");
            return;
        }
        Msg.error(p, "You have no bait (Compost, Honey Jar, Dung, Plant Matter, Tasty Cheese, Jelly, Night Dew).");
    }

    // =====================================================================================
    // the Pestwarden (the reference's Pesthunter Phillip) - paid in Pests, the vacuum bag currency (lane 9, G7)
    // =====================================================================================

    static long bonusMinutes() {
        return Math.round(G.perk(G.pestPerk.get()) ? G.pesthunterPerkMinutes.get() : G.pesthunterMinutes.get());
    }

    /** Pests one hand-in takes: all of them, at most as many as reach the Farming Fortune cap (wiki: 40). */
    static long handIn(GardenMember mem) {
        double per = Math.max(0.0001, G.pesthunterPerPest.get());
        int max = (int) Math.max(1, Math.ceil(G.pesthunterCap.get() / per));
        return EffectMath.pestsToHandIn(mem.pestBag, max);
    }

    private static ItemStack bonusIcon(Player p, GardenMember mem) {
        long now = System.currentTimeMillis();
        long give = handIn(mem);
        double offer = EffectMath.pesthunterBonus(give, G.pesthunterPerPest.get(), G.pesthunterCap.get());
        List<String> lore = new ArrayList<>();
        lore.add("&7Hand in the Pests in your vacuum bag");
        lore.add("&7for &6☘ Farming Fortune &7in your Garden");
        lore.add("&7for &a" + bonusMinutes() + " minutes&7.");
        lore.add("&7(&6+" + Effects.fmt(G.pesthunterPerPest.get()) + "☘ &7a Pest, at most &6+" + Effects.fmt(G.pesthunterCap.get()) + "☘&7)");
        lore.add("");
        lore.add("&7Pests in your bag: &6" + Text.commasInt(mem.pestBag));
        lore.add("&7You would hand in: &6" + Text.commasInt(give) + " &7for &6+" + Effects.fmt(offer) + "☘");
        if (mem.pestBonusUntil > now)
            lore.add("&7Running now: &6+" + Effects.fmt(mem.pestBonusFf) + "☘ &7for &e" + Text.duration((mem.pestBonusUntil - now) / 1000));
        lore.add("");
        lore.add(give > 0 ? "&eClick to hand them in!" : "&cVacuum some pests first!");
        return Icons.of(Material.EMERALD, "&aPestwarden's Bonus", lore);
    }

    static void turnIn(Player p) {
        GardenMember mem = G.member(p);
        long now = System.currentTimeMillis();
        long give = handIn(mem);
        if (give <= 0) { Msg.error(p, "Your vacuum bag is empty!"); return; }
        double offer = EffectMath.pesthunterBonus(give, G.pesthunterPerPest.get(), G.pesthunterCap.get());
        if (mem.pestBonusUntil > now && mem.pestBonusFf > offer) {
            Msg.error(p, "Your running bonus (+" + Effects.fmt(mem.pestBonusFf) + "☘) is bigger - come back with more Pests!");
            return;
        }
        mem.pestBag -= give;
        mem.pestsTurnedIn += give;
        mem.pestBonusFf = offer;
        mem.pestBonusUntil = now + bonusMinutes() * 60_000L;
        Sky.stats().invalidate(p);
        Msg.npc(p, "Pestwarden Pell", List.of("Fine work. Here's something for your trouble."), null);
        Msg.success(p, "PESTWARDEN'S BONUS! &6+" + Effects.fmt(offer) + "☘ Farming Fortune &7in your Garden for &a" + bonusMinutes()
                + " minutes&7. &8(-" + give + " Pests)");
        Msg.sound(p, Sound.ENTITY_VILLAGER_YES, 1f);
        pestwarden(p).open(p);
    }

    /** What a shop line is: an item id, or "skin:&lt;barnSkin&gt;". */
    static String entryName(GardenTables.PestShopEntry e) {
        if (e.item.startsWith("skin:")) {
            GardenTables.BarnSkin s = G.T.barnSkin(e.item.substring(5));
            return "&a" + (s == null ? e.item.substring(5) : s.name) + " Barn Skin";
        }
        return G.itemName(e.item);
    }

    static boolean available(GardenTables.PestShopEntry e) {
        if (e.item.startsWith("skin:")) return G.T.barnSkin(e.item.substring(5)) != null;
        return G.exists(e.item);
    }

    static Menu pestwarden(Player p) {
        GardenMember mem = G.member(p);
        Menu m = new Menu("Pestwarden Pell", 6).chrome();
        m.set(4, Icons.of(Material.IRON_HOE, "&aPestwarden Pell", "&7\"Every pest you vacuum goes in the bag.", "&7Bring me the bag and I'll kit you out.\"", "",
                "&7Pests in your bag: &6" + Text.commasInt(mem.pestBag), "&7Pests killed: &a" + Text.commasInt(mem.pestsKilled)));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 41, 42, 43};
        int i = 0;
        for (GardenTables.PestShopEntry e : G.T.garden.pesthunterShop) {
            if (!available(e) || i >= slots.length) continue;
            boolean skin = e.item.startsWith("skin:");
            boolean ownedSkin = skin && G.data(p).barnSkins.contains(e.item.substring(5));
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("&7Cost: &6" + Text.commasInt(e.pests) + " Pests");
            for (Map.Entry<String, Integer> t : e.tradeIn.entrySet()) lore.add("&7Trade in: " + G.itemName(t.getKey()) + (t.getValue() > 1 ? " &8x" + t.getValue() : ""));
            lore.add("");
            lore.add(ownedSkin ? "&aYou own this skin!" : mem.pestBag >= e.pests ? "&eClick to buy!" : "&cNot enough Pests!");
            ItemStack icon = skin ? Icons.of(Material.MOSSY_COBBLESTONE, entryName(e), lore) : Icons.withLore(G.icon(e.item, 1), lore);
            m.set(slots[i++], icon, c -> {
                if (ownedSkin) return;
                List<String> cost = new ArrayList<>(List.of("&7Cost: &6" + Text.commasInt(e.pests) + " Pests"));
                for (Map.Entry<String, Integer> t : e.tradeIn.entrySet()) cost.add("&7Trade in: " + G.itemName(t.getKey()));
                Sky.menus().confirm(c.player(), "Buy " + Text.strip(entryName(e)) + "?", cost, () -> {
                    buy(c.player(), e);
                    pestwarden(c.player()).open(c.player());
                }, () -> pestwarden(c.player()).open(c.player()));
            });
        }
        // Pestwarden's Bonus: hand in the bag for a while of Farming Fortune
        m.set(13, bonusIcon(p, mem), c -> turnIn(c.player()));
        m.back("Pests", () -> build(p));
        return m;
    }

    /** One purchase: Pests and trade-ins checked, then taken, then the item given (all on the main thread). */
    static boolean buy(Player p, GardenTables.PestShopEntry e) {
        GardenMember mem = G.member(p);
        if (mem.pestBag < e.pests) { Msg.error(p, "You need " + Text.commasInt(e.pests) + " Pests in your vacuum bag!"); return false; }
        if (e.item.startsWith("skin:")) {
            GardenData d = G.data(p);
            String id = e.item.substring(5);
            if (d.barnSkins.contains(id)) { Msg.error(p, "You already own that skin!"); return false; }
            mem.pestBag -= e.pests;
            d.barnSkins.add(id);
            Msg.success(p, "You bought the " + Text.strip(entryName(e)) + "! &7Pick it in Barn Skins.");
            return true;
        }
        if (!e.tradeIn.isEmpty() && !G.has(p, e.tradeIn)) {
            List<String> names = new ArrayList<>();
            for (String id : e.tradeIn.keySet()) names.add(Text.strip(G.itemName(id)));
            Msg.error(p, "You need " + String.join(" and ", names) + " to trade in!");
            return false;
        }
        if (!e.tradeIn.isEmpty() && !G.take(p, e.tradeIn)) return false;
        mem.pestBag -= e.pests;
        G.give(p, e.item, 1, LootSource.NPC_SHOP);
        Msg.success(p, "You bought " + G.itemName(e.item) + "&a!");
        Msg.sound(p, Sound.ENTITY_VILLAGER_YES, 1f);
        return true;
    }
}
