package com.thestill.item;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Every custom item the plugin can build, discovered by construction rather than
 * by maintenance.
 *
 * <p><b>The law, and it is the same law {@link com.thestill.config.DialRegistry}
 * already enforces for tunables: an item that is not in the registry cannot be
 * built.</b> Over there {@code register()} is the only path to a {@code Dial}, so
 * {@code /config} can never fall behind the code. Here the guarantee is stronger
 * than a convention because the compiler holds it: {@link ItemTags#stamp} is
 * package-private, so <em>identity</em> — the kind+id pair in the persistent data
 * container that makes a soul lantern a Fog Lamp rather than a soul lantern — can
 * only be applied from inside {@code com.thestill.item}. A class in another
 * package can build something that looks like a custom item; it cannot build one.
 *
 * <p>The failure this forecloses is the one the dial registry was written for.
 * Shadow Prison kept its admin panel's contents in a hand-written table, and a
 * whole family of tunables shipped with no panel entry because adding one meant
 * editing four places and the fourth got forgotten. An item vault built the same
 * way would be a second list of what exists, drifting silently away from the
 * first — and the whole reason the vault exists is so the owner can find a thing
 * without knowing a command for it. A catalogue that is out of date is worse than
 * no catalogue, because you stop checking.
 *
 * <p>Concretely: {@code /still lamp give}, the onboarding kit and the vault page
 * all call {@link #build(String)}. There is one definition of a Fog Lamp and it is
 * the entry in {@link StillItems}.
 *
 * <p>Registration is eager and total — {@link StillItems#catalogue} seeds the whole
 * thing at enable, before any of it is asked for. There is no pending-override
 * dance like the dial registry's, because an item has no file to read; the code
 * <em>is</em> the item.
 */
public final class ItemRegistry {

    /**
     * Same charset as a region id, and for the same reason.
     *
     * <p>{@code [a-zA-Z0-9_.+-]} is Brigadier's unquoted-string set, so an id that
     * strays outside it cannot be typed as a command argument without quoting.
     * Region ids learned this the hard way (no colons, ever) and an item id is
     * headed for exactly the same places — a command argument, a log line, an
     * audit row. Lowercase only, so {@code Fog_Lamp} and {@code fog_lamp} can
     * never both exist and mean the same thing.
     */
    private static final Pattern VALID_ID = Pattern.compile("^[a-z0-9][a-z0-9_.+-]{0,47}$");

    /**
     * One buildable item.
     *
     * @param id       stable, typeable, and the primary key everywhere
     * @param category the vault's grouping — "Tools", "Instruments", …
     * @param label    what the vault calls it, which need not be the item's own name
     * @param purpose  one sentence saying what it is FOR. The vault exists so the
     *                 owner can test a thing without knowing it exists, and a tile
     *                 that shows only a pretty name has told them nothing.
     * @param kind     the {@code kind} half of the PDC identity
     * @param tagId    the {@code id} half. Later marks are new tag ids, not new kinds
     * @param factory  builds a fresh, fully tagged stack of one
     */
    public record CustomItem(String id, String category, String label, String purpose,
                             String kind, String tagId, Supplier<ItemStack> factory) {

        /**
         * A fresh stack, every time.
         *
         * <p>Never a cached instance handed round. An {@code ItemStack} is mutable
         * and Bukkit hands out live references — one shared prototype would let a
         * menu that decorated a tile quietly rewrite the lore of every lamp handed
         * out afterwards.
         */
        public ItemStack create() {
            ItemStack stack = factory.get();
            if (stack == null) {
                throw new IllegalStateException("the factory for item '" + id
                        + "' returned nothing");
            }
            return stack;
        }
    }

    /** Insertion-ordered, so the vault lays out in declaration order within a category. */
    private final Map<String, CustomItem> items = new LinkedHashMap<>();

    /** kind/tagId -> item id, so two items cannot claim one identity. */
    private final Map<String, String> byTag = new LinkedHashMap<>();

    public Builder define(String id) {
        return new Builder(this, id);
    }

    private CustomItem register(CustomItem item) {
        if (items.putIfAbsent(item.id(), item) != null) {
            throw new IllegalStateException("duplicate item id: " + item.id());
        }
        String tag = item.kind() + "/" + item.tagId();
        String owner = byTag.putIfAbsent(tag, item.id());
        if (owner != null) {
            // Two items sharing kind+id are literally the same item to every
            // matcher in the plugin — ItemTags.is, the price table, the fog
            // service's "am I holding a lamp" check. They would work by accident
            // until one of them was given different behaviour.
            throw new IllegalStateException("items '" + owner + "' and '" + item.id()
                    + "' both claim the tag " + tag + "; nothing could tell them apart");
        }
        return item;
    }

    // ---- queries --------------------------------------------------------

    public CustomItem byId(String id) {
        return items.get(id);
    }

    /**
     * Builds the item with this id.
     *
     * <p>Throws rather than returning null on an unknown id, and names it. Every
     * caller is passing a constant from {@link StillItems}, so a miss is a typo at
     * a call site and it should fail at the moment it is asked for rather than
     * turning into a null somewhere downstream.
     */
    public ItemStack build(String id) {
        CustomItem item = items.get(id);
        if (item == null) {
            throw new IllegalArgumentException("no item registered as '" + id
                    + "' — known ids: " + String.join(", ", items.keySet()));
        }
        return item.create();
    }

    public Collection<CustomItem> all() {
        return Collections.unmodifiableCollection(items.values());
    }

    public int size() {
        return items.size();
    }

    /** Grouped for the vault, categories alphabetical, items in declaration order. */
    public Map<String, List<CustomItem>> byCategory() {
        Map<String, List<CustomItem>> grouped = new TreeMap<>();
        for (CustomItem item : items.values()) {
            grouped.computeIfAbsent(item.category(), c -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    public static boolean isValidId(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }

    // ---- builder --------------------------------------------------------

    public static final class Builder {

        private final ItemRegistry registry;
        private final String id;

        private String category;
        private String label;
        private String purpose;
        private String kind;
        private String tagId;

        private Builder(ItemRegistry registry, String id) {
            this.registry = registry;
            this.id = id;
        }

        public Builder category(String category) {
            this.category = category;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        /** One sentence, present tense, saying what the thing is for. */
        public Builder purpose(String purpose) {
            this.purpose = purpose;
            return this;
        }

        /** The PDC identity this item carries. Both halves are mandatory. */
        public Builder tag(String kind, String tagId) {
            this.kind = kind;
            this.tagId = tagId;
            return this;
        }

        /**
         * Files the item and returns it.
         *
         * <p>Everything is mandatory on purpose, in the same spirit as a numeric
         * dial having to declare its own range: the fields that get skipped are
         * always the descriptive ones, and an entry with no purpose line is an
         * entry nobody can use without reading the source — which is the exact
         * problem the vault was asked for.
         */
        public CustomItem register(Supplier<ItemStack> factory) {
            require(isValidId(id), "bad item id '" + id + "' — expected lowercase "
                    + "letters, digits and _ . + - (max 48 chars), so it can be typed "
                    + "as a command argument");
            require(notBlank(category), "item '" + id + "' has no category");
            require(notBlank(label), "item '" + id + "' has no label");
            require(notBlank(purpose), "item '" + id + "' has no purpose line — say "
                    + "what it is for, or the vault tile is a picture with no caption");
            require(notBlank(kind) && notBlank(tagId), "item '" + id + "' declares no "
                    + "PDC tag; identity is kind+id and it is never the display name");
            require(factory != null, "item '" + id + "' has no factory");
            return registry.register(new CustomItem(id, category, label, purpose,
                    kind, tagId, factory));
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }

        private static void require(boolean condition, String problem) {
            if (!condition) {
                throw new IllegalStateException(problem);
            }
        }
    }
}
