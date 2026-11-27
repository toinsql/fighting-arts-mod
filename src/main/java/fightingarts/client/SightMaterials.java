package fightingarts.client;

import fightingarts.SantoryuSkills;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/** Material and block type for each voxel inside the visible range, stored as an atlas of horizontal slices. */
final class SightMaterials implements AutoCloseable {
    private static final int SIDE = 2 * (SantoryuSkills.SIGHT_RANGE + 3);
    private static final int TILES = 9;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int CUTTABLE_EDGE = 0xffe6f0ff;
    private static final int UNBREAKABLE_EDGE = 0xffff2e38;
    private final int[] solidVoxels = new int[SIDE * SIDE * SIDE];
    private VertexBuffer contours;
    private boolean hasContours;
    private final Minecraft mc = Minecraft.getInstance();
    private DynamicTexture atlas;
    private BlockPos corner;
    private long updated;

    void bind(ShaderInstance shader, Vec3 eye) {
        BlockPos next = mc.player.blockPosition().offset(-SIDE/2,-SIDE/2,-SIDE/2);
        long now = mc.level.getGameTime();
        if(atlas==null){
            atlas=new DynamicTexture(SIDE*TILES,SIDE*Math.ceilDiv(SIDE,TILES),true);
        }
        if(corner==null || corner.distManhattan(next)>=3 || now<updated || now-updated>=10){
            corner=next;
            rebuild();
            updated=now;
        }
        shader.setSampler("Materials",atlas.getId());
        shader.safeGetUniform("Eye").set((float)(eye.x-corner.getX()),(float)(eye.y-corner.getY()),(float)(eye.z-corner.getZ()));
        shader.safeGetUniform("MaterialSide").set(SIDE);
    }

    private void rebuild() {
        int minX=corner.getX(),minY=corner.getY(),minZ=corner.getZ();
        var chunks=mc.level.getChunkSource();
        var pixels=atlas.getPixels();
        var pos=new BlockPos.MutableBlockPos();
        GameType mode=mc.gameMode.getPlayerMode();
        var border=mc.level.getWorldBorder();
        for(int chunkZ=minZ>>4;chunkZ<=(minZ+SIDE-1)>>4;chunkZ++){
            for(int chunkX=minX>>4;chunkX<=(minX+SIDE-1)>>4;chunkX++){
                var chunk=chunks.getChunk(chunkX,chunkZ,ChunkStatus.FULL,false);
                int startX=Math.max(minX,chunkX<<4),endX=Math.min(minX+SIDE,(chunkX+1)<<4);
                int startZ=Math.max(minZ,chunkZ<<4),endZ=Math.min(minZ+SIDE,(chunkZ+1)<<4);
                for(int y=0;y<SIDE;y++){
                    int tileX=(y%TILES)*SIDE,tileY=(y/TILES)*SIDE;
                    for(int z=startZ;z<endZ;z++)for(int x=startX;x<endX;x++){
                        int value=0;
                        pos.set(x,minY+y,z);
                        if(chunk!=null){
                            var state=chunk.getBlockState(pos);
                            if(!state.isAir()){
                                if(state.getBlock() instanceof LiquidBlock){
                                    value=1;
                                }else if(!state.getCollisionShape(mc.level,pos).isEmpty()){
                                    int kind=SantoryuSkills.cuttableMaterial(mc.level,pos,state)
                                            && border.isWithinBounds(pos) && !mc.player.blockActionRestricted(mc.level,pos,mode)?3:2;
                                    if(state.isCollisionShapeFullBlock(mc.level,pos))kind+=2;
                                    value=(BuiltInRegistries.BLOCK.getId(state.getBlock())<<8)|kind;
                                }
                            }
                        }
                        int kind=value&255;
                        solidVoxels[index(x-minX,y,z-minZ)]=kind>=4?value:0;
                        pixels.setPixelRGBA(tileX+x-minX,tileY+z-minZ,0xff000000|value);
                    }
                }
            }
        }
        atlas.upload();
        rebuildContours();
    }

    private static int index(int x,int y,int z){return (y*SIDE+z)*SIDE+x;}

    private int solid(int x,int y,int z){
        return x>=0 && x<SIDE && y>=0 && y<SIDE && z>=0 && z<SIDE ? solidVoxels[index(x,y,z)] : 0;
    }

    private void rebuildContours(){
        try(var storage=new ByteBufferBuilder(65536)){
            var vertices=new BufferBuilder(storage,VertexFormat.Mode.DEBUG_LINES,DefaultVertexFormat.POSITION_COLOR);
            // A face contributes only the perimeter of its coplanar group, never the shared seams.
            for(int y=1;y<SIDE-1;y++)for(int z=1;z<SIDE-1;z++)for(int x=1;x<SIDE-1;x++){
                int material=solidVoxels[index(x,y,z)];
                if(material==0)continue;
                int color=(material&255)==5?CUTTABLE_EDGE:UNBREAKABLE_EDGE;
                for(Direction face:DIRECTIONS){
                    int facingX=x+face.getStepX(),facingY=y+face.getStepY(),facingZ=z+face.getStepZ();
                    if(solid(facingX,facingY,facingZ)!=0)continue;
                    for(Direction side:DIRECTIONS){
                        if(side.getAxis()==face.getAxis())continue;
                        int dx=side.getStepX(),dy=side.getStepY(),dz=side.getStepZ();
                        if(solid(x+dx,y+dy,z+dz)==material && solid(facingX+dx,facingY+dy,facingZ+dz)==0)continue;
                        float a=x+Math.max(0,face.getStepX())+Math.max(0,dx);
                        float b=y+Math.max(0,face.getStepY())+Math.max(0,dy);
                        float c=z+Math.max(0,face.getStepZ())+Math.max(0,dz);
                        vertices.addVertex(a,b,c).setColor(color);
                        vertices.addVertex(a+(face.getStepX()==0 && dx==0?1:0),
                                b+(face.getStepY()==0 && dy==0?1:0),
                                c+(face.getStepZ()==0 && dz==0?1:0)).setColor(color);
                    }
                }
            }
            var mesh=vertices.build();
            hasContours=mesh!=null;
            if(!hasContours)return;
            if(contours==null)contours=new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            contours.bind();
            try{contours.upload(mesh);}
            finally{VertexBuffer.unbind();}
        }
    }

    void drawContours(Matrix4f modelView,Matrix4f projection,Vec3 eye){
        if(!hasContours)return;
        Matrix4f relative=new Matrix4f(modelView).translate(
                (float)(corner.getX()-eye.x),(float)(corner.getY()-eye.y),(float)(corner.getZ()-eye.z));
        contours.bind();
        try{contours.drawWithShader(relative,projection,GameRenderer.getPositionColorShader());}
        finally{VertexBuffer.unbind();}
    }

    public void close(){
        if(contours!=null)contours.close();
        contours=null;
        hasContours=false;
        if(atlas!=null)atlas.close();
        atlas=null;
        corner=null;
    }
}
