using System;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace AudioCapture.Recording;

/// <summary>
/// Decides where a take lands and writes its transcript. Kept free of any UI reference so the
/// numbering — the easiest thing here to get subtly wrong — can be tested directly.
/// </summary>
public static class OutputWriter
{
    private static readonly UTF8Encoding Utf8NoBom = new(false);

    public static string SanitizePrefix(string raw)
    {
        var cleaned = Regex.Replace(raw.Trim(), @"[^A-Za-z0-9_\-]", "_");
        return cleaned.Length == 0 ? "voice" : cleaned;
    }

    public static string CleanTranscript(string raw) => Regex.Replace(raw.Trim(), @"\s+", " ");

    /// <summary>
    /// Dataset mode numbers takes into <c>wavs/</c>; otherwise each take is timestamped in place.
    /// </summary>
    public static (string path, string id) NextOutputPath(string folder, string rawPrefix, bool dataset, DateTime now)
    {
        string prefix = SanitizePrefix(rawPrefix);

        if (dataset)
        {
            string wavs = Path.Combine(folder, "wavs");
            Directory.CreateDirectory(wavs);

            int next = 1;
            foreach (var f in Directory.EnumerateFiles(wavs, prefix + "_*.wav"))
            {
                string stem = Path.GetFileNameWithoutExtension(f);
                if (stem.Length <= prefix.Length + 1) continue;
                // Anything that is not a plain number (a stray timestamped take, say) is ignored.
                if (int.TryParse(stem[(prefix.Length + 1)..], out int n) && n >= next) next = n + 1;
            }

            string dsId = $"{prefix}_{next:D4}";
            return (Path.Combine(wavs, dsId + ".wav"), dsId);
        }

        Directory.CreateDirectory(folder);
        string stamp = $"{prefix}_{now:yyyyMMdd_HHmmss}";
        string path = Path.Combine(folder, stamp + ".wav");
        for (int dup = 2; File.Exists(path); dup++)
            path = Path.Combine(folder, $"{stamp}_{dup}.wav");

        return (path, Path.GetFileNameWithoutExtension(path));
    }

    /// <summary>
    /// Renames a take, carrying its transcript sidecar with it. Never overwrites — an existing name
    /// gets a numeric suffix, because losing a take to a name collision is worse than an ugly name.
    /// </summary>
    public static string RenameTake(string wavPath, string rawStem)
    {
        string stem = SanitizePrefix(rawStem);
        string dir = Path.GetDirectoryName(wavPath)!;

        if (string.Equals(stem, Path.GetFileNameWithoutExtension(wavPath), StringComparison.Ordinal))
            return wavPath;

        string target = Path.Combine(dir, stem + ".wav");
        for (int dup = 2; File.Exists(target); dup++)
            target = Path.Combine(dir, $"{stem}_{dup}.wav");

        File.Move(wavPath, target);

        string oldTxt = Path.ChangeExtension(wavPath, ".txt");
        if (File.Exists(oldTxt)) File.Move(oldTxt, Path.ChangeExtension(target, ".txt"));

        return target;
    }

    /// <summary>Writes the transcript and returns a short note for the status line.</summary>
    public static string WriteTranscript(string folder, string wavPath, string id, string rawTranscript, bool dataset)
    {
        string text = CleanTranscript(rawTranscript);

        if (dataset)
        {
            // A row with no text would be a broken dataset entry, so skip it rather than write it.
            if (text.Length == 0) return "no transcript, metadata row skipped";
            File.AppendAllText(Path.Combine(folder, "metadata.csv"), $"{id}|{text}|{text}\n", Utf8NoBom);
            return "metadata.csv updated";
        }

        if (text.Length == 0) return "";
        File.WriteAllText(Path.ChangeExtension(wavPath, ".txt"), text, Utf8NoBom);
        return "transcript saved";
    }
}
