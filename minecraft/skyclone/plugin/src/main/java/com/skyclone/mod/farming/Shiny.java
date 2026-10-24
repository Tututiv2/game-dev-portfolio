package com.skyclone.mod.farming;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Shiny Pigs (wave 5). Breaking a grown crop on a farming island (or in the Garden) can make a glowing Shiny Pig appear
 * next to a floating Shiny Orb. It runs from you (circling its orb, never off across the island); right-click it to
 * catch it, walk it back to the orb and the orb pays out: coins (Pig pet Hamfisted and the Shiny Rod raise them),
 * sometimes Harvesting VI (its only source in the reference) or Potato Spreading, and a Pig Brain Mixin can roll the
 * items twice. Delivering it counts a Shiny Pig in the Bestiary, which is what the Pig pet's Shining Stampede pays
 * Potato Fortune for. One pig per player at a time, then a cooldown; nothing is saved - a pig that gets away is gone.
 */
final class Shiny implements Listener {

    static final String MOB = "shiny_pig", FAMILY = "shiny_pig", ORB = "SHINY_ORB", ROD = "SHINY_ROD";
    static final String BEHAVIOUR = "farming_shiny_pig";
    static final String HARVESTING = "ENCHANTMENT_HARVESTING_6", SPREADING = "POTATO_SPREADING", TOKEN = "SHINY_SHARD";

    private final FarmingModule m;
    /** player uuid -> their pig. */
    private final Map<UUID, Hunt> hunts = new HashMap<>();
    /** pig uuid -> player uuid. */
    private final Map<UUID, UUID> owners = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    static final class Hunt {
        final UUID player;
        final UUID pig;
        final UUID orb;
        final Location orbAt;
        final long expires;
        boolean caught;

        Hunt(UUID player, UUID pig, UUID orb, Location orbAt, long expires) {
            this.player = player; this.pig = pig; this.orb = orb; this.orbAt = orbAt; this.expires = expires;
        }
    }

    Shiny(FarmingModule m) { this.m = m; }

    void start() {
        // never fought: every hit is cancelled (core still fires the hit through the behaviour), a hint instead
        Sky.mobs().registerBehaviour(BEHAVIOUR, new MobBehaviour() {
            @Override public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
                ctx.cancelled = true;
                if (ctx.attacker instanceof Player pl) Sky.vitals().flash(pl, "&dRight-click the Shiny Pig to catch it!", 30);
            }
        });
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 20L, 5L);
    }

    void stop() {
        for (Hunt h : new ArrayList<>(hunts.values())) despawn(h);
        hunts.clear();
        owners.clear();
    }

    int active() { return hunts.size(); }

    Hunt hunt(Player p) { return hunts.get(p.getUniqueId()); }

    // =================================================================================================
    // appearing
    // =================================================================================================

    /** A grown crop was broken at {@code at}: maybe a Shiny Pig appears. Cheapest test first (it runs on every break). */
    void onHarvest(Player p, Location at) {
        if (at == null || at.getWorld() == null || !m.dials.shinyEnabled.get()) return;
        double ch = m.dials.shinyChance.get();
        if (!(ch > 0) || ThreadLocalRandom.current().nextDouble() >= ch) return;
        if (hunts.containsKey(p.getUniqueId())) return;
        Long cd = cooldownUntil.get(p.getUniqueId());
        if (cd != null && cd > System.currentTimeMillis()) return;
        String island = Sky.worlds().islandOf(at.getWorld());
        if (island == null || !m.dials.shinyIslandSet().contains(island)) return;
        spawn(p, at);
    }

    /** Make a Shiny Pig appear for {@code p} at {@code at}. Returns null, or why not (admin command). */
    String spawn(Player p, Location at) {
        if (hunts.containsKey(p.getUniqueId())) return p.getName() + " already has a Shiny Pig";
        if (Sky.mobs().def(MOB) == null) return "mob " + MOB + " is not registered";
        World w = at.getWorld();
        if (w == null) return "no world";
        Location spot = standable(at);
        LivingEntity pig = Sky.mobs().spawn(MOB, spot, p);
        if (pig == null) return "the pig could not spawn there";
        try { pig.setGlowing(true); } catch (RuntimeException ignored) { }
        UUID orbId = null;
        try {
            ItemStack icon = Sky.items().exists(ORB) ? Sky.items().create(ORB) : new ItemStack(Material.HEART_OF_THE_SEA);
            ItemDisplay orb = w.spawn(spot.clone().add(0, 1.1, 0), ItemDisplay.class, d -> {
                d.setItemStack(icon);
                d.setPersistent(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setGlowing(true);
                d.setGlowColorOverride(Color.FUCHSIA);
            });
            orbId = orb.getUniqueId();
        } catch (RuntimeException e) {
            Sky.log().warning("[farming] could not place a Shiny Orb: " + e);
        }
        long until = System.currentTimeMillis() + Math.max(5, m.dials.shinySeconds.get()) * 1000L;
        Hunt h = new Hunt(p.getUniqueId(), pig.getUniqueId(), orbId, spot.clone(), until);
        hunts.put(p.getUniqueId(), h);
        owners.put(pig.getUniqueId(), p.getUniqueId());
        Msg.send(p, "&d&lSHINY PIG! &7A &dShiny Pig &7appeared by its &dShiny Orb&7!");
        Msg.send(p, "&7Right-click it to catch it, then bring it back to the orb within &e" + m.dials.shinySeconds.get() + "s&7.");
        Msg.sound(p, Sound.ENTITY_PIG_AMBIENT, 1.6f);
        Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2f);
        return null;
    }

    /** The crop's own block if a pig fits there, else up to 3 blocks higher. */
    private static Location standable(Location at) {
        Block b = at.getBlock();
        for (int i = 0; i < 3 && (b.getType().isSolid() || b.getRelative(BlockFace.UP).getType().isSolid()); i++) b = b.getRelative(BlockFace.UP);
        return b.getLocation().add(0.5, 0, 0.5);
    }

    // =================================================================================================
    // the chase
    // =================================================================================================

    private void tick() {
        if (hunts.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<Hunt> it = hunts.values().iterator(); it.hasNext(); ) {
            Hunt h = it.next();
            Player p = Bukkit.getPlayer(h.player);
            LivingEntity pig = living(h.pig);
            String why = null;
            if (p == null || !p.isOnline()) why = "quit";
            else if (pig == null) why = "lost";
            else if (!pig.getWorld().equals(p.getWorld()) || !pig.getWorld().equals(h.orbAt.getWorld())) why = "left";
            else if (now > h.expires) why = "time";
            if (why != null) {
                it.remove();
                escaped(h, p, why);
                continue;
            }
            Location loc = pig.getLocation(), pl = p.getLocation();
            if (h.caught) {
                if (ShinyMath.delivered(loc.getX() - h.orbAt.getX(), loc.getY() - h.orbAt.getY(), loc.getZ() - h.orbAt.getZ(),
                        Math.max(0.5, m.dials.shinyOrbRadius.get()))) {
                    it.remove();
                    deliver(h, p, pig);
                    continue;
                }
                double d2 = loc.distanceSquared(pl);
                if (d2 > 16 * 16) pig.teleport(pl);                         // the player outran / flew off: it keeps up
                else if (d2 > 2.5 * 2.5 && pig instanceof Mob mob) mob.getPathfinder().moveTo(pl, 1.6);
                lead(loc, pl);
            } else {
                double d2 = loc.distanceSquared(pl);
                if (d2 < 10 * 10 && pig instanceof Mob mob) {
                    double[] t = ShinyMath.fleeTarget(loc.getX(), loc.getZ(), pl.getX(), pl.getZ(), h.orbAt.getX(), h.orbAt.getZ(),
                            6, Math.max(4, m.dials.shinyRoam.get()));
                    double speed = 1.15 * (holdsRod(p) ? 1 + Math.max(0, m.dials.shinyRodFlee.get()) / 100.0 : 1);
                    mob.getPathfinder().moveTo(new Location(loc.getWorld(), t[0], loc.getY(), t[1]), speed);
                }
                loc.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0, 0.9, 0), 1, 0.3, 0.3, 0.3, 0);
            }
            h.orbAt.getWorld().spawnParticle(Particle.WITCH, h.orbAt.clone().add(0, 1.1, 0), 2, 0.25, 0.25, 0.25, 0);
            long secs = Math.max(0, (h.expires - now) / 1000);
            Sky.vitals().flash(p, h.caught ? "&dShiny Pig caught &7- bring it to the orb &8(&e" + secs + "s&8)"
                    : "&dShiny Pig on the loose! &7Right-click to catch &8(&e" + secs + "s&8)", 8);
        }
    }

    private static void lead(Location pig, Location player) {
        World w = pig.getWorld();
        double dx = player.getX() - pig.getX(), dy = player.getY() + 1 - (pig.getY() + 0.6), dz = player.getZ() - pig.getZ();
        for (int i = 1; i <= 4; i++) {
            double f = i / 5.0;
            w.spawnParticle(Particle.END_ROD, pig.getX() + dx * f, pig.getY() + 0.6 + dy * f, pig.getZ() + dz * f, 1, 0, 0, 0, 0);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEntityEvent e) {
        Entity clicked = e.getRightClicked();
        UUID owner = owners.get(clicked.getUniqueId());
        if (owner == null) return;
        e.setCancelled(true);                                   // no saddles, leads or vanilla riding on a Shiny Pig
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        try { Sky.abilities().consumeClick(p); } catch (RuntimeException ignored) { }
        if (!p.getUniqueId().equals(owner)) {
            Sky.vitals().flash(p, "&cThat Shiny Pig is someone else's!", 30);
            return;
        }
        Hunt h = hunts.get(owner);
        if (h == null || h.caught) return;
        h.caught = true;
        if (clicked instanceof Mob mob) mob.getPathfinder().stopPathfinding();
        Msg.send(p, "&d&lCAUGHT! &7The Shiny Pig follows you now - take it back to its &dShiny Orb&7.");
        Msg.sound(p, Sound.ITEM_LEAD_TIED, 1.2f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Hunt h = hunts.remove(e.getPlayer().getUniqueId());
        if (h != null) despawn(h);
    }

    // =================================================================================================
    // endings
    // =================================================================================================

    private void escaped(Hunt h, Player p, String why) {
        despawn(h);
        cooldown(h.player);
        if (p == null || !p.isOnline() || "quit".equals(why)) return;
        if (Sky.profiles().isReady(p)) m.data(p).shinyPigsEscaped++;
        Msg.send(p, "&cThe Shiny Pig got away!" + ("time".equals(why) ? " &7(out of time)" : ""));
        Msg.sound(p, Sound.ENTITY_PIG_HURT, 0.8f);
    }

    private void deliver(Hunt h, Player p, LivingEntity pig) {
        owners.remove(h.pig);
        cooldown(h.player);
        removeOrb(h);
        if (!Sky.profiles().isReady(p)) { Sky.mobs().remove(pig); return; }
        FarmData d = m.data(p);
        int lvl = Sky.has(SkillService.class) ? Sky.service(SkillService.class).level(p, SkillType.FARMING) : 0;
        double ham = Perks.petAbility(p, "pig_hamfisted", "a");
        boolean rod = hasRod(p);
        long coins = ShinyMath.coins(m.dials.shinyCoins.get(), m.dials.shinyCoinsPerLevel.get(), lvl, ham,
                rod ? m.dials.shinyRodCoins.get() : 0);
        if (coins > 0) Sky.economy().deposit(p, coins, "farming:shiny_pig");
        List<String> items = rollItems();
        boolean mixin = mixinActive(p) && ThreadLocalRandom.current().nextDouble() < m.dials.shinyMixinChance.get();
        if (mixin) items.addAll(rollItems());
        d.shinyPigsCaught++;
        d.shinyCoins += coins;
        // the delivery is the "kill": core fires MobKill (owner = killer), so the Bestiary counts a Shiny Pig
        Sky.mobs().setHealth(pig, 0);

        Msg.send(p, "&d&lSHINY PIG DELIVERED! &7The orb bursts open:");
        StringBuilder why = new StringBuilder();
        if (ham > 0) why.append("Ham Fist +").append(Text.commasInt(ham)).append("%");
        if (rod) why.append(why.length() > 0 ? ", " : "").append("Shiny Rod +").append(Text.commasInt(m.dials.shinyRodCoins.get())).append("%");
        Msg.send(p, "&7  &6+" + Text.commasInt(coins) + " coins" + (why.length() > 0 ? " &8(" + why + ")" : ""));
        for (String id : items) {
            Harvest.give(p, id, 1, LootSource.FARMING);
            ItemDef def = Sky.items().def(id);
            if (def != null) Msg.rareDrop(p, "&d&lSHINY DROP!", itemName(id), 0);
        }
        if (mixin && !items.isEmpty()) Msg.send(p, "&7  &9Pig Brain Mixin &7rolled the orb twice!");
        long kills = Perks.shinyKills(p);
        Msg.send(p, "&7  Shiny Pigs delivered: &d" + Text.commasInt(d.shinyPigsCaught) + " &8(bestiary tier "
                + ShinyMath.tier(kills, m.tables.shinyPigTiers) + "/" + m.tables.shinyPigTiers.length + ")");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
        h.orbAt.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, h.orbAt.clone().add(0, 1, 0), 30, 0.6, 0.6, 0.6, 0);
    }

    /** The orb's item roll: Harvesting VI, Potato Spreading, (Year of the Pig) Shiny Token - each only if registered. */
    List<String> rollItems() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<String> out = new ArrayList<>();
        if (Sky.items().exists(HARVESTING) && r.nextDouble() < m.dials.shinyHarvestingChance.get()) out.add(HARVESTING);
        if (Sky.items().exists(SPREADING) && r.nextDouble() < m.dials.shinySpreadingChance.get()) out.add(SPREADING);
        if (Sky.items().exists(TOKEN) && r.nextDouble() < m.dials.shinyTokenChance.get()) out.add(TOKEN);
        return out;
    }

    private void despawn(Hunt h) {
        owners.remove(h.pig);
        LivingEntity pig = living(h.pig);
        if (pig != null) {
            try { Sky.mobs().remove(pig); } catch (RuntimeException e) { pig.remove(); }
        }
        removeOrb(h);
    }

    private static void removeOrb(Hunt h) {
        if (h.orb == null) return;
        Entity e = Bukkit.getEntity(h.orb);
        if (e != null) e.remove();
    }

    private void cooldown(UUID player) {
        cooldownUntil.put(player, System.currentTimeMillis() + Math.max(0, m.dials.shinyCooldown.get()) * 1000L);
        if (cooldownUntil.size() > 256) {
            long now = System.currentTimeMillis();
            cooldownUntil.entrySet().removeIf(en -> en.getValue() < now);
        }
    }

    void clearCooldown(Player p) { cooldownUntil.remove(p.getUniqueId()); }

    long cooldownLeft(Player p) {
        Long c = cooldownUntil.get(p.getUniqueId());
        return c == null ? 0 : Math.max(0, c - System.currentTimeMillis());
    }

    /** Admin: end a player's hunt now; {@code deliverIt} pays it as if brought to the orb. */
    String finish(Player p, boolean deliverIt) {
        Hunt h = hunts.get(p.getUniqueId());
        if (h == null) return p.getName() + " has no Shiny Pig";
        hunts.remove(p.getUniqueId());
        LivingEntity pig = living(h.pig);
        if (deliverIt && pig != null) deliver(h, p, pig);
        else escaped(h, p, "admin");
        return null;
    }

    private static LivingEntity living(UUID id) {
        if (id == null) return null;
        Entity e = Bukkit.getEntity(id);
        return e instanceof LivingEntity le && le.isValid() && !le.isDead() ? le : null;
    }

    private static boolean holdsRod(Player p) {
        return ROD.equals(Sky.items().idOf(p.getInventory().getItemInMainHand()));
    }

    private static boolean hasRod(Player p) {
        for (ItemStack s : p.getInventory().getContents()) if (s != null && ROD.equals(Sky.items().idOf(s))) return true;
        return false;
    }

    private boolean mixinActive(Player p) {
        if (!Sky.has(Features.Potions.class)) return false;
        String id = m.dials.shinyMixinEffect.get();
        if (id == null || id.isBlank()) return false;
        try {
            Map<String, Integer> a = Sky.service(Features.Potions.class).active(p);
            return a != null && a.containsKey(id.trim());
        } catch (RuntimeException e) { return false; }
    }

    // =================================================================================================
    // menu (/shinypigs, Farming hub slot 29)
    // =================================================================================================

    void open(Player p) {
        FarmData d = m.data(p);
        Menu menu = new Menu("Shiny Pigs", 4).chrome();
        long kills = Perks.shinyKills(p);
        int[] th = m.tables.shinyPigTiers;
        int tier = ShinyMath.tier(kills, th);
        List<String> lore = new ArrayList<>();
        lore.add("&7While you farm, a &dShiny Pig &7can appear");
        lore.add("&7next to a floating &dShiny Orb&7. &eRight-click");
        lore.add("&eit &7to catch it and walk it back to the orb");
        lore.add("&7within &e" + m.dials.shinySeconds.get() + "s &7for coins and rare drops.");
        lore.add("");
        lore.add("&7Delivered: &d" + Text.commasInt(d.shinyPigsCaught) + "   &7Got away: &c" + Text.commasInt(d.shinyPigsEscaped));
        lore.add("&7Coins earned: &6" + Text.commasInt(d.shinyCoins));
        lore.add("&7Bestiary tier: &a" + tier + "&7/&a" + th.length + (tier < th.length ? " &8(" + Text.commasInt(kills) + "/" + th[tier] + ")" : " &a(max)"));
        menu.set(13, Icons.of(Material.PORKCHOP, "&dShiny Pigs", lore));

        Hunt h = hunts.get(p.getUniqueId());
        List<String> now = new ArrayList<>();
        if (h != null) {
            Location o = h.orbAt;
            now.add(h.caught ? "&aCaught! &7Bring it to the orb." : "&eOn the loose! &7Right-click it to catch it.");
            now.add("&7Orb at &f" + o.getBlockX() + ", " + o.getBlockY() + ", " + o.getBlockZ()
                    + (o.getWorld() != null && o.getWorld().equals(p.getWorld()) ? " &8(" + (int) o.distance(p.getLocation()) + " blocks)" : ""));
            now.add("&7Time left: &e" + Math.max(0, (h.expires - System.currentTimeMillis()) / 1000) + "s");
        } else {
            long cd = cooldownLeft(p);
            now.add(cd > 0 ? "&7Next one can appear in &e" + Text.duration(cd / 1000) : "&7No Shiny Pig right now - keep farming!");
            now.add("&7Where: &f" + String.join(", ", m.dials.shinyIslandSet().stream().map(Text::titleCase).toList()));
        }
        menu.set(11, Icons.of(h != null ? Material.ENDER_EYE : Material.CLOCK, "&aYour Shiny Pig", now));

        List<String> bonus = new ArrayList<>();
        double ham = Perks.petAbility(p, "pig_hamfisted", "a");
        double per = Perks.petAbility(p, "pig_shining_stampede", "a");
        bonus.add("&7Pig pet &aHam Fist&7: " + (ham > 0 ? "&6+" + Text.commasInt(ham) + "% coins" : "&8not active"));
        bonus.add("&7Pig pet &aShine Rush&7: " + (per > 0 ? "&6+" + Text.commasInt(ShinyMath.stampedeFortune(per, tier)) + " Potato Fortune"
                + " &8(" + Text.commasInt(per) + " x tier " + tier + ")" : "&8not active"));
        bonus.add("&7Shiny Rod: " + (hasRod(p) ? "&6+" + Text.commasInt(m.dials.shinyRodCoins.get()) + "% coins" : "&8not carried"));
        bonus.add("&7Pig Brain Mixin: " + (mixinActive(p) ? "&a" + Math.round(m.dials.shinyMixinChance.get() * 100) + "% extra roll" : "&8not active"));
        bonus.add("");
        int lvl = Sky.has(SkillService.class) ? Sky.service(SkillService.class).level(p, SkillType.FARMING) : 0;
        bonus.add("&7Next delivery pays: &6" + Text.commasInt(ShinyMath.coins(m.dials.shinyCoins.get(), m.dials.shinyCoinsPerLevel.get(), lvl, ham,
                hasRod(p) ? m.dials.shinyRodCoins.get() : 0)) + " coins");
        menu.set(15, Icons.of(Material.CARROT_ON_A_STICK, "&6Shiny Bonuses", bonus));

        List<String> drops = new ArrayList<>();
        drops.add("&7Rolled by the orb on every delivery:");
        dropLine(drops, HARVESTING, m.dials.shinyHarvestingChance.get());
        dropLine(drops, SPREADING, m.dials.shinySpreadingChance.get());
        dropLine(drops, TOKEN, m.dials.shinyTokenChance.get());
        menu.set(22, Icons.of(Material.ENCHANTED_BOOK, "&9Orb Drops", drops));
        menu.set(menu.closeSlot() - 1, Icons.back("Farming"), c -> m.menus.openMain(c.player()));
        menu.open(p);
    }

    private static void dropLine(List<String> out, String id, double chance) {
        if (!(chance > 0) || !Sky.items().exists(id)) return;
        ItemDef d = Sky.items().def(id);
        out.add("&8- " + itemName(id) + " &7(" + pct(chance) + ")");
    }

    /** Rarity-coloured name; a book names its enchant ("Enchanted Book (Harvesting VI)"). */
    private static String itemName(String id) {
        if (id.startsWith("ENCHANTMENT_")) return Contests.bookName(id);
        ItemDef d = Sky.items().def(id);
        return d == null ? id : d.rarity.code() + Text.strip(d.name);
    }

    private static String pct(double v) {
        double p = v * 100;
        return (p >= 1 ? String.valueOf(Math.round(p * 10) / 10.0) : String.valueOf(Math.round(p * 100) / 100.0)) + "%";
    }
}
