package com.skyclone.mod.armor;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import static com.skyclone.mod.armor.SetIds.*;

/**
 * Event-shaped bonuses: kills, gathering, experience, fire, sneaking, right-click equipping, set
 * ticks, the Rancher's Boots ability, Wise Blood's mana discount, item counters and their lore.
 */
final class BonusListener implements Listener {

    static final String KEY_MAGMA = "magma_kills", KEY_YOG = "yog_kills", KEY_GROWTH = "growth_hp", KEY_SPEED_CAP = "speed_cap";

    // ================================================================================== wiring

    static void register(BonusListener l) {
        Bukkit.getPluginManager().registerEvents(l, Sky.plugin());
        Sky.abilities().addCostModifier("armor_wise_blood", (use, cost) ->
                ArmorDials.ENABLED.get() && Worn.of(use.player()).full(WISE) ? cost * ArmorDials.WISE_COST.get() : cost);
        Sky.abilities().register("armor_ranchers_speed", use -> { ranchersPrompt(use.player()); return true; });
        // wave 5: Great Spook Staff. Spooker (left click) is done on the punch itself (ExtraBonuses.onPunch, own
        // 60s cooldown) so a click on air never burns it; Spook (right click) is the Dead Bush disguise.
        Sky.abilities().register("armor_spooker", use -> false);
        Sky.abilities().register("armor_spook", use -> ExtraBonuses.disguise(use.player()));

        setTick(BLAZE, p -> blazeAura(p, false));
        setTick(FROZEN_BLAZE, p -> blazeAura(p, true));
        setTick(MUSHROOM, p -> effect(p, PotionEffectType.NIGHT_VISION, 0, 400));
        setTick(DIVER, p -> effect(p, PotionEffectType.WATER_BREATHING, 0, 60));
        setTick(ABYSSAL, p -> effect(p, PotionEffectType.WATER_BREATHING, 0, 60));
        setTick(RABBIT, p -> { if (p.isSneaking()) effect(p, PotionEffectType.JUMP_BOOST, 1, 30); });
        setTick(UNSTABLE, BonusListener::unstable);
        setTick(EMBER, p -> { if (p.getFireTicks() > 0) p.setFireTicks(0); });
        setTick(GREAT_SPOOK, BonusListener::fearsome);

        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), BonusListener::secondTick, 20L, 20L);

        Sky.items().addStatContributor("armor_growth", (def, data, rarity, sheet) -> {
            if (def != null && GROWTH.equals(def.setId)) {
                long hp = ArmorUtil.longExtra(data.extra, KEY_GROWTH);
                if (hp > 0) sheet.add(Stat.HEALTH, hp, ItemStatSheet.Source.OTHER);
            }
        });
        Sky.items().addLoreContributor("armor_counters", new LoreContributor() {
            @Override public Section section() { return Section.BEFORE_FOOTER; }
            @Override public List<String> lines(ItemDef def, com.skyclone.api.item.ItemData data) { return counterLore(def, data); }
        });
        Sky.items().addLoreContributor("armor_other_stats", new LoreContributor() {
            @Override public Section section() { return Section.AFTER_STATS; }
            @Override public List<String> lines(ItemDef def, com.skyclone.api.item.ItemData data) { return otherStatLore(def); }
        });
        Sky.vitals().actionBarSegment("armor", 300, BonusListener::actionBar);
    }

    /**
     * Wave 5: the reference stats with no {@code Stat} here (Fear, Pressure Resistance) are still shown on our
     * armour pieces, like the accessories module shows them on equipment. Only for pieces this module owns.
     */
    static List<String> otherStatLore(ItemDef def) {
        if (def == null || (ArmorModule.sets().setOf(def.id) == null && !SetIds.GREAT_SPOOK_STAFF.equals(def.id))) return List.of();
        List<String> out = new ArrayList<>();
        double fear = Fear.other(def, "FEAR"), pressure = Fear.other(def, "PRESSURE_RESISTANCE");
        if (fear != 0) out.add("&7Fear: &5+" + Text.commasInt(fear));
        if (pressure != 0) out.add("&7Pressure Resistance: &9+" + Text.commasInt(pressure));
        return out;
    }

    /** Core's set tick counts pieces without requirements; re-check through Worn (requirements met, full set). */
    private static void setTick(String setId, java.util.function.Consumer<Player> fn) {
        ArmorSets.SetInfo s = ArmorModule.sets().get(setId);
        int need = s == null ? 4 : Math.max(1, Math.min(4, s.size()));
        Sky.abilities().registerSetTick(setId, need, p -> {
            if (ArmorDials.ENABLED.get() && Sky.profiles().isReady(p) && Worn.of(p).full(setId)) fn.accept(p);
        });
    }

    static List<String> counterLore(ItemDef def, com.skyclone.api.item.ItemData data) {
        if (def == null) return List.of();
        List<String> out = new ArrayList<>();
        switch (def.id) {
            case "ARMOR_OF_MAGMA_CHESTPLATE" -> {
                long k = ArmorUtil.longExtra(data.extra, KEY_MAGMA);
                double b = SetMath.perKills(k, 10, 200);
                out.add("&7Magma Cubes Killed: &a" + Text.commasInt(k));
                out.add("&7Bonus: &c+" + Text.commasInt(b) + " ❤ &b+" + Text.commasInt(b) + " ✎");
            }
            case "ARMOR_OF_YOG_CHESTPLATE" -> {
                long k = ArmorUtil.longExtra(data.extra, KEY_YOG);
                out.add("&7Lava Beasts Slain: &a" + Text.commasInt(k));
                out.add("&7Bonus: &6+" + Text.commasInt(SetMath.perKills(k, 5, 500)) + " ⸕ Mining Speed");
            }
            case "RANCHERS_BOOTS" -> {
                long cap = ArmorUtil.longExtra(data.extra, KEY_SPEED_CAP);
                out.add("&7Speed Cap: " + (cap > 0 ? "&f" + cap + " ✦" : "&8none"));
            }
            default -> {
                if (GROWTH.equals(def.setId)) out.add("&7Grown Health: &c+" + ArmorUtil.longExtra(data.extra, KEY_GROWTH) + "&7/100");
            }
        }
        return out;
    }

    // ================================================================================== kills

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        Player p = e.killer;
        if (p == null || !ArmorDials.ENABLED.get() || !Sky.profiles().isReady(p)) return;
        Worn w = Worn.of(p);
        if (!w.any()) return;
        MobDef def = e.def;
        long now = System.currentTimeMillis();
        ArmorState.P st = ArmorState.of(p.getUniqueId());

        if (w.full(GOLEM)) golemAbsorption(p);
        if (w.full(MERCENARY) && def != null && def.level >= ArmorDials.MERC_MIN_LEVEL.get()) {
            e.coins += ArmorDials.MERC_COINS.get();
            Sky.vitals().heal(p, ArmorDials.MERC_HEAL.get());
        }
        if (w.pieces(GROWTH) > 0 && ArmorUtil.monster(def) && now >= st.growthReady) {
            st.growthReady = now + (long) (ArmorDials.GROWTH_COOLDOWN.get() * 1000);
            Sky.vitals().heal(p, Sky.vitals().maxHealth(p) * ArmorDials.GROWTH_HEAL_PCT.get() / 100.0);
            for (int i = 0; i < 4; i++) {
                if (!w.usable[i] || w.defs[i] == null || !GROWTH.equals(w.defs[i].setId)) continue;
                bump(p, i, KEY_GROWTH, (long) (double) ArmorDials.GROWTH_MAX.get());
            }
        }
        // Indulgence / Overindulgence (crowns): mythic creatures pay more coins.
        if (ArmorUtil.mythic(def)) {
            double m = SetMath.crownCoins(w.wears(CROWN_GREED), w.wears(CROWN_AVARICE), w.wears(CROWN_AVARICE_2),
                    ArmorDials.CROWN_GREED_COINS.get(), ArmorDials.CROWN_AVARICE_COINS.get(), ArmorDials.CROWN_AVARICE_2_COINS.get());
            if (m > 1) e.coins *= m;
        }
        if (w.full(MAGMA) && ArmorUtil.familyContains(def, "magma_cube")) bumpPiece(p, w, "ARMOR_OF_MAGMA_CHESTPLATE", KEY_MAGMA, Long.MAX_VALUE);
        if (w.full(YOG) && ArmorUtil.familyContains(def, "yog")) bumpPiece(p, w, "ARMOR_OF_YOG_CHESTPLATE", KEY_YOG, Long.MAX_VALUE);
    }

    private static void golemAbsorption(Player p) {
        double want = ArmorDials.GOLEM_ABSORPTION.get();
        double have = Sky.vitals().absorption(p);
        if (have >= want) return;
        double add = want - have;
        Sky.vitals().addAbsorption(p, add);
        long ticks = (long) (ArmorDials.GOLEM_SECONDS.get() * 20);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            double cur = Sky.vitals().absorption(p);
            if (cur > 0) Sky.vitals().addAbsorption(p, -Math.min(cur, add));
        }, ticks);
    }

    private static void bumpPiece(Player p, Worn w, String pieceId, String key, long cap) {
        int slot = w.slotOf(pieceId);
        if (slot >= 0) bump(p, slot, key, cap);
    }

    /** +1 on an ItemData counter of the piece in a worn slot, written back into that slot. */
    static void bump(Player p, int slot, String key, long cap) {
        PlayerInventory inv = p.getInventory();
        ItemStack s = slotItem(inv, slot);
        if (s == null || s.getType().isAir()) return;
        Sky.items().update(s, d -> {
            JsonObject x = d.extra;
            long v = ArmorUtil.longExtra(x, key);
            if (v < cap) x.addProperty(key, v + 1);
        });
        setSlotItem(inv, slot, s);
        Sky.stats().invalidate(p);
    }

    static ItemStack slotItem(PlayerInventory inv, int slot) {
        return switch (slot) {
            case Worn.HEAD -> inv.getHelmet();
            case Worn.CHEST -> inv.getChestplate();
            case Worn.LEGS -> inv.getLeggings();
            default -> inv.getBoots();
        };
    }

    static void setSlotItem(PlayerInventory inv, int slot, ItemStack s) {
        switch (slot) {
            case Worn.HEAD -> inv.setHelmet(s);
            case Worn.CHEST -> inv.setChestplate(s);
            case Worn.LEGS -> inv.setLeggings(s);
            default -> inv.setBoots(s);
        }
    }

    // ================================================================================== farming ladder

    @EventHandler
    public void onGather(SkyEvents.Gather e) {
        if (e.player == null || e.kind != LootSource.FARMING || !ArmorDials.ENABLED.get()) return;
        Worn w = Worn.of(e.player);
        if (!w.any()) return;
        String crop = e.what == null ? "" : e.what.toUpperCase(Locale.ROOT);
        int melon = w.pieces(MELON), cropie = w.pieces(CROPIE), squash = w.pieces(SQUASH), ferm = w.pieces(FERMENTO), heli = w.pieces(HELIANTHUS);
        // Higher tiers carry the lower tiers' rolls (Fermenta: Tater+Cropling+Squash; Helianthus: all four).
        int tater = Math.max(melon, Math.max(ferm, heli));
        int crop2 = Math.max(cropie, Math.max(ferm, heli));
        int sq = Math.max(squash, Math.max(ferm, heli));
        int fe = Math.max(ferm, heli);
        if (tater > 0 && isAny(crop, "WHEAT", "CARROT", "POTATO")) rareCrop(e.player, CROPIE_ITEM, SetMath.CROPIE_PCT, tater);
        if (crop2 > 0 && isAny(crop, "PUMPKIN", "MELON", "COCOA", "INK_SACK:3")) rareCrop(e.player, SQUASH_ITEM, SetMath.SQUASH_PCT, crop2);
        if (sq > 0 && isAny(crop, "SUGAR_CANE", "CACTUS", "MUSHROOM", "NETHER_STALK", "NETHER_WART")) rareCrop(e.player, FERMENTO_ITEM, SetMath.FERMENTO_PCT, sq);
        if (fe > 0 && isAny(crop, "WILD_ROSE", "SUNFLOWER", "MOONFLOWER", "DOUBLE_PLANT")) rareCrop(e.player, HELIANTHUS_ITEM, SetMath.HELIANTHUS_PCT, fe);
    }

    private static boolean isAny(String crop, String... keys) {
        for (String k : keys) if (crop.contains(k)) return true;
        return false;
    }

    private static void rareCrop(Player p, String itemId, double pctAtFour, int pieces) {
        double chance = SetMath.rareCropChance(pctAtFour, pieces, ArmorDials.RARE_CROP_MULT.get());
        if (ThreadLocalRandom.current().nextDouble() >= chance) return;
        if (!Sky.items().exists(itemId)) return; // farming/garden owns the item; no item, no drop
        Sky.loot().give(p, itemId, 1, LootSource.FARMING);
        ItemDef d = Sky.items().def(itemId);
        Msg.rareDrop(p, "&6&lRARE CROP!", d.rarity.code() + d.name, 0);
    }

    // ================================================================================== experience

    @EventHandler(priority = EventPriority.HIGH)
    public void onExp(PlayerExpChangeEvent e) {
        if (e.getAmount() <= 0 || !ArmorDials.ENABLED.get()) return;
        Worn w = Worn.of(e.getPlayer());
        if (!w.any()) return;
        double pct = SetMath.perPiece(w.pieces(SHIMMER), ArmorDials.SHIMMER_XP_PCT.get());
        if (ArmorUtil.onMiningIsland(e.getPlayer())) pct += SetMath.perPiece(w.pieces(LAPIS), ArmorDials.LAPIS_XP_PCT.get());
        if (pct > 0) e.setAmount((int) Math.round(e.getAmount() * (1 + pct / 100.0)));
    }

    // ================================================================================== fire / lava

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVanillaDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !ArmorDials.ENABLED.get()) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c == EntityDamageEvent.DamageCause.FALL) { bouncy(p, e); return; }
        boolean fire = c == EntityDamageEvent.DamageCause.FIRE || c == EntityDamageEvent.DamageCause.FIRE_TICK
                || c == EntityDamageEvent.DamageCause.LAVA || c == EntityDamageEvent.DamageCause.HOT_FLOOR;
        if (!fire) return;
        Worn w = Worn.of(p);
        boolean immune = w.full(EMBER) || (w.fireproof() && ArmorUtil.on(p, "crimson_isle"));
        if (immune) { e.setCancelled(true); p.setFireTicks(0); }
    }

    /** Bouncy (Slug Boots): land from a height while sneaking and spring back up instead of taking the fall. */
    private static void bouncy(Player p, EntityDamageEvent e) {
        Worn w = Worn.of(p);
        if (!w.wears(SLUG_BOOTS) || !p.isSneaking()) return;
        double up = SetMath.bounceVelocity(p.getFallDistance(), ArmorDials.BOUNCY_MIN_FALL.get(), ArmorDials.BOUNCY_POWER.get());
        if (up <= 0) return;
        e.setCancelled(true);
        p.setFallDistance(0);
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            p.setVelocity(p.getVelocity().setY(up));
            p.getWorld().spawnParticle(Particle.ITEM_SLIME, p.getLocation(), 20, 0.4, 0.1, 0.4, 0.1);
            p.playSound(p.getLocation(), Sound.BLOCK_SLIME_BLOCK_FALL, 1f, 1.2f);
        });
    }

    // ================================================================================== knockback

    /** Sieve Body (Rotten, full set): arrows knock you back less. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent e) {
        if (!(e.getEntity() instanceof Player p) || !ArmorDials.ENABLED.get()) return;
        if (!(e.getPushedBy() instanceof org.bukkit.entity.AbstractArrow)) return;
        if (!Sky.profiles().isReady(p) || !Worn.of(p).full(ROTTEN)) return;
        double f = Math.max(0, ArmorDials.SIEVE_KNOCKBACK.get());
        e.setKnockback(e.getKnockback().multiply(f));
    }

    // ================================================================================== Farmer's Grace

    static final String[] GRACE_SETS = {MELON, CROPIE, SQUASH, FERMENTO, HELIANTHUS};

    /** Farmer's Grace (the farming ladder and Rancher's Boots): you never trample farmland, on your own island too. */
    @EventHandler(priority = EventPriority.LOW)
    public void onTrample(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL || e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.FARMLAND) return;
        if (!ArmorDials.ENABLED.get() || !Sky.profiles().isReady(e.getPlayer())) return;
        Worn w = Worn.of(e.getPlayer());
        if (!w.any()) return;
        boolean grace = w.wears(RANCHERS_BOOTS);
        for (String s : GRACE_SETS) if (!grace && w.pieces(s) > 0) grace = true;
        if (grace) e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
    }

    // ================================================================================== fishing

    static final List<String> MUSIC_DISCS = List.of("RECORD_13", "RECORD_3", "RECORD_4", "RECORD_5", "RECORD_6", "RECORD_7",
            "RECORD_8", "RECORD_9", "RECORD_10", "RECORD_11", "RECORD_12");

    /** Beat Saver (Music Pants): sometimes a catch comes up with a music disc as well. */
    @EventHandler
    public void onFish(SkyEvents.FishCaught e) {
        Player p = e.player;
        if (p == null || e.seaCreature || !ArmorDials.ENABLED.get() || !Sky.profiles().isReady(p)) return;
        if (!Worn.of(p).wears(MUSIC_PANTS)) return;
        if (ThreadLocalRandom.current().nextDouble() * 100 >= ArmorDials.BEAT_SAVER_CHANCE.get()) return;
        List<String> have = new ArrayList<>();
        for (String id : MUSIC_DISCS) if (Sky.items().exists(id)) have.add(id);
        if (have.isEmpty()) return;
        String disc = have.get(ThreadLocalRandom.current().nextInt(have.size()));
        Sky.loot().give(p, disc, 1, LootSource.FISHING);
        Msg.send(p, "&d&lBEAT SAVER! &7You reeled in a &b" + Text.strip(Sky.items().def(disc).name) + "&7!");
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.6f);
    }

    // ================================================================================== sneak abilities

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p = e.getPlayer();
        if (!ArmorDials.ENABLED.get() || !Sky.profiles().isReady(p)) return;
        Worn w = Worn.of(p);
        if (!w.any()) return;
        ArmorState.P st = ArmorState.of(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (e.isSneaking()) {
            if ((w.full(SALMON) || w.full(SALMON_NEW)) && p.isInWater()) {
                if (Sky.vitals().useMana(p, ArmorDials.SALMON_MANA.get())) {
                    p.setVelocity(p.getLocation().getDirection().normalize().multiply(ArmorDials.SALMON_POWER.get()));
                    p.getWorld().spawnParticle(Particle.BUBBLE_POP, p.getLocation(), 30, 0.4, 0.4, 0.4, 0.1);
                    p.playSound(p.getLocation(), Sound.ENTITY_DOLPHIN_SWIM, 1f, 1f);
                    Sky.vitals().flash(p, "&b-" + Text.commasInt(ArmorDials.SALMON_MANA.get()) + " Mana (&6Water Burst&b)", 30);
                }
            }
            if (w.wears(SPIDER_BOOTS) && !p.isOnGround() && !st.doubleJumpUsed && !p.isFlying()) {
                if (Sky.vitals().useMana(p, ArmorDials.SPIDER_JUMP_MANA.get())) {
                    st.doubleJumpUsed = true;
                    Vector v = p.getLocation().getDirection().setY(0);
                    if (v.lengthSquared() > 0.001) v.normalize().multiply(0.4);
                    p.setVelocity(v.setY(0.9));
                    p.playSound(p.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 0.7f, 1.6f);
                    Sky.vitals().flash(p, "&b-" + Text.commasInt(ArmorDials.SPIDER_JUMP_MANA.get()) + " Mana (&6Double Jump&b)", 30);
                }
            }
            if (w.wears(SPRING_BOOTS) && p.isOnGround()) st.springSince = now;
            if (w.wears(FISH_HAT) || w.wears(SALMON_HAT) || w.wears(SALMON_HAT_2)) splash(p, false, 10);
            if (w.wears(MAGMA_FISH_HAT)) splash(p, true, 20);
        } else if (st.springSince > 0) {
            double charge = Math.min(1.0, (now - st.springSince) / 2000.0);
            st.springSince = 0;
            if (w.wears(SPRING_BOOTS) && charge >= 0.25 && p.isOnGround()) {
                p.setVelocity(p.getVelocity().setY(ArmorDials.SPRING_MAX_POWER.get() * charge));
                p.playSound(p.getLocation(), Sound.BLOCK_PISTON_EXTEND, 1f, 1.5f);
                p.setFallDistance(0);
            }
        }
    }

    private static void splash(Player p, boolean magma, double mana) {
        if (Sky.abilities().cooldownLeft(p, "armor_splash") > 0) return;
        if (!Sky.vitals().useMana(p, mana)) return;
        Sky.abilities().setCooldown(p, "armor_splash", 1);
        p.getWorld().spawnParticle(magma ? Particle.LAVA : Particle.SPLASH, p.getLocation().add(0, 1, 0), magma ? 12 : 40, 0.8, 0.4, 0.8, 0.1);
        p.playSound(p.getLocation(), magma ? Sound.BLOCK_LAVA_POP : Sound.ENTITY_PLAYER_SPLASH, 1f, 1f);
    }

    // ================================================================================== right-click equip

    /**
     * Last right-click equip per player as {tick, slot}: one click can arrive as a block event AND an
     * air event (same tick, or the next one if the packets straddle a tick), and the second must not
     * swap the piece straight back. A click on a DIFFERENT slot is never swallowed.
     */
    private static final java.util.Map<java.util.UUID, int[]> EQUIP_TICK = new java.util.HashMap<>();

    /**
     * Right-click a sky armour piece in the main hand to wear it, swapping with what is worn in
     * that slot — EVERY slot, done here on the server, not only head/block helmets.
     *
     * <p>Round 1 (real client): helmet (ours) and chestplate (vanilla) equipped, leggings and boots
     * stayed in the hotbar. Vanilla's equip runs through the item-use path, which any other
     * listener's {@code setUseItemInHand(DENY)} / cancel, a Paper interact de-dupe, or a missing
     * {@code equippable} component silently turns into "nothing happened". So the armour module
     * now owns the whole gesture: it swaps the piece itself and denies the vanilla use, so the
     * result is the same for every slot and every material (leather, iron, head, block).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !ArmorDials.RIGHT_CLICK_EQUIP.get()) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return;
        String id = Sky.items().idOf(hand);
        ItemDef def = id == null ? null : Sky.items().def(id);
        int slot = def == null ? -1 : ArmorUtil.armorIndex(def.category);
        if (slot < 0) return;
        // an armour piece with its own right-click ability keeps the click for the ability
        if (def.abilities != null) for (com.skyclone.api.item.AbilityDef a : def.abilities) {
            if (a != null && (a.trigger == com.skyclone.api.item.AbilityDef.Trigger.RIGHT_CLICK
                    || a.trigger == com.skyclone.api.item.AbilityDef.Trigger.SNEAK_RIGHT_CLICK)) return;
        }
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()
                && !p.isSneaking()) return; // let doors/chests work
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);   // vanilla must not equip it a second time
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        int now = Bukkit.getCurrentTick();
        int[] last = EQUIP_TICK.put(p.getUniqueId(), new int[]{now, slot});
        if (SetMath.sameEquipClick(last, now, slot)) return;   // the second packet of the same click: already swapped
        equipFromHand(p, hand, slot);
    }

    /** Puts one of {@code hand} into armour slot {@code slot} (armorIndex order); what was worn goes back to the hand. */
    static void equipFromHand(Player p, ItemStack hand, int slot) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] armor = inv.getArmorContents();
        ItemStack old = armor[slot];
        boolean hadOld = old != null && !old.getType().isAir();
        ItemStack wear = hand.clone();
        wear.setAmount(1);
        armor[slot] = wear;
        inv.setArmorContents(armor);
        if (hand.getAmount() <= 1) {
            inv.setItemInMainHand(hadOld ? old : null);
        } else {
            hand.setAmount(hand.getAmount() - 1);
            inv.setItemInMainHand(hand);
            if (hadOld) for (ItemStack left : inv.addItem(old).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        }
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 1f, 1f);
        Worn.forget(p.getUniqueId());   // Worn caches per tick; this tick's copy predates the swap
        Sky.stats().invalidate(p);
    }

    // ================================================================================== Strong Blood

    @EventHandler
    public void onAbility(SkyEvents.AbilityUsed e) {
        if (!ArmorDials.ENABLED.get() || e.player == null) return;
        String used = Sky.items().idOf(e.item);
        if (STARLIGHT_WAND.equals(used) && e.ability != null && Worn.of(e.player).full(STARLIGHT)) { starpower(e.player, e.ability); return; }
        if (!AOTE.equals(used)) return;
        if (!Worn.of(e.player).full(STRONG)) return;
        ArmorState.P st = ArmorState.of(e.player.getUniqueId());
        long now = System.currentTimeMillis();
        ArmorState.live(st.strong, now);
        st.strong.addLast(now + (long) (ArmorDials.STRONG_SECONDS.get() * 1000));
        Sky.stats().invalidate(e.player);
        // +2 teleport range (wave 3): after the weapon's own teleport lands, carry on up to N more
        // blocks along the look direction, stopping at the first block a player could not stand in.
        int extra = ArmorDials.STRONG_EXTRA_RANGE.get();
        if (extra <= 0) return;
        Player p = e.player;
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline() || p.isDead()) return;
            Location from = p.getLocation();
            Vector dir = from.getDirection().normalize();
            Location best = null;
            for (int step = 1; step <= extra; step++) {
                Location at = from.clone().add(dir.clone().multiply(step));
                if (!at.getBlock().isPassable() || !at.clone().add(0, 1, 0).getBlock().isPassable()) break;
                best = at;
            }
            if (best == null) return;
            best.setYaw(from.getYaw());
            best.setPitch(from.getPitch());
            p.teleport(best);
            p.setFallDistance(0);
        });
    }

    /**
     * Starpower (Starlight, full set): the wand's Starfall lasts twice as long and reaches +1 block.
     * The wand still owns its own field (3 blocks, 5 pulses, 1/s, research 06); this adds the ring
     * between 3 and 3+N blocks during those pulses, then a second field of the full radius for as many
     * pulses again, at the same aim point (same ray as the wand's: 20 blocks, stop at a block).
     */
    static void starpower(Player p, com.skyclone.api.item.AbilityDef ability) {
        Location eye = p.getEyeLocation();
        org.bukkit.util.RayTraceResult r = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), 20);
        Location at = r != null ? r.getHitPosition().toLocation(p.getWorld()).subtract(eye.getDirection().multiply(0.3))
                : eye.clone().add(eye.getDirection().multiply(20));
        double base = SetMath.STARFALL_RADIUS, radius = base + ArmorDials.STARPOWER_RANGE.get();
        int pulses = SetMath.STARFALL_PULSES;
        new org.bukkit.scheduler.BukkitRunnable() {
            int n;
            @Override public void run() {
                if (!p.isOnline() || n >= pulses * 2 || !at.isWorldLoaded()) { cancel(); return; }
                boolean extended = n >= pulses;     // second half: the doubled duration
                n++;
                for (Entity ent : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
                    if (!(ent instanceof LivingEntity le) || !canHit(p, le)) continue;
                    double d = le.getLocation().distance(at);
                    if (d > radius || (!extended && d <= base)) continue;   // the wand already hits the inner field
                    Sky.combat().magicDamage(p, le, ability.damage, ability.scaling, ability.name);
                }
                at.getWorld().spawnParticle(Particle.END_ROD, at.clone().add(0, 2, 0), extended ? 20 : 6, radius / 2, 1, radius / 2, 0.01);
            }
        }.runTaskTimer(Sky.plugin(), 0L, 20L);
    }

    // ================================================================================== Rancher's Boots

    static long ranchersCap(ItemStack boots) {
        if (boots == null) return 0;
        return ArmorUtil.longExtra(Sky.items().data(boots).extra, KEY_SPEED_CAP);
    }

    static void ranchersPrompt(Player p) {
        Sky.menus().inputNumber(p, "Max Speed (0 = none)", n -> {
            if (n == null || !p.isOnline()) return;
            long cap = Math.max(0, Math.min(1000, Math.round(n)));
            ItemStack target = null;
            PlayerInventory inv = p.getInventory();
            if (RANCHERS_BOOTS.equals(Sky.items().idOf(inv.getItemInMainHand()))) target = inv.getItemInMainHand();
            else if (RANCHERS_BOOTS.equals(Sky.items().idOf(inv.getBoots()))) target = inv.getBoots();
            if (target == null) { Msg.error(p, "Hold or wear your Rancher's Boots to set the cap."); return; }
            ItemStack t = target;
            Sky.items().update(t, d -> d.extra.addProperty(KEY_SPEED_CAP, cap));
            if (t == inv.getBoots()) inv.setBoots(t); else inv.setItemInMainHand(t);
            Sky.stats().invalidate(p);
            Msg.success(p, cap > 0 ? "Speed capped at " + cap + "." : "Speed cap removed.");
        });
    }

    // ================================================================================== ticks

    private static void effect(Player p, PotionEffectType type, int amp, int ticks) {
        PotionEffect cur = p.getPotionEffect(type);
        if (cur != null && cur.getAmplifier() > amp) return;
        if (cur != null && cur.getAmplifier() == amp && cur.getDuration() > ticks / 2) return;
        p.addPotionEffect(new PotionEffect(type, ticks, amp, true, false, false));
    }

    private static boolean canHit(Player p, LivingEntity le) {
        if (le instanceof Player || !le.isValid() || le.isDead() || !Sky.mobs().isSkyMob(le)) return false;
        java.util.UUID owner = Sky.mobs().owner(le);
        return owner == null || owner.equals(p.getUniqueId());
    }

    static void blazeAura(Player p, boolean frozen) {
        if (!ArmorDials.ENABLED.get()) return;
        double r = ArmorDials.BLAZE_RANGE.get();
        double cap = SetMath.blazeAuraCap(BonusStats.collection(p, "BLAZE_ROD"), ArmorDials.BLAZE_CAP.get(), ArmorDials.BLAZE_CAP_PER_5K.get());
        double pet = blazePetPercent(p);
        boolean any = false;
        for (Entity ent : p.getNearbyEntities(r, r, r)) {
            if (!(ent instanceof LivingEntity le) || !canHit(p, le)) continue;
            double dmg = SetMath.petBoosted(SetMath.blazeAuraDamage(Sky.mobs().maxHealth(le), ArmorDials.BLAZE_PCT.get(),
                    frozen ? ArmorDials.FROZEN_BASE.get() : 0, cap), pet);
            Sky.combat().rawDamage(le, dmg, p, DamageContext.Kind.MAGIC);
            if (frozen) le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 0, true, false, false));
            any = true;
        }
        if (any) p.getWorld().spawnParticle(frozen ? Particle.SNOWFLAKE : Particle.FLAME, p.getLocation().add(0, 1, 0), 12, r / 2, 0.5, r / 2, 0.01);
    }

    /**
     * Wave 5 (pets hand-off): the Blaze pet's "Bling Armor" raises Blaze Armor stats AND ability by a%. The
     * pets module applies the stat half (its {@code armor_buff} on "BLAZE_" pieces); the aura is ours, so
     * its damage is raised here by the same number. 0 without the pet or with the dial off.
     */
    static double blazePetPercent(Player p) {
        if (!ArmorDials.BLAZE_PET_ABILITY.get()) return 0;
        try {
            return Math.max(0, Sky.service(com.skyclone.api.feature.Features.Pets.class).abilityValue(p, "blaze_bling_armor", "a"));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static void unstable(Player p) {
        if (!ArmorDials.ENABLED.get()) return;
        ArmorState.P st = ArmorState.of(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (now < st.unstableNext) return;
        double r = ArmorDials.UNSTABLE_RANGE.get();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity ent : p.getNearbyEntities(r, r, r)) {
            if (!(ent instanceof LivingEntity le) || !canHit(p, le)) continue;
            double d = le.getLocation().distanceSquared(p.getLocation());
            if (d < bestD) { bestD = d; best = le; }
        }
        if (best == null) return;
        st.unstableNext = now + ArmorDials.UNSTABLE_INTERVAL.get() * 1000L;
        best.getWorld().strikeLightningEffect(best.getLocation());
        Sky.combat().magicDamage(p, best, ArmorDials.UNSTABLE_DAMAGE.get(), 0, "Unstable Blood");
    }

    /** Once a second for every online player: single-item effects that are not set ticks. */
    static void secondTick() {
        if (!ArmorDials.ENABLED.get()) return;
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            Worn w = Worn.of(p);
            breath(p, w);
            if (!w.any()) continue;
            ArmorState.P st = ArmorState.of(p.getUniqueId());
            // Rekindle: how long the wearer has been burning (feeds the damage bonus).
            if (w.pieces(REKINDLED) > 0 && p.getFireTicks() > 0) { if (st.burningSince <= 0) st.burningSince = now; }
            else st.burningSince = 0;
            if (p.isOnGround()) st.doubleJumpUsed = false;
            if (w.wears(SKELETON_HELMET)) BonusCombat.regrowBones(st, now);
            if (w.wears(VAMPIRE_MASK) || w.wears(VAMPIRE_WITCH_MASK)) {
                double drain = ArmorDials.VAMPIRE_DRAIN.get(), total = 0;
                for (Entity ent : p.getNearbyEntities(8, 4, 8)) {
                    if (!(ent instanceof LivingEntity le) || !canHit(p, le) || !ArmorUtil.monster(ArmorUtil.def(le))) continue;
                    total += Sky.combat().rawDamage(le, drain, p, DamageContext.Kind.TRUE);
                }
                if (total > 0) Sky.vitals().heal(p, total);
            }
            if (w.wears(SUNFLOWER_HEAD_ID)) p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 2.2, 0), 2, 0.3, 0.2, 0.3, 0);
        }
    }

    static final String SUNFLOWER_HEAD_ID = "SUNFLOWER_HEAD";

    /**
     * Respiration on armour (Diver 3/piece, Abyssal 5/piece in the reference data) and Long Tuba
     * (Snorkeling, levels per piece by dial) become a longer breath: vanilla 15 s, +15 s per level.
     * Puts the player's maximum air back the moment nothing worn grants any.
     */
    static void breath(Player p, Worn w) {
        double levels = 0;
        if (w.any()) {
            for (int i = 0; i < 4; i++) {
                if (!w.usable[i] || w.defs[i] == null || w.defs[i].extra == null || !w.defs[i].extra.has("respiration")) continue;
                try { levels += w.defs[i].extra.get("respiration").getAsDouble(); } catch (RuntimeException ignored) { }
            }
            levels += w.pieces(SNORKELING) * ArmorDials.SNORKEL_LEVELS.get();
        }
        ArmorState.P st = ArmorState.peek(p.getUniqueId());
        if (levels > 0) {
            int air = SetMath.maxAirTicks(levels, ArmorDials.BREATH_PER_LEVEL.get());
            if (p.getMaximumAir() != air) p.setMaximumAir(air);
            ArmorState.of(p.getUniqueId()).airRaised = true;
        } else if (st != null && st.airRaised) {
            restoreAir(p);
            st.airRaised = false;
        }
    }

    static void restoreAir(Player p) {
        p.setMaximumAir(300);
        if (p.getRemainingAir() > 300) p.setRemainingAir(300);
    }

    /**
     * Fearsome (Great Spook, full set): monsters at or below your total Fear level lose interest in you and
     * are pushed back. Wave 5: the threshold is the real Fear sum ({@link Fear#total}: 4 x 5 from the set,
     * plus spooky equipment, the talisman line and the Greater Spook reforge), not a fixed level.
     */
    static void fearsome(Player p) {
        double r = ArmorDials.FEARSOME_RANGE.get();
        double maxLevel = Fear.total(p);
        boolean any = false;
        for (Entity ent : p.getNearbyEntities(r, Math.max(2, r / 2), r)) {
            if (!(ent instanceof LivingEntity le) || ent instanceof Player || !Sky.mobs().isSkyMob(le)) continue;
            MobDef def = ArmorUtil.def(le);
            if (def == null || !ArmorUtil.monster(def) || def.level > maxLevel || def.has("boss")) continue;
            if (le instanceof org.bukkit.entity.Mob mob && p.equals(mob.getTarget())) mob.setTarget(null);
            Vector away = le.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 0.0001) continue;
            le.setVelocity(away.normalize().multiply(ArmorDials.FEARSOME_PUSH.get()).setY(0.2));
            any = true;
        }
        if (any) p.getWorld().spawnParticle(Particle.SOUL, p.getLocation().add(0, 1, 0), 6, 0.6, 0.6, 0.6, 0.02);
    }

    static String actionBar(Player p) {
        if (!ArmorDials.ENABLED.get() || !Sky.profiles().isReady(p)) return null;
        Worn w = Worn.of(p);
        if (!w.any()) return null;
        StringBuilder sb = new StringBuilder();
        ArmorState.P st = ArmorState.of(p.getUniqueId());
        if (w.full(THUNDER)) {
            int c = SetMath.staticCharges((System.currentTimeMillis() - st.thunderSince) / 1000, ArmorDials.THUNDER_SECONDS.get(), ArmorDials.THUNDER_CAP.get());
            sb.append("&e⚡ ").append(c).append("/").append(ArmorDials.THUNDER_CAP.get());
        }
        if (w.wears(SKELETON_HELMET) && st.bones >= 0) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("&fBones ").append(st.bones);
        }
        if (w.full(WEREWOLF)) {
            int n = ArmorState.live(st.howl, System.currentTimeMillis());
            if (n > 0) { if (sb.length() > 0) sb.append("  "); sb.append("&aHowl x").append(n); }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ================================================================================== cleanup

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ArmorState.P st = ArmorState.peek(e.getPlayer().getUniqueId());
        if (st != null && st.airRaised) restoreAir(e.getPlayer());
        Worn.forget(e.getPlayer().getUniqueId());
        EQUIP_TICK.remove(e.getPlayer().getUniqueId());
        ArmorState.forget(e.getPlayer().getUniqueId());
    }

    /** Useful for the admin info command: where the player is standing. */
    static String where(Player p) {
        Location l = p.getLocation();
        return ArmorUtil.island(p) + " @ " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + " light " + l.getBlock().getLightLevel()
                + (p.isInWater() ? " (in water)" : "") + (Material.WATER == p.getEyeLocation().getBlock().getType() ? " (under water)" : "");
    }
}
