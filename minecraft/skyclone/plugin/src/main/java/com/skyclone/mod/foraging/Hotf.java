package com.skyclone.mod.foraging;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Heart of the Forest: the foraging twin of the mining tree (research 07 §4.5). Tiers 1-8 from
 * HotF XP (Tree Gifts and Moonmarsh logs), tokens to open perks, Forest/Desert Whispers to level them.
 */
final class Hotf {

    private Hotf() {}

    static final String[] TIER_REWARD = {
            "&7Opens the tree", "&7Axe abilities stir", "&7Deeper roots", "&aThe Torrent Canyon opens",
            "&6Foraging emblem", "&7Old growth", "&6Golden emblem", "&7The daily bonus pays whispers"};

    // ================================================================================ state

    static int level(ForagingData d, String perk) {
        if (d.disabledPerks.contains(perk)) return 0;
        return d.perks.getOrDefault(perk, 0);
    }

    static int rawLevel(ForagingData d, String perk) { return d.perks.getOrDefault(perk, 0); }

    static int tier(ForagingData d) { return d.hotfUnlocked ? SweepMath.hotfTier(d.hotfXp) : 0; }

    static int tokensTotal(ForagingData d) {
        if (!d.hotfUnlocked) return 0;
        int c = rawLevel(d, "center");
        return SweepMath.tokensAt(tier(d)) + (c >= 1 ? 1 : 0) + (c >= 5 ? 1 : 0);
    }

    static int tokensFree(ForagingData d) { return Math.max(0, tokensTotal(d) - d.tokensSpent); }

    static void rollDay(ForagingData d) {
        long today = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        if (d.day != today) { d.day = today; d.giftsToday = 0; d.woodsToday.clear(); d.contestsToday = 0; }
    }

    // ================================================================================ stats

    static void contribute(Player p, StatMap into) {
        ForagingData d = ForagingModule.data(p);
        if (!d.hotfUnlocked) return;
        into.add(Stat.SWEEP, level(d, "sweep"));
        into.add(Stat.FORAGING_FORTUNE, 3.0 * level(d, "foraging_fortune"));
        into.add(Stat.FORAGING_WISDOM, 0.1 * level(d, "efficient_forager"));
        into.add(Stat.HUNTING_FORTUNE, level(d, "hunters_luck"));                     // wave 5: Hunter's Luck
        if (Bonuses.daily(p, d, "daily_grove", 4) == HiveMath.DAILY_SWEEP) into.add(Stat.SWEEP, 10);   // Wandering Grove
        if (level(d, "center") >= 2) { into.addPercent(Stat.SWEEP, 5); into.addPercent(Stat.FORAGING_FORTUNE, 5); }
        String island = Chopper.island(p.getWorld());
        boolean foraging = Chopper.foragingIsland(island);
        if (foraging) {
            into.add(Stat.STRENGTH, 2.0 * level(d, "strength_boost"));
            into.add(Stat.SPEED, 2.0 * level(d, "speed_boost"));
            into.add(Stat.FISHING_SPEED, 0.5 * level(d, "forest_fisher"));
            into.add(Stat.SEA_CREATURE_CHANCE, 0.1 * level(d, "forest_fisher"));
            int fs = level(d, "forest_strength"), sp = level(d, "forest_speed");
            if (fs > 0) {
                double v = 0.001 * fs * Math.min(1000, Sky.stats().get(p, Stat.STRENGTH));
                into.add(Stat.FORAGING_FORTUNE, v); into.add(Stat.SWEEP, v);
            }
            if (sp > 0) {
                double v = 0.002 * sp * Math.min(500, Sky.stats().get(p, Stat.SPEED));
                into.add(Stat.FORAGING_FORTUNE, v); into.add(Stat.SWEEP, v);
            }
            for (String[] pair : new String[][]{{"half_full", "half_empty"}, {"half_empty", "half_full"}}) {
                int lv = level(d, pair[0]);
                if (lv <= 0) continue;
                int n = 0;
                for (Player o : p.getWorld().getPlayers()) {
                    if (o == p || !Sky.profiles().isReady(o)) continue;
                    if (o.getLocation().distanceSquared(p.getLocation()) > 64 * 64) continue;
                    if (level(ForagingModule.data(o), pair[1]) > 0) n++;
                    if (n >= 5) break;
                }
                into.add(Stat.FORAGING_FORTUNE, 1.0 * lv * n);
                into.add(Stat.SWEEP, 0.4 * lv * n);
            }
        }
        if (Chopper.toughIsland(island)) {
            double m = 0.5 * level(d, "galateas_might");
            if (m > 0) for (Stat s : new Stat[]{Stat.HEALTH, Stat.DEFENSE, Stat.STRENGTH, Stat.CRIT_DAMAGE}) into.addPercent(s, m);
        }
    }

    /**
     * Timber: the chance a swing fells the whole tree. The keystone gives 0.1 Timber per level (research 07 §4.5),
     * Petal Shower on the axe 0.2-1.0; each point is {@code foraging.timber-percent-per-point} % (TUNE).
     */
    static double timberPoints(Player p, ItemStack hand, Wood wood) {
        double t = 0.1 * level(ForagingModule.data(p), "timber") + Bonuses.timberBonus(p, wood);
        if (hand != null && !hand.getType().isAir() && Sky.items().idOf(hand) != null) {
            int pf = Sky.items().data(hand).enchant("petalfall");
            if (pf > 0 && Sky.items().enchant("petalfall") != null) t += Sky.items().enchant("petalfall").value(pf);
        }
        return t;
    }

    static boolean timber(Player p, ItemStack hand, Wood wood) {
        double t = timberPoints(p, hand, wood);
        return t > 0 && ThreadLocalRandom.current().nextDouble() * 100 < t * FDials.timberPercentPerPoint.get();
    }

    /** What a daily perk's face means, for menus. */
    static String dailyFace(String perk, int face) {
        if ("daily_grove".equals(perk)) return switch (face) {
            case HiveMath.DAILY_FIG -> "+50 Fig Fortune"; case HiveMath.DAILY_MANGROVE -> "+50 Mangrove Fortune";
            case HiveMath.DAILY_HELIX -> "+50 Spiral Fortune"; default -> "+10 Sweep"; };
        return switch (face) {
            case HiveMath.DAILY_REFILL -> "hives refill 20% faster"; case HiveMath.DAILY_LURE -> "honey lures 20% faster";
            case HiveMath.DAILY_COMB -> "+2 Honeycomb per hive"; default -> "25% chance of a second critter"; };
    }

    static boolean ricochet(Player p) {
        int lv = level(ForagingModule.data(p), "ricochet");
        return lv > 0 && ThreadLocalRandom.current().nextDouble() < lv / 100.0;
    }

    // ================================================================================ earning

    /** Called for every swing: Daily Wishes, and on the Moonmarsh per-log whispers + HotF XP (TUNE). */
    static void onLogs(Player p, Wood wood, int logs, boolean marsh) {
        ForagingData d = ForagingModule.data(p);
        if (!d.hotfUnlocked) return;
        rollDay(d);
        int wishes = level(d, "daily_wishes");
        if (wishes > 0 && d.woodsToday.add(wood.name())) {
            addWhispers(p, 4L * wishes, 8L * wishes, "Daily Wishes");
        }
        if (marsh) {
            long w = Math.round(logs * FDials.whisperScale.get());
            if (wood == Wood.HELIX) addWhispers(p, 0, w, null); else addWhispers(p, w, 0, null);
            addXp(p, logs * FDials.hotfXpPerLog.get());
        }
    }

    static void addWhispers(Player p, long forest, long desert, String reason) {
        ForagingData d = ForagingModule.data(p);
        double bonus = level(d, "center") >= 3 ? 1.10 : 1.0;
        long f = Math.round(forest * bonus * Bonuses.forestWhisperFactor(p)), de = Math.round(desert * bonus * Bonuses.desertWhisperFactor(p));
        d.forestWhispers += f;
        d.desertWhispers += de;
        if (reason != null && (f > 0 || de > 0)) {
            StringBuilder sb = new StringBuilder("&3+");
            if (f > 0) sb.append("&b").append(Text.commasInt(f)).append(" Forest Whispers");
            if (f > 0 && de > 0) sb.append(" &7and &e");
            if (de > 0) sb.append(f > 0 ? "" : "&e").append(Text.commasInt(de)).append(" Desert Whispers");
            sb.append(" &7(").append(reason).append(")");
            Msg.send(p, sb.toString());
        }
    }

    static void addXp(Player p, double amount) {
        ForagingData d = ForagingModule.data(p);
        if (!d.hotfUnlocked || amount <= 0) return;
        int before = tier(d);
        d.hotfXp += Math.round(amount * FDials.hotfXpScale.get() * Bonuses.hotfXpFactor(p));
        int after = tier(d);
        if (after > before) tierUp(p, d, before, after);
    }

    static void tierUp(Player p, ForagingData d, int from, int to) {
        for (int t = Math.max(from + 1, d.hotfRewarded + 1); t <= to; t++) {
            List<String> body = new ArrayList<>();
            body.add("&8+&5" + SweepMath.HOTF_TOKENS[t - 1] + " Token" + (SweepMath.HOTF_TOKENS[t - 1] > 1 ? "s" : "") + " of the Forest");
            body.add("&8+&b" + SweepMath.HOTF_SBXP[t - 1] + " SkyBlock XP");
            body.add(TIER_REWARD[t - 1]);
            Msg.box(p, "&2&lHEART OF THE FOREST &8Tier " + Text.roman(t), body);
            Sky.service(Features.SkyLevel.class).award(p, "hotf:" + t, SweepMath.HOTF_SBXP[t - 1], "Heart of the Forest " + Text.roman(t));
            d.hotfRewarded = t;
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        Sky.profiles().saveNow(p);
    }

    static void unlock(Player p) {
        ForagingData d = ForagingModule.data(p);
        if (d.hotfUnlocked) return;
        d.hotfUnlocked = true;
        d.hotfRewarded = 0;
        tierUp(p, d, 0, tier(d));
    }

    // ================================================================================ menu

    static Menu menu(Player p) {
        ForagingData d = ForagingModule.data(p);
        Menu m = new Menu("Heart of the Forest", 6).chrome();
        if (!d.hotfUnlocked) {
            m.set(22, Icons.of(Material.OAK_SAPLING, "&cHeart of the Forest", "&7The forest has not opened its heart", "&7to you yet.", "",
                    "&7Reach &aForaging XII&7, travel to the", "&2Moonmarsh &7and speak to &aKeeper Ilse&7.", "", "&cLocked"));
            m.set(48, Icons.back("Foraging"), c -> ForagingMenus.main(c.player()).open(c.player()));
            return m;
        }
        int tier = tier(d);
        for (HotfPerk perk : HotfPerk.ALL) m.set(perk.slot(), perkIcon(p, d, perk, tier), c -> click(c.player(), perk, c.right()));
        long next = tier < 8 ? SweepMath.HOTF_CUMULATIVE[tier] : 0;
        List<String> tl = new ArrayList<>();
        tl.add("&7Tier: &a" + tier + "&7/8");
        if (tier < 8) {
            long base = SweepMath.HOTF_CUMULATIVE[tier - 1];
            double f = (double) (d.hotfXp - base) / Math.max(1, next - base);
            tl.add("&7Progress: &e" + Text.commasInt(d.hotfXp - base) + "&6/&e" + Text.shortNum(next - base));
            tl.add(Text.dashBar(f, 20));
        } else tl.add("&aMaxed!");
        tl.add("");
        tl.add("&7Earn HotF XP from &aTree Gifts&7, &eStarfall");
        tl.add("&eContests &7and cutting logs in the &2Moonmarsh&7.");
        m.set(45, Icons.of(Material.MOSS_BLOCK, "&2Heart of the Forest " + Text.roman(tier), tl));
        m.set(46, Icons.of(Material.EMERALD, "&5Tokens of the Forest", "&7Available: &5" + tokensFree(d), "&7Earned: &5" + tokensTotal(d), "",
                "&7Each new perk costs one token."));
        m.set(50, Icons.of(Material.PRISMARINE_CRYSTALS, "&bWhispers", "&7Forest Whispers: &b" + Text.commasInt(d.forestWhispers),
                "&7Desert Whispers: &e" + Text.commasInt(d.desertWhispers), "&7Forest Essence: &d" + Text.commasInt(d.forestEssence), "",
                "&7Forest Whispers come from fig and", "&7mangrove trees, Desert Whispers from", "&7the spiral trees of the canyon."));
        m.set(48, Icons.back("Foraging"), c -> ForagingMenus.main(c.player()).open(c.player()));
        m.set(47, HotfAbilities.icon(d), c -> {
            if (tier(ForagingModule.data(c.player())) < 2) { Msg.error(c.player(), "You need Heart of the Forest II!"); return; }
            HotfAbilities.menu(c.player()).open(c.player());
        });
        m.set(53, Icons.of(Material.BOOK, "&aHow it works", "&7Left-click a perk to unlock or level it.",
                "&7Right-click an unlocked perk to toggle it", "&7on or off (nothing is refunded).", "",
                "&7Tier I-VIII unlock rows from the bottom", "&7up. Half Full and Half Empty cannot be", "&7active together."));
        return m;
    }

    private static ItemStack perkIcon(Player p, ForagingData d, HotfPerk perk, int tier) {
        int lv = rawLevel(d, perk.id());
        boolean disabled = d.disabledPerks.contains(perk.id());
        boolean tierOk = tier >= perk.tier();
        List<String> lore = new ArrayList<>();
        lore.add("&7Level " + lv + "&8/" + perk.max() + (disabled ? " &c(DISABLED)" : ""));
        lore.add("&8Tier " + Text.roman(perk.tier()) + " perk");
        lore.add("");
        for (String l : perk.describe()) lore.add("&7" + HotfPerk.render(l, Math.max(1, lv)));
        if (perk.kind() == HotfPerk.Kind.TOKEN) {
            int face = HiveMath.dailyPick(p.getUniqueId().getMostSignificantBits(), p.getUniqueId().getLeastSignificantBits(),
                    LocalDate.now(ZoneOffset.UTC).toEpochDay(), perk.id(), 4);
            lore.add("");
            lore.add("&7Today" + (lv > 0 ? "" : " &8(if taken)") + "&7: &a" + dailyFace(perk.id(), face));
        }
        if (lv > 0 && lv < perk.max()) {
            lore.add("");
            lore.add("&7Next level:");
            for (String l : perk.describe()) lore.add("&8" + Text.strip(HotfPerk.render(l, lv + 1)));
        }
        lore.add("");
        Material mat;
        if (!tierOk) {
            lore.add("&cRequires Heart of the Forest " + Text.roman(perk.tier()));
            mat = Material.COAL;
        } else if (lv >= perk.max()) {
            lore.add("&aMaxed!");
            mat = Material.matchMaterial(perk.icon()) == null ? Material.EMERALD_BLOCK : Material.matchMaterial(perk.icon());
        } else if (lv == 0 && perk.kind() != HotfPerk.Kind.CORE) {
            lore.add("&7Cost: &51 Token of the Forest");
            if (perk.exclusive() != null && rawLevel(d, perk.exclusive()) > 0) lore.add("&cCannot be taken with " + HotfPerk.byId(perk.exclusive()).name());
            lore.add("&eClick to unlock!");
            mat = Material.REDSTONE;
        } else {
            long cost = perk.cost(lv);
            boolean desert = perk.desertFor(lv);
            long have = desert ? d.desertWhispers : d.forestWhispers;
            lore.add("&7Cost: " + (desert ? "&e" : "&b") + Text.commasInt(cost) + " " + perk.currency(lv));
            lore.add(have >= cost ? "&eClick to level up!" : "&cNot enough whispers!");
            if (lv > 0) lore.add("&eRight-click to " + (disabled ? "enable" : "disable") + "!");
            Material im = Material.matchMaterial(perk.icon());
            mat = im == null ? Material.EMERALD : im;
        }
        String color = !tierOk ? "&c" : lv >= perk.max() ? "&a" : lv > 0 ? "&e" : "&c";
        ItemStack it = Icons.of(mat, color + perk.name(), lore);
        return lv > 0 && !disabled ? Icons.glow(it) : it;
    }

    private static void click(Player p, HotfPerk perk, boolean right) {
        ForagingData d = ForagingModule.data(p);
        int lv = rawLevel(d, perk.id());
        if (right) {
            if (lv <= 0 || perk.kind() == HotfPerk.Kind.CORE) return;
            if (!d.disabledPerks.remove(perk.id())) d.disabledPerks.add(perk.id());
            else if (perk.exclusive() != null && rawLevel(d, perk.exclusive()) > 0) d.disabledPerks.add(perk.exclusive());
            Sky.stats().invalidate(p);
            menu(p).open(p);
            return;
        }
        if (tier(d) < perk.tier()) { Msg.error(p, "You need Heart of the Forest " + Text.roman(perk.tier()) + "!"); return; }
        if (lv >= perk.max()) return;
        if (lv == 0 && perk.kind() != HotfPerk.Kind.CORE) {
            if (perk.exclusive() != null && rawLevel(d, perk.exclusive()) > 0) { Msg.error(p, "You already chose " + HotfPerk.byId(perk.exclusive()).name() + "."); return; }
            if (tokensFree(d) <= 0) { Msg.error(p, "You have no Tokens of the Forest left!"); return; }
            d.tokensSpent++;
        } else {
            long cost = perk.cost(lv);
            boolean desert = perk.desertFor(lv);
            if ((desert ? d.desertWhispers : d.forestWhispers) < cost) { Msg.error(p, "You do not have enough " + perk.currency(lv) + "!"); return; }
            if (desert) d.desertWhispers -= cost; else d.forestWhispers -= cost;
        }
        d.perks.put(perk.id(), lv + 1);
        Sky.stats().invalidate(p);
        Msg.success(p, (lv == 0 ? "Unlocked " : "Upgraded ") + perk.name() + " to level " + (lv + 1) + "!");
        menu(p).open(p);
    }
}
