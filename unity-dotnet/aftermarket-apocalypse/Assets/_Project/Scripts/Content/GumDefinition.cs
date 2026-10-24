using UnityEngine;

/// <summary>
/// Spec §18.1 + §6. A gobblegum as data.
///
/// Two of the three shipped gums were defects (§13.2, §13.3): Perkaholic was a
/// 30-second no-op that only logged, and In Plain Sight was a speed buff rather
/// than stealth. Both are implemented properly in GobblegumManager; this asset is
/// what lets the next twenty gums be authored instead of coded.
/// </summary>
[CreateAssetMenu(menuName = "Aftermarket/Gobblegum", fileName = "Gum_")]
public class GumDefinition : ScriptableObject
{
    public GumType gum = GumType.MaxAmmo;
    public string displayName = "Max Ammo";
    [TextArea] public string description;
    public Sprite icon;

    [Tooltip("Seconds. Ignored by gums that are instantaneous or permanent.")]
    public float duration = 10f;

    [Tooltip("Relative weight in the machine's random draw.")]
    public int weight = 1;
}
