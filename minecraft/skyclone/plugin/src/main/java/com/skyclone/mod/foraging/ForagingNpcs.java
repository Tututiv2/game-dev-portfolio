package com.skyclone.mod.foraging;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Every foraging NPC: the five forest keepers' log quests, the Park's axe trader, Lyra's harp,
 * the Ember Trials, the Hub woodcutter, and the Moonmarsh's keeper / axewright / sapwitch.
 */
final class ForagingNpcs {

    private ForagingNpcs() {}

    /** A keeper's three-stage log delivery (TUNE: the reference quest amounts are not in the research). */
    record Quest(String id, String npc, String name, String island, String anchor, String wood, String woodName,
                 String finalItem, List<String> greeting) {}

    static final int[] STAGE_LOGS = {64, 256, 1024};
    static final int[] STAGE_COINS = {1_000, 4_000, 15_000};
    static final int[] STAGE_XP = {500, 2_000, 8_000};

    static final List<Quest> QUESTS = List.of(
            new Quest("birch", "park_ranger", "&fRanger Ellery", "park", "npc.park_ranger", "LOG:2", "Birch Logs", "JUNGLE_AXE",
                    List.of("Welcome to the Park! Five forests, each higher than the last.", "Birch grows here. Bring me some and I'll make it worth your while.")),
            new Quest("spruce", "spruce_keeper", "&fKestrel", "park", "npc.spruce_keeper", "LOG:1", "Spruce Logs", "PARK_CAVE_TRAVEL_SCROLL",
                    List.of("Cold up here, isn't it? The wolves below don't mind.", "I'm stocking the cabin for winter. Spruce, if you've got it.")),
            new Quest("dark_oak", "thicket_warden", "&fWarden Ashby", "park", "npc.thicket_warden", "LOG_2:1", "Dark Oak Logs", null,
                    List.of("Mind the mushrooms. And the dark.", "Dark oak burns slow. I need a great deal of it.")),
            new Quest("acacia", "harpist", "&fLyra", "park", "npc.harpist", "LOG_2", "Acacia Logs", null,
                    List.of("Can you hear the plateau sing?", "My harp needs a new frame. Acacia rings the truest.")),
            new Quest("jungle", "jungle_elder", "&fMossbeard", "park", "npc.jungle_elder", "LOG:3", "Jungle Logs", "PARK_JUNGLE_TRAVEL_SCROLL",
                    List.of("You climbed all the way up here? Good legs.", "Bring me jungle wood and I'll show you the short way back."))
    );

    static final String[] TRIAL_WOODS = {"LOG", "LOG:2", "LOG:1", "LOG_2:1", "LOG_2", "LOG:3"};

    /** Harp songs: id, name, notes, Intelligence (TUNE: the reference's per-song numbers are not in the research). */
    record Song(String id, String name, int notes, int intelligence) {}

    static final List<Song> SONGS = List.of(
            new Song("hymn", "Morning Hymn", 5, 1), new Song("brother", "Brother Bell", 6, 1),
            new Song("grace", "Gentle Grace", 7, 2), new Song("lullaby", "Old Lullaby", 8, 2),
            new Song("round", "Birthday Round", 9, 2), new Song("sleeves", "Green Sleeves", 10, 3),
            new Song("minuet", "Minuet in G", 11, 3), new Song("joyful", "Joyful World", 12, 4));

    static Map<String, String> skins = new HashMap<>();

    static void register() {
        try (InputStream in = Sky.plugin().getResource("data/foraging/skins.json")) {
            if (in != null) skins = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {}.getType());
        } catch (Exception e) {
            Sky.log().warning("[foraging] skins.json: " + e.getMessage());
        }
        for (Quest q : QUESTS) npc(q.npc(), q.name(), q.island(), q.anchor(), "&eQUEST", p -> keeper(p, q));
        npc("axe_trader", "&fAxe-trader Hewitt", "park", "npc.axe_trader", "&eSHOP", ForagingNpcs::axeTrader);
        npc("ember_trials", "&fGaspard", "park", "npc.ember_trials", "&6TRIALS", ForagingNpcs::emberTrials);
        if (FDials.lumberJack.get()) npc("lumber_jack", "&fWoodcutter Haldane", "hub", "npc.lumber_jack", "&eQUEST", ForagingNpcs::lumberJack);
        npc("hotf_keeper", "&fKeeper Ilse", Chopper.MARSH, "npc.hotf_keeper", "&2HEART OF THE FOREST", ForagingNpcs::hotfKeeper);
        npc("marsh_axewright", "&fAxewright Thane", Chopper.MARSH, "npc.marsh_axewright", "&eSHOP", ForagingNpcs::marshShop);
        npc("sapwitch", "&fSapwitch Elowen", Chopper.MARSH, "npc.sapwitch", "&dESSENCE", ForagingNpcs::sapwitch);
        // wave 5: the Marsh contest host (ref. Agatha) at the Loch Outpost; her coupons are "Herbalist's Coupons"
        npc("herbalist", "&fHerbalist Jorunn", Chopper.MARSH, "npc.contest_host", "&eSTARFALL CONTEST", p -> {
            ForagingData d = ForagingModule.data(p);
            if (d.met.add("herbalist")) talk(p, "herbalist", List.of(
                    "Every day the stars fall a little lower over the loch.",
                    "Cut, pick, fish and hunt here while a contest runs, and I'll pay you in coupons.",
                    "Spend them in my shop. The Curator does the same over in the Torrent Canyon."), () -> Contest.menu(p).open(p));
            else Contest.menu(p).open(p);
        });

        // wave 5 (second pass): the jade dragon (ref. the Jade Dragon in the Dragon's Lair) in the Wyrm's Hollow under the loch
        npc("jade_dragon", "&aVerdigris", Chopper.MARSH, "npc.jade_dragon", "&2JADE DRAGON", p -> {
            ForagingData d = ForagingModule.data(p);
            if (d.met.add("jade_dragon")) talk(p, "jade_dragon", List.of(
                    "Few swim this deep. Fewer climb out dry.",
                    "I keep one egg. It hatches for one who has felled the Moonmarsh's oldest wood.",
                    "Bring me what I ask, and it is yours."), () -> jadeShop(p).open(p));
            else jadeShop(p).open(p);
        });

        // Lyra's songs grant Intelligence for good
        Sky.stats().addProvider("foraging_harp", (p, into) -> {
            if (!Sky.profiles().isReady(p)) return;
            int v = 0;
            ForagingData d = ForagingModule.data(p);
            for (Song s : SONGS) if (d.songs.contains(s.id())) v += s.intelligence();
            if (v > 0) into.add(com.skyclone.api.stat.Stat.INTELLIGENCE, v * FDials.harpIntScale.get());
        });
    }

    private static void npc(String id, String name, String island, String anchor, String title, Consumer<Player> onClick) {
        NpcDef d = NpcDef.of("foraging_" + id, name, island, anchor).title(title);
        String skin = skins.get(id);
        if (skin != null) d.skin(skin, null);
        Sky.npcs().register(d, p -> { if (Sky.profiles().isReady(p)) onClick.accept(p); });
    }

    static void talk(Player p, String npcId, List<String> lines, Runnable then) {
        Sky.npcs().talk(p, "foraging_" + npcId, lines, then);
    }

    // ================================================================================ keepers

    static void keeper(Player p, Quest q) {
        ForagingData d = ForagingModule.data(p);
        Runnable open = () -> ("harpist".equals(q.npc()) ? Harp.lyra(p) : questMenu(p, q)).open(p);
        if (d.met.add(q.npc())) { talk(p, q.npc(), q.greeting(), open); return; }
        open.run();
    }

    static Menu questMenu(Player p, Quest q) {
        ForagingData d = ForagingModule.data(p);
        int done = d.quests.getOrDefault(q.id(), 0);
        Menu m = new Menu(Text.strip(q.name()) + "'s Request", 4).chrome();
        for (int i = 0; i < 3; i++) {
            int stage = i;
            boolean complete = done > i, current = done == i;
            int need = STAGE_LOGS[i];
            int have = count(p, q.wood());
            List<String> lore = new ArrayList<>();
            lore.add("&7Deliver &a" + Text.commasInt(need) + " " + q.woodName() + "&7.");
            lore.add("");
            lore.add("&7Rewards:");
            lore.add(" &6" + Text.commasInt(STAGE_COINS[i] * FDials.questRewardScale.get()) + " Coins");
            lore.add(" &3" + Text.commasInt(STAGE_XP[i] * FDials.questRewardScale.get()) + " Foraging Experience");
            if (i == 2 && q.finalItem() != null && Sky.items().exists(q.finalItem())) lore.add(" " + Sky.items().def(q.finalItem()).rarity.code() + Text.strip(Sky.items().def(q.finalItem()).name));
            lore.add("");
            Material mat;
            if (complete) { lore.add("&aCompleted!"); mat = Material.LIME_DYE; }
            else if (current) {
                lore.add("&7You have: " + (have >= need ? "&a" : "&c") + Text.commasInt(have) + "&7/" + Text.commasInt(need) + " &8(inventory + sacks)");
                lore.add(have >= need ? "&eClick to deliver!" : "&cNot enough logs!");
                mat = Material.YELLOW_DYE;
            } else { lore.add("&cFinish the previous request first."); mat = Material.GRAY_DYE; }
            m.set(11 + i * 2, Icons.of(mat, (complete ? "&a" : current ? "&e" : "&c") + "Request " + Text.roman(i + 1), lore),
                    c -> { if (current) deliver(c.player(), q, stage); });
        }
        m.set(31, Icons.of(Material.OAK_SIGN, "&aThe Park's keepers", "&7Each forest's keeper wants their own", "&7wood. Requests " + done + "&8/3 &7done here."));
        return m;
    }

    private static void deliver(Player p, Quest q, int stage) {
        ForagingData d = ForagingModule.data(p);
        if (d.quests.getOrDefault(q.id(), 0) != stage) return;
        int need = STAGE_LOGS[stage];
        if (count(p, q.wood()) < need) { Msg.error(p, "You need " + Text.commasInt(need) + " " + q.woodName() + "!"); return; }
        if (take(p, q.wood(), need) < need) { Msg.error(p, "Could not take the logs."); return; }
        d.quests.put(q.id(), stage + 1);
        double coins = STAGE_COINS[stage] * FDials.questRewardScale.get();
        Sky.economy().deposit(p, coins, "foraging: " + q.id() + " request " + (stage + 1));
        Sky.service(SkillService.class).addXp(p, SkillType.FORAGING, STAGE_XP[stage] * FDials.questRewardScale.get(), "foraging:quest");
        List<String> body = new ArrayList<>(List.of("&6+" + Text.commasInt(coins) + " Coins", "&3+" + Text.commasInt(STAGE_XP[stage] * FDials.questRewardScale.get()) + " Foraging XP"));
        if (stage == 2 && q.finalItem() != null && Sky.items().exists(q.finalItem())) {
            Sky.loot().give(p, q.finalItem(), 1, LootSource.REWARD);
            body.add("&a+1 " + Sky.items().def(q.finalItem()).name);
        }
        Sky.profiles().saveNow(p);
        Msg.box(p, "&a&lREQUEST COMPLETE &7" + Text.strip(q.name()) + " " + Text.roman(stage + 1), body);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        questMenu(p, q).open(p);
    }

    /** Count in inventory plus sacks. */
    static int count(Player p, String id) {
        return Sky.items().count(p, id) + Sky.service(Features.Sacks.class).count(p, id);
    }

    /** Take from the inventory first, then sacks. Returns how many were taken. */
    static int take(Player p, String id, int n) {
        int got = Sky.items().remove(p, id, n);
        if (got < n) got += Sky.service(Features.Sacks.class).remove(p, id, n - got);
        return got;
    }

    // ================================================================================ shops

    record Offer(String item, int amount, double price, boolean essence) {}

    static final List<Offer> AXE_TRADER = List.of(
            new Offer("ROOKIE_AXE", 1, 12, false), new Offer("PROMISING_AXE", 1, 35, false), new Offer("SWEET_AXE", 1, 100, false),
            new Offer("EFFICIENT_AXE", 1, 100, false), new Offer("SCULPTORS_AXE", 1, 500, false));   // Sculptor's price TUNE

    static final List<Offer> MARSH_AXES = List.of(
            new Offer("SERIOUSLY_DAMAGED_AXE", 1, 5_000, false), new Offer("DECENT_AXE", 1, 1_000_000, false),
            new Offer("CANOPY_HELMET", 1, 116_400, false), new Offer("CANOPY_CHESTPLATE", 1, 135_800, false),
            new Offer("CANOPY_LEGGINGS", 1, 126_100, false), new Offer("CANOPY_BOOTS", 1, 106_700, false));

    /** Forest Essence prices: TUNE (the reference spends essence on perks; we sell the gift materials). */
    static final List<Offer> SAPWITCH = List.of(
            new Offer("TENDER_WOOD", 1, 25, true), new Offer("VINESAP", 1, 25, true), new Offer("STRETCHING_STICKS", 1, 15, true),
            new Offer("FLEXBONE", 1, 30, true), new Offer("DEEP_ROOT", 1, 40, true),
            // wave 5: boosters (no reference source for most of them: TUNE prices)
            new Offer("SWEEP_BOOSTER", 1, 150, true), new Offer("FORAGING_FORTUNE_BOOSTER", 1, 150, true),
            new Offer("FORAGING_WISDOM_BOOSTER", 1, 150, true), new Offer("HUNTING_WISDOM_BOOSTER_COMMON", 1, 150, true),
            new Offer("SWEEP_BOOSTER_UNCOMMON", 1, 600, true), new Offer("FORAGING_FORTUNE_BOOSTER_UNCOMMON", 1, 600, true),
            new Offer("FORAGING_WISDOM_BOOSTER_UNCOMMON", 1, 600, true), new Offer("HUNTING_WISDOM_BOOSTER_UNCOMMON", 1, 600, true),
            new Offer("FIGHTING_BOOSTER_UNCOMMON", 1, 600, true));

    // ================================================================================ the jade dragon

    /** The egg and what it costs (NEU JADE_DRAGON_NPC: 500M coins, Shiniest, 64 Figstone / Mangcore / Helixis). */
    static final String JADE_EGG = "JADE_DRAGON;4";
    static final String[][] JADE_MATS = {{"SHINIEST", "1"}, {"FIGSTONE", "64"}, {"MANGCORE", "64"}, {"HELIXIS", "64"}};

    static Menu jadeShop(Player p) {
        Menu m = new Menu("Verdigris's Hoard", 4).chrome();
        double coins = FDials.jadeDragonCoins.get();
        if (!Sky.items().exists(JADE_EGG)) {
            m.set(13, Icons.of(Material.BARRIER, "&cNo egg today", "&7The pets module has no Jade Dragon pet."));
            return m;
        }
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Cost");
        lore.add((Sky.economy().purse(p) >= coins ? "&a✔ " : "&c✖ ") + "&6" + Text.commasInt(coins) + " Coins");
        for (String[] mat : JADE_MATS) {
            int need = Integer.parseInt(mat[1]);
            ItemDef def = Sky.items().def(mat[0]);
            lore.add((count(p, mat[0]) >= need ? "&a✔ " : "&c✖ ") + "&f" + need + "x " + (def == null ? mat[0] : Text.strip(def.name)));
        }
        lore.add("");
        lore.add("&8The egg hatches as you level it.");
        lore.add("&eClick to buy!");
        m.set(13, Icons.withLore(Sky.items().create(JADE_EGG, 1), lore), c -> buyEgg(c.player()));
        return m;
    }

    private static final Map<java.util.UUID, Long> lastEgg = new HashMap<>();

    /** All-or-nothing: every material and the coins, or nothing at all. */
    static void buyEgg(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastEgg.put(p.getUniqueId(), now);
        if (last != null && now - last < 500) return;
        double coins = FDials.jadeDragonCoins.get();
        if (Sky.economy().purse(p) < coins) { Msg.error(p, "You don't have enough coins!"); return; }
        for (String[] mat : JADE_MATS)
            if (!Sky.items().exists(mat[0]) || count(p, mat[0]) < Integer.parseInt(mat[1])) { Msg.error(p, "You don't have everything the dragon asks for."); return; }
        if (p.getInventory().firstEmpty() < 0) { Msg.error(p, "Your inventory is full!"); return; }
        int[] took = new int[JADE_MATS.length];
        boolean ok = true;
        for (int i = 0; i < JADE_MATS.length; i++) {
            took[i] = take(p, JADE_MATS[i][0], Integer.parseInt(JADE_MATS[i][1]));
            if (took[i] < Integer.parseInt(JADE_MATS[i][1])) ok = false;
        }
        if (ok && !Sky.economy().withdraw(p, coins, "foraging: jade dragon egg")) ok = false;
        if (!ok) {
            for (int i = 0; i < JADE_MATS.length; i++) if (took[i] > 0) Chopper.giveChunked(p, JADE_MATS[i][0], took[i], LootSource.NPC_SHOP);
            Msg.error(p, "Something moved while you were buying; nothing was taken.");
            return;
        }
        Sky.loot().give(p, JADE_EGG, 1, LootSource.NPC_SHOP);
        Msg.box(p, "&2&lJADE DRAGON", List.of("&7Verdigris gives you its egg.", "&7Right-click it to add it to your pets."));
        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_AMBIENT, 0.6f, 1.6f);
        jadeShop(p).open(p);
    }

    static void axeTrader(Player p) { shop(p, "Axe-trader Hewitt", AXE_TRADER, true).open(p); }

    static void marshShop(Player p) { shop(p, "Axewright Thane", MARSH_AXES, true).open(p); }

    static void sapwitch(Player p) { shop(p, "Sapwitch Elowen", SAPWITCH, false).open(p); }

    static Menu shop(Player p, String title, List<Offer> offers, boolean sellLogs) {
        Menu m = new Menu(title, 4).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        for (Offer o : offers) {
            if (i >= slots.length) break;
            ItemDef def = Sky.items().def(o.item());
            if (def == null) continue;
            ItemStack icon = Sky.items().create(o.item(), o.amount());
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("&7Cost");
            extra.add(o.essence() ? "&d" + Text.commasInt(o.price()) + " Forest Essence" : "&6" + Text.commasInt(o.price()) + " Coins");
            extra.add("");
            extra.add("&eClick to buy!");
            m.set(slots[i++], Icons.withLore(icon, extra), c -> buy(c.player(), o, title));
        }
        if (sellLogs) m.set(31, Icons.of(Material.HOPPER, "&aSell Logs", "&7Sell every plain log in your inventory", "&7at its merchant price.", "", "&eClick to sell!"),
                c -> sellLogs(c.player()));
        if (!sellLogs) m.set(31, Icons.of(Material.GHAST_TEAR, "&dForest Essence", "&7You have &d" + Text.commasInt(ForagingModule.data(p).forestEssence),
                "&7Earn it from &2Tree Gifts &7in the Moonmarsh."));
        return m;
    }

    private static void buy(Player p, Offer o, String title) {
        if (!Sky.items().exists(o.item())) return;
        if (o.essence()) {
            ForagingData d = ForagingModule.data(p);
            if (d.forestEssence < o.price()) { Msg.error(p, "You don't have enough Forest Essence!"); return; }
            d.forestEssence -= (long) o.price();
        } else if (!Sky.economy().withdraw(p, o.price(), "foraging shop " + title + ": buy " + o.item() + " x" + o.amount())) {
            Msg.error(p, "You don't have enough coins!");
            return;
        }
        Sky.loot().give(p, o.item(), o.amount(), LootSource.NPC_SHOP);
        Msg.success(p, "Bought " + Text.strip(Sky.items().def(o.item()).name) + (o.amount() > 1 ? " x" + o.amount() : "") + "!");
    }

    static void sellLogs(Player p) {
        double total = 0;
        int n = 0;
        for (Wood w : Wood.values()) {
            ItemDef def = Sky.items().def(w.itemId);
            if (def == null || def.npcSell <= 0) continue;
            int c = Sky.items().count(p, w.itemId);
            if (c <= 0) continue;
            int got = Sky.items().remove(p, w.itemId, c);
            total += got * def.npcSell;
            n += got;
        }
        if (n == 0) { Msg.error(p, "You have no logs to sell."); return; }
        Sky.economy().deposit(p, total, "foraging shop: sold " + n + " logs");
        Msg.success(p, "Sold " + Text.commasInt(n) + " logs for &6" + Text.commas(total) + " coins&a!");
    }

    // ================================================================================ Ember Trials

    static void emberTrials(Player p) { trialsMenu(p).open(p); }

    static Menu trialsMenu(Player p) {
        ForagingData d = ForagingModule.data(p);
        Menu m = new Menu("Ember Trials", 5).chrome();
        int next = d.trialTier + 1;
        m.set(4, Icons.of(Material.CAMPFIRE, "&6The Ember Trials", "&7Feed the fire with logs from every", "&7forest and earn a &6Campfire Badge&7.",
                "&7Each badge is worn in place of the last.", "", "&7Badge tier: &a" + d.trialTier + "&8/29"));
        for (int i = 0; i < 7; i++) {
            int tier = d.trialTier - 2 + i;
            if (tier < 1 || tier > 29) continue;
            String badge = "CAMPFIRE_TALISMAN_" + tier;
            boolean got = tier <= d.trialTier;
            String wood = TRIAL_WOODS[(tier - 1) % TRIAL_WOODS.length];
            int logs = (int) Math.round(SweepMath.trialLogs(tier) * FDials.trialLogScale.get());
            ItemDef bd = Sky.items().def(badge), wd = Sky.items().def(wood);
            List<String> lore = new ArrayList<>();
            lore.add("&7Feed &a" + Text.commasInt(logs) + " " + (wd == null ? wood : Text.strip(wd.name)) + (tier > 1 ? " &7and your" : ""));
            if (tier > 1) lore.add("&7tier " + (tier - 1) + " badge to the fire.");
            lore.add("");
            lore.add("&7Reward: " + (bd == null ? "&8(badge not available)" : bd.rarity.code() + Text.strip(bd.name)));
            lore.add("");
            if (got) lore.add("&aEarned!");
            else if (tier == next) lore.add(count(p, wood) >= logs ? "&eClick to feed the fire!" : "&cYou have " + Text.commasInt(count(p, wood)) + "/" + Text.commasInt(logs));
            else lore.add("&cEarn the previous badge first.");
            Material mat = got ? Material.LIME_STAINED_GLASS_PANE : tier == next ? Material.ORANGE_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            int t = tier;
            m.set(19 + i, Icons.of(mat, (got ? "&a" : tier == next ? "&6" : "&c") + "Trial " + Text.roman(tier), lore), c -> { if (t == next) feed(c.player(), t); });
        }
        return m;
    }

    private static void feed(Player p, int tier) {
        ForagingData d = ForagingModule.data(p);
        if (d.trialTier + 1 != tier || tier > 29) return;
        String badge = "CAMPFIRE_TALISMAN_" + tier;
        if (!Sky.items().exists(badge)) { Msg.error(p, "The fire cannot make that badge yet."); return; }
        String wood = TRIAL_WOODS[(tier - 1) % TRIAL_WOODS.length];
        int logs = (int) Math.round(SweepMath.trialLogs(tier) * FDials.trialLogScale.get());
        if (count(p, wood) < logs) { Msg.error(p, "You need more logs!"); return; }
        String prev = "CAMPFIRE_TALISMAN_" + (tier - 1);
        if (tier > 1 && Sky.items().count(p, prev) < 1) { Msg.error(p, "Bring your tier " + (tier - 1) + " badge with you!"); return; }
        if (tier > 1 && Sky.items().remove(p, prev, 1) < 1) return;
        if (take(p, wood, logs) < logs) { Msg.error(p, "Could not take the logs."); return; }
        d.trialTier = tier;
        Sky.loot().give(p, badge, 1, LootSource.REWARD);
        Sky.profiles().saveNow(p);
        Msg.box(p, "&6&lEMBER TRIAL " + Text.roman(tier), List.of("&7The fire roars! You earned", Sky.items().def(badge).name));
        p.playSound(p.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1f, 1f);
        trialsMenu(p).open(p);
    }

    // ================================================================================ Hub woodcutter

    static void lumberJack(Player p) {
        ForagingData d = ForagingModule.data(p);
        if (d.lumberJack) {
            talk(p, "lumber_jack", List.of("That axe treating you well? The Park is through the forest gate.",
                    "Use &a/warp park &fonce you know your way around a tree."), null);
            return;
        }
        if (Sky.items().count(p, "LOG") >= 20) {
            talk(p, "lumber_jack", List.of("Twenty oak logs! You've a knack for this.", "Here, take my spare axe. It learns as it works."), () -> {
                ForagingData dd = ForagingModule.data(p);
                if (dd.lumberJack || Sky.items().remove(p, "LOG", 20) < 20) return;
                dd.lumberJack = true;
                Sky.loot().give(p, "PROMISING_AXE", 1, LootSource.REWARD);
                Sky.profiles().saveNow(p);
                Msg.box(p, "&a&lQUEST COMPLETE &7The Woodcutter", List.of("&a+1 Promising Axe", "&7The Park awaits: &e/warp park"));
            });
            return;
        }
        talk(p, "lumber_jack", List.of("These woods need thinning. Chop me &a20 Oak Logs&f.",
                "Do that and I'll hand you an axe worth keeping."), null);
    }

    // ================================================================================ Moonmarsh

    static void hotfKeeper(Player p) {
        ForagingData d = ForagingModule.data(p);
        if (!d.hotfUnlocked) {
            talk(p, "hotf_keeper", List.of("Every tree here remembers who cut it.", "Listen closely and the forest will teach you.",
                    "I have opened its &2Heart &fto you. Use &a/hotf&f."), () -> {
                Hotf.unlock(p);
                Hotf.menu(p).open(p);
            });
            return;
        }
        Hotf.menu(p).open(p);
    }
}
