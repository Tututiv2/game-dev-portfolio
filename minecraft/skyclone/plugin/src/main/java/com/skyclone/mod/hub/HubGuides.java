package com.skyclone.mod.hub;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * District Guides: the 2026 hub rework's per-district first-entry objectives (research 03 section
 * 2.2: "Mining District: buy tools from the Mining Merchant, learn about reforges at the Blacksmith,
 * travel to the Coal Mine"; Foraging Camp: the Lumber Jack and the Carpenter; Combat: the
 * Bartender...). Data in {@code data/hub/guides.json}; each step names a trigger:
 * <pre>
 *   talk:&lt;hub anchor&gt;            clicked whatever NPC stands on that hub anchor (any module's)
 *   zone:&lt;zone id&gt;               walked into that hub zone
 *   gather:&lt;KIND&gt;:&lt;WHAT|*&gt;:&lt;n&gt;  Gather events in the hub (kind MINING/FARMING/FORAGING, WHAT contained in the id)
 *   kill:&lt;mob id prefix&gt;:&lt;n&gt;     killed sky mobs whose id starts with that
 *   fish:&lt;n&gt;                     caught anything in the hub
 *   pad:&lt;warp&gt;                   rode that launch pad
 * </pre>
 * Steps count in any order; the sidebar shows the first unfinished one for the district you are
 * in. Finishing a guide pays coins and one-time SkyBlock XP (both TUNE: the reference does not
 * publish them).
 */
final class HubGuides implements Listener {

    /** One guide (a row of guides.json). */
    static final class Guide {
        String id, name, icon, about;
        double coins;
        int sbxp;
        List<Step> steps = new ArrayList<>();
    }

    static final class Step {
        String text, trigger;
    }

    static final List<Guide> ALL = new ArrayList<>();

    static void load() {
        try (InputStream in = Sky.plugin().getResource("data/hub/guides.json")) {
            if (in == null) { Sky.log().warning("[hub] data/hub/guides.json missing"); return; }
            List<Guide> g = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<List<Guide>>() {}.getType());
            ALL.clear();
            ALL.addAll(g);
        } catch (Exception e) {
            Sky.log().warning("[hub] could not read guides.json: " + e);
        }
    }

    static Guide byId(String id) {
        for (Guide g : ALL) if (g.id.equals(id)) return g;
        return null;
    }

    /** How many a step needs (the trailing number of gather/kill/fish triggers, else 1). */
    static int need(Step s) {
        String[] p = s.trigger.split(":");
        if (p[0].equals("gather") || p[0].equals("kill") || p[0].equals("fish")) {
            try { return Math.max(1, Integer.parseInt(p[p.length - 1])); } catch (NumberFormatException e) { return 1; }
        }
        return 1;
    }

    static int have(HubData d, Guide g, int i) { return d.guideSteps.getOrDefault(g.id + "#" + i, 0); }

    static boolean stepDone(HubData d, Guide g, int i) { return have(d, g, i) >= need(g.steps.get(i)); }

    static int stepsDone(HubData d, Guide g) {
        int n = 0;
        for (int i = 0; i < g.steps.size(); i++) if (stepDone(d, g, i)) n++;
        return n;
    }

    /** Does an event key ("talk:npc.blacksmith", "gather:MINING:COAL", "kill:hub_wolf", "fish", "zone:crypt", "pad:barn") satisfy this trigger? */
    static boolean matches(String trigger, String event) {
        String[] t = trigger.split(":"), e = event.split(":");
        if (!t[0].equals(e[0])) return false;
        return switch (t[0]) {
            case "talk", "zone", "pad" -> t.length > 1 && e.length > 1 && t[1].equals(e[1]);
            case "gather" -> t.length >= 4 && e.length >= 3 && t[1].equalsIgnoreCase(e[1])
                    && (t[2].equals("*") || e[2].toUpperCase(Locale.ROOT).contains(t[2].toUpperCase(Locale.ROOT)));
            case "kill" -> t.length >= 3 && e.length >= 2 && e[1].startsWith(t[1]);
            case "fish" -> true;
            default -> false;
        };
    }

    /** Count {@code amount} towards every unfinished step any guide has for this event. */
    static void progress(Player p, String event, int amount) {
        HubErrands.progress(p, event, amount);   // running townsfolk errands count the same events
        if (!HubModule.guidesOn.get() || amount <= 0 || !Sky.profiles().isReady(p)) return;
        HubData d = HubModule.data(p);
        for (Guide g : ALL) {
            if (d.guidesDone.contains(g.id)) continue;
            boolean changed = false;
            for (int i = 0; i < g.steps.size(); i++) {
                Step s = g.steps.get(i);
                if (stepDone(d, g, i) || !matches(s.trigger, event)) continue;
                int now = Math.min(need(s), have(d, g, i) + amount);
                d.guideSteps.put(g.id + "#" + i, now);
                changed = true;
                if (now >= need(s)) {
                    Msg.send(p, "&e" + g.name + " Guide &8» &a" + s.text + " &a&l✔ &8(" + stepsDone(d, g) + "/" + g.steps.size() + ")");
                    Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4f);
                }
            }
            if (changed && stepsDone(d, g) == g.steps.size()) finish(p, d, g);
        }
    }

    static void finish(Player p, HubData d, Guide g) {
        if (d.guidesDone.contains(g.id)) return;
        d.guidesDone.add(g.id);
        double coins = Math.max(0, g.coins * HubModule.guideCoinScale.get());
        List<String> body = new ArrayList<>();
        body.add("&7You know your way around the &e" + g.name + "&7 now.");
        body.add("");
        body.add("&a&lREWARDS");
        if (coins > 0) { Sky.economy().deposit(p, coins, "hub: district guide " + g.id); body.add("  &6+" + Text.commasInt(coins) + " Coins"); }
        if (g.sbxp > 0 && Sky.has(Features.SkyLevel.class)) {
            Sky.service(Features.SkyLevel.class).award(p, "hub_guide:" + g.id, g.sbxp, g.name + " District Guide");
            body.add("  &b+" + g.sbxp + " SkyBlock XP");
        }
        Msg.box(p, "&6&lDISTRICT GUIDE COMPLETE &e" + g.name, body);
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
        Sky.profiles().saveNow(p);
    }

    // ---- sidebar --------------------------------------------------------------------------

    static List<String> sidebar(Player p) {
        if (!HubModule.guidesOn.get() || !HubModule.guideSidebar.get() || !Sky.profiles().isReady(p)) return List.of();
        if (!HubModule.ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) return List.of();
        HubData d = HubModule.data(p);
        Guide g = d.lastDistrict == null ? null : byId(d.lastDistrict);
        if (g == null || d.guidesDone.contains(g.id)) return List.of();
        // Only inside that district. The section outranks the Objective, so a guide that followed the
        // player around the whole Hub hid the main objective everywhere after one walk through the
        // Graveyard (wave-3 playtest: "Guide: Graveyard » Talk to the Bartender" in the Village).
        com.skyclone.api.world.gen.Layout l = Sky.worlds().layout(HubModule.ISLAND);
        if (l != null) {
            org.bukkit.Location at = p.getLocation();
            boolean inside = false;
            for (com.skyclone.api.world.gen.Layout.Zone z : l.zones())
                if (z.id().equals(g.id) && z.box().contains(at.getX(), at.getY(), at.getZ())) { inside = true; break; }
            if (!inside) return List.of();
        }
        for (int i = 0; i < g.steps.size(); i++) {
            if (stepDone(d, g, i)) continue;
            Step s = g.steps.get(i);
            int need = need(s);
            String count = need > 1 ? " &7(" + have(d, g, i) + "/" + need + ")" : "";
            return List.of("&eGuide: &f" + g.name, " &7» &f" + s.text + count);
        }
        return List.of();
    }

    // ---- triggers -------------------------------------------------------------------------

    /** The hub anchor of the NPC this entity is, or null. */
    static String anchorOf(Entity ent) {
        if (ent == null || !HubModule.ISLAND.equals(Sky.worlds().islandOf(ent.getWorld()))) return null;
        for (NpcDef def : Sky.npcs().all()) {
            if (!HubModule.ISLAND.equals(def.island) || def.anchor == null) continue;
            Entity live = Sky.npcs().entity(def.id);
            if (live != null && live.getUniqueId().equals(ent.getUniqueId())) return def.anchor;
        }
        return null;
    }

    // core's NPC listener cancels these at LOW; we only watch, so read them regardless
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        String a = anchorOf(e.getRightClicked());
        if (a != null) progress(e.getPlayer(), "talk:" + a, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(PrePlayerAttackEntityEvent e) {
        String a = anchorOf(e.getAttacked());
        if (a != null) progress(e.getPlayer(), "talk:" + a, 1);
    }

    @EventHandler
    public void onGather(SkyEvents.Gather e) {
        if (e.player == null || e.kind == null || e.what == null || e.location == null) return;
        if (!HubModule.ISLAND.equals(Sky.worlds().islandOf(e.location.getWorld()))) return;
        progress(e.player, "gather:" + e.kind.name() + ":" + e.what, e.amount);
    }

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null || e.def.id == null) return;
        if (!HubModule.ISLAND.equals(Sky.worlds().islandOf(e.killer.getWorld()))) return;
        progress(e.killer, "kill:" + e.def.id, 1);
    }

    @EventHandler
    public void onFish(SkyEvents.FishCaught e) {
        if (e.player == null || e.lava || !HubModule.ISLAND.equals(Sky.worlds().islandOf(e.player.getWorld()))) return;
        progress(e.player, "fish", 1);
    }

    /** Problems with the guide data (unknown trigger kinds, empty guides); null when fine. */
    static String validate() {
        List<String> bad = new ArrayList<>();
        if (ALL.isEmpty()) bad.add("no guides loaded");
        for (Guide g : ALL) {
            if (g.steps == null || g.steps.isEmpty()) bad.add(g.id + " has no steps");
            if (g.steps != null) for (Step s : g.steps) {
                String k = s.trigger == null ? "" : s.trigger.split(":")[0];
                if (!List.of("talk", "zone", "gather", "kill", "fish", "pad").contains(k)) bad.add(g.id + ": bad trigger " + s.trigger);
                if (k.equals("gather") && s.trigger.split(":").length < 4) bad.add(g.id + ": gather needs KIND:WHAT:n");
            }
        }
        return bad.isEmpty() ? null : String.join(", ", bad);
    }
}
