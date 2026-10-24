using System.Runtime.InteropServices;
using ZombieSurge.Game;

try
{
    GameApp.Run(args);
}
catch (Exception ex)
{
    var log = Path.Combine(LogPaths.LogsDirectory(), "fatal-startup.txt");
    File.WriteAllText(log, ex.ToString());
    NativeMsg.Error("Zombie Surge 3D could not start.\n\n" + ex.Message + "\n\nFull log:\n" + log);
    Environment.Exit(1);
}

internal static class NativeMsg
{
    private const uint MbIconError = 0x10;

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int MessageBox(nint hWnd, string text, string caption, uint type);

    public static void Error(string text) => MessageBox(0, text, "Zombie Surge 3D", MbIconError);
}
