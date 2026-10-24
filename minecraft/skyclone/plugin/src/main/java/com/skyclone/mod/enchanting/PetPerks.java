package com.skyclone.mod.enchanting;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import org.bukkit.entity.Player;

/**
 * Pet perks whose effect lives in an enchanting system (pets hand-off, Known risks 10: "enchanting:
 * lion_first_pounce, flying_fish_rapid_decay, guardian_lucky_seven"). Read through
 * {@code Features.Pets.abilityValue}, which answers 0 when the summoned pet lacks the perk or no pets module runs.
 * <ul>
 *   <li><b>Lion - First Pounce</b>: First Strike, Triple-Strike and Combo are {a}% stronger ({@link #firstPounce}).</li>
 *   <li><b>Guardian - Lucky Seven</b>: +{a}% chance for ultra rare books while experimenting ({@link #luckySeven}).</li>
 * </ul>
 * Flying Fish's Rapid Decay (Flash triggers more often) is fishing's: Flash lives in {@code fishing/Casting}.
 */
final class PetPerks {

    private PetPerks() {}

    static double value(Player p, String key) {
        if (p == null) return 0;
        try {
            Features.Pets pets = Sky.service(Features.Pets.class);
            if (pets == null) return 0;
            double v = pets.abilityValue(p, key, "a");
            return Double.isFinite(v) && v > 0 ? v : 0;
        } catch (RuntimeException e) {
            return 0;   // pets module absent or broken: the perk simply does nothing
        }
    }

    /** Multiplier on First Strike / Triple-Strike / Combo damage from the Lion pet (1.0 without it). */
    static double firstPounce(Player p) { return 1 + value(p, "lion_first_pounce") / 100.0; }

    /** Percent boost to ultra rare Experimentation Table books from the Guardian pet (0 without it). */
    static double luckySeven(Player p) { return value(p, "guardian_lucky_seven"); }
}
