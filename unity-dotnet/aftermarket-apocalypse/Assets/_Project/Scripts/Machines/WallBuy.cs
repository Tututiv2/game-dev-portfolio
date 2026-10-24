using UnityEngine;

/// <summary>
/// Phase 6: a chalk weapon outline on a wall. §1621 calls wallbuys "the core
/// economy sink of BO2" and says they are "entirely absent".
///
/// <para><b>This needed no change to the interaction system, and that is the
/// point of <see cref="IInteractable"/>.</b> §3.4 records that the old build
/// dispatched interaction through a hardcoded eight-branch if/else, with the
/// mystery box matched by <c>hit.collider.name.Contains("MysteryBox")</c>. A
/// wallbuy is just another component implementing two methods.</para>
///
/// <para><b>Two prices, and the second one is the sink.</b> Buying a gun you do
/// not carry costs <see cref="WeaponDefinition.wallbuyCost"/>; buying one you
/// already hold refills its magazine and reserve for
/// <see cref="WeaponDefinition.wallbuyAmmoCost"/>. The refill is what players
/// actually spend on, round after round, and it is why the numbers live on the
/// weapon asset rather than on this component — one price per gun, not one per
/// chalk outline, so two outlines for the same weapon cannot disagree.</para>
/// </summary>
public class WallBuy : MonoBehaviour, IInteractable
{
    [Tooltip("Index into WeaponInventory.weapons. This is the same slot list the " +
             "Mystery Box draws from.")]
    public int weaponSlot;

    [Tooltip("Drives the prompt text and both prices. Without one the wallbuy " +
             "still works but shows 'Weapon' and falls back to the constants below.")]
    public WeaponDefinition definition;

    /// <summary>Used only when <see cref="definition"/> is missing, so a wallbuy
    /// placed before its asset exists is obviously wrong rather than free.</summary>
    public int fallbackCost = 500;
    public int fallbackAmmoCost = 250;

    public int Cost => definition != null ? definition.wallbuyCost : fallbackCost;
    public int AmmoCost => definition != null ? definition.wallbuyAmmoCost : fallbackAmmoCost;
    public string WeaponName => definition != null ? definition.displayName : "Weapon";

    /// <summary>
    /// FindFirstObjectByType, NOT FindGameObjectWithTag("Player").
    ///
    /// The Player object in this map carries no tag at all, so a tag lookup
    /// returns null and every prompt would read "Buy" forever regardless of what
    /// you already own -- and the purchase branch would pick the wrong price.
    /// </summary>
    WeaponInventory Inventory => Object.FindFirstObjectByType<WeaponInventory>();

    public string GetPrompt()
    {
        var inventory = Inventory;
        bool owned = inventory != null && inventory.HasWeapon(weaponSlot);

        return owned
            ? "[F] " + WeaponName + " Ammo [" + AmmoCost + "]"
            : "[F] Buy " + WeaponName + " [" + Cost + "]";
    }

    public bool Interact(GameObject player)
    {
        var inventory = player != null
            ? player.GetComponentInChildren<WeaponInventory>(true)
            : Inventory;

        if (inventory == null)
        {
            Debug.LogWarning($"[WallBuy] {name}: no WeaponInventory on the player, so there is " +
                             "nothing to give the weapon to.");
            return false;
        }

        // REFUSE BEFORE CHARGING if the slot cannot be delivered.
        //
        // GiveWeapon returns -1 for an out-of-range slot, and the player rig's
        // `weapons` list starts EMPTY (no view-model prefabs exist yet). Spending
        // first and discovering that afterwards is a machine that takes your money
        // and hands you nothing -- strictly worse than a machine that is absent,
        // and the exact shape of bug this project keeps recording.
        if (weaponSlot < 0 || weaponSlot >= inventory.weapons.Count)
        {
            Debug.LogWarning($"[WallBuy] {name}: slot {weaponSlot} is not in the player's " +
                             $"weapons list ({inventory.weapons.Count} entries), so nothing was " +
                             "charged and nothing was given. Populate WeaponInventory.weapons.");
            return false;
        }

        bool owned = inventory.HasWeapon(weaponSlot);
        int price = owned ? AmmoCost : Cost;

        // Charge FIRST and bail if it fails, so a refused purchase cannot hand out
        // a free resupply. TrySpend is the only thing that decrements Essence.
        if (!ScoreManager.TrySpend(price)) return false;

        // GiveWeapon already distinguishes the two cases: it refills and re-equips
        // a weapon you carry, and swaps out your active slot when you are at
        // carryCapacity. Duplicating that here would be a second source of truth
        // for what "buying a gun you already own" means.
        inventory.GiveWeapon(weaponSlot);
        return true;
    }
}
