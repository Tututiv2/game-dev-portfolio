package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Heart of the Mountain logic and powder bookkeeping (Features.Mining is built on this). */
final class Hotm implements Features.Mining {

    final HotmTree tree;
    private final MiningModule m;

    Hotm(MiningModule m, HotmTree tree) { this.m = m; this.tree = tree; }

    // ---- tiers, tokens --------------------------------------------------------------------

    int tier(Player p) { return tree.tier(MiningUtil.data(p).hotmXp); }

    int coreLevel(Player p) { return MiningUtil.data(p).perk("core_of_the_mountain"); }

    int tokensTotal(Player p) { return tree.tokensFor(tier(p), coreLevel(p)); }

    int tokensFree(Player p) { return Math.max(0, tokensTotal(p) - MiningUtil.data(p).tokensSpent); }

    /** Pickaxe ability level: 1, +1 from Core of the Mountain, +1 from a Blue Cheese omelette (research 2.7). */
    int abilityLevel(Player p) {
        int l = 1;
        if (coreLevel(p) >= 1) l++;
        if (m.drills.blueCheese(p)) l++;
        return Math.min(3, l);
    }

    // ---- perk levels ----------------------------------------------------------------------

    int raw(Player p, String perk) { return MiningUtil.data(p).perk(perk); }

    /** Level that counts right now: 0 when disabled, +1 with a Blue Cheese omelette on a leveled perk. */
    int effective(Player p, String perkId) {
        MiningData d = MiningUtil.data(p);
        int l = d.perk(perkId);
        if (l <= 0 || d.disabledPerks.contains(perkId)) return 0;
        HotmTree.Perk perk = tree.perk(perkId);
        if (perk != null && perk.maxLevel > 1 && !perk.core() && m.drills.blueCheese(p)) l++;
        return l;
    }

    boolean has(Player p, String perk) { return effective(p, perk) > 0; }

    double value(Player p, String perkId) {
        HotmTree.Perk perk = tree.perk(perkId);
        int l = effective(p, perkId);
        return perk == null || l <= 0 ? 0 : perk.value(l);
    }

    // ---- buying -----------------------------------------------------------------------------

    /** Unlock (level 1) with a token. Returns null on success or the reason. */
    String unlock(Player p, HotmTree.Perk perk) {
        MiningData d = MiningUtil.data(p);
        if (d.perk(perk.id) > 0) return "You already unlocked " + perk.name + "!";
        if (tier(p) < perk.tier) return "Requires Heart of the Mountain Tier " + perk.tier + "!";
        if (!tree.reachable(perk, d.perks, tier(p))) return "Unlock a perk next to this one first!";
        if (tokensFree(p) < 1) return "You don't have a Token of the Mountain to spend!";
        d.tokensSpent++;
        d.perks.put(perk.id, 1);
        if (perk.ability && d.selectedAbility == null) d.selectedAbility = perk.id;
        Sky.stats().invalidate(p);
        return null;
    }

    /** Upgrade by {@code times} levels with powder. Returns null on success or the reason. */
    String upgrade(Player p, HotmTree.Perk perk, int times) {
        MiningData d = MiningUtil.data(p);
        int lvl = d.perk(perk.id);
        if (lvl <= 0) return unlock(p, perk);
        int bought = 0;
        String err = null;
        for (int i = 0; i < times; i++) {
            if (lvl >= perk.maxLevel) { err = perk.name + " is already maxed!"; break; }
            long cost = perk.costToNext(lvl);
            String type = perk.powderFor(lvl);
            if (cost < 0) { err = "This perk cannot be upgraded."; break; }
            if (d.powder(type) < cost) { err = "You need " + Text.commas(cost) + " " + MiningUtil.powderName(type) + "!"; break; }
            d.powder.merge(type, -cost, Long::sum);
            d.powderSpent.merge(type, cost, Long::sum);
            lvl++;
            bought++;
        }
        if (bought > 0) {
            d.perks.put(perk.id, lvl);
            Sky.stats().invalidate(p);
            return null;
        }
        return err;
    }

    long costOf(HotmTree.Perk perk, int fromLevel, int levels) {
        long sum = 0;
        for (int l = fromLevel; l < Math.min(perk.maxLevel, fromLevel + levels); l++) sum += Math.max(0, perk.costToNext(l));
        return sum;
    }

    /** Refunds every powder spent and every token. Coins must be paid by the caller. */
    void reset(Player p) {
        MiningData d = MiningUtil.data(p);
        for (Map.Entry<String, Long> e : d.powderSpent.entrySet()) d.powder.merge(e.getKey(), e.getValue(), Long::sum);
        d.powderSpent.clear();
        d.perks.clear();
        d.disabledPerks.clear();
        d.tokensSpent = 0;
        d.selectedAbility = null;
        Sky.stats().invalidate(p);
    }

    // ---- XP ---------------------------------------------------------------------------------

    /** Bingo pet "Climber": % more HOTM XP from play (0 with no pet); admin grants are never scaled. */
    double xpBonusPct(Player p) { return MiningUtil.pet(p, "bingo_climber"); }

    /** "+N Forge Slot(s)" for the slots reaching this tier adds, or null when it adds none (see {@link Forge#slots}). */
    String forgeSlotLine(int tier) {
        int n = m.forge == null ? 0 : m.forge.slotsGained(tier);
        return n <= 0 ? null : "&8+&a" + n + " Forge Slot" + (n == 1 ? "" : "s");
    }

    void addXp(Player p, double xp, String reason) {
        if (!MiningUtil.ready(p) || xp <= 0) return;
        if (!"admin".equals(reason)) xp *= 1 + Math.max(0, xpBonusPct(p)) / 100.0;
        MiningData d = MiningUtil.data(p);
        awaken(p);
        int before = tree.tier(d.hotmXp);
        double cap = tree.xpForTier(tree.maxTier());
        d.hotmXp = Math.min(cap, d.hotmXp + xp);
        int after = tree.tier(d.hotmXp);
        payTierRewards(p);
        if (after > before) {
            for (int t = before + 1; t <= after; t++) announceTier(p, t);
        }
    }

    /**
     * Wake the Heart (first visit to a powder island, or the first HOTM XP): pays Tier 1's one-time rewards
     * with its own reveal. Idempotent; a no-op for anyone already awake or already paid for Tier 1.
     */
    void awaken(Player p) {
        if (!MiningUtil.ready(p)) return;
        MiningData d = MiningUtil.data(p);
        if (d.hotmAwakened) return;
        d.hotmAwakened = true;
        if (d.rewardedTier >= 1) return;   // paid before this gate existed
        payTierRewards(p);
        List<String> body = new ArrayList<>();
        body.add("&7The &5Heart of the Mountain &7stirs beneath your feet.");
        body.add("&a&lREWARDS");
        int tok = tree.tokens.length > 0 ? tree.tokens[0] : 0;
        body.add("  &8+&5" + tok + " Token" + (tok == 1 ? "" : "s") + " of the Mountain");
        String fl = forgeSlotLine(1);
        if (fl != null) body.add("  " + fl);
        int sb = tree.skyblockXp != null && tree.skyblockXp.length > 0 ? tree.skyblockXp[0] : 0;
        if (sb > 0) body.add("  &8+&b" + sb + " SkyBlock XP");
        body.add("&7Open it with &e/hotm&7.");
        Msg.box(p, "&5&lHEART OF THE MOUNTAIN &r&5Tier I", body);
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
    }

    /**
     * Idempotent: pays SkyBlock XP for every tier reached but not yet rewarded. Nothing is paid until the
     * Heart is awake - Tier 1 sits at 0 XP, so a brand-new profile has "reached" it without doing anything.
     */
    void payTierRewards(Player p) {
        MiningData d = MiningUtil.data(p);
        if (!d.hotmAwakened && d.hotmXp <= 0) return;
        int t = tree.tier(d.hotmXp);
        while (d.rewardedTier < t) {
            d.rewardedTier++;
            int idx = d.rewardedTier - 1;
            int sbxp = tree.skyblockXp != null && idx < tree.skyblockXp.length ? tree.skyblockXp[idx] : 0;
            if (sbxp > 0) Sky.service(Features.SkyLevel.class).award(p, "hotm:tier:" + d.rewardedTier, sbxp, "Heart of the Mountain Tier " + d.rewardedTier);
        }
    }

    private void announceTier(Player p, int t) {
        List<String> body = new ArrayList<>();
        body.add("&a&lREWARDS");
        int tok = t - 1 < tree.tokens.length ? tree.tokens[t - 1] : 0;
        body.add("  &8+&5" + tok + " Token" + (tok == 1 ? "" : "s") + " of the Mountain");
        String fl = forgeSlotLine(t);
        if (fl != null) body.add("  " + fl);
        if (t == m.dials.forgeUnlockTier.get()) body.add("  &7Access to &6The Forge");
        int sb = t - 1 < tree.skyblockXp.length ? tree.skyblockXp[t - 1] : 0;
        if (sb > 0) body.add("  &8+&b" + sb + " SkyBlock XP");
        if (t == 4) body.add("  &7Access to the &dCrystal Caverns");
        if (t == 7) body.add("  &7Access to the &bFrozen Tunnels");
        Msg.box(p, "&5&lHEART OF THE MOUNTAIN &r&5Tier " + Text.roman(t - 1) + " &8➜ &5" + Text.roman(t), body);
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
        p.showTitle(net.kyori.adventure.title.Title.title(Text.c("&5&lHOTM " + Text.roman(t)), Text.c("&7Heart of the Mountain levelled up!")));
    }

    // ---- powder -----------------------------------------------------------------------------

    /**
     * Grant powder. {@code boosted} applies Powder Buff, the Spicy omelette, the 2x Powder event and the
     * global dial: "+X% more powder from any source". Returns the amount granted.
     */
    long grant(Player p, String type, double base, String reason, boolean boosted) {
        if (!MiningUtil.ready(p) || base <= 0 || type == null) return 0;
        double amt = base;
        if (boosted) {
            double pct = value(p, "powder_buff") + m.drills.powderBonus(p) + m.skyMall.powderBonus(p)
                    + MiningUtil.pet(p, MiningUtil.petPowderAbility(type));
            amt *= 1 + pct / 100.0;
            if (m.events.active(MiningUtil.island(p), MiningEvents.Type.DOUBLE_POWDER)) amt *= 2;
            amt *= m.dials.powderMult.get();
        }
        long n = Math.max(0, Math.round(amt));
        if (n <= 0) return 0;
        MiningData d = MiningUtil.data(p);
        d.powder.merge(type, n, Long::sum);
        d.powderEarned.merge(type, n, Long::sum);
        m.feedback.powder(p, type, n);
        if (boosted && m.events.active(MiningUtil.island(p), MiningEvents.Type.DOUBLE_POWDER))
            m.comms.progress(p, "POWDER", type, (int) n, null);
        return n;
    }

    boolean take(Player p, String type, long amount) {
        MiningData d = MiningUtil.data(p);
        if (d.powder(type) < amount) return false;
        d.powder.merge(type, -amount, Long::sum);
        return true;
    }

    // ---- Features.Mining ----------------------------------------------------------------------

    /** Features.Mining.openMenu (core wave 5): the mining main menu, same as {@code /mining}. */
    @Override public void openMenu(Player p) { if (p != null && MiningUtil.ready(p)) m.menus.openMain(p); }

    @Override public int hotmLevel(Player p) { return MiningUtil.ready(p) ? tier(p) : 0; }

    @Override public long powder(Player p, String type) {
        String t = MiningUtil.parsePowder(type);
        return MiningUtil.ready(p) && t != null ? MiningUtil.data(p).powder(t) : 0;
    }

    @Override public void addPowder(Player p, String type, long amount, String reason) {
        String t = MiningUtil.parsePowder(type);
        if (t == null || amount <= 0 || !MiningUtil.ready(p)) return;
        grant(p, t, amount, reason, false);
    }

    @Override public int heat(Player p) { return m.hollows.heat(p); }

    @Override public int cold(Player p) { return m.tunnels.cold(p); }
}
