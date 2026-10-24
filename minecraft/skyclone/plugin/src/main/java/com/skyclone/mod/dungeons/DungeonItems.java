package com.skyclone.mod.dungeons;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.dungeons.classes.ClassSystem;
import com.skyclone.mod.dungeons.run.DungeonRun;
import com.skyclone.mod.dungeons.run.RunManager;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;

/**
 * The dungeon consumables (Soul Leap, Megablast TNT, Decoy, Inflatable Dummy, Revive Stone, Dungeon Potions I-VII) and
 * the Provisioner's shop (research 04 sec 3.1 "Ophelia": NEU OPHELIA_NPC prices; ours where marked TUNE).
 */
public final class DungeonItems {

    private DungeonItems() {}

    /** id, coins, input item (or null), amount. */
    public record Offer(String id, double coins, String input, int amount) {}

    public static final List<Offer> SHOP = List.of(
            new Offer("SUPERBOOM_TNT", 3_000, null, 1),            // NEU Ophelia: 3,000
            new Offer("SPIRIT_LEAP", 4_000, null, 1),              // TUNE (secret/bazaar item in the reference)
            new Offer("DUNGEON_DECOY", 1_500, null, 1),            // TUNE
            new Offer("INFLATABLE_JERRY", 500, null, 1),           // TUNE
            new Offer("POTION_DUNGEON_1", 5_000, null, 1), new Offer("POTION_DUNGEON_2", 10_000, null, 1),
            new Offer("POTION_DUNGEON_3", 15_000, null, 1), new Offer("POTION_DUNGEON_4", 20_000, null, 1),
            new Offer("POTION_DUNGEON_5", 25_000, null, 1), new Offer("POTION_DUNGEON_6", 30_000, null, 1),
            new Offer("POTION_DUNGEON_7", 35_000, null, 1),         // NEU Ophelia: 5k..35k
            new Offer("ARROW", 200, null, 20),                      // NEU Ophelia: 200 for 20
            // the upgrade lines (registered by the dungeon gear half; shown only when the items exist)
            new Offer("CLEAVER", 0, null, 0), new Offer("SUPER_CLEAVER", 80_000, "CLEAVER", 1),
            new Offer("HYPER_CLEAVER", 800_000, "SUPER_CLEAVER", 1), new Offer("GIANT_CLEAVER", 5_000_000, "HYPER_CLEAVER", 1),
            new Offer("UNDEAD_BOW", 80_000, null, 1), new Offer("SUPER_UNDEAD_BOW", 800_000, "UNDEAD_BOW", 1),
            new Offer("DEATH_BOW", 5_000_000, "SUPER_UNDEAD_BOW", 1),
            new Offer("DARK_GOGGLES", 80_000, null, 1), new Offer("SHADOW_GOGGLES", 800_000, "DARK_GOGGLES", 1),
            new Offer("WITHER_GOGGLES", 5_000_000, "SHADOW_GOGGLES", 1),
            new Offer("MENDER_HELMET", 80_000, null, 1), new Offer("MENDER_FEDORA", 800_000, "MENDER_HELMET", 1),
            new Offer("MENDER_CROWN", 5_000_000, "MENDER_FEDORA", 1),
            new Offer("STONE_CHESTPLATE", 80_000, null, 1), new Offer("METAL_CHESTPLATE", 800_000, "STONE_CHESTPLATE", 1),
            new Offer("STEEL_CHESTPLATE", 5_000_000, "METAL_CHESTPLATE", 1));

    static void init() {
        Sky.abilities().register("dungeons_spirit_leap", use -> leap(use.player()));
        Sky.abilities().register("dungeons_superboom", use -> superboom(use.player(), use.item()));
        Sky.abilities().register("dungeons_decoy", use -> decoy(use.player(), use.item(), 10));
        Sky.abilities().register("dungeons_dummy", use -> decoy(use.player(), use.item(), 5));
        Sky.abilities().register("dungeons_revive", use -> revive(use.player()));
        Sky.abilities().register("dungeons_potion", use -> potion(use.player(), use.item(), use.itemDef().id));
    }

    private static DungeonRun live(Player p) {
        DungeonRun r = RunManager.get().runOf(p);
        if (r == null || r.world == null || !p.getWorld().equals(r.world)
                || (r.state != DungeonRun.State.CLEARING && r.state != DungeonRun.State.BOSS)) {
            Msg.error(p, "This item only works inside the Catacombs!");
            return null;
        }
        if (r.isGhost(p)) { Msg.error(p, "Ghosts cannot use that."); return null; }
        return r;
    }

    private static void consume(ItemStack it) { if (it != null) it.setAmount(it.getAmount() - 1); }

    private static boolean leap(Player p) { return leap(p, true); }

    /** The Soul Leap menu; {@code consume} false for the Endless Soul Leap (part B). */
    public static boolean leap(Player p, boolean consume) {
        DungeonRun r = live(p);
        if (r == null) return false;
        List<Player> targets = new ArrayList<>();
        for (Player q : r.alive()) if (!q.equals(p)) targets.add(q);
        if (targets.isEmpty()) { Msg.error(p, "There is no living teammate to leap to."); return false; }
        Menu m = new Menu("Soul Leap", 3).chrome();
        int slot = 11;
        for (Player q : targets) {
            DungeonRun.Member qm = r.member(q);
            m.set(slot++, Icons.playerHead(q.getUniqueId(), "&b" + q.getName(), List.of(qm == null ? "" : qm.cls.colored(), "", "&eClick to leap!")), c -> {
                if (!q.isOnline() || r.isGhost(q) || (consume && Sky.items().count(p, "SPIRIT_LEAP") <= 0)) { p.closeInventory(); return; }
                if (consume) Sky.items().remove(p, "SPIRIT_LEAP", 1);
                p.closeInventory();
                p.teleportAsync(q.getLocation());
                p.setNoDamageTicks(20);
                Fx.particle(p.getLocation().add(0, 1, 0), Particle.PORTAL, 40, 0.5, 0.5);
                Fx.sound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                Msg.send(p, "&aYou leapt to &b" + q.getName() + "&a!");
            });
        }
        m.open(p);
        return true;
    }

    private static boolean superboom(Player p, ItemStack it) {
        DungeonRun r = live(p);
        if (r == null) return false;
        Location eye = p.getEyeLocation();
        RayTraceResult res = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), 6, FluidCollisionMode.NEVER, true);
        Location at = res == null ? eye.clone().add(eye.getDirection().multiply(4)) : res.getHitPosition().toLocation(p.getWorld());
        consume(it);
        r.explode(p, at, 3.0);
        return true;
    }

    private static boolean decoy(Player p, ItemStack it, int seconds) {
        DungeonRun r = live(p);
        if (r == null) return false;
        Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(2));
        ArmorStand st = p.getWorld().spawn(at, ArmorStand.class, a -> {
            a.setPersistent(false);
            a.setInvulnerable(true);
            a.setArms(true);
            a.setBasePlate(false);
            a.customName(Text.c("&eDecoy"));
            a.setCustomNameVisible(true);
            a.getEquipment().setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
        });
        r.addProp(st);
        consume(it);
        ClassSystem.distract(r, at, 10, seconds);
        org.bukkit.Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (st.isValid()) { Fx.particle(st.getLocation(), Particle.POOF, 20, 0.4, 0.02); st.remove(); } }, seconds * 20L);
        Msg.send(p, "&aThe decoy draws the monsters' attention!");
        return true;
    }

    private static boolean revive(Player p) {
        DungeonRun r = live(p);
        if (r == null) return false;
        List<DungeonRun.Member> dead = new ArrayList<>();
        for (DungeonRun.Member m : r.members.values()) if (m.ghost && m.player() != null) dead.add(m);
        if (dead.isEmpty()) { Msg.error(p, "Nobody needs reviving."); return false; }
        Menu menu = new Menu("Revive a Teammate", 3).chrome();
        int slot = 11;
        for (DungeonRun.Member m : dead) {
            menu.set(slot++, Icons.playerHead(m.id, "&b" + m.name, List.of("&7Ghost", "", "&eClick to revive!")), c -> {
                Player q = m.player();
                if (q == null || !m.ghost || Sky.items().count(p, "REVIVE_STONE") <= 0) { p.closeInventory(); return; }
                Sky.items().remove(p, "REVIVE_STONE", 1);
                p.closeInventory();
                r.revive(q, p.getLocation());
            });
        }
        menu.open(p);
        return true;
    }

    private static boolean potion(Player p, ItemStack it, String id) {
        DungeonRun r = live(p);
        if (r == null) return false;
        int lvl;
        try { lvl = Integer.parseInt(id.substring(id.lastIndexOf('_') + 1)); } catch (RuntimeException e) { return false; }
        DungeonRun.Member m = r.member(p);
        if (m == null) return false;
        if (m.potion >= lvl) { Msg.error(p, "You already drank a Dungeon Potion this strong."); return false; }
        m.potion = lvl;
        consume(it);
        Sky.stats().invalidate(p);
        Fx.sound(p, Sound.ENTITY_GENERIC_DRINK, 1f, 1f);
        Msg.success(p, "Dungeon Potion " + Text.roman(lvl) + " is active for the rest of the run.");
        return true;
    }

    // ---- the Provisioner's shop ------------------------------------------------------------------

    public static void shop(Player p) {
        Menu m = new Menu("Provisioner", 6).chrome();
        int slot = 10;
        for (Offer o : SHOP) {
            if (o.amount <= 0 || !Sky.items().exists(o.id) || (o.input != null && !Sky.items().exists(o.input))) continue;
            while (slot % 9 == 0 || slot % 9 == 8) slot++;
            if (slot > 43) break;
            ItemStack icon = Sky.items().create(o.id, Math.max(1, Math.min(64, o.amount)));
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("&7Cost");
            extra.add("&6" + Text.commas(o.coins) + " Coins");
            if (o.input != null) extra.add(Sky.items().def(o.input).rarity.code() + Text.strip(Sky.items().def(o.input).name));
            extra.add("");
            extra.add("&eClick to buy!");
            m.set(slot++, Icons.withLore(icon, extra), c -> buy(c.player(), o));
        }
        m.set(48, Icons.back("Catacombs"), c -> DungeonMenus.main(c.player()));
        m.open(p);
    }

    static void buy(Player p, Offer o) {
        if (o.input != null && Sky.items().count(p, o.input) < 1) {
            Msg.error(p, "You need " + Text.strip(Sky.items().def(o.input).name) + " to upgrade.");
            return;
        }
        if (!Sky.economy().withdraw(p, o.coins, "dungeons: provisioner " + o.id + " x" + o.amount)) {
            Msg.error(p, "You don't have enough coins!");
            return;
        }
        if (o.input != null && Sky.items().remove(p, o.input, 1) < 1) {
            Sky.economy().deposit(p, o.coins, "dungeons: provisioner refund " + o.id);
            Msg.error(p, "The trade-in item went missing; you were refunded.");
            return;
        }
        Sky.loot().give(p, o.id, o.amount, LootSource.NPC_SHOP);
        Msg.success(p, "You bought " + o.amount + "x " + Text.strip(Sky.items().def(o.id).name) + "!");
    }
}
