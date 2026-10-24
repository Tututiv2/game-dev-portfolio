using System.Collections;
using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// Phase 6: the real Mystery Box. §1622 asks for "spin animation, random pool
/// from all weapons, 10 s grab window, fly-away after N uses, teddy bear,
/// relocation" — §1129 records that the old one was a name-string placeholder
/// with no spin, no random pool, no relocation, and always granted slot 1.
///
/// <para><b>It is a state machine, and the states are what make it a gamble.</b>
/// Idle → Spinning → Offering (the window where you can take it, or not) →
/// either back to Idle or, on a teddy bear, gone to a different location. A box
/// that hands you a gun the instant you pay is a vending machine.</para>
///
/// <para><b>The teddy bear is rolled at spin time, not at grab time.</b> Rolling
/// it when the player reaches for the weapon would mean the reward you were shown
/// could be snatched back, which reads as the game cheating. Rolling up front
/// means the spin either lands on a weapon or lands on the bear, and both are
/// honest outcomes of the money already spent.</para>
/// </summary>
public class MysteryBox : MonoBehaviour, IInteractable
{
    /// <summary>Spec §6's table: the box is 950 and needs no power.</summary>
    public const int Cost = 950;

    [Tooltip("Seconds the weapon hovers before the box takes it back. §1622 asks " +
             "for 10.")]
    public float grabWindow = 10f;

    [Tooltip("Seconds the spin runs before landing.")]
    public float spinDuration = 2.5f;

    [Tooltip("Chance the box leaves instead of paying out, rolled per spin. Zero " +
             "on the first use of a fresh location so a box cannot vanish on the " +
             "very first pull.")]
    [Range(0f, 1f)] public float teddyBearChance = 0.12f;

    [Tooltip("Where this box may reappear after a teddy bear. Populated by " +
             "Phase6Builder from the spawn markers it places.")]
    public List<Transform> relocationPoints = new List<Transform>();

    [Tooltip("Weapon slots this box may roll, as indices into " +
             "WeaponInventory.weapons. Empty means every weapon the player rig knows.")]
    public List<int> weaponPool = new List<int>();

    public enum State { Idle, Spinning, Offering }
    public State CurrentState { get; private set; } = State.Idle;

    /// <summary>
    /// Identifies WHICH offer a running <see cref="SpinRoutine"/> belongs to.
    ///
    /// <para>Without it the grab-window timeout is a bug rather than a timer. The
    /// coroutine sleeps the full <see cref="grabWindow"/> after landing and then
    /// clears the state if it finds <c>Offering</c> — but <see cref="Take"/>
    /// returns the box to Idle immediately WITHOUT stopping the coroutine, so a
    /// finished spin keeps a sleeper alive that will happily cancel somebody
    /// else's offer. With the shipped defaults: spin at t=0, offer at t=2.5, take
    /// at t=3, pay again at t=4, second offer at t=6.5 — and at t=12.5 the FIRST
    /// coroutine wakes and wipes the second purchase's weapon, six seconds into a
    /// window the prompt advertises as ten. The Essence is already spent.</para>
    ///
    /// <para>Bumped on every spin and on every successful take, so a routine only
    /// clears the offer it created.</para>
    /// </summary>
    int offerToken;

    /// <summary>The slot currently on offer, or -1. Public so a view model or a
    /// test can see what the spin landed on without reaching into the coroutine.</summary>
    public int OfferedSlot { get; private set; } = -1;

    int usesAtThisLocation;

    public string GetPrompt()
    {
        switch (CurrentState)
        {
            case State.Spinning:
                return null;                       // nothing to press during the spin
            case State.Offering:
                return "[F] Take " + NameOf(OfferedSlot);
            default:
                return "[F] Mystery Box [" + Cost + "]";
        }
    }

    public bool Interact(GameObject player)
    {
        switch (CurrentState)
        {
            case State.Spinning:
                return false;

            case State.Offering:
                return Take(player);

            default:
                return StartSpin();
        }
    }

    bool StartSpin()
    {
        // Same rule as WallBuy: nothing to give means nothing to charge. With an
        // empty weapons list the spin would land on a slot GiveWeapon rejects, and
        // 950 Essence would vanish for a box that opens onto nothing.
        if (!HasAnythingToGive())
        {
            Debug.LogWarning($"[MysteryBox] {name}: the player's WeaponInventory has no weapons, " +
                             "so nothing was charged. Populate WeaponInventory.weapons.");
            return false;
        }

        if (!ScoreManager.TrySpend(Cost)) return false;

        CurrentState = State.Spinning;
        StartCoroutine(SpinRoutine(++offerToken));
        return true;
    }

    IEnumerator SpinRoutine(int token)
    {
        yield return new WaitForSeconds(spinDuration);

        // Rolled here, not when the player grabs. See the class summary.
        bool teddy = usesAtThisLocation > 0 && Random.value < teddyBearChance;
        usesAtThisLocation++;

        // ROLL, THEN CHECK THE BOX CAN HONOUR IT. A teddy bear the box cannot act
        // on takes 950 Essence, hands back no weapon, moves nothing, and announces
        // that it moved -- which is precisely the lie Relocate's own "never pick
        // where it already is" rule exists to prevent. If there is nowhere free to
        // go, the spin pays out a weapon instead.
        if (teddy && Relocate())
        {
            CurrentState = State.Idle;
            OfferedSlot = -1;

            // Announced only after the move actually happened.
            if (PowerUpManager.instance != null)
                PowerUpManager.instance.Announce("THE BOX HAS MOVED");

            yield break;
        }

        OfferedSlot = RollSlot();
        CurrentState = State.Offering;

        yield return new WaitForSeconds(grabWindow);

        // Window closed without a grab. The money is gone — that is the gamble.
        // The token test is what stops this expiring somebody ELSE's offer: by now
        // the player may have taken this one and paid for another.
        if (CurrentState == State.Offering && offerToken == token)
        {
            CurrentState = State.Idle;
            OfferedSlot = -1;
        }
    }

    bool Take(GameObject player)
    {
        var inventory = player != null ? player.GetComponentInChildren<WeaponInventory>(true) : null;
        if (inventory == null || OfferedSlot < 0) return false;

        inventory.GiveWeapon(OfferedSlot);
        CurrentState = State.Idle;
        OfferedSlot = -1;

        // Retire this offer so its still-sleeping grab-window coroutine cannot
        // wake up later and cancel whatever offer is current by then.
        offerToken++;
        return true;
    }

    /// <summary>True when a spin could actually pay out.</summary>
    public bool HasAnythingToGive()
    {
        var inventory = Object.FindFirstObjectByType<WeaponInventory>();
        if (inventory == null || inventory.weapons.Count == 0) return false;

        if (weaponPool.Count == 0) return true;
        foreach (int slot in weaponPool)
            if (slot >= 0 && slot < inventory.weapons.Count) return true;
        return false;
    }

    int RollSlot()
    {
        if (weaponPool.Count > 0) return weaponPool[Random.Range(0, weaponPool.Count)];

        // No explicit pool: draw from whatever the player rig actually knows about,
        // so adding a weapon to the inventory list adds it to the box for free.
        //
        // FindFirstObjectByType, not FindGameObjectWithTag("Player"): the Player
        // object in this map carries no tag, so the tag lookup returned null and
        // the box silently rolled slot 0 every time -- a fresh copy of the exact
        // placeholder bug §1129 records ("always granted slot 1").
        var inventory = Object.FindFirstObjectByType<WeaponInventory>();
        int count = inventory != null ? inventory.weapons.Count : 0;
        return count > 0 ? Random.Range(0, count) : 0;
    }

    /// <summary>How near counts as "the same spot", squared. One metre.</summary>
    const float k_SameSpotSqr = 1f;

    /// <summary>
    /// Moves the box to a free relocation point. Returns whether it actually
    /// moved, so the caller can avoid announcing a move that did not happen.
    ///
    /// <para><b>Two rules, and the second one is why this used to be a one-shot
    /// that permanently destroyed itself.</b> The original only checked "not where
    /// I already am". Phase 6 wires each box's relocation list to the OTHER box's
    /// live Transform, so the first teddy bear moved box A exactly on top of box
    /// B — two boxes in one spot, one site abandoned. From then on every candidate
    /// was at distance zero from the box considering it, so both boxes failed the
    /// first rule forever and <c>candidates.Count == 0</c> was permanent. Every
    /// later teddy bear took 950 Essence, gave no weapon, moved nothing, and
    /// announced that the box had moved.</para>
    ///
    /// <para>Rejecting points another box is standing on fixes the collision at
    /// its source, and returning a bool stops the banner lying about the rest.</para>
    /// </summary>
    bool Relocate()
    {
        if (relocationPoints.Count == 0) return false;

        var candidates = new List<Transform>();
        foreach (var t in relocationPoints)
        {
            if (t == null) continue;

            // Never pick where it already is, or "the box has moved" is a lie.
            if ((t.position - transform.position).sqrMagnitude <= k_SameSpotSqr) continue;

            // Never pick a spot another box occupies, or the two stack and both
            // lists deadlock on the rule above.
            if (IsOccupied(t.position)) continue;

            candidates.Add(t);
        }

        if (candidates.Count == 0) return false;

        var destination = candidates[Random.Range(0, candidates.Count)];
        transform.position = destination.position;
        transform.rotation = destination.rotation;
        usesAtThisLocation = 0;
        return true;
    }

    /// <summary>True when some other Mystery Box is already standing here.</summary>
    bool IsOccupied(Vector3 position)
    {
        foreach (var box in Object.FindObjectsByType<MysteryBox>(FindObjectsSortMode.None))
        {
            if (box == this) continue;
            if ((box.transform.position - position).sqrMagnitude <= k_SameSpotSqr) return true;
        }
        return false;
    }

    string NameOf(int slot)
    {
        var inventory = Object.FindFirstObjectByType<WeaponInventory>();
        if (inventory == null || slot < 0 || slot >= inventory.weapons.Count) return "Weapon";

        // GunSystem carries weaponName, not a WeaponDefinition reference.
        var gun = inventory.weapons[slot] != null
            ? inventory.weapons[slot].GetComponentInChildren<GunSystem>(true)
            : null;
        return gun != null && !string.IsNullOrEmpty(gun.weaponName) ? gun.weaponName : "Weapon";
    }
}
