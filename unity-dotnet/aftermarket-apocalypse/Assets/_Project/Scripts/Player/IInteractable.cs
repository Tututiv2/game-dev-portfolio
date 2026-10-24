using UnityEngine;

/// <summary>
/// Anything the player can press F on.
///
/// This replaces the old InteractionSystem's hardcoded 8-branch if/else chain
/// (spec §3.4) — and with it the mystery box's
/// <c>hit.collider.name.Contains("MysteryBox")</c> string match, which spec
/// §13.4 calls a placeholder rather than a feature. Wallbuys (Phase 6) are the
/// reason this matters: a chalk outline on a wall is just another IInteractable,
/// and adding one must not mean editing the interaction system.
/// </summary>
public interface IInteractable
{
    /// <summary>Text shown on the HUD while looked at, e.g. "Buy Juggernog [1500]".
    /// Return null or empty to show nothing.</summary>
    string GetPrompt();

    /// <summary>Do the thing. Return true if something actually happened
    /// (used for audio/feedback); false for "couldn't afford it" and similar.</summary>
    bool Interact(GameObject player);
}
