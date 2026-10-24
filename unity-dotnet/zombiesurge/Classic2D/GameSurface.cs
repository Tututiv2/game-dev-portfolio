using System.Windows;
using System.Windows.Media;

namespace ZombieSurge;

public sealed class GameSurface : FrameworkElement
{
    public UndeadRoundsEngine Engine { get; } = new();

    public GameSurface()
    {
        IsHitTestVisible = true;
        UseLayoutRounding = true;
        SnapsToDevicePixels = true;
    }

    protected override void OnRender(DrawingContext dc)
    {
        if (ActualWidth < 1 || ActualHeight < 1)
        {
            return;
        }

        var size = new Size(ActualWidth, ActualHeight);
        Engine.Draw(dc, size);
    }
}
