package com.thestill.role;

import com.thestill.config.Dial;
import com.thestill.player.Profile;
import com.thestill.player.Profiles;
import com.thestill.ui.Gradients;
import com.thestill.ui.Menu;
import com.thestill.ui.MenuItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /store} — a hub of categories, each opening its own page.
 *
 * <p>Laid out from Shadow Prison's {@code StoreMenu}, which is five pages hanging off
 * one home screen: buy the currency with real money, then spend it on cases, ranks,
 * rank upgrades and keys. The first attempt here was one flat page of ranks with grey
 * bullet points, and the owner's verdict on it was correct.
 *
 * <p><b>What makes a tile read well</b> is not the words. It is that the perk list is
 * ordered by desirability and colour-graded — pink headline, blue, gold, green tail —
 * so the eye lands on the reason to buy first. See {@link Perks#DISPLAY_ORDER}.
 *
 * <p><b>Real money touches this server exactly once, as a URL.</b> No payment API, no
 * webhook, no callback, and therefore no refund path to get wrong. You buy Static on
 * the web; an admin credits it. Everything past that point is in-game currency.
 */
public final class StoreMenu extends Menu {

    /** Which screen this instance is. Navigation opens a new instance, never mutates. */
    public enum Page { HOME, CURRENCY, CASES, RANKS, UPGRADES, KEYS }

    /** The grid inside the border. Paged, so no tier is ever hidden. */
    private static final int[] GRID = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43,
    };

    /** Real-money packs. Price in whole USD cents so nothing is a float. */
    private record Pack(String id, long amount, int cents) {
    }

    private static final List<Pack> PACKS = List.of(
            new Pack("static-250", 250, 499),
            new Pack("static-600", 600, 999),
            new Pack("static-1400", 1400, 1999),
            new Pack("static-3200", 3200, 3999),
            new Pack("static-9000", 9000, 9999));

    private static final TextColor STATIC_COLOUR = TextColor.color(0xB48CFF);

    private final RoleService roles;
    private final Profiles profiles;
    private final Profile profile;
    private final Dial<String> storeUrl;
    private final Page page;

    public StoreMenu(Plugin plugin, Player viewer, RoleService roles, Profiles profiles,
                     Profile profile, Dial<String> storeUrl, Page page) {
        super(plugin, viewer);
        this.roles = roles;
        this.profiles = profiles;
        this.profile = profile;
        this.storeUrl = storeUrl;
        this.page = page;
    }

    @Override
    protected int rows() {
        return 6;
    }

    @Override
    protected Component title() {
        return switch (page) {
            case HOME -> heading("The Company Store");
            case CURRENCY -> heading("Static");
            case CASES -> heading("Dredge Cases");
            case RANKS -> heading("Ranks");
            case UPGRADES -> heading("Rank Upgrades");
            case KEYS -> heading("Foreman's Keys");
        };
    }

    private static Component heading(String text) {
        return Gradients.bold(text, com.thestill.ui.sidebar.StillLines.EMBER,
                com.thestill.ui.sidebar.StillLines.EMBER_PALE);
    }

    @Override
    protected void build() {
        fillBorder(MenuItems.of(page == Page.HOME
                        ? Material.GRAY_STAINED_GLASS_PANE
                        : Material.BLACK_STAINED_GLASS_PANE,
                Component.empty(), List.of()));

        switch (page) {
            case HOME -> buildHome();
            case CURRENCY -> buildCurrency();
            case CASES -> buildCases();
            case RANKS -> buildRanks();
            case UPGRADES -> buildUpgrades();
            case KEYS -> buildKeys();
        }

        if (page != Page.HOME) {
            button(49, Material.ARROW, Component.text("Back", NamedTextColor.YELLOW),
                    List.of(grey("To the store.")), click -> go(Page.HOME));
        } else {
            button(49, Material.BARRIER, Component.text("Close", NamedTextColor.DARK_GRAY),
                    List.of(), click -> closeLater());
        }
    }

    private void go(Page next) {
        openLater(new StoreMenu(plugin, viewer, roles, profiles, profile, storeUrl, next));
    }

    // ---- home ------------------------------------------------------------

    private void buildHome() {
        set(4, MenuItems.of(Material.AMETHYST_SHARD,
                Gradients.bold("  ❖ THE COMPANY STORE ❖  ",
                        com.thestill.ui.sidebar.StillLines.EMBER,
                        com.thestill.ui.sidebar.StillLines.EMBER_PALE),
                List.of(Component.text("Balance: ", NamedTextColor.GRAY)
                                .append(Component.text(Static.format(Static.balanceOf(profile)),
                                        STATIC_COLOUR)).decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        grey("Everything here is cosmetic."),
                        grey("Nothing sold makes the dark any kinder."))));

        category(20, Material.AMETHYST_CLUSTER, "Static", STATIC_COLOUR, Page.CURRENCY,
                "The company's scrip.", "Bought with real money.",
                "Spent on everything else here.");

        category(22, Material.DECORATED_POT, "Dredge Cases", TextColor.color(0xFF6FD8), Page.CASES,
                "Something came up with the rock.", "Cosmetics, serialised.",
                "A rare one carries a number nobody else has.");

        category(24, Material.NETHER_STAR, "Ranks", TextColor.color(0xFFD86F), Page.RANKS,
                "Member through The Still.", "A tag, and what it unlocks.",
                "Cosmetic. Forever.");

        category(31, Material.TRIAL_KEY, "Foreman's Keys", TextColor.color(0x8FE08F), Page.KEYS,
                "Opens what the company locked.", "One key, one case.");

        category(33, Material.SMITHING_TABLE, "Rank Upgrades", TextColor.color(0x6FD8FF),
                Page.UPGRADES, "Already carrying a tag?", "Pay the difference, not the whole.");
    }

    private void category(int slot, Material icon, String name, TextColor colour, Page target,
                          String... lines) {
        List<Component> lore = new ArrayList<>();
        for (String line : lines) {
            lore.add(grey(line));
        }
        lore.add(Component.empty());
        lore.add(Component.text("  Click to open", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        set(slot, MenuItems.of(icon, Component.text(name, colour, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false), lore), click -> go(target));
    }

    // ---- currency --------------------------------------------------------

    private void buildCurrency() {
        header(Material.AMETHYST_SHARD, "Static",
                "The only thing on this server bought with money.",
                "Real-money checkout. An admin credits it after payment.");

        int[] slots = {20, 21, 22, 23, 24};
        for (int i = 0; i < PACKS.size() && i < slots.length; i++) {
            Pack pack = PACKS.get(i);
            set(slots[i], MenuItems.of(Material.AMETHYST_SHARD,
                    Component.text(String.format("%,d Static", pack.amount()), STATIC_COLOUR,
                            TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false),
                    List.of(line(STATIC_COLOUR, "Receive " + String.format("%,d", pack.amount())),
                            Component.text("  Price: ", NamedTextColor.GRAY)
                                    .append(Component.text(usd(pack.cents()), NamedTextColor.GOLD))
                                    .decoration(TextDecoration.ITALIC, false),
                            Component.empty(),
                            grey("Real money purchase."),
                            Component.text("  Click for the checkout link", NamedTextColor.YELLOW)
                                    .decoration(TextDecoration.ITALIC, false))),
                    click -> checkout(click.player(), pack.id(),
                            String.format("%,d Static", pack.amount())));
        }
    }

    private static String usd(int cents) {
        return "$" + (cents / 100) + "." + String.format("%02d", cents % 100) + " USD";
    }

    // ---- ranks -----------------------------------------------------------

    private void buildRanks() {
        RoleDefinition held = roles.roleIn(profile, RoleConfig.DONOR);
        header(Material.NETHER_STAR, "Ranks",
                "A tag, and the cosmetics it unlocks.",
                held == null ? "You carry no rank." : null,
                held != null ? "Use Rank Upgrades — pay only the difference." : null);

        List<RoleDefinition> tiers = roles.config().inCategory(RoleConfig.DONOR);
        for (int i = 0; i < tiers.size() && i < GRID.length; i++) {
            RoleDefinition tier = tiers.get(i);
            boolean owned = held != null && held.id().equals(tier.id());
            boolean below = held != null && tier.weight() <= held.weight();
            set(GRID[i], rankTile(tier, priceOf(tier), owned, below), click -> {
                if (owned || below) {
                    deny(click.player(), owned
                            ? "You already carry that tag."
                            : "You are already above that tag.");
                    return;
                }
                if (held != null) {
                    deny(click.player(), "You carry a rank — use Rank Upgrades.");
                    return;
                }
                buy(click.player(), tier, priceOf(tier));
            });
        }
    }

    private void buildUpgrades() {
        RoleDefinition held = roles.roleIn(profile, RoleConfig.DONOR);
        header(Material.SMITHING_TABLE, "Rank Upgrades",
                held == null ? "You carry no rank — buy one from Ranks first." : null,
                held != null ? "You pay only the difference." : null);

        if (held == null) {
            return;
        }
        List<RoleDefinition> tiers = roles.config().inCategory(RoleConfig.DONOR);
        int slot = 0;
        for (RoleDefinition tier : tiers) {
            if (tier.weight() <= held.weight() || slot >= GRID.length) {
                continue;
            }
            long difference = Math.max(1, priceOf(tier) - priceOf(held));
            set(GRID[slot++], rankTile(tier, difference, false, false),
                    click -> buy(click.player(), tier, difference));
        }
    }

    /** Price is derived from the tier's own weight, so a new tier is priced by existing. */
    private static long priceOf(RoleDefinition tier) {
        return tier.weight() * 20L;
    }

    /**
     * One rank tile.
     *
     * <p>The shape that makes it read: description, blank, <b>"What you get:"</b>, the
     * perks in desirability order with a coloured {@code ✦} each, blank, price, blank,
     * the call to action. Every one of those blanks is load-bearing — a tile without
     * them is a wall of text and that is what the first version looked like.
     */
    private org.bukkit.inventory.ItemStack rankTile(RoleDefinition tier, long price,
                                                    boolean owned, boolean below) {
        List<Component> lore = new ArrayList<>();
        lore.add(grey(flavour(tier.id())));
        lore.add(Component.empty());
        lore.add(Component.text("  What you get:", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        for (String perk : Perks.DISPLAY_ORDER) {
            if (tier.perks().contains(perk)) {
                lore.add(line(Perks.colourOf(perk), Perks.describe(perk)));
            }
        }
        lore.add(Component.empty());
        lore.add(Component.text("  Price: ", NamedTextColor.GRAY)
                .append(Component.text(Static.format(price), STATIC_COLOUR))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(owned
                ? Component.text("  You carry this.", NamedTextColor.GREEN, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false)
                : below
                        ? grey("  Below your rank.")
                        : Component.text("  Click to purchase", NamedTextColor.YELLOW)
                                .decoration(TextDecoration.ITALIC, false));

        Material icon = switch (tier.id()) {
            case "muffled" -> Material.BROWN_TERRACOTTA;
            case "hushed" -> Material.LIGHT_BLUE_TERRACOTTA;
            case "silent" -> Material.BLUE_TERRACOTTA;
            case "unheard" -> Material.PURPLE_TERRACOTTA;
            case "thestill" -> Material.CRYING_OBSIDIAN;
            default -> Material.GRAY_TERRACOTTA;
        };
        return MenuItems.of(icon, roles.tag(tier), lore);
    }

    private static String flavour(String id) {
        return switch (id) {
            case "muffled" -> "They hear you late.";
            case "hushed" -> "They hear you wrong.";
            case "silent" -> "They hear the room, not you.";
            case "unheard" -> "They stop turning their heads.";
            case "thestill" -> "You are weather now.";
            default -> "Everybody starts here.";
        };
    }

    // ---- cases and keys --------------------------------------------------

    private void buildCases() {
        header(Material.DECORATED_POT, "Dredge Cases",
                "Something came up with the rock that was not rock.",
                "Cosmetics only — nothing in a case touches the dark.");
        comingSoon(22, Material.DECORATED_POT, "Dredge Case",
                "Serialised cosmetics, dragged up from a depth.",
                "Needs the collectible system. Not built yet.");
    }

    private void buildKeys() {
        header(Material.TRIAL_KEY, "Foreman's Keys",
                "The company locked it. This opens it.");
        comingSoon(22, Material.TRIAL_KEY, "Foreman's Key",
                "One key, one Dredge Case.",
                "Needs the collectible system. Not built yet.");
    }

    /**
     * An honest empty shelf.
     *
     * <p>A category that exists with nothing in it is better than a category that is
     * missing — it says what is coming and it stops the store looking like it has been
     * abandoned. It is the same argument as the rig's sealed shopfronts, which are
     * built in rust with a sign reading <em>sealed</em> rather than left as a gap.
     */
    private void comingSoon(int slot, Material icon, String name, String what, String why) {
        set(slot, MenuItems.of(icon,
                Component.text(name, NamedTextColor.DARK_GRAY, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false),
                List.of(grey(what), Component.empty(),
                        Component.text("  SEALED", NamedTextColor.DARK_RED, TextDecoration.BOLD)
                                .decoration(TextDecoration.ITALIC, false),
                        grey(why))), click -> deny(click.player(), "That counter is sealed."));
    }

    // ---- shared ----------------------------------------------------------

    private void header(Material icon, String name, String... lines) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("  Balance: ", NamedTextColor.GRAY)
                .append(Component.text(Static.format(Static.balanceOf(profile)), STATIC_COLOUR))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        for (String line : lines) {
            if (line != null) {
                lore.add(grey(line));
            }
        }
        set(4, MenuItems.of(icon, heading(name), lore));
    }

    private void buy(Player player, RoleDefinition tier, long price) {
        if (!Static.spend(profile, price)) {
            deny(player, "That costs " + Static.format(price) + ". You have "
                    + Static.format(Static.balanceOf(profile)) + ".");
            return;
        }
        // Debit first, then grant — the same order SellService uses, and for the same
        // reason: there must be no path where the charge and the delivery disagree.
        roles.assign(profile, RoleConfig.DONOR, tier.id());
        profiles.saveDurable(profile);
        roles.applyPermissions(player, profile);
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  You are ", NamedTextColor.GRAY)
                .append(roles.tag(tier)).append(Component.text(" now.", NamedTextColor.GRAY)));
        player.sendMessage(Component.empty());
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f);
        refresh();
    }

    private void checkout(Player player, String packId, String what) {
        String address = storeUrl.get().trim().replaceFirst("/+$", "");
        String url = (address.startsWith("http") ? address : "https://" + address)
                + "/package/" + packId;
        closeLater();
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  " + what, STATIC_COLOUR, TextDecoration.BOLD));
        player.sendMessage(Component.text("  ").append(Component.text(url, NamedTextColor.AQUA,
                        TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(Component.text("Opens in your browser", NamedTextColor.DARK_GRAY))));
        player.sendMessage(grey("  Credited by an admin after payment."));
        player.sendMessage(Component.empty());
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.4f);
    }

    private static void deny(Player player, String why) {
        player.sendMessage(Component.text("  " + why, NamedTextColor.DARK_GRAY,
                TextDecoration.ITALIC));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.2f);
    }

    private static Component line(TextColor colour, String text) {
        return Component.text("  ✦ ", colour)
                .append(Component.text(text, NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component grey(String text) {
        return Component.text("  " + text, NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }
}
