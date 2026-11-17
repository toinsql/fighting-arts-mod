package fightingarts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import fightingarts.StyleNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** Local feedback for damage confirmed by the server. */
final class DamageNumbers {
    private static final int LIFETIME_TICKS = 18;
    private static final int MAX_NUMBERS = 64;
    private static final int COLOR = 0xffe3a6;
    private static final Deque<Number> NUMBERS = new ArrayDeque<>();
    private static ClientLevel level;

    private DamageNumbers() {}

    static void add(StyleNetwork.DamageNumber packet) {
        ClientLevel current = Minecraft.getInstance().level;
        if (current == null) return;
        if (current != level) {
            clear();
            level = current;
        }

        var random = ThreadLocalRandom.current();
        double angle = random.nextDouble(2 * Math.PI);
        Vec3 drift = new Vec3(Math.cos(angle), 0, Math.sin(angle)).scale(random.nextDouble(0.18, 0.32));
        float amount = packet.amount();
        String text = amount < 0.05f ? "<0.1"
                : Math.abs(amount - Math.round(amount)) < 0.05f ? Integer.toString(Math.round(amount))
                : String.format(Locale.ROOT, "%.1f", amount);
        if (NUMBERS.size() == MAX_NUMBERS) NUMBERS.removeFirst();
        NUMBERS.addLast(new Number(text, packet.position().add(drift.scale(0.25)), drift, current.getGameTime()));
    }

    static void clear() {
        NUMBERS.clear();
        level = null;
    }

    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || NUMBERS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            clear();
            return;
        }

        double now = level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        while (!NUMBERS.isEmpty() && now - NUMBERS.peekFirst().start >= LIFETIME_TICKS) NUMBERS.removeFirst();
        if (NUMBERS.isEmpty()) return;

        PoseStack pose = event.getPoseStack();
        var camera = event.getCamera();
        var buffers = mc.renderBuffers().bufferSource();
        for (Number number : NUMBERS) {
            double age = Math.clamp((now - number.start) / LIFETIME_TICKS, 0, 1);
            double rise = 1 - Math.pow(1 - age, 2);
            double fade = Math.clamp((1 - age) / 0.45, 0, 1);
            int alpha = (int)(255 * fade * fade * (3 - 2 * fade));
            if (alpha < 4) continue;

            // Grow quickly on impact, then settle while drifting upward.
            double pop = age / 0.12;
            float scale = 0.027f * (float)(1 + 0.5 * pop * Math.exp(1 - pop) - 0.22 * Math.exp(-4 * pop));
            Vec3 position = number.origin.add(number.drift.scale(rise)).add(0, 0.75 * rise, 0)
                    .subtract(camera.getPosition());
            pose.pushPose();
            pose.translate(position.x, position.y, position.z);
            pose.mulPose(camera.rotation());
            pose.scale(scale, -scale, scale);
            mc.font.drawInBatch(number.text, -mc.font.width(number.text) / 2f, -4,
                    (alpha << 24) | COLOR, true, pose.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
        buffers.endBatch();
    }

    private record Number(String text, Vec3 origin, Vec3 drift, long start) {}
}
