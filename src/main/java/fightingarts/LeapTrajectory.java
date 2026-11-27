package fightingarts;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The preview and launch share vanilla gravity and air drag, with no per-tick teleports. */
public final class LeapTrajectory {
    private LeapTrajectory() {}
    public record Plan(Vec3 velocity, Vec3 landing, List<Vec3> path) {}

    public static Plan aim(Player player, Vec3 start, Vec3 eye, Vec3 look) {
        return aim(player,start,eye,look,false);
    }

    public static Plan aim(Player player, Vec3 start, Vec3 eye, Vec3 look, boolean awakened) {
        return aim(player, start, eye, look, awakened ? StyleMode.AWAKENED : StyleMode.COMMON);
    }

    public static Plan aim(Player player, Vec3 start, Vec3 eye, Vec3 look, StyleMode mode) {
        double range = switch (mode) {
            case SIMPLE -> 15;
            case AWAKENED -> 25;
            default -> 20;
        };
        double height = mode == StyleMode.AWAKENED ? 20 : 10;
        Vec3 horizontal = new Vec3(look.x, 0, look.z).normalize();
        if (horizontal.lengthSqr() < .001) horizontal = Vec3.directionFromRotation(0, player.getYRot());
        var hit = player.level().clip(new ClipContext(eye, eye.add(look.scale(80)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 wanted = hit.getType() == HitResult.Type.MISS ? start.add(horizontal.scale(range)) : hit.getLocation();
        if(hit.getType()!=HitResult.Type.MISS && hit.getDirection()!=net.minecraft.core.Direction.UP)
            wanted=wanted.subtract(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(.01));
        double distance = Math.min(range, wanted.subtract(start).horizontalDistance());
        Vec3 direction = new Vec3(wanted.x-start.x, 0, wanted.z-start.z).normalize();
        if (direction.lengthSqr() < .001) direction = horizontal;
        if (hit.getType() != HitResult.Type.MISS && hit.getDirection() == net.minecraft.core.Direction.UP
                && wanted.subtract(start).horizontalDistance() <= range) {
            Plan aimedSurface = fit(player, start, wanted, height);
            if (aimedSurface != null) return aimedSurface;
        }
        // Search from the requested surface back toward the player; never select the underside of a ceiling.
        for (double d=distance; d>=0; d-=.25) {
            Vec3 column = start.add(direction.scale(d));
            double top=start.y+height-.1;
            if(d==distance && hit.getType()!=HitResult.Type.MISS && hit.getDirection()==net.minecraft.core.Direction.UP)
                top=Math.min(top,wanted.y+.02);
            var floor = player.level().clip(new ClipContext(new Vec3(column.x,top,column.z), column.add(0, -40, 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (floor.getType() == HitResult.Type.MISS || floor.getDirection() != net.minecraft.core.Direction.UP) continue;
            Vec3 destination = floor.getLocation();
            Plan plan = fit(player, start, destination, height);
            if (plan != null) return plan;
        }
        return openLaunch(player,start,horizontal,range,height);
    }

    // Open water, a ledge over the void or a tunnel may not have a reachable landing point.
    // Preview the collision-clipped impulse instead of denying the launch.
    private static Plan openLaunch(Player player,Vec3 start,Vec3 forward,double range,double height) {
        Vec3 velocity=solve(start,start.add(forward.scale(range)),player.getGravity(),height).velocity();
        var box=player.getBoundingBox().move(start.subtract(player.position()));
        var vertical=net.minecraft.world.entity.Entity.collideBoundingBox(player,new Vec3(0,velocity.y,0),box,player.level(),List.of());
        velocity=new Vec3(velocity.x,Math.max(0,vertical.y),velocity.z);
        var path=new ArrayList<Vec3>();
        Vec3 position=start, motion=velocity;
        path.add(position);
        for(int tick=0;tick<80;tick++){
            Vec3 allowed=net.minecraft.world.entity.Entity.collideBoundingBox(player,motion,
                    box.move(position.subtract(start)),player.level(),List.of());
            position=position.add(allowed);
            path.add(position);
            if(allowed.y!=motion.y && motion.y<0 || allowed.lengthSqr()<1e-6)break;
            motion=new Vec3(allowed.x==motion.x?allowed.x*.91:0,
                    ((allowed.y==motion.y?motion.y:0)-player.getGravity())*.98,
                    allowed.z==motion.z?allowed.z*.91:0);
        }
        return new Plan(velocity,position,List.copyOf(path));
    }

    public static Plan solve(Vec3 start, Vec3 end, double gravity) {
        return solve(start, end, gravity, 10);
    }

    private static Plan fit(Player player, Vec3 start, Vec3 destination, double height) {
        var box = player.getBoundingBox().move(destination.subtract(player.position())).deflate(.001);
        if (!player.level().noCollision(player, box)) return null;
        double minimumApex = Math.max(.1, destination.y - start.y + .05);
        for (double apex = height; apex >= minimumApex; apex -= .1) {
            Plan plan = solve(start, destination, player.getGravity(), apex);
            if (clearPath(player, plan)) return plan;
        }
        return null;
    }

    private static boolean clearPath(Player player, Plan plan) {
        for (int i = 1; i < plan.path.size(); i++) {
            Vec3 before = plan.path.get(i - 1);
            Vec3 movement = plan.path.get(i).subtract(before);
            var box = player.getBoundingBox().move(before.subtract(player.position())).deflate(.01);
            Vec3 allowed = net.minecraft.world.entity.Entity.collideBoundingBox(
                    player, movement, box, player.level(), List.of());
            if (allowed.distanceToSqr(movement) > .0001) return false;
        }
        return true;
    }

    public static Plan solve(Vec3 start, Vec3 end, double gravity, double apex) {
        gravity=Math.max(.001,gravity);
        double low=0, high=4;
        for (int pass=0; pass<32; pass++) {
            double speed=(low+high)/2, y=0, v=speed;
            for(int tick=0;v>0 && tick<400;tick++) { y+=v; v=(v-gravity)*.98; }
            if (y<apex) low=speed; else high=speed;
        }
        double vy=(low+high)/2, y=0, v=vy, flight=0;
        for (int n=1; n<=160; n++) {
            double next=y+v;
            if (v<0 && next<=end.y-start.y) { flight=n-1+(y-(end.y-start.y))/(y-next); break; }
            y=next; v=(v-gravity)*.98;
        }
        flight=Math.max(1,flight);
        double whole=Math.floor(flight), fraction=flight-whole;
        double sum=(1-Math.pow(.91,whole))/.09+fraction*Math.pow(.91,whole);
        Vec3 velocity=new Vec3((end.x-start.x)/sum, vy, (end.z-start.z)/sum);
        List<Vec3> path=new ArrayList<>();
        Vec3 position=start, motion=velocity;
        path.add(start);
        for (int n=0; n<whole; n++) {
            position=position.add(motion); path.add(position);
            motion=new Vec3(motion.x*.91,(motion.y-gravity)*.98,motion.z*.91);
        }
        path.add(end);
        return new Plan(velocity,end,List.copyOf(path));
    }
}
