package com.skyclone.mod.foraging;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Moonmarsh trees are SHARED objects (research 07 §4.4): everyone who cuts one has a share, and when
 * the last log falls every contributor with at least 10% gets a Tree Gift scaled by their share
 * (x1 / x1.5 / x2 at 10 / 20 / 33%) and by the tree's size (1-20x).
 */
final class MarshTrees {

    /** One tree being cut: identified by the regrowth record its first cut created. */
    static final class State {
        Wood wood;
        int total;
        int cut;
        final Map<UUID, Integer> by = new HashMap<>();
        /** The first log cut (where a gift creature appears). */
        String world;
        int x, y, z;
    }

    /** Guaranteed + rare contents per wood (research 07 §4.4; rare items TUNE where the research names hunting shards). */
    record Gift(int essence, double foragingXp, double hotfXp, int whispersMin, int whispersMax, boolean desert,
                String bonusItem, double bonusChance, String rareItem, double rareChance) {}

    static Gift giftOf(Wood w) {
        return switch (w) {
            case FIG -> new Gift(2, 100, 10, 20, 40, false, "TENDER_WOOD", 0.5, "STRETCHING_STICKS", 0.10);
            case MANGROVE -> new Gift(3, 500, 20, 20, 40, false, "VINESAP", 0.5, "DEEP_ROOT", 0.04);
            case HELIX -> new Gift(4, 1500, 30, 75, 150, true, null, 0, "FLEXBONE", 0.05);
            default -> null;
        };
    }

    private final TreeRegen regen;
    private final Map<TreeRegen.Rec, State> states = new IdentityHashMap<>();

    MarshTrees(TreeRegen regen) { this.regen = regen; }

    boolean mayChop(Player p, Chopper.Tree t) { return true; }   // access is the zone's job (Foraging XII / HotF IV)

    int active() { return states.size(); }

    void onChopped(Player p, Chopper.Tree t, int n) {
        Block first = t.logs().get(0);
        TreeRegen.Rec rec = regen.recFor(first);
        if (rec == null) return;
        State s = states.computeIfAbsent(rec, r -> {
            State x = new State();
            x.wood = t.wood();
            x.total = r.pos.size();
            x.world = first.getWorld().getName(); x.x = first.getX(); x.y = first.getY(); x.z = first.getZ();
            return x;
        });
        s.cut += n;
        s.by.merge(p.getUniqueId(), n, Integer::sum);
        states.keySet().removeIf(r -> { Block fb = firstBlock(r); return fb == null || regen.recFor(fb) != r; });   // forget trees that already regrew
        if (s.cut >= s.total) {
            states.remove(rec);
            regen.reschedule(rec, FDials.marshTreeRespawn.get());
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> gifts(s), 4L);
        }
    }

    /** Karma on the held axe: % chance a Tree Gift holds double loot (enchanting data, 1-5%). */
    static double karmaPercent(org.bukkit.inventory.ItemStack hand) {
        if (hand == null || hand.getType().isAir() || Sky.items().idOf(hand) == null) return 0;
        int lv = Sky.items().data(hand).enchant("karma");
        var def = Sky.items().enchant("karma");
        return lv > 0 && def != null ? BoosterMath.extrapolate(def.values, lv) : 0;   // Karma VI via Forest's Favor
    }

    private static Block firstBlock(TreeRegen.Rec r) {
        long[] q = r.pos.get(0);
        org.bukkit.World w = Bukkit.getWorld(r.world);
        return w == null ? null : w.getBlockAt((int) q[0], (int) q[1], (int) q[2]);
    }

    /** Size multiplier 1-20x from the tree's log count (TUNE: 25 logs per step). */
    static int sizeOf(int logs) { return Math.max(1, Math.min(20, Math.round(logs / 25f))); }

    private void gifts(State s) {
        Gift g = giftOf(s.wood);
        if (g == null) return;
        int size = sizeOf(s.total);
        for (Map.Entry<UUID, Integer> e : s.by.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !Sky.profiles().isReady(p)) continue;
            double share = (double) e.getValue() / Math.max(1, s.total);
            double mult = SweepMath.giftShare(share);
            if (mult <= 0) {
                Msg.send(p, "&7You helped fell a " + s.wood.display + " tree, but cut too little of it for a gift &8(" + Math.round(share * 100) + "%, 10% needed)&7.");
                continue;
            }
            org.bukkit.World w = s.world == null ? null : Bukkit.getWorld(s.world);
            give(p, s.wood, g, mult * size, share, w == null ? null : new org.bukkit.Location(w, s.x, s.y, s.z));
        }
    }

    void give(Player p, Wood wood, Gift g, double scale, double share) { give(p, wood, g, scale, share, null); }

    /** One player's Tree Gift; {@code at} is a log of the felled tree (where a creature appears), or null. */
    void give(Player p, Wood wood, Gift g, double scale, double share, org.bukkit.Location at) {
        ForagingData d = ForagingModule.data(p);
        Hotf.rollDay(d);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double luck = 1 + Hotf.level(d, "luck_of_the_forest") * 0.005;
        boolean early = d.giftsToday < 250;
        if (early) luck += Hotf.level(d, "gifts_250") * 0.01;
        if (Hotf.level(d, "center") >= 4) luck += 0.10;
        double k = scale * FDials.giftScale.get() * luck;
        List<String> body = new ArrayList<>();
        body.add("&7" + wood.display + " tree, your share &a" + Math.round(share * 100) + "% &8(x" + Text.commas(scale) + ")");
        // Karma (axe enchant): a chance for the gift to hold double loot
        double karma = karmaPercent(p.getInventory().getItemInMainHand());
        if (karma > 0 && rng.nextDouble() * 100 < karma) {
            k *= 2;
            body.add("&dKarma! &7This gift holds &adouble loot&7.");
        } else if (rng.nextDouble() * 100 < Bonuses.giftDoublePercent(p)) {   // Puck attribute (wave 5)
            k *= 2;
            body.add("&dForest Serendipity! &7This gift holds &adouble loot&7.");
        }
        body.add("");

        int essence = (int) Math.round(g.essence() * k * Bonuses.essenceFactor(p));
        if (rng.nextDouble() < Hotf.level(d, "essence_fortune") * 0.005) essence *= 2;
        d.forestEssence += essence;
        body.add("&d+" + Text.commasInt(essence) + " Forest Essence");

        double fxp = g.foragingXp() * k;
        Sky.service(SkillService.class).addXp(p, SkillType.FORAGING, fxp, "foraging:tree_gift");
        body.add("&3+" + Text.commasInt(fxp) + " Foraging Experience");

        long whispers = Math.round((g.whispersMin() + rng.nextInt(g.whispersMax() - g.whispersMin() + 1)) * k * FDials.whisperScale.get());
        if (early && Hotf.level(d, "gifts_250") > 0) whispers += 20;
        if (d.hotfUnlocked) {
            Hotf.addWhispers(p, g.desert() ? 0 : whispers, g.desert() ? whispers : 0, null);
            body.add((g.desert() ? "&e+" : "&b+") + Text.commasInt(whispers) + (g.desert() ? " Desert" : " Forest") + " Whispers");
            double hxp = g.hotfXp() * k;
            Hotf.addXp(p, hxp);
            body.add("&2+" + Text.commasInt(hxp) + " Heart of the Forest XP");
        }
        if (g.bonusItem() != null && Sky.items().exists(g.bonusItem())) {
            int n = 0;
            int rolls = Math.max(1, (int) Math.round(k));
            for (int i = 0; i < rolls; i++) if (rng.nextDouble() < g.bonusChance()) n++;
            if (n > 0) { Chopper.giveChunked(p, g.bonusItem(), n, LootSource.FORAGING); body.add("&a+" + n + " " + Text.strip(Sky.items().def(g.bonusItem()).name)); }
        }
        if (g.rareItem() != null && Sky.items().exists(g.rareItem()) && rng.nextDouble() < Math.min(1, g.rareChance() * luck * Bonuses.giftRareFactor(p, wood))) {
            Chopper.giveChunked(p, g.rareItem(), 1, LootSource.FORAGING);
            body.add("&6&lRARE! &f" + Sky.items().def(g.rareItem()).name);
        }
        extras(p, wood, luck, body);
        creature(p, wood, at, body);
        d.giftsToday++;
        d.treeGifts++;
        Contest.addGift(p, wood);
        Msg.box(p, "&2&lTREE GIFT!", body);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
    }

    // ================================================================================ wave 5: gift bonus drops + creatures

    /** The rare bonus rows (books, boosters, shards, runes, Tree the Fish...): each its own roll. */
    private static void extras(Player p, Wood wood, double luck, List<String> body) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double k = luck * FDials.giftExtraScale.get() * Bonuses.giftRareFactor(p, wood);
        for (GiftTables.Extra e : GiftTables.extras(wood)) {
            String id = null;
            for (String c : e.ids()) if (Sky.items().exists(c)) { id = c; break; }
            if (id == null || rng.nextDouble() >= Math.min(1, e.chance() * k)) continue;
            Chopper.giveChunked(p, id, 1, LootSource.FORAGING);
            body.add((e.chance() <= 0.002 ? "&d&lVERY RARE! &f" : "&6&lRARE! &f") + Sky.items().def(id).name);
            if (e.chance() <= 0.002) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        }
    }

    /** At most one creature (research odds x Tree Lurker x dial) appears by the tree, owned by this player. */
    private static void creature(Player p, Wood wood, org.bukkit.Location at, List<String> body) {
        if (!FDials.giftMonsters.get()) return;
        double factor = FDials.giftMonsterScale.get() * (1 + Bonuses.treeLurkerPercent(p) / 100.0);
        GiftTables.Creature c = GiftTables.pick(wood, factor, ThreadLocalRandom.current().nextDouble());
        if (c == null) return;
        String id = Sky.mobs().def(c.mob()) != null ? c.mob() : c.fallback() != null && Sky.mobs().def(c.fallback()) != null ? c.fallback() : null;
        if (id == null) return;
        org.bukkit.Location spot = at != null && at.getWorld() != null
                ? Hives.groundBeside(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ())
                : p.getLocation().clone().add(p.getLocation().getDirection().setY(0).normalize().multiply(3));
        org.bukkit.entity.LivingEntity mob = Sky.mobs().spawn(id, spot, p);
        if (mob == null) return;
        body.add("&c&lA creature burst out of the gift! &7" + Text.strip(Sky.mobs().def(id).name));
        p.playSound(spot, Sound.ENTITY_PHANTOM_AMBIENT, 1f, 1.3f);
        java.util.UUID mid = mob.getUniqueId();
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {   // it flies off after 3 minutes (TUNE)
            if (Bukkit.getEntity(mid) instanceof org.bukkit.entity.LivingEntity le && le.isValid()) Sky.mobs().remove(le);
        }, 20L * 180);
    }
}
