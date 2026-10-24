using UnityEngine;

/// <summary>
/// A playable character. Spec §18.1: "Every weapon, perk, gum, power-up, map,
/// character, skin, case and design is an .asset. Adding a gun or a skin must
/// never require touching code."
///
/// <para>Characters were the one entry on that list still missing. The roster
/// lived as a hardcoded one-element array inside CharacterSelectBuilder, so
/// adding a second character meant editing an editor script — exactly what §18.1
/// exists to prevent, and the reason the Next/Prev buttons had been sitting on
/// screen since the screen was built with nothing to step to.</para>
/// </summary>
[CreateAssetMenu(menuName = "Aftermarket/Character", fileName = "Character_")]
public class CharacterDefinition : ScriptableObject
{
    [Header("Identity")]
    [Tooltip("Keys PlayerPrefs and entitlements. MUST STAY STABLE FOREVER — " +
             "changing it loses every player's saved selection and theme.")]
    public string characterId = "inquisitor";

    public string displayName = "The Assassin";

    [TextArea(2, 4)]
    public string tagline = "";

    [Tooltip("The model shown in the preview and worn in play.")]
    public GameObject prefab;

    [Header("Availability (§15.12.2)")]
    [Tooltip("Presentation only. This decides how the tile LOOKS; the BACKEND " +
             "decides whether it can actually be equipped. Never gate on this alone.")]
    public bool adminOnly;

    void OnValidate()
    {
        // A blank id would collide with every other blank id in PlayerPrefs, so
        // two characters would share one saved theme and neither would be
        // diagnosable from the symptom.
        if (string.IsNullOrWhiteSpace(characterId)) characterId = name;
    }
}
