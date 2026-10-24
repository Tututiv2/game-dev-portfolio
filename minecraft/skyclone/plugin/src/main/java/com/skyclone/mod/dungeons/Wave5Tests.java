package com.skyclone.mod.dungeons;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.mod.dungeons.gear.StarredWeapons;
import com.skyclone.mod.dungeons.loot.LootTables;
import com.skyclone.mod.dungeons.model.DungeonPerks;
import com.skyclone.mod.dungeons.model.FloorDef;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/** Wave 5 (2026-10-06) live self-tests ({@code /sb selftest dungeons.w5}). */
final class Wave5Tests {

    private Wave5Tests() {}

    /** A throwaway listener that doubles the self-test's own XP event (track "selftest"). */
    static final class Doubler implements Listener {
        int seen;
        @EventHandler(priority = EventPriority.HIGHEST)
        public void on(SkyEvents.DungeonXpGain e) {
            if (!"selftest".equals(e.track)) return;
            seen++;
            e.amount *= 2;
        }
    }

    static void register() {
        // the XP event is fired and its (scaled) amount is what Progress adds; source "admin" so accessories' ring skips it
        Sky.commands().selfTest("dungeons.w5-xp-event", () -> {
            Doubler d = new Doubler();
            Bukkit.getPluginManager().registerEvents(d, Sky.plugin());
            try {
                double out = Progress.fire(null, "selftest", "MAGE", 3, true, 100, "admin");
                if (d.seen != 1) return "DungeonXpGain was not delivered (seen " + d.seen + ")";
                if (Math.abs(out - 200) > 1e-6) return "listener's change was not kept: " + out;
            } finally {
                HandlerList.unregisterAll(d);
            }
            return null;
        });
        Sky.commands().selfTest("dungeons.w5-master-mode-service", () -> {
            try {
                Progress.class.getDeclaredMethod("masterMode", Player.class);
            } catch (NoSuchMethodException e) {
                return "Progress does not override Features.Dungeons.masterMode";
            }
            for (FloorDef f : Floors.masters()) {
                if (!f.master) return f.id + " is listed as Master but not flagged";
                if (f.secretPct < 1.0) return f.id + " needs " + f.secretPct + " secrets (Master Mode: 100%)";
                if (f.requirement == null || !f.requirement.contains("F7")) return f.id + " is not gated on Floor VII";
            }
            return null;
        });
        Sky.commands().selfTest("dungeons.w5-stars-and-medallion", () -> {
            // 5 essence + 5 master stars: +40% (8% x 5) on a normal floor, +65% on Master Mode
            if (Math.abs(DungeonPerks.starFraction(10, false, 0.10, 0.05) - 0.40) > 1e-9) return "master stars apply outside Master Mode";
            if (Math.abs(DungeonPerks.starFraction(10, true, 0.10, 0.05) - 0.65) > 1e-9) return "master stars missing in Master Mode";
            if (DungeonPerks.medallionPercent(9) != 1 || DungeonPerks.medallionPercent(12_345) != 5 || DungeonPerks.medallionPercent(9_999_999) != 5)
                return "General's Medallion digits wrong";
            return null;
        });
        Sky.commands().selfTest("dungeons.w5-floor-gate", () -> {
            if (!"F2".equals(DungeonPerks.previousFloor("F3")) || !"M3".equals(DungeonPerks.previousFloor("M4"))) return "previous floor wrong";
            if (DungeonPerks.previousFloor("F1") != null || DungeonPerks.previousFloor("M1") != null || DungeonPerks.previousFloor("E") != null)
                return "a first floor has a previous floor";
            for (String id : new String[]{"F1", "F2", "F3", "F4", "F5", "F6", "F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7"}) {
                String prev = DungeonPerks.previousFloor(id);
                if (prev != null && Floors.get(prev) == null) return id + " needs missing floor " + prev;
            }
            return null;
        });
        Sky.commands().selfTest("dungeons.w5-content", () -> {
            for (String id : new String[]{"dboss_apostle", "dboss_wither_husk", "dboss_giant_diamond"})
                if (Sky.mobs().def(id) == null) return "mob " + id + " not registered";
            for (String id : new String[]{"DYE_LIVID", "DYE_NECRON"}) if (!Sky.items().exists(id)) return id + " not registered";
            if (!hasEntry("F5", "DYE_LIVID") || !hasEntry("M5", "DYE_LIVID")) return "Lurid Dye is not in the F5/M5 chests";
            if (!hasEntry("F7", "DYE_NECRON") || !hasEntry("M7", "DYE_NECRON")) return "Morvane Dye is not in the F7/M7 chests";
            return null;
        });
        Sky.commands().selfTest("dungeons.w5-starred-weapons", () -> {
            if (StarredWeapons.ROWS.isEmpty()) return "starred_weapons.json not loaded";
            for (StarredWeapons.Row r : StarredWeapons.ROWS) {
                ItemDef base = Sky.items().def(r.base);
                ItemDef st = Sky.items().def(r.id);
                if (base == null) continue;                       // the weapons module did not register the base: nothing to copy
                if (st == null) return r.id + " was not registered (" + StarredWeapons.SKIPPED + ")";
                if (st.dungeon != r.isDungeon()) return r.id + (r.isDungeon() ? " is not a dungeon item" : " is marked a dungeon item");
                if (st.damage + 1e-9 < base.damage) return r.id + " hits softer than " + r.base;
                if (Sky.recipes().producing(r.id).isEmpty() && r.grid != null) return r.id + " has no recipe";
            }
            return null;
        });
    }

    private static boolean hasEntry(String floor, String item) {
        LootTables.FloorLoot f = LootTables.floor(floor);
        if (f == null) return false;
        for (LootTables.Entry e : f.entries) if (item.equals(e.item)) return true;
        return false;
    }
}
