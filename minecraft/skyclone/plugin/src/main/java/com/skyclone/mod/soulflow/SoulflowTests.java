package com.skyclone.mod.soulflow;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Live self-tests ({@code /sb selftest soulflow}). Each is cheap and synchronous. */
final class SoulflowTests {

    private SoulflowTests() {}

    static final String[] OWN = {"RAW_SOULFLOW", "SOULFLOW", "SOUL_ESOWARD"};
    static final String[] BANNED = SoulflowMath.REFERENCE_NAMES;

    static void register(SoulflowModule mod) {
        Sky.commands().selfTest("soulflow.items-exist", () -> {
            for (String id : OWN) {
                ItemDef d = Sky.items().def(id);
                if (d == null) return id + " is not registered";
                if (d.extra == null || !d.extra.has("src") || !"soulflow".equals(d.extra.get("src").getAsString()))
                    return id + " was registered by another module first (ours must win; check ModuleList order)";
            }
            for (SoulflowConfig.Tier t : mod.config().tiers)
                if (!Sky.items().exists(t.item)) return "accessory tier " + t.item + " is not registered (accessories)";
            for (SoulflowConfig.Drain d : mod.config().drains)
                if (d.item != null && !Sky.items().exists(d.item)) return "drain accessory " + d.item + " is not registered";
            for (String c : mod.config().currency.keySet())
                if (!Sky.items().exists(c)) return "currency item " + c + " is not registered";
            return null;
        });

        Sky.commands().selfTest("soulflow.costs-match-items", () -> {
            // every cost points at an ability that really exists on that item: a renamed ability id would silently
            // make the ability free (the item+trigger fallback still catches a re-registered katana)
            List<String> bad = new ArrayList<>();
            int checked = 0;
            for (SoulflowConfig.Cost c : mod.config().costs) {
                for (String it : c.items) {
                    ItemDef d = Sky.items().def(it);
                    if (d == null) continue;   // owner absent or its fallback switched off: nothing to charge
                    checked++;
                    boolean match = false;
                    for (AbilityDef a : d.abilities) {
                        if (a == null) continue;
                        if (mod.config().costFor(a.id, d.id, a.trigger == null ? null : a.trigger.name()) == c) { match = true; break; }
                    }
                    if (!match) bad.add(it + " has no ability matching " + c.ability + "/" + c.trigger);
                }
            }
            if (!bad.isEmpty()) return String.join("; ", bad);
            if (checked == 0) return "none of the Soulstream consumers is registered";
            if (!Sky.items().exists("ETHERWARP_CONDUIT")) return "the Phasewarp Conduit (ETHERWARP_CONDUIT) is not registered";
            return null;
        });

        Sky.commands().selfTest("soulflow.research-numbers", () -> {
            SoulflowConfig c = mod.config();
            if (c.unitsOf("RAW_SOULFLOW") != 1 || c.unitsOf("SOULFLOW") != 160) return "currency is not 1 / 160";
            String e = tier(c, "SOULFLOW_PILE", 5, 200);
            if (e == null) e = tier(c, "SOULFLOW_BATTERY", 4, 400);
            if (e == null) e = tier(c, "SOULFLOW_SUPERCELL", 4, 600);
            if (e != null) return e;
            if (Math.abs(SoulflowMath.overflowTarget(600, 30) - 780) > 1e-9) return "Supercell + Fumes should cap at 780 Overflow";
            SoulflowMath.Conversion cv = SoulflowMath.convert(200, 5, 0, 100);
            if (cv.units() != 5 || Math.abs(cv.overflowAfter() - 200) > 1e-9) return "Pile from empty: " + cv;
            Map<String, Long> want = Map.of("weapons_ether_transmission", 1L, "weapons_soulcry_atomsplit", 2L,
                    "weapons_gravity_storm", 10L, "weapons_cells_alignment", 2L, "weapons_salvation", 1L,
                    "slayer_grimoire", 15L, "soulflow_soulward", 10L);
            for (Map.Entry<String, Long> w : want.entrySet()) {
                SoulflowConfig.Cost k = c.costFor(w.getKey(), null, null);
                if (k == null || k.cost != w.getValue()) return w.getKey() + " should cost " + w.getValue() + " (research 06 / NEU)";
            }
            return null;
        });

        Sky.commands().selfTest("soulflow.service-provided", () -> {
            if (!Sky.has(Features.Soulflow.class)) return "Features.Soulflow is not provided";
            Features.Soulflow s = Sky.service(Features.Soulflow.class);
            if (!(s instanceof SoulflowService)) return "Features.Soulflow is provided by someone else: " + s.getClass().getName();
            if (s.consume(null, 1, "selftest")) return "consume(null) succeeded";
            if (s.soulflow(null) != 0) return "soulflow(null) is not 0";
            SoulflowMath.Plan pl = SoulflowMath.plan(3, true, 2, true, 6);
            if (pl.ok()) return "a 6 charge against 3+2 must be refused";
            pl = SoulflowMath.plan(3, true, 2, true, 5);
            if (!pl.ok() || pl.fromPool() != 3 || pl.fromRaw() != 2) return "5 against 3+2 should take 3 from the pool and 2 raw: " + pl;
            return null;
        });

        Sky.commands().selfTest("soulflow.menu-and-names", () -> {
            List<String> all = new ArrayList<>();
            all.addAll(SoulflowMenu.summary(160, 3, false, 420, "&aPile", 10, 5, 2));
            all.addAll(SoulflowMenu.internalizeLore(3, 1, "inventory"));
            all.addAll(SoulflowMenu.overflowLore(null, 0, 0, 0, true, true));
            all.addAll(SoulflowMenu.overflowLore("&9Battery", 4, 400, 120, false, false));
            for (SoulflowConfig.Drain d : mod.config().drains) all.addAll(SoulflowMenu.drainLore(d, false, true, true));
            all.addAll(mod.menuForTests().costLore());
            for (String id : OWN) {
                ItemDef d = Sky.items().def(id);
                if (d == null) continue;
                all.add(d.name);
                all.addAll(d.lore);
                for (AbilityDef a : d.abilities) { all.add(a.name); all.addAll(a.lore); }
            }
            for (String line : all) {
                String low = Text.strip(line).toLowerCase(java.util.Locale.ROOT);
                for (String b : BANNED) if (low.contains(b)) return "reference name '" + b + "' shown to players: " + line;
            }
            return all.size() < 20 ? "menu lore came out empty" : null;
        });
    }

    private static String tier(SoulflowConfig c, String id, int units, double overflow) {
        SoulflowConfig.Tier t = c.tier(id);
        if (t == null) return id + " missing from tiers";
        if (t.units != units || Math.abs(t.overflow - overflow) > 1e-9) return id + " should convert " + units + " -> " + overflow;
        return null;
    }
}
