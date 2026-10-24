package com.skyclone.mod.galatea;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.WorldService;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Every Galatea menu. Builders take a nullable player so the self-test can build them with nobody online. */
final class GalateaMenus {

    private GalateaMenus() {}

    /** A shop row: what you get, what it costs (coins + items), what unlocks it. */
    record Offer(String item, int amount, double coins, String[] costItems, int[] costAmounts, String requirement) {
        static Offer coins(String item, int amount, double coins, String req) { return new Offer(item, amount, coins, new String[0], new int[0], req); }
    }

    // Professor Quennell (ref. Researcher Ribery) - NEU npc_shop prices; title gates from the item cards.
    static final List<Offer> PROFESSOR = List.of(
            Offer.coins("FISHING_ROD", 1, 20, null),
            Offer.coins("BRONZE_TROPHY_FROG_SACK", 1, 50_000, "frogs:title:1"),
            Offer.coins("APPLICANT_STATEMENT", 1, 100_000, "frogs:title:1"),
            Offer.coins("SILVER_TROPHY_FROG_SACK", 1, 100_000, "frogs:title:2"),
            Offer.coins("STUDENT_STUDIES", 1, 250_000, "frogs:title:2"),
            Offer.coins("LOTUS_ATOLL_TRAVEL_SCROLL", 1, 500_000, "frogs:title:2"),
            Offer.coins("MASTER_THESIS", 1, 500_000, "frogs:title:3"),
            Offer.coins("PHD_GRIMOIRE", 1, 1_000_000, "frogs:title:4"),
            Offer.coins("RIBBIT_THE_FISH", 1, 1_000_000, "frogs:title:4"));

    // Bait-seller Corvina - TUNE (no reference vendor on the Atoll; basics so nobody is stranded)
    static final List<Offer> VENDOR = List.of(
            Offer.coins("FISHING_ROD", 1, 20, null),
            Offer.coins("WATER_LILY", 16, 160, null));

    // The Lotus Eater - NEU: Lily Pad Minion XII upgrade stone for one Diamond Lotus.
    static final List<Offer> EATER = List.of(
            new Offer("GENERATOR_UPGRADE_STONE_LILY_PAD_12", 1, 0, new String[]{"LOTUS_DIAMOND"}, new int[]{1}, null));

    // The Ant Overseer (ref. the Ant on Torrhus) - NEU: 2,000,000 coins + 1,024 of the enchanted log each.
    static final List<Offer> OVERSEER;
    static {
        List<Offer> o = new ArrayList<>();
        for (String log : new String[]{"OAK", "SPRUCE", "BIRCH", "DARK_OAK", "ACACIA", "JUNGLE"})
            o.add(new Offer("GENERATOR_UPGRADE_STONE_" + log + "_12", 1, 2_000_000, new String[]{"ENCHANTED_" + log + "_LOG"}, new int[]{1024}, null));
        OVERSEER = List.copyOf(o);
    }

    static final int[] GRID = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    // ---- overview --------------------------------------------------------------------------------

    static void main(Player p) { buildMain(p).open(p); }

    static Menu buildMain(Player p) {
        Menu m = new Menu("Outer Islands", 6).chrome();
        m.set(4, Icons.of(Material.LILY_PAD, "&dThe Outer Islands", "&7Two islands the old maps left out:", "&7the &dLotus Atoll&7, a fishing atoll far", "&7out to sea, and &6Torrent Canyon&7, past", "&7the Moonmarsh."));
        m.set(20, travelIcon(p, GalateaModule.ATOLL, Material.LILY_PAD, "&7A ring of reefs round a lily lagoon.", "&7Trophy Frogs, Lotus, the Lotus Eater."), c -> travel(c.player(), GalateaModule.ATOLL));
        m.set(24, travelIcon(p, GalateaModule.CANYON, Material.RED_SANDSTONE, "&7A sandstone canyon with deep springs.", "&7Spiral trees, Parched, the Queen Ant."), c -> travel(c.player(), GalateaModule.CANYON));
        m.set(29, travelIcon(p, "torrhus_springs", Material.WATER_BUCKET, "&7Straight to the spring pool.", "&8Needs its travel scroll."), c -> travel(c.player(), "torrhus_springs"));
        m.set(33, travelIcon(p, "torrhus_safari", Material.LEAD, "&7Straight to the Safari gate.", "&8Needs its travel scroll."), c -> travel(c.player(), "torrhus_safari"));
        GalateaData d = p != null && GalateaModule.ready(p) ? GalateaModule.data(p) : new GalateaData();
        int title = FrogMath.title(d.frogs);
        m.set(39, Icons.of(Material.SLIME_BALL, "&aTrophy Frog Ledger", "&7Every species and quality you have", "&7caught on the Atoll.", "",
                "&7Title: " + (title == 0 ? "&8none yet" : "&a" + FrogMath.TITLES[title]), "&7Logged: &a" + Frogs.ledgerCount(d.frogs) + "&7/&a" + FrogMath.SPECIES.size() * 4, "", "&eClick to open!"), c -> ledger(c.player()));
        m.set(41, Icons.of(Material.WRITABLE_BOOK, "&6Canyon Journal", "&7Chapters of tasks in Torrent Canyon.", "",
                "&7Chapters done: &a" + d.chaptersPaid.size() + "&7/&a" + Journal.chapters().size(), "", "&eClick to open!"), c -> journal(c.player()));
        return m;
    }

    static ItemStack travelIcon(Player p, String warp, Material icon, String l1, String l2) {
        WorldService.WarpInfo w = null;
        for (WorldService.WarpInfo i : Sky.worlds().warpInfos()) if (i.id().equals(warp)) w = i;
        List<String> lore = new ArrayList<>(List.of(l1, l2, ""));
        if (w == null) return Icons.of(Material.GRAY_DYE, "&8" + warp, "&8Not charted.");
        boolean ok = w.requirement() == null || p != null && Sky.requirements().check(p, w.requirement());
        lore.add(w.requirement() == null ? "&aOpen to everyone" : ok ? "&aUnlocked" : "&cRequires " + Sky.requirements().describe(w.requirement()));
        lore.add("");
        lore.add(ok ? "&eClick to travel!" : "&cLocked");
        return Icons.of(icon, (ok ? "&a" : "&c") + w.displayName(), lore);
    }

    static void travel(Player p, String warp) {
        p.closeInventory();
        Sky.worlds().warp(p, warp);
    }

    // ---- frogs -----------------------------------------------------------------------------------

    static void professor(Player p) { buildProfessor(p).open(p); }

    static Menu buildProfessor(Player p) {
        Menu m = new Menu("Professor Quennell", 4).chrome();
        GalateaData d = p != null && GalateaModule.ready(p) ? GalateaModule.data(p) : new GalateaData();
        int title = FrogMath.title(d.frogs);
        m.set(4, Icons.of(Material.LECTERN, "&9Professor Quennell", "&7Logs every Trophy Frog you bring.", "",
                "&7Your title: " + (title == 0 ? "&8none yet" : "&a" + FrogMath.TITLES[title])));
        m.set(11, Icons.of(Material.BOOK, "&aFrog Ledger", "&7Every species, every quality,", "&7and how to catch it.", "", "&eClick to open!"), c -> ledger(c.player()));
        m.set(13, Icons.of(Material.PINK_TULIP, "&aRelease Frogs", "&7Hand frogs over for research and be", "&7paid in &aLotus&7: bigger frogs pay", "&7Silver, Gold or Diamond Lotus.", "", "&eClick to open!"), c -> release(c.player()));
        m.set(15, Icons.of(Material.EMERALD, "&aProfessor's Shop", "&7Sacks, papers and a scroll home,", "&7unlocked by your collector title.", "", "&eClick to browse!"), c -> shop(c.player(), "Professor's Shop", PROFESSOR, () -> buildProfessor(c.player())));
        return m;
    }

    static void ledger(Player p) { buildLedger(p).open(p); }

    static Menu buildLedger(Player p) {
        Menu m = new Menu("Trophy Frog Ledger", 6).chrome();
        GalateaData d = p != null && GalateaModule.ready(p) ? GalateaModule.data(p) : new GalateaData();
        int title = FrogMath.title(d.frogs);
        List<String> head = new ArrayList<>(List.of("&7Catch frogs while fishing on the", "&dLotus Atoll&7. Every species has its", "&7own trick - hover each one.", ""));
        for (int t = 1; t <= 4; t++) head.add((title >= t ? "&a✔ " : "&c✖ ") + "&7" + FrogMath.TITLES[t] + " &8(every species at " + FrogMath.Tier.values()[t - 1].label + ")");
        head.add("");
        head.add("&7Pity: &6Gold &7in &e" + Math.max(0, GalateaModule.D.goldPity.get() - d.sinceGold) + "&7, &bDiamond &7in &e" + Math.max(0, GalateaModule.D.diamondPity.get() - d.sinceDiamond));
        m.set(4, Icons.of(Material.SLIME_BALL, "&aTrophy Frogs", head));
        int i = 0;
        for (FrogMath.Species s : FrogMath.SPECIES) {
            List<String> lore = new ArrayList<>(Text.wrap("&7" + s.hint(), 34));
            lore.add("");
            int best = FrogMath.bestTier(d.frogs, s);
            for (FrogMath.Tier t : FrogMath.Tier.values()) {
                int n = d.frogs.getOrDefault(s.item(t), 0);
                lore.add(t.color + t.label + "&7: " + (n > 0 ? "&a" + Text.commasInt(n) : "&c✖"));
            }
            ItemDef def = Sky.items().def(s.item(FrogMath.Tier.BRONZE));
            String color = def == null ? "&f" : def.rarity.code();
            m.set(GRID[i++], Icons.of(best < 0 ? Material.GRAY_DYE : Material.SLIME_BALL, (best < 0 ? "&8" : color) + s.name(), lore));
        }
        m.set(48, Icons.back("Professor Quennell"), c -> professor(c.player()));
        return m;
    }

    static void release(Player p) { buildRelease(p).open(p); }

    static Menu buildRelease(Player p) {
        Menu m = new Menu("Release Frogs", 6).chrome();
        m.set(4, Icons.of(Material.PINK_TULIP, "&aRelease Frogs", "&7Click a frog to release every one of", "&7that kind you carry. Value in Lotus:", "",
                "&7Common &a4 &8x quality", "&7Uncommon &a8&7, Rare &a16&7, Epic &a32&7, Legendary &a64", "&8Bronze x1, Silver x4, Gold x16, Diamond x64", "",
                "&880 Lotus = 1 Silver, 80 Silver = 1 Gold,", "&820 Gold = 1 Diamond Lotus."));
        int i = 0;
        for (FrogMath.Species s : FrogMath.SPECIES)
            for (FrogMath.Tier t : FrogMath.Tier.values()) {
                String id = s.item(t);
                int have = p == null ? 0 : Sky.items().count(p, id);
                if (have <= 0 || i >= GRID.length) continue;
                ItemStack icon = Sky.items().create(id, Math.min(64, have));
                long value = FrogMath.lotusValue(s, t) * have;
                m.set(GRID[i++], Icons.withLore(icon, List.of("", "&7You carry: &a" + have, "&7Pays: &a" + Text.commasInt(value) + " Lotus", "", "&eClick to release all!")), c -> {
                    if (Frogs.release(c.player(), id) > 0) Msg.sound(c.player(), Sound.ENTITY_FROG_LONG_JUMP, 1.4f);
                    release(c.player());
                });
            }
        if (i == 0) m.set(22, Icons.of(Material.BARRIER, "&cNo frogs", "&7You are not carrying any Trophy Frogs."));
        m.set(48, Icons.back("Professor Quennell"), c -> professor(c.player()));
        return m;
    }

    static void frogHints(Player p, GalateaModule.Npc n) {
        GalateaData d = GalateaModule.data(p);
        for (String stem : n.frogs) {
            FrogMath.Species s = FrogMath.species(stem);
            if (s == null) continue;
            int best = FrogMath.bestTier(d.frogs, s);
            Msg.send(p, "&8 ▸ &a" + s.name() + "&7: " + s.hint() + (best >= 0 ? " &8(best: " + FrogMath.Tier.values()[best].label + ")" : " &c(not caught yet)"));
        }
    }

    // ---- shops -----------------------------------------------------------------------------------

    static void vendor(Player p) { shop(p, "Bait-seller Corvina", VENDOR, null); }

    static void lotusEater(Player p) { shop(p, "The Lotus Eater", EATER, null); }

    static void overseer(Player p) { shop(p, "The Ant Overseer", OVERSEER, null); }

    static void shop(Player p, String title, List<Offer> offers, java.util.function.Supplier<Menu> back) { buildShop(p, title, offers, back).open(p); }

    static Menu buildShop(Player p, String title, List<Offer> offers, java.util.function.Supplier<Menu> back) {
        Menu m = new Menu(title, 4).chrome();
        int i = 0;
        for (Offer o : offers) {
            if (i >= GRID.length || !Sky.items().exists(o.item())) continue;
            ItemStack icon = Sky.items().create(o.item(), Math.max(1, Math.min(64, o.amount())));
            List<String> lore = new ArrayList<>(List.of("", "&7Cost"));
            if (o.coins() > 0) lore.add("&6" + Text.commasInt(o.coins()) + " Coins");
            for (int k = 0; k < o.costItems().length; k++) {
                ItemDef cd = Sky.items().def(o.costItems()[k]);
                lore.add((cd == null ? "&f" + o.costItems()[k] : cd.rarity.code() + cd.name) + " &8x" + Text.commasInt(o.costAmounts()[k]));
            }
            lore.add("");
            boolean ok = o.requirement() == null || p != null && Sky.requirements().check(p, o.requirement());
            if (!ok) lore.add("&cRequires " + Sky.requirements().describe(o.requirement()));
            lore.add(ok ? "&eClick to buy!" : "&cLocked");
            m.set(GRID[i++], Icons.withLore(icon, lore), c -> { buy(c.player(), o); shop(c.player(), title, offers, back); });
        }
        if (back != null) m.back("Back", back);
        return m;
    }

    static void buy(Player p, Offer o) {
        if (!GalateaModule.ready(p)) return;
        if (o.requirement() != null && !Sky.requirements().checkOrTell(p, o.requirement())) return;
        for (int k = 0; k < o.costItems().length; k++)
            if (Sky.items().count(p, o.costItems()[k]) < o.costAmounts()[k]) {
                ItemDef cd = Sky.items().def(o.costItems()[k]);
                Msg.error(p, "You need " + Text.commasInt(o.costAmounts()[k]) + "x " + (cd == null ? o.costItems()[k] : cd.name) + "!");
                return;
            }
        if (o.coins() > 0 && Sky.economy().purse(p) < o.coins()) { Msg.error(p, "You don't have enough coins!"); return; }
        if (o.coins() > 0 && !Sky.economy().withdraw(p, o.coins(), "galatea: shop " + o.item())) { Msg.error(p, "You don't have enough coins!"); return; }
        for (int k = 0; k < o.costItems().length; k++) {
            int took = Sky.items().remove(p, o.costItems()[k], o.costAmounts()[k]);
            if (took < o.costAmounts()[k]) {      // raced away between check and take: refund what we can
                if (took > 0) Sky.loot().give(p, o.costItems()[k], took, LootSource.NPC_SHOP);
                if (o.coins() > 0) Sky.economy().deposit(p, o.coins(), "galatea: refund " + o.item());
                Msg.error(p, "Something changed in your inventory - nothing was bought.");
                return;
            }
        }
        Sky.loot().give(p, o.item(), o.amount(), LootSource.NPC_SHOP);
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f);
    }

    // ---- the canyon journal ----------------------------------------------------------------------

    static void journal(Player p) { buildJournal(p).open(p); }

    static Menu buildJournal(Player p) {
        Menu m = new Menu("Canyon Journal", 6).chrome();
        GalateaData d = p != null && GalateaModule.ready(p) ? GalateaModule.data(p) : new GalateaData();
        m.set(4, Icons.of(Material.WRITABLE_BOOK, "&6Canyon Journal", "&7Wayfinder Calla's record of Torrent", "&7Canyon. Finish a chapter's tasks and", "&7its reward is paid at once."));
        int[] slots = {19, 21, 23, 25, 29, 31, 33};
        int i = 0;
        double scale = Math.max(0, GalateaModule.D.journalScale.get());
        for (Journal.Chapter c : Journal.chapters()) {
            if (i >= slots.length) break;
            boolean done = d.chaptersPaid.contains(c.id);
            List<String> lore = new ArrayList<>();
            for (Journal.Task t : c.tasks) {
                boolean ok = d.journalDone.contains(t.id);
                String prog = t.need > 1 && !ok ? " &8(" + d.journal.getOrDefault(t.id, 0) + "/" + t.need + ")" : "";
                lore.add((ok ? "&a✔ &7" : "&c✖ &f") + t.text + prog);
            }
            lore.add("");
            lore.add("&7Reward:");
            lore.add(" &3" + Text.commasInt(c.foragingXp * scale) + " Foraging XP");
            lore.add(" &6" + Text.commasInt(c.coins * scale) + " Coins");
            lore.add("");
            lore.add(done ? "&a&lCOMPLETE" : "&eIn progress");
            m.set(slots[i++], Icons.of(done ? Material.LIME_DYE : Material.BOOK, (done ? "&a" : "&e") + "Chapter " + Text.roman(i) + ": " + c.name, lore));
        }
        m.set(48, Icons.back("Outer Islands"), c -> main(c.player()));
        return m;
    }
}
