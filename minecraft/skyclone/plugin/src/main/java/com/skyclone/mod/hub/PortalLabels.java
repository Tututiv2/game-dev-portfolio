package com.skyclone.mod.hub;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Layout;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Floating labels over the Portal Plaza gates ("The Barn / Requires Farming I"), drawn from the
 * registered warps so they always say what the gate really does. Non-persistent TextDisplays at
 * the layout's {@code label.portal.*} anchors, re-created by a watchdog when a chunk reloads.
 */
final class PortalLabels implements Listener {

    private final Map<String, UUID> live = new HashMap<>();
    private final NamespacedKey key = new NamespacedKey(Sky.plugin(), "hub_label");

    @EventHandler
    public void onBuilt(SkyEvents.IslandBuilt e) {
        if (HubModule.ISLAND.equals(e.islandId)) { clear(); tick(); }
    }

    /** Spawn missing labels (every 10 s). */
    void tick() {
        if (!HubModule.labels.get()) { clear(); return; }
        if (!Sky.worlds().isBuilt(HubModule.ISLAND)) return;
        Layout l = Sky.worlds().layout(HubModule.ISLAND);
        World w = Sky.worlds().world(HubModule.ISLAND);
        if (l == null || w == null) return;
        for (Layout.Anchor a : l.anchors().values()) {
            String kind = a.name().startsWith("label.portal.") ? "portal" : a.name().startsWith("label.pad.") ? "pad"
                    : a.name().startsWith("label.gate.") ? "gate" : null;
            if (kind == null) continue;
            UUID id = live.get(a.name());
            Entity ent = id == null ? null : Bukkit.getEntity(id);
            if (ent != null && ent.isValid()) continue;
            Location at = new Location(w, a.x(), a.y(), a.z());
            if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            String warpId = a.name().substring(("label." + kind + ".").length());
            String text = labelText(warpId, kind);
            TextDisplay td = w.spawn(at, TextDisplay.class, t -> {
                t.text(Text.c(text.split("\n")[0]).append(Component.newline()).append(Text.c(text.contains("\n") ? text.split("\n")[1] : "")));
                t.setBillboard(Display.Billboard.CENTER);
                t.setShadowed(true);
                t.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                t.setPersistent(false);
                t.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            });
            live.put(a.name(), td.getUniqueId());
        }
    }

    static String labelText(String warpId) { return labelText(warpId, "portal"); }

    /** Two lines: what it is and where it goes, then who may use it. {@code kind}: portal, pad or gate. */
    static String labelText(String warpId, String kind) {
        WorldService.WarpInfo w = HubMenus.warp(warpId);
        String what = kind.equals("pad") ? "&a&lLAUNCH PAD &8- " : kind.equals("gate") ? "&6&lGATE &8- " : "";
        if (w == null) return what + "&7" + Text.titleCase(warpId) + "\n&8Not open yet";
        String req = HubMenus.requirementOf(w);
        // the Rift's requirement text is a sentence; the arch's sign says it short
        if (warpId.equals("rift")) return "&d&lTHE ARCH &8- &b&l" + w.displayName() + "\n&7Needs a &dPlanar Infusion &8(ask the Warden)";
        return what + "&b&l" + w.displayName() + "\n" + (req == null ? "&aOpen to everyone" : "&7Requires " + Sky.requirements().describe(req));
    }

    void clear() {
        for (UUID id : live.values()) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        live.clear();
        World w = Bukkit.getWorld("sb_" + HubModule.ISLAND);
        if (w != null) w.getEntitiesByClass(TextDisplay.class).forEach(t -> { if (t.getPersistentDataContainer().has(key)) t.remove(); });
    }

    int count() { return live.size(); }
}
