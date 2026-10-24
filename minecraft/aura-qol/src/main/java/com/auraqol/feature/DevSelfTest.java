package com.auraqol.feature;

import java.util.ArrayList;
import java.util.List;

import com.auraqol.AuraQoL;
import com.auraqol.skyblock.PriceService;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Dev-only self check, OFF unless the JVM is started with
 * {@code -Dauraqol.selftest=true} (gradlew runClient -Pselftest). It never
 * touches input: it only LOGS what the SkyBlock features computed, so a
 * headless test run can be judged from latest.log.
 *
 * <p>Every 2 s while in a world it logs: whether the sidebar reads as
 * SkyBlock, the purse it parsed, the money tracker's status, and the exact
 * price-tooltip lines for every SkyBlock item in the inventory - the same
 * lines a hover would add, built by the same code path.
 */
final class DevSelfTest {

    private static int tick;

    private DevSelfTest() {
    }

    static void registerIfEnabled() {
        if (!Boolean.getBoolean("auraqol.selftest")) {
            return;
        }
        AuraQoL.LOG.info("[auraqol] SELFTEST enabled - logging feature state every 2 s");
        ClientTickEvents.END_CLIENT_TICK.register(DevSelfTest::onTick);
    }

    private static final boolean HOLD_ATTACK = Boolean.getBoolean("auraqol.holdattack");

    private static void onTick(Minecraft mc) {
        if (HOLD_ATTACK && mc.player != null && com.auraqol.compat.Mc.screen(mc) == null) {
            // In-process key state only (no OS input): lets an RCON-driven
            // test break blocks around an adventure-mode player and check that
            // blocks/s counts the mined block plus its "spread".
            mc.options.keyAttack.setDown(true);
        }
        if (mc.player == null || ++tick % 40 != 0) {
            return;
        }
        try {
            AuraQoL.LOG.info("[auraqol] SELFTEST sidebar skyblock={} lines={}", Skyblock.inSkyblock(), Skyblock.sidebar());
            AuraQoL.LOG.info("[auraqol] SELFTEST purse={}",
                    com.auraqol.skyblock.SkyblockText.purse(Skyblock.sidebar()));
            for (String s : MoneyTracker.status()) {
                AuraQoL.LOG.info("[auraqol] SELFTEST money {}", s);
            }
            AuraQoL.LOG.info("[auraqol] SELFTEST prices bazaar={} lbin={} ({}) npc={} err='{}'",
                    PriceService.data().bazaarSize(), PriceService.data().lbinSize(),
                    PriceService.data().lbinSource(), PriceService.data().npcSize(), PriceService.lastError());
            AuraQoL.LOG.info("[auraqol] SELFTEST bps now={} peak={} total={} spread={} todo={}",
                    BlocksPerSecond.current(), BlocksPerSecond.peak(), BlocksPerSecond.total(),
                    BlocksPerSecond.spreadTotal(), TodoHud.list().items());
            AuraQoL.LOG.info("[auraqol] SELFTEST tab ({} lines) area={} comms={}",
                    Skyblock.tabLines().size(), CommissionsHud.area(), CommissionsHud.current());
            AuraQoL.LOG.info("[auraqol] SELFTEST slayer={}", SlayerHud.current());
            AuraQoL.LOG.info("[auraqol] SELFTEST ability={}", AbilityHud.view());
            for (String s : ShadowLive.status()) {
                AuraQoL.LOG.info("[auraqol] SELFTEST shadow {}", s);
            }
            Inventory inv = mc.player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (SkyblockItems.read(s) == null) {
                    continue;
                }
                List<Component> lines = new ArrayList<>();
                PriceTooltip.appendForTest(s, lines);
                List<String> plain = new ArrayList<>();
                for (Component c : lines) {
                    plain.add(c.getString());
                }
                AuraQoL.LOG.info("[auraqol] SELFTEST tooltip slot {} {} -> {}", i, SkyblockItems.read(s), plain);
            }
        } catch (Throwable t) {
            AuraQoL.LOG.warn("[auraqol] SELFTEST failed", t);
        }
    }
}
