using System.Collections.Generic;
using NUnit.Framework;
using UnityEditor;
using UnityEngine;

/// <summary>
/// Guards for the first-person body — and for the class of bug it kept hitting,
/// which is a check that is green while the feature is invisible.
/// </summary>
public class FirstPersonBodyTests
{
    const string k_Catalog = "Assets/_Project/Content/Characters/CharacterCatalog.asset";

    static CharacterCatalog Catalog()
    {
        var c = AssetDatabase.LoadAssetAtPath<CharacterCatalog>(k_Catalog);
        if (c == null) Assert.Ignore("No CharacterCatalog yet. Run Build Character Select.");
        return c;
    }

    /// <summary>
    /// The whole approach rests on this: a muscle name that does not exist is
    /// SILENTLY IGNORED by Unity, so a typo leaves that joint wherever it was
    /// and the pose is subtly wrong rather than broken. This is the same shape
    /// as interactKey, whatIsEnemy and viewModelPrefab — a knob that turns
    /// nothing — and it is the cheapest of the three to catch.
    /// </summary>
    [Test]
    public void FirstPerson_EveryMuscleNameInEveryPoseReallyExists()
    {
        var known = new HashSet<string>(HumanTrait.MuscleName);
        var bad = new List<string>();

        foreach (var pose in new[] { FirstPersonPoses.EmptyOpen, FirstPersonPoses.Curled })
            foreach (var m in pose)
                if (!known.Contains(m.name)) bad.Add(m.name);

        Assert.That(bad, Is.Empty,
            "these muscle names are not in HumanTrait.MuscleName, so Unity ignores them and " +
            "that part of the pose does nothing: " + string.Join(", ", bad));
    }

    /// <summary>Muscle values outside -1..1 are clamped, so an out-of-range
    /// number is a pose that quietly differs from what it says.</summary>
    [Test]
    public void FirstPerson_NoPoseValueIsOutOfRange()
    {
        foreach (var m in FirstPersonPoses.EmptyOpen)
            Assert.That(Mathf.Abs(m.value), Is.LessThanOrEqualTo(1f),
                $"'{m.name}' is {m.value}, outside the -1..1 muscle range; it will be clamped.");
    }

    /// <summary>
    /// Both hands, every finger joint. An earlier pose set the knuckles and left
    /// the tips at whatever the previous pose had, which reads as a hand that is
    /// open at the base and clenched at the ends.
    /// </summary>
    [Test]
    public void FirstPerson_TheOpenPoseDrivesEveryFingerJointOnBothHands()
    {
        var named = new HashSet<string>();
        foreach (var m in FirstPersonPoses.EmptyOpen) named.Add(m.name);

        var missing = new List<string>();
        foreach (string side in new[] { "Left", "Right" })
            foreach (string finger in new[] { "Thumb", "Index", "Middle", "Ring", "Little" })
            {
                foreach (string joint in new[] { "1 Stretched", "2 Stretched", "3 Stretched" })
                    if (!named.Contains($"{side} {finger} {joint}")) missing.Add($"{side} {finger} {joint}");
                if (!named.Contains($"{side} {finger} Spread")) missing.Add($"{side} {finger} Spread");
            }

        Assert.That(missing, Is.Empty, "unposed finger joints: " + string.Join(", ", missing));
    }

    /// <summary>
    /// The point of muscles over bone rotations is that ONE pose serves every
    /// character. A pose that named a side only once would work on one arm.
    /// </summary>
    [Test]
    public void FirstPerson_ThePoseIsSymmetricAcrossBothArms()
    {
        var left = new Dictionary<string, float>();
        var right = new Dictionary<string, float>();
        foreach (var m in FirstPersonPoses.EmptyOpen)
        {
            if (m.name.StartsWith("Left ")) left[m.name.Substring(5)] = m.value;
            else if (m.name.StartsWith("Right ")) right[m.name.Substring(6)] = m.value;
        }

        Assert.That(left.Count, Is.GreaterThan(0), "the pose names no left-side muscles at all.");
        CollectionAssert.AreEquivalent(left.Keys, right.Keys,
            "the two sides drive different joints, so one arm will be posed and the other will not.");
        foreach (var kv in left)
            Assert.That(right[kv.Key], Is.EqualTo(kv.Value).Within(1e-4f),
                $"'{kv.Key}' differs between sides ({kv.Value} vs {right[kv.Key]}); muscle values " +
                "are already mirrored, so differing numbers make an asymmetric pose.");
    }

    /// <summary>
    /// Every character in the catalog has to be able to carry the pose. A rig
    /// that is not humanoid, or whose avatar failed to build, renders a perfectly
    /// good character standing in a T-pose with nothing logged.
    /// </summary>
    [Test]
    public void FirstPerson_EveryCharacterHasAHumanoidAvatarWithHands()
    {
        var catalog = Catalog();
        var broken = new List<string>();

        foreach (var def in catalog.characters)
        {
            if (def == null || def.prefab == null) continue;
            var animator = def.prefab.GetComponentInChildren<Animator>(true);
            if (animator == null) { broken.Add($"{def.characterId}: no Animator"); continue; }
            if (animator.avatar == null) { broken.Add($"{def.characterId}: no Avatar"); continue; }
            if (!animator.avatar.isValid) { broken.Add($"{def.characterId}: Avatar is INVALID"); continue; }
            if (!animator.avatar.isHuman) { broken.Add($"{def.characterId}: Avatar is not Humanoid"); continue; }

            // Hands specifically: a humanoid avatar can be valid with no fingers
            // mapped, and fingers are what gloves and grips both need.
            foreach (var bone in new[] { HumanBodyBones.LeftHand, HumanBodyBones.RightHand,
                                         HumanBodyBones.LeftIndexDistal, HumanBodyBones.RightIndexDistal })
                if (animator.GetBoneTransform(bone) == null)
                    broken.Add($"{def.characterId}: no {bone}");
        }

        Assert.That(broken, Is.Empty,
            "these characters cannot be posed in first person: " + string.Join("; ", broken));
    }
}
