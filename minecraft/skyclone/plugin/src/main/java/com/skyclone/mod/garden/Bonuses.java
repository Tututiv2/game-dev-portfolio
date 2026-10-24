package com.skyclone.mod.garden;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * "Garden Bonuses" (Desk slot 25, {@code /gardenbonuses}): one page that says what every accessory, worn piece,
 * pet perk and reforge is doing for you in the Garden right now, with the live numbers. Nothing here changes
 * state; it reads the same functions the effects run through, so the page and the game cannot disagree.
 */
final class Bonuses {

    private Bonuses() {}

    static String pct(double v) { return "&a+" + Effects.fmt(Math.round(v * 10) / 10.0) + "%"; }

    static Menu build(Player p) {
        GardenData d = G.data(p);
        GardenMember mem = G.member(p);
        Menu m = new Menu("Garden Bonuses", 6).chrome();
        m.set(4, Icons.of(Material.NETHER_STAR, "&aGarden Bonuses",
                "&7Everything your gear and pet", "&7does for you in the Garden.", "",
                "&7Farming Fortune: &6" + Text.commasInt(Sky.stats().get(p, Stat.FARMING_FORTUNE)) + "☘",
                "&7Fortune against pests: &6" + Text.commasInt(Pests.pestFortune(p)) + "☘",
                "&7Overbloom: &d" + Effects.fmt(Math.round(Effects.overbloom(p, false) * 10) / 10.0)
                        + " &8(&d" + Effects.fmt(Math.round(Effects.overbloom(p, true) * 10) / 10.0) + " &8vs pests)"));

        // ---- accessories ----
        int ct = Effects.tier(p, "COPPER"), gt = Effects.tier(p, "GRATITUDE"), bt = Effects.tier(p, "BIOANALYSIS");
        m.set(19, card(ct > 0, Material.COPPER_INGOT, "&6Copper Talisman Line", ct,
                "&9Rare &7and better visitors are " + pct(Effects.copperRarePct(p)), "&7more likely to arrive."));
        m.set(20, card(gt > 0, Material.EMERALD, "&aGratitude Talisman Line", gt,
                "&7Visitors arrive " + pct(Effects.gratitudePct(p)) + " &7sooner", "&7while you are in your Garden."));
        m.set(21, card(bt > 0, Material.SPYGLASS, "&dBioanalysis Talisman Line", bt,
                "&7Greenhouse crops are " + pct(Effects.bioanalysisPct(p)), "&7more likely to mutate."));
        int season = Effects.season();
        boolean filter = Effects.hasAccessory(p, "ATMOSPHERIC_FILTER");
        String[] seasons = {"&aSpring", "&eSummer", "&6Autumn", "&bWinter"};
        List<String> fl = new ArrayList<>();
        fl.add("&7Season: " + (season < 0 ? "&8?" : seasons[season]));
        fl.add(season == 2 ? "&7Pests show up " + pct(G.filterAutumnPct.get()) + " &7more often." : season == 3
                ? "&7Visitors pay " + pct(G.filterWinterPct.get()) + " &7more Copper." : "&7Its stat half is on the item itself.");
        fl.add("");
        fl.add(filter ? "&aActive" : "&cYou do not have one");
        m.set(22, Icons.of(filter ? Material.CAMPFIRE : Material.GRAY_DYE, "&9Atmospheric Filter", fl));

        // ---- equipment ----
        List<String> pieces = new ArrayList<>();
        double pieceTotal = 0;
        for (ItemStack s : Effects.worn(p)) {
            String id = Effects.id(s);
            if (!EffectMath.isGrowingPiece(id)) continue;
            long served = Effects.served(mem, s);
            double b = EffectMath.pieceBonus(id, served);
            pieceTotal += b;
            int next = EffectMath.nextThreshold(served);
            pieces.add(G.itemName(id) + " &6+" + Effects.fmt(b) + "☘ &8(" + Text.commasInt(served) + (next < 0 ? ", max)" : "/" + Text.commasInt(next) + ")"));
        }
        if (pieces.isEmpty()) { pieces.add("&7Wear Peony or Blossom pieces: every"); pieces.add("&7visitor offer you complete grows them."); }
        pieces.add(0, "&7Piece Bonus total: &6+" + Effects.fmt(pieceTotal) + "☘ Farming Fortune");
        pieces.add(1, "");
        m.set(28, Icons.of(Material.PINK_TULIP, "&dPeony & Blossom Pieces", pieces));
        int ph = Effects.pesthunterPieces(p);
        double cd = GardenModule.pests.cooldown(p);
        m.set(29, Icons.of(Material.LEATHER_CHESTPLATE, "&2Pesthunter's Equipment",
                "&7Pieces worn: &a" + ph + "&7/&a4", "&7Eradicator: &6+" + Effects.fmt(EffectMath.eradicatorFortune(ph, G.eradicatorPerPiece.get()))
                        + "☘ &7Farming Fortune against pests", "",
                "&7Your pest cooldown now: &e" + Text.duration((long) cd)));

        // ---- the held vacuum ----
        ItemStack hand = p.getInventory().getItemInMainHand();
        String handId = Effects.id(hand);
        GardenTables.Vacuum v = handId == null ? null : G.T.vacuum(handId);
        if (v != null) {
            String rf = Effects.reforgeOf(hand);
            m.set(30, Icons.of(Material.HOPPER, "&aHeld Vacuum",
                    "&7Damage per second: &c" + Text.commasInt(Pests.vacuumDamage(p, hand, v.damage, 0)),
                    "&7Reforge: " + (rf == null ? "&8none" : "&9" + Text.titleCase(rf)),
                    "&8Buzzing doubles damage; Beady adds", "&8+100☘ Farming Fortune against pests."));
        } else m.set(30, Icons.of(Material.GRAY_DYE, "&7Held Vacuum", "&7Hold a vacuum to see its numbers."));

        // ---- garden chips ----
        List<String> cl = new ArrayList<>();
        for (java.util.Map.Entry<String, GardenTables.Chip> e : G.T.garden.chips.entrySet()) {
            int lvl = Chips.level(d, e.getKey());
            if (lvl > 0) cl.add("&a" + e.getValue().name + " " + lvl + "&7: " + Chips.effect(e.getValue(), lvl, Chips.rarity(d, e.getKey())));
        }
        if (cl.isEmpty()) cl.add("&8No chips redeemed yet.");
        double temp = Effects.temporaryFortune(p);
        if (temp > 0) cl.add("&7Temporary Farming Fortune now: &6+" + Effects.fmt(temp) + "☘");
        cl.add("");
        cl.add("&eClick to view chips!");
        m.set(31, Icons.of(Material.AMETHYST_SHARD, "&aGarden Chips", cl), c -> Chips.menu(c.player()).open(c.player()));

        // ---- pet ----
        List<String> pl = new ArrayList<>();
        String type = null;
        try { type = Sky.service(Features.Pets.class).activeType(p); } catch (Throwable ignored) { }
        pl.add("&7Active pet: " + (type == null ? "&8none" : "&a" + Text.titleCase(type)));
        pl.add("");
        addPet(pl, p, "mosquito_smooth_jazz", "a", "&7Pest vinyls ", "% stronger");
        addPet(pl, p, "mosquito_buzzin_barterer", "a", "&7Sugar Cane Fortune per unique visitor ", "");
        addPet(pl, p, "mosquito_bloodsucker_s_betrayal", "a", "&7Pest traps catch ", "% faster");
        addPet(pl, p, "rose_dragon_rosy_scales", "a", "&7Farming Fortune per crop milestone ", "");
        addPet(pl, p, "rose_dragon_dragon_s_gluttony", "a", "&7Overbloom ", "");
        addPet(pl, p, "rose_dragon_spiritual_perfection", "a", "&7Copper from visitors and analysis ", "%");
        addPet(pl, p, "slug_repugnant_aroma", "a", "&7Farming Fortune on sprayed plots ", "");
        addPet(pl, p, "hedgehog_spiky_quills", "a", "&7Damage to pests ", "%");
        addPet(pl, p, "hedgehog_fearsome_farmer", "a", "&7Farming Fortune against pests ", "");
        addPet(pl, p, "elephant_abundant_harvest", "a", "&7Sowdust from farming ", "%");
        double bandana = Effects.greenBandanaFortune(p, G.level(d));
        if (bandana > 0) pl.add("&7Green Bandana: &6+" + Effects.fmt(bandana) + "☘ &8(4 per Garden level)");
        if (pl.size() == 2) pl.add("&8No Garden perks on this pet.");
        m.set(32, Icons.of(Material.BONE, "&aPet Perks", pl));

        int squeaky = 0;
        for (ItemStack s : Effects.worn(p)) if ("squeaky".equals(Effects.reforgeOf(s))) squeaky++;
        m.set(33, Icons.of(Material.NOTE_BLOCK, "&9Reforges", "&7Squeaky pieces worn: &a" + squeaky + " &8(pests return sooner)",
                "&7Overbloom from reforges and", "&7accessories raises rare-drop odds", "&7on pests, plot clearing and the", "&7greenhouse bounty."));
        m.back("The Desk", () -> Desk.build(p));
        return m;
    }

    private static ItemStack card(boolean on, Material icon, String name, int tier, String... lines) {
        List<String> lore = new ArrayList<>();
        String[] names = {"&8none", "&fTalisman", "&aRing", "&9Artifact"};
        lore.add("&7Best owned: " + names[Math.max(0, Math.min(3, tier))]);
        lore.add("");
        for (String l : lines) lore.add(l);
        return Icons.of(on ? icon : Material.GRAY_DYE, name, lore);
    }

    private static void addPet(List<String> out, Player p, String key, String tok, String label, String unit) {
        double v = Effects.pet(p, key, tok);
        if (v != 0) out.add(label + "&a+" + Effects.fmt(Math.round(v * 100) / 100.0) + unit);
    }
}
