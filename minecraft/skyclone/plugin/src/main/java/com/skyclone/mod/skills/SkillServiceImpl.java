package com.skyclone.mod.skills;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The SkillService: XP, levels, caps, level-up rewards and the "skills" stat provider. */
final class SkillServiceImpl implements SkillService {

    static final String KEY = "skills";
    /** Hard ceiling on stored XP, far above level 60 (111.7M) and below the economy's 1e15 guard. */
    static final double XP_CEILING = 1e13;

    final SkillTable table;
    final SkillDials dials;
    SkillMenus menus;

    SkillServiceImpl(SkillTable table, SkillDials dials) {
        this.table = table;
        this.dials = dials;
    }

    SkillData data(Player p) { return Sky.profiles().member(p, KEY, SkillData.class); }

    boolean ready(Player p) { return p != null && p.isOnline() && Sky.profiles().isReady(p); }

    // ---- reads --------------------------------------------------------------------------------

    @Override public double xp(Player p, SkillType s) {
        if (!ready(p) || s == null) return 0;
        return rawXp(data(p), s);
    }

    static double rawXp(SkillData d, SkillType s) {
        Double v = d.xp.get(s.name());
        return v == null || v.isNaN() ? 0 : Math.max(0, v);
    }

    @Override public int level(Player p, SkillType s) {
        if (!ready(p) || s == null) return 0;
        return levelOf(data(p), s);
    }

    int levelOf(SkillData d, SkillType s) { return table.levelFor(s, rawXp(d, s), capOf(d, s)); }

    @Override public double progress(Player p, SkillType s) {
        if (!ready(p) || s == null) return 0;
        SkillData d = data(p);
        return table.progress(s, rawXp(d, s), levelOf(d, s));
    }

    @Override public double needed(Player p, SkillType s) {
        if (!ready(p) || s == null) return 0;
        SkillData d = data(p);
        int lvl = levelOf(d, s);
        return lvl >= capOf(d, s) ? 0 : table.needed(s, lvl);
    }

    @Override public double average(Player p) {
        if (!ready(p)) return 0;
        return averageOf(data(p));
    }

    double averageOf(SkillData d) {
        int n = 0;
        double sum = 0;
        boolean frac = dials.averageFractional.get();
        for (SkillType s : SkillType.values()) {
            if (!counted(s)) continue;
            n++;
            int lvl = levelOf(d, s);
            sum += lvl;
            if (frac && lvl < capOf(d, s)) {
                double need = table.needed(s, lvl);
                if (need > 0) sum += Math.min(1, table.progress(s, rawXp(d, s), lvl) / need);
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /**
     * Whether a skill counts toward the Skill Average. Research 02: every skill except Social, Runecrafting and
     * Dungeoneering, so Carpentry and Hunting DO count in the reference, while the API enum marks both cosmetic.
     * Carpentry: dial {@code skills.average-includes-carpentry} (true). Hunting, which the API marks cosmetic so nobody's
     * average fell the day it was added: dial {@code skills.average-includes-hunting}: auto = count it once a module provides
     * {@code Features.Hunting} (a skill you can actually level), true / false = always / never.
     */
    boolean counted(SkillType s) {
        if (s == SkillType.SOCIAL || s == SkillType.RUNECRAFTING) return false;
        if (s == SkillType.CARPENTRY) return dials.averageCarpentry.get();   // research 02 counts it; the API enum says cosmetic
        if (s == SkillType.HUNTING) {
            String v = dials.averageHunting.get();
            if ("true".equalsIgnoreCase(v)) return true;
            if ("false".equalsIgnoreCase(v)) return false;
            return Sky.has(Features.Hunting.class);
        }
        return !s.cosmetic;
    }

    @Override public int cap(Player p, SkillType s) {
        if (s == null) return 0;
        if (!ready(p)) return defaultCap(s);
        return capOf(data(p), s);
    }

    int defaultCap(SkillType s) {
        int max = table.maxLevel(s);
        if (!dials.capGates.get()) return max;
        return Math.max(1, Math.min(max, dials.defaultCap.get(s).get()));
    }

    int capOf(SkillData d, SkillType s) {
        int max = table.maxLevel(s);
        if (!dials.capGates.get()) return max;
        int def = defaultCap(s);
        Integer c = d.caps.get(s.name());
        return c == null ? def : Math.max(def, Math.min(max, c));
    }

    // ---- writes --------------------------------------------------------------------------------

    @Override public void addXp(Player p, SkillType s, double baseXp, String source) {
        if (!ready(p) || s == null || !(baseXp > 0) || Double.isInfinite(baseXp)) return;
        double wisdom = Sky.stats().get(p, s.wisdom);
        double amount = baseXp * (1 + wisdom / 100.0) * dials.xpMultiplier.get() * dials.perSkill.get(s).get();
        if (!(amount > 0) || Double.isInfinite(amount)) return;
        SkyEvents.SkillXpGain e = new SkyEvents.SkillXpGain(p, s, amount, source == null ? "" : source);
        Bukkit.getPluginManager().callEvent(e);
        if (e.isCancelled() || !(e.amount > 0) || Double.isInfinite(e.amount)) return;
        grant(p, s, e.amount, true);
    }

    /** Add XP with no wisdom and no event (admin). Processes level-ups. */
    void grant(Player p, SkillType s, double amount, boolean popup) {
        if (!ready(p)) return;
        SkillData d = data(p);
        int before = levelOf(d, s);
        double nx = Math.min(XP_CEILING, rawXp(d, s) + amount);
        d.xp.put(s.name(), nx);
        d.lastSkill = s.name();
        int after = levelOf(d, s);
        if (popup && d.xpPopups) flash(p, d, s, amount, after);
        if (after > before) levelUps(p, s, before, after);
    }

    private void flash(Player p, SkillData d, SkillType s, double amount, int level) {
        int ticks = dials.flashTicks.get();
        if (ticks <= 0) return;
        double x = rawXp(d, s);
        String tail;
        if (level >= capOf(d, s)) tail = Text.commasInt(x);
        else tail = Text.commasInt(table.progress(s, x, level)) + "/" + Text.commasInt(table.needed(s, level));
        Sky.vitals().flash(p, "&3+" + SkillText.fmt(amount) + " " + s.display + " (" + tail + ")", ticks);
    }

    /** Admin: set the cumulative XP. Going up pays the levels in between (once ever per level). */
    void setXp(Player p, SkillType s, double xp) {
        if (!ready(p)) return;
        SkillData d = data(p);
        int before = levelOf(d, s);
        d.xp.put(s.name(), Math.max(0, Math.min(XP_CEILING, xp)));
        int after = levelOf(d, s);
        Sky.stats().invalidate(p);
        if (after > before) levelUps(p, s, before, after);
    }

    /** Admin: exactly this level (XP = the level's threshold). Raises the cap when needed so the level sticks. */
    void setLevel(Player p, SkillType s, int level) {
        if (!ready(p)) return;
        int lvl = Math.max(0, Math.min(level, table.maxLevel(s)));
        SkillData d = data(p);
        if (lvl > capOf(d, s)) d.caps.put(s.name(), lvl);
        setXp(p, s, table.total(s, lvl));
    }

    @Override public void setCap(Player p, SkillType s, int cap) {
        if (!ready(p) || s == null) return;
        SkillData d = data(p);
        int before = levelOf(d, s);
        int oldCap = capOf(d, s);
        d.caps.put(s.name(), Math.max(0, Math.min(cap, table.maxLevel(s))));
        int newCap = capOf(d, s);
        if (newCap > oldCap)
            Msg.send(p, "&aYour &e" + s.display + " &alevel cap is now &e" + newCap + "&a!");
        int after = levelOf(d, s);
        Sky.stats().invalidate(p);
        if (after > before) levelUps(p, s, before, after);
    }

    void reset(Player p, SkillType s) {
        if (!ready(p)) return;
        SkillData d = data(p);
        d.xp.remove(s.name());
        d.caps.remove(s.name());
        // paid / sbxpPaid stay: rewards already handed out must never be handed out twice.
        Sky.stats().invalidate(p);
    }

    // ---- level-ups ----------------------------------------------------------------------------

    /** Pays rewards for (from, to], shows the box, plays the sound, fires one SkillLevelUp per level. */
    void levelUps(Player p, SkillType s, int from, int to) {
        SkillData d = data(p);
        String id = s.name();

        // coins: each level at most once, ever
        double coins = 0;
        int paid = d.paid.getOrDefault(id, 0);
        if (dials.coinRewards.get()) {
            for (int l = Math.max(from, paid) + 1; l <= to; l++) {
                SkillTable.Level lv = table.level(s, l);
                if (lv != null) coins += lv.coins * Math.max(0, dials.coinMultiplier.get());
            }
        }
        if (to > paid) d.paid.put(id, to);
        if (coins > 0) Sky.economy().deposit(p, coins, "skills: " + s.display + " level " + (from + 1) + "-" + to + " rewards");

        int sbxp = awardSkyBlockXp(p, d, s, to);

        // the box
        int threshold = Math.max(1, dials.bulkBoxThreshold.get());
        if (to - from <= threshold) {
            for (int l = from + 1; l <= to; l++) {
                double c = 0;
                if (l > paid && dials.coinRewards.get()) {
                    SkillTable.Level lv = table.level(s, l);
                    c = lv == null ? 0 : lv.coins * Math.max(0, dials.coinMultiplier.get());
                }
                List<String> body = new ArrayList<>();
                body.add("&a&lREWARDS");
                body.addAll(SkillText.levelRewards(table, s, l, 0, dials.perkScale.get(), "  "));
                if (c > 0) body.add("  &8+&6" + Text.commasInt(c) + " &7Coins");
                Msg.box(p, headline(s, l - 1, l), body);
            }
        } else {
            List<String> body = new ArrayList<>();
            body.add("&a&lREWARDS");
            body.addAll(SkillText.bulkRewards(table, s, from, to, coins, sbxp, dials.perkScale.get(), "  "));
            Msg.box(p, headline(s, from, to), body);
        }
        if (dials.levelUpSound.get()) Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1f);

        Sky.stats().invalidate(p);
        Sky.profiles().saveNow(p);
        for (int l = from + 1; l <= to; l++)
            Bukkit.getPluginManager().callEvent(new SkyEvents.SkillLevelUp(p, s, l));
    }

    static String headline(SkillType s, int from, int to) {
        String a = from <= 0 ? "0" : Text.roman(from);
        return "&b&lSKILL LEVEL UP &3" + s.display + " &8" + a + SkillText.ARROW + "&3" + Text.roman(to);
    }

    /**
     * Hand SkyBlock XP for every level up to {@code to} not yet handed to a REAL SkyLevel service.
     * The award key ("skill:FARMING:5") is one-time on the SkyLevel side as well.
     */
    int awardSkyBlockXp(Player p, SkillData d, SkillType s, int to) {
        if (!Sky.has(Features.SkyLevel.class)) return 0;
        Features.SkyLevel sl = Sky.service(Features.SkyLevel.class);
        int done = d.sbxpPaid.getOrDefault(s.name(), 0);
        int total = 0;
        for (int l = done + 1; l <= to; l++) {
            SkillTable.Level lv = table.level(s, l);
            if (lv == null || lv.sbxp <= 0) continue;
            sl.award(p, "skill:" + s.name() + ":" + l, lv.sbxp, s.display + " " + Text.roman(l));
            total += lv.sbxp;
        }
        if (to > done) d.sbxpPaid.put(s.name(), to);
        return total;
    }

    /** On join: SkyBlock XP for levels reached while no SkyLevel service was installed. */
    void catchUp(Player p) {
        if (!ready(p) || !dials.sbxpCatchUp.get() || !Sky.has(Features.SkyLevel.class)) return;
        SkillData d = data(p);
        for (SkillType s : SkillType.values()) {
            int lvl = levelOf(d, s);
            if (lvl > d.sbxpPaid.getOrDefault(s.name(), 0)) awardSkyBlockXp(p, d, s, lvl);
        }
    }

    // ---- stats ---------------------------------------------------------------------------------

    /** StatProvider "skills": every skill's cumulative stat rewards + perk stats. Runs every 10 ticks. */
    void contribute(Player p, StatMap into) {
        if (!ready(p)) return;
        SkillData d = data(p);
        double perkScale = dials.perkScale.get();
        for (SkillType s : SkillType.values()) {
            int lvl = levelOf(d, s);
            if (lvl <= 0) continue;
            for (Map.Entry<Stat, Double> e : table.statsAt(s, lvl).entrySet()) into.add(e.getKey(), e.getValue());
            Stat ps = table.perkStat(s);
            if (ps != null) into.add(ps, table.perk(s, lvl) * perkScale);
        }
        // wave 5: the Runebook accessory (accessories hook "slayer", but Runic mobs are this module's) -
        // +1 Runecrafting Wisdom per digit of the Runic kills counted while you carried it
        int rb = runebookWisdom(p, d);
        if (rb > 0) into.add(Stat.RUNECRAFTING_WISDOM, rb);
    }

    static final String RUNEBOOK = "RUNEBOOK";

    boolean hasRunebook(Player p) {
        if (!dials.runebook.get() || !Sky.has(Features.Accessories.class)) return false;
        try { return Sky.service(Features.Accessories.class).has(p, RUNEBOOK); } catch (RuntimeException e) { return false; }
    }

    int runebookWisdom(Player p, SkillData d) {
        return hasRunebook(p) ? TrainerTiers.digits(d.runebookKills) : 0;
    }

    /** Everything a menu needs, copied (so a menu can also be built for a fake profile in a self-test). */
    SkillMenus.View view(Player p) {
        EnumMap<SkillType, Double> xp = new EnumMap<>(SkillType.class);
        EnumMap<SkillType, Integer> caps = new EnumMap<>(SkillType.class);
        boolean popups = true;
        if (ready(p)) {
            SkillData d = data(p);
            for (SkillType s : SkillType.values()) { xp.put(s, rawXp(d, s)); caps.put(s, capOf(d, s)); }
            popups = d.xpPopups;
        } else {
            for (SkillType s : SkillType.values()) { xp.put(s, 0.0); caps.put(s, defaultCap(s)); }
        }
        int cata = 0;
        try { cata = Sky.service(Features.Dungeons.class).catacombsLevel(p); } catch (RuntimeException ignored) { }
        return new SkillMenus.View(xp, caps, popups, cata);
    }

    @Override public void openMenu(Player p) {
        if (p == null) return;
        if (!ready(p)) { Msg.error(p, "Your profile is still loading!"); return; }
        menus.yourSkills(p).open(p);
    }
}
