package com.skyclone.mod.bank;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.util.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Boon Cookie (our name for the genre's booster cookie) and the Boon Buff it grants
 * (research 05 section 6.1/6.2): +25 on every Wisdom stat, +15 Magic Find, the purse is kept on death,
 * flight on the private island and the garden, /bank from anywhere, and BITS: each cookie adds
 * 4,800 x fame multiplier to a pool that pays 250 x multiplier every 30 minutes of play.
 *
 * <p>Other modules ask "is the buff on?" with the requirement key {@code cookie}
 * ({@code Sky.requirements().check(p, "cookie")}) - e.g. for /ah and /bz from anywhere.
 */
final class Cookie implements Listener {

    static final String COOKIE = "BOOSTER_COOKIE";
    static final String FREE_COOKIE = "FREE_COOKIE";
    static final Set<String> COOKIE_IDS = Set.of(COOKIE, FREE_COOKIE);

    private static final Set<UUID> FLY_GRANTED = new HashSet<>();

    private static final Stat[] WISDOM = {
            Stat.COMBAT_WISDOM, Stat.MINING_WISDOM, Stat.FARMING_WISDOM, Stat.FORAGING_WISDOM, Stat.FISHING_WISDOM,
            Stat.ENCHANTING_WISDOM, Stat.ALCHEMY_WISDOM, Stat.CARPENTRY_WISDOM, Stat.RUNECRAFTING_WISDOM,
            Stat.SOCIAL_WISDOM, Stat.TAMING_WISDOM};

    static boolean active(Player p) {
        return Bank.ready(p) && Bank.member(p).cookieUntil > System.currentTimeMillis();
    }

    static long millisLeft(Player p) {
        if (!Bank.ready(p)) return 0;
        return Math.max(0, Bank.member(p).cookieUntil - System.currentTimeMillis());
    }

    // ---- registration --------------------------------------------------------------------------------

    static void register() {
        Sky.abilities().register("bank_eat_cookie", use -> {
            Player p = use.player();
            if (!Bank.ready(p)) return false;
            String id = Sky.items().idOf(use.item());
            confirmEat(p, id, true);
            return false;   // nothing to charge: eating happens on confirm
        });
        Sky.stats().addProvider("bank.cookie", Cookie::stats);
        Sky.requirements().register("cookie", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) { return active(p); }
            @Override public String describe(String[] parts) { return "&dthe Boon Buff"; }
        });
        Sky.sidebar().tabWidget("bank.cookie", 145, p -> {
            if (!Bank.ready(p)) return List.of();
            BankData.Member m = Bank.member(p);
            List<String> out = new ArrayList<>();
            out.add("&d&lBoon Buff");
            out.add(active(p) ? " &a" + BankMath.duration(millisLeft(p)) + " &7left" : " &7Not active");
            if (m.bitsAvailable > 0) out.add(" &7Bits Available: &b" + BankMath.commas(m.bitsAvailable));
            return out;
        });
    }

    private static void stats(Player p, StatMap into) {
        if (!active(p)) return;
        double w = Bank.cookieWisdom.get();
        if (w != 0) for (Stat s : WISDOM) into.add(s, w);
        double mf = Bank.cookieMagicFind.get();
        if (mf != 0) into.add(Stat.MAGIC_FIND, mf);
    }

    // ---- eating ------------------------------------------------------------------------------------------

    /** Ask before eating ({@code fromHand}: the cookie the player is holding; else any in the inventory). */
    static void confirmEat(Player p, String id, boolean fromHand) {
        if (id == null || !COOKIE_IDS.contains(id)) return;
        double hours = Bank.cookieHours.get();
        long left = millisLeft(p);
        List<String> lore = new ArrayList<>();
        lore.add("&7Gain the &dBoon Buff &7for &b" + BankMath.duration((long) (hours * 3_600_000L)) + "&7.");
        if (left > 0) lore.add("&7Your buff has &a" + BankMath.duration(left) + " &7left; this adds to it.");
        long pool = BankMath.cookiePool(Bank.cookieBitsPool.get(), Bank.fameMultiplier(p));
        lore.add("&7Adds &b" + BankMath.commas(pool) + " Bits &7to your Bits Available.");
        Sky.menus().confirm(p, "Eat Boon Cookie?", lore, () -> eat(p, id, fromHand), null);
    }

    static void eat(Player p, String id, boolean fromHand) {
        if (!Bank.ready(p)) return;
        if (!Bank.accountReady(p)) { Msg.error(p, "Your account is still loading, try again in a moment."); return; }
        boolean taken = false;
        if (fromHand) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (id.equals(Sky.items().idOf(hand)) && hand.getAmount() > 0) {
                hand.setAmount(hand.getAmount() - 1);
                p.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
                taken = true;
            }
        }
        if (!taken) taken = Sky.items().remove(p, id, 1) == 1;
        if (!taken) { Msg.error(p, "You don't have a Boon Cookie any more!"); return; }
        grant(p, (long) (Bank.cookieHours.get() * 3_600_000L), true);
        Bank.member(p).cookiesEaten++;
        Msg.box(p, "&d&lBOON BUFF &aActive for &b" + BankMath.duration(millisLeft(p)), List.of(
                "&3+" + BankMath.commas(Bank.cookieWisdom.get()) + " Wisdom &7on every skill",
                "&b+" + BankMath.commas(Bank.cookieMagicFind.get()) + " ✯ Magic Find",
                "&7Your purse is safe when you die",
                "&7Bits Available: &b" + BankMath.commas(Bank.member(p).bitsAvailable),
                "&7Use &a/bank &7from anywhere"));
        Msg.sound(p, Sound.ENTITY_GENERIC_EAT, 1f);
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
        Sky.profiles().saveNow(p);
    }

    /** Add buff time (and, when {@code withBits}, one cookie's bits pool). Also used by admin commands. */
    static void grant(Player p, long millis, boolean withBits) {
        BankData.Member m = Bank.member(p);
        long now = System.currentTimeMillis();
        boolean wasActive = m.cookieUntil > now;
        m.cookieUntil = Math.max(now, m.cookieUntil) + Math.max(0, millis);
        m.cookieExpiredNotified = false;
        if (withBits) m.bitsAvailable += BankMath.cookiePool(Bank.cookieBitsPool.get(), Bank.fameMultiplier(p));
        if (!wasActive) m.lastBitsPayout = now;   // the first payout comes one interval after eating
        Sky.stats().invalidate(p);
        updateFly(p);
    }

    static void clear(Player p) {
        BankData.Member m = Bank.member(p);
        m.cookieUntil = 0;
        m.cookieExpiredNotified = true;
        Sky.stats().invalidate(p);
        updateFly(p);
    }

    // ---- periodic: bits payout, expiry, flight -----------------------------------------------------------

    static void tick() {
        long now = System.currentTimeMillis();
        long interval = Math.max(1, Bank.cookiePayoutMinutes.get()) * 60_000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Bank.accountReady(p)) continue;
            BankData.Member m = Bank.member(p);
            if (m.cookieUntil > now) {
                if (m.bitsAvailable > 0 && now - m.lastBitsPayout >= interval) payBits(p, m, now);
            } else if (m.cookieUntil > 0 && !m.cookieExpiredNotified) {
                m.cookieExpiredNotified = true;
                Msg.send(p, "&d[Boon Buff] &cYour Boon Buff has run out! &7Eat another Boon Cookie to keep earning Bits.");
                Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f);
                Sky.stats().invalidate(p);
            }
            updateFly(p);
        }
    }

    static void payBits(Player p, BankData.Member m, long now) {
        long n = BankMath.bitsPayout(m.bitsAvailable, Bank.cookieBitsPerPayout.get(), Bank.fameMultiplier(p));
        m.lastBitsPayout = now;
        if (n <= 0) return;
        m.bitsAvailable -= n;
        // Bits Talisman (accessories module's item): "whenever you gain bits, 10% chance to gain double"
        boolean doubled = Sky.service(Features.Accessories.class).has(p, "BITS_TALISMAN")
                && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < Bank.bitsTalismanChance.get();
        long gained = doubled ? n * 2 : n;
        Sky.economy().addBits(p, gained, doubled ? "bank: boon buff bits (talisman double)" : "bank: boon buff bits");
        Sky.vitals().flash(p, "&b+" + BankMath.commas(gained) + " Bits" + (doubled ? " &d(x2)" : ""), 60);
        Msg.send(p, "&b+" + BankMath.commas(gained) + " Bits &7from your Boon Buff" + (doubled ? " &d(doubled by your Bits Talisman!)" : ".")
                + " &8(" + BankMath.commas(m.bitsAvailable) + " still available)");
    }

    static void updateFly(Player p) {
        if (!p.isOnline()) return;
        GameMode gm = p.getGameMode();
        boolean special = gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
        String island = Sky.worlds().islandOf(p.getLocation());
        boolean want = Bank.cookieFly.get() && Bank.ready(p) && active(p) && !special
                && ("private_island".equals(island) || "garden".equals(island));
        UUID id = p.getUniqueId();
        if (want) {
            if (!p.getAllowFlight()) { p.setAllowFlight(true); FLY_GRANTED.add(id); }
        } else if (FLY_GRANTED.remove(id) && !special) {
            p.setFlying(false);
            p.setAllowFlight(false);
            p.setFallDistance(0);
        }
    }

    // ---- events --------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(SkyEvents.SkyDeath e) {
        if (!Bank.cookieKeepCoins.get() || e.coinsLost <= 0 || !active(e.player)) return;
        double saved = e.coinsLost;
        e.coinsLost = 0;
        Player p = e.player;
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (p.isOnline()) Msg.send(p, "&dYour Boon Buff kept your &6" + BankMath.commas(saved) + " coins &dsafe!");
        });
    }

    @EventHandler
    public void onIsland(SkyEvents.IslandChange e) { Bukkit.getScheduler().runTask(Sky.plugin(), () -> updateFly(e.player)); }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) { FLY_GRANTED.remove(e.player.getUniqueId()); }

    /** At login: at most ONE bits payout for the time spent offline (research 6.2). */
    static void onReady(Player p) {
        BankData.Member m = Bank.member(p);
        long now = System.currentTimeMillis();
        long interval = Math.max(1, Bank.cookiePayoutMinutes.get()) * 60_000L;
        if (m.cookieUntil > now && m.bitsAvailable > 0 && now - m.lastBitsPayout >= interval) payBits(p, m, now);
        updateFly(p);
    }

    // ---- the Boon Buff menu (/cookie; SkyBlock Menu "Booster Cookie") -------------------------------------

    static Menu menu(Player p) {
        Menu m = new Menu("Boon Buff", 4).chrome();
        BankData.Member d = Bank.member(p);
        boolean on = active(p);
        List<String> lore = new ArrayList<>();
        lore.add(on ? "&7Status: &aActive" : "&7Status: &cNot active");
        if (on) lore.add("&7Time left: &b" + BankMath.duration(millisLeft(p)));
        lore.add("");
        lore.add("&7While active:");
        lore.add("&8- &3+" + BankMath.commas(Bank.cookieWisdom.get()) + " Wisdom &7on every skill");
        lore.add("&8- &b+" + BankMath.commas(Bank.cookieMagicFind.get()) + " Magic Find");
        lore.add("&8- &7Keep your &6coins &7on death");
        lore.add("&8- &7Fly on your island and garden");
        lore.add("&8- &7Earn &bBits &7while you play");
        lore.add("&8- &a/bank &7from anywhere");
        ItemStack status = Icons.of(Material.COOKIE, "&dBoon Buff", lore);
        if (on) Icons.glow(status);
        m.set(13, status);

        long next = Math.max(0, d.lastBitsPayout + Bank.cookiePayoutMinutes.get() * 60_000L - System.currentTimeMillis());
        m.set(11, Icons.of(Material.PRISMARINE_CRYSTALS, "&bBits",
                "&7Bits: &b" + BankMath.commas(Sky.economy().bits(p)),
                "&7Bits Available: &b" + BankMath.commas(d.bitsAvailable),
                on && d.bitsAvailable > 0 ? "&7Next payout in: &e" + BankMath.duration(next) : "&7No payouts while the buff is off.",
                "",
                "&7Each cookie adds &b" + BankMath.commas(Bank.cookieBitsPool.get()) + " &7x your fame",
                "&7multiplier to Bits Available, paid",
                "&7out &b" + BankMath.commas(Bank.cookieBitsPerPayout.get()) + " &7at a time every &e" + Bank.cookiePayoutMinutes.get() + " minutes&7.",
                "",
                "&7Fame multiplier: &a" + BankMath.commas(Bank.fameMultiplier(p)) + "x"));
        m.set(15, Icons.of(Material.EMERALD, "&aCommunity Shop", "&7Spend Bits, buy Boon Cookies and", "&7build account upgrades.", "",
                "&eClick to open!"), c -> CommunityShop.open(c.player(), true));

        String inInv = null;
        for (String id : List.of(FREE_COOKIE, COOKIE)) if (Sky.items().count(p, id) > 0) { inInv = id; break; }
        if (inInv != null) {
            final String id = inInv;
            m.set(22, Icons.glow(Icons.of(Material.COOKIE, "&6Eat a Boon Cookie", "&7You have one in your inventory.", "",
                    "&eClick to eat!")), c -> confirmEat(c.player(), id, false));
        } else {
            m.set(22, Icons.of(Material.GRAY_DYE, "&7No Boon Cookie", "&7Buy one at the &aCommunity Shop&7."));
        }
        if (Sky.has(Features.SkyMenu.class)) {
            m.set(30, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        }
        return m;
    }
}
