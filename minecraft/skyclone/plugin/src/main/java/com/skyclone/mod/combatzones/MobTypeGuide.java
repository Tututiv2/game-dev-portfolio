package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fenn and Lark's crib on the Den Entrance (the reference's Ike and Michael at Michael's Crib, "Mob Types intro",
 * research 03 sec 13.1/13.3): they explain mob types, the guide menu lists every type with the mobs of this game
 * that carry it, and the first kill of each type pays SkyBlock XP (research 02: "mob types (1 each)").
 */
final class MobTypeGuide implements Listener {

    private final CombatzonesModule m;
    private Map<String, List<MobDef>> byType;

    MobTypeGuide(CombatzonesModule m) { this.m = m; }

    /** Every registered mob grouped by type (built lazily: other modules' mobs load in their own enable). */
    Map<String, List<MobDef>> byType() {
        if (byType != null) return byType;
        Map<String, List<MobDef>> out = new LinkedHashMap<>();
        for (MobDef def : Sky.mobs().all())
            for (String t : m.essence.typesOf(def)) out.computeIfAbsent(t, k -> new ArrayList<>()).add(def);
        return byType = out;
    }

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null || !Sky.profiles().isReady(e.killer)) return;
        Set<String> types = m.essence.typesOf(e.def);
        if (types.isEmpty()) return;
        CzData d = m.data(e.killer);
        for (String t : types) {
            if (!d.mobTypes.add(t)) continue;
            MobTypeCatalog.Type ty = MobTypeCatalog.type(t);
            String name = ty == null ? Text.titleCase(t) : ty.name();
            int xp = Math.max(0, m.dials.mobTypeSbXp.get());
            if (xp > 0) Sky.service(Features.SkyLevel.class).award(e.killer, "mob_type:" + t, xp, "Slay " + ("AEIOUaeiou".indexOf(name.isEmpty() ? 'x' : name.charAt(0)) >= 0 ? "an " : "a ") + name + " monster");
            Msg.send(e.killer, "&a&lNEW MOB TYPE! &7You slew your first &" + (ty == null ? '7' : ty.color()) + name
                    + " &7monster. &8(" + d.mobTypes.size() + "/" + MobTypeCatalog.TYPES.size() + ", /mobtypes)");
            Msg.sound(e.killer, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.3f);
        }
    }

    Menu menu(Player p) {
        Menu menu = new Menu("Mob Types", 6).chrome();
        CzData d = m.data(p);
        menu.set(4, Icons.of(Material.ZOMBIE_HEAD, "&aMob Types",
                "&7Every monster belongs to one or more", "&7types. Weapons, enchantments, pets", "&7and accessories that name a type", "&7only work against it.", "",
                "&7Types slain: &a" + d.mobTypes.size() + "&7/&a" + MobTypeCatalog.TYPES.size(),
                "&7Each first kill pays &b+" + m.dials.mobTypeSbXp.get() + " SkyBlock XP&7."));
        int slot = 10;
        for (MobTypeCatalog.Type t : MobTypeCatalog.TYPES) {
            if (slot > 43) break;
            List<MobDef> mobs = byType().getOrDefault(t.id(), List.of());
            boolean slain = d.mobTypes.contains(t.id());
            List<String> lore = new ArrayList<>();
            for (String s : t.lesson().split("\n")) lore.add("&7" + s);
            lore.add("");
            lore.add("&7Monsters of this type: &e" + mobs.size());
            List<String> names = new ArrayList<>();
            for (MobDef md : mobs) {
                String n = Text.strip(md.name == null ? md.id : md.name);
                if (!names.contains(n)) names.add(n);
                if (names.size() >= 4) break;
            }
            for (String n : names) lore.add(" &8- &f" + n);
            lore.add("");
            lore.add(slain ? "&aYou have slain one!" : "&cNot slain yet");
            Material icon = Material.matchMaterial(t.icon());
            menu.set(slot, Icons.of(icon == null ? Material.PAPER : icon, "&" + t.color() + t.name(), lore));
            slot = ZoneMenus.next(slot);
        }
        menu.back("Combat Zones", () -> m.menus.main(p));
        return menu;
    }
}
