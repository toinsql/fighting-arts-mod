package fightingarts.client;

import fightingarts.SantoryuSkills;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ViewportEvent;

/** Camera motion is added to the rendered view, preserving the player's aim. */
public final class SkillImpact {
    private static final Minecraft MC = Minecraft.getInstance();
    private static double impactTick = Double.NEGATIVE_INFINITY;

    private SkillImpact() {}

    static void trigger() { impactTick = MC.level.getGameTime(); }
    static void clear() { impactTick = Double.NEGATIVE_INFINITY; }

    public record Motion(double yaw, double pitch, double roll, double fov) {}

    public static Motion sample(double skillAge, double impactAge) {
        double progress = Math.clamp((skillAge - SantoryuSkills.SPIN_START * 20) / (43 - SantoryuSkills.SPIN_START * 20), 0, 1);
        double charge = skillAge >= SantoryuSkills.SPIN_START * 20 && skillAge < 43
                ? .08 + 1.12 * progress * progress : 0;
        double impact = impactAge >= 0 && impactAge < 10 ? Math.pow(1 - impactAge / 10, 2) : 0;
        double impactTime = Double.isFinite(impactAge) ? impactAge : 0;
        double yaw = charge * Math.sin(skillAge * 2.31) + 4.5 * impact * Math.sin(impactTime * 2.7 + .7);
        double pitch = charge * .8 * Math.sin(skillAge * 3.17) + impact * (3.5 * Math.sin(impactTime * 3.4) - 2.5);
        double roll = charge * .55 * Math.sin(skillAge * 2.73) + 3 * impact * Math.sin(impactTime * 2.9 + .5);
        return new Motion(yaw, pitch, roll, -3 * progress * charge / 1.2 + 11 * impact);
    }

    private static Motion current(double partial) {
        if (MC.player == null || MC.level == null || MC.isPaused()) return new Motion(0, 0, 0, 0);
        var status = SkillHud.local();
        double now = MC.level.getGameTime() + partial;
        double age = status != null && status.skill() == 3 ? now - status.start() : -1;
        return sample(age, now - impactTick);
    }

    static void camera(ViewportEvent.ComputeCameraAngles event) {
        if (MC.getCameraEntity() != MC.player) return;
        Motion motion = current(event.getPartialTick());
        event.setYaw(event.getYaw() + (float) motion.yaw);
        event.setPitch(event.getPitch() + (float) motion.pitch);
        event.setRoll(event.getRoll() + (float) motion.roll);
    }

    static void fov(ViewportEvent.ComputeFov event) {
        if (MC.getCameraEntity() == MC.player)
            event.setFOV(event.getFOV() + current(event.getPartialTick()).fov * MC.options.fovEffectScale().get());
    }
}
