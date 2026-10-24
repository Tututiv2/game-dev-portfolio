using System.Collections;
using UnityEngine;

/// <summary>
/// Keeps child renderers and lights disabled until power, then enables them
/// after a delay. This is how the light cascades work (spec §9).
/// </summary>
public class EnableWithPower : MonoBehaviour
{
    [Tooltip("-1 = use the cascade's distance-ordered delay. Set a value to pin " +
             "this element to a fixed moment (e.g. 3.2 for the Ferris wheel rim).")]
    public float overrideDelay = -1f;

    private Renderer[] renderers;
    private Light[] lights;

    void Awake()
    {
        renderers = GetComponentsInChildren<Renderer>(true);
        lights = GetComponentsInChildren<Light>(true);
        SetEnabled(false);
    }

    void Start()
    {
        // A map with no switch at all is powered by definition (spec §9).
        if (PowerSwitch.instance == null) SetEnabled(true);
    }

    public void TurnOn(float delay)
    {
        StartCoroutine(TurnOnAfter(delay));
    }

    IEnumerator TurnOnAfter(float delay)
    {
        if (delay > 0f) yield return new WaitForSeconds(delay);
        SetEnabled(true);
    }

    void SetEnabled(bool state)
    {
        if (renderers != null)
            foreach (Renderer r in renderers) if (r != null) r.enabled = state;
        if (lights != null)
            foreach (Light l in lights) if (l != null) l.enabled = state;
    }
}
