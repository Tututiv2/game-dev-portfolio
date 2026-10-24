package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.Recipe;
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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The Mining menu and its side menus: shops, the King, Fetcher, Riddlewright, the block guide, stats. */
final class MiningMenus {

    static final String[] KINGS = {"King Borrik", "King Hallgrim", "King Dunmar", "King Ostwin", "King Gravik", "King Keldor"};

    private final MiningModule m;

    MiningMenus(MiningModule m) { this.m = m; }

    // ---- main -------------------------------------------------------------------------------------

    void openMain(Player p) {
        if (!MiningUtil.ready(p)) return;
        MiningData d = MiningUtil.data(p);
        Menu menu = new Menu("Mining", 6).chrome();
        int tier = m.hotm.tier(p);
        menu.set(4, Icons.of(Material.DIAMOND_PICKAXE, "&aYour Mining",
                "&7Heart of the Mountain: &5Tier " + tier,
                "&7Blocks mined: &a" + Text.commas(d.blocksMined),
                "&7Commissions: &a" + Text.commas(d.commissionsDone),
                "", powderLine(d, "MITHRIL"), powderLine(d, "GEMSTONE"), powderLine(d, "GLACITE")));
        menu.set(19, Icons.of(Material.REDSTONE_BLOCK, "&5Heart of the Mountain", "&7Spend tokens and powder on", "&7mining perks and abilities.", "", "&7Tier: &5" + tier + " &7Tokens: &5" + m.hotm.tokensFree(p), "", "&eClick to open! &8(/hotm)"),
                c -> m.hotmMenu.open(c.player(), 0));
        menu.set(20, Icons.of(Material.WRITABLE_BOOK, "&6Commissions", "&7Tasks from the King: mine, slay,", "&7loot. Rewards HOTM XP and powder.", "", "&eClick to open! &8(/commissions)"),
                c -> m.comms.open(c.player(), null));
        int ready = m.forge.ready(p);
        menu.set(21, Icons.of(Material.ANVIL, "&6The Forge", "&7Refine metals, cut gems, build", "&7drills and parts.", "", ready > 0 ? "&a" + ready + " item(s) ready to claim!" : "&7Slots: &a" + m.forge.slots(p), "", "&eClick to open! &8(/forge)"),
                c -> m.forge.open(c.player()));
        menu.set(22, Icons.of(Material.PRISMARINE_SHARD, "&2Drill Mechanic", "&7Fit parts and refuel the drill", "&7in your hand.", "", "&eClick to open! &8(/drill)"),
                c -> m.drills.openMechanic(c.player()));
        menu.set(23, Icons.of(Material.IRON_PICKAXE, "&aPickaxe Smith", "&7Buy and upgrade pickaxes,", "&7Biofuel and chisels.", "", "&eClick to shop!"),
                c -> openShop(c.player(), "Pickaxe Smith"));
        menu.set(24, Icons.of(Material.BEACON, "&5Crystal Nucleus", "&7Place the five zone crystals.", "", "&7Runs: &a" + d.nucleusRuns, "", "&eClick to open! &8(/nucleus)"),
                c -> m.hollows.openNucleus(c.player()));
        menu.set(25, Icons.of(Material.BRUSH, "&aFossil Excavator", "&7Dig Suspicious Scrap for fossils.", "", "&eClick to open! &8(/excavator)"),
                c -> m.excavator.open(c.player()));
        menu.set(28, eventsIcon(), c -> openEvents(c.player()));
        SkyMall.Buff b = m.skyMall.today();
        menu.set(29, Icons.of(Material.EMERALD, "&bDeep Market", "&7Today's buff on mining islands:", "&8• " + b.text,
                "", m.hotm.has(p, "sky_mall") ? "&aYou have this perk." : "&cUnlock the Deep Market perk in /hotm."));
        menu.set(30, Icons.of(Material.GOLDEN_HELMET, "&6" + kingToday(), "&7The King of the mines changes", "&7every SkyBlock day. Pay your", "&7respects once a day for a gift.",
                "", "&7Kings visited: &a" + d.kingsVisited.size() + "&7/" + KINGS.length, "", "&eClick to visit!"), c -> openKing(c.player()));
        menu.set(31, Icons.of(Material.CHEST, "&eFetcher", "&7A dwarf who always wants", "&7something. Bring it once a day.", "", "&eClick to talk!"), c -> openFetcher(c.player()));
        menu.set(32, Icons.of(Material.OAK_SIGN, "&eRiddlewright", "&7Solve a daily arrow puzzle.", "", "&eClick to play!"), c -> openPuzzle(c.player()));
        menu.set(33, Icons.of(Material.PACKED_ICE, "&bBase Camp & Mineshafts",
                "&7Mining the frozen tunnels slowly", "&7opens a mineshaft.", "",
                "&7Mineshafts found: &a" + d.mineshaftsFound, "&7Corpses looted: &a" + d.corpsesLooted, "&7Pity: &a" + Math.max(0, d.mineshaftPity),
                "", "&eClick to open!"), c -> openCamp(c.player()));
        menu.set(34, statsIcon(p), c -> openStats(c.player()));
        menu.set(38, Icons.of(Material.AMETHYST_SHARD, "&aGemstone Grinder", "&7Unlock gemstone slots on your", "&7gear and set or remove gems.", "", "&eClick to open! &8(/gemstones)"),
                c -> { if (!Sky.commands().run(c.player(), "gemstones")) Msg.error(c.player(), "The Gemstone Grinder is not available right now."); });
        menu.set(39, Icons.of(Material.MAP, "&aTravel Scrolls", scrollLore(d)), c -> { if (!Sky.commands().run(c.player(), "warp")) Msg.error(c.player(), "Fast travel is not available right now."); });
        menu.set(40, Icons.of(Material.BOOKSHELF, "&aMining Guide", "&7Every mineable block: where it is,", "&7its strength and Breaking Power.", "", "&eClick to read!"),
                c -> openGuide(c.player(), 0));
        menu.set(41, Icons.of(Material.AMETHYST_CLUSTER, "&dHollows Merchant", "&7Jungle pickaxes, compasses, ropes", "&7and campfires, plus the Crystal", "&7Nucleus and caverns commissions.", "", "&eClick to browse!"),
                c -> openMerchant(c.player()));
        menu.set(42, Icons.of(Material.GOLDEN_PICKAXE, "&6Pickaxe Abilities", abilityLore(p)), c -> m.hotmMenu.open(c.player(), 0));
        if (Sky.has(Features.SkyMenu.class))
            menu.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        menu.open(p);
    }

    private static List<String> scrollLore(MiningData d) {
        List<String> lore = new ArrayList<>(List.of("&7Read a Travel Scroll to add its", "&7destination to &e/warp&7.", ""));
        for (String[] w : Consumables.scrollWarps())
            lore.add((d.scrolls.contains(w[0]) ? "&a✔ " : "&c✖ ") + "&7" + w[4]);
        lore.add("");
        lore.add("&eClick to fast travel!");
        return lore;
    }

    private List<String> abilityLore(Player p) {
        MiningData d = MiningUtil.data(p);
        List<String> lore = new ArrayList<>();
        lore.add("&7Selected: &6" + PickaxeAbilities.name(d.selectedAbility) + (d.selectedAbility == null ? "" : " " + Text.roman(m.hotm.abilityLevel(p))));
        if (d.selectedAbility != null) {
            lore.addAll(m.abilities.describe(d.selectedAbility, m.hotm.abilityLevel(p)));
            double cd = com.skyclone.api.Sky.abilities().cooldownLeft(p, PickaxeAbilities.ID);
            lore.add(cd > 0 ? "&7Ready in &e" + Text.duration((long) Math.ceil(cd)) : "&aReady! &7Right-click a pickaxe or drill.");
        } else lore.add("&7Unlock one in the &5Heart of the Mountain&7.");
        lore.addAll(m.abilities.statusLines(p));
        lore.add("");
        lore.add("&eClick to choose in /hotm!");
        return lore;
    }

    private static String powderLine(MiningData d, String type) {
        return MiningUtil.powderColor(type) + MiningUtil.DOT + " " + MiningUtil.powderName(type) + ": " + MiningUtil.powderColor(type) + Text.commas(d.powder(type));
    }

    private ItemStack statsIcon(Player p) {
        return Icons.of(Material.GOLDEN_PICKAXE, "&aMining Stats",
                "&6⸕ Mining Speed: &f" + Text.commas(Sky.stats().get(p, Stat.MINING_SPEED)),
                "&6☘ Mining Fortune: &f" + Text.commas(Sky.stats().get(p, Stat.MINING_FORTUNE)),
                "&2Ⓟ Breaking Power: &f" + m.engine.toolPower(p),
                "&5✧ Pristine: &f" + Text.commas(Sky.stats().get(p, Stat.PRISTINE)),
                "&e▚ Mining Spread: &f" + Text.commas(Sky.stats().get(p, Stat.MINING_SPREAD)),
                "", "&eClick for details!");
    }

    void openStats(Player p) {
        Menu menu = new Menu("Mining Stats", 4).chrome();
        Stat[] stats = {Stat.MINING_SPEED, Stat.MINING_FORTUNE, Stat.ORE_FORTUNE, Stat.BLOCK_FORTUNE, Stat.DWARVEN_METAL_FORTUNE,
                Stat.GEMSTONE_FORTUNE, Stat.BREAKING_POWER, Stat.PRISTINE, Stat.MINING_SPREAD, Stat.MINING_WISDOM, Stat.HEAT_RESISTANCE, Stat.COLD_RESISTANCE};
        int[] pos = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23};
        for (int i = 0; i < stats.length; i++) {
            Stat s = stats[i];
            List<String> lore = new ArrayList<>();
            lore.add("&7Total: &f" + Text.commas(Sky.stats().get(p, s)));
            lore.add("");
            for (var e : Sky.stats().breakdown(p, s).entrySet()) if (e.getValue() != 0) lore.add("&8• &7" + e.getKey() + ": &f" + Text.commas(e.getValue()));
            menu.set(pos[i], Icons.of(Material.PAPER, s.label(), lore));
        }
        menu.set(31, Icons.back("Mining"), c -> openMain(c.player()));
        menu.open(p);
    }

    // ---- events ---------------------------------------------------------------------------------

    private ItemStack eventsIcon() {
        List<String> lore = new ArrayList<>();
        lore.add("&7Events run on every mining island");
        lore.add("&7every SkyBlock day.");
        lore.add("");
        for (String s : m.events.status()) lore.add("&8• &7" + s.replace("dwarven_mines", "Dwarven Mines").replace("crystal_hollows", "Crystal Caverns").replace("glacite_tunnels", "Frozen Tunnels"));
        lore.add("");
        lore.add("&eClick for the list!");
        return Icons.of(Material.CLOCK, "&aMining Events", lore);
    }

    void openEvents(Player p) {
        Menu menu = new Menu("Mining Events", 4).chrome();
        menu.set(4, eventsIcon());
        int i = 10;
        for (MiningEvents.Type t : MiningEvents.Type.values()) {
            menu.set(i++, Icons.of(Material.FIREWORK_STAR, t.color + t.display, Text.wrap("&7" + t.desc, 32)));
            if (i == 17) i = 19;
        }
        menu.set(31, Icons.back("Mining"), c -> openMain(c.player()));
        menu.open(p);
    }

    // ---- the Hollows merchant ----------------------------------------------------------------------

    void openMerchant(Player p) {
        Menu menu = new Menu("Hollows Merchant", 4).chrome();
        menu.set(11, Icons.of(Material.EMERALD, "&aShop", "&7Jungle pickaxes, compasses, ropes,", "&7campfires and Recall Potions.", "", "&eClick to browse!"),
                c -> openShop(c.player(), "Hollows Merchant"));
        List<String> lore = new ArrayList<>(List.of("&7Place the five zone crystals at the", "&7Nucleus for its treasure.", ""));
        lore.addAll(m.hollows.crystalStatus(p));
        lore.add("");
        lore.add("&eClick to open!");
        menu.set(13, Icons.of(Material.BEACON, "&5Crystal Nucleus", lore), c -> m.hollows.openNucleus(c.player()));
        menu.set(15, Icons.of(Material.WRITABLE_BOOK, "&6Commissions", "&7Crystal Caverns commissions.", "", "&eClick to open!"),
                c -> m.comms.open(c.player(), "crystal_hollows"));
        menu.set(31, Icons.back("Mining"), c -> openMain(c.player()));
        menu.open(p);
    }

    // ---- the Base Camp (frozen tunnels) ----------------------------------------------------------------

    void openCamp(Player p) {
        MiningData d = MiningUtil.data(p);
        Menu menu = new Menu("Base Camp", 4).chrome();
        int cold = m.tunnels.cold(p);
        menu.set(4, Icons.of(Material.CAMPFIRE, "&bBase Camp",
                "&7The warm heart of the frozen tunnels.", "&7Cold drains away while you are here.", "",
                "&7Your Cold: " + (cold > 0 ? "&b-" + cold : "&a0"), "&7Mineshafts found: &a" + d.mineshaftsFound,
                "&7Corpses looted: &a" + d.corpsesLooted, "&7Suspicious Scrap found: &a" + d.scrapsFound));
        menu.set(10, Icons.of(Material.WRITABLE_BOOK, "&6Commissions", "&7Frozen Tunnels commissions.", "", "&eClick to open!"),
                c -> m.comms.open(c.player(), "glacite_tunnels"));
        menu.set(12, Icons.of(Material.PACKED_ICE, "&bFrozen Mineshafts",
                "&7Mining the frozen tunnels slowly", "&7opens a mineshaft: veins of one", "&7ore, and frozen corpses to loot.", "",
                "&7Pity left: &a" + Math.max(0, d.mineshaftPity), "&7Exit pays &b1,000 &7+ &b500&7/scrap", "&7+ &b250&7/corpse + &b10&7/Cold.",
                "", "&eClick to enter one you found!"), c -> m.tunnels.enter(c.player()));
        menu.set(14, Icons.of(Material.BRUSH, "&aFossil Excavator", "&7Dig Suspicious Scrap for fossils.", "", "&eClick to open!"),
                c -> m.excavator.open(c.player()));
        menu.set(16, Icons.of(Material.BONE_BLOCK, "&6Fossil Research", "&7Donate each fossil kind once.", "",
                "&7Donated: &a" + d.fossilsDonated.size() + "&7/" + Excavator.DONATABLE.size(), "", "&eClick to view!"),
                c -> m.excavator.openResearch(c.player()));
        menu.set(18, Icons.of(Material.SKELETON_SKULL, "&6Fossil Muncher", "&7Feed it one fossil a day.", "",
                MiningUtil.today().equals(d.muncherDate) ? "&aFed today!" : "&eClick to open!"), c -> m.excavator.openMuncher(c.player()));
        menu.set(20, Icons.of(Material.TRIPWIRE_HOOK, "&5Corpse Keys",
                "&9Lapis &7corpses open freely.", "&6Umber &7and &8Tungsten &7corpses need", "&7their key; &dVanguard &7corpses a", "&7Skeleton Key. Keys are made at the", "&6Forge&7.", "", "&eClick to open the Forge!"),
                c -> m.forge.open(c.player()));
        menu.set(22, Icons.of(Material.IRON_PICKAXE, "&aPickaxe Smith", "&7Pickaxes, Biofuel and Chisels.", "", "&eClick to shop!"),
                c -> openShop(c.player(), "Pickaxe Smith"));
        menu.set(24, Icons.of(Material.SOUL_CAMPFIRE, "&dHollows Merchant", "&7Portable Campfires, ropes and", "&7compasses.", "", "&eClick to browse!"),
                c -> openShop(c.player(), "Hollows Merchant"));
        menu.set(31, Icons.back("Mining"), c -> openMain(c.player()));
        menu.open(p);
    }

    // ---- shops ------------------------------------------------------------------------------------

    void openShop(Player p, String station) {
        List<Recipe> list = new ArrayList<>();
        for (Recipe r : Sky.recipes().byType(Recipe.Type.NPC_SHOP)) if (station.equals(r.station) && MiningUtil.exists(r.result)) list.add(r);
        Sky.menus().paged(station, list, r -> shopIcon(p, r), pc -> buy(pc.click().player(), pc.entry(), station),
                pg -> pg.set(48, Icons.back("Mining"), c -> openMain(c.player())), 0).open(p);
    }

    private ItemStack shopIcon(Player p, Recipe r) {
        ItemStack it = Sky.items().create(r.result, Math.max(1, r.amount));
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Cost:");
        for (String c : r.inputs) {
            String[] cc = MiningUtil.cell(c);
            if (cc == null) continue;
            if ("COINS".equals(cc[0])) lore.add("&6" + Text.commas(Double.parseDouble(cc[1])) + " Coins");
            else lore.add(MiningUtil.itemName(cc[0]) + " &8x" + cc[1]);
        }
        if (r.unlock != null && !Sky.recipes().unlocked(p, r)) { lore.add(""); lore.add("&cRequires " + Sky.requirements().describe(r.unlock)); }
        lore.add("");
        lore.add("&eClick to buy!");
        return Icons.withLore(it, lore);
    }

    private void buy(Player p, Recipe r, String station) {
        if (r.unlock != null && !Sky.requirements().checkOrTell(p, r.unlock)) return;
        String carry = m.forge.takeInputs(p, r);
        if (Forge.FAILED.equals(carry)) return;
        Sky.loot().give(p, Forge.makeResult(r.result, Math.max(1, r.amount), carry), LootSource.NPC_SHOP);
        Msg.success(p, "You bought " + Text.strip(MiningUtil.itemName(r.result)) + "!");
        Msg.sound(p, Sound.ENTITY_VILLAGER_YES, 1f);
        openShop(p, station);
    }

    // ---- the King ----------------------------------------------------------------------------------

    String kingToday() { return KINGS[(int) Math.floorMod(m.skyMall.dayKey(), (long) KINGS.length)]; }

    void openKing(Player p) {
        MiningData d = MiningUtil.data(p);
        String king = kingToday();
        boolean claimed = MiningUtil.today().equals(d.kingDate);
        Menu menu = new Menu(king, 4).chrome();
        menu.set(11, Icons.of(Material.GOLDEN_HELMET, "&6" + king,
                "&7The Dwarven throne changes hands", "&7every SkyBlock day. Meet all six", "&7Kings for a royal gift.", "",
                "&7Kings met: &a" + d.kingsVisited.size() + "&7/" + KINGS.length));
        menu.set(13, Icons.of(claimed ? Material.MINECART : Material.CHEST_MINECART, claimed ? "&7Daily Gift claimed" : "&aDaily Gift",
                "&2+" + Text.commas(m.dials.kingDailyPowder.get()) + " Mithril Powder", "", claimed ? "&cCome back tomorrow!" : "&eClick to claim!"),
                claimed ? null : c -> claimKing(c.player()));
        menu.set(15, Icons.of(Material.WRITABLE_BOOK, "&6Commissions", "", "&eClick to open!"), c -> m.comms.open(c.player(), "dwarven_mines"));
        menu.set(31, Icons.back("Mining"), c -> openMain(c.player()));
        menu.open(p);
    }

    void claimKing(Player p) {
        MiningData d = MiningUtil.data(p);
        if (MiningUtil.today().equals(d.kingDate)) { Msg.error(p, "You already received today's gift."); return; }
        d.kingDate = MiningUtil.today();
        String king = kingToday();
        m.hotm.grant(p, "MITHRIL", m.dials.kingDailyPowder.get(), "king daily", false);
        d.kingsVisited.add(king);
        Msg.npc(p, "&6" + king, List.of("Welcome, miner. Take this for your trouble."), null);
        if (d.kingsVisited.size() >= KINGS.length && !d.kingTalismanGiven && MiningUtil.exists("KING_TALISMAN")) {
            d.kingTalismanGiven = true;
            Sky.loot().give(p, "KING_TALISMAN", 1, LootSource.REWARD);
            Msg.box(p, "&6&lROYAL GIFT", List.of("&7You met all six Kings!", MiningUtil.itemName("KING_TALISMAN")));
        }
        openKing(p);
    }

    // ---- Fetcher (reference: Fetchur) ----------------------------------------------------------------

    MiningLoot.Line fetchToday() {
        List<MiningLoot.Line> l = m.loot.f.fetchur;
        if (l.isEmpty()) return null;
        long day = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        for (int i = 0; i < l.size(); i++) {
            MiningLoot.Line x = l.get((int) Math.floorMod(day + i, (long) l.size()));
            if (MiningUtil.exists(x.item)) return x;
        }
        return null;
    }

    void openFetcher(Player p) {
        MiningData d = MiningUtil.data(p);
        MiningLoot.Line want = fetchToday();
        boolean done = MiningUtil.today().equals(d.fetchurDate);
        Menu menu = new Menu("Fetcher", 4).chrome();
        if (want == null) menu.set(13, Icons.of(Material.BARRIER, "&cFetcher is napping."));
        else {
            int have = Sky.items().count(p, want.item);
            ItemStack icon = Icons.amount(Sky.items().create(want.item), Math.max(1, want.amount));
            menu.set(13, Icons.withLore(icon, List.of("", "&7\"Bring me &f" + want.amount + "x " + Text.strip(MiningUtil.itemName(want.item)) + "&7, will ya?\"", "",
                    "&7You have: " + (have >= want.amount ? "&a" : "&c") + have, "",
                    "&7Reward: &2" + Text.commas(m.dials.fetchurPowder.get()) + " Mithril Powder &7+ &6" + Text.commas(m.dials.fetchurCoins.get()) + " Coins",
                    "", done ? "&cAlready done today!" : have >= want.amount ? "&eClick to hand it over!" : "&cYou don't have enough.")),
                    done ? null : c -> {
                        Player q = c.player();
                        MiningData dd = MiningUtil.data(q);
                        // re-check at click time: the handler runs a tick later and a double click must not pay twice
                        if (MiningUtil.today().equals(dd.fetchurDate)) { Msg.error(q, "You already helped the Fetcher today."); return; }
                        // count first, then take: a partial remove would eat the items and pay nothing
                        if (Sky.items().count(q, want.item) < want.amount) { Msg.error(q, "You don't have that."); return; }
                        int took = Sky.items().remove(q, want.item, want.amount);
                        if (took < want.amount) {
                            if (took > 0) Sky.loot().give(q, want.item, took, LootSource.OTHER);
                            Msg.error(q, "You don't have that.");
                            return;
                        }
                        dd.fetchurDate = MiningUtil.today();
                        m.hotm.grant(q, "MITHRIL", m.dials.fetchurPowder.get(), "fetcher", false);
                        Sky.economy().deposit(q, m.dials.fetchurCoins.get(), "mining: fetcher daily");
                        Msg.npc(q, "&eFetcher", List.of("Hah! Just what I needed. Here, take this."), null);
                        openFetcher(q);
                    });
        }
        menu.set(31, Icons.back("Mining"), c -> openMain(c.player()));
        menu.open(p);
    }

    // ---- Riddlewright (reference: Puzzler) -----------------------------------------------------------

    /** Five arrows from the centre of a 5x5 board; deterministic per player per day. Returns the moves (0 N,1 E,2 S,3 W). */
    static int[] puzzle(String seed) {
        Random r = new Random(seed.hashCode());
        int[] moves = new int[5];
        int x = 2, y = 2;
        for (int i = 0; i < moves.length; i++) {
            int mv;
            int nx, ny;
            do {
                mv = r.nextInt(4);
                nx = x + (mv == 1 ? 1 : mv == 3 ? -1 : 0);
                ny = y + (mv == 2 ? 1 : mv == 0 ? -1 : 0);
            } while (nx < 0 || nx > 4 || ny < 0 || ny > 4);
            moves[i] = mv;
            x = nx; y = ny;
        }
        return moves;
    }

    static int[] solve(int[] moves) {
        int x = 2, y = 2;
        for (int mv : moves) { x += mv == 1 ? 1 : mv == 3 ? -1 : 0; y += mv == 2 ? 1 : mv == 0 ? -1 : 0; }
        return new int[]{x, y};
    }

    void openPuzzle(Player p) {
        MiningData d = MiningUtil.data(p);
        // puzzlerDate = today once solved; "x" + today once failed (one answer a day: 25 squares would
        // otherwise make the puzzle a matter of clicking every one)
        boolean done = MiningUtil.today().equals(d.puzzlerDate) || ("x" + MiningUtil.today()).equals(d.puzzlerDate);
        boolean failed = ("x" + MiningUtil.today()).equals(d.puzzlerDate);
        int[] moves = puzzle(MiningUtil.today() + p.getUniqueId());
        int[] ans = solve(moves);
        String[] arrows = {"▲", "▶", "▼", "◀"};
        StringBuilder sb = new StringBuilder();
        for (int mv : moves) sb.append("&e").append(arrows[mv]).append(" ");
        Menu menu = new Menu("Riddlewright", 6).chrome();
        menu.set(8, Icons.of(Material.OAK_SIGN, "&eRiddlewright's Puzzle", "&7Start on the &agreen &7square.", "&7Follow the arrows:", sb.toString().trim(),
                "&7then click where you end up.", "&8You get one answer a day.", "", "&7Reward: &2" + Text.commas(m.dials.puzzlerPowder.get()) + " Mithril Powder", "",
                failed ? "&cWrong answer today. Come back tomorrow!" : done ? "&cSolved today already." : "&eGood luck!"));
        for (int y = 0; y < 5; y++) for (int x = 0; x < 5; x++) {
            int slot = y * 9 + 2 + x;
            final int fx = x, fy = y;
            boolean start = x == 2 && y == 2;
            menu.set(slot, Icons.of(start ? Material.LIME_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE, start ? "&aStart" : "&7Square", "", done ? "&8Come back tomorrow." : "&eClick to answer!"),
                    done ? null : c -> {
                        Player q = c.player();
                        MiningData dq = MiningUtil.data(q);
                        String today = MiningUtil.today();
                        if (today.equals(dq.puzzlerDate) || ("x" + today).equals(dq.puzzlerDate)) { Msg.error(q, "You already answered today's puzzle."); return; }
                        if (fx == ans[0] && fy == ans[1]) {
                            dq.puzzlerDate = today;
                            m.hotm.grant(q, "MITHRIL", m.dials.puzzlerPowder.get(), "riddlewright", false);
                            Msg.npc(q, "&eRiddlewright", List.of("Correct! Sharp mind for a miner."), null);
                            Msg.sound(q, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
                        } else {
                            dq.puzzlerDate = "x" + today;
                            Msg.npc(q, "&eRiddlewright", List.of("Wrong! The arrows ended on another square. Try again tomorrow."), null);
                            Msg.sound(q, Sound.ENTITY_VILLAGER_NO, 1f);
                        }
                        q.closeInventory();
                    });
        }
        menu.set(48, Icons.back("Mining"), c -> openMain(c.player()));
        menu.open(p);
    }

    // ---- block guide -------------------------------------------------------------------------------

    void openGuide(Player p, int page) {
        List<BlockTable.Def> list = new ArrayList<>(m.blocks.all());
        Sky.menus().paged("Mining Guide", list, def -> {
            Material mat = Material.matchMaterial(def.materials.isEmpty() ? "STONE" : def.materials.get(0));
            if (mat == null || !mat.isItem()) mat = Material.STONE;
            List<String> lore = new ArrayList<>();
            lore.add("&7Strength: &f" + Text.commas(def.strength));
            lore.add("&7Breaking Power: &2" + def.breakingPower);
            lore.add("&7Insta-mine at: &6" + Text.commas((def.kind.sixtyRule() ? 60 : 30) * def.strength + 1) + " ⸕");
            lore.add("&7Drops: " + MiningUtil.itemName(def.drop) + (def.max > 1 ? " &8x" + def.min + "-" + def.max : ""));
            lore.add("&7Mining XP: &3" + Text.commas(def.xp));
            if (def.powder != null) lore.add("&7Powder: " + MiningUtil.powderColor(def.powder) + def.powderAmount + " " + MiningUtil.powderName(def.powder));
            lore.add("&7Found on: &f" + String.join(", ", def.islands).replace("_", " "));
            if (def.areaOnly) lore.add("&8Only inside the marked ore areas.");
            return Icons.of(mat, "&a" + def.name, lore);
        }, pc -> { }, pg -> pg.set(48, Icons.back("Mining"), c -> openMain(c.player())), page).open(p);
    }

    // ---- sidebar + tab -------------------------------------------------------------------------------

    List<String> sidebar(Player p) {
        if (!MiningUtil.ready(p)) return List.of();
        String island = MiningUtil.island(p);
        if (island == null || !MiningUtil.POWDER_ISLANDS.contains(island)) return List.of();
        MiningData d = MiningUtil.data(p);
        String type = MiningUtil.powderOf(island);
        List<String> out = new ArrayList<>();
        out.add(MiningUtil.powderColor(type) + MiningUtil.DOT + " " + MiningUtil.powderName(type).replace(" Powder", "") + ": " + MiningUtil.powderColor(type) + Text.commas(d.powder(type)));
        // Commission progress at a glance (wave-4 playtest: it was only in /commissions, and the AI show's observer
        // reads the sidebar, not the tab). One line in slot order; names are in the tab widget and the menu.
        if (m.dials.sidebarCommissions.get()) {
            String ci = "mineshaft".equals(island) ? "glacite_tunnels" : island;
            if (Commissions.ISLANDS.contains(ci)) {
                String line = MiningMath.commissionLine(m.comms.active(p, ci));
                if (!line.isEmpty()) out.add(line);
            }
        }
        MiningEvents.Running r = m.events.current("mineshaft".equals(island) ? "glacite_tunnels" : island);
        if (r != null) out.add(r.type.color + r.type.display + " &7" + Text.duration((r.until - System.currentTimeMillis()) / 1000));
        else if ("crystal_hollows".equals(island) && m.hollows.heat(p) > 0) out.add("&cHeat: " + m.hollows.heat(p));
        else if (m.tunnels.cold(p) > 0) out.add("&bCold: -" + m.tunnels.cold(p));
        return out;
    }

    List<String> tabCommissions(Player p) {
        if (!MiningUtil.ready(p)) return List.of();
        String island = MiningUtil.island(p);
        if ("mineshaft".equals(island)) island = "glacite_tunnels";
        if (island == null || !Commissions.ISLANDS.contains(island)) return List.of();
        List<String> out = new ArrayList<>();
        out.add("&9&lCommissions:");
        for (MiningData.Commission c : m.comms.active(p, island)) {
            String pct = c.done() ? "&aDONE" : "&e" + MiningMath.commissionPercent(c.progress, c.target) + "%";
            out.add(" &f" + (c.name == null ? c.id : c.name) + ": " + pct);
        }
        return out;
    }

    List<String> tabPowders(Player p) {
        if (!MiningUtil.ready(p) || !MiningUtil.onMiningIsland(p)) return List.of();
        MiningData d = MiningUtil.data(p);
        List<String> out = new ArrayList<>();
        out.add("&9&lPowders:");
        for (String t : MiningUtil.POWDERS) out.add(" " + MiningUtil.powderColor(t) + MiningUtil.powderName(t).replace(" Powder", "") + ": &f" + Text.commas(d.powder(t)));
        out.add(" &7Ability: &6" + PickaxeAbilities.name(d.selectedAbility));
        return out;
    }
}
