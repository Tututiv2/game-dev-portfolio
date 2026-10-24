using UnityEngine;

/// <summary>
/// Spec §18.1: "Every weapon, perk, gum, power-up, map, character, skin, case and
/// design is an .asset. Adding a gun or a skin must never require touching code."
///
/// This is also the structural cure for Part I's "READ FIRST" bug class. A
/// serialized inspector value on a scene INSTANCE always beats a script default,
/// which is how Map2.unity ended up running timeBetweenSpawns 5 against a script
/// that said 2, and weapon bob at 14/18 against a script that said 3.5/5.5.
/// A ScriptableObject is a single shared asset: there is exactly one copy of the
/// number and no per-instance override to drift from it.
///
/// Wallbuys (Phase 6) and the real Mystery Box need a weapon REGISTRY rather than
/// two hardcoded slots; this is that registry's element type.
/// </summary>
[CreateAssetMenu(menuName = "Aftermarket/Weapon", fileName = "Weapon_")]
public class WeaponDefinition : ScriptableObject
{
    [Header("Identity")]
    [Tooltip("Keys camo progression in PlayerPrefs. Must stay stable forever.")]
    public string weaponName = "M4";
    public string displayName = "M4";
    public GameObject viewModelPrefab;

    [Header("Damage (spec §4.1)")]
    public float damage = 10f;
    public float headshotMultiplier = 5f;
    public float range = 100f;
    public float impactForce = 2f;

    [Header("Fire")]
    [Tooltip("SECONDS BETWEEN SHOTS, not RPM. 0.1 = 600 RPM.")]
    public float fireRate = 0.1f;
    public bool allowButtonHold = true;
    public float sprintToFireDelay = 0.3f;

    [Header("Recoil")]
    public float recoilUp = 2f;
    public float recoilSide = 1f;

    [Header("Ammo")]
    public int magazineSize = 30;
    public int maxReserve = 180;
    public float reloadTime = 1.5f;

    [Header("Wallbuy (Phase 6)")]
    public int wallbuyCost = 500;
    public int wallbuyAmmoCost = 250;

    void OnValidate()
    {
        // The invariant behind spec §13.1 defect #26: a reserve above its own
        // maximum means the first Ammo Crate purchase DESTROYS the difference.
        maxReserve = Mathf.Max(magazineSize, maxReserve);
        fireRate = Mathf.Max(0.01f, fireRate);
        if (string.IsNullOrWhiteSpace(weaponName)) weaponName = name;
    }

    /// <summary>Push this definition onto a live gun. One direction only —
    /// the asset is the source of truth, the component is a view of it.</summary>
    public void ApplyTo(GunSystem gun)
    {
        if (gun == null) return;
        gun.weaponName = weaponName;
        gun.damage = damage;
        gun.headshotMultiplier = headshotMultiplier;
        gun.range = range;
        gun.impactForce = impactForce;
        gun.fireRate = fireRate;
        gun.allowButtonHold = allowButtonHold;
        gun.sprintToFireDelay = sprintToFireDelay;
        gun.recoilUp = recoilUp;
        gun.recoilSide = recoilSide;
        gun.magazineSize = magazineSize;
        gun.maxReserve = maxReserve;
        gun.reserveAmmo = Mathf.Min(gun.reserveAmmo, maxReserve);
        gun.reloadTime = reloadTime;
    }
}
