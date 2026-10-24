package com.skyclone.mod.weapons;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Player menus: the Arsenal ({@code /weapons}: every weapon by type, what it does, where it comes
 * from, and a live readout of the weapon in your hand) and the Arrow Selector ({@code /arrows}).
 */
final class WeaponMenus {

    enum Kind {
        SWORDS("Swords", Material.IRON_SWORD), BOWS("Bows", Material.BOW), WANDS("Wands & Staffs", Material.BLAZE_ROD),
        ARROWS("Arrows & Poisons", Material.ARROW), TOOLS("Utility", Material.FISHING_ROD);
        final String title;
        final Material icon;
        Kind(String title, Material icon) { this.title = title; this.icon = icon; }
    }

    private final Traits traits;

    WeaponMenus(Traits traits) { this.traits = traits; }

    /** Set by the module (per-target Magic Find, Labyrinth Blade tiers). */
    TargetBonuses targets;
    Passives passives;

    static Kind kindOf(ItemDef d) {
        if (d.category == ItemCategory.BOW) return Kind.BOWS;
        if (d.category == ItemCategory.WAND) return Kind.WANDS;
        if (d.category == ItemCategory.ARROW || d.has("arrow_poison")) return Kind.ARROWS;
        if (d.category == ItemCategory.SWORD || d.category == ItemCategory.LONGSWORD || d.category == ItemCategory.FISHING_WEAPON)
            return Kind.SWORDS;
        return Kind.TOOLS;
    }

    List<ItemDef> items(Kind k) {
        List<ItemDef> out = new ArrayList<>();
        for (String id : traits.ownedIds()) {
            ItemDef d = Sky.items().def(id);
            if (d != null && kindOf(d) == k) out.add(d);
        }
        out.sort(Comparator.comparingInt((ItemDef d) -> d.rarity.ordinal()).thenComparingDouble(d -> d.damage)
                .thenComparing(d -> Text.strip(d.name)));
        return out;
    }

    // ---- the Arsenal ---------------------------------------------------------------------------

    Menu arsenal(Player p) {
        Menu m = new Menu("Arsenal", 6).chrome();
        m.set(4, Icons.of(Material.DIAMOND_SWORD, "&aArsenal",
                "&7Every weapon in the game, what it", "&7does and how to get it.", "",
                "&7Arrows shot: &a" + Text.commasInt(stat(p, true)), "&7Abilities cast: &a" + Text.commasInt(stat(p, false))));
        int[] slots = {20, 21, 22, 23, 24};
        Kind[] kinds = Kind.values();
        for (int i = 0; i < kinds.length; i++) {
            Kind k = kinds[i];
            int n = items(k).size();
            m.set(slots[i], Icons.of(k.icon, "&a" + k.title, "&7Browse &e" + n + " &7items.", "", "&eClick to browse!"),
                    c -> category(c.player(), k, 0).open(c.player()));
        }
        m.set(31, heldIcon(p));
        m.set(32, Icons.of(Material.SPECTRAL_ARROW, "&aArrow Selector",
                "&7Pick which arrows your bows use.", "", "&7Current: &f" + selectedName(p), "", "&eClick to open!"),
                c -> arrows(c.player()).open(c.player()));
        m.set(30, Icons.of(Material.BOOK, "&aHow weapons work",
                "&7Swords and longswords hit with their", "&cDamage &7plus your &c❁ Strength&7.",
                "&7Bows add the arrow's damage. Shortbows", "&7fire the instant you click; their",
                "&7shot cooldown shrinks with &e⚔ Bonus", "&eAttack Speed&7.",
                "&7Wands and staffs cast abilities that", "&7scale with &b✎ Intelligence&7."));
        m.set(33, Icons.of(Material.ENDER_EYE, "&aTeleport Upgrades", Teleporters.panel(p.getInventory().getItemInMainHand())), c -> {
            boolean ok = c.right() ? Teleporters.applyMerger(c.player()) : Teleporters.applyTuner(c.player());
            if (ok) arsenal(c.player()).open(c.player());
        });
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        return m;
    }

    private ItemStack heldIcon(Player p) {
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Traits.idOf(held);
        ItemDef d = id == null ? null : Sky.items().def(id);
        if (d == null || !traits.owns(id)) {
            return Icons.of(Material.ITEM_FRAME, "&aYour Weapon", "&7Hold one of the weapons from this", "&7menu to see its live numbers here.");
        }
        Trait t = traits.of(id);
        List<String> lore = new ArrayList<>();
        lore.add("&7Holding: " + d.rarity.code() + Text.strip(d.name));
        lore.add("");
        if (t.isShortbow()) {
            double as = Sky.stats().get(p, Stat.ATTACK_SPEED);
            lore.add("&7Shot cooldown: &a" + String.format(Locale.US, "%.2fs", WeaponMath.shortbowCooldown(t.shortbow, as,
                    WeaponDials.SHORTBOW_MIN_CD.get())) + " &8(" + Text.commasInt(as) + "% attack speed)");
        }
        if (t.emerald) lore.add("&7Purse bonus: &c+" + Text.commas(Math.floor(WeaponMath.emeraldBlade(Sky.economy().purse(p),
                WeaponDials.EMERALD_COEFF.get(), WeaponDials.EMERALD_CAP.get()) * 10) / 10) + " Damage");
        if (t.hurricane) lore.add("&7Arrows per shot: &a" + WeaponMath.hurricaneArrows(Passives.kills(held)));
        if (t.shaman > 0) lore.add("&7Health bonus: &c+" + (int) WeaponMath.perHealth(Sky.vitals().maxHealth(p), t.shaman) + " Damage");
        if (t.tactician) lore.add("&7Collection bonus: &c+15 Damage &7per Combat collection at VII+");
        if (t.raider) lore.add("&7Kill bonus: &c+" + WeaponMath.raiderDamage(Passives.kills(held)) + " Damage");
        if (t.fear) {
            int f = Passives.fear(p);
            lore.add("&7Fear: &5" + f + " &8(&c+" + f + " Damage&8, &c+" + f + " Strength&8)");
        }
        if (t.petCopy) lore.add("&7Copying your active pet's stats.");
        if (t.souls > 0) lore.add("&7Souls: &a" + Souls.stored(Sky.items().data(held)).size() + "&7/" + t.souls);
        if (t.ignite > 0) lore.add("&7Burn: &c" + Text.commas(WeaponDials.FLAMING_DOT_PERCENT.get()) + "% &7of the hit per second for &a"
                + (int) t.ignite + "s");
        if (t.mfVs != null) t.mfVs.forEach((type, mf) -> lore.add("&7Magic Find vs " + type + ": &b+" + Text.commas(mf)));
        if (t.mythBestiary != null && targets != null) {
            int tiers = targets.mythTiers(p);
            lore.add("&7Mythological Bestiary tiers: &a" + tiers + " &8(&c+" + Text.commas(WeaponMath.perTier(tiers, t.mythBestiary.dmg))
                    + "% Damage&8, &b+" + Text.commas(WeaponMath.perTier(tiers, t.mythBestiary.mf)) + " Magic Find&8)");
        }
        if (t.tiered != null) lore.add("&7Floor Roll: &a" + WeaponMath.floorName(Passives.itemTier(Sky.items().data(held))));
        if (t.classAbility) lore.add(Passives.inDungeon(p) ? "&7Right-click: &ause your class ability" : "&8Right-click uses your class ability in dungeons.");
        if (t.adaptive) {
            String cls = Passives.dungeonClass(p);
            lore.add("&7Adapted to: " + (Passives.inDungeon(p) && cls != null ? "&a" + Text.titleCase(cls) : "&8nothing (outside a dungeon)"));
        }
        if (t.whip != null && passives != null && Passives.inDungeon(p)) lore.add("&7Rallying Lash: &c+" + passives.whipKills(p) + " Strength");
        if (List.of("TERMINATOR", "VOIDEDGE_KATANA", "VORPAL_KATANA", "ATOMSPLIT_KATANA", "ETHERWARP_CONDUIT", "ASPECT_OF_THE_VOID",
                "GYROKINETIC_WAND").contains(id) && Sky.has(Features.Soulflow.class)) lore.add("&7Soulstream: &3" + Text.commasInt(SoulflowLink.balance(p)));
        if (Teleporters.TUNABLE.contains(id)) lore.addAll(Teleporters.panel(held).subList(0, Teleporters.MERGEABLE.contains(id) ? 3 : 2));
        for (AbilityDef a : d.abilities) {
            double cd = Sky.abilities().cooldownLeft(p, a.id);
            lore.add("&6" + a.name + "&7: " + (cd > 0 ? "&c" + (int) Math.ceil(cd) + "s cooldown" : "&aready"));
        }
        if (lore.size() == 2) lore.add("&7No live numbers for this weapon.");
        return Icons.glow(Icons.of(Material.ITEM_FRAME, "&aYour Weapon", lore));
    }

    private static long stat(Player p, boolean arrows) {
        WeaponData d = WeaponsModule.data(p);
        return d == null ? 0 : arrows ? d.arrowsShot : d.abilitiesCast;
    }

    Menu category(Player p, Kind k, int page) {
        List<ItemDef> list = items(k);
        return Sky.menus().paged(k.title, list, this::icon, pc -> clicked(pc.click().player(), pc.entry()),
                pm -> pm.set(48, Icons.back("Arsenal"), c -> arsenal(c.player()).open(c.player())), page);
    }

    private ItemStack icon(ItemDef d) {
        ItemStack base;
        try { base = Sky.items().create(d.id); } catch (RuntimeException e) { base = Icons.of(Material.BARRIER, d.name); }
        List<String> extra = new ArrayList<>();
        extra.add("");
        String owner = d.extra.has("owner") ? d.extra.get("owner").getAsString() : "weapons";
        if (!"weapons".equals(owner)) extra.add("&8Placeholder until the " + owner + " module provides it.");
        boolean craftable = false;
        for (Recipe r : Sky.recipes().producing(d.id)) if (r.type == Recipe.Type.CRAFTING) craftable = true;
        extra.add(craftable ? "&eClick to view the recipe!" : "&7Found at merchants, as a drop or in events.");
        return Icons.withLore(base, extra);
    }

    private void clicked(Player p, ItemDef d) {
        boolean craftable = false;
        for (Recipe r : Sky.recipes().producing(d.id)) if (r.type == Recipe.Type.CRAFTING) craftable = true;
        if (craftable && Sky.commands().run(p, "recipe", d.id)) return;
        Msg.send(p, "&7" + Text.strip(d.name) + " &7has no crafting recipe: look for it at merchants, as a mob drop or in events.");
    }

    // ---- Arrow Selector ------------------------------------------------------------------------------

    String selectedName(Player p) {
        WeaponData d = WeaponsModule.data(p);
        if (d == null || d.selectedArrow == null || d.selectedArrow.isEmpty()) return "Weakest first";
        ItemDef def = Sky.items().def(d.selectedArrow);
        return def == null ? "Weakest first" : Text.strip(def.name);
    }

    Menu arrows(Player p) {
        Menu m = new Menu("Arrow Selector", 6).chrome();
        WeaponData data = WeaponsModule.data(p);
        String sel = data == null ? null : data.selectedArrow;
        m.set(4, Icons.of(Material.SPECTRAL_ARROW, "&aArrow Selector",
                "&7Your bows use this arrow while you", "&7have it (inventory first, then your", "&7quiver). Otherwise the weakest arrow",
                "&7you can use goes first.", "", "&7Current: &f" + selectedName(p)));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (ItemDef d : Quiver.arrowTypes()) {
            if (i >= slots.length) break;
            boolean usable = Quiver.usable(p, d);
            int have = Quiver.available(p, d.id);
            boolean isSel = d.id.equals(sel);
            List<String> lore = new ArrayList<>();
            lore.add("&7Damage: &c+" + Text.commas(d.damage));
            double cc = Quiver.arrowCritChance(d);
            if (cc > 0) lore.add("&7Crit Chance: &9+" + Text.commas(cc) + "%");
            lore.add("&7You have: &a" + Text.commasInt(have));
            for (String r : d.requirements) {
                boolean ok;
                try { ok = Sky.requirements().check(p, r); } catch (RuntimeException e) { ok = false; }
                if (!ok) lore.add("&cRequires " + Sky.requirements().describe(r));
            }
            lore.add("");
            lore.add(isSel ? "&aSelected!" : usable ? "&eClick to select!" : "&cLocked");
            ItemStack ic = Icons.of(Material.ARROW, d.rarity.code() + Text.strip(d.name), lore);
            if (isSel) Icons.glow(ic);
            m.set(slots[i++], ic, c -> {
                WeaponData wd = WeaponsModule.data(c.player());
                if (wd == null) return;
                if (!Quiver.usable(c.player(), d)) { Msg.error(c.player(), "You can't use that arrow yet!"); return; }
                wd.selectedArrow = d.id;
                Msg.success(c.player(), "Your bows will now use " + Text.strip(d.name) + ".");
                arrows(c.player()).open(c.player());
            });
        }
        m.set(40, Icons.of(Material.HOPPER, "&aWeakest first", "&7Clear your choice: bows use the", "&7weakest arrow you can use.", "",
                sel == null || sel.isEmpty() ? "&aSelected!" : "&eClick to select!"), c -> {
            WeaponData wd = WeaponsModule.data(c.player());
            if (wd != null) wd.selectedArrow = null;
            arrows(c.player()).open(c.player());
        });
        boolean poisons = data == null || data.usePoisons;
        List<String> pl = new ArrayList<>();
        pl.add("&7Arrow poisons in your inventory or");
        pl.add("&7quiver are used up as you shoot.");
        pl.add("");
        for (ItemDef d : Quiver.poisons()) pl.add("&7" + Text.strip(d.name) + ": &a" + Quiver.available(p, d.id));
        pl.add("");
        pl.add(poisons ? "&aEnabled &7- &eClick to disable!" : "&cDisabled &7- &eClick to enable!");
        m.set(42, Icons.of(poisons ? Material.LIME_DYE : Material.GRAY_DYE, "&aArrow Poisons", pl), c -> {
            WeaponData wd = WeaponsModule.data(c.player());
            if (wd != null) wd.usePoisons = !wd.usePoisons;
            arrows(c.player()).open(c.player());
        });
        m.set(48, Icons.back("Arsenal"), c -> arsenal(c.player()).open(c.player()));
        return m;
    }
}
