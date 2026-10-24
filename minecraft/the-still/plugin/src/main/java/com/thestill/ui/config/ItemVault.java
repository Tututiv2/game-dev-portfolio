package com.thestill.ui.config;

import com.thestill.admin.AdminAudit;
import com.thestill.item.ItemRegistry;
import com.thestill.player.Profiles;

/**
 * What the item vault page needs to exist: a catalogue, and somewhere to write down
 * who took what.
 *
 * <p>Three constructor arguments threaded through two menus is three chances to pass
 * them in the wrong order, so they travel as one thing — the same shape
 * {@code AdminSetContext} already uses for the admin panel's dependencies.
 *
 * <p>Nullable at the {@link ConfigMenu} level on purpose: if the vault was never
 * wired the hub simply does not draw the tile, rather than drawing a button that
 * throws when it is clicked.
 */
public record ItemVault(ItemRegistry items, Profiles profiles, AdminAudit audit) {
}
