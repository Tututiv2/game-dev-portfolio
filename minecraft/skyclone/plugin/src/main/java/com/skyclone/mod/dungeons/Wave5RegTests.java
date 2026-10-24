package com.skyclone.mod.dungeons;

import com.skyclone.api.Sky;
import com.skyclone.api.mob.MobDef;
import com.skyclone.mod.dungeons.loot.LootNames;
import com.skyclone.mod.dungeons.loot.LootTables;
import com.skyclone.mod.dungeons.model.Balance;
import com.skyclone.mod.dungeons.model.FloorDef;
import com.skyclone.mod.dungeons.run.DungeonRun;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

/** wave-5 regression fixer live self-tests ({@code /sb selftest dungeons.w5r}): solo rooms and titled books. */
final class Wave5RegTests {

    private Wave5RegTests() {}

    static void register() {
        // every enchanted book a dungeon chest can roll is titled with its enchant; anything else keeps its own name
        Sky.commands().selfTest("dungeons.w5r-book-titles", () -> {
            if (Sky.items().exists("ENCHANTMENT_ULTIMATE_COMBO_1") && !LootNames.plain("ENCHANTMENT_ULTIMATE_COMBO_1").equals("Enchanted Book (Combo I)"))
                return "Combo I book reads '" + LootNames.plain("ENCHANTMENT_ULTIMATE_COMBO_1") + "'";
            int books = 0;
            for (FloorDef f : Floors.all()) {
                LootTables.FloorLoot fl = LootTables.floor(f.id);
                if (fl == null) continue;
                for (LootTables.Entry e : fl.entries) {
                    if (!Sky.items().exists(e.item)) continue;
                    String n = LootNames.plain(e.item);
                    if (e.item.startsWith("ENCHANTMENT_")) {
                        books++;
                        if (!n.startsWith("Enchanted Book (") || !n.endsWith(")")) return f.id + " " + e.item + " reads '" + n + "'";
                    } else if (n.contains("Enchanted Book")) return e.item + " was titled as a book: '" + n + "'";
                }
            }
            return books == 0 ? "no enchanted book in any floor's chest table" : null;
        });
        // a solo run's rooms scale like its boss floor; a full party is untouched; boss bodies/adds/puzzles are not rooms
        Sky.commands().selfTest("dungeons.w5r-room-party-scale", () -> {
            for (String k : new String[]{"star", "mini", "blood", "mimic", "crypt"})
                if (!DungeonRun.roomSide(k)) return k + " is not scaled as a room mob";
            for (String k : new String[]{"boss", "summon", "puzzle", null})
                if (DungeonRun.roomSide(k)) return k + " is scaled as a room mob";
            double hp = DDials.SOLO_ROOM_HP.get(), dmg = DDials.SOLO_ROOM_DMG.get();
            if (Math.abs(Balance.partyScale(1, hp) - Math.max(0, hp)) > 1e-9) return "solo HP scale is not the dial";
            if (Math.abs(Balance.partyScale(5, dmg) - 1) > 1e-9) return "a full party's damage is scaled";
            return null;
        });
        // the shipping F1 pack (live mob defs, live dials) costs the measured solo character at most 40% of his health
        Sky.commands().selfTest("dungeons.w5r-f1-pack-model", () -> {
            FloorDef f = Floors.get("F1");
            if (f == null || f.mobPool.isEmpty()) return "no F1 mob pool";
            double raw = 0, hp = 0;
            for (String id : f.mobPool) {
                MobDef d = Sky.mobs().def(id);
                if (d == null) return "F1 mob " + id + " not registered";
                raw += d.damage / f.mobPool.size();
                hp += d.health / f.mobPool.size();
            }
            double sd = Balance.partyScale(1, DDials.SOLO_ROOM_DMG.get()), sh = Balance.partyScale(1, DDials.SOLO_ROOM_HP.get());
            double taken = Balance.packTaken(f.mobsPerCell, raw * f.dmgScale * DDials.MOB_DMG_SCALE.get() * sd,
                    hp * f.hpScale * DDials.MOB_HP_SCALE.get() * sh, Balance.EARLY);
            return taken <= Balance.EARLY.health() * 0.4 ? null
                    : "an average solo F1 pack costs " + Math.round(taken) + " of " + Math.round(Balance.EARLY.health()) + " HP at the current dials";
        });
        // a room mob notices players at room-aggro-range, not vanilla's 35
        Sky.commands().selfTest("dungeons.w5r-room-aggro", () -> {
            double want = DDials.ROOM_AGGRO.get();
            if (!(want > 0)) return null;                          // dial off: vanilla range by design
            World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (w == null) return "no world";
            Location at = w.getSpawnLocation().clone();
            at.setY(Math.min(w.getMaxHeight() - 2, at.getY() + 60));
            LivingEntity z = (LivingEntity) w.spawnEntity(at, EntityType.ZOMBIE);
            try {
                AttributeInstance fr = z.getAttribute(Attribute.FOLLOW_RANGE);
                if (fr == null) return "zombie has no follow range";
                DungeonRun.packAggro(z);
                return Math.abs(fr.getBaseValue() - Math.max(4, want)) < 1e-6 ? null : "follow range " + fr.getBaseValue() + ", want " + want;
            } finally {
                z.remove();
            }
        });
    }
}
