package com.skyclone.mod.kuudra;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Every Leviathan menu: the hunt board, crew, perks, the run panel, reward chests, loot preview, records. */
final class KMenus {

    private static final Material[] TIER_ICONS = {Material.MAGMA_CREAM, Material.BLAZE_POWDER, Material.FIRE_CHARGE, Material.BLAZE_ROD, Material.NETHER_STAR};
    static final String KISMET = "KISMET_FEATHER";

    private final KuudraModule mod;

    KMenus(KuudraModule mod) { this.mod = mod; }

    private void toSkyMenu(Menu m) {
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
    }

    // =====================================================================================
    // the hunt board
    // =====================================================================================

    static final String WHEEL = "WHEEL_OF_FATE";

    /**
     * The dock (Nell / the end of the pier). While The Leviathan's Wake waits on a talk step, Nell speaks first
     * (KuudraModule.introTalk: MEET is the old briefing grown into the questline's opening, REPORT her send-off with a
     * key); then the board.
     */
    void dock(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        if (Sky.npcs().talking(p, KuudraModule.CRIMSON_DOCK_NPC)) return;          // mid-conversation: no second copy
        if (mod.runs.of(p) != null) { main(p); return; }
        mod.introTalk(p, () -> { if (p.isOnline()) main(p); });
    }

    // =====================================================================================
    // the intro questline (The Leviathan's Wake)
    // =====================================================================================

    private ItemStack introIcon(Player p) {
        int step = mod.intro(p);
        KData d = mod.data(p);
        List<String> l = new ArrayList<>();
        l.add("&8Questline");
        l.add("");
        if (step >= KIntro.DONE) {
            l.add("&aCompleted! &7Dockhand Nell trusts you");
            l.add("&7with a boat.");
        } else {
            l.add("&7Objective:");
            l.add("&e" + KIntro.objective(step, d.introKills, KDials.INTRO_KILLS.get()));
            l.add("");
            l.add("&7Step &e" + (step + 1) + "&7/&e" + KIntro.STEPS.size());
        }
        l.add("");
        l.add("&eClick to view!");
        ItemStack it = Icons.of(step >= KIntro.DONE ? Material.ENCHANTED_BOOK : Material.WRITABLE_BOOK, "&c" + KIntro.NAME, l);
        return step < KIntro.DONE ? Icons.glow(it) : it;
    }

    /** The quest log page: every step, done / current / ahead. {@code /kuudra intro}. */
    void intro(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        int step = mod.introRefresh(p);
        KData d = mod.data(p);
        Menu m = new Menu(KIntro.NAME, 4).chrome();
        m.set(4, Icons.of(Material.MAGMA_CREAM, "&c" + KIntro.NAME,
                "&7Dockhand Nell rows crews out to the", "&7Magma Leviathan. She wants to know", "&7you can stand the heat first.", "",
                step >= KIntro.DONE ? "&aCompleted!" : "&7Progress: &e" + step + "&7/&e" + KIntro.STEPS.size()));
        int slot = 11;
        for (KIntro.Step s : KIntro.STEPS) {
            boolean done = step > s.index(), now = step == s.index();
            List<String> l = new ArrayList<>();
            l.add("&7" + KIntro.objective(s.index(), d.introKills, KDials.INTRO_KILLS.get()));
            if (s.talk()) l.add("&8(at the Leviathan dock, Crimson Isle)");
            if (s.index() == KIntro.REPORT && KDials.INTRO_KEY.get()) l.add("&7Reward: &a1x Basic Leviathan Key");
            if (s.index() == KIntro.HUNT) l.add("&7Unlocks: &aBasic &7hunts");
            l.add("");
            l.add(done ? "&aCompleted" : now ? "&eIn progress" : "&8Not started");
            ItemStack it = Icons.of(done ? Material.LIME_DYE : now ? Material.YELLOW_DYE : Material.GRAY_DYE,
                    (done ? "&a" : now ? "&e" : "&7") + s.title(), l);
            m.set(slot, now ? Icons.glow(it) : it);
            slot += 1;
        }
        boolean onIsle = "crimson_isle".equals(Sky.worlds().islandOf(p.getLocation()));
        KIntro.Step cur = KIntro.step(step);
        if (cur != null && cur.talk() && !onIsle)
            m.set(22, Icons.of(Material.ENDER_PEARL, "&bTravel to the Dock", "&7Dockhand Nell waits at the", "&7Leviathan dock on the Crimson Isle.", "", "&eClick to warp!"),
                    c -> { c.player().closeInventory(); Sky.worlds().warp(c.player(), "kuudra"); });
        m.set(31, Icons.back("Leviathan's Hollow"), c -> main(c.player()));
        m.open(p);
    }

    void main(Player p) {
        if (!Sky.profiles().isReady(p)) return;
        if (mod.runs.of(p) != null) { run(p); return; }
        KData d = mod.data(p);
        Menu m = new Menu("Leviathan's Hollow", 6).chrome();
        m.set(4, Icons.of(Material.MAGMA_BLOCK, "&c&lThe Magma Leviathan",
                "&7A lava beast the size of a hill sleeps", "&7under the Crimson Isle. Crews of up to",
                "&7four sail out to bring it down.", "",
                "&7Clears: &a" + d.totalCompletions() + " &8| &7Unclaimed chests: &6" + d.unclaimed(), "",
                "&8Tiers unlock with faction reputation."));
        String last = mod.runs.lastTier.get(p.getUniqueId());
        KCatalog.Tier lastT = last == null ? null : mod.cat.tier(last);
        if (lastT != null && mod.blocker(p, lastT) == null) m.set(13, againIcon(p, lastT), c -> huntAgain(c.player()));
        m.set(11, introIcon(p), c -> intro(c.player()));
        int slot = 20;
        for (KCatalog.Tier t : mod.cat.tiers) {
            final KCatalog.Tier tier = t;
            m.set(slot++, tierIcon(p, t, d), c -> pickTier(c.player(), tier));
        }
        List<UUID> crew = mod.crew.members(p.getUniqueId());
        List<String> cl = new ArrayList<>();
        cl.add("&7Hunt with up to &e" + KDials.MAX_PARTY.get() + " &7players.");
        cl.add("");
        for (UUID u : crew) cl.add((u.equals(mod.crew.leader(p.getUniqueId())) ? "&6★ " : "&7- ") + "&f" + nameOf(u));
        if (crew.size() == 1) cl.add("&8(you hunt alone)");
        cl.add("");
        cl.add("&eClick to manage your crew!");
        m.set(29, Icons.playerHead(p.getUniqueId(), "&aHunting Crew &8(" + crew.size() + "/" + KDials.MAX_PARTY.get() + ")", cl), c -> crew(c.player()));
        m.set(31, Icons.of(Material.CHEST, "&6Reward Chests", "&7Chests from hunts you won but", "&7have not emptied yet.", "",
                "&7Waiting: &e" + d.unclaimed(), "", "&eClick to view!"), c -> chests(c.player(), 0));
        m.set(33, Icons.of(Material.BOOK, "&aHunt Records", "&7Clears, best times and totals.", "", "&eClick to view!"), c -> stats(c.player()));
        m.set(39, Icons.of(Material.KNOWLEDGE_BOOK, "&eHow to Hunt", guideLore()));
        m.set(41, Icons.of(Material.GOLD_INGOT, "&6Loot Preview", "&7What each tier's chests can hold.", "", "&eClick to browse!"), c -> lootTiers(c.player()));
        m.set(42, salvageIcon(p), c -> salvageClick(c.player(), c.shift()));
        boolean onIsle = "crimson_isle".equals(Sky.worlds().islandOf(p.getLocation()));
        if (!onIsle) m.set(40, Icons.of(Material.ENDER_PEARL, "&bTravel to the Dock", "&7Hunts set out from the dock on", "&7the &cCrimson Isle&7.", "", "&eClick to warp!"),
                c -> { c.player().closeInventory(); Sky.worlds().warp(c.player(), "kuudra"); });
        toSkyMenu(m);
        m.open(p);
    }

    private ItemStack tierIcon(Player p, KCatalog.Tier t, KData d) {
        String why = mod.blocker(p, t);
        int keys = Sky.items().exists(t.key) ? Sky.items().count(p, t.key) : 0;
        String keyName = Sky.items().exists(t.key) ? Text.strip(Sky.items().def(t.key).name) : t.key;
        List<String> l = new ArrayList<>();
        l.add("&8" + t.name + " Tier");
        l.add("");
        l.add("&7Leviathan health: &c" + Text.shortNum(t.bossHp * KDials.BOSS_HP.get()) + " &8(4 players)");
        l.add("&7Brood waves every &e" + Math.round(t.waveSeconds * KDials.WAVE_SCALE.get()) + "s");
        l.add("&7Phases: &6Supplies &8> &eBuild &8> &cBallista" + (t.stun ? " &8+ &dStun" : "") + " &8> &4Kill" + (t.lair ? " &8> &4Lair" : ""));
        l.add("");
        l.add("&7Clears: &a" + d.completions(t.id) + (d.bestMillis.containsKey(t.id) ? " &8| &7Best: &e" + KMath.clock(d.bestMillis.get(t.id)) : ""));
        l.add("&7First clear: &b+" + t.sbXp + " SkyBlock XP");
        l.add("&7Free chest: &5" + t.freeEssence[0] + (t.freeEssence[1] != t.freeEssence[0] ? "-" + t.freeEssence[1] : "") + " Crimson Essence");
        l.add("&7Paid chest: &a" + keyName + " &8(you have " + keys + ")");
        l.add("");
        if (why == null) l.add("&eClick to set out!");
        else { l.add("&cLocked"); l.add("&c" + why); }
        ItemStack it = Icons.of(TIER_ICONS[Math.min(TIER_ICONS.length - 1, t.index)], (why == null ? "&a" : "&c") + t.name + " Hunt", l);
        return why == null ? Icons.glow(it) : it;
    }

    private void pickTier(Player p, KCatalog.Tier t) {
        String why = mod.blocker(p, t);
        if (why != null) { Msg.error(p, why); return; }
        List<UUID> crew = mod.crew.members(p.getUniqueId());
        List<String> desc = new ArrayList<>();
        desc.add("&7Set out on a " + t.colored() + " &7hunt with:");
        for (UUID u : crew) desc.add("&8- &f" + nameOf(u));
        desc.add("");
        desc.add("&7The run is free; the paid chest needs a");
        desc.add("&a" + (Sky.items().exists(t.key) ? Text.strip(Sky.items().def(t.key).name) : t.key) + "&7.");
        Sky.menus().confirm(p, "Hunt: " + t.name, desc, () -> {
            String err = mod.runs.start(p, t, false);
            if (err != null) Msg.error(p, err);
            else p.closeInventory();
        }, null);
    }

    /** Leviathan Mandible salvage (audit K22): one per click, shift-click for every one carried. */
    private ItemStack salvageIcon(Player p) {
        String id = KuudraModule.MANDIBLE;
        String name = Sky.items().exists(id) ? Text.strip(Sky.items().def(id).name) : "Leviathan Mandible";
        int have = Sky.items().exists(id) ? Sky.items().count(p, id) : 0;
        int each = Math.max(0, KDials.MANDIBLE_SALVAGE.get());
        return Icons.of(Material.GRINDSTONE, "&aSalvage Mandibles", "&7Break a &5" + name + " &7down into",
                "&5" + Text.commasInt(each) + " Crimson Essence&7.", "", "&7You have: " + (have > 0 ? "&a" : "&c") + have, "",
                have > 0 ? "&eClick to salvage one!" : "&cYou have none.", have > 1 ? "&eShift-click to salvage all!" : "");
    }

    private void salvageClick(Player p, boolean all) {
        if (mod.salvageMandibles(p, all) > 0) p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_GRINDSTONE_USE, 1f, 1f);
        main(p);
    }

    private static List<String> guideLore() {
        return List.of("&61. Supplies &7- crouch beside a crate to", "&7   lift it, crouch on a pad by the", "&7   ballista to set it down.",
                "&e2. Build &7- crouch next to the ballista.", "&c3. Ballista &7- grab fuel cells (shore,", "&7   brood drops), crouch by the ballista",
                "&7   to load them, right-click to fire.", "&d   Burning+: &7ride the cannon into the", "&7   beast's Gullet and break its organs",
                "&7   to stun it - only then do bolts bite.", "&44. Kill &7- the beast beaches itself.", "&4   Infernal: &7the lair below. Deaths",
                "&7   there stick unless a &aLifeline &7saves you.", "", "&7Earn &6tokens &7and spend them on perks", "&7with the &cHunt Perks &7item.", "",
                "&7Done? &a/kuudra again &7sets out once more", "&7with the same crew and tier.");
    }

    static String nameOf(UUID u) {
        Player p = Bukkit.getPlayer(u);
        if (p != null) return p.getName();
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName() == null ? "?" : op.getName();
    }

    // =====================================================================================
    // crew
    // =====================================================================================

    void crew(Player p) {
        Menu m = new Menu("Hunting Crew", 6).chrome();
        UUID me = p.getUniqueId();
        boolean leader = mod.crew.isLeader(me);
        List<UUID> members = mod.crew.members(me);
        int slot = 19;
        for (UUID u : members) {
            boolean isLeader = u.equals(mod.crew.leader(me));
            List<String> l = new ArrayList<>();
            l.add(isLeader ? "&6Crew leader" : "&7Member");
            Player x = Bukkit.getPlayer(u);
            if (x != null && Sky.profiles().isReady(x)) l.add("&7Clears: &a" + mod.data(x).totalCompletions());
            if (leader && !u.equals(me)) { l.add(""); l.add("&cClick to remove!"); }
            final UUID target = u;
            m.set(slot++, Icons.playerHead(u, "&f" + nameOf(u), l), leader && !u.equals(me) ? c -> { mod.crew.kick(c.player(), nameOf(target)); crew(c.player()); } : null);
        }
        if (leader && members.size() < KDials.MAX_PARTY.get())
            m.set(30, Icons.of(Material.WRITABLE_BOOK, "&aInvite a Player", "&7Type their name.", "", "&eClick to invite!"),
                    c -> Sky.menus().input(c.player(), "Player to invite", name -> {
                        if (name == null || name.isBlank()) return;
                        Player t = Bukkit.getPlayerExact(name.trim());
                        if (t == null) Msg.error(c.player(), "No online player called " + name.trim() + ".");
                        else mod.crew.invite(c.player(), t);
                    }));
        if (members.size() > 1)
            m.set(32, Icons.of(Material.RED_BED, leader ? "&cDisband Crew" : "&cLeave Crew", "", "&eClick!"), c -> {
                if (mod.crew.isLeader(c.player().getUniqueId())) mod.crew.disband(c.player()); else mod.crew.leave(c.player());
                crew(c.player());
            });
        int s = 37;
        for (String from : mod.crew.pendingFrom(me)) {
            if (s > 43) break;
            m.set(s++, Icons.of(Material.PAPER, "&eInvite from " + from, "", "&aClick to join!"), c -> { mod.crew.accept(c.player(), from); crew(c.player()); });
        }
        m.set(48, Icons.back("Leviathan's Hollow"), c -> main(c.player()));
        m.open(p);
    }

    // =====================================================================================
    // in a hunt
    // =====================================================================================

    void run(Player p) {
        Run r = mod.runs.of(p);
        if (r == null) { main(p); return; }
        Menu m = new Menu("Hunt - " + r.tier.name, 6).chrome();
        m.drawer(menu -> {
            Run.PS me = r.ps.get(p.getUniqueId());
            menu.set(4, Icons.of(Material.MAGMA_BLOCK, "&c" + r.tier.name + " Leviathan Hunt",
                    "&7Phase: &e" + r.phaseName(), "&7Time: &e" + KMath.clock(System.currentTimeMillis() - (r.started == 0 ? System.currentTimeMillis() : r.started)),
                    "&7Leviathan: &c" + (int) r.pct() + "%", "&7Supplies: &6" + r.delivered + " &8| &7Build: &e" + (int) r.build + "% &8| &7Fuel: &c" + r.fuel,
                    "&7Your tokens: &6" + (me == null ? 0 : me.tokens)));
            int slot = 19;
            for (Run.PS s : r.ps.values()) {
                if (slot > 22) break;
                menu.set(slot++, Icons.playerHead(s.id, "&f" + s.name, List.of("&7Tokens: &6" + s.tokens, "&7Brood slain: &c" + s.kills,
                        "&7Damage to beast: &c" + Text.shortNum(s.damage), "&7Deaths: &c" + s.deaths, s.ghost ? "&8☠ Fallen" : "")));
            }
        });
        m.refreshEvery(20);
        m.set(24, Icons.of(Material.EMERALD, "&aHunt Perks", "&7Spend tokens on perks for this hunt.", "", "&eClick to open!"), c -> perks(c.player()));
        if (r.phase == Run.Phase.VICTORY)
            m.set(25, Icons.glow(Icons.of(Material.CHEST, "&6Your Reward Chests", "", "&eClick to open!")), c -> chest(c.player(), r.id));
        m.set(31, Icons.of(Material.KNOWLEDGE_BOOK, "&eHow to Hunt", guideLore()));
        if (r.finished()) m.set(38, againIcon(p, r.tier), c -> huntAgain(c.player()));
        m.set(40, Icons.of(Material.OAK_DOOR, "&cLeave the Hunt", "&7Return to the dock.", r.phase == Run.Phase.VICTORY ? "&7Your chests stay in /kuudra." : "&cYou forfeit this hunt's rewards!", "", "&eClick to leave!"),
                c -> Sky.menus().confirm(c.player(), "Leave the hunt?", List.of(r.phase == Run.Phase.VICTORY ? "&7Chests stay in /kuudra chests." : "&cNo rewards if you leave now."),
                        () -> { Run rr = mod.runs.of(c.player()); if (rr != null) rr.leave(c.player(), true); }, null));
        m.open(p);
    }

    void perks(Player p) {
        Run r = mod.runs.of(p);
        if (r == null) { Msg.error(p, "Perks are bought during a hunt."); return; }
        Menu m = new Menu("Hunt Perks", 6).chrome();
        m.drawer(menu -> {
            Run.PS s = r.ps.get(p.getUniqueId());
            if (s == null) return;
            menu.set(4, Icons.of(Material.SUNFLOWER, "&6Tokens: " + s.tokens, "&7Earned by lifting supplies, building,", "&7loading fuel, firing, stunning and", "&7slaying the brood. Perks last until", "&7this hunt ends."));
            int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
            int i = 0;
            for (KCatalog.Perk k : mod.cat.perks) {
                if (i >= slots.length) break;
                final KCatalog.Perk perk = k;
                int lvl = s.perk(k.id);
                int cost = k.costOf(lvl);
                List<String> l = new ArrayList<>();
                if (!k.consumable()) l.add("&8Level " + lvl + "/" + k.max);
                l.add("");
                double shown = k.per * (k.consumable() ? 1 : Math.max(1, Math.min(k.max, lvl + 1)));
                l.add(k.lore.replace("{v}", Text.commas(shown)));
                if (k.lore2 != null) l.add(k.lore2);
                if ("stun".equals(k.phase) && !r.tier.stun) l.add("&8(No use below Burning tier)");
                if ("lair".equals(k.phase) && !r.tier.lair) l.add("&8(Only matters on Infernal)");
                l.add("");
                if (cost < 0) l.add("&aMaxed!");
                else { l.add("&7Cost: &6" + cost + " tokens"); l.add(s.tokens >= cost ? "&eClick to buy!" : "&cNot enough tokens!"); }
                Material mat = Material.matchMaterial(k.icon);
                ItemStack icon = Icons.of(mat == null ? Material.PAPER : mat, (cost < 0 ? "&a" : s.tokens >= cost ? "&e" : "&c") + k.name, l);
                if (lvl > 0) icon = Icons.amount(Icons.glow(icon), lvl);
                menu.set(slots[i++], icon, c -> { if (r.buy(c.player(), perk)) menu.redraw(); });
            }
        });
        m.set(48, Icons.back("Hunt"), c -> run(c.player()));
        m.open(p);
    }

    // =====================================================================================
    // reward chests
    // =====================================================================================

    void chests(Player p, int page) {
        KData d = mod.data(p);
        List<KData.Chest> list = new ArrayList<>(d.chests);
        java.util.Collections.reverse(list);
        Sky.menus().paged("Leviathan Chests", list, c -> {
            KCatalog.Tier t = mod.cat.tier(c.tier);
            List<String> l = new ArrayList<>();
            l.add("&7" + new java.text.SimpleDateFormat("MMM d, HH:mm").format(new java.util.Date(c.at)));
            l.add("");
            l.add("&7Free chest: " + (c.freeClaimed ? "&8claimed" : "&aready"));
            l.add("&7Paid chest: " + (c.paidClaimed ? "&8opened" : "&enot opened"));
            l.add("");
            l.add("&eClick to open!");
            return Icons.of(c.freeClaimed && c.paidClaimed ? Material.ENDER_CHEST : Material.CHEST, (t == null ? "&7" : t.colored()) + " Hunt", l);
        }, pc -> chest(pc.click().player(), pc.entry().runId), menu -> menu.set(48, Icons.back("Leviathan's Hollow"), c -> main(c.player())), page).open(p);
    }

    void chest(Player p, String runId) {
        KData d = mod.data(p);
        KData.Chest c = d.chest(runId);
        if (c == null) { Msg.error(p, "That chest is gone."); return; }
        KCatalog.Tier t = mod.cat.tier(c.tier);
        Menu m = new Menu("Leviathan Chests - " + (t == null ? c.tier : t.name), 6).chrome();
        // free chest: left
        m.set(10, Icons.of(Material.CHEST, "&aFree Chest", "&7Everyone who finished the hunt", "&7gets this one."));
        int s = 19;
        for (Map.Entry<String, Integer> e : c.free.entrySet()) { if (s > 21) break; m.set(s++, display(e.getKey(), e.getValue())); }
        if (!c.freeClaimed)
            m.set(37, Icons.glow(Icons.of(Material.LIME_DYE, "&aClaim Free Chest", "", "&eClick to claim!")), x -> claimFree(x.player(), runId));
        else m.set(37, Icons.of(Material.GRAY_DYE, "&8Claimed"));
        // paid chest: right
        String keyId = t == null ? null : t.key;
        String keyName = keyId != null && Sky.items().exists(keyId) ? Text.strip(Sky.items().def(keyId).name) : String.valueOf(keyId);
        int have = keyId != null && Sky.items().exists(keyId) ? Sky.items().count(p, keyId) : 0;
        m.set(15, Icons.of(Material.ENDER_CHEST, "&6Paid Chest", "&7Opened with a &a" + keyName + "&7.", "&7Keys are sold by the faction", "&7quartermasters on the isle."));
        s = 23;
        for (Map.Entry<String, Integer> e : c.paid.entrySet()) { if (s > 25 && s < 32) s = 32; if (s > 34) break; m.set(s++, display(e.getKey(), e.getValue())); }
        if (!c.paidClaimed) {
            m.set(43, Icons.glow(Icons.of(have > 0 ? Material.TRIPWIRE_HOOK : Material.BARRIER, (have > 0 ? "&a" : "&c") + "Open Paid Chest",
                    "&7Costs: &a1x " + keyName, "&7You have: " + (have > 0 ? "&a" : "&c") + have, "", have > 0 ? "&eClick to open!" : "&cYou need a key!")),
                    x -> claimPaid(x.player(), runId));
            if (Sky.items().exists(KISMET) && !c.rerolled) {
                int kis = Sky.items().count(p, KISMET);
                m.set(42, Icons.of(Material.FEATHER, "&dReroll Paid Chest", "&7Spend &a1x " + Text.strip(Sky.items().def(KISMET).name), "&7to roll this chest again (once).",
                        "&7You have: " + (kis > 0 ? "&a" : "&c") + kis, "", kis > 0 ? "&eClick to reroll!" : "&cYou have none."), x -> reroll(x.player(), runId));
            }
            // the Wheel of Embers (reference Wheel of Fate): the chest's shard becomes a higher-rarity one
            String shard = KLoot.shardIn(c.paid);
            KCatalog.Loot lt = t == null ? null : mod.cat.loot.get(t.id);
            if (shard != null && lt != null && Sky.items().exists(WHEEL)) {
                int wheels = Sky.items().count(p, WHEEL);
                String rar = lt.shardRarityOf(shard);
                boolean top = KLoot.upgradeShard(lt, shard, new Random(0), Sky.items()::exists) == null;
                String wheelName = Text.strip(Sky.items().def(WHEEL).name);
                m.set(41, Icons.of(Material.CLOCK, (top ? "&8" : "&d") + "Spin a " + wheelName,
                        "&7Turn the &dshard &7in this chest", "&7(" + (rar == null ? "?" : Text.titleCase(rar.toLowerCase())) + ") into one of a higher rarity.",
                        "&7Costs: &a1x " + wheelName, "&7You have: " + (wheels > 0 ? "&a" : "&c") + wheels,
                        c.wheels > 0 ? "&8Spun " + c.wheels + "x on this chest" : "", "",
                        top ? "&8Already the rarest there is." : wheels > 0 ? "&eClick to spin!" : "&cYou have none."), x -> spin(x.player(), runId));
            }
        } else m.set(43, Icons.of(Material.GRAY_DYE, "&8Opened"));
        m.set(48, Icons.back("Leviathan Chests"), x -> chests(x.player(), 0));
        Run here = mod.runs.of(p);
        if (here != null && here.finished() && runId.equals(here.id)) m.set(50, againIcon(p, here.tier), x -> huntAgain(x.player()));
        m.open(p);
    }

    /** The "Hunt Again" button: same tier, same crew, straight from the finished hollow. */
    private ItemStack againIcon(Player p, KCatalog.Tier t) {
        boolean lead = mod.crew.isLeader(p.getUniqueId());
        UUID l = mod.crew.leader(p.getUniqueId());
        int keys = Sky.items().exists(t.key) ? Sky.items().count(p, t.key) : 0;
        List<String> lore = new ArrayList<>();
        lore.add("&7Set out on another " + t.colored() + " &7hunt");
        lore.add(mod.runs.of(p) != null ? "&7with the same crew, straight from here." : "&7with your crew, like last time.");
        lore.add("&7Unopened chests wait in &a/kuudra chests&7.");
        lore.add("");
        lore.add("&7Keys for the paid chest: " + (keys > 0 ? "&a" : "&c") + keys);
        lore.add("");
        lore.add(lead ? "&eClick to hunt again!" : "&c" + (l == null ? "Your leader" : nameOf(l)) + " starts the next hunt.");
        ItemStack it = Icons.of(Material.FIRE_CHARGE, (lead ? "&a" : "&7") + "Hunt Again", lore);
        return lead ? Icons.glow(it) : it;
    }

    private void huntAgain(Player p) {
        String err = mod.runs.again(p);
        if (err != null) Msg.error(p, err);
        else p.closeInventory();
    }

    private ItemStack display(String id, int n) {
        ItemDef def = Sky.items().def(id);
        if (def == null) return Icons.of(Material.BARRIER, "&c" + id);
        Material mat = Material.matchMaterial(def.material);
        // pet items (ids NAME;rarity) are named after the creature: say it is a pet
        String base = Text.strip(def.name) + (id.indexOf(';') > 0 && !Text.strip(def.name).endsWith("Pet") ? " Pet" : "");
        String nm = def.rarity.code() + base + (n > 1 ? " &8x" + Text.commasInt(n) : "");
        List<String> l = new ArrayList<>(def.lore.size() > 4 ? def.lore.subList(0, 4) : def.lore);
        l.add("");
        l.add(def.rarity.code() + "&l" + def.rarity.display());
        ItemStack it = def.skull != null && !def.skull.isEmpty() ? Icons.skull(def.skull, nm, l) : Icons.of(mat == null ? Material.PAPER : mat, nm, l);
        return n > 1 ? Icons.amount(it, Math.min(64, n)) : it;
    }

    private void claimFree(Player p, String runId) {
        KData d = mod.data(p);
        KData.Chest c = d.chest(runId);
        if (c == null || c.freeClaimed) return;
        c.freeClaimed = true;                     // mark first: a second click can never pay twice
        Sky.profiles().saveNow(p);
        mod.giveItems(p, c.free, "free chest " + runId);
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_CHEST_OPEN, 1f, 1f);
        tidy(d, c);
        chest(p, runId);
    }

    private void claimPaid(Player p, String runId) {
        KData d = mod.data(p);
        KData.Chest c = d.chest(runId);
        if (c == null || c.paidClaimed) return;
        KCatalog.Tier t = mod.cat.tier(c.tier);
        if (t == null || !Sky.items().exists(t.key)) { Msg.error(p, "This chest's key does not exist."); return; }
        if (Sky.items().count(p, t.key) < 1) { Msg.error(p, "You need a " + Text.strip(Sky.items().def(t.key).name) + "."); return; }
        if (Sky.items().remove(p, t.key, 1) != 1) { Msg.error(p, "Could not take the key."); return; }
        c.paidClaimed = true;
        Sky.profiles().saveNow(p);
        mod.giveItems(p, c.paid, "paid chest " + runId + " (key " + t.key + ")");
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
        KCatalog.Loot lt = mod.cat.loot.get(t.id);
        for (String id : c.paid.keySet()) {
            ItemDef def = Sky.items().def(id);
            if (def == null) continue;
            boolean rareRoll = false;
            if (lt != null) for (KCatalog.Rare r : lt.rares) if (id.equals(r.item)) rareRoll = true;
            if (rareRoll || def.rarity.ordinal() >= com.skyclone.api.item.Rarity.LEGENDARY.ordinal()) {
                String what = def.rarity.code() + Text.strip(def.name) + (id.indexOf(';') > 0 ? " Pet" : "");
                Msg.rareDrop(p, rareRoll ? "&d&lCRAZY RARE LEVIATHAN LOOT!" : "&6&lLEVIATHAN LOOT!", what, 0);
                // the crew (still in the hollow) hears about a rare-table drop too
                Run r = mod.runs.of(p);
                if (rareRoll && r != null) for (Player o : r.players()) if (o != p) Msg.send(o, "&d&lRARE! &e" + p.getName() + " &7found " + what + " &7in their chest!");
            }
        }
        tidy(d, c);
        chest(p, runId);
    }

    private void reroll(Player p, String runId) {
        KData d = mod.data(p);
        KData.Chest c = d.chest(runId);
        if (c == null || c.paidClaimed || c.rerolled || !Sky.items().exists(KISMET)) return;
        if (Sky.items().remove(p, KISMET, 1) != 1) { Msg.error(p, "You have no feather to spend."); return; }
        KCatalog.Tier t = mod.cat.tier(c.tier);
        KData.Chest fresh = mod.rollChest(p, t, runId, new Random().nextLong());
        c.paid.clear();
        c.paid.putAll(fresh.paid);
        c.rerolled = true;
        Sky.profiles().saveNow(p);
        Msg.success(p, "The paid chest was rerolled.");
        chest(p, runId);
    }

    /** Spend one Wheel of Embers on the unopened paid chest's shard. The wheel is taken before the chest changes. */
    private void spin(Player p, String runId) {
        KData d = mod.data(p);
        KData.Chest c = d.chest(runId);
        if (c == null || c.paidClaimed || !Sky.items().exists(WHEEL)) return;
        KCatalog.Tier t = mod.cat.tier(c.tier);
        KCatalog.Loot lt = t == null ? null : mod.cat.loot.get(t.id);
        String shard = KLoot.shardIn(c.paid);
        if (lt == null || shard == null) { Msg.error(p, "There is no shard in this chest."); return; }
        String next = KLoot.upgradeShard(lt, shard, new Random(), Sky.items()::exists);
        if (next == null) { Msg.error(p, "That shard is already the rarest there is."); return; }
        if (Sky.items().count(p, WHEEL) < 1 || Sky.items().remove(p, WHEEL, 1) != 1) { Msg.error(p, "You have no " + Text.strip(Sky.items().def(WHEEL).name) + "."); return; }
        java.util.Map<String, Integer> paid = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : c.paid.entrySet()) {
            if (e.getKey().equals(shard)) paid.merge(next, e.getValue(), Integer::sum);
            else paid.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        c.paid.clear();
        c.paid.putAll(paid);
        c.wheels++;
        Sky.profiles().saveNow(p);
        ItemDef nd = Sky.items().def(next);
        Msg.send(p, "&dThe wheel spins... &7your shard became " + (nd == null ? next : nd.rarity.code() + Text.strip(nd.name)) + "&7!");
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.4f);
        Sky.log().info("[kuudra] " + p.getName() + " spun a wheel on " + runId + ": " + shard + " -> " + next);
        chest(p, runId);
    }

    private void tidy(KData d, KData.Chest c) {
        if (c.freeClaimed && c.paidClaimed) d.chests.remove(c);
    }

    // =====================================================================================
    // loot preview + records
    // =====================================================================================

    void lootTiers(Player p) {
        Menu m = new Menu("Loot Preview", 4).chrome();
        int slot = 11;
        for (KCatalog.Tier t : mod.cat.tiers) {
            final KCatalog.Tier tier = t;
            m.set(slot++, Icons.of(TIER_ICONS[t.index], t.colored() + " &7Chests", "", "&eClick to browse!"), c -> loot(c.player(), tier, 0));
        }
        m.set(31, Icons.back("Leviathan's Hollow"), c -> main(c.player()));
        m.open(p);
    }

    void loot(Player p, KCatalog.Tier t, int page) {
        KCatalog.Loot lt = mod.cat.loot.get(t.id);
        List<ItemStack> icons = new ArrayList<>();
        if (lt != null) {
            java.util.function.Predicate<String> ex = Sky.items()::exists;
            if (ex.test(KLoot.ESSENCE)) {
                long paidEss = Math.round((lt.paidEssence == null || lt.paidEssence.length == 0 ? 0 : lt.paidEssence[0]) * KDials.PAID_ESSENCE_SCALE.get());
                icons.add(Icons.withLore(display(KLoot.ESSENCE, 1), List.of("", "&aFree chest: &5" + range(lt.freeEssence),
                        "&6Paid chest: &5" + Text.commasInt(paidEss) + " &7guaranteed")));
            }
            for (KCatalog.Entry g : lt.guaranteed)
                if (g.item != null && ex.test(g.item))
                    icons.add(Icons.withLore(display(g.item, g.count), List.of("", "&6Paid chest: &fx" + g.count + " &7guaranteed",
                            KLoot.isShard(g.item) ? "&8Goes straight into your Hunting Box." : "")));
            double w1 = KCatalog.Loot.weight(lt.slot1, ex), w2 = KCatalog.Loot.weight(lt.slot2, ex);
            for (KCatalog.Entry e : lt.slot1) slotIcon(icons, e, w1, "Slot 1", ex);
            for (KCatalog.Entry e : lt.slot2) slotIcon(icons, e, w2, "Slot 2", ex);
            for (KCatalog.Rare r : lt.rares)
                if (r.chance > 0 && ex.test(r.item))
                    icons.add(Icons.withLore(display(r.item, 1), List.of("", "&dRare: &f" + KLoot.odds(r.chance) + " &7per chest")));
        }
        Menu m = Sky.menus().paged(t.name + " Chest Loot", icons, i -> i, null, menu -> menu.set(48, Icons.back("Loot Preview"), c -> lootTiers(c.player())), page);
        m.open(p);
    }

    private void slotIcon(List<ItemStack> icons, KCatalog.Entry e, double total, String slot, java.util.function.Predicate<String> ex) {
        if (e.item == null || e.weight <= 0 || total <= 0 || !ex.test(e.item)) return;
        List<String> l = new ArrayList<>();
        l.add("");
        l.add("&6Paid chest " + slot + ": &f" + KLoot.odds(e.weight / total));
        if (KLoot.isShard(e.item)) l.add("&dA Wheel of Embers spins it a rarity higher.");
        icons.add(Icons.withLore(display(e.item, e.count), l));
    }

    private static String range(int[] r) {
        if (r == null || r.length == 0) return "0";
        return r.length == 1 || r[1] == r[0] ? Text.commasInt(r[0]) : Text.commasInt(r[0]) + "-" + Text.commasInt(r[1]);
    }

    void stats(Player p) {
        KData d = mod.data(p);
        Menu m = new Menu("Hunt Records", 4).chrome();
        int slot = 11;
        for (KCatalog.Tier t : mod.cat.tiers) {
            Long best = d.bestMillis.get(t.id);
            m.set(slot++, Icons.of(TIER_ICONS[t.index], t.colored() + " &7Tier", "&7Clears: &a" + d.completions(t.id),
                    "&7Best time: &e" + (best == null ? "-" : KMath.clock(best))));
        }
        m.set(22, Icons.of(Material.PAPER, "&aTotals", "&7Hunts set out: &e" + d.huntsStarted, "&7Hunts won: &a" + d.totalCompletions(),
                "&7Tokens earned: &6" + Text.commasInt(d.tokensEarned), "&7Brood slain: &c" + Text.commasInt(d.brood), "&7Deaths: &c" + d.deaths));
        m.set(31, Icons.back("Leviathan's Hollow"), c -> main(c.player()));
        m.open(p);
    }
}
