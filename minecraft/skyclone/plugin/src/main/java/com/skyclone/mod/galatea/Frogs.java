package com.skyclone.mod.galatea;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Trophy Frogs on the Lotus Atoll: the roll on every catch, the ledger, titles and releasing frogs for Lotus. */
final class Frogs implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCatch(SkyEvents.FishCaught e) {
        Player p = e.player;
        if (!GalateaModule.D.frogs.get() || e.lava || !GalateaModule.ready(p) || !GalateaModule.on(p, GalateaModule.ATOLL)) return;
        GalateaData d = GalateaModule.data(p);
        String id = FrogMath.roll(context(p), ThreadLocalRandom.current(), GalateaModule.D.frogBase.get(),
                Sky.stats().get(p, Stat.TROPHY_FISH_CHANCE), d.sinceGold, d.sinceDiamond,
                GalateaModule.D.goldPity.get(), GalateaModule.D.diamondPity.get());
        if (id != null) award(p, id, "catch");
    }

    /** The Puddle Jumper sea creature carries its trophy frog (NEU: Bronze 73.4 / Silver 24.5 / Gold 2.0 / Diamond 0.2 %). */
    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null || !"sc_puddle_jumper".equals(e.def.id) || !GalateaModule.D.puddleJumperDrop.get()) return;
        if (!GalateaModule.ready(e.killer)) return;
        double r = ThreadLocalRandom.current().nextDouble();
        FrogMath.Tier t = r < 0.002 ? FrogMath.Tier.DIAMOND : r < 0.022 ? FrogMath.Tier.GOLD : r < 0.2665 ? FrogMath.Tier.SILVER : FrogMath.Tier.BRONZE;
        award(e.killer, "PUDDLE_JUMPER_" + t.name(), "puddle_jumper");
    }

    /** What the player is doing right now, as the frog rules see it. */
    static FrogMath.Context context(Player p) {
        FrogMath.Context c = new FrogMath.Context();
        c.onAtoll = GalateaModule.on(p, GalateaModule.ATOLL);
        c.raining = Sky.calendar().active(GalateaModule.RAIN);
        c.midair = !p.isOnGround() && !p.isInWater() && !p.isFlying() && !p.isGliding();
        c.flying = p.isFlying() || p.isGliding();
        c.underwater = p.getEyeLocation().getBlock().getType() == Material.WATER;
        Block below = p.getLocation().clone().subtract(0, 0.2, 0).getBlock();
        c.onLeaves = below.getType().name().endsWith("_LEAVES");
        Layout.Zone z = Sky.worlds().zoneOf(p);
        String zone = z == null ? "" : z.id();
        c.inCave = zone.equals("lotus_atoll_cave");
        c.onHighlands = zone.equals("lotus_atoll_highlands");
        GalateaData d = GalateaModule.data(p);
        c.blessed = d.blessingUntil > System.currentTimeMillis();
        ItemStack helmet = p.getInventory().getHelmet();
        String hid = helmet == null ? null : Sky.items().idOf(helmet);
        c.froggles = hid != null && hid.startsWith("FROGGLES");
        for (ItemStack a : p.getInventory().getArmorContents())
            if (a != null && "RED_SWEATER".equals(Sky.items().idOf(a))) c.redSweater = true;
        int pads = 0;
        Block feet = p.getLocation().getBlock();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 0; dy++)
            if (feet.getRelative(dx, dy, dz).getType() == Material.LILY_PAD) pads++;
        c.amongLilyPads = pads >= 4;
        return c;
    }

    /** Gives one trophy frog, logs it, moves the pity counters and announces titles. */
    static void award(Player p, String itemId, String why) {
        Object[] parsed = FrogMath.parse(itemId);
        if (parsed == null || !Sky.items().exists(itemId)) return;
        FrogMath.Species s = (FrogMath.Species) parsed[0];
        FrogMath.Tier t = (FrogMath.Tier) parsed[1];
        GalateaData d = GalateaModule.data(p);
        boolean first = d.frogs.getOrDefault(itemId, 0) == 0;
        d.frogs.merge(itemId, 1, Integer::sum);
        d.sinceGold = t.ordinal() >= FrogMath.Tier.GOLD.ordinal() ? 0 : d.sinceGold + 1;
        d.sinceDiamond = t == FrogMath.Tier.DIAMOND ? 0 : d.sinceDiamond + 1;
        Sky.loot().give(p, itemId, 1, LootSource.FISHING);
        ItemDef def = Sky.items().def(itemId);
        String name = def == null ? s.name() : def.rarity.code() + s.name() + " " + t.color + "&l" + t.label;
        Msg.send(p, "&2&lTROPHY FROG! &7You caught a " + name + "&7." + (first ? " &a&lNEW!" : ""));
        Msg.sound(p, t == FrogMath.Tier.BRONZE ? Sound.ENTITY_FROG_AMBIENT : Sound.ENTITY_FROG_LONG_JUMP, 1.2f);
        if (t == FrogMath.Tier.DIAMOND && GalateaModule.D.announce.get())
            Msg.broadcast("&2&lTROPHY FROG! &b" + p.getName() + " &7caught a " + name + "&7!");
        int title = FrogMath.title(d.frogs);
        if (title > d.title) {
            d.title = title;
            Msg.box(p, "&2&lFROG COLLECTOR " + Text.roman(title), List.of("&7You are now a &a" + FrogMath.TITLES[title] + "&7!",
                    "&7Professor Quennell has new stock for you."));
            Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
            Sky.profiles().saveNow(p);
        }
    }

    /**
     * Releases every frog of {@code itemId} in the inventory to the professor and pays its Lotus value,
     * in the largest denominations. Returns the Lotus units paid (0 = nothing released).
     */
    static long release(Player p, String itemId) {
        Object[] parsed = FrogMath.parse(itemId);
        if (parsed == null) return 0;
        int have = Sky.items().count(p, itemId);
        if (have <= 0) return 0;
        int removed = Sky.items().remove(p, itemId, have);
        if (removed <= 0) return 0;
        long lotus = FrogMath.lotusValue((FrogMath.Species) parsed[0], (FrogMath.Tier) parsed[1]) * removed;
        payLotus(p, lotus, "frogs: release " + itemId + " x" + removed);
        return lotus;
    }

    static void payLotus(Player p, long lotus, String why) {
        long[] dn = FrogMath.denominations(lotus);
        String[] ids = {"LOTUS_DIAMOND", "LOTUS_GOLD", "LOTUS_SILVER", "LOTUS"};
        for (int i = 0; i < 4; i++) {
            long n = dn[i];
            while (n > 0) {
                int stack = (int) Math.min(64, n);
                if (Sky.items().exists(ids[i])) Sky.loot().give(p, ids[i], stack, LootSource.REWARD);
                n -= stack;
            }
        }
        Msg.success(p, "Released! &7Paid &a" + Text.commasInt(lotus) + " Lotus &7worth" + summary(dn) + "&7.");
    }

    static String summary(long[] dn) {
        StringBuilder sb = new StringBuilder(" (");
        String[] names = {"&6Diamond", "&5Gold", "&9Silver", "&aLotus"};
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            if (dn[i] <= 0) continue;
            if (any) sb.append("&7, ");
            sb.append(names[i]).append(" &7x").append(dn[i]);
            any = true;
        }
        return any ? sb.append("&7)").toString() : "";
    }

    static int ledgerCount(Map<String, Integer> frogs) {
        int n = 0;
        for (FrogMath.Species s : FrogMath.SPECIES) for (FrogMath.Tier t : FrogMath.Tier.values()) if (frogs.getOrDefault(s.item(t), 0) > 0) n++;
        return n;
    }
}
