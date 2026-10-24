package com.skyclone.mod.armor;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Armor Sets browser: categories -> sets -> one set (pieces, bonus, totals, how to get it) ->
 * a recipe preview; plus "Your Armor" (what you wear and which bonuses are live).
 * Reached by {@code /armor} and (once the menus module wires it) from the SkyBlock Menu.
 */
final class ArmorMenus {

    private ArmorMenus() {}

    static final int[] PIECE_SLOTS = {10, 19, 28, 37};

    // =========================================================================== main

    static Menu main(Player p) {
        Menu m = new Menu("Armor Sets", 6).chrome();
        ArmorSets sets = ArmorModule.sets();
        int multi = 0;
        for (ArmorSets.SetInfo s : sets.all()) if (s.isSet) multi++;
        m.set(4, Icons.of(Material.DIAMOND_CHESTPLATE, "&aArmor Sets",
                "&7Every armor set in the game, what it", "&7does, and how to get it.", "",
                "&7Sets: &a" + multi + " &8(" + Text.commasInt(sets.pieceCount()) + " pieces in total)"));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 31};
        for (int i = 0; i < ArmorSets.CATEGORIES.size() && i < slots.length; i++) {
            String cat = ArmorSets.CATEGORIES.get(i);
            List<ArmorSets.SetInfo> list = sets.inCategory(cat);
            m.set(slots[i], Icons.of(categoryIcon(cat), "&a" + categoryName(cat),
                    "&7" + categoryBlurb(cat), "", "&7Entries: &a" + list.size(), "", "&eClick to browse!"),
                    c -> category(c.player(), cat, 0).open(c.player()));
        }
        m.set(40, Icons.of(Material.ARMOR_STAND, "&aYour Armor",
                "&7See the pieces you wear, which set", "&7bonuses are active and what they", "&7add to your stats right now.", "",
                "&eClick to view!"), c -> worn(c.player()).open(c.player()));
        backToSkyMenu(m);
        return m;
    }

    static void backToSkyMenu(Menu m) {
        if (Sky.has(Features.SkyMenu.class)) {
            m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        }
    }

    static Material categoryIcon(String cat) {
        return switch (cat) {
            case "COMBAT" -> Material.IRON_SWORD;
            case "MINING" -> Material.IRON_PICKAXE;
            case "FARMING" -> Material.GOLDEN_HOE;
            case "FISHING" -> Material.FISHING_ROD;
            case "FORAGING" -> Material.JUNGLE_SAPLING;
            case "DUNGEONEERING" -> Material.SKELETON_SKULL;
            case "VANILLA" -> Material.IRON_CHESTPLATE;
            default -> Material.CARVED_PUMPKIN;
        };
    }

    static String categoryName(String cat) {
        return switch (cat) {
            case "DUNGEONEERING" -> "Dungeon Starter";
            case "VANILLA" -> "Classic Armor";
            case "MISC" -> "Miscellaneous";
            default -> Text.titleCase(cat);
        };
    }

    static String categoryBlurb(String cat) {
        return switch (cat) {
            case "COMBAT" -> "Dragon sets, shop sets and fighters' gear.";
            case "MINING" -> "Mining speed, fortune and heat gear.";
            case "FARMING" -> "Fortune and the rare-crop ladder.";
            case "FISHING" -> "Sea creature chance and water gear.";
            case "FORAGING" -> "Gear for the trees.";
            case "DUNGEONEERING" -> "Early dungeon drops.";
            case "VANILLA" -> "Leather, iron, gold, diamond...";
            default -> "Hats, masks, event and oddity pieces.";
        };
    }

    // =========================================================================== category list

    static Menu category(Player p, String cat, int page) {
        List<ArmorSets.SetInfo> list = new ArrayList<>(ArmorModule.sets().inCategory(cat));
        list.sort((a, b) -> {
            int r = Integer.compare(rarityOrd(a), rarityOrd(b));
            return r != 0 ? r : a.name.compareToIgnoreCase(b.name);
        });
        return Sky.menus().paged(categoryName(cat) + " Armor", list, ArmorMenus::setIcon,
                pc -> set(pc.click().player(), pc.entry(), cat, pc.page()).open(pc.click().player()),
                menu -> menu.back("Armor Sets", () -> main(p)), page);
    }

    static int rarityOrd(ArmorSets.SetInfo s) {
        return Rarity.parse(s.rarity).ordinal();
    }

    static ItemStack setIcon(ArmorSets.SetInfo s) {
        String first = s.pieces.isEmpty() ? null : s.pieces.get(0);
        Rarity r = Rarity.parse(s.rarity);
        List<String> lore = new ArrayList<>();
        lore.add("&8" + (s.isSet ? s.size() + "-piece set" : "Single piece"));
        lore.add("");
        lore.addAll(statLines(totals(s)));
        if (!s.bonuses.isEmpty()) {
            lore.add("");
            for (ArmorSets.Bonus b : s.bonuses) lore.add("&6" + kindLabel(b.kind) + b.name);
        }
        String req = requirementText(first);
        if (req != null) { lore.add(""); lore.add("&4❣ &cRequires " + req); }
        lore.add("");
        lore.add(obtainShort(s));
        lore.add("");
        lore.add("&eClick to view!");
        return render(first, r.code() + s.name, lore);
    }

    // =========================================================================== one set

    static Menu set(Player p, ArmorSets.SetInfo s, String cat, int page) {
        Menu m = new Menu(Text.strip(s.name) + (s.isSet ? " Set" : ""), 6).chrome();
        for (int i = 0; i < s.pieces.size() && i < PIECE_SLOTS.length; i++) {
            String id = s.pieces.get(i);
            ItemStack icon = pieceIcon(id);
            m.set(PIECE_SLOTS[i], icon, c -> recipe(c.player(), id, () -> set(p, s, cat, page)).open(c.player()));
        }
        List<String> bonus = new ArrayList<>();
        if (s.bonuses.isEmpty()) bonus.add("&7This " + (s.isSet ? "set" : "piece") + " has no special bonus.");
        for (ArmorSets.Bonus b : s.bonuses) {
            if (!bonus.isEmpty()) bonus.add("");
            bonus.add("&6" + kindLabel(b.kind) + b.name);
            bonus.addAll(b.lines);
        }
        m.set(13, Icons.of(Material.ENCHANTED_BOOK, "&6Bonuses", bonus));
        List<String> tot = new ArrayList<>(statLines(totals(s)));
        if (tot.isEmpty()) tot.add("&7No base stats.");
        tot.add(0, "&7Base stats with every piece worn:");
        tot.add(1, "");
        m.set(22, Icons.of(Material.IRON_CHESTPLATE, "&aSet Totals", tot));
        m.set(31, Icons.of(Material.CRAFTING_TABLE, "&aHow to Get It", obtainLines(p, s)));
        // Wave 5: "does this upgrade into anything?" answered in the browser itself.
        ArmorSets.SetInfo next = firstSet(s.upgradeInto), prev = firstSet(s.upgradeFrom);
        m.set(40, Icons.of(next != null ? Material.ANVIL : Material.CHIPPED_ANVIL, "&aUpgrade Path", upgradeLines(s)), c -> {
            ArmorSets.SetInfo go = c.type().isRightClick() ? prev : next;
            if (go != null) set(c.player(), go, go.category, 0).open(c.player());
        });
        Worn w = Worn.of(p);
        int worn = 0;
        for (String id : s.pieces) if (w.wears(id)) worn++;
        m.set(16, Icons.of(worn >= s.size() && s.isSet ? Material.LIME_DYE : Material.GRAY_DYE, "&aYou Wear",
                "&7Pieces worn: " + (worn >= s.size() ? "&a" : "&e") + worn + "&7/" + s.size(),
                s.isSet ? (worn >= s.size() ? "&aFull set bonus active!" : "&7Wear every piece for the full bonus.") : ""));
        m.back(categoryName(cat) + " Armor", () -> category(p, cat, page));
        return m;
    }

    static ItemStack pieceIcon(String id) {
        if (!Sky.items().exists(id)) return Icons.of(Material.BARRIER, "&c" + id, "&7Not registered.");
        ItemStack it = Sky.items().create(id);
        strip(it);
        boolean craft = !recipes(id).isEmpty();
        return Icons.withLore(it, List.of("", craft ? "&eClick to view recipe!" : "&eClick for details!"));
    }

    // =========================================================================== recipe preview

    static List<Recipe> recipes(String id) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : Sky.recipes().producing(id)) if (r.type == Recipe.Type.CRAFTING || r.type == Recipe.Type.FORGE) out.add(r);
        return out;
    }

    static Menu recipe(Player p, String id, java.util.function.Supplier<Menu> back) {
        ItemDef def = Sky.items().def(id);
        String name = def == null ? id : Text.strip(def.name);
        Menu m = new Menu("Recipe: " + name, 6).chrome();
        List<Recipe> rs = recipes(id);
        if (rs.isEmpty()) {
            m.set(22, Icons.of(Material.BOOK, "&cNot Craftable", "&7" + name + " cannot be crafted.",
                    "&7It comes from drops, merchants or", "&7events instead."));
        } else {
            Recipe r = rs.get(0);
            int[] grid = {10, 11, 12, 19, 20, 21, 28, 29, 30};
            List<String> cells = new ArrayList<>();
            if (r.type == Recipe.Type.CRAFTING && r.grid != null) for (String c : r.grid) cells.add(c);
            else cells.addAll(r.inputs);
            for (int i = 0; i < grid.length && i < cells.size(); i++) {
                String cell = cells.get(i);
                if (cell == null || cell.isEmpty()) continue;
                m.set(grid[i], ingredientIcon(cell));
            }
            m.set(23, pieceIcon(id));
            boolean ok = Sky.recipes().unlocked(p, r);
            List<String> info = new ArrayList<>();
            info.add("&7Made at: &a" + (r.station == null ? "Crafting Table" : r.station));
            if (r.type == Recipe.Type.FORGE && r.durationSeconds > 0) info.add("&7Forge time: &a" + Text.duration(r.durationSeconds));
            if (r.unlock != null) info.add("&7Unlocked by: " + Sky.requirements().describe(r.unlock));
            info.add("");
            info.add(ok ? "&aYou have unlocked this recipe!" : "&cLocked");
            m.set(25, Icons.of(ok ? Material.LIME_DYE : Material.RED_DYE, ok ? "&aUnlocked" : "&cLocked", info));
            if (r.type == Recipe.Type.CRAFTING) {
                m.set(32, Icons.of(Material.CRAFTING_TABLE, "&aCraft It", "&7Opens the crafting table.", "", "&eClick to open!"),
                        c -> { if (!Sky.commands().run(c.player(), "craft")) c.player().closeInventory(); });
            }
        }
        m.back("the set", back);
        return m;
    }

    static ItemStack ingredientIcon(String cell) {
        String id = cell;
        int amount = 1;
        if (!Sky.items().exists(cell)) {
            int c = cell.lastIndexOf(':');
            if (c > 0) {
                id = cell.substring(0, c);
                try { amount = Integer.parseInt(cell.substring(c + 1)); } catch (NumberFormatException ignored) { }
            }
        }
        if (!Sky.items().exists(id)) return Icons.of(Material.BARRIER, "&c" + id + " &8x" + amount, "&7This ingredient is not in the game yet.");
        ItemStack it = Sky.items().create(id, 1);
        strip(it);
        it = Icons.withLore(it, List.of("", "&7Needed: &a" + Text.commasInt(amount)));
        return Icons.amount(it, Math.min(64, amount));
    }

    // =========================================================================== your armor

    static Menu worn(Player p) {
        Menu m = new Menu("Your Armor", 6).chrome();
        Worn w = Worn.of(p);
        for (int i = 0; i < 4; i++) {
            ItemStack s = w.stacks[i];
            if (s == null || s.getType().isAir()) {
                m.set(PIECE_SLOTS[i], Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Empty " + new String[]{"Helmet", "Chestplate", "Leggings", "Boots"}[i] + " Slot"));
            } else {
                ItemStack icon = s.clone();
                strip(icon);
                if (!w.usable[i]) icon = Icons.withLore(icon, List.of("", "&cYou do not meet this piece's", "&crequirements: it gives nothing."));
                m.set(PIECE_SLOTS[i], icon);
            }
        }
        List<String> active = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 4; i++) {
            if (!w.usable[i] || w.defs[i] == null) continue;
            String sid = w.defs[i].setId != null ? w.defs[i].setId : ArmorModule.sets().setOf(w.ids[i]);
            if (sid != null) seen.add(sid);
        }
        int[] slots = {12, 13, 14, 15, 21, 22, 23, 24};
        int idx = 0;
        for (String sid : seen) {
            ArmorSets.SetInfo s = ArmorModule.sets().get(sid);
            if (s == null || idx >= slots.length) continue;
            int n = s.isSet ? w.pieces(sid) : 1;
            boolean full = !s.isSet || w.full(sid);
            List<String> lore = new ArrayList<>();
            lore.add("&7Pieces: " + (full ? "&a" : "&e") + n + "&7/" + s.size());
            if (s.bonuses.isEmpty()) lore.add("&8No bonus.");
            for (ArmorSets.Bonus b : s.bonuses) {
                boolean on = !"FULL".equals(b.kind) || full;
                lore.add((on ? "&a✔ " : "&c✖ ") + "&6" + kindLabel(b.kind) + b.name);
            }
            lore.addAll(liveLines(p, sid));
            lore.add("");
            lore.add("&eClick to view the set!");
            ArmorSets.SetInfo fs = s;
            m.set(slots[idx++], render(s.pieces.isEmpty() ? null : s.pieces.get(0), Rarity.parse(s.rarity).code() + s.name, lore),
                    c -> set(c.player(), fs, fs.category, 0).open(c.player()));
            active.add(s.name);
        }
        if (seen.isEmpty()) {
            // Playtest wave 5: wearing four pieces you lack the skill for said "You are not wearing any armor."
            boolean wearing = false;
            for (int i = 0; i < 4; i++) if (w.ids[i] != null) wearing = true;
            m.set(13, wearing
                    ? Icons.of(Material.BARRIER, "&cNo Usable Armor", "&7You do not meet the requirements", "&7of the armor you are wearing,", "&7so it gives no stats or bonuses.")
                    : Icons.of(Material.BARRIER, "&cNo Armor", "&7You are not wearing any armor."));
        }
        // what armor bonuses add right now (both providers)
        List<String> stats = new ArrayList<>();
        for (Stat st : Stat.values()) {
            Map<String, Double> b = Sky.stats().breakdown(p, st);
            double v = b.getOrDefault("armor_sets", 0.0) + b.getOrDefault("armor_sets_final", 0.0);
            double pct = b.getOrDefault("armor_sets (%)", 0.0);
            if (Math.abs(v) >= 0.05) stats.add("&7" + st.display + ": " + (v >= 0 ? "&a+" : "&c") + Text.commas(Math.round(v * 10) / 10.0));
            if (Math.abs(pct) >= 0.05) stats.add("&7" + st.display + ": &a+" + Text.commas(pct) + "%");
        }
        if (stats.isEmpty()) stats.add("&7No set bonus is adding stats right now.");
        stats.add(0, "&7What your set bonuses add right now:");
        stats.add(1, "");
        m.set(40, Icons.of(Material.NETHER_STAR, "&aBonus Stats", stats));
        m.back("Armor Sets", () -> main(p));
        return m;
    }

    /** Live numbers for the sets whose bonus depends on something (counters, charges, collection). */
    static List<String> liveLines(Player p, String sid) {
        List<String> out = new ArrayList<>();
        Worn w = Worn.of(p);
        ArmorState.P st = ArmorState.of(p.getUniqueId());
        switch (sid) {
            case SetIds.EMERALD -> out.add("&7Bonus: &a+" + Text.commasInt(SetMath.emeraldBonus(BonusStats.collection(p, "EMERALD"),
                    ArmorDials.EMERALD_PER.get(), ArmorDials.EMERALD_CAP.get())) + " &7❤ and ❈");
            case SetIds.MAGMA -> out.add("&7Magma Cubes killed: &a" + BonusStats.counter(w, "ARMOR_OF_MAGMA_CHESTPLATE", BonusListener.KEY_MAGMA));
            case SetIds.YOG -> out.add("&7Lava beasts slain: &a" + BonusStats.counter(w, "ARMOR_OF_YOG_CHESTPLATE", BonusListener.KEY_YOG));
            case SetIds.THUNDER -> out.add("&7Static charges: &e" + SetMath.staticCharges((System.currentTimeMillis() - st.thunderSince) / 1000,
                    ArmorDials.THUNDER_SECONDS.get(), ArmorDials.THUNDER_CAP.get()));
            case SetIds.GOBLIN -> out.add("&7Intelligence converted: &b" + Text.commasInt(st.goblinRemoved));
            case SetIds.CRYSTAL -> out.add("&7Light: &e" + p.getLocation().getBlock().getLightLevel() + " &7(" + Math.round(SetMath.crystalFactor(p.getLocation().getBlock().getLightLevel()) * 100) + "%)");
            case SetIds.BLAZE, SetIds.FROZEN_BLAZE -> {
                out.add("&7Aura cap: &c" + Text.commasInt(SetMath.blazeAuraCap(BonusStats.collection(p, "BLAZE_ROD"),
                        ArmorDials.BLAZE_CAP.get(), ArmorDials.BLAZE_CAP_PER_5K.get())) + "/s");
                double pet = BonusListener.blazePetPercent(p);
                if (pet > 0) out.add("&7Blaze pet: aura &a+" + Text.commas(pet) + "%");
            }
            case SetIds.GREAT_SPOOK -> out.add("&7Fear: &5" + Text.commasInt(Fear.total(p)) + " &7(monsters up to that level flee)");
            case SetIds.YOUNG -> out.add("&7Speed cap: &f" + Text.commasInt(ArmorDials.CORE_SPEED_CAP.get() + ArmorDials.YOUNG_SPEED_CAP.get())
                    + " &7(over the cap now: &a+" + Math.round(CapBonuses.speedModifier(p) * 100) + "%&7)");
            case SetIds.THERMODYNAMIC -> {
                int[] win = CapBonuses.windows(p);
                out.add("&7Attack Speed cap: &e" + Text.commasInt(ArmorDials.CORE_AS_CAP.get() + ArmorDials.NEWTON_AS_CAP.get()));
                out.add(win == null ? "&8Only matters above " + Text.commasInt(ArmorDials.CORE_AS_CAP.get()) + " Attack Speed."
                        : "&7Hit every &a" + win[0] + " &7ticks &8(instead of " + win[1] + ")");
            }
            case SetIds.GLACITE -> out.add("&7Mining level: &a" + BonusStats.skill(p, com.skyclone.api.skill.SkillType.MINING));
            case SetIds.REKINDLED -> {
                double burning = st.burningSince <= 0 ? 0 : (System.currentTimeMillis() - st.burningSince) / 1000.0;
                out.add("&7Vs burning enemies: &c+" + Text.commasInt(SetMath.rekindleBonus(w.pieces(SetIds.REKINDLED), ArmorDials.REKINDLE_PCT.get(),
                        burning, ArmorDials.REKINDLE_FIRE_PER_SEC.get(), ArmorDials.REKINDLE_FIRE_CAP.get())) + "% damage");
                out.add("&7You are " + (p.getFireTicks() > 0 ? "&6on fire &7(" + (int) burning + "s)" : "&8not burning"));
            }
            case SetIds.SNORKELING -> out.add("&7Breath: &b" + (p.getMaximumAir() / 20) + "s");
            case SetIds.FAIRY -> out.add("&7Fairy Souls: &d" + BonusStats.fairySouls(p));
            // wave 5, second pass
            case SetIds.MAGMA_LORD -> {
                boolean necklace = w.wearsEquipment(SetIds.MAGMA_LORD_NECKLACE);
                out.add("&7Pieces: &a" + w.magmaLord() + "&7/5 " + (necklace ? "&8(necklace worn)" : "&8(no Magma Lord Necklace)"));
                out.add("&7Fireproof " + (w.fireproof() ? "&a✔" : "&c✖") + " &7Lord's Blessing " + (w.lordsBlessing() ? "&a✔" : "&c✖"));
            }
            case SetIds.BRONZE, SetIds.SILVER, SetIds.GOLD_HUNTER, SetIds.DIAMOND_HUNTER ->
                    out.add("&7Peace Treaty: " + (w.peaceTreaty() ? "&aon &8(no sea creatures)" : "&8off"));
            case SetIds.OLD -> out.add("&7Feather Falling: &a+" + Text.commas(Math.round(ExtraBonuses.fallModifier(p) * 10) / 10.0) + " &7safe fall");
            case SetIds.EMBER -> out.add("&7Obsidian walking: " + (NetherLord.active(p, w) ? "&aon" : "&8off here"));
            case "witch_mask", "vampire_witch_mask" -> out.add("&7Bats: &a" + BatSwarm.describe(p)
                    + " &8(" + Text.commasInt(BatSwarm.damageFor(w)) + " dmg)");
            default -> { }
        }
        return out;
    }

    // =========================================================================== helpers

    static String kindLabel(String kind) {
        if (kind == null) return "";
        return switch (kind) {
            case "FULL" -> "Full Set Bonus: ";
            case "TIERED" -> "Tiered Bonus: ";
            case "PIECE" -> "Piece Bonus: ";
            default -> "Ability: ";
        };
    }

    static Map<Stat, Double> totals(ArmorSets.SetInfo s) {
        Map<Stat, Double> t = new EnumMap<>(Stat.class);
        for (String id : s.pieces) {
            ItemDef d = Sky.items().def(id);
            if (d == null || d.stats == null) continue;
            d.stats.forEach((k, v) -> t.merge(k, v, Double::sum));
        }
        return t;
    }

    static List<String> statLines(Map<Stat, Double> t) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Stat, Double> e : t.entrySet()) {
            Stat s = e.getKey();
            double v = e.getValue();
            if (v == 0) continue;
            out.add("&7" + s.display + ": " + (v > 0 ? "&a+" : "&c") + Text.commas(v) + (s.percent ? "%" : ""));
        }
        return out;
    }

    static String requirementText(String itemId) {
        ItemDef d = itemId == null ? null : Sky.items().def(itemId);
        if (d == null || d.requirements == null || d.requirements.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (String k : d.requirements) parts.add(Sky.requirements().describe(k));
        return String.join("&c, ", parts);
    }

    static String obtainShort(ArmorSets.SetInfo s) {
        for (String id : s.pieces) {
            List<Recipe> rs = recipes(id);
            if (rs.isEmpty()) continue;
            Recipe r = rs.get(0);
            if (r.type == Recipe.Type.FORGE) return "&7Obtained: &aForged";
            return "&7Obtained: &aCrafted" + (r.unlock != null ? " &8(" + Text.strip(Sky.requirements().describe(r.unlock)) + ")" : "");
        }
        if (!s.shops.isEmpty()) return "&7Obtained: &aSold by " + s.shops.get(0).npc;
        return "&7Obtained: &eDrops, merchants or events";
    }

    static List<String> obtainLines(Player p, ArmorSets.SetInfo s) {
        List<String> out = new ArrayList<>();
        for (String id : s.pieces) {
            ItemDef d = Sky.items().def(id);
            String n = d == null ? id : d.rarity.code() + d.name;
            List<Recipe> rs = recipes(id);
            ArmorSets.Shop shop = shopFor(s, id);
            if (rs.isEmpty()) {
                out.add(n + "&7: " + (shop == null ? "&edrop, merchant or event" : "&aBuy &7for " + costText(shop)));
                continue;
            }
            Recipe r = rs.get(0);
            boolean ok = Sky.recipes().unlocked(p, r);
            out.add(n + "&7: " + (r.type == Recipe.Type.FORGE ? "&aForge" : "&aCraft") + (ok ? " &a✔" : " &c✖ " + (r.unlock == null ? "" : Sky.requirements().describe(r.unlock))));
            if (shop != null) out.add("&8  or buy it for " + Text.strip(costText(shop)));
        }
        if (!s.shops.isEmpty()) {
            ArmorSets.Shop sh = s.shops.get(0);
            out.add("");
            out.add("&7Sold by " + sh.npc + "&7" + (sh.island == null ? "" : " on " + islandName(sh.island))
                    + (sh.where == null ? "" : " &8(" + sh.where + ")"));
            if (sh.req != null && !sh.req.isBlank()) out.add("&7Needs: " + Sky.requirements().describe(sh.req));
        }
        out.add("");
        out.add("&eClick a piece to see its recipe.");
        return out;
    }

    /** First merchant offer of this piece, or null. */
    static ArmorSets.Shop shopFor(ArmorSets.SetInfo s, String pieceId) {
        for (ArmorSets.Shop sh : s.shops) if (pieceId.equals(sh.piece)) return sh;
        return null;
    }

    /** "&61,000 coins &7+ &aAngler Helmet &7+ &a4x Rusty Coin" (names from the registry, never ids). */
    static String costText(ArmorSets.Shop sh) {
        List<String> parts = new ArrayList<>();
        if (sh.coins > 0) parts.add("&6" + Text.commasInt(sh.coins) + " coins");
        for (String cell : sh.items) {
            int c = cell.lastIndexOf(':');
            String id = c > 0 ? cell.substring(0, c) : cell;
            int amount = 1;
            if (c > 0) try { amount = Integer.parseInt(cell.substring(c + 1)); } catch (NumberFormatException ignored) { }
            ItemDef d = Sky.items().def(id);
            String name = d == null ? Text.titleCase(id.replace('_', ' ')) : Text.strip(d.name);
            parts.add("&a" + (amount > 1 ? amount + "x " : "") + name);
        }
        return parts.isEmpty() ? "&afree" : String.join(" &7+ ", parts);
    }

    static String islandName(String islandId) {
        try {
            var d = Sky.worlds().island(islandId);
            if (d != null && d.displayName != null && !d.displayName.isBlank()) return d.displayName;
        } catch (RuntimeException ignored) { }
        return Text.titleCase(islandId.replace('_', ' '));
    }

    static ArmorSets.SetInfo firstSet(List<String> ids) {
        for (String id : ids) {
            ArmorSets.SetInfo x = ArmorModule.sets().get(id);
            if (x != null) return x;
        }
        return null;
    }

    /** The Upgrade Path icon: the whole line the set sits on, what feeds it and what it feeds. */
    static List<String> upgradeLines(ArmorSets.SetInfo s) {
        List<String> out = new ArrayList<>();
        List<String> line = ArmorModule.sets().upgradeLine(s.id);
        if (line.size() > 1) {
            out.add("&7Upgrade line:");
            for (String id : line) {
                ArmorSets.SetInfo x = ArmorModule.sets().get(id);
                String nm = x == null ? id : Rarity.parse(x.rarity).code() + x.name;
                out.add(id.equals(s.id) ? "&e» " + nm + " &e«" : "&8  " + Text.strip(nm));
            }
            out.add("");
        }
        out.add("&7Made from: " + setNames(s.upgradeFrom, "&8nothing - a starting point"));
        out.add("&7Upgrades into: " + setNames(s.upgradeInto, "&8nothing - top of its line"));
        out.add("");
        ArmorSets.SetInfo next = firstSet(s.upgradeInto), prev = firstSet(s.upgradeFrom);
        if (next != null) out.add("&eLeft-click to view " + Text.strip(next.name) + "!");
        if (prev != null) out.add("&bRight-click to view " + Text.strip(prev.name) + "!");
        if (next == null && prev == null) out.add("&7This set is not part of an upgrade line.");
        return out;
    }

    static String setNames(List<String> ids, String none) {
        List<String> names = new ArrayList<>();
        for (String id : ids) {
            ArmorSets.SetInfo x = ArmorModule.sets().get(id);
            if (x != null) names.add("&a" + Text.strip(x.name));
        }
        return names.isEmpty() ? none : String.join("&7, ", names);
    }

    /** Render a menu icon from a piece's look with our own name/lore, stripped of every sky tag. */
    static ItemStack render(String pieceId, String name, List<String> lore) {
        ItemStack it;
        if (pieceId != null && Sky.items().exists(pieceId)) {
            it = Sky.items().create(pieceId);
            strip(it);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.displayName(Text.c(name));
                meta.lore(Text.lines(lore));
                Icons.hide(meta);
                it.setItemMeta(meta);
            }
            return it;
        }
        return Icons.of(Material.LEATHER_CHESTPLATE, name, lore);
    }

    /** Remove every PDC key so a menu icon is never a real (unique) sky item. */
    static void strip(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        for (NamespacedKey k : new ArrayList<>(meta.getPersistentDataContainer().getKeys())) meta.getPersistentDataContainer().remove(k);
        it.setItemMeta(meta);
    }

    static String lc(String s) { return s.toLowerCase(Locale.ROOT); }
}
