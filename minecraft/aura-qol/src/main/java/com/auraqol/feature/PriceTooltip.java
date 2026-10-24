package com.auraqol.feature;

import java.util.ArrayList;
import java.util.List;

import com.auraqol.AuraQoL;
import com.auraqol.skyblock.Fmt;
import com.auraqol.skyblock.PriceData;
import com.auraqol.skyblock.PriceService;
import com.auraqol.skyblock.Pricing;
import com.auraqol.skyblock.SbItem;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

/**
 * Bazaar insta-buy / insta-sell, lowest BIN and NPC sell price under any
 * SkyBlock item's tooltip.
 *
 * <p><b>Coexisting with SkyHanni and Skyblocker.</b> Both can add their own
 * price lines. This one only ever APPENDS to the list Fabric hands every
 * listener, never removes or rewrites anything, and is off with one command
 * ({@code /aq toggle prices}) - so whichever mod's lines the owner prefers,
 * the others can be switched off per feature, and none of them can break
 * another. Our lines are indented under a small "Aura" heading so it is
 * always clear whose numbers they are when three mods print a "Lowest BIN".
 *
 * <p><b>Cost.</b> The callback runs every frame the tooltip is open. Reading
 * NBT copies it and a pet's petInfo is JSON, so the built lines are cached
 * against the stack object, its count and the price snapshot - hovering one
 * item for ten seconds does the work once.
 */
public final class PriceTooltip {

    private static final int LABEL = 0xFFAAAAAA;   // §7
    private static final int VALUE = 0xFFFFAA00;   // §6
    private static final int STACK = 0xFF777777;   // dark
    private static final int HEAD = 0xFFE0B01E;    // Aura accent
    private static final int STALE = 0xFF8A6A3A;

    /** Older than this and the lines say how old (offline, or a fetch keeps failing). */
    private static final long STALE_MS = 20 * 60_000L;

    private static ItemStack cachedStack;
    private static int cachedCount;
    private static PriceData cachedData;
    private static boolean cachedShift;
    private static List<Component> cachedLines = List.of();
    private static boolean loggedFailure;

    private PriceTooltip() {
    }

    public static void register() {
        Skyblock.register();
        PriceService.start(
                FabricLoader.getInstance().getConfigDir().resolve("auraqol-cache").resolve("prices.json"),
                PriceTooltip::wanted);
        ItemTooltipCallback.EVENT.register((stack, ctx, flag, lines) -> {
            try {
                append(stack, lines);
            } catch (Throwable t) {
                if (!loggedFailure) {
                    loggedFailure = true;
                    AuraQoL.LOG.warn("[auraqol] price tooltip failed once (further errors silenced)", t);
                }
            }
        });
    }

    /** Asked by the price thread before each fetch. */
    private static boolean wanted() {
        HudConfig c = HudConfig.get();
        if (!c.priceTooltip && !c.moneyEnabled && !c.recipeBuy) {
            return false;
        }
        return !c.pricesOnlyOnHypixel || Skyblock.onHypixel();
    }

    /** The exact lines a hover would add; used by {@link DevSelfTest}. */
    static void appendForTest(ItemStack stack, List<Component> lines) {
        append(stack, lines);
    }

    private static void append(ItemStack stack, List<Component> lines) {
        if (!HudConfig.get().priceTooltip || stack == null || stack.isEmpty()) {
            return;
        }
        PriceData data = PriceService.data();
        if (data.isEmpty()) {
            return;
        }
        boolean npc = HudConfig.get().priceTooltipNpc;
        if (stack != cachedStack || stack.getCount() != cachedCount || data != cachedData || npc != cachedShift) {
            cachedStack = stack;
            cachedCount = stack.getCount();
            cachedData = data;
            cachedShift = npc;
            cachedLines = build(stack, data, npc);
        }
        lines.addAll(cachedLines);
    }

    private static List<Component> build(ItemStack stack, PriceData data, boolean showNpc) {
        SbItem it = SkyblockItems.read(stack);
        if (it == null) {
            return List.of();
        }
        Pricing.Quote q = Pricing.quote(data, it);
        int n = it.count();
        List<Component> out = new ArrayList<>(6);
        long now = System.currentTimeMillis();

        if (q.bazaar() != null) {
            PriceData.Bazaar b = q.bazaar();
            if (b.instabuy() > 0) {
                out.add(line("Bazaar Buy", b.instabuy(), n));
            }
            if (b.instasell() > 0) {
                out.add(line("Bazaar Sell", b.instasell(), n));
            }
            if (b.instabuy() <= 0 && b.instasell() <= 0) {
                out.add(Component.literal(" Bazaar: no orders").withColor(LABEL));
            }
        }
        if (!q.petPrices().isEmpty()) {
            for (Pricing.PetPrice pp : q.petPrices()) {
                String label = pp.level() == 0 ? "Lowest BIN" : "Lowest BIN Lvl " + pp.level();
                out.add(line(label, pp.price(), 1));
            }
        } else if (!Double.isNaN(q.lbin())) {
            out.add(line("Lowest BIN", q.lbin(), n));
        }
        if (showNpc && !Double.isNaN(q.npc()) && q.npc() > 0) {
            out.add(line("NPC Sell", q.npc(), n));
        }
        if (out.isEmpty()) {
            return List.of();
        }

        long newest = 0;
        if (q.bazaar() != null) {
            newest = Math.max(newest, data.bazaarAt());
        }
        if (!q.petPrices().isEmpty() || !Double.isNaN(q.lbin())) {
            newest = Math.max(newest, data.lbinAt());
        }
        MutableComponent head = Component.literal("Aura prices").withColor(HEAD);
        if (newest > 0 && now - newest > STALE_MS) {
            head.append(Component.literal("  (" + Fmt.age(now - newest) + " old)").withColor(STALE));
        }
        out.add(0, head);
        return List.copyOf(out);
    }

    /** " Bazaar Buy: 1,368.4 (64x 87,578)" - per unit first, the stack total when there is a stack. */
    private static Component line(String label, double unit, int count) {
        MutableComponent c = Component.literal(" " + label + ": ").withColor(LABEL);
        c.append(Component.literal(Fmt.coins(unit)).withColor(VALUE));
        if (count > 1) {
            c.append(Component.literal(" (" + count + "x " + Fmt.coins(unit * count) + ")").withColor(STACK));
        }
        return c;
    }
}
