/* ============================================================================
   NOISE — the whole GLSL toolbox the planet shader is built from.
   Exported as a string so every shader can `${NOISE}` it in.

   Everything here is a pure function of position. No uniforms, no time, no
   state: that is what keeps planets deterministic from a seed.

   DOMAIN LIMIT: snoise() wraps at +-289 (mod289 permutation table). The planet
   shader feeds it a unit direction times a frequency, and frequencies stay
   under ~220, so we are always inside the table. If you ever raise a frequency
   past ~250 the world will visibly tile. Add a second seeded offset instead.
   ========================================================================= */

/* The two-line helpers, on their own, because the BAKED planet shader wants
   them and wants nothing else in this file: it reads a texture instead of
   evaluating noise, and pulling in 300 lines of simplex to get sat() would
   undo a good part of the point. NOISE includes them, so including both is
   a redeclaration error — pick one. */
export const GLSL_HELPERS = /* glsl */`
float sat(float x){ return clamp(x, 0.0, 1.0); }
vec3  sat3(vec3 x){ return clamp(x, 0.0, 1.0); }
float smoothb(float a, float b, float x){ return smoothstep(a, b, x); }
`;

export const NOISE = /* glsl */`
${GLSL_HELPERS}
// ---------------------------------------------------------------- simplex 3D
// Ashima Arts / Stefan Gustavson, public domain. The standard implementation.
vec3  mod289(vec3 x){ return x - floor(x * (1.0/289.0)) * 289.0; }
vec4  mod289(vec4 x){ return x - floor(x * (1.0/289.0)) * 289.0; }
vec4  permute(vec4 x){ return mod289(((x*34.0)+1.0)*x); }
vec4  taylorInvSqrt(vec4 r){ return 1.79284291400159 - 0.85373472095314 * r; }
// scalar overloads — the cell hash below needs them, and every intermediate
// stays under 2^24 so these are exact in 32-bit and 64-bit alike
float mod289(float x){ return x - floor(x * (1.0/289.0)) * 289.0; }
float permute(float x){ return mod289(((x*34.0)+1.0)*x); }

float snoise(vec3 v){
  const vec2 C = vec2(1.0/6.0, 1.0/3.0);
  const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);

  vec3 i  = floor(v + dot(v, C.yyy));
  vec3 x0 = v - i + dot(i, C.xxx);

  vec3 g  = step(x0.yzx, x0.xyz);
  vec3 l  = 1.0 - g;
  vec3 i1 = min(g.xyz, l.zxy);
  vec3 i2 = max(g.xyz, l.zxy);

  vec3 x1 = x0 - i1 + C.xxx;
  vec3 x2 = x0 - i2 + C.yyy;
  vec3 x3 = x0 - D.yyy;

  i = mod289(i);
  vec4 p = permute( permute( permute(
             i.z + vec4(0.0, i1.z, i2.z, 1.0))
           + i.y + vec4(0.0, i1.y, i2.y, 1.0))
           + i.x + vec4(0.0, i1.x, i2.x, 1.0));

  float n_ = 0.142857142857;
  vec3  ns = n_ * D.wyz - D.xzx;

  // The 7x7 gradient index, computed so it CANNOT depend on float precision.
  //
  // The textbook form is  j = p - 49*floor(p*ns.z*ns.z);  x_ = floor(j*ns.z),
  // which leans on 1/7 rounding a particular way. It rounds UP in 32-bit and
  // DOWN in 64-bit, so floor(7 * 1/7) is 1 on the GPU and 0 on the CPU, and six
  // of the forty-nine gradients land off the end of the table. That is exactly
  // the kind of difference that makes the ground you stand on disagree with the
  // ground you see.
  //
  // p and j are integer-valued, so adding 0.5 before the floor leaves half a
  // unit of slack on either side and the result is identical everywhere.
  vec4 j  = floor(mod(p + 0.5, 49.0));
  vec4 x_ = floor((j + 0.5) * 0.142857142857);
  vec4 y_ = j - 7.0 * x_;

  vec4 x = x_ * ns.x + ns.yyyy;
  vec4 y = y_ * ns.x + ns.yyyy;
  vec4 h = 1.0 - abs(x) - abs(y);

  vec4 b0 = vec4(x.xy, y.xy);
  vec4 b1 = vec4(x.zw, y.zw);

  vec4 s0 = floor(b0)*2.0 + 1.0;
  vec4 s1 = floor(b1)*2.0 + 1.0;
  // -1e-4, NOT 0. Seven of the forty-nine gradient entries give |x|+|y| == 1
  // exactly, so h lands on zero and this branch is decided by whichever way the
  // rounding error fell — which differs between the GPU's 32-bit floats and the
  // CPU mirror's 64-bit ones, and put 32% of samples on different gradients.
  // See src/core/simplex.js for the full account. KEEP THESE TWO IN STEP.
  vec4 sh = -step(h, vec4(-1e-4));

  vec4 a0 = b0.xzyw + s0.xzyw*sh.xxyy;
  vec4 a1 = b1.xzyw + s1.xzyw*sh.zzww;

  vec3 p0 = vec3(a0.xy, h.x);
  vec3 p1 = vec3(a0.zw, h.y);
  vec3 p2 = vec3(a1.xy, h.z);
  vec3 p3 = vec3(a1.zw, h.w);

  vec4 norm = taylorInvSqrt(vec4(dot(p0,p0), dot(p1,p1), dot(p2,p2), dot(p3,p3)));
  p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;

  vec4 m = max(0.6 - vec4(dot(x0,x0), dot(x1,x1), dot(x2,x2), dot(x3,x3)), 0.0);
  m = m * m;
  return 42.0 * dot(m*m, vec4(dot(p0,x0), dot(p1,x1), dot(p2,x2), dot(p3,x3)));
}

// ------------------------------------------------------------------- fractals
// Unrolled by octave count: GLSL loops with a uniform bound compile to real
// loops on some drivers and to nothing useful on others. Fixed counts inline.
float fbm2(vec3 p){
  return snoise(p)*0.6667 + snoise(p*2.03)*0.3333;
}
float fbm3(vec3 p){
  float s = snoise(p);
  s += snoise(p*2.03)*0.5;
  s += snoise(p*4.07)*0.25;
  return s * 0.5714;
}
float fbm4(vec3 p){
  float s = snoise(p);
  s += snoise(p*2.03)*0.5;
  s += snoise(p*4.07)*0.25;
  s += snoise(p*8.11)*0.125;
  return s * 0.5333;
}
float fbm5(vec3 p){
  float s = snoise(p);
  s += snoise(p*2.03)*0.5;
  s += snoise(p*4.07)*0.25;
  s += snoise(p*8.11)*0.125;
  s += snoise(p*16.2)*0.0625;
  return s * 0.5161;
}
float fbm6(vec3 p){
  float s = snoise(p);
  s += snoise(p*2.03)*0.5;
  s += snoise(p*4.07)*0.25;
  s += snoise(p*8.11)*0.125;
  s += snoise(p*16.2)*0.0625;
  s += snoise(p*32.5)*0.03125;
  return s * 0.5079;
}

// Ridged multifractal. pow() sharpens the ridge: >1 makes knife edges, which is
// what an unweathered world looks like. Do NOT round these off — no rain here.
float ridged4(vec3 p, float sharp){
  float s = 0.0, a = 1.0, f = 1.0, m = 0.0;
  for(int i=0;i<4;i++){
    float v = 1.0 - abs(snoise(p*f));
    v = pow(v, sharp);
    s += a*v; m += a;
    a *= 0.5; f *= 2.11;
  }
  return s/m;
}
float ridged5(vec3 p, float sharp){
  float s = 0.0, a = 1.0, f = 1.0, m = 0.0;
  for(int i=0;i<5;i++){
    float v = 1.0 - abs(snoise(p*f));
    v = pow(v, sharp);
    s += a*v; m += a;
    a *= 0.5; f *= 2.11;
  }
  return s/m;
}

// ----------------------------------------------------------- domain warping
// iq's trick. fBm evaluated at a position that has itself been displaced by
// fBm. One iteration gives marbling; two gives the deeply recursive, fractal
// coastlines in the reference images. Straight fBm gives blobby continents and
// that is the single biggest reason the old planets read as "normal space".
vec3 warp1(vec3 p, float amp){
  vec3 w = vec3(fbm2(p),
                fbm2(p + vec3(5.2, 1.3, 2.8)),
                fbm2(p + vec3(1.7, 9.2, 3.4)));
  return p + amp*w;
}
vec3 warp2(vec3 p, float amp){
  vec3 w1 = vec3(fbm2(p),
                 fbm2(p + vec3(5.2, 1.3, 2.8)),
                 fbm2(p + vec3(1.7, 9.2, 3.4)));
  vec3 q  = p + amp*w1;
  vec3 w2 = vec3(fbm2(q + vec3(8.3, 2.8, 4.4)),
                 fbm2(q + vec3(3.1, 6.6, 7.7)),
                 fbm2(q + vec3(9.8, 4.1, 1.2)));
  return p + amp*w2;
}

// --------------------------------------------------------- cellular / Worley
// THE signature technique. F2-F1 is small near a cell boundary and large in a
// cell interior, so thresholding it draws the branching, polygonal crack /
// plate network that dominates the lava and glacier references.
/* CELL HASH — built from permute() so it is BIT-EXACT in 32-bit and 64-bit.

   The obvious choice here is a Hoskins-style multiply/fract hash, and that is
   what this was. It is fast and well distributed and it is NOT PORTABLE: it
   works by squeezing entropy out of the low mantissa bits of a fract(), and
   there are 24 of those on the GPU versus 53 in the JavaScript mirror. The two
   therefore disagree BY CONSTRUCTION, which moved every crack boundary slightly
   and left the terrain you stand on out of step with the terrain you see.

   permute() is the same primitive simplex already uses, and every intermediate
   here stays under 2^24, where both float sizes are exact. Two independently
   salted chains give 289^2 = 83,521 distinct cell signatures — with 27 cells in
   a Worley neighbourhood that is roughly a 0.4% chance of one collision, which
   is invisible in jitter.

   It costs about 4x what the fract hash did on the crack path. Worth it:
   binding rule 3 says physics equals visuals, and this is what buys it. */
vec3 hash33(vec3 p){
  vec3 i = floor(p);
  float a = permute(permute(permute(mod289(i.x))        + mod289(i.y))        + mod289(i.z));
  float b = permute(permute(permute(mod289(i.x + 37.0)) + mod289(i.y + 91.0)) + mod289(i.z + 13.0));
  float c = permute(a + b);
  return vec3(a, b, c) * (1.0/289.0);
}

// returns (F1, F2) — the two nearest cell-point distances.
vec2 worley(vec3 p){
  vec3 ip = floor(p), fp = p - ip;
  float f1 = 9.0, f2 = 9.0;
  for(int z=-1; z<=1; z++)
  for(int y=-1; y<=1; y++)
  for(int x=-1; x<=1; x++){
    vec3 g = vec3(float(x), float(y), float(z));
    vec3 o = hash33(ip + g);
    vec3 d = g + o - fp;
    float dd = dot(d,d);
    if(dd < f1){ f2 = f1; f1 = dd; }
    else if(dd < f2){ f2 = dd; }
  }
  return vec2(sqrt(f1), sqrt(f2));
}

// A crack mask in 0..1: 1 exactly on a cell boundary, 0 inside a cell.
// width is in cell units — 0.02 is a hairline, 0.14 is a canyon.
float crackMask(vec3 p, float width){
  vec2 w = worley(p);
  return 1.0 - smoothstep(0.0, width, w.y - w.x);
}

/* ---------------------------------------------------------------------------
   BAND-LIMITING — the fix for "the whole planet looks like etched fingerprints".

   A 4-octave ridged fBm at base frequency 8 has its finest detail at 8*2.11^3
   = 75, i.e. a wavelength of 0.013 radians. From orbit that is under three
   pixels wide, so the finite-difference normal samples pure noise and the world
   turns to fizz. Frequencies finer than the pixel footprint carry NO
   information, only aliasing.

   So every octave is weighted by how big it is relative to uEps -- the angular
   size of one pixel, which the CPU recomputes each frame from the camera
   distance. Octaves fade out as they approach pixel size instead of popping.
   The normaliser m is accumulated UNWEIGHTED on purpose: a faded octave must
   reduce the amplitude, not get renormalised straight back in.

   gEps IS A GLOBAL, NOT A UNIFORM, and that is deliberate. It used to be
   uniform float uEps, which works fine when every pixel on screen is the same
   size -- true for a camera, false for a cube-map face. Baking the planet into
   a cubemap means one texel at a face CORNER subtends about 47% of the angle a
   texel at the face CENTRE does, so a single value per face either aliases the
   middle or smears the corners, and the smear lands in eight patches you can
   see. The bake therefore sets gEps per pixel from the exact face geometry.
   Every main() assigns it as its first statement; the default is only a floor
   so that a shader which forgets cannot silently unfold every octave at once.

   NB: no backticks and no dollar-brace anywhere below this line. Everything in
   this file lives inside a JS template literal, so one backtick in a comment
   silently ends the string and the rest of the GLSL gets parsed as JavaScript.
   ------------------------------------------------------------------------ */
float gEps = 0.002;

// descending ramp, written as 1 - ascending: smoothstep with edge0 > edge1 is
// UNDEFINED in GLSL, not reversed, and different drivers disagree about it.
float bandw(float absFreq){ return 1.0 - smoothstep(0.55, 1.7, absFreq*gEps); }

/* GAIN 0.40, NOT 0.50 — and this is the single most important number here.

   Textbook fBm halves the amplitude and doubles the frequency each octave, so
   every octave contributes the SAME amount of SLOPE (0.5 x 2.0 = 1.0). Shade
   that with a normal and the finest octave wins on sight, because it is the
   densest, and the terrain reads as noise no matter how good the big shapes are.

   At gain 0.40 each octave contributes 0.40 x 2.03 = 0.81 of the previous
   octave's slope, so detail decays instead of piling up: broad forms you can
   read, fine texture that stays texture. */
float fbmB(vec3 p, float baseFreq, int oct){
  float s = 0.0, a = 1.0, f = 1.0, m = 0.0;
  for(int i=0;i<6;i++){
    if(i >= oct) break;
    s += a*bandw(baseFreq*f)*snoise(p*f);
    m += a; a *= 0.40; f *= 2.03;
  }
  return s/max(m, 1e-4);
}

float ridgedB(vec3 p, float baseFreq, float sharp, int oct){
  float s = 0.0, a = 1.0, f = 1.0, m = 0.0;
  for(int i=0;i<5;i++){
    if(i >= oct) break;
    // max(..., 0) is NOT decorative. Simplex slightly overshoots +-1, so
    // 1 - |noise| goes a little negative, and pow() of a negative base is
    // UNDEFINED in GLSL (and NaN in the JS mirror). Either way: a hole.
    float v = max(1.0 - abs(snoise(p*f)), 0.0);
    s += a*bandw(baseFreq*f)*pow(v, sharp);
    m += a; a *= 0.40; f *= 2.11;
  }
  return s/max(m, 1e-4);
}

/** crack network with the cell grid itself warped, so cells vary in size and
    shape instead of reading as a regular polygonal tiling, and band-limited so
    a fine network dissolves smoothly instead of aliasing. */
float crackB(vec3 p, float baseFreq, float width, float warpAmt){
  float w = bandw(baseFreq*1.4);
  if(w <= 0.001) return 0.0;
  vec3 q = warpAmt > 0.001 ? warp1(p, warpAmt) : p;
  return crackMask(q, width)*w;
}

// ------------------------------------------------------------------ helpers
// (sat / sat3 / smoothb live in GLSL_HELPERS above — they are needed by
//  shaders that want none of the rest of this file.)

// hue rotation in RGB, cheap. Used to make two worlds of one archetype differ.
vec3 hueShift(vec3 c, float h){
  const vec3 k = vec3(0.57735);
  float ca = cos(h*6.2831853);
  return c*ca + cross(k, c)*sin(h*6.2831853) + k*dot(k, c)*(1.0-ca);
}

// HSL -> RGB, so palettes can be authored the way a painter thinks.
vec3 hsl2rgb(vec3 c){
  vec3 rgb = clamp(abs(mod(c.x*6.0 + vec3(0.0,4.0,2.0), 6.0)-3.0)-1.0, 0.0, 1.0);
  return c.z + c.y*(rgb-0.5)*(1.0 - abs(2.0*c.z-1.0));
}

// A spiral warp around an axis on the sphere — cyclones, ice vortices.
// The rotation angle decays with distance from the eye, which shears the noise
// into a real spiral rather than stamping a picture of one.
// Distance uses 2*(1-dot) instead of acos(): same shape, no transcendental, and
// this runs three times per pixel inside the normal's finite difference.
vec3 spiralWarp(vec3 n, vec3 axis, float strength, float falloff){
  float d2 = 2.0*(1.0 - clamp(dot(n, axis), -1.0, 1.0));
  float k = exp(-d2/(falloff*falloff));
  float ang = strength*k;
  float c = cos(ang), s = sin(ang);
  return n*c + cross(axis, n)*s + axis*dot(axis, n)*(1.0-c);   // Rodrigues
}
`;
