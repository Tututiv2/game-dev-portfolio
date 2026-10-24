package com.aisky.showcam;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ShowCam: a smooth cinematic camera driven over localhost HTTP, plus a
 * per-client window title, for the AISky show rig (CONTRACT section 11).
 *
 * <p><b>Inert unless asked for</b> (see {@link Flags}): the instance it ships in
 * is also the owner's own. {@code SHOWCAM_TITLE} alone only renames the window;
 * {@code SHOWCAM=1} additionally starts the HTTP server and the camera override.
 */
public final class ShowCam implements ClientModInitializer {

    public static final Logger LOG = LoggerFactory.getLogger("showcam");

    @Override
    public void onInitializeClient() {
        if (!Flags.ANY) {
            LOG.info("[showcam] inactive (set SHOWCAM=1 or SHOWCAM_TITLE to enable); doing nothing");
            return;
        }
        if (Flags.TITLE != null) {
            LOG.info("[showcam] window title pinned to \"{}\"", Flags.TITLE);
        }
        if (!Flags.CAMERA) {
            return;
        }
        LOG.info("[showcam] CAMERA ACTIVE - port {}", Flags.port());
        CamServer.start(Flags.port());
        ClientTickEvents.END_CLIENT_TICK.register(CamDirector::tick);
    }
}
