/* ============================================================================
   CUSTOM SLOT  —  select "Custom" in the shader options screen to run this.

   This is where a shader you found on Shadertoy goes. Paste it BETWEEN the two
   markers below, replacing what's there, and it should compile as-is.

   Three things to know:

     1. Nothing else in this pack defines helper functions, so a pasted shader
        can't collide with anything. Only this one file, one shader at a time.

     2. Your shader reads the game frame as `iChannel0`. On Shadertoy that
        would be whatever texture the author wired up — usually a photo or a
        noise tile. If the shader you copied expects an actual texture (grain,
        a pattern, a cubemap) rather than "the previous image", it will look
        wrong until you point it at `noisetex` or bake the texture in.

     3. Read UVs through `SAMPLE(uv)` rather than `texture(iChannel0, uv)` when
        you offset them. Shadertoy tiles out-of-range reads; Minecraft smears
        the border pixel instead. SAMPLE clamps for you.

   `CONVERT.md` in the project root has the full porting recipe and the list of
   things that don't translate.
   ========================================================================== */

/* This whole file is skipped unless Custom is the selected effect, so a paste
   that doesn't compile only breaks Custom — the other six keep working while
   you fix it. */
#if EFFECT == 8

#define mainImage mainImage_CUSTOM

/* ======================= PASTE SHADERTOY CODE BELOW ======================= */

/* Shipped placeholder: a slow hue rotation with a vignette, so the slot does
   something visible out of the box. Delete all of it. */

vec3 custom_hueShift(vec3 col, float a) {
    const vec3 k = vec3(0.57735);               // normalize(vec3(1.0))
    float c = cos(a);
    /* Rodrigues' rotation formula, rotating the colour about the grey axis. */
    return col * c + cross(k, col) * sin(a) + k * dot(k, col) * (1.0 - c);
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    vec2 uv = fragCoord / iResolution.xy;

    vec3 col = SAMPLE(uv).rgb;
    col = custom_hueShift(col, iTime * 0.3);

    /* Vignette. */
    vec2 v = uv * (1.0 - uv);
    col *= pow(clamp(v.x * v.y * 16.0, 0.0, 1.0), 0.2);

    fragColor = vec4(col, 1.0);
}

/* ======================= PASTE SHADERTOY CODE ABOVE ======================= */

#undef mainImage

#endif
