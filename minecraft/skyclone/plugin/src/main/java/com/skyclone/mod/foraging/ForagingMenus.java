package com.skyclone.mod.foraging;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** /foraging and everything under it. */
final class ForagingMenus {

    private ForagingMenus() {}

    /** A place a player can go to forage. */
    record Place(String name, String warp, String requirement, String wood, Material icon, String blurb) {}

    static final List<Place> PLACES = List.of(
            new Place("Hub Forest", "hub", null, "Oak", Material.OAK_SAPLING, "Oak trees just outside the village."),
            new Place("Birch Park", "park", "skill:foraging:1", "Birch", Material.BIRCH_SAPLING, "The Park's entrance, a sunny birch wood."),
            new Place("Spruce Woods", "park", "skill:foraging:2", "Spruce", Material.SPRUCE_SAPLING, "Snowy pines over the Howling Cave."),
            new Place("Dark Thicket", "park", "skill:foraging:3", "Dark Oak", Material.DARK_OAK_SAPLING, "A dim canopy of dark oak."),
            new Place("Savanna Woodland", "park", "skill:foraging:4", "Acacia", Material.ACACIA_SAPLING, "Terraced mesa and Lyra's plateau."),
            new Place("Jungle Island", "jungle", "skill:foraging:5", "Jungle", Material.JUNGLE_SAPLING, "The highest forest. Needs a travel scroll to warp."),
            new Place("Howling Cave", "howl", "skill:foraging:2", null, Material.BONE, "Wolf spirits under the Spruce Woods."),
            new Place("Moonmarsh", "moonmarsh", "skill:foraging:12", "Fig & Mangrove", Material.MANGROVE_PROPAGULE, "Shared giant trees, Tree Gifts and the Heart of the Forest."),
            new Place("Spiral Mesa", "mesa", "skill:foraging:12 & hotf:4", "Spiral", Material.CHERRY_SAPLING, "The Moonmarsh's sandstone mesa: spiral trees and hives."),
            new Place("Torrent Canyon", "canyon", "skill:foraging:12 & hotf:4", "Spiral", Material.CHERRY_SAPLING, "Spiral trees, honey hives and the Spring Depths."));

    static Menu main(Player p) {
        ForagingData d = ForagingModule.data(p);
        SkillService sk = Sky.service(SkillService.class);
        Menu m = new Menu("Foraging", 6).chrome();
        double sweep = Sky.stats().get(p, Stat.SWEEP), ff = Sky.stats().get(p, Stat.FORAGING_FORTUNE);
        m.set(4, Icons.of(Material.IRON_AXE, "&aForaging " + Text.roman(Math.max(1, sk.level(p, SkillType.FORAGING))),
                "&7Chop trees in the &aHub Forest&7, &aThe Park", "&7and the &2Moonmarsh&7.", "",
                "&2∮ Sweep: &a" + Text.commas(sweep) + " &8(" + SweepMath.logsPerSwing(sweep, 0, false, 0, false) + " logs a swing)",
                "&6☘ Foraging Fortune: &a" + Text.commas(ff),
                "&7Logs felled: &a" + Text.commasInt(d.logs)));
        m.set(19, Icons.of(Material.OAK_LOG, "&aWoods & Trees", "&7Every wood, its XP and toughness,", "&7and what your Sweep fells.", "", "&eClick to view!"),
                c -> woods(c.player()).open(c.player()));
        m.set(20, Icons.of(Material.GOLDEN_AXE, "&aAxes", "&7Every axe, its stats and where", "&7to get it.", "", "&eClick to view!"),
                c -> axes(c.player(), 0).open(c.player()));
        m.set(21, Icons.of(Material.BIRCH_SAPLING, "&aWhere to Forage", "&7The forests, their requirements", "&7and warps.", "", "&eClick to view!"),
                c -> places(c.player()).open(c.player()));
        int questsDone = 0;
        for (ForagingNpcs.Quest q : ForagingNpcs.QUESTS) questsDone += d.quests.getOrDefault(q.id(), 0);
        m.set(22, Icons.of(Material.WRITABLE_BOOK, "&aKeepers' Requests", "&7The five forest keepers each want", "&7their own wood.", "",
                "&7Done: &a" + questsDone + "&8/" + ForagingNpcs.QUESTS.size() * 3, "", "&eClick to view!"), c -> requests(c.player()).open(c.player()));
        m.set(23, Icons.of(Material.MOSS_BLOCK, "&2Heart of the Forest", d.hotfUnlocked ? "&7Tier &a" + Hotf.tier(d) : "&cLocked",
                "&7Perks for Sweep, Fortune and more.", "", "&eClick to view!"), c -> Hotf.menu(c.player()).open(c.player()));
        m.set(24, Icons.of(Material.CAMPFIRE, "&6Ember Trials", "&7Campfire badge tier: &a" + d.trialTier + "&8/29", "&7Talk to &aGaspard &7in the Savanna."));
        m.set(25, Icons.of(Material.NOTE_BLOCK, "&dLyra's Songs", "&7Learned: &a" + d.songs.size() + "&8/" + ForagingNpcs.SONGS.size(),
                "&7Talk to &aLyra &7on her plateau."));
        List<String> sl = new ArrayList<>();
        sl.add("&7Read a travel scroll to unlock its warp.");
        sl.add("");
        sl.add((d.scrolls.contains("jungle") ? "&a✔" : "&c✖") + " &7Jungle Island &8(/warp jungle)");
        sl.add((d.scrolls.contains("howl") ? "&a✔" : "&c✖") + " &7Howling Cave &8(/warp howl)");
        m.set(30, Icons.of(Material.PAPER, "&aTravel Scrolls", sl));
        m.set(31, Icons.withLore(contestIcon(d), List.of("", "&eClick for the contest shops!")), c -> Contest.menu(c.player()).open(c.player()));
        m.set(33, honeyIcon(p, d));
        m.set(29, depthIcon(p));
        m.set(34, Icons.of(Material.SMITHING_TABLE, "&aBooster Bench", "&7Put &bboosters &7on your foraging", "&7axes, armor and equipment, and", "&dForest's Favor &7on a Karma V axe.", "",
                "&8/boosters", "&eClick to open!"), c -> Boosters.bench(c.player()).open(c.player()));
        m.set(32, Icons.of(Material.BOOK, "&aHow foraging works",
                "&2∮ Sweep &7fells more logs per swing:", "&71 + Sweep logs on Park trees (max 36).",
                "&7Tough Moonmarsh trees need more Sweep", "&7per extra log (max 21).", "",
                "&6☘ Foraging Fortune &7adds drops:", "&7every 100 is one more log per log.", "",
                "&7Right-click a throwing axe to hurl it", "&7at a tree &8(50% of the Sweep)&7.", "",
                "&7Moonmarsh giants have a &ecutting order&7:", "&7cut the wrong part first for half the logs.",
                "&7Sneak + right-click an axe to use your", "&7Heart of the Forest axe ability.", "",
                "&7Trees regrow whole a minute after", "&7they were first cut."));
        if (Sky.has(Features.SkyMenu.class))
            m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        return m;
    }

    /** Honey hives and lathered trees (wave 5). */
    static ItemStack honeyIcon(Player p, ForagingData d) {
        List<String> l = new ArrayList<>();
        l.add("&7Hit or right-click a full &ehoney hive");
        l.add("&7on the &6Torrent Canyon &7or the &6Spiral Mesa");
        l.add("&7for &fHoneycomb&7. Each hive refills for you");
        l.add("&7alone in &a" + Text.duration(HiveMath.refillMillis(FDials.hiveRefillSeconds.get(), Bonuses.dailyHive(p) == HiveMath.DAILY_REFILL) / 1000) + "&7.");
        l.add("");
        l.add("&7Smear a &ePot of Honeycomb &7on a fig,");
        l.add("&7mangrove or spiral tree: a critter comes");
        l.add("&7for the honey (lure &a" + Text.commas(Bonuses.latherFasterPercent(p)) + "% &7faster).");
        l.add("");
        l.add("&7Hives harvested: &a" + Text.commasInt(d.hivesHarvested) + "&7, honeycomb: &a" + Text.commasInt(d.honeycomb));
        l.add("&7Critters lured: &a" + Text.commasInt(d.crittersLured));
        long now = System.currentTimeMillis();
        for (ForagingData.Lather x : d.lathered)
            l.add("&8 " + x.wood.toLowerCase() + " tree at " + x.x + ", " + x.z + ": " + (x.due <= now ? "&aready" : "&e" + Text.duration((x.due - now) / 1000)));
        int face = Bonuses.dailyHive(p);
        if (face >= 0) { l.add(""); l.add("&7Hive Mind today: &a" + Hotf.dailyFace("daily_hive", face)); }
        return Icons.of(Material.BEE_NEST, "&6Honey", l);
    }

    /** Breath and pressure (wave 5). */
    static ItemStack depthIcon(Player p) {
        List<String> l = new ArrayList<>();
        l.add("&7Under the &2Dimwater Loch &7and in the");
        l.add("&6Spring Depths &7the water presses harder");
        l.add("&7with every block you dive.");
        l.add("");
        for (String s : Depths.report(p)) l.add(s);
        l.add("");
        l.add("&7Raise them with diving armour, the");
        l.add("&7Respiration and Scuba enchants, talismans,");
        l.add("&7attributes and the Heart of the Forest.");
        l.add("");
        l.add("&7A tunnel from the bottom of the depths");
        l.add("&7climbs to the &aWyrm's Hollow&7, where an");
        l.add("&7old jade dragon guards its egg.");
        return Icons.of(Material.PRISMARINE_SHARD, "&3Diving", l);
    }

    static org.bukkit.inventory.ItemStack contestIcon(ForagingData d) {
        List<String> l = new ArrayList<>();
        long left = Contest.msLeft() / 1000;
        boolean now = d.contestId == Contest.currentId();
        l.add("&7A contest runs every SkyBlock day; this");
        l.add("&7one ends in &e" + (left / 60) + "m " + (left % 60) + "s&7.");
        l.add("");
        l.add("&7Marsh points: &e" + Text.commasInt(now ? d.contestMarsh : 0) + bracketTag(now ? d.contestMarsh : 0, false));
        l.add("&7Canyon points: &e" + Text.commasInt(now ? d.contestCanyon : 0) + bracketTag(now ? d.contestCanyon : 0, true));
        l.add("");
        l.add("&7Score on the &2Moonmarsh &7(or the canyon):");
        l.add("&7 Tree Gift &e10&7, Velvet Lilac &e9&7, Sea Lumies &e2");
        l.add("&7 per 100 logs: Fig &e3&7, Mangrove &e6&7, Spiral &e9");
        l.add("&7 fishing and hunting shards score too");
        l.add("");
        l.add("&7Brackets pay Whispers, Forest Essence,");
        l.add("&7Foraging XP, HotF XP, Coupons and Bits.");
        l.add("&7First 3 contests a day: &2+1,200 HotF XP");
        l.add("");
        l.add("&7Contests placed today: &a" + d.contestsToday + "&8/3");
        if (d.bestBracket >= 0) l.add("&7Best bracket: " + Contest.COLORS[d.bestBracket] + Contest.BRACKETS[d.bestBracket]);
        return Icons.of(Material.NETHER_STAR, "&eStarfall Contest", l);
    }

    private static String bracketTag(double pts, boolean canyon) {
        int b = Contest.bracket(pts, canyon);
        return b < 0 ? "" : " " + Contest.COLORS[b] + Contest.BRACKETS[b];
    }

    static Menu woods(Player p) {
        ForagingData d = ForagingModule.data(p);
        double sweep = Sky.stats().get(p, Stat.SWEEP), ff = Sky.stats().get(p, Stat.FORAGING_FORTUNE);
        Menu m = new Menu("Woods & Trees", 5).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 21, 22, 23};
        int i = 0;
        for (Wood w : Wood.values()) {
            ItemDef def = Sky.items().def(w.itemId);
            Material mat = Material.matchMaterial(w.blocks.get(0));
            int logs = SweepMath.logsPerSwing(sweep, w.toughness, false, 0, false);
            List<String> lore = new ArrayList<>();
            lore.add("&7Foraging XP: &3" + Text.commas(w.xp) + " &7per log");
            lore.add("&7Toughness: " + (w.toughness == 0 ? "&anone" : "&c" + w.toughness));
            lore.add("&7Found in: &a" + where(w));
            if (w.stages() > 1) lore.add("&7Cutting order: &e" + w.orderLine() + " &8(wrong: -50%)");
            lore.add("");
            lore.add("&7Your swing fells &a" + logs + " &7log" + (logs == 1 ? "" : "s") + " &8(~" + Text.commas(SweepMath.expectedDrops(logs, ff)) + " drops)");
            if (w.toughness > 0 && logs < 21) lore.add("&7Next log at &2" + Text.commas(SweepMath.sweepFor(logs + 1, w.toughness)) + "∮ Sweep");
            lore.add("&7You have felled: &a" + Text.commasInt(d.byWood.getOrDefault(w.itemId, 0L)));
            m.set(slots[i++], Icons.of(mat == null ? Material.OAK_LOG : mat, "&a" + (def == null ? w.display + " Log" : Text.strip(def.name)), lore));
        }
        // the Moonmarsh's island resources (picked, not chopped)
        int rs = 29;
        int collector = d.hotfUnlocked ? Hotf.level(d, "collector") : 0;
        for (IslandResource r : IslandResource.values()) {
            List<String> lore = new ArrayList<>();
            lore.add("&8Island resource");
            lore.add("&7Foraging XP: &3" + Text.commas(r.xp) + " &7each");
            lore.add("&7Found in: &a" + (r == IslandResource.SEA_LUMIES ? "the Dimwater Loch, underwater"
                    : r.canyon ? "the Torrent Canyon, in the shade of the spiral trees" : "the Moonmarsh meadows"));
            lore.add("&7Break it to pick it; it grows back.");
            if (collector > 0) lore.add("&7Collector: &a" + Text.commas(collector * 2.0) + "% &7double chance");
            lore.add("");
            lore.add("&7You have picked: &a" + Text.commasInt(d.resources.getOrDefault(r.itemId, 0L)));
            Material mat = Material.matchMaterial(r.block);
            m.set(rs, Icons.of(mat == null || !mat.isItem() ? Material.PAPER : mat, "&a" + r.display, lore));
            rs += 2;
        }
        m.back("Foraging", () -> main(p));
        return m;
    }

    static String where(Wood w) {
        return switch (w) {
            case OAK -> "Hub Forest, your island";
            case BIRCH -> "Birch Park";
            case SPRUCE -> "Spruce Woods";
            case DARK_OAK -> "Dark Thicket";
            case ACACIA -> "Savanna Woodland";
            case JUNGLE -> "Jungle Island";
            case FIG -> "Moonmarsh uplands";
            case MANGROVE -> "Moonmarsh loch";
            case HELIX -> "Torrent Canyon";
        };
    }

    static List<ItemDef> axeDefs() {
        List<ItemDef> out = new ArrayList<>();
        for (ItemDef d : Sky.items().all()) if (d.has("foraging_axe")) out.add(d);
        out.sort(Comparator.<ItemDef>comparingInt(d -> d.rarity.ordinal()).thenComparingDouble(d -> d.stats.getOrDefault(Stat.SWEEP, 0.0)).thenComparing(d -> d.id));
        return out;
    }

    static Menu axes(Player p, int page) {
        return Sky.menus().paged("Axes", axeDefs(), def -> {
            ItemStack it = Sky.items().create(def.id);
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("&7Get it: &a" + source(def.id));
            extra.add("&eClick to view recipes!");
            return Icons.withLore(it, extra);
        }, pc -> Sky.commands().run(pc.click().player(), "recipe", pc.entry().id), menu -> menu.back("Foraging", () -> main(p)), page);
    }

    static String source(String id) {
        for (ForagingNpcs.Offer o : ForagingNpcs.AXE_TRADER) if (o.item().equals(id)) return "Axe-trader Hewitt (The Park)";
        for (ForagingNpcs.Offer o : ForagingNpcs.MARSH_AXES) if (o.item().equals(id)) return "Axewright Thane (Moonmarsh)";
        if (!Sky.recipes().producing(id).isEmpty()) return "Crafting";
        return switch (id) {
            case "JUNGLE_AXE" -> "Crafting, or Ranger Ellery's last request";
            case "WOODEN_AXE", "STONE_AXE", "IRON_AXE", "GOLDEN_AXE", "DIAMOND_AXE" -> "Crafting table";
            default -> "Merchants";
        };
    }

    static Menu places(Player p) {
        Menu m = new Menu("Where to Forage", 4).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 21, 23};
        ForagingData d = ForagingModule.data(p);
        for (int i = 0; i < PLACES.size(); i++) {
            Place pl = PLACES.get(i);
            boolean ok = pl.requirement() == null || Sky.requirements().check(p, pl.requirement());
            List<String> lore = new ArrayList<>();
            lore.add("&7" + pl.blurb());
            if (pl.wood() != null) lore.add("&7Wood: &a" + pl.wood());
            lore.add("");
            if (pl.requirement() != null) lore.add((ok ? "&a✔ " : "&c✖ ") + "&7Requires " + Sky.requirements().describe(pl.requirement()));
            boolean scroll = pl.warp().equals("jungle") || pl.warp().equals("howl");
            if (scroll && !d.scrolls.contains(pl.warp())) lore.add("&c✖ &7Warp needs its travel scroll");
            lore.add("");
            lore.add("&eClick to warp!");
            m.set(slots[i], Icons.of(pl.icon(), (ok ? "&a" : "&c") + pl.name(), lore), c -> {
                c.player().closeInventory();
                Sky.worlds().warp(c.player(), pl.warp());
            });
        }
        m.back("Foraging", () -> main(p));
        return m;
    }

    static Menu requests(Player p) {
        ForagingData d = ForagingModule.data(p);
        Menu m = new Menu("Keepers' Requests", 4).chrome();
        int i = 0;
        for (ForagingNpcs.Quest q : ForagingNpcs.QUESTS) {
            int done = d.quests.getOrDefault(q.id(), 0);
            List<String> lore = new ArrayList<>();
            lore.add("&7Wants: &a" + q.woodName());
            lore.add("&7Progress: &a" + done + "&8/3");
            if (done < 3) lore.add("&7Next: &a" + Text.commasInt(ForagingNpcs.STAGE_LOGS[done]) + " " + q.woodName() + " &8(you have " + Text.commasInt(ForagingNpcs.count(p, q.wood())) + ")");
            else lore.add("&aAll done!");
            lore.add("");
            lore.add("&7Find them in The Park.");
            m.set(11 + i++, Icons.of(done >= 3 ? Material.LIME_DYE : Material.PAPER, (done >= 3 ? "&a" : "&e") + Text.strip(q.name()), lore));
        }
        m.back("Foraging", () -> main(p));
        return m;
    }
}
