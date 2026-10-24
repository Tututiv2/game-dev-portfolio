package com.skyclone.mod.hub;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.hub.gen.HubAnchors;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Target Practice at the Archery Range (research 03 section 2.2: "Jax's Target Practice unlocks better
 * arrows"). Talk to Range Master Corliss, press Start, and for {@code hub.practice-seconds} one of the four
 * targets glows for you alone (a per-player block change plus particles): an arrow into the glowing one
 * from behind the shooting line scores and lights another. Arrows are your own, so the Fletcher's stall
 * next door matters. Medal tiers pay coins and SkyBlock XP once each, and are readable by any module as
 * the requirement {@code hub:practice:<1-4>} (shops can gate better arrows on it). Several players can
 * practise at once: every glow and score is per player.
 */
final class TargetPractice implements Listener {

    /** A medal: score needed in one round, name, colour, one-time coins (TUNE: the reference publishes none). */
    record Tier(int score, String name, String colour, double coins, Material icon) {}

    static final List<Tier> TIERS = List.of(
            new Tier(5, "Bronze", "&6", 250, Material.COPPER_INGOT),
            new Tier(10, "Silver", "&f", 750, Material.IRON_INGOT),
            new Tier(15, "Gold", "&e", 1500, Material.GOLD_INGOT),
            new Tier(20, "Diamond", "&b", 3000, Material.DIAMOND));
    static final int SBXP_PER_TIER = 5;

    /** Highest tier (1-based, 0 = none) a score reaches. Pure. */
    static int tierFor(int score) {
        int t = 0;
        for (int i = 0; i < TIERS.size(); i++) if (score >= TIERS.get(i).score()) t = i + 1;
        return t;
    }

    /** The next target to light: any of {@code count} except {@code prev}. Pure given {@code roll} in [0,1). */
    static int nextTarget(int prev, int count, double roll) {
        if (count <= 1) return 0;
        if (prev < 0 || prev >= count) return (int) (roll * count) % count;   // the first target: any of them
        int pick = (int) (roll * (count - 1)) % (count - 1);
        return pick >= prev ? pick + 1 : pick;
    }

    /** Score and lit target after an arrow lands in target {@code hit}. */
    record Step(int score, int active, boolean right) {}

    /**
     * What one arrow in a target does. Pure given {@code roll} in [0,1).
     * <ul>
     *   <li>The lit target: +1, and another one lights (never the same one).</li>
     *   <li>A dark target: {@code -penalty} (never below 0) AND the light moves to any target but the one
     *       just hit.</li>
     * </ul>
     * Wave-3 playtest: shooting the four targets in turn without looking reached Gold (17), because a wrong
     * target cost nothing and the light stayed put, so a fixed rotation was guaranteed to reach it within
     * three arrows. A penalty alone does not fix that (one point per cycle against one wrong arrow per cycle
     * on average is a fair coin, which still drifts into the medals); moving the light on a miss is what
     * breaks the rotation: every blind arrow is then a one-in-three guess, and the score drifts down.
     */
    static Step step(int score, int active, int hit, int count, double roll, int penalty) {
        if (hit == active) return new Step(score + 1, nextTarget(active, count, roll), true);
        return new Step(Math.max(0, score - Math.max(0, penalty)), nextTarget(hit, count, roll), false);
    }

    /** One player's round. */
    static final class Session {
        final UUID player;
        final long endsAt;
        /** {@code hits} counts lit-target arrows; it is NOT the score once a dark target can cost a point. */
        int score, shots, misses, hits, active = -1, refresh;
        /** A short note for the action-bar segment ("Wrong target! -1", "GOLD!") and when it ends. */
        String note;
        long noteUntil;
        Session(UUID player, long endsAt) { this.player = player; this.endsAt = endsAt; }
        void note(String text, long now) { note = text; noteUntil = now + 1500; }
    }

    static final String NPC_ID = "hub_range_master";
    static final TargetPractice INSTANCE = new TargetPractice();

    final Map<UUID, Session> sessions = new HashMap<>();
    /** Arrow id -> whether it was loosed from behind the shooting line. */
    final Map<UUID, Boolean> arrows = new HashMap<>();
    private BukkitTask task;

    private TargetPractice() {}

    // ---- setup ----------------------------------------------------------------------------------

    static void registerRequirement() {
        Sky.requirements().register("hub", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (parts.length < 3 || !Sky.profiles().isReady(p)) return false;
                HubData d = HubModule.data(p);
                return switch (parts[1]) {
                    case "practice" -> d.practiceTier >= num(parts[2]);
                    case "guide" -> d.guidesDone.contains(parts[2]);
                    case "met" -> met(d, parts[2]);
                    case "errand" -> d.errands.getOrDefault(parts[2], 0) == HubErrands.DONE;
                    default -> false;
                };
            }
            @Override public String describe(String[] parts) {
                if (parts.length >= 3 && parts[1].equals("practice")) {
                    int t = Math.max(1, Math.min(TIERS.size(), num(parts[2])));
                    Tier tier = TIERS.get(t - 1);
                    return tier.colour() + tier.name() + " &7in Target Practice &8(Archery Range)";
                }
                if (parts.length >= 3 && parts[1].equals("guide")) {
                    HubGuides.Guide g = HubGuides.byId(parts[2]);
                    return "&afinishing the " + (g == null ? parts[2] : g.name) + " District Guide";
                }
                if (parts.length >= 3 && parts[1].equals("met")) {
                    HubNpcs.Spec s = metSpec(parts[2]);
                    return "&ameeting " + (s == null ? parts[2] : s.name) + " &ain the Hub";
                }
                if (parts.length >= 3 && parts[1].equals("errand")) {
                    Errands.Errand e = HubErrands.byId(parts[2]);
                    return "&afinishing the errand &e" + (e == null ? parts[2] : e.name) + " &8(/errands)";
                }
                return "&a" + String.join(":", parts);
            }
        });
    }

    /**
     * {@code hub:met:<key>}: the player has talked to that hub NPC. The key is the NPC id ({@code hub_andrew}), the id
     * without its prefix ({@code andrew}) or the reference key in npcs.json ({@code madame_eleanor_q_goldsworth_iii}),
     * so the garden can gate its hub-home visitors on meeting them at home (research 08: "tied to its home NPC
     * having been met").
     */
    static boolean met(HubData d, String key) {
        HubNpcs.Spec s = metSpec(key);
        return d.talks.getOrDefault(s == null ? key : s.id, 0) > 0;
    }

    static HubNpcs.Spec metSpec(String key) {
        for (HubNpcs.Spec s : HubNpcs.SPECS)
            if (s.id.equals(key) || s.id.equals("hub_" + key) || key.equals(s.ref)) return s;
        return null;
    }

    static int num(String s) { try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return Integer.MAX_VALUE; } }

    void registerNpc() {
        NpcDef def = NpcDef.of(NPC_ID, "&fRange Master Corliss", HubModule.ISLAND, "npc.range_master").title("&a&lTARGET PRACTICE");
        def.hand = "BOW";
        def.lines.add("&7Test your aim! &eClick me!");
        Sky.npcs().register(def, this::click);
        // the round's score and clock ride on core's action bar as a segment AFTER health, defense and mana:
        // sending our own action bar every 5 ticks (wave 3) replaced the HP/Defense bar for the whole round
        Sky.vitals().actionBarSegment("hub_practice", 400, this::segment);
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::tick, 5L, 5L);
    }

    /** The action-bar segment for a player in a round (null = not practising). */
    String segment(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return null;
        long now = System.currentTimeMillis();
        long left = Math.max(0, (s.endsAt - now + 999) / 1000);
        String bar = "&a&lPRACTICE &a" + s.score + " &8| &" + (left <= 5 ? "c" : "e") + left + "s";
        if (s.note != null && now < s.noteUntil) bar += " " + s.note;
        return bar;
    }

    void click(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        HubData d = HubModule.data(p);
        int n = d.talks.getOrDefault(NPC_ID, 0);
        d.talks.put(NPC_ID, n + 1);
        if (n == 0) {
            Sky.npcs().talk(p, NPC_ID, List.of(
                    "&fSteady hands, traveller? Four targets down the far end of the range.",
                    "&fWhen I say go, one of them glows. Put an arrow in the glowing one and another lights up. Hit a dark one and you lose a point.",
                    "&fShoot from behind the line, mind. Bring your own arrows; Fletcher Quill sells them right there.",
                    "&fBronze at " + TIERS.get(0).score() + ", Diamond at " + TIERS.get(TIERS.size() - 1).score()
                            + ". Every medal pays once, and the Fletcher saves his best arrows for proper shots."),
                    () -> menu(p).open(p));
            return;
        }
        menu(p).open(p);
    }

    // ---- menu -----------------------------------------------------------------------------------

    Menu menu(Player p) {
        HubData d = Sky.profiles().isReady(p) ? HubModule.data(p) : new HubData();
        Menu m = new Menu("Target Practice", 5).chrome();
        int secs = Math.max(10, HubModule.practiceSeconds.get());
        m.set(4, Icons.of(Material.BOW, "&aTarget Practice",
                "&7Hit the &aglowing &7target from", "&7behind the shooting line. Each", "&7hit lights another one.", "&7A &cdark &7target costs a point", "&7and moves the light.", "",
                "&7Round: &e" + secs + "s", "&7Best score: &a" + d.practiceBest, "&7Rounds played: &e" + d.practiceRuns));
        int[] slots = {20, 21, 23, 24};
        double scale = Math.max(0, HubModule.practiceCoinScale.get());
        for (int i = 0; i < TIERS.size() && i < slots.length; i++) {
            Tier t = TIERS.get(i);
            boolean got = d.practiceTier > i;
            List<String> lore = new ArrayList<>();
            lore.add("&7Score &e" + t.score() + " &7in one round.");
            lore.add("");
            lore.add("&7Reward (once):");
            lore.add("  &6+" + Text.commasInt(t.coins() * scale) + " Coins");
            lore.add("  &b+" + SBXP_PER_TIER + " SkyBlock XP");
            lore.add("");
            lore.add(got ? "&a&lEARNED" : "&cNot earned yet");
            m.set(slots[i], Icons.of(got ? t.icon() : Material.GRAY_DYE, t.colour() + t.name() + " Medal", lore));
        }
        boolean running = sessions.containsKey(p.getUniqueId());
        if (running) {
            m.set(31, Icons.of(Material.RED_DYE, "&cStop Practice", "&7End this round now.", "", "&eClick to stop!"), c -> {
                c.player().closeInventory();
                finish(c.player(), true);
            });
        } else {
            m.set(31, Icons.of(Material.LIME_DYE, "&aStart Practice", "&7A " + secs + " second round.", "", hasBow(p) ? "&eClick to start!" : "&cYou need a bow!"),
                    c -> { c.player().closeInventory(); start(c.player()); });
        }
        return m;
    }

    static boolean hasBow(Player p) {
        for (ItemStack it : p.getInventory().getContents())
            if (it != null && (it.getType() == Material.BOW || it.getType() == Material.CROSSBOW)) return true;
        return false;
    }

    // ---- the round ----------------------------------------------------------------------------

    /** Start a round; the reason it cannot start, or null. */
    String start(Player p) {
        String why = !HubModule.practiceOn.get() ? "Target Practice is closed right now."
                : !Sky.profiles().isReady(p) ? "Your profile is still loading."
                : !HubModule.ISLAND.equals(Sky.worlds().islandOf(p.getWorld())) ? "Target Practice is at the Hub's Archery Range."
                : targets().size() < 2 ? "The range's targets are missing (the hub is still being built?)."
                : sessions.containsKey(p.getUniqueId()) ? "You are already practising!"
                : !hasBow(p) ? "You need a bow! The Weaponsmith and the Fletcher sell them." : null;
        if (why != null) { Msg.error(p, why); return why; }
        long secs = Math.max(10, HubModule.practiceSeconds.get());
        Session s = new Session(p.getUniqueId(), System.currentTimeMillis() + secs * 1000L);
        sessions.put(p.getUniqueId(), s);
        light(p, s, nextTarget(-1, targets().size(), ThreadLocalRandom.current().nextDouble()));
        p.showTitle(net.kyori.adventure.title.Title.title(Text.c("&a&lGO!"), Text.c("&7Hit the glowing target"),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(900), java.time.Duration.ofMillis(300))));
        Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 1.6f);
        return null;
    }

    /** The four target blocks, from the layout (empty before the hub is built). */
    static List<Location> targets() {
        Layout l = Sky.worlds().layout(HubModule.ISLAND);
        World w = Sky.worlds().world(HubModule.ISLAND);
        List<Location> out = new ArrayList<>();
        if (l == null || w == null) return out;
        for (String a : HubAnchors.ARCHERY_TARGETS) {
            Layout.Anchor an = l.anchor(a);
            if (an != null) out.add(new Location(w, Math.floor(an.x()), Math.floor(an.y()), Math.floor(an.z())));
        }
        return out;
    }

    static Box area(String name) {
        Layout l = Sky.worlds().layout(HubModule.ISLAND);
        return l == null ? null : l.area(name);
    }

    /** Show target {@code i} glowing to this player (and put the previous one back). */
    void light(Player p, Session s, int i) {
        List<Location> t = targets();
        if (s.active >= 0 && s.active < t.size()) restore(p, t.get(s.active));
        s.active = i;
        if (i >= 0 && i < t.size()) glow(p, t.get(i));
    }

    static void glow(Player p, Location at) {
        p.sendBlockChange(at, Bukkit.createBlockData(Material.REDSTONE_LAMP, "[lit=true]"));
        p.sendBlockChange(at.clone().add(0, 1, 0), Bukkit.createBlockData(Material.SEA_LANTERN));
    }

    static void restore(Player p, Location at) {
        p.sendBlockChange(at, at.getBlock().getBlockData());
        p.sendBlockChange(at.clone().add(0, 1, 0), at.clone().add(0, 1, 0).getBlock().getBlockData());
    }

    void tick() {
        if (sessions.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<Location> t = targets();
        Box range = area("archery");
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player);
            if (p == null) { sessions.remove(s.player); continue; }
            Location at = p.getLocation();
            boolean away = !HubModule.ISLAND.equals(Sky.worlds().islandOf(p.getWorld()))
                    || range != null && !range.grow(4).contains(at.getX(), at.getY(), at.getZ());
            if (away) { Msg.send(p, "&cYou left the Archery Range: Target Practice stopped."); finish(p, false); continue; }
            if (now >= s.endsAt) { finish(p, false); continue; }
            if (s.active >= 0 && s.active < t.size()) {
                // the glow is a per-player block change: a chunk re-send (walking off and back, a relog of the
                // view, a block update next to it) silently puts the real block back on the client, so the
                // round would go on with no visible target. Re-assert it once a second.
                if (++s.refresh % 4 == 0) glow(p, t.get(s.active));
                Location c = t.get(s.active).clone().add(0.5, 0.5, 0.5);
                p.spawnParticle(Particle.DUST, c, 6, 0.45, 0.45, 0.45, 0, new Particle.DustOptions(Color.LIME, 1.4f));
            }
            // score and time: core's action bar shows our segment (see segment()), never a bar of our own
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p) || !(e.getProjectile() instanceof AbstractArrow a)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        s.shots++;
        Box line = area("archery.line");
        Location at = p.getLocation();
        arrows.put(a.getUniqueId(), line != null && line.contains(at.getX(), at.getY(), at.getZ()));
        if (arrows.size() > 256) arrows.clear();   // stray arrows that never landed
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof AbstractArrow a)) return;
        Boolean fromLine = arrows.remove(a.getUniqueId());
        if (fromLine == null || !(a.getShooter() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        Block b = e.getHitBlock();
        List<Location> t = targets();
        int hit = -1;
        if (b != null) for (int i = 0; i < t.size(); i++) {
            Location l = t.get(i);
            if (b.getX() == l.getBlockX() && b.getY() == l.getBlockY() && b.getZ() == l.getBlockZ()
                    || b.getX() == l.getBlockX() && b.getY() == l.getBlockY() + 1 && b.getZ() == l.getBlockZ()) hit = i;
        }
        if (hit < 0) { s.misses++; return; }
        if (!fromLine) {
            Msg.send(p, "&cThat one doesn't count: shoot from behind the shooting line!");
            Msg.sound(p, Sound.ENTITY_VILLAGER_NO, 1f);
            return;
        }
        int penalty = Math.max(0, HubModule.practicePenalty.get());
        Step st = step(s.score, s.active, hit, t.size(), ThreadLocalRandom.current().nextDouble(), penalty);
        long nowMs = System.currentTimeMillis();
        if (!st.right()) {
            s.misses++;
            int lost = s.score - st.score();
            s.score = st.score();
            s.note(lost > 0 ? "&cWrong target! -" + lost : "&cWrong target!", nowMs);
            Msg.sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f);
            light(p, s, st.active());   // the light moves: a blind rotation can no longer walk onto it
            return;
        }
        int was = tierFor(s.score);
        s.hits++;
        s.score = st.score();
        Msg.sound(p, Sound.ENTITY_ARROW_HIT_PLAYER, 1.2f);
        int now = tierFor(s.score);
        if (now > was) {
            Tier tier = TIERS.get(now - 1);
            s.note(tier.colour() + "&l" + tier.name().toUpperCase() + "!", nowMs);
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
        }
        light(p, s, st.active());
    }

    /** End a round, show the result and pay any new medal. */
    void finish(Player p, boolean stopped) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        List<Location> t = targets();
        if (s.active >= 0 && s.active < t.size()) restore(p, t.get(s.active));
        if (!Sky.profiles().isReady(p)) return;
        HubData d = HubModule.data(p);
        d.practiceRuns++;
        boolean best = s.score > d.practiceBest;
        if (best) d.practiceBest = s.score;
        int tier = tierFor(s.score);
        List<String> body = new ArrayList<>();
        body.add("&7Score: &a" + s.score + (best && s.score > 0 ? " &6&lNEW BEST!" : " &8(best " + d.practiceBest + ")"));
        body.add("&7Arrows loosed: &e" + s.shots + "&7, accuracy &e" + (s.shots == 0 ? 0 : Math.round(100.0 * s.hits / s.shots)) + "%");
        if (tier > 0) body.add("&7Medal: " + TIERS.get(tier - 1).colour() + TIERS.get(tier - 1).name());
        double scale = Math.max(0, HubModule.practiceCoinScale.get());
        boolean paid = false;
        for (int i = d.practiceTier; i < tier; i++) {
            Tier tt = TIERS.get(i);
            if (!paid) { body.add(""); body.add("&a&lNEW MEDAL REWARDS"); paid = true; }
            double coins = tt.coins() * scale;
            if (coins > 0) { Sky.economy().deposit(p, coins, "hub: target practice " + tt.name()); body.add("  " + tt.colour() + tt.name() + " &6+" + Text.commasInt(coins) + " Coins"); }
            if (Sky.has(Features.SkyLevel.class)) {
                Sky.service(Features.SkyLevel.class).award(p, "hub_practice:" + (i + 1), SBXP_PER_TIER, "Target Practice " + tt.name());
                body.add("  &b+" + SBXP_PER_TIER + " SkyBlock XP");
            }
        }
        if (tier > d.practiceTier) d.practiceTier = tier;
        if (tier < TIERS.size()) body.add("&7Next medal: " + TIERS.get(tier).colour() + TIERS.get(tier).name() + " &7at &e" + TIERS.get(tier).score());
        Msg.box(p, stopped ? "&c&lPRACTICE STOPPED" : "&a&lTARGET PRACTICE OVER", body);
        Msg.sound(p, paid ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_NOTE_BLOCK_CHIME, 1.2f);
        HubGuides.progress(p, "practice", s.score);
        Sky.profiles().saveNow(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.remove(e.getPlayer().getUniqueId());
        if (s != null && Sky.profiles().isReady(e.getPlayer())) HubModule.data(e.getPlayer()).practiceRuns++;
    }

    void stopAll() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) finish(p, true); else sessions.remove(id);
        }
        if (task != null) { task.cancel(); task = null; }
    }

    /** Admin info line. */
    String info() {
        StringBuilder sb = new StringBuilder("&7Target Practice: &e" + sessions.size() + " &7running, " + targets().size() + " targets");
        for (Session s : sessions.values()) {
            Player p = Bukkit.getPlayer(s.player);
            sb.append("\n&7- &f").append(p == null ? s.player : p.getName()).append(" &7score &a").append(s.score)
                    .append(" &7shots &e").append(s.shots).append(" &7target #").append(s.active + 1)
                    .append(" &7left &e").append(Math.max(0, (s.endsAt - System.currentTimeMillis()) / 1000)).append("s");
        }
        return sb.toString();
    }
}
