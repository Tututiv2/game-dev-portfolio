package com.skyclone.mod.kuudra;

import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * One hunt of the Magma Leviathan (reference: Kuudra), research 04 sec 4.4:
 * <ol>
 *   <li><b>SUPPLIES</b> - carry {@code supplies} crates from the cavern floor to the pads (crouch to pick up,
 *       crouch on a pad until the meter fills to set one down; a hit while channelling resets it).</li>
 *   <li><b>BUILD</b> - crouch beside the ballista to raise it to 100% (more builders = faster).</li>
 *   <li><b>BALLISTA</b> - fuel cells wash up on the shore and drop from the brood; load {@code fuelPerVolley}
 *       then right-click the ballista to fire. Burning and up: a volley barely scratches the beast unless it is
 *       STUNNED, which needs someone in its Gullet (ride the cannon, or get swallowed) breaking every organ.</li>
 *   <li><b>DPS</b> - at {@code dpsAtPercent} the beast beaches itself and every weapon works.</li>
 *   <li><b>LAIR</b> (Infernal) - at 25% the floor gives way; deaths there are permanent unless a Lifeline revives.</li>
 * </ol>
 * Brood waves arrive every {@code waveSeconds} throughout. {@link #tick()} runs every 5 server ticks.
 */
final class Run {

    enum Phase { PREPARING, STARTING, SUPPLIES, BUILD, BALLISTA, DPS, LAIR, VICTORY, FAILED, CLOSED }

    enum Carry { NONE, SUPPLY, FUEL }

    static final double DT = 0.25;

    /** One hunter's state for this hunt. */
    static final class PS {
        final UUID id;
        final String name;
        int tokens;
        final Map<String, Integer> perks = new LinkedHashMap<>();
        Carry carry = Carry.NONE;
        BlockDisplay carryDisplay;
        Crate crate;
        String chanKey;
        double chan;
        /** When the channel's target was lost (knocked off the pad, let go of crouch); 0 = not lost. */
        long chanLostAt;
        /** Last blow from the beast itself (spit, slam, rampage): interrupts channels and building. */
        long hurtAt;
        /** Last hit from anything else (brood, fire): only slows channels and building. */
        long staggerAt;
        int kills;
        double damage;
        int deaths;
        boolean secondWindUsed;
        boolean ghost;
        GameMode restore;
        Location marker;
        double revive;
        boolean inStomach;
        int earned;

        PS(Player p) { this.id = p.getUniqueId(); this.name = p.getName(); }

        int perk(String id) { return perks.getOrDefault(id, 0); }
    }

    static final class Crate { Location home; Location at; BlockDisplay display; TextDisplay label; boolean carried; }

    static final class Cell { Location at; BlockDisplay display; TextDisplay label; int spot = -1; }

    static final class Tele { Location at; double radius; long fireAt; double damage; String what; boolean knock; boolean ring; }

    final KuudraModule mod;
    final String id;
    final KCatalog.Tier tier;
    final KCatalog.Fight f;
    final Arena arena;
    final Map<UUID, PS> ps = new LinkedHashMap<>();
    final Random rng;
    final boolean admin;

    Phase phase = Phase.PREPARING;
    long started;
    long phaseAt;
    long ticks;
    int countdown = 5;

    LivingEntity boss;
    double bossMax;
    double lastHp;
    Location bossHome;
    int bossPos = -1;
    BossBar bar;

    final List<Crate> crates = new ArrayList<>();
    final List<Location> pads = new ArrayList<>();
    boolean[] padFull;
    int delivered;
    double build;
    int partsPlaced;

    int fuel;
    final List<Cell> cells = new ArrayList<>();
    long[] fuelSpotReady = new long[0];
    List<Location> fuelSpots = List.of();

    boolean stunned;
    long stunUntil;
    boolean stomach;
    long stomachEnds;
    List<Location> orbLocs = List.of();
    int[] orbHits = new int[0];
    boolean[] orbBroken = new boolean[0];

    int wave;
    long nextWave;
    final Set<UUID> mobs = new HashSet<>();
    final Set<UUID> dropships = new HashSet<>();
    /** Brood that never walk at you (landmines): they do not hold back the next wave. */
    final Set<UUID> idle = new HashSet<>();
    long nextSpit, nextSlam, nextDevour, nextMove, nextRampage, nextDigest;
    final List<Tele> teles = new ArrayList<>();
    final List<Entity> props = new ArrayList<>();
    long endAt;
    boolean elitesBuild, elitesDps;
    int volleys;
    String failReason;

    /** The cutscene playing now (null when none), how far into it (server ticks), and who has crouched to skip it. */
    KScript.Scene scene;
    int sceneTick;
    final Set<UUID> skipVotes = new HashSet<>();
    final Scenes scenes = new Scenes(this);
    /** Where the beast died (the victory scene's carcass sinks from there). */
    Location bossDiedAt;
    /** The beaching crawl (DPS): from where to where, and the hunt step it started on (-1 = not crawling). */
    Location crawlFrom, crawlTo;
    long crawlStart = -1;
    /** "STUNNED" over the beast's head while the stun lasts. */
    TextDisplay stunLabel;

    Run(KuudraModule mod, String id, KCatalog.Tier tier, Arena arena, List<Player> players, boolean admin) {
        this.mod = mod;
        this.id = id;
        this.tier = tier;
        this.f = mod.cat.fight;
        this.arena = arena;
        this.admin = admin;
        this.rng = new Random(id.hashCode() * 31L + System.nanoTime());
        for (Player p : players) ps.put(p.getUniqueId(), new PS(p));
    }

    // =====================================================================================
    // helpers
    // =====================================================================================

    List<Player> players() {
        List<Player> out = new ArrayList<>();
        for (UUID u : ps.keySet()) { Player p = Bukkit.getPlayer(u); if (p != null && p.isOnline()) out.add(p); }
        return out;
    }

    List<Player> alive() {
        List<Player> out = new ArrayList<>();
        for (Player p : players()) { PS s = ps.get(p.getUniqueId()); if (s != null && !s.ghost && !p.isDead()) out.add(p); }
        return out;
    }

    /** Alive, on the arena floor (not in the Gullet). */
    List<Player> onFloor() {
        List<Player> out = new ArrayList<>();
        for (Player p : alive()) if (!ps.get(p.getUniqueId()).inStomach) out.add(p);
        return out;
    }

    boolean fighting() { return phase == Phase.SUPPLIES || phase == Phase.BUILD || phase == Phase.BALLISTA || phase == Phase.DPS || phase == Phase.LAIR; }

    /** Won or lost: chests are stored, nothing left to do here but leave or hunt again. */
    boolean finished() { return finishedPhase(phase); }

    static boolean finishedPhase(Phase ph) { return ph == Phase.VICTORY || ph == Phase.FAILED || ph == Phase.CLOSED; }

    long now() { return System.currentTimeMillis(); }

    void tell(String legacy) { for (Player p : players()) Msg.send(p, legacy); }

    /** Lines Ferryman Brask has already said this hunt (each beat is said once). */
    private final Set<String> said = new HashSet<>();

    /**
     * Ferryman Brask calls the hunt as it happens (the reference's dock NPC narrates every phase in chat).
     * {@code key} makes a beat one-shot; a null key always speaks.
     */
    void narrate(String key, String line) {
        if (key != null && !said.add(key)) return;
        for (Player p : players()) {
            Msg.send(p, "&e[NPC] &6Ferryman Brask&f: " + line);
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.6f, 0.8f);
        }
    }

    /** The clickable "[HUNT AGAIN] [LEAVE]" line shown when a hunt ends. Only the leader can start; others are told who. */
    void offerAgain() {
        for (Player p : players()) {
            boolean lead = mod.crew.isLeader(p.getUniqueId());
            var line = Text.c("&7 ");
            if (lead) line = line.append(Text.c("&a&l[HUNT AGAIN]")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/kuudra again"))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Text.c("&eSet out on another " + tier.colored() + " &ehunt\n&7with the same crew, straight from here."))))
                    .append(Text.c(" "));
            line = line.append(Text.c("&c&l[LEAVE]")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/kuudra leave"))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Text.c("&7Back to the dock. Chests stay in &a/kuudra chests&7."))));
            if (!lead) {
                UUID l = mod.crew.leader(p.getUniqueId());
                line = line.append(Text.c(" &8(" + (l == null ? "your leader" : KMenus.nameOf(l)) + " can start the next hunt)"));
            }
            p.sendMessage(line);
        }
    }

    /** {@code /kuudra again}: the same tier, the same crew, from inside the finished hollow (or anywhere the board works). */
    String again(Player p) {
        if (!finished()) return "Finish this hunt first.";
        return mod.runs.start(p, tier, false);
    }

    void title(String a, String b, int stayMs) {
        Title t = Title.title(Text.c(a), Text.c(b), Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(stayMs), Duration.ofMillis(300)));
        for (Player p : players()) p.showTitle(t);
    }

    void sound(Sound s, float pitch) { for (Player p : players()) p.playSound(p.getLocation(), s, 1f, pitch); }

    double bossDamage() { return tier.bossDamage * KDials.BOSS_DMG.get(); }

    double hp() { return boss != null && boss.isValid() ? Sky.mobs().health(boss) : lastHp; }

    double pct() { return bossMax <= 0 ? 0 : 100.0 * hp() / bossMax; }

    /** The boss's max health, before it has spawned too (playtest wave 5: the opening's name card said "0 health"). */
    double expectedBossMax() {
        if (bossMax > 0) return bossMax;
        return Math.max(1, tier.bossHp * KDials.BOSS_HP.get() * KMath.partyHpScale(ps.size(), KDials.SOLO_HP.get()));
    }

    void addTokens(PS s, double amount) {
        int n = (int) Math.round(amount * KDials.TOKEN_SCALE.get());
        if (n <= 0 || s == null) return;
        s.tokens += n;
        s.earned += n;
    }

    private <T extends Entity> T tag(T e) {
        e.setPersistent(false);
        if (Arena.TAG != null) e.getPersistentDataContainer().set(Arena.TAG, PersistentDataType.STRING, id);
        return e;
    }

    BlockDisplay blockDisplay(Location at, String state, float scale) {
        World w = at.getWorld();
        return tag(w.spawn(at, BlockDisplay.class, d -> {
            try { d.setBlock(Bukkit.createBlockData("minecraft:" + state)); } catch (IllegalArgumentException ignored) { }
            float off = -scale / 2f;
            d.setTransformation(new Transformation(new Vector3f(off, 0, off), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            d.setBrightness(new Display.Brightness(15, 15));
        }));
    }

    TextDisplay label(Location at, String text) {
        return tag(at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(Text.c(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.setSeeThrough(false);
            d.setDefaultBackground(false);
            d.setShadowed(true);
        }));
    }

    static void remove(Entity e) { if (e != null) { try { e.remove(); } catch (RuntimeException ignored) { } } }

    // =====================================================================================
    // cutscenes (KScript timelines performed by Scenes)
    // =====================================================================================

    /** Start a scene: freeze the crew if it says so; its beats then run from {@link #sceneStep}. */
    void playScene(KScript.Scene s) {
        if (scene != null) endScene(true);
        scene = s;
        sceneTick = 0;
        skipVotes.clear();
        if (s.freeze()) for (Player p : players()) Scenes.freeze(p);
        sceneStep(0);
    }

    /** Advance the scene by {@code by} server ticks, running every beat passed; ends it at its length. */
    private void sceneStep(int by) {
        KScript.Scene s = scene;
        if (s == null) return;
        int from = by == 0 ? -1 : sceneTick;
        sceneTick += by;
        for (KScript.Beat b : s.between(from, sceneTick)) {
            if (scene != s) return;
            scenes.beat(s.id(), b.id());
        }
        if (scene != s) return;
        if (s.skippable() && KDials.CUTSCENE_SKIP.get()) {
            List<Player> crew = players();
            for (Player p : crew) if (p.isSneaking()) skipVotes.add(p.getUniqueId());
            skipVotes.retainAll(ps.keySet());
            if (KScript.skipped(skipVotes.size(), crew.size())) { endScene(true); return; }
            if (ticks % 2 == 0) for (Player p : crew)
                Sky.vitals().flash(p, "&8Cutscene &7- crouch to skip" + (crew.size() > 1 ? " &8(" + skipVotes.size() + "/" + crew.size() + ")" : ""), 12);
        }
        if (sceneTick >= s.length()) endScene(false);
    }

    void endScene(boolean skipped) {
        KScript.Scene s = scene;
        if (s == null) return;
        scene = null;
        skipVotes.clear();
        for (Player p : players()) Scenes.unfreeze(p);
        scenes.end(s.id(), skipped);
    }

    /** Frozen in a cutscene: no movement, and Runs.onDamage turns every hit away. */
    boolean frozen(Player p) { return scene != null && scene.freeze() && ps.containsKey(p.getUniqueId()); }

    /** Opening beat "rise": the beast starts climbing out of the lake (riseTick does the rest). */
    void startRise() { if (riseStart < 0) riseStart = ticks; }

    /** The opening is over (played or skipped): the beast is up, and the next hunt step starts the supplies phase. */
    void surfaceNow() {
        if (riseTo != null && boss != null && boss.isValid()) { boss.teleport(riseTo); bossHome = riseTo.clone(); }
        if (boss != null && boss.isValid()) boss.setInvulnerable(false);
        riseTo = null;
        if (riseStart < 0) riseStart = ticks;
        countdown = 0;
    }

    Location bossLastLocation() {
        if (bossDiedAt != null) return bossDiedAt;
        if (boss != null) return boss.getLocation();
        return bossHome;
    }

    double bossWidth() {
        try { return boss == null ? 8 : boss.getBoundingBox().getWidthX(); } catch (RuntimeException e) { return 8; }
    }

    // =====================================================================================
    // lifecycle
    // =====================================================================================

    /** The arena is ready and everyone is standing on the platform. */
    void begin() {
        started = now();
        phaseAt = started;
        phase = Phase.STARTING;
        countdown = 5;
        for (Player p : players()) {
            PS s = ps.get(p.getUniqueId());
            s.tokens = KDials.START_TOKENS.get();
            mod.giveRunItems(p);
            mod.data(p).huntsStarted++;
        }
        pads.clear();
        for (int i = 0; i < 6; i++) { Location l = arena.anchor("pad." + i); if (l != null) pads.add(l); }
        padFull = new boolean[pads.size()];
        fuelSpots = arena.anchors("fuel.");
        fuelSpotReady = new long[fuelSpots.size()];
        orbLocs = arena.orbs();
        orbHits = new int[orbLocs.size()];
        orbBroken = new boolean[orbLocs.size()];
        Location ferry = arena.anchor("npc.ferryman"), qm = arena.anchor("npc.quartermaster");
        try {
            if (ferry != null) Sky.npcs().spawnAt(KuudraModule.NPC_FERRY, id, ferry);
            if (qm != null) Sky.npcs().spawnAt(KuudraModule.NPC_QM, id, qm);
        } catch (RuntimeException e) { Sky.log().log(Level.WARNING, "[kuudra] npc spawnAt failed", e); }
        // the opening shot: the beast surfaces out of the lake over the countdown (riseTick)
        riseTo = arena.anchor("kuudra.rise");
        spawnBoss(riseTo == null ? null : riseTo.clone().add(0, -KMath.RISE_DEPTH, 0), -1);
        if (riseTo != null) bossHome = riseTo.clone();
        try {
            bar = BossBar.bossBar(Text.c("&cMagma Leviathan"), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
            for (Player p : players()) p.showBossBar(bar);
        } catch (RuntimeException ignored) { }
        tell(Msg.BAR);
        tell(" &c&lTHE MAGMA LEVIATHAN &7- " + tier.colored() + " &7hunt");
        tell(" &7Carry &e" + KDials.SUPPLIES.get() + " supply crates &7to the pads around the ballista.");
        tell(" &7Crouch beside a crate to lift it, crouch on a pad to set it down.");
        tell(" &7Right-click your &cHunt Perks &7to spend tokens.");
        tell(Msg.BAR);
        // the opening: the full cutscene for a crew with someone who has never seen it, else the 5 s countdown
        List<Boolean> seen = new ArrayList<>();
        for (Player p : players()) seen.add(Sky.profiles().isReady(p) && mod.data(p).scenesSeen.contains(KScript.OPENING));
        if (riseTo != null && KScript.playFull(KDials.CUTSCENES.get(), KDials.CUTSCENE_REPEAT.get(), seen)) {
            countdown = 0;
            playScene(KScript.opening());
        }
    }

    void spawnBoss(Location at, double hp) {
        if (at == null) return;
        try {
            boss = Sky.mobs().spawn(tier.bossId(), at);
        } catch (RuntimeException e) {
            Sky.log().log(Level.WARNING, "[kuudra] cannot spawn " + tier.bossId(), e);
            boss = null;
            return;
        }
        if (boss == null) return;
        mod.runs.byEntity.put(boss.getUniqueId(), this);
        if (bossMax <= 0) bossMax = tier.bossHp * KDials.BOSS_HP.get() * KMath.partyHpScale(ps.size(), KDials.SOLO_HP.get());
        bossMax = Math.max(1, bossMax);
        Sky.mobs().setMaxHealth(boss, bossMax);
        if (hp > 0) Sky.mobs().setHealth(boss, Math.min(bossMax, hp));
        lastHp = Sky.mobs().health(boss);
        bossHome = at.clone();
        MobDef d = Sky.mobs().def(tier.bossId());
        int size = 12;
        double scale = 2.4;
        if (d != null && d.extra != null) {
            if (d.extra.has("size")) size = d.extra.get("size").getAsInt();
            if (d.extra.has("scale")) scale = d.extra.get("scale").getAsDouble();
        }
        if (boss instanceof Slime s) resize(s, size);
        AttributeInstance sc = boss.getAttribute(Attribute.SCALE);
        if (sc != null) sc.setBaseValue(scale);
        boss.setGravity(false);
        // its hitbox reaches onto the shore when beached: a collidable beast would shove hunters into the lava
        boss.setCollidable(false);
        boss.setSilent(true);
        boss.setFireTicks(0);
    }

    /**
     * Slime.setSize resets vanilla max health, speed and attack attributes; core keeps the vanilla health as a
     * fixed gauge (its real HP lives in the PDC), so put the gauge and speed back exactly as core left them.
     */
    static void resize(Slime s, int size) {
        AttributeInstance mh = s.getAttribute(Attribute.MAX_HEALTH);
        AttributeInstance sp = s.getAttribute(Attribute.MOVEMENT_SPEED);
        double gauge = mh == null ? 0 : mh.getBaseValue();
        double hp = s.getHealth();
        double speed = sp == null ? 0 : sp.getBaseValue();
        s.setSize(size);
        if (mh != null) { mh.setBaseValue(gauge); s.setHealth(Math.max(0.5, Math.min(gauge, hp))); }
        if (sp != null) sp.setBaseValue(speed);
    }

    void setPhase(Phase p) {
        phase = p;
        phaseAt = now();
    }

    void tick() {
        if (phase == Phase.CLOSED || phase == Phase.PREPARING) return;
        ticks++;
        long now = now();
        // drop hunters who left the game or the hollow
        for (Iterator<Map.Entry<UUID, PS>> it = ps.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PS> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || !p.isOnline()) { cleanupState(e.getValue(), null); it.remove(); mod.runs.byPlayer.remove(e.getKey()); continue; }
            if (p.getWorld() != arena.world() && phase != Phase.VICTORY) {
                cleanupState(e.getValue(), p);
                it.remove();
                mod.runs.byPlayer.remove(e.getKey());
                Msg.send(p, "&7You left the hunt.");
            }
        }
        if (ps.isEmpty()) { close(); return; }
        if (scene != null) {
            try { sceneStep(5); } catch (RuntimeException e) { Sky.log().log(Level.WARNING, "[kuudra] scene step", e); endScene(true); }
        }
        if (fighting() && now - started > KDials.TIME_LIMIT.get() * 60_000L) { fail("The Leviathan outlasted you (time limit)."); return; }
        if (fighting()) keepBoss();
        try {
            switch (phase) {
                case STARTING -> starting();
                case SUPPLIES -> { carryTick(); if (delivered >= Math.min(KDials.SUPPLIES.get(), Math.max(1, pads.size()))) toBuild(); }
                case BUILD -> buildTick();
                case BALLISTA -> ballistaTick(now);
                case DPS -> dpsTick(now);
                case LAIR -> lairTick(now);
                case VICTORY -> { if (now >= endAt) close(); }
                case FAILED -> { if (now >= endAt) close(); }
                default -> { }
            }
            if (fighting()) {
                wavesTick(now);
                attacksTick(now);
                telesTick(now);
                dropshipsTick();
                if (ticks % 8 == 0) hunt();
                if (stomach) stomachTick(now);
                if (stunned && now >= stunUntil) unstun();
                dazedTick();
            }
            if (ticks % 4 == 0) hud();
        } catch (RuntimeException e) {
            Sky.log().log(Level.WARNING, "[kuudra] hunt " + id + " tick threw in " + phase, e);
        }
    }

    private void keepBoss() {
        if (boss != null && boss.isValid() && !boss.isDead()) {
            lastHp = Sky.mobs().health(boss);
            if (bossHome != null && boss.getLocation().distanceSquared(bossHome) > 4) boss.teleport(bossHome);
            return;
        }
        if (phase == Phase.VICTORY) return;
        // sky mobs are not persistent: bring it back where it was, with the health it had
        if (boss != null) mod.runs.byEntity.remove(boss.getUniqueId());
        spawnBoss(bossHome != null ? bossHome : arena.anchor("kuudra.rise"), lastHp);
        if (stunned && boss != null) boss.setGlowing(true);
    }

    /** Where the beast ends its surfacing (kuudra.rise); null once it has surfaced. */
    private Location riseTo;

    /** Every run tick during the countdown: lift the beast a step out of the lava, spraying it as it comes. */
    private void riseTick() {
        if (riseTo == null || boss == null || !boss.isValid()) return;
        int total = 5 * 4;                                   // countdown 5 s at 4 run ticks a second
        int done = Math.min(total, (int) (ticks - riseStart));
        double t = KMath.easeOut(done / (double) total);
        Location at = riseTo.clone().add(0, -KMath.RISE_DEPTH * (1 - t), 0);
        boss.teleport(at);
        World w = riseTo.getWorld();
        Location surface = riseTo.clone();
        surface.setY(com.skyclone.mod.kuudra.gen.KuudraArenaGenerator.FLOOR + 1);   // the lake's surface
        double r = Math.max(3, boss.getBoundingBox().getWidthX() / 2);
        w.spawnParticle(Particle.LAVA, surface, 10 + done, r * 0.6, 0.3, r * 0.6, 0);
        w.spawnParticle(Particle.LARGE_SMOKE, surface.clone().add(0, 1, 0), 6 + done / 2, r * 0.7, 0.6, r * 0.7, 0.02);
        if (done % 4 == 0) for (Player p : players()) p.playSound(riseTo, Sound.BLOCK_LAVA_POP, 2f, 0.5f + done * 0.02f);
        if (done % 8 == 4) for (Player p : players()) p.playSound(riseTo, Sound.ENTITY_RAVAGER_ROAR, 2f, 0.5f);
        boss.setInvulnerable(done < total);                 // never hurt by the rock it climbs through
        if (done >= total) riseTo = null;
    }

    private long riseStart = -1;

    private void starting() {
        if (scene != null && KScript.OPENING.equals(scene.id())) {
            // the full opening drives the surfacing itself (beat "rise"); the countdown waits for the scene
            if (riseStart >= 0) riseTick();
            return;
        }
        if (riseStart < 0) riseStart = ticks;                // the short opening: surface over the countdown
        riseTick();
        if (ticks % 4 != 0) return;
        if (countdown > 0) {
            title("&c" + countdown, "&7The lava stirs...", 800);
            sound(Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f + 0.1f * (5 - countdown));
            countdown--;
            return;
        }
        if (riseTo != null && boss != null && boss.isValid()) { boss.teleport(riseTo); boss.setInvulnerable(false); riseTo = null; }
        title("&c&lSUPPLIES", "&7Bring " + KDials.SUPPLIES.get() + " crates to the ballista", 1600);
        narrate("supplies", "It's up! Get those crates onto the pads before it finds its appetite!");
        sound(Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f);
        spawnCrates();
        setPhase(Phase.SUPPLIES);
        nextWave = now() + 8_000;
        nextSpit = now() + 20_000;
    }

    // =====================================================================================
    // phase 1: supplies
    // =====================================================================================

    private void spawnCrates() {
        List<Location> spots = new ArrayList<>(arena.anchors("supply."));
        java.util.Collections.shuffle(spots, rng);
        int n = Math.min(spots.size(), KDials.SUPPLIES.get());
        for (int i = 0; i < n; i++) {
            Crate c = new Crate();
            c.home = spots.get(i).clone();
            placeCrate(c, c.home);
            crates.add(c);
        }
    }

    private void placeCrate(Crate c, Location at) {
        remove(c.display);
        remove(c.label);
        c.at = at.clone();
        c.carried = false;
        c.display = blockDisplay(at.clone().add(0, 0, 0), "chest[facing=north]", 0.9f);
        c.label = label(at.clone().add(0, 1.6, 0), "&6&lSUPPLIES\n&eCrouch beside it to lift");
        at.getWorld().spawnParticle(Particle.FLAME, at.clone().add(0, 0.5, 0), 12, 0.4, 0.4, 0.4, 0.01);
    }

    private double gripPct(Player p, PS s) {
        double v = s.perk("sure_grip") * perkPer("sure_grip");
        v += mod.accessoryCarryBonus(p);
        return v;
    }

    double perkPer(String id) { KCatalog.Perk k = mod.cat.perk(id); return k == null ? 0 : k.per; }

    private double channelSecs(double base, Player p, PS s) {
        return KMath.channelSeconds(base, gripPct(p, s)) / Math.max(0.05, KDials.CHANNEL_SPEED.get());
    }

    /** Lift / set down supplies (SUPPLIES) and fuel (BALLISTA). */
    private void carryTick() {
        long now = now();
        for (Player p : onFloor()) {
            PS s = ps.get(p.getUniqueId());
            if (s.carryDisplay != null && s.carryDisplay.isValid()) s.carryDisplay.teleport(overHead(p));
            if (s.carry != Carry.NONE)
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 15, gripPct(p, s) >= 30 ? 0 : 1, false, false, false));
            boolean hurt = now - s.hurtAt < 500;
            boolean slowed = now - s.staggerAt < 600;
            String key = null;
            double secs = 0;
            Runnable done = null;
            Location ploc = p.getLocation();
            if (phase == Phase.SUPPLIES && s.carry == Carry.NONE && p.isSneaking()) {
                Crate best = null;
                double bd = 2.6 * 2.6;
                for (Crate c : crates) {
                    if (c.carried || c.at == null || c.at.getWorld() != ploc.getWorld()) continue;
                    double d = c.at.distanceSquared(ploc);
                    if (d < bd) { bd = d; best = c; }
                }
                if (best != null) {
                    Crate c = best;
                    key = "lift:" + System.identityHashCode(c);
                    secs = channelSecs(f.pickupSeconds, p, s);
                    done = () -> liftCrate(p, s, c);
                }
            } else if (phase == Phase.SUPPLIES && s.carry == Carry.SUPPLY && p.isSneaking()) {
                int pad = nearestFreePad(ploc, 2.2);
                if (pad >= 0) {
                    key = "pad:" + pad;
                    secs = channelSecs(f.deliverSeconds, p, s);
                    done = () -> deliver(p, s, pad);
                }
            } else if (phase == Phase.BALLISTA && s.carry == Carry.NONE) {
                double magnet = s.perk("fuel_magnet") > 0 ? perkPer("fuel_magnet") : 0;
                Cell best = null;
                double bd = 2.6 * 2.6;
                for (Cell c : cells) {
                    if (c.at.getWorld() != ploc.getWorld() || Math.abs(c.at.getY() - ploc.getY()) > 2.5) continue;
                    // horizontal: a cell on a slab or a dropped one a block lower is still "beside" you
                    double dx = c.at.getX() - ploc.getX(), dz = c.at.getZ() - ploc.getZ();
                    double d = dx * dx + dz * dz;
                    if (magnet > 0 && d < magnet * magnet) { best = c; bd = -1; break; }
                    if (p.isSneaking() && d < bd) { bd = d; best = c; }
                }
                if (best != null) {
                    Cell c = best;
                    if (bd < 0) { takeFuel(p, s, c); continue; }
                    key = "fuel:" + System.identityHashCode(c);
                    secs = channelSecs(f.fuelPickupSeconds, p, s);
                    done = () -> takeFuel(p, s, c);
                }
            } else if (phase == Phase.BALLISTA && s.carry == Carry.FUEL && p.isSneaking()
                    && horizontal(ploc, arena.ballistaBase()) < 5.5 && fuel < KDials.FUEL_PER_VOLLEY.get()) {
                key = "load";
                secs = channelSecs(f.fuelLoadSeconds, p, s);
                done = () -> loadFuel(p, s);
            }
            if (key == null && !hurt && s.chanKey != null && s.chan > 0) {
                // wave4-endgame playtest: a brood knockback shoved the hunter a step off the pad and the 3 s set-down
                // restarted from 0% - with Followers on you it never finished. Progress now holds for a moment.
                if (s.chanLostAt == 0) s.chanLostAt = now;
                if (KMath.channelHolds(now - s.chanLostAt)) {
                    Sky.vitals().flash(p, "&7" + label(s.chanKey) + " " + Text.dashBar(s.chan, 20) + " &7" + (int) (s.chan * 100) + "% &8(held - get back and crouch)", 8);
                    continue;
                }
            }
            if (key == null || hurt) {
                if (hurt && s.chanKey != null && s.chan > 0) Sky.vitals().flash(p, "&c&lINTERRUPTED!", 20);
                s.chanKey = null;
                s.chan = 0;
                s.chanLostAt = 0;
                continue;
            }
            s.chanLostAt = 0;
            if (!key.equals(s.chanKey)) { s.chanKey = key; s.chan = 0; }
            s.chan += DT / Math.max(0.05, secs) * (slowed ? KMath.BROOD_HIT_SLOW : 1.0);
            if (s.chan >= 1) {
                s.chanKey = null;
                s.chan = 0;
                done.run();
            } else {
                Sky.vitals().flash(p, (slowed ? "&6" : "&e") + label(key) + " " + Text.dashBar(s.chan, 20) + " &e" + (int) (s.chan * 100) + "%"
                        + (slowed ? " &6(hit - slowed)" : ""), 8);
                if (ticks % 2 == 0) p.playSound(ploc, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 0.6f + (float) s.chan);
            }
        }
    }

    private static String label(String key) {
        if (key.startsWith("lift")) return "Lifting";
        if (key.startsWith("pad")) return "Setting down";
        if (key.startsWith("fuel")) return "Grabbing fuel";
        return "Loading";
    }

    static double horizontal(Location a, Location b) {
        if (a == null || b == null || a.getWorld() != b.getWorld()) return Double.MAX_VALUE;
        double dx = a.getX() - b.getX() - 0.5, dz = a.getZ() - b.getZ() - 0.5;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The free pad a carrier is on: the one they were setting the crate down on (even if a knockback carried them a
     * step off it), else any free pad within set-down range. -1 when none.
     */
    private int padUnder(PS s, Location l) {
        if (s.chanKey != null && s.chanKey.startsWith("pad:")) {
            try {
                int i = Integer.parseInt(s.chanKey.substring(4));
                if (i >= 0 && i < pads.size() && !padFull[i]) return i;
            } catch (NumberFormatException ignored) { }
        }
        return l == null ? -1 : nearestFreePad(l, 2.2);
    }

    private int nearestFreePad(Location l, double r) {
        int best = -1;
        double bd = r * r;
        for (int i = 0; i < pads.size(); i++) {
            if (padFull[i]) continue;
            Location pad = pads.get(i);
            if (pad.getWorld() != l.getWorld()) continue;
            double dx = pad.getX() - l.getX(), dz = pad.getZ() - l.getZ();
            double d = dx * dx + dz * dz;
            if (d < bd && Math.abs(pad.getY() - l.getY()) < 2.5) { bd = d; best = i; }
        }
        return best;
    }

    /**
     * The carried crate/cell floats over the hunter's head. It FOLLOWS them (re-placed every tick with client
     * interpolation) rather than riding them: Paper refuses to teleport a player who has passengers, which would
     * silently break every warp while carrying.
     */
    private void carryVisual(Player p, PS s, String state) {
        remove(s.carryDisplay);
        BlockDisplay d = blockDisplay(overHead(p), state, 0.55f);
        d.setTeleportDuration(5);
        s.carryDisplay = d;
    }

    private static Location overHead(Player p) {
        Location l = p.getLocation().add(0, 2.05, 0);
        l.setPitch(0f);
        return l;
    }

    private void liftCrate(Player p, PS s, Crate c) {
        if (c.carried) return;
        c.carried = true;
        remove(c.display);
        remove(c.label);
        c.display = null;
        c.label = null;
        s.carry = Carry.SUPPLY;
        s.crate = c;
        carryVisual(p, s, "chest[facing=north]");
        addTokens(s, f.token("pickup"));
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 0.8f);
        Sky.vitals().flash(p, "&6Carrying supplies! &7Crouch on a pad by the ballista.", 40);
    }

    private void deliver(Player p, PS s, int pad) {
        if (padFull[pad] || s.carry != Carry.SUPPLY) return;
        padFull[pad] = true;
        s.carry = Carry.NONE;
        remove(s.carryDisplay);
        s.carryDisplay = null;
        if (s.crate != null) crates.remove(s.crate);
        s.crate = null;
        props.add(blockDisplay(pads.get(pad).clone(), "chest[facing=north]", 0.9f));
        delivered++;
        addTokens(s, f.token("deliver"));
        int need = Math.min(KDials.SUPPLIES.get(), pads.size());
        tell("&6" + p.getName() + " &7delivered supplies! &e(" + delivered + "/" + need + ")");
        if (need > 2 && delivered == need / 2) narrate("supplies-half", "Halfway there! Keep them coming!");
        else if (need > 1 && delivered == need - 1) narrate("supplies-last", "One more crate! Just one!");
        sound(Sound.BLOCK_ANVIL_PLACE, 1.4f);
    }

    /** A carrier died / left: the crate falls where they were (or goes home if that is not solid ground). */
    private void dropCarry(PS s, Location at) {
        remove(s.carryDisplay);
        s.carryDisplay = null;
        Carry was = s.carry;
        s.carry = Carry.NONE;
        if (was == Carry.SUPPLY) {
            Crate carried = s.crate;
            s.crate = null;
            if (carried == null) carried = new Crate();
            if (!crates.contains(carried)) crates.add(carried);
            Location spot = safeSpot(at, carried.home);
            if (carried.home == null) carried.home = spot;
            placeCrate(carried, spot);
        } else if (was == Carry.FUEL) {
            spawnCell(safeSpot(at, fuelSpots.isEmpty() ? arena.anchor("spawn") : fuelSpots.get(0)), -1);
        }
    }

    /**
     * Standing room on solid, dry ground under {@code at} (a blaze dies in the air, a husk at the lava's edge),
     * or null when there is none within 8 blocks or it is outside the arena: a fuel cell must land where a hunter
     * can crouch beside it.
     */
    private Location groundUnder(Location at) {
        if (at == null || at.getWorld() != arena.world()) return null;
        Block b = at.getBlock();
        for (int i = 0; i < 8 && b.getY() > at.getWorld().getMinHeight(); i++, b = b.getRelative(0, -1, 0)) {
            if (b.isLiquid()) return null;
            Block below = b.getRelative(0, -1, 0);
            if (b.isPassable() && below.getType().isSolid()) {
                Location l = b.getLocation().add(0.5, 0, 0.5);
                return arena.inArea(l, "arena", 2) ? l : null;
            }
        }
        return null;
    }

    private Location safeSpot(Location at, Location fallback) {
        if (at != null && at.getWorld() == arena.world() && arena.inArea(at, "arena", 0)) {
            Block below = at.getBlock().getRelative(0, -1, 0);
            if (below.getType().isSolid() && !at.getBlock().isLiquid()) return at.getBlock().getLocation().add(0.5, 0, 0.5);
        }
        return fallback != null ? fallback.clone() : arena.anchor("spawn");
    }

    // =====================================================================================
    // phase 2: build
    // =====================================================================================

    private void toBuild() {
        setPhase(Phase.BUILD);
        build = 0;
        partsPlaced = 0;
        title("&e&lBUILD", "&7Crouch beside the ballista to raise it", 1600);
        tell("&e&lALL SUPPLIES DELIVERED! &7Crouch next to the ballista to build it.");
        narrate("build", "That's every crate! Now hammer the ballista together - all hands on it!");
        sound(Sound.BLOCK_ANVIL_USE, 0.8f);
        breather();
        if (!elitesBuild) { elitesBuild = true; spawnElite("magma_follower", 1); }
    }

    private void buildTick() {
        long now = now();
        Location base = arena.ballistaBase();
        double add = 0;
        for (Player p : onFloor()) {
            PS s = ps.get(p.getUniqueId());
            if (!p.isSneaking() || horizontal(p.getLocation(), base) > 6.5 || Math.abs(p.getLocation().getY() - base.getY() - 1) > 2.5) continue;
            if (now - s.hurtAt < 750) { Sky.vitals().flash(p, "&c&lINTERRUPTED!", 10); continue; }
            boolean slowed = now - s.staggerAt < 600;
            double bonus = s.perk("master_mason") * perkPer("master_mason") + mod.accessoryBuildBonus(p);
            double a = KMath.buildRate(f.buildPercentPerSecond, bonus) * KDials.BUILD_SPEED.get() * DT * (slowed ? KMath.BROOD_HIT_SLOW : 1.0);
            add += a;
            addTokens(s, a * f.tokenD("buildPerPercent"));
            Sky.vitals().flash(p, (slowed ? "&6" : "&e") + "Building " + Text.dashBar(Math.min(1, (build + add) / 100), 20) + " &e" + (int) Math.min(100, build + add) + "%"
                    + (slowed ? " &6(hit - slowed)" : ""), 8);
            if (ticks % 2 == 0) p.getWorld().spawnParticle(Particle.CRIT, base.clone().add(0.5, 2.5, 0.5), 4, 1, 0.6, 1, 0.05);
        }
        if (add <= 0) return;
        build = Math.min(100, build + add);
        int n = com.skyclone.mod.kuudra.gen.BallistaShape.partsAt(build);
        if (n != partsPlaced) {
            partsPlaced = n;
            arena.setBallista(n);
            base.getWorld().playSound(base, Sound.BLOCK_WOOD_PLACE, 1f, 0.9f);
        }
        if (build >= 50) narrate("build-half", "Halfway built! Keep the brood off the frame!");
        if (build >= 100) toBallista();
    }

    // =====================================================================================
    // phase 3: fuel, fire, stun
    // =====================================================================================

    private void toBallista() {
        setPhase(Phase.BALLISTA);
        arena.setBallista(com.skyclone.mod.kuudra.gen.BallistaShape.built().size());
        title("&a&lBALLISTA READY", "&7Load " + KDials.FUEL_PER_VOLLEY.get() + " fuel cells, then right-click it", 2000);
        tell(Msg.BAR);
        tell(" &a&lTHE BALLISTA STANDS!");
        tell(" &7Fuel cells wash up on the shore and fall from the brood.");
        tell(" &7Crouch to grab one, crouch by the ballista to load it.");
        if (tier.stun) {
            tell(" &cThe beast's hide shrugs off bolts! &7Stun it first:");
            tell(" &7ride the &ecannon &7into its maw (Cannon Pass perk) and");
            tell(" &7break every glowing organ in its Gullet.");
        }
        tell(Msg.BAR);
        sound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        narrate("ballista", tier.stun ? "She stands! But its hide is too thick at this heat - get inside it and knock it senseless first!"
                : "Ha! She stands! Feed her " + KDials.FUEL_PER_VOLLEY.get() + " fuel cells and let it fly!");
        breather();
        long now = now();
        nextMove = now + f.moveSeconds * 1000L;
        nextSlam = now + 6_000;
        nextDevour = now + f.devourSeconds * 1000L;
        for (int i = 0; i < fuelSpotReady.length; i++) fuelSpotReady[i] = now + 1500L * i;
        moveBoss();
    }

    private void ballistaTick(long now) {
        carryTick();
        // keep fuel cells washing up
        int ground = cells.size();
        for (int i = 0; i < fuelSpots.size() && ground < f.fuelOnGround; i++) {
            boolean taken = false;
            for (Cell c : cells) if (c.spot == i) { taken = true; break; }
            if (taken || now < fuelSpotReady[i]) continue;
            spawnCell(fuelSpots.get(i), i);
            ground++;
        }
        if (!stunned && now >= nextMove) { nextMove = now + f.moveSeconds * 1000L; moveBoss(); }
        if (tier.stun && !stunned && !stomach && now >= nextDevour) {
            nextDevour = now + f.devourSeconds * 1000L;
            List<Player> c = new ArrayList<>();
            for (Player p : onFloor()) if (ps.get(p.getUniqueId()).carry == Carry.NONE) c.add(p);
            if (!c.isEmpty()) {
                Player v = c.get(rng.nextInt(c.size()));
                tell("&c&lDEVOURED! &7A tentacle drags &e" + v.getName() + " &7into the Leviathan's maw!");
                narrate(null, "It swallowed " + v.getName() + "! Break its guts from the inside, " + v.getName() + "!");
                enterStomach(v);
            }
        }
        if (pct() <= f.dpsAtPercent + 1e-6) toDps();
    }

    void spawnCell(Location at, int spot) {
        if (at == null) return;
        Cell c = new Cell();
        c.at = at.clone();
        c.spot = spot;
        c.display = blockDisplay(at.clone(), "respawn_anchor[charges=4]", 0.6f);
        c.label = label(at.clone().add(0, 1.2, 0), "&c&lFUEL CELL\n&eCrouch beside it to grab");
        cells.add(c);
        at.getWorld().spawnParticle(Particle.LAVA, at.clone().add(0, 0.5, 0), 6, 0.3, 0.2, 0.3, 0);
    }

    private void takeFuel(Player p, PS s, Cell c) {
        if (!cells.remove(c)) return;
        remove(c.display);
        remove(c.label);
        if (c.spot >= 0 && c.spot < fuelSpotReady.length) fuelSpotReady[c.spot] = now() + f.fuelRespawnSeconds * 1000L;
        s.carry = Carry.FUEL;
        carryVisual(p, s, "respawn_anchor[charges=4]");
        p.playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 1.2f);
        Sky.vitals().flash(p, "&cCarrying fuel! &7Crouch beside the ballista to load it.", 40);
    }

    private void loadFuel(Player p, PS s) {
        if (s.carry != Carry.FUEL) return;
        s.carry = Carry.NONE;
        remove(s.carryDisplay);
        s.carryDisplay = null;
        fuel = Math.min(KDials.FUEL_PER_VOLLEY.get(), fuel + 1);
        addTokens(s, f.token("fuelLoad"));
        int need = KDials.FUEL_PER_VOLLEY.get();
        tell("&c" + p.getName() + " &7loaded fuel. &e(" + fuel + "/" + need + ")" + (fuel >= need ? " &a&lREADY TO FIRE! &7Right-click the ballista." : ""));
        arena.ballistaBase().getWorld().playSound(arena.ballistaBase(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 0.8f);
    }

    /** Right-click on the ballista. */
    void fire(Player p) {
        PS s = ps.get(p.getUniqueId());
        if (s == null || s.ghost) return;
        if (phase == Phase.BUILD) { Msg.send(p, "&eThe ballista is " + (int) build + "% built. &7Crouch beside it to help."); return; }
        if (phase != Phase.BALLISTA) { Msg.send(p, "&7The ballista is silent."); return; }
        int need = KDials.FUEL_PER_VOLLEY.get();
        if (fuel < need) { Msg.error(p, "The ballista needs " + need + " fuel cells (" + fuel + " loaded)."); return; }
        if (boss == null || !boss.isValid()) return;
        fuel = 0;
        volleys++;
        addTokens(s, f.token("fire"));
        boolean full = !tier.stun || stunned;
        double pctHit = (full ? f.volleyPercent : f.unstunnedPercent) * KDials.VOLLEY_SCALE.get();
        double over = s.perk("overcharge") * perkPer("overcharge");
        Location from = arena.ballistaBase().clone().add(0.5, 4.5, -3.5);
        Location to = boss.getLocation().clone().add(0, 4, 0);
        sound(Sound.ENTITY_GENERIC_EXPLODE, 0.6f);
        title("", "&c" + p.getName() + " &7fires the ballista!", 900);
        Vector step = to.toVector().subtract(from.toVector());
        double len = step.length();
        step.normalize();
        final Vector stepF = step;
        for (int i = 0; i < 10; i++) {
            final int k = i;
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (phase == Phase.CLOSED) return;
                Location pt = from.clone().add(stepF.clone().multiply(len * (k + 1) / 10.0));
                pt.getWorld().spawnParticle(Particle.FLAME, pt, 20, 0.3, 0.3, 0.3, 0.02);
                pt.getWorld().spawnParticle(Particle.LARGE_SMOKE, pt, 4, 0.2, 0.2, 0.2, 0.0);
                if (k == 9) volleyHit(p, pctHit, over, full);
            }, 2L * (i + 1));
        }
    }

    private void volleyHit(Player shooter, double pctHit, double over, boolean full) {
        if (boss == null || !boss.isValid() || phase != Phase.BALLISTA) return;
        double floor = bossMax * f.dpsAtPercent / 100.0;
        double hp = Sky.mobs().health(boss);
        double dmg = KMath.volley(hp, bossMax, pctHit, over, floor);
        Sky.mobs().setHealth(boss, hp - dmg);
        lastHp = hp - dmg;
        PS s = ps.get(shooter.getUniqueId());
        if (s != null) s.damage += dmg;
        Location at = boss.getLocation().add(0, 5, 0);
        at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 2, 1, 1, 1, 0);
        Sky.combat().indicator(at, dmg, full, DamageContext.Kind.TRUE);
        if (full) tell("&a&lDIRECT HIT! &7The volley tears &c" + Text.shortNum(dmg) + " &7health from the beast. &8(" + String.format(java.util.Locale.ROOT, "%.0f", pct()) + "%)");
        if (full) narrate("first-hit", "Right in the hide! Again - load her up again!");
        else tell("&c&lGLANCING BLOW! &7Only &c" + Text.shortNum(dmg) + "&7. &eStun the Leviathan first!");
        if (pct() <= f.dpsAtPercent + 1e-6) toDps();
    }

    void moveBoss() {
        List<Location> spots = arena.anchors("kuudra.pos.");
        if (spots.isEmpty() || boss == null) return;
        int i = rng.nextInt(spots.size());
        if (i == bossPos && spots.size() > 1) i = (i + 1) % spots.size();
        bossPos = i;
        Location from = boss.getLocation();
        from.getWorld().spawnParticle(Particle.LAVA, from.clone().add(0, 3, 0), 40, 4, 2, 4, 0);
        bossHome = spots.get(i).clone();
        boss.teleport(bossHome);
        bossHome.getWorld().spawnParticle(Particle.LAVA, bossHome.clone().add(0, 3, 0), 60, 5, 2, 5, 0);
        bossHome.getWorld().playSound(bossHome, Sound.ENTITY_GENERIC_SPLASH, 3f, 0.5f);
    }

    /** Cannon (right-click inside the cannon area). */
    void cannon(Player p) {
        PS s = ps.get(p.getUniqueId());
        if (s == null || s.ghost) return;
        if (!tier.stun) { Msg.send(p, "&7The cannon is cold. &8(Burning tier and up)"); return; }
        if (phase != Phase.BALLISTA) { Msg.send(p, "&7The cannon fires only once the ballista stands."); return; }
        if (stunned) { Msg.send(p, "&aThe beast is already stunned - fire the ballista!"); return; }
        if (s.carry != Carry.NONE) { Msg.error(p, "Put down what you are carrying first."); return; }
        if (KDials.CANNON_NEEDS_PERK.get() && s.perk("cannon_pass") <= 0) { Msg.error(p, "You need the Cannon Pass perk (Hunt Perks)."); return; }
        Location target = boss == null ? p.getLocation() : boss.getLocation();
        Vector v = target.toVector().subtract(p.getLocation().toVector()).setY(0).normalize().multiply(1.4).setY(1.3);
        p.setVelocity(v);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.4f);
        p.getWorld().spawnParticle(Particle.EXPLOSION, p.getLocation(), 3, 0.5, 0.5, 0.5, 0);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (phase == Phase.BALLISTA && ps.containsKey(p.getUniqueId()) && p.isOnline()) {
                tell("&e" + p.getName() + " &7dives into the Leviathan's maw!");
                enterStomach(p);
            }
        }, 22L);
    }

    void enterStomach(Player p) {
        PS s = ps.get(p.getUniqueId());
        Location in = arena.anchor("stomach.spawn");
        if (s == null || in == null) return;
        if (s.carry != Carry.NONE) dropCarry(s, p.getLocation());
        if (!stomach) {
            stomach = true;
            stomachEnds = now() + KDials.STOMACH_SECONDS.get() * 1000L;
            arena.restoreOrbs();
            java.util.Arrays.fill(orbHits, 0);
            java.util.Arrays.fill(orbBroken, false);
            nextDigest = now() + 1000;
        }
        s.inStomach = true;
        p.setFallDistance(0);
        p.teleport(in);
        p.showTitle(Title.title(Text.c("&c&lDEVOURED"), Text.c("&7Break the &6" + orbLocs.size() + " glowing organs&7!"),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(300))));
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EAT, 1f, 0.5f);
        int need = KMath.orbHits(f.orbHits, s.perk("gut_pick"));
        Msg.send(p, "&7Each organ takes &e" + need + " &7strikes. Hurry - the Gullet burns!");
    }

    /** A strike on organ {@code i} (the break event was cancelled by core). */
    void orbStrike(Player p, int i) {
        PS s = ps.get(p.getUniqueId());
        if (s == null || !s.inStomach || !stomach || i < 0 || i >= orbBroken.length || orbBroken[i]) return;
        int need = KMath.orbHits(f.orbHits, s.perk("gut_pick"));
        orbHits[i]++;
        Location l = orbLocs.get(i);
        if (orbHits[i] >= need) {
            orbBroken[i] = true;
            l.getBlock().setType(org.bukkit.Material.AIR, false);
            l.getWorld().spawnParticle(Particle.BLOCK, l.clone().add(0.5, 0.5, 0.5), 30, 0.3, 0.3, 0.3, 0, org.bukkit.Material.OCHRE_FROGLIGHT.createBlockData());
            l.getWorld().playSound(l, Sound.ENTITY_SLIME_SQUISH, 1.5f, 0.6f);
            addTokens(s, f.token("orb"));
            int left = 0;
            for (boolean b : orbBroken) if (!b) left++;
            for (Player x : players()) if (ps.get(x.getUniqueId()).inStomach) Msg.send(x, "&6" + p.getName() + " &7ruptured an organ! &e(" + left + " left)");
            if (left == 0) stun();
        } else {
            Sky.vitals().flash(p, "&6Organ " + orbHits[i] + "/" + need, 15);
            l.getWorld().playSound(l, Sound.ENTITY_SLIME_HURT, 1f, 0.8f);
        }
    }

    private void stomachTick(long now) {
        boolean anyone = false;
        for (Player p : alive()) {
            PS s = ps.get(p.getUniqueId());
            if (!s.inStomach) continue;
            anyone = true;
            if (!arena.inArea(p.getLocation(), "stomach", 3)) p.teleport(arena.anchor("stomach.spawn"));
            if (now >= nextDigest) Sky.vitals().damage(p, Sky.vitals().maxHealth(p) * f.digestPercent / 100.0, "kuudra_digest");
            if (ticks % 4 == 0) Sky.vitals().flash(p, "&c&lDIGESTING &7- " + Math.max(0, (stomachEnds - now) / 1000) + "s", 10);
        }
        if (now >= nextDigest) nextDigest = now + 1000;
        if (!anyone) { stomach = false; return; }
        if (now >= stomachEnds) {
            tell("&cThe Leviathan spits out everyone in its Gullet!");
            eject();
        }
    }

    private void eject() {
        stomach = false;
        Location out = arena.anchor("spawn");
        for (Player p : players()) {
            PS s = ps.get(p.getUniqueId());
            if (!s.inStomach) continue;
            s.inStomach = false;
            if (out != null) { p.setFallDistance(0); p.teleport(out); }
        }
        arena.restoreOrbs();
    }

    private void stun() {
        for (Player p : players()) if (ps.get(p.getUniqueId()).inStomach) addTokens(ps.get(p.getUniqueId()), f.token("stun"));
        eject();
        stunned = true;
        stunUntil = now() + KDials.STUN_SECONDS.get() * 1000L;
        if (boss != null) boss.setGlowing(true);
        remove(stunLabel);
        stunLabel = boss == null || !boss.isValid() ? null : label(stunAt(), "&e&l✦ STUNNED ✦");
        if (stunLabel != null) stunLabel.setTeleportDuration(5);
        title("&e&lSTUNNED!", "&7Fire the ballista now! (" + KDials.STUN_SECONDS.get() + "s)", 2000);
        narrate(null, "It's reeling! Fire NOW, before it shakes it off!");
        sound(Sound.ENTITY_WITHER_HURT, 0.5f);
        if (bar != null) bar.color(BossBar.Color.YELLOW);
    }

    /** Over the beast's head, where the dazed label and its stars circle. */
    private Location stunAt() {
        Location l = boss.getLocation();
        return l.add(0, boss.getBoundingBox().getHeight() + 1.5, 0);
    }

    /** Every hunt step while stunned: the label follows the beast and the stars circle its head. */
    private void dazedTick() {
        if (!stunned || boss == null || !boss.isValid()) return;
        Location top = stunAt();
        if (stunLabel != null && stunLabel.isValid()) {
            stunLabel.teleport(top);
            stunLabel.text(Text.c("&e&l✦ STUNNED ✦\n&f" + Math.max(0, (stunUntil - now()) / 1000) + "s"));
        }
        double r = Math.max(2.5, bossWidth() / 3);
        for (int i = 0; i < 3; i++) {
            double a = (ticks * 0.6) + i * Math.PI * 2 / 3;
            top.getWorld().spawnParticle(Particle.WAX_OFF, top.clone().add(Math.cos(a) * r, -1, Math.sin(a) * r), 2, 0.1, 0.1, 0.1, 0);
        }
    }

    private void unstun() {
        stunned = false;
        remove(stunLabel);
        stunLabel = null;
        if (boss != null) boss.setGlowing(false);
        if (bar != null) bar.color(BossBar.Color.RED);
        tell("&cThe Leviathan shakes off the stun.");
        nextDevour = now() + f.devourSeconds * 1000L;
    }

    // =====================================================================================
    // phase 4: DPS, and the Infernal lair
    // =====================================================================================

    private void toDps() {
        if (phase != Phase.BALLISTA) return;
        setPhase(Phase.DPS);
        if (stomach) eject();
        if (stunned) unstun();
        for (Cell c : cells) { remove(c.display); remove(c.label); }
        cells.clear();
        for (Player p : players()) { PS s = ps.get(p.getUniqueId()); if (s.carry != Carry.NONE) { s.carry = Carry.NONE; s.crate = null; remove(s.carryDisplay); s.carryDisplay = null; } }
        Location dps = beachSpot();
        if (dps != null && boss != null && boss.isValid() && KDials.CUTSCENES.get()) {
            // the beaching: it drags itself from where it lay to the shore over CRAWL_STEPS (crawlTick), untouchable
            crawlFrom = boss.getLocation().clone();
            crawlTo = dps.clone();
            crawlStart = ticks;
            boss.setInvulnerable(true);
            title("&c&lIT COMES ASHORE", "&7Get through the rail gap onto the shore!", 2600);
        } else {
            if (dps != null && boss != null) { bossHome = dps.clone(); boss.teleport(dps); }
            title("&c&lKILL IT!", "&7The Leviathan beaches itself - every weapon works now", 2200);
        }
        tell("&c&lTHE LEVIATHAN CRAWLS ONTO THE SHORE! &7Attack it with everything!");
        narrate("dps", "It's dragging itself onto the shore! Through the rail gap - cut it down!");
        tell("&7Go through the &egap in the rail &7onto the shore - it is in sword reach there. &8(Its Rampage ring hits the shore too.)");
        sound(Sound.ENTITY_RAVAGER_ROAR, 0.5f);
        breather();
        nextRampage = now() + f.rampageSeconds * 1000L + (crawling() ? KMath.CRAWL_STEPS * 250L : 0);
        if (!elitesDps) {
            elitesDps = true;
            spawnElite("wither_sentry", 1);
            if (tier.index >= 3) { spawnElite("blazing_golem", 1); spawnElite("slasher", 1); }
        }
    }

    /**
     * Where the beast beaches: the {@code kuudra.dps} anchor's x and height, moved along z so the front face of
     * its REAL hitbox lies on the shore ({@code kuudra.beach}). The anchor alone put the centre 13 blocks past the
     * rail with the face out over the lava, out of sword reach from anywhere a hunter could stand (playtest
     * 2026-10-03). Measured from the live bounding box, so a size or scale change cannot open the gap again.
     */
    Location beachSpot() {
        Location dps = arena.anchor("kuudra.dps");
        if (dps == null || boss == null || !boss.isValid()) return dps;
        Location beach = beachLine();
        if (beach == null) return dps;
        var box = boss.getBoundingBox();
        Location at = dps.clone();
        at.setZ(KMath.beachedCentreZ(beach.getZ(), box.getWidthZ() / 2.0));
        at.setY(KMath.beachedFeetY(dps.getY(), beach.getY(), box.getHeight()));
        return at;
    }

    /** The shore line the beached beast's face reaches (anchor, or the generator constant for an older layout). */
    Location beachLine() {
        Location beach = arena.anchor("kuudra.beach");
        if (beach != null) return beach;
        if (arena.origin == null) return null;
        return new Location(arena.world(), arena.origin.getBlockX() + 0.5, com.skyclone.mod.kuudra.gen.KuudraArenaGenerator.FLOOR + 1,
                arena.origin.getBlockZ() + com.skyclone.mod.kuudra.gen.KuudraArenaGenerator.BEACH_Z);
    }

    boolean crawling() { return crawlStart >= 0; }

    /** One hunt step of the beaching crawl: heave towards the shore, shaking the ground; then "KILL IT!". */
    private void crawlTick() {
        if (!crawling()) return;
        if (boss == null || !boss.isValid() || crawlFrom == null || crawlTo == null) { crawlStart = -1; return; }
        int done = (int) Math.min(KMath.CRAWL_STEPS, ticks - crawlStart);
        double t = KMath.crawlProgress(done, KMath.CRAWL_STEPS);
        Location at = crawlFrom.clone().add(crawlTo.clone().subtract(crawlFrom).toVector().multiply(t));
        at.setYaw(crawlTo.getYaw());
        bossHome = at;
        boss.teleport(at);
        World w = at.getWorld();
        Location front = at.clone().add(0, 1, Math.max(3, bossWidth() / 2));
        w.spawnParticle(Particle.LAVA, front, 12, 3, 0.5, 1, 0);
        w.spawnParticle(Particle.BLOCK, front, 30, 3, 0.3, 1, 0, Bukkit.createBlockData(Material.BASALT));
        if (done % 2 == 0) for (Player p : players()) {
            p.playSound(at, Sound.ENTITY_RAVAGER_STEP, 2f, 0.4f);
            p.playSound(p.getLocation(), Sound.BLOCK_BASALT_BREAK, 0.6f, 0.5f);
        }
        if (done < KMath.CRAWL_STEPS) return;
        crawlStart = -1;
        bossHome = crawlTo.clone();
        boss.teleport(crawlTo);
        boss.setInvulnerable(false);
        Location beach = beachLine();
        Location shock = beach != null ? beach : crawlTo;
        w.spawnParticle(Particle.EXPLOSION_EMITTER, shock.clone().add(0, 1, 0), 2, 2, 0.5, 2, 0);
        title("&c&lKILL IT!", "&7The Leviathan is ashore - every weapon works now", 2200);
        sound(Sound.ENTITY_RAVAGER_ROAR, 0.4f);
    }

    private void dpsTick(long now) {
        crawlTick();
        if (crawling()) return;
        if (now >= nextRampage && boss != null) {
            nextRampage = now + f.rampageSeconds * 1000L;
            Tele t = new Tele();
            // drawn at shore height (the beast's feet are under the lava, where the ring was invisible)
            Location beach = beachLine();
            t.at = boss.getLocation().clone();
            if (beach != null) t.at.setY(beach.getY());
            t.radius = boss.getBoundingBox().getWidthX() / 2.0 + 5;
            t.fireAt = now + 1500;
            t.damage = bossDamage() * 0.5;
            t.what = "rampage";
            t.ring = true;
            teles.add(t);
        }
    }

    /** Called by the damage listener before the Infernal beast drops under the lair threshold. */
    boolean lairDue() { return tier.lair && phase == Phase.DPS; }

    double lairHp() { return bossMax * f.lairAtPercent / 100.0; }

    void toLair() {
        if (phase != Phase.DPS) return;
        setPhase(Phase.LAIR);
        if (crawling()) { crawlStart = -1; if (boss != null) boss.setInvulnerable(false); }
        title("&4&lTHE GROUND GIVES WAY", "&7Fall into the Leviathan's lair...", 2500);
        sound(Sound.ENTITY_GENERIC_EXPLODE, 0.4f);
        tell("&4&lTHE LAIR! &cDeaths down here are permanent unless a crewmate has &aLifeline&c.");
        narrate("lair", "The floor! It's pulling the whole hollow down with it - hold on to something!");
        clearMobs();
        // the collapse: 3 s of rumbling, cracking ground and rock raining from the dome, then the floor goes
        for (int i = 0; i < 12; i++) {
            final int step = i;
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (phase == Phase.LAIR) collapseStep(step); }, 5L * i);
        }
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (phase != Phase.LAIR) return;
            Location in = arena.anchor("lair.spawn"), head = arena.anchor("lair.head");
            for (Player p : alive()) {
                p.setFallDistance(0);
                if (in == null) continue;
                // drop in from under the lair's roof (16 blocks of headroom at lair.spawn) instead of popping in
                Location drop = in.clone().add(rng.nextDouble() * 4 - 2, KMath.LAIR_DROP, -KMath.LAIR_DROP_INWARD + rng.nextDouble() * 4 - 2);
                p.teleport(drop);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 50, 0, false, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 14, 0, false, false, false));
                p.playSound(drop, Sound.BLOCK_BASALT_BREAK, 1f, 0.5f);
            }
            if (in != null) debris(in.clone().add(0, 0, -KMath.LAIR_DROP_INWARD - 2), 10, 6, KMath.LAIR_DROP + 4, KMath.LAIR_DROP + 4);
            if (head != null && boss != null) { bossHome = head.clone(); boss.teleport(head); }
            nextSlam = now() + 5500;
            nextWave = now() + 7500;
        }, 60L);
    }

    /** One beat (5 ticks) of the arena collapse: rumble, dust, cracks under every hunter, rock from the dome. */
    private void collapseStep(int step) {
        float pitch = 0.5f + step * 0.03f;
        for (Player p : alive()) {
            Location l = p.getLocation();
            p.playSound(l, step % 3 == 0 ? Sound.ENTITY_GENERIC_EXPLODE : Sound.BLOCK_DEEPSLATE_BREAK, 0.5f + step * 0.04f, pitch);
            if (step % 4 == 0) p.playSound(l, Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.6f);
            World w = l.getWorld();
            w.spawnParticle(Particle.BLOCK, l.clone().add(0, 0.1, 0), 30 + step * 4, 2.5, 0.1, 2.5, 0,
                    Bukkit.createBlockData(step % 2 == 0 ? Material.BLACKSTONE : Material.BASALT));
            w.spawnParticle(Particle.LAVA, l.clone().add(rng.nextDouble() * 6 - 3, 0.2, rng.nextDouble() * 6 - 3), 2 + step / 3, 0.5, 0, 0.5, 0);
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, l.clone().add(0, 0.2, 0), 4 + step, 3, 0.2, 3, 0.01);
            // a tremor you can feel: a small sideways nudge, never upwards (no free fall damage)
            if (step % 2 == 1 && p.isOnGround())
                p.setVelocity(p.getVelocity().add(new Vector(rng.nextDouble() * 0.16 - 0.08, 0, rng.nextDouble() * 0.16 - 0.08)));
        }
        Location base = arena.ballistaBase();
        if (base != null) debris(base, 2 + step / 2, 14, 22, 22);
        if (step == 11) title("&4&lCOLLAPSE!", "&7The floor falls away beneath you", 900);
    }

    /**
     * Rock falling from above: {@code n} block displays spawned {@code height} blocks over random spots within
     * {@code radius} of {@code centre}, slid down by client interpolation (one teleport, {@code fall} blocks),
     * then removed. Display entities only - the arena itself is never edited.
     */
    private void debris(Location centre, int n, double radius, double height, double fall) {
        String[] rock = {"blackstone", "basalt[axis=y]", "magma_block", "gilded_blackstone", "polished_blackstone"};
        for (int i = 0; i < n; i++) {
            double a = rng.nextDouble() * Math.PI * 2, r = Math.sqrt(rng.nextDouble()) * radius;
            Location top = centre.clone().add(Math.cos(a) * r, height, Math.sin(a) * r);
            BlockDisplay d = blockDisplay(top, rock[rng.nextInt(rock.length)], 0.5f + rng.nextFloat() * 0.9f);
            d.setTeleportDuration(16);
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (!d.isValid()) return;
                Location to = top.clone().add(0, -fall, 0);
                to.setYaw(rng.nextFloat() * 360f);
                d.teleport(to);
            }, 2L);
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (d.isValid()) {
                    Location at = d.getLocation();
                    at.getWorld().spawnParticle(Particle.BLOCK, at, 12, 0.4, 0.2, 0.4, 0, d.getBlock());
                }
                remove(d);
            }, 19L);
        }
    }

    private void lairTick(long now) {
        // revives (Lifeline perk): crouch by a fallen crewmate's marker
        for (Player p : alive()) {
            PS s = ps.get(p.getUniqueId());
            if (s.perk("lifeline") <= 0 || !p.isSneaking()) continue;
            for (Player g : players()) {
                PS gs = ps.get(g.getUniqueId());
                if (!gs.ghost || gs.marker == null || gs.marker.getWorld() != p.getWorld() || gs.marker.distanceSquared(p.getLocation()) > 9) continue;
                gs.revive += DT / 3.0;
                Sky.vitals().flash(p, "&aReviving " + g.getName() + " " + Text.dashBar(gs.revive, 20), 8);
                if (gs.revive >= 1) revive(g, gs);
            }
        }
        for (Player g : players()) {
            PS gs = ps.get(g.getUniqueId());
            if (gs.ghost && gs.marker != null && ticks % 2 == 0)
                gs.marker.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, gs.marker.clone().add(0, 0.5, 0), 6, 0.3, 0.6, 0.3, 0.01);
        }
        if (alive().isEmpty()) fail("Your whole crew fell in the lair.");
    }

    private void revive(Player g, PS gs) {
        gs.ghost = false;
        gs.revive = 0;
        g.setGameMode(gs.restore != null ? gs.restore : GameMode.SURVIVAL);
        mod.data(g).ghostRestore = null;
        if (gs.marker != null) g.teleport(gs.marker);
        Sky.vitals().setHealth(g, Sky.vitals().maxHealth(g) * 0.3);
        tell("&a" + g.getName() + " &7was pulled back to their feet!");
        g.playSound(g.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1f);
    }

    // =====================================================================================
    // waves, elites, dropships
    // =====================================================================================

    private void wavesTick(long now) {
        if (phase == Phase.SUPPLIES || phase == Phase.BUILD || phase == Phase.BALLISTA || phase == Phase.DPS || phase == Phase.LAIR) {
            mobs.removeIf(u -> { Entity e = Bukkit.getEntity(u); boolean gone = e == null || !e.isValid() || e.isDead(); if (gone) mod.runs.byEntity.remove(u); return gone; });
            idle.retainAll(mobs);
            int cap = aliveCap();
            int pressing = pressing();
            if (!KMath.waveDue(now, nextWave, pressing, cap, waveMillis())) {
                // the timer is due but the last wave is still standing: hold it until the crew thins it out
                if (now >= nextWave && ticks % 40 == 0)
                    for (Player p : onFloor()) Sky.vitals().flash(p, "&7The brood holds back while &c" + pressing + " &7still stand...", 30);
                return;
            }
            nextWave = now + waveMillis();
            wave++;
            int n = KMath.waveSize(wave, ps.size(), 8);
            if (phase == Phase.LAIR) {
                List<Location> spots = arena.anchors("lair.slam.");
                for (int i = 0; i < n + 2 && mobs.size() < cap; i++) spawnMob(mod.kindId("cindermite", tier), spots.isEmpty() ? null : spots.get(rng.nextInt(spots.size())));
                return;
            }
            List<Location> spots = arena.anchors("spawn.mob.");
            if (spots.isEmpty()) return;
            for (int i = 0; i < n && mobs.size() < cap; i++) {
                String mob = mod.pickWaveMob(tier, wave, rng);
                if (mob != null) spawnMob(mob, spots.get(rng.nextInt(spots.size())));
            }
            if (phase == Phase.BALLISTA && tier.index >= 1 && wave >= 4 && wave % 3 == 0) spawnDropship();
            for (Player p : onFloor()) Sky.vitals().flash(p, "&c&lWAVE " + wave + " &7- the brood climbs out of the lava!", 30);
        }
    }

    /** Brood that are actually coming for the crew (not landmines, not dropships). */
    int pressing() {
        int n = 0;
        for (UUID u : mobs) if (!idle.contains(u) && !dropships.contains(u)) n++;
        return n;
    }

    /** Most brood alive at once for this crew (the dial is the four-hunter number). */
    int aliveCap() { return KMath.crowdCap(KDials.MAX_ALIVE.get(), ps.size()); }

    long waveMillis() { return (long) (tier.waveSeconds * 1000L * Math.max(0.1, KDials.WAVE_SCALE.get())); }

    /** A phase change gives the crew one wave interval of quiet before the brood comes again. */
    private void breather() { nextWave = Math.max(nextWave, now() + waveMillis()); }

    LivingEntity spawnMob(String mobId, Location at) {
        if (mobId == null || at == null || Sky.mobs().def(mobId) == null) return null;
        try {
            LivingEntity le = Sky.mobs().spawn(mobId, at.clone().add(rng.nextDouble() * 2 - 1, 0, rng.nextDouble() * 2 - 1));
            if (le == null) return null;
            double s = KDials.MOB_HP.get();
            if (Math.abs(s - 1) > 1e-6) Sky.mobs().setMaxHealth(le, Math.max(1, Sky.mobs().def(mobId).health * s));
            boolean mine = "landmine".equals(kind(mobId));
            if (le instanceof Slime sl && !(le.equals(boss))) resize(sl, mine ? 1 : 2);
            mobs.add(le.getUniqueId());
            if (mine) idle.add(le.getUniqueId());
            mod.runs.byEntity.put(le.getUniqueId(), this);
            AttributeInstance follow = le.getAttribute(Attribute.FOLLOW_RANGE);
            if (follow != null) follow.setBaseValue(Math.max(follow.getBaseValue(), 96));
            return le;
        } catch (RuntimeException e) {
            Sky.log().log(Level.WARNING, "[kuudra] spawn " + mobId + " failed", e);
            return null;
        }
    }

    String kind(String mobId) {
        MobDef d = Sky.mobs().def(mobId);
        return d == null || d.extra == null || !d.extra.has("kuudraKind") ? "" : d.extra.get("kuudraKind").getAsString();
    }

    private void spawnElite(String kind, int n) {
        List<Location> spots = arena.anchors("spawn.mob.");
        if (spots.isEmpty()) return;
        for (int i = 0; i < n; i++) {
            LivingEntity le = spawnMob(mod.kindId(kind, tier), spots.get(rng.nextInt(spots.size())));
            if (le != null) tell("&c&lELITE! &7A &c" + Text.strip(Sky.mobs().def(mod.kindId(kind, tier)).name) + " &7joins the fight!");
        }
    }

    private void spawnDropship() {
        Location rise = arena.anchor("kuudra.rise");
        if (rise == null) return;
        Location at = rise.clone().add(rng.nextInt(60) - 30, 22, -10);
        LivingEntity d = spawnMob(mod.kindId("dropship", tier), at);
        if (d == null) return;
        d.setGravity(false);
        dropships.add(d.getUniqueId());
        tell("&4&lDROPSHIP INBOUND! &7Shoot it down before it lands troops! &6(+" + f.token("dropship") + " tokens)");
        sound(Sound.ENTITY_GHAST_WARN, 0.7f);
    }

    private void dropshipsTick() {
        if (dropships.isEmpty()) return;
        Location target = arena.ballistaBase().clone().add(0.5, 5, 0.5);
        for (Iterator<UUID> it = dropships.iterator(); it.hasNext(); ) {
            Entity e = Bukkit.getEntity(it.next());
            if (!(e instanceof LivingEntity le) || !le.isValid()) { it.remove(); continue; }
            Location l = le.getLocation();
            Vector v = target.toVector().subtract(l.toVector());
            if (v.lengthSquared() < 36) {
                it.remove();
                tell("&c&lTHE DROPSHIP LANDED! &7Its troops pour onto the platform!");
                Location drop = arena.anchor("spawn");
                for (int i = 0; i < 3; i++) spawnMob(mod.kindId("follower", tier), drop != null ? drop : l);
                Sky.mobs().remove(le);
                continue;
            }
            Location next = l.add(v.normalize().multiply(0.9));
            next.setDirection(v);
            le.teleport(next);
        }
    }

    /** Brood members without a living target go after the nearest hunter on the floor (they spawn far away). */
    private void hunt() {
        List<Player> targets = onFloor();
        if (targets.isEmpty()) return;
        for (UUID u : mobs) {
            if (dropships.contains(u)) continue;
            if (!(Bukkit.getEntity(u) instanceof org.bukkit.entity.Mob m) || !m.hasAI()) continue;
            LivingEntity t = m.getTarget();
            if (t instanceof Player tp && tp.isValid() && !tp.isDead() && tp.getGameMode() != GameMode.SPECTATOR && targets.contains(tp)) continue;
            Player best = null;
            double bd = 140 * 140;
            for (Player p : targets) {
                double d = p.getLocation().distanceSquared(m.getLocation());
                if (d < bd) { bd = d; best = p; }
            }
            if (best != null) m.setTarget(best);
        }
    }

    void clearMobs() {
        for (UUID u : new ArrayList<>(mobs)) {
            Entity e = Bukkit.getEntity(u);
            if (e instanceof LivingEntity le && Sky.mobs().isSkyMob(le)) Sky.mobs().remove(le);
            else if (e != null) e.remove();
            mod.runs.byEntity.remove(u);
        }
        mobs.clear();
        dropships.clear();
        idle.clear();
        clearShots(null);
    }

    /**
     * Remove the arrows and fireballs the brood already loosed ({@code shooter} null: every non-hunter projectile in
     * this hollow; otherwise only that mob's). wave4-endgame playtest: after VICTORY cleared the brood, an arrow
     * still in the air killed the winner - "You died (projectile)" with no name, because a projectile whose shooter
     * is gone reaches core as nameless environment damage (5% of max health per vanilla point, not the mob's hit).
     */
    void clearShots(UUID shooter) {
        World w = arena.world();
        if (w == null || arena.origin == null) return;
        for (org.bukkit.entity.Projectile pr : w.getNearbyEntitiesByType(org.bukkit.entity.Projectile.class,
                arena.origin.clone().add(0, 64, 0), 110, 80, 110)) {
            if (pr.getShooter() instanceof Player) continue;
            if (shooter != null && !(pr.getShooter() instanceof Entity e && shooter.equals(e.getUniqueId()))) continue;
            pr.remove();
        }
    }

    /** A brood member (or the beast) died. */
    void onKill(SkyEvents.MobKill e) {
        UUID u = e.mob.getUniqueId();
        mod.runs.byEntity.remove(u);
        if (boss != null && u.equals(boss.getUniqueId())) { victory(); return; }
        mobs.remove(u);
        idle.remove(u);
        clearShots(u);   // its arrows would land as nameless environment damage (see clearShots)
        boolean ship = dropships.remove(u);
        PS s = e.killer == null ? null : ps.get(e.killer.getUniqueId());
        if (s != null) {
            s.kills++;
            int tok = 0;
            JsonObject x = e.def == null ? null : e.def.extra;
            if (x != null && x.has("tokens")) tok = x.get("tokens").getAsInt();
            if (ship) tok += f.token("dropship");
            addTokens(s, tok);
            mod.data(e.killer).brood++;
            if (ship) tell("&a" + e.killer.getName() + " &7shot down the dropship! &6+" + f.token("dropship") + " tokens");
        }
        if (phase == Phase.BALLISTA && rng.nextDouble() < f.fuelDropChance && e.location != null) {
            Location spot = groundUnder(e.location);
            if (spot != null) spawnCell(spot, -1);
        }
    }

    // =====================================================================================
    // the beast's attacks
    // =====================================================================================

    private void attacksTick(long now) {
        if (boss == null || !boss.isValid() || stunned) return;
        if (phase != Phase.LAIR && now >= nextSpit) {
            nextSpit = now + f.spitSeconds * 1000L;
            List<Player> c = onFloor();
            if (!c.isEmpty()) {
                Player t = c.get(rng.nextInt(c.size()));
                Tele te = new Tele();
                te.at = t.getLocation().clone();
                te.radius = 3;
                te.fireAt = now + 1500;
                te.damage = bossDamage() * 0.6;
                te.what = "Magma Spit";
                teles.add(te);
                beam(boss.getLocation().add(0, 6, 0), te.at, Particle.FLAME);
                boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 3f, 0.5f);
            }
        }
        if ((phase == Phase.BALLISTA || phase == Phase.DPS || phase == Phase.LAIR) && now >= nextSlam) {
            nextSlam = now + (phase == Phase.LAIR ? 5_000L : f.slamSeconds * 1000L);
            Location spot;
            List<Player> c = onFloor();
            if (phase == Phase.BALLISTA && rng.nextBoolean()) spot = arena.ballistaBase().clone().add(rng.nextInt(17) - 8, 1, rng.nextInt(17) - 8);
            else if (!c.isEmpty()) spot = c.get(rng.nextInt(c.size())).getLocation().clone();
            else return;
            Tele te = new Tele();
            te.at = spot;
            te.radius = 4;
            te.fireAt = now + 2000;
            te.damage = bossDamage();
            te.what = "Tentacle Slam";
            te.knock = true;
            teles.add(te);
            beam(boss.getLocation().add(0, 3, 0), spot, Particle.LARGE_SMOKE);
        }
    }

    private void beam(Location from, Location to, Particle p) {
        if (from.getWorld() != to.getWorld()) return;
        Vector d = to.toVector().subtract(from.toVector());
        double len = d.length();
        d.normalize();
        for (double t = 0; t < len; t += 1.5) from.getWorld().spawnParticle(p, from.clone().add(d.clone().multiply(t)), 1, 0.05, 0.05, 0.05, 0);
    }

    private void telesTick(long now) {
        for (Iterator<Tele> it = teles.iterator(); it.hasNext(); ) {
            Tele t = it.next();
            World w = t.at.getWorld();
            boolean soon = t.fireAt - now < 600;
            Particle.DustOptions dust = new Particle.DustOptions(soon ? Color.fromRGB(255, 40, 0) : Color.fromRGB(255, 140, 0), 1.6f);
            int pts = (int) Math.max(12, t.radius * 6);
            for (int i = 0; i < pts; i++) {
                double a = Math.PI * 2 * i / pts;
                w.spawnParticle(Particle.DUST, t.at.clone().add(Math.cos(a) * t.radius, 0.2, Math.sin(a) * t.radius), 1, 0, 0, 0, 0, dust);
            }
            if (!t.ring) w.spawnParticle(Particle.FLAME, t.at.clone().add(0, 0.2, 0), 4, t.radius / 3, 0.05, t.radius / 3, 0.0);
            if (now < t.fireAt) continue;
            it.remove();
            w.spawnParticle(Particle.EXPLOSION, t.at.clone().add(0, 0.5, 0), (int) t.radius, t.radius / 2, 0.3, t.radius / 2, 0);
            w.playSound(t.at, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, t.knock ? 0.6f : 1.1f);
            for (Player p : alive()) {
                if (p.getWorld() != w || ps.get(p.getUniqueId()).inStomach) continue;
                Location l = p.getLocation();
                double dx = l.getX() - t.at.getX(), dz = l.getZ() - t.at.getZ();
                if (dx * dx + dz * dz > t.radius * t.radius || Math.abs(l.getY() - t.at.getY()) > 4) continue;
                if (boss != null) Sky.combat().damagePlayer(p, t.damage, boss, DamageContext.Kind.MOB);
                if (t.knock) p.setVelocity(new Vector(dx, 0, dz).normalize().multiply(0.6).setY(0.8));
                ps.get(p.getUniqueId()).hurtAt = now;
                Sky.vitals().flash(p, "&c" + t.what + "!", 20);
            }
        }
    }

    // =====================================================================================
    // players
    // =====================================================================================

    /** A hunter took damage (to interrupt channels). */
    void hurt(Player p, Entity attacker) {
        PS s = ps.get(p.getUniqueId());
        if (s == null) return;
        if (attacker != null && boss != null && attacker.getUniqueId().equals(boss.getUniqueId())) s.hurtAt = now();
        else s.staggerAt = now();
    }

    /** Second Wind: survive one killing blow. Returns true when the blow was absorbed. */
    boolean secondWind(Player p, double incoming) {
        PS s = ps.get(p.getUniqueId());
        if (s == null || s.secondWindUsed || s.perk("second_wind") <= 0) return false;
        if (incoming < Sky.vitals().health(p) + Sky.vitals().absorption(p)) return false;
        s.secondWindUsed = true;
        Sky.vitals().setHealth(p, Sky.vitals().maxHealth(p) * perkPer("second_wind") / 100.0);
        p.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 1f, 1.2f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.3);
        Msg.send(p, "&a&lSECOND WIND! &7You refused to fall.");
        return true;
    }

    void onDeath(SkyEvents.SkyDeath e) {
        Player p = e.player;
        PS s = ps.get(p.getUniqueId());
        if (s == null) return;
        if (!KDials.DEATH_COIN_LOSS.get()) e.coinsLost = 0;
        s.deaths++;
        mod.data(p).deaths++;
        int pad = s.carry == Carry.SUPPLY && phase == Phase.SUPPLIES ? padUnder(s, p.getLocation()) : -1;
        if (pad >= 0) {
            // wave4-endgame playtest: knocked down while setting a crate on a pad, and the crate fell beside the pad
            // for someone to lift and carry all over again. A crate that falls on a free pad is set down on it.
            tell("&7The crate tumbles onto the pad as &e" + p.getName() + " &7falls.");
            deliver(p, s, pad);
        }
        if (s.carry != Carry.NONE) dropCarry(s, p.getLocation());
        s.chanKey = null;
        s.chanLostAt = 0;
        s.inStomach = false;
        if (phase == Phase.LAIR) {
            Location at = p.getLocation().clone();
            e.respawnAt = at;
            s.ghost = true;
            s.marker = at;
            s.restore = p.getGameMode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : p.getGameMode();
            mod.data(p).ghostRestore = s.restore.name();
            tell("&c☠ &e" + p.getName() + " &7fell in the lair" + (anyLifeline() ? " - a &aLifeline &7can still save them." : "."));
            Bukkit.getScheduler().runTask(Sky.plugin(), () -> { if (p.isOnline() && s.ghost) p.setGameMode(GameMode.SPECTATOR); });
            return;
        }
        Location back = arena.anchor("spawn");
        if (back != null) e.respawnAt = back;
        tell("&c☠ &e" + p.getName() + " &7was knocked down and drags themself back to the platform.");
        Bukkit.getScheduler().runTask(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 50, 0, false, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, false, false, false));
        });
    }

    private boolean anyLifeline() {
        for (PS s : ps.values()) if (!s.ghost && s.perk("lifeline") > 0) return true;
        return false;
    }

    /** Remove a player from this hunt (command, quit, kick). */
    void leave(Player p, boolean teleport) {
        PS s = ps.remove(p.getUniqueId());
        mod.runs.byPlayer.remove(p.getUniqueId());
        if (s == null) return;
        cleanupState(s, p);
        if (teleport && p.isOnline()) mod.sendOut(p);
        if (ps.isEmpty()) close();
        else tell("&e" + p.getName() + " &7left the hunt.");
    }

    void cleanupState(PS s, Player p) {
        if (s.carry != Carry.NONE && fighting()) dropCarry(s, p != null ? p.getLocation() : null);
        remove(s.carryDisplay);
        s.carryDisplay = null;
        if (p == null) return;
        Scenes.unfreeze(p);
        if (s.ghost || p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(s.restore != null ? s.restore : GameMode.SURVIVAL);
            s.ghost = false;
        }
        if (Sky.profiles().isReady(p)) mod.data(p).ghostRestore = null;
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        if (bar != null) { try { p.hideBossBar(bar); } catch (RuntimeException ignored) { } }
        mod.takeRunItems(p);
        Sky.stats().invalidate(p);
    }

    // =====================================================================================
    // the end
    // =====================================================================================

    void victory() {
        if (phase == Phase.VICTORY || phase == Phase.CLOSED) return;
        setPhase(Phase.VICTORY);
        long took = now() - started;
        if (scene != null) endScene(true);
        bossDiedAt = boss != null ? boss.getLocation() : bossHome;
        crawlStart = -1;
        remove(stunLabel);
        stunLabel = null;
        clearMobs();
        teles.clear();
        for (Cell c : cells) { remove(c.display); remove(c.label); }
        cells.clear();
        for (Crate c : crates) { remove(c.display); remove(c.label); }
        if (stomach) eject();
        endAt = now() + KDials.REWARD_SECONDS.get() * 1000L;
        if (bar != null) { bar.name(Text.c("&a&lVICTORY &7- claim your chests")); bar.progress(1f); bar.color(BossBar.Color.GREEN); }
        Location rewards = arena.anchor("rewards");
        title("&6&lLEVIATHAN SLAIN", "&7" + tier.colored() + " &7in &e" + KMath.clock(took), 3500);
        narrate("win", "It's going under! You did it! Your chests are by the platform - and the drinks are on me.");
        sound(Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f);
        sound(Sound.ENTITY_PLAYER_LEVELUP, 0.6f);
        for (Player p : players()) {
            PS s = ps.get(p.getUniqueId());
            if (s.ghost) { p.setGameMode(s.restore != null ? s.restore : GameMode.SURVIVAL); s.ghost = false; }
            stopHarm(p);
            if (phase == Phase.VICTORY && rewards != null && p.getLocation().getY() < 45) { p.setFallDistance(0); p.teleport(rewards); }
            mod.reward(p, this, s, took);
        }
        if (KDials.ANNOUNCE.get() && tier.lair)
            Msg.broadcast("&6&lLEVIATHAN! &e" + String.join(", ", names()) + " &7slew the &4Infernal &cMagma Leviathan &7in &e" + KMath.clock(took) + "&7!");
        // the beast goes under (KScript.victory: 4 s, crouch to skip), then the chests open
        if (KDials.CUTSCENES.get()) playScene(KScript.victory());
        else Bukkit.getScheduler().runTaskLater(Sky.plugin(), this::afterVictoryScene, 50L);
    }

    /** After the victory scene (or 2.5 s without one): open everyone's chest pair and offer Hunt Again. */
    void afterVictoryScene() {
        if (phase != Phase.VICTORY) return;
        for (Player p : players()) mod.menus.chest(p, id);
        offerAgain();
    }

    /**
     * The hunt is over: nothing the fight started may still hurt this hunter (burning from a blaze, a Blight's
     * wither, poison). Runs.onDamage also turns away every hit in a finished hollow.
     */
    private static void stopHarm(Player p) {
        p.setFireTicks(0);
        p.removePotionEffect(PotionEffectType.WITHER);
        p.removePotionEffect(PotionEffectType.POISON);
    }

    /** No hit lands on a hunter of a won or lost hunt while they are still in its hollow. */
    boolean sheltered(Player p) { return finished() && p.getWorld() == arena.world(); }

    List<String> names() {
        List<String> n = new ArrayList<>();
        for (PS s : ps.values()) n.add(s.name);
        return n;
    }

    void fail(String why) {
        if (phase == Phase.FAILED || phase == Phase.CLOSED || phase == Phase.VICTORY) return;
        failReason = why;
        if (scene != null) endScene(true);
        crawlStart = -1;
        remove(stunLabel);
        stunLabel = null;
        setPhase(Phase.FAILED);
        endAt = now() + 10_000;
        clearMobs();
        teles.clear();
        if (stomach) eject();
        for (Player p : players()) {
            PS s = ps.get(p.getUniqueId());
            if (s.ghost) { p.setGameMode(s.restore != null ? s.restore : GameMode.SURVIVAL); s.ghost = false; mod.data(p).ghostRestore = null; }
            stopHarm(p);
        }
        title("&c&lHUNT FAILED", "&7" + why, 3000);
        tell("&c&lHUNT FAILED! &7" + why + " &8No rewards this time.");
        narrate("fail", "Back to the boats! It'll be hungry again soon - we'll get it next time.");
        offerAgain();
        sound(Sound.ENTITY_WITHER_DEATH, 0.6f);
    }

    /** Remove everything and send everyone home. Idempotent. */
    void close() {
        if (phase == Phase.CLOSED) return;
        phase = Phase.CLOSED;
        scene = null;                 // no end beats: everything the scene spawned is cleared right here
        skipVotes.clear();
        scenes.clear();
        remove(stunLabel);
        stunLabel = null;
        clearMobs();
        if (boss != null) {
            mod.runs.byEntity.remove(boss.getUniqueId());
            if (boss.isValid() && Sky.mobs().isSkyMob(boss)) Sky.mobs().remove(boss);
        }
        for (Cell c : cells) { remove(c.display); remove(c.label); }
        for (Crate c : crates) { remove(c.display); remove(c.label); }
        for (Entity e : props) remove(e);
        cells.clear();
        crates.clear();
        props.clear();
        teles.clear();
        for (UUID u : new ArrayList<>(ps.keySet())) {
            PS s = ps.get(u);
            Player p = Bukkit.getPlayer(u);
            cleanupState(s, p);
            if (p != null && p.isOnline() && p.getWorld() == arena.world()) mod.sendOut(p);
            mod.runs.byPlayer.remove(u);
        }
        ps.clear();
        try { Sky.npcs().despawn(KuudraModule.NPC_FERRY, id); Sky.npcs().despawn(KuudraModule.NPC_QM, id); } catch (RuntimeException ignored) { }
        mod.runs.closed(this);
    }

    // =====================================================================================
    // HUD
    // =====================================================================================

    private void hud() {
        if (bar == null) return;
        String name;
        float prog;
        switch (phase) {
            case STARTING -> { name = "&cThe Magma Leviathan stirs..."; prog = 1f; }
            case SUPPLIES -> {
                int need = Math.min(KDials.SUPPLIES.get(), Math.max(1, pads.size()));
                name = "&6Supplies &e" + delivered + "/" + need + " &8| &7Brood wave " + wave;
                prog = (float) delivered / need;
            }
            case BUILD -> { name = "&eBuilding the ballista &a" + (int) build + "%"; prog = (float) (build / 100); }
            case BALLISTA -> {
                name = (stunned ? "&e&lSTUNNED &e" + Math.max(0, (stunUntil - now()) / 1000) + "s &8| " : "")
                        + "&cMagma Leviathan &a" + Text.shortNum(hp()) + "&f/&a" + Text.shortNum(bossMax)
                        + " &8| &7Fuel &c" + fuel + "/" + KDials.FUEL_PER_VOLLEY.get();
                prog = (float) Math.max(0, Math.min(1, hp() / bossMax));
            }
            case DPS, LAIR -> {
                name = (phase == Phase.LAIR ? "&4&lLAIR &8| " : "&c&lKILL IT &8| ") + "&cMagma Leviathan &a" + Text.shortNum(hp()) + "&f/&a" + Text.shortNum(bossMax);
                prog = (float) Math.max(0, Math.min(1, hp() / bossMax));
            }
            case FAILED -> { name = "&c&lHUNT FAILED &8| &7back to the dock in &e" + Math.max(0, (endAt - now()) / 1000) + "s"; prog = 0f; }
            case VICTORY -> {
                long left = Math.max(0, endAt - now());
                name = "&a&lVICTORY &7- claim your chests &8| &7home in &e" + (left / 1000) + "s &8| &a/kuudra again";
                long span = Math.max(1, KDials.REWARD_SECONDS.get() * 1000L);
                prog = (float) Math.min(1, left / (double) span);
            }
            default -> { return; }
        }
        try { bar.name(Text.c(name)); bar.progress(Math.max(0f, Math.min(1f, prog))); } catch (RuntimeException ignored) { }
    }

    /** Two sidebar lines for a hunter. */
    List<String> sidebar(Player p) {
        PS s = ps.get(p.getUniqueId());
        String obj = switch (phase) {
            case PREPARING, STARTING -> "&7Preparing...";
            case SUPPLIES -> "&6Supplies &e" + delivered + "/" + Math.min(KDials.SUPPLIES.get(), Math.max(1, pads.size()));
            case BUILD -> "&eBuild &a" + (int) build + "%";
            case BALLISTA -> stunned ? "&e&lSTUNNED!" : "&cFuel &e" + fuel + "/" + KDials.FUEL_PER_VOLLEY.get() + " &7" + (int) pct() + "%";
            case DPS -> "&c&lKILL IT &7" + (int) pct() + "%";
            case LAIR -> "&4&lLAIR &7" + (int) pct() + "%";
            case VICTORY -> "&a&lVICTORY";
            default -> "&c&lFAILED";
        };
        return List.of("&c☠ Leviathan " + tier.colored(), " " + obj + " &8| &6" + (s == null ? 0 : s.tokens) + "⛃ &8" + KMath.clock(now() - (started == 0 ? now() : started)));
    }

    /** Action bar segment: what you are carrying. */
    String actionBar(Player p) {
        PS s = ps.get(p.getUniqueId());
        if (s == null) return null;
        if (s.ghost) return "&8☠ GHOST";
        return switch (s.carry) {
            case SUPPLY -> "&6❖ Supplies";
            case FUEL -> "&c❖ Fuel";
            default -> "&6" + s.tokens + " ⛃ tokens";
        };
    }

    /** Spend tokens on a perk. */
    boolean buy(Player p, KCatalog.Perk perk) {
        PS s = ps.get(p.getUniqueId());
        if (s == null || !fighting() && phase != Phase.STARTING) { Msg.error(p, "Perks are bought during a hunt."); return false; }
        int lvl = s.perk(perk.id);
        int cost = perk.costOf(lvl);
        if (cost < 0) { Msg.error(p, perk.name + " is already maxed."); return false; }
        if (s.tokens < cost) { Msg.error(p, "You need " + cost + " tokens (you have " + s.tokens + ")."); return false; }
        s.tokens -= cost;
        if (perk.consumable()) {
            if ("mending_tonic".equals(perk.id)) Sky.vitals().heal(p, Sky.vitals().maxHealth(p) * perk.per / 100.0);
            Msg.success(p, "Used " + perk.name + "!");
        } else {
            s.perks.put(perk.id, lvl + 1);
            Msg.success(p, "Bought " + perk.name + " " + Text.roman(lvl + 1) + "!");
            Sky.stats().invalidate(p);
        }
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.3f);
        return true;
    }

    String phaseName() {
        return switch (phase) {
            case PREPARING -> "Preparing";
            case STARTING -> "Starting";
            case SUPPLIES -> "Supplies";
            case BUILD -> "Build";
            case BALLISTA -> stunned ? "Ballista (stunned)" : "Ballista";
            case DPS -> "Kill";
            case LAIR -> "Lair";
            case VICTORY -> "Victory";
            case FAILED -> "Failed";
            case CLOSED -> "Closed";
        };
    }

    /** Admin: jump to a phase. */
    void skipTo(String what) {
        if (scene != null) endScene(true);
        switch (what) {
            case "supplies" -> { if (phase == Phase.STARTING) countdown = 0; }
            case "build" -> { if (phase == Phase.STARTING) { countdown = 0; starting(); } delivered = 99; if (phase == Phase.SUPPLIES) toBuild(); }
            case "ballista" -> { if (phase.ordinal() < Phase.BALLISTA.ordinal()) { if (phase == Phase.STARTING) { countdown = 0; starting(); } toBallista(); } fuel = KDials.FUEL_PER_VOLLEY.get(); }
            case "dps" -> { if (phase.ordinal() < Phase.BALLISTA.ordinal()) skipTo("ballista"); if (boss != null) { Sky.mobs().setHealth(boss, bossMax * f.dpsAtPercent / 100.0); } toDps(); }
            case "lair" -> { if (phase != Phase.DPS) skipTo("dps"); if (tier.lair) { if (boss != null) Sky.mobs().setHealth(boss, lairHp()); toLair(); } }
            case "stun" -> { if (phase == Phase.BALLISTA) stun(); }
            case "win" -> { if (boss != null && boss.isValid()) Sky.mobs().setHealth(boss, 0); else victory(); }
            default -> { }
        }
    }
}
