package com.skyclone.mod.extras;

import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.util.Requirements;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * extras (wave 5): the harp, the phone (the reference's Abiphone), City Projects, Fire Sales and the item skins
 * they sell. Century Cakes are NOT here: progression already built them (/cakes).
 */
public final class ExtrasModule implements GameModule, Listener {

    static Phone phone;

    @Override public String id() { return "extras"; }

    @Override public void enable() {
        XDials.register();
        X.catalog = Catalog.load();
        Skins.init();
        Sky.items().loadJson("extras", "items.json");
        Sky.db().migrate("extras", 1,
                "CREATE TABLE IF NOT EXISTS extras_city (cycle INTEGER NOT NULL, project TEXT NOT NULL, contributions INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (cycle, project))",
                "CREATE TABLE IF NOT EXISTS extras_firesale (sale TEXT PRIMARY KEY, sold INTEGER NOT NULL DEFAULT 0)");

        phone = new Phone();
        Sky.provide(Features.Abiphone.class, phone);
        Bukkit.getPluginManager().registerEvents(phone, Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new City(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(new Skins(), Sky.plugin());
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());

        // false: nothing to charge, and opening a phone is not an "ability use" other systems should count
        Sky.abilities().register("extras_phone_open", use -> { phone.open(use.player()); return false; });
        Sky.abilities().register("extras_phone_speed_dial", use -> { phone.speedDial(use.player()); return false; });

        Sky.stats().addProvider("extras", (p, into) -> {
            if (!X.ready(p)) return;
            Harp.contribute(p, into);
            City.contribute(p, into);
        });
        Sky.loot().addMagicFindModifier("extras_city", (p, def) -> City.communityValue("magic_find", null));
        Sky.items().addLoreContributor("extras_skin", new Skins.Lore());

        Sky.requirements().register("abiphone", new PhoneRequirement());
        Sky.requirements().register("harp", new HarpRequirement());

        registerCommands();
        ExtrasAdmin.register();
        ExtrasTests.register();

        City.load();
        FireSales.load();
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            try { phone.incomingTick(); } catch (RuntimeException e) { Sky.plugin().getLogger().warning("extras: incoming calls: " + e); }
            try { FireSales.tick(); } catch (RuntimeException e) { Sky.plugin().getLogger().warning("extras: fire sale tick: " + e); }
            long c = City.cycle();
            if (!City.PROGRESS.isEmpty() && City.PROGRESS.keySet().stream().noneMatch(k -> k.startsWith(c + ":"))) City.load();
        }, 20L * 60, 20L * 60);
        int every = Math.max(1, XDials.skinPassSeconds.get());
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try { Skins.pass(p); } catch (RuntimeException ignored) { }
            }
        }, 20L * every, 20L * every);
    }

    @Override public void disable() {
        Harp.stopAll();
        Apps.stopAll();
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (e.player.isOnline()) Skins.pass(e.player); }, 20L);
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) {
        Harp.stop(e.player);
        Apps.stopSnake(e.player.getUniqueId());
    }

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("harp", "Play the harp at the harpist (songs give permanent Intelligence)")
                .category("Skills").playerOnly().run((s, a) -> Harp.open((Player) s)));
        Sky.commands().register(SkyCommand.of("phone", "Open your phone (/phone [contact] calls; /phone answer)")
                .alias("abiphone", "ringphone").usage("[contact|answer]").category("General").playerOnly()
                .run((s, a) -> {
                    Player p = (Player) s;
                    if (a.length == 0) { phone.open(p); return; }
                    if (a[0].equalsIgnoreCase("answer")) { phone.answer(p); return; }
                    Catalog.Contact c = X.catalog.contact(a[0].toLowerCase(Locale.ROOT));
                    if (c == null) { Msg.error(p, "Unknown contact."); return; }
                    phone.call(p, c, false);
                })
                .tab((s, a) -> {
                    List<String> out = new ArrayList<>();
                    if (a.length == 1 && s instanceof Player p && X.ready(p)) {
                        out.add("answer");
                        out.addAll(X.data(p).contacts);
                    }
                    return out;
                }));
        Sky.commands().register(SkyCommand.of("community", "Community desk: Community Shop, City Projects, Fire Sales")
                .category("General").playerOnly().run((s, a) -> Community.open((Player) s)));
        Sky.commands().register(SkyCommand.of("cityprojects", "City Projects: fund community builds for permanent perks")
                .alias("cityproject", "city").category("General").playerOnly().run((s, a) -> City.open((Player) s)));
        Sky.commands().register(SkyCommand.of("firesales", "Fire Sales: limited cosmetic skins for Bits")
                .alias("firesale").category("General").playerOnly().run((s, a) -> FireSales.open((Player) s)));
        Sky.commands().register(SkyCommand.of("skins", "Apply or remove item skins")
                .alias("itemskins").category("General").playerOnly().run((s, a) -> Skins.open((Player) s)));
    }

    // ---- requirement keys ----------------------------------------------------------------------------------

    /** {@code abiphone:contacts:N}, {@code abiphone:contact:<id>}, {@code abiphone:phone}. */
    static final class PhoneRequirement implements Requirements.Resolver {
        @Override public boolean check(Player p, String[] parts) {
            if (!X.ready(p) || parts.length < 2) return false;
            return switch (parts[1]) {
                case "phone" -> Phone.bestPhone(p) != null;
                case "contacts" -> parts.length > 2 && X.data(p).contacts.size() >= parseInt(parts[2]);
                case "contact" -> parts.length > 2 && phone.hasContact(p, parts[2]);
                default -> false;
            };
        }
        @Override public String describe(String[] parts) {
            if (parts.length < 2) return "&aa phone";
            return switch (parts[1]) {
                case "phone" -> "&aa phone";
                case "contacts" -> "&a" + (parts.length > 2 ? parts[2] : "?") + " phone contacts";
                case "contact" -> {
                    Catalog.Contact c = parts.length > 2 ? X.catalog.contact(parts[2]) : null;
                    yield c == null ? "&aa phone contact" : Phone.display(c) + " &aas a phone contact";
                }
                default -> "&a" + String.join(":", parts);
            };
        }
    }

    /** {@code harp:songs:N}, {@code harp:song:<SONG_ID>}, {@code harp:perfect:N}. */
    static final class HarpRequirement implements Requirements.Resolver {
        @Override public boolean check(Player p, String[] parts) {
            if (!X.ready(p) || parts.length < 3) return false;
            ExtrasData d = X.data(p);
            return switch (parts[1]) {
                case "songs" -> d.songsDone.size() >= parseInt(parts[2]);
                case "perfect" -> d.songsPerfect.size() >= parseInt(parts[2]);
                case "song" -> d.songsDone.contains(parts[2].toUpperCase(Locale.ROOT));
                default -> false;
            };
        }
        @Override public String describe(String[] parts) {
            if (parts.length < 3) return "&aharp songs";
            return switch (parts[1]) {
                case "songs" -> "&a" + parts[2] + " harp songs completed";
                case "perfect" -> "&a" + parts[2] + " perfect harp songs";
                case "song" -> {
                    Catalog.Song s = X.catalog.songs.get(parts[2].toUpperCase(Locale.ROOT));
                    yield "&a" + (s == null ? parts[2] : s.name) + " &7(harp)";
                }
                default -> "&a" + String.join(":", parts);
            };
        }
    }

    static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return Integer.MAX_VALUE; }
    }
}
