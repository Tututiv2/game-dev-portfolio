package com.skyclone.core.combat;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext.Kind;
import com.skyclone.api.command.CommandService;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /sb mob, /sb spawner, /sb damage, /sb combat, and the combat self-tests. */
final class CombatCommands {

    private CombatCommands() {}

    private static void say(CommandSender s, String legacy) { s.sendMessage(Text.c(legacy)); }

    static void register(CombatServiceImpl combat, MobServiceImpl mobs, LootServiceImpl loot) {
        CommandService cs = Sky.commands();

        // ---- /sb mob ------------------------------------------------------------------------
        cs.admin("mob", "<spawn <id> [count] | list [filter] | killall [radius] | info>",
                "Sky mobs: spawn, list types, remove nearby, inspect the one you look at",
                (s, a) -> mobCommand(s, a, mobs),
                (s, a) -> {
                    if (a.length <= 1) return filter(List.of("spawn", "list", "killall", "info"), a.length == 0 ? "" : a[0]);
                    if (a.length == 2 && a[0].equalsIgnoreCase("spawn")) {
                        List<String> ids = new ArrayList<>();
                        for (MobDef d : mobs.all()) ids.add(d.id);
                        return filter(ids, a[1]);
                    }
                    return List.of();
                });

        // ---- /sb spawner --------------------------------------------------------------------
        cs.admin("spawner", "<list [filter] | pause <ruleId>>", "Spawn rules: status, live/max; pause toggles one rule",
                (s, a) -> spawnerCommand(s, a, mobs),
                (s, a) -> {
                    if (a.length <= 1) return filter(List.of("list", "pause"), a.length == 0 ? "" : a[0]);
                    if (a.length == 2 && a[0].equalsIgnoreCase("pause")) {
                        List<String> ids = new ArrayList<>();
                        for (MobSpawner.RuleState rs : mobs.spawner.states()) ids.add(rs.rule.id);
                        return filter(ids, a[1]);
                    }
                    return List.of();
                });

        // ---- /sb damage ---------------------------------------------------------------------
        cs.admin("damage", "<amount> [mob|true|env]", "Hurt yourself through the real pipeline (defense applies unless true/env)",
                (s, a) -> {
                    if (!(s instanceof Player p)) { say(s, "&cPlayers only."); return; }
                    if (a.length < 1) { say(s, "&cUsage: /sb damage <amount> [mob|true|env]"); return; }
                    double amt;
                    try { amt = Double.parseDouble(a[0]); } catch (NumberFormatException e) { say(s, "&cNot a number: " + a[0]); return; }
                    if (!(amt > 0) || amt > 1e12) { say(s, "&cAmount must be between 0 and 1e12."); return; }
                    String k = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "mob";
                    double dealt = switch (k) {
                        case "true" -> combat.damagePlayer(p, amt, null, Kind.TRUE);
                        case "env", "environment" -> combat.environment(p, amt, "admin");
                        default -> combat.damagePlayer(p, amt, null, Kind.MOB);
                    };
                    say(s, "&7Took &c" + Text.commasInt(dealt) + " &7of &f" + Text.commas(amt) + " &7(" + k + ").");
                },
                (s, a) -> a.length == 2 ? filter(List.of("mob", "true", "env"), a[1]) : List.of());

        // ---- /sb combat ---------------------------------------------------------------------
        cs.admin("combat", "<debug | modifiers | stats>", "Combat tools: per-hit breakdown toggle, modifier list, live counters",
                (s, a) -> {
                    String sub = a.length == 0 ? "debug" : a[0].toLowerCase(Locale.ROOT);
                    switch (sub) {
                        case "debug" -> {
                            if (!(s instanceof Player p)) { say(s, "&cPlayers only."); return; }
                            UUID id = p.getUniqueId();
                            boolean on = !combat.debug.remove(id);
                            if (on) combat.debug.add(id);
                            say(s, on ? "&aCombat debug ON: every hit you deal prints its breakdown." : "&cCombat debug OFF.");
                        }
                        case "modifiers" -> {
                            List<String> ids = combat.modifierIds();
                            say(s, "&6Damage modifiers (" + ids.size() + ", run in this order):");
                            for (String m : ids) say(s, " &7" + m);
                        }
                        case "stats" -> say(s, "&7Live sky mobs: &f" + mobs.aliveCount() + " &7indicators: &f"
                                + (CombatCore.indicators == null ? 0 : CombatCore.indicators.size())
                                + " &7mob types: &f" + mobs.all().size() + " &7spawn rules: &f" + mobs.spawner.states().size());
                        default -> say(s, "&cUsage: /sb combat <debug|modifiers|stats>");
                    }
                },
                (s, a) -> a.length <= 1 ? filter(List.of("debug", "modifiers", "stats"), a.length == 0 ? "" : a[0]) : List.of());

        selfTests(mobs);
    }

    // ---- mob command ----------------------------------------------------------------------------

    private static void mobCommand(CommandSender s, String[] a, MobServiceImpl mobs) {
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "spawn" -> {
                if (!(s instanceof Player p)) { say(s, "&cPlayers only."); return; }
                if (a.length < 2) { say(s, "&cUsage: /sb mob spawn <id> [count]"); return; }
                MobDef def = mobs.def(a[1]);
                if (def == null) { say(s, "&cUnknown mob id: " + a[1]); return; }
                int count = 1;
                if (a.length > 2) {
                    try { count = Integer.parseInt(a[2]); } catch (NumberFormatException e) { say(s, "&cBad count."); return; }
                }
                count = Math.max(1, Math.min(100, count));
                Location at;
                Block b = p.getTargetBlockExact(32);
                at = b != null ? b.getLocation().add(0.5, 1, 0.5) : p.getLocation();
                int ok = 0;
                for (int i = 0; i < count; i++) if (mobs.spawn(def.id, at, null) != null) ok++;
                say(s, "&aSpawned &f" + ok + "x &c" + def.name + " &8(" + def.id + ")");
            }
            case "list" -> {
                String f = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                int shown = 0, total = 0;
                for (MobDef d : mobs.all()) {
                    if (!f.isEmpty() && !d.id.toLowerCase(Locale.ROOT).contains(f) && !Text.strip(d.name).toLowerCase(Locale.ROOT).contains(f)) continue;
                    total++;
                    if (shown >= 40) continue;
                    shown++;
                    say(s, " &7" + d.id + " &8- &c" + d.name + " &8Lv" + d.level + " &a" + Text.shortNum(d.health) + "&c❤ &7dmg &f"
                            + Text.shortNum(d.damage) + " &7alive &f" + mobs.live(d.id).size());
                }
                say(s, "&6" + total + " mob types" + (total > shown ? " &7(showing " + shown + ", narrow with a filter)" : ""));
            }
            case "killall" -> {
                double radius = -1;
                if (a.length > 1) {
                    try { radius = Double.parseDouble(a[1]); } catch (NumberFormatException e) { say(s, "&cBad radius."); return; }
                }
                Location center = s instanceof Player p ? p.getLocation() : null;
                int n = 0;
                for (LiveMob lm : new ArrayList<>(mobs.liveMobs())) {
                    if (radius > 0) {
                        if (center == null || lm.entity.getWorld() != center.getWorld()
                                || lm.entity.getLocation().distanceSquared(center) > radius * radius) continue;
                    } else if (center != null && lm.entity.getWorld() != center.getWorld()) {
                        continue;
                    }
                    mobs.remove(lm.entity);
                    n++;
                }
                say(s, "&aRemoved &f" + n + " &asky mobs (no loot).");
            }
            case "info" -> {
                if (!(s instanceof Player p)) { say(s, "&cPlayers only."); return; }
                Entity t = p.getTargetEntity(32);
                if (!(t instanceof LivingEntity le) || mobs.get(le) == null) { say(s, "&cLook at a sky mob (within 32 blocks)."); return; }
                LiveMob lm = mobs.get(le);
                MobDef d = lm.def;
                say(s, "&6" + d.id + " &8(" + d.entity + ") &7" + d.name + " &8Lv" + d.level);
                say(s, " &7Health &a" + Text.commas(lm.hp) + "&7/&a" + Text.commas(lm.max) + " &7Damage &c" + Text.commas(d.damage)
                        + " &7Defense &a" + Text.commas(d.defense) + " &7Speed &f" + d.speed);
                say(s, " &7Coins &6" + Text.commas(d.coins) + " &7Combat XP &3" + Text.commas(d.combatXp)
                        + " &7Family &f" + d.familyId() + (d.slayer != null ? " &7Slayer &f" + d.slayer : ""));
                say(s, " &7Flags &f" + d.flags + " &7Behaviours &f" + d.behaviours);
                say(s, " &7Owner &f" + (lm.owner == null ? "-" : lm.owner) + " &7Rule &f" + (lm.ruleId == null ? "-" : lm.ruleId)
                        + " &7Drops &f" + d.drops.size());
                for (DropDef dd : d.drops) {
                    say(s, "   &8- &f" + dd.item + " &7" + dd.min + "-" + dd.max + " @ " + Text.commas(dd.chance * 100) + "%"
                            + (dd.requirement != null ? " &8req " + dd.requirement : ""));
                }
            }
            default -> say(s, "&cUsage: /sb mob <spawn <id> [count] | list [filter] | killall [radius] | info>");
        }
    }

    private static void spawnerCommand(CommandSender s, String[] a, MobServiceImpl mobs) {
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                String f = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                int n = 0;
                for (MobSpawner.RuleState rs : mobs.spawner.states()) {
                    SpawnRule r = rs.rule;
                    if (!f.isEmpty() && !r.id.toLowerCase(Locale.ROOT).contains(f) && !r.island.contains(f)) continue;
                    if (++n > 50) continue;
                    String where = r.area != null ? "area " + r.area : "anchors " + r.anchors;
                    say(s, " " + (rs.paused ? "&c[P] " : rs.active ? "&a● " : "&7○ ") + "&f" + r.id + " &8" + r.island + "/" + where
                            + " &7" + r.mob + " &f" + rs.live + "&7/&f" + r.max + " &8(" + rs.pending.size() + " pending) &7" + rs.status);
                }
                say(s, "&6" + n + " spawn rules" + (CombatDials.SPAWNER_ENABLED.get() ? "" : " &c(spawner disabled by dial mob.spawner-enabled)"));
            }
            case "pause" -> {
                if (a.length < 2) { say(s, "&cUsage: /sb spawner pause <ruleId>"); return; }
                Boolean now = mobs.spawner.togglePause(a[1]);
                if (now == null) { say(s, "&cUnknown rule: " + a[1]); return; }
                say(s, now ? "&ePaused &f" + a[1] + " &7(live mobs stay; nothing respawns)." : "&aResumed &f" + a[1]);
            }
            default -> say(s, "&cUsage: /sb spawner <list [filter] | pause <ruleId>>");
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        if (out.size() > 100) return out.subList(0, 100);
        return out;
    }

    // ---- self-tests -------------------------------------------------------------------------------

    private static String expect(String what, double got, double want, double eps) {
        return Math.abs(got - want) <= eps ? null : what + ": got " + got + ", expected " + want;
    }

    private static String first(String... results) {
        for (String r : results) if (r != null) return r;
        return null;
    }

    static void selfTests(MobServiceImpl mobs) {
        final LootServiceImpl loot = CombatCore.loot;
        CommandService cs = Sky.commands();

        cs.selfTest("combat.formula-research-examples", () -> first(
                expect("bare fist", DamageMath.melee(0, 0, 0, 0, 1, false, 0), 5, 1e-9),
                expect("str 100 weapon 95", DamageMath.melee(95, 0, 100, 0, 1, false, 0), 200, 1e-9),
                expect("additive 100 x 4.5", DamageMath.melee(95, 0, 0, 350, 1, false, 0), 450, 1e-9),
                expect("combined 1,811", DamageMath.mobSide(DamageMath.melee(95, 0, 0, 350, 4.025, false, 0), 0, 0, 0), 1811, 0),
                expect("1000 into 2000 def", DamageMath.mobSide(1000, 0, 0, 2000), 47, 0),
                expect("apex dragon cap", DamageMath.mobSide(600_000_000, 0, 100_000, 2500), 2_311_153, 0),
                expect("crit 50cd", DamageMath.melee(0, 0, 0, 0, 1, true, 50), 7.5, 1e-9),
                expect("magic AOTD int 500", DamageMath.magic(12000, 500, 0.1, 0, 0, 1), 18000, 1e-6)));

        cs.selfTest("combat.ferocity-and-defense", () -> first(
                DamageMath.ferocityStrikes(250, 500, 0.49) == 3 ? null : "250 fer roll .49 should be 3 strikes",
                DamageMath.ferocityStrikes(250, 500, 0.50) == 2 ? null : "250 fer roll .50 should be 2 strikes",
                DamageMath.ferocityStrikes(900, 500, 0.0) == 5 ? null : "ferocity cap 500 should give 5",
                expect("100 def halves", DamageMath.reduce(100, 100), 50, 1e-9),
                expect("EHP 2000hp 900def", DamageMath.ehp(2000, 900), 20000, 1e-9),
                DamageMath.invulnTicks(10, 100, 100, 2) == 5 ? null : "100% attack speed should halve the window"));

        cs.selfTest("combat.indicator-and-nameplate-format", () -> {
            String crit = CombatFormat.indicator(1234, true, Kind.MELEE);
            if (!crit.equals("&f✧&f1&e,&62&c3&f4&e✧")) return "crit indicator was " + crit;
            String normal = CombatFormat.indicator(1234, false, Kind.MELEE);
            if (!normal.equals("&71,234")) return "normal indicator was " + normal;
            String plate = CombatFormat.nameplate("Zealot", 150, 12000, 13000, false, 100000, null);
            if (!plate.equals("&8[&7Lv150&8] &cZealot &a12,000&f/&a13,000&c❤")) return "nameplate was " + plate;
            String low = CombatFormat.nameplate("Zealot", 150, 2000, 13000, false, 100000, null);
            if (!low.contains("&c2,000")) return "low health should be red: " + low;
            return null;
        });

        cs.selfTest("loot.magic-find-and-labels", () -> {
            DropDef rare = new DropDef("X", 1, 1, 0.01);
            DropDef common = new DropDef("X", 1, 1, 0.5);
            String r = first(
                    expect("1% with 100 MF", LootMath.chance(rare, 100, 0.05, 900), 0.02, 1e-12),
                    expect("50% ignores MF", LootMath.chance(common, 100, 0.05, 900), 0.5, 1e-12),
                    expect("MF capped at 900", LootMath.chance(rare, 5000, 0.05, 900), 0.10, 1e-12));
            if (r != null) return r;
            if (new DropDef("X", 1, 1, 0.04).effectiveLabel() != DropDef.Label.RARE) return "4% should be RARE";
            if (new DropDef("X", 1, 1, 0.005).effectiveLabel() != DropDef.Label.VERY_RARE) return "0.5% should be VERY_RARE";
            if (new DropDef("X", 1, 1, 0.0005).effectiveLabel() != DropDef.Label.CRAZY_RARE) return "0.05% should be CRAZY_RARE";
            DropDef range = new DropDef("X", 2, 4, 1);
            if (LootMath.amount(range, 0) != 2 || LootMath.amount(range, 0.999) != 4) return "amount range 2..4 wrong";
            return null;
        });

        cs.selfTest("mob.drops-exist", () -> {
            List<String> miss = mobs.missingDropItems();
            if (miss.isEmpty()) return null;
            return miss.size() + " drop items not registered, e.g. " + miss.subList(0, Math.min(6, miss.size()));
        });

        cs.selfTest("mob.defs-valid", () -> {
            List<String> bad = new ArrayList<>();
            for (MobDef d : mobs.all()) {
                if (MobServiceImpl.parseType(d.entity) == null) bad.add(d.id + ": entity " + d.entity);
                for (String b : d.behaviours) if (!mobs.hasBehaviour(b)) bad.add(d.id + ": behaviour " + b);
                for (String slot : d.equipment.keySet()) if (MobServiceImpl.parseSlot(slot) == null) bad.add(d.id + ": slot " + slot);
                if (d.health <= 0 || d.damage < 0) bad.add(d.id + ": health/damage");
            }
            return bad.isEmpty() ? null : bad.size() + " problems, e.g. " + bad.subList(0, Math.min(6, bad.size()));
        });

        cs.selfTest("mob.spawn-rules-resolve", () -> {
            List<String> bad = new ArrayList<>();
            boolean worlds = Sky.has(WorldService.class);
            for (MobSpawner.RuleState rs : mobs.spawner.states()) {
                SpawnRule r = rs.rule;
                if (mobs.def(r.mob) == null) { bad.add(r.id + ": unknown mob " + r.mob); continue; }
                if (r.area == null && r.anchors.isEmpty()) { bad.add(r.id + ": no area and no anchors"); continue; }
                if (!worlds) continue;
                try {
                    if (Sky.worlds().island(r.island) == null) { bad.add(r.id + ": unknown island " + r.island); continue; }
                    Layout lay = Sky.worlds().layout(r.island);
                    if (lay == null) { bad.add(r.id + ": island " + r.island + " has no built layout"); continue; }
                    if (r.area != null && lay.area(r.area) == null) bad.add(r.id + ": area " + r.area + " missing in " + r.island);
                    for (String an : r.anchors) {
                        if (an.endsWith("*")) {
                            String pre = an.substring(0, an.length() - 1);
                            if (lay.anchors().keySet().stream().noneMatch(k -> k.startsWith(pre))) bad.add(r.id + ": no anchors " + an);
                        } else if (lay.anchor(an) == null) {
                            bad.add(r.id + ": anchor " + an + " missing");
                        }
                    }
                } catch (RuntimeException e) {
                    bad.add(r.id + ": " + e);
                }
            }
            return bad.isEmpty() ? null : bad.size() + " problems, e.g. " + bad.subList(0, Math.min(6, bad.size()));
        });

        // ---- wave 5 -----------------------------------------------------------------------------
        cs.selfTest("loot.magic-find-modifiers", () -> {
            String id = "__selftest_mf";
            MobDef probe = new MobDef();
            probe.id = "__selftest_mf_probe";
            try {
                loot.addMagicFindModifier(id, (pl, d) -> d != null && "__selftest_mf_probe".equals(d.id) ? 25.0 : 0.0);
                if (!loot.magicFindModifierIds().contains(id)) return "modifier was not registered";
                loot.addMagicFindModifier("__selftest_mf_throws", (pl, d) -> { throw new IllegalStateException("probe"); });
                for (Player p : Bukkit.getOnlinePlayers()) {
                    double base = loot.magicFind(p, null), with = loot.magicFind(p, probe);
                    if (Math.abs(with - base - 25) > 1e-9) return "per-target MF for " + p.getName() + ": " + base + " -> " + with + " (want +25; a throwing modifier must count as 0)";
                    break;
                }
                if (loot.magicFind(null, probe) != 0) return "null player should have 0 MF";
            } finally {
                loot.addMagicFindModifier(id, null);
                loot.addMagicFindModifier("__selftest_mf_throws", null);
            }
            if (loot.magicFindModifierIds().contains(id)) return "a null modifier did not remove it";
            return null;
        });

        cs.selfTest("mob.display-name-plate", () -> {
            String plate = CombatFormat.nameplate("&dSpecial Zealot", 55, 2000, 2000, false, 100000, null);
            if (!plate.contains("Special Zealot")) return "override name missing from plate: " + plate;
            if (!plate.contains("Lv55")) return "level missing from override plate: " + plate;
            return null;
        });

        cs.selfTest("combat.services-installed", () -> {
            if (Sky.combat() == null || Sky.mobs() == null || Sky.loot() == null) return "a combat-core service is missing";
            if (Bukkit.getPluginManager() == null) return "no plugin manager";
            return null;
        });
    }
}
