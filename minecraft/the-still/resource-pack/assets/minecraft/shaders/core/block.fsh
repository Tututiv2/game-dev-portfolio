#version 330

// SHADOW PRISON — byte-for-byte vanilla 26.1.2 block.fsh plus an EMISSIVE branch.
//
// WHAT THIS BUYS: individual PIXELS of a block texture can render at full brightness regardless of
// world light — a genuinely glowing vein/ring inside an otherwise normally-lit block. Vanilla has
// no per-pixel emissive; `shade:false` only removes face shading, and a block's light level is a
// property of the Block, not of the pack. A core shader is the only door, and this pack already
// walks through it for animated chat text (knowledge/animated-chat.md).
//
// ─────────────────────────────────────────────────────────────────────────────────────────────
// ⚠⚠ THIS SHADER RUNS ON EVERY SOLID BLOCK IN THE WORLD. A sloppy marker makes random terrain
// glow. The marker is therefore keyed on THREE things at once, and a texel must satisfy all of
// them. Read this before touching the constants:
//
//   1. ALPHA ~250/255, READ WITH texelFetch AT MIP LEVEL 0. This part matters: sampling the
//      marker with the ordinary filtered texture() would be unsafe, because mipmapping averages
//      alpha across neighbouring texels and cutout foliage (leaves, grass, vines, cobweb)
//      therefore produces EVERY intermediate alpha value at distance — including ours. Reading
//      the base level unfiltered returns exactly the authored byte, so a mipmap average can
//      never forge the marker no matter how far away the block is.
//   2. NEAR-GREYSCALE (max channel - min channel is tiny). Kills anything coloured.
//   3. BRIGHT (luminance above half). Kills dark texels.
//
// Only pixels we deliberately author white-ish at alpha 250 light up. Nothing in vanilla, and
// nothing else in this pack, satisfies all three.
// ─────────────────────────────────────────────────────────────────────────────────────────────
//
// Currently used by: Umbrite's ore block (chiseled_deepslate) — its event-horizon ring.
// Authored by scripts/build-custom-ore-art.py, which stamps alpha 250 on the ring pixels.
//
// ⚠ Core shaders are client-version-specific. On a Minecraft upgrade, extract that exact client's
// block.fsh and re-apply this diff — do not carry this file forward blind.
// ⚠ A third-party shader pack (OptiFine/Iris) replaces core shaders, so the glow silently turns
// off for those players. Stock 26.1.2 is what this supports. Never make a gameplay-relevant
// signal emissive-only for that reason — this is decoration.

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec4 vertexColorUnlit;
in vec3 eyeSpacePos;

out vec4 fragColor;

// ═════════════════════════════════════════════════════════════════════════════════════════════
// THE FOG LAMP — the cone half. The other half is com.thestill.fog.FogService.
//
// The Still is drowned in fog that closes to fully opaque at 22 blocks. The lamp is the thing
// you point into it. It CANNOT be a light block: Minecraft's fog is distance-based, not
// light-based, so a torch lights a surface that is still fogged out. Only a shader or a biome
// can move fog, which is why this is split in two and why the halves live in different files.
//
//   the biome  — set per-player by the server, pushes the fog out to 90 blocks ALL ROUND
//   this cone  — pushes it much further again, but only near the middle of the screen
//
// WHY BOTH. A design note worth keeping, because a later session will be tempted to merge the
// lamp with heat: heat is meant to buy AMBIENT vision (run warm, see everywhere, and the
// Wanting come for you), and the lamp buys DIRECTIONAL vision (see a long way, but only where
// you are pointing, and pointing is your main hand). If the lamp ever becomes all-round they
// collapse into one upgrade and the world loses a decision. Keep the cone narrow.
//
// ── GATING. ──────────────────────────────────────────────────────────────────────────────────
// A resource pack is one static file shipped identically to everybody, so this shader cannot
// know who is holding a lamp. What it CAN see is the fog the server gave this player, and the
// lamplight biome carries a distinctive pair of distances that nothing else in the pack uses:
//
//   thestill:depths     start  3  end  22       (the weather)
//   thestill:rig        start 34  end 110       (clear air over the deck)
//   thestill:haven      start 34  end 110       (a station lifts the fog)
//   thestill:lamplight  start 41  end  90   <-- THE SENTINEL. Only the lamp sets this pair.
//
// Matching the PAIR and not just the end distance is deliberate. The client blends environmental
// fog between neighbouring biomes, and 90 sits between 22 and 110 — so a single-value test on
// the end distance fires briefly every time you walk the boundary between the depths and a
// station, and the cone flickers on at the door of every winch. The pair (41, 90) is not on the
// line between (3, 22) and (34, 110), so no blend of the other three can forge it.
//
// ⚠ ANY NEW BIOME IN THIS PACK MUST AVOID start 41 / end 90 TOGETHER. That is the contract.
//
// ── BISECTING A "THE LAMP DOES NOTHING" REPORT. ──────────────────────────────────────────────
// Set FL_GATE to 0.0. The cone then applies everywhere, to everyone, always. If you now see a
// clear cone down the middle of the screen, the shader is loading and the GATE is at fault
// (biome not painted, datapack not enabled, distances edited). If you still see nothing, the
// client is not loading this pack's core shaders at all — same conclusion SP_PROBE reaches for
// the emissive marker, and the same fix: check for OptiFine/Iris, which replace core shaders.
// PUT IT BACK TO 1.0.
const float FL_GATE = 1.0;

// The sentinel the gate looks for, and how much slop it allows. Slop is half a block, because
// these arrive as floats straight out of the biome file and are not interpolated when you are
// standing well inside one region.
const float FL_LAMP_START = 41.0;
const float FL_LAMP_END = 90.0;
const float FL_SENTINEL_SLOP = 0.5;

// ── THE TUNING. These four numbers are the whole feel of the lamp. ───────────────────────────
// FL_REACH   how much further the fog is pushed dead ahead. 3.0 against the biome's 90 is 270
//            blocks in the middle of the screen — a beam that finds the far wall of a plains.
// FL_CORE    half-angle of the full-strength core, in degrees. Narrow on purpose: a wide cone
//            is just "less fog" and stops being a thing you aim.
// FL_EDGE    half-angle at which the lamp has faded to nothing. The gap between the two is the
//            soft edge; too small and the beam has a hard rim that reads as a rendering bug.
// FL_START   how much of the reach also applies to the fog's near edge. Pushing the start out
//            with the end keeps the gradient's shape; leaving it behind makes the beam wash out
//            near the camera. Slightly less than 1.0 so the beam still has some haze in it and
//            does not look like a hole cut in the weather.
const float FL_REACH = 3.0;
const float FL_CORE = 11.0;
const float FL_EDGE = 30.0;
const float FL_START = 0.85;

// ── TWO INDEPENDENT MARKERS, either one is enough ────────────────────────────────────────────
// The alpha marker alone did not work in game (owner 2026-07-29: "nope still no shine", with the
// animated chat shaders confirmed still running, which proves core shaders DO load on his client
// and so the marker — not the shader — was at fault). The block atlas is not obliged to preserve
// a sub-255 alpha byte on an OPAQUE block, so alpha is not a trustworthy carrier here.
//
// RGB always survives, so the primary marker is now a colour sentinel: an emissive pixel is
// authored as pure grey with its BLUE channel exactly one step above red/green — (v, v, v+1).
// One 1/255 step of blue is invisible to the eye, and vanilla greyscale art is (v, v, v), so it
// never trips. Read with texelFetch so the comparison is against the authored byte.
//
// The alpha marker is kept as a second chance in case alpha does survive on some setups. A pixel
// glows if EITHER fires (and it must also be near-grey and bright).
const float SP_STEP = 1.0 / 255.0;
const float SP_EPS = 0.4 / 255.0;         // half a byte — exact-match tolerance
const float SP_EMISSIVE_ALPHA = 0.98039;  // 250/255
const float SP_EMISSIVE_TOLERANCE = 0.008;
const float SP_EMISSIVE_MAX_CHROMA = 0.06;
const float SP_EMISSIVE_MIN_LUMA = 0.5;

// TEMPORARY DIAGNOSTIC — set to 1.0 to render every pure-black texel magenta. Umbrite's core is
// pure black, so a pink centre means "the pack's block shader IS running and the marker is the
// problem", and a black centre means "this client is not loading it at all". Costs one compare.
// LEAVE AT 0.0. Only flip it to bisect a not-rendering report, then flip it straight back.
const float SP_PROBE = 0.0;

/**
 * How much of the lamp this fragment gets: 1.0 dead ahead, 0.0 outside the cone.
 *
 * Returns 0.0 flat when the player is not standing in the lamplight biome, which is what makes
 * a pack-wearing player with no lamp see exactly the vanilla fog — no cone, no tint, nothing to
 * explain. That matters because the pack is optional and the dial that hands out lamps ships
 * OFF: whatever a player has installed, the world must look like itself.
 */
float fl_lamp_strength() {
    if (FL_GATE < 0.5) {
        // Bisecting. Cone everywhere, so "is this shader even running" gets a yes or a no.
        return smoothstep(cos(radians(FL_EDGE)), cos(radians(FL_CORE)),
                          -eyeSpacePos.z / max(length(eyeSpacePos), 1e-4));
    }
    bool lamplit = abs(FogEnvironmentalStart - FL_LAMP_START) < FL_SENTINEL_SLOP
                && abs(FogEnvironmentalEnd - FL_LAMP_END) < FL_SENTINEL_SLOP;
    if (!lamplit) {
        return 0.0;
    }
    // -z is forward in eye space, so this is the cosine of the angle off the view axis.
    // Guarded against the degenerate fragment at the camera's own position, where the
    // direction is undefined and the divide would produce a NaN that survives the smoothstep.
    float axis = -eyeSpacePos.z / max(length(eyeSpacePos), 1e-4);
    return smoothstep(cos(radians(FL_EDGE)), cos(radians(FL_CORE)), axis);
}

bool sp_is_emissive(vec2 uv) {
    // texelFetch = the authored byte, no filtering and no mipmap. See the note above.
    ivec2 texel_xy = ivec2(uv * vec2(textureSize(Sampler0, 0)));
    vec4 raw = texelFetch(Sampler0, texel_xy, 0);

    float chroma = max(max(raw.r, raw.g), raw.b) - min(min(raw.r, raw.g), raw.b);
    if (chroma > SP_EMISSIVE_MAX_CHROMA) {
        return false;
    }
    if (dot(raw.rgb, vec3(0.299, 0.587, 0.114)) <= SP_EMISSIVE_MIN_LUMA) {
        return false;
    }

    bool rgbMarked = abs(raw.g - raw.r) < SP_EPS && abs(raw.b - (raw.r + SP_STEP)) < SP_EPS;
    bool alphaMarked = abs(raw.a - SP_EMISSIVE_ALPHA) <= SP_EMISSIVE_TOLERANCE;
    return rgbMarked || alphaMarked;
}

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    bool emissive = sp_is_emissive(texCoord0);

    if (SP_PROBE > 0.5) {
        ivec2 p = ivec2(texCoord0 * vec2(textureSize(Sampler0, 0)));
        vec4 raw = texelFetch(Sampler0, p, 0);
        if (max(max(raw.r, raw.g), raw.b) < SP_EPS) {
            fragColor = vec4(1.0, 0.0, 1.0, 1.0);
            return;
        }
    }

    // Emissive texels skip the lightmap entirely (vertexColorUnlit), so they read at full
    // brightness in a pitch-black mine. Everything else takes the exact vanilla path.
    vec4 color = texel * (emissive ? vertexColorUnlit : vertexColor) * ColorModulator;
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    if (emissive) {
        // The marker lives in alpha, so restore full opacity before it reaches the framebuffer,
        // and skip fog: a glowing ring that fades into the fog colour stops looking like a light.
        color.a = 1.0;
        fragColor = color;
        return;
    }
    // THE FOG LAMP. Only the ENVIRONMENTAL pair is moved. The render-distance pair is the
    // client's own view distance and pushing that would ask the GPU to draw geometry the chunk
    // loader never sent — you would get a cone of grey nothing, which looks worse than fog.
    float lamp = fl_lamp_strength();
    float fogStart = FogEnvironmentalStart * mix(1.0, FL_REACH * FL_START, lamp);
    float fogEnd = FogEnvironmentalEnd * mix(1.0, FL_REACH, lamp);
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, fogStart, fogEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
