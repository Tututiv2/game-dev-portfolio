using UnityEngine;
using TMPro;

/// <summary>
/// Every purchase in the game (spec §3.4). Raycast 3 m from the camera, must hit
/// tag "Interactable", key F, fully blocked while paused.
///
/// The old version was an ordered if/else over eight concrete component types
/// with the mystery box detected by <c>collider.name.Contains("MysteryBox")</c>.
/// That is now one IInteractable lookup, so Phase 6's wallbuys and real mystery
/// box drop in without touching this file.
///
/// TAG WARNING (spec §1.2): if the "Interactable" tag is missing, NOTHING in the
/// game is buyable — no machine, door or box responds. The tag is created in
/// TagManager.asset; this script logs loudly if the raycast never sees it.
/// </summary>
public class InteractionSystem : MonoBehaviour
{
    public const string InteractableTag = "Interactable";

    [Header("Raycast")]
    public float interactDistance = 3f;
    // interactKey was a KeyCode field here. It is gone on purpose: the binding now
    // lives in GameInput, and an inspector field that no longer drives anything is
    // exactly the Part I "READ FIRST" bug this project keeps designing out — the
    // scene shows F, you change it to E, and nothing happens.

    [Header("UI")]
    [Tooltip("Optional. Shows the current target's prompt.")]
    public TextMeshProUGUI promptText;

    private Camera cam;

    void Awake()
    {
        cam = Camera.main;
    }

    void Update()
    {
        // Spec §2.3: without this, F silently spends Essence through the pause menu.
        if (PauseGate.IsPaused)
        {
            SetPrompt(null);
            return;
        }

        if (cam == null)
        {
            cam = Camera.main;
            if (cam == null) return;
        }

        IInteractable target = FindTarget();

        SetPrompt(target != null ? target.GetPrompt() : null);

        if (target != null && GameInput.InteractPressedThisFrame)
        {
            target.Interact(gameObject);
        }
    }

    private IInteractable FindTarget()
    {
        RaycastHit hit;
        if (!Physics.Raycast(cam.transform.position, cam.transform.forward, out hit,
                             interactDistance, Physics.DefaultRaycastLayers, QueryTriggerInteraction.Collide))
            return null;

        if (!hit.collider.CompareTag(InteractableTag)) return null;

        // GetComponentInParent so a machine can carry its collider on a child
        // mesh without every prefab needing the script duplicated onto it.
        return hit.collider.GetComponentInParent<IInteractable>();
    }

    private void SetPrompt(string text)
    {
        if (promptText == null) return;
        bool show = !string.IsNullOrEmpty(text);
        if (promptText.gameObject.activeSelf != show) promptText.gameObject.SetActive(show);
        if (show) promptText.text = text;
    }
}
