using UnityEngine;

/// <summary>
/// A perk vending machine (spec §6). Requires power. Costs vary per map:
/// Quick Revive 1500 - Double Tap 2000 - Stamin-Up 2000 - Juggernog 2500 -
/// Speed Cola 3000 (spec §12.1, §12.2).
///
/// Gate order, all of which must pass: power -> perks manager exists -> not
/// already owned AND under the 4-slot cap -> can afford -> spend -> grant.
/// The old build made the machine responsible for calling CanBuyPerk itself
/// (spec §3.3); PlayerPerks.GivePerk now enforces it too, so a new machine that
/// forgets cannot break the cap.
/// </summary>
public class PerkMachine : MonoBehaviour, IInteractable
{
    public PerkType perk = PerkType.Juggernog;
    public int cost = 2500;
    public bool requiresPower = true;

    public string GetPrompt()
    {
        if (requiresPower && !PowerSwitch.IsPowered) return "REQUIRES POWER";
        if (PlayerPerks.instance != null && PlayerPerks.instance.HasPerk(perk)) return "Already owned";
        if (PlayerPerks.instance != null && !PlayerPerks.instance.CanBuyPerk(perk))
            return "Perk slots full (" + PlayerPerks.MaxPerks + ")";
        return "Buy " + perk + " [" + cost + "]";
    }

    public bool Interact(GameObject player)
    {
        if (requiresPower && !PowerSwitch.IsPowered) return false;
        if (PlayerPerks.instance == null) return false;
        if (!PlayerPerks.instance.CanBuyPerk(perk)) return false;
        if (!ScoreManager.TrySpend(cost)) return false;

        if (!PlayerPerks.instance.GivePerk(perk))
        {
            // Should be unreachable — refund rather than eat the Essence if it is.
            ScoreManager.Award(cost);
            return false;
        }

        if (PowerUpManager.instance != null) PowerUpManager.instance.Announce(perk.ToString().ToUpper());
        return true;
    }
}
