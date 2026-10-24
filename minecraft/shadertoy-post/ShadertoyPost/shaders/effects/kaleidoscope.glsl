/* KALEIDOSCOPE — fold the screen into N mirrored wedges around the crosshair,
   rotating slowly. Everything you look at becomes a mandala. */

#if EFFECT == 3

#define mainImage mainImage_KALEIDO

const float KALEIDO_SIDES = 6.0;

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    /* Centre the coordinate and make it aspect-correct, so the wedges are
       actually equal angles and not squashed by a widescreen monitor. */
    vec2 p = (fragCoord - 0.5 * iResolution.xy) / iResolution.y;

    float a = atan(p.y, p.x);
    float r = length(p);

    /* Fold the full circle into one wedge, then mirror that wedge. Rotating
       the fold (rather than the image) is what makes it swirl. */
    float seg = 6.28318530718 / KALEIDO_SIDES;
    a = mod(a + iTime * 0.06, seg);
    a = abs(a - seg * 0.5);

    /* Back to a screen UV. p was divided by resolution.y, so undo exactly that
       and re-centre. */
    vec2 folded = vec2(cos(a), sin(a)) * r;
    vec2 suv = folded * (iResolution.y / iResolution.xy) + 0.5;

    /* Mirror rather than clamp: the wedge reaches past the frame at the
       corners, and a mirrored seam is invisible where a clamped one smears. */
    suv = toy_mirror(suv);

    vec3 col = SAMPLE(suv).rgb;

    /* Darken the very centre so the fold point doesn't read as a hot pixel. */
    col *= smoothstep(0.0, 0.02, r);

    fragColor = vec4(col, 1.0);
}

#undef mainImage

#endif
