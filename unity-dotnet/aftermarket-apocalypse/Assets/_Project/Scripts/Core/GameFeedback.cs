using UnityEngine;
using TMPro;

/// <summary>
/// Floating Essence popups (spec §2.1). Self-building: if nothing is wired in
/// the scene it makes its own world-space TMP label, so this works on a bare map.
/// </summary>
public class GameFeedback : MonoBehaviour
{
    public static GameFeedback instance;

    [Tooltip("Optional. World-space TMP prefab used for damage/Essence popups. Left empty, one is generated.")]
    public GameObject popupPrefab;

    void Awake()
    {
        if (instance != null && instance != this) { Destroy(this); return; }
        instance = this;
    }

    void OnDestroy()
    {
        if (instance == this) instance = null;
    }

    public static void ShowPoints(Vector3 worldPosition, int points)
    {
        if (points == 0) return;                      // spec §5.3: the Nuke case, do not carpet the map in "+0"
        Show(worldPosition, "+" + points, new Color(1f, 0.9f, 0.35f));
    }

    public static void ShowDamage(Vector3 worldPosition, float damage)
    {
        Show(worldPosition, Mathf.RoundToInt(damage).ToString(), Color.white);
    }

    public static void Show(Vector3 worldPosition, string text, Color color)
    {
        GameObject go;
        if (instance != null && instance.popupPrefab != null)
        {
            go = Instantiate(instance.popupPrefab, worldPosition, Quaternion.identity);
        }
        else
        {
            go = new GameObject("Popup");
            go.transform.position = worldPosition;
            TextMeshPro tmp = go.AddComponent<TextMeshPro>();
            tmp.text = text;
            tmp.color = color;
            tmp.fontSize = 4f;
            tmp.alignment = TextAlignmentOptions.Center;
            // LAW 2 (spec §14): TMP renders from its -Z side. A world label that
            // is not flipped is invisible from the front.
            go.transform.rotation = Quaternion.Euler(0f, 180f, 0f);
        }

        FloatingText ft = go.GetComponent<FloatingText>();
        if (ft == null) ft = go.AddComponent<FloatingText>();
        ft.Init(text, color);
    }
}
