package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.fishing.FishingCatalog.Pool;
import com.skyclone.mod.fishing.FishingCatalog.PoolCreature;
import com.skyclone.mod.fishing.FishingCatalog.ShopEntry;
import com.skyclone.mod.fishing.FishingCatalog.TrophyFish;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Every fishing menu. Reachable from /fishing (and the SkyBlock Menu, which runs /fishing). */
final class FishMenus {

    /** Playtest wave 5: an island's display name (a title-cased id leaked reference names like "Galatea"). */
    private static String islandName(String id) {
        if (id == null) return "";
        com.skyclone.api.world.IslandDef d = com.skyclone.api.Sky.worlds().island(id);
        return d != null && d.displayName != null ? com.skyclone.api.ui.Text.strip(d.displayName) : com.skyclone.api.ui.Text.titleCase(id.replace('_', ' '));
    }

    private FishMenus() {}

    // ---- main -------------------------------------------------------------------------------------

    static void main(Player p) {
        if (!Fishing.ready(p)) return;
        Menu m = new Menu("Fishing", 6).chrome();
        FishData d = Fishing.data(p);
        SkillService sk = Sky.service(SkillService.class);
        int lvl = Fishing.level(p);
        double prog = sk.progress(p, SkillType.FISHING), need = sk.needed(p, SkillType.FISHING);
        List<String> head = new ArrayList<>();
        head.add("&7Fishing Skill: &a" + lvl);
        if (need > 0) {
            head.add("&7Progress: &e" + Text.commas(Math.floor(prog)) + "&6/&e" + Text.shortNum(need));
            head.add(Text.bar(prog / need, 20));
        }
        head.add("");
        head.add("&7Cast into water with a rod, wait for");
        head.add("&c&l!!! &7over your bobber, then reel in.");
        head.add("&7Lava needs a &6Lava Rod&7.");
        m.set(4, Icons.of(Material.FISHING_ROD, "&aFishing", head));

        List<String> sl = new ArrayList<>(statLines(p, p.getLocation(), null));
        sl.add("");
        sl.add("&eClick to view details!");
        m.set(20, Icons.of(Material.PRISMARINE_SHARD, "&aYour Fishing Stats", sl), c -> stats(c.player()));
        m.set(22, Icons.of(Material.COMPASS, "&aFishing Here", hereSummary(p)), c -> here(c.player()));
        int caughtKinds = 0;
        for (String id : creatureOrder()) if (d.caught(id) > 0) caughtKinds++;
        m.set(24, Icons.of(Material.BOOK, "&aSea Creature Guide",
                "&7Every creature in the water and lava,", "&7where it bites and what it drops.",
                "", "&7Discovered: &a" + caughtKinds + "&7/" + creatureOrder().size(), "", "&eClick to browse!"), c -> guide(c.player(), 0));
        int title = Trophies.title(d);
        m.set(29, Icons.of(Material.TROPICAL_FISH, "&6Trophy Fishing",
                "&7Lava fish of the Crimson Isle, four", "&7tiers each.", "",
                "&7Title: " + (title == 0 ? "&8None yet" : "&6" + Trophies.titleName(title)),
                "&7Trophy fish caught: &a" + Text.commasInt(d.trophyTotal), "", "&eClick to view!"), c -> trophies(c.player(), false));
        m.set(31, Icons.of(Material.WHEAT_SEEDS, "&aBaits",
                "&7One bait is used per cast and", "&7changes what bites.", "",
                "&7Preferred: " + (d.preferredBait == null ? "&8first found" : Fishing.name(d.preferredBait)), "", "&eClick to browse!"), c -> baits(c.player()));
        m.set(33, Icons.of(Material.ANVIL, "&aTackle-smith",
                "&7Fit your rod with a &9Hook&7, a &9Line", "&7and a &9Sinker&7.", "", "&eClick to open!"), c -> RodParts.open(c.player()));
        m.set(38, Icons.of(Material.MAGENTA_DYE, "&dFishing Hotspots",
                "&7Rings on the water that boost your", "&7fishing and attract hotspot creatures.", "",
                "&7Active now: &d" + Hotspots.live().size(), "", "&eClick to view!"), c -> hotspots(c.player()));
        m.set(40, Icons.of(Material.CLOCK, "&bFestivals & Events", eventLines()), c -> events(c.player()));
        int out = ChumBuckets.owned(p.getUniqueId()).size();
        m.set(13, Icons.of(Material.BUCKET, "&aChum Buckets",
                "&7Set one down by the water: slain", "&7sea creatures drop &aChum&7, deposits", "&7pay coins and Fishing XP.", "",
                "&7Your buckets out: &a" + out, "&7Here: &a" + ChumBuckets.inReach(p.getLocation(), false) + " &7in reach", "",
                "&eClick to view!"), c -> ChumBuckets.menu(c.player()));
        int cap = Baits.capacity(p);
        m.set(42, Icons.of(cap > 0 ? Material.CHEST : Material.GRAY_DYE, "&aFishing Bag",
                cap > 0 ? List.of("&7Holds your baits.", "", "&7Slots: &a" + cap, "", "&eClick to open!")
                        : List.of("&7Holds your baits.", "", "&cUnlocks at Raw Cod Collection III")), c -> Baits.openBag(c.player()));
        m.set(50, Icons.of(Material.PAPER, "&aFishing Records",
                "&7Casts: &a" + Text.commasInt(d.casts), "&7Items caught: &a" + Text.commasInt(d.itemsCaught),
                "&7Sea creatures hooked: &a" + Text.commasInt(sum(d.caught)), "&7Sea creatures killed: &a" + Text.commasInt(sum(d.kills)),
                "&7Double hooks: &a" + Text.commasInt(d.doubleHooks),
                "&7Treasure: &6" + d.treasureGood + " &9" + d.treasureGreat + " &d" + d.treasureOutstanding,
                "&7Magmafish filleted: &6" + Text.commasInt(d.magmafishFilleted),
                "&7Puddle Jumpers ridden: &9" + d.puddleRides + "  &7Isopod Husks eaten: &3" + d.isopodHusks + "&7/" + FishDials.HUSK_MAX.get()));
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        m.open(p);
    }

    static long sum(Map<String, Long> m) { long t = 0; for (long v : m.values()) t += v; return t; }

    // ---- stats -----------------------------------------------------------------------------------

    static List<String> statLines(Player p, Location at, Casting.Cast cast) {
        List<String> l = new ArrayList<>();
        String island = Fishing.island(at);
        int cap = Fishing.CAT.fsCap(island, FishDials.FS_CAP.get());
        double chumFs = ChumBuckets.fishingSpeed(at);
        double fs = Sky.stats().get(p, Stat.FISHING_SPEED) + chumFs;
        double scc = Sky.stats().get(p, Stat.SEA_CREATURE_CHANCE);
        l.add(Stat.FISHING_SPEED.label() + ": &f" + Text.commas(fs) + " &8(cap " + cap + ")"
                + (chumFs > 0 ? " &8(+" + Text.commas(chumFs) + " Chumcap)" : ""));
        l.add(Stat.SEA_CREATURE_CHANCE.label() + ": &f" + Text.commas(scc) + "%");
        l.add(Stat.TREASURE_CHANCE.label() + ": &f" + Text.commas(Sky.stats().get(p, Stat.TREASURE_CHANCE)) + "%");
        l.add(Stat.DOUBLE_HOOK_CHANCE.label() + ": &f" + Text.commas(Sky.stats().get(p, Stat.DOUBLE_HOOK_CHANCE)) + "%");
        l.add(Stat.TROPHY_FISH_CHANCE.label() + ": &f" + Text.commas(Sky.stats().get(p, Stat.TROPHY_FISH_CHANCE)) + "%");
        l.add(Stat.FISHING_WISDOM.label() + ": &f" + Text.commas(Sky.stats().get(p, Stat.FISHING_WISDOM)));
        double avg = FishMath.averageWaitSeconds(fs, cap, 0);
        l.add("&7Average wait here: &a" + Text.commas(Math.round(avg * 10) / 10.0) + "s");
        for (String s : FishDials.SCC_PENALTY_ISLANDS.get().split(","))
            if (s.trim().equals(island)) l.add("&cSea Creature Chance is divided by " + Text.commas(FishDials.SCC_PENALTY_DIVISOR.get()) + " here.");
        return l;
    }

    static void stats(Player p) {
        Menu m = new Menu("Your Fishing Stats", 6).chrome();
        Stat[] stats = {Stat.FISHING_SPEED, Stat.SEA_CREATURE_CHANCE, Stat.TREASURE_CHANCE, Stat.DOUBLE_HOOK_CHANCE,
                Stat.TROPHY_FISH_CHANCE, Stat.FISHING_WISDOM, Stat.MAGIC_FIND};
        Material[] icons = {Material.FEATHER, Material.PRISMARINE_CRYSTALS, Material.CHEST, Material.TRIPWIRE_HOOK,
                Material.TROPICAL_FISH, Material.EXPERIENCE_BOTTLE, Material.STICK};
        String[] what = {"Shortens the wait before something bites.", "Chance a catch is a sea creature.",
                "Chance a non-creature catch is treasure.", "Chance a sea creature brings a twin.",
                "Raises every trophy fish's chance.", "Bonus Fishing XP.", "Better rare drops from sea creatures."};
        int[] slots = {19, 20, 21, 22, 23, 24, 25};
        for (int i = 0; i < stats.length; i++) {
            Stat s = stats[i];
            List<String> lore = new ArrayList<>();
            lore.add("&7" + what[i]);
            lore.add("");
            lore.add("&7Total: " + "&" + s.color + Text.commas(Sky.stats().get(p, s)) + (s.percent ? "%" : ""));
            Map<String, Double> br = Sky.stats().breakdown(p, s);
            if (br != null && !br.isEmpty()) {
                lore.add("");
                lore.add("&7From:");
                int n = 0;
                for (Map.Entry<String, Double> e : br.entrySet()) {
                    if (e.getValue() == 0) continue;
                    lore.add(" &8- &7" + Text.titleCase(e.getKey()) + ": &f" + Text.commas(e.getValue()));
                    if (++n >= 8) break;
                }
            }
            m.set(slots[i], Icons.of(icons[i], s.label(), lore));
        }
        List<String> here = statLines(p, p.getLocation(), null);
        m.set(31, Icons.of(Material.CLOCK, "&aWait Time", here.subList(6, here.size())));
        m.set(4, Icons.of(Material.FISHING_ROD, "&aYour Fishing Stats", "&7Baits, hotspots and your rod's parts", "&7add to these while you fish."));
        m.set(48, Icons.back("Fishing"), c -> main(c.player()));
        m.open(p);
    }

    // ---- here -------------------------------------------------------------------------------------

    static PoolResolver.Spot spotFor(Player p) {
        Casting.Cast fake = new Casting.Cast(p.getUniqueId(), p.getUniqueId(), Sky.items().idOf(p.getInventory().getItemInMainHand()),
                Sky.items().data(p.getInventory().getItemInMainHand()), Fishing.isLavaRod(Sky.items().idOf(p.getInventory().getItemInMainHand())), false);
        fake.liquid = fake.lavaRod ? "lava" : "water";
        fake.bait = Fishing.data(p).preferredBait;
        String island = Fishing.island(p.getLocation());
        return Casting.spot(p, fake, p.getLocation(), island);
    }

    static List<String> hereSummary(Player p) {
        List<String> l = new ArrayList<>();
        PoolResolver.Spot s = spotFor(p);
        Pool base = Fishing.RES.basePool(s);
        l.add("&7Island: &a" + (s.island == null ? "none" : islandName(s.island)));
        l.add("&7" + (s.liquid.equals("lava") ? "Lava" : "Water") + " pool: &b" + (base == null ? "none" : base.name));
        l.add("&7Creatures you can hook: &a" + Fishing.RES.options(s).size());
        if (s.festival) l.add("&bFishing Festival: sharks are biting!");
        if (s.spooky) l.add("&6Spooky Festival: spooky creatures bite!");
        l.add("");
        l.add("&eClick to see the odds!");
        return l;
    }

    static void here(Player p) {
        PoolResolver.Spot s = spotFor(p);
        List<PoolResolver.Option> all = Fishing.RES.allHere(s);
        List<PoolResolver.Option> can = Fishing.RES.options(s);
        Casting.Cast fake = new Casting.Cast(p.getUniqueId(), p.getUniqueId(), Sky.items().idOf(p.getInventory().getItemInMainHand()),
                Sky.items().data(p.getInventory().getItemInMainHand()), s.liquid.equals("lava"), false);
        fake.bait = s.bait;
        PoolResolver.Mods mods = Casting.mods(p, fake, s);
        Map<String, Double> odds = PoolResolver.odds(can, mods);
        String island = s.island;
        double divisor = 1;
        for (String x : FishDials.SCC_PENALTY_ISLANDS.get().split(",")) if (x.trim().equals(island)) divisor = FishDials.SCC_PENALTY_DIVISOR.get();
        double pSea = FishMath.seaCreatureProbability(Sky.stats().get(p, Stat.SEA_CREATURE_CHANCE), divisor);
        List<Object> entries = new ArrayList<>(all);
        for (PoolResolver.Replacement r : Fishing.RES.replacements(s)) entries.add(r);
        Sky.menus().paged("Fishing Here", entries, o -> {
            if (o instanceof PoolResolver.Replacement r) {
                double ch = Casting.replaceChance(p, fake, r.pool());
                List<String> lore = new ArrayList<>();
                lore.add("&7Any creature you hook has a");
                lore.add("&a" + Text.commas(Math.round(ch * 1000) / 10.0) + "% &7chance to be one of these:");
                for (PoolResolver.Option x : r.options()) lore.add(" &8- " + Fishing.mobName(x.mob()));
                return Icons.of(Material.NAUTILUS_SHELL, "&d" + r.pool().name, lore);
            }
            PoolResolver.Option x = (PoolResolver.Option) o;
            Double share = odds.get(x.mob());
            return creatureIcon(p, x.mob(), share == null ? null : share * pSea);
        }, pc -> { if (pc.entry() instanceof PoolResolver.Option x) creature(pc.click().player(), x.mob(), () -> here(pc.click().player())); },
                menu -> {
                    menu.set(48, Icons.back("Fishing"), c -> main(c.player()));
                    menu.set(4, Icons.of(Material.COMPASS, "&aFishing Here", hereSummary(p).subList(0, hereSummary(p).size() - 2)));
                    menu.set(50, Icons.of(Material.PRISMARINE_SHARD, "&7Sea creature chance: &3" + Text.commas(Math.round(pSea * 1000) / 10.0) + "%",
                            "&7Percentages on creatures are the chance", "&7per catch, with your current stats.",
                            "&7Hold a &6Lava Rod &7to see the lava pool."));
                }, 0).open(p);
    }

    // ---- guide ------------------------------------------------------------------------------------

    static List<String> creatureOrder() {
        Set<String> out = new LinkedHashSet<>();
        for (Pool pl : Fishing.CAT.pools) for (PoolCreature c : pl.creatures) out.add(c.mob);
        return new ArrayList<>(out);
    }

    static Map<String, List<String>> wherePools() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (Pool pl : Fishing.CAT.pools) for (PoolCreature c : pl.creatures) m.computeIfAbsent(c.mob, k -> new ArrayList<>()).add(pl.name);
        return m;
    }

    static int levelOf(String mob) {
        for (Pool pl : Fishing.CAT.pools) for (PoolCreature c : pl.creatures) if (c.mob.equals(mob)) return c.level;
        return 0;
    }

    static ItemStack creatureIcon(Player p, String mob, Double chance) {
        MobDef d = Sky.mobs().def(mob);
        FishData fd = Fishing.data(p);
        if (d == null) return Icons.of(Material.BARRIER, "&c" + mob, "&7Not registered.");
        int need = levelOf(mob);
        boolean locked = Fishing.level(p) < need;
        String rarity = Fishing.rarityOf(d);
        List<String> lore = new ArrayList<>();
        lore.add(Fishing.rarityColor(rarity) + "&l" + rarity + " &8Sea Creature");
        lore.add("");
        lore.add("&7Fishing level: " + (locked ? "&c" : "&a") + need);
        lore.add("&7Health: &c" + Text.shortNum(d.health) + "❤  &7Damage: &c" + Text.shortNum(d.damage));
        lore.add("&7Fishing XP: &3" + Text.commas(Fishing.fishingXpOf(d)));
        List<String> where = wherePools().getOrDefault(mob, List.of());
        lore.add("&7Found in: &b" + String.join(", ", where.subList(0, Math.min(3, where.size()))));
        if (chance != null) lore.add("&7Chance per catch: &a" + Text.commas(Math.round(chance * 10000) / 100.0) + "%");
        lore.add("");
        lore.add("&7Hooked: &a" + Text.commasInt(fd.caught(mob)) + "  &7Killed: &a" + Text.commasInt(fd.kills(mob)));
        if (d.extra != null && d.extra.has("requires")) lore.add("&cRequires " + Sky.requirements().describe(d.extra.get("requires").getAsString()));
        lore.add("");
        lore.add(locked ? "&cRequires Fishing " + need + "!" : "&eClick to view drops!");
        String name = (fd.caught(mob) > 0 ? "&a✔ " : "") + Fishing.rarityColor(rarity) + d.name;
        if (d.skull != null && !d.skull.isBlank()) return Icons.skull(d.skull, name, lore);
        Material egg = Material.matchMaterial(d.entity + "_SPAWN_EGG");
        return Icons.of(egg == null ? Material.COD : egg, name, lore);
    }

    static void guide(Player p, int page) {
        List<String> order = creatureOrder();
        Sky.menus().paged("Sea Creature Guide", order, id -> creatureIcon(p, id, null),
                pc -> creature(pc.click().player(), pc.entry(), () -> guide(pc.click().player(), pc.page())),
                menu -> menu.set(48, Icons.back("Fishing"), c -> main(c.player())), page).open(p);
    }

    static void creature(Player p, String mob, Runnable back) {
        MobDef d = Sky.mobs().def(mob);
        if (d == null) return;
        Menu m = new Menu(Text.strip(d.name), 6).chrome();
        m.set(4, creatureIcon(p, mob, null));
        String msg = d.extra != null && d.extra.has("catchMessage") ? d.extra.get("catchMessage").getAsString() : "";
        m.set(13, Icons.of(Material.OAK_SIGN, "&aWhen It Bites", Text.wrap("&7\"" + msg + "\"", 34)));
        if (PuddleJumper.elusive(d) && FishDials.PUDDLE_JUMPER.get()) {
            FishData fd = Fishing.data(p);
            m.set(15, Icons.of(Material.LILY_PAD, "&9How to Catch It", "&dElusive: &7nothing can hurt it.", "",
                    "&71. Cast your rod &binto &7it (or right-click", "&7   it with a rod in your hand).", "&72. It leaps and drags you along.",
                    "&73. &bHold on&7: keep the rod in hand, don't", "&7   sneak, don't reel - until it lands.", "",
                    "&7Ridden: &a" + fd.puddleRides + "  &7Lost: &c" + fd.puddleFalls));
        }
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (DropDef dr : d.drops) {
            if (i >= slots.length) break;
            if (!Sky.items().exists(dr.item)) continue;
            ItemStack it = Sky.items().create(dr.item, Math.max(1, Math.min(64, dr.min)));
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("&7Amount: &a" + (dr.min == dr.max ? dr.min : dr.min + "-" + dr.max));
            double ch = dr.chance * 100;
            lore.add("&7Chance: &a" + (ch >= 1 ? Text.commas(Math.round(ch * 10) / 10.0) : Text.commas(Math.round(ch * 100000) / 100000.0)) + "%");
            m.set(slots[i++], Icons.withLore(it, lore));
        }
        if (d.extra != null && d.extra.has("powder")) {
            var o = d.extra.getAsJsonObject("powder");
            for (String k : o.keySet()) if (i < slots.length)
                m.set(slots[i++], Icons.of(Material.PRISMARINE_CRYSTALS, "&2" + Text.titleCase(k) + " Powder", "&7Amount: &a" + o.get(k).getAsLong()));
        }
        m.set(40, Icons.of(Material.GOLD_NUGGET, "&6Rewards", "&7Coins: &6" + Text.commas(d.coins), "&7Combat XP: &3" + Text.commas(d.combatXp),
                "&7Fishing XP: &3" + Text.commas(Fishing.fishingXpOf(d))));
        m.set(48, Icons.back("Sea Creature Guide"), c -> back.run());
        m.open(p);
    }

    // ---- trophies ---------------------------------------------------------------------------------

    static void trophies(Player p, boolean atKeeper) {
        FishData d = Fishing.data(p);
        Menu m = new Menu("Trophy Fishing", 6).chrome();
        int title = Trophies.title(d);
        List<String> tl = new ArrayList<>();
        tl.add("&7Current title: " + (title == 0 ? "&8None" : "&6" + Trophies.titleName(title)));
        if (title < 4) {
            List<String> miss = Trophies.missingFor(d, title + 1);
            tl.add("");
            tl.add("&7Next: &6" + Trophies.titleName(title + 1));
            tl.add("&7Needs every fish at " + Trophies.tierLabel(FishingCatalog.TIERS[title]) + "&7+, missing &c" + miss.size() + "&7:");
            for (int i = 0; i < Math.min(6, miss.size()); i++) tl.add(" &8- &7" + miss.get(i));
            if (miss.size() > 6) tl.add(" &8...and " + (miss.size() - 6) + " more");
        }
        tl.add("");
        tl.add("&7Pity: &6Gold &7in &e" + Math.max(1, Fishing.CAT.trophy.tiers.pity.getOrDefault("GOLD", 100) - d.sinceGold)
                + " &7catches, &bDiamond &7in &e" + Math.max(1, Fishing.CAT.trophy.tiers.pity.getOrDefault("DIAMOND", 600) - d.sinceDiamond));
        tl.add("&7Lava fishing streak: &e" + Trophies.sessionMinutes(p) + "m &8(Golden Fish after "
                + Text.commas(FishDials.GOLDEN_START.get()) + "m)");
        if (FishEffects.teraShell(p)) { tl.add(""); tl.add("&cYour Tera Shell Necklace scares every"); tl.add("&ctrophy fish away while you wear it."); }
        m.set(4, Icons.of(Material.GOLD_INGOT, "&6Trophy Fisher", tl));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (Map.Entry<String, TrophyFish> e : Fishing.CAT.trophy.fish.entrySet()) {
            if (i >= slots.length) break;
            TrophyFish f = e.getValue();
            int best = d.bestTier(e.getKey());
            List<String> lore = new ArrayList<>();
            lore.add(Fishing.rarityColor(f.rarity) + "&l" + f.rarity + " &8Trophy Fish");
            lore.add("");
            lore.addAll(Text.wrap("&7" + f.desc, 36));
            lore.add("");
            String rate = "mana".equals(f.cond) ? "mana / 1000 %" : "golden".equals(f.cond) ? "special" : Text.commas(f.rate == null ? 0 : f.rate) + "%";
            lore.add("&7Base chance: &a" + rate);
            if ("geyser".equals(f.cond)) lore.add("&7Geysers: &e" + Geysers.describe(Trophies.TROPHY_ISLAND));
            if ("patience".equals(f.cond)) lore.add("&7Your wait: &e" + Trophies.slugfishWaitMs(p) / 1000.0 + "s");
            if ("eruption".equals(f.cond))
                lore.add(Trophies.gusherWindow() ? "&6Biting now! &7Window closes in &e" + Trophies.clock(Trophies.gusherSeconds())
                        : "&7Bites again in &e" + Trophies.clock(Trophies.gusherSeconds()) + " &8(after an eruption)");
            lore.add("");
            for (String tier : FishingCatalog.TIERS) {
                int n = d.trophy(e.getKey(), tier);
                lore.add(Trophies.tierLabel(tier) + " &7" + (n > 0 ? "&a✔ " + n : "&c✖") + " &8(fillet: " + FishMath.fillet(f.fillet, tier) + ")");
            }
            String iconId = e.getKey() + "_" + (best >= 0 ? FishingCatalog.TIERS[best] : "BRONZE");
            ItemStack icon = Sky.items().exists(iconId) ? Baits.stripLore(Sky.items().create(iconId, 1)) : new ItemStack(Material.COD);
            if (best < 0) icon = new ItemStack(Material.GRAY_DYE);
            ItemStack named = Icons.of(icon.getType(), (best >= 0 ? Fishing.rarityColor(f.rarity) : "&8") + f.name, lore);
            m.set(slots[i++], best >= 0 && icon.getType() != Material.GRAY_DYE ? Icons.withLore(stripName(icon, (Fishing.rarityColor(f.rarity) + f.name)), lore) : named);
        }
        boolean canFillet = atKeeper || FishDials.FILLET_ANYWHERE.get();
        m.set(47, Icons.of(Material.IRON_SWORD, "&aFillet Trophy Fish",
                "&7Turn trophy fish into &6Magmafish&7.", "", canFillet ? "&eClick to fillet!" : "&cOnly at the Trophy Keeper."),
                c -> { if (canFillet) fillet(c.player(), atKeeper); else Msg.error(c.player(), "Visit the Trophy Keeper on the Crimson Isle to fillet."); });
        m.set(51, Icons.of(Material.EMERALD, "&aTrophy Keeper's Wares",
                "&7Hunter armour, teeth and sacks for", "&7proven trophy fishers.", "", atKeeper ? "&eClick to browse!" : "&cOnly at the Trophy Keeper."),
                c -> { if (atKeeper) shop(c.player(), "Trophy Keeper", Fishing.CAT.keeperShop, () -> trophies(c.player(), true));
                       else Msg.error(c.player(), "The Trophy Keeper sells these on the Crimson Isle."); });
        m.set(48, Icons.back("Fishing"), c -> main(c.player()));
        m.open(p);
    }

    static ItemStack stripName(ItemStack it, String name) {
        var meta = it.getItemMeta();
        if (meta != null) { meta.displayName(Text.c(name)); meta.lore(new ArrayList<>()); it.setItemMeta(meta); }
        return it;
    }

    static void fillet(Player p, boolean atKeeper) {
        Menu m = new Menu("Fillet Trophy Fish", 6);
        int[] free = new int[36];
        for (int i = 0; i < 36; i++) free[i] = i;
        m.free(free);
        for (int i = 36; i < 54; i++) m.set(i, Icons.filler());
        m.set(40, Icons.of(Material.IRON_SWORD, "&aFillet!", "&7Put trophy fish above, then click.", "&7Anything else is handed back.", "", "&eClick to fillet!"), c -> {
            Player pl = c.player();
            int total = 0, fish = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack it = c.menu().getInventory().getItem(i);
                if (it == null || it.getType().isAir()) continue;
                int v = Trophies.filletValue(Sky.items().idOf(it));
                if (v <= 0) continue;
                total += v * it.getAmount();
                fish += it.getAmount();
                c.menu().getInventory().setItem(i, null);
            }
            if (total <= 0) { Msg.error(pl, "Put some trophy fish in first."); return; }
            Sky.loot().give(pl, "MAGMA_FISH", total, LootSource.FISHING);
            Fishing.data(pl).magmafishFilleted += total;
            pl.playSound(pl.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.2f);
            Msg.success(pl, "Filleted " + fish + " trophy fish into &6" + Text.commasInt(total) + " Magmafish&a.");
        });
        m.set(48, Icons.back("Trophy Fishing"), c -> trophies(c.player(), atKeeper));
        m.closeButton();
        m.open(p);
    }

    // ---- baits ------------------------------------------------------------------------------------

    static void baits(Player p) {
        List<String> ids = new ArrayList<>();
        for (String id : Fishing.CAT.baits.keySet()) if (Sky.items().exists(id) && Fishing.CAT.baits.get(id).trophyBait == null) ids.add(id);
        Map<String, Integer> owned = Baits.owned(p);
        Sky.menus().paged("Baits", ids, id -> {
            List<String> lore = new ArrayList<>(Baits.describe(id));
            lore.add("");
            lore.add("&7You have: &a" + owned.getOrDefault(id, 0));
            lore.add("");
            lore.add("&eClick to view the recipe!");
            return Icons.withLore(Baits.stripLore(Sky.items().create(id, 1)), lore);
        }, pc -> { if (!Sky.commands().run(pc.click().player(), "recipe", pc.entry())) Msg.error(pc.click().player(), "No recipe viewer installed."); },
                menu -> {
                    menu.set(48, Icons.back("Fishing"), c -> main(c.player()));
                    menu.set(50, Icons.of(Material.CHEST, "&aFishing Bag", "&eClick to open!"), c -> Baits.openBag(c.player()));
                    menu.set(51, Icons.of(Material.TRIPWIRE_HOOK, "&aPreferred Bait", "&eClick to choose!"), c -> Baits.choosePreferred(c.player()));
                }, 0).open(p);
    }

    // ---- hotspots / events ------------------------------------------------------------------------

    static void hotspots(Player p) {
        List<Hotspots.Hotspot> list = new ArrayList<>(Hotspots.live());
        Sky.menus().paged("Fishing Hotspots", list, h -> Icons.of(h.lava ? Material.MAGMA_CREAM : Material.HEART_OF_THE_SEA,
                "&d" + islandName(h.island) + " Hotspot",
                "&7Bonus: " + h.buff.label(1),
                "&7Where: &f" + h.center.getBlockX() + ", " + h.center.getBlockY() + ", " + h.center.getBlockZ(),
                "&7Moves in: &e" + Text.duration(h.secondsLeft()),
                "", "&7Hotspot creatures bite here &a15% &7of", "&7the time (more with Hotspot Bait/Hook).",
                "&7A &aHotspot Sinker &7doubles the bonus."), pc -> { }, menu -> {
            menu.set(48, Icons.back("Fishing"), c -> main(c.player()));
            List<String> lore = new ArrayList<>();
            lore.add("&7Hotspots per island:");
            for (Map.Entry<String, Integer> e : Fishing.CAT.hotspots.entrySet()) lore.add(" &8- &7" + Text.titleCase(e.getKey()) + ": &d" + e.getValue());
            lore.add("");
            lore.add("&7They only appear while someone is on");
            lore.add("&7the island, and move every &e" + Text.duration(FishDials.HOTSPOT_LIFETIME.get()) + "&7.");
            menu.set(4, Icons.of(Material.MAGENTA_DYE, "&dFishing Hotspots", lore));
        }, 0).open(p);
    }

    static List<String> eventLines() {
        List<String> l = new ArrayList<>();
        boolean fest = Fishing.festival();
        l.add("&7Fishing Festival: " + (fest ? "&a&lACTIVE &7- sharks bite!" : "&8inactive"));
        l.add("&7Spooky Festival: " + (Fishing.spooky() ? "&6&lACTIVE" : "&8inactive"));
        l.add("&7Season of Gerald: " + (Fishing.jerrySeason() ? "&f&lACTIVE" : "&8inactive"));
        l.add("");
        l.add("&eClick for details!");
        return l;
    }

    static void events(Player p) {
        Menu m = new Menu("Festivals & Events", 4).chrome();
        boolean fest = Fishing.festival();
        long until = 0;
        try { until = Sky.calendar().millisUntil(Fishing.FESTIVAL_EVENT); } catch (RuntimeException ignored) { }
        String perk = FishDials.FESTIVAL_PERK.get();
        boolean perkOn = Sky.service(Features.Mayors.class).perkActive(perk);
        FishData d = Fishing.data(p);
        long sharks = d.caught("sc_nurse_shark") + d.caught("sc_blue_shark") + d.caught("sc_tiger_shark") + d.caught("sc_great_white_shark");
        m.set(11, Icons.of(Material.PRISMARINE_SHARD, "&bFishing Festival",
                "&7The first three days of every month,", "&7while the fishing mayor is in office.",
                "&7Hooked creatures may be &bSharks&7.", "",
                "&7Status: " + (fest ? "&a&lACTIVE" : "&cinactive"),
                "&7Mayor perk: " + (perkOn ? "&aactive" : "&cnot active") + (FishDials.FESTIVAL_REQUIRE_MAYOR.get() ? "" : " &8(not required)"),
                fest ? "" : "&7Next festival days in: &e" + Text.duration(until / 1000), "",
                "&7Shark chance: &a15% &7base, &a+20% &7Shark Bait,", "&7Shark Scale armour adds more (max &a80%&7).",
                "&7Sharks you hooked: &a" + sharks));
        m.set(13, Icons.of(Material.JACK_O_LANTERN, "&6Spooky Festival",
                "&7During the Spooky Festival, spooky", "&7creatures rise from any water.", "",
                "&7Status: " + (Fishing.spooky() ? "&a&lACTIVE" : "&cinactive"), "",
                "&7Spooky Bait &a+15%&7, Spooky Hook &a+20%&7."));
        m.set(15, Icons.of(Material.SNOWBALL, "&fWinter Workshop",
                "&7The frozen pond always holds winter", "&7creatures; Ice and Frozen baits only", "&7work there.", "",
                "&7Season of Gerald: " + (Fishing.jerrySeason() ? "&a&lACTIVE &7(Festive Sinker gifts)" : "&cinactive")));
        m.set(31, Icons.back("Fishing"), c -> main(c.player()));
        m.open(p);
    }

    // ---- shops ------------------------------------------------------------------------------------

    static void shop(Player p, String title, List<ShopEntry> entries, Runnable back) {
        List<ShopEntry> list = new ArrayList<>();
        for (ShopEntry e : entries) if (Sky.items().exists(e.item)) list.add(e);
        Sky.menus().paged(title, list, e -> {
            ItemStack it = Sky.items().create(e.item, 1);
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("&7Cost");
            lore.add("&6" + Text.commasInt(e.coins) + " Coins");
            if (e.title > 0) {
                boolean ok = Trophies.titleOf(p) >= e.title;
                lore.add("");
                lore.add((ok ? "&a✔ " : "&c✖ ") + "&7Requires " + Trophies.titleName(e.title));
            }
            lore.add("");
            ItemDef d = Sky.items().def(e.item);
            boolean stack = d != null && !d.isUnique();
            lore.add("&eClick to buy!" + (stack ? " &bShift-click for 16!" : ""));
            return Icons.withLore(it, lore);
        }, pc -> buy(pc.click().player(), pc.entry(), pc.click().shift() ? 16 : 1), menu -> menu.set(48, Icons.back("Back"), c -> back.run()), 0).open(p);
    }

    static void buy(Player p, ShopEntry e, int n) {
        ItemDef d = Sky.items().def(e.item);
        if (d == null) return;
        if (d.isUnique()) n = 1;
        if (e.title > 0 && Trophies.titleOf(p) < e.title) { Msg.error(p, "You need the " + Trophies.titleName(e.title) + " title."); return; }
        double cost = e.coins * n;
        if (!Sky.economy().withdraw(p, cost, "fishing shop: " + e.item + " x" + n)) { Msg.error(p, "You don't have enough coins!"); return; }
        Sky.loot().give(p, e.item, n, LootSource.NPC_SHOP);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f);
        Msg.send(p, "&aYou bought " + Fishing.name(e.item) + (n > 1 ? " &8x" + n : "") + " &afor &6" + Text.commasInt(cost) + " Coins&a!");
    }
}
