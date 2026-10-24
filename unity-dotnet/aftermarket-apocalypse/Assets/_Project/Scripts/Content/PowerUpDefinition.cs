using UnityEngine;

/// <summary>
/// Spec §18.1 + §7. A power-up as data.
///
/// Spec §16 wants eight power-ups eventually; the build has four. The drop chance
/// and buff duration live in GameConfig because they are pacing, not content.
/// </summary>
[CreateAssetMenu(menuName = "Aftermarket/Power-Up", fileName = "PowerUp_")]
public class PowerUpDefinition : ScriptableObject
{
    public PowerUpType type = PowerUpType.MaxAmmo;
    public string displayName = "Max Ammo";

    [Tooltip("Orb colour and its point light (spec §7).")]
    public Color color = new Color(0.2f, 0.6f, 1f);

    [Tooltip("Banner shown on pickup. Routed through PowerUpManager.Announce.")]
    public string announceText = "MAX AMMO";

    [Tooltip("Relative weight in the drop roll. Spec §7 is a uniform 25% across four.")]
    public int weight = 1;

    [Tooltip("Nuke only: flat Essence awarded once (spec §7).")]
    public int flatEssenceAward = 0;
}
