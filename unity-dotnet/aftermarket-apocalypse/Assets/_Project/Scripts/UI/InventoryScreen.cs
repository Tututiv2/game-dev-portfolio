using System.Collections.Generic;
using System.Threading.Tasks;
using TMPro;
using UnityEngine;
using UnityEngine.UI;

/// <summary>
/// What you own — spec §15.2, §15.7 and §15.9.
///
/// <para><b>Why this had to exist before Phase 8 could be called finished.</b>
/// The opening spends five seconds telling the player that the serial is "the
/// thing that is unique in the world" (§15.8.1), and then the reveal fades and
/// the item is gone into <c>collection.json</c> with no way to look at it again.
/// A chase economy where you cannot see what you caught is a slot machine with
/// no payout screen.</para>
///
/// <para>The tile shows §15.8.1's own priority order — serial first, then name,
/// rarity, float — because that is the order the reveal taught the player to read.
/// The detail panel adds the §15.9 provenance that makes an item an item rather
/// than a number: who minted it, how many hands it has passed through, and when.</para>
/// </summary>
public class InventoryScreen : MonoBehaviour
{
    [Header("Content")]
    public SkinDesign[] designs = new SkinDesign[0];

    [Header("Grid")]
    public RectTransform grid;
    [Tooltip("One disabled tile, cloned per owned item. Needs an Image and a child TMP_Text.")]
    public Button tileTemplate;

    [Header("Header")]
    public TMP_Text countLabel;
    public TMP_Text emptyLabel;

    [Header("Detail")]
    public GameObject detailPanel;
    public Image detailBorder;
    public TMP_Text detailSerial;
    public TMP_Text detailName;
    public TMP_Text detailRarity;
    public TMP_Text detailFloat;
    public TMP_Text detailVariant;
    public TMP_Text detailProvenance;
    public Button detailClose;

    readonly List<Button> tiles = new List<Button>();
    readonly List<SkinInstance> shown = new List<SkinInstance>();
    Task<PlayerProfile> load;

    void Start()
    {
        if (tileTemplate != null) tileTemplate.gameObject.SetActive(false);
        if (detailPanel != null) detailPanel.SetActive(false);
        if (detailClose != null) detailClose.onClick.AddListener(CloseDetail);
        Refresh();
    }

    public void Refresh()
    {
        load = Backend.Current.LoadProfile(StoreScreen.UserId);
    }

    void Update()
    {
        if (load == null || !load.IsCompleted) return;
        Task<PlayerProfile> done = load;
        load = null;

        if (done.Status != TaskStatus.RanToCompletion)
        {
            Debug.LogWarning("[Inventory] could not load the profile: " +
                             (done.Exception == null ? "(no exception)" : done.Exception.Message));
            return;
        }
        Build(done.Result);
    }

    /// <summary>
    /// Serialized items first, then by rarity, then by serial ascending.
    ///
    /// <para>Ascending serial is deliberate: #1 of a design is a one-time event in
    /// the game's lifetime (§15.5) and belongs at the top of its group, not buried
    /// under whatever was minted most recently.</para>
    /// </summary>
    public static void SortForDisplay(List<SkinInstance> items)
    {
        items.Sort(delegate (SkinInstance a, SkinInstance b)
        {
            if (a.IsSerialized != b.IsSerialized) return a.IsSerialized ? -1 : 1;
            if (a.rarity != b.rarity) return b.rarity.CompareTo(a.rarity);
            if (a.serial != b.serial) return a.serial.CompareTo(b.serial);
            return string.CompareOrdinal(a.itemId, b.itemId);
        });
    }

    void Build(PlayerProfile profile)
    {
        foreach (Button b in tiles)
        {
            if (b != null) Destroy(b.gameObject);
        }
        tiles.Clear();
        shown.Clear();

        var items = new List<SkinInstance>();
        if (profile != null && profile.inventory != null)
        {
            foreach (SkinInstance i in profile.inventory)
            {
                if (i != null) items.Add(i);
            }
        }
        SortForDisplay(items);

        if (countLabel != null)
            countLabel.text = items.Count + (items.Count == 1 ? " ITEM" : " ITEMS");

        // An empty grid and a broken grid look identical. Say which it is.
        if (emptyLabel != null)
        {
            emptyLabel.gameObject.SetActive(items.Count == 0);
            emptyLabel.text = "Nothing yet.\nOpen a Sacred in the store.";
        }

        if (grid == null || tileTemplate == null) return;

        foreach (SkinInstance item in items)
        {
            Button b = Instantiate(tileTemplate, grid);
            b.gameObject.SetActive(true);
            b.name = "Item_" + item.itemId;

            Image img = b.GetComponent<Image>();
            if (img != null) img.color = SacredReel.ColorFor(item.rarity);

            TMP_Text label = b.GetComponentInChildren<TMP_Text>();
            if (label != null) label.text = TileText(item, DesignFor(item));

            SkinInstance captured = item;
            b.onClick.AddListener(delegate { ShowDetail(captured); });

            tiles.Add(b);
            shown.Add(item);
        }
    }

    /// <summary>§15.8.1's reading order, compressed to a tile.</summary>
    public static string TileText(SkinInstance item, SkinDesign design)
    {
        string name = design != null ? design.displayName : item.designId;
        string serial = item.IsSerialized ? item.DisplaySerial() : "";
        string variant = item.variant != SkinVariant.None
            ? "  " + item.variant.ToString().ToUpperInvariant() : "";
        return (string.IsNullOrEmpty(serial) ? "" : serial + "\n")
             + name + variant + "\n"
             + FloatWear.ShortName(item.Band);
    }

    public SkinDesign DesignFor(SkinInstance item)
    {
        if (item == null) return null;
        foreach (SkinDesign d in designs)
        {
            if (d != null && d.designId == item.designId) return d;
        }
        return null;
    }

    void ShowDetail(SkinInstance item)
    {
        if (detailPanel != null) detailPanel.SetActive(true);
        SkinDesign design = DesignFor(item);
        Color tier = SacredReel.ColorFor(item.rarity);

        if (detailBorder != null) detailBorder.color = tier;

        if (detailSerial != null)
        {
            detailSerial.text = item.IsSerialized ? item.DisplaySerial() : "UNNUMBERED";
            detailSerial.color = item.IsFirstOfItsKind ? new Color(1f, 0.92f, 0.4f) : Color.white;
        }
        if (detailName != null)
        {
            detailName.text = design != null
                ? design.displayName + "  ·  " + design.weaponName
                : item.designId;
        }
        if (detailRarity != null)
        {
            detailRarity.text = item.rarity.ToString().ToUpperInvariant();
            detailRarity.color = tier;
        }
        if (detailFloat != null)
        {
            detailFloat.text = string.Format("{0} ({1:0.0000})",
                                             FloatWear.ShortName(item.Band), item.wear);
        }
        if (detailVariant != null)
        {
            bool has = item.variant != SkinVariant.None;
            detailVariant.gameObject.SetActive(has);
            detailVariant.text = has
                ? item.variant.ToString().ToUpperInvariant() +
                  (item.variant == SkinVariant.Tracker ? "  " + item.trackerKills + " kills" : "")
                : "";
        }
        if (detailProvenance != null) detailProvenance.text = Provenance(item);
    }

    /// <summary>
    /// §15.9's chain. The "OG" line is the one players care about: the original
    /// owner NEVER changes, so being it is permanent and unforgeable.
    /// </summary>
    public static string Provenance(SkinInstance item)
    {
        int hands = item.previousOwnerIds == null ? 0 : item.previousOwnerIds.Length;
        bool og = !string.IsNullOrEmpty(item.originalOwnerUserId)
               && item.originalOwnerUserId == item.currentOwnerId;

        var sb = new System.Text.StringBuilder();
        if (item.IsFirstOfItsKind) sb.AppendLine("FIRST OF ITS KIND");
        sb.AppendLine(og ? "OG — you minted this" : "Minted by " + item.originalOwnerUserId);
        sb.AppendLine(hands == 0 ? "Never traded" : hands + " previous owner(s)");
        if (!string.IsNullOrEmpty(item.mintDate)) sb.AppendLine("Minted " + item.mintDate);
        if (!string.IsNullOrEmpty(item.acquisitionSource)) sb.AppendLine("From " + item.acquisitionSource);
        sb.AppendLine("id " + item.itemId);
        return sb.ToString();
    }

    void CloseDetail()
    {
        if (detailPanel != null) detailPanel.SetActive(false);
    }

    /// <summary>How many items are on screen. For tests.</summary>
    public int ShownCount { get { return shown.Count; } }
}
