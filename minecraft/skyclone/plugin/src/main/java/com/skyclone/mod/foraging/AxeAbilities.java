package com.skyclone.mod.foraging;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Axe abilities and passives: the Throwing Axe, the Sculptor's Axe, travel scrolls, Park-only
 * Foraging Fortune, and Frenzy (Sweep that grows with Moonmarsh logs cut, kept on the axe).
 */
final class AxeAbilities {

    private final Chopper chopper;

    AxeAbilities(Chopper chopper) { this.chopper = chopper; }

    void register() {
        AbilityService a = Sky.abilities();
        a.register("foraging_throw_axe", this::throwAxe);
        a.register("foraging_sculpt", this::sculpt);
        a.register("foraging_read_scroll", this::readScroll);

        // Park-only Foraging Fortune from the held axe ("Park Regular"/"Park Devotee")
        Sky.stats().addProvider("foraging", (p, into) -> {
            if (!Sky.profiles().isReady(p)) return;
            ItemStack held = p.getInventory().getItemInMainHand();
            String id = Sky.items().idOf(held);
            if (id != null) {
                ItemDef def = Sky.items().def(id);
                if (def != null && def.extra != null && def.extra.has("parkFortune")
                        && Chopper.PARK.equals(Chopper.island(p.getWorld())))
                    into.add(Stat.FORAGING_FORTUNE, def.extra.get("parkFortune").getAsDouble());
            }
            Hotf.contribute(p, into);
        });

        // Frenzy: +1 Sweep per N Moonmarsh logs this axe has cut, capped
        Sky.items().addStatContributor("foraging_frenzy", (def, data, rarity, sheet) -> {
            int bonus = frenzyBonus(def, data);
            if (bonus > 0) sheet.add(Stat.SWEEP, bonus, ItemStatSheet.Source.OTHER);
        });

        Sky.items().addLoreContributor("foraging_counters", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, ItemData data) {
                List<String> out = new ArrayList<>();
                if (def == null || def.extra == null) return out;
                JsonObject ex = def.extra;
                if (ex.has("frenzyEvery")) {
                    int every = ex.get("frenzyEvery").getAsInt(), max = ex.get("frenzyMax").getAsInt();
                    long cut = data.extra.has("logsCut") ? data.extra.get("logsCut").getAsLong() : 0;
                    int bonus = frenzyBonus(def, data);
                    out.add("&7Current Bonus: &2+" + bonus + "∮ Sweep");
                    if (bonus < max) out.add("&7Next Upgrade: &a+" + (bonus + 1) + " &8(" + Text.commasInt(cut % every) + "/" + Text.commasInt(every) + ")");
                    out.add("&7Logs Cut: &a" + Text.commasInt(cut));
                }
                if (ex.has("growsAt")) {
                    int goal = ex.get("growsAt").getAsInt();
                    int c = data.extra.has("blocksBroken") ? data.extra.get("blocksBroken").getAsInt() : 0;
                    out.add("&7Logs Felled: &a" + Text.commasInt(c) + (c < goal ? "&8/" + goal : " &8(learned)"));
                }
                return out;
            }
        });
    }

    static int frenzyBonus(ItemDef def, ItemData data) {
        if (def == null || def.extra == null || !def.extra.has("frenzyEvery")) return 0;
        long cut = data.extra.has("logsCut") ? data.extra.get("logsCut").getAsLong() : 0;
        int every = Math.max(1, def.extra.get("frenzyEvery").getAsInt());
        return (int) Math.min(def.extra.get("frenzyMax").getAsInt(), cut / every);
    }

    // ================================================================================ throwing axe

    private boolean throwAxe(AbilityService.Use use) {
        Player p = use.player();
        if (HotfAbilities.justUsed(p)) return false;   // this sneak-click fired a Heart of the Forest axe ability
        String island = Chopper.island(p.getWorld());
        if (!Chopper.foragingIsland(island)) {
            Msg.error(p, "You can only throw your axe on a foraging island!");
            return false;
        }
        Block target = findLog(p, FDials.throwRange.get());
        if (target == null) {
            Msg.error(p, "There is no tree in range to throw your axe at.");
            return false;
        }
        if (!chopper.handles(p, target, Sky.worlds().zoneAt(target.getLocation()))) {
            Msg.error(p, "Your axe bounced off. That is not a tree you can fell.");
            return false;
        }
        Chopper.Tree tree = chopper.scan(target, Wood.ofBlock(target.getType().name(), Chopper.toughIsland(island)));
        animateThrow(p, use.item(), target, () -> {
            if (!p.isOnline() || target.getType().isAir()) return;
            chopper.chop(p, chopper.scan(target, tree.wood()), island, true);
            if (Hotf.ricochet(p)) {
                Block next = nearbyLog(target, tree, 10);
                if (next != null) {
                    Msg.send(p, "&aRicochet! &7Your axe bounced on to another tree.");
                    chopper.chop(p, chopper.scan(next, tree.wood()), island, true);
                }
            }
        });
        // Lumberjack talismans and the Drybark attribute: the axe "comes back" sooner (core sets the 1 s first)
        double k = Bonuses.throwCooldownFactor(p);
        if (k < 1) {
            double base = use.ability() == null ? 1 : Math.max(0, use.ability().cooldown);
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> Sky.abilities().setCooldown(p, "foraging_throw_axe", base * k));
        }
        return true;
    }

    /** A log of the same wood within {@code r} blocks that is not part of {@code tree}. */
    private static Block nearbyLog(Block from, Chopper.Tree tree, int r) {
        java.util.Set<Block> own = new java.util.HashSet<>(tree.logs());
        for (int d = 2; d <= r; d++)
            for (int dx = -d; dx <= d; dx++)
                for (int dz = -d; dz <= d; dz++)
                    for (int dy = -3; dy <= 3; dy++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                        Block b = from.getRelative(dx, dy, dz);
                        if (tree.wood().blocks.contains(b.getType().name()) && !own.contains(b)) return b;
                    }
        return null;
    }

    /** March along the look direction through air, plants and leaves until a log (or something solid). */
    static Block findLog(Player p, int range) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize().multiply(0.3);
        Location at = eye.clone();
        World w = p.getWorld();
        boolean marsh = Chopper.toughIsland(Chopper.island(w));
        for (int i = 0; i < range / 0.3; i++) {
            at.add(dir);
            Block b = w.getBlockAt(at);
            Material m = b.getType();
            if (Wood.ofBlock(m.name(), marsh) != null) return b;
            if (m.isAir() || !m.isSolid() || Wood.isLeaves(m.name())) continue;
            return null;
        }
        return null;
    }

    private void animateThrow(Player p, ItemStack axe, Block target, Runnable onHit) {
        Location from = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(0.8));
        Location to = target.getLocation().add(0.5, 0.5, 0.5);
        double dist = from.distance(to);
        int ticks = Math.max(3, Math.min(14, (int) Math.ceil(dist / 2.2)));
        ItemStack look = axe == null ? new ItemStack(Material.IRON_AXE) : axe.clone();
        ItemDisplay d = p.getWorld().spawn(from, ItemDisplay.class, e -> {
            e.setItemStack(look);
            e.setPersistent(false);
            e.setInterpolationDuration(1);
            e.setTeleportDuration(1);
        });
        p.playSound(from, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.4f);
        Vector step = to.toVector().subtract(from.toVector()).multiply(1.0 / ticks);
        new BukkitRunnable() {
            int t = 0;
            float spin = 0;
            @Override public void run() {
                t++;
                if (!d.isValid() || t > ticks) {
                    d.remove();
                    cancel();
                    to.getWorld().spawnParticle(Particle.SWEEP_ATTACK, to, 1);
                    onHit.run();
                    return;
                }
                Location l = from.clone().add(step.clone().multiply(t));
                d.teleport(l);
                spin += 1.1f;
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(new AxisAngle4f(spin, 0, 0, 1)),
                        new Vector3f(1, 1, 1), new Quaternionf()));
            }
        }.runTaskTimer(Sky.plugin(), 1L, 1L);
    }

    // ================================================================================ sculptor's axe

    private boolean sculpt(AbilityService.Use use) {
        Player p = use.player();
        Block b = p.getTargetBlockExact(5);
        if (b == null || !b.getType().name().endsWith("_LOG") || b.getType().name().startsWith("STRIPPED_")) {
            Msg.error(p, "Look at a log to carve it.");
            return false;
        }
        Features.PrivateIsland isl = Sky.service(Features.PrivateIsland.class);
        boolean own = Sky.has(Features.PrivateIsland.class) ? isl.canBuild(p, b.getLocation())
                : Chopper.PRIVATE.equals(Chopper.island(b.getWorld()));
        if (!own) {
            Msg.error(p, "You can only carve logs on your own island.");
            return false;
        }
        Material wood = Material.matchMaterial(b.getType().name().replace("_LOG", "_WOOD"));
        if (wood == null) return false;
        BlockData old = b.getBlockData();
        BlockData nd = Bukkit.createBlockData(wood);
        if (old instanceof Orientable o && nd instanceof Orientable n) n.setAxis(o.getAxis());
        b.setBlockData(nd, false);
        p.playSound(b.getLocation(), Sound.ITEM_AXE_STRIP, 1f, 1f);
        return true;
    }

    // ================================================================================ travel scrolls

    private boolean readScroll(AbilityService.Use use) {
        Player p = use.player();
        ItemDef def = use.itemDef();
        if (def == null || def.extra == null || !def.extra.has("warp")) return false;
        String warp = def.extra.get("warp").getAsString();
        ForagingData d = ForagingModule.data(p);
        if (d.scrolls.contains(warp)) {
            Msg.error(p, "You already know the way: /warp " + warp);
            return false;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!def.id.equals(Sky.items().idOf(hand))) return false;
        hand.setAmount(hand.getAmount() - 1);
        d.scrolls.add(warp);
        Sky.profiles().saveNow(p);
        Msg.box(p, "&a&lTRAVEL SCROLL", List.of("&7You can now warp straight to", "&a" + Text.strip(def.name.replace("Travel Scroll to ", "")) + "&7: &e/warp " + warp));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        return true;
    }
}
