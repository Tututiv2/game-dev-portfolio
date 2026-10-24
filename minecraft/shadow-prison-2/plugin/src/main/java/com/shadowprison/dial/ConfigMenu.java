package com.shadowprison.dial;

import com.shadowprison.menu.Button;
import com.shadowprison.menu.PagedMenu;
import com.shadowprison.menu.Prompts;
import com.shadowprison.menu.Ui;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /config — generated from {@link DialRegistry}. Nobody writes a config page by hand, so no dial
 * can be missing from it and no page can drift out of date (SP1 ended up with crate settings in
 * EIGHT different admin homes).
 */
public final class ConfigMenu extends PagedMenu<String> {

    public static final String PERMISSION = "shadow.admin";

    private final DialRegistry registry;
    private final Prompts prompts;

    public ConfigMenu(DialRegistry registry, Prompts prompts) {
        super(Ui.title("Configuration"));
        this.registry = registry;
        this.prompts = prompts;
    }

    @Override
    protected List<String> entries(Player viewer) {
        return new ArrayList<>(registry.byCategory().keySet());
    }

    @Override
    protected Button entry(Player viewer, String category) {
        List<Dial> dials = registry.byCategory().get(category);
        return Button.of(Ui.icon(dials.get(0).icon()).name(category, Ui.ACCENT)
                .line(dials.size() + " setting" + (dials.size() == 1 ? "" : "s"))
                .blank().action("Click to open!").build(), p -> new Category(category).open(p));
    }

    /** One category's dials. */
    private final class Category extends PagedMenu<Dial> {
        private final String category;

        Category(String category) {
            super(Ui.title("Config » " + category));
            this.category = category;
        }

        @Override
        protected List<Dial> entries(Player viewer) {
            Map<String, List<Dial>> all = registry.byCategory();
            return all.getOrDefault(category, List.of());
        }

        @Override
        protected Button entry(Player viewer, Dial d) {
            Ui.Icon icon = Ui.icon(d.icon()).name(d.label(), Ui.ACCENT);
            for (String line : wrap(d.description())) {
                icon.line(line);
            }
            icon.blank().stat("Value", d.format(d.get()), d.get() == d.defaultValue() ? Ui.GOOD : Ui.ACCENT)
                    .stat("Default", d.format(d.defaultValue()), Ui.MUTED);
            if (d.kind() != Dial.Kind.TOGGLE) {
                icon.stat("Range", d.format(d.min()) + " – " + d.format(d.max()), Ui.MUTED)
                        .blank()
                        .action("Left/Right: +/- " + d.format(d.step()))
                        .action("Shift: x10 · Middle: type exact")
                        .action("Q: reset to default");
            } else {
                icon.blank().action("Click to toggle · Q: reset");
            }
            icon.glint(d.get() != d.defaultValue());
            return Button.of(icon.build(), (p, click) -> adjust(p, d, click));
        }

        private void adjust(Player p, Dial d, ClickType click) {
            if (!p.hasPermission(PERMISSION)) {
                return;   // permission is re-checked on EVERY click, not only on open (SP1 rule 28)
            }
            String who = p.getName() + " (" + p.getUniqueId() + ")";
            if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
                registry.set(d, d.defaultValue(), who);
            } else if (d.kind() == Dial.Kind.TOGGLE) {
                registry.set(d, d.on() ? 0 : 1, who);
            } else if (click == ClickType.MIDDLE || click == ClickType.CREATIVE) {
                prompts.ask(p, "New value for " + d.label() + " (" + d.format(d.min()) + " – " + d.format(d.max()) + ")", text -> {
                    Long v = d.parse(text);
                    if (v == null) {
                        p.sendMessage(Ui.text("That isn't a valid value.", Ui.BAD));
                    } else {
                        long stored = registry.set(d, v, who);
                        p.sendMessage(Ui.text(d.label() + " = " + d.format(stored), Ui.GOOD));
                    }
                    open(p);
                });
                return;
            } else {
                long mult = click.isShiftClick() ? 10 : 1;
                long delta = Math.multiplyExact(d.step(), mult) * (click.isRightClick() ? -1 : 1);
                registry.set(d, saturatingAdd(d.get(), delta), who);
            }
            refresh(p);
        }
    }

    private static long saturatingAdd(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return b > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return r;
    }

    /** Wraps a description to ~32 chars per lore line. */
    static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > 32 && !line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
        return out;
    }
}
