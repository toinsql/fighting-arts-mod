#version 150
uniform sampler2D Scene, SceneDepth, Bodies, BodyDepth, Letters, Materials, Contours, ContourDepth;
uniform int MaterialSide;
uniform mat4 InvView;
uniform vec3 Eye, Observer;
uniform float VisionRange;
uniform vec2 Pixel;
uniform float Progress;
in vec2 uv;
out vec4 fragColor;
vec3 location(vec2 st,float depth){
    vec4 p=InvView*vec4(st*2.-1.,depth*2.-1.,1.);
    return p.xyz/p.w;
}
ivec3 voxelAt(ivec3 cell){
    if(any(lessThan(cell,ivec3(0))) || any(greaterThanEqual(cell,ivec3(MaterialSide))))return ivec3(0);
    ivec2 tile=ivec2(cell.y%9,cell.y/9)*MaterialSide;
    return ivec3(round(texelFetch(Materials,tile+cell.xz,0).rgb*255.));
}
ivec3 blockAt(vec3 ray,float inset){
    return voxelAt(ivec3(floor(ray+Eye+normalize(ray)*inset)));
}
ivec3 materialAt(vec3 ray){
    ivec3 material=blockAt(ray,.032);
    return material.r==0?blockAt(ray,.105):material;
}
float surfaceOutline(vec2 step,float depth,ivec3 material){
    float a=texture(SceneDepth,uv-step).r;
    float b=texture(SceneDepth,uv+step).r;
    // Screen depth varies linearly over a plane. A slope change marks a crease or the end of a face.
    float slope=max(abs(a-depth),abs(b-depth));
    float tolerance=max(.000001,.15*slope);
    float bend=smoothstep(tolerance,2.*tolerance,abs(a+b-2.*depth));
    // Attribute silhouettes to the nearer surface, not the surface behind it.
    if(min(a,b)<depth-tolerance && max(a,b)<=depth+tolerance)bend=0.;
    if(b<.999999){
        ivec3 adjacent=materialAt(location(uv+step,b));
        if(adjacent.r>=2 && any(notEqual(adjacent,material)))bend=max(bend,.85);
    }
    return bend;
}
void main(){
    vec3 scene=texture(Scene,uv).rgb;
    float depth=texture(SceneDepth,uv).r;
    vec3 ray=location(uv,depth);
    ivec3 material=materialAt(ray);
    vec3 ink=material.r==3?vec3(.9,.94,1.):vec3(1.,.18,.22);
    float line=0.;
    if((material.r==2 || material.r==3) && depth<.999999){
        line=max(surfaceOutline(vec2(Pixel.x,0.),depth,material),
                 surfaceOutline(vec2(0.,Pixel.y),depth,material));
    }

    float distanceToObserver=length(ray-Observer);
    line*=1.-smoothstep(VisionRange-5.,VisionRange,distanceToObserver);
    vec4 perimeter=texture(Contours,uv);
    if(perimeter.a>.01){
        vec3 edge=location(uv,texture(ContourDepth,uv).r);
        float edgeDistance=length(edge);
        // A small depth allowance keeps a one-pixel line attached to its original face.
        if(edgeDistance<=length(ray)+.055+.004*edgeDistance){
            ink=perimeter.rgb;
            line=max(line,1.-smoothstep(VisionRange-5.,VisionRange,length(edge-Observer)));
        }
    }
    vec4 body=texture(Bodies,uv);
    float bdepth=texture(BodyDepth,uv).r;
    bool behind=bdepth>depth+.00012;
    bool marked=body.a>.02 && (!behind || body.a>.8);
    vec3 result=depth<.999999 || line>0.?ink*line:vec3(.004,.006,.011);
    if(marked) result=behind?mix(body.rgb*.64,ink,line):body.rgb;
    float lettering=texture(Letters,uv).r;
    result=mix(result,marked?vec3(.012):vec3(.98),lettering);
    float radial=length((uv-.5)*vec2(1.4,1.0)) + .045*sin(min(length(ray),60.)*.32);
    float reveal=smoothstep(.91-1.1*Progress,.98-1.1*Progress,radial);
    fragColor=vec4(mix(scene,result,reveal),1.);
}
