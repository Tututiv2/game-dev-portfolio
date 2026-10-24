using System.Numerics;
using Veldrid;
using Veldrid.Sdl2;
using Veldrid.StartupUtilities;
using ZombieSurge.Core;

namespace ZombieSurge.Game;

public static class GameApp
{
    private static int s_mouseLookSettleFrames;
    private static bool s_gameplayMouseCapture;

    public static void Run(string[] args)
    {
        _ = args;
        var level = LevelIO.LoadOrDefault();
        var world = new GameWorld3D(level);

        var wci = new WindowCreateInfo
        {
            X = 80,
            Y = 80,
            WindowWidth = 1280,
            WindowHeight = 720,
            WindowTitle = "Zombie Surge 3D",
        };

        Sdl2Native.SDL_Init(SDLInitFlags.Video);
        var window = VeldridStartup.CreateWindow(ref wci);
        var options = new GraphicsDeviceOptions(
            debug: false,
            swapchainDepthFormat: PixelFormat.D24_UNorm_S8_UInt,
            syncToVerticalBlank: true,
            resourceBindingModel: ResourceBindingModel.Improved,
            preferDepthRangeZeroToOne: true,
            preferStandardClipSpaceYDirection: true,
            swapchainSrgbFormat: false);

        // Prefer Vulkan (SPIR-V native). OpenGL uses UTF-8 GLSL in GameRenderer; D3D11 last (SPIR-V→HLSL can mis-compile).
        var gd = CreateGraphicsDeviceWithFallback(window, options);
        var factory = gd.ResourceFactory;
        var cl = factory.CreateCommandList();

        using var renderer = new GameRenderer(gd, cl);

        window.Resized += () =>
        {
            gd.MainSwapchain.Resize((uint)Math.Max(1, window.Width), (uint)Math.Max(1, window.Height));
        };

        // Stopwatch: DateTime.UtcNow only ticks every ~15.6 ms on Windows, which makes dt jitter badly.
        var clock = System.Diagnostics.Stopwatch.StartNew();
        var last = clock.Elapsed;
        window.CursorVisible = true;
        window.Resizable = true;

        while (window.Exists)
        {
            var now = clock.Elapsed;
            var dt = (float)(now - last).TotalSeconds;
            last = now;
            if (dt > 0.05f)
            {
                dt = 0.05f;
            }

            var snapshot = window.PumpEvents();
            InputTracker.Step(snapshot, window);

            if (InputTracker.KeyDown(Key.Escape))
            {
                if (s_gameplayMouseCapture)
                {
                    SdlInput.SetRelativeMouseLook(window, false);
                    s_gameplayMouseCapture = false;
                }

                window.Close();
                continue;
            }

            var altEnterFullscreen = (InputTracker.Key(Key.LAlt) || InputTracker.Key(Key.RAlt)) && InputTracker.KeyDown(Key.Enter);
            if (InputTracker.KeyDown(Key.F11) || altEnterFullscreen)
            {
                window.WindowState = window.WindowState == WindowState.BorderlessFullScreen
                    ? WindowState.Normal
                    : WindowState.BorderlessFullScreen;
            }

            var cap = world.Phase is GamePhase.Combat or GamePhase.WaveBreak;
            window.CursorVisible = !cap;
            if (cap != s_gameplayMouseCapture)
            {
                s_gameplayMouseCapture = cap;
                SdlInput.SetRelativeMouseLook(window, cap);
            }

            var reload = world.Phase == GamePhase.Combat && InputTracker.Key(Key.R);
            var restart = world.Phase == GamePhase.Dead && InputTracker.KeyDown(Key.R);
            var start = (InputTracker.KeyDown(Key.Space) || InputTracker.KeyDown(Key.Enter)) && !altEnterFullscreen;

            var phaseBefore = world.Phase;
            world.Update(
                dt,
                InputTracker.Key(Key.W),
                InputTracker.Key(Key.S),
                InputTracker.Key(Key.A),
                InputTracker.Key(Key.D),
                InputTracker.Mouse(MouseButton.Left),
                reload,
                InputTracker.Key(Key.F),
                start,
                restart);

            if (phaseBefore == GamePhase.Attract && world.Phase != GamePhase.Attract)
            {
                s_mouseLookSettleFrames = 5;
            }

            if (cap)
            {
                var md = InputTracker.MouseDelta;
                if (s_mouseLookSettleFrames > 0)
                {
                    s_mouseLookSettleFrames--;
                    md = Vector2.Zero;
                }

                md.X = Math.Clamp(md.X, -120f, 120f);
                world.AddYaw(md.X * 0.0045f);
            }

            renderer.Render(world);
        }
    }

    private static GraphicsDevice CreateGraphicsDeviceWithFallback(Sdl2Window window, GraphicsDeviceOptions options)
    {
        var errors = new List<string>();
        foreach (var backend in PreferBackends())
        {
            if (!GraphicsDevice.IsBackendSupported(backend))
            {
                continue;
            }

            try
            {
                return VeldridStartup.CreateGraphicsDevice(window, options, backend);
            }
            catch (Exception ex)
            {
                errors.Add($"{backend}: {ex.Message}");
            }
        }

        var logFile = Path.Combine(LogPaths.LogsDirectory(), "graphics-failed.txt");
        File.WriteAllText(logFile, string.Join(Environment.NewLine, errors));
        throw new InvalidOperationException(
            "Could not start graphics. Tried: " + string.Join(", ", errors) + ". Details: " + logFile);
    }

    private static IEnumerable<GraphicsBackend> PreferBackends()
    {
        yield return GraphicsBackend.Vulkan;
        yield return GraphicsBackend.OpenGL;
        yield return GraphicsBackend.Direct3D11;
    }
}
