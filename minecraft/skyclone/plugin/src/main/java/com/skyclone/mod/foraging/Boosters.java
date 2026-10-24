package com.skyclone.mod.foraging;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boosters (ref. the foraging update's Sweep / Foraging Fortune / Foraging Wisdom / Hunting Wisdom / Fighting
 * boosters, "Combinable in Anvil" with Foraging Armor, Equipment or Axes) and Forest's Favor (Karma V -> VI, once).
 * Ours go on at the <b>Booster Bench</b>: {@code /boosters}, right-clicking any booster, or the {@code /foraging}
 * menu. One booster per item; the Uncommon one upgrades a Common one of the same kind. The stat lands on the item
 * itself (stat contributor), so the tooltip and the player's stats always agree; Fighting adds % damage to
 * creatures on the foraging islands (combat modifier). Numbers: {@link BoosterMath} (TUNE).
 */
final class Boosters {

    private Boosters() {}

    static final String FAVOR = "FORESTS_FAVOR";
    static final String K_KIND = "booster", K_TIER = "boosterTier", K_FAVOR = "forestsFavor";
    private static final Stat[] FORAGING_STATS = {Stat.SWEEP, Stat.FORAGING_FORTUNE, Stat.FORAGING_WISDOM, Stat.HUNTING_FORTUNE, Stat.HUNTING_WISDOM};
    private static final String[] FORAGING_OTHER = {"fig_fortune", "mangrove_fortune", "helix_fortune", "spiral_fortune"};

    static void register() {
        Sky.items().addStatContributor("foraging_booster", (def, data, rarity, sheet) -> {
            if (!FDials.boosters.get() || data == null) return;
            BoosterMath.Kind k = kindOf(data);
            int t = tierOf(data);
            Stat s = stat(k);
            if (s != null && t > 0) sheet.add(s, k.value(t) * FDials.boosterScale.get(), ItemStatSheet.Source.OTHER);
        });
        Sky.items().addLoreContributor("foraging_booster", new LoreContributor() {
            @Override public Section section() { return Section.AFTER_ENCHANTS; }
            @Override public List<String> lines(ItemDef def, ItemData data) {
                List<String> out = new ArrayList<>();
                if (data == null) return out;
                BoosterMath.Kind k = kindOf(data);
                int t = tierOf(data);
                if (k != null && t > 0) {
                    String tier = t >= 2 ? "&aUncommon" : "&fCommon";
                    out.add("&b✦ " + tier + " " + k.display + " Booster"
                            + (k == BoosterMath.Kind.FIGHTING ? " &8(+" + Text.commas(k.value(t) * FDials.boosterScale.get()) + "% vs foraging creatures)" : ""));
                }
                if (data.extra != null && data.extra.has(K_FAVOR)) out.add("&d✿ Forest's Favor &8(Karma +1)");
                return out;
            }
        });
        // Fighting: % more damage to creatures on the foraging islands, summed over the held item and worn gear
        Sky.combat().addModifier("foraging_fighting_booster", 412, ctx -> {
            Player p = ctx.playerAttacker();
            if (p == null || ctx.victim instanceof Player || ctx.kind == DamageContext.Kind.TRUE || !FDials.boosters.get()) return;
            if (!Chopper.foragingIsland(Chopper.island(ctx.victim.getWorld()))) return;
            double pct = fighting(p);
            if (pct > 0) ctx.additive += pct;
        });
        Sky.abilities().register("foraging_booster", u -> { bench(u.player()).open(u.player()); return true; });
        Sky.commands().register(SkyCommand.of("boosters", "The Booster Bench: put boosters and Forest's Favor on your foraging gear")
                .alias("booster", "boosterbench").category("Skills").playerOnly().run((s, a) -> bench((Player) s).open((Player) s)));
    }

    // ================================================================================ reading

    static BoosterMath.Kind kindOf(ItemData d) {
        return d == null || d.extra == null || !d.extra.has(K_KIND) ? null : BoosterMath.Kind.parse(d.extra.get(K_KIND).getAsString());
    }

    static int tierOf(ItemData d) {
        try { return d == null || d.extra == null || !d.extra.has(K_TIER) ? 0 : d.extra.get(K_TIER).getAsInt(); } catch (RuntimeException e) { return 0; }
    }

    static Stat stat(BoosterMath.Kind k) {
        if (k == null) return null;
        return switch (k) {
            case SWEEP -> Stat.SWEEP;
            case FORAGING_FORTUNE -> Stat.FORAGING_FORTUNE;
            case FORAGING_WISDOM -> Stat.FORAGING_WISDOM;
            case HUNTING_WISDOM -> Stat.HUNTING_WISDOM;
            case FIGHTING -> null;
        };
    }

    /** % damage the Fighting boosters on the held item and worn gear add. */
    static double fighting(Player p) {
        double pct = 0;
        List<ItemStack> items = new ArrayList<>(Bonuses.worn(p));
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (gear(hand)) items.add(hand);
        for (ItemStack it : items) {
            ItemData d = Sky.items().data(it);
            if (kindOf(d) == BoosterMath.Kind.FIGHTING) pct += BoosterMath.Kind.FIGHTING.value(tierOf(d));
        }
        return pct * FDials.boosterScale.get();
    }

    /** A booster ITEM: its kind and tier from the def's extra, or null. */
    static BoosterMath.Kind boosterKind(ItemDef d) {
        return d == null || d.extra == null || !d.extra.has(K_KIND) ? null : BoosterMath.Kind.parse(d.extra.get(K_KIND).getAsString());
    }

    static int boosterTier(ItemDef d) {
        try { return d == null || d.extra == null || !d.extra.has(K_TIER) ? 0 : d.extra.get(K_TIER).getAsInt(); } catch (RuntimeException e) { return 0; }
    }

    static boolean gear(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        String id = Sky.items().idOf(it);
        return id != null && gear(Sky.items().def(id));
    }

    /** Foraging Armor, Equipment or an Axe: an axe, or a worn piece that gives a foraging/hunting stat. */
    static boolean gear(ItemDef d) {
        if (d == null) return false;
        if (d.category == ItemCategory.AXE) return true;
        switch (d.category) {
            case HELMET, CHESTPLATE, LEGGINGS, BOOTS, NECKLACE, CLOAK, BELT, GLOVES -> { }
            default -> { return false; }
        }
        if (d.tags != null && (d.tags.contains("foraging") || d.tags.contains("foraging_armor"))) return true;
        for (Stat s : FORAGING_STATS) if (d.stats != null && d.stats.getOrDefault(s, 0.0) > 0) return true;
        for (String k : FORAGING_OTHER) if (Bonuses.otherStat(d, k) > 0) return true;
        return false;
    }

    // ================================================================================ the bench

    static Menu bench(Player p) {
        Menu m = new Menu("Booster Bench", 6).chrome();
        m.set(4, Icons.of(Material.SMITHING_TABLE, "&aBooster Bench",
                "&7Pick a piece of &2foraging gear&7, then", "&7a booster to put on it.", "",
                "&7Gear: &aaxes &7and armor / equipment that", "&7give Sweep, Foraging Fortune or Wisdom.",
                "&7One booster per item; an &aUncommon", "&7booster upgrades a &fCommon &7one.", "",
                "&dForest's Favor &7lifts &dKarma V &7to &dVI&7.", "",
                "&7Boosters come from &2Tree Gifts&7, the", "&7Herbalist and Sapwitch Elowen.",
                "&8Equipment must be in your inventory."));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        int i = 0;
        ItemStack[] inv = p.getInventory().getContents();
        for (int s = 0; s < inv.length && i < slots.length; s++) {
            ItemStack it = inv[s];
            if (!gear(it)) continue;
            int slot = s;
            ItemData d = Sky.items().data(it);
            List<String> extra = new ArrayList<>();
            extra.add("");
            BoosterMath.Kind k = kindOf(d);
            extra.add(k == null ? "&7Booster: &8none" : "&7Booster: " + (tierOf(d) >= 2 ? "&aUncommon " : "&fCommon ") + k.display);
            extra.add("&8" + (slot >= 36 && slot <= 39 ? "worn" : slot == 40 ? "off hand" : "inventory slot " + slot));
            extra.add("");
            extra.add("&eClick to pick a booster!");
            m.set(slots[i++], Icons.withLore(it.clone(), extra), c -> pick(c.player(), slot, uuidOf(it)).open(c.player()));
        }
        if (i == 0) m.set(22, Icons.of(Material.BARRIER, "&cNo foraging gear", "&7Carry an axe or a piece of foraging", "&7armor or equipment to boost it."));
        m.set(49, Icons.back("Foraging"), c -> ForagingMenus.main(c.player()).open(c.player()));
        return m;
    }

    private static String uuidOf(ItemStack it) {
        ItemData d = Sky.items().data(it);
        return d == null || d.uuid == null ? "" : d.uuid;
    }

    /** Your boosters (and Forest's Favor) for the gear in that slot. */
    static Menu pick(Player p, int slot, String uuid) {
        Menu m = new Menu("Pick a Booster", 4).chrome();
        ItemStack target = p.getInventory().getItem(slot);
        if (target != null) m.set(4, target.clone());
        Map<String, Integer> have = new LinkedHashMap<>();
        for (ItemStack it : p.getInventory().getContents()) {
            String id = it == null ? null : Sky.items().idOf(it);
            ItemDef d = id == null ? null : Sky.items().def(id);
            if (d == null || (boosterKind(d) == null && !FAVOR.equals(id))) continue;
            have.merge(id, it.getAmount(), Integer::sum);
        }
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int i = 0;
        ItemData td = target == null ? null : Sky.items().data(target);
        for (Map.Entry<String, Integer> e : have.entrySet()) {
            if (i >= slots.length) break;
            String id = e.getKey();
            ItemDef d = Sky.items().def(id);
            String why = target == null ? "The item moved." : FAVOR.equals(id) ? favorProblem(target, td)
                    : BoosterMath.check(boosterKind(d), boosterTier(d), kindOf(td), tierOf(td));
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("&7You have &a" + e.getValue());
            extra.add(why == null ? "&eClick to apply!" : "&c" + why);
            m.set(slots[i++], Icons.withLore(Sky.items().create(id, 1), extra), c -> apply(c.player(), slot, uuid, id));
        }
        if (i == 0) m.set(13, Icons.of(Material.BARRIER, "&cYou have no boosters",
                "&7Find them in &2Tree Gifts&7, buy the &cFighting", "&7one from the Herbalist, the rest from", "&7Sapwitch Elowen for Forest Essence."));
        m.set(31, Icons.back("Booster Bench"), c -> bench(c.player()).open(c.player()));
        return m;
    }

    static String favorProblem(ItemStack target, ItemData td) {
        ItemDef def = Sky.items().def(Sky.items().idOf(target));
        if (def == null || def.category != ItemCategory.AXE) return "Forest's Favor only goes on an axe.";
        if (td != null && td.extra != null && td.extra.has(K_FAVOR)) return "This axe already has Forest's Favor.";
        if (td == null || td.enchant("karma") < 5) return "Requires Karma V!";
        return null;
    }

    static void apply(Player p, int slot, String uuid, String boosterId) {
        ItemStack target = p.getInventory().getItem(slot);
        if (target == null || !gear(target) || !uuid.equals(uuidOf(target))) { Msg.error(p, "That item moved. Pick it again."); bench(p).open(p); return; }
        ItemData td = Sky.items().data(target);
        ItemDef bd = Sky.items().def(boosterId);
        boolean favor = FAVOR.equals(boosterId);
        String why = favor ? favorProblem(target, td) : BoosterMath.check(boosterKind(bd), boosterTier(bd), kindOf(td), tierOf(td));
        if (why != null) { Msg.error(p, why); return; }
        if (Sky.items().remove(p, boosterId, 1) < 1) { Msg.error(p, "You don't have that booster any more."); return; }
        // the remove may have shifted stacks; the target slot holds the same unique item (uuid checked above)
        ItemStack again = p.getInventory().getItem(slot);
        if (again == null || !uuid.equals(uuidOf(again))) {
            Sky.loot().give(p, boosterId, 1, com.skyclone.api.loot.LootSource.NPC_SHOP);
            Msg.error(p, "That item moved; your booster was given back.");
            return;
        }
        if (favor) {
            Sky.items().update(again, x -> { x.enchants.put("karma", x.enchant("karma") + 1); x.extra.addProperty(K_FAVOR, true); });
        } else {
            BoosterMath.Kind k = boosterKind(bd);
            int t = boosterTier(bd);
            Sky.items().update(again, x -> { JsonObject e = x.extra; e.addProperty(K_KIND, k.name()); e.addProperty(K_TIER, t); });
        }
        Sky.stats().invalidate(p);
        p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 1f, 1.2f);
        Msg.success(p, "Applied " + Text.strip(bd == null ? boosterId : bd.name) + "!");
        bench(p).open(p);
    }
}
