using System.Numerics;
using Veldrid;
using Veldrid.Sdl2;

namespace ZombieSurge.Game;

/// <summary>Tracks held keys and mouse buttons from <see cref="InputSnapshot"/> (Veldrid NeoDemo pattern).</summary>
public static class InputTracker
{
    private static readonly HashSet<Key> PressedKeys = new();
    private static readonly HashSet<Key> NewKeys = new();
    private static readonly HashSet<MouseButton> PressedMouse = new();
    private static readonly HashSet<MouseButton> NewMouse = new();

    public static Vector2 MouseDelta { get; private set; }

    public static void Step(InputSnapshot snapshot, Sdl2Window window)
    {
        NewKeys.Clear();
        NewMouse.Clear();
        MouseDelta = window.MouseDelta;

        for (var i = 0; i < snapshot.KeyEvents.Count; i++)
        {
            var ke = snapshot.KeyEvents[i];
            if (ke.Down)
            {
                if (PressedKeys.Add(ke.Key))
                {
                    NewKeys.Add(ke.Key);
                }
            }
            else
            {
                PressedKeys.Remove(ke.Key);
            }
        }

        for (var i = 0; i < snapshot.MouseEvents.Count; i++)
        {
            var me = snapshot.MouseEvents[i];
            if (me.Down)
            {
                if (PressedMouse.Add(me.MouseButton))
                {
                    NewMouse.Add(me.MouseButton);
                }
            }
            else
            {
                PressedMouse.Remove(me.MouseButton);
            }
        }
    }

    public static bool Key(Key k) => PressedKeys.Contains(k);

    public static bool KeyDown(Key k) => NewKeys.Contains(k);

    public static bool Mouse(MouseButton b) => PressedMouse.Contains(b);
}
