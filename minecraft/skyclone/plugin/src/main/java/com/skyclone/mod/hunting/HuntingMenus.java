package com.skyclone.mod.hunting;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Every hunting menu: /hunting (the hub), the Hunting Box, the Attribute Menu, one attribute, the Fusion
 * Machine, the Shard Collection, Huntraps, Habitats, Tools, Salts, the Safari desks and the huntaxe attunement.
 */
final class HuntingMenus {

    /** Playtest wave 5: an island's display name (a title-cased id leaked reference names like "Galatea"). */
    private static String islandName(String id) {
        if (id == null) return "";
        com.skyclone.api.world.IslandDef d = com.skyclone.api.Sky.worlds().island(id);
        return d != null && d.displayName != null ? com.skyclone.api.ui.Text.strip(d.displayName) : com.skyclone.api.ui.Text.titleCase(id.replace('_', ' '));
    }

    private HuntingMenus() {}

    // ================================================================= hub

    static Menu main(Player p) {
        Menu m = new Menu("Hunting", 6).chrome();
        SkillService sk = Sky.service(SkillService.class);
        int lvl = sk.level(p, SkillType.HUNTING);
        HuntingData d = Hunt.data(p);
        AttrSnapshot s = Hunt.snapshot(p);
        m.set(4, Icons.of(Material.LEAD, "&aHunting " + Text.roman(Math.max(1, lvl)),
                "&7Track and trap creatures for their", "&7shards, then syphon them into", "&dattributes &7that last for ever.", "",
                "&7Hunting Level: &a" + lvl, "&7Hunting Fortune: &d☘" + HuntMath.fmt(Sky.stats().get(p, Stat.HUNTING_FORTUNE)),
                "&7Charm Chance: &d❣" + HuntMath.fmt(Hunt.charm(p, Sky.worlds().islandOf(p.getWorld()), false)) + "%",
                "&7Shards captured: &a" + Text.commasInt(d.totalCaught), "&7Attributes owned: &a" + s.levels.size() + "&7/&a" + Hunt.cat.size(),
                "", "&eClick for your Hunting skill!"), c -> sk.openMenu(c.player()));
        int boxed = 0;
        for (int v : d.box.values()) boxed += v;
        m.set(20, Icons.of(Material.CHEST, "&aHunting Box", "&7Shards you keep for syphoning and", "&7fusing. Right-click a shard item to", "&7send it here.", "",
                "&7Stored: &a" + Text.commasInt(boxed) + " shards", "", "&eClick to open!"), c -> box(c.player(), 0).open(c.player()));
        m.set(21, Icons.of(Material.ENCHANTED_BOOK, "&dAttribute Menu", "&7Every attribute, its level and what", "&7it does. Syphon shards to level them", "&7and switch them on or off.", "",
                "&7Owned: &a" + s.levels.size() + "&7/&a" + Hunt.cat.size(), "", "&eClick to open!"), c -> attributes(c.player(), "ALL", 0).open(c.player()));
        m.set(22, Icons.of(Material.RESPAWN_ANCHOR, "&5Fusion Machine", "&7Fuse two shards from your Hunting", "&7Box into a different one. Some", "&7shards can ONLY be fused.", "",
                "&eClick to fuse!"), c -> fusion(c.player(), null, null).open(c.player()));
        m.set(23, Icons.of(Material.BOOK, "&aShard Collection", "&7Every shard you have captured,", "&7and where the rest are hiding.", "",
                "&7Discovered: &a" + d.caught.size() + "&7/&a" + Hunt.cat.size(), "", "&eClick to view!"), c -> collection(c.player(), "ALL", 0).open(c.player()));
        m.set(24, Icons.of(Material.SPYGLASS, "&aHabitats", "&7Where critters live, how many are", "&7out right now, and what to catch", "&7them with.", "", "&eClick to view!"),
                c -> habitats(c.player()).open(c.player()));
        m.set(29, Icons.of(Material.TRIPWIRE_HOOK, "&aHuntraps", "&7Traps you have set.", "", "&7Set: &a" + d.traps.size() + "&7/&a" + HDials.TRAP_MAX.get(), "", "&eClick to view!"),
                c -> traps(c.player()).open(c.player()));
        m.set(30, Icons.of(Material.IRON_AXE, "&aHunting Tools", "&7Lassos, nets, black holes,", "&7huntaxes, traps - and how", "&7each one catches.", "", "&eClick to view!"),
                c -> tools(c.player()).open(c.player()));
        m.set(31, Icons.of(Material.SUGAR, "&dSalts", "&7Charm Chance buffs you are under.", "", "&eClick to view!"), c -> salts(c.player()).open(c.player()));
        m.set(32, Icons.of(Material.SPRUCE_SAPLING, "&2Critter Safari", "&7Four biomes of rare critters.", "&7Needs &aHunting " + Text.roman(HDials.SAFARI_LEVEL.get()) + " &7and a ticket.", "",
                Safari.active(p) ? "&aHunt running: &e" + Text.duration(Safari.left(p)) + " left" : "&7No hunt running.", "", "&eClick to view!"),
                c -> safari(c.player()).open(c.player()));
        m.set(33, Icons.of(Material.ENDER_PEARL, "&bTravel to the Safari", "&7Warp to the Critter Safari", "&7entrance.", "", "&eClick to warp!"),
                c -> { c.player().closeInventory(); Sky.worlds().warp(c.player(), "safari"); });
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        return m;
    }

    // ================================================================= hunting box

    static Menu box(Player p, int page) {
        HuntingData d = Hunt.data(p);
        List<ShardDef> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : d.box.entrySet()) {
            ShardDef s = Hunt.cat.get(e.getKey());
            if (s != null && e.getValue() > 0) list.add(s);
        }
        list.sort(Comparator.comparingInt(ShardDef::rarityIndex).reversed().thenComparing(x -> x.name));
        return Sky.menus().paged("Hunting Box", list, s -> {
            int n = Hunt.boxCount(p, s.id);
            List<String> lore = new ArrayList<>(shardLore(p, s));
            lore.add("");
            lore.add("&7In box: &a" + Text.commasInt(n));
            lore.add("");
            lore.add("&eLeft-click for its attribute!");
            lore.add("&eRight-click to take a stack out!");
            return Icons.amount(icon(s, lore), Math.min(64, n));
        }, pc -> {
            Player pl = pc.click().player();
            if (pc.click().right()) {
                Hunt.withdraw(pl, pc.entry().id, pc.click().shift() ? Integer.MAX_VALUE : 64);
                box(pl, pc.page()).open(pl);
            } else attribute(pl, pc.entry()).open(pl);
        }, m -> {
            m.set(48, Icons.back("Hunting"), c -> main(c.player()).open(c.player()));
            m.set(50, Icons.of(Material.HOPPER, "&aDeposit Shards", "&7Move every shard in your inventory", "&7into the Hunting Box.", "", "&eClick to deposit!"), c -> {
                int moved = 0;
                for (ShardDef s : Hunt.cat.all()) if (Sky.items().count(c.player(), s.id) > 0) moved += Hunt.storeFromInventory(c.player(), s.id, Integer.MAX_VALUE);
                Msg.send(c.player(), moved > 0 ? "&aDeposited &f" + moved + " &ashards." : "&7You have no shards in your inventory.");
                box(c.player(), 0).open(c.player());
            });
        }, page);
    }

    // ================================================================= attributes

    static final String[] FILTERS = {"ALL", "OWNED", "Forest", "Water", "Combat"};

    static Menu attributes(Player p, String filter, int page) {
        AttrSnapshot snap = Hunt.snapshot(p);
        HuntingData d = Hunt.data(p);
        List<ShardDef> list = new ArrayList<>();
        for (ShardDef s : Hunt.cat.all()) {
            if ("OWNED".equals(filter) && d.syphoned.getOrDefault(s.id, 0) <= 0) continue;
            if (!"ALL".equals(filter) && !"OWNED".equals(filter) && !filter.equals(s.alignment)) continue;
            list.add(s);
        }
        list.sort(Comparator.comparing((ShardDef x) -> x.alignment).thenComparingInt(ShardDef::rarityIndex).thenComparingInt(x -> x.num));
        return Sky.menus().paged("Attributes", list, s -> {
            int lvl = HuntMath.level(s.rarity, d.syphoned.getOrDefault(s.id, 0));
            boolean off = d.disabled.contains(s.attr);
            List<String> lore = new ArrayList<>(attrLore(p, s, snap));
            lore.add("");
            if (lvl > 0) lore.add(off ? "&cDisabled &7- right-click to enable" : "&aEnabled &7- right-click to disable");
            lore.add("&eLeft-click for details!");
            ItemStack it = lvl > 0 ? icon(s, lore) : Icons.of(Material.GRAY_DYE, Hunt.rarityColor(s.rarity) + s.attrName + " &8(locked)", lore);
            if (lvl > 0) it = Icons.amount(it, lvl);
            return it;
        }, pc -> {
            Player pl = pc.click().player();
            ShardDef s = pc.entry();
            if (pc.click().right() && HuntMath.level(s.rarity, Hunt.data(pl).syphoned.getOrDefault(s.id, 0)) > 0) {
                toggle(pl, s);
                attributes(pl, filter, pc.page()).open(pl);
            } else attribute(pl, s).open(pl);
        }, m -> {
            m.set(48, Icons.back("Hunting"), c -> main(c.player()).open(c.player()));
            int idx = 0;
            for (int i = 0; i < FILTERS.length; i++) if (FILTERS[i].equals(filter)) idx = i;
            String next = FILTERS[(idx + 1) % FILTERS.length];
            List<String> fl = new ArrayList<>();
            for (String f : FILTERS) fl.add((f.equals(filter) ? "&a▶ " : "&7") + Text.titleCase(f));
            fl.add("");
            fl.add("&eClick to switch!");
            m.set(50, Icons.of(Material.HOPPER, "&aFilter", fl), c -> attributes(c.player(), next, 0).open(c.player()));
        }, page);
    }

    static void toggle(Player p, ShardDef s) {
        HuntingData d = Hunt.data(p);
        if (!d.disabled.remove(s.attr)) d.disabled.add(s.attr);
        Hunt.invalidate(p);
        Msg.send(p, (d.disabled.contains(s.attr) ? "&cDisabled " : "&aEnabled ") + Hunt.rarityColor(s.rarity) + s.attrName + "&7.");
    }

    static Menu attribute(Player p, ShardDef s) {
        HuntingData d = Hunt.data(p);
        AttrSnapshot snap = Hunt.snapshot(p);
        int done = d.syphoned.getOrDefault(s.id, 0);
        int lvl = HuntMath.level(s.rarity, done);
        Menu m = new Menu(s.attrName, 6).chrome();
        m.set(13, icon(s, attrLore(p, s, snap)));
        int inBox = Hunt.boxCount(p, s.id);
        List<String> sy = new ArrayList<>(List.of("&7Feed shards from your Hunting Box", "&7into this attribute.", "",
                "&7In box: &a" + inBox, "&7Syphoned: &a" + done + "&7/&a" + HuntMath.maxShards(s.rarity)));
        if (lvl >= HuntMath.MAX_LEVEL) sy.add("&aMaxed!");
        else {
            sy.add("&7Next level: &a" + HuntMath.toNext(s.rarity, done) + " &7more shards");
            sy.add("");
            sy.add("&eLeft-click to syphon one level!");
            sy.add("&eRight-click to syphon as many as you can!");
        }
        if (s.unconsumable) sy = new ArrayList<>(List.of("&cThis shard cannot be syphoned.", "&7Fuse it in the &5Fusion Machine&7."));
        m.set(29, Icons.of(Material.BREWING_STAND, "&dSyphon", sy), c -> {
            Hunt.syphon(c.player(), s, c.right());
            attribute(c.player(), s).open(c.player());
        });
        if (lvl > 0) {
            boolean off = d.disabled.contains(s.attr);
            m.set(31, Icons.of(off ? Material.RED_DYE : Material.LIME_DYE, off ? "&cDisabled" : "&aEnabled",
                    "&7Switch this attribute off without", "&7losing its level.", "", "&eClick to toggle!"), c -> {
                toggle(c.player(), s);
                attribute(c.player(), s).open(c.player());
            });
        }
        m.set(33, Icons.of(Material.RESPAWN_ANCHOR, "&5Fuse this Shard", "&7Use it as the first shard in the", "&7Fusion Machine.", "", "&eClick to fuse!"),
                c -> fusion(c.player(), s, null).open(c.player()));
        m.set(15, Icons.of(Material.MAP, "&aWhere to find it", sourceLore(s)));
        if (inBox > 0) m.set(11, Icons.of(Material.CHEST, "&aTake out", "&7Take &a" + Math.min(64, inBox) + " &7out of the box.", "", "&eClick!"), c -> {
            Hunt.withdraw(c.player(), s.id, 64);
            attribute(c.player(), s).open(c.player());
        });
        m.set(48, Icons.back("Attributes"), c -> attributes(c.player(), "ALL", 0).open(c.player()));
        return m;
    }

    // ================================================================= fusion

    static Menu fusion(Player p, ShardDef a, ShardDef b) {
        Menu m = new Menu("Fusion Machine", 6).chrome();
        m.set(4, Icons.of(Material.RESPAWN_ANCHOR, "&5Fusion Machine", "&7Pick two shards from your Hunting", "&7Box. Each fusion uses a few of", "&7each (Common 5 ... Legendary 1),",
                "&7then choose one result.", "", "&8Same-family pairs make a member", "&8of that family one rarity higher."));
        m.set(20, a == null ? Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&eFirst Shard", "&7Click to pick!") : withCost(p, a),
                c -> pick(c.player(), true, a, b, 0).open(c.player()));
        m.set(24, b == null ? Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&eSecond Shard", "&7Click to pick!") : withCost(p, b),
                c -> pick(c.player(), false, a, b, 0).open(c.player()));
        m.set(22, Icons.of(Material.ARROW, "&7+"));
        if (a != null && b != null) {
            List<Fusion.Output> outs = Fusion.outputs(Hunt.cat, a, b);
            int[] slots = {38, 39, 40, 41, 42};
            if (outs.isEmpty()) m.set(40, Icons.of(Material.BARRIER, "&cThese shards do not fuse."));
            for (int i = 0; i < outs.size() && i < slots.length; i++) {
                Fusion.Output o = outs.get(i);
                ShardDef r = Hunt.cat.get(o.shardId());
                if (r == null) continue;
                List<String> lore = new ArrayList<>(shardLore(p, r));
                lore.add("");
                lore.add("&7Result: &a" + o.amount() + "x &7(" + kindText(o.kind()) + ")");
                lore.add("&7Uses: &f" + Fusion.cost(a) + "x " + a.name + " &7+ &f" + Fusion.cost(b) + "x " + b.name);
                lore.add("");
                lore.add("&eClick to fuse!");
                m.set(slots[i], Icons.amount(icon(r, lore), o.amount()), c -> {
                    if (Hunt.fuse(c.player(), a, b, o)) fusion(c.player(), a, b).open(c.player());
                });
            }
        }
        m.set(48, Icons.back("Hunting"), c -> main(c.player()).open(c.player()));
        return m;
    }

    private static String kindText(String k) {
        if (k.startsWith("family:")) return k.substring(7) + " Fusion";
        return "chameleon".equals(k) ? "Chameleon Fusion" : "Line Fusion";
    }

    private static ItemStack withCost(Player p, ShardDef s) {
        List<String> lore = new ArrayList<>(shardLore(p, s));
        lore.add("");
        lore.add("&7In box: &a" + Hunt.boxCount(p, s.id) + " &7(uses " + Fusion.cost(s) + ")");
        lore.add("&eClick to change!");
        return icon(s, lore);
    }

    private static Menu pick(Player p, boolean first, ShardDef a, ShardDef b, int page) {
        HuntingData d = Hunt.data(p);
        List<ShardDef> list = new ArrayList<>();
        for (Map.Entry<String, Integer> e : d.box.entrySet()) {
            ShardDef s = Hunt.cat.get(e.getKey());
            if (s != null && e.getValue() > 0) list.add(s);
        }
        list.sort(Comparator.comparingInt(ShardDef::rarityIndex).thenComparing(x -> x.name));
        return Sky.menus().paged("Pick a Shard", list, s -> {
            List<String> lore = new ArrayList<>(shardLore(p, s));
            lore.add("");
            lore.add("&7In box: &a" + Hunt.boxCount(p, s.id));
            lore.add("&eClick to pick!");
            return icon(s, lore);
        }, pc -> {
            Player pl = pc.click().player();
            if (first) fusion(pl, pc.entry(), b).open(pl);
            else fusion(pl, a, pc.entry()).open(pl);
        }, m -> m.set(48, Icons.back("Fusion Machine"), c -> fusion(c.player(), a, b).open(c.player())), page);
    }

    // ================================================================= collection

    static Menu collection(Player p, String filter, int page) {
        HuntingData d = Hunt.data(p);
        List<ShardDef> list = new ArrayList<>();
        for (ShardDef s : Hunt.cat.all()) if ("ALL".equals(filter) || filter.equals(s.alignment)) list.add(s);
        list.sort(Comparator.comparing((ShardDef x) -> x.alignment).thenComparingInt(ShardDef::rarityIndex).thenComparingInt(x -> x.num));
        return Sky.menus().paged("Shard Collection", list, s -> {
            long n = d.caught.getOrDefault(s.id, 0L);
            List<String> lore = new ArrayList<>();
            lore.add("&8" + s.alignment + " shard (ID " + s.code + ")");
            lore.add("");
            if (n > 0) {
                lore.add("&7Captured: &a" + Text.commasInt(n));
                lore.add("&7Attribute: " + Hunt.rarityColor(s.rarity) + s.attrName);
            } else lore.add("&7Not captured yet.");
            lore.add("");
            lore.addAll(sourceLore(s));
            return n > 0 ? icon(s, lore) : Icons.of(Material.GRAY_DYE, "&7" + s.name + " Shard", lore);
        }, null, m -> {
            m.set(48, Icons.back("Hunting"), c -> main(c.player()).open(c.player()));
            String[] f = {"ALL", "Forest", "Water", "Combat"};
            int idx = 0;
            for (int i = 0; i < f.length; i++) if (f[i].equals(filter)) idx = i;
            String next = f[(idx + 1) % f.length];
            m.set(50, Icons.of(Material.HOPPER, "&aFilter: &e" + Text.titleCase(filter), "&eClick to switch!"), c -> collection(c.player(), next, 0).open(c.player()));
        }, page);
    }

    // ================================================================= traps, habitats, tools, salts

    static Menu traps(Player p) {
        Menu m = new Menu("Huntraps", 6).chrome();
        HuntingData d = Hunt.data(p);
        long now = System.currentTimeMillis();
        int[] slots = {20, 21, 22, 23, 24, 29, 30, 31, 32, 33};
        for (int i = 0; i < d.traps.size() && i < slots.length; i++) {
            HuntingData.Trap t = d.traps.get(i);
            boolean ready = t.readyAt <= now;
            ItemDef it = Sky.items().def(t.item);
            final int idx = i;
            m.set(slots[i], Icons.of(ready ? Material.TRIPWIRE_HOOK : Material.STRING,
                    (ready ? "&a" : "&e") + (it == null ? "Huntrap" : Text.strip(it.name)),
                    "&7Set in: &e" + Tools.habitatName(t.habitat),
                    ready ? "&aSomething is caught!" : "&7Ready in: &e" + Text.duration((t.readyAt - now) / 1000), "",
                    ready ? "&eClick to collect!" : "&eClick to pick it up early (no catch)!"), c -> {
                Tools.collectTrap(c.player(), idx);
                traps(c.player()).open(c.player());
            });
        }
        if (d.traps.isEmpty()) m.set(22, Icons.of(Material.GRAY_DYE, "&7No traps set", "&7Right-click a Huntrap inside a", "&7critter habitat to set it."));
        m.set(48, Icons.back("Hunting"), c -> main(c.player()).open(c.player()));
        return m;
    }

    static Menu habitats(Player p) {
        List<ShardCatalog.Habitat> list = new ArrayList<>(Hunt.cat.habitats());
        Map<String, Integer> live = Critters.counts();
        return Sky.menus().paged("Habitats", list, h -> {
            List<String> lore = new ArrayList<>();
            com.skyclone.api.world.IslandDef isl = Sky.worlds().island(h.island);
            lore.add("&7Island: &e" + (isl != null && isl.displayName != null ? Text.strip(isl.displayName) : Text.titleCase(h.island)));
            lore.add("&7Kind: &f" + ("water".equals(h.kind) ? "Water (Fishing Net)" : "combat".equals(h.kind) ? "Combat (Black Hole)" : "Land (Lasso)"));
            if (h.safari) lore.add("&2Critter Safari biome (Capsules)");
            lore.add("&7Critters out now: &a" + live.getOrDefault(h.id, 0));
            lore.add("");
            lore.add("&7Lives here:");
            int n = 0;
            for (String c : h.critters) {
                ShardDef s = Hunt.cat.byCritter(c);
                if (s == null) continue;
                if (n++ >= 10) { lore.add("&8...and " + (h.critters.size() - 10) + " more"); break; }
                lore.add(" " + Hunt.rarityColor(s.rarity) + s.name);
            }
            Material m = "water".equals(h.kind) ? Material.COD : "combat".equals(h.kind) ? Material.IRON_SWORD : h.safari ? Material.SPRUCE_SAPLING : Material.OAK_SAPLING;
            return Icons.of(m, "&a" + h.name, lore);
        }, null, m -> m.set(48, Icons.back("Hunting"), c -> main(c.player()).open(c.player())), 0);
    }

    /** Tools on offer at the Huntmaster: coin prices for the starters, the rest are crafted (/recipe). // TUNE prices */
    static final Object[][] SHOP = {
            {"ABYSMAL_LASSO", 1_000.0}, {"BASIC_FISHING_NET", 5_000.0}, {"RETIA_BASICA", 10_000.0},
            {"SMALL_POCKET_BLACK_HOLE", 100_000.0}, {"MEDIUM_POCKET_BLACK_HOLE", 1_000_000.0}, {"VENATOR_GENESIS", 5_000.0},
    };

    static Menu tools(Player p) {
        Menu m = new Menu("Hunting Tools", 6).chrome();
        String[][] rows = {
                {"ABYSMAL_LASSO", "VINERIP_LASSO", "ENTANGLER_LASSO", "EVERSTRETCH_LASSO"},
                {"BASIC_FISHING_NET", "MEDIUM_FISHING_NET", "TURBO_FISHING_NET", "GIGANTIC_FISHING_NET"},
                {"SMALL_POCKET_BLACK_HOLE", "MEDIUM_POCKET_BLACK_HOLE", "LARGE_POCKET_BLACK_HOLE", "VENATOR_GENESIS", "SILVA_DOMINUS", "CURSUS_FERAE", "APEX_PRAEDATOR"},
                {"RETIA_BASICA", "RETIA_MELIORA", "RETIA_ROBUSTA", "RETIA_FORTA", "RETIA_SUPREMA"},
        };
        int[] starts = {10, 19, 28, 37};
        for (int r = 0; r < rows.length; r++) {
            for (int i = 0; i < rows[r].length && i < 7; i++) {
                String id = rows[r][i];
                if (!Sky.items().exists(id)) continue;
                ItemStack base = Sky.items().create(id);
                Double price = price(id);
                List<String> extra = new ArrayList<>();
                extra.add("");
                if (price != null) { extra.add("&7Cost: &6" + Text.commasInt(price) + " coins"); extra.add("&eClick to buy!"); }
                else extra.add("&eClick to see the recipe!");
                m.set(starts[r] + i, Icons.withLore(base, extra), c -> {
                    if (price != null) buy(c.player(), id, price);
                    else Sky.commands().run(c.player(), "recipe", id);
                });
            }
        }
        m.set(48, Icons.back("Hunting"), c -> main(c.player()).open(c.player()));
        return m;
    }

    static Double price(String id) {
        for (Object[] r : SHOP) if (r[0].equals(id)) return (Double) r[1];
        return null;
    }

    static void buy(Player p, String id, double price) {
        if (!Sky.economy().withdraw(p, price, "hunting: buy " + id)) {
            Msg.error(p, "&cYou need &6" + Text.commasInt(price) + " coins&c.");
            return;
        }
        Sky.loot().give(p, id, 1, LootSource.NPC_SHOP);
        Msg.success(p, "&aBought " + Sky.items().def(id).name + "&a!");
    }

    static Menu salts(Player p) {
        Menu m = new Menu("Salts", 4).chrome();
        HuntingData d = Hunt.data(p);
        long now = System.currentTimeMillis();
        int slot = 10;
        for (Salts.Salt s : Salts.all().values()) {
            long until = d.salts.getOrDefault(s.id, 0L);
            ItemDef def = Sky.items().def(s.id);
            String nm = def == null ? Text.titleCase(s.id) : Text.strip(def.name);
            List<String> lore = new ArrayList<>(List.of("&7Charm Chance: &d+" + HuntMath.fmt(s.charm) + "❣",
                    "&7Towards: &f" + (s.target == null ? "every mob" : (s.target.startsWith("island:") ? islandName(s.target.substring(7)) : Text.titleCase(s.target.replace('_', ' ')))), "&7Lasts: &a" + HuntMath.fmt(s.hours) + "h", ""));
            lore.add(until > now ? "&aActive: &e" + Text.duration((until - now) / 1000) + " left" : "&7Not active. Left-click one to eat it.");
            m.set(slot++, Icons.of(until > now ? Material.GLOWSTONE_DUST : Material.SUGAR, (until > now ? "&a" : "&7") + nm, lore));
            if (slot == 17) slot = 19;
        }
        m.set(m.closeSlot() - 1, Icons.back("Hunting"), c -> main(c.player()).open(c.player()));
        return m;
    }

    // ================================================================= safari desks

    static Menu safari(Player p) {
        Menu m = new Menu("Critter Safari", 6).chrome();
        HuntingData d = Hunt.data(p);
        m.set(4, Icons.of(Material.SPRUCE_SAPLING, "&2Critter Safari",
                "&7Four biomes of critters you catch", "&7with &aCritter Capsules&7. A ticket starts", "&7a timed hunt.", "",
                Safari.active(p) ? "&aHunt running: &e" + Text.duration(Safari.left(p)) + " left" : "&7No hunt running.",
                "&7Caught this hunt: &a" + d.safariCaught, "&7Sparkling caught ever: &d" + d.sparkling));
        m.set(20, Icons.of(Material.PAPER, "&aBuy a Basic Ticket", "&7" + "32 capsules, " + HuntMath.fmt(HDials.SAFARI_MINUTES.get()) + " minutes.", "",
                "&7Cost: &6" + Text.commasInt(HDials.TICKET_PRICE.get()) + " coins", "", "&eClick to buy!"), c -> {
            buy(c.player(), "SAFARI_TICKET_BASIC", HDials.TICKET_PRICE.get());
            safari(c.player()).open(c.player());
        });
        m.set(21, Icons.of(Material.FILLED_MAP, "&aUpgrade the Ticket in Hand", "&7Basic -> Economy: &2500 Forest Essence",
                "&7Economy -> Premium: &21,500", "&7Premium -> First-Class: &25,000", "", "&eClick to upgrade!"), c -> {
            c.player().closeInventory();
            Safari.upgradeTicket(c.player());
        });
        List<String> ms = new ArrayList<>(List.of("&7Hunts per biome raise your", "&aSafari Belt&7's bonus.", ""));
        for (String b : Safari.BIOMES) {
            long n = d.safariHunts.getOrDefault(b, 0L);
            ms.add("&e" + Text.titleCase(b) + "&7: &a" + n + " &8(tier " + Safari.milestoneTier(n) + "/" + Safari.MILESTONES.length + ")");
        }
        ms.add("");
        ms.add("&7Belt bonus: &d+" + Safari.milestoneTiers(d) + " ☘ Hunting Fortune");
        m.set(23, Icons.of(Material.LEATHER, "&6Safari Milestones", ms));
        m.set(24, Icons.of(Material.EMERALD, "&dSafari Perks", "&7Spend &aSafari Essence &7on lasting", "&7perks.", "", "&7Essence: &a" + Text.commasInt(Safari.essence(p)), "", "&eClick to open!"),
                c -> perks(c.player()).open(c.player()));
        if (Safari.active(p)) m.set(31, Icons.of(Material.BARRIER, "&cEnd Hunt Early", "&7Unused capsules vanish.", "", "&eClick to end!"), c -> {
            Safari.end(c.player(), "You ended the hunt.");
            c.player().closeInventory();
        });
        m.set(48, Icons.back("Hunting"), c -> main(c.player()).open(c.player()));
        return m;
    }

    static Menu perks(Player p) {
        Menu m = new Menu("Safari Perks", 4).chrome();
        HuntingData d = Hunt.data(p);
        int slot = 11;
        String[][] text = {{"capsules", "&7+2 Critter Capsules per ticket"}, {"essence", "&7+5% Safari Essence"}, {"sparkle", "&7+10% Sparkling chance"}};
        for (String[] t : text) {
            int[] def = Safari.PERKS.get(t[0]);
            int lvl = d.safariPerks.getOrDefault(t[0], 0);
            List<String> lore = new ArrayList<>(List.of(t[1] + " &8per level", "", "&7Level: &a" + lvl + "&7/&a" + def[0]));
            if (lvl < def[0]) { lore.add("&7Cost: &a" + Text.commasInt((long) def[1] * (lvl + 1)) + " Safari Essence"); lore.add("&eClick to upgrade!"); }
            else lore.add("&aMaxed!");
            m.set(slot, Icons.of(Material.EXPERIENCE_BOTTLE, "&a" + Text.titleCase(t[0]), lore), c -> {
                Safari.buyPerk(c.player(), t[0]);
                perks(c.player()).open(c.player());
            });
            slot += 2;
        }
        if (Sky.items().exists("MORNING_DEW")) m.set(17, Icons.withLore(Sky.items().create("MORNING_DEW"), List.of("", "&7Cost: &a2,500 Safari Essence", "&eClick to buy!")), c -> {
            if (Safari.takeEssence(c.player(), 2500)) { Sky.loot().give(c.player(), "MORNING_DEW", 1, LootSource.NPC_SHOP); Msg.success(c.player(), "&aBought Morning Dew!"); }
            else Msg.error(c.player(), "&cYou need 2,500 Safari Essence.");
        });
        m.set(m.closeSlot() - 1, Icons.back("Critter Safari"), c -> safari(c.player()).open(c.player()));
        return m;
    }

    // ================================================================= huntaxe attunement

    static Menu huntaxe(Player p, String axe) {
        List<ItemStack> weapons = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType().isAir()) continue;
            String id = Sky.items().idOf(it);
            ItemDef d = id == null ? null : Sky.items().def(id);
            if (d == null || d.category == null || !d.category.isWeapon() || "huntaxe".equals(Hunt.tool(id)) || ids.contains(id)) continue;
            ids.add(id);
            weapons.add(it);
        }
        return Sky.menus().paged("Borrowed Edge", ids, id -> {
            ItemDef d = Sky.items().def(id);
            boolean cur = id.equals(Hunt.data(p).huntaxeWeapon.get(axe));
            return Icons.withLore(Sky.items().create(id), List.of("", cur ? "&aAttuned!" : "&eClick to attune your huntaxe!", "&8" + d.id));
        }, pc -> {
            Hunt.data(pc.click().player()).huntaxeWeapon.put(axe, pc.entry());
            Sky.stats().invalidate(pc.click().player());
            Msg.success(pc.click().player(), "&aYour huntaxe now absorbs " + Sky.items().def(pc.entry()).name + "&a.");
            pc.click().player().closeInventory();
        }, m -> m.set(4, Icons.of(Material.GOLDEN_AXE, "&6Borrowed Edge", "&7Pick a weapon you carry: your", "&7huntaxe borrows part of its", "&7damage and stats. It is not consumed.")), 0);
    }

    // ================================================================= lore helpers

    static ItemStack icon(ShardDef s, List<String> lore) {
        ItemDef d = Sky.items().def(s.id);
        String name = Hunt.rarityColor(s.rarity) + s.name + " Shard";
        return d != null && d.skull != null ? Icons.skull(d.skull, name, lore) : Icons.of(Material.PRISMARINE_SHARD, name, lore);
    }

    static List<String> shardLore(Player p, ShardDef s) {
        List<String> l = new ArrayList<>();
        l.add(Hunt.rarityColor(s.rarity) + s.attrName + " &8(" + s.alignment + ", " + s.code + ")");
        l.addAll(Text.wrap(s.effectText(1, 1), 38));
        return l;
    }

    static List<String> attrLore(Player p, ShardDef s, AttrSnapshot snap) {
        HuntingData d = Hunt.data(p);
        int done = d.syphoned.getOrDefault(s.id, 0);
        int lvl = HuntMath.level(s.rarity, done);
        double echo = snap.echoMult.getOrDefault(s.attr, 1.0);
        List<String> l = new ArrayList<>();
        l.add("&8" + s.name + " Shard - " + s.alignment + " (" + s.code + ")");
        l.add("");
        l.add("&7Level: " + (lvl > 0 ? "&a" + Text.roman(lvl) : "&c0") + "&7/&a" + Text.roman(HuntMath.MAX_LEVEL));
        if (lvl > 0) l.addAll(Text.wrap(s.effectText(lvl, echo), 38));
        else l.addAll(Text.wrap(s.effectText(1, 1), 38));
        if (echo > 1.0001) l.add("&8(Echo +" + HuntMath.fmt((echo - 1) * 100) + "%)");
        if (lvl < HuntMath.MAX_LEVEL) {
            l.add("");
            l.add("&7Next: " + Text.wrap(s.effectText(lvl + 1, echo), 38).get(0));
            l.add("&7Shards to next: &a" + HuntMath.toNext(s.rarity, done) + " &8(box: " + Hunt.boxCount(p, s.id) + ")");
        }
        if ("hook".equals(s.effect.kind)) { l.add(""); l.add("&8Works with another system (see /hunting)."); }
        return l;
    }

    static List<String> sourceLore(ShardDef s) {
        List<String> l = new ArrayList<>();
        l.add("&7Found:");
        for (ShardDef.Source src : s.sources) {
            switch (src.type) {
                case "mob" -> {
                    List<String> names = new ArrayList<>();
                    for (String id : src.mobs) {
                        var md = Sky.mobs().def(id);
                        String n = md == null ? id : Text.strip(md.name);
                        if (!names.contains(n)) names.add(n);
                    }
                    l.add(" &c✦ &7Killing &f" + String.join(", ", names.subList(0, Math.min(3, names.size()))));
                    l.add("   &8(Charm Chance drop, or a Black Hole)");
                }
                case "critter" -> {
                    String tool = switch (src.method == null ? "" : src.method) {
                        case "net" -> "a Fishing Net";
                        case "blackhole" -> "a Black Hole";
                        default -> "a Lasso";
                    };
                    l.add(" &a✦ &7A critter, with " + tool);
                    List<String> hs = new ArrayList<>();
                    for (String h : src.habitats) hs.add(Tools.habitatName(h));
                    if (!hs.isEmpty()) l.add("   &8in " + String.join(", ", hs.subList(0, Math.min(3, hs.size()))));
                }
                case "slayer" -> {
                    List<String> names = new ArrayList<>();
                    for (String id : src.mobs) {
                        var md = Sky.mobs().def(id);
                        String n = md == null ? id : Text.strip(md.name);
                        if (!names.contains(n)) names.add(n);
                    }
                    l.add(" &c✦ &7Every Tier IV and V slayer boss");
                    l.add("   &8(its own drop pool from slayer level 5)");
                    if (!names.isEmpty()) l.add("   &8" + String.join(", ", names.subList(0, Math.min(3, names.size()))) + (names.size() > 3 ? "..." : ""));
                }
                case "kuudra" -> {
                    l.add(" &6✦ &7Every paid Kuudra chest");
                    l.add("   &8(guaranteed, more at higher tiers)");
                }
                default -> l.add(" &5✦ &7Fusion only");
            }
        }
        return l;
    }
}
