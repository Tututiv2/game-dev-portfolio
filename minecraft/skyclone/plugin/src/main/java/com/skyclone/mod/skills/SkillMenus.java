package com.skyclone.mod.skills;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * "Your Skills" and the per-skill level road ("Farming Skill"), in the genre's layout
 * (research 08 §11.3). Menus are built from a {@link View} snapshot so a self-test can build them
 * for a fake profile.
 */
final class SkillMenus {

    /** A copy of one player's skill state. */
    record View(EnumMap<SkillType, Double> xp, EnumMap<SkillType, Integer> caps, boolean popups, int catacombs) {
        static View empty(SkillServiceImpl svc) {
            EnumMap<SkillType, Double> xp = new EnumMap<>(SkillType.class);
            EnumMap<SkillType, Integer> caps = new EnumMap<>(SkillType.class);
            for (SkillType s : SkillType.values()) { xp.put(s, 0.0); caps.put(s, svc.defaultCap(s)); }
            return new View(xp, caps, true, 0);
        }
    }

    /** Slots in "Your Skills" (research 08 §11.3; Hunting is not a skill here, Dungeoneering takes 33). */
    static final Map<SkillType, Integer> SLOTS = new EnumMap<>(Map.ofEntries(
            Map.entry(SkillType.FARMING, 19), Map.entry(SkillType.MINING, 20), Map.entry(SkillType.COMBAT, 21),
            Map.entry(SkillType.FORAGING, 22), Map.entry(SkillType.FISHING, 23), Map.entry(SkillType.ENCHANTING, 24),
            Map.entry(SkillType.ALCHEMY, 25), Map.entry(SkillType.CARPENTRY, 29), Map.entry(SkillType.RUNECRAFTING, 30),
            Map.entry(SkillType.SOCIAL, 31), Map.entry(SkillType.TAMING, 32),
            Map.entry(SkillType.HUNTING, 34)));   // core-wave5: Hunting joined SkillType
    static final int DUNGEONEERING_SLOT = 33;
    static final int POPUP_SLOT = 50;

    /** The 25-level zig-zag road (research 08 §11.3 clone default). */
    static final int[] SNAKE = {9, 18, 27, 28, 29, 20, 11, 2, 3, 4, 13, 22, 31, 32, 33, 24, 15, 6, 7, 8, 17, 26, 35, 44, 53};
    static final int PER_PAGE = SNAKE.length;

    private final SkillServiceImpl svc;

    SkillMenus(SkillServiceImpl svc) { this.svc = svc; }

    /** Opens the Runic Pedestal (set by the module once runes are loaded); null = no button. */
    java.util.function.Consumer<Player> pedestal;
    /** Opens the Pet Trainer (wave 5); null = no button. */
    java.util.function.Consumer<Player> trainer;

    static Material icon(SkillType s) {
        Material m = Material.matchMaterial(s.icon);
        if (m != null && m.isItem()) return m;
        return switch (s) {
            case TAMING -> Material.BONE;
            case FORAGING -> Material.JUNGLE_SAPLING;
            default -> Material.BOOK;
        };
    }

    // ---- Your Skills ---------------------------------------------------------------------------

    Menu yourSkills(Player p) { return yourSkills(svc.view(p)); }

    Menu yourSkills(View v) {
        Menu m = new Menu("Your Skills", 6).chrome();
        double avg = average(v);
        m.set(4, Icons.of(Material.DIAMOND_SWORD, "&aYour Skills",
                "&7View your progress in every skill",
                "&7and the rewards still ahead.",
                "",
                "&6" + SkillText.fmt(Math.floor(avg * 10) / 10.0) + " Skill Avg."));
        for (Map.Entry<SkillType, Integer> e : SLOTS.entrySet()) {
            SkillType s = e.getKey();
            List<String> lore = new ArrayList<>(summaryLore(v, s, true));
            lore.add("");
            lore.add("&eClick to view!");
            int lvl = level(v, s);
            ItemStack it = Icons.of(icon(s), "&a" + SkillText.name(s, lvl), lore);
            m.set(e.getValue(), it, c -> road(c.player(), s, pageOf(level(svc.view(c.player()), s))).open(c.player()));
        }
        m.set(DUNGEONEERING_SLOT, Icons.of(Material.WITHER_SKELETON_SKULL, "&aDungeoneering",
                        "&7Your Catacombs level is tracked by",
                        "&7the dungeon system.",
                        "",
                        "&7Catacombs Level: &e" + v.catacombs(),
                        "",
                        "&eClick to view!"),
                c -> {
                    Player pl = c.player();
                    if (!Sky.commands().run(pl, "catacombs") && !Sky.commands().run(pl, "dungeons"))
                        Msg.error(pl, "The dungeons are not open yet!");
                });
        m.set(POPUP_SLOT, Icons.of(v.popups() ? Material.LIME_DYE : Material.GRAY_DYE, "&aSkill XP Popups",
                        "&7Show &3+4 Farming (x/y) &7on your",
                        "&7action bar whenever you gain",
                        "&7skill XP.",
                        "",
                        "&7Currently: " + (v.popups() ? "&aON" : "&cOFF"),
                        "",
                        "&eClick to toggle!"),
                c -> {
                    Player pl = c.player();
                    if (!svc.ready(pl)) return;
                    SkillData d = svc.data(pl);
                    d.xpPopups = !d.xpPopups;
                    Msg.sound(pl, Sound.UI_BUTTON_CLICK, 1f);
                    yourSkills(pl).open(pl);
                });
        m.set(48, Icons.back("SkyBlock Menu"), c -> {
            if (Sky.has(Features.SkyMenu.class)) Sky.service(Features.SkyMenu.class).open(c.player());
            else c.player().closeInventory();
        });
        return m;
    }

    // ---- one skill's road -------------------------------------------------------------------------

    static int pageOf(int level) { return Math.max(0, level) / PER_PAGE; }

    Menu road(Player p, SkillType s, int page) { return road(svc.view(p), s, page); }

    Menu road(View v, SkillType s, int page) {
        SkillTable t = svc.table;
        int max = t.maxLevel(s);
        int pages = (max + PER_PAGE - 1) / PER_PAGE;
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu m = new Menu(s.display + " Skill", 6).chrome();
        int lvl = level(v, s);
        int cap = v.caps().get(s);

        List<String> head = new ArrayList<>(summaryLore(v, s, false));
        SkillTable.Spec spec = t.spec(s);
        if (spec.perkName != null && lvl > 0) {
            head.add("");
            head.add("&e" + spec.perkName + " " + Text.roman(lvl) + "&7:");
            double ps = svc.dials.perkScale.get();
            head.addAll(SkillText.perkLines(spec, 0, t.perk(s, lvl) * ps, 0, t.perk2(s, lvl) * ps, " "));
        }
        m.set(0, Icons.of(icon(s), "&a" + SkillText.name(s, lvl), head));

        double ps = svc.dials.perkScale.get();
        double cs = svc.dials.coinRewards.get() ? svc.dials.coinMultiplier.get() : 0;
        for (int i = 0; i < PER_PAGE; i++) {
            int L = pg * PER_PAGE + i + 1;
            if (L > max) break;
            m.set(SNAKE[i], levelIcon(v, s, L, lvl, cap, cs, ps));
        }

        if (pg > 0) m.set(45, Icons.prevPage(pg + 1), c -> road(c.player(), s, pg - 1).open(c.player()));
        if (pg < pages - 1) m.set(50, Icons.nextPage(pg + 1), c -> road(c.player(), s, pg + 1).open(c.player()));
        if (cap < max || !spec.capHint.isEmpty()) {
            List<String> lore = new ArrayList<>();
            lore.add("&7Your cap: &e" + cap + "&7/&e" + max);
            if (cap < max) {
                lore.add("");
                lore.addAll(spec.capHint.isEmpty() ? List.of("&7Higher levels must be unlocked.") : spec.capHint);
            } else {
                lore.add("");
                lore.add("&aEvery level is unlocked!");
            }
            m.set(51, Icons.of(Material.GOLD_INGOT, "&6" + s.display + " Level Cap", lore));
        }
        m.set(48, Icons.back("Your Skills"), c -> yourSkills(c.player()).open(c.player()));
        if (s == SkillType.RUNECRAFTING && pedestal != null && pages == 1) {
            m.set(50, Icons.of(Material.END_PORTAL_FRAME, "&5Runic Pedestal",
                    "&7Apply runes to your gear, or fuse",
                    "&7two identical runes into a stronger",
                    "&7one for Runecrafting XP.",
                    "",
                    "&eClick to open!"), c -> pedestal.accept(c.player()));
        }
        // wave 5: the roads point at the things their unlock lines name
        if (s == SkillType.TAMING && trainer != null) {
            m.set(46, Icons.of(Material.LEAD, "&aPet Trainer",
                    "&7Leave your pet for a training session",
                    "&7and it comes back with pet XP. Your",
                    "&7Taming level unlocks the sessions.",
                    "",
                    "&eClick to open!"), c -> trainer.accept(c.player()));
        }
        if (s == SkillType.CARPENTRY) {
            m.set(46, Icons.of(Material.CRAFTING_TABLE, "&aFurniture",
                    "&7Every piece of furniture your",
                    "&7Carpentry level has unlocked, and",
                    "&7its recipe.",
                    "",
                    "&eClick to open!"), c -> {
                        if (!Sky.commands().run(c.player(), "furniture")) Msg.error(c.player(), "Furniture is not available right now.");
                    });
        }
        return m;
    }

    private ItemStack levelIcon(View v, SkillType s, int L, int lvl, int cap, double coinScale, double perkScale) {
        SkillTable t = svc.table;
        List<String> lore = new ArrayList<>();
        Material mat;
        String name;
        List<String> rewards = SkillText.levelRewards(t, s, L, coinScale, perkScale, "  ");
        if (L <= lvl) {
            mat = Material.LIME_STAINED_GLASS_PANE;
            name = "&a" + SkillText.name(s, L);
            lore.add("&7Rewards:");
            lore.addAll(rewards);
            lore.add("");
            lore.add("&a&lUNLOCKED");
        } else if (L > cap) {
            mat = Material.RED_STAINED_GLASS_PANE;
            name = "&c" + SkillText.name(s, L);
            lore.add("&7Rewards:");
            lore.addAll(rewards);
            lore.add("");
            lore.add("&cAbove your level cap (&e" + cap + "&c).");
            lore.addAll(t.spec(s).capHint);
        } else if (L == lvl + 1) {
            mat = Material.YELLOW_STAINED_GLASS_PANE;
            name = "&e" + SkillText.name(s, L);
            double x = v.xp().get(s);
            double prog = t.progress(s, x, lvl), need = t.needed(s, lvl);
            lore.add("&7Progress: &e" + pct(prog, need) + "%");
            lore.add(Text.bar(need <= 0 ? 1 : prog / need, 20) + " &e" + Text.commasInt(prog) + "&6/&e" + Text.shortNum(need));
            lore.add("");
            lore.add("&7Rewards:");
            lore.addAll(rewards);
        } else {
            mat = Material.RED_STAINED_GLASS_PANE;
            name = "&c" + SkillText.name(s, L);
            lore.add("&7Rewards:");
            lore.addAll(rewards);
            lore.add("");
            lore.add("&7Requires &e" + Text.commasInt(t.total(s, L)) + " &7total " + s.display + " XP.");
        }
        return Icons.amount(Icons.of(mat, name, lore), L);
    }

    // ---- shared lore ---------------------------------------------------------------------------

    /** The skill icon's lore: description, progress bar, next level's rewards (or max / cap notes). */
    List<String> summaryLore(View v, SkillType s, boolean nextRewards) {
        SkillTable t = svc.table;
        SkillTable.Spec spec = t.spec(s);
        List<String> lore = new ArrayList<>();
        for (String d : spec.description) lore.add("&7" + d);
        if (!svc.counted(s)) lore.add("&8(Not counted in your Skill Average)");
        lore.add("");
        int lvl = level(v, s);
        int cap = v.caps().get(s);
        int max = t.maxLevel(s);
        double x = v.xp().get(s);
        if (lvl < cap) {
            double prog = t.progress(s, x, lvl), need = t.needed(s, lvl);
            lore.add("&7Progress to Level " + Text.roman(lvl + 1) + ": &e" + pct(prog, need) + "%");
            lore.add(Text.bar(need <= 0 ? 1 : prog / need, 20) + " &e" + Text.commasInt(prog) + "&6/&e" + Text.shortNum(need));
            if (nextRewards) {
                lore.add("");
                lore.add("&7Level " + Text.roman(lvl + 1) + " Rewards:");
                double cs = svc.dials.coinRewards.get() ? svc.dials.coinMultiplier.get() : 0;
                lore.addAll(SkillText.levelRewards(t, s, lvl + 1, cs, svc.dials.perkScale.get(), "  "));
            }
        } else if (lvl < max) {
            lore.add("&cLevel cap reached! &7(&e" + cap + "&7/&e" + max + "&7)");
            lore.addAll(spec.capHint);
            lore.add("&7Banked XP: &e" + Text.commasInt(x));
        } else {
            lore.add("&6&lMAX LEVEL REACHED!");
            lore.add("&7Total XP: &e" + Text.commasInt(x));
        }
        return lore;
    }

    int level(View v, SkillType s) { return svc.table.levelFor(s, v.xp().get(s), v.caps().get(s)); }

    double average(View v) {
        EnumMap<SkillType, Integer> lv = new EnumMap<>(SkillType.class);
        for (SkillType s : SkillType.values()) lv.put(s, level(v, s));
        double avg = SkillTable.average(lv, svc::counted);
        if (svc.dials.averageFractional.get()) {
            int n = 0;
            double extra = 0;
            for (SkillType s : SkillType.values()) {
                if (!svc.counted(s)) continue;
                n++;
                int l = lv.get(s);
                double need = l >= v.caps().get(s) ? 0 : svc.table.needed(s, l);
                if (need > 0) extra += Math.min(1, svc.table.progress(s, v.xp().get(s), l) / need);
            }
            if (n > 0) avg += extra / n;
        }
        return avg;
    }

    static String pct(double prog, double need) {
        if (need <= 0) return "100";
        return SkillText.fmt(Math.floor(Math.min(1, prog / need) * 1000) / 10.0);
    }
}
