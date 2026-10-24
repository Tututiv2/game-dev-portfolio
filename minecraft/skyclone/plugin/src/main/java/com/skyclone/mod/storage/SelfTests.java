package com.skyclone.mod.storage;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Recipe;

import java.util.ArrayList;
import java.util.List;

/** /sb selftest storage : the data hangs together and the formulas give the research numbers. */
final class SelfTests {

    private final StorageModule mod;

    SelfTests(StorageModule mod) { this.mod = mod; }

    void register() {
        Sky.commands().selfTest("storage.catalogue-loaded", this::catalogue);
        Sky.commands().selfTest("storage.items-registered", this::items);
        Sky.commands().selfTest("storage.sack-contents-exist", this::contents);
        Sky.commands().selfTest("storage.formulas", this::formulas);
        Sky.commands().selfTest("storage.gates", this::gates);
        Sky.commands().selfTest("storage.recipes-valid", this::recipes);
        Sky.commands().selfTest("storage.services-provided", this::services);
        Sky.commands().selfTest("storage.rune-sack", this::runeSack);
        Sky.commands().selfTest("storage.gfs-resolves", this::gfs);
        // wave 5
        Sky.commands().selfTest("storage.equipment-wardrobe", this::equipmentWardrobe);
        Sky.commands().selfTest("storage.dupe-scan-source", this::dupeSource);
        Sky.commands().selfTest("storage.sack-size", this::sackSize);
        Sky.commands().selfTest("storage.rune-groups", this::runeGroups);
        Sky.commands().selfTest("storage.backpack-skins", this::backpackSkins);
    }

    /**
     * Wave 5: the 29 reference backpack skins are registered (cosmetic, unique, with a head texture), and a skin put on a
     * stored backpack survives the base64 round trip and changes only its look.
     */
    private String backpackSkins() {
        List<String> skins = new ArrayList<>();
        for (ItemDef d : Sky.items().all()) if (d != null && d.has("backpack_skin")) skins.add(d.id);
        if (mod.skinsLoaded > 0 && skins.size() < 29) return "only " + skins.size() + " backpack skins registered";
        for (String id : skins) {
            ItemDef d = Sky.items().def(id);
            if (!d.isUnique()) return id + " stacks";
            if (d.category != ItemCategory.COSMETIC) return id + " is " + d.category;
            if (!StorageMenus.isBackpackSkin(id)) return id + " has no head texture";
        }
        if (skins.isEmpty() || !Sky.items().exists("SMALL_BACKPACK")) return null;
        org.bukkit.inventory.ItemStack bp = Sky.items().create("SMALL_BACKPACK");
        if (StorageMenus.skinOf(bp) != null) return "a fresh backpack has a skin";
        String skin = skins.get(0);
        Sky.items().update(bp, d -> d.extra.addProperty(StorageMenus.SKIN, skin));
        org.bukkit.inventory.ItemStack back = Codec.decode(Codec.encode(bp));
        if (back == null || !skin.equals(StorageMenus.skinOf(back))) return "skin lost in storage: " + (back == null ? "undecodable" : StorageMenus.skinOf(back));
        if (!"SMALL_BACKPACK".equals(Codec.id(back))) return "skinned backpack changed id to " + Codec.id(back);
        if (StorageMenus.skinned(back).getType() != org.bukkit.Material.PLAYER_HEAD) return "skinned look is not a head";
        if (mod.catalog().backpackSlots(Codec.id(back)) != 9) return "skin changed the backpack size";
        return null;
    }

    /**
     * Wave 5: every registered necklace/cloak/belt/gloves has exactly one Equipment Wardrobe row, nothing else does,
     * no equipment piece fits an ARMOUR row (and no armour an equipment row), the slot ladder covers 18 slots with the
     * first free, and the bridge reports a state.
     */
    private String equipmentWardrobe() {
        int pieces = 0;
        for (ItemDef d : Sky.items().all()) {
            if (d == null || d.category == null) continue;
            int row = EquipBridge.piece(d.category);
            if (d.category.isEquipment() != (row >= 0)) return d.id + " (" + d.category + ") row " + row;
            if (row >= 0) pieces++;
        }
        for (String id : List.of("NECKLACE", "CLOAK", "BELT", "GLOVES")) {
            if (EquipBridge.piece(ItemCategory.parse(id)) != java.util.Arrays.asList(EquipBridge.SLOTS).indexOf(id)) return "row order of " + id;
        }
        // an equipment piece never fits an armour row
        for (ItemDef d : Sky.items().all()) {
            if (d == null || d.category == null || !d.category.isEquipment()) continue;
            org.bukkit.inventory.ItemStack it = Sky.items().create(d.id);
            for (int r = 0; r < 4; r++) if (WardrobeMenu.fits(it, r)) return d.id + " fits armour row " + r;
            if (!EquipWardrobeMenu.fits(it, EquipBridge.piece(d.category))) return d.id + " does not fit its own equipment row";
            break;
        }
        for (String armour : List.of("DIAMOND_HELMET", "IRON_CHESTPLATE")) {
            if (!Sky.items().exists(armour)) continue;
            org.bukkit.inventory.ItemStack it = Sky.items().create(armour);
            for (int r = 0; r < 4; r++) if (EquipWardrobeMenu.fits(it, r)) return armour + " fits equipment row " + r;
        }
        int[] lv = StorageMath.thresholds(StorageDials.EQ_SLOT_LEVELS.get());
        if (lv.length < 18) return "equipment-wardrobe-slot-levels has " + lv.length + " entries for 18 slots";
        if (lv[0] != 0) return "the first Equipment Wardrobe slot is not free";
        if (mod.equipBridge().describe() == null) return "bridge has no state";
        return pieces == 0 && Sky.has(Features.Equipment.class) ? "no equipment items registered although Features.Equipment is provided" : null;
    }

    /**
     * Wave 5: what core's dupe scan reads from us. A unique item survives the stored base64 round trip with its uuid
     * (so a copy in storage matches the one in the inventory), a stackable carries none (never reported).
     */
    private String dupeSource() {
        String unique = null, stack = null;
        for (String id : List.of("ASPECT_OF_THE_END", "DIAMOND_SWORD", "SMALL_BACKPACK", "BEGINNER_AGRONOMY_SACK")) {
            ItemDef d = Sky.items().def(id);
            if (d != null && d.isUnique()) { unique = id; break; }
        }
        for (String id : List.of("WHEAT", "COBBLESTONE", "COAL")) if (Sky.items().exists(id)) { stack = id; break; }
        if (unique == null) {
            for (ItemDef d : Sky.items().all()) if (d != null && d.isUnique()) { unique = d.id; break; }
        }
        if (unique == null) return "no unique item registered to test with";
        org.bukkit.inventory.ItemStack it = Sky.items().create(unique);
        String u = Codec.uuid(it);
        if (u == null || u.isEmpty()) return unique + " has no uuid";
        org.bukkit.inventory.ItemStack back = Codec.decode(Codec.encode(it));
        if (back == null) return unique + " did not survive encode/decode";
        if (!u.equals(Codec.uuid(back))) return unique + " uuid changed in storage: " + u + " -> " + Codec.uuid(back);
        if (stack != null && Codec.uuid(Sky.items().create(stack)) != null) return stack + " is stackable but has a uuid";
        if (!StorageDials.DUPE_SCAN_SOURCE.get()) return null;   // turned off on purpose
        return null;
    }

    /** Wave 5: Hunting's Sack Size (+1%/level, value:sack_size) raises capacity, floors, and is a real attribute key. */
    private String sackSize() {
        if (StorageMath.withPercent(640, 0) != 640) return "0% changed 640";
        if (StorageMath.withPercent(640, 10) != 704) return "640 +10% = " + StorageMath.withPercent(640, 10);
        if (StorageMath.withPercent(2240, 5) != 2352) return "2,240 +5% = " + StorageMath.withPercent(2240, 5);
        if (StorageMath.withPercent(20160, -5) != 20160) return "a negative Sack Size shrank a sack";
        if (StorageMath.withPercent(Long.MAX_VALUE / 2, 100) < Long.MAX_VALUE / 4) return "overflow";
        if (mod.sacks().sackSizePct(null) != 0) return "no player but a Sack Size";
        try (java.io.InputStream in = Sky.plugin().getResource("data/hunting/shards.json")) {
            if (in != null) {
                String all = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!all.contains("\"sack_size\"")) return "hunting's shards.json has no sack_size attribute (renamed?)";
            }
        } catch (java.io.IOException e) {
            return "could not read hunting shards: " + e.getMessage();
        }
        return null;
    }

    /** Wave 5: the grouped Rune Sack shows every rune it holds exactly once, tiers under their own rune. */
    private String runeGroups() {
        Catalog.Family f = mod.catalog().familyById.get("RUNE");
        if (f == null) return "no RUNE family";
        java.util.Map<String, String[]> g = mod.sackMenus().runeGroups();
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (String[] tiers : g.values()) for (String id : tiers) if (id != null && !shown.add(id)) return id + " shown twice";
        for (String id : f.contents()) if (!shown.contains(id)) return id + " missing from the grouped Rune Sack";
        if (!mod.runeTiers.isEmpty()) {
            boolean full = false;
            for (String[] tiers : g.values()) full |= tiers.length == 3 && tiers[0] != null && tiers[1] != null && tiers[2] != null;
            if (!full) return "no rune has all three tiers grouped";
            for (java.util.Map.Entry<String, String> en : mod.runeTiers.entrySet()) {
                ItemDef d = Sky.items().def(en.getKey());
                if (d != null && !en.getValue().equals(StorageModule.runeTag(d))) return en.getKey() + " tag " + en.getValue();
            }
        }
        if (!SackMenus.baseRuneName("◆ Blood Rune III").equals("◆ Blood Rune")) return "rune name strip";
        return null;
    }

    /** Wave 3: the Rune family holds every stackable rune any module registered. */
    private String runeSack() {
        int runes = 0;
        for (ItemDef d : Sky.items().all()) if (d != null && d.has("rune") && !d.isUnique()) runes++;
        Catalog.Family f = mod.catalog().familyById.get("RUNE");
        if (f == null) return "no RUNE family";
        if (runes == 0) return null;   // no module registers runes: nothing to hold
        if (f.contents().size() < runes) return "Rune family holds " + f.contents().size() + " of " + runes + " runes";
        for (String id : f.contents()) if (mod.catalog().holdersOf(id).stream().noneMatch(x -> x.id().equals("RUNE"))) return id + " not indexed";
        return null;
    }

    /** Wave 3: /gfs resolves ids and display names of real sack contents. */
    private String gfs() {
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        for (String id : mod.catalog().allContents()) {
            ItemDef d = Sky.items().def(id);
            if (d != null) names.put(id, com.skyclone.api.ui.Text.strip(d.name));
        }
        for (String id : List.of("WHEAT", "COBBLESTONE", "ENCHANTED_DIAMOND")) {
            if (!names.containsKey(id)) continue;
            List<String> byId = StorageMath.matchItem(id.toLowerCase(), names);
            if (!byId.equals(List.of(id))) return "/gfs " + id.toLowerCase() + " -> " + byId;
            List<String> byName = StorageMath.matchItem(names.get(id), names);
            if (byName.size() != 1) return "/gfs " + names.get(id) + " -> " + byName;
        }
        return null;
    }

    private String catalogue() {
        Catalog c = mod.catalog();
        if (c.families.size() < 30) return "only " + c.families.size() + " sack families loaded (expected 34)";
        if (c.sacks.size() < 60) return "only " + c.sacks.size() + " sacks loaded (expected 68)";
        if (c.backpackBase.size() != 5) return "backpack sizes loaded: " + c.backpackBase.keySet();
        for (Catalog.Sack s : c.sacks.values()) {
            if (!c.familyById.containsKey(s.family())) return s.id() + " points at unknown family " + s.family();
            if (s.capacity() <= 0) return s.id() + " has no capacity";
        }
        for (Catalog.Family f : c.families) {
            if (f.sacks().isEmpty()) return "family " + f.id() + " has no sack item";
            if (f.contents().isEmpty() && !f.id().equals("RUNE")) return "family " + f.id() + " holds nothing";
        }
        if (c.quiver.isEmpty()) return "no arrow ids for the quiver";
        return null;
    }

    private String items() {
        List<String> bad = new ArrayList<>();
        for (String id : mod.catalog().sacks.keySet()) {
            ItemDef d = Sky.items().def(id);
            if (d == null) bad.add(id + " missing");
            else if (d.category != ItemCategory.SACK) bad.add(id + " is " + d.category);
        }
        for (String id : mod.catalog().backpackSlots.keySet()) {
            ItemDef d = Sky.items().def(id);
            if (d == null) bad.add(id + " missing");
            else if (d.category != ItemCategory.BACKPACK) bad.add(id + " is " + d.category);
        }
        for (String id : List.of("JUMBO_BACKPACK_UPGRADE", "POCKET_SACK_IN_A_SACK")) if (!Sky.items().exists(id)) bad.add(id + " missing");
        if (mod.itemsLoaded < 130) bad.add("items.json loaded only " + mod.itemsLoaded);
        return bad.isEmpty() ? null : bad.size() + " problems: " + head(bad);
    }

    /** Families whose items belong to wave-2 modules (dungeons, rift) and may legitimately be absent today. */
    static final java.util.Set<String> WAVE2 = java.util.Set.of("DUNGEON", "RUNE", "WITCH", "FROG_BRONZE", "FROG_SILVER");

    /** Contents are other modules' items. Up to 15% (outside wave-2 families) may be missing while modules are built. */
    private String contents() {
        List<String> miss = new ArrayList<>();
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        for (Catalog.Family f : mod.catalog().families) if (!WAVE2.contains(f.id())) ids.addAll(f.contents());
        int total = 0;
        for (String id : ids) {
            total++;
            if (!Sky.items().exists(id)) miss.add(id);
        }
        // the core materials every early sack needs
        for (String id : List.of("WHEAT", "COBBLESTONE", "COAL", "ROTTEN_FLESH", "BONE", "STRING", "LOG", "LEATHER")) {
            if (!mod.catalog().sackable(id)) return "no sack holds " + id;
        }
        double frac = total == 0 ? 1 : miss.size() / (double) total;
        if (frac > 0.15) return miss.size() + "/" + total + " sack contents are not registered (see /sb storage missing): " + head(miss);
        return null;
    }

    private String formulas() {
        Catalog c = mod.catalog();
        // capacities from the item lore (research data): 640 / 2,240 / 6,720 / 20,160
        String r = cap(c, "BEGINNER_AGRONOMY_SACK", 640);
        if (r == null) r = cap(c, "SMALL_AGRONOMY_SACK", 2240);
        if (r == null) r = cap(c, "MEDIUM_AGRONOMY_SACK", 6720);
        if (r == null) r = cap(c, "LARGE_AGRONOMY_SACK", 20160);
        if (r == null) r = cap(c, "LARGE_GEMSTONE_SACK", 2560000);
        if (r == null) r = cap(c, "BRONZE_TROPHY_FISHING_SACK", 512);
        if (r != null) return r;
        // Pocket Sack-in-a-Sack: +200% of the original, up to three times -> 20,160 x 7
        long lined = StorageMath.sackCapacity(20160, 3, 3, 2.0);
        if (lined != 141120) return "3 linings on 20,160 gave " + lined + " (expected 141,120)";
        if (StorageMath.sackCapacity(20160, 5, 3, 2.0) != 141120) return "linings are not capped at 3";
        // backpacks 9 / 18 / 27 / 36 / 45
        int[] want = {9, 18, 27, 36, 45};
        String[] sizes = {"SMALL", "MEDIUM", "LARGE", "GREATER", "JUMBO"};
        for (int i = 0; i < sizes.length; i++) {
            int got = c.backpackSlots(sizes[i] + "_BACKPACK");
            if (got != want[i]) return sizes[i] + "_BACKPACK has " + got + " slots (expected " + want[i] + ")";
        }
        // ender chest: 8 pages of 45 + one of 36 = 396
        if (StorageMath.enderSlots(9, 9, 36) != 396) return "ender chest total " + StorageMath.enderSlots(9, 9, 36) + " (expected 396)";
        // quiver: String III 27, VI 36, IX 45
        if (StorageMath.quiverSlots(3, 27) != 27 || StorageMath.quiverSlots(6, 27) != 36 || StorageMath.quiverSlots(9, 27) != 45 || StorageMath.quiverSlots(2, 27) != 0)
            return "quiver slots do not follow String III/VI/IX";
        // Sack of Sacks: Tropical Fish IV unlock (3), +4 at V-VIII, +5 at IX = 24, plus the 2 free account slots
        if (StorageMath.sackBagSlots(2, 9, 3) != 26) return "Sack of Sacks at Tropical Fish IX gave " + StorageMath.sackBagSlots(2, 9, 3) + " (expected 26)";
        // gems: a fine gem weighs 6,400 rough, so 2,560,000 rough holds exactly 400 fine
        if (StorageMath.fits(2560000, 0, 1000, 6400) != 400) return "gem weighting broken";
        return null;
    }

    private static String cap(Catalog c, String id, long want) {
        Catalog.Sack s = c.sacks.get(id);
        if (s == null) return id + " missing from the catalogue";
        return s.capacity() == want ? null : id + " capacity " + s.capacity() + " (expected " + want + ")";
    }

    private String gates() {
        int[] souls = StorageMath.thresholds(StorageDials.BP_SOULS.get());
        if (souls.length < mod.store().bpSlots()) return "backpack-souls has " + souls.length + " entries for " + mod.store().bpSlots() + " slots";
        int[] research = {0, 5, 10, 20, 30, 40, 50, 65, 80, 95, 110, 125, 140, 155, 170, 185, 200, 220};
        if (StorageDials.BP_SOULS.get().equals(StorageDials.BP_SOULS.defaultValue())) {
            for (int i = 0; i < research.length; i++) if (souls[i] != research[i]) return "backpack slot " + (i + 1) + " needs " + souls[i] + " souls (research " + research[i] + ")";
        }
        int ec = StorageMath.thresholds(StorageDials.EC_PAGE_LEVELS.get()).length;
        if (ec < mod.store().ecPages()) return "ender-page-levels has " + ec + " entries for " + mod.store().ecPages() + " pages";
        int wd = StorageMath.thresholds(StorageDials.WD_SLOT_LEVELS.get()).length;
        if (wd < 18) return "wardrobe-slot-levels has " + wd + " entries for 18 slots";
        int[] w = StorageMath.thresholds(StorageDials.WD_SLOT_LEVELS.get());
        if (w[0] != 0) return "the first wardrobe slot is not free";
        if (StorageMath.tieredValue(StorageDials.VAULT_TIERS.get(), 50_000_000) <= 0) return "a starter bank account has no Personal Vault slots";
        return null;
    }

    private String recipes() {
        int n = 0;
        for (String id : mod.catalog().sacks.keySet()) {
            for (Recipe r : Sky.recipes().producing(id)) {
                n++;
                String miss = StorageModule.missingIngredient(r);
                if (miss != null) return "recipe for " + id + " needs unknown " + miss;
            }
        }
        for (String id : mod.catalog().backpackBase.values()) {
            for (Recipe r : Sky.recipes().producing(id)) {
                n++;
                String miss = StorageModule.missingIngredient(r);
                if (miss != null) return "recipe for " + id + " needs unknown " + miss;
            }
        }
        if (Sky.recipes().producing("SMALL_BACKPACK").isEmpty()) return "Small Backpack has no recipe";
        if (Sky.recipes().producing("BEGINNER_AGRONOMY_SACK").isEmpty()) return "Beginner Agronomy Sack has no recipe";
        return n == 0 ? "no storage recipes at all" : null;
    }

    private String services() {
        if (!Sky.has(Features.Sacks.class)) return "Features.Sacks not provided";
        if (!Sky.has(Features.Storage.class)) return "Features.Storage not provided";
        if (!(Sky.service(Features.Sacks.class) instanceof SackService)) return "another module provides Features.Sacks";
        if (!(Sky.service(Features.Storage.class) instanceof StorageMenus)) return "another module provides Features.Storage";
        return null;
    }

    private static String head(List<String> l) {
        return l.size() <= 12 ? String.valueOf(l) : l.subList(0, 12) + " ...";
    }
}
