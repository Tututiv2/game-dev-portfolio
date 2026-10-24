#version 330 compatibility

/* The `final` pass draws one fullscreen quad. All the vertex stage has to do is
   put it on screen and hand the fragment stage its texture coordinate. */

out vec2 texcoord;

void main() {
    gl_Position = ftransform();
    texcoord = gl_MultiTexCoord0.xy;
}
