package com.skyclone.mod.bank;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.ui.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wave 5: the Community Shop's <b>Co-op Slots</b> upgrade (research 05 section 6.4 / 11: "Max members 5, +1/+2/+3 via
 * Co-op Slots I-III -> 8"). Core's {@code /coop} enforces ONE server-wide cap (dial {@code profiles.max-coop-members});
 * the bank enforces the per-profile cap underneath it: {@code base} members, or the owned tier's value.
 *
 * <p>So that the upgrade can add anything, the server-wide cap must leave room above the base: at boot, when core's
 * cap is still at its untouched default of 5, it is raised to {@code base + 3} (dial
 * {@code bank.cshop.coop-raise-core-cap}; an admin-set cap is never touched). A typed {@code /coop invite} or
 * {@code /coopadd} over the profile's cap is refused (same listener pattern menus uses for its invite setting), and
 * so is the matching {@code /coop accept} if the co-op filled up meanwhile.
 */
final class CoopSlots implements Listener {

    private static Dials.Dial<Integer> coreCap;
    private static final int CORE_DEFAULT = 5;

    /** target uuid -> {profile id, cap at invite time, expiry}. */
    private record Pending(String profileId, int cap, long expires) {}
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    static void register() {
        // returns core's own dial (same key, same default); only read and, once at boot, raised from its default
        coreCap = Sky.dials().integer("profiles.max-coop-members", CORE_DEFAULT, "Members per co-op profile (genre: 5)");
        Bukkit.getPluginManager().registerEvents(new CoopSlots(), Sky.plugin());
    }

    /** postEnable: make room above the base for the upgrade's tiers (only if nobody tuned the core cap). */
    static void raiseCoreCap() {
        if (coreCap == null || !Bank.coopRaiseCoreCap.get()) return;
        int want = base() + maxExtra();
        int now = coreCap.get();
        if (now == CORE_DEFAULT && want > now) {
            coreCap.set(want);
            Sky.log().info("[bank] raised profiles.max-coop-members " + now + " -> " + want
                    + " so Co-op Slots I-III can add members; the bank keeps each co-op at " + base() + " + its Co-op Slots tier");
        }
    }

    static int base() { return Math.max(1, Bank.coopBase.get()); }

    /** Extra members the top tier gives (research: +3). */
    static int maxExtra() {
        BankCatalog.Upgrade u = Bank.catalog == null ? null : Bank.catalog.upgrades.get("coop_slots");
        if (u == null || u.maxTier() <= 0) return 0;
        return Math.max(0, (int) Math.round(u.tier(u.maxTier()).value) - CORE_DEFAULT);
    }

    static int coreCap() { return coreCap == null ? CORE_DEFAULT : Math.max(1, coreCap.get()); }

    /** The upgrade can add a member only when core's server-wide cap is above the base. */
    static boolean live() { return coreCap() > base(); }

    /** The co-op cap of this player's profile: base, or the owned Co-op Slots tier, never above core's cap. */
    static int capFor(Player p) {
        int tier = CommunityShop.owned(p, "coop_slots");
        double v = tier > 0 ? CommunityShop.value(p, "coop_slots") : 0;
        return BankMath.coopCap(base(), tier, v, CORE_DEFAULT, coreCap());
    }

    // ---- enforcement -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!Bank.coopEnforce.get()) return;
        String[] w = BankMath.coopCommand(e.getMessage());
        if (w == null) return;
        Player p = e.getPlayer();
        if (!Bank.ready(p)) return;
        if (w[0].equals("invite")) {
            String pid = Sky.profiles().profileId(p);
            if (pid == null) return;
            int members = Sky.profiles().members(pid).size();
            int cap = capFor(p);
            if (members >= cap) {
                e.setCancelled(true);
                full(p, cap);
                return;
            }
            Player t = Bukkit.getPlayerExact(w[1]);
            if (t != null) PENDING.put(t.getUniqueId(), new Pending(pid, cap, System.currentTimeMillis() + 10 * 60_000L));
        } else if (w[0].equals("accept")) {
            Pending inv = PENDING.get(p.getUniqueId());
            if (inv == null) return;
            if (inv.expires() < System.currentTimeMillis()) { PENDING.remove(p.getUniqueId()); return; }
            int members = Sky.profiles().members(inv.profileId()).size();
            if (members >= inv.cap() && members > 0) {
                e.setCancelled(true);
                PENDING.remove(p.getUniqueId());
                Msg.error(p, "That co-op is full (" + inv.cap() + " members).");
            }
        }
    }

    private static void full(Player p, int cap) {
        int tier = CommunityShop.owned(p, "coop_slots");
        BankCatalog.Upgrade u = Bank.catalog.upgrades.get("coop_slots");
        boolean more = u != null && tier < u.maxTier() && live();
        Msg.error(p, "This co-op is full (" + cap + " members)."
                + (more ? " Build Co-op Slots at the Community Shop (/cshop) for more." : ""));
    }

    static void forget(Player p) { if (p != null) PENDING.remove(p.getUniqueId()); }
}
