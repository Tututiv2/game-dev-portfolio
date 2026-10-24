package com.skyclone.mod.reforges;

import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.ui.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders "Fabled Bonus" (with the numbers for the item's EFFECTIVE rarity) and the small upgrade
 * notes (Art of War, enrichment, master stars) above the footer. Pure function of (def, data).
 */
final class BonusLore implements LoreContributor {

    @Override public Section section() { return Section.BEFORE_FOOTER; }

    @Override
    public List<String> lines(ItemDef def, ItemData data) {
        if (def == null || data == null || ReforgesModule.DATA == null) return List.of();
        List<String> out = new ArrayList<>();
        Rarity base = def.rarity == null ? Rarity.COMMON : def.rarity;
        Rarity eff = data.recombobulated ? base.upgrade() : base;
        if (data.reforge != null) {
            ReforgeData.Row row = ReforgesModule.DATA.rows.get(data.reforge);
            ReforgeData.Bonus b = row == null ? null : ReforgeData.bonusFor(row, eff);
            if (b != null && b.text != null && !b.text.isBlank()) {
                out.add("&9" + row.name + " Bonus");
                out.addAll(Text.wrap(b.text, 38));
                out.add("");
            }
        }
        if (ItemMods.flag(data, "art_of_war")) out.add("&6[&cThe Art of War&6] &c+5❁ Strength");
        if (ItemMods.flag(data, "art_of_peace")) out.add("&6[&aThe Art of Peace&6] &a+40❤ Health");
        int polar = ItemMods.count(data, "polarvoid");
        if (polar > 0) out.add("&9Polarvoid Books: &a" + polar + "&7/" + ItemMods.POLARVOID_MAX);
        int disint = ItemMods.count(data, "mana_disintegrator_count");
        if (disint > 0) out.add("&9Mana Disintegrators: &a" + disint + "&7/" + ItemMods.MANA_DISINTEGRATOR_MAX + " &8(-" + disint + "% mana cost)");
        if (ItemMods.flag(data, "wood_singularity_count")) out.add("&6[&6Wood Singularity&6] &6+25☘ Foraging Fortune");
        // wave 5 anvil upgrades (the teleport blades' tuner/merger lines are the weapons module's lore; farming tools' almanac line is farming's)
        String scroll = ItemMods.str(data, ItemMods.SCROLL_KEY);
        if (scroll != null) {
            ItemDef sd = com.skyclone.api.Sky.items().def(scroll);
            String col = sd != null && sd.extra != null && sd.extra.has("color") ? "&" + sd.extra.get("color").getAsString() : "&5";
            out.add(col + "⦾ " + (sd == null ? Text.titleCase(scroll) : sd.name) + " &8(on RIGHT CLICK ability)");
        }
        if (def.category == com.skyclone.api.item.ItemCategory.VACUUM) {
            int alm = ItemMods.count(data, ItemMods.ALMANAC_KEY);
            if (alm > 0) out.add("&7Farmer's Almanac: &a" + alm + "&7/&a" + ItemMods.almanacMax.getAsInt());
            int worm = ItemMods.count(data, ItemMods.BOOKWORM_KEY);
            if (worm > 0) out.add("&9Bookworm's books: &a" + worm + "&7/" + ItemMods.BOOKWORM_MAX + " &8(+" + Text.commasInt(worm * ItemMods.BOOKWORM_DAMAGE) + " damage)");
        }
        int chomp = Bonuses.chompCount(data);
        if (chomp > 0 && "chomp".equals(data.reforge)) out.add("&7Lava sea creature kinds slain: &a" + chomp);
        if (data.extra != null && data.extra.has("stats_book")) {
            out.add((ItemMods.farmingTool(def) && !ItemMods.weapon(def) ? "&7Crops Broken: &a" : "&7Kills: &a")
                    + Text.commasInt(ItemMods.count(data, "stats_book")));
        }
        String en = ItemMods.str(data, "enrichment");
        if (en != null) {
            ReforgeData.Enrich e = ReforgesModule.DATA.enrichments.get(en);
            if (e != null) out.add("&7Enrichment: &" + e.color + "+" + Text.commas(e.value) + " " + e.label);
        }
        if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) out.add("");
        return out;
    }
}
