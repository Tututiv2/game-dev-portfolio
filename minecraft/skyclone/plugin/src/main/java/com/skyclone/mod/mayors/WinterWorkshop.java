package com.skyclone.mod.mayors;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Winter Workshop island and the Season of Gerald (research/05 §7.7): on Late Winter 24 lava
 * raiders attack the workshop in five rounds. Gift Thieves (magma cubes) only take damage from
 * Packed Snowballs and steal presents if they reach the door. After each round everyone who dealt
 * damage is paid in gifts by rank (top: Red, top quarter: Green, the rest: White) plus North Stars.
 * Gifts are opened by giving them to another player (both get the contents); sneak-right-click opens
 * one alone when {@code mayors.gifts.self-open} is on.
 */
final class WinterWorkshop implements Listener {

    static final String ISLAND = "jerrys_workshop";

    enum Phase { IDLE, WAITING, ROUND, BREAK }

    private final MayorsModule mod;
    private Phase phase = Phase.IDLE;
    private int round;              // 1-based while running
    private long phaseEnds;
    private int stolen;
    private final Set<UUID> live = new HashSet<>();
    private final Map<UUID, Double> damage = new HashMap<>();
    private int lastSeasonYear = Integer.MIN_VALUE;
    /** This round's attackers, split into waves (SiegeRules.split); {@code wave} = how many have marched in. */
    private List<Map<String, Integer>> waves = new ArrayList<>();
    private int wave, lastWaveSize;
    private long nextWaveAt;
    private double roundHp = 1;
    /** True only inside a snowball hit, so Gift Thieves accept exactly that damage. */
    static boolean snowballHit;

    WinterWorkshop(MayorsModule mod) { this.mod = mod; }

    void register() {
        IslandDef def = IslandDef.of(ISLAND, "Winter Workshop", new WinterWorkshopGenerator());
        Sky.worlds().registerIsland(def);
        Sky.worlds().registerWarp(ISLAND, ISLAND, "spawn", "Winter Workshop", "mayors:workshop");
        Sky.worlds().registerWarp("winter", ISLAND, "spawn", "Winter Workshop", "mayors:workshop");
        Sky.mobs().registerBehaviour("mayors_siege", new SiegeBehaviour());
        Sky.mobs().registerBehaviour("mayors_snow_only", new MobBehaviour() {
            @Override public void onDamaged(LivingEntity mob, MobDef d, DamageContext ctx) { if (!snowballHit) ctx.cancelled = true; }
        });
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
    }

    boolean open() { return mod.dials().workshopAlwaysOpen.get() || Cal.active(Cal.WORKSHOP) || Cal.active(Cal.SEASON_OF_JERRY); }

    Phase phase() { return phase; }
    int round() { return round; }

    // ---- siege -----------------------------------------------------------------------------------

    void tick() {
        int year = Sky.calendar().now().year();
        if (phase == Phase.IDLE && Cal.active(Cal.SEASON_OF_JERRY) && lastSeasonYear != year) startSiege();
        if (phase == Phase.IDLE) return;
        live.removeIf(u -> { var e = Bukkit.getEntity(u); return !(e instanceof LivingEntity le) || !le.isValid(); });
        long now = System.currentTimeMillis();
        switch (phase) {
            case WAITING -> {
                if (!playersOnIsland().isEmpty()) beginRound(1);
                else if (!Cal.active(Cal.SEASON_OF_JERRY) && now > phaseEnds) stopSiege("Nobody came to defend the workshop.");
            }
            case ROUND -> {
                if (now > phaseEnds) { endRound(); break; }
                if (wave < waves.size()) {
                    if (SiegeRules.nextWaveDue(live.size(), lastWaveSize, now, nextWaveAt)) spawnWave();
                } else if (live.isEmpty()) {
                    endRound();
                }
            }
            case BREAK -> {
                if (now > phaseEnds) beginRound(round + 1);
            }
            default -> { }
        }
    }

    String startSiege() {
        if (phase != Phase.IDLE) return "a siege is already running";
        lastSeasonYear = Sky.calendar().now().year();
        phase = Phase.WAITING;
        round = 0;
        phaseEnds = System.currentTimeMillis() + 5 * 60_000L;
        damage.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!EventAlerts.news(p, Cal.SEASON_OF_JERRY)) continue;
            Msg.send(p, "&c&lSEASON OF GERALD! &7Lava raiders are marching on the &bWinter Workshop&7!");
            Msg.send(p, "&7Defend it for &cRed&7, &aGreen &7and &fWhite Gifts&7: &a/warp jerrys_workshop &7or &a/winter&7.");
            Msg.sound(p, Sound.EVENT_RAID_HORN, 1f);
        }
        return null;
    }

    void stopSiege(String why) {
        for (UUID u : live) { var e = Bukkit.getEntity(u); if (e instanceof LivingEntity le) Sky.mobs().remove(le); }
        live.clear();
        phase = Phase.IDLE;
        if (why != null) tellIsland("&b" + why);
    }

    private List<Player> playersOnIsland() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) out.add(p);
        return out;
    }

    private void tellIsland(String msg) { for (Player p : playersOnIsland()) Msg.send(p, msg); }

    private void beginRound(int r) {
        EventData.Siege s = mod.data().winter.siege;
        if (r > s.rounds || r > s.waves.size()) { finish(); return; }
        round = r;
        phase = Phase.ROUND;
        phaseEnds = System.currentTimeMillis() + mod.dials().siegeRoundSeconds.get() * 1000L;
        stolen = 0;
        damage.clear();
        roundHp = (1 + s.hpPerRound * (r - 1)) * Math.max(0.01, mod.dials().siegeHpScale.get());
        Set<String> bosses = new HashSet<>();
        for (String id : s.waves.get(r - 1).keySet()) {
            MobDef d = Sky.mobs().def(id);
            if (d != null && d.has("boss")) bosses.add(id);
        }
        waves = SiegeRules.split(s.waves.get(r - 1), mod.dials().siegeWaves.get(), bosses);
        wave = 0;
        int total = 0;
        for (Map<String, Integer> w : waves) for (int n : w.values()) total += n;
        if (Sky.worlds().anchors(ISLAND, "siege.spawn.").isEmpty() && Sky.worlds().anchor(ISLAND, "spawn") == null) {
            stopSiege("The raiders could not find the island.");
            return;
        }
        for (Player p : playersOnIsland()) {
            Msg.send(p, "&c&lROUND " + r + "/" + s.rounds + "! &7" + total + " raiders march on the workshop in &e" + waves.size()
                    + " waves&7. Stop the &cGift Thieves &7with &fPacked Snowballs&7!");
            Msg.sound(p, Sound.EVENT_RAID_HORN, 1.2f);
        }
        spawnWave();
    }

    /** March the next wave of this round in at the siege spawns. */
    private void spawnWave() {
        if (wave >= waves.size()) return;
        Map<String, Integer> w = waves.get(wave++);
        List<Location> spawns = Sky.worlds().anchors(ISLAND, "siege.spawn.");
        if (spawns.isEmpty()) { Location sp = Sky.worlds().anchor(ISLAND, "spawn"); if (sp != null) spawns = List.of(sp); }
        if (spawns.isEmpty()) return;
        int i = ThreadLocalRandom.current().nextInt(spawns.size()), made = 0;
        boolean boss = false;
        for (Map.Entry<String, Integer> e : w.entrySet()) {
            for (int n = 0; n < e.getValue(); n++) {
                Location at = spawns.get(i++ % spawns.size()).clone().add(ThreadLocalRandom.current().nextDouble(-2, 2), 0, ThreadLocalRandom.current().nextDouble(-2, 2));
                try {
                    LivingEntity le = Sky.mobs().spawn(e.getKey(), at);
                    if (le == null) continue;
                    MobDef d = Sky.mobs().defOf(le);
                    if (d != null && !d.extra.has("hits") && Math.abs(roundHp - 1) > 1e-9) Sky.mobs().setMaxHealth(le, Sky.mobs().maxHealth(le) * roundHp);
                    if (d != null && d.has("boss")) boss = true;
                    live.add(le.getUniqueId());
                    made++;
                } catch (Throwable t) {
                    Sky.log().warning("[mayors] siege could not spawn " + e.getKey() + ": " + t);
                }
            }
        }
        lastWaveSize = made;
        nextWaveAt = System.currentTimeMillis() + Math.max(5, mod.dials().siegeWaveGap.get()) * 1000L;
        if (wave > 1 || boss) {
            for (Player p : playersOnIsland()) {
                Msg.send(p, (boss ? "&4&lTHE LAVA BEHEMOTH APPROACHES! " : "&cWave " + wave + "/" + waves.size() + "! ") + "&7" + made + " more raiders are coming.");
                Msg.sound(p, boss ? Sound.ENTITY_RAVAGER_ROAR : Sound.EVENT_RAID_HORN, boss ? 0.8f : 1.4f);
            }
        }
    }

    private void endRound() {
        EventData.Siege s = mod.data().winter.siege;
        for (UUID u : live) { var e = Bukkit.getEntity(u); if (e instanceof LivingEntity le) Sky.mobs().remove(le); }
        live.clear();
        List<Map.Entry<UUID, Double>> ranked = new ArrayList<>(damage.entrySet());
        ranked.removeIf(e -> e.getValue() <= 0);
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        int greens = (int) Math.ceil(ranked.size() * s.greenShare);
        boolean noRed = stolen >= s.stolenForNoRed;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < ranked.size(); i++) {
            UUID u = ranked.get(i).getKey();
            String gift = i < s.redTop && !noRed ? "RED_GIFT" : i < s.redTop + greens ? "GREEN_GIFT" : "WHITE_GIFT";
            Player p = Bukkit.getPlayer(u);
            int count = 1;
            if (p != null) {
                gift = SiegeRules.upgradeToRed(gift, noRed, mod.carnival().perks().value(p, "red_fad"), rnd.nextDouble());
                count = SiegeRules.giftCount(giftBonus(p), rnd.nextDouble());
            }
            Claims.give(u, gift, count, null, "siege round " + round);
            if (s.northStarsPerRound > 0) Claims.give(u, "NORTH_STAR", s.northStarsPerRound, null, "siege round " + round);
            if (p != null) {
                PlayerData pd = mod.data(p);
                if (pd != null) pd.siegeRounds++;
                Msg.send(p, "&a&lROUND " + round + " DEFENDED! &7You placed &e#" + (i + 1) + " &7(" + Text.commasInt(ranked.get(i).getValue())
                        + " damage) and earned " + (count > 1 ? "&e" + count + "x " : "a ") + Goods.displayName(gift) + "&7!");
            }
        }
        if (stolen > 0) tellIsland("&c" + stolen + " gift" + (stolen == 1 ? " was" : "s were") + " stolen this round" + (noRed ? ": no Red Gifts!" : "."));
        if (round >= s.rounds) { finish(); return; }
        phase = Phase.BREAK;
        phaseEnds = System.currentTimeMillis() + mod.dials().siegeBreakSeconds.get() * 1000L;
        tellIsland("&7Next round in &e" + mod.dials().siegeBreakSeconds.get() + "s&7. Stock up on snowballs from &aSaint Gerald&7!");
    }

    /** Extra-gift chance in %: Present Connoisseur (+2 a tier), the Snow set (+5 a piece), a Reindeer's Gift Sense. */
    double giftBonus(Player p) {
        double b = mod.carnival().perks().value(p, "present_connoissuer");
        b += 5 * snowPieces(p);
        try {
            Features.Pets pets = Sky.service(Features.Pets.class);
            if ("REINDEER".equals(pets.activeType(p))) b += Math.max(0, pets.abilityValue(p, "reindeer_snow_power", "a"));
        } catch (Throwable ignored) { }
        return b;
    }

    static final String[] SNOW_SET = {"SNOW_NECKLACE", "SNOW_CLOAK", "SNOW_BELT", "SNOW_GLOVES"};

    static int snowPieces(Player p) {
        Set<String> worn = new HashSet<>();
        try {
            for (ItemStack it : Sky.service(Features.Equipment.class).worn(p)) {
                String id = it == null ? null : Sky.items().idOf(it);
                if (id != null) worn.add(id);
            }
        } catch (Throwable ignored) { }
        int n = 0;
        for (String id : SNOW_SET) if (worn.contains(id)) n++;
        return n;
    }

    /** Gifts opened: the best gift talisman's bonus to coins and skill XP (White 5% .. Gold 25%). */
    static double giftTalisman(Player p) {
        String[][] t = {{"GOLD_GIFT_TALISMAN", "25"}, {"PURPLE_GIFT_TALISMAN", "20"}, {"BLUE_GIFT_TALISMAN", "15"},
                {"GREEN_GIFT_TALISMAN", "10"}, {"WHITE_GIFT_TALISMAN", "5"}};
        try {
            Features.Accessories a = Sky.service(Features.Accessories.class);
            for (String[] x : t) if (a.has(p, x[0])) return Double.parseDouble(x[1]);
        } catch (Throwable ignored) { }
        return 0;
    }

    private static final SkillType[] GIFT_SKILLS = {SkillType.FARMING, SkillType.MINING, SkillType.COMBAT, SkillType.FORAGING, SkillType.FISHING};

    private void finish() {
        phase = Phase.IDLE;
        for (Player p : Bukkit.getOnlinePlayers()) Msg.send(p, "&b&lTHE WORKSHOP IS SAFE! &7The Season of Gerald siege is over. Happy gifting!");
    }

    void skip() { if (phase == Phase.ROUND) phaseEnds = 0; else if (phase == Phase.BREAK || phase == Phase.WAITING) { phaseEnds = 0; if (phase == Phase.WAITING) beginRound(1); } }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(SkyEvents.Damage e) {
        if (phase != Phase.ROUND) return;
        Player p = e.ctx.playerAttacker();
        if (p == null || !live.contains(e.ctx.victim.getUniqueId())) return;
        double dealt = e.ctx.finalDamage;
        MobDef d = Sky.mobs().defOf(e.ctx.victim);
        if (d != null && d.extra.has("hits")) dealt = 1000;   // a snowball on a thief is worth a lot of contribution
        damage.merge(p.getUniqueId(), Math.max(0, dealt), Double::sum);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSnowball(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Snowball) || !(e.getEntity().getShooter() instanceof Player p)) return;
        if (!(e.getHitEntity() instanceof LivingEntity le) || !Sky.mobs().isSkyMob(le)) return;
        MobDef d = Sky.mobs().defOf(le);
        if (d == null || !d.has("siege")) return;
        snowballHit = true;
        try { Sky.combat().rawDamage(le, d.extra.has("hits") ? 1 : Math.max(1, d.health * 0.02), p, DamageContext.Kind.PROJECTILE); }
        finally { snowballHit = false; }
    }

    /** Raiders walk to the workshop door unless they are fighting someone; thieves steal on arrival. */
    final class SiegeBehaviour implements MobBehaviour {
        @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
            Location target = Sky.worlds().anchor(ISLAND, "siege.target");
            if (target == null || target.getWorld() != mob.getWorld()) return;
            double d2 = mob.getLocation().distanceSquared(target);
            if (def.extra.has("thief")) {
                if (d2 < 9) {
                    stolen++;
                    live.remove(mob.getUniqueId());
                    Sky.mobs().remove(mob);
                    tellIsland("&c&lGIFT STOLEN! &7A Gift Thief escaped with a present!");
                    return;
                }
                if (mob.isOnGround() && tick % 2 == 0) {
                    Vector v = target.toVector().subtract(mob.getLocation().toVector()).setY(0);
                    if (v.lengthSquared() > 0.01) mob.setVelocity(v.normalize().multiply(0.35).setY(0.42));
                }
                return;
            }
            if (mob instanceof Mob m && (m.getTarget() == null || !m.getTarget().isValid())) {
                if (d2 > 9) m.getPathfinder().moveTo(target, 1.0);
            }
        }
    }

    // ---- gifts -------------------------------------------------------------------------------------

    private static boolean isGift(String id) { return "WHITE_GIFT".equals(id) || "GREEN_GIFT".equals(id) || "RED_GIFT".equals(id); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGiveGift(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Player other)) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(held);
        if (!isGift(id)) return;
        e.setCancelled(true);
        if (!Sky.profiles().isReady(other) || other.equals(p)) return;
        if (Sky.items().remove(p, id, 1) < 1) return;
        openGift(other, id, p);
        openGift(p, id, null);
        PlayerData pd = mod.data(p);
        if (pd != null) pd.giftsGiven++;
        Msg.send(p, "&aYou shared a " + Goods.displayName(id) + " &awith &e" + other.getName() + "&a!");
        Msg.send(other, "&e" + p.getName() + " &ashared a " + Goods.displayName(id) + " &awith you!");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        String id = Sky.items().idOf(p.getInventory().getItemInMainHand());
        if (!isGift(id)) return;
        e.setCancelled(true);
        if (!p.isSneaking() || !mod.dials().giftSelfOpen.get()) {
            Msg.send(p, "&7Right-click another player to share this gift" + (mod.dials().giftSelfOpen.get() ? ", or &esneak-right-click &7to open it alone." : "."));
            return;
        }
        if (Sky.items().remove(p, id, 1) < 1) return;
        openGift(p, id, null);
    }

    void openGift(Player p, String giftId, Player from) {
        EventData.Bag bag = mod.data().gifts.get(giftId);
        if (bag == null) return;
        int copies = mod.election().perkActive("foxy.sweet_benevolence") && ThreadLocalRandom.current().nextDouble() < mod.election().perkValue("foxy.sweet_benevolence") / 100.0 ? 2 : 1;
        int rolledCoins = 0, rolledXp = 0, stars = 0;
        List<String> items = new ArrayList<>();
        for (int c = 0; c < copies; c++) {
            rolledCoins += Goods.between(bag.coins);
            rolledXp += Goods.between(bag.skillXp);
            stars += Goods.between(bag.northStars);
            items.addAll(Goods.roll(bag.drops, 1.0));
        }
        double talisman = giftTalisman(p), gifter = mod.carnival().perks().value(p, "professional_gifter");
        long coins = SiegeRules.giftScaled(rolledCoins, talisman, gifter);
        long xp = SiegeRules.giftScaled(rolledXp, talisman, gifter);
        SkillType skill = GIFT_SKILLS[ThreadLocalRandom.current().nextInt(GIFT_SKILLS.length)];
        if (xp > 0) {
            try { Sky.service(SkillService.class).addXp(p, skill, xp, "gift"); } catch (Throwable ignored) { }
        }
        if (coins > 0) Sky.economy().deposit(p, coins, "mayors: opened " + giftId);
        if (stars > 0) Sky.loot().give(p, "NORTH_STAR", stars, LootSource.EVENT);
        for (String it : items) Goods.give(p, it, 1, LootSource.EVENT);
        PlayerData pd = mod.data(p);
        if (pd != null) pd.giftsOpened++;
        StringBuilder sb = new StringBuilder(Goods.displayName(giftId) + "&7: &6+" + Text.commasInt(coins) + " coins");
        if (xp > 0) sb.append("&7, &3+").append(Text.commasInt(xp)).append(" ").append(skill.display).append(" XP");
        if (stars > 0) sb.append("&7, &d+").append(stars).append(" North Star").append(stars == 1 ? "" : "s");
        for (String it : items) sb.append("&7, ").append(Goods.displayName(it));
        if (copies > 1) sb.append(" &d(Sugar Rush: doubled!)");
        Msg.send(p, sb.toString());
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.8f);
    }

    // ---- menus ---------------------------------------------------------------------------------------

    /** /winter : Saint Gerald. */
    void open(Player p) {
        Menu m = new Menu("Winter Workshop", 4).chrome();
        List<String> info = new ArrayList<>();
        info.add("&7Every &eLate Winter 24th &7lava raiders");
        info.add("&7attack the workshop in &c5 rounds&7.");
        info.add("&7Deal damage to earn &fWhite&7, &aGreen &7and");
        info.add("&cRed Gifts&7, plus &dNorth Stars&7.");
        info.add("");
        switch (phase) {
            case IDLE -> info.add("&7Next siege: " + Cal.when(Cal.SEASON_OF_JERRY));
            case WAITING -> info.add("&eRaiders gather: get to the island!");
            case ROUND -> info.add("&cRound " + round + " is on! &7Raiders left: &c" + live.size());
            case BREAK -> info.add("&aBreak: &7round " + (round + 1) + " in &e" + Math.max(0, (phaseEnds - System.currentTimeMillis()) / 1000) + "s");
        }
        m.set(4, Icons.of(Material.LAVA_BUCKET, "&cSeason of Gerald", info));
        boolean here = ISLAND.equals(Sky.worlds().islandOf(p.getWorld()));
        m.set(11, Icons.of(Material.SNOWBALL, "&fPacked Snowballs", "&7The only thing a &cGift Thief &7fears.", "",
                phase != Phase.IDLE ? "&eClick to take 16!" : "&7Handed out during the siege."), c -> {
            if (phase == Phase.IDLE) { Msg.error(c.player(), "Snowballs are only handed out during the siege."); return; }
            if (Sky.items().count(c.player(), "SIEGE_SNOWBALL") >= 64) { Msg.error(c.player(), "You have plenty already!"); return; }
            Sky.loot().give(c.player(), "SIEGE_SNOWBALL", 16, LootSource.EVENT);
        });
        m.set(13, Icons.of(Material.ENDER_PEARL, here ? "&aBack to the Hub" : "&bTravel to the Winter Workshop",
                here ? "&eClick to warp!" : open() ? "&eClick to warp!" : "&cThe workshop opens in Late Winter."),
                c -> Sky.worlds().warp(c.player(), here ? "hub" : ISLAND));
        m.set(15, Icons.of(Material.NETHER_STAR, "&dIngvar's Emporium", "&7Spend &dNorth Stars &7on gift talismans,", "&7winter gear and books.", "", "&eClick to browse!"),
                c -> openShop(c.player(), "Ingvar's Emporium", mod.data().winter.einary));
        m.set(22, Icons.of(Material.PACKED_ICE, "&bMarnie's Showroom", "&7Winter bait, sinkers and sacks for coins.", "", "&eClick to browse!"),
                c -> openShop(c.player(), "Marnie's Showroom", mod.data().winter.sherry));
        PlayerData pd = mod.data(p);
        m.set(20, Icons.of(Material.PAPER, "&aGifts", "&7Gifts given: &e" + (pd == null ? 0 : pd.giftsGiven), "&7Gifts opened: &e" + (pd == null ? 0 : pd.giftsOpened),
                "&7Siege rounds defended: &e" + (pd == null ? 0 : pd.siegeRounds), "", "&7Hold a gift and right-click a player", "&7to open it together."));
        m.set(30, Icons.back("Festivals & Events"), c -> EventBoard.open(c.player()));
        m.open(p);
    }

    void openShop(Player p, String title, List<EventData.Offer> offers) {
        Menu m = new Menu(title, 5).chrome();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int i = 0;
        for (EventData.Offer o : offers) {
            if (!Goods.available(o) || i >= slots.length) continue;
            m.set(slots[i++], ShopUi.offerIcon(p, o, 1.0, null), c -> { ShopUi.buy(c.player(), o, 1.0, title); openShop(c.player(), title, offers); });
        }
        if (i == 0) m.set(22, Icons.of(Material.BARRIER, "&cNothing in stock", "&7None of this shop's goods exist on", "&7this server yet."));
        m.set(39, Icons.back("Winter Workshop"), c -> open(c.player()));
        m.open(p);
    }

    List<String> sidebar(Player p) {
        if (phase == Phase.IDLE || !ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))) return List.of();
        return switch (phase) {
            case ROUND -> List.of("&cWorkshop Siege", " &7Round &e" + round + " &7wave &e" + wave + "/" + waves.size() + " &7- &c" + live.size() + " &7left");
            case BREAK -> List.of("&cWorkshop Siege", " &7Break &e" + Math.max(0, (phaseEnds - System.currentTimeMillis()) / 1000) + "s");
            default -> List.of("&cWorkshop Siege", " &7Starting...");
        };
    }

    void shutdown() { stopSiege(null); }
}
