package fightingarts.client;
import fightingarts.*;
import fightingarts.client.mixin.MultiPlayerGameModeInvoker;
import net.minecraft.client.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.*;
import org.lwjgl.glfw.GLFW;
public final class StyleClient {
 private static final Minecraft MC=Minecraft.getInstance();
 private static final KeyMapping TOGGLE=new KeyMapping("key.fightingarts.toggle",GLFW.GLFW_KEY_X,"Fighting Arts");
 private static EntryCamera entryCamera;
 private static long entryImpactTick = Long.MIN_VALUE;
 public static void install(IEventBus bus){
  try {Class<?> api=Class.forName("punchymoves.api.client.AnimationIntent"),kind=Class.forName("punchymoves.api.client.AnimationIntent$Kind");java.util.function.Function<Object,Object> reader=p->{StyleState s=AnimationRenderer.STATES.get(((net.minecraft.world.entity.Entity)p).getUUID());String name=s!=null&&!s.phase().equals("OFF")?"SPECIAL_ACTION":"UNSPECIFIED";for(Object k:kind.getEnumConstants())if(k.toString().equals(name))return k;return null;};api.getMethod("register",java.util.function.Function.class).invoke(null,reader);}catch(ReflectiveOperationException ignored){}
  bus.addListener((RegisterKeyMappingsEvent e)->e.register(TOGGLE));
  bus.addListener(SwordLayer::register);
  bus.addListener((EntityRenderersEvent.AddLayers event)->MoonMarkLayer.register(event));
  SkillHud.install();
  StyleNetwork.clientDamageReceiver = DamageNumbers::add;
  StyleNetwork.clientDashReceiver = impulse -> {
   if (MC.player != null) MC.player.setDeltaMovement(impulse.x(), MC.player.getDeltaMovement().y, impulse.z());
  };
  NeoForge.EVENT_BUS.addListener(DamageNumbers::render);
  StyleNetwork.clientReceiver=s-> {
   StyleState old=AnimationRenderer.STATES.put(s.playerId(),s);
   if (MC.level != null && MC.level.getPlayerByUUID(s.playerId()) instanceof net.minecraft.client.player.AbstractClientPlayer player)
    AnimationRenderer.entryStateChanged(player, old, s);
   AnimationRenderer.recordAttack(old,s);
   WorldEffects.stateChanged(s);
   if (MC.player != null && s.playerId().equals(MC.player.getUUID())) {
    AttackHotbar.stateChanged(old, s);
    if (s.phase().equals("DRAWING") || s.phase().equals("UPGRADING")) {
     startEntryCamera();
     entryImpactTick = s.phaseStart() + StyleService.DRAW_TICKS;
    } else {
     restore();
     if (s.phase().equals("OFF") || s.phase().equals(StyleService.SIMPLE_DRAWING)) entryImpactTick = Long.MIN_VALUE;
    }
   }
  };
  NeoForge.EVENT_BUS.addListener(StyleClient::tick); NeoForge.EVENT_BUS.addListener(StyleClient::hud);NeoForge.EVENT_BUS.addListener(WorldEffects::render);
  NeoForge.EVENT_BUS.addListener(StyleClient::lockPerspective);
  NeoForge.EVENT_BUS.addListener(StyleClient::lockMovement);
  NeoForge.EVENT_BUS.addListener(StyleClient::removeSheathingZoom);
  NeoForge.EVENT_BUS.addListener(WeaponSlotsHud::render);
  NeoForge.EVENT_BUS.addListener(AttackHotbar::render);
  NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event)->{
   if(event.getEntity()==MC.player && attackMode())event.setUseItem(net.neoforged.neoforge.common.util.TriState.FALSE);
  });
  NeoForge.EVENT_BUS.addListener((InputEvent.MouseScrollingEvent e) -> {
   if (MC.screen == null && attackMode()) e.setCanceled(true);
  });
  NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered e)->{
   if(!e.isAttack())return;
   boolean leapHit=SkillHud.local()!=null && SkillHud.local().skill()==4 && MC.player!=null && !MC.player.onGround();
   if(SkillHud.local()!=null && SkillHud.local().skill()!=0 && !leapHit){e.setSwingHand(false);e.setCanceled(true);return;}
   if (leapHit && !(MC.hitResult instanceof net.minecraft.world.phys.EntityHitResult)) {
    StyleNetwork.requestAttack(false);e.setSwingHand(false);e.setCanceled(true);return;
   }
   StyleState s=local();
   long age=MC.level==null||s==null?0:MC.level.getGameTime()-s.comboStart();
   if(s!=null&&StyleService.isAttackLocked(s.phase(),s.combo(),age)){e.setSwingHand(false);e.setCanceled(true);return;}
   if(s!=null&&s.phase().equals("ACTIVE")&&MC.hitResult!=null&&MC.hitResult.getType()==net.minecraft.world.phys.HitResult.Type.MISS) StyleNetwork.requestAttack(false);
  });
 }
 public static boolean hasFallingAttack() {
  StyleState state = local();
  return state != null && state.phase().equals("ACTIVE") && state.fallingAttackStart() >= 0;
 }
 public static StyleState local(){return MC.player==null?null:AnimationRenderer.STATES.get(MC.player.getUUID());}
 private static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e){
  if(MC.level==null){restore();AttackHotbar.reset();entryImpactTick=Long.MIN_VALUE;AnimationRenderer.STATES.clear();AnimationRenderer.clearAttacks();DamageNumbers.clear();return;}
  StyleState s=local(); boolean drawing=isCinematic();
  if(MC.player!=null) AnimationRenderer.tickMotion(MC.player);
  if(MC.player!=null&&(s==null||!s.phase().equals("ACTIVE")||!MC.options.getCameraType().isFirstPerson())) AnimationRenderer.cancelHandGesture(MC.player);
  if(MC.player!=null&&(s==null||s.phase().equals("OFF")||!MC.options.getCameraType().isFirstPerson())) AnimationRenderer.resetHandBlend(MC.player);
  if(drawing){
   startEntryCamera();
   if (entryCamera != null) {
    MC.player.setYBodyRot(entryCamera.heading);
    MC.player.yBodyRotO = entryCamera.heading;
   }
  }
  else restore();
 }

 public static boolean attackMode() {
  StyleState state = local();
  return state != null && !state.phase().equals("OFF");
 }

 public static void handleCombatKeys() {
  if (MC.player == null || MC.player.isSpectator()) return;
  boolean active = attackMode();
  if (active) {
   SkillHud.keys();
   discardClicks(MC.options.keyDrop);
   discardClicks(MC.options.keyPickItem);
  }
  if (active || (MC.player.getMainHandItem().isEmpty() && MC.player.getOffhandItem().isEmpty())) {
   while (MC.options.keySwapOffhand.consumeClick()) {
    if (active || StyleService.findSwords(MC.player).size() == StyleWeapons.SANTORYU_SLOTS) {
     // Send any pending scroll selection before the server checks the selected stack.
     ((MultiPlayerGameModeInvoker) MC.gameMode).fightingarts$ensureCarriedItemSent();
     StyleNetwork.requestToggle(StyleMode.SIMPLE);
    }
   }
  }
  while (TOGGLE.consumeClick()) {
   if (active || MC.player.getOffhandItem().isEmpty()) StyleNetwork.requestToggle(StyleMode.COMMON);
  }
 }

 private static void discardClicks(KeyMapping key) {
  while (key.consumeClick()) {
   // Reserve hotbar controls for the style while it is active.
  }
 }

 public static boolean isCinematic() {
  StyleState state = local();
  return MC.level != null && state != null && (state.phase().equals("DRAWING") || state.phase().equals("UPGRADING"));
 }

 private static void lockMovement(MovementInputUpdateEvent event) {
  if (event.getEntity() != MC.player || !(isCinematic() || SkillHud.movementLocked() || local()!=null && local().phase().equals("UPGRADING"))) return;
  var input = event.getInput();
  input.forwardImpulse = input.leftImpulse = 0;
  input.up = input.down = input.left = input.right = false;
  input.jumping = input.shiftKeyDown = false;
 }

 private static void startEntryCamera() {
  if (entryCamera != null) return;
  if (MC.options.getCameraType().isFirstPerson() && AttackHotbar.loweringVanillaHand(local(), MC.level.getGameTime())) return;
  entryCamera = new EntryCamera(MC.options.getCameraType(), MC.player.getYRot());
  MC.options.setCameraType(CameraType.THIRD_PERSON_BACK);
  MC.player.setYBodyRot(entryCamera.heading);
  MC.player.yBodyRotO = entryCamera.heading;
 }

 private static void restore() {
  if (entryCamera == null) return;
  MC.options.setCameraType(entryCamera.previous);
  entryCamera = null;
 }

 private static void lockPerspective(ClientTickEvent.Pre event) {
  if (isCinematic()) {
   while (MC.options.keyTogglePerspective.consumeClick()) {
    // Discard perspective changes before Minecraft processes them.
   }
  }
 }

 public static double[] camera(float partialTick) {
  if (!isCinematic() || entryCamera == null || MC.getCameraEntity() != MC.player) return null;
  double elapsed = MC.level.getGameTime() - local().phaseStart() + (double)partialTick;
  return entryCamera.pose(elapsed, MC.player.getPosition(partialTick));
 }

 static double entryImpactAge(float partialTick) {
  if (MC.level == null || entryImpactTick == Long.MIN_VALUE) return Double.NEGATIVE_INFINITY;
  return MC.level.getGameTime() - entryImpactTick + (double)partialTick;
 }

 private record EntryCamera(CameraType previous, float heading) {
  private double[] pose(double elapsed, Vec3 playerPosition) {
   double approachTicks = 20;
   double drawTicks = StyleService.DRAW_TICKS;
   double approachStart = drawTicks - approachTicks;
   double progress = Math.clamp(elapsed / approachStart, 0, 1);
   double approach = Math.clamp((elapsed - approachStart) / approachTicks, 0, 1);
   double ease = approach * approach * (3 - 2 * approach);
   // Ease into the frontal framing while the camera pulls back and moves in again.
   double orbit = progress * progress * (3 - 2 * progress);
   double yaw = heading + 50 - 96 * orbit + 42 * ease;
   double angle = Math.toRadians(yaw);
   double zoomOut = Math.sin(Math.PI * approach);
   double radius = 3 + .85 * zoomOut - .30 * ease;
   double height = 1.45 + .75 * ease;
   double lookYaw = yaw + 180;
   double pitch = Math.toDegrees(Math.atan2(height - 1.0, radius));
   double sinceImpact = elapsed - drawTicks;
   double shakeTicks = 8;
   if (sinceImpact >= 0 && sinceImpact < shakeTicks) {
    double fade = 1 - sinceImpact / shakeTicks;
    double strength = fade * fade;
    height += Math.sin(sinceImpact * 2.9) * .06 * strength;
    lookYaw += Math.sin(sinceImpact * 2.3) * 2.4 * strength;
    pitch += Math.sin(sinceImpact * 3.4) * 1.7 * strength;
   }
   return new double[]{playerPosition.x - Math.sin(angle) * radius, playerPosition.y + height,
           playerPosition.z + Math.cos(angle) * radius, lookYaw, pitch};
  }
 }

 private static void removeSheathingZoom(ComputeFovModifierEvent event) {
  if (event.getPlayer() != MC.player) return;
  var player = event.getPlayer();
  var movement = player.getAttribute(Attributes.MOVEMENT_SPEED);
  if (movement == null || player.getAbilities().getWalkingSpeed() <= 0) return;
  var slowdown = movement.getModifier(StyleService.SHEATH_SLOW);
  if (slowdown == null) return;

  // Undo the style's multiplier for FOV only, retaining other FOV effects.
  double speedRatio = movement.getValue() / player.getAbilities().getWalkingSpeed();
  double withoutSlowdown = speedRatio / (1 + slowdown.amount());
  double correction = (withoutSlowdown - speedRatio) / (speedRatio + 1);
  event.setNewFovModifier(event.getNewFovModifier()
          + (float)(event.getFovModifier() * correction * MC.options.fovEffectScale().get()));
 }
 private static void hud(RenderGuiEvent.Post e){
  if(MC.level==null)return;
  boolean draw=isCinematic();
  float partialTick = e.getPartialTick().getGameTimeDeltaPartialTick(false);
  double impactAge=entryImpactAge(partialTick);
  boolean showTitle=impactAge>=0&&impactAge<35;
  if(!draw&&!showTitle)return;
  GuiGraphics g=e.getGuiGraphics();int w=g.guiWidth(),h=g.guiHeight();
  com.mojang.blaze3d.systems.RenderSystem.enableBlend();
  if (draw) {
   double age = MC.level.getGameTime() - local().phaseStart() + (double) partialTick;
   int barHeight = (int) Math.round(h * .1 * AttackHotbar.ease(age / AttackHotbar.SLIDE_TICKS));
   g.fill(0, 0, w, barHeight, 0xff000000);
   g.fill(0, h - barHeight, w, h, 0xff000000);
  }
  if(showTitle){
   float alpha=(float)Math.clamp((35-impactAge)/10,0,1);
   int size=Math.min(h/3,112),cx=w/2,cy=h/2;
   g.setColor(1,1,1,alpha);
   image(g,"style_symbol",cx-size/2,cy-size-12,size);
   g.setColor(1,1,1,1);
   int color=((int)(alpha*255)<<24)|0xffe8c5;
   if(alpha>0)g.drawCenteredString(MC.font,Component.literal("§lEstilo Três Espadas"),cx,cy+18,color);
  }
 }
 private static void image(GuiGraphics g,String name,int x,int y,int size){g.blit(ResourceLocation.fromNamespaceAndPath("fightingarts","textures/gui/"+name+".png"),x,y,0,0,size,size,size,size);}
}
