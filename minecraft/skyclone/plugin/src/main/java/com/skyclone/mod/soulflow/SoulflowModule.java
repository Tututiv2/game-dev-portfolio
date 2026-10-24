package com.skyclone.mod.soulflow;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Soulstream (reference: Soulflow) - the Enderman-slayer fuel.
 *
 * <ul>
 *   <li>A per-player pool of whole units (⸎), fed by internalizing Raw Soulstream (1) and Soulstream (160) - from the
 *       hand, the inventory or the sacks - and by any module through {@link Features.Soulflow#add}.</li>
 *   <li>{@link Features.Soulflow} for every consumer; this module ALSO charges the costs of other modules' abilities
 *       itself ({@link AbilityGate}): Phasewarp, Soul Howl, Judgement Beam, Gravity Well, Cell Sync, Life Tap.</li>
 *   <li>The three accessories (Pile / Battery / Supercell) turn Soulstream into Overflow Mana when you leave combat;
 *       Overflow pays ability mana once ordinary mana runs short.</li>
 *   <li>Combat drains: Handy Blood Chalice (+20 Ferocity), Pocket Espresso Machine (+100 Intelligence), Final
 *       Destination's sneaking bonus (2⸎ / 5s).</li>
 *   <li>Owns RAW_SOULFLOW, SOULFLOW (they were materials' fallback defs) and SOUL_ESOWARD (Soulguard Sprig).</li>
 * </ul>
 */
public final class SoulflowModule implements GameModule, Listener {

    private SoulflowConfig cfg;
    private SoulflowService svc;
    private AbilityGate gate;
    private SoulflowRuntime rt;
    private SoulflowMenu menu;
    private BukkitTask task;

    @Override public String id() { return "soulflow"; }

    @Override public void enable() {
        SoulflowDials.register();
        cfg = SoulflowConfig.read(Sky.plugin().getResource("data/soulflow/soulflow.json"));
        Sky.items().loadJson("soulflow", "items.json");

        svc = new SoulflowService(cfg);
        Sky.provide(Features.Soulflow.class, svc);
        gate = new AbilityGate(svc);
        rt = new SoulflowRuntime(svc);
        menu = new SoulflowMenu(svc);
        menu.runtime(rt);

        Bukkit.getPluginManager().registerEvents(gate, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(rt, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new SoulflowItems(svc, menu), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());

        Sky.abilities().register("soulflow_soulward", rt::soulward);
        Sky.combat().addModifier("soulflow_soulward_weak", 400, rt::modify);
        Sky.stats().addProvider("soulflow", rt::stats);

        Sky.vitals().actionBarSegment("soulflow", 60, this::actionBar);
        Sky.sidebar().tabWidget("soulflow", 155, this::tabLines);
        registerLore();
        registerCommands();
        SoulflowTests.register(this);
    }

    @Override public void postEnable() {
        task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { rt.tick(); } catch (RuntimeException e) { Sky.log().warning("[soulflow] tick failed: " + e); }
        }, 20L, 20L);
    }

    @Override public void disable() {
        if (task != null) task.cancel();
        task = null;
    }

    SoulflowConfig config() { return cfg; }
    SoulflowService service() { return svc; }
    SoulflowRuntime runtime() { return rt; }
    AbilityGate gate() { return gate; }
    SoulflowMenu menuForTests() { return menu; }

    // ---- HUD ------------------------------------------------------------------------------------------

    private String actionBar(Player p) {
        String d = svc.delta(p);
        SoulflowData data = svc.data(p);
        String ov = data != null && data.overflow >= 1 ? "&3" + Text.commasInt(data.overflow) + "ʬ" : null;
        if (d == null) return ov;
        return ov == null ? d : ov + "  " + d;
    }

    private List<String> tabLines(Player p) {
        SoulflowData d = svc.data(p);
        if (d == null) return List.of();
        SoulflowConfig.Tier t = svc.tier(p);
        if (t == null && d.pool <= 0 && d.overflow < 1) return List.of();
        List<String> l = new ArrayList<>();
        l.add("&3&lSoulstream:");
        l.add(" &7Internalized: &3" + SoulflowMath.units(d.pool));
        if (d.overflow >= 1 || t != null) l.add(" &7Overflow: &b" + Text.commasInt(d.overflow) + "ʬ");
        return l;
    }

    // ---- lore on other modules' items (pure functions of def + data) ---------------------------------

    private void registerLore() {
        Map<String, List<SoulflowConfig.Cost>> costItems = new HashMap<>();
        for (SoulflowConfig.Cost c : cfg.costs)
            for (String it : c.items) if (!"SOUL_ESOWARD".equals(it)) costItems.computeIfAbsent(it, k -> new ArrayList<>()).add(c);
        Set<String> tierItems = new HashSet<>();
        for (SoulflowConfig.Tier t : cfg.tiers) tierItems.add(t.item);
        Set<String> drainItems = new HashSet<>();
        Map<String, SoulflowConfig.Drain> drainSets = new HashMap<>();
        for (SoulflowConfig.Drain d : cfg.drains) {
            if (d.item != null) drainItems.add(d.item);
            if (d.set != null) drainSets.put(d.set, d);
        }

        Sky.items().addLoreContributor("soulflow", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) {
                if (def == null || def.id == null) return List.of();
                List<SoulflowConfig.Cost> cs = costItems.get(def.id);
                if (cs != null) {
                    List<String> out = new ArrayList<>();
                    for (SoulflowConfig.Cost c : cs) {
                        if (c.whenExtra != null && !extraPositive(data, c.whenExtra)) continue;
                        out.add("&8" + (c.label == null ? "Ability" : c.label) + " Soulstream Cost: &3"
                                + SoulflowDials.scaled(c.cost) + "⸎");
                    }
                    return out;
                }
                if (tierItems.contains(def.id)) {
                    return List.of("&7Abilities drain your internalized &3⸎&7.", "&8Manage it with &e/soulflow&8.");
                }
                if (drainItems.contains(def.id)) {
                    return List.of("&8Switch it on or off in &e/soulflow&8.");
                }
                SoulflowConfig.Drain set = def.setId == null ? null : drainSets.get(def.setId);
                if (set != null && (!set.ownerGated || SoulflowDials.OWNER_GATED_DRAINS.get())) {
                    return List.of("&8" + (set.label == null ? "Set bonus" : set.label) + " costs &3" + set.cost
                            + "⸎ &8per " + set.every + "s in combat" + (set.sneaking ? " while sneaking." : "."));
                }
                return List.of();
            }
        });
    }

    private static boolean extraPositive(ItemData data, String key) {
        try {
            var e = data == null || data.extra == null ? null : data.extra.get(key);
            return e != null && e.getAsDouble() > 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // ---- commands -----------------------------------------------------------------------------------

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("soulflow", "Your Soulstream: internalize it, toggle Overflow Mana and the drain accessories")
                .alias("soulstream").category("Combat").usage("[internalize|toggle <overflow|chalice|espresso>]").playerOnly()
                .run((s, a) -> playerCommand((Player) s, a))
                .tab((s, a) -> a.length <= 1 ? List.of("internalize", "toggle")
                        : a.length == 2 && "toggle".equalsIgnoreCase(a[0]) ? toggleIds() : List.of()));

        Sky.commands().admin("soulflow", "<player> [info|give|take|set|overflow|combat] [n|on|off]",
                "Soulstream: show a player's pool, change it, set Overflow Mana, or force combat state",
                this::admin, (s, a) -> a.length == 1 ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
                        : a.length == 2 ? List.of("info", "give", "take", "set", "overflow", "combat") : List.of());
    }

    private List<String> toggleIds() {
        List<String> l = new ArrayList<>();
        l.add("overflow");
        for (SoulflowConfig.Drain d : cfg.drains) if (d.toggle) l.add(d.id);
        return l;
    }

    private void playerCommand(Player p, String[] a) {
        if (a.length == 0) { menu.open(p); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "internalize" -> {
                String why = svc.internalizeBlock(p);
                if (why != null) { Msg.error(p, why); return; }
                long got = svc.internalizeInventory(p, SoulflowService.RAW, Integer.MAX_VALUE)
                        + svc.internalizeInventory(p, "SOULFLOW", Integer.MAX_VALUE);
                if (got <= 0) Msg.error(p, "You have no Soulstream to internalize.");
                else Msg.success(p, "Internalized &3+" + SoulflowMath.units(got) + " Soulstream&a!");
            }
            case "toggle" -> {
                if (a.length < 2 || !toggleIds().contains(a[1].toLowerCase(Locale.ROOT))) {
                    Msg.error(p, "Usage: /soulflow toggle <" + String.join("|", toggleIds()) + ">");
                    return;
                }
                String id = a[1].toLowerCase(Locale.ROOT);
                SoulflowConfig.Drain d = cfg.drain(id);
                boolean def = d == null || d.defaultOn;
                boolean now = !svc.toggle(p, id, def);
                svc.setToggle(p, id, now);
                Sky.stats().invalidate(p);
                Msg.send(p, "&7" + (d == null ? "Overflow Mana conversion" : Text.strip(SoulflowRuntime.drainName(d)))
                        + ": " + (now ? "&aOn" : "&cOff"));
            }
            default -> menu.open(p);
        }
    }

    private void admin(CommandSender s, String[] a) {
        if (a.length < 1) { s.sendMessage(Text.c("&cUsage: /sb soulflow <player> [info|give|take|set|overflow|combat] [n|on|off]")); return; }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) { s.sendMessage(Text.c("&cNo online player " + a[0])); return; }
        SoulflowData d = svc.data(t);
        if (d == null) { s.sendMessage(Text.c("&c" + t.getName() + "'s profile is not ready")); return; }
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        long n = 0;
        if (a.length > 2 && !"combat".equals(sub)) {
            try { n = Math.max(0, Long.parseLong(a[2].replace(",", ""))); }
            catch (NumberFormatException e) { s.sendMessage(Text.c("&cNot a whole number: " + a[2])); return; }
        }
        switch (sub) {
            case "give" -> svc.add(t, n, "admin");
            case "take" -> d.pool = Math.max(0, d.pool - n);
            case "set" -> d.pool = Math.min(n, SoulflowMath.HARD_CAP);
            case "overflow" -> d.overflow = Math.min(n, 1_000_000);
            case "combat" -> {
                boolean on = a.length < 3 || "on".equalsIgnoreCase(a[2]);
                if (on) rt.markCombat(t); else rt.clearCombat(t);
            }
            case "info" -> { }
            default -> { s.sendMessage(Text.c("&cUnknown: " + sub)); return; }
        }
        SoulflowConfig.Tier tier = svc.tier(t);
        s.sendMessage(Text.c("&3" + t.getName() + "&7: pool &3" + SoulflowMath.units(d.pool) + " &7(usable "
                + svc.poolUsable(t) + "), raw in inventory &3" + svc.raw(t) + "&7, spendable &3" + svc.soulflow(t)
                + "&7, overflow &b" + Text.commasInt(d.overflow) + "ʬ&7, accessory " + (tier == null ? "&cnone" : "&a" + tier.item)
                + "&7, in combat " + rt.inCombat(t) + ", toggles " + d.toggles));
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        rt.forget(e.getPlayer());
        gate.forget(e.getPlayer());
        svc.forget(e.getPlayer());
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) {
        if (e.player != null) rt.forget(e.player);
    }
}
