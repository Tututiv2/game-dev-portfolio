using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// The list of themes the game knows about. A plain asset rather than a
/// <c>Resources</c> scan: <c>Resources.LoadAll</c> pulls everything it finds into
/// the build whether or not it is reachable, and makes "which themes exist"
/// depend on folder layout instead of on something you can look at.
/// </summary>
[CreateAssetMenu(menuName = "Aftermarket/Theme Catalog", fileName = "ThemeCatalog")]
public class ThemeCatalog : ScriptableObject
{
    public ThemeDefinition[] themes = new ThemeDefinition[0];

    Dictionary<string, ThemeDefinition> byId;

    public ThemeDefinition Find(string themeId)
    {
        if (string.IsNullOrEmpty(themeId)) return null;
        if (byId == null || byId.Count != CountNonNull())
        {
            byId = new Dictionary<string, ThemeDefinition>();
            if (themes != null)
            {
                foreach (ThemeDefinition t in themes)
                {
                    if (t == null || string.IsNullOrEmpty(t.themeId)) continue;
                    byId[t.themeId] = t;
                }
            }
        }
        ThemeDefinition found;
        return byId.TryGetValue(themeId, out found) ? found : null;
    }

    int CountNonNull()
    {
        int n = 0;
        if (themes != null)
        {
            foreach (ThemeDefinition t in themes)
            {
                if (t != null && !string.IsNullOrEmpty(t.themeId)) n++;
            }
        }
        return n;
    }
}
