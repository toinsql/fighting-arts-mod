package fightingarts.client;

import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import fightingarts.SantoryuSkills;
import fightingarts.client.mixin.SightRenderTypeAccessor;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.*;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;

/** Separate depth, posed bodies and glyph coverage let terrain occlude aura without hiding the lettering. */
final class BlindSight {
    private static final Minecraft MC=Minecraft.getInstance();
    private static final int[] SHIELD_SYMBOL = {
            0b1111111,
            0b1000001,
            0b1000001,
            0b1000001,
            0b1000001,
            0b0100010,
            0b0010100,
            0b0001000
    };
    private static final int[] SWORD_SYMBOL = {
            0b0000001,
            0b0000011,
            0b0000110,
            0b1001100,
            0b0111000,
            0b0011000,
            0b0100100,
            0b1000000
    };
    static final Map<Integer,SantoryuSkills.SightTarget> targets=new HashMap<>();
    private static boolean rawDamage;
    private static double exitingAt=-1, stoppedAge;
    private static final int REVEAL_TICKS=22, HUD_TICKS=14, HAND_TICKS=20;

    static void statusChanged(SantoryuSkills.Status before,SantoryuSkills.Status after){
        if(MC.level==null)return;
        if(after.visionStart()>=0 && (before==null || before.visionStart()!=after.visionStart())){
            rawDamage=false;
            exitingAt=-1;
        }else if(after.visionStart()<0 && before!=null && before.visionStart()>=0){
            exitingAt=after.visionEnd()>=0?after.visionEnd():MC.level.getGameTime();
            stoppedAge=Math.max(0,exitingAt-before.visionStart());
        }
    }

    private static double progress(double now,int length){
        if(active())return Math.clamp((now-SkillHud.local().visionStart())/length,0,1);
        if(exitingAt<0)return 0;
        double elapsed=Math.max(0,now-exitingAt);
        return Math.clamp(stoppedAge/length,0,1)*(1-Math.clamp(elapsed/length,0,1));
    }

    static double handProgress(double now){return progress(now,HAND_TICKS);}
    static float normalHudCover(double now){return (float)progress(now,HUD_TICKS);}
    static float symbolOpacity(double now){return (float)progress(now,15);}
    static void toggleDamageReadout(){rawDamage=!rawDamage;}
    private static ShaderInstance program, bodyMask;
    private static TextureTarget scene,bodies,letters,hudBackdrop,contours;
    private static final SightMaterials materials=new SightMaterials();
    private static final List<LivingEntity> visible=new ArrayList<>();

    static void registerShader(RegisterShadersEvent event){
        try{
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath("fightingarts","blind_sight"),DefaultVertexFormat.POSITION),s->program=s);
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath("fightingarts","sight_body_mask"),DefaultVertexFormat.POSITION_TEX_COLOR),s->bodyMask=s);
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    static boolean active(){var status=SkillHud.local();return MC.level!=null && status!=null && status.visionStart()>=0;}
    static void hideVanillaOutline(RenderHighlightEvent.Block event){
        // Vanilla's selection box writes a second depth contour in front of the material.
        if(active())event.setCanceled(true);
    }
    static void hideLayer(RenderGuiLayerEvent.Pre event){
        if(MC.level==null)return;
        double now=MC.level.getGameTime()+event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if(normalHudCover(now)>=1)event.setCanceled(true);
    }
    static void captureBackdrop(RenderGuiEvent.Pre event){
        if(MC.level==null || scene==null)return;
        double now=MC.level.getGameTime()+event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float cover=normalHudCover(now);
        if(cover<=0 || cover>=1)return;
        RenderTarget main=MC.getMainRenderTarget();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,main.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,hudBackdrop.frameBufferId);
        GL30.glBlitFramebuffer(0,0,main.width,main.height,0,0,main.width,main.height,GL30.GL_COLOR_BUFFER_BIT,GL30.GL_NEAREST);
        main.bindWrite(false);
    }
    static void fadeHud(GuiGraphics g,float cover){
        if(hudBackdrop==null || cover<=0 || cover>=1)return;
        g.flush();var matrix=g.pose().last();
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);RenderSystem.setShaderTexture(0,hudBackdrop.getColorTextureId());
        RenderSystem.setShaderColor(1,1,1,cover);
        var q=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX);
        q.addVertex(matrix,0,0,0).setUv(0,1);q.addVertex(matrix,0,g.guiHeight(),0).setUv(0,0);
        q.addVertex(matrix,g.guiWidth(),g.guiHeight(),0).setUv(1,0);q.addVertex(matrix,g.guiWidth(),0,0).setUv(1,1);
        BufferUploader.drawWithShader(q.buildOrThrow());RenderSystem.setShaderColor(1,1,1,1);RenderSystem.enableDepthTest();
    }
    static void hud(GuiGraphics g,SantoryuSkills.Status s,double now){
        int x=g.guiWidth()/2,y=g.guiHeight()-43;
        float alpha=symbolOpacity(now);
        RenderSystem.enableBlend();g.setColor(1,1,1,alpha);
        SkillHud.skillIcon(g,7,x-8,y+3,16,16);
        g.setColor(1,1,1,1);
        if(alpha<.1f)return;
        int white=((int)(alpha*255)<<24)|0xffffff;
        g.drawString(MC.font,MC.options.keyHotbarSlots[5].getTranslatedKeyMessage(),x+13,y+9,white);
        double energy=active()?Math.max(0,s.energy()-((now-s.visionStart())%2)*.5):s.energy();
        g.fill(x-25,y+23,x+25,y+25,((int)(alpha*255)<<24)|0x343434);g.fill(x-25,y+23,x-25+(int)(energy/4),y+25,white);
        g.drawCenteredString(MC.font,String.format(Locale.ROOT,"%.1f",energy),x,y+28,white);
        drawDamageSymbol(g,x-21,y+8,white);
    }

    private static void drawDamageSymbol(GuiGraphics graphics,int x,int y,int color){
        int[] symbol = rawDamage ? SWORD_SYMBOL : SHIELD_SYMBOL;
        for(int row=0;row<symbol.length;row++){
            for(int column=0;column<7;column++){
                if((symbol[row] & (1<<(6-column)))!=0){
                    graphics.fill(x+column,y+row,x+column+1,y+row+1,color);
                }
            }
        }
    }
    private static void allocate(RenderTarget main){
        if(scene!=null && scene.width==main.width && scene.height==main.height && scene.isStencilEnabled()==main.isStencilEnabled())return;
        release();
        scene=new TextureTarget(main.width,main.height,true,Minecraft.ON_OSX);
        if(main.isStencilEnabled())scene.enableStencil();
        bodies=new TextureTarget(main.width,main.height,true,Minecraft.ON_OSX);
        contours=new TextureTarget(main.width,main.height,true,Minecraft.ON_OSX);
        contours.setClearColor(0,0,0,0);
        letters=new TextureTarget(main.width,main.height,false,Minecraft.ON_OSX);
        bodies.setClearColor(0,0,0,0);letters.setClearColor(0,0,0,0);hudBackdrop=new TextureTarget(main.width,main.height,false,Minecraft.ON_OSX);
    }
    private static void release(){
        if(scene!=null){scene.destroyBuffers();bodies.destroyBuffers();letters.destroyBuffers();hudBackdrop.destroyBuffers();contours.destroyBuffers();scene=bodies=letters=hudBackdrop=contours=null;}
        materials.close();
    }
    static void clear(){
        exitingAt=-1;rawDamage=false;targets.clear();visible.clear();
        if(scene!=null){
            release();
            MC.getMainRenderTarget().bindWrite(false);
        }
    }
    static void world(RenderLevelStageEvent e){
        boolean terrain=e.getStage()==RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS;
        boolean masks=e.getStage()==RenderLevelStageEvent.Stage.AFTER_PARTICLES;
        if(!terrain && !masks && e.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;
        if(MC.level==null)return;
        float partial=e.getPartialTick().getGameTimeDeltaPartialTick(false);
        double now=MC.level.getGameTime()+partial;
        double strength=progress(now,REVEAL_TICKS);
        if(!active() && strength<=0){
            if(scene!=null)clear();
            return;
        }
        if(program==null)return;
        RenderTarget main=MC.getMainRenderTarget();
        allocate(main);

        if(terrain){
            // Water writes to the final depth buffer. Take the terrain depth before translucent chunks render.
            scene.copyDepthFrom(main);
            main.bindWrite(false);
            return;
        }

        Vec3 camera=e.getCamera().getPosition();
        if(masks){
            visible.clear();
            for(var entity:MC.level.entitiesForRendering()){
                if(entity instanceof LivingEntity mob && mob!=MC.player && !mob.isSpectator() && mob.isAlive()
                        && e.getFrustum().isVisible(mob.getBoundingBox().inflate(.5))
                        && SantoryuSkills.sightReveals(MC.player,mob))visible.add(mob);
            }
            try {
                bodies.clear(Minecraft.ON_OSX);bodies.bindWrite(false);
                drawBodies(e.getPoseStack(),camera,partial);
                letters.clear(Minecraft.ON_OSX);letters.bindWrite(false);
                drawLabels(e,camera,partial);
            }finally{
                main.bindWrite(false);RenderSystem.depthMask(true);RenderSystem.enableDepthTest();RenderSystem.enableCull();
            }
            return;
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,main.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,scene.frameBufferId);
        GL30.glBlitFramebuffer(0,0,main.width,main.height,0,0,main.width,main.height,
                GL30.GL_COLOR_BUFFER_BIT,GL30.GL_NEAREST);
        try{
            main.bindWrite(false);
            materials.bind(program,camera);
            contours.clear(Minecraft.ON_OSX);
            contours.bindWrite(false);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1,1,1,1);
            RenderSystem.lineWidth(1.0f);
            materials.drawContours(e.getModelViewMatrix(),e.getProjectionMatrix(),camera);
            main.bindWrite(false);
            program.setSampler("Contours",contours.getColorTextureId());
            program.setSampler("ContourDepth",contours.getDepthTextureId());
            program.setSampler("Scene",scene.getColorTextureId());program.setSampler("SceneDepth",scene.getDepthTextureId());
            program.setSampler("Bodies",bodies.getColorTextureId());program.setSampler("BodyDepth",bodies.getDepthTextureId());
            program.setSampler("Letters",letters.getColorTextureId());
            program.safeGetUniform("InvView").set(new Matrix4f(e.getProjectionMatrix()).mul(e.getModelViewMatrix()).invert());
            Vec3 observer=MC.player.getEyePosition(partial).subtract(camera);
            program.safeGetUniform("Observer").set((float)observer.x,(float)observer.y,(float)observer.z);
            program.safeGetUniform("VisionRange").set((float)SantoryuSkills.SIGHT_RANGE);
            program.safeGetUniform("Pixel").set(1f/main.width,1f/main.height);
            program.safeGetUniform("Progress").set((float)strength);
            RenderSystem.disableDepthTest();RenderSystem.depthMask(false);RenderSystem.disableCull();RenderSystem.disableBlend();
            RenderSystem.setShader(()->program);
            var quad=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION);
            quad.addVertex(-1,-1,0);quad.addVertex(1,-1,0);quad.addVertex(1,1,0);quad.addVertex(-1,1,0);
            BufferUploader.drawWithShader(quad.buildOrThrow());
        }finally{
            main.bindWrite(false);RenderSystem.depthMask(true);RenderSystem.enableDepthTest();RenderSystem.enableCull();
            RenderSystem.setShaderColor(1,1,1,1);RenderSystem.defaultBlendFunc();
        }
    }
    private static void drawBodies(PoseStack pose,Vec3 camera,float partial){
        if(bodyMask==null)return;
        var batches=new LinkedHashMap<ResourceLocation,MaskBatch>();
        var textures=new IdentityHashMap<RenderType,ResourceLocation>();
        var discard=new MaskVertices(null,0);
        try{
            for(var mob:visible){
                var info=targets.get(mob.getId());
                int tint=info==null?SantoryuSkills.SIGHT_YELLOW:info.tint();
                int alpha = mob.distanceToSqr(MC.player) <= SantoryuSkills.SIGHT_THROUGH_WALLS * SantoryuSkills.SIGHT_THROUGH_WALLS
                        ? 0xff000000 : 0x80000000;
                int color=alpha|tint;
                var renderer=MC.getEntityRenderDispatcher().getRenderer(mob);
                Vec3 pos=mob.getPosition(partial).add(renderer.getRenderOffset(mob,partial)).subtract(camera);
                pose.pushPose();pose.translate(pos.x,pos.y,pos.z);
                try{
                    renderer.render(mob,mob.getYRot(),partial,pose,type->{
                        if(type.format()!=DefaultVertexFormat.NEW_ENTITY || type.mode()!=VertexFormat.Mode.QUADS)return discard;
                        var texture=textures.computeIfAbsent(type,BlindSight::maskTexture);
                        var batch=batches.computeIfAbsent(texture,key->new MaskBatch());
                        return new MaskVertices(batch.quads,color);
                    },0xf000f0);
                }finally{pose.popPose();}
            }
            RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.disableBlend();RenderSystem.disableCull();
            RenderSystem.setShaderColor(1,1,1,1);RenderSystem.setShader(()->bodyMask);
            for(var batch:batches.entrySet()){
                var mesh=batch.getValue().quads.build();
                if(mesh==null)continue;
                RenderSystem.setShaderTexture(0,batch.getKey());
                BufferUploader.drawWithShader(mesh);
            }
        }finally{
            for(var batch:batches.values())batch.storage.close();
        }
    }

    private static ResourceLocation maskTexture(RenderType type){
        if(type instanceof SightRenderTypeAccessor composite){
            var state=(SightRenderTypeAccessor.State)(Object)composite.fightingarts$state();
            var texture=(SightRenderTypeAccessor.Texture)(Object)state.fightingarts$textureState();
            return texture.fightingarts$cutoutTexture().orElseGet(MissingTextureAtlasSprite::getLocation);
        }
        return MissingTextureAtlasSprite.getLocation();
    }

    private static final class MaskBatch {
        final ByteBufferBuilder storage=new ByteBufferBuilder(4096);
        final BufferBuilder quads=new BufferBuilder(storage,VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
    }

    private record MaskVertices(BufferBuilder out,int tint) implements VertexConsumer{
        public VertexConsumer addVertex(float x,float y,float z){if(out!=null)out.addVertex(x,y,z).setColor(tint);return this;}
        public VertexConsumer setUv(float u,float v){if(out!=null)out.setUv(u,v);return this;}
        public VertexConsumer setUv1(int u,int v){return this;}
        public VertexConsumer setUv2(int u,int v){return this;}
        public VertexConsumer setColor(int r,int g,int b,int a){return this;}
        public VertexConsumer setNormal(float x,float y,float z){return this;}
    }
    private static String stat(float value){
        return Math.abs(value-Math.round(value))<.01f?Integer.toString(Math.round(value)):String.format(Locale.forLanguageTag("pt-BR"),"%.1f",value);
    }
    private static void drawLabels(RenderLevelStageEvent e,Vec3 camera,float partial){
        var buffers=MC.renderBuffers().bufferSource();
        var pose=e.getPoseStack();
        for(var mob:visible){
            var data=targets.get(mob.getId());
            Vec3 pos=mob.getPosition(partial).add(0,mob.getBbHeight()*.52,0).subtract(camera);
            pose.pushPose();pose.translate(pos.x,pos.y,pos.z);pose.mulPose(e.getCamera().rotation());
            float scale=(float)Math.clamp(pos.length()*.0027,.019,.085);
            pose.scale(scale,-scale,scale);
            String hp="♥ "+stat(mob.getHealth())+"/"+stat(mob.getMaxHealth());
            String damage="⚔ "+(data==null?"…":stat(rawDamage?data.rawDamage():data.damageAfterDefense()));
            MC.font.drawInBatch(hp,-MC.font.width(hp)*.5f,-9,0xffffffff,false,pose.last().pose(),buffers,Font.DisplayMode.SEE_THROUGH,0,0xf000f0);
            MC.font.drawInBatch(damage,-MC.font.width(damage)*.5f,1,0xffffffff,false,pose.last().pose(),buffers,Font.DisplayMode.SEE_THROUGH,0,0xf000f0);
            pose.popPose();
        }
        buffers.endBatch();
    }
}
