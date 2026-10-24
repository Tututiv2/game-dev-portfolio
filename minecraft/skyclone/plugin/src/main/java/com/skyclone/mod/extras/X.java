package com.skyclone.mod.extras;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Text;
import org.bukkit.entity.Player;

/** Shared helpers for the module (static, main thread). */
final class X {

    private X() {}

    static Catalog catalog = Catalog.load();

    static boolean ready(Player p) { return p != null && p.isOnline() && Sky.profiles().isReady(p); }

    /** Re-fetch every time (never keep across ticks). */
    static ExtrasData data(Player p) { return Sky.profiles().member(p, "extras", ExtrasData.class); }

    /** An item's display name with its rarity colour, or the id when nobody registered it. */
    static String itemName(String id) {
        ItemDef d = Sky.items().def(id);
        return d == null ? "&7" + id : d.rarity.code() + Text.strip(d.name);
    }

    /** Live display name of an NPC (with colour), or null when it is not registered. */
    static String npcName(String npcId) {
        NpcDef d = npcId == null ? null : Sky.npcs().def(npcId);
        return d == null ? null : (d.name == null ? npcId : d.name);
    }

    /** One-time keyed SkyBlock XP. */
    static void sbxp(Player p, String key, int xp, String reason) {
        if (xp <= 0) return;
        try { Sky.service(Features.SkyLevel.class).award(p, key, xp, reason); } catch (RuntimeException ignored) { }
    }

    /** Go Back to the SkyBlock Menu (menus module provides it). */
    static void backToSkyMenu(Menu m) {
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
    }

    static String rarityName(String r) {
        try { return com.skyclone.api.item.Rarity.valueOf(r).code() + r.replace('_', ' '); }
        catch (RuntimeException e) { return "&f" + r; }
    }
}
