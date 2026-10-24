package com.secondwatch.game;

import com.secondwatch.core.Journal;
import com.secondwatch.core.JournalEntry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;

/**
 * The previous group's journal, as a real book you can pick up and read.
 *
 * <p>A vanilla written book on purpose. A custom item would need a model, a
 * texture, a translation key and a registry entry, and would buy nothing - what
 * makes this object frightening is entirely the words in it, and a book that
 * looks exactly like every other book in Minecraft is doing the horror a favour.
 * The players are not meant to think "mod content". They are meant to think
 * somebody left this here.
 */
public final class JournalBook {

    /**
     * Characters per page. Vanilla allows more, but a wall of text is skimmed
     * and skimming is fatal here - the false leads only work if every page is
     * actually read.
     */
    private static final int PAGE_CHARS = 380;

    private JournalBook() {
    }

    public static ItemStack create(Journal journal, String diarist) {
        List<Filterable<Component>> pages = new ArrayList<>();
        for (JournalEntry entry : journal.entries()) {
            for (String chunk : paginate(entry.rendered())) {
                pages.add(Filterable.passThrough(Component.literal(chunk)));
            }
        }

        // Titles are length-limited, and a run with a long username would
        // otherwise throw on an edge case nobody would ever reproduce on demand.
        String title = trim("notes - " + diarist, 30);

        WrittenBookContent content = new WrittenBookContent(
                Filterable.passThrough(title),
                diarist,
                0,
                pages,
                true);

        ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
        stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
        return stack;
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Split one entry across pages, breaking on blank lines first and spaces
     * second, so the prescient entry's deliberate line breaks survive - the
     * username has to land alone on its own line to do its job.
     */
    private static List<String> paginate(String text) {
        List<String> out = new ArrayList<>();
        String remaining = text;

        while (remaining.length() > PAGE_CHARS) {
            int cut = remaining.lastIndexOf("\n\n", PAGE_CHARS);
            if (cut <= 0) cut = remaining.lastIndexOf('\n', PAGE_CHARS);
            if (cut <= 0) cut = remaining.lastIndexOf(' ', PAGE_CHARS);
            if (cut <= 0) cut = PAGE_CHARS;

            out.add(remaining.substring(0, cut).stripTrailing());
            remaining = remaining.substring(cut).stripLeading();
        }
        if (!remaining.isBlank()) out.add(remaining);
        if (out.isEmpty()) out.add("");
        return out;
    }
}
