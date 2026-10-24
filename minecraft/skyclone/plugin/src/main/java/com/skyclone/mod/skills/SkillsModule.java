package com.skyclone.mod.skills;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The skills module: 11 skills with the reference XP curves, per-level stats, perks, coin and
 * SkyBlock XP rewards, level caps, Combat XP from kills, Social XP from island visits, the
 * "Your Skills" menus, the {@code skill:} requirement prefix, and the Skills tab widget.
 *
 * <p>Provides {@link SkillService}. Other modules grant their own skill's XP through it
 * (mining grants Mining XP, fishing Fishing XP...); this module grants Combat and Social.
 */
public final class SkillsModule implements GameModule, Listener {

    private SkillTable table;
    private SkillDials dials;
    private SkillServiceImpl svc;
    private SkillMenus menus;
    private SkillAdmin admin;

    @Override public String id() { return "skills"; }

    @Override public void enable() {
        table = loadTable();
        dials = new SkillDials(table);
        svc = new SkillServiceImpl(table, dials);
        menus = new SkillMenus(svc);
        svc.menus = menus;
        Sky.provide(SkillService.class, svc);

        var hideEnchants = Sky.dials().flag("skills.hide-missing-enchant-unlocks", true,
                "Enchanting level rewards skip enchants this game has not registered (true), or list them anyway (false)");
        SkillText.hideMissingEnchants = hideEnchants::get;

        Sky.stats().addProvider("skills", svc::contribute);
        Sky.combat().addModifier("skills_warrior", 150, this::warrior);
        Sky.requirements().register("skill", new SkillRequirement());
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::socialTick, 1200L, 1200L);

        Sky.sidebar().tabWidget("skills", 110, this::tabLines);

        Sky.commands().register(SkyCommand.of("skills", "Open your Skills menu").usage("[skill]")
                .category("General").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!svc.ready(p)) { Msg.error(p, "Your profile is still loading!"); return; }
                    if (a.length >= 1) {
                        SkillType t = SkillType.parse(a[0]);
                        if (t == null) { Msg.error(p, "Unknown skill. Try /skills farming"); return; }
                        menus.road(p, t, SkillMenus.pageOf(svc.level(p, t))).open(p);
                    } else svc.openMenu(p);
                })
                .tab((s, a) -> a.length == 1 ? skillNames(a[0], false) : List.of()));

        admin = new SkillAdmin(svc, table);
        Sky.commands().admin("skill", SkillAdmin.USAGE, "Skills: give XP, set levels/caps, inspect the table", admin::run, admin::complete);
        Sky.commands().admin("skills", SkillAdmin.USAGE, "Alias of /sb skill", admin::run, admin::complete);

        SkillSelfTests.register(svc, table, menus);

        // ---- Runecrafting: runes, the Runic Pedestal, Runic mobs (wave 3) ----
        // skills enables FIRST in ModuleList, so these definitions win over slayer's trophy fallbacks
        runes = new Runes(svc);
        runes.load();
        runes.registerItems();
        runes.start();
        RunicPedestal pedestal = new RunicPedestal(runes, svc, menus);
        menus.pedestal = pedestal::open;
        Sky.commands().register(SkyCommand.of("runes", "Open the Runic Pedestal: apply and fuse runes")
                .category("General").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (!svc.ready(p)) { Msg.error(p, "Your profile is still loading!"); return; }
                    pedestal.open(p);
                }));
        Sky.commands().admin("runes", Runes.USAGE, "Runes: give, list, make nearby mobs Runic", runes::admin, runes::complete);
        Sky.commands().selfTest("skills.runes-valid", runes::validate);
        this.pedestal = pedestal;

        // ---- wave 5: Pet Trainer, the two Hub NPCs, Runebook, furniture lines, Hunting in the average ----
        trainer = new PetTrainer(svc, menus);
        trainer.start();
        menus.trainer = trainer::open;
        admin.trainer = trainer;
        Sky.commands().register(SkyCommand.of("pettrainer", "Open the Pet Trainer: training sessions for your pet")
                .category("General").playerOnly()
                .run((s, a) -> trainer.open((Player) s)));
        npcs = new SkillNpcs();
        npcs.add(PEDESTAL_NPC, "Runic Pedestal", "npc.runic_pedestal", "npc.blacksmith", "runic_pedestal", "rune_pedestal", "runic pedestal", "runecraft", "dusk");
        npcs.add(PetTrainer.NPC_ID, "Pet Trainer", "npc.pet_trainer", "npc.pet_sitter", "pet_trainer", "pet trainer", "fann");
        Sky.commands().admin("skillnpc", SkillNpcs.USAGE, "Skills' Hub NPCs (Runic Pedestal, Pet Trainer): where they are, move them here, or back to auto",
                npcs::admin, (s, a) -> npcs.complete(a));
        Wave5Tests.register(svc, table, trainer, npcs);
    }

    static final String PEDESTAL_NPC = "skills_runic_pedestal";
    static final String PEDESTAL_NAME = "&5Runescribe Gloaming";

    private Runes runes;
    private RunicPedestal pedestal;
    private PetTrainer trainer;
    private SkillNpcs npcs;

    @Override public void postEnable() {
        // NPCs are decided here, once every module has registered its own (so we can stand aside for one)
        NpcDef ped = NpcDef.of(PEDESTAL_NPC, PEDESTAL_NAME, SkillNpcs.HUB, "npc.runic_pedestal").title("&5&lRUNIC PEDESTAL");
        ped.hand = "AMETHYST_SHARD";
        ped.lines.add("&7Apply and fuse runes");
        npcs.register(npcs.spots.get(0), ped, this::pedestalClick);
        NpcDef tr = NpcDef.of(PetTrainer.NPC_ID, PetTrainer.NPC_NAME, SkillNpcs.HUB, "npc.pet_trainer").title("&a&lPET TRAINER");
        tr.hand = "LEAD";
        tr.lines.add("&7Training sessions for your pet");
        npcs.register(npcs.spots.get(1), tr, trainer::npcClick);
        npcs.start();
    }

    private void pedestalClick(Player p) {
        if (!svc.ready(p)) return;
        SkillData d = svc.data(p);
        if (!d.metPedestal) {
            d.metPedestal = true;
            Sky.npcs().talk(p, PEDESTAL_NPC, List.of(
                    "Runes remember the hands that carry them. Set one on your blade and watch it answer.",
                    "Two of the same tier, laid side by side, will fuse into something stronger.",
                    "And if you meet a monster glowing purple, bring me what it drops."), () -> pedestal.open(p));
            return;
        }
        pedestal.open(p);
    }

    private static SkillTable loadTable() {
        try (InputStream in = Sky.plugin().getResource("data/skills/skills.json")) {
            if (in == null) throw new IllegalStateException("data/skills/skills.json missing from the jar");
            return SkillTable.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read data/skills/skills.json", e);
        }
    }

    static List<String> skillNames(String prefix, boolean withAll) {
        List<String> out = new ArrayList<>();
        String pre = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (SkillType t : SkillType.values()) {
            String n = t.name().toLowerCase(Locale.ROOT);
            if (n.startsWith(pre)) out.add(n);
        }
        if (withAll && "all".startsWith(pre)) out.add("all");
        return out;
    }

    // ---- Combat XP + perks -------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(SkyEvents.MobKill e) {
        Player k = e.killer;
        if (k == null || !dials.combatXp.get() || !(e.combatXp > 0) || !svc.ready(k)) return;
        if (!dials.combatXpPrivateIsland.get() && e.location != null) {
            String island = null;
            try { island = Sky.worlds().islandOf(e.location); } catch (RuntimeException ignored) { }
            if ("private_island".equals(island)) return;   // research 02 §2.0: island mobs give no Combat XP
        }
        svc.addXp(k, SkillType.COMBAT, e.combatXp, "mob:" + (e.def == null ? "?" : e.def.id));
    }

    /** Combat perk: +X% damage to mobs (additive, like the reference's "deal X% more damage to mobs"). */
    private void warrior(DamageContext ctx) {
        Player a = ctx.playerAttacker();
        if (a == null || ctx.victim instanceof Player || !dials.warrior.get()) return;
        switch (ctx.kind) {
            case MELEE, PROJECTILE -> { }
            case MAGIC -> { if (!dials.warriorMagic.get()) return; }
            default -> { return; }
        }
        if (!svc.ready(a)) return;
        int lvl = svc.level(a, SkillType.COMBAT);
        if (lvl <= 0) return;
        ctx.additive += table.perk(SkillType.COMBAT, lvl) * dials.perkScale.get();
    }

    /** Enchanting perk: more vanilla experience orbs. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExp(PlayerExpChangeEvent e) {
        if (!dials.conjurer.get() || e.getAmount() <= 0 || !svc.ready(e.getPlayer())) return;
        int lvl = svc.level(e.getPlayer(), SkillType.ENCHANTING);
        if (lvl <= 0) return;
        double pct = table.perk(SkillType.ENCHANTING, lvl) * dials.perkScale.get();
        e.setAmount((int) Math.round(e.getAmount() * (1 + pct / 100.0)));
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        // a little later, so the SkyBlock Level module has set itself up for this player too
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> svc.catchUp(p), 40L);
    }

    // ---- Social XP -----------------------------------------------------------------------------

    /** Every minute: visitors on someone else's private island earn Social XP, and so do their hosts. */
    private void socialTick() {
        if (!dials.socialXp.get() || !Sky.has(Features.PrivateIsland.class)) return;
        Features.PrivateIsland pi = Sky.service(Features.PrivateIsland.class);
        List<Player> onIslands = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!svc.ready(p)) continue;
            String island = null;
            try { island = Sky.worlds().islandOf(p.getLocation()); } catch (RuntimeException ignored) { }
            if ("private_island".equals(island)) onIslands.add(p);
        }
        for (Player host : onIslands) {
            if (!pi.isOnOwnIsland(host)) {
                svc.addXp(host, SkillType.SOCIAL, dials.socialVisitPerMinute.get(), "social:visit");
                continue;
            }
            boolean hosting = false;
            for (Player guest : onIslands) {
                if (guest == host || guest.getWorld() != host.getWorld() || pi.isOnOwnIsland(guest)) continue;
                if (!pi.canBuild(host, guest.getLocation())) continue;
                hosting = true;
                SkillData d = svc.data(host);
                String g = guest.getUniqueId().toString();
                if (!d.guests.contains(g)) {
                    if (d.guests.size() >= 1000) d.guests.remove(0);
                    d.guests.add(g);
                    svc.addXp(host, SkillType.SOCIAL, dials.socialUniqueGuest.get(), "social:new-guest");
                }
            }
            if (hosting) svc.addXp(host, SkillType.SOCIAL, dials.socialHostPerMinute.get(), "social:host");
        }
    }

    // ---- tab widget --------------------------------------------------------------------------

    private List<String> tabLines(Player p) {
        if (!dials.tabWidget.get() || !svc.ready(p)) return List.of();
        SkillData d = svc.data(p);
        List<String> out = new ArrayList<>();
        out.add("&e&lSkills:");
        SkillType last = d.lastSkill == null || d.lastSkill.isEmpty() ? null : SkillType.parse(d.lastSkill);
        if (last != null) {
            int lvl = svc.levelOf(d, last);
            String tail;
            if (lvl >= svc.capOf(d, last)) tail = lvl >= table.maxLevel(last) ? "&6MAX" : "&cCAPPED";
            else tail = "&a" + SkillMenus.pct(table.progress(last, SkillServiceImpl.rawXp(d, last), lvl), table.needed(last, lvl)) + "%";
            out.add(" &7" + last.display + " " + lvl + ": " + tail);
        }
        out.add(" &7Skill Average: &6" + SkillText.fmt(Math.floor(svc.averageOf(d) * 10) / 10.0));
        return out;
    }

    // ---- requirement prefix "skill" ----------------------------------------------------------

    /** {@code skill:<skill>:<level>} and {@code skill:average:<n>}. */
    private final class SkillRequirement implements Requirements.Resolver {
        @Override public boolean check(Player p, String[] parts) {
            if (parts.length < 3 || p == null) return false;
            double n;
            try { n = Double.parseDouble(parts[2]); } catch (NumberFormatException e) { return false; }
            if ("average".equalsIgnoreCase(parts[1])) return svc.average(p) >= n;
            SkillType t = SkillType.parse(parts[1]);
            return t != null && svc.level(p, t) >= n;
        }

        @Override public String describe(String[] parts) {
            if (parts.length < 3) return "&aan unknown skill level";
            if ("average".equalsIgnoreCase(parts[1])) return "&aSkill Average " + parts[2];
            SkillType t = SkillType.parse(parts[1]);
            return "&a" + (t == null ? Text.titleCase(parts[1]) : t.display) + " Skill " + parts[2];
        }
    }
}
