/* DREAM GLOW — bloom. Only the bright parts of the frame are smeared, then
   added back, then rolled off so they glow instead of clipping to white.
   Torches, lava, glowstone and the sun all bleed light into the air. */

#if EFFECT == 6

#define mainImage mainImage_GLOW

const int   GLOW_TAPS     = 16;
const float GLOW_RADIUS   = 0.014;  // in screen heights
const float GLOW_STRENGTH = 2.2;

/* Where "bright" starts. This has to sit ABOVE the daytime sky or the sky
   itself blooms and the whole frame washes out to white — colortex0 here is
   low dynamic range (0..1), so there is no headroom above it to hide in. */
const float GLOW_THRESHOLD = 0.88;

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv   = fragCoord / iResolution.xy;
    vec3 base = SAMPLE(uv).rgb;

    /* Spiral sampling on the golden angle: every tap lands in a fresh gap, so
       16 of them cover the disc about as evenly as a much larger grid. */
    float aspect = iResolution.y / iResolution.x;
    vec3  glow   = vec3(0.0);

    for (int i = 0; i < GLOW_TAPS; i++) {
        float f   = float(i);
        float ang = f * 2.39996323;
        float rad = sqrt((f + 0.5) / float(GLOW_TAPS)) * GLOW_RADIUS;

        vec2 o = vec2(cos(ang) * aspect, sin(ang)) * rad;
        vec3 s = SAMPLE(uv + o).rgb;

        /* Weight by how bright the sample is — dark pixels contribute nothing,
           which is the difference between bloom and blur. */
        glow += s * smoothstep(GLOW_THRESHOLD, 1.05, toy_luma(s));
    }
    glow /= float(GLOW_TAPS);

    /* Add, don't tonemap. A global curve here would lift every midtone in the
       frame — which reads as "someone turned the brightness up", not as glow.
       Only the halo should be new light. */
    vec3 col = base + glow * GLOW_STRENGTH;

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}

#undef mainImage

#endif
