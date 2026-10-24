package com.skyclone.mod.hub;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.config.Dials;
import com.skyclone.api.mob.MobBehaviour;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.world.gen.Box;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Golden Ghoul keeps to its vault.
 *
 * <p>Wave-4 playtest ("loops", hub): a Ghoul-slayer grinder (600 HP, 630 Defense) farming Crypt Ghouls for the
 * T3 Ghoul bar died three times in about six minutes and lost 2M, 1M and 500k coins (half the purse each time)
 * while only about 10% of a 2,400 XP bar filled. The Crypt Ghouls were not the cause: the Lv60 Golden Ghoul
 * (45,000 HP, 800 a hit, the reference's numbers) spawned anywhere in the Crypt and walked the main hall about
 * 14 blocks past the landing, which is exactly where everyone grinds. A player who came for the 2,000 HP
 * ghouls had no way to keep out of the 45,000 HP one, and an AI player does not read a nameplate's level.
 *
 * <p>So the Golden Ghoul has a place of its own and the danger is opt-in:
 * <ul>
 *   <li>it spawns only in the vault ({@code crypt_vault}: the dead-end room at the top of the first north
 *       branch, 47 blocks up a side corridor from the main hall; generator v8);</li>
 *   <li>it fights only a player standing in the vault (its floor plus {@link #MARGIN} blocks, so the doorway
 *       counts) or a player who hit it in the last {@link GraveyardPack#ENGAGE_MS}. Anyone else is dropped as
 *       a target and none of its swings land on them ({@code onAttack} cancels: the guarantee, not the AI);</li>
 *   <li>with no target and outside its vault, it walks home;</li>
 *   <li>it notices players at {@code hub.golden-ghoul-aggro} (8) rather than the Crypt pack's 12.</li>
 * </ul>
 * It still carries {@code hub_crypt_pack}, so inside the vault it counts toward the Crypt's chaser cap.
 * Walking in says so ({@code Golden Ghoul's Vault} zone), and the Crypt's entrance says where the vault is
 * and what a death there costs ({@link #cryptTipLines}). The rule itself is {@link #mayFight}, pure.
 */
final class GoldenVault implements MobBehaviour {

    static final String ID = "hub_golden_vault";
    /** The layout area the Golden Ghoul spawns in, and the zone (area + {@link #MARGIN}) that warns on entry. */
    static final String AREA = "crypt_vault";
    /** Blocks round the vault floor a player still counts as inside (the doorway). */
    static final int MARGIN = 2;
    static final long HOME_EVERY_MS = 2_000;
    static final long VAULT_WARN_EVERY_MS = 5 * 60_000L;
    static final long CRYPT_TIP_EVERY_MS = 20 * 60_000L;
    /** The Crypt tip mentions banking from this purse up. */
    static final double TIP_PURSE = 1_000;

    static final GoldenVault INSTANCE = new GoldenVault();

    static Dials.Dial<Boolean> keepToVault, cryptTip;
    static Dials.Dial<Double> aggro;

    /** mob uuid -> (player uuid -> engaged until, ms). */
    private final Map<UUID, Map<UUID, Long>> engaged = new HashMap<>();
    private final Map<UUID, Long> homeSent = new HashMap<>();
    private Box vault;
    private long vaultLookedUp;

    private GoldenVault() {}

    static void register() {
        Dials d = Sky.dials();
        keepToVault = d.flag("hub.golden-ghoul-vault", true,
                "The Golden Ghoul fights only players inside its vault (the north branch's dead-end room) or who hit it; "
                        + "false = it fights anyone in the Crypt (the wave-4 coin furnace)");
        aggro = d.number("hub.golden-ghoul-aggro", 8,
                "Blocks at which the Golden Ghoul notices a player (it stays in its vault; the Crypt Ghouls use hub.crypt-aggro-range)");
        cryptTip = d.flag("hub.crypt-tip", true,
                "Walking into the Crypt says what a death costs (half the purse) and where the Golden Ghoul's vault is (every 20 min at most)");
        Sky.mobs().registerBehaviour(ID, INSTANCE);
    }

    // ---- the pure rule ----------------------------------------------------------------------------

    /** May the Golden Ghoul target and hit this player? */
    static boolean mayFight(boolean ruleOn, boolean playerInVault, boolean engagedByPlayer) {
        return !ruleOn || playerInVault || engagedByPlayer;
    }

    /** The player's feet are on the vault floor or within {@link #MARGIN} of it. Null vault (old layout) = everywhere. */
    static boolean inVault(Box vault, double x, double y, double z) {
        return vault == null || vault.grow(MARGIN).contains(x, y, z);
    }

    /** What to tell a player walking into the Crypt. Pure. */
    static List<String> cryptTipLines(double purse, int maxChasers) {
        List<String> out = new ArrayList<>();
        if (purse >= TIP_PURSE)
            out.add("&7- Dying here costs &6half your purse &7(" + coins(Math.floor(purse / 2)) + " coins right now). "
                    + "Bank them first: the &6Banker &7in the Village (&e/village&7), or &e/bank &7with a Boon Buff.");
        out.add("&7- The &6Golden Ghoul &7(&cLv60, 45,000 HP, 800 damage a hit&7) lives in its &6vault&7, the room at the far end "
                + "of the first north branch. Outside it, it leaves you alone unless you hit it.");
        out.add("&7- Crypt Ghouls hit for 350" + (maxChasers > 0 ? ": at most " + maxChasers + " chase you at once" : "")
                + ", and none follow you onto the stairs (the entrance is safe ground).");
        return out;
    }

    /** Said on walking into the vault. Pure. */
    static List<String> vaultLines(double purse) {
        List<String> out = new ArrayList<>();
        out.add("&6&lGOLDEN GHOUL'S VAULT! &7The Golden Ghoul (&cLv60, 45,000 HP, 800 damage a hit&7) fights anyone in this room.");
        if (purse >= TIP_PURSE)
            out.add("&7- A death costs &6" + coins(Math.floor(purse / 2)) + " coins &7(half your purse). Step back out of the doorway and it lets you go.");
        else
            out.add("&7- Step back out of the doorway and it lets you go (unless you hit it).");
        return out;
    }

    /** Blocks (horizontal) the vault must keep from the warp.crypt landing: the grind hall lies between. */
    static final double MIN_FROM_LANDING = 30;

    /**
     * Null if the layout gives the Golden Ghoul a proper vault: the area exists inside the Crypt's spawn area,
     * far from the warp.crypt landing and clear of the SAFE landing zone, and the smallest zone at its centre is
     * the {@code crypt_vault} zone (not SAFE, so the Ghoul can spawn). Pure (Layout only): self-test and unit test.
     */
    static String vaultProblem(com.skyclone.api.world.gen.Layout l) {
        if (l == null) return "no layout";
        Box v = l.area(AREA);
        if (v == null) return "no " + AREA + " area";
        Box crypt = l.area("crypt");
        if (crypt == null) return "no crypt area";
        if (v.minX() < crypt.minX() || v.maxX() > crypt.maxX() || v.minZ() < crypt.minZ() || v.maxZ() > crypt.maxZ()
                || v.minY() < crypt.minY() || v.maxY() > crypt.maxY())
            return AREA + " " + v + " is not inside the crypt area " + crypt;
        var w = l.anchor("warp.crypt");
        if (w == null) return "warp.crypt anchor missing";
        double dx = Math.max(0, Math.max(v.minX() - w.x(), w.x() - (v.maxX() + 1)));
        double dz = Math.max(0, Math.max(v.minZ() - w.z(), w.z() - (v.maxZ() + 1)));
        if (Math.hypot(dx, dz) < MIN_FROM_LANDING)
            return String.format(Locale.ROOT, "the vault is %.0f blocks from warp.crypt (want %.0f+)", Math.hypot(dx, dz), MIN_FROM_LANDING);
        com.skyclone.api.world.gen.Layout.Zone best = null;
        double cx = v.centerX() + 0.5, cy = v.minY() + 0.1, cz = v.centerZ() + 0.5;
        for (var zn : l.zones()) {
            if (zn.id().equals("crypt_landing") && zn.box().intersects(v.grow(MARGIN))) return "the vault overlaps the SAFE landing";
            if (zn.box().contains(cx, cy, cz) && (best == null || zn.box().volume() < best.box().volume())) best = zn;
        }
        if (best == null || !best.id().equals(AREA)) return "the zone at the vault's centre is " + (best == null ? "none" : best.id()) + ", not " + AREA;
        if (best.flags() != null && best.flags().contains(com.skyclone.api.world.gen.Layout.Zone.SAFE)) return "the vault zone is SAFE";
        return null;
    }

    static String coins(double v) { return String.format(Locale.ROOT, "%,d", (long) Math.max(0, v)); }

    // ---- hooks ----------------------------------------------------------------------------------

    @Override
    public void onSpawn(LivingEntity mob, MobDef def) {
        setFollowRange(mob);
    }

    @Override
    public void onTick(LivingEntity mob, MobDef def, long tick) {
        if (!(mob instanceof Mob m)) return;
        setFollowRange(mob);     // the Crypt pack's dial resets every member's range to its own; ours wins
        if (m.getTarget() instanceof Player p && p.isValid() && !p.isDead()) {
            if (allowed(m, p)) return;
            m.setTarget(null);
        }
        goHome(m);
    }

    @Override
    public void onAttack(LivingEntity mob, MobDef def, Player target, DamageContext ctx) {
        if (mob instanceof Mob m && !allowed(m, target)) ctx.cancelled = true;
    }

    @Override
    public void onDamaged(LivingEntity mob, MobDef def, DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null) return;
        engaged.computeIfAbsent(mob.getUniqueId(), k -> new HashMap<>())
                .put(p.getUniqueId(), System.currentTimeMillis() + GraveyardPack.ENGAGE_MS);
    }

    @Override
    public void onDeath(LivingEntity mob, MobDef def, Player killer) {
        engaged.remove(mob.getUniqueId());
        homeSent.remove(mob.getUniqueId());
    }

    // ---- helpers --------------------------------------------------------------------------------

    private boolean allowed(Mob m, Player p) {
        if (p.getWorld() != m.getWorld()) return false;
        Location at = p.getLocation();
        return mayFight(keepToVault == null || keepToVault.get(), inVault(vault(), at.getX(), at.getY(), at.getZ()), engagedBy(m, p));
    }

    private boolean engagedBy(Mob m, Player p) {
        Map<UUID, Long> by = engaged.get(m.getUniqueId());
        if (by == null) return false;
        Long until = by.get(p.getUniqueId());
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            by.remove(p.getUniqueId());
            if (by.isEmpty()) engaged.remove(m.getUniqueId());
            return false;
        }
        return true;
    }

    /** No target and off the vault floor: walk back (every {@link #HOME_EVERY_MS} at most). */
    private void goHome(Mob m) {
        Box v = vault();
        if (v == null || keepToVault == null || !keepToVault.get()) return;
        Location l = m.getLocation();
        if (v.grow(1).contains(l.getX(), l.getY(), l.getZ())) return;
        long now = System.currentTimeMillis();
        if (homeSent.size() > 64) homeSent.clear();
        Long last = homeSent.get(m.getUniqueId());
        if (last != null && now - last < HOME_EVERY_MS) return;
        homeSent.put(m.getUniqueId(), now);
        try {
            m.getPathfinder().moveTo(new Location(m.getWorld(), v.centerX() + 0.5, v.minY(), v.centerZ() + 0.5));
        } catch (RuntimeException ignored) { }
    }

    /** The vault box in hub coordinates, or null until the hub's layout has one (looked up every 10 s while missing). */
    Box vault() {
        if (vault == null) {
            long now = System.currentTimeMillis();
            if (now - vaultLookedUp > 10_000) {
                vaultLookedUp = now;
                try { vault = Sky.worlds().area(HubModule.ISLAND, AREA); } catch (RuntimeException ignored) { }
            }
        }
        return vault;
    }

    private void setFollowRange(LivingEntity mob) {
        if (aggro == null) return;
        AttributeInstance fr = mob.getAttribute(Attribute.FOLLOW_RANGE);
        double want = Math.max(4, aggro.get());
        if (fr != null && fr.getBaseValue() != want) fr.setBaseValue(want);
    }

    /** For {@code /sb hub pack}. */
    List<String> report() {
        Box v = vault();
        return List.of("&6Golden Ghoul &7vault " + (v == null ? "&cmissing (old hub build? /sb island build hub force)"
                : "&a" + v.minX() + "," + v.minY() + "," + v.minZ() + " .. " + v.maxX() + "," + v.maxY() + "," + v.maxZ())
                + " &7keep-to-vault &f" + (keepToVault != null && keepToVault.get()) + " &7aggro &f" + (aggro == null ? "?" : aggro.get())
                + " &7engaged by &f" + engaged.size() + " &7ghoul(s)");
    }

    // ---- the Crypt tip and the vault warning ------------------------------------------------------

    private static final Map<UUID, Long> cryptTipped = new HashMap<>();
    private static final Map<UUID, Long> vaultWarned = new HashMap<>();

    /** Walked into the Crypt (or landed on its stair): what a death costs and where the vault is. */
    static void maybeCryptTip(Player p) {
        if (cryptTip == null || !cryptTip.get() || !Sky.profiles().isReady(p)) return;
        if (!due(cryptTipped, p, CRYPT_TIP_EVERY_MS)) return;
        Msg.send(p, "&c&lCRYPTS! &7Ghouls hit hard down here. Before you fight:");
        int cap = GraveyardPack.CRYPT.maxChasers == null ? 0 : GraveyardPack.CRYPT.maxChasers.get();
        for (String l : cryptTipLines(purse(p), cap)) Msg.send(p, l);
    }

    /** Walked into the vault: always worth saying (every 5 minutes at most). */
    static void maybeVaultWarning(Player p) {
        if (keepToVault == null || !keepToVault.get() || !Sky.profiles().isReady(p)) return;
        if (!due(vaultWarned, p, VAULT_WARN_EVERY_MS)) return;
        for (String l : vaultLines(purse(p))) Msg.send(p, l);
    }

    private static boolean due(Map<UUID, Long> seen, Player p, long every) {
        long now = System.currentTimeMillis();
        Long last = seen.get(p.getUniqueId());
        if (last != null && now - last < every) return false;
        seen.put(p.getUniqueId(), now);
        return true;
    }

    private static double purse(Player p) {
        try { return Sky.economy().purse(p); } catch (RuntimeException e) { return 0; }
    }

    static void forget(UUID id) {
        cryptTipped.remove(id);
        vaultWarned.remove(id);
    }
}
