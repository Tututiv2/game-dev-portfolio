using UnityEngine;

/// <summary>
/// One skin DESIGN — the thing that has an edition cap. Instances of it are
/// SkinInstance. Spec §18.1: "Adding a gun or a skin must never require touching
/// code", so this is an .asset.
/// </summary>
[CreateAssetMenu(menuName = "Aftermarket/Skin Design", fileName = "SkinDesign_")]
public class SkinDesign : ScriptableObject
{
    [Tooltip("Stable id. Renaming the asset must not change this — it is baked into every minted item.")]
    public string designId = "starfade_camo";

    public string displayName = "Starfade Camo";

    [Tooltip("Which weapon this skin is for, matching GunSystem.weaponName.")]
    public string weaponName = "M4";

    [Header("Edition")]
    [Tooltip("Spec §15.5: at cap the design retires FOREVER.")]
    public int editionCap = 250;

    [Header("Float range (spec §15.6)")]
    [Tooltip("Per-design caps let a skin exist only in certain bands.")]
    [Range(0f, 1f)] public float floatMin = 0f;
    [Range(0f, 1f)] public float floatMax = 1f;

    [Header("Presentation")]
    public Material material;
    public Sprite icon;

    [Tooltip("Optional: unlocking this design also unlocks this camo index (spec §10.3).")]
    public int linkedCamoIndex = -1;

    void OnValidate()
    {
        if (floatMax < floatMin) floatMax = floatMin;
        if (editionCap < 1) editionCap = 1;
        if (string.IsNullOrWhiteSpace(designId)) designId = name;
    }
}
