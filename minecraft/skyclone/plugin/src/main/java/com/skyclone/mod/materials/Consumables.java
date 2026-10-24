package com.skyclone.mod.materials;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;

/**
 * Wave 5: the catalogue's consumables that no other module wires. Each one's def (generated with its ability in
 * {@code items_misc.json}) carries an ability id handled here, so the behaviour exists exactly while materials
 * owns the def: if a specialist module registers the same id first, its def has no such ability and this code
 * never runs for it.
 * <ul>
 *   <li>Chyme - right click: +90,000 exp to the active pet (Features.Pets).</li>
 *   <li>Filet O' Fortune - right click: +20 Mining Fortune for 60 minutes (needs HOTM 4, from the def).</li>
 *   <li>Reaper Pepper - right click: permanently +1 Health and +1 Cold Resistance, up to 5 peppers.</li>
 *   <li>Saving Grace - carried: a death that would cost coins costs none, eats one, and you respawn at home.</li>
 *   <li>Delver's O's cereals (ids DWARVEN_OS_*, fallbacks in {@code items_integration.json}; audit 2026-10-06 mining
 *       follow-up) - right click: the first 5 of EACH kind give +2 of its fortune for good (Ore / Block /
 *       Gemstone / Dwarven Metal Fortune).</li>
 * </ul>
 * Per-player state is member data {@code materials} ({@link Data}); the stat bonus is a StatProvider.
 */
final class Consumables implements Listener {

    static final String CHYME = "CHYME", FILET = "FILET_O_FORTUNE", PEPPER = "REAPER_PEPPER", GRACE = "SAVING_GRACE";
    static final String A_CHYME = "materials_chyme", A_FILET = "materials_filet_o_fortune",
            A_PEPPER = "materials_reaper_pepper", A_GRACE = "materials_saving_grace";
    /** Item id -> the ability id its generated def carries. */
    static final List<String[]> WIRED = List.of(
            new String[]{CHYME, A_CHYME}, new String[]{FILET, A_FILET},
            new String[]{PEPPER, A_PEPPER}, new String[]{GRACE, A_GRACE});

    /** Dwarven O's cereal id -> the stat each one eaten raises (item id, ability id, stat). */
    static final List<Object[]> CEREALS = List.of(
            new Object[]{"DWARVEN_OS_ORE_OATS", "materials_dwarven_os_ore_oats", Stat.ORE_FORTUNE},
            new Object[]{"DWARVEN_OS_BLOCK_BRAN", "materials_dwarven_os_block_bran", Stat.BLOCK_FORTUNE},
            new Object[]{"DWARVEN_OS_GEMSTONE_GRAHAMS", "materials_dwarven_os_gemstone_grahams", Stat.GEMSTONE_FORTUNE},
            new Object[]{"DWARVEN_OS_METALLIC_MINIS", "materials_dwarven_os_metallic_minis", Stat.DWARVEN_METAL_FORTUNE});

    /** Member data key {@code materials}. Add fields, never retype them (CORE_GUIDE §0.3). */
    public static final class Data {
        public int reaperPeppers;
        public long filetUntil;
        public int savingGracesUsed;
        /** Cereal item id -> how many of that kind were eaten. */
        public java.util.Map<String, Integer> cereals = new java.util.HashMap<>();
    }

    private final MaterialsModule mod;
    private Dials.Dial<Boolean> enabled;
    private Dials.Dial<Integer> chymeXp;
    private Dials.Dial<Integer> filetFortune;
    private Dials.Dial<Integer> filetMinutes;
    private Dials.Dial<Integer> pepperCap;
    private Dials.Dial<Boolean> graceHome;
    private Dials.Dial<Integer> cerealCap;
    private Dials.Dial<Integer> cerealFortune;

    Consumables(MaterialsModule mod) { this.mod = mod; }

    void enable() {
        enabled = Sky.dials().flag("materials.consumables", true,
                "Catalogue consumables nobody else wires (Chyme, Filet O' Fortune, Reaper Pepper, Saving Grace) work. Live.");
        chymeXp = Sky.dials().integer("materials.chyme-pet-xp", 90_000,
                "Pet exp one Chyme feeds the active pet (reference 90,000). Live.");
        filetFortune = Sky.dials().integer("materials.filet-mining-fortune", 20,
                "Mining Fortune while a Filet O' Fortune is active (reference +20). Live.");
        filetMinutes = Sky.dials().integer("materials.filet-minutes", 60,
                "How long one Filet O' Fortune lasts, in minutes (reference 60). Eating another resets it. Live.");
        pepperCap = Sky.dials().integer("materials.reaper-pepper-cap", 5,
                "Reaper Peppers one profile member can eat; each is +1 Health and +1 Cold Resistance for good. Live.");
        graceHome = Sky.dials().flag("materials.saving-grace-home", true,
                "A Saving Grace also respawns you on your private island (false = coins only). Live.");

        cerealCap = Sky.dials().integer("materials.cereal-cap", 5,
                "Delver's O's cereals of EACH kind one profile member can eat for a stat (reference 5). Live.");
        cerealFortune = Sky.dials().integer("materials.cereal-fortune", 2,
                "Fortune each Delver's O's cereal adds for good: Ore / Block / Gemstone / Dwarven Metal (reference +2). Live.");
        for (Object[] c : CEREALS) {
            String item = (String) c[0];
            Stat stat = (Stat) c[2];
            Sky.abilities().register((String) c[1], use -> cereal(use.player(), item, stat));
        }
        Sky.abilities().register(A_CHYME, use -> chyme(use.player()));
        Sky.abilities().register(A_FILET, use -> filet(use.player()));
        Sky.abilities().register(A_PEPPER, use -> pepper(use.player()));
        Sky.stats().addProvider("materials.consumables", (p, into) -> {
            if (!enabled.get() || !Sky.profiles().isReady(p)) return;
            Data d = data(p);
            contribute(d, System.currentTimeMillis(), filetFortune.get(), pepperCap.get(), into);
            contributeCereals(d, cerealCap.get(), cerealFortune.get(), into);
        });
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());
    }

    // ---- pure (self-tests and JUnit) ------------------------------------------------------------

    /** What a member's consumable state adds to their stats at {@code now}. */
    static void contribute(Data d, long now, int filetFortune, int pepperCap, StatMap into) {
        if (d == null) return;
        int peppers = Math.max(0, Math.min(d.reaperPeppers, Math.max(0, pepperCap)));
        if (peppers > 0) {
            into.add(Stat.HEALTH, peppers);
            into.add(Stat.COLD_RESISTANCE, peppers);
        }
        if (d.filetUntil > now) into.add(Stat.MINING_FORTUNE, filetFortune);
    }

    /** The cereals' permanent fortune: {@code each} per cereal eaten, at most {@code cap} of each kind. */
    static void contributeCereals(Data d, int cap, int each, StatMap into) {
        if (d == null || d.cereals == null || each <= 0) return;
        for (Object[] c : CEREALS) {
            int n = Math.max(0, Math.min(d.cereals.getOrDefault((String) c[0], 0), Math.max(0, cap)));
            if (n > 0) into.add((Stat) c[2], (double) n * each);
        }
    }

    /** New expiry after eating a filet: a fresh full duration, never shortening one already running longer. */
    static long filetExpiry(long current, long now, long durationMs) {
        return Math.max(current, now + Math.max(0, durationMs));
    }

    // ---- abilities -------------------------------------------------------------------------------

    Data data(Player p) { return Sky.profiles().member(p, "materials", Data.class); }

    private boolean ready(Player p) {
        if (!enabled.get()) { Msg.error(p, "&cThat item does nothing right now."); return false; }
        if (!Sky.profiles().isReady(p)) { Msg.error(p, "&cYour profile is still loading."); return false; }
        return true;
    }

    private boolean chyme(Player p) {
        if (!ready(p)) return false;
        Features.Pets pets = Sky.service(Features.Pets.class);
        if (!Sky.has(Features.Pets.class) || pets.activeType(p) == null) {
            Msg.error(p, "&cSummon a pet first, then feed it the Chyme.");
            return false;
        }
        if (Sky.items().remove(p, CHYME, 1) < 1) return false;
        int xp = Math.max(0, chymeXp.get());
        pets.addPetXp(p, xp, "chyme");
        Msg.send(p, "&aYour pet devoured the Chyme! &6+" + Text.commasInt(xp) + " Pet Exp");
        Msg.sound(p, Sound.ENTITY_GENERIC_EAT, 0.8f);
        return true;
    }

    private boolean filet(Player p) {
        if (!ready(p)) return false;
        if (Sky.items().remove(p, FILET, 1) < 1) return false;
        Data d = data(p);
        long now = System.currentTimeMillis();
        int minutes = Math.max(1, filetMinutes.get());
        d.filetUntil = filetExpiry(d.filetUntil, now, minutes * 60_000L);
        Sky.stats().invalidate(p);
        Msg.send(p, "&aYou ate a Filet O' Fortune! &6+" + filetFortune.get() + "☘ Mining Fortune &afor &e"
                + Text.duration((d.filetUntil - now) / 1000) + "&a.");
        Msg.sound(p, Sound.ENTITY_GENERIC_EAT, 1.2f);
        return true;
    }

    private boolean pepper(Player p) {
        if (!ready(p)) return false;
        Data d = data(p);
        int cap = Math.max(0, pepperCap.get());
        if (d.reaperPeppers >= cap) {
            Msg.error(p, "&cYou can't stomach another Reaper Pepper! &7(" + d.reaperPeppers + "/" + cap + ")");
            return false;
        }
        if (Sky.items().remove(p, PEPPER, 1) < 1) return false;
        d.reaperPeppers++;
        Sky.profiles().saveNow(p);
        Sky.stats().invalidate(p);
        Msg.send(p, "&cYou ate a Reaper Pepper! &7It burns. &c+1❤ Health &7and &b+1❄ Cold Resistance &7for good. &8("
                + d.reaperPeppers + "/" + cap + ")");
        Msg.sound(p, Sound.ENTITY_BLAZE_SHOOT, 1.4f);
        return true;
    }

    private boolean cereal(Player p, String item, Stat stat) {
        if (!ready(p)) return false;
        Data d = data(p);
        if (d.cereals == null) d.cereals = new java.util.HashMap<>();
        int cap = Math.max(0, cerealCap.get()), eaten = d.cereals.getOrDefault(item, 0);
        if (eaten >= cap) {
            Msg.error(p, "&cYou already got everything this cereal has to give! &7(" + eaten + "/" + cap + ")");
            return false;
        }
        if (Sky.items().remove(p, item, 1) < 1) return false;
        d.cereals.put(item, eaten + 1);
        Sky.profiles().saveNow(p);
        Sky.stats().invalidate(p);
        Msg.send(p, "&aCrunch! &6+" + cerealFortune.get() + "☘ " + stat.display + " &afor good. &8(" + (eaten + 1) + "/" + cap + ")");
        Msg.sound(p, Sound.ENTITY_GENERIC_EAT, 1.0f);
        return true;
    }

    // ---- Saving Grace ----------------------------------------------------------------------------

    /** HIGHEST: read the coin loss after every rule (safe zones, dungeons, mayor perks) has had its say. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(SkyEvents.SkyDeath ev) {
        Player p = ev.player;
        if (p == null || !enabled.get() || ev.coinsLost <= 0) return;
        if (!ownsGrace() || Sky.items().count(p, GRACE) < 1) return;
        if (Sky.items().remove(p, GRACE, 1) < 1) return;
        double saved = ev.coinsLost;
        ev.coinsLost = 0;
        if (graceHome.get() && Sky.has(Features.PrivateIsland.class)) {
            Location home = Sky.service(Features.PrivateIsland.class).home(p);
            if (home != null) ev.respawnAt = home;
        }
        if (Sky.profiles().isReady(p)) data(p).savingGracesUsed++;
        Msg.send(p, "&dYour Saving Grace crumbled and caught you! &7You kept &6" + Text.commas(saved) + " coins&7.");
        Msg.sound(p, Sound.ITEM_TOTEM_USE, 1.2f);
    }

    /** Saving Grace is passive, so its def is checked here rather than by an ability id. */
    private boolean ownsGrace() {
        ItemDef d = Sky.items().def(GRACE);
        return d != null && mod.catalogue().registered().contains(GRACE) && d.abilities != null
                && d.abilities.stream().anyMatch(a -> A_GRACE.equals(a.id));
    }

    // ---- admin ------------------------------------------------------------------------------------

    String describe(Player p) {
        if (!Sky.profiles().isReady(p)) return "&cprofile not ready";
        Data d = data(p);
        long left = Math.max(0, d.filetUntil - System.currentTimeMillis()) / 1000;
        return "&7Reaper Peppers &c" + d.reaperPeppers + "&7/" + pepperCap.get() + " &7| Filet O' Fortune "
                + (left > 0 ? "&a" + Text.duration(left) + " left" : "&8inactive") + " &7| Saving Graces used &d"
                + d.savingGracesUsed + " &7| Cereals eaten &6" + (d.cereals == null ? "{}" : d.cereals);
    }

    /** Admin: reset or set a player's state ("pepper N", "filet MINUTES", "reset"). */
    String set(Player p, String what, int n) {
        if (!Sky.profiles().isReady(p)) return "&cprofile not ready";
        Data d = data(p);
        switch (what) {
            case "pepper" -> d.reaperPeppers = Math.max(0, n);
            case "filet" -> d.filetUntil = n <= 0 ? 0 : System.currentTimeMillis() + n * 60_000L;
            case "reset" -> { d.reaperPeppers = 0; d.filetUntil = 0; d.savingGracesUsed = 0; if (d.cereals != null) d.cereals.clear(); }
            default -> { return "&cUse pepper <n>, filet <minutes> or reset."; }
        }
        Sky.stats().invalidate(p);
        return "&aOK. " + describe(p);
    }

    Dials.Dial<Integer> dialFiletFortune() { return filetFortune; }
    Dials.Dial<Integer> dialPepperCap() { return pepperCap; }
}
