#version 150
in vec3 Position;
in vec4 Color;
in vec2 UV0;
uniform mat4 ModelViewMat, ProjMat;
out vec4 tint;
out vec2 texCoord;
void main() {
    gl_Position=ProjMat*ModelViewMat*vec4(Position,1.);
    tint=Color;
    texCoord=UV0;
}
