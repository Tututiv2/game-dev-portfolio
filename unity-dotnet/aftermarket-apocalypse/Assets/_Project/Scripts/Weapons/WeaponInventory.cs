using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// List-driven weapon inventory — replaces the old WeaponSwitcher (spec §13.5,
/// §16 "Weapon inventory rework").
///
/// The old one had two hardcoded slots and, worse, Start() OVERWROTE
/// weaponsUnlocked to { true, false } on every scene load, so anything you
/// bought was gone the moment you died. It also had no pause guard, so the
/// scroll wheel switched weapons through the pause menu.
///
/// This version holds a List, so Phase 6's wallbuys and the real Mystery Box can
/// add weapons at runtime without touching the switch logic, and 30+ weapons is
/// just a longer list.
///
/// Deliberately still simple: per-weapon ammo state already lives on each
/// GunSystem (they are separate GameObjects that stay in the scene), and
/// per-weapon PaP level lives on GunSystem.IsPackAPunched. Spec §16's full
/// rework — equipped skin, serialized instance binding — lands in Phase 6/8.
/// </summary>
public class WeaponInventory : MonoBehaviour
{
    [Tooltip("Every weapon GameObject under the weapon holder, in slot order. " +
             "Only the ones in 'startingWeapons' are held at match start.")]
    public List<GameObject> weapons = new List<GameObject>();

    [Tooltip("Indices into 'weapons' the player begins with.")]
    public List<int> startingWeapons = new List<int> { 0 };

    [Tooltip("BO2 default is 2 (3 with Mule Kick, which is Phase 7).")]
    public int carryCapacity = 2;

    private readonly List<int> carried = new List<int>();
    private int activeSlot = -1;

    public int ActiveSlot { get { return activeSlot; } }
    public IReadOnlyList<int> Carried { get { return carried; } }

    public GunSystem ActiveGun
    {
        get
        {
            if (activeSlot < 0 || activeSlot >= weapons.Count || weapons[activeSlot] == null) return null;
            return weapons[activeSlot].GetComponentInChildren<GunSystem>(true);
        }
    }

    void Start()
    {
        Initialize();
    }

    /// <summary>Public so tests can drive it without a play-mode scene.</summary>
    public void Initialize()
    {
        foreach (GameObject w in weapons)
            if (w != null) w.SetActive(false);

        carried.Clear();
        foreach (int i in startingWeapons)
            if (i >= 0 && i < weapons.Count) carried.Add(i);

        if (carried.Count > 0) Equip(carried[0]);
    }

    void Update()
    {
        // The old switcher had NO pause guard (spec §4.4).
        if (PauseGate.IsPaused) return;
        if (carried.Count < 2) return;

        for (int i = 0; i < carried.Count && i < 9; i++)
        {
            if (GameInput.SlotPressedThisFrame(i)) Equip(carried[i]);
        }

        float scroll = GameInput.WeaponScroll;
        if (Mathf.Abs(scroll) > 0.01f)
        {
            int current = carried.IndexOf(activeSlot);
            if (current < 0) current = 0;
            // Direction now actually matters. The old one toggled either way.
            int step = scroll > 0f ? 1 : -1;
            int next = (current + step + carried.Count) % carried.Count;
            Equip(carried[next]);
        }
    }

    public void Equip(int slot)
    {
        if (slot < 0 || slot >= weapons.Count) return;
        if (!carried.Contains(slot)) return;

        for (int i = 0; i < weapons.Count; i++)
            if (weapons[i] != null) weapons[i].SetActive(i == slot);

        activeSlot = slot;
    }

    /// <summary>
    /// Give the player a weapon. Over capacity, it REPLACES the currently held
    /// one — the BO2 rule, and what wallbuys and the Mystery Box both need.
    /// Returns the slot that ended up equipped, or -1.
    /// </summary>
    public int GiveWeapon(int slot)
    {
        if (slot < 0 || slot >= weapons.Count) return -1;

        if (carried.Contains(slot))
        {
            // Already own it: this is a resupply, which is what buying your own
            // wallbuy again does in BO2.
            GunSystem gun = weapons[slot] != null ? weapons[slot].GetComponentInChildren<GunSystem>(true) : null;
            if (gun != null) gun.RefillAmmo();
            Equip(slot);
            return slot;
        }

        if (carried.Count >= carryCapacity)
        {
            int replaceIndex = carried.IndexOf(activeSlot);
            if (replaceIndex < 0) replaceIndex = 0;
            carried[replaceIndex] = slot;
        }
        else
        {
            carried.Add(slot);
        }

        Equip(slot);
        return slot;
    }

    public bool HasWeapon(int slot)
    {
        return carried.Contains(slot);
    }
}
