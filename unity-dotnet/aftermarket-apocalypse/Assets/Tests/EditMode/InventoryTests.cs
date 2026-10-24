using System.Collections.Generic;
using NUnit.Framework;

/// <summary>
/// Spec §15.2, §15.5 and §15.9 — the collection screen.
///
/// <para>The display logic is written as static functions on
/// <see cref="InventoryScreen"/> so the parts that can be quietly wrong are
/// testable without building a scene. What can be quietly wrong here is ORDER and
/// PROVENANCE: a grid that renders 40 correct tiles in a useless order looks
/// finished, and an "OG" badge on an item you did not mint is a claim about
/// ownership that the whole §15.9 design exists to make trustworthy.</para>
/// </summary>
public class InventoryTests
{
    static SkinInstance Item(int serial, Rarity rarity, string owner = "local",
                             string original = "local")
    {
        return new SkinInstance
        {
            itemId = "id-" + rarity + "-" + serial,
            designId = "starfade_camo",
            serial = serial,
            editionCap = 250,
            wear = 0.0412f,
            rarity = rarity,
            currentOwnerId = owner,
            originalOwnerUserId = original,
            mintDate = "2026-07-31",
            acquisitionSource = "ash_sacred",
        };
    }

    [Test]
    public void Inventory_SerializedItemsSortAboveUnnumberedOnes()
    {
        var items = new List<SkinInstance>
        {
            Item(0, Rarity.Common),
            Item(37, Rarity.Limited),
            Item(0, Rarity.Rare),
        };
        InventoryScreen.SortForDisplay(items);

        Assert.IsTrue(items[0].IsSerialized,
            "an unnumbered item sorted above a serialized one. The serial is the thing that is " +
            "unique in the world (§15.8.1); burying it under commons wastes the whole screen.");
    }

    /// <summary>
    /// §15.5: serial #1 of a design happens once in the game's entire lifetime.
    /// Sorting newest-first would hide it under everything minted since.
    /// </summary>
    [Test]
    public void Inventory_LowerSerialsSortFirstWithinATier()
    {
        var items = new List<SkinInstance>
        {
            Item(112, Rarity.Limited),
            Item(1, Rarity.Limited),
            Item(9, Rarity.Limited),
        };
        InventoryScreen.SortForDisplay(items);

        Assert.AreEqual(1, items[0].serial, "#1 is not at the top of its tier.");
        Assert.AreEqual(9, items[1].serial);
        Assert.AreEqual(112, items[2].serial);
    }

    [Test]
    public void Inventory_RarerTiersSortAboveCommonerOnes()
    {
        var items = new List<SkinInstance>
        {
            Item(5, Rarity.Limited),
            Item(5, Rarity.SecretRGB),
            Item(5, Rarity.Secret),
        };
        InventoryScreen.SortForDisplay(items);

        Assert.AreEqual(Rarity.SecretRGB, items[0].rarity);
        Assert.AreEqual(Rarity.Secret, items[1].rarity);
        Assert.AreEqual(Rarity.Limited, items[2].rarity);
    }

    [Test]
    public void Inventory_SortIsStableAndTotal()
    {
        // Two items identical in every sort key except id must still order
        // deterministically, or the grid reshuffles itself on every refresh.
        var a = Item(5, Rarity.Limited);
        var b = Item(5, Rarity.Limited);
        b.itemId = "id-aaa";
        a.itemId = "id-zzz";

        var one = new List<SkinInstance> { a, b };
        var two = new List<SkinInstance> { b, a };
        InventoryScreen.SortForDisplay(one);
        InventoryScreen.SortForDisplay(two);

        Assert.AreEqual(one[0].itemId, two[0].itemId,
            "the same set sorted into two different orders depending on input order, so the " +
            "grid will visibly reshuffle every time it refreshes.");
    }

    [Test]
    public void Inventory_TheTileShowsTheSerialOnlyWhenThereIsOne()
    {
        string serialized = InventoryScreen.TileText(Item(37, Rarity.Limited), null);
        StringAssert.Contains("#37 / 250", serialized,
            "a serialized tile does not show its serial.");

        string plain = InventoryScreen.TileText(Item(0, Rarity.Common), null);
        StringAssert.DoesNotContain("#", plain,
            "an unnumbered item is showing a serial marker, which implies it is numbered when " +
            "it is not — that is the difference between tradable and not (§15.2).");
    }

    /// <summary>
    /// The OG badge is a claim about ownership. §15.9 makes
    /// <c>originalOwnerUserId</c> permanent precisely so it can be trusted, so it
    /// must be shown only when it is actually true.
    /// </summary>
    [Test]
    public void Inventory_TheOgBadgeAppearsOnlyForTheOriginalOwner()
    {
        string mine = InventoryScreen.Provenance(Item(37, Rarity.Limited, "local", "local"));
        StringAssert.Contains("OG", mine, "the minter of an item is not shown as its OG.");

        string theirs = InventoryScreen.Provenance(Item(37, Rarity.Limited, "local", "someone_else"));
        StringAssert.DoesNotContain("OG —", theirs,
            "an item minted by someone else is claiming an OG badge. That badge is the one " +
            "piece of provenance players actually chase; showing it wrongly makes all of it " +
            "worthless.");
        StringAssert.Contains("someone_else", theirs, "the real minter is not named.");
    }

    [Test]
    public void Inventory_FirstOfItsKindIsSurfacedLoudly()
    {
        StringAssert.Contains("FIRST OF ITS KIND",
            InventoryScreen.Provenance(Item(1, Rarity.Limited)),
            "§15.5 calls serial #1 a one-time event in the game's lifetime and says to surface " +
            "it loudly. It is not surfaced at all.");

        StringAssert.DoesNotContain("FIRST OF ITS KIND",
            InventoryScreen.Provenance(Item(2, Rarity.Limited)),
            "#2 is claiming to be first.");
    }

    [Test]
    public void Inventory_ProvenanceStatesWhetherAnItemHasEverBeenTraded()
    {
        SkinInstance fresh = Item(37, Rarity.Limited);
        StringAssert.Contains("Never traded", InventoryScreen.Provenance(fresh));

        SkinInstance passed = Item(37, Rarity.Limited, "local", "someone_else");
        passed.previousOwnerIds = new[] { "someone_else", "a_third" };
        StringAssert.Contains("2 previous owner", InventoryScreen.Provenance(passed),
            "the owner chain length is not shown, so a heavily-traded item looks identical to a " +
            "freshly minted one.");
    }
}
