package com.skyclone.mod.skills;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.skill.SkillType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

/** Live self-tests for the wave-5 additions ({@code /sb selftest skills}). */
final class Wave5Tests {

    private Wave5Tests() {}

    static void register(SkillServiceImpl svc, SkillTable t, PetTrainer trainer, SkillNpcs npcs) {
        var c = Sky.commands();

        c.selfTest("skills.trainer-sessions", trainer::validate);

        c.selfTest("skills.trainer-payments", () -> {
            // only Free is free, only Turbo costs Bits, nothing pays coins OUT (claiming gives pet XP only)
            for (TrainerTiers.Tier tt : TrainerTiers.TIERS) {
                boolean free = tt.id().equals("free");
                if (free && (trainer.coins(tt) != 0 || trainer.bits(tt) != 0)) return "the Free session costs something";
                if (!free && trainer.costScale.get() > 0 && trainer.coins(tt) <= 0) return tt.id() + " costs no coins";
                if (!tt.id().equals("turbo") && trainer.bits(tt) != 0) return tt.id() + " costs Bits";
            }
            if (!Sky.has(Features.Pets.class)) return "no Pets service: sessions can never be started";
            return null;
        });

        c.selfTest("skills.npc-names", () -> {
            // our two people must not share a name word with anyone else (core's one-name-one-person rule)
            List<String> ours = List.of(SkillsModule.PEDESTAL_NAME, PetTrainer.NPC_NAME);
            for (var d : Sky.npcs().all()) {
                if (d == null || d.name == null || d.id.equals(SkillsModule.PEDESTAL_NPC) || d.id.equals(PetTrainer.NPC_ID)) continue;
                String other = com.skyclone.api.ui.Text.strip(d.name).toLowerCase();
                for (String n : ours) {
                    String w = com.skyclone.api.ui.Text.strip(n).toLowerCase();
                    String last = w.substring(w.lastIndexOf(' ') + 1);
                    if (other.contains(last)) return d.id + " (" + d.name + ") shares a name with " + n;
                }
            }
            return null;
        });

        c.selfTest("skills.npcs-placed", () -> {
            List<String> bad = new ArrayList<>();
            World hub = Bukkit.getWorld("sb_" + SkillNpcs.HUB);
            for (SkillNpcs.Spot s : npcs.spots) {
                if (s.mode.startsWith("not started")) { bad.add(s.label + ": never registered (postEnable did not run?)"); continue; }
                if (s.mode.startsWith("off") || s.mode.startsWith("yielded")) continue;
                if (Sky.npcs().def(s.npcId) == null) { bad.add(s.label + ": not registered"); continue; }
                if (!s.mode.startsWith("auto") && !s.mode.startsWith("dial")) continue;   // a hub anchor: core's ui.npc-* tests cover it
                if (hub == null) continue;                                               // Hub not loaded: nothing to place yet
                Location near = Sky.worlds().anchor(SkillNpcs.HUB, s.nearAnchor);
                if (near == null) { bad.add(s.label + ": Hub has no " + s.nearAnchor + " anchor to stand beside"); continue; }
                boolean loaded = near.getWorld() != null && near.getWorld().isChunkLoaded(near.getBlockX() >> 4, near.getBlockZ() >> 4);
                if (loaded && s.placed == null && npcs.target(s, near.getWorld()) == null)
                    bad.add(s.label + ": no free standable block 2-6 from " + s.nearAnchor + " (set one: /sb skillnpc " + s.label.toLowerCase().replace(' ', '-') + " here)");
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        c.selfTest("skills.runebook", () -> {
            if (TrainerTiers.digits(0) != 0 || TrainerTiers.digits(9) != 1 || TrainerTiers.digits(10) != 2 || TrainerTiers.digits(999) != 3)
                return "digit count is wrong";
            if (!Sky.items().exists(SkillServiceImpl.RUNEBOOK)) return "RUNEBOOK not registered (owner: accessories)";
            if (!Sky.has(Features.Accessories.class)) return "no Accessories service: the Runebook can never count";
            return null;
        });

        c.selfTest("skills.furniture-unlocks", () -> {
            int n = 0;
            List<String> missing = new ArrayList<>();
            for (int L = 1; L <= t.maxLevel(SkillType.CARPENTRY); L++)
                for (SkillTable.Unlock u : t.level(SkillType.CARPENTRY, L).unlocks) {
                    if (!"furniture".equals(u.type)) continue;
                    n++;
                    ItemDef d = SkillText.item(u.id);
                    if (d == null || !d.tags.contains("furniture")) missing.add(u.id);
                }
            if (n != 37) return "Carpentry road names " + n + " furniture pieces (research: 37 at levels 1-25)";
            return missing.isEmpty() ? null : missing.size() + " furniture ids not registered as furniture (owner: crafting): " + missing;
        });

        c.selfTest("skills.average-rule", () -> {
            if (svc.counted(SkillType.SOCIAL) || svc.counted(SkillType.RUNECRAFTING)) return "Social / Runecrafting counted in the average";
            for (SkillType s : SkillType.values())
                if (s != SkillType.SOCIAL && s != SkillType.RUNECRAFTING && s != SkillType.HUNTING && s != SkillType.CARPENTRY && !svc.counted(s))
                    return s + " not counted in the average";
            if (svc.counted(SkillType.CARPENTRY) != svc.dials.averageCarpentry.get()) return "Carpentry ignores its dial";
            String v = svc.dials.averageHunting.get();
            boolean want = "true".equalsIgnoreCase(v) || (!"false".equalsIgnoreCase(v) && Sky.has(Features.Hunting.class));
            if (svc.counted(SkillType.HUNTING) != want) return "Hunting counted=" + svc.counted(SkillType.HUNTING) + " but dial '" + v + "' says " + want;
            return null;
        });
    }
}
