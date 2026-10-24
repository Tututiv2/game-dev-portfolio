#version 330 compatibility

/* ============================================================================
   SHADERTOY POST  —  final.fsh
   ----------------------------------------------------------------------------
   `final` is the last pass in the pipeline. Minecraft has already drawn the
   entire world into colortex0; this shader gets that finished frame, does
   whatever it likes to it, and writes the result to the screen.

   That maps almost exactly onto Shadertoy: one fullscreen fragment shader with
   the previous image on iChannel0. Which is why a Shadertoy post-processing
   shader ports over with barely an edit.

   The HUD, hotbar and chat are drawn AFTER this pass, so they stay readable no
   matter how badly the world gets mangled. Screenshots keep the effect.
   ========================================================================== */

/* ---- Options (these appear on the in-game shader config screen) --------- */

#define EFFECT 1 // [0 1 2 3 4 5 6 7 8]

/* How far to blend from the untouched frame toward the effect. */
#define INTENSITY 1.00 // [0.00 0.10 0.20 0.30 0.40 0.50 0.60 0.70 0.80 0.90 1.00]

/* ---- Includes ----------------------------------------------------------- */

#include "/lib/shadertoy.glsl"

/* Each of these compiles only when it is the selected EFFECT. */
#include "/effects/crt.glsl"
#include "/effects/vhs.glsl"
#include "/effects/kaleidoscope.glsl"
#include "/effects/ripple.glsl"
#include "/effects/retro.glsl"
#include "/effects/dreamglow.glsl"
#include "/effects/edges.glsl"
#include "/effects/custom.glsl"

/* ---- Plumbing ----------------------------------------------------------- */

in vec2 texcoord;

/* Named `outColor`, not `fragColor` — Shadertoy's mainImage already uses
   `fragColor` for its out parameter, and shadowing it here would be confusing
   at best. */
layout(location = 0) out vec4 outColor;

void main() {
    vec4 original = texture(colortex0, texcoord);
    vec4 effected = original;

    /* Shadertoy's fragCoord is pixels with the origin bottom-left, and so is
       OpenGL's gl_FragCoord. No flip needed — they already agree. */
#if   EFFECT == 1
    mainImage_CRT(effected, gl_FragCoord.xy);
#elif EFFECT == 2
    mainImage_VHS(effected, gl_FragCoord.xy);
#elif EFFECT == 3
    mainImage_KALEIDO(effected, gl_FragCoord.xy);
#elif EFFECT == 4
    mainImage_RIPPLE(effected, gl_FragCoord.xy);
#elif EFFECT == 5
    mainImage_RETRO(effected, gl_FragCoord.xy);
#elif EFFECT == 6
    mainImage_GLOW(effected, gl_FragCoord.xy);
#elif EFFECT == 7
    mainImage_EDGES(effected, gl_FragCoord.xy);
#elif EFFECT == 8
    mainImage_CUSTOM(effected, gl_FragCoord.xy);
#endif
    /* EFFECT == 0 falls through with effected == original: passthrough. */

    vec3 col = mix(original.rgb, effected.rgb, clamp(INTENSITY, 0.0, 1.0));

    outColor = vec4(col, 1.0);
}
