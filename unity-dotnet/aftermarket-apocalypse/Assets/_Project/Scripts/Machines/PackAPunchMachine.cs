using System.Collections;
using UnityEngine;

/// <summary>
/// 5000 Essence, requires power, once per gun (spec §6).
///
/// The old version spent BEFORE the 3 s wait with no rollback, so a gun that
/// turned out to be already upgraded cost you 5000 for nothing. The eligibility
/// check now happens first and the spend happens once it is known to be valid.
/// </summary>
public class PackAPunchMachine : MonoBehaviour, IInteractable
{
    public int cost = 5000;
    public float upgradeDuration = 3f;
    public bool requiresPower = true;

    private bool busy;

    public string GetPrompt()
    {
        if (requiresPower && !PowerSwitch.IsPowered) return "REQUIRES POWER";
        if (busy) return "Upgrading...";
        GunSystem gun = ActiveGun();
        if (gun == null) return null;
        if (gun.IsPackAPunched) return "Already Pack-a-Punched";
        return "Pack-a-Punch [" + cost + "]";
    }

    public bool Interact(GameObject player)
    {
        if (busy) return false;
        if (requiresPower && !PowerSwitch.IsPowered) return false;

        GunSystem gun = ActiveGun();
        if (gun == null || gun.IsPackAPunched) return false;
        if (!ScoreManager.TrySpend(cost)) return false;

        StartCoroutine(Upgrade(gun));
        return true;
    }

    IEnumerator Upgrade(GunSystem gun)
    {
        busy = true;
        yield return new WaitForSeconds(upgradeDuration);
        if (gun != null) gun.PackAPunch();
        if (PowerUpManager.instance != null) PowerUpManager.instance.Announce("PACK-A-PUNCHED");
        busy = false;
    }

    GunSystem ActiveGun()
    {
        WeaponInventory inv = FindFirstObjectByType<WeaponInventory>();
        if (inv != null) return inv.ActiveGun;

        // Fall back to whichever GunSystem is actually enabled in the scene.
        GunSystem[] guns = FindObjectsByType<GunSystem>(FindObjectsSortMode.None);
        foreach (GunSystem g in guns)
            if (g != null && g.gameObject.activeInHierarchy) return g;
        return null;
    }
}
