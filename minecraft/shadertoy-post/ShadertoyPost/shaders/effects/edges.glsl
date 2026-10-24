/* EDGES — cel shading. Flatten the colour into bands and ink the outlines.

   The outlines come from DEPTH, not from colour, so they trace real geometry
   instead of every block texture. This is the one effect here that couldn't
   have come off Shadertoy unchanged — Shadertoy has no depth buffer. */

#if EFFECT == 7

#define mainImage mainImage_EDGES

const float EDGES_BANDS = 5.0;

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec2 px = 1.0 / iResolution.xy;

    /* Work in 1/depth. For ANY flat surface, 1/depth is linear across the
       screen no matter how obliquely you view it — so the second difference is
       zero on a plane and only spikes at a genuine discontinuity. Comparing
       raw depths instead would outline every floor you looked along. */
    float ic = 1.0 / toy_depth(uv);
    float il = 1.0 / toy_depth(uv - vec2(px.x, 0.0));
    float ir = 1.0 / toy_depth(uv + vec2(px.x, 0.0));
    float iu = 1.0 / toy_depth(uv + vec2(0.0, px.y));
    float id = 1.0 / toy_depth(uv - vec2(0.0, px.y));

    float discontinuity = abs((il + ir) * 0.5 - ic)
                        + abs((iu + id) * 0.5 - ic);

    /* Divide by 1/depth to make the threshold scale-free: an edge is an edge
       whether it's two blocks away or two hundred. */
    float e = discontinuity / max(ic, 1e-6);
    e = smoothstep(0.02, 0.14, e);

    /* A little luminance edge on top, so creepers and signs get interior lines
       and not just a silhouette. */
    float lc = toy_luma(SAMPLE(uv).rgb);
    float lx = toy_luma(SAMPLE(uv + vec2(px.x, 0.0)).rgb) - lc;
    float ly = toy_luma(SAMPLE(uv + vec2(0.0, px.y)).rgb) - lc;
    e = max(e, smoothstep(0.10, 0.30, sqrt(lx * lx + ly * ly)) * 0.7);

    /* Band the BRIGHTNESS and leave the hue alone. Quantising r, g and b
       separately moves each by a different amount, which drags colours off
       toward whatever corner of the cube they round into — grass goes olive.
       Scaling the colour by a banded luminance keeps the hue exactly. */
    vec3  col = SAMPLE(uv).rgb;
    float l   = toy_luma(col);
    float band = floor(l * EDGES_BANDS + 0.5) / EDGES_BANDS;
    col *= band / max(l, 0.001);

    /* Ink the outline over the top. */
    col *= 1.0 - e;

    fragColor = vec4(col, 1.0);
}

#undef mainImage

#endif
