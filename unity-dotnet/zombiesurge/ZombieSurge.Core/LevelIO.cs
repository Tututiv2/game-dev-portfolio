using System.Text.Json;

namespace ZombieSurge.Core;

public static class LevelIO
{
    public static string ContentLevelsDirectory() =>
        Path.Combine(AppContext.BaseDirectory, "Content", "levels");

    public static string DefaultLevelPath() =>
        Path.Combine(ContentLevelsDirectory(), "default.json");

    public static LevelData LoadOrDefault(string? path = null)
    {
        path ??= DefaultLevelPath();
        try
        {
            if (File.Exists(path))
            {
                var json = File.ReadAllText(path);
                var level = JsonSerializer.Deserialize<LevelData>(json, LevelData.JsonOptions);
                if (level is not null)
                {
                    return level;
                }
            }
        }
        catch
        {
            // fall through
        }

        return LevelData.CreateDefaultArena();
    }

    public static void Save(LevelData level, string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var json = JsonSerializer.Serialize(level, LevelData.JsonOptions);
        File.WriteAllText(path, json);
    }
}
