/* CRT — curved glass, aperture grille, scanlines, chromatic fringing.
   Shadertoy-style: the body below is ordinary `mainImage` code. */

#if EFFECT == 1

#define mainImage mainImage_CRT

/* Bulge the screen out toward the viewer. */
vec2 crt_curve(vec2 uv) {
    uv = uv * 2.0 - 1.0;
    vec2 offset = abs(uv.yx) / vec2(6.0, 4.5);
    uv += uv * offset * offset;
    return uv * 0.5 + 0.5;
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;
    vec2 c  = crt_curve(uv);

    /* Off the edge of the tube — that's the bezel, and it's black. */
    if (c.x < 0.0 || c.x > 1.0 || c.y < 0.0 || c.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    /* The three guns don't converge perfectly, and they miss by more toward
       the edge of the tube — so the split scales with distance from centre. */
    vec2  dir = c - 0.5;
    float ca  = 0.0035;
    vec3  col;
    col.r = SAMPLE(c + dir * ca).r;
    col.g = SAMPLE(c).g;
    col.b = SAMPLE(c - dir * ca).b;

    /* Scanlines, drifting slowly so the pattern never sits still. */
    float scan = sin(c.y * iResolution.y * 1.6 - iTime * 6.0);
    col *= 0.88 + 0.12 * scan;

    /* Aperture grille: vertical RGB stripes, one per screen pixel triad. */
    float grille = 0.86 + 0.14 * cos(fragCoord.x * 3.14159265);
    col *= grille;

    /* Vignette — bright in the middle, falling off into the corners. */
    float v = 16.0 * c.x * c.y * (1.0 - c.x) * (1.0 - c.y);
    col *= pow(clamp(v, 0.0, 1.0), 0.22);

    /* Phosphor doesn't go fully dark, and it lifts the gamma a touch. */
    col = pow(col, vec3(0.94)) * 1.08;

    fragColor = vec4(col, 1.0);
}

#undef mainImage

#endif
