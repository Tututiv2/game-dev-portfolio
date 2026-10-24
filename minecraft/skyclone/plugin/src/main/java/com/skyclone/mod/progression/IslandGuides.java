package com.skyclone.mod.progression;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Island Guides (research 03 2.2): the 2026 rework's per-district FIRST-ENTRY objectives, for every island but the
 * Hub (whose districts are the hub module's District Guides). A guide starts the first time a player stands on
 * its island; its steps go in order; while the player is on that island the sidebar's Objective shows the
 * guide's step; finishing pays coins (TUNE) and SkyBlock XP {@code island_guide:<id>}.
 * Data: data/progression/island_guides.json (generated, tools/gen/progression).
 */
final class IslandGuides implements Listener {

    private final List<Catalogs.IslandGuide> guides;
    private final Map<String, Catalogs.IslandGuide> byId = new LinkedHashMap<>();
    private final Map<String, Catalogs.IslandGuide> byIsland = new HashMap<>();
    /** Players with a start announcement scheduled (an IslandChange and a poll can both see the first entry). */
    private final Set<UUID> starting = new HashSet<>();

    IslandGuides() {
        this.guides = Prog.guidesFile == null ? List.of() : Prog.guidesFile.guides;
        for (Catalogs.IslandGuide g : guides) {
            byId.put(g.id, g);
            if (g.island != null) byIsland.putIfAbsent(g.island, g);
        }
    }

    List<Catalogs.IslandGuide> all() { return guides; }

    Catalogs.IslandGuide guide(String id) { return id == null ? null : byId.get(id); }

    // ---- per-player state ---------------------------------------------------------------------------------

    /** -1 = not started, 0..size-1 = current step, size = finished. */
    static int step(PData.Guides d, Catalogs.IslandGuide g) { return d.step.getOrDefault(g.id, -1); }

    static boolean started(PData.Guides d, Catalogs.IslandGuide g) { return d.step.containsKey(g.id); }

    static boolean finished(PData.Guides d, Catalogs.IslandGuide g) { return d.finished.contains(g.id) || step(d, g) >= g.steps.size(); }

    static boolean active(PData.Guides d, Catalogs.IslandGuide g) {
        return started(d, g) && !finished(d, g) && !d.skipped.contains(g.id);
    }

    /** The guide the player is standing in and working on, or null. */
    Catalogs.IslandGuide activeHere(Player p) {
        if (!Prog.guidesEnabled.get() || !Prog.ready(p)) return null;
        String island = Sky.worlds().islandOf(p.getWorld());
        Catalogs.IslandGuide g = island == null ? null : byIsland.get(island);
        if (g == null) return null;
        return active(Prog.guides(p), g) ? g : null;
    }

    private Catalogs.GuideStep current(PData.Guides d, Catalogs.IslandGuide g) {
        int s = step(d, g);
        return s >= 0 && s < g.steps.size() ? g.steps.get(s) : null;
    }

    // ---- start ---------------------------------------------------------------------------------------------

    /** First time on a guided island: the guide starts (announced a moment later, once the arrival settles). */
    void maybeStart(Player p, String island) {
        if (!Prog.guidesEnabled.get() || !Prog.ready(p) || island == null) return;
        Catalogs.IslandGuide g = byIsland.get(island);
        if (g == null || g.steps.isEmpty()) return;
        PData.Guides d = Prog.guides(p);
        if (started(d, g)) return;
        d.step.put(g.id, 0);
        d.progress.remove(g.id);
        if (!starting.add(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            starting.remove(p.getUniqueId());
            if (!Prog.ready(p)) return;
            PData.Guides now = Prog.guides(p);
            Catalogs.GuideStep first = current(now, g);
            if (first == null) return;
            List<String> body = new ArrayList<>();
            if (g.about != null && !g.about.isBlank()) body.addAll(Text.wrap("&7" + g.about, 40));
            body.add("");
            body.add("&e" + g.steps.size() + " objectives &7- the sidebar shows the next one");
            body.add("&7while you are here. &8/islandguides");
            Msg.box(p, "&6&lISLAND GUIDE &e" + g.name, body);
            Msg.send(p, "&eObjective: &f" + first.text);
            if (first.hint != null && !first.hint.isBlank()) Msg.send(p, "&7" + first.hint);
            Msg.sound(p, Sound.UI_TOAST_IN, 1.2f);
            check(p, g);   // already standing in the step's zone, already meeting a requirement
        }, 40L);
    }

    // ---- progress -------------------------------------------------------------------------------------------

    /** Count {@code by} towards the current step of {@code g} if it is of {@code type} and {@code match} accepts it. */
    private void bump(Player p, Catalogs.IslandGuide g, String type, java.util.function.Predicate<Catalogs.Trigger> match, int by) {
        if (g == null || !Prog.ready(p)) return;
        PData.Guides d = Prog.guides(p);
        if (!active(d, g)) return;
        Catalogs.GuideStep st = current(d, g);
        if (st == null || st.trigger == null || !type.equals(st.trigger.type) || !match.test(st.trigger)) return;
        int need = Math.max(1, st.trigger.amount);
        int now = d.progress.merge(g.id, Math.max(1, by), Integer::sum);
        if (now >= need) completeStep(p, g);
    }

    /** The current step is done: announce it, move on, finish the guide after the last step. */
    void completeStep(Player p, Catalogs.IslandGuide g) {
        PData.Guides d = Prog.guides(p);
        int s = step(d, g);
        if (s < 0 || s >= g.steps.size()) return;
        Catalogs.GuideStep done = g.steps.get(s);
        int next = PMath.guideAdvance(s, g.steps.size());
        d.step.put(g.id, next);
        d.progress.remove(g.id);
        Msg.send(p, "&6&lGUIDE &e" + g.name + " &8» &a✔ " + done.text + " &8(" + next + "/" + g.steps.size() + ")");
        Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f);
        if (next >= g.steps.size()) { finish(p, g); return; }
        Catalogs.GuideStep st = g.steps.get(next);
        Msg.send(p, "&eObjective: &f" + st.text);
        if (st.hint != null && !st.hint.isBlank()) Msg.send(p, "&7" + st.hint);
        check(p, g);
    }

    private void finish(Player p, Catalogs.IslandGuide g) {
        PData.Guides d = Prog.guides(p);
        if (d.finished.contains(g.id)) return;
        d.finished.add(g.id);
        d.skipped.remove(g.id);
        long coins = Math.round(Math.max(0, g.coins) * Math.max(0, Prog.guidesCoinScale.get()));
        if (coins > 0) Sky.economy().deposit(p, coins, "island guide: " + g.id);
        List<String> body = new ArrayList<>();
        body.add("&7You finished the &e" + g.name + " &7guide.");
        body.add("");
        body.add("&a&lREWARDS");
        if (coins > 0) body.add("  &6+" + Text.commasInt(coins) + " Coins");
        if (g.sbxp > 0) body.add("  &b+" + g.sbxp + " SkyBlock XP");
        Msg.box(p, "&6&lISLAND GUIDE COMPLETE", body);
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
        if (g.sbxp > 0) Prog.award(p, "island_guide:" + g.id, g.sbxp, "Island Guide: " + g.name);
        Sky.profiles().saveNow(p);
    }

    /** Polled / just-changed checks: zone steps (standing in it) and requirement steps. */
    void check(Player p, Catalogs.IslandGuide g) {
        if (g == null || !Prog.ready(p)) return;
        PData.Guides d = Prog.guides(p);
        if (!active(d, g)) return;
        Catalogs.GuideStep st = current(d, g);
        if (st == null || st.trigger == null) return;
        if (!g.island.equals(Sky.worlds().islandOf(p.getWorld()))) return;
        try {
            switch (st.trigger.type) {
                case "zone" -> {
                    var z = Sky.worlds().zoneOf(p);
                    if (z != null && st.trigger.zones.contains(z.id())) completeStep(p, g);
                }
                case "requirement" -> {
                    if (st.trigger.key != null && Sky.requirements().check(p, st.trigger.key)) completeStep(p, g);
                }
                default -> { }
            }
        } catch (Throwable ignored) { }
    }

    /** Called from the module's objective poll. */
    void poll(Player p) {
        if (!Prog.guidesEnabled.get() || !Prog.ready(p)) return;
        String island = Sky.worlds().islandOf(p.getWorld());
        if (island == null) return;
        Catalogs.IslandGuide g = byIsland.get(island);
        if (g == null) return;
        if (!started(Prog.guides(p), g)) { maybeStart(p, island); return; }
        check(p, g);
    }

    private Catalogs.IslandGuide here(Player p) {
        String island = Sky.worlds().islandOf(p.getWorld());
        return island == null ? null : byIsland.get(island);
    }

    // ---- events ----------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onIsland(SkyEvents.IslandChange e) {
        if (e.player == null || e.to == null) return;
        maybeStart(e.player, e.to);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onZone(SkyEvents.ZoneChange e) {
        Player p = e.player;
        if (p == null || e.to == null || !Prog.ready(p)) return;
        Catalogs.IslandGuide g = e.island == null ? null : byIsland.get(e.island);
        String zone = e.to.id();
        bump(p, g, "zone", t -> t.zones.contains(zone), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNpc(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (!Prog.ready(p)) return;
        String anchor = Objectives.npcAnchor(e.getRightClicked());
        if (anchor == null) return;
        bump(p, here(p), "npc", t -> t.anchors.contains(anchor), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (p == null || e.def == null || !Prog.ready(p)) return;
        String island = e.location != null && e.location.getWorld() != null ? Sky.worlds().islandOf(e.location.getWorld()) : null;
        Catalogs.IslandGuide g = island == null ? null : byIsland.get(island);
        bump(p, g, "kill", t -> PMath.killMatches(e.def.id, e.def.familyId(), t.what), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGather(SkyEvents.Gather e) {
        Player p = e.player;
        if (p == null || e.kind == null || !Prog.ready(p)) return;
        String island = e.location != null && e.location.getWorld() != null ? Sky.worlds().islandOf(e.location.getWorld()) : null;
        Catalogs.IslandGuide g = island == null ? null : byIsland.get(island);
        bump(p, g, "gather", t -> (t.gather == null || t.gather.equalsIgnoreCase(e.kind.name())) && PMath.gatherMatches(e.what, t.what), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFish(SkyEvents.FishCaught e) {
        Player p = e.player;
        if (p == null || !Prog.ready(p)) return;
        bump(p, here(p), "fish", t -> true, 1);
    }

    void onQuit(Player p) { starting.remove(p.getUniqueId()); }

    // ---- sidebar -------------------------------------------------------------------------------------------

    /** "Objective" + the guide's step while the player is on its island (empty otherwise). */
    List<String> sidebar(Player p) {
        if (!Prog.guidesSidebar.get()) return List.of();
        Catalogs.IslandGuide g = activeHere(p);
        if (g == null) return List.of();
        PData.Guides d = Prog.guides(p);
        Catalogs.GuideStep st = current(d, g);
        if (st == null) return List.of();
        String text = st.text;
        int need = Math.max(1, st.trigger == null ? 1 : st.trigger.amount);
        if (need > 1) text += " &7(" + d.progress.getOrDefault(g.id, 0) + "/" + need + ")";
        return List.of("&fObjective", "&e" + text);
    }

    // ---- admin ---------------------------------------------------------------------------------------------

    boolean adminStep(Player p, String id) {
        Catalogs.IslandGuide g = byId.get(id);
        if (g == null) return false;
        PData.Guides d = Prog.guides(p);
        if (!started(d, g)) d.step.put(g.id, 0);
        d.skipped.remove(g.id);
        completeStep(p, g);
        return true;
    }

    boolean adminReset(Player p, String id) {
        PData.Guides d = Prog.guides(p);
        if (id == null || id.equals("all")) {
            d.step.clear(); d.progress.clear(); d.finished.clear(); d.skipped.clear();
            return true;
        }
        if (!byId.containsKey(id)) return false;
        d.step.remove(id); d.progress.remove(id); d.finished.remove(id); d.skipped.remove(id);
        return true;
    }

    // ---- menu (/islandguides; Quest Log slot 51) -----------------------------------------------------------

    void openMenu(Player p) {
        if (!Prog.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        build(p, 0).open(p);
    }

    Menu build(Player viewer, int page) {
        PData.Guides d = Prog.guides(viewer);
        int done = 0;
        for (Catalogs.IslandGuide g : guides) if (finished(d, g)) done++;
        final int doneCount = done;
        return Sky.menus().paged("Island Guides", new ArrayList<>(guides), g -> icon(d, g), pc -> {
            Player p = pc.click().player();
            if (!Prog.ready(p)) return;
            PData.Guides live = Prog.guides(p);
            Catalogs.IslandGuide g = pc.entry();
            if (live.skipped.contains(g.id)) {
                live.skipped.remove(g.id);
                Msg.success(p, "Resumed the " + g.name + " guide.");
                build(p, pc.page()).open(p);
            } else if (pc.click().right() && active(live, g)) {
                Sky.menus().confirm(p, "Skip this guide?", List.of("&7" + g.name, "", "&cYou get no reward for it.",
                        "&7You can resume it here any time."), () -> {
                    if (!Prog.ready(p)) return;
                    PData.Guides l2 = Prog.guides(p);
                    if (!l2.skipped.contains(g.id)) l2.skipped.add(g.id);
                    Msg.send(p, "&7Skipped the &f" + g.name + " &7guide.");
                    build(p, pc.page()).open(p);
                }, () -> build(p, pc.page()).open(p));
            }
        }, m -> {
            m.set(4, Icons.of(Material.FILLED_MAP, "&aIsland Guides",
                    "&7Every island has a short guide that", "&7starts the first time you arrive.",
                    "&7While you are on that island, the", "&7sidebar's Objective follows it.", "",
                    "&7Finished: &a" + doneCount + "&7/&a" + guides.size(), Prog.bar(doneCount, Math.max(1, guides.size())), "",
                    "&8The Hub's districts have their own", "&8District Guides: /hubguides"));
            m.back("Quest Log", () -> Prog.objectives.buildQuestLog(viewer, Prog.obj(viewer), 0));
        }, page);
    }

    private org.bukkit.inventory.ItemStack icon(PData.Guides d, Catalogs.IslandGuide g) {
        Material mat = Prog.mat(g.icon, Material.MAP);
        List<String> lore = new ArrayList<>();
        if (g.about != null && !g.about.isBlank()) lore.addAll(Text.wrap("&7" + g.about, 34));
        lore.add("");
        int s = step(d, g);
        boolean fin = finished(d, g);
        for (int i = 0; i < g.steps.size(); i++) {
            Catalogs.GuideStep st = g.steps.get(i);
            String mark;
            if (fin || (s >= 0 && i < s)) mark = "&a✔ &7";
            else if (s == i && started(d, g)) mark = "&e▶ &f";
            else mark = "&8✖ &8";
            String txt = st.text;
            int need = Math.max(1, st.trigger == null ? 1 : st.trigger.amount);
            if (s == i && need > 1 && !fin) txt += " &7(" + d.progress.getOrDefault(g.id, 0) + "/" + need + ")";
            lore.add(mark + (started(d, g) || fin ? txt : "???"));
        }
        lore.add("");
        lore.add("&a&lREWARDS");
        long coins = Math.round(Math.max(0, g.coins) * Math.max(0, Prog.guidesCoinScale.get()));
        if (coins > 0) lore.add(" &6+" + Text.commasInt(coins) + " Coins");
        if (g.sbxp > 0) lore.add(" &b+" + g.sbxp + " SkyBlock XP");
        lore.add("");
        if (fin) {
            lore.add("&a✔ COMPLETE");
            return Icons.of(mat, "&a" + g.name, lore);
        }
        if (d.skipped.contains(g.id)) {
            lore.add("&7Skipped. &eClick to resume it!");
            return Icons.of(Material.LIGHT_GRAY_DYE, "&7" + g.name, lore);
        }
        if (!started(d, g)) {
            lore.add("&7Starts the first time you visit.");
            return Icons.of(Material.GRAY_DYE, "&7" + g.name, lore);
        }
        Catalogs.GuideStep cur = current(d, g);
        if (cur != null && cur.hint != null && !cur.hint.isBlank()) {
            lore.addAll(Text.wrap("&7" + cur.hint, 34));
            lore.add("");
        }
        lore.add("&e▶ IN PROGRESS");
        lore.add("&8Right-click to skip (no reward)");
        return Icons.glow(Icons.of(mat, "&e" + g.name, lore));
    }

    // ---- wiring check (self-test progression.guide-targets) ---------------------------------------------

    /** Every island / zone / NPC anchor / mob a guide step points at, that this world does not have. */
    List<String> unwired() {
        List<String> out = new ArrayList<>();
        Set<String> npcAnchors = new HashSet<>();
        try {
            for (var n : Sky.npcs().all()) if (n != null && n.anchor != null) npcAnchors.add(n.island + "|" + n.anchor);
        } catch (Throwable ignored) { }
        for (Catalogs.IslandGuide g : guides) {
            if (Sky.worlds().island(g.island) == null) { out.add(g.id + ": island " + g.island + " not registered"); continue; }
            com.skyclone.api.world.gen.Layout l = null;
            try { l = Sky.worlds().layout(g.island); } catch (Throwable ignored) { }
            Set<String> zones = new HashSet<>();
            if (l != null) for (var z : l.zones()) zones.add(z.id());
            for (Catalogs.GuideStep st : g.steps) {
                Catalogs.Trigger t = st.trigger;
                if (t == null) { out.add(g.id + ": step without trigger"); continue; }
                switch (t.type) {
                    case "zone" -> {
                        if (l == null) break;
                        boolean any = false;
                        for (String z : t.zones) if (zones.contains(z)) any = true;
                        if (!any) out.add(g.id + ": zone " + t.zones + " (" + st.text + ")");
                    }
                    case "npc" -> {
                        boolean any = false;
                        for (String a : t.anchors) if (npcAnchors.contains(g.island + "|" + a)) any = true;
                        if (!any) out.add(g.id + ": no NPC at " + t.anchors + " (" + st.text + ")");
                    }
                    case "kill" -> {
                        boolean any = t.what.isEmpty();
                        for (var def : Sky.mobs().all()) if (def != null && PMath.killMatches(def.id, def.familyId(), t.what)) { any = true; break; }
                        if (!any) out.add(g.id + ": no mob matches " + t.what + " (" + st.text + ")");
                    }
                    default -> { }
                }
            }
        }
        return out;
    }
}
