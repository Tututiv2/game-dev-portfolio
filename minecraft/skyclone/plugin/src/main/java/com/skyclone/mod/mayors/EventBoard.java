package com.skyclone.mod.mayors;

import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** "Festivals & Events": every event this module runs, its status, and a button into it. */
final class EventBoard {

    private EventBoard() {}

    private static ItemStack entry(Material m, String name, String when, String... desc) {
        List<String> lore = new ArrayList<>(List.of(desc));
        lore.add("");
        lore.add("&7Status: " + when);
        lore.add("");
        lore.add("&eClick to open!");
        ItemStack it = Icons.of(m, name, lore);
        return when.startsWith("&aACTIVE") ? Icons.glow(it) : it;
    }

    static void open(Player p) {
        MayorsModule mod = MayorsModule.get();
        Menu m = new Menu("Festivals & Events", 5).chrome();
        m.set(4, Icons.of(Material.CLOCK, "&aFestivals & Events", "&7What the calendar and the mayors have", "&7in store. The full calendar: &e/calendar&7."));
        m.set(10, entry(Material.JACK_O_LANTERN, "&6Spooky Festival", mod.spooky().active() ? "&aACTIVE NOW" : Cal.when(Cal.SPOOKY),
                "&7Autumn 29th-31st. Candy from every mob,", "&7spooky mobs at night, a candy shop and", "&7a leaderboard with big prizes."), c -> mod.spooky().open(c.player()));
        m.set(11, entry(Material.CAKE, "&dNew Year Celebration", Cal.when(Cal.NEW_YEAR),
                "&7Late Winter 29th-31st. One free dated", "&7cake per year from the Baker."), c -> mod.newYear().open(c.player()));
        m.set(12, entry(Material.LEAD, "&eTraveling Zoo", mod.zoo().active() ? "&aACTIVE NOW" : Cal.when(Cal.ZOO_SUMMER),
                "&7Early Summer and Early Winter. Three pets,", "&7one of them Legendary."), c -> mod.zoo().open(c.player()));
        m.set(13, entry(Material.LAVA_BUCKET, "&cSeason of Gerald", mod.winter().phase() != WinterWorkshop.Phase.IDLE ? "&aACTIVE NOW" : Cal.when(Cal.SEASON_OF_JERRY),
                "&7Late Winter 24th. Defend the Winter", "&7Workshop for gifts and North Stars."), c -> mod.winter().open(c.player()));
        m.set(14, entry(Material.ENDER_EYE, "&5Dark Auction", mod.darkAuction().session() != null ? "&aACTIVE NOW" : Cal.when(Cal.DARK_AUCTION),
                "&7Every real hour at :55. Bid on rare", "&7artifacts, books and pets."), c -> mod.darkAuction().join(c.player(), false));
        m.set(15, entry(Material.GOLDEN_SHOVEL, "&2Mythological Ritual", mod.myth().active() ? "&aACTIVE NOW" : "&8needs the ritual perk",
                "&7Dig Griffin Burrows in the Hub while", "&7the huntress governs."), c -> mod.myth().open(c.player()));
        m.set(16, entry(Material.PAPER, "&6Stonks Auction", mod.stonks().active() ? "&aACTIVE NOW" : "&8needs the Share Floor perk",
                "&7Bid coins for Shares of Stonks while", "&7the Economist governs."), c -> mod.stonks().open(c.player()));
        m.set(21, entry(Material.FIREWORK_ROCKET, "&dThe Carnival", mod.carnival().active() ? "&aACTIVE NOW" : "&8needs the Fairground perk",
                "&7Free tickets every day, three booth", "&7games and a mask shop while the Fox governs."), c -> mod.carnival().openLeader(c.player()));
        m.set(22, entry(Material.JUKEBOX, "&aElection", Cal.when(Cal.ELECTION),
                "&7Vote for next year's mayor."), c -> ElectionMenus.open(c.player()));
        m.set(31, entry(Material.SNOW_BLOCK, "&bWinter Workshop", mod.winter().open() ? "&aOPEN" : Cal.when(Cal.WORKSHOP),
                "&7Gerald's snowy island: shops, the", "&7frozen pond and the siege."), c -> mod.winter().open(c.player()));
        m.set(39, Icons.back("Election"), c -> ElectionMenus.open(c.player()));
        AlertPolicy.Scope sc = EventAlerts.scope(p);
        // wave-4 endgame playtest: the lore printed the current scope's meaning as one long unwrapped line and then the
        // Important-only text again underneath, whatever was selected. Now: the three choices, the current one marked.
        List<String> lore = new ArrayList<>(List.of("&7Which events are announced in chat.", ""));
        lore.add((sc == AlertPolicy.Scope.IMPORTANT ? "&b▶ " : "&8  ") + AlertPolicy.Scope.IMPORTANT.label + "&7: festivals with");
        lore.add("&7    rewards, the election, the Dark");
        lore.add("&7    Auction once you can afford it");
        lore.add((sc == AlertPolicy.Scope.ALL ? "&b▶ " : "&8  ") + AlertPolicy.Scope.ALL.label + "&7: every event,");
        lore.add("&7    including the hourly ones");
        lore.add((sc == AlertPolicy.Scope.OFF ? "&b▶ " : "&8  ") + AlertPolicy.Scope.OFF.label + "&7: no calendar chat");
        lore.add("");
        lore.add("&eClick to cycle! &8(/eventalerts)");
        m.set(41, Icons.of(Material.BELL, "&aCalendar Alerts", lore), c -> {
            EventAlerts.setScope(c.player(), EventAlerts.scope(c.player()).next());
            open(c.player());
        });
        m.open(p);
    }
}
