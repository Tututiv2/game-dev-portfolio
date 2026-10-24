/* ============================================================================
   SHADERTOY COMPATIBILITY LAYER  —  Iris / OptiFine
   ----------------------------------------------------------------------------
   Shadertoy hands your shader a small set of globals. Minecraft hands you a
   different set with different names. This file is the adapter: include it and
   Shadertoy code compiles almost verbatim.

   Include it ONCE, from final.fsh, before any effect file.
   ========================================================================== */

/* ---- Minecraft's real uniforms ------------------------------------------ */

uniform sampler2D colortex0;      // the finished frame: world + sky + entities
uniform sampler2D depthtex0;      // depth, including translucents
uniform sampler2D depthtex1;      // depth, opaque geometry only
uniform sampler2D noisetex;       // Iris' built-in 64x64 noise texture

uniform float frameTimeCounter;   // seconds since the pack loaded (WRAPS at 3600)
uniform float frameTime;          // seconds the previous frame took
uniform int   frameCounter;       // frames since load (wraps at 720720)

uniform float viewWidth;
uniform float viewHeight;

uniform float near;               // near plane, 0.05
uniform float far;                // far plane == render distance in blocks

uniform int   worldTime;          // 0..23999, ticks since dawn
uniform float rainStrength;       // 0..1
uniform int   isEyeInWater;       // 0 air, 1 water, 2 lava, 3 powder snow

/* ---- Shadertoy's globals, aliased onto the above ------------------------ */

#define iChannel0 colortex0
#define iChannel1 depthtex0
#define iChannel2 noisetex

#define iResolution vec3(viewWidth, viewHeight, 1.0)
#define iTime       (frameTimeCounter)
#define iTimeDelta  (frameTime)
#define iFrame      (frameCounter)
#define iFrameRate  (1.0 / max(frameTime, 0.0001))

/* Minecraft has no cursor position to give you — Shadertoy shaders that steer
   off iMouse will just sit at their origin. Replace it by hand if you care;
   `vec4(0.5 * iResolution.xy, 0.0, 0.0)` centres most of them. */
#define iMouse vec4(0.0)

/* iDate is (year, month, day, seconds-in-day). Only .w tends to matter, so it
   is wired to Minecraft's own clock: one MC day == 24000 ticks. */
#define iDate vec4(0.0, 0.0, 0.0, float(worldTime) * 3.6)

/* NOTE: `iChannelResolution[0]` cannot be a macro (you can't subscript one).
   Substitute iResolution by hand — every channel here is screen-sized anyway. */

/* ---- Helpers ------------------------------------------------------------ */

/* Sample the game frame with the UV clamped into range.

   Shadertoy's channels default to REPEAT wrapping, so a shader that reads
   slightly off-screen tiles harmlessly. Minecraft's colour buffers clamp, and
   an unclamped read smears the border pixel across the edge. Any effect that
   offsets its UV should sample through this. */
#define SAMPLE(uv) texture(colortex0, clamp((uv), vec2(0.0), vec2(1.0)))

/* Mirror a UV back into 0..1 instead of clamping — the seam is invisible,
   which is what you want for kaleidoscopes and tiling warps. */
vec2 toy_mirror(vec2 uv) {
    return abs(fract(uv * 0.5) * 2.0 - 1.0);
}

/* Raw depth is nonlinear and useless for comparisons. This returns the real
   distance to the fragment, in blocks. */
float toy_depth(vec2 uv) {
    float d = texture(depthtex0, clamp(uv, vec2(0.0), vec2(1.0))).r;
    return (near * far) / (far - d * (far - near));
}

float toy_luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}
