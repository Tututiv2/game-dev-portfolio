package com.skyclone.mod.hub;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The hub's own NPCs, from {@code data/hub/npcs.json}: a long introduction the first time, a short line after
 * that, then optionally a menu (the Hub Map, the Portal Directory, the ship...). Wave 5: an NPC that gives a
 * townsfolk errand ({@code data/hub/errands.json}) offers it, tracks it and pays for it instead; the Archmage
 * points at the Rift's Warden; the Carnival pair say whether the carnival is in town.
 */
final class HubNpcs {

    private HubNpcs() {}

    /** One row of npcs.json. {@code ref} = the reference NPC's key, for {@code hub:met:<ref>} (garden visitors). */
    static final class Spec {
        String id, anchor, name, title, entity, hand, ref, then = "none";
        List<String> lines = new ArrayList<>(), intro = new ArrayList<>(), repeat = new ArrayList<>();
    }

    static final List<Spec> SPECS = new ArrayList<>();

    /** Read npcs.json (also used by the self-tests before anything registers). */
    static void load() {
        if (!SPECS.isEmpty()) return;
        try (InputStream in = Sky.plugin().getResource("data/hub/npcs.json")) {
            if (in == null) { Sky.log().warning("[hub] data/hub/npcs.json missing"); return; }
            List<Spec> specs = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), new TypeToken<List<Spec>>() {}.getType());
            SPECS.addAll(specs);
        } catch (Exception e) {
            Sky.log().warning("[hub] could not read npcs.json: " + e);
        }
    }

    static Spec byId(String id) {
        for (Spec s : SPECS) if (s.id.equals(id)) return s;
        return null;
    }

    static void register() {
        load();
        for (Spec s : SPECS) {
            // yield the spot to any module that already stationed an NPC there (foraging's woodcutter...)
            NpcDef other = null;
            for (NpcDef d : Sky.npcs().all())
                if (HubModule.ISLAND.equals(d.island) && s.anchor.equals(d.anchor) && !s.id.equals(d.id)) other = d;
            if (other != null) { Sky.log().info("[hub] " + s.anchor + " is run by " + other.id + "; hub's " + s.id + " stands down"); continue; }
            NpcDef def = NpcDef.of(s.id, s.name, HubModule.ISLAND, s.anchor);
            if (s.title != null) def.title(s.title);
            if (s.entity != null) def.entity(s.entity);
            def.hand = s.hand;
            def.lines.addAll(s.lines);
            Sky.npcs().register(def, p -> click(p, s));
        }
    }

    static void click(Player p, Spec s) {
        if (!Sky.profiles().isReady(p)) return;
        HubData d = HubModule.data(p);
        int n = d.talks.getOrDefault(s.id, 0);
        d.talks.put(s.id, n + 1);
        Errands.Errand errand = HubModule.errandsOn.get() ? HubErrands.byNpc(s.id) : null;
        if (errand != null) {
            HubErrands.click(p, s, errand, n == 0 ? s.intro : List.of());
            return;
        }
        List<String> raw = n == 0 && !s.intro.isEmpty() ? s.intro : s.repeat;
        if (s.then.equals("carnival") && carnivalOn()) raw = carnivalLines(s);
        List<String> lines = new ArrayList<>();
        for (String l : raw) lines.add("&f" + l);
        Runnable then = switch (s.then) {
            case "hubmap" -> () -> HubMenus.openMap(p);
            case "portals" -> () -> HubMenus.openPortals(p);
            case "ship" -> () -> HubMenus.openShip(p);
            case "guides" -> () -> HubMenus.openGuides(p);
            case "errands" -> () -> HubErrands.openMenu(p);
            case "rift" -> () -> rift(p);
            case "carnival" -> () -> carnival(p);
            default -> null;
        };
        if (lines.isEmpty()) { if (then != null) then.run(); return; }
        Sky.npcs().talk(p, s.id, lines, then);
    }

    // ---- the Archmage: the Rift's Warden ----------------------------------------------------

    /** Point the compass at the Warden of the Arch and open the Rift's own overview when it exists. */
    static void rift(Player p) {
        if (!p.isOnline()) return;
        Location warden = Sky.worlds().anchor(HubModule.ISLAND, "npc.rift_warden");
        if (warden != null && HubModule.ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) {
            p.setCompassTarget(warden);
            Location me = p.getLocation();
            double dx = warden.getX() - me.getX(), dz = warden.getZ() - me.getZ();
            Msg.send(p, "&7The &dWarden of the Arch &7stands &e" + Math.round(Math.hypot(dx, dz)) + " blocks &7to the &e"
                    + HubMenus.compass(dx, dz) + "&7, beside the arch. &8(Your compass points there now.)");
            Msg.sound(p, Sound.ITEM_LODESTONE_COMPASS_LOCK, 1.2f);
        }
        if (Sky.commands().exists("rift")) Sky.commands().run(p, "rift");
    }

    // ---- the Carnival pair -------------------------------------------------------------------

    static final String CARNIVAL = "carnival";

    static boolean carnivalOn() {
        try { return Sky.calendar().active(CARNIVAL); } catch (RuntimeException e) { return false; }
    }

    /** What the barker and the games host say while the carnival is in town (mayors request 9). */
    static List<String> carnivalLines(Spec s) {
        if (s.id.equals("hub_carnival_games"))
            return List.of("The carnival's in town! The Token Trader is right beside me: masks and perks for your tokens.",
                    "The games themselves are on the Carnival Grounds. Off you go!");
        return List.of("Roll up, roll up! The carnival's in town!",
                "The Ringmaster beside me hands out free Carnival Tickets every day. Spend them at the booths on the Carnival Grounds!");
    }

    /** After the talk: the grounds when the carnival is on, otherwise when it comes and why. */
    static void carnival(Player p) {
        if (!p.isOnline()) return;
        if (carnivalOn()) {
            if (HubMenus.warp(CARNIVAL) != null) {
                Msg.send(p, "&d[NPC] &fThis way to the Carnival Grounds!");
                Sky.worlds().warp(p, CARNIVAL);
            }
            return;
        }
        String mayor = null;
        try { if (Sky.has(Features.Mayors.class)) mayor = Sky.service(Features.Mayors.class).mayorName(); } catch (RuntimeException ignored) { }
        Msg.send(p, "&dThe carnival is not in town. &7It comes with a mayor who runs the &dCarnival &7perk"
                + (mayor == null || mayor.isBlank() ? "." : "; the mayor now is &e" + Text.strip(mayor) + "&7."));
        long ms = 0;
        try { ms = Sky.calendar().millisUntil(CARNIVAL); } catch (RuntimeException ignored) { }
        if (ms > 0 && ms < 1000L * 60 * 60 * 24 * 30) Msg.send(p, "&7Next carnival in &e" + Text.duration(ms / 1000) +"&7.");
        Msg.send(p, "&8(Vote for one in the Election Hall: /election)");
    }
}
