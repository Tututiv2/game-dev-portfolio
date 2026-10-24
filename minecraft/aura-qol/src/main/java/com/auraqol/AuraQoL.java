package com.auraqol;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AuraQoL - the quality-of-life half of the owner's own client.
 *
 * <p>Sibling of AuraCapes and deliberately a SEPARATE mod: a cape is
 * cosmetic and a HUD is not, so a crash in one must never take the other
 * down, and either can be removed on its own.
 *
 * <p>Each feature registers itself from here and owns its own file. Nothing
 * shares state, so a broken feature is a broken feature rather than a broken
 * mod.
 */
public final class AuraQoL implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("auraqol");

    @Override
    public void onInitializeClient() {
        LOG.info("[auraqol] loading features");
        Features.registerAll();
        LOG.info("[auraqol] ready");
    }
}
