package com.skyclone.core.combat;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * PersistentDataContainer keys owned by the combat core. Namespace is the plugin's ("skyclone").
 * <ul>
 *   <li>{@code skyclone:mob} (STRING) on every sky mob = its MobDef id</li>
 *   <li>{@code skyclone:mob_owner} (STRING uuid) on owned mobs</li>
 *   <li>{@code skyclone:mob_hp}, {@code skyclone:mob_max} (DOUBLE) health backup</li>
 *   <li>{@code skyclone:indicator} (BYTE) on damage indicator TextDisplays</li>
 *   <li>{@code skyclone:runic} (BYTE 1) set by the skills module on Runic mobs: core draws their name purple</li>
 *   <li>{@code skyclone:loot_owner} (STRING uuid), {@code skyclone:loot_until} (LONG epoch ms, 0 = forever) on owned item drops</li>
 * </ul>
 */
final class CombatKeys {

    private CombatKeys() {}

    static NamespacedKey MOB, OWNER, HP, MAX, INDICATOR, LOOT_OWNER, LOOT_UNTIL, RUNIC, NAME;

    static void init(Plugin plugin) {
        MOB = new NamespacedKey(plugin, "mob");
        OWNER = new NamespacedKey(plugin, "mob_owner");
        HP = new NamespacedKey(plugin, "mob_hp");
        MAX = new NamespacedKey(plugin, "mob_max");
        INDICATOR = new NamespacedKey(plugin, "indicator");
        LOOT_OWNER = new NamespacedKey(plugin, "loot_owner");
        LOOT_UNTIL = new NamespacedKey(plugin, "loot_until");
        RUNIC = new NamespacedKey(plugin, "runic");
        NAME = new NamespacedKey(plugin, "mob_name");   // wave 5: MobService.setDisplayName
    }
}
