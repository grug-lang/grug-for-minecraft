#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    // Temporary #294 instrumentation, not for committing: puts the texture sample in red and the
    // vertex color (the shade times the lightmap sample) in green, with no fog, so a capture can
    // say which of the two carries the wall's radial field.
    fragColor = vec4(texture(Sampler0, texCoord0).r, vertexColor.r, 0.0, 1.0);
}
