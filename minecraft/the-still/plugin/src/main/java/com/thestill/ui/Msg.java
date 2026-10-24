package com.thestill.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/** Consistent chat output. One prefix, three tones, no ad-hoc colour codes anywhere else. */
public final class Msg {

    private static final Component PREFIX =
            Component.text("The Still ", NamedTextColor.DARK_AQUA)
                    .append(Component.text("· ", NamedTextColor.DARK_GRAY));

    private Msg() {
    }

    public static void info(CommandSender to, String text) {
        to.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.GRAY)));
    }

    public static void ok(CommandSender to, String text) {
        to.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.AQUA)));
    }

    public static void warn(CommandSender to, String text) {
        to.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.GOLD)));
    }

    public static void error(CommandSender to, String text) {
        to.sendMessage(PREFIX.append(Component.text(text, NamedTextColor.RED)));
    }

    public static void raw(CommandSender to, Component component) {
        to.sendMessage(component);
    }

    /** "key: value" in the standard two-tone. */
    public static Component field(String label, String value) {
        return Component.text("  " + label + ": ", NamedTextColor.DARK_GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }
}
