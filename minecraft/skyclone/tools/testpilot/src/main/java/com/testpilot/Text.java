package com.testpilot;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.Optional;

/** Component to plain text and to legacy section-sign colour codes. */
public final class Text {

    private static final char S = '§';

    private Text() {
    }

    public static String plain(Component c) {
        return c == null ? null : c.getString();
    }

    /**
     * Legacy form: every styled run is prefixed with its colour (or {@code §r}
     * when uncoloured) and its decorations, e.g. {@code §6§lLEGENDARY}. RGB
     * colours use the {@code §x§R§R§G§G§B§B} form.
     */
    public static String legacy(Component c) {
        if (c == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        c.visit((style, s) -> {
            if (!s.isEmpty()) {
                sb.append(codes(style)).append(s);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return sb.toString();
    }

    private static String codes(Style style) {
        StringBuilder sb = new StringBuilder();
        TextColor color = style.getColor();
        if (color == null) {
            sb.append(S).append('r');
        } else {
            ChatFormatting named = ChatFormatting.getByName(color.serialize());
            if (named != null && named.isColor()) {
                sb.append(S).append(named.getChar());
            } else {
                String hex = String.format("%06x", color.getValue() & 0xFFFFFF);
                sb.append(S).append('x');
                for (char ch : hex.toCharArray()) {
                    sb.append(S).append(ch);
                }
            }
        }
        if (style.isObfuscated()) {
            sb.append(S).append('k');
        }
        if (style.isBold()) {
            sb.append(S).append('l');
        }
        if (style.isStrikethrough()) {
            sb.append(S).append('m');
        }
        if (style.isUnderlined()) {
            sb.append(S).append('n');
        }
        if (style.isItalic()) {
            sb.append(S).append('o');
        }
        return sb.toString();
    }
}
