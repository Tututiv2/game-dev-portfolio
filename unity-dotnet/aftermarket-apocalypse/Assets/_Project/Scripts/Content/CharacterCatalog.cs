using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// The roster. A plain asset rather than a <c>Resources</c> scan, for the same
/// reason <see cref="ThemeCatalog"/> is: <c>Resources.LoadAll</c> pulls everything
/// it finds into the build whether or not it is reachable, and makes "which
/// characters exist" a property of folder layout instead of something you can open
/// and look at. <see cref="LocalBackend"/> already lost its entire Sacred catalogue
/// to that exact assumption.
///
/// <para><b>Array ORDER is the display order and nothing else.</b> Nothing may
/// persist a roster index — see <see cref="CharacterSelect"/>, where both the
/// selected character and the per-character theme were once keyed by position, so
/// inserting a character silently handed every player a different one.</para>
/// </summary>
[CreateAssetMenu(menuName = "Aftermarket/Character Catalog", fileName = "CharacterCatalog")]
public class CharacterCatalog : ScriptableObject
{
    public CharacterDefinition[] characters = new CharacterDefinition[0];

    Dictionary<string, CharacterDefinition> byId;

    public CharacterDefinition Find(string characterId)
    {
        if (string.IsNullOrEmpty(characterId)) return null;
        if (byId == null || byId.Count != CountNonNull())
        {
            byId = new Dictionary<string, CharacterDefinition>();
            if (characters != null)
            {
                foreach (CharacterDefinition c in characters)
                {
                    if (c == null || string.IsNullOrEmpty(c.characterId)) continue;
                    byId[c.characterId] = c;
                }
            }
        }
        CharacterDefinition found;
        return byId.TryGetValue(characterId, out found) ? found : null;
    }

    /// <summary>Position of an id, or -1. Used to restore a saved selection by
    /// IDENTITY and then step from there, never to persist a number.</summary>
    public int IndexOf(string characterId)
    {
        if (characters == null || string.IsNullOrEmpty(characterId)) return -1;
        for (int i = 0; i < characters.Length; i++)
            if (characters[i] != null && characters[i].characterId == characterId) return i;
        return -1;
    }

    public int CountNonNull()
    {
        int n = 0;
        if (characters != null)
        {
            foreach (CharacterDefinition c in characters)
            {
                if (c != null && !string.IsNullOrEmpty(c.characterId)) n++;
            }
        }
        return n;
    }
}
