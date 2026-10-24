using System.Text.Json;
using System.Text.Json.Serialization;

namespace ZombieSurge.Core;

public sealed class LevelBox
{
    public Vec3 Min { get; set; }
    public Vec3 Max { get; set; }
}

public sealed class LevelData
{
    public int Version { get; set; } = 1;

    public Vec3 PlayerStart { get; set; } = new(0f, 0f, 0f);

    public List<LevelBox> Boxes { get; set; } = new();

    /// <summary>XZ spawn points; Y is ignored (ground).</summary>
    public List<Vec3> ZombieSpawns { get; set; } = new();

    [JsonIgnore]
    public static JsonSerializerOptions JsonOptions { get; } = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public static LevelData CreateDefaultArena()
    {
        var level = new LevelData
        {
            PlayerStart = new Vec3(0f, 0f, 0f),
            Boxes =
            {
                new LevelBox { Min = new Vec3(-42f, -1f, -42f), Max = new Vec3(42f, 0f, 42f) },
                new LevelBox { Min = new Vec3(-44f, 0f, -44f), Max = new Vec3(44f, 3f, -42f) },
                new LevelBox { Min = new Vec3(-44f, 0f, 42f), Max = new Vec3(44f, 3f, 44f) },
                new LevelBox { Min = new Vec3(-44f, 0f, -42f), Max = new Vec3(-42f, 3f, 42f) },
                new LevelBox { Min = new Vec3(42f, 0f, -42f), Max = new Vec3(44f, 3f, 42f) },
                new LevelBox { Min = new Vec3(-8f, 0f, -8f), Max = new Vec3(8f, 2.2f, 8f) },
            },
            ZombieSpawns =
            {
                new Vec3(18f, 0f, 18f),
                new Vec3(-18f, 0f, 18f),
                new Vec3(18f, 0f, -18f),
                new Vec3(-18f, 0f, -18f),
                new Vec3(0f, 0f, 22f),
            },
        };
        return level;
    }

    /// <summary>Compact arena for quick playtests (easier to see walls than the huge default).</summary>
    public static LevelData CreateSmallPlaytestArena()
    {
        return new LevelData
        {
            PlayerStart = new Vec3(0f, 0f, 0f),
            Boxes =
            {
                new LevelBox { Min = new Vec3(-14f, -0.5f, -14f), Max = new Vec3(14f, 0f, 14f) },
                new LevelBox { Min = new Vec3(-15f, 0f, -15f), Max = new Vec3(15f, 2.5f, -14f) },
                new LevelBox { Min = new Vec3(-15f, 0f, 14f), Max = new Vec3(15f, 2.5f, 15f) },
                new LevelBox { Min = new Vec3(-15f, 0f, -14f), Max = new Vec3(-14f, 2.5f, 14f) },
                new LevelBox { Min = new Vec3(14f, 0f, -14f), Max = new Vec3(15f, 2.5f, 14f) },
                new LevelBox { Min = new Vec3(-4f, 0f, -4f), Max = new Vec3(4f, 1.8f, 4f) },
            },
            ZombieSpawns =
            {
                new Vec3(10f, 0f, 10f),
                new Vec3(-10f, 0f, 10f),
                new Vec3(10f, 0f, -10f),
                new Vec3(-10f, 0f, -10f),
            },
        };
    }

    /// <summary>Medium neighborhood: streets, building blocks, plaza — good default to iterate on.</summary>
    public static LevelData CreateDistrictMap()
    {
        return new LevelData
        {
            PlayerStart = new Vec3(0f, 0f, 10f),
            Boxes =
            {
                new LevelBox { Min = new Vec3(-38f, -1.2f, -38f), Max = new Vec3(38f, 0f, 38f) },
                new LevelBox { Min = new Vec3(-38f, 0f, -38f), Max = new Vec3(38f, 9f, -30f) },
                new LevelBox { Min = new Vec3(-38f, 0f, 30f), Max = new Vec3(38f, 7f, 38f) },
                new LevelBox { Min = new Vec3(-38f, 0f, -30f), Max = new Vec3(-26f, 6f, -12f) },
                new LevelBox { Min = new Vec3(-38f, 0f, 4f), Max = new Vec3(-26f, 5f, 24f) },
                new LevelBox { Min = new Vec3(26f, 0f, -30f), Max = new Vec3(38f, 7f, -6f) },
                new LevelBox { Min = new Vec3(26f, 0f, 6f), Max = new Vec3(38f, 6f, 30f) },
                new LevelBox { Min = new Vec3(-10f, 0f, -10f), Max = new Vec3(10f, 0.35f, 10f) },
                new LevelBox { Min = new Vec3(-18f, 0f, -22f), Max = new Vec3(-14f, 2.5f, -18f) },
                new LevelBox { Min = new Vec3(14f, 0f, 18f), Max = new Vec3(18f, 2.5f, 22f) },
                new LevelBox { Min = new Vec3(-3f, 0f, -24f), Max = new Vec3(3f, 1.4f, -20f) },
                new LevelBox { Min = new Vec3(-3f, 0f, 20f), Max = new Vec3(3f, 1.4f, 24f) },
            },
            ZombieSpawns =
            {
                new Vec3(24f, 0f, 20f),
                new Vec3(-24f, 0f, 20f),
                new Vec3(24f, 0f, -20f),
                new Vec3(-24f, 0f, -20f),
                new Vec3(0f, 0f, -26f),
                new Vec3(0f, 0f, 26f),
                new Vec3(16f, 0f, 0f),
                new Vec3(-16f, 0f, 0f),
                new Vec3(8f, 0f, -8f),
                new Vec3(-8f, 0f, 8f),
            },
        };
    }
}
