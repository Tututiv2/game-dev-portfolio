using System.Collections;
using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// The power switch (spec §6, §9, §12.2).
///
/// IsPowered is TRUE when no switch exists in the scene, so a map without one
/// keeps working — that behaviour is deliberate and is carried over.
///
/// The cascade is this game's signature moment: main lights roll on 0.12 s apart,
/// SORTED NEAREST-FIRST from the switch, so the light visibly sweeps outward.
/// </summary>
public class PowerSwitch : MonoBehaviour, IInteractable
{
    public static PowerSwitch instance;

    [Tooltip("Seconds between each light coming on, nearest to the switch first.")]
    public float cascadeStep = 0.12f;

    public bool isPowerOn { get; private set; }

    /// <summary>True when powered OR when the map has no switch at all.</summary>
    public static bool IsPowered
    {
        get { return instance == null || instance.isPowerOn; }
    }

    public static event System.Action PowerTurnedOn;

    void Awake()
    {
        if (instance != null && instance != this) { Destroy(this); return; }
        instance = this;
        isPowerOn = false;
    }

    void OnDestroy()
    {
        if (instance == this) instance = null;
    }

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
    static void ResetStatics()
    {
        instance = null;
        PowerTurnedOn = null;
    }

    public string GetPrompt()
    {
        return isPowerOn ? null : "Turn On Power";
    }

    public bool Interact(GameObject player)
    {
        if (isPowerOn) return false;
        isPowerOn = true;

        if (PowerUpManager.instance != null) PowerUpManager.instance.Announce("POWER ON");
        if (PowerTurnedOn != null) PowerTurnedOn();

        StartCoroutine(Cascade());
        return true;
    }

    IEnumerator Cascade()
    {
        List<EnableWithPower> lights = new List<EnableWithPower>(
            FindObjectsByType<EnableWithPower>(FindObjectsInactive.Include, FindObjectsSortMode.None));

        Vector3 origin = transform.position;
        lights.Sort((a, b) =>
            (a.transform.position - origin).sqrMagnitude.CompareTo(
            (b.transform.position - origin).sqrMagnitude));

        for (int i = 0; i < lights.Count; i++)
        {
            if (lights[i] == null) continue;
            // Each element can override with its own delay (the Ferris wheel's
            // rim bulbs are hard-delayed to 3.2 s so the wheel lights LAST).
            lights[i].TurnOn(lights[i].overrideDelay >= 0f ? lights[i].overrideDelay : i * cascadeStep);
            yield return null;
        }
    }
}
