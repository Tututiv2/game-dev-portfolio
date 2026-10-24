#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>
#moj_import <minecraft:globals.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
flat out float animatedChatPhase;
flat out float animatedChatBrightness;
flat out float animatedChatDirection;
flat out float animatedChatTheme;

const int ANIMATED_CHAT_MARKER_SLOTS = 64;   // per family; family 2 rides 64+, family 3 rides 127+
const int ANIMATED_CHAT_FAMILY2_BASE = 64;
const int ANIMATED_CHAT_FAMILY3_BASE = 127;  // family 2 tops out at slot 62 (blue would go negative)
const int ANIMATED_CHAT_FAMILY4_BASE = 189;  // family 3 tops out at slot 61 (t=61 -> blue 2)
const int ANIMATED_CHAT_FAMILY5_BASE = 249;  // family 4 tops out at slot 59 (u=59 -> blue 2)

bool nearByte(float value, float byteValue) {
    return abs(value - byteValue / 255.0) < 0.001;
}

int normalMarkerSlot(vec4 color) {
    for (int slot = 0; slot < ANIMATED_CHAT_MARKER_SLOTS; slot++) {
        // family 1: #RR(01+4S)(FE-4S)
        if (nearByte(color.g, 1.0 + float(slot) * 4.0)
                && nearByte(color.b, 254.0 - float(slot) * 4.0)) {
            return slot;
        }
        // family 2: #RR(03+4s)(FA-4s)
        if (nearByte(color.g, 3.0 + float(slot) * 4.0)
                && nearByte(color.b, 250.0 - float(slot) * 4.0)) {
            return ANIMATED_CHAT_FAMILY2_BASE + slot;
        }
        // family 3: #RR(02+4t)(F6-4t) — green is 2 mod 4, so it can never be read as
        // family 1 (1 mod 4) or family 2 (3 mod 4); blue base 246 keeps its QUARTERED
        // shadow (61-t) clear of family 1's (63-S) and family 2's (62-s).
        if (nearByte(color.g, 2.0 + float(slot) * 4.0)
                && nearByte(color.b, 246.0 - float(slot) * 4.0)) {
            return ANIMATED_CHAT_FAMILY3_BASE + slot;
        }
        // family 4: #RR(04+4u)(EE-4u) — green is 0 mod 4, the last free residue class, but
        // starts at 4 so the marker is never #RR00xx (a plain blue a player could actually pick).
        if (nearByte(color.g, 4.0 + float(slot) * 4.0)
                && nearByte(color.b, 238.0 - float(slot) * 4.0)) {
            return ANIMATED_CHAT_FAMILY4_BASE + slot;
        }
        // family 5: #RRFF(06+4j) — families 1-4 spent every `green mod 4` residue, so this one
        // pins GREEN to 255 (unreachable by the others) and rides BLUE instead.
        if (nearByte(color.g, 255.0)
                && nearByte(color.b, 6.0 + float(slot) * 4.0)) {
            return ANIMATED_CHAT_FAMILY5_BASE + slot;
        }
    }
    return -1;
}

int shadowMarkerSlot(vec4 color) {
    // Minecraft's packed text-shadow color quarters every marker byte:
    // family 1 #RR(01+4S)(FE-4S) -> #rrSS(3F-S); family 2 #RR(03+4s)(FA-4s) -> #rrss(3E-s).
    for (int slot = 0; slot < ANIMATED_CHAT_MARKER_SLOTS; slot++) {
        if (nearByte(color.g, float(slot)) && nearByte(color.b, 63.0 - float(slot))) {
            return slot;
        }
        if (nearByte(color.g, float(slot)) && nearByte(color.b, 62.0 - float(slot))) {
            return ANIMATED_CHAT_FAMILY2_BASE + slot;
        }
        if (nearByte(color.g, float(slot)) && nearByte(color.b, 61.0 - float(slot))) {
            return ANIMATED_CHAT_FAMILY3_BASE + slot;
        }
        // family 4 #RR(04+4u)(EE-4u) -> #rr(01+u)(3B-u). Green is offset by one, so this pattern
        // sums to 60 and cannot be confused with 63/62/61 above at any slot.
        if (nearByte(color.g, 1.0 + float(slot)) && nearByte(color.b, 59.0 - float(slot))) {
            return ANIMATED_CHAT_FAMILY4_BASE + slot;
        }
        // family 5 #RRFF(06+4j) -> #rr3F(01+j). Blue starts at 6 rather than 2 precisely so this
        // is never (63, 0), which is family 1's slot-63 shadow.
        if (nearByte(color.g, 63.0) && nearByte(color.b, 1.0 + float(slot))) {
            return ANIMATED_CHAT_FAMILY5_BASE + slot;
        }
    }
    return -1;
}

/**
 * MOTION themes (2026-07-27) — the text physically moves, not just its colour.
 *
 * Returns a motion id for a theme index; 0 = none, so every pre-existing theme is untouched.
 * Kept as a plain if-ladder to match the palette dispatch in the fragment shader.
 */
int motionFor(float theme) {
    if (theme > 69.5 && theme < 70.5) { return 1; }   // Tidal      - gentle wave
    if (theme > 70.5 && theme < 71.5) { return 2; }   // Fault Line - hard shake
    if (theme > 71.5 && theme < 72.5) { return 3; }   // Kindling   - light bounce
    if (theme > 72.5 && theme < 73.5) { return 4; }   // Wisp       - slow drift
    if (theme > 73.5 && theme < 74.5) { return 5; }   // Corrupted  - violent glitch
    if (theme > 74.5 && theme < 75.5) { return 6; }   // Living     - breathing pulse
    if (theme > 80.5 && theme < 81.5) { return 7; }   // Orbit
    if (theme > 81.5 && theme < 82.5) { return 8; }   // Cascade
    if (theme > 82.5 && theme < 83.5) { return 9; }   // Ripple
    if (theme > 83.5 && theme < 84.5) { return 10; }  // Pendulum
    if (theme > 84.5 && theme < 85.5) { return 11; }  // Vertigo
    // third wave (2026-07-28) — these ride marker family 4.
    if (theme > 90.5 && theme < 91.5) { return 12; }  // Heartbeat
    if (theme > 91.5 && theme < 92.5) { return 13; }  // Serpent
    if (theme > 92.5 && theme < 93.5) { return 14; }  // Magnetic
    if (theme > 93.5 && theme < 94.5) { return 15; }  // Spring
    if (theme > 94.5 && theme < 95.5) { return 16; }  // Stagger
    if (theme > 95.5 && theme < 96.5) { return 17; }  // Stomp
    if (theme > 96.5 && theme < 97.5) { return 18; }  // Haunted
    if (theme > 97.5 && theme < 98.5) { return 19; }  // Helix
    if (theme > 98.5 && theme < 99.5) { return 20; }  // Shockwave
    if (theme > 99.5 && theme < 100.5) { return 21; } // Zero G
    // fourth wave (2026-07-28).
    if (theme > 100.5 && theme < 101.5) { return 22; } // Flutter
    if (theme > 101.5 && theme < 102.5) { return 23; } // Whirlwind
    if (theme > 102.5 && theme < 103.5) { return 24; } // Carousel
    if (theme > 103.5 && theme < 104.5) { return 25; } // Marionette
    if (theme > 104.5 && theme < 105.5) { return 26; } // Accordion
    if (theme > 105.5 && theme < 106.5) { return 27; } // Buzz
    if (theme > 106.5 && theme < 107.5) { return 28; } // Scramble
    if (theme > 107.5 && theme < 108.5) { return 29; } // Burst
    if (theme > 108.5 && theme < 109.5) { return 30; } // Drain
    if (theme > 109.5 && theme < 110.5) { return 31; } // Metronome
    // fifth wave (2026-07-28). All periodic or per-letter - none use absolute positions.
    if (theme > 110.5 && theme < 111.5) { return 32; } // Swarm
    if (theme > 111.5 && theme < 112.5) { return 33; } // Typewriter
    if (theme > 112.5 && theme < 113.5) { return 34; } // Pulseline
    if (theme > 113.5 && theme < 114.5) { return 35; } // Jelly
    if (theme > 114.5 && theme < 115.5) { return 36; } // Snap
    if (theme > 115.5 && theme < 116.5) { return 37; } // Undertow
    if (theme > 116.5 && theme < 117.5) { return 38; } // Clockwork
    if (theme > 117.5 && theme < 118.5) { return 39; } // Gravity
    if (theme > 118.5 && theme < 119.5) { return 40; } // Waveform
    if (theme > 119.5 && theme < 120.5) { return 41; } // Shiver
    // sixth wave (2026-07-28) — the first themes to ride marker family 5.
    if (theme > 120.5 && theme < 121.5) { return 42; } // Riptide
    if (theme > 121.5 && theme < 122.5) { return 43; } // Stutter
    if (theme > 122.5 && theme < 123.5) { return 44; } // Slingshot
    if (theme > 123.5 && theme < 124.5) { return 45; } // Rumble
    if (theme > 124.5 && theme < 125.5) { return 46; } // Moth
    if (theme > 125.5 && theme < 126.5) { return 47; } // Pendulum Wave
    if (theme > 126.5 && theme < 127.5) { return 48; } // Zipper
    if (theme > 127.5 && theme < 128.5) { return 49; } // Chatter
    if (theme > 128.5 && theme < 129.5) { return 50; } // Warp
    if (theme > 129.5 && theme < 130.5) { return 51; } // Coil
    if (theme > 130.5 && theme < 131.5) { return 52; } // Void Rift
    // seventh wave (2026-07-29).
    if (theme > 131.5 && theme < 132.5) { return 53; } // Tether
    if (theme > 132.5 && theme < 133.5) { return 54; } // Datastream
    if (theme > 133.5 && theme < 134.5) { return 55; } // Quake
    if (theme > 134.5 && theme < 135.5) { return 56; } // Piston Wave
    if (theme > 135.5 && theme < 136.5) { return 57; } // Twitch
    if (theme > 136.5 && theme < 137.5) { return 58; } // Thread
    if (theme > 137.5 && theme < 138.5) { return 59; } // Lull
    if (theme > 138.5 && theme < 139.5) { return 60; } // Fracture
    if (theme > 139.5 && theme < 140.5) { return 61; } // Uprise
    if (theme > 140.5 && theme < 141.5) { return 62; } // Shunt
    return 0;
}

/**
 * Per-glyph displacement in text space (a chat character is ~8 units tall).
 *
 * <p>The travelling phase is derived from the glyph's own X position, NOT from the marker's red
 * byte. That matters: Minecraft draws a quarter-brightness SHADOW copy whose red byte has been
 * divided by 4, so a phase read from the marker would differ between a glyph and its shadow and
 * the two would visibly separate as they moved. World position is identical for both.
 */
vec3 animatedChatOffset(int motion, vec3 pos) {
    if (motion == 0) {
        return vec3(0.0);
    }
    float t = GameTime * 1200.0;          // GameTime is tiny; scale into a usable rate
    float travel = pos.x * 0.55;          // wave rides along the line
    if (motion == 1) {                    // Tidal - subtle, stays readable
        return vec3(0.0, sin(t * 1.6 + travel) * 0.9, 0.0);
    }
    if (motion == 2) {                    // Fault Line - deliberately harsh
        float j = fract(sin(floor(pos.x * 3.0) * 12.9898 + floor(t * 6.0) * 4.1414) * 43758.5453);
        return vec3((j - 0.5) * 1.6, (fract(j * 7.3) - 0.5) * 1.6, 0.0);
    }
    if (motion == 3) {                    // Kindling - springy hop, one letter at a time
        float hop = max(0.0, sin(t * 2.0 + travel));
        return vec3(0.0, hop * hop * 1.6, 0.0);
    }
    if (motion == 4) {                    // Wisp - weightless float
        return vec3(sin(t * 0.5 + travel * 0.4) * 0.5, cos(t * 0.7 + travel * 0.3) * 0.7, 0.0);
    }
    if (motion == 5) {                    // Corrupted - mostly still, then snaps
        float burst = step(0.86, fract(sin(floor(t * 3.0) * 78.233) * 43758.5453));
        float per = fract(sin(floor(pos.x * 2.0) * 12.9898) * 43758.5453);
        return vec3(burst * (per - 0.5) * 5.0, burst * (fract(per * 3.7) - 0.5) * 2.0, 0.0);
    }
    if (motion == 6) {                    // Living - the whole line breathes as one
        return vec3(0.0, sin(t * 1.1) * 0.7, 0.0);
    }
    if (motion == 7) {                    // Orbit - each glyph circles its own centre
        return vec3(cos(t * 2.2 + travel) * 0.7, sin(t * 2.2 + travel) * 0.7, 0.0);
    }
    if (motion == 8) {                    // Cascade - letters drop in sequence, then reset
        float fall = fract(t * 0.35 - travel * 0.12);
        return vec3(0.0, (1.0 - fall) * (1.0 - fall) * 4.5, 0.0);
    }
    if (motion == 9) {                    // Ripple - a pulse runs outward from the middle
        float d = abs(pos.x - 0.0) * 0.35;
        return vec3(0.0, sin(t * 2.4 - d) * exp(-d * 0.25) * 1.2, 0.0);
    }
    if (motion == 10) {                   // Pendulum - the whole line swings as one body
        return vec3(sin(t * 1.3) * 1.4, 0.0, 0.0);
    }
    if (motion == 11) {                   // Vertigo - shear: the lean grows with height
        return vec3(sin(t * 1.5) * (pos.y * 0.10), 0.0, 0.0);
    }

    // ---- third wave (2026-07-28). `letter` buckets vertices per glyph so the per-character
    // randoms below stay stable across a glyph's 4 corners instead of tearing it apart.
    float letter = floor(pos.x * 0.16);

    if (motion == 12) {                   // Heartbeat - lub-DUB, then stillness
        float c = fract(t * 0.30);
        float lub = exp(-70.0 * c * c);
        float dub = exp(-70.0 * (c - 0.17) * (c - 0.17)) * 0.7;
        return vec3(0.0, (lub + dub) * 1.7, 0.0);
    }
    if (motion == 13) {                   // Serpent - a coil travelling the line, both axes
        float w = t * 2.0 + travel * 1.4;
        return vec3(cos(w) * 0.4, sin(w) * 1.5, 0.0);
    }
    if (motion == 14) {                   // Magnetic - attractors sweep along, pulling letters in
        // PERIODIC in pos.x, never an absolute position (fixed 2026-07-28). The old version put
        // the focus at an absolute 80 +/- 90, which covers a chat line starting at x=0 but
        // completely MISSES a nametag - those sit centred near x=0 and are only ~60 wide - and
        // ran off the end of any short message. fract() makes the attractor repeat, so every
        // glyph gets swept no matter where the text is or how long it is.
        float d = fract(pos.x * 0.025 - t * 0.55) - 0.5;
        float pull = exp(-d * d * 45.0);
        return vec3(-d * 12.0 * pull, -pull * 1.5, 0.0);
    }
    if (motion == 15) {                   // Spring - snap up, overshoot, settle, repeat
        float c = fract(t * 0.40);
        return vec3(0.0, sin(c * 34.0) * exp(-6.0 * c) * 2.2, 0.0);
    }
    if (motion == 16) {                   // Stagger - lurches and recovers, never in step
        float o = fract(sin(letter * 45.233) * 43758.5453) * 6.2831;
        return vec3(sin(t * 0.9 + o) * 0.9, sin(t * 1.37 + o * 1.7) * 0.8, 0.0);
    }
    if (motion == 17) {                   // Stomp - the whole line slams down and shakes it off
        float c = fract(t * 0.32);
        float drop = c < 0.18 ? c / 0.18 : exp(-9.0 * (c - 0.18));
        return vec3(0.0, -drop * 2.4, 0.0);
    }
    if (motion == 18) {                   // Haunted - a letter slips out of place, then snaps back
        float win = floor(t * 1.3);
        float r = fract(sin(letter * 12.9898 + win * 78.233) * 43758.5453);
        float env = step(0.80, r) * exp(-5.0 * fract(t * 1.3));
        return vec3((fract(r * 7.3) - 0.5) * 4.0 * env,
                    (fract(r * 3.1) - 0.5) * 3.0 * env, 0.0);
    }
    if (motion == 19) {                   // Helix - an ellipse tight along x reads as a turning strand
        float a = t * 2.2 + pos.x * 0.30;
        return vec3(cos(a) * 1.1, sin(a) * 1.5, 0.0);
    }
    if (motion == 20) {                   // Shockwave - a hard front tears down the line
        // Same absolute-coordinate fix as Magnetic: the front used to travel 0 -> 220 in world
        // units, so it crossed a short line (or a nametag) for a fraction of its cycle and looked
        // dead the rest of the time. Periodic front, identical on any text at any position.
        float d = fract(pos.x * 0.02 - t * 0.45) - 0.5;
        return vec3(0.0, -exp(-d * d * 140.0) * 3.0, 0.0);
    }
    if (motion == 21) {                   // Zero G - nothing holds it down, each letter adrift
        float o = fract(sin(letter * 91.7) * 43758.5453) * 6.2831;
        return vec3(sin(t * 0.33 + o) * 1.1, cos(t * 0.27 + o * 1.3) * 1.3, 0.0);
    }

    // ---- fourth wave (2026-07-28). `mid` is the line's rough centre in text space; several of
    // these work on distance from it, which is what makes them read as one BODY moving rather
    // than a row of independent letters.
    float mid = pos.x - 80.0;

    if (motion == 22) {                   // Flutter - fast shallow flaps, each letter its own beat
        float o = fract(sin(letter * 23.117) * 43758.5453) * 6.2831;
        return vec3(0.0, sin(t * 7.0 + o) * 0.55, 0.0);
    }
    if (motion == 23) {                   // Whirlwind - calm at the start, wilder along the line
        float amp = pos.x * 0.012;
        float a = t * 3.0 + pos.x * 0.20;
        return vec3(cos(a) * amp * 0.6, sin(a) * amp, 0.0);
    }
    if (motion == 24) {                   // Carousel - the line rotates bodily about its centre
        float ang = sin(t * 0.9) * 0.5;
        return vec3(mid * (cos(ang) - 1.0), mid * sin(ang), 0.0);
    }
    if (motion == 25) {                   // Marionette - hangs slack, then a string yanks it up
        float o = fract(sin(letter * 61.3) * 43758.5453);
        float c = fract(t * 0.5 + o * 0.3);
        return vec3(0.0, exp(-14.0 * c) * 2.6 - 0.6, 0.0);
    }
    if (motion == 26) {                   // Accordion - the line squeezes together and pulls apart
        return vec3(mid * 0.10 * sin(t * 1.5), 0.0, 0.0);
    }
    if (motion == 27) {                   // Buzz - tight high-frequency electric vibration
        float n = fract(sin(letter * 12.9898 + floor(t * 30.0) * 7.13) * 43758.5453);
        return vec3((n - 0.5) * 0.5, (fract(n * 9.7) - 0.5) * 0.5, 0.0);
    }
    if (motion == 28) {                   // Scramble - letters trade places, then snap back
        float win = floor(t * 1.1);
        float r = fract(sin(letter * 33.7 + win * 51.9) * 43758.5453);
        float env = step(0.65, r) * (1.0 - fract(t * 1.1));
        return vec3(sign(fract(r * 5.0) - 0.5) * 6.0 * env, 0.0, 0.0);
    }
    if (motion == 29) {                   // Burst - blows apart from the middle, then reforms
        float e = exp(-5.0 * fract(t * 0.35));
        return vec3(mid * 0.25 * e,
                    (fract(sin(letter * 7.1) * 43758.5453) - 0.5) * 6.0 * e, 0.0);
    }
    if (motion == 30) {                   // Drain - spirals with a radius that opens and closes
        float a = t * 2.5 + letter * 0.7;
        float r = 1.4 * (0.5 + 0.5 * sin(t * 0.6));
        return vec3(cos(a) * r, sin(a) * r, 0.0);
    }
    if (motion == 31) {                   // Metronome - hard discrete ticks, never a smooth swing
        return vec3((mod(floor(t * 1.6), 2.0) * 2.0 - 1.0) * 1.6, 0.0, 0.0);
    }

    // ---- fifth wave (2026-07-28). Every one of these is either PER-LETTER or PERIODIC in
    // pos.x - deliberately no absolute positions, which is what broke Magnetic/Shockwave on
    // nametags and short lines.

    if (motion == 32) {                   // Swarm - each letter loops its own little circuit
        float o = fract(sin(letter * 17.3) * 43758.5453) * 6.2831;
        float rate = 1.5 + fract(sin(letter * 5.1) * 43758.5453) * 2.0;
        return vec3(cos(t * rate + o) * 0.8, sin(t * rate * 1.3 + o) * 0.8, 0.0);
    }
    if (motion == 33) {                   // Typewriter - a lift-and-drop that runs along the line
        return vec3(0.0, exp(-fract(pos.x * 0.02 - t * 0.4) * 9.0) * 3.0, 0.0);
    }
    if (motion == 34) {                   // Pulseline - a heart-monitor spike, up hard then under
        float d = fract(pos.x * 0.02 - t * 0.5) - 0.5;
        float up = exp(-d * d * 400.0) * 3.2;
        float down = exp(-(d - 0.035) * (d - 0.035) * 400.0) * 1.4;
        return vec3(0.0, up - down, 0.0);
    }
    if (motion == 35) {                   // Jelly - a wobble with an overshoot harmonic on it
        float w = t * 2.5 - pos.x * 0.06;
        return vec3(0.0, (sin(w) + 0.35 * sin(w * 2.7)) * 1.3, 0.0);
    }
    if (motion == 36) {                   // Snap - holds dead still, then the whole line jumps
        float win = floor(t * 1.4);
        return vec3((fract(sin(win * 91.3) * 43758.5453) - 0.5) * 3.0,
                    (fract(sin(win * 47.9) * 43758.5453) - 0.5) * 2.4, 0.0);
    }
    if (motion == 37) {                   // Undertow - a deep slow swell with chop riding on top
        return vec3(0.0, sin(t * 0.6 + pos.x * 0.02) * 1.5
                       + sin(t * 3.1 + pos.x * 0.12) * 0.35, 0.0);
    }
    if (motion == 38) {                   // Clockwork - orbits quantised to 8 hard positions
        float a = floor(fract(t * 0.5 + letter * 0.08) * 8.0) / 8.0 * 6.2831;
        return vec3(cos(a) * 0.9, sin(a) * 0.9, 0.0);
    }
    if (motion == 39) {                   // Gravity - bounces that lose height, each letter offset
        float c = fract(t * 0.5 + fract(sin(letter * 29.7) * 43758.5453));
        return vec3(0.0, abs(sin(c * 9.4)) * exp(-c * 2.2) * 4.0, 0.0);
    }
    if (motion == 40) {                   // Waveform - a travelling wave under a slow envelope
        float env = 0.35 + 0.65 * abs(sin(t * 0.45));
        return vec3(0.0, sin(t * 4.0 + pos.x * 0.10) * 1.6 * env, 0.0);
    }
    if (motion == 41) {                   // Shiver - trembling that swells and passes
        float env = pow(max(0.0, sin(t * 0.8)), 3.0);
        float n = fract(sin(letter * 12.9 + floor(t * 24.0) * 3.7) * 43758.5453);
        return vec3((n - 0.5) * 1.6 * env, (fract(n * 6.1) - 0.5) * 1.2 * env, 0.0);
    }

    // ---- sixth wave (2026-07-28). Periodic or per-letter only, same rule as the fifth.

    if (motion == 42) {                   // Riptide - long SIDEWAYS pulls; almost everything
        return vec3(sin(t * 1.1 + pos.x * 0.035) * 2.2, 0.0, 0.0);   // else moves vertically
    }
    if (motion == 43) {                   // Stutter - holds a frame, jumps, holds again
        float q = floor(t * 6.0) / 6.0;
        return vec3(sin(q * 5.3) * 1.5, cos(q * 3.7) * 1.1, 0.0);
    }
    if (motion == 44) {                   // Slingshot - slow drag back, then a hard release
        float c = fract(t * 0.45);
        return vec3(c < 0.75 ? -(c / 0.75) * 1.6
                             : ((c - 0.75) / 0.25) * 4.0 - 1.6, 0.0, 0.0);
    }
    if (motion == 45) {                   // Rumble - heavy low shake, weighted vertical
        float n = fract(sin(letter * 3.7 + floor(t * 8.0) * 11.3) * 43758.5453);
        return vec3((n - 0.5) * 0.9, (fract(n * 4.3) - 0.5) * 1.8, 0.0);
    }
    if (motion == 46) {                   // Moth - erratic darting, each letter on its own clock
        float o = fract(sin(letter * 71.3) * 43758.5453);
        float w = floor(t * 2.2 + o * 3.0);
        float e = 1.0 - fract(t * 2.2 + o * 3.0);
        return vec3((fract(sin(w * 12.9 + letter) * 43758.5453) - 0.5) * 3.0 * e,
                    (fract(sin(w * 78.2 + letter) * 43758.5453) - 0.5) * 2.6 * e, 0.0);
    }
    if (motion == 47) {                   // Pendulum Wave - each letter swings at its OWN rate, so
        return vec3(0.0, sin(t * (1.0 + letter * 0.035)) * 1.5, 0.0);  // they drift in and out of
    }                                                                  // phase and re-converge
    if (motion == 48) {                   // Zipper - odd and even letters split apart, then close
        float side = mod(letter, 2.0) * 2.0 - 1.0;
        return vec3(0.0, side * sin(t * 1.8) * 1.6, 0.0);
    }
    if (motion == 49) {                   // Chatter - hard binary snap, teeth-chattering fast
        return vec3(0.0, step(0.5, fract(sin(letter * 19.7
                + floor(t * 18.0) * 5.1) * 43758.5453)) * 1.1, 0.0);
    }
    if (motion == 50) {                   // Warp - a lens of compression slides along the line
        float d = fract(pos.x * 0.018 - t * 0.4) - 0.5;
        return vec3(-d * 20.0 * exp(-d * d * 30.0), 0.0, 0.0);
    }
    if (motion == 51) {                   // Coil - neighbours spiral opposite ways, like a braid
        float side = mod(letter, 2.0) * 2.0 - 1.0;
        float a = t * 2.0 + letter * 0.5;
        return vec3(cos(a) * 0.8, sin(a) * 1.3 * side, 0.0);
    }
    if (motion == 52) {                   // Void Rift - torn outward into the dark, dragged back
        float pull = pow(max(0.0, sin(fract(t * 0.28) * 3.14159)), 2.0);
        float o = fract(sin(letter * 13.7) * 43758.5453) * 6.2831;
        return vec3(cos(o) * 2.6 * pull, sin(o) * 2.0 * pull - pull * 0.8, 0.0);
    }

    // ---- seventh wave (2026-07-29). Periodic or per-letter only.

    if (motion == 53) {                   // Tether - hangs from a point and sways under it
        float sw = sin(t * 1.6 + fract(sin(letter * 37.1) * 43758.5453) * 2.5);
        return vec3(sw * 1.3, -abs(sw) * 0.5, 0.0);
    }
    if (motion == 54) {                   // Datastream - snaps between hard rows as it scrolls
        return vec3(0.0, (floor(fract(pos.x * 0.03 - t * 0.35) * 5.0) - 2.0) * 0.7, 0.0);
    }
    if (motion == 55) {                   // Quake - shaking that BUILDS, then releases
        float env = pow(fract(t * 0.35), 3.0);
        float n = fract(sin(letter * 7.3 + floor(t * 20.0) * 3.1) * 43758.5453);
        return vec3((n - 0.5) * 3.0 * env, (fract(n * 5.7) - 0.5) * 3.0 * env, 0.0);
    }
    if (motion == 56) {                   // Piston Wave - groups of letters shove up in turn
        float c = fract(t * 0.6 + floor(letter * 0.34) * 0.25);
        return vec3(0.0, (c < 0.5 ? smoothstep(0.0, 0.5, c)
                                  : 1.0 - smoothstep(0.5, 1.0, c)) * 2.2, 0.0);
    }
    if (motion == 57) {                   // Twitch - exactly one letter flicks at a time
        float pick = floor(fract(sin(floor(t * 3.0) * 17.3) * 43758.5453) * 24.0);
        float on = step(abs(letter - pick), 0.5);
        return vec3(0.0, on * exp(-fract(t * 3.0) * 8.0) * 2.8, 0.0);
    }
    if (motion == 58) {                   // Thread - a bump so narrow only one letter lifts
        float d = fract(pos.x * 0.02 - t * 0.35) - 0.5;
        return vec3(0.0, exp(-d * d * 900.0) * 3.4, 0.0);
    }
    if (motion == 59) {                   // Lull - very slow, very wide. Nearly at rest.
        return vec3(sin(t * 0.22 + pos.x * 0.008) * 1.8, cos(t * 0.17) * 1.4, 0.0);
    }
    if (motion == 60) {                   // Fracture - blocks of letters shear apart
        float blk = floor(letter * 0.34);
        return vec3(0.0, (fract(sin(blk * 29.3) * 43758.5453) - 0.5) * 3.0 * sin(t * 1.1), 0.0);
    }
    if (motion == 61) {                   // Uprise - letters climb from below in sequence
        float c = fract(t * 0.4 - letter * 0.06);
        return vec3(0.0, -(1.0 - c) * (1.0 - c) * 4.0, 0.0);
    }
    if (motion == 62) {                   // Shunt - hard sideways shoves travelling the line
        return vec3(sign(sin(t * 1.2 + pos.x * 0.03)) * 1.4, 0.0, 0.0);
    }
    return vec3(0.0);
}

void main() {

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;

    animatedChatPhase = -1.0;
    animatedChatBrightness = 1.0;
    animatedChatDirection = 1.0;
    animatedChatTheme = -1.0;

    int slot = normalMarkerSlot(Color);
    if (slot >= 0) {
        animatedChatPhase = Color.r;
        animatedChatTheme = float(slot / 2);
        animatedChatDirection = (slot % 2 == 1) ? -1.0 : 1.0;
    } else {
        slot = shadowMarkerSlot(Color);
        if (slot >= 0) {
            animatedChatPhase = clamp(Color.r * 4.0, 0.0, 1.0);
            animatedChatBrightness = 0.25;
            animatedChatTheme = float(slot / 2);
            animatedChatDirection = (slot % 2 == 1) ? -1.0 : 1.0;
        }
    }

    // Displace LAST, once the theme is known. Non-marked text takes the identity path, so
    // ordinary chat, signs, scoreboards and item names are bit-for-bit unaffected.
    vec3 animatedPos = Position;
    if (animatedChatTheme >= 0.0) {
        animatedPos += animatedChatOffset(motionFor(animatedChatTheme), Position);
    }
    gl_Position = ProjMat * ModelViewMat * vec4(animatedPos, 1.0);
}
