package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.mob.DropDef;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code /sb selftest rift}: the Rift's data, layout and rules hang together. */
final class RiftSelfTests {

    private RiftSelfTests() {}

    static final String[] REWARDS = {"CHICKEN_LEGGS", "GUNTHER_SNEAKERS", "HEALING_MELON", "METAL_HEART", "WILTED_BERBERIS", "AGARICUS_CAP"};

    static void register(RiftModule m) {
        Sky.commands().selfTest("rift.items-exist", () -> {
            List<String> miss = new ArrayList<>();
            for (RiftEconomy.Shop s : m.economy.shops.values())
                for (RiftEconomy.Offer o : s.offers()) if (!Sky.items().exists(o.item())) miss.add("shop " + s.id() + ": " + o.item());
            for (String id : RiftWorldState.GATHER.values()) if (!Sky.items().exists(id)) miss.add("gather: " + id);
            for (String id : REWARDS) if (!Sky.items().exists(id)) miss.add("reward: " + id);
            for (MobDef d : Sky.mobs().all()) {
                if (!RiftCombat.isRiftMob(d)) continue;
                for (DropDef dd : d.drops) if (!Sky.items().exists(dd.item)) miss.add("drop of " + d.id + ": " + dd.item);
            }
            for (Recipe r : Sky.recipes().all()) {
                if (r.id == null || !r.id.startsWith("rift:")) continue;
                for (String cell : r.grid) {
                    if (cell == null || cell.isEmpty()) continue;
                    String id = Sky.items().exists(cell) ? cell : cell.substring(0, cell.lastIndexOf(':') < 0 ? cell.length() : cell.lastIndexOf(':'));
                    if (!Sky.items().exists(id)) miss.add("recipe " + r.result + ": " + cell);
                }
            }
            return miss.isEmpty() ? null : miss.size() + " missing: " + miss.subList(0, Math.min(8, miss.size()));
        });

        Sky.commands().selfTest("rift.layout", () -> {
            Layout l = Sky.worlds().layout(RiftCatalog.ISLAND);
            if (l == null) return "no rift layout yet (island not generated)";
            List<String> miss = new ArrayList<>();
            for (String a : RiftCatalog.NPC_ANCHORS) if (l.anchor(a) == null) miss.add(a);
            for (String a : RiftCatalog.POINT_ANCHORS) if (l.anchor(a) == null) miss.add(a);
            for (String a : RiftCatalog.MOB_AREAS) if (l.area(a) == null) miss.add("area " + a);
            for (String a : RiftCatalog.OTHER_AREAS) if (l.area(a) == null) miss.add("area " + a);
            for (String s : RiftCatalog.soulIds()) {
                if (l.anchor(s) == null) miss.add(s);
                else if (!l.data().containsKey(s + ".hint")) miss.add(s + ".hint");
            }
            for (int i = 1; i <= RiftCatalog.EGGS; i++) if (l.anchor("egg." + i) == null) miss.add("egg." + i);
            for (int i = 1; i <= RiftCatalog.HEART_SPOTS; i++) if (l.anchor("heart." + i) == null) miss.add("heart." + i);
            for (int i = 1; i <= RiftCatalog.RACE_CHECKPOINTS; i++) if (l.anchor("race." + i) == null) miss.add("race." + i);
            int motes = 0;
            for (String a : l.anchors().keySet()) if (a.startsWith("mote.")) motes++;
            if (motes < 40) miss.add("only " + motes + " mote orbs");
            return miss.isEmpty() ? null : "missing " + miss;
        });

        Sky.commands().selfTest("rift.gates", () -> {
            Layout l = Sky.worlds().layout(RiftCatalog.ISLAND);
            List<String> bad = new ArrayList<>();
            for (RiftCatalog.Area a : RiftCatalog.AREAS) {
                for (String c : a.gateCharms()) if (RiftCatalog.charm(c) == null) bad.add(a.id() + " gate names unknown charm " + c);
                if (l == null) continue;
                boolean found = false;
                for (Layout.Zone z : l.zones()) {
                    if (!z.id().equals(a.id())) continue;
                    found = true;
                    String want = a.requirementKey();
                    if (want == null ? z.requirement() != null : !want.equals(z.requirement())) bad.add(a.id() + " zone requirement " + z.requirement());
                    if (!z.flags().contains(Layout.Zone.NO_FALL)) bad.add(a.id() + " lacks NO_FALL");
                }
                if (!found) bad.add("no zone for area " + a.id());
                if (a.requirementKey() != null && Sky.requirements().describe(a.requirementKey()).isBlank()) bad.add(a.id() + " gate has no description");
            }
            for (RiftCatalog.Charm c : RiftCatalog.CHARMS) if (RiftCatalog.area(c.area()) == null) bad.add("charm " + c.id() + " in unknown area");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("rift.math", () -> {
            long h4 = 4 * 3_600_000L;
            RiftMath.Infusions a = RiftMath.accrue(0, 1000, 1000 + 2 * h4 + 5, h4, 3);
            if (a.count() != 2) return "0 infusions + 8h should be 2, got " + a.count();
            if (RiftMath.accrue(2, 1000, 1000 + 10 * h4, h4, 3).count() != 3) return "infusions must cap at 3";
            if (RiftMath.heartDamage(1, 100, 10) != 10) return "1 heart of 10 on 100 HP should be 10";
            if (RiftMath.maxTime(480, 2, 60, 9, 4, 5, 15) != 480 + 120 + 10 + 15) return "max time sum wrong";
            if (!RiftMath.clock(452).equals("7m 32s")) return "clock(452) = " + RiftMath.clock(452);
            for (long seed = 0; seed < 40; seed++) {
                long[] rows = RiftMath.mazePath(11, 17, seed);
                for (int r = 0; r < rows.length; r++) if (rows[r] == 0) return "maze row " + r + " empty for seed " + seed;
                for (int r = 0; r + 1 < rows.length; r++) if ((rows[r] & rows[r + 1]) == 0) return "maze rows " + r + "/" + (r + 1) + " do not touch, seed " + seed;
            }
            RiftCatalog.Area colo = RiftCatalog.area("colosseum");
            if (RiftMath.passesGate(colo, List.of("living"), 899) || !RiftMath.passesGate(colo, List.of("living"), 900)) return "colosseum gate is not 15:00 + Quickmetal";
            return null;
        });

        Sky.commands().selfTest("rift.economy", () -> {
            if (m.economy.loadError != null) return m.economy.loadError;
            if (m.economy.shops.isEmpty() || m.economy.sell.isEmpty() || m.economy.transferable.isEmpty()) return "economy.json is empty";
            List<String> loops = new ArrayList<>();
            for (RiftEconomy.Shop s : m.economy.shops.values())
                for (RiftEconomy.Offer o : s.offers()) if (m.economy.sellValue(o.item()) >= o.motes()) loops.add(o.item() + " buy " + o.motes() + " sell " + m.economy.sellValue(o.item()));
            for (RiftEconomy.Shop s : m.economy.shops.values())
                for (RiftEconomy.Offer o : s.offers()) if (o.requirement() != null && Sky.requirements().describe(o.requirement()).isBlank()) loops.add("undescribed " + o.requirement());
            return loops.isEmpty() ? null : "mote loops: " + loops;
        });

        Sky.commands().selfTest("rift.mobs", () -> {
            List<String> bad = new ArrayList<>();
            int n = 0;
            for (MobDef d : Sky.mobs().all()) {
                if (!RiftCombat.isRiftMob(d)) continue;
                n++;
                if (!d.extra.has("hearts")) bad.add(d.id + " has no hearts");
                if (d.health <= 0) bad.add(d.id + " has no health");
            }
            if (n < 15) bad.add("only " + n + " rift mobs registered");
            for (String b : RiftCombat.BOSSES) {
                MobDef d = Sky.mobs().def(b);
                if (d == null) bad.add("boss " + b + " missing");
                else if (d.behaviours.isEmpty()) bad.add("boss " + b + " has no behaviour");
            }
            int vamp = 0;
            for (MobDef d : Sky.mobs().all()) if (RiftCombat.isRiftMob(d) && "vampire".equals(d.slayer)) vamp++;
            if (vamp == 0) bad.add("no rift mob counts for the vampire slayer");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("rift.menus-build", () -> {
            Player p = null;
            for (Player q : Bukkit.getOnlinePlayers()) if (Sky.profiles().isReady(q)) { p = q; break; }
            if (p == null) return null;                    // nothing to build against with nobody online
            Player fp = p;
            try {
                m.menus.overview(fp);
                m.menus.souls(fp);
                m.menus.charms(fp);
                m.menus.areas(fp);
                m.menus.shop(fp, "glade_peddler");
                m.menus.entry(fp);
                m.gallery.menu(fp);
                m.splitSteal.menu(fp);
                m.menus.shop(fp, "curator");
                m.cat.menu(fp);
                m.cat.eyesMenu(fp);
            } finally {
                fp.closeInventory();
            }
            return null;
        });

        Sky.commands().selfTest("rift.wave3", () -> {
            List<String> bad = new ArrayList<>();
            // Split or Steal never creates motes, and the pot halves cleanly
            long pot = m.dials.splitStealPot.get();
            for (boolean x : new boolean[]{true, false}) for (boolean y : new boolean[]{true, false}) {
                long[] r = RiftMath.splitSteal(x, y, pot);
                if (r[0] + r[1] > pot) bad.add("split/steal pays " + (r[0] + r[1]) + " from a pot of " + pot);
            }
            if (m.economy.shops.get("curator") == null) bad.add("no curator shop in economy.json");
            for (String id : new String[]{RiftOdonata.EMPTY, RiftOdonata.BOTTLED}) if (!Sky.items().exists(id)) bad.add("odonata item " + id + " not registered (materials)");
            if (m.economy.sellValue(RiftOdonata.BOTTLED) <= 0) bad.add("the Mote Monger does not buy Bottled Odonata");
            for (String id : RiftGallery.SCALED) if (!Sky.items().exists(id)) bad.add("gallery-scaled item " + id + " not registered (accessories)");
            for (RiftCatalog.Charm c : RiftCatalog.CHARMS) if (!RiftGallery.LOOK.containsKey(c.id())) bad.add("no exhibit look for " + c.id());
            Layout l = Sky.worlds().layout(RiftCatalog.ISLAND);
            if (l != null) {
                if (RiftGallery.spots() != RiftCatalog.CHARMS.size()) bad.add("gallery exhibit spots " + RiftGallery.spots() + "/" + RiftCatalog.CHARMS.size() + " (island built before generator v2?)");
                if (l.anchor("npc.curator") == null) bad.add("npc.curator anchor missing");
                if (l.anchor("npc.split_steal") == null) bad.add("npc.split_steal anchor missing");
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        // ---- wave 5 ----
        Sky.commands().selfTest("rift.riddles-catalog", () -> {
            List<String> bad = new ArrayList<>();
            if (!RiftEnigma.consistent()) bad.add("RiftEnigma riddles per area do not match SOULS_PER_AREA");
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (RiftEnigma.Riddle r : RiftEnigma.RIDDLES) {
                if (!ids.add(r.id())) bad.add("duplicate riddle " + r.id());
                if (r.kind() == RiftEnigma.Kind.SHOP && r.cost() <= 0) bad.add(r.id() + " sells for nothing");
                if (r.npc() != null && !RiftEnigma.NPCS.containsKey(r.npc())) bad.add(r.id() + " npc " + r.npc());
                if (r.npc() != null && Sky.npcs().def(RiftEnigma.npcId(r.npc())) == null) bad.add("npc not registered " + r.npc());
            }
            for (String id : new String[]{"BERBERIS_BLOWGUN", "DEAD_CAT_DETECTOR", "TIME_GUN"}) if (!Sky.items().exists(id)) bad.add("item " + id);
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("rift.riddles-layout", () -> {
            if (Sky.worlds().layout(RiftCatalog.ISLAND) == null) return "rift layout not loaded";
            m.riddles.index();
            List<String> bad = new ArrayList<>();
            String c = m.riddles.check();
            if (c != null) bad.add(c);
            int degraded = 0;
            for (RiftRiddles.Puzzle pz : m.riddles.puzzles().values()) if (pz.degraded()) degraded++;
            if (degraded > 4) bad.add(degraded + " riddles have no props (island built before generator v3?)");
            if (m.riddles.buttonCount() < 4) bad.add("only " + m.riddles.buttonCount() + " buttons");
            String cat = m.cat.check();
            if (cat != null) bad.add(cat);
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("rift.riddles-math", () -> {
            List<String> bad = new ArrayList<>();
            // NEU lore of FRACTURED_MONTEZUMA_SOUL: 1 piece = +25s (RARE), 6 pieces = +100s and +12% Mana Regen (EPIC)
            if (RiftEnigma.catRiftTime(1) != 25 || RiftEnigma.catRiftTime(6) != 100) bad.add("Pharaoh rift time");
            if (RiftEnigma.catManaRegen(6) != 12 || RiftEnigma.catManaRegen(1) != 0) bad.add("Pharaoh mana regen");
            if (RiftEnigma.catRarity(1) != 2 || RiftEnigma.catRarity(6) != 3) bad.add("Pharaoh rarity");
            if (RiftEnigma.cloakClaimable(7) != 4 || RiftEnigma.cloakClaimable(52) != 42) bad.add("cloak steps");
            if (RiftEnigma.boxToOpen(0, 0, 3) == 0 || RiftEnigma.boxToOpen(2, 1, 3) != 0) bad.add("Monty Hall box");
            if (RiftEnigma.averageTarget(List.of(5, 5, 5), 5) != 5) bad.add("averages");
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        Sky.commands().selfTest("rift.features-motes", () -> {
            var f = Sky.service(com.skyclone.api.feature.Features.Rift.class);
            Player p = null;
            for (Player q : Bukkit.getOnlinePlayers()) if (Sky.profiles().isReady(q)) { p = q; break; }
            if (p == null) return Sky.has(com.skyclone.api.feature.Features.Rift.class) ? null : "Features.Rift not provided";
            RiftData d = m.data(p);
            long before = d.motes;
            if (f.motes(p) != before) return "motes() disagrees with the profile";
            if (f.spendMotes(p, before + 1, "selftest")) return "spent more Motes than the player has";
            if (d.motes != before) return "a refused spend took Motes";
            return null;
        });

        Sky.commands().selfTest("rift.warp-and-npcs", () -> {
            List<String> bad = new ArrayList<>();
            if (!Sky.worlds().warps().contains("rift")) bad.add("warp rift not registered");
            if (Sky.worlds().anchor("hub", "warp.wizard_tower") == null) bad.add("hub anchor warp.wizard_tower missing (Warden copy has nowhere to stand)");
            for (Map.Entry<String, String> e : Map.of("rift_warden", "npc.rift_wizard", "rift_riddler", "npc.riddler", "rift_wilma", "npc.wilma").entrySet())
                if (Sky.npcs().def(e.getKey()) == null) bad.add("npc " + e.getKey());
            return bad.isEmpty() ? null : String.join("; ", bad);
        });
    }
}
