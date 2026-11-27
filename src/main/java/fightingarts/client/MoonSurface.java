package fightingarts.client;

import com.mojang.blaze3d.vertex.PoseStack;
import fightingarts.MoonSlashSurface;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

final class MoonSurface {
    static List<MoonSlashSurface.Patch> project(Vec3 eye, Vec3 end) {
        var player = Minecraft.getInstance().player;
        return player == null ? List.of() : MoonSlashSurface.project(player,eye,end);
    }

    static void render(PoseStack pose, MultiBufferSource buffers, List<MoonSlashSurface.Patch> patches, float alpha, float reveal) {
        if (patches.isEmpty()) return;
        var out = buffers.getBuffer(SkillMesh.layer(SkillMesh.FISSURE));
        var level = Minecraft.getInstance().level;
        patches.removeIf(patch -> level.getBlockState(patch.block()) != patch.state());
        for (var patch : patches) {
            var clipped = MoonSlashSurface.clip(patch.vertices(),p->reveal-p.u());
            var transformed = new ArrayList<DecalGeometry.Vertex>();
            Vec3 normal = patch.normal();
            for (var vertex : clipped) {
                Vec3 p = vertex.position().add(normal.scale(.012));
                Vector3f pos = pose.last().pose().transformPosition((float)p.x,(float)p.y,(float)p.z,new Vector3f());
                transformed.add(new DecalGeometry.Vertex(new Vec3(pos),vertex.u(),vertex.v()));
            }
            DecalGeometry.emit(out,transformed,alpha,normal);
        }
    }
}
