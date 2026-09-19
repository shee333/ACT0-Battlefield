#version 150

uniform sampler2D DiffuseSampler;
uniform float Intensity;

in vec2 texCoord;

out vec4 fragColor;

void main(){
    vec4 diffuseColor = texture(DiffuseSampler, texCoord);
    float luma = dot(diffuseColor.rgb, vec3(0.299, 0.587, 0.114));
    fragColor = vec4(mix(diffuseColor.rgb, vec3(luma), Intensity), diffuseColor.a);
}
