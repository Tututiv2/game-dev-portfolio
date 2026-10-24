using UnityEngine;

/// <summary>
/// Spec §18.1 + §3.3. Perk costs vary per map (§12.1, §12.2), so cost lives on
/// the machine; the perk's VALUES live here so Juggernog is 250 HP everywhere.
///
/// Spec §16 lists seven more perks to come — Mule Kick, PHD Flopper, Tombstone,
/// Who's Who, Vulture Aid, Electric Cherry, Deadshot Daiquiri. Adding one must
/// be authoring an .asset, not editing PlayerPerks.
/// </summary>
[CreateAssetMenu(menuName = "Aftermarket/Perk", fileName = "Perk_")]
public class PerkDefinition : ScriptableObject
{
    public PerkType perk = PerkType.Juggernog;
    public string displayName = "Juggernog";
    public Sprite hudIcon;
    public Color machineColor = Color.white;

    [Header("Effect (only the field this perk uses is read)")]
    public float juggernogHealth = 250f;
    [Range(0.1f, 1f)] public float speedColaMultiplier = 0.5f;
    public float doubleTapDamage = 2f;
    [Range(0.1f, 1f)] public float doubleTapFireRate = 0.75f;
    public float staminUpMultiplier = 1.3f;

    [Header("Default cost (a machine may override per map)")]
    public int defaultCost = 2500;
}
