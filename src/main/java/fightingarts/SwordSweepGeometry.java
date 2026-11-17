package fightingarts;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Samples the same authored rig and blade endpoints used by SwordLayer. */
final class SwordSweepGeometry {
    private static final Map<String,JsonObject> GROUPS=new HashMap<>();
    private static final Map<String,JsonObject> CLIPS=new HashMap<>();
    private static boolean loaded;
    private SwordSweepGeometry(){}
    private static void load() {
        if(loaded)return;
        try(var rig=reader("styles/santoryu_rig.json");var animations=reader("animations/santoryu.json")){
            for(var element:JsonParser.parseReader(rig).getAsJsonObject().getAsJsonArray("groups")){
                var group=element.getAsJsonObject();GROUPS.put(group.get("name").getAsString(),group);
            }
            JsonParser.parseReader(animations).getAsJsonObject().getAsJsonObject("animations")
                    .entrySet().forEach(entry->CLIPS.put(entry.getKey(),entry.getValue().getAsJsonObject().getAsJsonObject("bones")));
            loaded=true;
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private static Reader reader(String path) {
        var stream=Objects.requireNonNull(SwordSweepGeometry.class.getResourceAsStream("/assets/fightingarts/"+path));
        return new InputStreamReader(stream,StandardCharsets.UTF_8);
    }
    static Vec3[] blade(ServerPlayer player,int combo,int hand,double seconds,double scale) {
        load();
        var bones=CLIPS.get("fa_hit_combo"+combo);
        String adjustment=hand==0?"espada_right_item_AJUSTE":"espada_left_item_AJUSTE";
        Matrix4f matrix=world(adjustment,bones,seconds,new HashMap<>());
        Vector3f base=matrix.transformPosition(new Vector3f(-6.8f,-6.8f,0));
        Vector3f tip=matrix.transformPosition(new Vector3f(6.8f,6.8f,0));
        return new Vec3[]{toWorld(player,base,scale),toWorld(player,tip,scale)};
    }
    private static Vec3 toWorld(ServerPlayer p,Vector3f v,double scale){
        Vec3 local=new Vec3(v.x/16*scale,v.y/16,v.z/16*scale);
        return local.yRot((float)Math.toRadians(180-p.getYRot())).add(p.position());
    }
    private static Matrix4f world(String name,JsonObject bones,double seconds,Map<String,Matrix4f> cache) {
        if(cache.containsKey(name))return cache.get(name);
        JsonObject group=GROUPS.get(name);
        String parent=group.get("parent").isJsonNull()?null:group.get("parent").getAsString();
        Vector3f origin=vec(group.get("origin")),rotation=vec(group.get("rotation"));
        Vector3f parentOrigin=parent==null?new Vector3f():vec(GROUPS.get(parent).get("origin"));
        String track=name.equals("body")?"root":name.equals("torso")?"body":name;
        JsonObject channels=bones.getAsJsonObject(track);
        Vector3f position=channel(channels,"position",seconds,new Vector3f());
        rotation.add(channel(channels,"rotation",seconds,new Vector3f())).mul((float)Math.PI/180);
        Matrix4f matrix=parent==null?new Matrix4f():new Matrix4f(world(parent,bones,seconds,cache));
        matrix.translate(origin.sub(parentOrigin).add(position)).rotateZYX(rotation.z,rotation.y,rotation.x)
                .scale(channel(channels,"scale",seconds,new Vector3f(1)));
        cache.put(name,matrix);return matrix;
    }
    private static Vector3f channel(JsonObject channels,String key,double time,Vector3f fallback){
        if(channels==null || !channels.has(key))return fallback;
        JsonElement value=channels.get(key);
        if(value.isJsonArray())return vec(value);
        double low=-Double.MAX_VALUE,high=Double.MAX_VALUE;
        JsonElement before=null,after=null;
        for(var entry:value.getAsJsonObject().entrySet()){
            double t=Double.parseDouble(entry.getKey());
            if(t<=time&&t>low){low=t;before=entry.getValue();}
            if(t>=time&&t<high){high=t;after=entry.getValue();}
        }
        if(before==null)return vec(after);
        if(after==null||high==low)return vec(before);
        return vec(before).lerp(vec(after),(float)((time-low)/(high-low)));
    }
    private static Vector3f vec(JsonElement value) {
        if(value.isJsonObject())value=value.getAsJsonObject().get("post");
        var a=value.getAsJsonArray();return new Vector3f(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());
    }
}
