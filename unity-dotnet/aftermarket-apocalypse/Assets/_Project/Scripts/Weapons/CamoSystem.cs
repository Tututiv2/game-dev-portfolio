using UnityEngine;

/// <summary>
/// Per-weapon camo progression and application (spec §4.3), saved to PlayerPrefs
/// keyed by weaponName.
///
/// Spec §13.10 / trap #11 fix: ApplyCamo used to be PRIVATE and called ONLY from
/// Awake, so equipping a camo required a scene reload. It is public here and
/// safe to call at any moment on a live object. That is not a nicety — spec
/// §15.12.3's live 3D store preview and §15.11's ThemeSystem both depend on
/// materials being changeable while the game is running.
///
/// The old ApplyCamo also leaked a material instance per renderer per call
/// (it assigned to .material). This version assigns sharedMaterial and keeps the
/// original array so a camo can be removed again without a reload.
/// </summary>
public class CamoSystem : MonoBehaviour
{
    [Tooltip("14 camo materials in CamoProgression.Names order.")]
    public Material[] camoMaterials = new Material[CamoProgression.Count];

    private string weaponName = "Weapon";
    private Renderer[] renderers;
    private Material[][] originalMaterials;

    // rapid-kill streak tracking
    private int rapidKillCount;
    private float lastKillTime = -999f;

    public int EquippedIndex
    {
        get { return PlayerPrefs.GetInt(CamoProgression.EquippedKey(weaponName), -1); }
    }

    void Awake()
    {
        GunSystem gun = GetComponent<GunSystem>();
        if (gun != null && !string.IsNullOrEmpty(gun.weaponName)) weaponName = gun.weaponName;

        CacheRenderers();
        ApplyCamo(EquippedIndex);
    }

    void CacheRenderers()
    {
        Renderer[] all = GetComponentsInChildren<Renderer>(true);
        var keep = new System.Collections.Generic.List<Renderer>();
        foreach (Renderer r in all)
        {
            // Skipped, exactly as the old build did: muzzle flashes, particles,
            // tracers and text must not be re-skinned.
            if (r is ParticleSystemRenderer) continue;
            if (r is LineRenderer) continue;
            if (r.GetComponent<TMPro.TMP_Text>() != null) continue;   // world-space labels
            string n = r.gameObject.name;
            if (n.Contains("Flash") || n.Contains("Muzzle")) continue;
            keep.Add(r);
        }
        renderers = keep.ToArray();

        originalMaterials = new Material[renderers.Length][];
        for (int i = 0; i < renderers.Length; i++)
            originalMaterials[i] = renderers[i].sharedMaterials;
    }

    /// <summary>
    /// Apply a camo by index, or -1 to restore the weapon's original materials.
    /// PUBLIC and live — no scene reload required.
    /// </summary>
    public void ApplyCamo(int index)
    {
        if (renderers == null) CacheRenderers();

        if (index < 0 || index >= camoMaterials.Length || camoMaterials[index] == null)
        {
            for (int i = 0; i < renderers.Length; i++)
                if (renderers[i] != null) renderers[i].sharedMaterials = originalMaterials[i];
            return;
        }

        Material camo = camoMaterials[index];
        for (int i = 0; i < renderers.Length; i++)
        {
            if (renderers[i] == null) continue;
            int slots = originalMaterials[i].Length;
            Material[] mats = new Material[slots];
            for (int s = 0; s < slots; s++) mats[s] = camo;
            renderers[i].sharedMaterials = mats;
        }
    }

    /// <summary>Equip and persist. Live — the gun changes immediately.</summary>
    public void EquipCamo(int index)
    {
        PlayerPrefs.SetInt(CamoProgression.EquippedKey(weaponName), index);
        PlayerPrefs.Save();
        ApplyCamo(index);
    }

    public bool IsUnlocked(int index)
    {
        return PlayerPrefs.GetInt(CamoProgression.CamoKey(weaponName, index), 0) == 1;
    }

    public void Unlock(int index)
    {
        if (index < 0 || index >= CamoProgression.Count) return;
        if (IsUnlocked(index)) return;
        PlayerPrefs.SetInt(CamoProgression.CamoKey(weaponName, index), 1);
        PlayerPrefs.Save();
        if (PowerUpManager.instance != null)
            PowerUpManager.instance.Announce("CAMO UNLOCKED — " + CamoProgression.Names[index]);
    }

    /// <summary>Called by GunSystem on every kill this weapon lands.</summary>
    public void RegisterKill(bool wasHeadshot)
    {
        int headshots = PlayerPrefs.GetInt(CamoProgression.HeadshotKey(weaponName), 0);

        if (wasHeadshot && CamoProgression.HeadshotsStillCount(headshots))
        {
            headshots++;
            PlayerPrefs.SetInt(CamoProgression.HeadshotKey(weaponName), headshots);
            int earned = CamoProgression.Stage1UnlockCount(headshots);
            for (int i = 0; i < earned; i++) Unlock(i);
        }

        // Rapid-kill streaks: 10 kills, no more than 5 s apart.
        if (Time.time - lastKillTime > CamoProgression.RapidKillWindow) rapidKillCount = 0;
        lastKillTime = Time.time;
        rapidKillCount++;

        if (rapidKillCount >= CamoProgression.RapidKillsPerStreak)
        {
            rapidKillCount = 0;
            if (CamoProgression.StreaksStillCount(headshots))
            {
                int streaks = PlayerPrefs.GetInt(CamoProgression.RapidKillKey(weaponName), 0) + 1;
                PlayerPrefs.SetInt(CamoProgression.RapidKillKey(weaponName), streaks);
                int earned = CamoProgression.Stage2UnlockCount(streaks);
                for (int i = 0; i < earned; i++) Unlock(10 + i);
            }
        }

        PlayerPrefs.Save();
    }
}
