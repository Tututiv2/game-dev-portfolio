package com.shadowprison.item;

import com.shadowprison.menu.Ui;
import com.shadowprison.stat.Stat;
import com.shadowprison.stat.StatMap;
import com.shadowprison.store.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The item registry: definitions in, ItemStacks out, and the only place that knows how an item is
 * tagged. Identity is PDC {@code shadowprison:id} (+ {@code :uid} for unique items) — NEVER the
 * display name, which an anvil can forge.
 */
public final class Items {

    private final Database db;
    private final NamespacedKey idKey;
    private final NamespacedKey uidKey;
    private final Map<String, ItemDef> defs = new LinkedHashMap<>();

    public Items(JavaPlugin plugin, Database db) {
        this.db = db;
        this.idKey = new NamespacedKey(plugin, "id");
        this.uidKey = new NamespacedKey(plugin, "uid");
    }

    public void register(ItemDef def) {
        if (defs.putIfAbsent(def.id(), def) != null) {
            throw new IllegalStateException("duplicate item id " + def.id());
        }
    }

    public ItemDef def(String id) {
        return id == null ? null : defs.get(id);
    }

    public Collection<ItemDef> all() {
        return Collections.unmodifiableCollection(defs.values());
    }

    /** Builds {@code amount} of a stackable item, or ONE unique item (amount ignored). */
    public ItemStack create(String id, int amount, String source) {
        ItemDef def = defs.get(id);
        if (def == null) {
            throw new IllegalArgumentException("unknown item " + id);
        }
        ItemStack stack = new ItemStack(def.material(), def.unique() ? 1 : Math.max(1, Math.min(amount, def.material().getMaxStackSize())));
        String uid = def.unique() ? UUID.randomUUID().toString() : null;
        stack.editMeta(meta -> {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(idKey, PersistentDataType.STRING, def.id());
            if (uid != null) {
                pdc.set(uidKey, PersistentDataType.STRING, uid);
                meta.setMaxStackSize(1);
            }
            meta.displayName(Ui.text(def.name(), def.rarity().color()));
            meta.lore(lore(def, uid));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_UNBREAKABLE);
            if (def.model() != null) {
                meta.setItemModel(NamespacedKey.fromString(def.model()));
            }
        });
        if (uid != null) {
            long now = System.currentTimeMillis();
            db.submit("item-instance", c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO item_instances(uid, item_id, serial, owner, source, created_at) VALUES (?, ?, NULL, NULL, ?, ?)")) {
                    ps.setString(1, uid);
                    ps.setString(2, def.id());
                    ps.setString(3, source == null ? "unknown" : source);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
            });
        }
        return stack;
    }

    /** SkyBlock layout: stats, blank, description, blank, RARITY CATEGORY. */
    public static List<Component> lore(ItemDef def, String uid) {
        List<Component> out = new ArrayList<>();
        StatMap stats = def.stats();
        for (Stat stat : Stat.values()) {
            long v = stats.get(stat);
            if (v != 0) {
                out.add(Ui.text(stat.display() + ": ", Ui.MUTED)
                        .append(Ui.text(stat.formatValue(v), stat.color())));
            }
        }
        if (!def.description().isEmpty()) {
            if (!out.isEmpty()) {
                out.add(Component.empty());
            }
            for (String line : def.description()) {
                out.add(Ui.text(line, Ui.MUTED));
            }
        }
        out.add(Component.empty());
        out.add(Ui.bold(def.rarity().name() + " " + def.category(), def.rarity().color()));
        if (uid != null) {
            out.add(Ui.text("#" + uid.substring(0, 8), Ui.DIM).decoration(TextDecoration.BOLD, false));
        }
        return out;
    }

    /** The item's registry id, or null for a vanilla/untagged stack. */
    public String idOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    public String uidOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(uidKey, PersistentDataType.STRING);
    }

    public boolean is(ItemStack stack, String id) {
        return id.equals(idOf(stack));
    }

    /** Stats an ItemStack contributes (registry stats; per-item upgrades layer on later). */
    public StatMap statsOf(ItemStack stack) {
        ItemDef def = def(idOf(stack));
        return def == null ? new StatMap() : def.stats();
    }
}
