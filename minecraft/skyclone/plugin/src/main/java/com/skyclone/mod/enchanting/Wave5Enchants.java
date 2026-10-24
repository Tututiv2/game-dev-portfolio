package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Msg;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import java.util.concurrent.ThreadLocalRandom;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wave 5 enchants that had data but no effect, wired through hooks other modules already expose:
 * <ul>
 *   <li><b>Scuba</b> (armour, +1..6 Pressure Resistance): there is no Pressure Resistance {@code Stat}, so the total
 *       is published as requirement key {@code pressure_resistance:N} ("Requires 20 Pressure Resistance"). A diving
 *       zone (Torrhus Spring Depths) gates itself with it; {@code /sb enchanting pressure <player>} shows the sum.</li>
 *   <li><b>Green Thumb</b> (equipment, 0.05..0.25 Farming Fortune per unique visitor served): the garden exposes the
 *       count only as requirement {@code garden:unique:N}, so the count is read back by binary search (8 checks),
 *       once per 30 s per wearer, outside the stat provider.</li>
 *   <li><b>Bug Blender</b> (vacuum, +20..100 Farming Fortune when vacuuming pests): applies while the vacuum carrying
 *       it is in your hand - holding the vacuum is how pests are vacuumed.</li>
 * </ul>
 */
final class Wave5Enchants implements Listener {

    /**
     * <b>Paleontologist</b> (pickaxe/drill/gauntlet, +2.5..12.5%): "Raises the chance of finding Suspicious Scrap in
     * glacite shafts". Mining owns the scrap roll ({@code Finds}, island {@code mineshaft}) and does not read the
     * enchant yet, so this rolls the BONUS share on its own: base x pct/100 per block mined in a mineshaft, behind dial
     * {@code paleontologist-fallback} (request to mining in the hand-off: fold it into the roll, then flip the dial).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMine(SkyEvents.Gather e) {
        if (e.kind != LootSource.MINING || e.player == null || e.location == null || !on()) return;
        if (!EnchantingModule.dials().paleontologistFallback.get()) return;
        Player p = e.player;
        int lvl = Gear.level(Gear.held(p), "paleontologist");
        EnchantSpec s = lvl <= 0 ? null : EnchantingModule.reg().spec("paleontologist");
        if (s == null || !Sky.items().exists("SUSPICIOUS_SCRAP")) return;
        String island;
        try { island = Sky.worlds().islandOf(e.location.getWorld()); } catch (RuntimeException ex) { return; }
        if (island == null || !island.toLowerCase(java.util.Locale.ROOT).contains("mineshaft")) return;
        double c = Wave5Math.paleontologistChance(EnchantingModule.dials().paleontologistBase.get(), s.v(lvl));
        if (ThreadLocalRandom.current().nextDouble() >= c) return;
        Sky.loot().give(p, "SUSPICIOUS_SCRAP", 1, LootSource.MINING);
        ItemDef scrap = Sky.items().def("SUSPICIOUS_SCRAP");
        EnchantSpec me = EnchantingModule.reg().spec("paleontologist");
        Msg.send(p, "&9&lRARE FIND! &9" + (scrap == null || scrap.name == null ? "Scrap" : scrap.name)
                + " &8(" + (me == null ? "Paleontologist" : me.name) + ")");
    }

    /** player -> {unique visitors served, refresh after ms}. */
    private static final Map<UUID, long[]> SERVED = new HashMap<>();
    private static int task = -1;

    void register() {
        Sky.stats().addProvider("enchanting_wave5", Wave5Enchants::stats);
        Sky.requirements().register("pressure_resistance", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                return p != null && pressureResistance(p) + 1e-9 >= Wave5Math.threshold(parts);
            }
            @Override public String describe(String[] parts) {
                double t = Wave5Math.threshold(parts);
                return "&9" + (t == Double.MAX_VALUE ? "?" : EnchantSpec.fmt(t)) + " Pressure Resistance";
            }
        });
        task = Bukkit.getScheduler().scheduleSyncRepeatingTask(Sky.plugin(), Wave5Enchants::sweep, 40L, 100L);
    }

    static void shutdown() {
        if (task != -1) Bukkit.getScheduler().cancelTask(task);
        task = -1;
        SERVED.clear();
    }

    private static boolean on() { return EnchantingModule.dials().effects.get(); }

    /** Pressure Resistance from enchants: Scuba on every worn piece. */
    static double pressureResistance(Player p) {
        if (!on()) return 0;
        return Gear.wornSum(p, "scuba");
    }

    private static void stats(Player p, StatMap m) {
        if (!on()) return;
        double gt = Gear.wornSum(p, "green_thumb");
        if (gt > 0) {
            long[] c = SERVED.get(p.getUniqueId());
            if (c != null && c[0] > 0) m.add(Stat.FARMING_FORTUNE, gt * c[0]);
        }
        ItemStack held = Gear.held(p);
        int bb = Gear.level(held, "bug_blender");
        if (bb > 0) {
            ItemDef d = Gear.def(held);
            EnchantSpec s = EnchantingModule.reg().spec("bug_blender");
            if (s != null && d != null && d.category == ItemCategory.VACUUM) m.add(Stat.FARMING_FORTUNE, s.v(bb));
        }
    }

    /** Unique garden visitors served, read back from the garden's requirement key (0 without a garden module). */
    static int uniqueServed(Player p) {
        Requirements r = Sky.requirements();
        if (!r.known("garden")) return 0;
        int cap = Math.max(1, EnchantingModule.dials().greenThumbVisitorCap.get());
        return Wave5Math.largestTrue(n -> r.check(p, "garden:unique:" + n), cap);
    }

    private static void sweep() {
        if (!on()) { SERVED.clear(); return; }
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!EnchantingModule.ready(p)) continue;
            UUID u = p.getUniqueId();
            if (Gear.wornSum(p, "green_thumb") <= 0) { if (SERVED.remove(u) != null) Sky.stats().invalidate(p); continue; }
            long[] c = SERVED.get(u);
            if (c != null && c[1] > now) continue;
            int n;
            try { n = uniqueServed(p); } catch (RuntimeException e) { n = 0; }
            boolean changed = c == null || c[0] != n;
            SERVED.put(u, new long[]{n, now + 30_000});
            if (changed) Sky.stats().invalidate(p);
        }
        SERVED.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
    }

    /** For the admin command. */
    static String describe(Player p) {
        long[] c = SERVED.get(p.getUniqueId());
        return "&7Pressure Resistance (Scuba): &9" + EnchantSpec.fmt(pressureResistance(p))
                + " &8| &7Green Thumb visitors: &a" + (c == null ? "-" : Text.commas(c[0]))
                + " &8| &7Stealth: &a" + Gear.wornMax(p, "stealth");
    }
}
