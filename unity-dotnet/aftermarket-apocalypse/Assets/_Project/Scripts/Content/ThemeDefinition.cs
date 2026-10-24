using System;
using UnityEngine;

/// <summary>
/// One region's recolour, per spec §15.11.2. The three numbers map straight onto
/// <c>Recolor.shadergraph</c>'s <c>_HueShift</c> / <c>_SaturationMul</c> /
/// <c>_ValueMul</c>.
/// </summary>
[Serializable]
public class RegionTint
{
    [Tooltip("Rotation around the colour wheel, in turns. 0 = unchanged, 0.5 = opposite.")]
    [Range(-1f, 1f)] public float hueShift = 0f;

    [Tooltip("Multiplies saturation. Below 1 washes out toward grey; 0 is greyscale.")]
    [Range(0f, 4f)] public float saturationMul = 1f;

    [Tooltip("Multiplies brightness. Below 1 darkens.")]
    [Range(0f, 2f)] public float valueMul = 1f;
}

/// <summary>
/// A colourway, authored as an .asset (spec §18.1) so adding one is never a code
/// change — §15.11.2.
///
/// <para><b>What a theme cannot do, and why it matters when you author one.</b>
/// The recolour is an HSV transform of the model's own albedo, which is the whole
/// point: it moves the colour and leaves the wear, stitching and dirt intact,
/// where a flat colour multiply would flatten the model into a paint swatch
/// (§15.11.1). But that also bounds it — <c>saturation * mul</c> is still 0 for
/// any multiplier when the source is grey, and <c>value * mul</c> only ever
/// darkens. <b>A near-white or near-black source texture cannot be themed into a
/// colour.</b> That is measured, not theoretical: of the Inquisitor's six clothes
/// atlases only 04 (mean saturation 0.206) and 03 (0.175) have anything to
/// rotate; 05 "White" is 0.007 and 02 "Blue" is 0.010. Author themes against a
/// saturated base.</para>
/// </summary>
[CreateAssetMenu(menuName = "Aftermarket/Theme", fileName = "Theme_")]
public class ThemeDefinition : ScriptableObject
{
    [Tooltip("Stable id. This is the entitlement key the backend grants (§15.12.2) " +
             "and the string PlayerPrefs stores, so renaming it orphans purchases.")]
    public string themeId = "default";

    public string displayName = "Default";

    [Tooltip("For the store grid (§15.12.3).")]
    public Sprite swatch;

    [Tooltip("One entry per _RegionMask channel: [0] R = cloth, [1] G = armour/metal, " +
             "[2] B = leather/skin. Entries you leave out stay at IDENTITY, so a theme " +
             "that names only [0] recolours the cloth and leaves the armour silver and " +
             "the face alone — which is the behaviour §15.11.1 actually wants.")]
    public RegionTint[] regions = new RegionTint[] { new RegionTint() };

    [Tooltip("Held back from normal players (§15.12.2). This flag is for PRESENTATION " +
             "only — render the swatch greyed with a lock badge. It must never be what " +
             "decides whether the theme is usable; that is an IBackend entitlement.")]
    public bool adminOnly = false;

    /// <summary>
    /// Region <paramref name="i"/>, or a neutral one when the author did not
    /// supply it. Never null.
    ///
    /// <para><b>Missing means IDENTITY, not "copy region 0".</b> Falling back to
    /// region 0 would make every one-region theme hue-shift the armour and the
    /// face as well — which is precisely the "everything including his face is
    /// blue" failure §15.11.1 says the mask exists to prevent. The safe default
    /// is to leave a region alone.</para>
    /// </summary>
    public RegionTint Region(int i)
    {
        if (regions == null || i < 0 || i >= regions.Length || regions[i] == null)
            return new RegionTint();
        return regions[i];
    }

    /// <summary>The cloth region — region 0. Never null.</summary>
    public RegionTint Primary
    {
        get { return Region(0); }
    }

    /// <summary>
    /// True when this theme leaves the art exactly as authored — checked across
    /// EVERY region, not just region 0. A theme that only desaturates the armour
    /// is a real theme, and reporting it as identity would suppress the
    /// "shader cannot recolour" warning in ThemeSystem for exactly the case
    /// where it is worth having.
    /// </summary>
    public bool IsIdentity
    {
        get
        {
            int n = regions == null ? 0 : regions.Length;
            for (int i = 0; i < n; i++)
            {
                RegionTint r = Region(i);
                if (!Mathf.Approximately(r.hueShift, 0f)) return false;
                if (!Mathf.Approximately(r.saturationMul, 1f)) return false;
                if (!Mathf.Approximately(r.valueMul, 1f)) return false;
            }
            return true;
        }
    }
}
