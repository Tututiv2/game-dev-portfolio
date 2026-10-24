package com.auraqol.skyblock;

import java.util.Locale;

/**
 * The glue between chat text and the {@link IncomeEngine}: a line is a trade,
 * a delivery, a drop, or nothing; names become price keys; a [Sacks] hover
 * becomes sack gains. Pure, so a whole chat sequence from a real session can
 * be replayed in a unit test against a price snapshot.
 */
public final class MoneyRouter {

    private MoneyRouter() {
    }

    /**
     * One chat line.
     *
     * @param legacy     the whole message as legacy "§6..." text, or null (only PET DROP! needs it)
     * @param countItems the "value items" switch: drops are items
     */
    public static void line(IncomeEngine e, PriceData data, long now, String line, String legacy, boolean countItems) {
        String rule = MoneyChat.tradeRule(line);
        if (rule != null) {
            e.tradeMessage(now, rule, Fmt.strip(line).trim());
        }
        MoneyChat.Acquired got = MoneyChat.acquired(line);
        if (got != null) {
            e.acquired(now, key(data, got.item()), got.count(), got.item(), got.rule());
            return;
        }
        if (!countItems) {
            return;
        }
        MoneyChat.Drop d = MoneyChat.drop(line, legacy);
        if (d != null) {
            drop(e, data, now, d);
        }
    }

    /** One drop: priced by name (pets by type + rarity at the level-1 bracket). */
    public static double drop(IncomeEngine e, PriceData data, long now, MoneyChat.Drop d) {
        String key;
        double unit;
        if (d.rule().startsWith("pet-drop")) {
            String type = d.item().toUpperCase(Locale.ROOT).replace(' ', '_');
            if (d.petRarity() == null) {
                key = null;
                unit = Double.NaN;
            } else {
                key = "PET:" + type + ":" + d.petRarity() + ":1";
                unit = data.lbin("LVL_1_" + d.petRarity() + "_" + type);
            }
        } else {
            String id = data.idForName(d.item());
            SbItem it = id == null ? null : SbItem.of(id, 1);
            key = it == null ? null : Pricing.valueKey(it);
            unit = it == null ? Double.NaN : Pricing.unitValue(data, it);
        }
        return e.drop(now, key, d.count(), unit, d.source(), d.item(), d.rule());
    }

    /**
     * A whole [Sacks] message: every hover (added AND removed), then the
     * engine settles the sackable inventory changes it was holding for it.
     */
    public static void sackSummary(IncomeEngine e, PriceData data, long now, Iterable<String> hovers) {
        for (String h : hovers) {
            sackHover(e, data, now, h);
        }
        e.sackSummary(now);
    }

    /**
     * One hover of a [Sacks] message. "Added items:" lines are sack gains;
     * "Removed items:" lines (and any "-N" line) are NEVER income or loss -
     * they are compaction, Replenish, moves out - and only teach the engine
     * that the item goes to sacks (see {@link IncomeEngine#sackRemoved}).
     */
    public static void sackHover(IncomeEngine e, PriceData data, long now, String hover) {
        if (hover == null) {
            return;
        }
        boolean added = Fmt.strip(hover).trim().startsWith("Added");
        for (SkyblockText.SackChange c : SkyblockText.sackChanges(hover)) {
            if (c.delta() == 0) {
                continue;
            }
            if (!added || c.delta() < 0) {
                String rid = data.idForName(c.itemName());
                SbItem rit = rid == null ? null : SbItem.of(rid, 1);
                e.sackRemoved(now, rit == null ? null : Pricing.valueKey(rit), c.delta(),
                        rit == null ? Double.NaN : Pricing.unitValue(data, rit), c.itemName());
                continue;
            }
            String id = data.idForName(c.itemName());
            if (id == null) {
                e.sackGain(now, null, c.delta(), Double.NaN, c.itemName());
                continue;
            }
            SbItem it = SbItem.of(id, 1);
            e.sackGain(now, Pricing.valueKey(it), c.delta(), Pricing.unitValue(data, it), c.itemName());
        }
    }

    /** Price key for an item name, or null if the price data does not know the name. */
    public static String key(PriceData data, String name) {
        String id = data.idForName(name);
        return id == null ? null : Pricing.valueKey(SbItem.of(id, 1));
    }
}
