package dev.morphhide.game;

import java.util.ArrayDeque;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Work is applied incrementally on the server thread; only our own dimension is touched. */
public final class ArenaBuilder {
    public static final int CENTER_X = 256, FLOOR = 100;
    public static final Vec3 LOBBY = new Vec3(0.5, FLOOR + 1, 0.5);
    private record Placement(BlockPos pos, BlockState state) {}
    /** A food field; x/z are world block coordinates of its centre. */
    public record Farm(Food food, int x, int z) {}
    public final java.util.List<Farm> farms = new java.util.ArrayList<>();
    private final ArrayDeque<Placement> queue = new ArrayDeque<>();
    private int total;
    public final long seed;
    public final String theme;
    private final int radius;

    public ArenaBuilder(int radius, long seed) {
        this.radius = radius; this.seed = seed;
        this.theme = new String[]{"苔石牧场", "林间营地", "失落花园"}[new Random(seed).nextInt(3)];
        plan(); total = queue.size();
    }

    public int progress() { return total == 0 ? 100 : 100 - queue.size() * 100 / total; }
    public boolean tick(ServerLevel level) {
        long until = System.nanoTime() + 6_000_000L;
        for (int i = 0; i < 2500 && !queue.isEmpty(); i++) {
            var p = queue.removeFirst();
            level.setBlock(p.pos, p.state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            if ((i & 63) == 63 && System.nanoTime() >= until) break;
        }
        return queue.isEmpty();
    }

    private void set(int x, int y, int z, Block block) { set(x, y, z, block.defaultBlockState()); }
    private void set(int x, int y, int z, BlockState state) {
        if (Math.abs(x) <= radius && Math.abs(z) <= radius)
            queue.addLast(new Placement(new BlockPos(CENTER_X + x, FLOOR + y, z), state));
    }
    private void box(int x, int y, int z, int w, int h, int d, Block block) {
        for (int a=x; a<x+w; a++) for(int b=y;b<y+h;b++) for(int c=z;c<z+d;c++) set(a,b,c,block);
    }
    private void plan() {
        Random random = new Random(seed);
        // Always clear the maximum supported footprint so decreasing the configured radius leaves no old map.
        for (int x=-64;x<=64;x++) for(int z=-64;z<=64;z++) {
            for(int y=-2;y<=14;y++) queue.addLast(new Placement(new BlockPos(CENTER_X+x,FLOOR+y,z), Blocks.AIR.defaultBlockState()));
        }
        for(int x=-radius;x<=radius;x++) for(int z=-radius;z<=radius;z++) {
            set(x,-2,z,Blocks.BEDROCK); set(x,-1,z,Blocks.DIRT);
            boolean path = Math.abs(x)<2 || Math.abs(z)<2 || Math.abs(Math.sqrt(x*x+z*z)-radius*0.60)<1.5;
            set(x,0,z,path ? Blocks.DIRT_PATH : random.nextInt(11)==0 ? Blocks.MOSS_BLOCK : Blocks.GRASS_BLOCK);
            if(Math.abs(x)==radius || Math.abs(z)==radius) {
                for(int y=1;y<=14;y++) set(x,y,z,Blocks.BARRIER);
                set(x,1,z,Blocks.MOSSY_STONE_BRICKS); set(x,2,z,Blocks.OAK_FENCE);
            }
        }
        // Small open centre and clear cross-shaped lanes keep navigation readable.
        box(-3,0,-3,7,1,7,Blocks.SMOOTH_STONE);
        set(0,0,0,Blocks.SEA_LANTERN);
        for(int n=0;n<80;n++) {
            int x=random.nextInt(radius*2-14)-radius+7;
            int z=random.nextInt(radius*2-14)-radius+7;
            if(Math.abs(x)<5 || Math.abs(z)<5) continue;
            int kind=random.nextInt(7);
            if(kind<=1) tree(x,z,random);
            else if(kind==2) {
                int len=3+random.nextInt(4);
                box(x,1,z,len,1,2,Blocks.HAY_BLOCK);
                set(x+1,2,z,Blocks.HAY_BLOCK);
            } else if(kind==3) {
                var leaves=Blocks.AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
                for(int a=0;a<4;a++) for(int b=0;b<2;b++) set(x+a,1,z+b,leaves);
            } else if(kind==4) ruin(x,z,random);
            else if(kind==5) {
                box(x,0,z,4,1,3,Blocks.SAND);
                box(x+1,0,z+1,2,1,1,Blocks.WATER);
            } else {
                set(x,1,z,Blocks.POPPY); set(x+1,1,z,Blocks.DANDELION);
                set(x,1,z+1,Blocks.SHORT_GRASS);
            }
        }
        // Four landmarks are also safe spawn points and public forage objectives.
        int r=radius/2;
        for(int[] point:new int[][]{{r,r},{-r,r},{r,-r},{-r,-r}}) {
            int x=point[0],z=point[1];
            box(x-3,1,z-3,7,10,7,Blocks.AIR);
            box(x-2,0,z-2,5,1,5,Blocks.SMOOTH_STONE);
            set(x,0,z,Blocks.SEA_LANTERN);
            set(x+2,1,z+2,Blocks.CHISELED_STONE_BRICKS);
            set(x+2,2,z+2,Blocks.LANTERN);
        }
        // Eight food fields (two per crop) on an outer ring, between the landmarks: animals must travel.
        int ring=(int)(radius*0.74);
        for(int k=0;k<8;k++) {
            double angle=Math.PI*2*(k+.5)/8;
            int x=(int)Math.round(Math.cos(angle)*ring),z=(int)Math.round(Math.sin(angle)*ring);
            Food food=Food.values()[k%Food.values().length];
            farm(x,z,food);
            farms.add(new Farm(food,CENTER_X+x,z));
        }
    }
    /** 7x7 field: path rim with lanterns, 5x5 ripe crops (berries on grass) around a water source. */
    private void farm(int x,int z,Food food) {
        box(x-3,1,z-3,7,6,7,Blocks.AIR);
        for(int a=-3;a<=3;a++) for(int b=-3;b<=3;b++) {
            boolean rim=Math.abs(a)==3||Math.abs(b)==3;
            if(rim) { set(x+a,0,z+b,Blocks.DIRT_PATH); continue; }
            if(a==0&&b==0) { set(x,0,z,Blocks.WATER); continue; }
            set(x+a,0,z+b,food==Food.BERRIES?Blocks.GRASS_BLOCK.defaultBlockState():Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.FarmlandBlock.MOISTURE,7));
            set(x+a,1,z+b,food.ripe());
        }
        for(int[] c:new int[][]{{-3,-3},{3,-3},{-3,3},{3,3}}) set(x+c[0],1,z+c[1],Blocks.LANTERN);
    }
    private void tree(int x,int z,Random random) {
        int h=4+random.nextInt(3);
        Block leaf=theme.equals("林间营地") ? Blocks.SPRUCE_LEAVES : Blocks.OAK_LEAVES;
        var leaves=leaf.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true);
        for(int y=h-2;y<=h+1;y++) for(int a=-2;a<=2;a++) for(int b=-2;b<=2;b++)
            if(Math.abs(a)+Math.abs(b)<(y==h+1?2:4)) set(x+a,y,z+b,leaves);
        box(x,1,z,1,h,1,Blocks.OAK_LOG);
    }
    private void ruin(int x,int z,Random random) {
        box(x,0,z,5,1,5,Blocks.MOSSY_STONE_BRICKS);
        for(int a=0;a<5;a++) {
            if(a!=2) box(x+a,1,z,1,2+random.nextInt(2),1,Blocks.MOSSY_STONE_BRICKS);
            if(a<3) box(x,1,z+a,1,2,1,Blocks.STONE_BRICKS);
        }
        box(x+4,1,z+4,1,3,1,Blocks.CHISELED_STONE_BRICKS);
    }

    public Vec3 spawn(int index) {
        int r=radius/2;
        return switch(Math.floorMod(index,4)) {
            case 0 -> new Vec3(CENTER_X+r+.5,FLOOR+1,r+.5);
            case 1 -> new Vec3(CENTER_X-r+.5,FLOOR+1,-r+.5);
            case 2 -> new Vec3(CENTER_X+r+.5,FLOOR+1,-r+.5);
            default -> new Vec3(CENTER_X-r+.5,FLOOR+1,r+.5);
        };
    }

    public static void buildLobby(ServerLevel level) {
        for(int x=-17;x<=17;x++) for(int z=-17;z<=17;z++) {
            double d=Math.sqrt(x*x+z*z);
            if(d>16) continue;
            put(level,x,0,z,Blocks.STONE_BRICKS);
            put(level,x,1,z,d<4?Blocks.SMOOTH_QUARTZ:Blocks.SPRUCE_PLANKS);
            for(int y=2;y<12;y++) put(level,x,y,z,Blocks.AIR);
            if(d>14.5) {
                // Persistent: lobby leaves have no logs nearby and would otherwise decay.
                level.setBlock(new BlockPos(x,FLOOR+1,z),Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true),Block.UPDATE_CLIENTS);
                put(level,x,3,z,Blocks.OAK_FENCE);
            }
        }
        for(int x:new int[]{-7,7}) for(int z:new int[]{-7,7}) {
            for(int y=2;y<=6;y++) put(level,x,y,z,Blocks.STRIPPED_SPRUCE_LOG);
            put(level,x,7,z,Blocks.SEA_LANTERN);
        }
        for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++)
            if(Math.abs(x)>=7 || Math.abs(z)>=7) put(level,x,8,z,Blocks.SPRUCE_SLAB);
        for(int i=-3;i<=3;i++) { put(level,i,1,0,Blocks.MOSS_BLOCK);put(level,0,1,i,Blocks.MOSS_BLOCK); }
        put(level,0,1,0,Blocks.SEA_LANTERN);
    }
    private static void put(ServerLevel level,int x,int y,int z,Block block) {
        // Lobby floor is FLOOR; y=1 is the top floor layer.
        level.setBlock(new BlockPos(x,FLOOR+y-1,z),block.defaultBlockState(),Block.UPDATE_CLIENTS);
    }
}
