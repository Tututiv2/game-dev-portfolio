package com.skyclone.mod.kuudra;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.util.Requirements;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.kuudra.gen.KuudraArenaGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Level;

/**
 * The Magma Leviathan hunt (reference: Kuudra), research 04 sec 4: five tiers gated by Crimson Isle faction
 * reputation, a 4-phase instanced fight (supplies, ballista build, fuel/stun/fire, DPS) plus the Infernal lair,
 * the brood waves, an in-run token perk shop, crews of up to four (solo works), and the free + paid reward
 * chests. Entry: the crimson module's Leviathan dock ({@code npc.leviathan_dock}, area {@code leviathan_passage}),
 * {@code /kuudra}, and {@code /warp kuudra}.
 */
public final class KuudraModule implements GameModule {

    public static final String ID = "kuudra";
    static final String NPC_FERRY = "kuudra_ferryman";
    static final String NPC_QM = "kuudra_quartermaster";
    static final String CRIMSON_DOCK_NPC = "leviathan_dock";
    static final String RUN_ITEM = "KUUDRA_SHOP_ITEM";

    KCatalog cat;
    Runs runs;
    final Crew crew = new Crew();
    KMenus menus;

    @Override public String id() { return ID; }

    KData data(Player p) { return Sky.profiles().member(p, ID, KData.class); }

    static Reader res(String file) {
        InputStream in = Sky.plugin().getResource("data/kuudra/" + file);
        if (in == null) throw new IllegalStateException("missing data/kuudra/" + file);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    @Override
    public void enable() {
        try (Reader t = res("tiers.json"); Reader p = res("perks.json"); Reader l = res("loot.json"); Reader n = res("npcs.json")) {
            cat = KCatalog.load(t, p, l, n);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("kuudra data", e);
        }
        KDials.register(cat);
        Arena.TAG = new NamespacedKey(Sky.plugin(), "kuudra_run");
        Scenes.FREEZE = new NamespacedKey(Sky.plugin(), "kuudra_freeze");
        runs = new Runs(this);
        menus = new KMenus(this);

        Sky.items().loadJson(ID, "items.json");
        Sky.mobs().loadJson(ID, "mobs.json");
        registerSalvageRecipe();
        registerBehaviours();

        IslandDef def = IslandDef.of(KuudraArenaGenerator.ISLAND, "Leviathan's Hollow", new KuudraArenaGenerator());
        def.instanced = true;
        def.instanceSpacing = 512;
        def.environment = "NETHER";
        def.fixedTime = 18000;
        Sky.worlds().registerIsland(def);
        // /warp kuudra -> the dock on the Crimson Isle (the hollow itself is per-hunt)
        Sky.worlds().registerWarp("kuudra", "crimson_isle", "npc.leviathan_dock", "Leviathan's Hollow", "visited:crimson_isle");

        registerNpcs();
        Sky.db().migrate(ID, 1, "CREATE TABLE kuudra_arenas (k TEXT PRIMARY KEY, version INTEGER NOT NULL)");
        try {
            Sky.db().query(c -> {
                try (var st = c.createStatement(); var rs = st.executeQuery("SELECT k, version FROM kuudra_arenas")) {
                    while (rs.next()) runs.versions.put(rs.getString(1), rs.getInt(2));
                }
                return null;
            }).join();
        } catch (RuntimeException e) {
            Sky.log().log(Level.WARNING, "[kuudra] could not read arena versions (arenas will rebuild)", e);
        }

        Sky.requirements().register("kuudra", new Requirements.Resolver() {
            @Override public boolean check(Player p, String[] parts) {
                if (!Sky.profiles().isReady(p) || parts.length < 2) return false;
                KData d = data(p);
                // kuudra:intro - Dockhand Nell has sent you out (The Leviathan's Wake, up to the HUNT step);
                // kuudra:intro:done - the whole questline
                if (parts[1].equalsIgnoreCase("intro"))
                    return parts.length > 2 && parts[2].equalsIgnoreCase("done") ? intro(p) >= KIntro.DONE : KIntro.unlocked(intro(p));
                int need = parts.length > 2 ? parseInt(parts[2], 1) : 1;
                if (parts[1].equalsIgnoreCase("any")) return d.totalCompletions() >= need;
                KCatalog.Tier t = cat.tier(parts[1]);
                return t != null && d.completions(t.id) >= need;
            }

            @Override public String describe(String[] parts) {
                if (parts.length > 1 && parts[1].equalsIgnoreCase("intro"))
                    return parts.length > 2 && parts[2].equalsIgnoreCase("done") ? "&acompleting &e" + KIntro.NAME
                            : "&aDockhand Nell's go-ahead &8(" + KIntro.NAME + ")";
                KCatalog.Tier t = parts.length > 1 ? cat.tier(parts[1]) : null;
                int need = parts.length > 2 ? parseInt(parts[2], 1) : 1;
                String what = t == null ? "&cMagma Leviathan" : t.colored() + " &cLeviathan";
                return need <= 1 ? "&aa clear of the " + what : "&a" + need + " clears of the " + what;
            }
        });

        Sky.abilities().register("kuudra_open_shop", use -> {
            if (runs.of(use.player()) == null) { Msg.error(use.player(), "Perks are only for use during a hunt."); return false; }
            menus.perks(use.player());
            return true;
        });

        // perk effects
        Sky.combat().addModifier("kuudra_perks", 400, ctx -> {
            Player atk = ctx.playerAttacker();
            if (atk != null && ctx.victim instanceof LivingEntity le) {
                Run r = runs.of(atk);
                if (r != null && runs.runOf(le) == r) {
                    Run.PS s = r.ps.get(atk.getUniqueId());
                    if (s != null) ctx.additive += s.perk("ember_edge") * r.perkPer("ember_edge");
                }
            }
            Player vic = ctx.playerVictim();
            if (vic != null && ctx.attacker instanceof LivingEntity src) {
                Run r = runs.of(vic);
                if (r != null) {
                    Run.PS s = r.ps.get(vic.getUniqueId());
                    if (s != null) ctx.multiplicative *= Math.max(0.1, 1 - s.perk("lava_hide") * r.perkPer("lava_hide") / 100.0);
                    Run owner = runs.runOf(src);
                    if (owner == r && (r.boss == null || !src.getUniqueId().equals(r.boss.getUniqueId())))
                        ctx.multiplicative *= Math.max(0, KDials.MOB_DMG.get())
                                * KMath.broodDamageScale(r.ps.size(), KDials.SOLO_MOB_DMG.get());   // a smaller crew, a gentler swarm
                }
            }
        });
        Sky.stats().addProvider("kuudra", (p, map) -> {
            Run r = runs.of(p);
            if (r == null) return;
            Run.PS s = r.ps.get(p.getUniqueId());
            if (s != null && s.perk("fleetfoot") > 0) map.add(Stat.SPEED, s.perk("fleetfoot") * r.perkPer("fleetfoot"));
        });

        Sky.sidebar().section("kuudra", 50, p -> {
            if (!KDials.SIDEBAR.get()) return List.of();
            Run r = runs.of(p);
            if (r == null) return List.of();
            // wave-4 endgame playtest: a won hunt keeps its members (for Hunt Again), so "Leviathan Basic VICTORY"
            // sat at the top of the sidebar (priority 50) on every island the player warped to afterwards.
            if (r.phase == Run.Phase.VICTORY && p.getWorld() != r.arena.world()) return List.of();
            return r.sidebar(p);
        });
        // the intro questline's objective, on the Crimson Isle until Nell has sent you out (and once more for the hunt)
        Sky.sidebar().section("kuudra_intro", 60, p -> {
            if (!KDials.SIDEBAR.get() || !Sky.profiles().isReady(p) || runs.of(p) != null) return List.of();
            if (!"crimson_isle".equals(Sky.worlds().islandOf(p.getLocation()))) return List.of();
            KData d = data(p);
            int step = intro(p);
            String obj = KIntro.objective(step, d.introKills, KDials.INTRO_KILLS.get());
            if (obj == null) return List.of();
            return List.of("&c" + KIntro.NAME, " &e" + obj);
        });
        Sky.vitals().actionBarSegment("kuudra", 300, p -> {
            Run r = runs.of(p);
            if (r == null || (r.phase == Run.Phase.VICTORY && p.getWorld() != r.arena.world())) return null;   // same as the sidebar
            return r.actionBar(p);
        });

        Bukkit.getPluginManager().registerEvents(runs, Sky.plugin());
        Sky.worlds().addBreakHandler(runs.breakHandler());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), runs::tick, 20L, 5L);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), runs::dockPoll, 40L, 10L);

        KCommands.register(this);
        KTests.register(this);
    }

    @Override
    public void postEnable() {
        if (KDials.TAKE_OVER_DOCK.get()) {
            try {
                var placeholder = Sky.dials().flag("crimson.leviathan-placeholder", true, "Run the dockhand at the Leviathan dock (turn off when the kuudra module owns it)");
                if (Boolean.TRUE.equals(placeholder.get())) placeholder.set(false);
            } catch (RuntimeException e) {
                Sky.log().log(Level.INFO, "[kuudra] could not switch off the crimson dock placeholder: " + e);
            }
        }
    }

    @Override
    public void disable() {
        if (runs != null) runs.closeAll();
    }

    static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; }
    }

    // =====================================================================================
    // NPCs
    // =====================================================================================

    private void registerNpcs() {
        NpcDef ferry = NpcDef.of(NPC_FERRY, "&6Ferryman Brask", KuudraArenaGenerator.ISLAND, "npc.ferryman").title("&e&lREWARDS & EXIT");
        String fs = cat.npcSkins.get("ferryman");
        if (fs != null) ferry.skin(fs, null);
        Sky.npcs().register(ferry, p -> {
            Run r = runs.of(p);
            if (r == null) { sendOut(p); return; }
            menus.run(p);
        });
        NpcDef qm = NpcDef.of(NPC_QM, "&eQuartermaster Ysra", KuudraArenaGenerator.ISLAND, "npc.quartermaster").title("&e&lPERKS");
        String qs = cat.npcSkins.get("quartermaster");
        if (qs != null) qm.skin(qs, null);
        Sky.npcs().register(qm, p -> {
            if (runs.of(p) == null) return;
            menus.perks(p);
        });
    }

    // =====================================================================================
    // brood behaviours
    // =====================================================================================

    private void registerBehaviours() {
        Sky.mobs().registerBehaviour("kuudra_boss", new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                if (tick % 4 == 0) mob.getWorld().spawnParticle(Particle.LAVA, mob.getLocation().add(0, 2, 0), 3, 4, 1, 4, 0);
            }
        });
        MobBehaviour explode = new MobBehaviour() {
            @Override public void onTick(LivingEntity mob, MobDef def, long tick) {
                double trigger = "kuudra_landmine".equals(firstBehaviour(def)) ? 2.4 : 1.8;
                for (Player p : mob.getWorld().getPlayers()) {
                    if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.getLocation().distanceSquared(mob.getLocation()) > trigger * trigger) continue;
                    blast(mob, def);
                    return;
                }
            }
        };
        Sky.mobs().registerBehaviour("kuudra_landmine", explode);
        Sky.mobs().registerBehaviour("kuudra_imp", explode);
        Sky.mobs().registerBehaviour("kuudra_knocker", new MobBehaviour() {
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
                Vector v = target.getLocation().toVector().subtract(mob.getLocation().toVector()).setY(0);
                Vector push = v.lengthSquared() < 1e-4 ? new Vector(0, 0, 1) : v.normalize();
                Bukkit.getScheduler().runTask(Sky.plugin(), () -> target.setVelocity(push.multiply(1.6).setY(0.55)));
            }
        });
        Sky.mobs().registerBehaviour("kuudra_blight", new MobBehaviour() {
            @Override public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0, false, true, true));
            }
        });
    }

    private static String firstBehaviour(MobDef d) { return d.behaviours == null || d.behaviours.isEmpty() ? "" : d.behaviours.get(0); }

    private void blast(LivingEntity mob, MobDef def) {
        Location at = mob.getLocation();
        at.getWorld().spawnParticle(Particle.EXPLOSION, at.clone().add(0, 0.5, 0), 3, 1, 0.5, 1, 0);
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.getLocation().distanceSquared(at) > 16) continue;
            Sky.combat().damagePlayer(p, def.damage, mob, DamageContext.Kind.MOB);   // the mob-damage dial applies in kuudra_perks
        }
        runs.byEntity.remove(mob.getUniqueId());
        Sky.mobs().remove(mob);
    }

    // =====================================================================================
    // helpers for Run / menus
    // =====================================================================================

    /** The mob id of a brood kind at a tier ("follower", basic -> kuudra_follower_basic). */
    String kindId(String kind, KCatalog.Tier tier) { return "kuudra_" + kind + "_" + tier.id; }

    /** A weighted random common brood member for this wave (kinds unlock by their first wave). */
    String pickWaveMob(KCatalog.Tier tier, int wave, Random rng) {
        List<MobDef> pool = new ArrayList<>();
        int total = 0;
        for (MobDef d : Sky.mobs().all()) {
            if (d.extra == null || !d.extra.has("kuudraKind") || !d.extra.has("tier") || !tier.id.equals(d.extra.get("tier").getAsString())) continue;
            if (!"common".equals(d.extra.has("role") ? d.extra.get("role").getAsString() : "")) continue;
            int first = d.extra.has("firstWave") ? d.extra.get("firstWave").getAsInt() : 1;
            int w = d.extra.has("weight") ? d.extra.get("weight").getAsInt() : 1;
            if (wave < first || w <= 0) continue;
            pool.add(d);
            total += w;
        }
        if (pool.isEmpty() || total <= 0) return Sky.mobs().def(kindId("follower", tier)) != null ? kindId("follower", tier) : null;
        int r = rng.nextInt(total);
        for (MobDef d : pool) { r -= d.extra.get("weight").getAsInt(); if (r < 0) return d.id; }
        return pool.get(0).id;
    }

    // =====================================================================================
    // the intro questline (KIntro)
    // =====================================================================================

    private static boolean pledged(Player p) {
        try { return Sky.requirements().check(p, "faction:any"); } catch (RuntimeException e) { return false; }
    }

    /** The intro step this player has reached, worked out from their record (no side effects). */
    int intro(Player p) {
        if (!Sky.profiles().isReady(p)) return KIntro.MEET;
        KData d = data(p);
        int s = KIntro.migrate(d.introStep, d.totalCompletions());
        if (d.introduced && s == KIntro.MEET) s = KIntro.PLEDGE;      // heard the wave-3 briefing: Nell has met them
        return KIntro.advance(s, pledged(p), d.introKills, KDials.INTRO_KILLS.get(), d.totalCompletions());
    }

    /** Store the step reached and announce every step finished since the last look (main thread only). */
    int introRefresh(Player p) {
        if (!Sky.profiles().isReady(p)) return KIntro.MEET;
        KData d = data(p);
        int was = d.introStep, now = intro(p);
        if (now == was) return now;
        d.introStep = now;
        if (now < was) return now;                                       // never announce going back (admin reset)
        if (now >= KIntro.DONE && now - was > 1) introStepDone(p, now - 1, now);   // a veteran's clear: one box, not five
        else for (int s = was; s < now; s++) introStepDone(p, s, now);
        return now;
    }

    /** "QUEST STEP COMPLETE" box for step {@code s}; the next objective follows (or the questline's end). */
    void introStepDone(Player p, int s, int now) {
        KIntro.Step done = KIntro.step(s);
        if (done == null) return;
        List<String> body = new ArrayList<>();
        body.add("&a✔ &7" + done.title());
        String next = KIntro.objective(now, data(p).introKills, KDials.INTRO_KILLS.get());
        if (next != null && s == now - 1) { body.add(""); body.add("&7Next: &e" + next); }
        if (now >= KIntro.DONE && s == now - 1) { body.add(""); body.add("&7Hotter hunts open with your faction's trust."); }
        Msg.box(p, now >= KIntro.DONE && s == now - 1 ? "&6&lQUEST COMPLETE &7- &c" + KIntro.NAME : "&6&lQUEST STEP &7- &c" + KIntro.NAME, body);
        p.playSound(p.getLocation(), now >= KIntro.DONE ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
    }

    /** A Crimson Isle kill counts for "Stand the Heat". */
    void introKill(Player p, Location at) {
        if (!Sky.profiles().isReady(p)) return;
        KData d = data(p);
        if (intro(p) != KIntro.CULL) return;
        String island = Sky.worlds().islandOf(at != null ? at : p.getLocation());
        if (!"crimson_isle".equals(island)) return;
        int need = Math.max(1, KDials.INTRO_KILLS.get());
        d.introKills = Math.min(need, d.introKills + 1);
        Sky.vitals().flash(p, "&c" + KIntro.NAME + " &7- creatures slain &e" + d.introKills + "/" + need, 40);
        introRefresh(p);
    }

    /**
     * Talking to Nell (her NPC or the end of the pier). Talk steps play her lines and move on (REPORT also hands over
     * one Basic key, dial {@code kuudra.intro-key}); then {@code then} runs (the hunt board).
     */
    void introTalk(Player p, Runnable then) {
        int step = introRefresh(p);
        KIntro.Step s = KIntro.step(step);
        if (s == null || !s.talk()) { then.run(); return; }
        KData d = data(p);
        d.introduced = true;
        List<String> lines = KIntro.lines(step, pledged(p));
        Runnable finish = () -> {
            if (!p.isOnline() || !Sky.profiles().isReady(p)) return;
            KData dd = data(p);
            if (dd.introStep != step) { then.run(); return; }            // moved on meanwhile (admin)
            dd.introStep = KIntro.talk(step);
            if (step == KIntro.REPORT && KDials.INTRO_KEY.get() && !dd.introKeyGiven) {
                String key = cat.tier(0) == null ? null : cat.tier(0).key;
                if (key != null && Sky.items().exists(key)) {
                    dd.introKeyGiven = true;                              // mark before giving: it can never pay twice
                    Sky.profiles().saveNow(p);
                    Sky.loot().give(p, Sky.items().create(key, 1), LootSource.REWARD);
                    Msg.send(p, "&e[NPC] &fDockhand Nell&f: Here - one key, on the dock. Don't lose it in the lava.");
                }
            }
            introStepDone(p, step, intro(p));       // this talk step first ...
            introRefresh(p);                          // ... then anything it lets finish at once (already pledged)
            then.run();
        };
        if (!KDials.BRIEFING.get()) { finish.run(); return; }
        try {
            Sky.npcs().talk(p, CRIMSON_DOCK_NPC, lines, finish);
        } catch (RuntimeException e) {
            finish.run();
        }
    }

    /** Why this player may not hunt at this tier, or null. */
    String blocker(Player p, KCatalog.Tier t) {
        if (KDials.INTRO_REQUIRED.get() && Sky.profiles().isReady(p) && !KIntro.unlocked(intro(p)))
            return "Dockhand Nell has not sent you out yet (" + KIntro.NAME + ": " + KIntro.objective(intro(p), data(p).introKills, KDials.INTRO_KILLS.get()) + ").";
        String req = KDials.requirement(t);
        if (req != null && !req.isBlank() && !Sky.requirements().check(p, req))
            return "Requires " + Text.strip(Sky.requirements().describe(req)) + ".";
        if (KDials.REQUIRE_PREVIOUS.get() && t.index > 0) {
            KCatalog.Tier prev = cat.tier(t.index - 1);
            if (prev != null && data(p).completions(prev.id) <= 0) return "Clear the " + prev.name + " tier first.";
        }
        return null;
    }

    /** % faster carrying from the Leviathan organ accessories (Kidney 10 / Lung 20 / Heart 30), best one counts. */
    double accessoryCarryBonus(Player p) {
        var acc = Sky.service(Features.Accessories.class);
        if (acc.has(p, "KUUDRAS_HEART")) return 30;
        if (acc.has(p, "KUUDRAS_LUNG")) return 20;
        if (acc.has(p, "KUUDRAS_KIDNEY")) return 10;
        return 0;
    }

    /** % faster building from the same family (5 / 10 / 15). */
    double accessoryBuildBonus(Player p) {
        var acc = Sky.service(Features.Accessories.class);
        if (acc.has(p, "KUUDRAS_HEART")) return 15;
        if (acc.has(p, "KUUDRAS_LUNG")) return 10;
        if (acc.has(p, "KUUDRAS_KIDNEY")) return 5;
        return 0;
    }

    void giveRunItems(Player p) {
        if (!Sky.items().exists(RUN_ITEM) || Sky.items().count(p, RUN_ITEM) > 0) return;
        ItemStack it = Sky.items().create(RUN_ITEM);
        var inv = p.getInventory();
        int slot = runItemSlot(p);
        if (slot < 0) { Msg.send(p, "&7Your inventory is full - use &e/kuudra perks &7for the perk shop."); return; }
        inv.setItem(slot, it);
    }

    /**
     * Hunt Perks belongs ON THE HOTBAR, in slot 8 (index 7; index 8 is the SkyBlock Menu star). It used to be
     * {@code addItem} whenever slot 8 was taken, which with a full hotbar put it in the main inventory (the
     * 2026-10-03 playtest found it in inventory slot 15). Now: slot 8 if free; else whatever is in slot 8 moves
     * into the main inventory and Hunt Perks takes its place; else any free hotbar slot. -1 = nowhere.
     */
    private static int runItemSlot(Player p) {
        var inv = p.getInventory();
        if (isEmpty(inv.getItem(7))) return 7;
        for (int i = 9; i < 36; i++) {
            if (!isEmpty(inv.getItem(i))) continue;
            ItemStack moved = inv.getItem(7);
            inv.setItem(i, moved);
            inv.setItem(7, null);
            Msg.send(p, "&7Moved your hotbar slot 8 item into your inventory to make room for &cHunt Perks&7.");
            return 7;
        }
        for (int i = 0; i < 7; i++) if (isEmpty(inv.getItem(i))) return i;
        return -1;
    }

    private static boolean isEmpty(ItemStack s) { return s == null || s.getType().isAir(); }

    void takeRunItems(Player p) {
        if (Sky.items().exists(RUN_ITEM)) Sky.items().remove(p, RUN_ITEM, 64);
    }

    /** Back to the Leviathan dock on the Crimson Isle (or home). */
    void sendOut(Player p) {
        Location dock = Sky.worlds().anchor("crimson_isle", "npc.leviathan_dock");
        p.setFallDistance(0);
        if (dock != null && Sky.worlds().isBuilt("crimson_isle")) {
            runs.suppressDock(p);
            Location out = dock.clone().add(0, 0, 6);
            out.setYaw(0f);
            p.teleport(out);
        } else {
            Sky.worlds().sendHome(p);
        }
    }

    /** Victory: record, award, roll the chests. */
    void reward(Player p, Run run, Run.PS s, long took) {
        if (!Sky.profiles().isReady(p)) return;
        KCatalog.Tier t = run.tier;
        KData d = data(p);
        int before = d.completions(t.id);
        d.completions.merge(t.id, 1, Integer::sum);
        Long best = d.bestMillis.get(t.id);
        boolean pb = best == null || took < best;
        if (pb) d.bestMillis.put(t.id, took);
        d.tokensEarned += s.earned;
        Sky.service(Features.SkyLevel.class).award(p, "kuudra:" + t.id, t.sbXp, "Magma Leviathan (" + t.name + ")");
        Sky.service(Features.Collections.class).add(p, "BOSS_KUUDRA", t.collection);
        KData.Chest c = rollChest(p, t, run.id, new Random().nextLong());
        d.chests.add(c);
        while (d.chests.size() > Math.max(1, KDials.CHEST_CAP.get())) d.chests.remove(0);
        List<String> lines = new ArrayList<>();
        lines.add("&7Time: &e" + KMath.clock(took) + (pb ? " &d&lNEW BEST!" : " &8(best " + KMath.clock(best) + ")"));
        lines.add("&7Damage to the beast: &c" + Text.shortNum(s.damage) + " &8| &7Brood slain: &c" + s.kills);
        lines.add("&7Tokens earned: &6" + s.earned + " &8| &7Deaths: &c" + s.deaths);
        lines.add("&7Clears at " + t.colored() + "&7: &a" + (before + 1));
        lines.add("");
        lines.add("&eOpen your chests at the platform or with &a/kuudra chests&e.");
        Msg.box(p, "&6&lLEVIATHAN SLAIN &7- " + t.colored(), lines);
        introRefresh(p);                                   // "Into the Hollow" ends The Leviathan's Wake
        Sky.profiles().saveNow(p);
    }

    KData.Chest rollChest(Player p, KCatalog.Tier t, String runId, long seed) {
        KData.Chest c = new KData.Chest();
        c.runId = runId;
        c.tier = t.id;
        c.at = System.currentTimeMillis();
        c.seed = seed;
        // essence: event dial + the Leviathan pet's Crimson Tithe + the hunting attribute Crimson Essence (Lava Leech)
        double bonus = KDials.ESSENCE_BONUS.get() + Sky.service(Features.Pets.class).abilityValue(p, "kuudra_crimson", "a")
                + huntingValue(p, "crimson_essence");
        // teeth: the hunting attribute Leviathan's Box (reference Kuudra's Box, Lavalurker shard): +5%/level extra teeth
        double teeth = huntingValue(p, "kuudra_box");
        // Ananke shard's Wings of Destiny: better odds of the Ananke Feather row
        double ananke = huntingValue(p, "wings_of_destiny");
        KLoot.Rolled r = KLoot.roll(cat.loot.get(t.id), new Random(seed), Sky.items()::exists,
                new KLoot.Mods(bonus, teeth, KDials.PAID_ESSENCE_SCALE.get(), ananke));
        c.free.putAll(r.free());
        c.paid.putAll(r.paid());
        return c;
    }

    // =====================================================================================
    // Mandible salvage (audit K22: the reference salvages a Kuudra Mandible for 500 Crimson Essence)
    // =====================================================================================

    static final String MANDIBLE = "KUUDRA_MANDIBLE";

    /**
     * The salvage as a recipe (type OTHER, station "Leviathan Salvage"): the bazaar's market makers read every recipe, so
     * their Crimson Essence bid can never top a Mandible bought and salvaged (the money-printer guard). Uses the dial
     * value at boot; the salvage itself reads it at use time.
     */
    private void registerSalvageRecipe() {
        try {
            com.skyclone.api.item.Recipe r = new com.skyclone.api.item.Recipe();
            r.id = "kuudra:mandible_salvage";
            r.type = com.skyclone.api.item.Recipe.Type.OTHER;
            r.result = KLoot.ESSENCE;
            r.amount = Math.max(1, KDials.MANDIBLE_SALVAGE.get());
            r.inputs = new ArrayList<>(List.of(MANDIBLE + ":1"));
            r.station = "Leviathan Salvage";
            Sky.recipes().register(r);
        } catch (RuntimeException e) {
            Sky.log().log(Level.WARNING, "[kuudra] mandible salvage recipe", e);
        }
    }

    /** Salvage one (or every) Leviathan Mandible in the inventory for Crimson Essence. Returns how many. */
    int salvageMandibles(Player p, boolean all) {
        if (!Sky.profiles().isReady(p) || !Sky.items().exists(MANDIBLE) || !Sky.items().exists(KLoot.ESSENCE)) return 0;
        int have = Sky.items().count(p, MANDIBLE);
        int want = all ? have : Math.min(1, have);
        if (want <= 0) { Msg.error(p, "You have no " + Text.strip(Sky.items().def(MANDIBLE).name) + " to salvage."); return 0; }
        int took = Sky.items().remove(p, MANDIBLE, want);              // the stones go first: a failure never pays
        if (took <= 0) return 0;
        int each = Math.max(0, KDials.MANDIBLE_SALVAGE.get());
        if (each > 0) giveItems(p, java.util.Map.of(KLoot.ESSENCE, took * each), "salvage of " + took + " mandible(s)");
        Msg.success(p, "Salvaged " + took + " " + Text.strip(Sky.items().def(MANDIBLE).name) + (took == 1 ? "" : "s")
                + " for " + Text.commasInt(took * each) + " Crimson Essence.");
        Sky.profiles().saveNow(p);
        return took;
    }

    /** A hunting attribute's applied number ({@code value:<attr>} = level x per-level x echoes), 0 without hunting. */
    static double huntingValue(Player p, String attr) {
        try { return Math.max(0, Sky.service(Features.Hunting.class).attributeLevel(p, "value:" + attr)); }
        catch (RuntimeException e) { return 0; }
    }

    /** Give chest items: created fresh (unique items get their own uuid), through loot so collections see them. */
    void giveItems(Player p, java.util.Map<String, Integer> items, String why) {
        for (var e : items.entrySet()) {
            if (!Sky.items().exists(e.getKey()) || e.getValue() <= 0) continue;
            if (KLoot.isShard(e.getKey()) && KDials.SHARD_TO_BOX.get() && Sky.has(Features.Hunting.class)) {
                // attribute shards go straight into the Hunting Box (and give Hunting XP), as the reference's do
                try {
                    Sky.service(Features.Hunting.class).addShards(p, e.getKey(), e.getValue(), "kuudra_chest");
                    Msg.send(p, "&7+ " + Sky.items().def(e.getKey()).rarity.code() + Text.strip(Sky.items().def(e.getKey()).name)
                            + " &8x" + e.getValue() + " &7(into your &aHunting Box&7)");
                    continue;
                } catch (RuntimeException ex) {
                    Sky.log().log(Level.WARNING, "[kuudra] addShards failed, giving the shard as an item", ex);
                }
            }
            var def = Sky.items().def(e.getKey());
            int left = e.getValue();
            if (def.isUnique()) {
                for (int i = 0; i < left; i++) Sky.loot().give(p, Sky.items().create(e.getKey(), 1), LootSource.DUNGEON);
            } else {
                int stack = Math.max(1, def.effectiveMaxStack());
                while (left > 0) {
                    int n = Math.min(stack, left);
                    Sky.loot().give(p, Sky.items().create(e.getKey(), n), LootSource.DUNGEON);
                    left -= n;
                }
            }
            Msg.send(p, "&7+ " + Sky.items().def(e.getKey()).rarity.code() + Text.strip(Sky.items().def(e.getKey()).name) + " &8x" + Text.commasInt(e.getValue()));
        }
        Sky.log().info("[kuudra] " + p.getName() + " claimed " + why + ": " + items);
    }

    static String tierWord(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
