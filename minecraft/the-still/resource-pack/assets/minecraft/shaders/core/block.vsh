#version 330

// SHADOW PRISON — byte-for-byte vanilla 26.1.2 block.vsh plus ONE added output.
//
// Terrain lighting is baked in the VERTEX stage (`Color * sample_lightmap(...)`), so a fragment
// shader has no way to un-apply it — you cannot divide the light back out reliably once it is
// multiplied in. The only clean way to render a texel at full brightness is to also hand the
// fragment stage the UNLIT vertex colour and let it choose. That is the entire change here.
//
// See block.fsh for the emissive marker and why it is keyed the way it is.
//
// ⚠ Core shaders are client-version-specific. On a Minecraft upgrade, extract that exact client's
// block.vsh/.fsh and re-apply this diff — do not carry these files forward blind.
// (Same rule as the animated-chat text shaders; see knowledge/animated-chat.md.)

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
// ADDED: the same vertex colour WITHOUT the lightmap multiplied in.
out vec4 vertexColorUnlit;
// ADDED (THE FOG LAMP): this vertex in EYE space — camera at the origin, looking down -Z.
//
// The lamp thins the fog toward the middle of the screen, so the fragment stage needs to know
// how far off the view axis it is. Neither of the two distances above can answer that:
// `sphericalVertexDistance` is length(pos) and `cylindricalVertexDistance` is a horizontal
// reach, and both are computed from the CAMERA-RELATIVE WORLD position, which has no idea
// which way you are facing. The camera's rotation lives in ModelViewMat and nowhere else, so
// the angle has to be taken here, where that matrix is in scope.
//
// A vec3 rather than a precomputed cosine on purpose. A cosine interpolated across a triangle
// is linear in screen space and wrong in the middle of anything large — a 16-block wall arrives
// with a visibly banded cone across it. Interpolating the position and doing the divide in the
// fragment stage is perspective-correct, and the divide is one rsqrt per pixel.
out vec3 eyeSpacePos;

void main() {
    vec3 pos = Position + ModelOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    vertexColorUnlit = Color;
    eyeSpacePos = (ModelViewMat * vec4(pos, 1.0)).xyz;
}
