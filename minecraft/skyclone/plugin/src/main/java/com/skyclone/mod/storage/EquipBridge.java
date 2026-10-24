package com.skyclone.mod.storage;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * The Equipment Wardrobe's door into the worn equipment slots, which the accessories module owns.
 *
 * <p>{@code Features.Equipment} has only {@code worn(p)} today, so nothing outside accessories can put a necklace
 * on. This binds BY NAME (the same trick accessories' DungeonXpHook used before its event existed) to two methods
 * requested in the storage hand-off:
 * <pre>
 *   ItemStack equipped(Player p, String slot)                  // "NECKLACE" / "CLOAK" / "BELT" / "GLOVES"
 *   boolean   setEquipped(Player p, String slot, ItemStack it) // null clears; true = done and saved
 * </pre>
 * They may live on {@code Features.Equipment} (as defaults the provider overrides) or simply as public methods of
 * the provider's own class, so accessories can turn the bridge on without waiting for an API change. It is live only
 * when the PROVIDER really implements both (a default body, a lambda or a no-op proxy is not an implementation).
 * Until then the Equipment Wardrobe still stores and hands back whole sets; it just cannot put them on for you.
 */
final class EquipBridge {

    static final String[] SLOTS = {"NECKLACE", "CLOAK", "BELT", "GLOVES"};
    static final String[] NAMES = {"Necklace", "Cloak", "Belt", "Gloves"};

    /** The provider object the two methods below were found on (re-bound when the provider changes). */
    private Object boundTo;
    private Method get;
    private Method set;

    /** Bind to the current provider; null methods when it does not implement them. */
    private synchronized Object provider() {
        Object impl;
        try { impl = Sky.service(Features.Equipment.class); } catch (RuntimeException e) { impl = null; }
        if (impl == boundTo) return impl;
        boundTo = impl;
        get = null;
        set = null;
        if (impl == null || Proxy.isProxyClass(impl.getClass())) return impl;
        Method g = find(impl.getClass(), "equipped", Player.class, String.class);
        Method s = find(impl.getClass(), "setEquipped", Player.class, String.class, ItemStack.class);
        if (g != null && s != null && ItemStack.class.isAssignableFrom(g.getReturnType())
                && (s.getReturnType() == boolean.class || s.getReturnType() == Boolean.class)) {
            get = g;
            set = s;
        }
        return impl;
    }

    /** A public, concrete (not interface-default) method of that name, made callable. */
    private static Method find(Class<?> c, String name, Class<?>... params) {
        try {
            Method m = c.getMethod(name, params);
            if (m.isDefault() || Modifier.isAbstract(m.getModifiers())) return null;
            if (m.getDeclaringClass().isInterface()) return null;
            try { m.setAccessible(true); } catch (RuntimeException ignored) { }
            return m;
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    /** True when the provider really implements both methods, so a set can be swapped on and off. */
    boolean live(Player p) {
        provider();
        return get != null && set != null;
    }

    /** Where the bridge stands, for admins: "live (Class)", "fallback (lambda/proxy)", "absent". */
    String describe() {
        Object impl = provider();
        if (impl == null) return "absent";
        return (get != null && set != null ? "live" : "fallback") + " (" + impl.getClass().getSimpleName() + ")";
    }

    /** The piece worn in one slot, or null. Live bridge first, then the read-only {@code worn(p)} list. */
    ItemStack equipped(Player p, int piece) {
        Object impl = provider();
        if (get != null && set != null) {
            try {
                Object r = get.invoke(impl, p, SLOTS[piece]);
                return r instanceof ItemStack s && !Codec.empty(s) ? s.clone() : null;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        try {
            List<ItemStack> worn = Sky.service(Features.Equipment.class).worn(p);
            if (worn != null) for (ItemStack s : worn) if (!Codec.empty(s) && piece(s) == piece) return s.clone();
        } catch (RuntimeException ignored) { }
        return null;
    }

    /** Put {@code it} (null clears) in one slot. False when the bridge is not live or the owner refused. */
    boolean set(Player p, int piece, ItemStack it) {
        Object impl = provider();
        if (get == null || set == null) return false;
        try {
            Object r = set.invoke(impl, p, SLOTS[piece], it == null ? null : it.clone());
            return Boolean.TRUE.equals(r);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Sky.log().warning("[storage] equipment bridge setEquipped failed: " + e);
            return false;
        }
    }

    // ---- which row a piece belongs in ---------------------------------------------------------------

    static int piece(ItemCategory c) {
        if (c == null) return -1;
        return switch (c) {
            case NECKLACE -> 0;
            case CLOAK -> 1;
            case BELT -> 2;
            case GLOVES -> 3;
            default -> -1;
        };
    }

    /** Row 0-3 for an equipment piece, -1 for anything else. */
    static int piece(ItemStack s) {
        ItemDef d = Codec.def(s);
        return d == null ? -1 : piece(d.category);
    }
}
