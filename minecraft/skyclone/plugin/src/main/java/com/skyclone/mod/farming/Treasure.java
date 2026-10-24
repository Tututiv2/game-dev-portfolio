package com.skyclone.mod.farming;

import com.skyclone.api.Sky;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.WorldService;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Digger Nim's buried treasure at the Treasure Hunter Camp (research 03 6.1/6.4: "sells hints (coins) to buried
 * treasure chests"). A hint costs coins and marks one patch of ground in the camp - only its buyer sees it, as
 * suspicious sand with dust rising off it. Dig it up for a FIXED payout (more than the hint cost) and some Farming XP.
 * Nothing is random about the reward on purpose: the owner's rule is no gambling, so this is a short errand that pays
 * a known amount on a cooldown, never a lottery ticket.
 */
final class Treasure implements WorldService.BreakHandler {

    static final String ISLAND = "mushroom_desert";
    static final String ZONE = "desert.camp";
    private static final Set<Material> GROUND = Set.of(Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.COARSE_DIRT,
            Material.DIRT, Material.ROOTED_DIRT, Material.PODZOL, Material.GRASS_BLOCK, Material.SUSPICIOUS_SAND,
            Material.SUSPICIOUS_GRAVEL, Material.PACKED_MUD, Material.MUD);

    private final FarmingModule m;

    Treasure(FarmingModule m) { this.m = m; }

    void start() { Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 50L, 40L); }

    // ---- NPC + menu ---------------------------------------------------------------------------------

    void talk(Player p) {
        FarmData d = m.data(p);
        if (d.treasuresFound == 0 && d.treasure == null) {
            Sky.npcs().talk(p, "farming_treasure_hunter", List.of(
                    "&fThese bones are older than the island, I swear it.",
                    "&fPeople buried all sorts round this camp. I know roughly where.",
                    "&fPay me for a hint and the spot's yours to dig."), () -> open(p));
        } else open(p);
    }

    void open(Player p) {
        FarmData d = m.data(p);
        long now = System.currentTimeMillis();
        Menu menu = new Menu("Digger Nim", 4).chrome();
        int cost = m.dials.treasureCost.get(), reward = m.dials.treasureReward.get();
        if (d.treasure != null) {
            FarmData.TreasureHint h = d.treasure;
            menu.set(13, Icons.of(Material.SUSPICIOUS_SAND, "&6Your Treasure Hint", List.of(
                    "&7Somewhere in the &6Treasure Hunter Camp&7.",
                    "&7The spot shows as &esuspicious sand",
                    "&7with dust rising when you are close.", "",
                    "&7Pays: &6" + Text.commasInt(reward) + " coins &7+ &3" + m.dials.treasureXp.get() + " Farming XP",
                    "&7Time left: &e" + Text.duration(Math.max(0, h.expires - now) / 1000), "",
                    "&eClick for directions!")), c -> directions(c.player()));
        } else {
            long left = Math.max(0, d.treasureCooldownUntil - now);
            List<String> l = new java.util.ArrayList<>(List.of(
                    "&7Nim knows where something is buried.", "",
                    "&7Hint cost: &6" + Text.commasInt(cost) + " coins",
                    "&7Treasure pays: &6" + Text.commasInt(reward) + " coins &7+ &3" + m.dials.treasureXp.get() + " Farming XP",
                    "&8Always the same: no luck involved.", ""));
            if (left > 0) l.add("&cNim is still catching his breath: &e" + Text.duration(left / 1000 + 1));
            else l.add(Sky.economy().purse(p) >= cost ? "&eClick to buy a hint!" : "&cYou can't afford this!");
            menu.set(13, Icons.of(Material.BRUSH, "&aBuy a Treasure Hint", l), c -> buy(c.player()));
        }
        menu.set(11, Icons.of(Material.CHEST, "&aTreasures Found", "&7Dug up: &e" + d.treasuresFound));
        menu.set(15, Icons.of(Material.MAP, "&aWhere is the camp?", "&7West of the Desert Settlement,", "&7past the tents and the dig site."));
        menu.set(30, Icons.back("Farming"), c -> m.menus.openMain(c.player()));
        menu.open(p);
    }

    void buy(Player p) {
        FarmData d = m.data(p);
        long now = System.currentTimeMillis();
        if (d.treasure != null) { Msg.error(p, "You already have a treasure to dig up!"); return; }
        if (now < d.treasureCooldownUntil) { Msg.error(p, "Nim is still catching his breath."); return; }
        int[] spot = pickSpot();
        if (spot == null) { Msg.error(p, "The camp is not ready yet. Try again in a moment."); return; }
        int cost = Math.max(0, m.dials.treasureCost.get());
        if (cost > 0 && !Sky.economy().withdraw(p, cost, "farming: treasure hint")) { Msg.error(p, "You can't afford this!"); return; }
        give(p, spot);
        Msg.npc(p, "&fDigger Nim", List.of("&fDig where the sand looks disturbed, over in the camp. Don't tell the others."), null);
        Msg.sound(p, Sound.ITEM_BRUSH_BRUSHING_SAND, 1f);
        directions(p);
        p.closeInventory();
    }

    /** Admin / buy: set a hint at a block. */
    void give(Player p, int[] spot) {
        FarmData.TreasureHint h = new FarmData.TreasureHint();
        h.x = spot[0]; h.y = spot[1]; h.z = spot[2];
        h.expires = System.currentTimeMillis() + Math.max(1, m.dials.treasureMinutes.get()) * 60_000L;
        m.data(p).treasure = h;
        show(p, h);
    }

    void clear(Player p) {
        FarmData d = m.data(p);
        if (d.treasure != null) unshow(p, d.treasure);
        d.treasure = null;
    }

    private void directions(Player p) {
        FarmData.TreasureHint h = m.data(p).treasure;
        if (h == null) return;
        if (!ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) {
            Msg.send(p, "&eYour treasure is buried at the &6Treasure Hunter Camp &ein the Mushroom Desert.");
            return;
        }
        double dx = h.x + 0.5 - p.getLocation().getX(), dz = h.z + 0.5 - p.getLocation().getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        Msg.send(p, "&eYour treasure is &a" + dist + " blocks &e" + Trapper.compass(dx, dz) + "&e of you.");
    }

    /** A random diggable ground block with air above, inside the camp zone (fallback: around Nim). */
    int[] pickSpot() {
        World w = Sky.worlds().world(ISLAND);
        Layout l = Sky.worlds().layout(ISLAND);
        if (w == null || l == null || !Sky.worlds().isBuilt(ISLAND)) return null;
        Box box = null;
        for (Layout.Zone z : l.zones()) if (ZONE.equals(z.id())) { box = z.box(); break; }
        if (box == null) {
            Layout.Anchor a = l.anchor("npc.treasure_hunter");
            if (a == null) return null;
            box = new Box((int) a.x() - 20, (int) a.y() - 8, (int) a.z() - 20, (int) a.x() + 20, (int) a.y() + 12, (int) a.z() + 20);
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 60; attempt++) {
            int x = box.minX() + 2 + r.nextInt(Math.max(1, box.maxX() - box.minX() - 3));
            int z = box.minZ() + 2 + r.nextInt(Math.max(1, box.maxZ() - box.minZ() - 3));
            for (int y = box.maxY(); y >= box.minY(); y--) {
                Block b = w.getBlockAt(x, y, z);
                if (b.getType().isAir()) continue;
                if (GROUND.contains(b.getType()) && b.getRelative(0, 1, 0).getType().isAir() && b.getRelative(0, 2, 0).getType().isAir())
                    return new int[]{x, y, z};
                break;
            }
        }
        return null;
    }

    private static void show(Player p, FarmData.TreasureHint h) {
        World w = Sky.worlds().world(ISLAND);
        if (w == null || !p.getWorld().equals(w)) return;
        p.sendBlockChange(new Location(w, h.x, h.y, h.z), Material.SUSPICIOUS_SAND.createBlockData());
    }

    private static void unshow(Player p, FarmData.TreasureHint h) {
        World w = Sky.worlds().world(ISLAND);
        if (w == null || !p.getWorld().equals(w)) return;
        Block b = w.getBlockAt(h.x, h.y, h.z);
        p.sendBlockChange(b.getLocation(), b.getBlockData());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            FarmData d = m.data(p);
            FarmData.TreasureHint h = d.treasure;
            if (h == null) continue;
            if (now > h.expires) {
                unshow(p, h);
                d.treasure = null;
                Msg.send(p, "&cSomeone else dug up your treasure first! &7Ask Digger Nim for another hint.");
                continue;
            }
            if (!ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) continue;
            Location at = new Location(p.getWorld(), h.x + 0.5, h.y + 1.1, h.z + 0.5);
            if (at.distanceSquared(p.getLocation()) > 48 * 48) continue;
            show(p, h);                                         // the client forgets fake blocks when a chunk reloads
            p.spawnParticle(Particle.FALLING_DUST, at, 6, 0.3, 0.2, 0.3, 0, Material.SAND.createBlockData());
        }
    }

    // ---- digging -----------------------------------------------------------------------------------

    @Override public int priority() { return 90; }

    @Override
    public boolean handles(Player p, Block block, Layout.Zone zone) {
        if (!Sky.profiles().isReady(p) || !ISLAND.equals(Sky.worlds().islandOf(block.getWorld()))) return false;
        FarmData.TreasureHint h = m.data(p).treasure;
        return h != null && h.x == block.getX() && h.y == block.getY() && h.z == block.getZ();
    }

    @Override
    public void onBreak(Player p, Block block, BlockBreakEvent event) {
        FarmData d = m.data(p);
        if (d.treasure == null) return;
        d.treasure = null;                                      // first: a second break event can never pay twice
        d.treasuresFound++;
        d.treasureCooldownUntil = System.currentTimeMillis() + Math.max(0, m.dials.treasureCooldown.get()) * 1000L;
        p.sendBlockChange(block.getLocation(), block.getBlockData());
        int reward = Math.max(0, m.dials.treasureReward.get());
        if (reward > 0) Sky.economy().deposit(p, reward, "farming: buried treasure");
        int xp = Math.max(0, m.dials.treasureXp.get());
        if (xp > 0) Sky.service(SkillService.class).addXp(p, SkillType.FARMING, xp, "farming:treasure");
        Sky.profiles().saveNow(p);
        Location c = block.getLocation().add(0.5, 1, 0.5);
        p.spawnParticle(Particle.HAPPY_VILLAGER, c, 20, 0.5, 0.4, 0.5, 0);
        Msg.sound(p, Sound.BLOCK_CHEST_OPEN, 1f);
        Msg.box(p, "&6&lTREASURE FOUND!", List.of("&7You dug up a buried strongbox.", "&6+" + Text.commasInt(reward) + " coins   &3+" + xp + " Farming XP",
                "&7Treasures found: &e" + d.treasuresFound));
    }
}
