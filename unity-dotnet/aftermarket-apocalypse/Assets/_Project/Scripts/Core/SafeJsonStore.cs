using System;
using System.IO;
using UnityEngine;

/// <summary>
/// Crash-safe JSON persistence — the root fix for spec §13.1 defect #24.
///
/// The old CollectionManager did a plain File.WriteAllText and an unguarded
/// JsonUtility.FromJson in Awake. Kill the process mid-write and the next launch
/// had NO items, and would re-issue #1 of a design whose #1 was already minted —
/// permanently breaking the one guarantee the whole economy rests on.
///
/// Every persistent file in this project goes through here:
///   Save  : write to "<file>.tmp", fsync, then atomically replace, keeping "<file>.bak".
///   Load  : try the live file; on ANY failure fall back to the .bak; never throw.
///
/// Nothing in here touches UnityEngine beyond JsonUtility, so it is directly
/// unit-testable in EditMode against a temp directory.
/// </summary>
public static class SafeJsonStore
{
    /// <summary>Set true by Load when it had to fall back to the backup, so callers can warn.</summary>
    public static bool LastLoadUsedBackup { get; private set; }

    public static string PathFor(string fileName)
    {
        return Path.Combine(Application.persistentDataPath, fileName);
    }

    /// <summary>
    /// Atomically persist <paramref name="data"/> as JSON at <paramref name="fullPath"/>.
    /// Returns false (and logs) rather than throwing — a failed save must never
    /// take a running match down with it.
    /// </summary>
    public static bool Save<T>(string fullPath, T data, bool prettyPrint = true)
    {
        try
        {
            string json = JsonUtility.ToJson(data, prettyPrint);
            return SaveRaw(fullPath, json);
        }
        catch (Exception e)
        {
            Debug.LogError("SafeJsonStore.Save failed to serialize " + fullPath + ": " + e);
            return false;
        }
    }

    public static bool SaveRaw(string fullPath, string json)
    {
        string tmp = fullPath + ".tmp";
        string bak = fullPath + ".bak";
        try
        {
            string dir = Path.GetDirectoryName(fullPath);
            if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir)) Directory.CreateDirectory(dir);

            // 1. Full content lands in a scratch file first. A crash here leaves
            //    the live file completely untouched.
            using (FileStream fs = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None))
            using (StreamWriter w = new StreamWriter(fs))
            {
                w.Write(json);
                w.Flush();
                fs.Flush(true);   // push to disk, not just to the OS cache
            }

            // 2. Swap it in. File.Replace is atomic on NTFS and rolls the previous
            //    good copy into .bak in the same operation.
            if (File.Exists(fullPath))
            {
                File.Replace(tmp, fullPath, bak, true);
            }
            else
            {
                File.Move(tmp, fullPath);
            }
            return true;
        }
        catch (Exception e)
        {
            Debug.LogError("SafeJsonStore.SaveRaw failed for " + fullPath + ": " + e);
            try { if (File.Exists(tmp)) File.Delete(tmp); } catch { }
            return false;
        }
    }

    /// <summary>
    /// Load JSON, falling back to the backup if the live file is missing, empty
    /// or corrupt. Returns default(T) only when neither copy is usable.
    /// </summary>
    public static T Load<T>(string fullPath) where T : class
    {
        LastLoadUsedBackup = false;

        T live = TryRead<T>(fullPath);
        if (live != null) return live;

        T backup = TryRead<T>(fullPath + ".bak");
        if (backup != null)
        {
            LastLoadUsedBackup = true;
            Debug.LogWarning("SafeJsonStore: '" + fullPath + "' was unreadable; recovered from .bak.");
            // Re-establish the live file from the backup so the next save has a
            // sane predecessor to roll forward.
            Save(fullPath, backup);
            return backup;
        }

        return null;
    }

    private static T TryRead<T>(string path) where T : class
    {
        try
        {
            if (!File.Exists(path)) return null;
            string json = File.ReadAllText(path);
            if (string.IsNullOrWhiteSpace(json)) return null;
            return JsonUtility.FromJson<T>(json);
        }
        catch (Exception e)
        {
            Debug.LogWarning("SafeJsonStore: could not read '" + path + "': " + e.Message);
            return null;
        }
    }
}
