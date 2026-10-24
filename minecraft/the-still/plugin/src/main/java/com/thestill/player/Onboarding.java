package com.thestill.player;

import com.thestill.state.StateKey;
import com.thestill.descent.DescentService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * A player's first sixty seconds.
 *
 * <p>The Phase 1 gate is that a brand-new player reaches D5 <em>without asking a
 * single question</em>. Almost all of that is won or lost here and in the
 * sidebar: they need a tool, somewhere to stand, and one short sentence telling
 * them what the next action is. Everything else they can discover.
 */
public final class Onboarding {

    /** Set once the starter kit has been handed out, so it cannot be farmed by relogging. */
    public static final StateKey<Boolean> KIT_GIVEN = StateKey.ofBoolean("onboarding.kit", false);

    public static final String TOOL_KIND = "tool";
    public static final String RIG_PICK_ID = "rig_pick";

    private final com.thestill.item.ItemRegistry items;
    private final Profiles profiles;
    private final DescentService descent;

    public Onboarding(com.thestill.item.ItemRegistry items, Profiles profiles,
                      DescentService descent) {
        this.items = items;
        this.profiles = profiles;
        this.descent = descent;
    }

    public boolean isNew(Profile profile) {
        return !profile.state().get(KIT_GIVEN);
    }

    /**
     * Runs once, ever, for a given player.
     *
     * <p>The flag is written durably before anything else. If the server dies
     * mid-welcome the worst case is a player who missed a chat line, not one who
     * can hand out a pickaxe to a friend and relog for another.
     */
    public void welcome(Player player, Profile profile) {
        if (!isNew(profile)) {
            return;
        }
        profile.state().set(KIT_GIVEN, true);
        profiles.saveDurable(profile);

        giveKit(player);

        Location start = descent.landing();
        if (start == null) {
            start = descent.hub();
        }
        if (start != null) {
            player.teleport(start);
        }

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("THE STILL", NamedTextColor.DARK_AQUA, TextDecoration.BOLD));
        player.sendMessage(Component.text("Noise calls. Heat keeps.", NamedTextColor.DARK_GRAY,
                TextDecoration.ITALIC));
        player.sendMessage(Component.empty());
        // YOU START AT THE BOTTOM, NOT ON THE RIG.
        //
        // The rig used to be the lobby: you spawned on a lit deck surrounded by
        // shops and the first instruction was "jump down the hole". That teaches
        // the world in the wrong order — it hands you safety and asks you to leave
        // it, and it means the winch, which is the single most dramatic thing in
        // the design, is something you discover an hour later by accident.
        //
        // Starting on the Landing inverts it. You are in the fog, on the ground,
        // under four columns of light you cannot yet reach, holding a pickaxe. The
        // rig is a place you can SEE and have to EARN, and the way up is the winch
        // — so the first time anyone uses it is in the first five minutes, which is
        // exactly when it should be.
        // THE FIRST INSTRUCTION IS "FOLLOW THE ROAD", and the road does the rest.
        //
        // It runs north for two hundred blocks, past the quarry, to the winch. So the
        // geography teaches itself in the order it matters: this is where you dig,
        // that is where you leave. A player who reads none of this and simply walks
        // toward the next lamp still arrives at both.
        line(player, "1.", "Follow the road north. You can always see the next lamp.");
        line(player, "2.", "The quarry it passes is the ore. Mine it, then /sell.");
        line(player, "3.", "The winch at the end is the way up. It screams. That is the price.");
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  The board on your right always shows what is next.",
                NamedTextColor.DARK_GRAY));
        player.sendMessage(Component.empty());
    }

    private void line(Player player, String number, String text) {
        player.sendMessage(Component.text("  " + number + " ", NamedTextColor.DARK_AQUA)
                .append(Component.text(text, NamedTextColor.GRAY)));
    }

    /**
     * The starting tool.
     *
     * <p>What the Rig Pick is, and why it is unbreakable <em>and</em> deliberately
     * bad, is documented where it is built —
     * {@link com.thestill.item.StillItems#RIG_PICK}. This class used to construct it
     * inline, which made the first sixty seconds of the game the second definition of
     * an item; the vault in {@code /config} now offers the same stack from the same
     * factory, so "what a new player is handed" and "what an admin can hand
     * themselves to test" cannot come apart.
     *
     * <p>Anything that does not fit is dropped at their feet rather than eaten. A kit
     * that silently vanishes is indistinguishable from a kit that was never given, and
     * this one runs exactly once per player, ever.
     */
    public void giveKit(Player player) {
        ItemStack pick = items.build(com.thestill.item.StillItems.RIG_PICK);
        player.getInventory().addItem(pick).values()
                .forEach(left -> player.getWorld()
                        .dropItemNaturally(player.getLocation(), left));
    }
}
