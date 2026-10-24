package com.thestill.admin;

import com.thestill.economy.Economy;
import com.thestill.economy.Money;
import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.rank.Rank;
import com.thestill.rank.RankLadder;
import com.thestill.rank.RankService;
import com.thestill.role.RoleConfig;
import com.thestill.role.RoleDefinition;
import com.thestill.role.RoleService;
import com.thestill.role.Static;
import com.thestill.ui.NametagService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;

import java.util.List;
import java.util.Map;

/**
 * Every change {@code /adminset} can make, in one audited place.
 *
 * <p>The menus above this class draw tiles and read values; they do not write
 * anything. That split is what makes three rules hold everywhere instead of on
 * whichever page somebody remembered:
 *
 * <ol>
 *   <li><b>Nothing writes a field directly.</b> Money goes through {@link Economy},
 *       Static through {@link Static}, the ladder through {@link RankService},
 *       tags through {@link RoleService}. Those classes carry the invariants — a
 *       deposit cannot be negative, a balance cannot be NaN, a role cannot grant a
 *       banned perk — and an admin path that reached past them would be the one
 *       place none of that applied.
 *   <li><b>Every change is audited</b>, with the value it replaced. See
 *       {@link AdminAudit}.
 *   <li><b>Every change re-checks the target first.</b> An offline edit holds a
 *       detached copy of somebody's profile; if they log in while the menu is open
 *       that copy is stale, and writing it would silently roll their live session
 *       back. {@link OfflineProfiles} rule 5, enforced here rather than trusted to
 *       each caller.
 * </ol>
 */
public final class AdminActions {

    /** What happened, and what to tell the admin. Never a silent no-op. */
    public record Outcome(boolean ok, String message) {

        static Outcome done(String message) {
            return new Outcome(true, message);
        }

        static Outcome refused(String why) {
            return new Outcome(false, why);
        }
    }

    private final OfflineProfiles offline;
    private final Profiles profiles;
    private final Economy economy;
    private final RankService ranks;
    private final RankLadder ladder;
    private final RoleService roles;
    private final NametagService nametags;
    private final AdminAudit audit;

    public AdminActions(OfflineProfiles offline, Profiles profiles, Economy economy,
                        RankService ranks, RankLadder ladder, RoleService roles,
                        NametagService nametags, AdminAudit audit) {
        this.offline = offline;
        this.profiles = profiles;
        this.economy = economy;
        this.ranks = ranks;
        this.ladder = ladder;
        this.roles = roles;
        this.nametags = nametags;
        this.audit = audit;
    }

    // ---- reads the menus need -------------------------------------------

    public RankLadder ladder() {
        return ladder;
    }

    public RankService ranks() {
        return ranks;
    }

    public RoleService roles() {
        return roles;
    }

    public AdminAudit audit() {
        return audit;
    }

    /** The live player behind a target, or null — they may have quit mid-menu. */
    public Player online(AdminTarget target) {
        return Bukkit.getPlayer(target.uuid());
    }

    /**
     * True when a detached copy has been overtaken by the real thing.
     *
     * <p>The menu asks this on every click, not just at open: an admin can sit on
     * an offline player's page for ten minutes, and the moment that player joins,
     * every value on screen is a photograph.
     */
    public boolean stale(AdminTarget target) {
        return !target.live() && profiles.get(target.uuid()) != null;
    }

    // ---- money -----------------------------------------------------------

    public Outcome setMoney(Player admin, AdminTarget target, long minorUnits) {
        Outcome blocked = guard(target);
        if (blocked != null) {
            return blocked;
        }
        Profile profile = target.profile();
        long before = profile.balance();
        economy.set(profile, Math.max(0L, minorUnits), "adminset by " + admin.getName());
        return finish(admin, target, "balance", Money.format(before),
                Money.format(profile.balance()),
                "Balance is now " + Money.format(profile.balance()) + ".");
    }

    /**
     * Moves a balance by a delta.
     *
     * <p>Deliberately not {@code setBalance(current + delta)}: a positive delta is a
     * deposit and a negative one is a withdrawal, and those are separate methods on
     * {@link Economy} because letting one do the other's job is how a sign error
     * becomes an exploit. A withdrawal larger than the balance takes what is there
     * and stops at zero rather than failing outright — an admin typing "-1m" at
     * somebody holding $400k means "empty them", not "do nothing".
     */
    public Outcome addMoney(Player admin, AdminTarget target, long deltaMinor) {
        Outcome blocked = guard(target);
        if (blocked != null) {
            return blocked;
        }
        Profile profile = target.profile();
        long before = profile.balance();
        if (deltaMinor >= 0) {
            economy.deposit(profile, deltaMinor, "adminset by " + admin.getName());
        } else {
            economy.withdraw(profile, Math.min(before, -deltaMinor),
                    "adminset by " + admin.getName());
        }
        return finish(admin, target, "balance", Money.format(before),
                Money.format(profile.balance()),
                (deltaMinor >= 0 ? "Gave " : "Took ") + Money.format(Math.abs(deltaMinor))
                        + " — now " + Money.format(profile.balance()) + ".");
    }

    // ---- Static ----------------------------------------------------------

    /**
     * Sets a Static balance by spending or granting the difference.
     *
     * <p>There is no {@code Static.set} and there should not be: {@link Static#give}
     * also moves the lifetime total, which is the number a refund question is
     * answered from. Going through give/spend keeps that ledger honest even when the
     * change came from an admin rather than a purchase.
     */
    public Outcome setStatic(Player admin, AdminTarget target, long amount) {
        Outcome blocked = guard(target);
        if (blocked != null) {
            return blocked;
        }
        Profile profile = target.profile();
        long before = Static.balanceOf(profile);
        long wanted = Math.max(0L, amount);
        if (wanted > before) {
            Static.give(profile, wanted - before);
        } else if (wanted < before) {
            Static.spend(profile, before - wanted);
        }
        return finish(admin, target, "static", Static.format(before),
                Static.format(Static.balanceOf(profile)),
                "Static is now " + Static.format(Static.balanceOf(profile)) + ".");
    }

    public Outcome addStatic(Player admin, AdminTarget target, long delta) {
        Profile profile = target.profile();
        return setStatic(admin, target, Static.balanceOf(profile) + delta);
    }

    // ---- the ladder ------------------------------------------------------

    /**
     * Moves somebody up or down the D1–D26 ladder.
     *
     * <p>This is the <em>earned</em> axis — it gates which depths they can enter —
     * so it is the one thing in here that changes what a player can do rather than
     * what they look like. It is free and instant for an admin on purpose; that is
     * what makes testing a depth possible without mining to it.
     */
    public Outcome setRank(Player admin, AdminTarget target, int index) {
        Outcome blocked = guard(target);
        if (blocked != null) {
            return blocked;
        }
        Profile profile = target.profile();
        Rank before = ranks.rankOf(profile);
        Rank after = ranks.setRank(profile, index);
        redrawTag(target);
        return finish(admin, target, "rank", before.display(), after.display(),
                target.name() + " is " + after.display() + " now.");
    }

    // ---- roles -----------------------------------------------------------

    /**
     * Grants or clears a paid rank / staff tag.
     *
     * <p>A null role id clears the category through {@link RoleService#assign}'s
     * sentinel rather than deleting the key — "explicitly nobody" and "never set"
     * have to stay different, or the category default quietly comes back and the
     * admin watches the tag they just removed reappear.
     *
     * <p>The nametag is <b>rebuilt</b>, not recoloured: the scoreboard team name
     * encodes tab sort order, so a role change that only repainted would leave the
     * player sorted where they used to be.
     */
    public Outcome setRole(Player admin, AdminTarget target, String category, String roleId) {
        Outcome blocked = guard(target);
        if (blocked != null) {
            return blocked;
        }
        Profile profile = target.profile();
        RoleDefinition before = roles.roleIn(profile, category);
        if (roleId != null) {
            RoleDefinition role = roles.config().role(roleId);
            if (role == null) {
                return Outcome.refused("There is no role called '" + roleId + "'.");
            }
            if (!role.category().equals(category)) {
                // Assigning across categories would write a donor id into the staff
                // slot, where roleIn() rejects it and silently falls back — a grant
                // that appears to work and does nothing.
                return Outcome.refused(role.name() + " is a " + role.category()
                        + " role, not " + category + ".");
            }
            roles.assign(profile, category, role.id());
        } else {
            roles.assign(profile, category, null);
        }
        Player live = online(target);
        if (live != null) {
            roles.applyPermissions(live, profile);
        }
        redrawTag(target);
        RoleDefinition after = roles.roleIn(profile, category);
        return finish(admin, target, "role." + category,
                before == null ? "none" : before.id(),
                after == null ? "none" : after.id(),
                after == null
                        ? "Cleared their " + category + " role."
                        : target.name() + " is " + after.name() + " (" + category + ").");
    }

    // ---- playtime --------------------------------------------------------

    public Outcome setPlaytime(Player admin, AdminTarget target, long millis) {
        Outcome blocked = guard(target);
        if (blocked != null) {
            return blocked;
        }
        Profile profile = target.profile();
        String before = hours(profile.playtimeMillis());
        profile.setPlaytimeMillis(Math.max(0L, millis));
        return finish(admin, target, "playtime", before, hours(profile.playtimeMillis()),
                "Playtime is now " + hours(profile.playtimeMillis()) + ".");
    }

    // ---- the state bag ---------------------------------------------------

    /**
     * Writes one state key.
     *
     * <p>The type is passed in rather than guessed here — see {@link StateValues},
     * where the reason is spelled out: re-typing an existing key does not throw, it
     * makes every read fall back to the key's default, which for {@code rank.index}
     * means a player quietly back at D1.
     */
    public Outcome setState(Player admin, AdminTarget target, String key, char type, String raw) {
        Outcome blocked = guard(target);
        if (blocked != null) {
            return blocked;
        }
        Profile profile = target.profile();
        String before = profile.state().serialise().get(key);
        String refusal = StateValues.apply(profile.state(), key, type, raw);
        if (refusal != null) {
            return Outcome.refused(refusal);
        }
        profile.markDirty();
        String after = profile.state().serialise().get(key);
        redrawTag(target);
        return finish(admin, target, "state " + key,
                before == null ? "(unset)" : before, after,
                key + " = " + after);
    }

    public Outcome removeState(Player admin, AdminTarget target, String key) {
        Outcome blocked = guard(target);
        if (blocked != null) {
            return blocked;
        }
        Profile profile = target.profile();
        String before = profile.state().serialise().get(key);
        if (before == null) {
            return Outcome.refused("They have no key called '" + key + "'.");
        }
        StateValues.remove(profile.state(), key);
        profile.markDirty();
        redrawTag(target);
        return finish(admin, target, "state " + key, before, "(removed)",
                "Removed " + key + ".");
    }

    /**
     * Empties the whole state bag.
     *
     * <p>This is the most destructive thing {@code /adminset} can do to somebody who
     * is not standing in front of you: rank, roles, Static, cosmetics, onboarding —
     * every one of them lives here rather than in a column, which is Phase 0's rule
     * 3 working exactly as designed and also why this button is armed twice.
     * The old contents go into the audit row, so it is recoverable by hand.
     */
    public Outcome wipeState(Player admin, AdminTarget target) {
        Outcome blocked = guard(target);
        if (blocked != null) {
            return blocked;
        }
        Profile profile = target.profile();
        Map<String, String> before = profile.state().serialise();
        if (before.isEmpty()) {
            return Outcome.refused("Their state bag is already empty.");
        }
        before.keySet().forEach(key -> StateValues.remove(profile.state(), key));
        profile.markDirty();
        redrawTag(target);
        return finish(admin, target, "state wipe", before.toString(), "(empty)",
                "Wiped " + before.size() + " state key(s).");
    }

    // ---- things that need a body ----------------------------------------

    public Outcome clearInventory(Player admin, AdminTarget target) {
        Player live = requireOnline(target);
        if (live == null) {
            return Outcome.refused(target.name() + " has to be online for that.");
        }
        int held = countItems(live.getInventory().getContents());
        live.getInventory().clear();
        live.getInventory().setArmorContents(null);
        live.getInventory().setItemInOffHand(null);
        audit.record(admin, target, "inventory clear", held + " stack(s)", "empty");
        return Outcome.done("Cleared " + held + " stack(s) from " + target.name() + ".");
    }

    public Outcome clearEnderChest(Player admin, AdminTarget target) {
        Player live = requireOnline(target);
        if (live == null) {
            return Outcome.refused(target.name() + " has to be online for that.");
        }
        int held = countItems(live.getEnderChest().getContents());
        live.getEnderChest().clear();
        audit.record(admin, target, "enderchest clear", held + " stack(s)", "empty");
        return Outcome.done("Cleared " + held + " stack(s) from their ender chest.");
    }

    public Outcome clearExperience(Player admin, AdminTarget target) {
        Player live = requireOnline(target);
        if (live == null) {
            return Outcome.refused(target.name() + " has to be online for that.");
        }
        String before = "level " + live.getLevel() + ", "
                + live.getActivePotionEffects().size() + " effect(s)";
        live.setExp(0f);
        live.setLevel(0);
        live.setTotalExperience(0);
        for (PotionEffect effect : List.copyOf(live.getActivePotionEffects())) {
            live.removePotionEffect(effect.getType());
        }
        audit.record(admin, target, "xp and effects reset", before, "level 0, no effects");
        return Outcome.done("Reset their XP and cleared every effect.");
    }

    // ---- shared ----------------------------------------------------------

    /** The one place staleness is checked, so no page can forget to. */
    private Outcome guard(AdminTarget target) {
        if (stale(target)) {
            return Outcome.refused(target.name() + " logged in while this was open — "
                    + "close it and run /adminset " + target.name() + " again.");
        }
        return null;
    }

    private Player requireOnline(AdminTarget target) {
        Player live = online(target);
        return live != null && live.isOnline() ? live : null;
    }

    /**
     * Commits, audits and reports.
     *
     * <p><b>The audit row is written whether or not the commit took.</b> By the time
     * we are here the value has already changed in memory and the owning service has
     * usually queued its own durable write, so "the commit was refused" does not mean
     * "nothing happened" — and an audit trail that only records the clean cases is
     * exactly the trail you cannot use to work out what went wrong. The refusal
     * message says the same thing rather than claiming the edit was discarded.
     */
    private Outcome finish(Player admin, AdminTarget target, String action,
                           String before, String after, String message) {
        boolean committed = offline.commit(target);
        audit.record(admin, target, action, before, after);
        if (!committed) {
            return Outcome.refused(target.name() + " logged in mid-edit — this may not have "
                    + "saved. Run /adminset " + target.name() + " again and check the value.");
        }
        return Outcome.done(message);
    }

    /** Rebuilds the tag above their head, if there is a head. */
    private void redrawTag(AdminTarget target) {
        Player live = online(target);
        if (live != null) {
            nametags.refresh(live);
        }
    }

    private static int countItems(org.bukkit.inventory.ItemStack[] contents) {
        int count = 0;
        for (org.bukkit.inventory.ItemStack stack : contents) {
            if (stack != null && !stack.getType().isAir()) {
                count++;
            }
        }
        return count;
    }

    /** {@code 12h 04m} — the form the info tile and the audit row both use. */
    public static String hours(long millis) {
        long minutes = millis / 60_000L;
        return (minutes / 60) + "h " + String.format("%02d", minutes % 60) + "m";
    }

    /** Every role category, best first, for the roles page. */
    public List<String> categories() {
        return roles.config().categories().stream()
                .filter(category -> !RoleConfig.DEFAULT.equals(category)
                        || !roles.config().inCategory(category).isEmpty())
                .toList();
    }
}
