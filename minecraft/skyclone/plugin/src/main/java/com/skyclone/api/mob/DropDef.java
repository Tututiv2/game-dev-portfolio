package com.skyclone.api.mob;

/**
 * One line of a loot table. JSON: {"item":"REVENANT_FLESH","min":1,"max":3,"chance":1.0}
 * or {"item":"BEHEADED_HORROR","chance":0.0005,"label":"CRAZY_RARE"}.
 *
 * <p>Magic Find multiplies {@code chance} by (1 + mf/100) for drops with chance &lt; 5% unless
 * {@code magicFind} is false. Rare drops broadcast "RARE DROP! ..." to the killer per {@link Label}.
 */
public class DropDef {

    public enum Label {
        NONE(null), RARE("&6&lRARE DROP!"), VERY_RARE("&9&lVERY RARE DROP!"),
        CRAZY_RARE("&d&lCRAZY RARE DROP!"), INSANE("&c&lINSANE DROP!"), RNGESUS("&5&lPRAY TO RNGESUS DROP!");
        public final String text;
        Label(String text) { this.text = text; }
    }

    public String item;
    public int min = 1;
    public int max = 1;
    /** 0..1 */
    public double chance = 1.0;
    public boolean magicFind = true;
    /** Auto-chosen from chance when null: <5% RARE, <1% VERY_RARE, <0.1% CRAZY_RARE. */
    public Label label;
    /** Optional requirement to see this drop at all ("slayer:zombie:4"). */
    public String requirement;

    public DropDef() {}

    public DropDef(String item, int min, int max, double chance) {
        this.item = item;
        this.min = min;
        this.max = max;
        this.chance = chance;
    }

    public Label effectiveLabel() {
        if (label != null) return label;
        if (chance >= 0.05) return Label.NONE;
        if (chance >= 0.01) return Label.RARE;
        if (chance >= 0.001) return Label.VERY_RARE;
        return Label.CRAZY_RARE;
    }
}
