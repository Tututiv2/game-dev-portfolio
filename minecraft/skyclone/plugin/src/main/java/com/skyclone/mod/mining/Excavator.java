package com.skyclone.mod.mining;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Fossil Excavator (research 2.14): put in a Suspicious Scrap, dig with a chisel's charges, uncover
 * treasure cells and, 10% of the time, a fossil. A fossil counts once 60% of its cells are uncovered.
 */
final class Excavator {

    static final int CELLS = 45;
    /** Fossil shapes as (row, col) offsets. */
    static final Map<String, int[][]> SHAPES = new HashMap<>();
    static {
        SHAPES.put("SPINE_FOSSIL", new int[][]{{0, 0}, {0, 1}, {0, 2}, {0, 3}, {0, 4}, {1, 2}});
        SHAPES.put("CLUBBED_FOSSIL", new int[][]{{0, 0}, {0, 1}, {0, 2}, {1, 2}, {1, 3}, {2, 3}});
        SHAPES.put("UGLY_FOSSIL", new int[][]{{0, 1}, {1, 0}, {1, 1}, {1, 2}, {2, 1}, {2, 2}});
        SHAPES.put("FOOTPRINT_FOSSIL", new int[][]{{0, 0}, {0, 2}, {1, 1}, {2, 0}, {2, 1}, {2, 2}});
        SHAPES.put("WEBBED_FOSSIL", new int[][]{{0, 0}, {1, 1}, {2, 2}, {0, 2}, {2, 0}, {1, 2}});
        SHAPES.put("TUSK_FOSSIL", new int[][]{{0, 0}, {1, 0}, {2, 1}, {3, 2}, {3, 3}});
    }

    static final class Dig {
        int charges;
        final Set<Integer> revealed = new HashSet<>();
        final Map<Integer, MiningLoot.Line> treasure = new HashMap<>();
        final Set<Integer> fossilCells = new HashSet<>();
        String fossil;
        final List<String> found = new ArrayList<>();
        /** Citrine: treasure cells the chisel's gem points at before they are dug. */
        final Set<Integer> hinted = new HashSet<>();
        /** Peridot: +% Fossil Essence. */
        double essencePct;
    }

    /** The chisel stack the dig uses (best by charges): its slotted gems drive the chisel gem effects. */
    org.bukkit.inventory.ItemStack chiselStack(Player p, String id) {
        if (id == null) return null;
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents())
            if (it != null && !it.getType().isAir() && id.equals(Sky.items().idOf(it))) return it;
        return null;
    }

    /** Slotted gem ids of the chisel by type ("AQUAMARINE" -> best quality slotted). */
    static Map<String, String> chiselGems(java.util.Collection<String> slotted) {
        Map<String, String> best = new HashMap<>();
        if (slotted == null) return best;
        for (String g : slotted) {
            String t = MiningMath.gemType(g);
            if (t == null) continue;
            String cur = best.get(t);
            if (cur == null || MiningMath.gemQuality(g) > MiningMath.gemQuality(cur)) best.put(t, g);
        }
        return best;
    }

    private final MiningModule m;
    private final Map<UUID, Dig> digs = new HashMap<>();

    Excavator(MiningModule m) { this.m = m; }

    String bestChisel(Player p) {
        String best = null;
        int bestCh = 0;
        for (String id : List.of("PERFECT_CHISEL", "GLACITE_CHISEL", "REINFORCED_CHISEL", "CHISEL")) {
            if (Sky.items().count(p, id) > 0) {
                ItemDef d = Sky.items().def(id);
                int ch = d == null || d.extra == null || !d.extra.has("charges") ? 16 : d.extra.get("charges").getAsInt();
                if (ch > bestCh) { best = id; bestCh = ch; }
            }
        }
        return best;
    }

    void open(Player p) {
        Dig d = digs.get(p.getUniqueId());
        if (d == null) { lobby(p); return; }
        Menu menu = new Menu("Fossil Excavator", 6).chrome();
        for (int i = 0; i < CELLS; i++) {
            final int cell = i;
            if (!d.revealed.contains(i) && d.hinted.contains(i)) {
                menu.set(i, Icons.glow(Icons.of(Material.SUSPICIOUS_SAND, "&eSomething glints here", "&7Your chisel's &6Citrine &7points at", "&7buried treasure.", "&7Charges left: &a" + d.charges, "", "&eClick to chisel!")),
                        c -> chisel(c.player(), cell));
            } else if (!d.revealed.contains(i)) {
                menu.set(i, Icons.of(Material.SUSPICIOUS_GRAVEL, "&7Dirt", "&7Charges left: &a" + d.charges, "", "&eClick to chisel!"),
                        c -> chisel(c.player(), cell));
            } else if (d.fossilCells.contains(i)) {
                menu.set(i, Icons.glow(Icons.of(Material.BONE_BLOCK, "&6Fossil Fragment", "&7Part of something big...")));
            } else if (d.treasure.containsKey(i)) {
                MiningLoot.Line l = d.treasure.get(i);
                Material mat = l.item.endsWith("_POWDER") ? Material.LIGHT_BLUE_DYE : l.item.startsWith("ESSENCE_") ? Material.BONE_MEAL : Material.CHEST;
                menu.set(i, Icons.of(mat, "&aTreasure!", "&7" + Text.strip(MiningUtil.itemName(l.item)), "&7Collected when you finish."));
            } else {
                menu.set(i, Icons.of(Material.BROWN_STAINED_GLASS_PANE, "&8Empty"));
            }
        }
        menu.set(49, Icons.of(Material.BARRIER, "&cFinish Excavation", "&7Stop and collect everything", "&7you have uncovered.", "", "&eClick to finish!"), c -> finish(c.player()));
        menu.set(48, Icons.of(Material.BRUSH, "&aCharges: &e" + d.charges, "&7Each click spends one charge."));
        menu.open(p);
    }

    private void lobby(Player p) {
        MiningData data = MiningUtil.data(p);
        Menu menu = new Menu("Fossil Excavator", 6).chrome();
        String chisel = bestChisel(p);
        int scrap = Sky.items().count(p, "SUSPICIOUS_SCRAP");
        menu.set(13, Icons.of(Material.BRUSH, "&aFossil Excavator",
                "&7Put in a &9Suspicious Scrap &7and dig", "&7with a &aChisel&7. Each click spends a", "&7charge and uncovers one cell.",
                "", "&7A fossil hides in &a10% &7of scraps.", "&7Uncover &a60% &7of it to keep it.",
                "", "&7Treasure includes &dFossil Essence&7,", "&7spent at the &fFossil Researcher&7.",
                "", "&7Excavations: &a" + data.excavations, "&7Fossils found: &a" + data.fossilsFound));
        menu.set(30, Icons.of(Material.SUSPICIOUS_GRAVEL, "&9Suspicious Scrap", "&7You have: " + (scrap > 0 ? "&a" + scrap : "&c0")));
        menu.set(32, Icons.of(Material.BRUSH, chisel == null ? "&cNo Chisel" : MiningUtil.itemName(chisel), chisel == null ? "&7Buy one from the Pickaxe Smith." : "&7Your best chisel is used."));
        menu.set(31, Icons.of(Material.LIME_CONCRETE, "&aStart Excavating", "", scrap > 0 && chisel != null ? "&eClick to start!" : "&cYou need a scrap and a chisel."),
                c -> start(c.player()));
        menu.set(34, Icons.of(Material.BONE_BLOCK, "&6Fossil Research", "&7Hand each kind of fossil to the", "&7researchers once for a reward.", "",
                "&7Donated: &a" + data.fossilsDonated.size() + "&7/" + DONATABLE.size(), "", "&eClick to view!"), c -> openResearch(c.player()));
        menu.set(48, Icons.back("Mining"), c -> m.menus.openMain(c.player()));
        menu.open(p);
    }

    /** Every fossil kind that can be found, plus the two retired shapes players may still hold. */
    static final List<String> DONATABLE = List.of("SPINE_FOSSIL", "CLUBBED_FOSSIL", "UGLY_FOSSIL", "FOOTPRINT_FOSSIL", "WEBBED_FOSSIL",
            "TUSK_FOSSIL", "CLAW_FOSSIL", "HELIX");

    /** Fossil Research (reference: the fossil curator): donate each kind once for powder and SkyBlock XP. */
    void openResearch(Player p) {
        MiningData data = MiningUtil.data(p);
        Menu menu = new Menu("Fossil Research", 4).chrome();
        int[] pos = {10, 11, 12, 13, 14, 15, 16, 22};
        for (int i = 0; i < DONATABLE.size() && i < pos.length; i++) {
            String id = DONATABLE.get(i);
            if (!MiningUtil.exists(id)) continue;
            boolean given = data.fossilsDonated.contains(id);
            int have = Sky.items().count(p, id);
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("&7Reward: " + MiningUtil.powderColor("GLACITE") + Text.commas(m.dials.fossilPowder.get()) + " " + MiningUtil.powderName("GLACITE"));
            lore.add("&7        &b+" + m.dials.fossilSkyblockXp.get() + " SkyBlock XP");
            lore.add("");
            if (given) lore.add("&a&lDONATED");
            else if (have > 0) lore.add("&eClick to donate one!");
            else lore.add("&cFind one in the Fossil Excavator.");
            org.bukkit.inventory.ItemStack icon = Icons.withLore(Sky.items().create(id), lore);
            menu.set(pos[i], given ? Icons.glow(icon) : icon, given || have <= 0 ? null : c -> donate(c.player(), id));
        }
        menu.set(28, Icons.of(Material.SKELETON_SKULL, "&6Fossil Muncher", "&7It eats one fossil a day.", "",
                MiningUtil.today().equals(data.muncherDate) ? "&aFed today!" : "&eClick to open!"), c -> openMuncher(c.player()));
        menu.set(31, Icons.back("Fossil Excavator"), c -> open(c.player()));
        menu.open(p);
    }

    // ---- Fossil Muncher (research 03 / 07: daily fossil exchange -> 25,000 Glacite Powder + 100 Fossil Essence) ----

    /** The fossil the Muncher takes: the kind you hold the most of (a spare first, never your last undonated one). */
    String muncherFossil(Player p) {
        MiningData d = MiningUtil.data(p);
        String best = null;
        int bestScore = 0;
        for (String id : DONATABLE) {
            int have = MiningUtil.exists(id) ? Sky.items().count(p, id) : 0;
            if (have <= 0) continue;
            int spare = d.fossilsDonated.contains(id) ? have : have - 1;   // keep one of a kind you still have to donate
            int score = spare > 0 ? 1000 + spare : 1;
            if (score > bestScore) { best = id; bestScore = score; }
        }
        return best;
    }

    void openMuncher(Player p) {
        MiningData d = MiningUtil.data(p);
        boolean fed = MiningUtil.today().equals(d.muncherDate);
        String fossil = muncherFossil(p);
        Menu menu = new Menu("Fossil Muncher", 3).chrome();
        List<String> lore = new ArrayList<>(List.of("&7A hungry thing that lives off old", "&7bones. Feed it one fossil a day.", "",
                "&7Reward: " + MiningUtil.powderColor("GLACITE") + Text.commas(m.dials.muncherPowder.get()) + " " + MiningUtil.powderName("GLACITE"),
                "&7        &a" + m.dials.muncherEssence.get() + " Fossil Essence", "", "&7Meals so far: &a" + d.muncherMeals, ""));
        if (fed) lore.add("&aIt is full. Come back tomorrow.");
        else if (fossil == null) lore.add("&cYou have no fossil to feed it.");
        else {
            lore.add("&7It will eat: " + MiningUtil.itemName(fossil));
            if (!d.fossilsDonated.contains(fossil)) lore.add("&c(your only one, not donated yet!)");
            lore.add("&eClick to feed it!");
        }
        menu.set(13, Icons.of(Material.SKELETON_SKULL, "&6Fossil Muncher", lore.toArray(new String[0])),
                fed || fossil == null ? null : c -> feedMuncher(c.player()));
        menu.set(22, Icons.back("Fossil Research"), c -> openResearch(c.player()));
        menu.open(p);
    }

    private void feedMuncher(Player p) {
        MiningData d = MiningUtil.data(p);
        if (MiningUtil.today().equals(d.muncherDate)) { Msg.error(p, "The Muncher is full. Come back tomorrow."); return; }   // click-time re-check
        String fossil = muncherFossil(p);
        if (fossil == null || Sky.items().count(p, fossil) < 1 || Sky.items().remove(p, fossil, 1) < 1) { Msg.error(p, "You have no fossil to feed it."); return; }
        d.muncherDate = MiningUtil.today();
        d.muncherMeals++;
        long got = m.hotm.grant(p, "GLACITE", m.dials.muncherPowder.get(), "fossil muncher", false);
        int ess = m.dials.muncherEssence.get();
        if (ess > 0 && MiningUtil.exists("ESSENCE_FOSSIL")) Sky.loot().give(p, "ESSENCE_FOSSIL", ess, LootSource.REWARD);
        Sky.profiles().saveNow(p);
        Msg.box(p, "&6&lFOSSIL MUNCHER &7fed!", List.of("&7It crunched your " + MiningUtil.itemName(fossil) + "&7.",
                MiningUtil.powderColor("GLACITE") + "+" + Text.commas(got) + " " + MiningUtil.powderName("GLACITE"), "&a+" + ess + " Fossil Essence"));
        Msg.sound(p, Sound.ENTITY_GENERIC_EAT, 0.8f);
        openMuncher(p);
    }

    private void donate(Player p, String id) {
        MiningData data = MiningUtil.data(p);
        if (data.fossilsDonated.contains(id)) { Msg.error(p, "You already donated that fossil."); return; }
        if (Sky.items().count(p, id) < 1 || Sky.items().remove(p, id, 1) < 1) { Msg.error(p, "You don't have that fossil."); return; }
        data.fossilsDonated.add(id);
        long got = m.hotm.grant(p, "GLACITE", m.dials.fossilPowder.get(), "fossil donation", false);
        int sb = m.dials.fossilSkyblockXp.get();
        if (sb > 0) Sky.service(com.skyclone.api.feature.Features.SkyLevel.class).award(p, "mining:fossil:" + id, sb, "Donated a " + Text.strip(MiningUtil.itemName(id)));
        Sky.profiles().saveNow(p);
        Msg.box(p, "&6&lFOSSIL DONATED", List.of(MiningUtil.itemName(id), MiningUtil.powderColor("GLACITE") + "+" + Text.commas(got) + " " + MiningUtil.powderName("GLACITE"),
                "&b+" + sb + " SkyBlock XP", "&7Fossils donated: &a" + data.fossilsDonated.size() + "&7/" + DONATABLE.size()));
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.2f);
        openResearch(p);
    }

    /**
     * One excavator roll: 1 in 500,000 (research 2.14) is the Fossil Dye, otherwise a weighted line of the pool
     * whose item this server has (a missing book / curio is re-rolled, never shown as a dead cell). Null if none.
     */
    MiningLoot.Line excavatorRoll(MiningLoot.Excavator ex, java.util.Random r) {
        if (ex.dye != null && ex.dyeChance > 0 && r.nextDouble() < ex.dyeChance && MiningUtil.exists(ex.dye)) {
            MiningLoot.Line dye = new MiningLoot.Line();
            dye.item = ex.dye;
            return dye;
        }
        for (int tries = 0; tries < 8; tries++) {
            MiningLoot.Line l = m.loot.pick(ex.table);
            if (l != null && givable(l.item)) return l;
        }
        return null;
    }

    static boolean givable(String id) {
        return id != null && (id.endsWith("_POWDER") || "COINS".equals(id) || MiningUtil.exists(id));
    }

    void start(Player p) {
        String chisel = bestChisel(p);
        if (chisel == null) { Msg.error(p, "You need a Chisel."); return; }
        if (Sky.items().remove(p, "SUSPICIOUS_SCRAP", 1) < 1) { Msg.error(p, "You need a Suspicious Scrap."); return; }
        MiningLoot.Excavator ex = m.loot.f.excavator;
        Dig d = new Dig();
        ItemDef cd = Sky.items().def(chisel);
        d.charges = cd == null || cd.extra == null || !cd.extra.has("charges") ? 16 : cd.extra.get("charges").getAsInt();
        var r = MiningUtil.rnd();
        if (r.nextDouble() < ex.fossilChance && !ex.fossils.isEmpty()) {
            d.fossil = ex.fossils.get(r.nextInt(ex.fossils.size()));
            int[][] shape = SHAPES.getOrDefault(d.fossil, SHAPES.get("UGLY_FOSSIL"));
            int rows = 0, cols = 0;
            for (int[] o : shape) { rows = Math.max(rows, o[0] + 1); cols = Math.max(cols, o[1] + 1); }
            int r0 = r.nextInt(5 - rows + 1), c0 = r.nextInt(9 - cols + 1);
            for (int[] o : shape) d.fossilCells.add((r0 + o[0]) * 9 + c0 + o[1]);
        }
        // chisel gem slots (research 2.14): Aquamarine +1 charge, Onyx extra treasure, Citrine hints, Peridot essence
        org.bukkit.inventory.ItemStack cs = chiselStack(p, chisel);
        Map<String, String> gems = cs == null ? Map.of() : chiselGems(Sky.items().data(cs).gems.values());
        List<String> gemNotes = new ArrayList<>();
        if (gems.containsKey("AQUAMARINE") && r.nextDouble() < MiningMath.chiselAquamarine(gems.get("AQUAMARINE"))) {
            d.charges++;
            gemNotes.add("&3Aquamarine: &a+1 charge");
        }
        d.essencePct = gems.containsKey("PERIDOT") ? MiningMath.chiselPeridot(gems.get("PERIDOT")) : 0;
        int rolls = m.loot.between(ex.rolls) + (gems.containsKey("ONYX") ? MiningMath.chiselOnyx(gems.get("ONYX")) : 0);
        for (int i = 0; i < rolls; i++) {
            int cell = r.nextInt(CELLS);
            if (d.fossilCells.contains(cell) || d.treasure.containsKey(cell)) continue;
            MiningLoot.Line line = excavatorRoll(ex, r);
            if (line != null) d.treasure.put(cell, line);
        }
        if (gems.containsKey("CITRINE")) {
            List<Integer> cells = new ArrayList<>(d.treasure.keySet());
            java.util.Collections.shuffle(cells, r);
            int n = Math.min(cells.size(), MiningMath.chiselCitrine(gems.get("CITRINE")));
            d.hinted.addAll(cells.subList(0, n));
            if (n > 0) gemNotes.add("&6Citrine: &a" + n + " treasure cell" + (n == 1 ? "" : "s") + " marked");
        }
        if (gems.containsKey("ONYX")) gemNotes.add("&8Onyx: &a+" + MiningMath.chiselOnyx(gems.get("ONYX")) + " treasure");
        if (d.essencePct > 0) gemNotes.add("&2Peridot: &a+" + (int) d.essencePct + "% Fossil Essence");
        if (!gemNotes.isEmpty()) Msg.send(p, "&7Chisel gems: " + String.join("&7, ", gemNotes));
        digs.put(p.getUniqueId(), d);
        MiningUtil.data(p).excavations++;
        Msg.sound(p, Sound.ITEM_BRUSH_BRUSHING_GRAVEL, 1f);
        open(p);
    }

    private void chisel(Player p, int cell) {
        Dig d = digs.get(p.getUniqueId());
        if (d == null || d.revealed.contains(cell)) return;
        if (d.charges <= 0) { finish(p); return; }
        d.charges--;
        d.revealed.add(cell);
        Msg.sound(p, Sound.ITEM_BRUSH_BRUSHING_GRAVEL_COMPLETE, 1.2f);
        if (d.charges <= 0) { finish(p); return; }
        open(p);
    }

    /** Peridot: a Fossil Essence line paid +pct% (rounded down, at least the base). */
    static MiningLoot.Line withEssence(MiningLoot.Line l, double pct) {
        if (l == null || pct <= 0 || l.item == null || !l.item.startsWith("ESSENCE_FOSSIL")) return l;
        MiningLoot.Line c = new MiningLoot.Line();
        c.item = l.item;
        c.weight = l.weight;
        c.zoneGem = l.zoneGem;
        c.min = (int) Math.max(l.min, Math.floor(l.min * (1 + pct / 100.0)));
        c.max = (int) Math.max(l.max, Math.floor(l.max * (1 + pct / 100.0)));
        return c;
    }

    void finish(Player p) {
        Dig d = digs.remove(p.getUniqueId());
        if (d == null) { p.closeInventory(); return; }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Integer, MiningLoot.Line> e : d.treasure.entrySet())
            if (d.revealed.contains(e.getKey())) {
                lines.add(m.loot.give(p, withEssence(e.getValue(), d.essencePct), null, LootSource.MINING));
                String id = e.getValue().item;
                if (id.startsWith("DYE_") || "FOSSIL_THE_FISH".equals(id))
                    Msg.rareDrop(p, id.startsWith("DYE_") ? "&d&lINSANE DROP!" : "&6&lRARE FIND!", MiningUtil.itemName(id), 0);
            }
        if (d.fossil != null) {
            long seen = d.fossilCells.stream().filter(d.revealed::contains).count();
            if (seen >= Math.ceil(d.fossilCells.size() * 0.6)) {
                Sky.loot().give(p, d.fossil, 1, LootSource.MINING);
                MiningUtil.data(p).fossilsFound++;
                lines.add("&6&lFOSSIL! " + MiningUtil.itemName(d.fossil));
            } else lines.add("&7A fossil was in there... you only uncovered &c" + seen + "&7/" + d.fossilCells.size() + " of it.");
        }
        if (lines.isEmpty()) lines.add("&7Nothing but dust.");
        Msg.box(p, "&a&lEXCAVATION COMPLETE", lines);
        p.closeInventory();
    }

    /**
     * A dig in progress is KEPT across a disconnect (the scrap was already spent): /excavator resumes it.
     * Only a server restart loses an unfinished dig.
     */
    void quit(UUID u) { }
}
