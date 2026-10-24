package com.skyclone.mod.farming;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
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
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Mushroom Desert trapper (research 03 section 6.3): he names an animal, its rarity and where it was seen;
 * you track it down (it glows once you are close), kill it, and he pays Pelts. Pelts buy the Surveyor's and the
 * Fur-trader's stock.
 */
final class Trapper implements Listener {

    static final String ISLAND = "mushroom_desert";
    static final String[] ANIMALS = {"cow", "pig", "sheep", "chicken", "rabbit", "horse"};
    static final String[] RARITIES = {"trackable", "untrackable", "undetected", "endangered", "elusive"};
    static final String[] RARITY_NAMES = {"&fTrackable", "&aUntrackable", "&9Undetected", "&5Endangered", "&dElusive"};
    /** TUNE: the reference gives no odds; common quarries are common. */
    static final double[] WEIGHTS = {40, 30, 18, 9, 3};

    private final FarmingModule m;

    Trapper(FarmingModule m) { this.m = m; }

    void start() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 40L, 40L);
        Sky.abilities().register("farming_theodolite", use -> theodolite(use.player()));
        Sky.items().addLoreContributor("farming_trapper_crest", new CrestLore());
    }

    /** Hunt areas from the desert layout: id -> display name (layout data "trapper.areas" = "id:Name,..."). */
    Map<String, String> areas() {
        Map<String, String> out = new LinkedHashMap<>();
        Layout l = Sky.worlds().layout(ISLAND);
        if (l == null) return out;
        String s = l.data().getOrDefault("trapper.areas", "");
        for (String part : s.split(",")) {
            int c = part.indexOf(':');
            if (c > 0) out.put(part.substring(0, c).trim(), part.substring(c + 1).trim());
        }
        return out;
    }

    List<Location> spots(String area) { return Sky.worlds().anchors(ISLAND, "trapper.spot." + area + "."); }

    // ---- quests ------------------------------------------------------------------------------------

    void talk(Player p) {
        FarmData d = m.data(p);
        long now = System.currentTimeMillis();
        if (d.quest != null) {
            openQuest(p);
            return;
        }
        if (now < d.trapperCooldownUntil) {
            Msg.npc(p, FarmingModule.TRAPPER_NAME, List.of("&fGive me a moment to sort these pelts. Back in &a"
                    + Text.duration((d.trapperCooldownUntil - now) / 1000 + 1) + "&f."), null);
            return;
        }
        if (!Sky.worlds().isBuilt(ISLAND) || areas().isEmpty()) {
            Msg.error(p, "The hunting grounds are not ready yet.");
            return;
        }
        assign(p, null, null);
    }

    /** Give the player a quest (admin may fix animal/rarity). */
    boolean assign(Player p, String animal, String rarity) {
        FarmData d = m.data(p);
        Map<String, String> areas = areas();
        List<String> keys = new ArrayList<>();
        for (String k : areas.keySet()) if (!spots(k).isEmpty()) keys.add(k);
        if (keys.isEmpty()) { Msg.error(p, "The hunting grounds are not ready yet."); return false; }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String a = animal != null ? animal : ANIMALS[r.nextInt(ANIMALS.length)];
        String ra = rarity != null ? rarity : RARITIES[pick(r.nextDouble())];
        String area = keys.get(r.nextInt(keys.size()));
        FarmData.TrapQuest q = new FarmData.TrapQuest();
        q.animal = a; q.rarity = ra; q.area = area; q.areaName = areas.get(area);
        q.started = System.currentTimeMillis();
        q.expires = q.started + Math.max(1, m.dials.trapperMinutes.get()) * 60_000L;
        d.quest = q;
        spawnQuarry(p, q);
        int ri = rarityIndex(ra);
        Msg.npc(p, FarmingModule.TRAPPER_NAME, List.of(
                "&fYou can find your " + RARITY_NAMES[ri] + " " + Text.titleCase(a) + " &fanimal near the &a" + q.areaName + "&f.",
                "&7It'll glow once you are close. You have &e" + m.dials.trapperMinutes.get() + " minutes&7."), null);
        return true;
    }

    static int pick(double u) {
        double total = 0;
        for (double w : WEIGHTS) total += w;
        double x = u * total;
        for (int i = 0; i < WEIGHTS.length; i++) { x -= WEIGHTS[i]; if (x < 0) return i; }
        return WEIGHTS.length - 1;
    }

    static int rarityIndex(String r) {
        for (int i = 0; i < RARITIES.length; i++) if (RARITIES[i].equals(r)) return i;
        return 0;
    }

    private void spawnQuarry(Player p, FarmData.TrapQuest q) {
        List<Location> sp = spots(q.area);
        if (sp.isEmpty()) return;
        Location at = sp.get(ThreadLocalRandom.current().nextInt(sp.size()));
        String id = "trapper_" + q.animal + "_" + q.rarity;
        if (Sky.mobs().def(id) == null) return;
        LivingEntity le = Sky.mobs().spawn(id, at, p);
        if (le == null) return;
        q.mobUuid = le.getUniqueId().toString();
        q.x = at.getX(); q.y = at.getY(); q.z = at.getZ();
    }

    private void respawnAt(Player p, FarmData.TrapQuest q) {
        org.bukkit.World w = Sky.worlds().world(ISLAND);
        String id = "trapper_" + q.animal + "_" + q.rarity;
        if (w == null || Sky.mobs().def(id) == null) return;
        LivingEntity le = Sky.mobs().spawn(id, new Location(w, q.x, q.y, q.z), p);
        if (le != null) q.mobUuid = le.getUniqueId().toString();
    }

    /** Pelts the quarry pays (its MobDef), so the menu never promises a different number than the kill pays. */
    static long peltsFor(String animal, String rarity) {
        MobDef d = Sky.mobs().def("trapper_" + animal + "_" + rarity);
        return d != null && d.extra != null && d.extra.has("pelts") ? d.extra.get("pelts").getAsLong() : 5 + 5L * rarityIndex(rarity);
    }

    private LivingEntity quarry(FarmData.TrapQuest q) {
        if (q == null || q.mobUuid == null) return null;
        try {
            Entity e = Bukkit.getEntity(UUID.fromString(q.mobUuid));
            return e instanceof LivingEntity le && le.isValid() ? le : null;
        } catch (IllegalArgumentException ex) { return null; }
    }

    void abandon(Player p) {
        FarmData d = m.data(p);
        if (d.quest == null) return;
        LivingEntity le = quarry(d.quest);
        if (le != null) Sky.mobs().remove(le);
        d.quest = null;
        d.trapperCooldownUntil = System.currentTimeMillis() + m.dials.trapperCooldown.get() * 1000L;
        Msg.send(p, "&cYou abandoned your trapper quest.");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Sky.profiles().isReady(p)) continue;
            FarmData d = m.data(p);
            FarmData.TrapQuest q = d.quest;
            if (q == null) continue;
            if (now > q.expires) {
                LivingEntity le = quarry(q);
                if (le != null) Sky.mobs().remove(le);
                d.quest = null;
                d.trapperCooldownUntil = now + m.dials.trapperCooldown.get() * 1000L;
                Msg.send(p, "&cYour quarry got away! &7Talk to " + FarmingModule.TRAPPER_NAME + " &7for another.");
                continue;
            }
            if (!ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) continue;
            LivingEntity le = quarry(q);
            if (le == null) {
                // chunk unloaded (sky mobs are not persistent) or never spawned: put it back where it was seen, but
                // only once the hunter is near - spawning into an unloaded chunk would load it, lose the mob again
                // when it unloads, and repeat every 2 s.
                if (q.mobUuid == null && q.x == 0 && q.z == 0) { spawnQuarry(p, q); continue; }
                double dx = q.x - p.getLocation().getX(), dz = q.z - p.getLocation().getZ();
                if (dx * dx + dz * dz <= 96 * 96) respawnAt(p, q);
                continue;
            }
            double dist = le.getLocation().distance(p.getLocation());
            boolean near = dist <= m.dials.trapperGlowRange.get();
            if (le.isGlowing() != near) le.setGlowing(near);
            if (near) le.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, le.getLocation().add(0, 1.2, 0), 4, 0.3, 0.3, 0.3, 0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null || !Sky.profiles().isReady(e.killer)) return;
        MobDef def = e.def;
        if (def.extra == null || !def.extra.has("trapper")) return;
        FarmData d = m.data(e.killer);
        FarmData.TrapQuest q = d.quest;
        if (q == null || q.mobUuid == null || !q.mobUuid.equals(e.mob.getUniqueId().toString())) return;
        long base = def.extra.has("pelts") ? def.extra.get("pelts").getAsLong() : 5;
        double xp = def.extra.has("farmingXp") ? def.extra.get("farmingXp").getAsDouble() : 0;
        Player k = e.killer;
        // research 06: Tracker's Plushie +1, Pelt Belt +1, Trapper Crest x1.1 (wave 5; accessories/equipment hooked to us)
        boolean plushie = Perks.has(k, "JAKE_PLUSHIE"), belt = Perks.wears(k, "PELT_BELT"), crest = Perks.has(k, "TRAPPER_CREST");
        int flat = (plushie ? Math.max(0, m.dials.plushiePelts.get()) : 0) + (belt ? Math.max(0, m.dials.beltPelts.get()) : 0);
        long pelts = PerkMath.pelts(base, flat, crest ? m.dials.crestMultiplier.get() : 1.0);
        d.pelts += pelts;
        d.peltsEarned += pelts;
        if (crest) crestProgress(k, d, pelts);
        d.trapsByRarity.merge(q.rarity, 1, Integer::sum);
        d.quest = null;
        d.trapperCooldownUntil = System.currentTimeMillis() + m.dials.trapperCooldown.get() * 1000L;
        if (xp > 0) Sky.service(SkillService.class).addXp(e.killer, SkillType.FARMING, xp * m.dials.xpMultiplier.get(), "farming:trapper");
        int ri = rarityIndex(q.rarity);
        Msg.send(e.killer, "&e[NPC] " + FarmingModule.TRAPPER_NAME + "&f: &bKilling the animal rewarded you &5" + pelts + " pelts&b."
                + (pelts > base ? " &7(" + base + " + gear bonus)" : ""));
        Msg.send(e.killer, "&7Your new total is &5" + Text.commasInt(d.pelts) + " pelts&7. (" + RARITY_NAMES[ri] + " " + Text.titleCase(q.animal) + "&7)");
        Msg.sound(e.killer, Sound.ENTITY_PLAYER_LEVELUP, 1.8f);
    }

    // ---- Trapper Crest (research 06: "Earn x1.1 Pelts ... Collect 100 Pelts to upgrade this accessory to UNCOMMON") ----

    static final String CREST = "TRAPPER_CREST";
    static final String K_CREST_UPGRADED = "crest_upgraded";

    /** Count pelts toward the crest's upgrade and upgrade a crest in the inventory once the count is reached. */
    void crestProgress(Player p, FarmData d, long pelts) {
        d.crestPelts += pelts;
        int need = Math.max(1, m.dials.crestUpgradePelts.get());
        if (d.crestPelts < need) return;
        ItemStack crest = upgradableCrest(p);
        if (crest != null) upgradeCrest(p, d, crest);
        else if (!d.crestReadyTold && !crestUpgradedAnywhere(p)) {
            d.crestReadyTold = true;
            Msg.send(p, "&aYour &fTrapper Crest &ahas collected " + need + " pelts! &7Carry it in your inventory and open &e/trapper &7to upgrade it.");
        }
    }

    /** A crest in the inventory that has not been upgraded yet (and is not already a rarity up), or null. */
    static ItemStack upgradableCrest(Player p) {
        for (ItemStack s : p.getInventory().getContents()) {
            if (s == null || !CREST.equals(Sky.items().idOf(s))) continue;
            com.skyclone.api.item.ItemData d = Sky.items().data(s);
            if (d.extra.has(K_CREST_UPGRADED) || d.recombobulated) continue;
            return s;
        }
        return null;
    }

    private static boolean crestUpgradedAnywhere(Player p) {
        for (ItemStack s : p.getInventory().getContents())
            if (s != null && CREST.equals(Sky.items().idOf(s)) && Sky.items().data(s).extra.has(K_CREST_UPGRADED)) return true;
        return false;
    }

    /**
     * Raises the crest one rarity. Core's only per-instance rarity step is the recombobulator flag, so the upgrade sets
     * it (and marks the stack): an upgraded crest therefore cannot ALSO be recombobulated - the reference allows both
     * (Common -> Uncommon -> Rare). Core request: an ItemData rarity-upgrades count.
     */
    boolean upgradeCrest(Player p, FarmData d, ItemStack crest) {
        int need = Math.max(1, m.dials.crestUpgradePelts.get());
        if (d.crestPelts < need || crest == null) return false;
        long pelts = d.crestPelts;
        Sky.items().update(crest, data -> {
            data.recombobulated = true;
            data.extra.addProperty(K_CREST_UPGRADED, true);
            data.extra.addProperty("crest_pelts", pelts);
        });
        Msg.send(p, "&a&lCREST UPGRADED! &7Your &fTrapper Crest &7is now " + Sky.items().rarity(crest).code() + Sky.items().rarity(crest).name() + "&7.");
        Msg.sound(p, Sound.BLOCK_ANVIL_USE, 1.4f);
        return true;
    }

    /** Lore on the crest itself: the count it was upgraded at. */
    static final class CrestLore implements com.skyclone.api.item.LoreContributor {
        @Override public Section section() { return Section.BEFORE_FOOTER; }
        @Override public List<String> lines(com.skyclone.api.item.ItemDef def, com.skyclone.api.item.ItemData data) {
            if (def == null || !CREST.equals(def.id) || data == null || !data.extra.has(K_CREST_UPGRADED)) return List.of();
            long n = data.extra.has("crest_pelts") ? data.extra.get("crest_pelts").getAsLong() : 0;
            return List.of("&aUpgraded by the trapper &8(" + Text.commasInt(n) + " pelts)");
        }
    }

    // ---- theodolite ---------------------------------------------------------------------------------

    private boolean theodolite(Player p) {
        if (!Sky.profiles().isReady(p)) return false;
        FarmData.TrapQuest q = m.data(p).quest;
        LivingEntity le = quarry(q);
        if (q == null) { Msg.error(p, "You are not hunting anything. Talk to the trapper first."); return false; }
        if (le == null || !le.getWorld().equals(p.getWorld())) {
            Msg.send(p, "&eYour quarry was last seen near the &a" + q.areaName + "&e.");
            return true;
        }
        Location a = p.getLocation(), b = le.getLocation();
        double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        int dy = (int) Math.round(b.getY() - a.getY());
        String dir = compass(dx, dz);
        Msg.send(p, "&aYour quarry is &e" + dist + " blocks &a" + dir + "&a, "
                + (dy == 0 ? "level with you" : Math.abs(dy) + " blocks " + (dy > 0 ? "above" : "below") + " you") + ".");
        p.getWorld().spawnParticle(Particle.END_ROD, a.clone().add(dx / Math.max(1, dist) * 2, 1.6, dz / Math.max(1, dist) * 2), 6, 0.05, 0.05, 0.05, 0);
        return true;
    }

    static String compass(double dx, double dz) {
        double ang = Math.toDegrees(Math.atan2(dx, -dz));     // 0 = north, 90 = east
        String[] n = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return n[(int) Math.floorMod(Math.round(ang / 45.0), 8)];
    }

    // ---- menus --------------------------------------------------------------------------------------

    void openQuest(Player p) {
        FarmData d = m.data(p);
        Menu menu = new Menu("Trapper", 4).chrome();
        FarmData.TrapQuest q = d.quest;
        if (q != null) {
            int ri = rarityIndex(q.rarity);
            menu.set(13, Icons.of(Material.LEAD, "&aCurrent Quarry", List.of(
                    "&7Animal: " + RARITY_NAMES[ri] + " " + Text.titleCase(q.animal),
                    "&7Seen near: &a" + q.areaName,
                    "&7Time left: &e" + Text.duration(Math.max(0, q.expires - System.currentTimeMillis()) / 1000),
                    "&7Reward: &5" + peltsFor(q.animal, q.rarity) + " pelts",
                    "", "&7It glows once you are within", "&a" + m.dials.trapperGlowRange.get() + " blocks&7.")));
            menu.set(31, Icons.of(Material.RED_STAINED_GLASS_PANE, "&cAbandon Quest", "&7Give up this quarry.", "", "&eClick to abandon!"),
                    c -> Sky.menus().confirm(c.player(), "Abandon quest?", List.of("&7You will have to wait before", "&7the next one."),
                            () -> abandon(c.player()), null));
        } else {
            long left = Math.max(0, d.trapperCooldownUntil - System.currentTimeMillis());
            boolean remote = canCall(p);
            List<String> lore = new ArrayList<>();
            if (left > 0) lore.add("&7Next quest in &e" + Text.duration(left / 1000 + 1));
            else if (remote) { lore.add("&7" + FarmingModule.TRAPPER_NAME + " &7will tell you where"); lore.add("&7an animal was seen."); lore.add(""); lore.add("&eClick to take a quest!"); }
            else { lore.add("&7Talk to " + FarmingModule.TRAPPER_NAME + " &7in the"); lore.add("&7Trapper's Den of the Mushroom Desert,"); lore.add("&7or add him to your phone's contacts."); }
            menu.set(13, Icons.of(Material.LEAD, "&aNo active quest", lore), c -> {
                if (left > 0 || !canCall(c.player())) { Msg.sound(c.player(), Sound.ENTITY_VILLAGER_NO, 1f); return; }
                c.player().closeInventory();
                talk(c.player());
            });
        }
        menu.set(22, crestIcon(p, d), c -> {
            ItemStack crest = upgradableCrest(c.player());
            if (crest != null && upgradeCrest(c.player(), m.data(c.player()), crest)) openQuest(c.player());
            else Msg.sound(c.player(), Sound.ENTITY_VILLAGER_NO, 1f);
        });
        menu.set(11, Icons.of(Material.RABBIT_HIDE, "&5Pelts: " + Text.commasInt(d.pelts), List.of("&7Earned all time: &5" + Text.commasInt(d.peltsEarned),
                "", "&7Spend them with the Surveyor and", "&7the Fur-trader next door.")));
        List<String> stats = new ArrayList<>();
        for (int i = 0; i < RARITIES.length; i++) stats.add(RARITY_NAMES[i] + "&7: &e" + d.trapsByRarity.getOrDefault(RARITIES[i], 0));
        menu.set(15, Icons.of(Material.BOOK, "&aHunting Log", stats));
        menu.set(30, Icons.back("Farming"), c -> m.menus.openMain(c.player()));
        menu.open(p);
    }

    /**
     * A quest away from the Den: the reference lets you CALL the trapper (the extras module's phone runs /trapper for
     * that contact). On the desert itself he is a short walk away, so the menu may assign one there too.
     */
    boolean canCall(Player p) {
        if (ISLAND.equals(Sky.worlds().islandOf(p.getWorld())) || !m.dials.trapperCallNeedsContact.get()) return true;
        if (!Sky.has(com.skyclone.api.feature.Features.Abiphone.class)) return false;
        com.skyclone.api.feature.Features.Abiphone ph = Sky.service(com.skyclone.api.feature.Features.Abiphone.class);
        try { return ph.hasContact(p, "farming_trapper") || ph.hasContact(p, "trevor"); } catch (RuntimeException e) { return false; }
    }

    private org.bukkit.inventory.ItemStack crestIcon(Player p, FarmData d) {
        int need = Math.max(1, m.dials.crestUpgradePelts.get());
        List<String> lore = new ArrayList<>();
        lore.add("&7With a &fTrapper Crest &7you earn");
        lore.add("&ax" + m.dials.crestMultiplier.get() + " &7pelts from every quest.");
        lore.add("");
        lore.add("&7Collected with it: &5" + Text.commasInt(Math.min(d.crestPelts, need)) + "&7/&5" + need + " pelts");
        lore.add(Text.bar(Math.min(1.0, d.crestPelts / (double) need), 20));
        lore.add("");
        if (crestUpgradedAnywhere(p)) lore.add("&aYour crest is upgraded!");
        else if (d.crestPelts >= need && upgradableCrest(p) != null) lore.add("&eClick to upgrade it to Uncommon!");
        else if (d.crestPelts >= need) lore.add("&7Carry the crest in your inventory to upgrade it.");
        else if (!Perks.has(p, CREST)) lore.add("&7You don't have a Trapper Crest.");
        else lore.add("&7Keep hunting to upgrade it.");
        lore.add("");
        lore.add("&7Pelt bonuses now: " + (Perks.has(p, "JAKE_PLUSHIE") ? "&aPlushie +" + m.dials.plushiePelts.get() + " " : "")
                + (Perks.wears(p, "PELT_BELT") ? "&aBelt +" + m.dials.beltPelts.get() + " " : "")
                + (Perks.has(p, CREST) ? "&aCrest x" + m.dials.crestMultiplier.get() : "")
                + (!Perks.has(p, "JAKE_PLUSHIE") && !Perks.wears(p, "PELT_BELT") && !Perks.has(p, CREST) ? "&8none" : ""));
        return Icons.of(Material.SHIELD, "&aTrapper Crest", lore);
    }

    List<String> sidebar(Player p) {
        if (!Sky.profiles().isReady(p) || !ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) return List.of();
        FarmData.TrapQuest q = m.data(p).quest;
        if (q == null) return List.of();
        int ri = rarityIndex(q.rarity);
        return List.of("&aTrapper: " + RARITY_NAMES[ri] + " " + Text.titleCase(q.animal), "&7⏣ " + q.areaName);
    }
}
