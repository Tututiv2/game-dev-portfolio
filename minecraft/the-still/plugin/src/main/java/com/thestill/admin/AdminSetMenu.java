package com.thestill.admin;

import com.thestill.economy.Money;
import com.thestill.player.Profile;
import com.thestill.rank.Rank;
import com.thestill.role.RoleDefinition;
import com.thestill.role.Static;
import com.thestill.ui.Menu;
import com.thestill.ui.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * {@code /adminset <player>} — one panel for everything about one player.
 *
 * <p>Ported wholesale from Shadow Prison's {@code AdminSetMenu}, which is 2,861
 * lines because it grew a page per feature for four months. This is smaller for two
 * reasons and neither is that it does less: most of what that panel edits is
 * <em>fields on a 961-line profile class</em>, and here almost everything a later
 * phase records is a key in a schema-free bag — so the {@link Page#STATE} page below
 * is not one feature, it is every feature that has not been written yet. Phase 2's
 * Din, Phase 4's heat, Phase 6's PvP flag and Phase 8's Ledger are already editable
 * from it, today, and nobody has to come back here when they land.
 *
 * <h2>Three bugs from the source that are fixed rather than copied</h2>
 * <ol>
 *   <li><b>Slot collisions.</b> Four of its pages wrote two controls into one slot,
 *       so the first one was invisibly deleted — Merchant Marks, the trinket, pet-egg
 *       and relic deposit tiles. {@link Menu#set} now throws on a repeat, which turns
 *       a whole family of "that button does nothing" into a failure you cannot ship.
 *   <li><b>The confirm that did not know who it was confirming.</b> See
 *       {@link ConfirmGate} — over there, arming Clear Inventory on one player and
 *       then opening another fired instantly against the second.
 *   <li><b>A tile is a picture of the past.</b> An open inventory never re-renders,
 *       so a drawn value is only true at the moment it was drawn. Every handler here
 *       starts with {@link #guard()}, which re-checks permission and whether the
 *       target has been overtaken, and redraws rather than acting on a stale screen.
 * </ol>
 *
 * <p>Navigation follows {@code StoreMenu}: the page is a constructor field and going
 * somewhere opens a <em>new</em> instance. Nothing here mutates an open menu into a
 * different one, which is what stops a back button leading somewhere its parent no
 * longer is.
 */
public final class AdminSetMenu extends Menu {

    /** Which screen this instance is. */
    public enum Page {
        HOME, MONEY, STATIC, LADDER, ROLES, ROLE_CATEGORY, STATE, DANGER, INFO
    }

    /** The interior grid, same shape the store uses. Paged, so nothing is ever hidden. */
    private static final int[] GRID = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
    };

    private static final int BACK = 49;

    /** Money steps, in minor units. Three each way is enough to reach any balance fast. */
    private static final long[] MONEY_STEPS = {1_000_00L, 10_000_00L, 100_000_00L};
    private static final long[] STATIC_STEPS = {10L, 100L, 1_000L};

    private final AdminSetContext context;
    private final AdminTarget target;
    private final Page page;
    /** The role category on {@link Page#ROLE_CATEGORY}; unused elsewhere. */
    private final String argument;
    /** The page number on {@link Page#STATE}; unused elsewhere. */
    private final int index;

    public AdminSetMenu(Plugin plugin, Player viewer, AdminSetContext context,
                        AdminTarget target, Page page, String argument, int index) {
        super(plugin, viewer);
        this.context = context;
        this.target = target;
        this.page = page;
        this.argument = argument;
        this.index = index;
    }

    public static AdminSetMenu home(Plugin plugin, Player viewer, AdminSetContext context,
                                    AdminTarget target) {
        return new AdminSetMenu(plugin, viewer, context, target, Page.HOME, null, 0);
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        String what = switch (page) {
            case HOME -> "Admin";
            case MONEY -> "Money";
            case STATIC -> "Static";
            case LADDER -> "Ladder";
            case ROLES -> "Roles";
            case ROLE_CATEGORY -> argument == null ? "Roles" : argument;
            case STATE -> "State";
            case DANGER -> "Destructive";
            case INFO -> "Info";
        };
        return Component.text("⚙ " + target.name() + " · " + what,
                page == Page.DANGER ? NamedTextColor.RED : NamedTextColor.AQUA);
    }

    private AdminActions actions() {
        return context.actions();
    }

    private Profile profile() {
        return target.profile();
    }

    @Override
    protected void build() {
        fillBorder(MenuItems.of(page == Page.DANGER
                        ? Material.RED_STAINED_GLASS_PANE
                        : Material.BLACK_STAINED_GLASS_PANE,
                Component.empty(), List.of()));

        switch (page) {
            case HOME -> buildHome();
            case MONEY -> buildMoney();
            case STATIC -> buildStatic();
            case LADDER -> buildLadder();
            case ROLES -> buildRoles();
            case ROLE_CATEGORY -> buildRoleCategory();
            case STATE -> buildState();
            case DANGER -> buildDanger();
            case INFO -> buildInfo();
        }

        if (page == Page.HOME) {
            button(BACK, Material.BARRIER, Component.text("Close", NamedTextColor.DARK_GRAY),
                    List.of(), click -> closeLater());
        } else {
            button(BACK, Material.ARROW, Component.text("Back", NamedTextColor.YELLOW),
                    List.of(grey(page == Page.ROLE_CATEGORY ? "To the categories." : "To the panel.")),
                    click -> go(page == Page.ROLE_CATEGORY ? Page.ROLES : Page.HOME));
        }
    }

    // ---- home ------------------------------------------------------------

    private void buildHome() {
        set(4, head());

        door(19, Material.EMERALD, "Money", NamedTextColor.GREEN, Page.MONEY,
                Money.format(profile().balance()),
                "Set it, or move it by a step.");
        door(21, Material.AMETHYST_SHARD, "Static", NamedTextColor.LIGHT_PURPLE, Page.STATIC,
                Static.format(Static.balanceOf(profile())),
                "The currency bought with real money.",
                "Credit it here after a purchase.");
        door(23, Material.DIAMOND_PICKAXE, "The ladder", NamedTextColor.AQUA, Page.LADDER,
                actions().ranks().rankOf(profile()).display(),
                "Which depths they may enter.",
                "Earned, not bought — this is the real one.");
        door(25, Material.NETHER_STAR, "Roles", NamedTextColor.GOLD, Page.ROLES,
                roleSummary(),
                "The ranks people pay for, and staff tags.",
                "Cosmetic, forever.");

        door(28, Material.LECTERN, "State", NamedTextColor.YELLOW, Page.STATE,
                profile().state().size() + " key(s)",
                "Every value this player carries that is",
                "not a column — rank, roles, cosmetics,",
                "and everything a later phase adds.");
        door(30, Material.CLOCK, "Playtime", NamedTextColor.WHITE, Page.INFO,
                AdminActions.hours(profile().playtimeMillis()),
                "Read it here; edit it on the Info page.");
        door(32, Material.BOOK, "Info", NamedTextColor.GRAY, Page.INFO,
                target.live() ? "online" : "offline",
                "Identity, first seen, playtime.");
        door(34, Material.TNT, "Destructive", NamedTextColor.RED, Page.DANGER,
                "inventory · ender chest · XP · state",
                "Everything that needs confirming twice.");

        button(40, Material.WRITTEN_BOOK,
                Component.text("Recent changes", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(grey("Every admin edit made to " + target.name() + ","),
                        grey("newest first, with the old value."),
                        Component.empty(),
                        yellow("  Click — it prints to chat")),
                click -> showAudit());
    }

    /**
     * The head tile, and the one place a stale panel announces itself.
     *
     * <p>An offline edit is a detached copy of somebody's profile. If they join while
     * this is open, everything on screen became a photograph — {@link #guard()} stops
     * the buttons working, and this says why before anybody clicks one.
     */
    private ItemStack head() {
        List<Component> lore = new ArrayList<>();
        if (actions().stale(target)) {
            lore.add(Component.text("  ⚠ THEY JUST LOGGED IN", NamedTextColor.RED,
                    TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            lore.add(grey("This panel is a copy of their old data."));
            lore.add(grey("Close it and run /adminset " + target.name() + " again."));
            lore.add(Component.empty());
        } else {
            lore.add(target.live()
                    ? Component.text("  ONLINE", NamedTextColor.GREEN)
                            .decoration(TextDecoration.ITALIC, false)
                    : Component.text("  OFFLINE — editing their stored record",
                            NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
        }
        lore.add(field("Balance", Money.format(profile().balance())));
        lore.add(field("Static", Static.format(Static.balanceOf(profile()))));
        lore.add(field("Ladder", actions().ranks().rankOf(profile()).display()));
        lore.add(field("Roles", roleSummary()));
        lore.add(field("Playtime", AdminActions.hours(profile().playtimeMillis())));
        lore.add(field("State", profile().state().size() + " key(s)"));

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(target.uuid()));
            meta.displayName(Component.text(target.name(), NamedTextColor.AQUA,
                    TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
        });
        return head;
    }

    private String roleSummary() {
        List<String> held = new ArrayList<>();
        for (String category : actions().categories()) {
            RoleDefinition role = actions().roles().roleIn(profile(), category);
            if (role != null) {
                held.add(role.name());
            }
        }
        return held.isEmpty() ? "none" : String.join(", ", held);
    }

    // ---- money and Static ------------------------------------------------

    private void buildMoney() {
        header(Material.EMERALD, "Money", Money.format(profile().balance()),
                "Integer minor units all the way down —",
                "there is no way to type a fractional cent in.");

        for (int i = 0; i < MONEY_STEPS.length; i++) {
            long step = MONEY_STEPS[MONEY_STEPS.length - 1 - i];
            step(19 + i, Material.RED_DYE, "− " + Money.format(step), NamedTextColor.RED,
                    click -> apply(actions().addMoney(viewer, target, -step)));
        }
        set(22, MenuItems.of(Material.GOLD_INGOT,
                Component.text(Money.format(profile().balance()), NamedTextColor.GOLD,
                        TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false),
                List.of(grey("What they are holding right now."))));
        for (int i = 0; i < MONEY_STEPS.length; i++) {
            long step = MONEY_STEPS[i];
            step(23 + i, Material.LIME_DYE, "+ " + Money.format(step), NamedTextColor.GREEN,
                    click -> apply(actions().addMoney(viewer, target, step)));
        }

        button(30, Material.NAME_TAG,
                Component.text("Set an exact amount", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(grey("Click, then type it in chat."),
                        grey("Understands 2500, 12.50, 250k, 1.4m.")),
                click -> promptFor("a balance for " + target.name(),
                        text -> {
                            Long minor = Money.parse(text);
                            if (minor == null) {
                                deny("'" + text + "' is not an amount.");
                                return;
                            }
                            apply(actions().setMoney(viewer, target, minor));
                        }));

        armed(32, Material.BARRIER, "Take everything", "money.zero",
                "Sets their balance to " + Money.format(0) + ".",
                click -> apply(actions().setMoney(viewer, target, 0L)));
    }

    private void buildStatic() {
        header(Material.AMETHYST_SHARD, "Static",
                Static.format(Static.balanceOf(profile())),
                "Bought on the web, credited here.",
                "Buys tags and cosmetics and nothing else —",
                "see Perks, which refuses the rest in code.");

        for (int i = 0; i < STATIC_STEPS.length; i++) {
            long step = STATIC_STEPS[STATIC_STEPS.length - 1 - i];
            step(19 + i, Material.RED_DYE, "− " + Static.format(step), NamedTextColor.RED,
                    click -> apply(actions().addStatic(viewer, target, -step)));
        }
        set(22, MenuItems.of(Material.AMETHYST_CLUSTER,
                Component.text(Static.format(Static.balanceOf(profile())),
                                NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false),
                List.of(grey("Lifetime: "
                        + Static.format(profile().state().get(Static.LIFETIME))),
                        grey("Lifetime only ever goes up — it is what"),
                        grey("a refund question is answered from."))));
        for (int i = 0; i < STATIC_STEPS.length; i++) {
            long step = STATIC_STEPS[i];
            step(23 + i, Material.LIME_DYE, "+ " + Static.format(step), NamedTextColor.GREEN,
                    click -> apply(actions().addStatic(viewer, target, step)));
        }

        button(30, Material.NAME_TAG,
                Component.text("Set an exact amount", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(grey("Click, then type a whole number.")),
                click -> promptFor("a Static balance for " + target.name(),
                        text -> {
                            try {
                                apply(actions().setStatic(viewer, target,
                                        Long.parseLong(text.trim())));
                            } catch (NumberFormatException e) {
                                deny("'" + text + "' is not a whole number.");
                            }
                        }));

        armed(32, Material.BARRIER, "Take all Static", "static.zero",
                "Sets their Static to zero. Lifetime is untouched.",
                click -> apply(actions().setStatic(viewer, target, 0L)));
    }

    // ---- the ladder ------------------------------------------------------

    private void buildLadder() {
        Rank current = actions().ranks().rankOf(profile());
        Rank next = actions().ladder().next(current);
        header(Material.DIAMOND_PICKAXE, "The ladder", current.display(),
                "A rung IS a depth: " + current.display() + " unlocks "
                        + current.display() + ".",
                next == null ? "They are at the top." : "Next rung costs "
                        + Money.format(actions().ranks().costOf(next)) + " at the live scale.",
                "This is the earned axis. Roles are the bought one.");

        step(19, Material.RED_DYE, "− 5 rungs", NamedTextColor.RED,
                click -> apply(actions().setRank(viewer, target, current.index() - 5)));
        step(20, Material.PINK_DYE, "− 1 rung", NamedTextColor.RED,
                click -> apply(actions().setRank(viewer, target, current.index() - 1)));
        set(22, MenuItems.of(Material.SCULK,
                Component.text(current.display(), current.colour(), TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false),
                List.of(grey("Rung " + (current.index() + 1) + " of "
                        + actions().ladder().size()))));
        step(24, Material.LIME_DYE, "+ 1 rung", NamedTextColor.GREEN,
                click -> apply(actions().setRank(viewer, target, current.index() + 1)));
        step(25, Material.GREEN_DYE, "+ 5 rungs", NamedTextColor.GREEN,
                click -> apply(actions().setRank(viewer, target, current.index() + 5)));

        button(29, Material.IRON_PICKAXE,
                Component.text("Back to the start", NamedTextColor.GRAY, TextDecoration.BOLD),
                List.of(grey("Sets them to " + actions().ladder().starting().display() + ".")),
                click -> apply(actions().setRank(viewer, target, 0)));
        button(31, Material.NAME_TAG,
                Component.text("Set an exact rung", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(grey("Click, then type D7 — or just 7."),
                        grey("A number is the rung's position, so 1 is"),
                        grey("the bottom of the ladder whatever it is called.")),
                click -> promptFor("a rung for " + target.name(), this::applyRung));
        button(33, Material.NETHERITE_PICKAXE,
                Component.text("Straight to the top", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(grey("Sets them to " + actions().ladder().highest().display() + ".")),
                click -> apply(actions().setRank(viewer, target,
                        actions().ladder().size() - 1)));
    }

    /**
     * Reads a rung the way an admin would say it.
     *
     * <p>{@code d7} first, because that is the id and it survives somebody renaming
     * the ladder in {@code ranks.yml}. A bare number is treated as a <em>position</em>
     * — 1 is the bottom rung — rather than as an index, because "set them to 7" and
     * "set them to index 6" are the same sentence to everybody except a programmer.
     */
    private void applyRung(String text) {
        String raw = text.trim();
        Rank byId = actions().ladder().byId(raw);
        if (byId != null) {
            apply(actions().setRank(viewer, target, byId.index()));
            return;
        }
        try {
            int position = Integer.parseInt(raw.replaceFirst("(?i)^d", ""));
            if (position < 1 || position > actions().ladder().size()) {
                deny("The ladder has rungs 1 to " + actions().ladder().size() + ".");
                return;
            }
            apply(actions().setRank(viewer, target, position - 1));
        } catch (NumberFormatException e) {
            deny("'" + raw + "' is not a rung.");
        }
    }

    // ---- roles -----------------------------------------------------------

    private void buildRoles() {
        header(Material.NETHER_STAR, "Roles", roleSummary(),
                "One role per category. These are cosmetic —",
                "nothing here touches ore, money or the ladder,",
                "and Perks refuses any row that tries.");

        List<String> categories = actions().categories();
        for (int i = 0; i < categories.size() && i < GRID.length; i++) {
            String category = categories.get(i);
            RoleDefinition held = actions().roles().roleIn(profile(), category);
            int count = actions().roles().config().inCategory(category).size();
            set(GRID[i], MenuItems.of(held == null ? Material.GRAY_DYE : Material.LIME_DYE,
                            Component.text(category, held == null ? NamedTextColor.GRAY
                                            : NamedTextColor.GREEN, TextDecoration.BOLD)
                                    .decoration(TextDecoration.ITALIC, false),
                            List.of(field("Holding", held == null ? "nobody" : held.name()),
                                    grey(count + " role(s) in this category"),
                                    Component.empty(),
                                    yellow("  Click to open"))),
                    click -> goCategory(category));
        }
    }

    private void buildRoleCategory() {
        String category = argument == null ? "" : argument;
        RoleDefinition held = actions().roles().roleIn(profile(), category);
        header(Material.NETHER_STAR, category,
                held == null ? "nobody" : held.name(),
                "Click a role to assign it.",
                "The tag above their head rebuilds immediately.");

        button(10, Material.BARRIER,
                Component.text("Clear this category", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(grey("Explicitly nobody, which is not the same"),
                        grey("as never set — the default cannot come back.")),
                click -> apply(actions().setRole(viewer, target, category, null)));

        List<RoleDefinition> roles = actions().roles().config().inCategory(category);
        for (int i = 0; i < roles.size() && i < GRID.length; i++) {
            RoleDefinition role = roles.get(i);
            boolean current = held != null && held.id().equals(role.id());
            set(GRID[i], MenuItems.of(current ? Material.LIME_DYE : Material.GRAY_DYE,
                            actions().roles().tag(role),
                            List.of(field("id", role.id()),
                                    field("weight", String.valueOf(role.weight())),
                                    field("perks", role.perks().isEmpty()
                                            ? "none" : String.join(", ", role.perks())),
                                    Component.empty(),
                                    current ? Component.text("  ✔ they have this",
                                                    NamedTextColor.GREEN)
                                            .decoration(TextDecoration.ITALIC, false)
                                            : yellow("  Click to assign"))),
                    click -> apply(actions().setRole(viewer, target, category, role.id())));
        }
    }

    // ---- the generic state editor ---------------------------------------

    /**
     * Every key on this player, whatever it is.
     *
     * <p>This is the page that makes {@code /adminset} outlive the features that
     * exist today. {@code StateMap.serialise()} enumerates itself in a
     * self-describing form, so listing a key nobody has written the UI for is free —
     * and it is the same idea {@code /still region state} already applies to the
     * other side of the same storage.
     */
    private void buildState() {
        Map<String, String> all = profile().state().serialise();
        List<String> keys = new ArrayList<>(all.keySet());
        keys.sort(String::compareTo);

        int pages = Math.max(1, (keys.size() + GRID.length - 1) / GRID.length);
        int current = Math.clamp(index, 0, pages - 1);

        header(Material.LECTERN, "State", keys.size() + " key(s)",
                "Left-click a key to change it.",
                "Shift-right-click twice to delete it.",
                "A key keeps the type it already has.");

        button(10, Material.WRITABLE_BOOK,
                Component.text("Add a key", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(grey("Type: <key> <value>"),
                        grey("The type is guessed from the value, so a"),
                        grey("new key that should be a decimal wants 0.0.")),
                click -> promptFor("a new key, as: name value", this::applyNewKey));

        if (pages > 1) {
            int previous = (current - 1 + pages) % pages;
            int following = (current + 1) % pages;
            button(12, Material.ARROW, Component.text("Previous", NamedTextColor.YELLOW),
                    List.of(grey("Page " + (previous + 1) + " of " + pages)),
                    click -> goState(previous));
            set(14, MenuItems.of(Material.PAPER, Component.text("Page " + (current + 1)
                    + " of " + pages, NamedTextColor.WHITE)));
            button(16, Material.ARROW, Component.text("Next", NamedTextColor.YELLOW),
                    List.of(grey("Page " + (following + 1) + " of " + pages)),
                    click -> goState(following));
        }

        int start = current * GRID.length;
        for (int i = 0; i < GRID.length && start + i < keys.size(); i++) {
            String key = keys.get(start + i);
            String encoded = all.get(key);
            set(GRID[i], stateTile(key, encoded), click -> {
                if (click.isShift() && click.isRight()) {
                    confirm("state.delete:" + key,
                            "Delete " + key + "?",
                            () -> apply(actions().removeState(viewer, target, key)));
                    return;
                }
                promptForStateValue(key, encoded);
            });
        }
    }

    private ItemStack stateTile(String key, String encoded) {
        char type = StateValues.typeOf(encoded);
        String body = StateValues.bodyOf(encoded);
        Material icon = switch (type) {
            case StateValues.BOOLEAN -> "true".equals(body) ? Material.LIME_DYE : Material.GRAY_DYE;
            case StateValues.DOUBLE -> Material.COMPARATOR;
            case StateValues.LONG, StateValues.INT -> Material.REPEATER;
            case StateValues.STRING -> Material.NAME_TAG;
            default -> Material.BARRIER;
        };
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("  " + body, NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(field("type", StateValues.describe(type)));
        lore.add(Component.empty());
        if (type == StateValues.UNKNOWN) {
            lore.add(Component.text("  This build cannot read that value.",
                    NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            lore.add(grey("It can still be deleted."));
        } else {
            lore.add(yellow("  Left-click: type a new value"));
        }
        lore.add(Component.text("  Shift-right-click twice: delete", NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        return MenuItems.of(icon, Component.text(key, NamedTextColor.YELLOW, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false), lore);
    }

    private void promptForStateValue(String key, String encoded) {
        char type = StateValues.typeOf(encoded);
        if (type == StateValues.UNKNOWN) {
            deny("This build cannot write that type. Delete it instead.");
            return;
        }
        promptFor("a new " + StateValues.describe(type) + " for " + key,
                // The TYPE COMES FROM THE STORED VALUE, never from what was typed.
                // Re-inferring it here is the silent-reset bug in StateValues' header:
                // rank.index written back as a Long reads as rank 0 forever.
                text -> apply(actions().setState(viewer, target, key, type, text)));
    }

    private void applyNewKey(String text) {
        String[] parts = text.trim().split("\\s+", 2);
        if (parts.length < 2) {
            deny("Type the key, a space, then the value.");
            return;
        }
        char type = StateValues.infer(parts[1]);
        apply(actions().setState(viewer, target, parts[0], type, parts[1]));
    }

    // ---- destructive -----------------------------------------------------

    private void buildDanger() {
        header(Material.TNT, "Destructive", target.live() ? "they are online" : "OFFLINE",
                "Every button here arms on the first click",
                "and fires on the second, within 8 seconds.",
                "An arm belongs to one player and one button.");

        armed(19, Material.CHEST, "Clear inventory", "inventory.clear",
                "Main inventory, armour and offhand."
                        + (target.live() ? "" : "  Needs them online."),
                click -> apply(actions().clearInventory(viewer, target)));
        armed(21, Material.ENDER_CHEST, "Clear ender chest", "enderchest.clear",
                "Empties all of it."
                        + (target.live() ? "" : "  Needs them online."),
                click -> apply(actions().clearEnderChest(viewer, target)));
        armed(23, Material.EXPERIENCE_BOTTLE, "Reset XP and effects", "xp.clear",
                "Levels to zero, every potion effect gone."
                        + (target.live() ? "" : "  Needs them online."),
                click -> apply(actions().clearExperience(viewer, target)));

        armed(31, Material.LAVA_BUCKET, "Wipe every state key", "state.wipe",
                "Rank, roles, Static, cosmetics, onboarding — "
                        + "all of it lives in the state bag. The old contents go "
                        + "into the audit trail.",
                click -> apply(actions().wipeState(viewer, target)));
    }

    // ---- info ------------------------------------------------------------

    private void buildInfo() {
        set(4, head());

        set(19, MenuItems.of(Material.PLAYER_HEAD,
                Component.text("Identity", NamedTextColor.AQUA, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false),
                List.of(field("uuid", target.uuid().toString()),
                        field("name", target.name()),
                        field("online", target.live() ? "yes" : "no"))));

        set(21, MenuItems.of(Material.CLOCK,
                Component.text("Seen", NamedTextColor.WHITE, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false),
                List.of(field("first", stamp(profile().firstSeen())),
                        field("last", stamp(profile().lastSeen())),
                        field("playtime", AdminActions.hours(profile().playtimeMillis())))));

        button(23, Material.NAME_TAG,
                Component.text("Set playtime", NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(grey("Click, then type a number of hours."),
                        grey("On somebody online this sets the STORED"),
                        grey("total; their live session still counts on top.")),
                click -> promptFor("a playtime in hours for " + target.name(), text -> {
                    try {
                        double asHours = Double.parseDouble(text.trim());
                        if (!Double.isFinite(asHours) || asHours < 0) {
                            deny("Hours have to be a positive number.");
                            return;
                        }
                        apply(actions().setPlaytime(viewer, target,
                                Math.round(asHours * 3_600_000d)));
                    } catch (NumberFormatException e) {
                        deny("'" + text + "' is not a number of hours.");
                    }
                }));

        set(25, MenuItems.of(Material.LECTERN,
                Component.text("Stored", NamedTextColor.YELLOW, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false),
                List.of(field("balance", Money.format(profile().balance())),
                        field("static", Static.format(Static.balanceOf(profile()))),
                        field("ladder", actions().ranks().rankOf(profile()).display()),
                        field("state keys", String.valueOf(profile().state().size())))));
    }

    private static String stamp(long millis) {
        return millis <= 0 ? "unknown" : new Date(millis).toString();
    }

    // ---- tiles -----------------------------------------------------------

    private void header(Material icon, String name, String value, String... lines) {
        List<Component> lore = new ArrayList<>();
        lore.add(field("Now", value));
        lore.add(Component.empty());
        for (String line : lines) {
            if (line != null) {
                lore.add(grey(line));
            }
        }
        if (actions().stale(target)) {
            lore.add(Component.empty());
            lore.add(Component.text("  ⚠ they logged in — reopen the panel",
                    NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        }
        set(4, MenuItems.of(icon, Component.text(name, NamedTextColor.AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false), lore));
    }

    private void door(int slot, Material icon, String name, NamedTextColor colour,
                      Page destination, String value, String... lines) {
        List<Component> lore = new ArrayList<>();
        lore.add(field("Now", value));
        lore.add(Component.empty());
        for (String line : lines) {
            lore.add(grey(line));
        }
        lore.add(Component.empty());
        lore.add(yellow("  Click to open"));
        set(slot, MenuItems.of(icon, Component.text(name, colour, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false), lore), click -> go(destination));
    }

    private void step(int slot, Material icon, String name, NamedTextColor colour,
                      java.util.function.Consumer<Click> onClick) {
        button(slot, icon, Component.text(name, colour, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false),
                List.of(grey("Applies immediately, and is logged.")), onClick);
    }

    /**
     * A button that has to be clicked twice.
     *
     * <p>The lore says how long is left on <em>this</em> button's arm — and it only
     * ever says it on the one that is armed, because the arm is bound to the button
     * and the player, not to the admin alone. That is the whole fix from the source.
     */
    private void armed(int slot, Material icon, String name, String action,
                       String what, java.util.function.Consumer<Click> onClick) {
        long left = context.gate().remaining(viewer.getUniqueId(), target.uuid(), action,
                System.currentTimeMillis());
        List<Component> lore = new ArrayList<>();
        lore.add(grey(what));
        lore.add(Component.empty());
        lore.add(left > 0
                ? Component.text("  ARMED — click again within " + (left / 1000 + 1) + "s",
                        NamedTextColor.RED, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false)
                : yellow("  Click once to arm"));
        set(slot, MenuItems.of(left > 0 ? Material.REDSTONE_TORCH : icon,
                Component.text(name, NamedTextColor.RED, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false), lore),
                click -> confirm(action, name, () -> onClick.accept(click)));
    }

    // ---- clicks ----------------------------------------------------------

    /**
     * The first line of every handler.
     *
     * <p>Permission is re-read here rather than trusted from when the panel opened:
     * an inventory stays on screen after the admin's role is revoked, and a menu is
     * an ability, not a picture. Staleness is re-read for the same reason one layer
     * down — see {@link AdminActions#stale}.
     */
    private boolean guard() {
        if (!viewer.hasPermission(AdminSetCommand.PERMISSION)) {
            deny("You no longer have permission for that.");
            closeLater();
            return false;
        }
        if (actions().stale(target)) {
            deny(target.name() + " logged in while this was open. Reopen the panel.");
            refresh();
            return false;
        }
        return true;
    }

    private void go(Page destination) {
        if (!guard()) {
            return;
        }
        // Leaving a page drops anything armed on it. An arm that survives navigation
        // is an arm somebody has forgotten about.
        context.gate().disarm(viewer.getUniqueId());
        openLater(new AdminSetMenu(plugin, viewer, context, target, destination, null, 0));
    }

    private void goCategory(String category) {
        if (!guard()) {
            return;
        }
        openLater(new AdminSetMenu(plugin, viewer, context, target,
                Page.ROLE_CATEGORY, category, 0));
    }

    private void goState(int wanted) {
        if (!guard()) {
            return;
        }
        openLater(new AdminSetMenu(plugin, viewer, context, target, Page.STATE, null, wanted));
    }

    /** Arm on the first press, run on the second. */
    private void confirm(String action, String what, Runnable onFire) {
        if (!guard()) {
            return;
        }
        ConfirmGate.Result result = context.gate().press(viewer.getUniqueId(), target.uuid(),
                action, System.currentTimeMillis());
        if (result == ConfirmGate.Result.FIRED) {
            onFire.run();
            return;
        }
        viewer.sendMessage(Component.text("  " + what + " — click again within "
                        + (ConfirmGate.WINDOW_MILLIS / 1000) + "s to confirm.",
                NamedTextColor.GOLD));
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 0.7f);
        refresh();
    }

    private void apply(AdminActions.Outcome outcome) {
        if (!outcome.ok()) {
            deny(outcome.message());
            refresh();
            return;
        }
        viewer.sendMessage(Component.text("  " + outcome.message(), NamedTextColor.AQUA));
        viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
        refresh();
    }

    /**
     * Asks for a line of chat.
     *
     * <p>Closes first, then arms — {@link com.thestill.cosmetic.NickPrompt} explains
     * why that order is not negotiable. The callback re-opens the same page, so a
     * value typed in chat lands back where it was asked for.
     */
    private void promptFor(String what, java.util.function.Consumer<String> onText) {
        if (!guard()) {
            return;
        }
        closeLater();
        viewer.sendMessage(Component.text("  Type " + what + ".", NamedTextColor.YELLOW));
        context.prompt().arm(viewer, text -> {
            if (!viewer.hasPermission(AdminSetCommand.PERMISSION)) {
                return;
            }
            onText.accept(text);
            openLater(new AdminSetMenu(plugin, viewer, context, target, page, argument, index));
        });
    }

    /**
     * Prints the audit trail to chat.
     *
     * <p>Chat rather than a page, because the read is async — {@code Database.read}
     * throws on the main thread on purpose — and a menu that has to wait for SQL
     * before it can draw is a menu that opens empty and fills in later.
     */
    private void showAudit() {
        if (!guard()) {
            return;
        }
        closeLater();
        context.audit().recent(target.uuid(), 20).thenAccept(entries ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline()) {
                        return;
                    }
                    viewer.sendMessage(Component.empty());
                    viewer.sendMessage(Component.text("  Admin changes to " + target.name(),
                            NamedTextColor.AQUA, TextDecoration.BOLD));
                    if (entries.isEmpty()) {
                        viewer.sendMessage(grey("  Nothing on record."));
                        return;
                    }
                    for (AdminAudit.Entry entry : entries) {
                        viewer.sendMessage(Component.text("  " + stamp(entry.at()) + "  ",
                                        NamedTextColor.DARK_GRAY)
                                .append(Component.text(entry.adminName(), NamedTextColor.WHITE))
                                .append(Component.text("  " + entry.action() + "  ",
                                        NamedTextColor.YELLOW))
                                .append(Component.text((entry.before() == null ? "-" : entry.before())
                                                + " → " + (entry.after() == null ? "-" : entry.after()),
                                        NamedTextColor.GRAY)));
                    }
                }));
    }

    private void deny(String why) {
        viewer.sendMessage(Component.text("  " + why, NamedTextColor.DARK_GRAY,
                TextDecoration.ITALIC));
        viewer.playSound(viewer.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.2f);
    }

    // ---- text ------------------------------------------------------------

    private static Component grey(String text) {
        return Component.text("  " + text, NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component yellow(String text) {
        return Component.text(text, NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component field(String label, String value) {
        return Component.text("  " + label + ": ", NamedTextColor.DARK_GRAY)
                .append(Component.text(value, NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false);
    }
}
