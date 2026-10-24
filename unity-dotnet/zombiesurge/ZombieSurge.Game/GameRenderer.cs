using System.Numerics;
using System.Runtime.InteropServices;
using System.Text;
using SixLabors.Fonts;
using SixLabors.ImageSharp;
using SixLabors.ImageSharp.Drawing.Processing;
using SixLabors.ImageSharp.PixelFormats;
using SixLabors.ImageSharp.Processing;
using Veldrid;
using Veldrid.SPIRV;
using ZombieSurge.Core;

namespace ZombieSurge.Game;

public sealed class GameRenderer : IDisposable
{
    private readonly GraphicsDevice _gd;
    private readonly CommandList _cl;
    private readonly Pipeline _worldPipeline;
    private readonly Pipeline _hudPipeline;
    private readonly DeviceBuffer _cubeVb;
    private readonly DeviceBuffer _cubeIb;
    private readonly DeviceBuffer _hudVb;
    private readonly DeviceBuffer _hudIb;
    private readonly DeviceBuffer _uniformBuffer;
    private readonly ResourceLayout _worldLayout;
    private readonly ResourceSet _worldResourceSet;
    private readonly ResourceLayout _hudLayout;
    private readonly ResourceSet _hudResourceSet;
    private readonly Texture _hudTexture;
    private readonly TextureView _hudView;
    private readonly Sampler _hudSampler;
    private readonly Image<Rgba32> _hudImage;
    private readonly byte[] _hudBytes;
    private readonly Font _font;
    private readonly uint _indexCount;
    private string? _hudKey;
    private bool _disposed;

    public GameRenderer(GraphicsDevice gd, CommandList cl)
    {
        _gd = gd;
        _cl = cl;
        var factory = gd.ResourceFactory;

        _cubeVb = factory.CreateBuffer(new BufferDescription(CubeVertices.SizeInBytes, BufferUsage.VertexBuffer));
        gd.UpdateBuffer(_cubeVb, 0, CubeVertices.Data);

        _cubeIb = factory.CreateBuffer(new BufferDescription(CubeIndices.BufferSizeInBytes, BufferUsage.IndexBuffer));
        gd.UpdateBuffer(_cubeIb, 0, CubeIndices.Data);
        _indexCount = CubeIndices.Count;

        _uniformBuffer = factory.CreateBuffer(new BufferDescription(128, BufferUsage.UniformBuffer));

        _worldLayout = factory.CreateResourceLayout(new ResourceLayoutDescription(
            new ResourceLayoutElementDescription("Data", ResourceKind.UniformBuffer, ShaderStages.Vertex)));

        var vs = CreateShader(factory, gd, ShaderStages.Vertex, WorldShaders.Vertex, WorldShaders.VertexOpenGL, "world.vert");
        var fs = CreateShader(factory, gd, ShaderStages.Fragment, WorldShaders.Fragment, WorldShaders.Fragment, "world.frag");

        var worldShaderSet = new ShaderSetDescription(
            new[]
            {
                new VertexLayoutDescription(
                    new VertexElementDescription("Position", VertexElementSemantic.Position, VertexElementFormat.Float3)),
            },
            new[] { vs, fs });

        _worldPipeline = factory.CreateGraphicsPipeline(new GraphicsPipelineDescription(
            BlendStateDescription.SingleDisabled,
            DepthStencilStateDescription.DepthOnlyLessEqual,
            new RasterizerStateDescription(FaceCullMode.None, PolygonFillMode.Solid, FrontFace.CounterClockwise, true, false),
            PrimitiveTopology.TriangleList,
            worldShaderSet,
            _worldLayout,
            gd.SwapchainFramebuffer.OutputDescription));

        _worldResourceSet = factory.CreateResourceSet(new ResourceSetDescription(_worldLayout, _uniformBuffer));

        _hudTexture = factory.CreateTexture(TextureDescription.Texture2D(640, 180, 1, 1, PixelFormat.R8_G8_B8_A8_UNorm, TextureUsage.Sampled));
        _hudView = factory.CreateTextureView(_hudTexture);
        _hudSampler = factory.CreateSampler(SamplerDescription.Linear);

        _hudLayout = factory.CreateResourceLayout(new ResourceLayoutDescription(
            new ResourceLayoutElementDescription("Tex", ResourceKind.TextureReadOnly, ShaderStages.Fragment),
            new ResourceLayoutElementDescription("Smp", ResourceKind.Sampler, ShaderStages.Fragment)));

        _hudResourceSet = factory.CreateResourceSet(new ResourceSetDescription(_hudLayout, _hudView, _hudSampler));

        // Vulkan: inverted clip Y → negate fullscreen Y. D3D11: usually not — avoid double flip on the HUD pass.
        var hudVertGlsl = gd.IsClipSpaceYInverted ? HudShaders.VertexBlitVulkan : HudShaders.VertexBlitD3D11;
        var hvs = CreateShader(factory, gd, ShaderStages.Vertex, hudVertGlsl, hudVertGlsl, "hud.vert");
        var hfs = CreateShader(factory, gd, ShaderStages.Fragment, HudShaders.Fragment, HudShaders.FragmentOpenGL, "hud.frag");

        var hudShaderSet = new ShaderSetDescription(
            new[]
            {
                new VertexLayoutDescription(
                    new VertexElementDescription("Position", VertexElementSemantic.Position, VertexElementFormat.Float2),
                    new VertexElementDescription("TexCoord", VertexElementSemantic.TextureCoordinate, VertexElementFormat.Float2)),
            },
            new[] { hvs, hfs });

        _hudPipeline = factory.CreateGraphicsPipeline(new GraphicsPipelineDescription(
            BlendStateDescription.SingleAlphaBlend,
            DepthStencilStateDescription.Disabled,
            new RasterizerStateDescription(FaceCullMode.None, PolygonFillMode.Solid, FrontFace.CounterClockwise, true, false),
            PrimitiveTopology.TriangleList,
            hudShaderSet,
            _hudLayout,
            gd.SwapchainFramebuffer.OutputDescription));

        HudVertex[] hudVerts =
        [
            new(new Vector2(-1f, -1f), new Vector2(0f, 0f)),
            new(new Vector2(1f, -1f), new Vector2(1f, 0f)),
            new(new Vector2(1f, 1f), new Vector2(1f, 1f)),
            new(new Vector2(-1f, 1f), new Vector2(0f, 1f)),
        ];
        ReadOnlySpan<ushort> hudIdx = stackalloc ushort[] { 0, 1, 2, 0, 2, 3 };

        _hudVb = factory.CreateBuffer(new BufferDescription((uint)(Marshal.SizeOf<HudVertex>() * 4), BufferUsage.VertexBuffer));
        _hudIb = factory.CreateBuffer(new BufferDescription(sizeof(ushort) * 6u, BufferUsage.IndexBuffer));
        gd.UpdateBuffer(_hudVb, 0, hudVerts);
        gd.UpdateBuffer(_hudIb, 0, hudIdx);

        _hudImage = new Image<Rgba32>(640, 180);
        _hudBytes = new byte[640 * 180 * 4];
        _font = SystemFonts.CreateFont("Segoe UI", 22f)
                ?? SystemFonts.CreateFont("Arial", 22f)
                ?? throw new InvalidOperationException("No system font available for HUD.");
    }

    private static Shader CreateShader(
        ResourceFactory factory,
        GraphicsDevice gd,
        ShaderStages stage,
        string glslVulkan,
        string glslOpenGL,
        string spirvDebugName)
    {
        if (gd.BackendType is GraphicsBackend.OpenGL or GraphicsBackend.OpenGLES)
        {
            return factory.CreateShader(new ShaderDescription(stage, Encoding.UTF8.GetBytes(glslOpenGL), "main"));
        }

        var spv = SpirvCompilation.CompileGlslToSpirv(glslVulkan, spirvDebugName, stage, new GlslCompileOptions());
        return factory.CreateShader(new ShaderDescription(stage, spv.SpirvBytes, "main"));
    }

    public void Render(GameWorld3D world)
    {
        var fb = _gd.SwapchainFramebuffer;
        var pw = Math.Max(1u, fb.Width);
        var ph = Math.Max(1u, fb.Height);
        var aspect = pw / (float)ph;
        var fov = 1.05f;
        var proj = Matrix4x4.CreatePerspectiveFieldOfView(fov, aspect, 0.1f, 250f);
        if (_gd.IsClipSpaceYInverted)
        {
            proj.M22 *= -1f;
        }

        var (eye, target) = world.CameraRig(distance: 4.35f, height: 1.55f, pivotHeight: 1.15f);
        var view = Matrix4x4.CreateLookAt(eye, target, Vector3.UnitY);

        RebuildHud(world);

        _cl.Begin();
        _cl.SetFramebuffer(fb);
        _cl.SetFullViewports();
        _cl.SetFullScissorRects();
        _cl.ClearColorTarget(0, new RgbaFloat(0.12f, 0.14f, 0.2f, 1f));
        _cl.ClearDepthStencil(1f);
        _cl.SetPipeline(_worldPipeline);
        _cl.SetGraphicsResourceSet(0, _worldResourceSet);
        _cl.SetVertexBuffer(0, _cubeVb);
        _cl.SetIndexBuffer(_cubeIb, IndexFormat.UInt16);

        foreach (var b in world.Level.Boxes)
        {
            DrawBox(b.Min.ToVector3(), b.Max.ToVector3(), new RgbaFloat(0.22f, 0.24f, 0.26f, 1f), view, proj);
        }

        DrawBox(
            min: world.PlayerPosition + new Vector3(-0.35f, 0f, -0.35f),
            max: world.PlayerPosition + new Vector3(0.35f, 1.78f, 0.35f),
            color: new RgbaFloat(0.25f, 0.45f, 0.95f, 1f),
            view,
            proj);

        foreach (var z in world.Zombies)
        {
            DrawBox(
                z.Position + new Vector3(-0.4f, 0f, -0.4f),
                z.Position + new Vector3(0.4f, 1.65f, 0.4f),
                new RgbaFloat(0.35f, 0.62f, 0.28f, 1f),
                view,
                proj);
        }

        _cl.SetPipeline(_hudPipeline);
        _cl.SetGraphicsResourceSet(0, _hudResourceSet);
        _cl.SetVertexBuffer(0, _hudVb);
        _cl.SetIndexBuffer(_hudIb, IndexFormat.UInt16);
        _cl.DrawIndexed(6, 1, 0, 0, 0);

        _cl.End();
        _gd.SubmitCommands(_cl);
        _gd.SwapBuffers(_gd.MainSwapchain);
    }

    private void DrawBox(Vector3 min, Vector3 max, RgbaFloat color, Matrix4x4 view, Matrix4x4 proj)
    {
        var c = (min + max) * 0.5f;
        var e = max - min;
        // Row-vector convention (System.Numerics): scale first, then translate.
        var world = Matrix4x4.Multiply(Matrix4x4.CreateScale(e), Matrix4x4.CreateTranslation(c));
        var mvp = Matrix4x4.Multiply(Matrix4x4.Multiply(world, view), proj);
        var u = new WorldUniforms
        {
            MVP = Matrix4x4.Transpose(mvp),
            Color = new Vector4(color.R, color.G, color.B, color.A),
        };
        // Record the update into the command list so it executes in order with the draws.
        // (GraphicsDevice.UpdateBuffer runs immediately, so every draw would see only the last value.)
        _cl.UpdateBuffer(_uniformBuffer, 0, ref u);
        _cl.DrawIndexed(_indexCount, 1, 0, 0, 0);
    }

    private void RebuildHud(GameWorld3D w)
    {
        // Only re-render + re-upload the HUD texture when the visible values change
        // (ImageSharp text rendering every frame is expensive and usually redundant).
        var key = w.Phase switch
        {
            GamePhase.Attract => "attract",
            GamePhase.Dead => $"dead|{w.Round}|{w.Points}",
            GamePhase.WaveBreak =>
                $"break|{w.Points}|{w.Round}|{w.Hp}|{w.Reloading}|{w.AmmoMag}|{w.AmmoPool}|{w.FlashUi > 0f}|{GameBalance.WaveBreakSeconds - w.WaveBreakT:0.0}",
            _ => $"combat|{w.Points}|{w.Round}|{w.Hp}|{w.Reloading}|{w.AmmoMag}|{w.AmmoPool}|{w.FlashUi > 0f}",
        };
        if (key == _hudKey)
        {
            return;
        }

        _hudKey = key;

        var attractBg = new Rgba32(10, 7, 9, 255);
        _hudImage.ProcessPixelRows(accessor =>
        {
            var fill = w.Phase == GamePhase.Attract ? attractBg : default(Rgba32);
            for (var y = 0; y < accessor.Height; y++)
            {
                accessor.GetRowSpan(y).Fill(fill);
            }
        });

        _hudImage.Mutate(ctx =>
        {
            if (w.Phase == GamePhase.Attract)
            {
                ctx.DrawText("ZOMBIE SURGE  —  3D", _font, Color.FromRgb(245, 240, 232), new SixLabors.ImageSharp.PointF(14f, 10f));
                ctx.DrawText("WASD move   Mouse look   LMB fire   R reload   F knife", _font, Color.FromRgb(200, 194, 186), new SixLabors.ImageSharp.PointF(14f, 46f));
                ctx.DrawText("SPACE / ENTER start   F11 / Alt+Enter fullscreen   ESC quit", _font, Color.FromRgb(200, 194, 186), new SixLabors.ImageSharp.PointF(14f, 78f));
                ctx.DrawText("Edit with ZombieSurgeEditor.exe -> Content/levels/default.json", _font, Color.FromRgb(154, 147, 124), new SixLabors.ImageSharp.PointF(14f, 120f));
            }
            else if (w.Phase == GamePhase.Dead)
            {
                ctx.DrawText("MIA", _font, Color.FromRgb(255, 107, 74), new SixLabors.ImageSharp.PointF(14f, 10f));
                ctx.DrawText($"Round {w.Round}    Points {w.Points}", _font, Color.FromRgb(238, 232, 221), new SixLabors.ImageSharp.PointF(14f, 46f));
                ctx.DrawText("Press R for title", _font, Color.FromRgb(200, 194, 186), new SixLabors.ImageSharp.PointF(14f, 78f));
            }
            else
            {
                var col = w.FlashUi > 0f ? Color.FromRgb(255, 176, 32) : Color.FromRgb(255, 154, 31);
                ctx.DrawText($"POINTS  {w.Points}", _font, col, new SixLabors.ImageSharp.PointF(14f, 10f));
                var roundLabel = w.Phase == GamePhase.WaveBreak
                    ? $"GET READY  R{w.Round}  ({(GameBalance.WaveBreakSeconds - w.WaveBreakT):0.0}s)"
                    : $"ROUND  {w.Round}";
                ctx.DrawText(roundLabel, _font, Color.FromRgb(255, 140, 66), new SixLabors.ImageSharp.PointF(420f, 10f));
                ctx.DrawText($"HP  {w.Hp}", _font, Color.FromRgb(225, 77, 77), new SixLabors.ImageSharp.PointF(14f, 50f));
                var ammo = w.Reloading ? "RELOAD…" : $"{w.AmmoMag} / {w.AmmoPool}";
                ctx.DrawText(ammo, _font, Color.FromRgb(184, 242, 195), new SixLabors.ImageSharp.PointF(420f, 50f));
            }
        });

        _hudImage.CopyPixelDataTo(_hudBytes);
        _gd.UpdateTexture(_hudTexture, _hudBytes, 0, 0, 0, 640, 180, 1, 0, 0);
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _cubeVb.Dispose();
        _cubeIb.Dispose();
        _hudVb.Dispose();
        _hudIb.Dispose();
        _uniformBuffer.Dispose();
        _worldResourceSet.Dispose();
        _worldLayout.Dispose();
        _worldPipeline.Dispose();
        _hudLayout.Dispose();
        _hudResourceSet.Dispose();
        _hudView.Dispose();
        _hudTexture.Dispose();
        _hudSampler.Dispose();
        _hudPipeline.Dispose();
        _hudImage.Dispose();
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WorldUniforms
    {
        public Matrix4x4 MVP;
        public Vector4 Color;
    }

    [StructLayout(LayoutKind.Sequential)]
    private readonly struct HudVertex
    {
        public readonly Vector2 Position;
        public readonly Vector2 TexCoord;

        public HudVertex(Vector2 position, Vector2 texCoord)
        {
            Position = position;
            TexCoord = texCoord;
        }
    }

    private static class WorldShaders
    {
        public const string Vertex = """
#version 450

layout(set = 0, binding = 0) uniform Data {
    mat4 MVP;
    vec4 ModelColor;
};

layout(location = 0) in vec3 Position;
layout(location = 0) out vec4 fsin_Color;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);
    fsin_Color = ModelColor;
}
""";

        public const string VertexOpenGL = """
#version 450 core
#extension GL_KHR_vulkan_glsl : enable

layout(set = 0, binding = 0) uniform Data {
    mat4 MVP;
    vec4 ModelColor;
};

layout(location = 0) in vec3 Position;
layout(location = 0) out vec4 fsin_Color;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);
    fsin_Color = ModelColor;
}
""";

        public const string Fragment = """
#version 450

layout(location = 0) in vec4 fsin_Color;
layout(location = 0) out vec4 fsout_Color;

void main() {
    fsout_Color = fsin_Color;
}
""";
    }

    private static class HudShaders
    {
        public const string VertexBlitVulkan = """
#version 450

layout(location = 0) in vec2 Position;
layout(location = 1) in vec2 TexCoord;
layout(location = 0) out vec2 fsin_tc;

void main() {
    gl_Position = vec4(Position.x, -Position.y, 0.0, 1.0);
    fsin_tc = vec2(TexCoord.x, 1.0 - TexCoord.y);
}
""";

        public const string VertexBlitD3D11 = """
#version 450

layout(location = 0) in vec2 Position;
layout(location = 1) in vec2 TexCoord;
layout(location = 0) out vec2 fsin_tc;

void main() {
    gl_Position = vec4(Position, 0.0, 1.0);
    fsin_tc = vec2(TexCoord.x, 1.0 - TexCoord.y);
}
""";

        public const string Fragment = """
#version 450

layout(set = 0, binding = 0) uniform texture2D Tex;
layout(set = 0, binding = 1) uniform sampler Smp;
layout(location = 0) in vec2 fsin_tc;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(sampler2D(Tex, Smp), fsin_tc);
}
""";

        public const string FragmentOpenGL = """
#version 450 core
#extension GL_KHR_vulkan_glsl : enable

layout(set = 0, binding = 0) uniform texture2D Tex;
layout(set = 0, binding = 1) uniform sampler Smp;
layout(location = 0) in vec2 fsin_tc;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(sampler2D(Tex, Smp), fsin_tc);
}
""";
    }

    private static class CubeVertices
    {
        public const uint SizeInBytes = 24 * 12;

        public static readonly Vector3[] Data =
        {
            new(-0.5f, -0.5f, 0.5f), new(0.5f, -0.5f, 0.5f), new(0.5f, 0.5f, 0.5f), new(-0.5f, 0.5f, 0.5f),
            new(0.5f, -0.5f, -0.5f), new(-0.5f, -0.5f, -0.5f), new(-0.5f, 0.5f, -0.5f), new(0.5f, 0.5f, -0.5f),
            new(-0.5f, -0.5f, -0.5f), new(-0.5f, -0.5f, 0.5f), new(-0.5f, 0.5f, 0.5f), new(-0.5f, 0.5f, -0.5f),
            new(0.5f, -0.5f, 0.5f), new(0.5f, -0.5f, -0.5f), new(0.5f, 0.5f, -0.5f), new(0.5f, 0.5f, 0.5f),
            new(-0.5f, -0.5f, -0.5f), new(0.5f, -0.5f, -0.5f), new(0.5f, -0.5f, 0.5f), new(-0.5f, -0.5f, 0.5f),
            new(-0.5f, 0.5f, 0.5f), new(0.5f, 0.5f, 0.5f), new(0.5f, 0.5f, -0.5f), new(-0.5f, 0.5f, -0.5f),
        };
    }

    private static class CubeIndices
    {
        public const uint Count = 36;
        public const uint BufferSizeInBytes = 36 * sizeof(ushort);

        public static readonly ushort[] Data =
        {
            0, 1, 2, 0, 2, 3,
            4, 5, 6, 4, 6, 7,
            8, 9, 10, 8, 10, 11,
            12, 13, 14, 12, 14, 15,
            16, 17, 18, 16, 18, 19,
            20, 21, 22, 20, 22, 23,
        };
    }
}
