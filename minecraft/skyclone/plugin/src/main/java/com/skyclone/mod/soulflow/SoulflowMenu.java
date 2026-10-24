package com.skyclone.mod.soulflow;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code /soulflow}: the pool, internalizing, the Overflow and drain toggles, and every Soulstream cost. */
final class SoulflowMenu {

    static final String TITLE = "Soulstream";

    private final SoulflowService svc;
    private SoulflowRuntime rt;

    SoulflowMenu(SoulflowService svc) { this.svc = svc; }

    void runtime(SoulflowRuntime rt) { this.rt = rt; }

    void open(Player p) {
        if (svc.data(p) == null) { Msg.error(p, "Your profile is still loading."); return; }
        build(p).open(p);
    }

    Menu build(Player p) {
        Menu m = new Menu(TITLE, 6).chrome();
        m.drawer(mm -> draw(mm, p));
        m.refreshEvery(20);
        draw(m, p);
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        return m;
    }

    private void draw(Menu m, Player p) {
        SoulflowData d = svc.data(p);
        if (d == null) return;
        SoulflowConfig.Tier t = svc.tier(p);
        String tierName = t == null ? null : name(t.item);
        double target = rt == null ? 0 : rt.overflowTarget(p, t);
        m.set(4, Icons.of(Material.SOUL_LANTERN, "&3Soulstream",
                summary(d.pool, svc.raw(p), svc.poolUsable(p), d.overflow, tierName, d.gained, d.spent, d.converted)));

        // internalize
        int rawInv = Sky.items().count(p, SoulflowService.RAW);
        int sfInv = Sky.items().count(p, "SOULFLOW");
        m.set(20, Icons.of(Material.BLAZE_POWDER, "&aInternalize from Inventory",
                internalizeLore(rawInv, sfInv, "inventory")), c -> {
            Player pl = c.player();
            String why = svc.internalizeBlock(pl);
            if (why != null) { Msg.error(pl, why); return; }
            long got = svc.internalizeInventory(pl, SoulflowService.RAW, Integer.MAX_VALUE)
                    + svc.internalizeInventory(pl, "SOULFLOW", Integer.MAX_VALUE);
            if (got <= 0) Msg.error(pl, "You have no Soulstream to internalize.");
            else Msg.success(pl, "Internalized &3+" + SoulflowMath.units(got) + " Soulstream&a!");
            draw(c.menu(), pl);
        });
        Features.Sacks sacks = Sky.service(Features.Sacks.class);
        int rawSack = safeSack(sacks, p, SoulflowService.RAW), sfSack = safeSack(sacks, p, "SOULFLOW");
        m.set(21, Icons.of(Material.BUNDLE, "&aInternalize from Sacks",
                internalizeLore(rawSack, sfSack, "sacks")), c -> {
            Player pl = c.player();
            String why = svc.internalizeBlock(pl);
            if (why != null) { Msg.error(pl, why); return; }
            int max = Math.max(1, SoulflowDials.SACK_INTERNALIZE_MAX.get());
            long got = svc.internalizeSacks(pl, SoulflowService.RAW, max) + svc.internalizeSacks(pl, "SOULFLOW", max);
            if (got <= 0) Msg.error(pl, "Your sacks hold no Soulstream.");
            else Msg.success(pl, "Internalized &3+" + SoulflowMath.units(got) + " Soulstream &afrom your sacks!");
            draw(c.menu(), pl);
        });

        // overflow
        boolean ovOn = svc.toggle(p, "overflow", true);
        m.set(23, Icons.of(t == null ? Material.GRAY_DYE : Material.LIGHT_BLUE_DYE, "&bOverflow Mana",
                overflowLore(tierName, t == null ? 0 : t.units, target, d.overflow, ovOn, SoulflowDials.OVERFLOW.get())), c -> {
            boolean now = !svc.toggle(c.player(), "overflow", true);
            svc.setToggle(c.player(), "overflow", now);
            Msg.send(c.player(), "&7Overflow Mana conversion: " + (now ? "&aOn" : "&cOff"));
            draw(c.menu(), c.player());
        });

        // drains
        int slot = 24;
        for (SoulflowConfig.Drain dr : svc.config().drains) {
            if (slot > 25 && !dr.toggle) slot = 31;
            boolean owned = dr.item == null || hasAcc(p, dr.item);
            boolean on = !dr.toggle || svc.toggle(p, dr.id, dr.defaultOn);
            boolean paid = rt != null && rt.paid(p, dr.id);
            List<String> lore = drainLore(dr, owned, on, paid);
            String title = SoulflowRuntime.drainName(dr);
            Material mat = dr.item != null ? (on ? Material.LIME_DYE : Material.GRAY_DYE) : Material.LEATHER_CHESTPLATE;
            if (dr.toggle) {
                m.set(slot, Icons.of(mat, title, lore), c -> {
                    boolean now = !svc.toggle(c.player(), dr.id, dr.defaultOn);
                    svc.setToggle(c.player(), dr.id, now);
                    Sky.stats().invalidate(c.player());
                    Msg.send(c.player(), title + "&7: " + (now ? "&aOn" : "&cOff"));
                    draw(c.menu(), c.player());
                });
            } else {
                m.set(slot, Icons.of(mat, title, lore));
            }
            slot++;
        }

        m.set(40, Icons.of(Material.ENDER_EYE, "&5Soulstream Costs", costLore()));
    }

    // ---- lore builders (no Player: the self-test renders them) -------------------------------------

    static List<String> summary(long pool, long raw, boolean usable, double overflow, String tierName,
                                long gained, long spent, long converted) {
        List<String> l = new ArrayList<>();
        l.add("&7Internalized: &3" + SoulflowMath.units(pool) + (usable ? "" : " &c(locked)"));
        l.add("&7Raw in inventory: &3" + SoulflowMath.units(raw));
        l.add("&7Overflow Mana: &3" + Text.commasInt(overflow) + "ʬ");
        l.add("");
        l.add("&7Accessory: " + (tierName == null ? "&cNone" : tierName));
        if (!usable) {
            l.add("&cCarry or bag a Soulstream accessory to");
            l.add("&cdraw on your internalized Soulstream.");
        }
        l.add("");
        l.add("&7Abilities drain your internalized &3⸎");
        l.add("&7first, then loose Raw Soulstream.");
        l.add("");
        l.add("&8Lifetime: +" + SoulflowMath.units(gained) + " / -" + SoulflowMath.units(spent)
                + " / " + SoulflowMath.units(converted) + " to Overflow");
        return l;
    }

    static List<String> internalizeLore(int raw, int sf, String where) {
        List<String> l = new ArrayList<>();
        l.add("&7Raw Soulstream in your " + where + ": &a" + raw + " &8(+" + raw + "⸎)");
        l.add("&7Soulstream in your " + where + ": &a" + sf + " &8(+" + (sf * 160L) + "⸎)");
        l.add("");
        l.add(raw + sf > 0 ? "&eClick to internalize all!" : "&cNothing to internalize.");
        return l;
    }

    static List<String> overflowLore(String tierName, int units, double target, double overflow, boolean on, boolean serverOn) {
        List<String> l = new ArrayList<>();
        if (tierName == null) {
            l.add("&7Needs a Soulstream accessory:");
            l.add("&8Pile 5⸎ -> 200ʬ, Battery 4⸎ -> 400ʬ,");
            l.add("&8Supercell 4⸎ -> 600ʬ.");
        } else {
            l.add("&7" + tierName + "&7: when you leave combat,");
            l.add("&7convert up to &3" + units + "⸎ &7into &b" + Text.commasInt(target) + "ʬ Overflow Mana&7.");
        }
        l.add("&7Overflow pays ability mana once your");
        l.add("&7ordinary mana runs short.");
        l.add("");
        l.add("&7Held: &b" + Text.commasInt(overflow) + "ʬ");
        if (!serverOn) l.add("&cSwitched off on this server.");
        l.add("&7Enabled: " + (on ? "&aOn" : "&cOff"));
        l.add("&eClick to toggle!");
        return l;
    }

    static List<String> drainLore(SoulflowConfig.Drain dr, boolean owned, boolean on, boolean paid) {
        List<String> l = new ArrayList<>();
        l.add("&7Costs &3" + dr.cost + "⸎ &7per &a" + dr.every + "s &7in combat"
                + (dr.sneaking ? " &7while sneaking" : "") + ":");
        for (Map.Entry<String, Double> e : dr.stats.entrySet()) {
            String label = e.getKey();
            try { label = com.skyclone.api.stat.Stat.valueOf(e.getKey()).label(); } catch (RuntimeException ignored) { }
            l.add(" &8- &a+" + Text.commasInt(e.getValue()) + " " + label);
        }
        if (dr.set != null) l.add(" &8- &7the full set's sneaking bonus");
        if (dr.ownerGated && SoulflowDials.OWNER_GATED_DRAINS != null && !SoulflowDials.OWNER_GATED_DRAINS.get())
            l.add("&8Not charged on this server yet.");
        l.add("");
        if (!owned) l.add("&cYou do not carry this accessory.");
        if (paid) l.add("&aActive now.");
        if (dr.toggle) {
            l.add("&7Enabled: " + (on ? "&aOn" : "&cOff"));
            l.add("&eClick to toggle!");
        }
        return l;
    }

    List<String> costLore() {
        Map<String, List<String>> byItem = new LinkedHashMap<>();
        for (SoulflowConfig.Cost c : svc.config().costs) {
            for (String it : c.items) {
                byItem.computeIfAbsent(it, k -> new ArrayList<>()).add("&7" + (c.label == null ? c.ability : c.label)
                        + ": &3" + SoulflowDials.scaled(c.cost) + "⸎" + (c.whenExtra != null ? " &8(merged)" : ""));
            }
        }
        List<String> l = new ArrayList<>();
        l.add("&7Every ability that burns Soulstream:");
        l.add("");
        for (Map.Entry<String, List<String>> e : byItem.entrySet()) {
            l.add(name(e.getKey()));
            for (String s : e.getValue()) l.add(" " + s);
        }
        return l;
    }

    static String name(String itemId) {
        ItemDef d = Sky.items().def(itemId);
        if (d == null) return "&7" + Text.titleCase(itemId);
        return d.rarity.code() + Text.strip(d.name);
    }

    private static boolean hasAcc(Player p, String id) {
        try { return Sky.service(Features.Accessories.class).has(p, id); } catch (RuntimeException e) { return false; }
    }

    private static int safeSack(Features.Sacks s, Player p, String id) {
        try { return Math.max(0, s.count(p, id)); } catch (RuntimeException e) { return 0; }
    }

    static ItemStack icon(Material m, String name, List<String> lore) { return Icons.of(m, name, lore); }
}
