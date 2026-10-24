package com.auracapes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Wear a different username, on your screen only.
 *
 * <p>Built for one job: preview what a different name looks like
 * <em>on you</em>, locally. Nothing here talks
 * to a server, so the name is a preview and never a claim - everyone else
 * still sees the real one, and nothing you do in this class can change that.
 *
 * <p><b>Why a find-and-replace rather than a name field.</b> There is no
 * single place a username lives. It arrives in the nameplate as a Component,
 * in chat as the argument of a {@code chat.type.text} translatable, in death
 * messages and join lines as yet another argument, and on most servers inside
 * a prefix a plugin wrote. The only thing every one of those has in common is
 * the literal text. So the swap works on the rendered strings, which costs a
 * tree-walk per message and in exchange covers cases nobody enumerated.
 *
 * <p><b>The tree-walk is why this is not just {@code getString().replace()}.</b>
 * Flattening a Component to a String and rebuilding it as a literal would work
 * and would throw away every colour, hover and click in the line - on a server
 * with coloured ranks that turns chat grey. Each node is rebuilt in place with
 * its own style instead, so only the text changes.
 */
public final class StreamerName {

    private static boolean on;
    private static String alias = "";

    private StreamerName() {
    }

    /** True only when there is actually something to show. */
    public static boolean enabled() {
        return on && !alias.isBlank();
    }

    public static String alias() {
        return alias;
    }

    public static boolean toggle() {
        on = !on;
        CapeRegistry.save();
        return on;
    }

    public static void set(String name) {
        alias = name == null ? "" : name.trim();
        on = !alias.isBlank();
        CapeRegistry.save();
    }

    public static void off() {
        on = false;
        CapeRegistry.save();
    }

    /** Restores state from the config file. Does not save. */
    static void restore(boolean enabledFlag, String name) {
        alias = name == null ? "" : name;
        on = enabledFlag;
    }

    static boolean rawEnabled() {
        return on;
    }

    /**
     * The name to replace.
     *
     * <p>Taken from the session rather than from the player entity: the entity
     * only exists once you are in a world, and the library screen is openable
     * from the main menu. The session name is also the one the server actually
     * addressed you by, which is what chat will contain.
     */
    public static String realName() {
        User user = Minecraft.getInstance().getUser();
        return user == null ? null : user.getName();
    }

    /**
     * A copy of {@code in} with the real username swapped for the alias.
     *
     * <p>Returns the original object untouched when the feature is off, when
     * the alias equals the real name, or when there is no session - callers
     * can hand every message through this without checking anything first.
     */
    public static Component swap(Component in) {
        if (in == null || !enabled()) {
            return in;
        }
        String real = realName();
        if (real == null || real.isBlank() || real.equals(alias)) {
            return in;
        }
        return rewrite(in, real, alias);
    }

    private static Component rewrite(Component node, String from, String to) {
        ComponentContents contents = node.getContents();
        MutableComponent out;

        if (contents instanceof PlainTextContents text) {
            String raw = text.text();
            out = Component.literal(raw.contains(from) ? raw.replace(from, to) : raw);
        } else if (contents instanceof TranslatableContents tr) {
            // This is the branch that matters. Vanilla chat is
            // chat.type.text with the sender as argument 0, so a translatable
            // whose ARGUMENTS are left alone is a message with the real name
            // still in it - the key and fallback never contain the name.
            Object[] args = tr.getArgs();
            Object[] copy = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Component child) {
                    copy[i] = rewrite(child, from, to);
                } else if (arg instanceof String s) {
                    copy[i] = s.contains(from) ? s.replace(from, to) : s;
                } else {
                    copy[i] = arg;
                }
            }
            out = Component.translatableWithFallback(tr.getKey(), tr.getFallback(), copy);
        } else {
            // Scores, selectors, keybinds: nothing to rewrite, but the node
            // still has to be rebuilt so its siblings below get walked.
            out = node.plainCopy();
        }

        out.setStyle(node.getStyle());
        for (Component sibling : node.getSiblings()) {
            out.append(rewrite(sibling, from, to));
        }
        return out;
    }
}
