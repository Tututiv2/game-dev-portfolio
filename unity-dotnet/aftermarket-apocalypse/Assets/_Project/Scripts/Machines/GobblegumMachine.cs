using System.Collections;
using UnityEngine;

/// <summary>500 Essence, no power needed, 2 s dispense, grants a random gum (spec §6).</summary>
public class GobblegumMachine : MonoBehaviour, IInteractable
{
    public int cost = 500;
    public float dispenseTime = 2f;

    private bool busy;

    public string GetPrompt()
    {
        if (busy) return "Dispensing...";
        if (GobblegumManager.instance != null && GobblegumManager.instance.Held.HasValue)
            return "Already holding a Gobblegum";
        return "Gobblegum [" + cost + "]";
    }

    public bool Interact(GameObject player)
    {
        if (busy) return false;
        if (GobblegumManager.instance == null) return false;
        if (GobblegumManager.instance.Held.HasValue) return false;
        if (!ScoreManager.TrySpend(cost)) return false;

        StartCoroutine(Dispense());
        return true;
    }

    IEnumerator Dispense()
    {
        busy = true;
        yield return new WaitForSeconds(dispenseTime);
        if (GobblegumManager.instance != null)
        {
            GumType gum = (GumType)Random.Range(0, System.Enum.GetValues(typeof(GumType)).Length);
            GobblegumManager.instance.Give(gum);
        }
        busy = false;
    }
}
