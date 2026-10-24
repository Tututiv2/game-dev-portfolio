package com.skyclone.mod.mining;

import com.google.gson.Gson;
import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Box;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Commissions (research 2.8): per island, 2 slots (+1 at 100 done, +1 from Core of the Mountain III),
 * progress from mining, kills, hits, chests, crystals, mineshafts, corpses, scrap and events; claimed
 * at the commission board / the King, or anywhere on the island after milestone I, or anywhere at all
 * with the Royal Pigeon. First 4 a day give +900 HOTM XP. Milestones I-VI.
 */
final class Commissions implements Listener {

    static final class Def {
        public String id, island, name, kind, zone, event, area;
        public int amount = 1, tier = 1;
        public List<Object> match = new ArrayList<>();
        List<String> keys() { List<String> o = new ArrayList<>(); for (Object x : match) o.add(String.valueOf(x)); return o; }
    }
    static final class Reward { public int[] miningXp, hotmXp, powderAmount; public String powder; }
    static final class Daily { public int count = 4; public int hotmXp = 900; public Map<String, Integer> maxedPowder = new LinkedHashMap<>(); }
    static final class Milestone { public int count; public int miningXp; public int skyblockXp; public String reward; }
    static final class File {
        public List<Def> commissions = new ArrayList<>();
        public Map<String, Reward> rewards = new LinkedHashMap<>();
        public Daily dailyBonus = new Daily();
        public List<Milestone> milestones = new ArrayList<>();
    }

    static final List<String> ISLANDS = List.of("dwarven_mines", "crystal_hollows", "glacite_tunnels");

    final File f;
    private final MiningModule m;
    private final Map<String, Def> byId = new LinkedHashMap<>();
    private final Map<String, Def> zoneDefs = new LinkedHashMap<>();
    private long zoneDefsAt;

    Commissions(MiningModule m, Reader r) {
        this.m = m;
        this.f = new Gson().fromJson(r, File.class);
        for (Def d : f.commissions) byId.put(d.id, d);
    }

    // ---- defs -----------------------------------------------------------------------------------

    /** Zone-specific Mithril / Titanium commissions made from the Dwarven Mines' ores.<zone> areas. */
    private Map<String, Def> zoneDefs() {
        long now = System.currentTimeMillis();
        if (now - zoneDefsAt < 60_000 && !zoneDefs.isEmpty()) return zoneDefs;
        zoneDefsAt = now;
        zoneDefs.clear();
        Layout lay = null;
        try { lay = Sky.worlds().layout("dwarven_mines"); } catch (Throwable ignored) { }
        if (lay == null) return zoneDefs;
        for (Map.Entry<String, Box> e : lay.areas().entrySet()) {
            String a = e.getKey();
            if (!a.startsWith("ores.")) continue;
            String suffix = a.substring(5);
            String disp = Text.titleCase(suffix);
            Box b = e.getValue();
            for (Layout.Zone z : lay.zones()) if (z.box().contains(b.centerX(), b.centerY(), b.centerZ())) { disp = Text.strip(z.displayName()); break; }
            Def mi = new Def();
            mi.id = "zone_mithril:" + suffix; mi.island = "dwarven_mines"; mi.name = disp + " Mithril"; mi.kind = "MINE";
            mi.amount = 250; mi.tier = 2; mi.area = a; mi.match = new ArrayList<>(List.of("mithril_gray", "mithril_prismarine", "mithril_blue"));
            Def ti = new Def();
            ti.id = "zone_titanium:" + suffix; ti.island = "dwarven_mines"; ti.name = disp + " Titanium"; ti.kind = "MINE";
            ti.amount = 10; ti.tier = 2; ti.area = a; ti.match = new ArrayList<>(List.of("titanium"));
            zoneDefs.put(mi.id, mi);
            zoneDefs.put(ti.id, ti);
        }
        return zoneDefs;
    }

    Def def(String id) {
        Def d = byId.get(id);
        return d != null ? d : zoneDefs().get(id);
    }

    List<Def> pool(String island) {
        List<Def> out = new ArrayList<>();
        for (Def d : f.commissions) {
            if (!island.equals(d.island)) continue;
            if (d.event != null && !eventLive(island, d.event)) continue;
            if (!possible(d)) continue;
            out.add(d);
        }
        if ("dwarven_mines".equals(island)) out.addAll(zoneDefs().values());
        return out;
    }

    private final Map<String, Boolean> possible = new java.util.HashMap<>();
    private long possibleAt;

    /**
     * Can this commission be finished on this server at all? A slayer commission whose mob no module
     * registers (an island that is not built yet) would sit in a slot for ever, and there is no way to
     * abandon one. Re-evaluated every minute, so it appears as soon as the mob does.
     */
    boolean possible(Def d) {
        if (d == null) return false;
        if (!m.dials.hideImpossibleCommissions.get()) return true;
        long now = System.currentTimeMillis();
        if (now - possibleAt > 60_000L) { possible.clear(); possibleAt = now; }
        return possible.computeIfAbsent(d.id, k -> checkPossible(d));
    }

    /** Mob id prefixes (CONTRACT §3: generic mobs carry their island) that pin a mob to one commission island. */
    private static final Map<String, List<String>> ISLAND_PREFIX = Map.of(
            "dwarven_mines", List.of("dwarven_"),
            "crystal_hollows", List.of("hollows_", "crystal_hollows_"),
            "glacite_tunnels", List.of("tunnels_", "glacite_tunnels_"));

    /** True when the mob id says it lives on a DIFFERENT commission island (an ice walker of the Dwarven Mines cannot finish a tunnels commission). */
    static boolean otherIslands(String island, String mobId) {
        for (Map.Entry<String, List<String>> e : ISLAND_PREFIX.entrySet()) {
            if (e.getKey().equals(island)) continue;
            for (String pre : e.getValue()) if (mobId.startsWith(pre)) return true;
        }
        return false;
    }

    private boolean checkPossible(Def d) {
        List<String> keys = d.keys();
        try {
            switch (d.kind == null ? "" : d.kind) {
                case "KILL", "HIT" -> {
                    if (keys.isEmpty() || keys.contains("*")) return true;
                    for (MobDef md : Sky.mobs().all())
                        if (md != null && md.id != null && matches(keys, md) && !otherIslands(d.island, md.id)) return true;
                    return false;
                }
                case "COLLECT", "CRYSTAL" -> {
                    for (String k : keys) if (MiningUtil.exists(k)) return true;
                    return keys.isEmpty();
                }
                case "MINE" -> {
                    for (String k : keys) {
                        BlockTable.Def b = m.blocks.byId(k);
                        if (b != null && b.islands.contains(d.island)) return true;
                    }
                    return keys.isEmpty();
                }
                default -> { return true; }
            }
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean eventLive(String island, String ev) {
        return switch (ev) {
            case "goblin_raid" -> m.events.active(island, MiningEvents.Type.GOBLIN_RAID);
            case "raffle" -> m.events.active(island, MiningEvents.Type.RAFFLE);
            case "double_powder" -> m.events.active(island, MiningEvents.Type.DOUBLE_POWDER);
            case "fallen_star" -> m.events.starActive();
            default -> false;
        };
    }

    int slots(Player p) {
        MiningData d = MiningUtil.data(p);
        int n = m.dials.commissionSlots.get();
        if (d.milestonesClaimed >= 3) n++;
        if (m.hotm.coreLevel(p) >= 3) n++;
        return Math.min(4, n);
    }

    /** Active commissions on an island, topped up to the slot count. */
    List<MiningData.Commission> active(Player p, String island) {
        MiningData d = MiningUtil.data(p);
        List<MiningData.Commission> list = d.commissions.computeIfAbsent(island, k -> new ArrayList<>());
        // drop commissions that no longer exist, and impossible ones nobody has started (see possible())
        list.removeIf(c -> def(c.id) == null || (c.progress == 0 && !possible(def(c.id))));
        int want = slots(p);
        List<Def> pool = pool(island);
        int guard = 0;
        while (list.size() < want && !pool.isEmpty() && guard++ < 50) {
            Def pick = pool.get(MiningUtil.rnd().nextInt(pool.size()));
            if (list.stream().anyMatch(c -> c.id.equals(pick.id))) continue;
            MiningData.Commission c = new MiningData.Commission(pick.id, pick.amount);
            c.name = pick.name;
            c.area = pick.area;
            list.add(c);
        }
        return list;
    }

    // ---- progress ---------------------------------------------------------------------------------

    private static String commIsland(String island) {
        if (island == null) return null;
        return "mineshaft".equals(island) ? "glacite_tunnels" : island;
    }

    private void add(Player p, MiningData.Commission c, int n) {
        if (c.done() || n <= 0) return;
        c.progress = Math.min(c.target, c.progress + n);
        if (c.done() && MiningUtil.setting(p, MiningUtil.SETTING_COMMISSION_ALERTS)) {
            Msg.send(p, "&a&lCOMMISSION COMPLETE! &e" + (c.name == null ? c.id : c.name) + " &7- claim it at the commission board or with &e/commissions&7.");
            Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
        }
    }

    void onMine(Player p, BlockTable.Def def, String dropId, int amount, Location at, String island) {
        String ci = commIsland(island);
        if (ci == null || !ISLANDS.contains(ci)) return;
        for (MiningData.Commission c : active(p, ci)) {
            Def d = def(c.id);
            if (d == null) continue;
            if ("MINE".equals(d.kind) && d.keys().contains(def.id)) {
                if (c.area != null) {
                    Box b = Sky.worlds().area("dwarven_mines", c.area);
                    if (b == null || !b.contains(at.getX(), at.getY(), at.getZ())) continue;
                }
                add(p, c, 1);
            } else if ("COLLECT".equals(d.kind) && d.keys().contains(dropId)) {
                add(p, c, amount);
            }
        }
    }

    /** Generic progress: kind = KILL/HIT/EVENT/RAFFLE/POWDER/CHEST/CRYSTAL/SCRAP/MINESHAFT/CORPSE. */
    void progress(Player p, String kind, String key, int amount, Location at) {
        if (!MiningUtil.ready(p)) return;
        String ci = commIsland(MiningUtil.island(at != null ? at : p.getLocation()));
        if (ci == null || !ISLANDS.contains(ci)) return;
        for (MiningData.Commission c : active(p, ci)) {
            Def d = def(c.id);
            if (d == null || !kind.equals(d.kind)) continue;
            List<String> keys = d.keys();
            if (!keys.isEmpty() && key != null && !key.isEmpty() && !keys.contains(key) && !"KILL".equals(kind) && !"HIT".equals(kind)) continue;
            if ("EVENT".equals(kind) && amount == 0) amount = 1;
            add(p, c, amount);
        }
    }

    void crystal(Player p, String crystalId) {
        MiningUtil.data(p).crystalsFound.add(crystalId);
        progress(p, "CRYSTAL", crystalId, 1, null);
    }

    private static boolean matches(List<String> keys, MobDef def) {
        String id = def.id.toLowerCase(), fam = def.familyId().toLowerCase();
        for (String k : keys) {
            if ("*".equals(k)) return true;
            if (id.contains(k) || fam.contains(k)) return true;
        }
        return false;
    }

    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null || !MiningUtil.ready(e.killer)) return;
        String ci = commIsland(MiningUtil.island(e.location));
        if (ci == null || !ISLANDS.contains(ci)) return;
        for (MiningData.Commission c : active(e.killer, ci)) {
            Def d = def(c.id);
            if (d != null && "KILL".equals(d.kind) && matches(d.keys(), e.def)) add(e.killer, c, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(SkyEvents.Damage e) {
        Player p = e.ctx.playerAttacker();
        if (p == null || !(e.ctx.victim instanceof LivingEntity le) || !MiningUtil.ready(p)) return;
        MobDef md = Sky.mobs().defOf(le);
        if (md == null) return;
        String ci = commIsland(MiningUtil.island(le.getLocation()));
        if (ci == null || !ISLANDS.contains(ci)) return;
        for (MiningData.Commission c : active(p, ci)) {
            Def d = def(c.id);
            if (d != null && "HIT".equals(d.kind) && matches(d.keys(), md)) add(p, c, 1);
        }
    }

    // ---- claiming -----------------------------------------------------------------------------------

    boolean canClaimHere(Player p, String island) {
        if (m.dials.claimAnywhere.get()) return true;
        if (Sky.items().count(p, "ROYAL_PIGEON") > 0) return true;
        String here = commIsland(MiningUtil.island(p));
        MiningData d = MiningUtil.data(p);
        if (island.equals(here) && d.milestonesClaimed >= 1) return true;   // emissaries
        for (String npc : List.of("mining_commissions", "mining_king", "mining_camp_commissions", "mining_hollows_merchant")) {
            Entity en = Sky.npcs().entity(npc);
            if (en != null && en.getWorld() == p.getWorld() && en.getLocation().distanceSquared(p.getLocation()) < 12 * 12) return true;
        }
        return false;
    }

    void claim(Player p, String island, int index) {
        List<MiningData.Commission> list = active(p, island);
        if (index < 0 || index >= list.size()) return;
        MiningData.Commission c = list.get(index);
        if (!c.done()) return;
        if (!canClaimHere(p, island)) {
            Msg.error(p, "Claim it at the commission board, or reach Milestone I to claim from anywhere on this island.");
            return;
        }
        Def d = def(c.id);
        list.remove(index);
        MiningData data = MiningUtil.data(p);
        MiningUtil.rollDay(data);
        Reward r = f.rewards.get(island);
        int t = Math.max(0, Math.min(1, (d == null ? 1 : d.tier) - 1));
        List<String> body = new ArrayList<>();
        if (r != null) {
            int mxp = r.miningXp[t];
            Sky.service(SkillService.class).addXp(p, SkillType.MINING, mxp, "commission");
            body.add("&8+&3" + Text.commas(mxp) + " Mining XP");
            double hx = r.hotmXp[t] * m.dials.commissionXpMult.get();
            if (Sky.service(Features.Accessories.class).has(p, "KING_TALISMAN")) hx += 10;
            boolean maxed = m.hotm.tier(p) >= m.hotm.tree.maxTier();
            // The base line comes first and the bonus is listed on its own; the old "+1,300 HOTM XP" total under a
            // "+900 daily bonus" line read as 2,200 (wave-4 loops playtest).
            body.add("&8+&5" + Text.commas(hx) + " HOTM XP");
            if (data.dailyCommissions < f.dailyBonus.count) {
                data.dailyCommissions++;
                if (!maxed) { hx += f.dailyBonus.hotmXp; body.add("&8+&5" + Text.commas(f.dailyBonus.hotmXp) + " &7daily bonus HOTM XP"); }
                else {
                    int bonus = f.dailyBonus.maxedPowder.getOrDefault(r.powder, 0);
                    long got = m.hotm.grant(p, r.powder, bonus, "commission daily", true);
                    body.add("&8+" + MiningUtil.powderColor(r.powder) + Text.commas(got) + " " + MiningUtil.powderName(r.powder) + " &7(daily)");
                }
            }
            double climb = maxed ? 0 : hx * Math.max(0, m.hotm.xpBonusPct(p)) / 100.0;   // added inside addXp
            if (climb >= 1) body.add("&8+&5" + Text.commas(Math.round(climb)) + " &7HOTM XP (Climber)");
            m.hotm.addXp(p, hx, "commission");
            long got = m.hotm.grant(p, r.powder, r.powderAmount[t], "commission", true);
            body.add("&8+" + MiningUtil.powderColor(r.powder) + Text.commas(got) + " " + MiningUtil.powderName(r.powder));
            if (m.hotm.has(p, "daily_grind") && data.dailyGrind.add(island)) {
                long g = m.hotm.grant(p, r.powder, 500.0 * m.hotm.tier(p), "daily grind", true);
                body.add("&8+" + MiningUtil.powderColor(r.powder) + Text.commas(g) + " " + MiningUtil.powderName(r.powder) + " &7(Daily Grind)");
            }
        }
        if ("dwarven_mines".equals(island)) {
            double ch = m.dials.commissionBonusChance.get();
            for (String bonus : List.of("GLACITE_JEWEL", "TREASURITE")) {
                if (MiningUtil.exists(bonus) && MiningUtil.rnd().nextDouble() < ch) {
                    Sky.loot().give(p, bonus, 1, LootSource.REWARD);
                    body.add("&8+" + MiningUtil.itemName(bonus));
                }
            }
        }
        data.commissionsDone++;
        Msg.box(p, "&6&lCOMMISSION COMPLETE &e" + (d == null ? c.id : d.name), body);
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.4f);
        milestones(p);
        active(p, island);
    }

    /** Pays every milestone reached and not yet paid. */
    void milestones(Player p) {
        MiningData d = MiningUtil.data(p);
        while (d.milestonesClaimed < f.milestones.size() && d.commissionsDone >= f.milestones.get(d.milestonesClaimed).count) {
            Milestone ms = f.milestones.get(d.milestonesClaimed);
            d.milestonesClaimed++;
            List<String> body = new ArrayList<>();
            Sky.service(SkillService.class).addXp(p, SkillType.MINING, ms.miningXp, "commission milestone");
            body.add("&8+&3" + Text.commas(ms.miningXp) + " Mining XP");
            Sky.service(Features.SkyLevel.class).award(p, "mining:commission_milestone:" + d.milestonesClaimed, ms.skyblockXp, "Commission Milestone " + d.milestonesClaimed);
            body.add("&8+&b" + ms.skyblockXp + " SkyBlock XP");
            if ("emissaries".equals(ms.reward)) body.add("&aClaim commissions from anywhere on their island");
            else if ("slot".equals(ms.reward)) body.add("&a+1 Commission Slot");
            else if (ms.reward != null && MiningUtil.exists(ms.reward)) {
                Sky.loot().give(p, ms.reward, 1, LootSource.REWARD);
                body.add(MiningUtil.itemName(ms.reward));
            }
            Msg.box(p, "&6&lCOMMISSION MILESTONE " + Text.roman(d.milestonesClaimed), body);
        }
    }

    // ---- menu ---------------------------------------------------------------------------------------

    void open(Player p, String island) {
        if (island == null || !ISLANDS.contains(island)) {
            String here = commIsland(MiningUtil.island(p));
            island = here != null && ISLANDS.contains(here) ? here : "dwarven_mines";
        }
        final String isl = island;
        MiningData d = MiningUtil.data(p);
        MiningUtil.rollDay(d);
        Menu menu = new Menu("Commissions", 6).chrome();
        List<MiningData.Commission> list = active(p, isl);
        menu.set(4, Icons.of(Material.WRITABLE_BOOK, "&6" + islandName(isl) + " Commissions",
                "&7Completed: &a" + Text.commas(d.commissionsDone),
                "&7Daily HOTM bonus: &a" + Math.min(d.dailyCommissions, f.dailyBonus.count) + "&7/" + f.dailyBonus.count,
                "&7Slots: &a" + slots(p),
                "", "&7Finish a commission, then claim it", "&7here for Mining XP, &5HOTM XP &7and powder."));
        int[] pos = {19, 21, 23, 25};
        boolean canClaim = canClaimHere(p, isl);
        for (int i = 0; i < list.size() && i < pos.length; i++) {
            MiningData.Commission c = list.get(i);
            Def def = def(c.id);
            String name = def == null ? c.id : def.name;
            double frac = c.target <= 0 ? 1 : c.progress / (double) c.target;
            List<String> lore = new ArrayList<>();
            lore.add("&8" + (def == null ? "" : kindText(def.kind)));
            lore.add("");
            lore.add("&7" + describe(def));
            lore.add("");
            lore.add(Text.bar(frac, 20) + " &e" + Text.commas(c.progress) + "&6/&e" + Text.commas(c.target));
            lore.add("");
            Reward r = f.rewards.get(isl);
            if (r != null && def != null) {
                int t = Math.max(0, Math.min(1, def.tier - 1));
                lore.add("&7Rewards:");
                lore.add("&8+&3" + Text.commas(r.miningXp[t]) + " Mining XP");
                lore.add("&8+&5" + Text.commas(r.hotmXp[t]) + " HOTM XP");
                lore.add("&8+" + MiningUtil.powderColor(r.powder) + Text.commas(r.powderAmount[t]) + " " + MiningUtil.powderName(r.powder));
                lore.add("");
            }
            final int idx = i;
            if (c.done()) {
                lore.add(canClaim ? "&eClick to claim rewards!" : "&cClaim at the commission board.");
                menu.set(pos[i], Icons.glow(Icons.of(Material.ENCHANTED_BOOK, "&a" + name + " &a&lCOMPLETED", lore)), cl -> { claim(cl.player(), isl, idx); open(cl.player(), isl); });
            } else {
                lore.add("&7In progress.");
                menu.set(pos[i], Icons.of(Material.PAPER, "&6" + name, lore));
            }
        }
        // milestones
        for (int i = 0; i < f.milestones.size() && i < 6; i++) {
            Milestone ms = f.milestones.get(i);
            boolean got = d.milestonesClaimed > i;
            List<String> lore = new ArrayList<>();
            lore.add("&7Complete &a" + ms.count + " &7commissions.");
            lore.add("");
            lore.add("&7Rewards:");
            lore.add("&8+&3" + Text.commas(ms.miningXp) + " Mining XP");
            lore.add("&8+&b" + ms.skyblockXp + " SkyBlock XP");
            if ("emissaries".equals(ms.reward)) lore.add("&8+&aClaim from anywhere on the island");
            else if ("slot".equals(ms.reward)) lore.add("&8+&a1 Commission Slot");
            else if (ms.reward != null) lore.add("&8+" + MiningUtil.itemName(ms.reward));
            lore.add("");
            lore.add(got ? "&a&lUNLOCKED" : Text.bar(Math.min(1, d.commissionsDone / (double) ms.count), 20) + " &e" + d.commissionsDone + "&6/&e" + ms.count);
            menu.set(37 + i + (i >= 3 ? 1 : 0), Icons.of(got ? Material.LIME_DYE : Material.GRAY_DYE, (got ? "&a" : "&c") + "Milestone " + Text.roman(i + 1), lore));
        }
        menu.set(48, Icons.back("Mining"), c -> m.menus.openMain(c.player()));
        String next = ISLANDS.get((ISLANDS.indexOf(isl) + 1) % ISLANDS.size());
        menu.set(50, Icons.of(Material.COMPASS, "&aSwitch Island", "&7Viewing: &e" + islandName(isl), "&7Next: &e" + islandName(next), "", "&eClick to switch!"),
                c -> open(c.player(), next));
        menu.set(53, Icons.of(Material.FEATHER, "&fRoyal Pigeon",
                "&7With a &fRoyal Pigeon &7in your", "&7inventory you can claim from", "&7anywhere. Earned at Milestone V.",
                "", Sky.items().count(p, "ROYAL_PIGEON") > 0 ? "&aYou have one!" : "&cYou don't have one yet."));
        menu.open(p);
    }

    static String islandName(String island) {
        return switch (island) {
            case "crystal_hollows" -> "Crystal Caverns";
            case "glacite_tunnels" -> "Frozen Tunnels";
            default -> "Dwarven Mines";
        };
    }

    private static String kindText(String k) {
        return switch (k == null ? "" : k) {
            case "MINE" -> "Mining";
            case "COLLECT" -> "Collecting";
            case "KILL" -> "Slaying";
            case "HIT" -> "Punching";
            case "CHEST" -> "Treasure";
            case "CRYSTAL" -> "Crystal Hunting";
            case "EVENT", "RAFFLE", "POWDER" -> "Event";
            default -> "Exploring";
        };
    }

    private static String describe(Def d) {
        if (d == null) return "This commission no longer exists.";
        return switch (d.kind) {
            case "MINE" -> "Mine &a" + d.amount + " &7" + (d.name.contains("Titanium") ? "Titanium" : d.name.contains("Hard Stone") ? "Hard Stone" : "Mithril") + (d.area != null ? " in this area" : "") + ".";
            case "COLLECT" -> "Collect &a" + Text.commas(d.amount) + " &7" + Text.strip(MiningUtil.itemName(d.keys().isEmpty() ? "" : d.keys().get(0))) + ".";
            case "KILL" -> "Slay &a" + d.amount + " &7" + d.name.replace(" Slayer", "") + (d.amount == 1 ? "" : "s") + ".";
            case "HIT" -> "Hit a " + d.name.replace(" Puncher", "") + " &a" + d.amount + " &7times.";
            case "CHEST" -> "Open &a" + d.amount + " &7treasure chests.";
            case "CRYSTAL" -> "Find a " + d.name.replace(" Hunter", "") + ".";
            case "MINESHAFT" -> "Discover a frozen mineshaft.";
            case "CORPSE" -> "Loot &a" + d.amount + " &7frozen corpses.";
            case "SCRAP" -> "Find a Suspicious Scrap.";
            case "RAFFLE" -> "Find &a" + d.amount + " &7Raffle Tickets.";
            case "POWDER" -> "Earn &a" + d.amount + " &7powder during 2x Powder.";
            case "EVENT" -> "Take part in the " + d.name + " event.";
            default -> d.name;
        };
    }

    int poolSize() { return f.commissions.size(); }
}
