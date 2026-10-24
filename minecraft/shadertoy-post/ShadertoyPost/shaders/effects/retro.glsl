/* RETRO — chunky pixels, few colours, ordered dithering. The game rendered as
   if the GPU were 30 years older than it is. */

#if EFFECT == 5

#define mainImage mainImage_RETRO

const float RETRO_PIXELS = 320.0;   // horizontal resolution to snap down to
const float RETRO_LEVELS = 6.0;     // colour steps per channel

/* 4x4 Bayer matrix, already divided by 16. Ordered dithering beats random
   noise here: it holds still between frames instead of boiling. */
float retro_bayer(vec2 cell) {
    const mat4 m = mat4(
        0.0000, 0.5000, 0.1250, 0.6250,
        0.7500, 0.2500, 0.8750, 0.3750,
        0.1875, 0.6875, 0.0625, 0.5625,
        0.9375, 0.4375, 0.8125, 0.3125
    );
    ivec2 i = ivec2(mod(cell, 4.0));
    return m[i.x][i.y];
}

void mainImage(out vec4 fragColor, in vec2 fragCoord) {
    /* Snap to a coarse grid, keeping pixels square on any aspect ratio. */
    vec2 grid = vec2(RETRO_PIXELS, RETRO_PIXELS * iResolution.y / iResolution.x);
    vec2 cell = floor(fragCoord / iResolution.xy * grid);

    /* Sample the centre of the cell, not its corner. */
    vec3 col = SAMPLE((cell + 0.5) / grid).rgb;

    /* Quantise, offsetting by the dither threshold first. This is what turns a
       hard banded gradient back into something that reads as continuous. */
    float d = retro_bayer(cell);
    col = floor(col * RETRO_LEVELS + d) / RETRO_LEVELS;

    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}

#undef mainImage

#endif
