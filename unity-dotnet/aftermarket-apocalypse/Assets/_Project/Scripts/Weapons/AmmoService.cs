using UnityEngine;

/// <summary>
/// One refill path for the whole game — the fix for spec §13.1 defect #26.
///
/// The old build had three separate refill call sites and they disagreed:
/// AmmoCrate.cs used the default FindObjectsInactive.Exclude, so paying 1000
/// Essence refilled ONLY the gun in your hands, while PowerUpManager and
/// GobblegumManager both deliberately included inactive guns and refilled
/// everything for free. The paid upgrade was strictly worse than the free one.
///
/// Every caller now goes through here and gets Include. There is no second
/// implementation to drift.
/// </summary>
public static class AmmoService
{
    /// <summary>Refill every GunSystem in the scene, holstered ones included.</summary>
    public static void RefillAllWeapons()
    {
        GunSystem[] guns = Object.FindObjectsByType<GunSystem>(FindObjectsInactive.Include, FindObjectsSortMode.None);
        foreach (GunSystem gun in guns)
        {
            if (gun != null) gun.RefillAmmo();
        }
    }
}
