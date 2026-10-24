using System.Collections.Generic;
using System.Numerics;
using System.Windows;
using System.Windows.Input;
using System.Windows.Threading;

namespace ZombieSurge;

public partial class MainWindow : Window
{
    private readonly HashSet<Key> _keys = new();
    private readonly DispatcherTimer _loop;
    private readonly System.Diagnostics.Stopwatch _clock = new();
    private TimeSpan _lastTick;
    private bool _isMouseLeftDown;
    private const float TickSeconds = 1f / 60f;
    private const float MaxDeltaSeconds = 0.05f;

    public MainWindow()
    {
        InitializeComponent();
        _loop = new DispatcherTimer(DispatcherPriority.Render) { Interval = TimeSpan.FromSeconds(TickSeconds) };
        _loop.Tick += GameTick;

        // KeyUp events are lost when focus leaves the window (alt-tab while holding a key),
        // which would leave movement/fire keys latched forever.
        Deactivated += (_, _) =>
        {
            _keys.Clear();
            _isMouseLeftDown = false;
        };
    }

    private void Window_OnLoaded(object sender, RoutedEventArgs e)
    {
        Focus();
        _clock.Restart();
        _lastTick = _clock.Elapsed;
        _loop.Start();
    }

    private void GameTick(object? sender, EventArgs e)
    {
        // DispatcherTimer gives no rate guarantee; measure real elapsed time so the
        // simulation stays time-correct instead of slowing down when ticks are late.
        var now = _clock.Elapsed;
        var dt = (float)(now - _lastTick).TotalSeconds;
        _lastTick = now;
        dt = Math.Clamp(dt, 0f, MaxDeltaSeconds);

        var w = (float)GameRoot.ActualWidth;
        var h = (float)GameRoot.ActualHeight;
        if (w < 1f || h < 1f)
        {
            return;
        }

        var p = Mouse.GetPosition(GameRoot);
        var left = _isMouseLeftDown || (Mouse.LeftButton == MouseButtonState.Pressed);

        var input = new GameInput
        {
            ViewSize = new Vector2(w, h),
            AimX = (float)p.X,
            AimY = (float)p.Y,
            Up = _keys.Contains(Key.W),
            Down = _keys.Contains(Key.S),
            Left = _keys.Contains(Key.A),
            Right = _keys.Contains(Key.D),
            Fire = left,
            Reload = _keys.Contains(Key.R),
            Knife = _keys.Contains(Key.F),
            Start = _keys.Contains(Key.Space),
            Restart = _keys.Contains(Key.R)
        };

        GameRoot.Engine.Update(dt, input);
        GameRoot.InvalidateVisual();
    }

    private void Window_OnKeyDown(object sender, KeyEventArgs e)
    {
        _keys.Add(e.Key);
    }

    private void Window_OnKeyUp(object sender, KeyEventArgs e)
    {
        _keys.Remove(e.Key);
    }

    private void Window_OnMouseMove(object sender, MouseEventArgs e)
    {
    }

    private void Window_OnMouseButton(object sender, MouseButtonEventArgs e)
    {
        _isMouseLeftDown = e.LeftButton == MouseButtonState.Pressed;
    }
}
