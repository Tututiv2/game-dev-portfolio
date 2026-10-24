package com.skyclone.api.item;

import java.util.List;

/**
 * Inserts lines into an item's lore at a fixed section. Core renders, in order:
 * <pre>
 *   name                                  (rarity colour, reforge prefix, stars)
 *   [stats]                               Damage/Strength/... with (+reforge) [+gem]
 *   [gem slot row]
 *   AFTER_STATS     &lt;- e.g. pet level bar, drill fuel, minion speed
 *   [enchants]
 *   AFTER_ENCHANTS
 *   [description]
 *   [abilities]
 *   [full set bonus]
 *   BEFORE_FOOTER   &lt;- e.g. "Kills: 1,234", counters, "Click to view recipes!"
 *   [reforgeable hint, requirements]
 *   FOOTER          (rarity + category, e.g. "&amp;6&amp;lLEGENDARY SWORD")
 * </pre>
 * Lines use '&amp;' codes. Return an empty list to add nothing.
 */
public interface LoreContributor {

    enum Section { AFTER_STATS, AFTER_ENCHANTS, BEFORE_FOOTER }

    Section section();

    List<String> lines(ItemDef def, ItemData data);
}
