package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.EnchantDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared state and small helpers every fishing class uses. */
final class Fishing {

    private Fishing() {}

    static FishingCatalog CAT = new FishingCatalog();
    static PoolResolver RES = new PoolResolver(CAT);
    static final String ID = "fishing";
    static final String FESTIVAL_EVENT = "fishing_festival";
    /** Registered and driven by mayors (Foxy's Extra Event, fishing variant). */
    static final String FESTIVAL_EXTRA_EVENT = "fishing_festival_extra";

    static void load() {
        try (InputStream in = Sky.plugin().getResource("data/fishing/fishing.json")) {
            if (in == null) { Sky.log().severe("[fishing] data/fishing/fishing.json missing from the jar"); return; }
            CAT = FishingCatalog.read(new InputStreamReader(in, StandardCharsets.UTF_8));
            RES = new PoolResolver(CAT);
        } catch (Exception e) {
            Sky.log().severe("[fishing] could not read fishing.json: " + e);
        }
    }

    // ---- player ------------------------------------------------------------------------------

    static FishData data(Player p) { return Sky.profiles().member(p, ID, FishData.class); }

    static boolean ready(Player p) { return p != null && p.isOnline() && Sky.profiles().isReady(p); }

    static int level(Player p) {
        try { return Sky.service(SkillService.class).level(p, SkillType.FISHING); } catch (RuntimeException e) { return 0; }
    }

    static void addXp(Player p, double xp, String source) {
        double v = xp * FishDials.XP_MULT.get();
        if (v <= 0) return;
        Sky.service(SkillService.class).addXp(p, SkillType.FISHING, v, source);
    }

    /** A menus-module setting (PDC read path, CONTRACT menus §settings); missing = default. */
    static boolean setting(Player p, String key, boolean def) {
        try {
            String v = p.getPersistentDataContainer().get(new NamespacedKey("skyclone", "setting." + key), PersistentDataType.STRING);
            return v == null ? def : !"false".equalsIgnoreCase(v);
        } catch (RuntimeException e) { return def; }
    }

    // ---- where -------------------------------------------------------------------------------

    static String island(Location l) {
        try { return l == null || l.getWorld() == null ? null : Sky.worlds().islandOf(l.getWorld()); }
        catch (RuntimeException e) { return null; }
    }

    /** Lower-case ids + names of every zone containing the point (smallest first), for keyword matching. */
    static String zoneText(Location l) {
        String island = island(l);
        if (island == null) return "";
        Layout lay = Sky.worlds().layout(island);
        if (lay == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Layout.Zone z : lay.zones()) {
            if (z.box().contains(l.getX(), l.getY(), l.getZ()) || z.box().containsXZ(l.getX(), l.getZ()) && l.getY() >= z.box().minY() - 8 && l.getY() <= z.box().maxY() + 8) {
                sb.append(' ').append(z.id()).append(' ').append(z.displayName() == null ? "" : z.displayName());
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** Does the island's layout have any zone whose id or name contains one of these keywords? */
    static boolean islandHasZone(String island, String keywordsPipe) {
        Layout lay = island == null ? null : Sky.worlds().layout(island);
        if (lay == null) return false;
        for (Layout.Zone z : lay.zones()) {
            String t = (z.id() + " " + z.displayName()).toLowerCase(Locale.ROOT);
            for (String k : keywordsPipe.split("\\|")) if (!k.isBlank() && t.contains(k)) return true;
        }
        return false;
    }

    static boolean night() {
        try { return Sky.calendar().now().isNight(); } catch (RuntimeException e) { return false; }
    }

    // ---- events ------------------------------------------------------------------------------

    static boolean festival() {
        String mode = FishDials.FESTIVAL_MODE.get();
        if ("on".equalsIgnoreCase(mode)) return true;
        if ("off".equalsIgnoreCase(mode)) return false;
        // Foxy's Extra Event (fishing variant): mayors forces this on only while that perk's window runs.
        try { if (Sky.calendar().active(FESTIVAL_EXTRA_EVENT)) return true; } catch (RuntimeException ignored) { }
        boolean cal;
        try { cal = Sky.calendar().active(FESTIVAL_EVENT); } catch (RuntimeException e) { cal = false; }
        if (!cal) return false;
        if (!FishDials.FESTIVAL_REQUIRE_MAYOR.get()) return true;
        return Sky.service(Features.Mayors.class).perkActive(FishDials.FESTIVAL_PERK.get());
    }

    static boolean spooky() {
        for (String id : FishDials.SPOOKY_EVENTS.get().split(",")) {
            try { if (!id.isBlank() && Sky.calendar().active(id.trim())) return true; } catch (RuntimeException ignored) { }
        }
        return false;
    }

    static boolean jerrySeason() {
        try { return Sky.calendar().active(FishDials.JERRY_EVENT.get()); } catch (RuntimeException e) { return false; }
    }

    // ---- items -------------------------------------------------------------------------------

    static boolean isRod(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        String id = Sky.items().idOf(it);
        if (id == null) return false;
        ItemDef d = Sky.items().def(id);
        return d != null && d.category == com.skyclone.api.item.ItemCategory.FISHING_ROD;
    }

    static boolean isLavaRod(String rodId) {
        FishingCatalog.Rod r = rodId == null ? null : CAT.rods.get(rodId);
        if (r != null) return r.lava;
        ItemDef d = rodId == null ? null : Sky.items().def(rodId);
        return d != null && d.extra != null && d.extra.has("lava") && d.extra.get("lava").getAsBoolean();
    }

    /** An enchant's value (from the enchanting module's data) on an item, 0 when absent. */
    static double enchant(ItemData d, String enchantId) {
        if (d == null) return 0;
        int lvl = d.enchant(enchantId);
        if (lvl <= 0) return 0;
        EnchantDef e = Sky.items().enchant(enchantId);
        return e == null ? 0 : e.value(lvl);
    }

    static String part(ItemData d, String slot) {
        if (d == null || d.extra == null || !d.extra.has("rodParts")) return null;
        var o = d.extra.getAsJsonObject("rodParts");
        return o.has(slot) && !o.get(slot).isJsonNull() ? o.get(slot).getAsString() : null;
    }

    static List<FishingCatalog.Part> parts(ItemData d) {
        List<FishingCatalog.Part> out = new ArrayList<>();
        for (String slot : new String[]{"hook", "line", "sinker"}) {
            String id = part(d, slot);
            FishingCatalog.Part p = id == null ? null : CAT.parts.get(id);
            if (p != null) out.add(p);
        }
        return out;
    }

    static String name(String itemId) {
        ItemDef d = itemId == null ? null : Sky.items().def(itemId);
        if (d == null) return itemId == null ? "?" : com.skyclone.api.ui.Text.titleCase(itemId);
        return d.rarity.code() + d.name;
    }

    static String rarityOf(MobDef d) {
        if (d == null || d.extra == null || !d.extra.has("rarity")) return "COMMON";
        return d.extra.get("rarity").getAsString();
    }

    static String rarityColor(String rarity) {
        return Rarity.parse(rarity).code();
    }

    static String mobName(String mobId) {
        MobDef d = Sky.mobs().def(mobId);
        return d == null ? mobId : rarityColor(rarityOf(d)) + d.name;
    }

    static double fishingXpOf(MobDef d) {
        return d == null || d.extra == null || !d.extra.has("fishingXp") ? 0 : d.extra.get("fishingXp").getAsDouble();
    }

    static boolean isSeaCreature(MobDef d) { return d != null && d.has("sea_creature") && d.id != null && d.id.startsWith("sc_"); }
}
