package com.skyclone.mod.progression;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Box;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The guided objective chain (research 03 section 2): the sidebar "Objective" line, the Quest Log,
 * completion from events, polling and other modules' {@code complete(p, id)} calls.
 */
final class Objectives implements Features.Objectives, Listener {

    /** Core tags NPC bodies (and their holograms) with these PDC keys; reading a key is not an import. */
    private static final NamespacedKey NPC_KEY = NamespacedKey.fromString("skyclone:npc");
    private static final NamespacedKey HOLO_KEY = NamespacedKey.fromString("skyclone:npc_holo");

    private final List<Catalogs.ObjectiveDef> chain;
    private final Map<String, Catalogs.ObjectiveDef> byId = new HashMap<>();
    private final Map<UUID, List<String>> pending = new HashMap<>();
    /** Players with a "New Objective" line already scheduled: two steps finishing inside the 30-tick delay
     *  (Talk to the Fairy, then Find a Fairy Soul already done) printed the same next step twice. */
    private final java.util.Set<UUID> announcing = new java.util.HashSet<>();

    Objectives() {
        this.chain = Prog.objectivesFile.chain;
        for (Catalogs.ObjectiveDef d : chain) byId.put(d.id, d);
    }

    List<Catalogs.ObjectiveDef> chain() { return chain; }

    Catalogs.ObjectiveDef def(String id) { return byId.get(id); }

    // ---- Features.Objectives ---------------------------------------------------------------------------

    @Override public void complete(Player p, String objectiveId) {
        if (p == null || objectiveId == null || objectiveId.isBlank()) return;
        if (!Prog.ready(p)) { pending.computeIfAbsent(p.getUniqueId(), u -> new ArrayList<>()).add(objectiveId); return; }
        PData.Objectives d = Prog.obj(p);
        if (d.done.contains(objectiveId)) return;
        Catalogs.ObjectiveDef def = byId.get(objectiveId);
        if (def == null) { d.done.add(objectiveId); return; }   // another module's own id: recorded, nothing to pay
        if (!PMath.objectiveReached(ids(), d.done, d.skipped, objectiveId)) {
            // AHEAD of the chain: remember it, say nothing. The box and the reward come when the chain gets
            // here (catch-up), so chat never announces a step the sidebar is not showing (playtest-endgame:
            // "OBJECTIVE COMPLETE Visit The End" while the sidebar still said "Talk to your Island Helper").
            if (!d.early.contains(objectiveId)) d.early.add(objectiveId);
            d.progress.remove(objectiveId);
            return;
        }
        boolean wasCurrent = def == currentDef(p);
        List<Catalogs.ObjectiveDef> credited = new ArrayList<>();
        credited.add(def);
        markDone(d, def.id);
        if (wasCurrent) credited.addAll(takeCatchUp(d));
        reward(p, credited);
        if (wasCurrent) announceNext(p);
    }

    @Override public boolean completed(Player p, String objectiveId) {
        if (!Prog.ready(p) || objectiveId == null) return false;
        PData.Objectives d = Prog.obj(p);
        return d.done.contains(objectiveId) || d.early.contains(objectiveId);
    }

    private List<String> ids() {
        List<String> out = new ArrayList<>(chain.size());
        for (Catalogs.ObjectiveDef o : chain) out.add(o.id);
        return out;
    }

    private static void markDone(PData.Objectives d, String id) {
        if (!d.done.contains(id)) d.done.add(id);
        d.skipped.remove(id);
        d.early.remove(id);
        d.progress.remove(id);
    }

    /** Credits (marks done) the steps done early that the chain now reaches; returns them in chain order. */
    private List<Catalogs.ObjectiveDef> takeCatchUp(PData.Objectives d) {
        List<Catalogs.ObjectiveDef> out = new ArrayList<>();
        for (String id : PMath.objectiveCatchUp(ids(), d.done, d.skipped, d.early)) {
            Catalogs.ObjectiveDef o = byId.get(id);
            markDone(d, id);
            if (o != null) out.add(o);
        }
        return out;
    }

    /**
     * The current step was already done early (an admin reset/skipto, a resumed skip, a login with a queued
     * completion): credit the run now, in one box, and announce the step after it.
     */
    void catchUp(Player p) {
        if (!Prog.ready(p)) return;
        PData.Objectives d = Prog.obj(p);
        if (d.early.isEmpty()) return;
        List<Catalogs.ObjectiveDef> run = takeCatchUp(d);
        if (run.isEmpty()) return;
        reward(p, run);
        announceNext(p);
    }

    /** The current objective's id, or "" when the chain is finished (or the profile is not ready). */
    @Override public String current(Player p) {
        Catalogs.ObjectiveDef d = currentDef(p);
        return d == null ? "" : d.id;
    }

    /** Display text of the current step ("Talk to the Banker"), or "" when the chain is through (wave 5). */
    @Override public String currentText(Player p) {
        Catalogs.ObjectiveDef d = currentDef(p);
        return d == null ? "" : d.text;
    }

    Catalogs.ObjectiveDef currentDef(Player p) {
        if (!Prog.ready(p)) return null;
        PData.Objectives d = Prog.obj(p);
        for (Catalogs.ObjectiveDef o : chain) {
            if (!d.done.contains(o.id) && !d.skipped.contains(o.id)) return o;
        }
        return null;
    }

    /**
     * One box for everything credited at once: the step just finished plus any steps after it that were
     * already done early (the chain catching up), so a catch-up is one announcement, not a stack of boxes.
     */
    private void reward(Player p, List<Catalogs.ObjectiveDef> defs) {
        if (defs.isEmpty()) return;
        long coins = 0;
        List<String> body = new ArrayList<>();
        for (int i = 0; i < defs.size(); i++) {
            Catalogs.ObjectiveDef def = defs.get(i);
            long c = Math.round(def.coins * Prog.objCoinScale.get());
            if (c > 0) { Sky.economy().deposit(p, c, "objective: " + def.id); coins += c; }
            body.add(i == 0 ? "&f" + def.text : "&7" + def.text + " &8(done earlier)");
        }
        body.add("");
        body.add("&a&lREWARDS");
        if (coins > 0) body.add("  &6+" + Text.commasInt(coins) + " Coins");
        body.add("  &b+" + (Prog.levelsFile.objectiveXp * defs.size()) + " SkyBlock XP");
        Msg.box(p, defs.size() == 1 ? "&6&lOBJECTIVE COMPLETE" : "&6&lOBJECTIVES COMPLETE", body);
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
        for (Catalogs.ObjectiveDef def : defs) Prog.award(p, "objective:" + def.id, Prog.levelsFile.objectiveXp, "Objective");
    }

    private void announceNext(Player p) {
        if (!announcing.add(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            announcing.remove(p.getUniqueId());
            if (!Prog.ready(p)) return;
            Catalogs.ObjectiveDef next = currentDef(p);
            if (next == null) {
                PData.Objectives d = Prog.obj(p);
                if (!d.finished) {
                    d.finished = true;
                    Msg.send(p, "&a&lALL OBJECTIVES COMPLETE! &7The rest of the world is yours to explore.");
                }
                return;
            }
            Msg.send(p, "&eNew Objective: &f" + next.text);
            Msg.send(p, "&7" + next.hint);
        }, 30L);
    }

    void skip(Player p, String id) {
        Catalogs.ObjectiveDef def = byId.get(id);
        if (def == null || !def.skippable || !Prog.ready(p)) return;
        PData.Objectives d = Prog.obj(p);
        if (d.done.contains(id) || d.skipped.contains(id)) return;
        boolean wasCurrent = def == currentDef(p);
        d.skipped.add(id);
        d.early.remove(id);
        Msg.send(p, "&7Skipped objective: &f" + def.text + " &7(no reward)");
        if (!wasCurrent) return;
        List<Catalogs.ObjectiveDef> run = takeCatchUp(d);   // the steps after it may already be done
        if (!run.isEmpty()) reward(p, run);
        announceNext(p);
    }

    void onReady(Player p) {
        List<String> q = pending.remove(p.getUniqueId());
        if (q != null) for (String id : q) complete(p, id);
        catchUp(p);
    }

    void onQuit(Player p) { pending.remove(p.getUniqueId()); }

    // ---- progress counters -----------------------------------------------------------------------------

    private void bump(Player p, Catalogs.ObjectiveDef def, int by) {
        PData.Objectives d = Prog.obj(p);
        int need = Math.max(1, def.trigger.amount);
        int now = d.progress.merge(def.id, by, Integer::sum);
        if (now >= need) complete(p, def.id);
    }

    /** Matching for "fire and forget" triggers that may complete any not-yet-done objective of the chain. */
    private void completeAllMatching(Player p, java.util.function.Predicate<Catalogs.ObjectiveDef> match) {
        PData.Objectives d = Prog.obj(p);
        for (Catalogs.ObjectiveDef o : chain) {
            if (d.done.contains(o.id) || d.early.contains(o.id)) continue;
            if (match.test(o)) complete(p, o.id);
        }
    }

    // ---- events ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onNpcClick(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (!Prog.ready(p)) return;
        String anchor = npcAnchor(e.getRightClicked());
        if (anchor == null) return;
        completeAllMatching(p, o -> "npc".equals(o.trigger.type) && o.trigger.anchors.contains(anchor));
    }

    /** Called by this module's own NPC handlers (the fairy, the curator). */
    void npcTalked(Player p, String anchor) {
        if (!Prog.ready(p)) return;
        completeAllMatching(p, o -> "npc".equals(o.trigger.type) && o.trigger.anchors.contains(anchor));
    }

    static String npcAnchor(Entity ent) {
        if (ent == null || NPC_KEY == null) return null;
        var pdc = ent.getPersistentDataContainer();
        String id = pdc.get(NPC_KEY, PersistentDataType.STRING);
        if (id == null && HOLO_KEY != null) id = pdc.get(HOLO_KEY, PersistentDataType.STRING);
        if (id == null) return null;
        NpcDef def = Sky.npcs().def(id);
        return def == null ? null : def.anchor;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (!Prog.ready(p)) return;
        Catalogs.ObjectiveDef cur = currentDef(p);
        if (cur == null || !"break".equals(cur.trigger.type)) return;
        if (!islandOk(cur.trigger.island, p.getLocation())) return;
        String mat = e.getBlock().getType().name();
        if (cur.trigger.materials.contains(mat)) bump(p, cur, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGather(SkyEvents.Gather e) {
        Player p = e.player;
        if (!Prog.ready(p)) return;
        Catalogs.ObjectiveDef cur = currentDef(p);
        if (cur == null || !"break".equals(cur.trigger.type) || cur.trigger.gather == null) return;
        if (e.kind == null || !cur.trigger.gather.equalsIgnoreCase(e.kind.name())) return;
        if (!islandOk(cur.trigger.island, e.location != null ? e.location : p.getLocation())) return;
        String what = e.what == null ? "" : e.what.toUpperCase(Locale.ROOT);
        boolean ok = cur.trigger.what.isEmpty() && cur.trigger.materials.isEmpty();
        for (String w : cur.trigger.what) if (what.contains(w.toUpperCase(Locale.ROOT))) ok = true;
        for (String m : cur.trigger.materials) if (what.equals(m)) ok = true;
        if (ok) bump(p, cur, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (!Prog.ready(p)) return;
        Catalogs.ObjectiveDef cur = currentDef(p);
        if (cur == null || !"kill".equals(cur.trigger.type)) return;
        Location at = e.location != null ? e.location : p.getLocation();
        if (!islandOk(cur.trigger.island, at)) return;
        // A district's mobs wander a few blocks past its box (the Graveyard's west gate, wave-5 regression playtest:
        // a Graveyard Zombie killed at the gate counted nothing). Either the mob or the killer near the area counts.
        if (!cur.trigger.areas.isEmpty() && !inAnyArea(cur.trigger.island, cur.trigger.areas, at, KILL_AREA_MARGIN)
                && !inAnyArea(cur.trigger.island, cur.trigger.areas, p.getLocation(), KILL_AREA_MARGIN)) return;
        bump(p, cur, 1);
    }

    /** Blocks around a kill objective's area that still count (mobs stray past a district's edge). */
    static final int KILL_AREA_MARGIN = 8;

    /**
     * "Craft a Workbench / Wood Pickaxe" complete the moment the craft happens (crafting request 3: ItemGain with
     * source CRAFT), not only when the 2 s poll sees the item still in the inventory - a table crafted and placed at
     * once used to be missed.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraft(SkyEvents.ItemGain e) {
        Player p = e.player;
        if (p == null || e.source != com.skyclone.api.loot.LootSource.CRAFT || e.itemId == null || !Prog.ready(p)) return;
        ItemDef def = Sky.items().def(e.itemId);
        String mat = def == null || def.material == null ? e.itemId : def.material.toUpperCase(Locale.ROOT);
        completeAllMatching(p, o -> "have".equals(o.trigger.type)
                && (o.trigger.what.contains(e.itemId) || o.trigger.materials.contains(e.itemId) || o.trigger.materials.contains(mat)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onIsland(SkyEvents.IslandChange e) {
        Player p = e.player;
        // from == null is the first island seen this session (a login), not travel: a new player
        // spawns in the hub for a moment while their island is made, which must not complete
        // "Go to the Hub" out of order. The poll still completes it if it is the current step.
        if (!Prog.ready(p) || e.to == null || e.from == null) return;
        completeAllMatching(p, o -> "island".equals(o.trigger.type) && e.to.equals(o.trigger.island));
    }

    /** "Visit X": standing in one of the trigger's zones (on its island) completes it, whatever the trigger type. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onZone(SkyEvents.ZoneChange e) {
        Player p = e.player;
        if (p == null || e.to == null || !Prog.ready(p)) return;
        zoneReached(p, e.island, e.to.id());
    }

    private void zoneReached(Player p, String island, String zoneId) {
        if (zoneId == null) return;
        completeAllMatching(p, o -> o.trigger.zones.contains(zoneId)
                && (o.trigger.island == null || o.trigger.island.isBlank() || o.trigger.island.equals(island)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!Prog.ready(p)) return;
        Catalogs.ObjectiveDef cur = currentDef(p);
        if (cur == null || !"use_category".equals(cur.trigger.type)) return;
        if (!islandOk(cur.trigger.island, p.getLocation())) return;
        ItemStack hand = e.getItem();
        String id = hand == null ? null : Sky.items().idOf(hand);
        ItemDef def = id == null ? null : Sky.items().def(id);
        if (def == null || def.category == null) return;
        if (def.category.name().equalsIgnoreCase(cur.trigger.category)
                || (cur.trigger.category != null && cur.trigger.category.equalsIgnoreCase("MINION") && def.category == ItemCategory.MINION)) {
            // count it a tick later, once the minion module has had its chance to place (or refuse) it
            int before = hand.getAmount();
            int slot = p.getInventory().getHeldItemSlot();
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
                if (!Prog.ready(p)) return;
                ItemStack now = p.getInventory().getItem(slot);
                String nowId = now == null ? null : Sky.items().idOf(now);
                if (now == null || !id.equals(nowId) || now.getAmount() < before) complete(p, cur.id);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!Prog.ready(p)) return;
        String msg = e.getMessage();
        if (msg.length() < 2) return;
        String cmd = msg.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (Prog.ready(p)) completeAllMatching(p, o -> o.trigger.commands.contains(cmd));
        });
    }

    // ---- polling ---------------------------------------------------------------------------------------

    /** Polled triggers for one player (called every objectives.poll-ticks). */
    void poll(Player p) {
        if (!Prog.ready(p)) return;
        catchUp(p);
        // menu-title alternatives are checked for every not-done objective (opening the Bazaar early counts)
        var open = Sky.menus().current(p);
        if (open != null && open.title() != null) {
            String t = Text.strip(open.title()).toLowerCase(Locale.ROOT);
            completeAllMatching(p, o -> matchesTitle(o.trigger.menu, t)
                    || ("menu".equals(o.trigger.type) && matchesTitle(o.trigger.title, t)));
        }
        // zone visits are polled too: a player who logs in (or is teleported) inside the zone fires no ZoneChange
        try {
            var zone = Sky.worlds().zoneOf(p);
            if (zone != null) zoneReached(p, Sky.worlds().islandOf(p.getLocation()), zone.id());
        } catch (Throwable ignored) { }
        Catalogs.ObjectiveDef cur = currentDef(p);
        if (cur == null) return;
        if (check(p, cur)) complete(p, cur.id);
    }

    private static boolean matchesTitle(List<String> wanted, String title) {
        for (String w : wanted) if (title.contains(w.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private boolean check(Player p, Catalogs.ObjectiveDef o) {
        Catalogs.Trigger t = o.trigger;
        try {
            switch (t.type) {
                case "have": return hasAny(p, t.materials, t.what);
                case "island": return t.island != null && t.island.equals(Sky.worlds().islandOf(p.getLocation()));
                case "area": return islandOk(t.island, p.getLocation()) && inAnyArea(t.island, t.areas, p.getLocation());
                case "requirement": return t.key != null && Sky.requirements().check(p, t.key);
                case "bank": return Sky.economy().bank(p) > 0;
                case "fairy_found": return Prog.fairy(p).found.size() >= Math.max(1, t.amount);
                case "museum": return Prog.museum != null && Prog.museum.exhibitCount(p) >= Math.max(1, t.amount);
                default: return false;
            }
        } catch (Throwable ex) {
            return false;
        }
    }

    private static boolean hasAny(Player p, List<String> materials, List<String> ids) {
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : inv.getContents()) {
            if (it == null || it.getType().isAir()) continue;
            if (materials.contains(it.getType().name())) return true;
            if (!ids.isEmpty()) {
                String id = Sky.items().idOf(it);
                if (id != null && ids.contains(id)) return true;
            }
        }
        return false;
    }

    private static boolean islandOk(String island, Location at) {
        if (island == null || island.isBlank()) return true;
        return at != null && at.getWorld() != null && island.equals(Sky.worlds().islandOf(at.getWorld()));
    }

    private static boolean inAnyArea(String island, List<String> areas, Location at) {
        return inAnyArea(island, areas, at, 0);
    }

    private static boolean inAnyArea(String island, List<String> areas, Location at, int margin) {
        if (island == null || at == null) return false;
        WorldService w = Sky.worlds();
        for (String a : areas) {
            Box b = w.area(island, a);
            if (b != null && margin > 0) b = b.grow(margin);
            if (b != null && b.contains(at.getX(), at.getY(), at.getZ())) return true;
            // also accept the generous column: areas are feet-space boxes, the player may be on a roof
            if (b != null && b.containsXZ(at.getX(), at.getZ()) && at.getY() >= b.minY() - 8 && at.getY() <= b.maxY() + 24) return true;
        }
        return false;
    }

    // ---- sidebar ---------------------------------------------------------------------------------------

    /** The filler section (priority 900): the Objective, unless {@link #districtSidebar} is showing it. */
    List<String> sidebar(Player p) {
        if (!Prog.ready(p)) return List.of();
        // On an island with an unfinished Island Guide the Objective follows the guide (research 03 2.2: the
        // first-entry objectives); the chain's own step stays in /questlog
        if (Prog.islandGuides != null) {
            List<String> g = Prog.islandGuides.sidebar(p);
            if (!g.isEmpty()) return g;
        }
        if (!Prog.objEnabled.get()) return List.of();
        if (Prog.objGuideLine.get() && guideHere(p) != null) return List.of();
        return objectiveLines(p);
    }

    /**
     * Priority 349, one above hub's District Guide section (350). The frame leaves 4 free lines and each
     * section costs a blank plus its lines, so the guide (3) and the Objective (3) never fit together and the
     * guide used to win inside every district (wave-3 playtest: an agent reading the sidebar lost its
     * objective in the Mining District, the Farm, the Foraging Camp, the Wizard Tower). Here the Objective
     * keeps its place and names the guide in one line under it; the guide's steps stay in /hubguides.
     */
    List<String> districtSidebar(Player p) {
        if (!Prog.objEnabled.get() || !Prog.objGuideLine.get() || !Prog.ready(p)) return List.of();
        String guide = guideHere(p);
        if (guide == null) return List.of();
        List<String> obj = objectiveLines(p);
        if (obj.isEmpty()) return List.of();   // nothing to protect: let the guide show
        List<String> out = new ArrayList<>(obj);
        out.add("&7Guide: &e" + guide + " &8/hubguides");
        return out;
    }

    // ---- hub District Guides (read through the hub's data file and its "hub:guide:<id>" requirement) ----

    /** guide id (= hub zone id) -> display name, from hub's data/hub/guides.json; empty when hub ships none. */
    private Map<String, String> guideNames;

    Map<String, String> guideNames() {
        if (guideNames != null) return guideNames;
        Map<String, String> m = new HashMap<>();
        try (java.io.InputStream in = Sky.plugin().getResource("data/hub/guides.json")) {
            if (in != null) {
                com.google.gson.JsonElement root = com.google.gson.JsonParser.parseReader(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                if (root.isJsonArray()) for (com.google.gson.JsonElement el : root.getAsJsonArray()) {
                    if (!el.isJsonObject()) continue;
                    com.google.gson.JsonObject o = el.getAsJsonObject();
                    if (!o.has("id")) continue;
                    String id = o.get("id").getAsString();
                    m.put(id, o.has("name") ? o.get("name").getAsString() : Text.titleCase(id));
                }
            }
        } catch (Throwable t) {
            Sky.log().warning("[progression] could not read data/hub/guides.json: " + t);
        }
        guideNames = m;
        return m;
    }

    /**
     * The name of the unfinished District Guide whose hub zone the player stands in (i.e. the one hub's guide
     * section would show), or null. Null when the hub module is absent ("hub:guide" unregistered) or its guide
     * sidebar is switched off, since then nothing competes with the Objective.
     */
    String guideHere(Player p) {
        Map<String, String> names = guideNames();
        if (names.isEmpty()) return null;
        try {
            if (!"hub".equals(Sky.worlds().islandOf(p.getWorld()))) return null;
            com.skyclone.api.world.gen.Layout l = Sky.worlds().layout("hub");
            if (l == null) return null;
            Location at = p.getLocation();
            for (com.skyclone.api.world.gen.Layout.Zone z : l.zones()) {
                String name = names.get(z.id());
                if (name == null || !z.box().contains(at.getX(), at.getY(), at.getZ())) continue;
                if (!hubGuidesLive()) return null;
                if (Sky.requirements().check(p, "hub:guide:" + z.id())) continue;   // finished: hub shows nothing
                return name;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** Hub registered "hub:guide" and both of its guide dials are on (read only once hub is known to exist). */
    private boolean hubGuidesLive() {
        // an unknown prefix describes as "&c<key>"; hub's resolver answers "&afinishing the ... District Guide"
        if (Sky.requirements().describe("hub:guide:x").startsWith("&c")) return false;
        return Sky.dials().flag("hub.guides", true, "District Guides").get()
                && Sky.dials().flag("hub.guide-sidebar", true, "Show the current district guide step on the sidebar").get();
    }

    /** "Objective" + the current step (or the next locked warp once the chain is through). */
    List<String> objectiveLines(Player p) {
        if (!Prog.objEnabled.get() || !Prog.ready(p)) return List.of();
        Catalogs.ObjectiveDef cur = currentDef(p);
        if (cur != null) {
            String text = cur.text;
            int need = Math.max(1, cur.trigger.amount);
            if (need > 1) text += " &7(" + Prog.obj(p).progress.getOrDefault(cur.id, 0) + "/" + need + ")";
            return List.of("&fObjective", "&e" + text);
        }
        String next = nextLockedWarp(p);
        return next == null ? List.of() : List.of("&fObjective", "&e" + next);
    }

    /** After the chain: point at the first warp the player cannot use yet. */
    String nextLockedWarp(Player p) {
        try {
            for (WorldService.WarpInfo w : Sky.worlds().warpInfos()) {
                if (w.requirement() == null || w.requirement().isBlank()) continue;
                if (Sky.requirements().check(p, w.requirement())) continue;
                String name = w.displayName() == null ? Text.titleCase(w.id()) : w.displayName();
                return "Unlock " + name;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ---- requirement "quest:<id>" ------------------------------------------------------------------------

    Requirements.Resolver resolver() {
        return new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                return parts.length >= 2 && completed(p, parts[1]);
            }
            @Override public String describe(String[] parts) {
                Catalogs.ObjectiveDef d = parts.length >= 2 ? byId.get(parts[1]) : null;
                return "&aObjective: " + (d != null ? d.text : parts.length >= 2 ? Text.titleCase(parts[1]) : "?");
            }
        };
    }

    // ---- Quest Log ---------------------------------------------------------------------------------------

    void openMenu(Player p) {
        if (!Prog.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        buildQuestLog(p, Prog.obj(p), 0).open(p);
    }

    Menu buildQuestLog(Player viewer, PData.Objectives d, int page) {
        Catalogs.ObjectiveDef cur = null;
        for (Catalogs.ObjectiveDef o : chain) if (!d.done.contains(o.id) && !d.skipped.contains(o.id)) { cur = o; break; }
        final Catalogs.ObjectiveDef current = cur;
        int done = 0;
        for (Catalogs.ObjectiveDef o : chain) if (d.done.contains(o.id)) done++;
        final int doneCount = done;
        return Sky.menus().paged("Quest Log", new ArrayList<>(chain), o -> {
            boolean isDone = d.done.contains(o.id);
            boolean skipped = d.skipped.contains(o.id);
            boolean early = d.early.contains(o.id);
            int step = chain.indexOf(o) + 1;
            if (isDone) {
                return Icons.of(Material.LIME_DYE, "&a" + o.text, "&8Objective " + step, "", "&a✔ Completed");
            }
            if (skipped) {
                return Icons.of(Material.LIGHT_GRAY_DYE, "&7" + o.text, "&8Objective " + step, "", "&7Skipped",
                        "&eClick to resume it!");
            }
            if (o == current) {
                List<String> lore = new ArrayList<>();
                lore.add("&8Objective " + step);
                lore.add("");
                lore.addAll(Text.wrap("&7" + o.hint, 34));
                int need = Math.max(1, o.trigger.amount);
                if (need > 1) { lore.add(""); lore.add(Prog.bar(d.progress.getOrDefault(o.id, 0), need)); }
                lore.add("");
                lore.add("&a&lREWARDS");
                long coins = Math.round(o.coins * Prog.objCoinScale.get());
                if (coins > 0) lore.add(" &6+" + Text.commasInt(coins) + " Coins");
                lore.add(" &b+" + Prog.levelsFile.objectiveXp + " SkyBlock XP");
                lore.add("");
                lore.add("&e▶ CURRENT OBJECTIVE");
                if (o.skippable) lore.add("&8Right-click to skip (no reward)");
                return Icons.glow(Icons.of(Material.WRITABLE_BOOK, "&e" + o.text, lore));
            }
            if (early) {
                return Icons.of(Material.CYAN_DYE, "&b" + o.text, "&8Objective " + step, "", "&b✔ Done early",
                        "&7Rewarded when you reach it.");
            }
            return Icons.of(Material.GRAY_DYE, "&7???", "&8Objective " + step, "", "&7Complete earlier objectives first.");
        }, pc -> {
            Player p = pc.click().player();
            if (!Prog.ready(p)) return;
            PData.Objectives live = Prog.obj(p);
            Catalogs.ObjectiveDef o = pc.entry();
            if (live.skipped.contains(o.id)) {
                live.skipped.remove(o.id);
                Msg.success(p, "Resumed objective: " + o.text);
            } else if (pc.click().right() && o.skippable && o == currentDef(p)) {
                Sky.menus().confirm(p, "Skip objective?", List.of("&7" + o.text, "", "&cYou get no reward for it."),
                        () -> skip(p, o.id), null);
                return;
            } else {
                return;
            }
            buildQuestLog(p, live, pc.page()).open(p);
        }, m -> {
            m.set(4, Icons.of(Material.BOOK, "&aQuest Log",
                    "&7Objectives guide you through your", "&7first days on the island.", "",
                    "&7Completed: &a" + doneCount + "&7/&a" + chain.size(), Prog.bar(doneCount, chain.size())));
            if (Prog.fairy != null && viewer != null) m.set(50, Prog.fairy.summaryIcon(viewer), c -> Prog.fairy.openMenu(c.player(), false));
            if (Prog.islandGuides != null && viewer != null) m.set(51, Icons.of(Material.FILLED_MAP, "&aIsland Guides",
                    "&7Short first-visit objectives for", "&7every island.", "", "&eClick to view!"),
                    c -> Prog.islandGuides.openMenu(c.player()));
            Prog.backToSkyMenu(m);
        }, page);
    }
}
