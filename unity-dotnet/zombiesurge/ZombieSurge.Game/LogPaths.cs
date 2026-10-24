namespace ZombieSurge.Game;

internal static class LogPaths
{
    public static string LogsDirectory()
    {
        var d = Path.Combine(AppContext.BaseDirectory, "logs");
        Directory.CreateDirectory(d);
        return d;
    }
}
