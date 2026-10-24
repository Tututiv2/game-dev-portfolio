using UnityEngine;

/// <summary>
/// 1000 Essence, no power required (spec §6).
///
/// Spec §13.1 defect #26: this used FindObjectsInactive.Exclude, so 1000 Essence
/// refilled ONLY the gun in your hands while the free Max Ammo power-up and the
/// free gobblegum both refilled everything. Paid was strictly worse than free.
/// It now goes through AmmoService, which is the single Include-everything path.
/// </summary>
public class AmmoCrate : MonoBehaviour, IInteractable
{
    public int cost = 1000;

    public string GetPrompt()
    {
        return "Refill Ammo [" + cost + "]";
    }

    public bool Interact(GameObject player)
    {
        if (!ScoreManager.TrySpend(cost)) return false;
        AmmoService.RefillAllWeapons();
        if (PowerUpManager.instance != null) PowerUpManager.instance.Announce("AMMO REFILLED");
        return true;
    }
}
