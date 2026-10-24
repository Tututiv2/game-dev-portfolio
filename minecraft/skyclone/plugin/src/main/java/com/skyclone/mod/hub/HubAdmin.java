package com.skyclone.mod.hub;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.SpawnRule;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.hub.gen.HubAnchors;
import org.bukkit.Bukkit;
import com.skyclone.api.npc.NpcDef;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /sb hub ...} — admin tools, all usable over RCON (every player-targeted one takes a name).
 * <pre>
 *   /sb hub info                         layout, build state, anchors, souls, warps, what is missing
 *   /sb hub tp &lt;player&gt; &lt;anchor&gt;           teleport to any hub anchor (npc.banker, warp.crypt, fairy_soul.7 ...)
 *   /sb hub discover &lt;player&gt; &lt;zone|all|reset&gt;  unlock / reset district fast travel
 *   /sb hub map &lt;player&gt;                   open the Hub Map for a player
 *   /sb hub portals &lt;player&gt;               open the Portal Directory for a player
 *   /sb hub spawns                       the hub's spawn rules with their live caps
 *   /sb hub anchors [prefix]             list layout anchors
 *   /sb hub labels                       respawn the portal/pad labels now
 *   /sb hub guide &lt;player&gt; &lt;id|all|reset&gt; [step]   finish a district guide (pays the reward) or wipe progress
 *   /sb hub guides|ship &lt;player&gt;          open the District Guides / Ship Navigator for a player
 *   /sb hub launch &lt;player&gt; &lt;warp&gt;        ride a launch pad or the mine gate as if walked onto (requirements apply)
 *   /sb hub travel                       list the pads and gates and whether their warps exist
 *   /sb hub pack                         Graveyard and Crypt packs: live members, held mobs, who is chased by how many
 *   /sb hub village &lt;player&gt;              open the Village Directory for a player
 *   /sb hub npcs                         every hub NPC: spawned, waiting for its chunk, or anchor missing
 *   /sb hub practice [player] [start|stop|reset]   Target Practice: running rounds, or start/stop/reset one player's
 *   /sb hub errand &lt;player&gt; &lt;id|all&gt; [accept|complete|reset]   townsfolk errands: list, start, finish (pays) or wipe
 *   /sb hub errands &lt;player&gt;              open the Townsfolk Errands menu for a player
 * </pre>
 */
final class HubAdmin {

    private HubAdmin() {}

    static void register() {
        Sky.commands().admin("hub", "info|tp|discover|map|portals|village|spawns|anchors|labels|guide|guides|launch|ship|travel|pack|npcs|practice|errand|errands ...",
                "Hub tools (layout check, anchors, districts, guides, launch pads, spawns)",
                HubAdmin::run, (s, a) -> a.length <= 1 ? SUBS : a.length == 3 && a[0].equalsIgnoreCase("guide") ? guideIds()
                        : a.length == 3 && a[0].equalsIgnoreCase("launch") ? List.of("spiders_den", "barn", "park", "gold_mine", "rift")
                        : a.length == 3 && a[0].equalsIgnoreCase("discover") ? districtIds()
                        : a.length == 3 && a[0].equalsIgnoreCase("errand") ? errandIds()
                        : a.length == 4 && a[0].equalsIgnoreCase("errand") ? List.of("accept", "complete", "reset") : List.of());
    }

    static void say(CommandSender s, String msg) { s.sendMessage(com.skyclone.api.ui.Text.c(msg)); }

    static void run(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(s);
            case "tp" -> {
                if (a.length < 3) { say(s, "&cUsage: /sb hub tp <player> <anchor>"); return; }
                Player p = Bukkit.getPlayerExact(a[1]);
                Location l = Sky.worlds().anchor(HubModule.ISLAND, a[2]);
                if (p == null || l == null) { say(s, "&cNo such player or anchor."); return; }
                p.teleportAsync(l);
                say(s, "&aSent " + p.getName() + " to " + a[2]);
            }
            case "discover" -> {
                if (a.length < 3) { say(s, "&cUsage: /sb hub discover <player> <zone|all|reset>"); return; }
                Player p = Bukkit.getPlayerExact(a[1]);
                if (p == null || !Sky.profiles().isReady(p)) { say(s, "&cPlayer not online/ready."); return; }
                HubData d = HubModule.data(p);
                if (a[2].equals("reset")) d.discovered.clear();
                else if (a[2].equals("all")) { d.discovered.clear(); for (HubDistrict x : HubDistrict.ALL) d.discovered.add(x.id()); }
                else if (HubDistrict.byId(a[2]) != null) { if (!d.discovered.contains(a[2])) d.discovered.add(a[2]); }
                else { say(s, "&cUnknown district. Known: " + ids()); return; }
                say(s, "&a" + p.getName() + " has " + d.discovered.size() + "/" + HubDistrict.ALL.size() + " districts.");
            }
            case "map", "portals", "village" -> {
                Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player pp ? pp : null;
                if (p == null) { say(s, "&cNo such player."); return; }
                if (sub.equals("map")) HubMenus.openMap(p); else if (sub.equals("village")) HubMenus.openVillage(p); else HubMenus.openPortals(p);
                say(s, "&aOpened for " + p.getName());
            }
            case "spawns" -> {
                for (SpawnRule r : HubModule.RULES)
                    say(s, "&7" + r.id + " &8" + r.mob + " in " + r.area + " &fmax " + r.max + " &8(base " + HubModule.BASE_MAX.get(r.id) + ") every "
                            + r.respawnSeconds + "s" + (r.condition == null ? "" : " when " + r.condition));
            }
            case "anchors" -> {
                Layout l = Sky.worlds().layout(HubModule.ISLAND);
                if (l == null) { say(s, "&cNo hub layout."); return; }
                String pre = a.length > 1 ? a[1] : "";
                List<String> names = new ArrayList<>();
                for (String n : l.anchors().keySet()) if (n.startsWith(pre)) names.add(n);
                say(s, "&7" + names.size() + " anchors: &f" + String.join(", ", names));
            }
            case "labels" -> { HubModuleAccess.labels(); say(s, "&aPortal labels refreshed."); }
            case "guides", "ship" -> {
                Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player pp ? pp : null;
                if (p == null) { say(s, "&cNo such player."); return; }
                if (sub.equals("guides")) HubMenus.openGuides(p); else HubMenus.openShip(p);
                say(s, "&aOpened for " + p.getName());
            }
            case "guide" -> guide(s, a);
            case "launch" -> {
                if (a.length < 3) { say(s, "&cUsage: /sb hub launch <player> <warp>  (rides that pad/gate as if walked onto)"); return; }
                Player p = Bukkit.getPlayerExact(a[1]);
                if (p == null) { say(s, "&cNo such player."); return; }
                TravelSpots.Spot spot = null;
                for (TravelSpots.Spot sp : HubModule.travel.spots()) if (sp.warp().equalsIgnoreCase(a[2])) spot = sp;
                if (spot == null) { say(s, "&cNo pad or gate to " + a[2] + " in the hub layout."); return; }
                String why = TravelSpots.blocker(p, spot.warp());
                say(s, why == null ? "&a" + p.getName() + " may use it; triggering." : "&e" + p.getName() + " is refused: " + why);
                HubModule.travel.trigger(null, p, spot);
            }
            case "travel" -> {
                for (TravelSpots.Spot sp : HubModule.travel.spots())
                    say(s, "&7" + sp.area() + " &8-> warp &f" + sp.warp() + (HubMenus.warp(sp.warp()) == null ? " &c(no warp registered)" : " &a(ok)")
                            + " &8" + sp.box());
                if (HubModule.travel.spots().isEmpty()) say(s, "&cNo pads/gates in the hub layout (old build? /sb island build hub force)");
            }
            case "pack" -> {
                for (GraveyardPack gp : GraveyardPack.ALL) for (String line : gp.report()) say(s, line);
                for (String line : GoldenVault.INSTANCE.report()) say(s, line);
            }
            case "npcs" -> npcs(s);
            case "errand" -> errand(s, a);
            case "errands" -> {
                Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player pp ? pp : null;
                if (p == null) { say(s, "&cNo such player."); return; }
                HubErrands.openMenu(p);
                say(s, "&aOpened for " + p.getName());
            }
            case "practice" -> {
                if (a.length < 2) { say(s, TargetPractice.INSTANCE.info()); return; }
                Player p = Bukkit.getPlayerExact(a[1]);
                if (p == null) { say(s, "&cNo such player."); return; }
                String what = a.length > 2 ? a[2].toLowerCase(java.util.Locale.ROOT) : "start";
                switch (what) {
                    case "stop" -> { TargetPractice.INSTANCE.finish(p, true); say(s, "&aStopped."); }
                    case "reset" -> {
                        if (!Sky.profiles().isReady(p)) { say(s, "&cProfile not ready."); return; }
                        HubData d = HubModule.data(p);
                        d.practiceBest = 0; d.practiceTier = 0; d.practiceRuns = 0;
                        say(s, "&aTarget Practice record wiped for " + p.getName() + ".");
                    }
                    default -> {
                        String why = TargetPractice.INSTANCE.start(p);
                        say(s, why == null ? "&aRound started for " + p.getName() + "." : "&c" + why);
                    }
                }
            }
            default -> say(s, "&cUnknown sub. Try " + String.join(", ", SUBS) + ".");
        }
    }

    static final List<String> SUBS = List.of("info", "tp", "discover", "map", "portals", "village", "spawns", "anchors", "labels", "guide", "guides", "launch", "ship", "travel", "pack", "npcs", "practice", "errand", "errands");

    static List<String> guideIds() {
        List<String> out = new ArrayList<>(List.of("all", "reset"));
        for (HubGuides.Guide g : HubGuides.ALL) out.add(g.id);
        return out;
    }

    static List<String> errandIds() {
        List<String> out = new ArrayList<>(List.of("all"));
        for (Errands.Errand e : HubErrands.ALL) out.add(e.id);
        return out;
    }

    /** /sb hub errand &lt;player&gt; [id|all] [accept|complete|reset]: with no action, lists that player's errands. */
    static void errand(CommandSender s, String[] a) {
        if (a.length < 2) { say(s, "&cUsage: /sb hub errand <player> [id|all] [accept|complete|reset]"); return; }
        Player p = Bukkit.getPlayerExact(a[1]);
        if (p == null || !Sky.profiles().isReady(p)) { say(s, "&cPlayer not online/ready."); return; }
        HubData d = HubModule.data(p);
        List<Errands.Errand> which = new ArrayList<>();
        if (a.length < 3 || a[2].equalsIgnoreCase("all")) which.addAll(HubErrands.ALL);
        else if (HubErrands.byId(a[2]) != null) which.add(HubErrands.byId(a[2]));
        else { say(s, "&cUnknown errand. Known: " + String.join(", ", errandIds())); return; }
        String act = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : "list";
        for (Errands.Errand e : which) {
            switch (act) {
                case "accept" -> HubErrands.accept(p, e);
                case "reset" -> {
                    d.errands.remove(e.id);
                    d.errandSteps.keySet().removeIf(k -> k.startsWith(e.id + "#"));
                    if (e.id.equals(d.errandFocus)) d.errandFocus = null;
                }
                case "complete" -> {
                    if (HubErrands.state(d, e) == HubErrands.DONE) break;
                    d.errands.put(e.id, HubErrands.DONE);
                    if (e.id.equals(d.errandFocus)) d.errandFocus = null;
                    HubErrands.pay(p, e);   // no items are taken: an admin completion
                }
                default -> { }
            }
            int st = HubErrands.state(d, e);
            say(s, "&7" + e.id + " &8(" + e.npc + ") &f" + (st == HubErrands.DONE ? "&adone" : st == HubErrands.RUNNING
                    ? "&erunning, unmet steps " + HubErrands.unmet(p, d, e) : "&7not started"));
        }
    }

    static List<String> districtIds() {
        List<String> out = new ArrayList<>(List.of("all", "reset"));
        for (HubDistrict d : HubDistrict.ALL) out.add(d.id());
        return out;
    }

    /** /sb hub guide &lt;player&gt; &lt;id|all|reset&gt; [step]: complete a guide (or one step of it), or wipe progress. */
    static void guide(CommandSender s, String[] a) {
        if (a.length < 3) { say(s, "&cUsage: /sb hub guide <player> <guide|all|reset> [step#]"); return; }
        Player p = Bukkit.getPlayerExact(a[1]);
        if (p == null || !Sky.profiles().isReady(p)) { say(s, "&cPlayer not online/ready."); return; }
        HubData d = HubModule.data(p);
        if (a[2].equalsIgnoreCase("reset")) { d.guideSteps.clear(); d.guidesDone.clear(); say(s, "&aReset every district guide for " + p.getName() + "."); return; }
        List<HubGuides.Guide> which = new ArrayList<>();
        if (a[2].equalsIgnoreCase("all")) which.addAll(HubGuides.ALL);
        else if (HubGuides.byId(a[2]) != null) which.add(HubGuides.byId(a[2]));
        else { say(s, "&cUnknown guide. Known: " + String.join(", ", guideIds())); return; }
        for (HubGuides.Guide g : which) {
            if (d.guidesDone.contains(g.id)) continue;
            for (int i = 0; i < g.steps.size(); i++) {
                if (a.length > 3 && !a[3].equals(String.valueOf(i + 1))) continue;
                d.guideSteps.put(g.id + "#" + i, HubGuides.need(g.steps.get(i)));
            }
            if (HubGuides.stepsDone(d, g) == g.steps.size()) HubGuides.finish(p, d, g);
            say(s, "&a" + g.id + ": " + HubGuides.stepsDone(d, g) + "/" + g.steps.size() + " steps" + (d.guidesDone.contains(g.id) ? " (complete, reward paid)" : ""));
        }
    }

    /**
     * /sb hub npcs: why each hub NPC is or is not standing there. Core spawns an NPC only once its
     * chunk is loaded (and again whenever it loads), so "not spawned" in {@code /sb npc list} is
     * normal for the far districts while nobody is near them. Only a missing anchor is a fault.
     */
    static void npcs(CommandSender s) {
        List<String> spawned = new ArrayList<>(), waiting = new ArrayList<>(), missing = new ArrayList<>();
        for (NpcDef d : Sky.npcs().all()) {
            if (!HubModule.ISLAND.equals(d.island)) continue;
            Location l = d.anchor == null ? null : Sky.worlds().anchor(HubModule.ISLAND, d.anchor);
            if (l == null) { missing.add(d.id + " &8(" + d.anchor + ")"); continue; }
            if (Sky.npcs().entity(d.id) != null) { spawned.add(d.id); continue; }
            World w = l.getWorld();
            boolean loaded = w != null && w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4);
            waiting.add(d.id + " &8@" + l.getBlockX() + "," + l.getBlockZ() + (loaded ? " &c(chunk loaded!)" : ""));
        }
        say(s, "&b&lHUB NPCS &a" + spawned.size() + " spawned &7· &e" + waiting.size() + " waiting &7· &c" + missing.size() + " anchor missing");
        if (!waiting.isEmpty()) say(s, "&eWaiting for their chunk to load (normal: they appear when a player comes near; "
                + "&c(chunk loaded!) &eis a fault): &f" + String.join("&7, &f", waiting));
        if (!missing.isEmpty()) say(s, "&cAnchor missing (fault: the NPC stands near spawn): &f" + String.join("&7, &f", missing));
    }

    static String ids() {
        List<String> out = new ArrayList<>();
        for (HubDistrict d : HubDistrict.ALL) out.add(d.id());
        return String.join(", ", out);
    }

    static void info(CommandSender s) {
        Layout l = Sky.worlds().layout(HubModule.ISLAND);
        say(s, "&b&lHUB &7built: " + (Sky.worlds().isBuilt(HubModule.ISLAND) ? "&ayes" : "&cno") + " &7layout: " + (l == null ? "&cnone" : "&ayes"));
        if (l == null) return;
        int souls = 0;
        for (String n : l.anchors().keySet()) if (n.startsWith("fairy_soul")) souls++;
        say(s, "&7anchors &f" + l.anchors().size() + " &7areas &f" + l.areas().size() + " &7zones &f" + l.zones().size() + " &7fairy souls &f" + souls);
        say(s, "&7townhouse residents &f" + l.data().getOrDefault("hub.residents", "? (layout older than v11)") + " &7errands &f" + HubErrands.ALL.size());
        List<String> miss = HubSelfTests.missingAnchors(l);
        say(s, miss.isEmpty() ? "&aEvery expected anchor/area/portal is present." : "&cMissing: " + String.join(", ", miss));
        List<String> unwired = HubSelfTests.unwiredPortals(l);
        say(s, unwired.isEmpty() ? "&aEvery portal has a registered warp." : "&ePortals waiting for their island's warp: " + String.join(", ", unwired));
        say(s, "&7spawn rules &f" + HubModule.RULES.size() + " &7scale &f" + HubModule.spawnScale.get() + " &7districts &f" + HubDistrict.ALL.size());
    }

    /** Small indirection so the labels can be poked without a static instance field. */
    static final class HubModuleAccess {
        static Runnable refresher = () -> {};
        static void labels() { refresher.run(); }
    }

    static List<String> anchorsExpected() {
        List<String> all = new ArrayList<>(HubAnchors.CONTRACT);
        all.addAll(HubAnchors.REQUESTED);
        all.addAll(HubAnchors.OWN);
        all.addAll(HubAnchors.TRAVEL);
        all.addAll(HubAnchors.RESIDENTS);
        all.addAll(HubAnchors.WAVE5);
        for (HubDistrict d : HubDistrict.ALL) all.add(d.anchor());
        for (String[] v : HubAnchors.VILLAGE) if (!all.contains(v[2])) all.add(v[2]);
        return all;
    }
}
