package fightingarts;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/** The decal and the awakened fracture use the same projection of the cut's texture. */
public final class MoonSlashSurface {
    private static final int COLUMNS = 96;
    private static final int ROWS = 24;
    private static final double LENGTH = 9.2;
    private static final double WIDTH = .65;
    private static final int[][] SAMPLES = {{1,1},{0,0},{2,0},{2,2},{0,2}};

    private MoonSlashSurface() {}

    public record Vertex(Vec3 position, double u, double v) {
        Vertex lerp(Vertex other, double t) {
            return new Vertex(position.lerp(other.position,t),u+(other.u-u)*t,v+(other.v-v)*t);
        }
    }

    public record Patch(List<Vertex> vertices, Direction face, BlockPos block, BlockState state) {
        public Vec3 normal() { return Vec3.atLowerCornerOf(face.getNormal()); }
    }

    public static List<Patch> project(Entity source, Vec3 eye, Vec3 end) {
        var world = source.level();
        Vec3 forward = end.subtract(eye);
        Vec3 right = forward.cross(new Vec3(0,1,0)).normalize();
        Vec3 up = right.cross(forward).normalize();
        Vec3 axis = right.scale(.82).subtract(up.scale(.57)).scale(LENGTH);
        Vec3 across = right.scale(.57).add(up.scale(.82)).scale(WIDTH);
        var projection = new Projection(eye, end, axis, across);
        List<Patch> patches = new ArrayList<>();

        for (int x=0; x<COLUMNS; x++) for (int y=0; y<ROWS; y++) {
            if (!Ink.CELLS[x][y]) continue;
            double u = x/(double)COLUMNS, v = y/(double)ROWS;
            var contacts = new ArrayList<BlockHitResult>(SAMPLES.length);
            for (int[] sample : SAMPLES) {
                Vec3 tip = projection.point(u+sample[0]*.5/COLUMNS,v+sample[1]*.5/ROWS);
                var contact = world.clip(new ClipContext(eye,tip,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,source));
                if (contact.getType() != HitResult.Type.MISS && contacts.stream().noneMatch(previous ->
                        previous.getBlockPos().equals(contact.getBlockPos()) && previous.getDirection() == contact.getDirection())) {
                    contacts.add(contact);
                }
            }
            for (var contact : contacts) {
                BlockPos pos = contact.getBlockPos();
                BlockState state = world.getBlockState(pos);
                Vec3 normal = Vec3.atLowerCornerOf(contact.getDirection().getNormal());
                var shape = state.getCollisionShape(world,pos);
                for (var local : shape.toAabbs()) {
                    var box = local.move(pos);
                    if (!box.inflate(.001).contains(contact.getLocation())) continue;
                    List<Vertex> polygon = projection.patch(contact,normal,box,u,v);
                    if (polygon.size() >= 3) patches.add(new Patch(polygon,contact.getDirection(),pos,state));
                }
            }
        }
        return patches;
    }

    private record Projection(Vec3 eye, Vec3 end, Vec3 axis, Vec3 across) {
        Vec3 point(double u, double v) {
            return end.add(axis.scale(u-.5)).add(across.scale(.5-v));
        }

        List<Vertex> patch(BlockHitResult hit, Vec3 normal, AABB box, double u, double v) {
            var polygon = new ArrayList<Vertex>(4);
            double depth = hit.getLocation().subtract(eye).dot(normal);
            for (int corner=1; corner<SAMPLES.length; corner++) {
                double cu = u+SAMPLES[corner][0]*.5/COLUMNS;
                double cv = v+SAMPLES[corner][1]*.5/ROWS;
                Vec3 ray = point(cu,cv).subtract(eye);
                double denominator = ray.dot(normal);
                if (Math.abs(denominator) < .001) return List.of();
                double t = depth/denominator;
                if (t < 0 || t > 1.02) return List.of();
                polygon.add(new Vertex(eye.add(ray.scale(t)),cu,cv));
            }
            List<Vertex> clipped = clip(polygon,p->p.position.x-box.minX);
            clipped = clip(clipped,p->box.maxX-p.position.x);
            clipped = clip(clipped,p->p.position.y-box.minY);
            clipped = clip(clipped,p->box.maxY-p.position.y);
            clipped = clip(clipped,p->p.position.z-box.minZ);
            return clip(clipped,p->box.maxZ-p.position.z);
        }
    }

    public static List<Vertex> clip(List<Vertex> input, ToDoubleFunction<Vertex> distance) {
        if (input.isEmpty()) return input;
        var out = new ArrayList<Vertex>();
        Vertex previous = input.getLast();
        double before = distance.applyAsDouble(previous);
        for (Vertex current : input) {
            double after = distance.applyAsDouble(current);
            if ((before >= 0) != (after >= 0)) out.add(previous.lerp(current,before/(before-after)));
            if (after >= 0) out.add(current);
            previous = current;
            before = after;
        }
        return out;
    }

    private static final class Ink {
        private static final boolean[][] CELLS = load();

        private static boolean[][] load() {
            var mod = ModList.get().getModFileById(FightingArts.MOD_ID).getFile();
            var path = mod.findResource("assets/fightingarts/textures/effect/moon_fissure.png");
            try (var stream = Files.newInputStream(path)) {
                var texture = ImageIO.read(stream);
                var cells = new boolean[COLUMNS][ROWS];
                for (int y=0; y<texture.getHeight(); y++) for (int x=0; x<texture.getWidth(); x++) {
                    if ((texture.getRGB(x,y) >>> 24) < 32) continue;
                    int left = x*COLUMNS/texture.getWidth();
                    int right = ((x+1)*COLUMNS-1)/texture.getWidth();
                    int top = y*ROWS/texture.getHeight();
                    int bottom = ((y+1)*ROWS-1)/texture.getHeight();
                    for (int col=left; col<=right; col++) for (int row=top; row<=bottom; row++) cells[col][row] = true;
                }
                return cells;
            } catch (IOException ex) {
                throw new IllegalStateException("Could not read the moon fissure texture",ex);
            }
        }
    }
}
