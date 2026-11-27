#version 150
uniform sampler2D Sampler0;
in vec4 tint;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    if(texture(Sampler0,texCoord).a<.1)discard;
    fragColor=tint;
}
