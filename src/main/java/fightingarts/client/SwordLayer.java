package fightingarts.client;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fightingarts.StyleState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Draws the player's actual sword stacks at the authored rig attachment bones. */
public final class SwordLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static final String[] ATTACHMENTS={"left_main","left_extra","right_main","mouth"};
    private static final Map<String,Group> GROUPS=new LinkedHashMap<>();
    private static final Map<String,String> ADJUSTMENTS=new LinkedHashMap<>();
    private static boolean loaded;
    private SwordLayer(RenderLayerParent<AbstractClientPlayer,PlayerModel<AbstractClientPlayer>> parent){super(parent);load();}
    public static void register(EntityRenderersEvent.AddLayers e){ for(var skin:e.getSkins()){PlayerRenderer r=e.getSkin(skin);if(r!=null)r.addLayer(new SwordLayer(r));} }

    private static void load(){if(loaded)return;loaded=true;try(var in=Minecraft.getInstance().getResourceManager().getResourceOrThrow(ResourceLocation.fromNamespaceAndPath("fightingarts","styles/santoryu_rig.json")).open();var reader=new InputStreamReader(in,StandardCharsets.UTF_8)){
        JsonObject root=JsonParser.parseReader(reader).getAsJsonObject();
        for(JsonElement e:root.getAsJsonArray("groups")){JsonObject g=e.getAsJsonObject();GROUPS.put(g.get("name").getAsString(),new Group(g.get("name").getAsString(),vec(g.getAsJsonArray("origin")),vec(g.getAsJsonArray("rotation")),g.get("parent").isJsonNull()?null:g.get("parent").getAsString()));}
        JsonObject at=root.getAsJsonObject("sword_attachments");
        for(String key:ATTACHMENTS){JsonObject a=at.getAsJsonObject(key);ADJUSTMENTS.put(key,a.get("adjustment").getAsString());}
    }catch(Exception ex){GROUPS.clear();}}

    @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,AbstractClientPlayer p,float swing,float amount,float partial,float age,float yaw,float pitch){
        StyleState s=AnimationRenderer.STATES.get(p.getUUID());if(s==null||s.swords().size()<3||p.isInvisible())return;
        double t=(p.level().getGameTime()-s.phaseStart()+partial)/20.0;
        if(s.phase().equals("OFF")){drawWaist(pose,buffers,light,p,s.swords(),"OFF",-1);return;}
        if(s.phase().equals("DRAWING")){
            drawWaist(pose,buffers,light,p,s.swords(),s.phase(),t);
            if(t>=.5&&t<1.16667)drawSword(pose,buffers,light,p,partial,"right_main",getParentModel().rightArm,transform(p,"right_item"),s.swords().get(0),0);
            if(t>=.5)drawSword(pose,buffers,light,p,partial,"left_main",getParentModel().leftArm,transform(p,"left_item"),s.swords().get(1),1);
            if(t>=1.16667&&t<2.625)drawSword(pose,buffers,light,p,partial,"left_extra",getParentModel().leftArm,transform(p,"left_item2"),s.swords().get(0),0);
            if(t>=1.91667&&t<2.29167)drawSword(pose,buffers,light,p,partial,"right_main",getParentModel().rightArm,transform(p,"right_item"),s.swords().get(2),2);
            if(t>=2.29167)drawSword(pose,buffers,light,p,partial,"mouth",getParentModel().head,transform(p,"right_item2"),s.swords().get(2),2);
            if(t>=2.625)drawSword(pose,buffers,light,p,partial,"right_main",getParentModel().rightArm,transform(p,"right_item"),s.swords().get(0),0);
            return;
        }
        if(s.phase().equals("SHEATHING")){
            drawWaist(pose,buffers,light,p,s.swords(),s.phase(),t);
            if(t<.41667)drawSword(pose,buffers,light,p,partial,"right_main",getParentModel().rightArm,transform(p,"right_item"),s.swords().get(0),0);
            if(t<.58333)drawSword(pose,buffers,light,p,partial,"left_main",getParentModel().leftArm,transform(p,"left_item"),s.swords().get(1),1);
            if(t<1.5)drawSword(pose,buffers,light,p,partial,"mouth",getParentModel().head,transform(p,"right_item2"),s.swords().get(2),2);
            drawSword(pose,buffers,light,p,partial,"left_extra",getParentModel().leftArm,transform(p,"left_item2"),s.swords().get(2),2);
            return;
        }
        drawSword(pose,buffers,light,p,partial,"left_main",getParentModel().leftArm,transform(p,"left_item"),s.swords().get(1),1);
        drawSword(pose,buffers,light,p,partial,"right_main",getParentModel().rightArm,transform(p,"right_item"),s.swords().get(0),0);
        drawSword(pose,buffers,light,p,partial,"mouth",getParentModel().head,transform(p,"right_item2"),s.swords().get(2),2);
    }

    private void drawSword(PoseStack stack,MultiBufferSource buffers,int light,AbstractClientPlayer p,float partial,String key,net.minecraft.client.model.geom.ModelPart anchor,float[] t,ItemStack item,int swordIndex){
        if(!ADJUSTMENTS.containsKey(key)||t==null||t.length<9||Math.abs(t[6]*t[7]*t[8])<1.0e-5f||item.isEmpty())return;
        stack.pushPose();anchor.translateAndRotate(stack);
        String bone=switch(key){case "left_main"->"left_item";case "left_extra"->"left_item2";case "right_main"->"right_item";default->"right_item2";};
        Group attachment=GROUPS.get(bone); Group parent=attachment==null?null:GROUPS.get(attachment.parent);
        if(attachment!=null&&parent!=null){float[] offset=sub(attachment.origin,parent.origin);float[] rotation=attachment.rotation;
            offset[0]+=t[0];offset[1]+=t[1];offset[2]+=t[2];rotation=new float[]{rotation[0]+t[3],rotation[1]+t[4],rotation[2]+t[5]};
            translate(stack,offset);rotate(stack,rotation);stack.scale(t[6],t[7],t[8]);}
        Group attach=GROUPS.get(bone),adjust=GROUPS.get(ADJUSTMENTS.get(key));
        if(attach!=null&&adjust!=null){translate(stack,sub(adjust.origin,attach.origin));rotate(stack,adjust.rotation);}
        recordTrail(stack,p,partial,swordIndex);
        SwordModel.render(stack,buffers,light,p,item,false);
        stack.popPose();
    }

    private void drawWaist(PoseStack stack,MultiBufferSource buffers,int light,AbstractClientPlayer p,List<ItemStack> swords,String phase,double elapsed){
        for(int i=0;i<3;i++){
            if(!waistVisible(i,phase,elapsed))continue;
            stack.pushPose();
            getParentModel().body.translateAndRotate(stack);
            // Keep the top sheath outermost and fan the three hilts around the forward axis.
            stack.translate((-6.3 + i * .5) / 16, (10.5 + i * 1.1) / 16, 0);
            stack.mulPose(Axis.XP.rotationDegrees((i - 1) * 10));
            stack.mulPose(Axis.YP.rotationDegrees(90));
            stack.mulPose(Axis.ZP.rotationDegrees(-45));
            SwordModel.render(stack,buffers,light,p,swords.get(i),false);
            stack.popPose();
        }
    }
    private static boolean waistVisible(int slot,String phase,double t){
        if(phase.equals("OFF"))return true;
        if(phase.equals("DRAWING"))return t<(slot==2?1.91667:.5);
        if(phase.equals("SHEATHING"))return t>=(slot==0?.41667:slot==1?.58333:2.04167);
        return false;
    }
    private static void recordTrail(PoseStack pose,AbstractClientPlayer p,float partial,int index){
        Matrix4f m=new Matrix4f(pose.last().pose());
        m.rotateZ((float)Math.PI);
        m.scale(.85f,.85f,.85f);
        Camera camera=Minecraft.getInstance().gameRenderer.getMainCamera();Vec3 cp=camera.getPosition();
        // The baked item icon occupies 16x16 pixels; its blade diagonal runs from
        // (-6.8,-6.8) at the guard to (+6.8,+6.8) at the tip in rig axes.
        Vector3f base=m.transformPosition(new Vector3f(-.5f,-.5f,0));
        Vector3f tip=m.transformPosition(new Vector3f(.5f,.5f,0));
        base.add((float)cp.x,(float)cp.y,(float)cp.z);tip.add((float)cp.x,(float)cp.y,(float)cp.z);
        WorldEffects.recordSwordGeometry(p,index,partial,base,tip);
    }
    private static float[] transform(AbstractClientPlayer p,String bone){return AnimationRenderer.transform(p.getUUID(),bone);}
    private static void translate(PoseStack p,float[] d){p.translate(-d[0]/16.0,-d[1]/16.0,d[2]/16.0);}
    private static void rotate(PoseStack p,float[] r){p.mulPose(new org.joml.Quaternionf().rotationZYX((float)Math.toRadians(r[2]),(float)Math.toRadians(-r[1]),(float)Math.toRadians(-r[0])));}
    private static float[] sub(float[] a,float[] b){return new float[]{a[0]-b[0],a[1]-b[1],a[2]-b[2]};}
    private static float[] vec(JsonArray a){float[] v=new float[a.size()];for(int i=0;i<v.length;i++)v[i]=a.get(i).getAsFloat();return v;}
    private record Group(String name,float[] origin,float[] rotation,String parent){}
}
