/* RIPPLE — underwater. Crossed sine waves push the image around, light
   refracts by wavelength, and caustics crawl over the top. */

#if EFFECT == 4

#define mainImage mainImage_RIPPLE

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2  uv = fragCoord / iResolution.xy;
    float t  = iTime;

    /* Two waves per axis at different rates — one alone reads as a machine,
       two beating against each other reads as water. */
    vec2 d = vec2(
        sin(uv.y * 24.0 + t * 1.7) + sin(uv.y *  9.0 - t * 1.1),
        sin(uv.x * 19.0 - t * 1.3) + sin(uv.x *  7.0 + t * 0.9)
    ) * 0.0024;

    /* Refraction is wavelength-dependent, so red and blue land apart. */
    vec3 col;
    col.r = SAMPLE(uv + d * 1.18).r;
    col.g = SAMPLE(uv + d       ).g;
    col.b = SAMPLE(uv + d * 0.82).b;

    /* Caustics: two diagonal wave trains crossing. Only the crests light up. */
    float caustic = sin((uv.x + uv.y) * 42.0 + t * 2.0)
                  * sin((uv.x - uv.y) * 33.0 - t * 1.6);
    col += vec3(0.03, 0.055, 0.065) * max(caustic, 0.0);

    /* Water eats red first. */
    col *= vec3(0.80, 0.96, 1.06);

    fragColor = vec4(col, 1.0);
}

#undef mainImage

#endif
