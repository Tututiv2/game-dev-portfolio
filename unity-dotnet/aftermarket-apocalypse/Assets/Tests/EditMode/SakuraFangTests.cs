using System.Collections.Generic;
using NUnit.Framework;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Guards for the Sakura Fang skin and, more importantly, for the two things it
/// exposed about the weapon pipeline generally.
///
/// <para>These SKIP rather than fail when the asset is absent, because the asset is
/// a build output of <c>Tools > Aftermarket > Build Sakura Fang</c> and a red suite
/// on a clean checkout would say "the project is broken" when it means "a menu item
/// has not been run". The skip message names the menu item. Note the standing
/// warning in CLAUDE.md though: a green run WITH skips proves less than it looks
/// like, so check the skip count, not just the colour.</para>
/// </summary>
public class SakuraFangTests
{
    const string k_Asset = "Assets/_Project/Content/Weapons/Weapon_SakuraFang.asset";
    const string k_ContentFolder = "Assets/_Project/Content/Weapons";

    static WeaponDefinition Load()
    {
        var d = AssetDatabase.LoadAssetAtPath<WeaponDefinition>(k_Asset);
        if (d == null)
            Assert.Ignore("Weapon_SakuraFang.asset does not exist yet. " +
                          "Run Tools > Aftermarket > Build Sakura Fang.");
        return d;
    }

    /// <summary>
    /// The one that protects the OTHER weapons. WeaponRigBuilder.LoadDefinitions
    /// sorts by asset name and Phase6Builder writes slot NUMBERS into each wallbuy,
    /// so adding a weapon whose filename sorts before an existing one silently
    /// renumbers every slot — and each chalk outline then sells a different gun,
    /// with nothing logged anywhere. This asserts the ordering property directly
    /// rather than the specific index, so it keeps holding as weapons are added.
    /// </summary>
    [Test]
    public void SakuraFang_AddingItDidNotRenumberTheExistingWeaponSlots()
    {
        var names = new List<string>();
        foreach (var guid in AssetDatabase.FindAssets("t:WeaponDefinition", new[] { k_ContentFolder }))
        {
            var path = AssetDatabase.GUIDToAssetPath(guid);
            var def = AssetDatabase.LoadAssetAtPath<WeaponDefinition>(path);
            if (def != null) names.Add(def.name);
        }
        names.Sort(string.CompareOrdinal);

        Assert.That(names.Count, Is.GreaterThanOrEqualTo(2), "expected at least Glock and M4");
        Assert.That(names[0], Is.EqualTo("Weapon_Glock"),
            "slot 0 must stay the Glock — it is the starting weapon, and " +
            "WeaponInventory.startingWeapons is hardcoded to slot 0.");
        Assert.That(names[1], Is.EqualTo("Weapon_M4"),
            "slot 1 must stay the M4 — Phase6Builder wrote slot 1 into a wallbuy.");
    }

    /// <summary>
    /// The dead-field guard. <c>WeaponDefinition.viewModelPrefab</c> existed on
    /// every weapon asset and was read by NOTHING in the entire project until this
    /// skin was wired in — a serialized inspector field that turned nothing, which
    /// is this project's signature bug class. Assign real art to it and the rig
    /// still built a grey placeholder cube, while the inspector showed the prefab
    /// sitting there looking perfectly connected.
    /// </summary>
    [Test]
    public void SakuraFang_TheViewModelPrefabIsAssignedAndSomethingReadsIt()
    {
        var d = Load();
        Assert.That(d.viewModelPrefab, Is.Not.Null,
            "viewModelPrefab is null, so the rig would fall back to the placeholder cube.");

        var source = System.IO.File.ReadAllText("Assets/_Project/Editor/WeaponRigBuilder.cs");
        Assert.That(source, Does.Contain("viewModelPrefab"),
            "WeaponRigBuilder no longer reads viewModelPrefab. The field is back to " +
            "being a knob that turns nothing, and every weapon silently reverts to a cube.");
    }

    /// <summary>
    /// A view model sits inside the camera's near plane, so a collider on it is hit
    /// by the player's OWN interaction raycast first. The symptom is that walking up
    /// to a door tells you about your gun — which reads as broken doors, not as a
    /// broken weapon, and sends you debugging the wrong system entirely.
    /// </summary>
    [Test]
    public void SakuraFang_TheViewModelCarriesNoColliders()
    {
        var d = Load();
        if (d.viewModelPrefab == null) Assert.Ignore("no view model prefab assigned yet.");

        var colliders = d.viewModelPrefab.GetComponentsInChildren<Collider>(true);
        Assert.That(colliders.Length, Is.Zero,
            "the view model has " + colliders.Length + " collider(s); they will eat the " +
            "player's interaction raycast.");
    }

    /// <summary>
    /// The magenta guard. An FBX carries the materials the DCC tool wrote into it,
    /// and Unity builds those against the Built-in shader — which renders as the
    /// magenta error colour under URP. Nothing warns; the import log stays clean.
    /// This is the fourth instance of the slot-binding class recorded in CLAUDE.md.
    /// </summary>
    [Test]
    public void SakuraFang_EveryMaterialRendersUnderUrpNotBuiltIn()
    {
        var d = Load();
        if (d.viewModelPrefab == null) Assert.Ignore("no view model prefab assigned yet.");

        var renderers = d.viewModelPrefab.GetComponentsInChildren<Renderer>(true);
        Assert.That(renderers.Length, Is.GreaterThan(0), "the view model has no renderers at all.");

        var bad = new List<string>();
        foreach (var r in renderers)
            foreach (var m in r.sharedMaterials)
            {
                if (m == null) { bad.Add($"{r.name}: null material"); continue; }
                if (m.shader == null) { bad.Add($"{m.name}: null shader"); continue; }
                if (!m.shader.name.StartsWith("Universal Render Pipeline/"))
                    bad.Add($"{m.name} on shader '{m.shader.name}'");
            }

        Assert.That(bad, Is.Empty,
            "these render magenta under URP: " + string.Join(", ", bad));
    }

    /// <summary>A sniper that fires like a rifle is not a sniper.</summary>
    [Test]
    public void SakuraFang_IsActuallyABoltActionSniper()
    {
        var d = Load();
        Assert.That(d.allowButtonHold, Is.False, "a bolt action cannot be held down.");
        Assert.That(d.fireRate, Is.GreaterThan(0.8f), "fireRate is SECONDS BETWEEN SHOTS.");
        Assert.That(d.damage, Is.GreaterThanOrEqualTo(100f));
        Assert.That(d.maxReserve, Is.GreaterThanOrEqualTo(d.magazineSize),
            "§13.1 defect #26: a reserve below the magazine loses ammo on the first crate buy.");
    }
}
