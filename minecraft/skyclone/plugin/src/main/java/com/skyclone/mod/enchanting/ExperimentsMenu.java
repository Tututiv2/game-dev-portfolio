package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The Experimentation Table (research 08 §3.4): a daily Superpairs game plus two optional add-ons
 * (Chronomatron, Ultrasequencer) that bank bonus Superpairs clicks. Six tiers gated by Enchanting
 * level. Games are pure state machines ({@link SuperpairsBoard}, {@link SequenceGame}); this class
 * draws them and drives their timers. Rewards are paid exactly once, when a game ends or its menu closes.
 */
final class ExperimentsMenu {

    private ExperimentsMenu() {}

    private static final Map<UUID, Game> GAMES = new HashMap<>();
    private static int task = -1;
    private static final Random RND = new Random();

    private static final Material[] KEY_DIM = {Material.RED_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
            Material.LIME_TERRACOTTA, Material.CYAN_TERRACOTTA, Material.BLUE_TERRACOTTA, Material.PURPLE_TERRACOTTA};
    private static final Material[] KEY_LIT = {Material.RED_STAINED_GLASS, Material.ORANGE_STAINED_GLASS, Material.YELLOW_STAINED_GLASS,
            Material.LIME_STAINED_GLASS, Material.CYAN_STAINED_GLASS, Material.BLUE_STAINED_GLASS, Material.PURPLE_STAINED_GLASS};
    private static final String[] KEY_NAME = {"&cRed", "&6Orange", "&eYellow", "&aLime", "&3Cyan", "&9Blue", "&5Purple"};

    /** A running game bound to one player and one menu. */
    private abstract static class Game {
        final Player p;
        final ExperimentSpec.Tier tier;
        final Menu menu;
        boolean done;
        Game(Player p, ExperimentSpec.Tier tier, String title) {
            this.p = p;
            this.tier = tier;
            this.menu = new Menu(title, 6);
            menu.onClose(pl -> finish(true));
        }
        abstract void tick();
        abstract void finish(boolean closed);
    }

    static void ensureTask() {
        if (task != -1) return;
        task = Bukkit.getScheduler().scheduleSyncRepeatingTask(Sky.plugin(), () -> {
            for (Game g : new ArrayList<>(GAMES.values())) {
                if (!g.p.isOnline()) { g.finish(true); continue; }
                try { g.tick(); } catch (RuntimeException ex) { Sky.log().warning("[enchanting] experiment tick: " + ex); g.finish(true); }
            }
        }, 2L, 2L);
    }

    static void shutdown() {
        for (Game g : new ArrayList<>(GAMES.values())) g.finish(true);
        GAMES.clear();
        if (task != -1) Bukkit.getScheduler().cancelTask(task);
        task = -1;
    }

    // ---- main menu -------------------------------------------------------------------------------------

    static void open(Player p) { build(p).open(p); }

    static Menu build(Player p) {
        Menu m = new Menu("Experimentation Table", 6).chrome();
        PlayerData d = p == null ? new PlayerData() : EnchantingModule.charges(p);
        int max = EnchantingModule.dials().experimentsMaxCharges.get();
        int left = Math.max(0, d.charges);
        long until = ExperimentCharges.untilNext(d, System.currentTimeMillis());
        m.set(4, Icons.of(Material.ENCHANTING_TABLE, "&aExperimentation Table",
                "&7Run enchanting experiments for",
                "&7Enchanting XP, Experience Bottles",
                "&7and rare enchanted books.", "",
                "&7Charges: " + (left > 0 ? "&a" : "&c") + left + "&7/&a" + max,
                until > 0 ? "&7Next charge in: &e" + com.skyclone.api.ui.Text.duration(until / 1000) : "&7The table is fully charged.",
                "&7Bonus Twin Tiles clicks: &e+" + d.bonusClicks));
        ExperimentSpec.ExtraRun extra = p == null ? null
                : ExperimentCharges.nextExtra(d, EnchantingModule.experiments(), EnchantingModule.dials().experimentsExtraRuns.get());
        if (extra != null) {
            m.set(31, Icons.of(Material.EXPERIENCE_BOTTLE, "&aBuy an Extra Game",
                    "&7Out of charges? Buy one more", "&7Twin Tiles game for today.", "",
                    "&7Cost", "&3" + extra.levels + " Exp Levels", "&b" + extra.bits + " Bits", "",
                    "&7Extra games today: &a" + d.extraRunsToday + "&7/&a" + EnchantingModule.dials().experimentsExtraRuns.get(),
                    "&7Resets in: &e" + resetIn(), "", "&eClick to buy!"),
                    c -> { buyExtra(c.player()); build(c.player()).open(c.player()); });
        }
        m.set(20, Icons.of(Material.CLOCK, "&aEcho Keys",
                "&7Repeat the melody the table plays.",
                "&7Each round adds a note.", "",
                "&7Reward: &3Enchanting XP&7 and up to",
                "&e+3 &7Twin Tiles clicks.", "",
                d.chronomatronDone ? "&cAlready played before your next Twin Tiles." : left > 0 ? "&eClick to choose a tier!" : "&cNo charges left."),
                c -> { if (!d.chronomatronDone && left > 0) tiers(c.player(), "chronomatron").open(c.player()); else Msg.error(c.player(), "Not available right now."); });
        m.set(22, Icons.glow(Icons.of(Material.ENCHANTED_BOOK, "&aTwin Tiles",
                "&7Flip tiles and find matching pairs.",
                "&7Every pair you find is yours.", "",
                "&7Play the add-ons first for", "&7bonus clicks!", "",
                left > 0 ? "&eClick to choose a tier!" : "&cNo charges left.")),
                c -> { if (left > 0) tiers(c.player(), "superpairs").open(c.player()); else Msg.error(c.player(), "The table has no charges left!"); });
        m.set(24, Icons.of(Material.REPEATER, "&aNumber Vault",
                "&7Memorise where the numbers are,",
                "&7then click them in order.",
                "&7Each round adds a number.", "",
                "&7Reward: &3Enchanting XP&7 and up to",
                "&e+3 &7Twin Tiles clicks.", "",
                d.ultrasequencerDone ? "&cAlready played before your next Twin Tiles." : left > 0 ? "&eClick to choose a tier!" : "&cNo charges left."),
                c -> { if (!d.ultrasequencerDone && left > 0) tiers(c.player(), "ultrasequencer").open(c.player()); else Msg.error(c.player(), "Not available right now."); });
        m.set(40, Icons.of(Material.PAPER, "&aYour Experiment Stats",
                "&7Twin Tiles played: &a" + d.superpairsPlayed,
                "&7Pairs found: &a" + d.pairsMatched,
                "&7Best Echo Keys: &a" + d.bestChronomatron + " rounds",
                "&7Best Number Vault: &a" + d.bestUltrasequencer + " rounds",
                "&7Items enchanted: &a" + d.tableEnchants,
                "&7Anvil combines: &a" + d.anvilCombines));
        return m;
    }

    static String resetIn() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime next = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay();
        long secs = java.time.Duration.between(now, next).getSeconds();
        return com.skyclone.api.ui.Text.duration(secs);
    }

    static Menu tiers(Player p, String game) {
        String title = switch (game) {
            case "chronomatron" -> "Echo Keys";
            case "ultrasequencer" -> "Number Vault";
            default -> "Twin Tiles";
        };
        Menu m = new Menu(title + " (Tiers)", 6).chrome();
        m.back("Experimentation Table", () -> build(p));
        Material[] dyes = {Material.LIME_DYE, Material.GREEN_DYE, Material.LIGHT_BLUE_DYE, Material.BLUE_DYE, Material.PURPLE_DYE, Material.MAGENTA_DYE};
        int ench = p == null ? 99 : EnchantingModule.enchantingLevel(p);
        boolean enforce = EnchantingModule.dials().enforceSkill.get();
        List<ExperimentSpec.Tier> tiers = EnchantingModule.experiments().tiers;
        int[] slots = {19, 20, 21, 23, 24, 25};
        for (int i = 0; i < tiers.size() && i < slots.length; i++) {
            ExperimentSpec.Tier t = tiers.get(i);
            boolean locked = enforce && t.skill > ench;
            List<String> lore = new ArrayList<>();
            if ("superpairs".equals(game)) {
                lore.add("&7Grid: &a" + t.rows + "x" + t.cols + " &7(" + t.pairs() + " pairs)");
                lore.add("&7Clicks: &a" + (t.clicks + EnchantingModule.dials().superpairsExtraClicks.get()));
                lore.add("&7Enchanting XP per pair: &3" + Math.round(t.pairXp * EnchantingModule.dials().superpairsXpMultiplier.get()));
                if (t.ultraRareChance > 0) lore.add("&dUltra-rare Endcap Upgrades can appear!");
                lore.add("");
                long fee = fee(t);
                lore.add("&7Cost: " + (fee > 0 ? "&6" + Sky.economy().format(fee) + " coins" : "&aFree") + " &7+ &a1 charge");
                lore.add("");
                lore.add("&7Possible rewards include:");
                for (String r : rewardPreview(t, 6)) lore.add("&8- " + r);
            } else {
                int rounds = "chronomatron".equals(game) ? t.chronomatronRounds : t.ultrasequencerRounds;
                lore.add("&7Rounds: &a" + rounds);
                lore.add("&7Enchanting XP per round: &3" + Math.round(EnchantingModule.experiments().addonXp(i) * EnchantingModule.dials().addonXpMultiplier.get()));
            }
            lore.add("");
            lore.add(locked ? "&cRequires Enchanting " + t.skill + "!" : "&eClick to start!");
            ItemStack icon = Icons.of(locked ? Material.GRAY_DYE : dyes[i], (locked ? "&c" : "&a") + t.name, lore);
            final ExperimentSpec.Tier tier = t;
            m.set(slots[i], icon, c -> {
                if (locked) { Msg.error(c.player(), "Requires Enchanting " + tier.skill + "!"); return; }
                start(c.player(), game, tier);
            });
        }
        return m;
    }

    static List<String> rewardPreview(ExperimentSpec.Tier t, int max) {
        List<ExperimentSpec.Reward> sorted = new ArrayList<>(t.rewards);
        sorted.sort((a, b) -> Integer.compare(b.weight, a.weight));
        List<String> out = new ArrayList<>();
        for (ExperimentSpec.Reward r : sorted) {
            if (out.size() >= max) { out.add("&8...and " + (t.rewards.size() - max) + " more"); break; }
            out.add(rewardName(r));
        }
        return out;
    }

    static String rewardName(ExperimentSpec.Reward r) {
        EnchantRegistry.BookRef b = EnchantingModule.reg().parseBook(r.item);
        if (b != null) {
            EnchantSpec s = EnchantingModule.reg().spec(b.enchant());
            return s.displayName(b.level());
        }
        ItemDef d = Sky.items().def(r.item);
        String n = d == null ? r.item : d.rarity.code() + d.name;
        return n + (r.amount > 1 ? " &8x" + r.amount : "");
    }

    /** Twin Tiles starting cost for a tier, after the fee dial. */
    static long fee(ExperimentSpec.Tier t) {
        return Math.max(0, Math.round(t.fee * Math.max(0, EnchantingModule.dials().superpairsFeeMultiplier.get())));
    }

    /** Buy one extra game (XP levels + bits) once the charges are spent; at most the dial's count per UTC day. */
    static void buyExtra(Player p) {
        PlayerData d = EnchantingModule.charges(p);
        ExperimentSpec.ExtraRun run = ExperimentCharges.nextExtra(d, EnchantingModule.experiments(), EnchantingModule.dials().experimentsExtraRuns.get());
        if (run == null) { Msg.error(p, d.charges > 0 ? "You still have a charge to use!" : "No more extra games today. Resets in " + resetIn() + "."); return; }
        boolean creative = p.getGameMode() == org.bukkit.GameMode.CREATIVE;
        if (!creative && p.getLevel() < run.levels) { Msg.error(p, "You need " + run.levels + " experience levels!"); return; }
        if (Sky.economy().bits(p) < run.bits) { Msg.error(p, "You need " + run.bits + " Bits!"); return; }
        if (!Sky.economy().spendBits(p, run.bits, "experiments_extra_game")) { Msg.error(p, "You need " + run.bits + " Bits!"); return; }
        if (!EnchantingModule.takeLevels(p, run.levels)) {
            Sky.economy().addBits(p, run.bits, "experiments_extra_game_refund");
            Msg.error(p, "You need " + run.levels + " experience levels!");
            return;
        }
        ExperimentCharges.bought(d);
        Sky.profiles().saveNow(p);
        Msg.success(p, "Bought an extra experiment charge!");
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f);
    }

    static void start(Player p, String game, ExperimentSpec.Tier tier) {
        if (GAMES.containsKey(p.getUniqueId())) { Msg.error(p, "You are already running an experiment!"); return; }
        PlayerData d = EnchantingModule.charges(p);
        if (d.charges <= 0) {
            long until = ExperimentCharges.untilNext(d, System.currentTimeMillis());
            Msg.error(p, "The table has no charges left! Next charge in " + com.skyclone.api.ui.Text.duration(until / 1000) + ".");
            return;
        }
        ensureTask();
        Game g;
        switch (game) {
            case "chronomatron" -> {
                if (d.chronomatronDone) { Msg.error(p, "Play Twin Tiles before another Echo Keys."); return; }
                g = new Chrono(p, tier);
            }
            case "ultrasequencer" -> {
                if (d.ultrasequencerDone) { Msg.error(p, "Play Twin Tiles before another Number Vault."); return; }
                g = new Ultra(p, tier);
            }
            default -> {
                long fee = fee(tier);
                if (fee > 0 && !Sky.economy().withdraw(p, fee, "experiments_" + tier.id)) {
                    Msg.error(p, "You need " + Sky.economy().format(fee) + " coins to start " + tier.name + " Twin Tiles!");
                    return;
                }
                if (!ExperimentCharges.spend(d, System.currentTimeMillis(), EnchantingModule.dials().experimentsMaxCharges.get(), EnchantingModule.chargePeriodMs())) {
                    if (fee > 0) Sky.economy().deposit(p, fee, "experiments_refund");
                    Msg.error(p, "The table has no charges left!");
                    return;
                }
                d.superpairsToday++;
                int clicks = tier.clicks + EnchantingModule.dials().superpairsExtraClicks.get() + d.bonusClicks;
                d.bonusClicks = 0;
                d.chronomatronDone = false;
                d.ultrasequencerDone = false;
                d.superpairsPlayed++;
                Sky.profiles().saveNow(p);
                g = new Pairs(p, tier, clicks);
            }
        }
        GAMES.put(p.getUniqueId(), g);
        g.menu.drawer(x -> draw(g));
        g.menu.open(p);
    }

    private static void draw(Game g) {
        if (g instanceof Pairs pg) pg.draw();
        else if (g instanceof Chrono cg) cg.draw();
        else if (g instanceof Ultra ug) ug.draw();
    }

    // ---- Superpairs --------------------------------------------------------------------------------------

    private static final class Pairs extends Game {
        final SuperpairsBoard board;
        final int[] slots;
        int hideAt = -1;
        int ticks;

        Pairs(Player p, ExperimentSpec.Tier t, int clicks) {
            super(p, t, "Twin Tiles (" + t.name + ")");
            board = SuperpairsBoard.deal(t, clicks, RND, EnchantingModule.ultraRareBoost(p), ultraPool(),
                    EnchantingModule.dials().ultraRareMultiplier.get());
            slots = new int[t.tiles()];
            int rows = Math.min(5, t.rows), cols = Math.min(9, t.cols);
            int r0 = rows >= 5 ? 0 : 1, c0 = (9 - cols) / 2;
            for (int i = 0; i < slots.length; i++) slots[i] = (r0 + i / cols) * 9 + c0 + i % cols;
        }

        void draw() {
            for (int i = 0; i < 54; i++) menu.set(i, Icons.filler());
            for (int i = 0; i < slots.length && i < board.tiles.size(); i++) {
                final int idx = i;
                SuperpairsBoard.Tile t = board.tiles.get(i);
                ItemStack icon;
                if (!t.revealed) icon = Icons.of(Material.PURPLE_STAINED_GLASS_PANE, "&a?", "&7Click any tile to flip it!", "&7Find two that match.");
                else if (t.kind == SuperpairsBoard.Kind.CLICKS) icon = Icons.of(Material.CLOCK, "&a+" + t.clicks + " Clicks", "&7Free clicks!");
                else if (t.kind == SuperpairsBoard.Kind.INSTANT) icon = Icons.glow(Icons.of(Material.ENDER_EYE, "&dInstant Find", "&7Claimed a pair for you!"));
                else {
                    icon = rewardIcon(t.reward);
                    icon = Icons.withLore(icon, List.of("", t.matched ? "&aMatched!" : "&eFind its pair!"));
                    if (t.matched) icon = Icons.glow(icon);
                }
                menu.set(slots[i], icon, board.finished() ? null : c -> click(idx));
            }
            menu.set(45, Icons.of(Material.EXPERIENCE_BOTTLE, "&aClicks Left: &e" + Math.max(0, board.clicksLeft),
                    "&7Clicks used: &a" + board.clicksUsed, "&7Pairs found: &a" + board.won.size() + "&7/&a" + tier.pairs()));
            List<String> won = new ArrayList<>();
            won.add("&7Rewards so far:");
            Map<String, Integer> sum = summarize(board.won);
            if (sum.isEmpty()) won.add("&8None yet");
            for (Map.Entry<String, Integer> e : sum.entrySet()) won.add("&8- " + e.getKey() + (e.getValue() > 1 ? " &8x" + e.getValue() : ""));
            if (done) { won.add(""); won.add("&aRewards claimed!"); }
            menu.set(53, Icons.of(Material.CHEST, "&aRewards", won));
            menu.set(49, Icons.close(), c -> c.player().closeInventory());
            if (done) menu.set(4, Icons.of(Material.LIME_DYE, "&aExperiment Over!", "&7Your rewards were added to your inventory."));
        }

        void click(int idx) {
            if (done) return;
            SuperpairsBoard.Outcome o = board.click(idx, RND);
            switch (o) {
                case IGNORED -> { return; }
                case FIRST -> Msg.sound(p, Sound.UI_BUTTON_CLICK, 1.4f);
                case MATCH, INSTANT_FIND -> Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f);
                case MISMATCH -> { Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f); hideAt = ticks + 8; }
                case BONUS_CLICKS -> Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
            }
            if (board.finished()) {
                board.hidePending();
                finish(false);
            }
            menu.redraw();
        }

        @Override void tick() {
            ticks++;
            if (hideAt > 0 && ticks >= hideAt) {
                hideAt = -1;
                if (board.pendingA >= 0) { board.hidePending(); menu.redraw(); }
            }
        }

        @Override void finish(boolean closed) {
            if (done) return;
            done = true;
            GAMES.remove(p.getUniqueId());
            if (!p.isOnline()) { payOffline(); return; }
            for (ExperimentSpec.Reward r : board.won) {
                if (!Sky.items().exists(r.item)) continue;
                Sky.loot().give(p, r.item, Math.max(1, r.amount), LootSource.REWARD);
                if (isUltraRare(r)) Msg.rareDrop(p, "&d&lULTRA RARE!", rewardName(r), 0);
            }
            double xp = board.won.size() * tier.pairXp;
            if (board.allFound()) xp += tier.pairXp * EnchantingModule.experiments().completionXpFactor;
            xp *= EnchantingModule.dials().superpairsXpMultiplier.get();
            EnchantingModule.addEnchantingXp(p, xp, "superpairs");
            PlayerData d = EnchantingModule.data(p);
            d.pairsMatched += board.won.size();
            Sky.profiles().saveNow(p);
            List<String> body = new ArrayList<>();
            body.add("&7Pairs found: &a" + board.won.size() + "&7/&a" + tier.pairs());
            body.add("&3+" + Math.round(xp) + " Enchanting XP");
            for (Map.Entry<String, Integer> e : summarize(board.won).entrySet()) body.add("&8+ " + e.getKey() + (e.getValue() > 1 ? " &8x" + e.getValue() : ""));
            Msg.box(p, "&d&lTWIN TILES &7(" + tier.name + ")", body);
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.0f);
            if (!closed) menu.redraw();
        }

        /** The player left mid-game: the rewards are not lost, they are given at the next ready join. */
        private void payOffline() {
            // Rare edge (quit with a game open). Online-only giving keeps every path through LootService;
            // log it so an admin can see what was forfeited.
            if (!board.won.isEmpty()) Sky.log().info("[enchanting] " + p.getName() + " left Twin Tiles with " + board.won.size() + " unclaimed pairs");
        }
    }

    /** The Endcap Upgrades that are registered right now (an unregistered id is never dealt). */
    static List<String> ultraPool() {
        List<String> out = new ArrayList<>();
        List<String> ids = EnchantingModule.experiments().ultraRare;
        if (ids != null) for (String id : ids) if (Sky.items().exists(id)) out.add(id);
        return out;
    }

    static boolean isUltraRare(ExperimentSpec.Reward r) {
        List<String> ids = EnchantingModule.experiments().ultraRare;
        return r != null && ids != null && ids.contains(r.item);
    }

    static ItemStack rewardIcon(ExperimentSpec.Reward r) {
        if (Sky.items().exists(r.item)) {
            ItemStack s = Sky.items().create(r.item, 1);
            return Icons.amount(s, r.amount);
        }
        return Icons.of(Material.BARRIER, "&c" + r.item);
    }

    static Map<String, Integer> summarize(List<ExperimentSpec.Reward> won) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ExperimentSpec.Reward r : won) out.merge(rewardName(r), 1, Integer::sum);
        return out;
    }

    // ---- shared add-on ending -----------------------------------------------------------------------------

    private static void addonEnd(Player p, ExperimentSpec.Tier tier, String name, SequenceGame g, boolean chrono) {
        int idx = EnchantingModule.experiments().index(tier);
        double xp = g.roundsCleared * EnchantingModule.experiments().addonXp(idx) * EnchantingModule.dials().addonXpMultiplier.get();
        int bonus = SequenceGame.bonusClicks(g.roundsCleared, g.maxRounds);
        if (!p.isOnline()) return;
        PlayerData d = EnchantingModule.data(p);
        if (chrono) { d.chronomatronDone = true; d.bestChronomatron = Math.max(d.bestChronomatron, g.roundsCleared); }
        else { d.ultrasequencerDone = true; d.bestUltrasequencer = Math.max(d.bestUltrasequencer, g.roundsCleared); }
        d.bonusClicks += bonus;
        EnchantingModule.addEnchantingXp(p, xp, chrono ? "chronomatron" : "ultrasequencer");
        Sky.profiles().saveNow(p);
        Msg.box(p, "&d&l" + name.toUpperCase() + " &7(" + tier.name + ")", List.of(
                "&7Rounds cleared: &a" + g.roundsCleared + "&7/&a" + g.maxRounds,
                "&3+" + Math.round(xp) + " Enchanting XP",
                "&e+" + bonus + " &7bonus Twin Tiles clicks"));
        Msg.sound(p, g.phase == SequenceGame.Phase.WON ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_VILLAGER_NO, 1.0f);
    }

    // ---- Chronomatron -------------------------------------------------------------------------------------

    private static final class Chrono extends Game {
        final SequenceGame g;
        final int keys;
        int ticks;
        int showIndex = -1;
        int nextAt;
        int litKey = -1;
        int unlightAt = -1;

        Chrono(Player p, ExperimentSpec.Tier t) {
            super(p, t, "Echo Keys (" + t.name + ")");
            keys = Math.max(2, Math.min(7, t.keys));
            g = new SequenceGame(SequenceGame.Mode.CHRONOMATRON, t.chronomatronRounds, keys, RND);
            nextAt = 20;
        }

        int col(int key) { return (9 - keys) / 2 + key; }

        void draw() {
            for (int i = 0; i < 54; i++) menu.set(i, Icons.filler());
            for (int k = 0; k < keys; k++) {
                final int key = k;
                boolean lit = k == litKey;
                for (int r = 1; r <= 3; r++) {
                    ItemStack icon = Icons.of(lit ? KEY_LIT[k] : KEY_DIM[k], KEY_NAME[k] + " Key", g.phase == SequenceGame.Phase.INPUT ? "&eClick to play!" : "&7Listen...");
                    if (lit) icon = Icons.glow(icon);
                    menu.set(r * 9 + col(k), icon, g.phase == SequenceGame.Phase.INPUT && !done ? c -> press(key) : null);
                }
            }
            String state = switch (g.phase) {
                case SHOWING -> "&eWatch and listen...";
                case INPUT -> "&aYour turn! &7(" + g.expected + "/" + g.sequence.size() + ")";
                case WON -> "&aYou cleared every round!";
                case LOST -> "&cWrong key!";
            };
            menu.set(4, Icons.of(Material.CLOCK, "&aRound " + (g.roundsCleared + (g.over() ? 0 : 1)), state,
                    "&7Rounds cleared: &a" + g.roundsCleared + "&7/&a" + g.maxRounds));
            menu.set(49, Icons.close(), c -> c.player().closeInventory());
        }

        void press(int key) {
            if (done || g.phase != SequenceGame.Phase.INPUT) return;
            int before = g.roundsCleared;
            boolean ok = g.input(key);
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_HARP, pitch(key));
            litKey = key;
            unlightAt = ticks + 2;
            if (!ok || g.over()) { finish(false); return; }
            if (g.roundsCleared > before) { showIndex = -1; nextAt = ticks + 10; }
            menu.redraw();
        }

        float pitch(int key) { return 0.6f + key * 0.2f; }

        @Override void tick() {
            ticks++;
            boolean redraw = false;
            if (unlightAt > 0 && ticks >= unlightAt) { litKey = -1; unlightAt = -1; redraw = true; }
            if (g.phase == SequenceGame.Phase.SHOWING && ticks >= nextAt) {
                showIndex++;
                if (showIndex >= g.sequence.size()) {
                    g.startInput();
                    redraw = true;
                } else {
                    int key = g.sequence.get(showIndex);
                    litKey = key;
                    int show = Math.max(2, EnchantingModule.dials().chronoShowTicks.get() / 2);
                    unlightAt = ticks + show;
                    nextAt = ticks + show + 2;
                    Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_HARP, pitch(key));
                    redraw = true;
                }
            }
            if (redraw) menu.redraw();
        }

        @Override void finish(boolean closed) {
            if (done) return;
            done = true;
            GAMES.remove(p.getUniqueId());
            addonEnd(p, tier, "Echo Keys", g, true);
            if (!closed) menu.redraw();
        }
    }

    // ---- Ultrasequencer -------------------------------------------------------------------------------------

    private static final class Ultra extends Game {
        static final int[] GRID;
        static {
            GRID = new int[28];
            int i = 0;
            for (int r = 1; r <= 4; r++) for (int c = 1; c <= 7; c++) GRID[i++] = r * 9 + c;
        }
        final SequenceGame g;
        int ticks;
        int hideAt;
        boolean showing = true;

        Ultra(Player p, ExperimentSpec.Tier t) {
            super(p, t, "Number Vault (" + t.name + ")");
            g = new SequenceGame(SequenceGame.Mode.ULTRASEQUENCER, t.ultrasequencerRounds, GRID.length, RND);
            hideAt = EnchantingModule.dials().ultraShowTicks.get() / 2;
        }

        void draw() {
            for (int i = 0; i < 54; i++) menu.set(i, Icons.filler());
            for (int i = 0; i < GRID.length; i++) {
                final int slotIdx = i;
                int number = g.sequence.indexOf(i) + 1;   // 0 when not part of this round
                boolean shown = number > 0 && (showing || number <= g.expected);
                ItemStack icon;
                if (shown) icon = Icons.amount(Icons.of(Material.LIME_STAINED_GLASS_PANE, "&a" + number), number);
                else icon = Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&7?", g.phase == SequenceGame.Phase.INPUT ? "&eClick the next number!" : "&7Memorise...");
                menu.set(GRID[i], icon, g.phase == SequenceGame.Phase.INPUT && !done ? c -> press(slotIdx) : null);
            }
            String state = switch (g.phase) {
                case SHOWING -> "&eRemember the numbers!";
                case INPUT -> "&aClick them in order! &7(" + g.expected + "/" + g.sequence.size() + ")";
                case WON -> "&aYou cleared every round!";
                case LOST -> "&cWrong slot!";
            };
            menu.set(4, Icons.of(Material.REPEATER, "&aRound " + (g.roundsCleared + (g.over() ? 0 : 1)), state,
                    "&7Rounds cleared: &a" + g.roundsCleared + "&7/&a" + g.maxRounds));
            menu.set(49, Icons.close(), c -> c.player().closeInventory());
        }

        void press(int slotIdx) {
            if (done || g.phase != SequenceGame.Phase.INPUT) return;
            int before = g.roundsCleared;
            boolean ok = g.input(slotIdx);
            Msg.sound(p, ok ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_NOTE_BLOCK_BASS, ok ? 0.8f + 0.05f * g.expected : 0.5f);
            if (!ok || g.over()) { finish(false); return; }
            if (g.roundsCleared > before) { showing = true; hideAt = ticks + 6 + EnchantingModule.dials().ultraShowTicks.get() / 2; }
            menu.redraw();
        }

        @Override void tick() {
            ticks++;
            if (showing && ticks >= hideAt) {
                showing = false;
                g.startInput();
                menu.redraw();
            }
        }

        @Override void finish(boolean closed) {
            if (done) return;
            done = true;
            GAMES.remove(p.getUniqueId());
            addonEnd(p, tier, "Number Vault", g, false);
            if (!closed) menu.redraw();
        }
    }

    static boolean playing(Player p) { return GAMES.containsKey(p.getUniqueId()); }
}
