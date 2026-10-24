package com.skyclone.mod.foraging;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Starfall Contests (ref. Starlyn Contests, research 07 §4.6): one runs every SkyBlock day (20 real minutes,
 * aligned to the wall clock so every player shares it). Everything you do on the Moonmarsh scores points
 * for the MARSH contest (the Herbalist's, ref. Agatha) and everything in the Torrent Canyon for the CANYON
 * contest (the Curator's, ref. Miria); at the end your points land you in a bracket that pays.
 *
 * <p>Points (research): Tree Gift 10; per 100 logs Fig 3 / Mangrove 6 / Spiral 9; Velvet Lilac 9, Sea Lumies 2;
 * marsh sea creature 10, marsh treasure 25, canyon Outstanding treasure 750; hunting shards 3-45 (marsh) /
 * 9-200 (canyon) by rarity (the in-between rarities are ours, TUNE). Ruby Veilshroom (no research number) 3.
 * Point multipliers: Starfall Supreme (HotF, +0.2 %/level), the Moonmarsh / Torrent Belts (+5 / +10 %), the
 * Sloth pet on chopping points, the Solar attribute.
 *
 * <p>Rewards per bracket interpolate the research ranges across the eight brackets: Coupons 10-45 (the
 * Herbalist's / Curator's Coupon items, spent in their shops below), Whispers 2,500-20,000, Forest Essence
 * 10-90, Foraging XP 10k-75k, Bits 15-120, HotF XP 100-750 marsh / 150-900 canyon; Epic+ a 10-20 % Prize; the
 * canyon also pays Safari Tickets (Economy -> Premium -> First-Class; Two-for-One may double it). The first
 * three contests you place in each day add +1,200 HotF XP (+1,200 Forest Whispers once HotF VIII).
 */
final class Contest {

    private Contest() {}

    static final String[] BRACKETS = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic", "Divine", "Special"};
    static final String[] COLORS = {"&f", "&a", "&9", "&5", "&6", "&d", "&b", "&c"};
    static final int[] MARSH_MIN = {25, 250, 1_000, 2_000, 3_000, 5_000, 8_000, 12_000};
    static final int[] CANYON_MIN = {50, 250, 1_000, 2_500, 5_000, 10_000, 15_000, 20_000};
    /** Points per hunting shard by rarity C/U/R/E/L (research: 3-45 marsh, 9-200 canyon; the middle is TUNE). */
    static final int[] MARSH_SHARD = {3, 6, 12, 24, 45};
    static final int[] CANYON_SHARD = {9, 25, 50, 100, 200};
    static final String MARSH_COUPON = "AGATHA_COUPON", CANYON_COUPON = "MIRIA_COUPON";
    static final String MARSH_PRIZE = "STARLYN_PRIZE", CANYON_PRIZE = "MIRIA_PRIZE";
    /** Canyon Safari Ticket by bracket (Common none). TUNE: research names the three tiers, not where each starts. */
    static final String[] TICKETS = {null, "SAFARI_TICKET_BASIC", "SAFARI_TICKET_BASIC", "SAFARI_TICKET_ECONOMY", "SAFARI_TICKET_ECONOMY",
            "SAFARI_TICKET_PREMIUM", "SAFARI_TICKET_PREMIUM", "SAFARI_TICKET_FIRST_CLASS"};

    static long periodMs() { return Math.max(60, FDials.contestMinutes.get() * 60L) * 1000L; }

    static long currentId() { return System.currentTimeMillis() / periodMs(); }

    static long msLeft() { return periodMs() - System.currentTimeMillis() % periodMs(); }

    /** Bracket index for a score, -1 below Common. */
    static int bracket(double points, boolean canyon) {
        int[] t = canyon ? CANYON_MIN : MARSH_MIN;
        int b = -1;
        for (int i = 0; i < t.length; i++) if (points >= t[i]) b = i;
        return b;
    }

    /** lerp over the eight brackets */
    static double scale(int bracket, double lo, double hi) { return lo + (hi - lo) * Math.max(0, bracket) / (BRACKETS.length - 1.0); }

    /** Points per log of a wood (per 100 logs: Fig 3, Mangrove 6, Spiral 9). */
    static double logPoints(Wood w) {
        return switch (w) { case FIG -> 0.03; case MANGROVE -> 0.06; case HELIX -> 0.09; default -> 0; };
    }

    static double resourcePoints(IslandResource r) {
        return switch (r) { case LUSHLILAC -> 9; case SEA_LUMIES -> 2; case RUBY_VEILSHROOM -> 3; };
    }

    /** Points for a fishing catch (research: marsh sea creature 10, marsh treasure 25, canyon Outstanding treasure 750). */
    static double fishPoints(boolean seaCreature, boolean treasure, String quality, boolean canyon) {
        if (canyon) return treasure && "outstanding".equalsIgnoreCase(quality) ? 750 : 0;
        if (seaCreature) return 10;
        return treasure ? 25 : 0;
    }

    /** Points for one hunting shard of a rarity (COMMON..LEGENDARY; higher rarities count as LEGENDARY). */
    static double shardPoints(int rarityIndex, boolean canyon) {
        int[] t = canyon ? CANYON_SHARD : MARSH_SHARD;
        return t[Math.max(0, Math.min(t.length - 1, rarityIndex))];
    }

    /** Coupons for a bracket (10-45). */
    static int coupons(int bracket) { return bracket < 0 ? 0 : (int) Math.round(scale(bracket, 10, 45)); }

    /** Bits for a bracket (15-120), before the fame multiplier. */
    static int bits(int bracket) { return bracket < 0 ? 0 : (int) Math.round(scale(bracket, 15, 120)); }

    /** Prize chance (Epic and up: 10 % rising to 20 % at Special). */
    static double prizeChance(int bracket) { return bracket < 3 ? 0 : 0.10 + 0.10 * (bracket - 3) / 4.0; }

    // ================================================================================ scoring

    static void addLogs(Player p, Wood w, int logs) { add(p, w == Wood.HELIX, logPoints(w) * logs, true); }

    static void addGift(Player p, Wood w) { add(p, w == Wood.HELIX, 10, true); }

    static void addResource(Player p, IslandResource r, int amount) {
        add(p, r.canyon || Chopper.TORRHUS.equals(Chopper.island(p.getWorld())), resourcePoints(r) * amount, false);
    }

    static void add(Player p, boolean canyon, double pts) { add(p, canyon, pts, false); }

    static void add(Player p, boolean canyon, double pts, boolean chop) {
        if (!FDials.contests.get() || pts <= 0 || !Sky.profiles().isReady(p)) return;
        ForagingData d = ForagingModule.data(p);
        settle(p, d);
        long id = currentId();
        if (d.contestId != id) { d.contestId = id; d.contestMarsh = 0; d.contestCanyon = 0; }
        double before = canyon ? d.contestCanyon : d.contestMarsh;
        double after = before + pts * FDials.contestPointScale.get() * (1 + Bonuses.contestPercent(p, chop) / 100.0);
        if (canyon) d.contestCanyon = after; else d.contestMarsh = after;
        int b0 = bracket(before, canyon), b1 = bracket(after, canyon);
        if (b1 > b0) {
            Msg.send(p, "&e" + name(canyon) + ": &7you reached " + COLORS[b1] + BRACKETS[b1] + " &7(" + Text.commasInt(after) + " points)");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.2f + 0.1f * b1);
        }
    }

    static String name(boolean canyon) { return canyon ? "Canyon Starfall Contest" : "Marsh Starfall Contest"; }

    /** Is the player scoring for the canyon contest where they stand? (null = not on a contest island) */
    static Boolean canyonAt(Player p) {
        String island = Chopper.island(p.getWorld());
        if (Chopper.TORRHUS.equals(island)) return true;
        if (!Chopper.MARSH.equals(island)) return null;
        Layout.Zone z = Sky.worlds().zoneAt(p.getLocation());
        return z != null && z.id() != null && z.id().contains("canyon");
    }

    /** Fishing and hunting points (research 07 §4.6). */
    static final class Points implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onFish(SkyEvents.FishCaught e) {
            if (e.lava) return;
            Boolean canyon = canyonAt(e.player);
            if (canyon == null) return;
            add(e.player, canyon, fishPoints(e.seaCreature, e.treasure, e.quality, canyon));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onShard(SkyEvents.ItemGain e) {
            if (e.itemId == null || !e.itemId.startsWith("SHARD_") || e.amount <= 0) return;
            if (e.source == LootSource.ADMIN || e.source == LootSource.NPC_SHOP || e.source == LootSource.TRADE
                    || e.source == LootSource.AUCTION || e.source == LootSource.BAZAAR || e.source == LootSource.STORAGE
                    || e.source == LootSource.SACK || e.source == LootSource.CRAFT) return;
            Boolean canyon = canyonAt(e.player);
            if (canyon == null) return;
            ItemDef d = Sky.items().def(e.itemId);
            int r = d == null ? 0 : Math.min(4, d.rarity.ordinal() - Rarity.COMMON.ordinal());
            add(e.player, canyon, shardPoints(r, canyon) * e.amount);
        }
    }

    // ================================================================================ settling

    /** Pay a finished contest (called on a timer for online players, and before any new score). */
    static void settle(Player p, ForagingData d) {
        if (d.contestId == 0 || d.contestId >= currentId()) return;
        long id = d.contestId;
        double marsh = d.contestMarsh, canyon = d.contestCanyon;
        d.contestId = 0; d.contestMarsh = 0; d.contestCanyon = 0;
        boolean placed = false;
        if (marsh > 0) placed |= pay(p, d, false, marsh);
        if (canyon > 0) placed |= pay(p, d, true, canyon);
        if (placed) {
            Hotf.rollDay(d);
            if (d.contestsToday < 3) {
                d.contestsToday++;
                if (d.hotfUnlocked && Hotf.tier(d) >= 8) Hotf.addWhispers(p, 1200, 0, "daily contest bonus " + d.contestsToday + "/3");
                else if (d.hotfUnlocked) {
                    Hotf.addXp(p, 1200);
                    Msg.send(p, "&2+1,200 Heart of the Forest XP &7(daily contest bonus " + d.contestsToday + "/3)");
                }
            }
        }
        d.contestsEntered++;
        Sky.profiles().saveNow(p);
        Sky.log().fine("[foraging] settled contest " + id + " for " + p.getName());
    }

    private static boolean pay(Player p, ForagingData d, boolean canyon, double points) {
        int b = bracket(points, canyon);
        List<String> body = new ArrayList<>();
        body.add("&7Your points: &e" + Text.commasInt(points));
        if (b < 0) {
            body.add("&7Not enough for a bracket &8(" + (canyon ? CANYON_MIN[0] : MARSH_MIN[0]) + " needed)");
            Msg.box(p, "&e&l" + name(canyon).toUpperCase() + " OVER", body);
            return false;
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double k = FDials.contestRewardScale.get();
        long whispers = Math.round(scale(b, 2_500, 20_000) * k);
        long essence = Math.round(scale(b, 10, 90) * k * Bonuses.essenceFactor(p));
        double fxp = scale(b, 10_000, 75_000) * k;
        double hxp = (canyon ? scale(b, 150, 900) : scale(b, 100, 750)) * k;
        body.add("&7Bracket: " + COLORS[b] + "&l" + BRACKETS[b]);
        body.add("");
        Sky.service(SkillService.class).addXp(p, SkillType.FORAGING, fxp, "foraging:contest");
        body.add("&3+" + Text.commasInt(fxp) + " Foraging Experience");
        d.forestEssence += essence;
        body.add("&d+" + Text.commasInt(essence) + " Forest Essence");
        if (d.hotfUnlocked) {
            Hotf.addWhispers(p, canyon ? 0 : whispers, canyon ? whispers : 0, null);
            body.add((canyon ? "&e+" + Text.commasInt(whispers) + " Desert" : "&b+" + Text.commasInt(whispers) + " Forest") + " Whispers");
            Hotf.addXp(p, hxp);
            body.add("&2+" + Text.commasInt(hxp) + " Heart of the Forest XP");
        } else body.add("&8(Whispers and HotF XP once Keeper Ilse opens the Heart of the Forest)");
        // wave 5: coupons, bits, prizes, safari tickets
        int coupons = (int) Math.round(coupons(b) * k);
        String couponId = canyon ? CANYON_COUPON : MARSH_COUPON;
        if (coupons > 0 && Sky.items().exists(couponId)) {
            Chopper.giveChunked(p, couponId, coupons, LootSource.REWARD);
            body.add("&a+" + coupons + " " + Text.strip(Sky.items().def(couponId).name));
        }
        if (FDials.contestBits.get()) {
            double mult = 1;
            try { mult = Sky.service(Features.Bank.class).bitsMultiplier(p); } catch (RuntimeException ignored) { }
            long bits = Math.round(bits(b) * k * mult);
            if (bits > 0) { Sky.economy().addBits(p, bits, "foraging:contest"); body.add("&b+" + Text.commasInt(bits) + " Bits"); }
        }
        String prize = canyon ? CANYON_PRIZE : MARSH_PRIZE;
        if (rng.nextDouble() < prizeChance(b) && Sky.items().exists(prize)) {
            Sky.loot().give(p, prize, 1, LootSource.REWARD);
            body.add("&6&lPRIZE! &f" + Sky.items().def(prize).name);
        }
        if (canyon) {
            String ticket = TICKETS[b];
            if (ticket != null && Sky.items().exists(ticket)) {
                int n = 1;
                double two = 0.25 * Hotf.level(d, "two_for_one");
                if (rng.nextDouble() * 100 < two) n++;
                Sky.loot().give(p, ticket, n, LootSource.REWARD);
                body.add("&a+" + n + " " + Text.strip(Sky.items().def(ticket).name) + (n > 1 ? " &8(Two-for-One!)" : ""));
            }
            if (b >= 4 && rng.nextDouble() < FDials.behemothChance.get() && Sky.items().exists("BEHEMOTH_POT_OF_HONEYCOMB")) {
                Sky.loot().give(p, "BEHEMOTH_POT_OF_HONEYCOMB", 1, LootSource.REWARD);
                body.add("&6&lPRIZE! &f" + Sky.items().def("BEHEMOTH_POT_OF_HONEYCOMB").name);
            }
        }
        if (b > d.bestBracket) d.bestBracket = b;
        Msg.box(p, "&e&l" + name(canyon).toUpperCase() + " OVER", body);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.3f);
        return true;
    }

    static void start() {
        Bukkit.getPluginManager().registerEvents(new Points(), Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!Sky.profiles().isReady(p)) continue;
                ForagingData d = ForagingModule.data(p);
                if (d.contestId != 0 && d.contestId < currentId()) settle(p, d);
            }
        }, 100L, 100L);
    }

    /** Sidebar lines for a player on a contest island. */
    static List<String> lines(Player p, ForagingData d, boolean canyon) {
        if (!FDials.contests.get()) return List.of();
        long left = msLeft() / 1000;
        double pts = d.contestId == currentId() ? (canyon ? d.contestCanyon : d.contestMarsh) : 0;
        int b = bracket(pts, canyon);
        List<String> out = new ArrayList<>();
        out.add("&e" + (canyon ? "Canyon" : "Marsh") + " Contest &7" + (left / 60) + ":" + String.format("%02d", left % 60));
        out.add(" &7Points: &e" + Text.commasInt(pts) + (b >= 0 ? " " + COLORS[b] + BRACKETS[b] : ""));
        return out;
    }

    // ================================================================================ the coupon shops

    /** One shop line: pay {@code coupons} (or {@code prizes}) and maybe hand in {@code tradeIn}. */
    record Offer(String item, int coupons, int prizes, String tradeIn) {}

    /** The Herbalist's shop (ref. Agatha, NEU prices, in NEU's order). An id list "A|B" sells the first one registered. */
    static final List<Offer> HERBALIST = List.of(
            new Offer("FROG;0", 20, 0, null),                // Common Frog pet (pets module's item)
            new Offer("SHARD_CROW", 15, 0, null),            // Fig Sharpening attribute shard
            new Offer("SHARD_HERON", 15, 0, null),           // Mangrove Sharpening
            new Offer("SMALL_FROG_TREAT", 30, 0, null), new Offer("MEDIUM_FROG_TREAT", 40, 0, null),
            new Offer("LARGE_FROG_TREAT", 50, 0, null), new Offer("GIANT_FROG_TREAT", 60, 0, null),
            new Offer("MOONGLADE_JEWEL", 50, 0, null),
            new Offer("FIGHTING_BOOSTER", 100, 0, null),
            new Offer("ENCHANTMENT_FOREST_PLEDGE_3|FOREST_PLEDGE;3", 30, 0, null),
            new Offer("MOONGLADE_TALISMAN", 30, 0, null), new Offer("MOONGLADE_RING", 30, 0, "MOONGLADE_TALISMAN"),
            new Offer("MOONGLADE_ARTIFACT", 30, 0, "MOONGLADE_RING"),
            new Offer("SHINIEST", 0, 20, null));

    /** The Curator's shop (ref. Miria, NEU prices). */
    static final List<Offer> CURATOR = List.of(
            new Offer("SHARD_VULTURE", 25, 0, null),         // Helix Sharpening
            new Offer("SHARD_WOODPECKER", 15, 0, null),      // Forest Felling
            new Offer("SLOTH;0", 20, 0, null),               // Common Sloth pet
            new Offer("SMALL_SLOTH_CLAW", 30, 0, null), new Offer("MEDIUM_SLOTH_CLAW", 40, 0, null),
            new Offer("LARGE_SLOTH_CLAW", 50, 0, null), new Offer("GIANT_SLOTH_CLAW", 60, 0, null),
            new Offer("ENCHANTMENT_PETALFALL_1|PETALFALL;1", 20, 0, null),
            new Offer("TORRHUS_TALISMAN", 30, 0, null), new Offer("TORRHUS_RING", 30, 0, "TORRHUS_TALISMAN"),
            new Offer("TORRHUS_ARTIFACT", 30, 0, "TORRHUS_RING"),
            new Offer("EXTREMELY_MILD_ADHESIVE", 100, 0, null), new Offer("RUBBER_SNORKEL", 125, 0, null),
            new Offer("WINDING_IVY", 250, 0, null),
            new Offer("VIAL_OF_SPRING_WATER", 0, 10, null));

    /** {@code /starfall}: the contest, both shops, how points work. */
    static Menu menu(Player p) {
        ForagingData d = ForagingModule.data(p);
        Menu m = new Menu("Starfall Contests", 4).chrome();
        m.set(4, ForagingMenus.contestIcon(d));
        m.set(11, Icons.of(Material.LILY_PAD, "&aThe Herbalist's Shop", "&7Spend &aHerbalist's Coupons &7from the", "&2Marsh &7contest.", "",
                "&7You have &a" + ForagingNpcs.count(p, MARSH_COUPON) + " &7coupons, &6" + ForagingNpcs.count(p, MARSH_PRIZE) + " &7prizes.", "",
                "&eClick to browse!"), c -> shop(c.player(), false).open(c.player()));
        m.set(15, Icons.of(Material.SANDSTONE, "&6The Curator's Shop", "&7Spend &6Curator's Coupons &7from the", "&6Canyon &7contest.", "",
                "&7You have &a" + ForagingNpcs.count(p, CANYON_COUPON) + " &7coupons, &6" + ForagingNpcs.count(p, CANYON_PRIZE) + " &7prizes.", "",
                "&eClick to browse!"), c -> shop(c.player(), true).open(c.player()));
        m.set(13, Icons.of(Material.BOOK, "&aHow points work",
                "&2Marsh &7(the Moonmarsh):", "&7 Tree Gift &e10&7, per 100 logs Fig &e3&7, Mangrove &e6",
                "&7 Velvet Lilac &e9&7, Sea Lumies &e2&7, Ruby Veilshroom &e3", "&7 sea creature &e10&7, treasure &e25", "&7 hunting shards &e3-45",
                "", "&6Canyon &7(the Torrent Canyon):", "&7 Tree Gift &e10&7, per 100 Spiral logs &e9",
                "&7 hunting shards &e9-200&7, Outstanding treasure &e750", "",
                "&7Brackets also pay &aCoupons&7, &bBits&7, a chance", "&7of a &6Prize&7, and in the canyon &aSafari Tickets&7.",
                "", "&7Your point bonus: &a+" + Text.commas(Bonuses.contestPercent(p, false)) + "%"));
        m.set(31, Icons.back("Foraging"), c -> ForagingMenus.main(c.player()).open(c.player()));
        return m;
    }

    static Menu shop(Player p, boolean canyon) {
        String title = canyon ? "The Curator's Shop" : "The Herbalist's Shop";
        Menu m = new Menu(title, 5).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        String coupon = canyon ? CANYON_COUPON : MARSH_COUPON, prize = canyon ? CANYON_PRIZE : MARSH_PRIZE;
        int i = 0;
        for (Offer o : canyon ? CURATOR : HERBALIST) {
            if (i >= slots.length) break;
            String sold = resolve(o.item());
            if (sold == null) continue;
            ItemStack icon = Sky.items().create(sold, 1);
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("&7Cost");
            if (o.coupons() > 0) extra.add("&a" + o.coupons() + " " + Text.strip(name(coupon)));
            if (o.prizes() > 0) extra.add("&6" + o.prizes() + " " + Text.strip(name(prize)));
            if (o.tradeIn() != null) extra.add("&f1 " + Text.strip(name(o.tradeIn())));
            extra.add("");
            extra.add("&eClick to buy!");
            m.set(slots[i++], Icons.withLore(icon, extra), c -> buy(c.player(), o, canyon));
        }
        m.set(40, Icons.back("Starfall Contests"), c -> menu(c.player()).open(c.player()));
        return m;
    }

    /** The first registered id of an "A|B" offer, or null when none is. */
    static String resolve(String ids) {
        for (String id : ids.split("[|]")) if (Sky.items().exists(id)) return id;
        return null;
    }

    private static String name(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? id : d.name;
    }

    private static final java.util.Map<java.util.UUID, Long> lastBuy = new java.util.HashMap<>();

    static void buy(Player p, Offer o, boolean canyon) {
        long now = System.currentTimeMillis();
        Long last = lastBuy.put(p.getUniqueId(), now);
        if (last != null && now - last < 250) return;   // rate limit: a spammed click buys once
        String coupon = canyon ? CANYON_COUPON : MARSH_COUPON, prize = canyon ? CANYON_PRIZE : MARSH_PRIZE;
        if (o.coupons() > 0 && ForagingNpcs.count(p, coupon) < o.coupons()) { Msg.error(p, "You need " + o.coupons() + " " + Text.strip(name(coupon)) + "!"); return; }
        if (o.prizes() > 0 && ForagingNpcs.count(p, prize) < o.prizes()) { Msg.error(p, "You need " + o.prizes() + " " + Text.strip(name(prize)) + "!"); return; }
        if (o.tradeIn() != null && Sky.items().count(p, o.tradeIn()) < 1) { Msg.error(p, "You need a " + Text.strip(name(o.tradeIn())) + " to trade in!"); return; }
        if (p.getInventory().firstEmpty() < 0) { Msg.error(p, "Your inventory is full!"); return; }
        // take everything first; on any shortfall give back what was taken
        int c = o.coupons() > 0 ? ForagingNpcs.take(p, coupon, o.coupons()) : 0;
        int z = o.prizes() > 0 ? ForagingNpcs.take(p, prize, o.prizes()) : 0;
        int t = o.tradeIn() != null ? Sky.items().remove(p, o.tradeIn(), 1) : 0;
        if (c < o.coupons() || z < o.prizes() || (o.tradeIn() != null && t < 1)) {
            if (c > 0) Chopper.giveChunked(p, coupon, c, LootSource.NPC_SHOP);
            if (z > 0) Chopper.giveChunked(p, prize, z, LootSource.NPC_SHOP);
            if (t > 0) Sky.loot().give(p, o.tradeIn(), t, LootSource.NPC_SHOP);
            Msg.error(p, "Something moved while you were buying; nothing was taken.");
            return;
        }
        String sold = resolve(o.item());
        Sky.loot().give(p, sold == null ? o.item() : sold, 1, LootSource.NPC_SHOP);
        Msg.success(p, "Bought " + Text.strip(name(sold == null ? o.item() : sold)) + "!");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
        shop(p, canyon).open(p);
    }
}
