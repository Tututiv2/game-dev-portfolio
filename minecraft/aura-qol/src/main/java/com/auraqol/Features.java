package com.auraqol;

/**
 * The one place every feature is switched on.
 *
 * <p>Each register call is wrapped, because these were written independently
 * and a feature that throws on startup would otherwise take the whole mod
 * with it - and a HUD counter is never worth losing a cape or a zoom key for.
 */
public final class Features {

    private Features() {
    }

    public static void registerAll() {
        safe("self nametag", com.auraqol.feature.SelfNametag::register);
        safe("zoom", com.auraqol.feature.Zoom::register);
        safe("info-hud", com.auraqol.feature.InfoHud::register);
        safe("cps-counter", com.auraqol.feature.CpsCounter::register);
        safe("keystrokes", com.auraqol.feature.Keystrokes::register);
        safe("hud-editor", com.auraqol.feature.HudEditor::register);
        safe("chat-hotkeys", com.auraqol.feature.ChatHotkeys::register);
        // SkyBlock features. Each is independent and individually switchable
        // (/aq toggle <name>), so a fault in one - or SkyHanni/Skyblocker
        // doing the same job better - costs that one and nothing else.
        safe("blocks-per-second", com.auraqol.feature.BlocksPerSecond::register);
        safe("price-tooltip", com.auraqol.feature.PriceTooltip::register);
        safe("sacks", com.auraqol.feature.SacksHud::register);
        safe("money-tracker", com.auraqol.feature.MoneyTracker::register);
        safe("todo", com.auraqol.feature.TodoHud::register);
        safe("commissions", com.auraqol.feature.CommissionsHud::register);
        safe("slayer", com.auraqol.feature.SlayerHud::register);
        safe("pickaxe-ability", com.auraqol.feature.AbilityHud::register);
        safe("recipe-bazaar", com.auraqol.feature.RecipeBazaar::register);
        // Shadow Prison live channel + its panels. Dormant everywhere else: it
        // only says HELLO to a server that announced shadowprison:live_req.
        safe("shadow-live", com.auraqol.feature.ShadowLive::register);
        safe("commands", com.auraqol.feature.AuraCommands::register);
    }

    static void safe(String name, Runnable r) {
        try {
            r.run();
            AuraQoL.LOG.info("[auraqol]   + {}", name);
        } catch (Throwable t) {
            AuraQoL.LOG.warn("[auraqol]   ! {} failed to start: {}", name, t.toString());
        }
    }
}
