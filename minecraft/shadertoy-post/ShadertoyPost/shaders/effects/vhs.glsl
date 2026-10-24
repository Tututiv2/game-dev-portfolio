/* VHS — tape wobble, a tracking band crawling up the screen, chroma bleed,
   luminance noise, washed-out colour. */

#if EFFECT == 2

#define mainImage mainImage_VHS

float vhs_hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

float vhs_noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(vhs_hash(i),                vhs_hash(i + vec2(1.0, 0.0)), f.x),
               mix(vhs_hash(i + vec2(0.0,1.0)), vhs_hash(i + vec2(1.0, 1.0)), f.x), f.y);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;

    /* Tape wobble: a slow horizontal jitter that varies down the screen. */
    float wobble = vhs_noise(vec2(uv.y * 40.0, iTime * 3.0)) - 0.5;
    uv.x += wobble * 0.005;

    /* A tracking band crawling upward. Inside it the head loses lock, so the
       line tears sideways by a random amount that changes every row. */
    float band = fract(uv.y + iTime * 0.15);
    float tear = smoothstep(0.955, 1.0, band) * 0.04;
    uv.x += tear * (vhs_hash(vec2(floor(iTime * 50.0), floor(uv.y * 120.0))) - 0.5);

    /* Chroma bleeds sideways off luma, and bleeds much worse inside the tear. */
    float shift = 0.0035 + tear * 0.6;
    vec3 col;
    col.r = SAMPLE(uv + vec2(shift, 0.0)).r;
    col.g = SAMPLE(uv).g;
    col.b = SAMPLE(uv - vec2(shift, 0.0)).b;

    /* Head noise, and the interlace pattern under it. */
    col += (vhs_hash(fragCoord + fract(iTime) * 100.0) - 0.5) * 0.09;
    col *= 0.93 + 0.07 * sin(uv.y * iResolution.y * 3.14159265);

    /* Third-generation dub: desaturated, warm in the lows, green-shifted. */
    col = mix(col, vec3(toy_luma(col)), 0.18);
    col *= vec3(1.03, 1.00, 0.95);
    col  = clamp(col, 0.0, 1.0);

    fragColor = vec4(col, 1.0);
}

#undef mainImage

#endif
