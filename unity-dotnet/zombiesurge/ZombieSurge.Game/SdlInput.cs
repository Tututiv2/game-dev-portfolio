using System.Runtime.InteropServices;
using Veldrid.Sdl2;

namespace ZombieSurge.Game;

internal static class SdlInput
{
    [DllImport("SDL2", CallingConvention = CallingConvention.Cdecl, EntryPoint = "SDL_RaiseWindow")]
    private static extern void RaiseWindow(IntPtr sdlWindow);

    [DllImport("SDL2", CallingConvention = CallingConvention.Cdecl, EntryPoint = "SDL_SetRelativeMouseMode")]
    private static extern int SetRelativeMouseMode(int enabled);

    /// <summary>SDL relative mode: cursor locked, continuous deltas at window edges (FPS mouselook).</summary>
    public static void SetRelativeMouseLook(Sdl2Window window, bool enabled)
    {
        if (enabled)
        {
            RaiseWindow(window.SdlWindowHandle);
            _ = SetRelativeMouseMode(1);
        }
        else
        {
            _ = SetRelativeMouseMode(0);
        }
    }
}
