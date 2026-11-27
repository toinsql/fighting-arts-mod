package fightingarts.client;

import fightingarts.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.*;

public final class SkillHud {
    public static final Map<UUID,SantoryuSkills.Status> STATUS=new HashMap<>();
    public static final ResourceLocation ATLAS=ResourceLocation.fromNamespaceAndPath("fightingarts","textures/gui/santoryu_skills.png");
    private static final ResourceLocation ICONS = guiTexture("santoryu_skill_icons");
    private static final ResourceLocation[] AWAKENED_ICONS = {
            guiTexture("rush"), guiTexture("sight"), guiTexture("rift_dragon"), guiTexture("sight_closed")
    };
    private static final ResourceLocation ENERGY_FRAME = guiTexture("energy_frame");
    private static final ResourceLocation ENERGY_FILL = guiTexture("energy_fill");
    private static final ResourceLocation AWAKENING_FRAME = guiTexture("awakening_frame");
    private static final ResourceLocation AWAKENING_FILL = guiTexture("awakening_fill");
    private static final ResourceLocation CREST = guiTexture("santoryu_hud_crest");
    private static final ResourceLocation BASE = guiTexture("santoryu_hud_base");
    private static final ResourceLocation RAIL = guiTexture("santoryu_hud_rail");
    private static final ResourceLocation DIVIDER = guiTexture("santoryu_hud_divider");
    private static final ResourceLocation PANEL_MATERIALS = guiTexture("santoryu_hud_materials");
    private static final int MATERIAL_WIDTH = 96;
    private static final int MATERIAL_HEIGHT = 40;
    private static boolean aiming, rushHeld, rushReleasePending;
    public static boolean sightActive(){return BlindSight.active();}
    static boolean autoRunning(){var s=local();return !rushReleasePending && s!=null && s.skill()==5;}
    private static final Map<UUID,Long> pendingLandings=new HashMap<>();
    static void queueLanding(UUID id){pendingLandings.put(id,MC.level.getGameTime());}
    private static final int SLIDE_TICKS = 8;
    private static final int SLIDE_DISTANCE = 72;
    private static boolean panelVisible;
    private static double slideStart;
    private static double slideFrom;
    private static double meterTick;
    private static float energyFill, awakeningFill;
    private static final Minecraft MC=Minecraft.getInstance();
    private SkillHud(){}

    private static ResourceLocation guiTexture(String name) {
        return ResourceLocation.fromNamespaceAndPath("fightingarts", "textures/gui/" + name + ".png");
    }

    public static void install() {
        SantoryuSkills.clientStatus=s->{
            var before=STATUS.put(s.player(),s);
            if(MC.player!=null && s.player().equals(MC.player.getUUID()))BlindSight.statusChanged(before,s);
            if(MC.player!=null && s.player().equals(MC.player.getUUID()) && s.skill()!=5)rushReleasePending=false;
        };
        SantoryuSkills.clientLeapFlight=p->{var status=STATUS.get(p.getUUID());return status!=null && status.leapFlight();};
        SantoryuSkills.clientRushing=p->p==MC.player && local()!=null && local().skill()==5;
        SantoryuSkills.clientSight=s->{
            if(s.tint()==0)BlindSight.targets.remove(s.entity());
            else BlindSight.targets.put(s.entity(),s);
        };
        SantoryuSkills.clientEffect=SkillEffects::add;
        var bus=net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener(SkillHud::render);
        bus.addListener(BlindSight::world);
        bus.addListener(BlindSight::hideLayer);
        bus.addListener(BlindSight::hideVanillaOutline);
        bus.addListener(BlindSight::captureBackdrop);
        bus.addListener(SkillHud::tick);
        bus.addListener(SkillEffects::render);
        bus.addListener(SkillImpact::camera);
        bus.addListener(SkillImpact::fov);
    }
    public static SantoryuSkills.Status local(){return MC.player==null?null:STATUS.get(MC.player.getUUID());}
    public static boolean movementLocked() {
        var status=local();
        return MC.player!=null && SantoryuSkills.stunned(MC.player) || status!=null&&(status.skill()==3||status.skill()==4||status.skill()==6);
    }
    public static boolean aimLocked() {
        var status=local();
        return status!=null && MC.level!=null && status.skill()==3 && MC.level.getGameTime()-status.start()>=43;
    }
    public static void keys() {
        if(BlindSight.active()){
            while(MC.options.keyUse.consumeClick())BlindSight.toggleDamageReadout();
        }
        var style=StyleClient.local();
        for(int i=0;i<MC.options.keyHotbarSlots.length;i++){
            while(MC.options.keyHotbarSlots[i].consumeClick()){
                if(i==5 && local()!=null && local().visionStart()>=0){SantoryuSkills.request(6,0);continue;}
                if(style==null || !style.mode().allowsSkill(i+1) || !style.phase().equals("ACTIVE") || DefenseClient.localGuarding())continue;
                if(sightActive() && i!=3)continue;
                if(i==3){
                    if (style.mode() != StyleMode.SIMPLE || simpleLeapReady(local(), MC.level.getGameTime())) aiming=true;
                }
                else if(i==4){if(!rushHeld){rushReleasePending=false;SantoryuSkills.request(5,0);}rushHeld=true;}
                else SantoryuSkills.request(i+1,0);
            }
        }
    }
    private static void tick(ClientTickEvent.Post event) {
        if(MC.level==null){
            rushReleasePending=false;rushHeld=false;STATUS.clear();BlindSight.clear();pendingLandings.clear();
            SkillEffects.clear();
            aiming = false;
            panelVisible = false;
            slideFrom = 0;
            return;
        }
        pendingLandings.entrySet().removeIf(entry->{
            var player=MC.level.getPlayerByUUID(entry.getKey());
            if(player instanceof net.minecraft.client.player.AbstractClientPlayer client && client.onGround()) {
                AnimationRenderer.skillLanded(client);return true;
            }
            return MC.level.getGameTime()-entry.getValue()>10;
        });
        var style=StyleClient.local();
        if(rushHeld && (!MC.options.keyHotbarSlots[4].isDown() || MC.screen!=null || style==null || style.mode()!=StyleMode.AWAKENED)){
            rushHeld=false;rushReleasePending=true;SantoryuSkills.request(-5,0);
            if(local()!=null && local().skill()==5)MC.player.setDeltaMovement(0,MC.player.getDeltaMovement().y,0);
        }
        if(MC.screen!=null || style==null || !style.mode().allowsSkill(4) || !style.phase().equals("ACTIVE") || DefenseClient.localGuarding()){aiming=false;return;}
        if (aiming && !MC.options.keyHotbarSlots[3].isDown()) {
            SantoryuSkills.request(4,0);
            aiming = false;
        }
    }
    static boolean simpleLeapReady(SantoryuSkills.Status status, double now) {
        return status != null && status.ready(3) <= now && status.hasSimpleCharge(now);
    }
    static boolean aiming(){return aiming;}
    public static void sprite(GuiGraphics g,int index,int x,int y,int width,int height) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        float cell = 1254f / 4;
        g.blit(ATLAS, x, y, width, height, (index % 4) * cell, (index / 4) * cell, 313, 313, 1254, 1254);
    }
    static void skillIcon(GuiGraphics graphics,int index,int x,int y,int w,int h){
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        if (index < 4) {
            graphics.blit(ICONS, x, y, w, h, index * 16, 0, 16, 16, 64, 16);
        } else {
            graphics.blit(AWAKENED_ICONS[index - 4], x, y, w, h, 0, 0, 16, 16, 16, 16);
        }
    }
    private static void render(RenderGuiEvent.Post event) {
        if(MC.level==null)return;
        var style = StyleClient.local();
        var status = local();
        boolean show = style != null && style.mode().hasSkills() && style.phase().equals("ACTIVE")
                && status != null && !MC.player.isSpectator();
        double now = MC.level.getGameTime() + (double) event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if (show != panelVisible) {
            double transitionTick = style == null ? now : Math.min(now, style.phaseStart());
            slideFrom = panelVisibility(transitionTick);
            slideStart = transitionTick;
            panelVisible = show;
            if (show && slideFrom == 0) {
                meterTick = now;
                energyFill = status.energy() / (float) SantoryuSkills.MAX_ENERGY;
                awakeningFill = awakeningFill(style, status, now);
            }
        }
        if (MC.options.hideGui) return;
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if(status!=null && BlindSight.normalHudCover(now)>=1){
            BlindSight.hud(event.getGuiGraphics(),status,now);return;
        }
        SkillEffects.screen(event.getGuiGraphics(), partialTick);
        double visibility = panelVisibility(now);
        GuiGraphics g=event.getGuiGraphics();
        boolean awakened = style != null && style.mode() == StyleMode.AWAKENED;
        int rows = awakened ? 7 : 4;
        int extra = (rows - 4) * 24;
        int x=g.guiWidth()-64+(int)Math.round((1-visibility)*SLIDE_DISTANCE),y=g.guiHeight()-157-extra;
        if (style != null && style.phase().equals("ACTIVE") && !MC.player.isSpectator()) {
            if (show && visibility > 0) ConsumableClient.renderSlot(g, x, y + 3, partialTick);
            if (!style.mode().hasSkills()) ConsumableClient.renderSlot(g, g.guiWidth() - 64, g.guiHeight() - 24, partialTick);
        }
        if (status == null || visibility <= 0) return;
        float energy = status.energy() / (float) SantoryuSkills.MAX_ENERGY;
        float awakening = awakeningFill(style, status, now);
        double elapsed = Math.max(0, now - meterTick);
        float easing = (float) -Math.expm1(-elapsed / 2.5);
        energyFill += (energy - energyFill) * easing;
        awakeningFill += (awakening - awakeningFill) * easing;
        meterTick = now;
        if(status.skill()==7 && status.preparationTicks()>0)dragonReticle(g,now-status.start());
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.setColor(.77f, .77f, .77f, 1);
        drawPanelMaterial(g, x + 6, y + 11, 3);
        for (int skill = 0; skill < rows; skill++) {
            drawMaterial(g, x + 6, y + 14 + skill * 24, 24, skill % 2, (skill / 2) % 2);
        }
        drawPanelMaterial(g, x + 6, y + 110 + extra, 32);
        g.setColor(1, 1, 1, 1);
        ornament(g, RAIL, x + 3, y + 15, 4, 126 + extra, 12, 96);
        ornament(g, RAIL, x + 53, y + 15, 4, 126 + extra, 12, 96);
        for (int i = 0; i < rows; i++) {
            int row = y + 14 + i * 24;
            int size = 18, center = x + 30;
            int left = center - size / 2, top = row + 2;
            if (i >= SantoryuSkills.COST.length) {
                drawNumber(g, Integer.toString(i + 1), x + 13, row + 11, 0xff938774);
                drawNumber(g, "—", center, row + 11, 0xff817484);
                ornament(g, DIVIDER, x + 3, row + 21, 54, 4, 108, 12);
                continue;
            }
            long remaining = Math.max(0, status.ready(i) - MC.level.getGameTime());
            if (remaining > 0) {
                int readyHeight = (int) (size * (1 - Math.min(1, remaining / (double) SantoryuSkills.COOLDOWN[i])));
                g.setColor(.45f, .45f, .45f, 1);
                skillIcon(g, i, left, top, size, size);
                g.setColor(1, 1, 1, 1);
                if (readyHeight > 0) {
                    g.enableScissor(left, top, left + size, top + readyHeight);
                    skillIcon(g, i, left, top, size, size);
                    g.disableScissor();
                }
                drawNumber(g, Long.toString((remaining + 19) / 20), center, row + 11, 0xffffffff);
            } else {
                skillIcon(g, i, left, top, size, size);
            }
            drawNumber(g, Integer.toString(i + 1), x + 13, row + 11, 0xfff3d394);
            drawNumber(g, (i==4 || i==5 ? "10/s" : Integer.toString(SantoryuSkills.COST[i])), x + 46, row + 11,
                    status.energy()>=SantoryuSkills.COST[i]?0xff81efa7:0xffb86a72);
            ornament(g, DIVIDER, x + 3, row + 21, 54, 4, 108, 12);
        }
        meter(g, ENERGY_FRAME, ENERGY_FILL, x + 8, y + 111+extra, 44, 9, energyFill);
        g.drawCenteredString(MC.font, status.energy()+"/"+SantoryuSkills.MAX_ENERGY, x+30, y+120+extra, 0xffe5eadb);
        meter(g, AWAKENING_FRAME, AWAKENING_FILL, x + 8, y + 130+extra, awakened ? 44 : 35, 8, awakeningFill);
        if (!awakened) g.drawCenteredString(MC.font, "V", x+47, y+130+extra, 0xffcda9f0);
        ornament(g, BASE, x + 1, y + 138+extra, 58, 13, 116, 32);
        ornament(g, CREST, x - 2, y + 3, 64, 22, 128, 60);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        BlindSight.fadeHud(g,BlindSight.normalHudCover(now));
        if(BlindSight.symbolOpacity(now)>0)BlindSight.hud(g,status,now);
    }

    private static float awakeningFill(StyleState style, SantoryuSkills.Status status, double now) {
        return style != null && style.mode() == StyleMode.AWAKENED ? style.awakeningRemaining(now) : status.awakening() / 100f;
    }

    private static void dragonReticle(GuiGraphics graphics,double age) {
        if(age<0 || age>=40)return;
        int x=graphics.guiWidth()/2,y=graphics.guiHeight()/2;
        boolean locked=age>=SantoryuSkills.DRAGON_AIM_TICKS;
        int gap=locked?8:8+(int)Math.round(5*(1-age/SantoryuSkills.DRAGON_AIM_TICKS));
        int color=locked?0xffd7b4ff:0xff97ffbb;
        for(int side:new int[]{-1,1}){
            int a=x+side*gap;
            graphics.fill(a-2,y,a+3,y+1,color);
            graphics.fill(a,y-2,a+1,y+3,color);
        }
    }

    private static void drawNumber(GuiGraphics graphics, String text, int centerX, int centerY, int color) {
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 0);
        float scale=text.endsWith("/s")?.64f:.8f;
        pose.scale(scale,scale,1);
        graphics.drawCenteredString(MC.font, text, 0, -4, color);
        pose.popPose();
    }

    private static double panelVisibility(double now) {
        double destination = panelVisible ? 1 : 0;
        return slideFrom + (destination - slideFrom) * AttackHotbar.ease((now - slideStart) / SLIDE_TICKS);
    }

    private static void drawPanelMaterial(GuiGraphics graphics, int x, int y, int height) {
        drawMaterial(graphics, x, y, height, 0, 2);
    }

    private static void drawMaterial(GuiGraphics graphics, int x, int y, int height, int column, int row) {
        graphics.blit(PANEL_MATERIALS, x, y, 48, height, column * MATERIAL_WIDTH, row * MATERIAL_HEIGHT,
                MATERIAL_WIDTH, MATERIAL_HEIGHT, 2 * MATERIAL_WIDTH, 3 * MATERIAL_HEIGHT);
    }

    private static void ornament(GuiGraphics graphics, ResourceLocation texture, int x, int y,
                                 int width, int height, int textureWidth, int textureHeight) {
        graphics.blit(texture, x, y, width, height, 0, 0, textureWidth, textureHeight, textureWidth, textureHeight);
    }

    private static void meter(GuiGraphics g, ResourceLocation frame, ResourceLocation fill, int x, int y,
                              int width, int height, float amount) {
        ornament(g, frame, x, y, width, height, 144, 32);
        int cap = Math.round(width * .11f);
        int channelX = x + cap, channelY = y + 2, channelWidth = width - 2 * cap, channelHeight = height - 4;
        int reveal = Math.round(channelWidth * Math.clamp(amount, 0, 1));
        if (reveal == 0) return;
        g.enableScissor(channelX, channelY, channelX + reveal, channelY + channelHeight);
        ornament(g, fill, channelX, channelY, channelWidth, channelHeight, 120, 16);
        g.disableScissor();
    }
}
