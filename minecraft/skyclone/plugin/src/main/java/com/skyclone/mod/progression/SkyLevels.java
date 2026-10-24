package com.skyclone.mod.progression;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * SkyBlock XP and SkyBlock Level (research 02 section 6): one-time keyed awards, 100 XP a level,
 * +5 Health a level, +1 Strength per 5 levels, the level-up box, and the "SkyBlock Leveling" menu.
 */
final class SkyLevels implements Features.SkyLevel {

    private record Pending(String key, int xp, String reason) {}

    /** Awards that arrived before the player's profile was ready; flushed on ProfileReady. */
    private final Map<UUID, List<Pending>> pending = new HashMap<>();

    /** award-key prefix -> {category index, task index} */
    private final Map<String, int[]> prefixIndex = new HashMap<>();

    SkyLevels() {
        List<Catalogs.Category> cats = Prog.levelsFile.categories;
        for (int c = 0; c < cats.size(); c++) {
            List<Catalogs.Task> tasks = cats.get(c).tasks;
            for (int t = 0; t < tasks.size(); t++) {
                for (String pre : tasks.get(t).prefixes) prefixIndex.putIfAbsent(pre.toLowerCase(Locale.ROOT), new int[]{c, t});
            }
        }
    }

    private int perLevel() { return Math.max(1, Prog.levelsFile.xpPerLevel); }

    // ---- Features.SkyLevel ------------------------------------------------------------------------------

    @Override public int level(Player p) {
        if (!Prog.ready(p)) return 0;
        return PMath.level(Prog.level(p).xp, perLevel());
    }

    @Override public double xp(Player p) {
        if (!Prog.ready(p)) return 0;
        return Prog.level(p).xp;
    }

    @Override public void award(Player p, String key, int xp, String reason) {
        if (p == null || key == null || key.isBlank() || xp <= 0) return;
        if (!Prog.ready(p)) {
            pending.computeIfAbsent(p.getUniqueId(), u -> new ArrayList<>()).add(new Pending(key, xp, reason));
            return;
        }
        PData.Level d = Prog.level(p);
        if (d.awards.containsKey(key)) return;
        int before = PMath.level(d.xp, perLevel());
        d.awards.put(key, xp);
        d.xp += xp;
        if (Prog.lvlAnnounce.get() && !PMath.quietAward(key, Prog.lvlQuietPrefixes.get())) {
            Msg.send(p, "&b+" + xp + " SkyBlock XP &7(" + (reason == null || reason.isBlank() ? "Progress" : reason) + ")");
        }
        int after = PMath.level(d.xp, perLevel());
        if (after > Math.max(before, d.announcedLevel)) {
            levelUp(p, Math.max(before, d.announcedLevel), after);
            d.announcedLevel = after;
            Sky.stats().invalidate(p);
            if (Prog.perksSys != null) Prog.perksSys.checkTome(p);
        }
    }

    /** Admin: take XP away (removes an award key) - never used by gameplay. */
    void revoke(Player p, String key) {
        PData.Level d = Prog.level(p);
        Integer v = d.awards.remove(key);
        if (v != null) d.xp = Math.max(0, d.xp - v);
        d.announcedLevel = Math.min(d.announcedLevel, PMath.level(d.xp, perLevel()));
        Sky.stats().invalidate(p);
    }

    void onReady(Player p) {
        PData.Level d = Prog.level(p);
        // re-add the total from the awards map: the single source of truth
        int sum = 0;
        for (int v : d.awards.values()) sum += v;
        d.xp = sum;
        d.announcedLevel = Math.max(d.announcedLevel, PMath.level(d.xp, perLevel()));
        List<Pending> q = pending.remove(p.getUniqueId());
        if (q != null) for (Pending a : q) award(p, a.key, a.xp, a.reason);
    }

    void onQuit(Player p) { pending.remove(p.getUniqueId()); hintedAt.remove(p.getUniqueId()); }

    private void levelUp(Player p, int from, int to) {
        List<String> body = new ArrayList<>();
        body.add("&a&lREWARDS");
        int levels = to - from;
        body.add("  &8+&c" + Text.commas(levels * Prog.lvlHealth.get()) + " " + Stat.HEALTH.label());
        int strGain = PMath.levelStrength(to, Prog.lvlStrengthEvery.get()) - PMath.levelStrength(from, Prog.lvlStrengthEvery.get());
        if (strGain > 0) body.add("  &8+&c" + strGain + " " + Stat.STRENGTH.label());
        for (Catalogs.Reward r : rewards()) {
            if (r.level > from && r.level <= to) body.add("  &a" + r.text);
        }
        for (Catalogs.Emblem e : Prog.levelsFile.emblems) {
            if (e.level > from && e.level <= to) body.add("  &7Emblem unlocked: &f" + e.symbol + " " + e.name);
        }
        if (!PMath.levelColor(from).equals(PMath.levelColor(to))) {
            body.add("  &7New level colour: " + PMath.levelBracket(to));
        }
        Msg.box(p, "&3&lSKYBLOCK LEVEL UP " + PMath.levelBracket(from) + " &8➜ " + PMath.levelBracket(to), body);
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
    }

    // ---- stats -----------------------------------------------------------------------------------------

    void contribute(Player p, StatMap into) {
        if (!Prog.ready(p)) return;
        int lvl = level(p);
        if (lvl <= 0) return;
        into.add(Stat.HEALTH, PMath.levelHealth(lvl, Prog.lvlHealth.get()));
        into.add(Stat.STRENGTH, PMath.levelStrength(lvl, Prog.lvlStrengthEvery.get()));
    }

    // ---- requirement "sblevel:<n>" -------------------------------------------------------------------------

    /**
     * The player whose "sblevel" check just FAILED on this thread, and the level they were short of. Core's
     * {@code checkOrTell} runs check -> describe -> "Requires SkyBlock Level 7!" back to back, and describe has
     * no player argument; this is how describe knows who is being refused. Cleared by every check and describe.
     */
    private final ThreadLocal<Object[]> lastFailed = new ThreadLocal<>();
    /** uuid -> System.currentTimeMillis() of the last "how to earn SkyBlock XP" hint (one per {@link #HINT_EVERY_MS}). */
    private final Map<UUID, Long> hintedAt = new HashMap<>();
    private static final long HINT_EVERY_MS = 15_000L;

    Requirements.Resolver resolver() {
        return new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                lastFailed.remove();
                if (parts.length < 2) return false;
                int need;
                try { need = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException e) { return false; }
                boolean ok = level(p) >= need;
                if (!ok && p != null) lastFailed.set(new Object[]{p, need});
                return ok;
            }
            @Override public String describe(String[] parts) {
                Object[] f = lastFailed.get();
                lastFailed.remove();
                // A bare "Requires SkyBlock Level 7!" was a dead end for a new player (playtest: the Bazaar agent
                // at Level 0). When this describe is building that refusal, follow it with how to get there.
                if (f != null && insideCheckOrTell()) hintNextTick((Player) f[0], (Integer) f[1]);
                return "&bSkyBlock Level " + (parts.length > 1 ? parts[1] : "?");
            }
        };
    }

    /** True when the current call came through {@code Requirements.checkOrTell} (i.e. the player is being refused
     *  in chat), not a menu or lore that merely shows the requirement. Matching the API method name keeps this
     *  independent of core internals; if it ever stops matching, the hint is simply not sent. */
    private static boolean insideCheckOrTell() {
        try {
            return StackWalker.getInstance().walk(s -> s.limit(16).anyMatch(f -> "checkOrTell".equals(f.getMethodName())));
        } catch (Throwable t) {
            return false;
        }
    }

    private void hintNextTick(Player p, int need) {
        long now = System.currentTimeMillis();
        Long last = hintedAt.get(p.getUniqueId());
        if (last != null && now - last < HINT_EVERY_MS) return;
        hintedAt.put(p.getUniqueId(), now);
        // one tick later so it lands UNDER the red "Requires ..." line rather than above it
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (p.isOnline() && Prog.ready(p)) Msg.send(p, howToEarn(p, need));
        }, 1L);
    }

    /** "You are Level 0 (55/100 XP) - 645 more SkyBlock XP to Level 7" plus where SkyBlock XP comes from. */
    List<String> howToEarn(Player p, int need) {
        int per = perLevel();
        double xp = xp(p);
        int lvl = level(p);
        List<String> out = new ArrayList<>();
        out.add("&7You are SkyBlock Level " + PMath.levelBracket(lvl) + " &8(" +Text.commasInt((long) PMath.intoLevel(xp, per))
                + "/" + per + " XP)&7 - &b" + Text.commasInt(PMath.xpToReach(xp, need, per)) + " more SkyBlock XP &7to reach &bLevel " + need + "&7.");
        // Wave-5 regression: a generic "levelling skills, growing collections" left the player (and the AI agents)
        // finishing objectives one by one at ~5 XP each; name the nearest concrete thresholds instead.
        List<String> tips = Prog.xpGuide == null ? List.of() : Prog.xpGuide.chat(p, 3);
        if (tips.isEmpty()) {
            out.add("&7Earn SkyBlock XP by finishing &eobjectives &8(/questlog)&7, levelling &eskills&7, growing &ecollections&7, "
                    + "finding &dFairy Souls &7and more - &e/sblevel &7lists every source.");
        } else {
            out.addAll(tips);
            out.add("&8More with &e/xpguide&8; &e/sblevel &8lists every source.");
        }
        return out;
    }

    /** {@code /xpguide}, {@code /sblevel guide} and the Leveling menu's guide item: the nearest SkyBlock XP, in chat. */
    void sendGuide(Player p) {
        if (!Prog.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        int per = perLevel();
        double xp = xp(p);
        int lvl = level(p);
        Msg.send(p, "&7SkyBlock Level " + PMath.levelBracket(lvl) + " &8(" + Text.commasInt((long) PMath.intoLevel(xp, per)) + "/" + per
                + " XP) &7- &b" + Text.commasInt(PMath.xpToReach(xp, lvl + 1, per)) + " &7to Level " + (lvl + 1) + ".");
        List<String> tips = Prog.xpGuide == null ? List.of() : Prog.xpGuide.chat(p, 8);
        if (tips.isEmpty()) Msg.send(p, "&7Nothing is close right now: level a skill, start a collection or find Fairy Souls (&e/sblevel&7).");
        else Msg.send(p, tips);
    }

    /** The Leveling menu's guide item (slot 13): the same list as {@code /xpguide}. */
    private org.bukkit.inventory.ItemStack guideIcon(Player viewer) {
        List<String> lore = new ArrayList<>();
        lore.add("&7The SkyBlock XP closest to you:");
        lore.add("&7the next skill levels, collection");
        lore.add("&7tiers, bestiary tiers, Fairy Soul");
        lore.add("&7exchange and your objective.");
        lore.add("");
        List<PMath.XpTip> tips = Prog.xpGuide == null || viewer == null ? List.of() : Prog.xpGuide.tips(viewer, 6);
        if (tips.isEmpty()) lore.add("&8Nothing close yet - start a skill!");
        for (PMath.XpTip t : tips) lore.add("&8▪ " + XpGuide.line(t));
        lore.add("");
        lore.add("&eClick to print it in chat!");
        return Icons.of(Material.COMPASS, "&aSkyBlock XP Guide", lore);
    }

    // ---- categories ------------------------------------------------------------------------------------------

    /** {category index, task index} of an award key; unknown prefixes land in Miscellaneous / Everything Else. */
    int[] classify(String key) {
        String pre = key.toLowerCase(Locale.ROOT);
        int i = pre.indexOf(':');
        if (i >= 0) pre = pre.substring(0, i);
        int[] hit = prefixIndex.get(pre);
        if (hit != null) return hit;
        // loose match: "skill_farming" -> "skill"
        for (Map.Entry<String, int[]> e : prefixIndex.entrySet()) {
            if (pre.startsWith(e.getKey() + "_") || pre.startsWith(e.getKey() + ".")) return e.getValue();
        }
        List<Catalogs.Category> cats = Prog.levelsFile.categories;
        for (int c = 0; c < cats.size(); c++) {
            if ("misc".equals(cats.get(c).id)) return new int[]{c, cats.get(c).tasks.size() - 1};
        }
        return new int[]{0, 0};
    }

    /** XP earned per category, and per task: [category][task]. */
    int[][] tally(PData.Level d) {
        List<Catalogs.Category> cats = Prog.levelsFile.categories;
        int[][] out = new int[cats.size()][];
        for (int c = 0; c < cats.size(); c++) out[c] = new int[cats.get(c).tasks.size()];
        for (Map.Entry<String, Integer> e : d.awards.entrySet()) {
            int[] at = classify(e.getKey());
            if (at[0] < out.length && at[1] < out[at[0]].length) out[at[0]][at[1]] += e.getValue();
        }
        return out;
    }

    // ---- menus -------------------------------------------------------------------------------------------

    @Override public void openMenu(Player p) {   // core-wave5: now Features.*.openMenu
        if (!Prog.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        buildMenu(p, Prog.level(p)).open(p);
    }

    Menu buildMenu(Player viewer, PData.Level d) {
        Menu m = new Menu("SkyBlock Leveling", 6).chrome();
        int lvl = PMath.level(d.xp, perLevel());
        double into = PMath.intoLevel(d.xp, perLevel());
        List<String> head = new ArrayList<>();
        head.add("&7Your SkyBlock Level: " + PMath.levelBracket(lvl));
        head.add("&7Total SkyBlock XP: &b" + Text.commasInt(d.xp));
        head.add("");
        head.add("&7Progress to Level " + (lvl + 1) + ":");
        head.add(Prog.bar(into, perLevel()));
        head.add("");
        head.add("&7Every level grants &c+" + Text.commas(Prog.lvlHealth.get()) + " " + Stat.HEALTH.label());
        head.add("&7and every &a" + Prog.lvlStrengthEvery.get() + " &7levels &c+1 " + Stat.STRENGTH.label() + "&7.");
        head.add("");
        head.add("&7Earn SkyBlock XP by doing things for the");
        head.add("&7first time: levelling skills, filling");
        head.add("&7collections, crafting minions and more.");
        m.set(4, Icons.glow(Icons.of(Material.EXPERIENCE_BOTTLE, "&aYour SkyBlock Level", head)));
        m.set(13, guideIcon(viewer), c -> { c.player().closeInventory(); sendGuide(c.player()); });

        List<Catalogs.Category> cats = Prog.levelsFile.categories;
        int[][] tally = tally(d);
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 31};
        for (int c = 0; c < cats.size() && c < slots.length; c++) {
            Catalogs.Category cat = cats.get(c);
            int earned = 0;
            for (int v : tally[c]) earned += v;
            int max = categoryMax(cat);
            List<String> lore = new ArrayList<>();
            lore.add("&7Progress: " + Prog.pct(earned, max));
            lore.add(Prog.bar(earned, max));
            lore.add("");
            for (int t = 0; t < cat.tasks.size(); t++) {
                Catalogs.Task task = cat.tasks.get(t);
                int tmax = taskMax(cat, task);
                String cap = tmax > 0 ? "&7/&b" + Text.commasInt(tmax) : "";
                // the numbers are SkyBlock XP, not counts: "Fairy Souls 0/530" after finding a soul read as broken
                String note = taskNote(viewer, task);
                lore.add("&8▪ &7" + task.name + ": &b" + Text.commasInt(tally[c][t]) + cap + " &8XP" + (note == null ? "" : " &8(" + note + "&8)"));
            }
            lore.add("");
            lore.add("&eClick to view tasks!");
            final int ci = c;
            m.set(slots[c], Icons.of(Prog.mat(cat.icon, Material.PAPER), "&a" + cat.name, lore),
                    click -> buildCategory(click.player(), Prog.level(click.player()), ci).open(click.player()));
        }

        // rewards road
        List<String> rl = new ArrayList<>();
        rl.add("&7Features and bonuses you unlock");
        rl.add("&7by raising your SkyBlock Level.");
        rl.add("");
        int shown = 0;
        for (Catalogs.Reward r : rewards()) {
            if (shown >= 8) break;
            if (r.level > lvl || shown < 3) {
                rl.add((r.level <= lvl ? "&a✔ " : "&c✖ ") + "&7Level " + r.level + ": &f" + r.text);
                shown++;
            }
        }
        rl.add("");
        rl.add("&eClick to view all rewards!");
        m.set(38, Icons.of(Material.CHEST, "&aLeveling Rewards", rl), c -> buildRewards(c.player(), Prog.level(c.player())).open(c.player()));

        // emblems
        Catalogs.Emblem sel = emblem(d.emblem);
        List<String> el = new ArrayList<>();
        el.add("&7Emblems are symbols you unlock by");
        el.add("&7levelling up. Pick one to show it");
        el.add("&7next to your level.");
        el.add("");
        el.add("&7Selected: " + (sel == null ? "&cNone" : "&f" + sel.symbol + " &a" + sel.name));
        el.add("");
        el.add("&eClick to choose!");
        m.set(40, Icons.of(Material.NAME_TAG, "&aEmblems", el), c -> buildEmblems(c.player(), 0).open(c.player()));

        // colour guide
        List<String> cl = new ArrayList<>();
        cl.add("&7Your level's colour changes every");
        cl.add("&740 levels:");
        cl.add("");
        for (int l = 0; l <= 480; l += 40) {
            cl.add((lvl >= l ? "&a✔ " : "&8✖ ") + PMath.levelBracket(l) + (l == 0 ? " &7Level 1-39" : ""));
        }
        m.set(42, Icons.of(Material.PAINTING, "&aLevel Colours", cl));
        Prog.backToSkyMenu(m);
        return m;
    }

    int categoryMax(Catalogs.Category cat) {
        if ("story".equals(cat.id)) {
            int sum = 0;
            for (Catalogs.Task t : cat.tasks) sum += taskMax(cat, t);
            return Math.max(cat.max, sum);
        }
        return cat.max;
    }

    int taskMax(Catalogs.Category cat, Catalogs.Task task) {
        if ("story".equals(cat.id) && task.prefixes.contains("objective") && Prog.objectivesFile != null) {
            int max = Prog.objectivesFile.chain.size() * Prog.levelsFile.objectiveXp;
            // the task also files Island Guides (island_guide:) and the hub's District Guides (hub_guide:, 5 each)
            if (Prog.guidesFile != null) for (Catalogs.IslandGuide g : Prog.guidesFile.guides) max += Math.max(0, g.sbxp);
            if (Prog.objectives != null) max += Prog.objectives.guideNames().size() * 5;
            return max;
        }
        return task.max;
    }

    Menu buildCategory(Player viewer, PData.Level d, int ci) {
        Catalogs.Category cat = Prog.levelsFile.categories.get(ci);
        Menu m = new Menu(cat.name, 6).chrome();
        int[][] tally = tally(d);
        int earned = 0;
        for (int v : tally[ci]) earned += v;
        int max = categoryMax(cat);
        m.set(4, Icons.of(Prog.mat(cat.icon, Material.PAPER), "&a" + cat.name,
                "&7Progress: " + Prog.pct(earned, max), Prog.bar(earned, max)));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int t = 0; t < cat.tasks.size() && t < slots.length; t++) {
            Catalogs.Task task = cat.tasks.get(t);
            int got = tally[ci][t];
            int tmax = taskMax(cat, task);
            List<String> lore = new ArrayList<>();
            if (tmax > 0) {
                lore.add("&7SkyBlock XP: " + Prog.pct(got, tmax));
                lore.add(Prog.bar(got, tmax));
            } else {
                lore.add("&7SkyBlock XP earned: &b" + Text.commasInt(got));
            }
            lore.add("");
            lore.addAll(taskHint(task));
            List<String> status = taskStatus(viewer, task);
            if (!status.isEmpty()) { lore.add(""); lore.addAll(status); }
            boolean done = tmax > 0 && got >= tmax;
            Material icon = done ? Material.LIME_DYE : got > 0 ? Material.YELLOW_DYE : Material.GRAY_DYE;
            m.set(slots[t], Icons.of(icon, (done ? "&a" : "&e") + task.name, lore));
        }
        m.back("SkyBlock Leveling", () -> buildMenu(viewer, Prog.level(viewer)));
        return m;
    }

    private static List<String> taskHint(Catalogs.Task task) {
        String n = task.name.toLowerCase(Locale.ROOT);
        String hint;
        if (n.contains("skill level")) hint = "Level up any skill. Early levels give 5 XP, later ones up to 30.";
        else if (n.contains("museum")) hint = "Donate items to the Museum in the Hub.";
        else if (n.contains("fairy")) hint = "Find Fairy Souls and trade them at the fairy, 10 XP per exchange.";
        else if (n.contains("collection")) hint = "Every collection tier you reach gives 4 XP.";
        else if (n.contains("minion")) hint = "Craft new minion tiers.";
        else if (n.contains("bestiary")) hint = "Kill mobs: every bestiary tier gives 1 XP, every milestone 10.";
        else if (n.contains("objective")) hint = "Follow the objectives on your sidebar, 5 XP each.";
        else if (n.contains("slayer")) hint = "Level up your slayers.";
        else if (task.prefixes.contains("scroll")) hint = "Read a Travel Scroll you have not read before: " + Prog.lvlScrollXp.get()
                + " XP each. Walking into a district also unlocks its fast travel, but only scrolls give XP.";
        else hint = "Complete this kind of task for the first time.";
        return Text.wrap("&7" + hint, 34);
    }

    /** Short live note after a task in the main menu, or null. */
    private String taskNote(Player viewer, Catalogs.Task task) {
        if (!Prog.ready(viewer)) return null;
        if (task.prefixes.contains("fairy") && Prog.fairy != null) {
            int found = Prog.fairy(viewer).found.size();
            return found == 0 ? null : found + " soul" + (found == 1 ? "" : "s") + " found";
        }
        if (task.prefixes.contains("scroll")) return "Travel Scrolls only";
        return null;
    }

    /** Live state under a task in its category page: what has been done that is not (yet) XP. */
    private List<String> taskStatus(Player viewer, Catalogs.Task task) {
        List<String> out = new ArrayList<>();
        if (!Prog.ready(viewer)) return out;
        if (task.prefixes.contains("fairy") && Prog.fairy != null) {
            PData.Fairy f = Prog.fairy(viewer);
            int found = f.found.size();
            int ready = Math.max(0, found - f.exchanged * 5);
            out.add("&7Souls found: &d" + found + "&7/&d" + Prog.fairy.total());
            out.add("&7Not yet exchanged: &d" + ready);
            out.add("&7SkyBlock XP is paid per exchange of");
            out.add("&75 souls at " + FairySouls.NPC_NAME + " &7(Hub).");
            if (PMath.fairyExchangesAvailable(found, f.exchanged) > 0) out.add("&aYou can exchange souls now!");
        } else if (task.prefixes.contains("scroll")) {
            int read = 0;
            for (String k : Prog.level(viewer).awards.keySet()) if (classifyTask(k) == task) read++;
            out.add("&7Travel Scrolls read: &b" + read);
        }
        return out;
    }

    private Catalogs.Task classifyTask(String key) {
        int[] at = classify(key);
        List<Catalogs.Category> cats = Prog.levelsFile.categories;
        if (at[0] >= cats.size() || at[1] >= cats.get(at[0]).tasks.size()) return null;
        return cats.get(at[0]).tasks.get(at[1]);
    }

    /** One-time SkyBlock XP for reading a Travel Scroll (key {@code scroll:<ITEM_ID>}), filed under Fast Travel Unlocked. */
    void awardScroll(Player p, String itemId, String name) {
        int xp = Prog.lvlScrollXp.get();
        if (xp <= 0 || itemId == null) return;
        award(p, "scroll:" + itemId, xp, "Travel Scroll: " + (name == null ? Text.titleCase(itemId) : name));
    }

    /**
     * The level rewards as they apply NOW: a reward tied to a feature's gate dial is listed at the level that dial
     * asks for (hidden when the gate is open to everyone), then sorted by level. Read at use time so a live
     * {@code /sb dial set} moves it at once.
     */
    static List<Catalogs.Reward> rewards() {
        List<Catalogs.Reward> out = new ArrayList<>();
        for (Catalogs.Reward r : Prog.levelsFile.rewards) {
            if (r.dial == null || r.dial.isBlank()) { out.add(r); continue; }
            String raw;
            try {
                raw = Sky.dials().text(r.dial, "sblevel:" + r.level, "Requirement to use: " + r.text).get();
            } catch (RuntimeException e) {
                raw = "sblevel:" + r.level;
            }
            int lvl = PMath.gateLevel(raw, r.level);
            if (lvl > 0) out.add(new Catalogs.Reward(lvl, r.text, r.dial));
        }
        out.sort(java.util.Comparator.comparingInt(x -> x.level));
        return out;
    }

    Menu buildRewards(Player viewer, PData.Level d) {
        int lvl = PMath.level(d.xp, perLevel());
        List<Catalogs.Reward> list = rewards();
        return Sky.menus().paged("Leveling Rewards", list, r -> {
            boolean got = lvl >= r.level;
            return Icons.of(got ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                    (got ? "&a" : "&c") + "Level " + r.level,
                    "&f" + r.text, "", got ? "&aUNLOCKED" : "&cReach SkyBlock Level " + r.level + "!");
        }, null, page -> page.back("SkyBlock Leveling", () -> buildMenu(viewer, Prog.level(viewer))), 0);
    }

    Catalogs.Emblem emblem(String id) {
        if (id == null) return null;
        for (Catalogs.Emblem e : Prog.levelsFile.emblems) if (e.id.equals(id)) return e;
        return null;
    }

    /** The selected emblem's symbol for display ("" when none or no longer unlocked). */
    @Override
    public String emblem(Player p) {
        String sym = emblemSymbol(p);
        if (sym.isEmpty()) return "";
        return PMath.levelColor(PMath.level(Prog.level(p).xp, perLevel())) + sym;
    }

    String emblemSymbol(Player p) {
        if (!Prog.ready(p)) return "";
        PData.Level d = Prog.level(p);
        Catalogs.Emblem e = emblem(d.emblem);
        if (e == null || PMath.level(d.xp, perLevel()) < e.level) return "";
        return e.symbol;
    }

    Menu buildEmblems(Player viewer, int page) {
        List<Catalogs.Emblem> list = new ArrayList<>(Prog.levelsFile.emblems);
        return Sky.menus().paged("Emblems", list, e -> {
            PData.Level d = Prog.level(viewer);
            int lvl = PMath.level(d.xp, perLevel());
            boolean ok = lvl >= e.level;
            boolean selected = e.id.equals(d.emblem);
            List<String> lore = new ArrayList<>();
            lore.add("&7Preview: " + PMath.levelBracket(lvl) + " &f" + viewer.getName() + " " + PMath.levelColor(lvl) + e.symbol);
            lore.add("");
            if (!ok) lore.add("&cRequires SkyBlock Level " + e.level + "!");
            else if (selected) { lore.add("&aSELECTED"); lore.add("&eClick to remove!"); }
            else lore.add("&eClick to select!");
            Material icon = ok ? (selected ? Material.LIME_DYE : Material.NAME_TAG) : Material.GRAY_DYE;
            return Icons.of(icon, (ok ? "&a" : "&c") + e.symbol + " " + e.name, lore);
        }, pc -> {
            Player p = pc.click().player();
            if (!Prog.ready(p)) return;
            PData.Level d = Prog.level(p);
            Catalogs.Emblem e = pc.entry();
            if (PMath.level(d.xp, perLevel()) < e.level) { Msg.error(p, "You need SkyBlock Level " + e.level + " for that emblem."); return; }
            d.emblem = e.id.equals(d.emblem) ? null : e.id;
            Msg.success(p, d.emblem == null ? "Emblem removed." : "Emblem set to " + e.symbol + " " + e.name + "!");
            buildEmblems(p, pc.page()).open(p);
        }, pg -> pg.back("SkyBlock Leveling", () -> buildMenu(viewer, Prog.level(viewer))), page);
    }

    /** Award keys of a player, grouped for the admin "info" output. */
    Map<String, Integer> awardsByCategory(PData.Level d) {
        Map<String, Integer> out = new LinkedHashMap<>();
        int[][] t = tally(d);
        List<Catalogs.Category> cats = Prog.levelsFile.categories;
        for (int c = 0; c < cats.size(); c++) {
            int s = 0;
            for (int v : t[c]) s += v;
            out.put(cats.get(c).name, s);
        }
        return out;
    }
}
