package dev.morphhide.game;

import com.mojang.math.Transformation;
import dev.morphhide.MorphHide;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Compact world-space lobby menu built from display/interaction entities (no vanilla buttons).
 * Layout follows a tile-menu convention: titled columns, radio options (selected = white + ◆),
 * a −/value/+ number input, and one large start tile. Readying up is a hotkey, not a wall tile.
 */
public final class LobbyControlWall {
    public static final String TAG="morphhide_wall", PREFIX="morphhide:wall:";
    /** Wall plane (block z) and the horizontal centre of the panel. Screen-right is −x for a viewer facing +z. */
    private static final int WALL_Z=12;
    private static final double CENTER_X=.5, FACE_Z=WALL_Z-.06, TEXT_Z=WALL_Z-.08, HIT_Z=WALL_Z-.1;
    private static final float TEXT=.7f, LINE=.3f, PAD=.1f;
    private static final int GREEN_TITLE=0x99FF99, ON_TEXT=0x000000, OFF_TEXT=0xAAAAAA;
    private static final Brightness ON=new Brightness(0,15), OFF=new Brightness(0,14), DIM=new Brightness(0,3), ALT=new Brightness(0,8);

    public record Member(String name, boolean ready, boolean host, String team) {}
    public record View(String phase, List<Member> members, int maxPlayers, int minPlayers, int hunterSetting, int readySeconds) {}

    private record Tile(Display.BlockDisplay plate, Display.TextDisplay label, float scale) {}
    private final List<Entity> entities=new ArrayList<>();
    private final Map<UUID,String> actions=new HashMap<>();
    private final Map<String,Tile> tiles=new HashMap<>();
    private Display.TextDisplay status,capacity,roster,rosterTitle,footer,teams;
    private Mob animalTeam;
    private net.minecraft.world.entity.decoration.Mannequin hunterTeam;

    private static double x(double u){return CENTER_X-u;}

    public void build(ServerLevel level) {
        // Clear the previous, much larger wall footprint before placing the compact frame.
        for(int x=-7;x<=7;x++)for(int y=101;y<=108;y++)level.setBlock(new BlockPos(x,y,WALL_Z),Blocks.AIR.defaultBlockState(),3);
        for(int x=-5;x<=5;x++)for(int y=101;y<=105;y++){
            boolean edge=Math.abs(x)==5||y==101||y==105, corner=Math.abs(x)==5&&(y==101||y==105);
            Block block=corner?Blocks.SEA_LANTERN:edge?Blocks.STRIPPED_SPRUCE_LOG:Blocks.CONCRETE.pick(DyeColor.BLACK);
            level.setBlock(new BlockPos(x,y,WALL_Z),block.defaultBlockState(),3);
        }
        // Keep the lobby loaded while everyone is in the arena: unloading and reloading the wall
        // chunks recreated these entities, which the stale sweep then removed.
        for(int cx=-2;cx<=1;cx++)for(int cz=-2;cz<=1;cz++)level.setChunkForced(cx,cz,true);
        var old=new ArrayList<Entity>();
        for(var e:level.getAllEntities())if(e.entityTags().contains(TAG))old.add(e);
        old.forEach(Entity::discard);
        entities.clear();actions.clear();tiles.clear();

        text(level,0,104.62,.9f,MorphHide.NAME,0xE5C984,true);
        status=text(level,0,104.40,.5f,"",0xBFD4C6,false);

        // Column layout, left to right in screen space.
        double top=104.25, u=-4.25;
        double aw=1.9, bw=1.9, cw=2.3, dw=2.1;
        // A: hunter count radio
        double a=u+aw/2;
        titlePanel(level,a,top,aw,"猎人数");
        String[] hunterLabels={"自动","1 人","2 人"};
        for(int i=0;i<3;i++)tile(level,"hunters:"+i,hunterLabels[i],a,top-2*(LINE+PAD)-i*(LINE+PAD),aw,LINE,TEXT);
        u+=aw+PAD;
        // B: capacity number input + animal picker
        double b=u+bw/2;
        titlePanel(level,b,top,bw,"人数上限");
        double row=top-2*(LINE+PAD);
        tile(level,"capacity_down","−",b-bw/2+LINE/2,row,LINE,LINE,TEXT);
        tile(level,"capacity_up","+",b+bw/2-LINE/2,row,LINE,LINE,TEXT);
        plate(level,b,row,bw-2*LINE-.2,LINE,Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState(),OFF);
        capacity=text(level,b,row+LINE/2-.13*TEXT,TEXT,"16",0xFFFFFF,true);
        teams=text(level,b,top-3*(LINE+PAD)-.05,.5f,"",0xFFFFFF,true);
        u+=bw+PAD;
        // C: roster
        double c=u+cw/2;
        rosterTitle=titlePanel(level,c,top,cw,"玩家");
        plate(level,c,102.75,cw,(float)(top-(LINE+PAD)-102.75),Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState(),DIM);
        roster=text(level,c,102.82,.45f,"",0xFFFFFF,true);
        u+=cw+PAD;
        // D: start + practice
        double d=u+dw/2;
        tile(level,"start","开始对局",d,top-.9,dw,.9f,.95f);
        tile(level,"practice","进入练习场",d,102.75,dw,.5f,TEXT);
        footer=text(level,0,102.2,.45f,"",0x8FA394,false);
        // Team pickers beside the wall: click the animal for the animal team, the player model for hunters.
        animalTeam=EntityTypes.COW.create(level,EntitySpawnReason.COMMAND);
        animalTeam.setNoAi(true);animalTeam.setSilent(true);animalTeam.setPermanentlyInvulnerable(true);animalTeam.setPersistenceRequired();
        teamPicker(level,animalTeam,"team:ANIMAL",7.0);
        hunterTeam=EntityTypes.MANNEQUIN.create(level,EntitySpawnReason.COMMAND);
        hunterTeam.setPermanentlyInvulnerable(true);hunterTeam.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.WOODEN_SWORD));
        ((dev.morphhide.mixin.MannequinAccessor)hunterTeam).morphhide$setImmovable(true);
        ((dev.morphhide.mixin.MannequinAccessor)hunterTeam).morphhide$setHideDescription(true);
        teamPicker(level,hunterTeam,"team:HUNTER",-6.0);
    }
    private void teamPicker(ServerLevel level,LivingEntity e,String action,double x){
        e.snapTo(x,101,WALL_Z-1.5,180,0);e.setYHeadRot(180);e.setYBodyRot(180);
        e.setCustomNameVisible(true);add(level,e);actions.put(e.getUUID(),action);
    }

    private void add(ServerLevel level,Entity e){e.addTag(TAG);level.addFreshEntity(e);entities.add(e);}
    private Display.TextDisplay text(ServerLevel level,double u,double y,float size,String value,int color,boolean shadow){
        var e=EntityTypes.TEXT_DISPLAY.create(level,EntitySpawnReason.COMMAND);
        e.snapTo(x(u),y,TEXT_Z,180,0);e.setText(Component.literal(value).withColor(color));
        e.setLineWidth(1000);e.setFlags(shadow?(byte)1:0);e.setBackgroundColor(0);e.setBrightnessOverride(ON);
        e.setTransformation(new Transformation(new Vector3f(),new Quaternionf(),new Vector3f(size),new Quaternionf()));
        add(level,e);return e;
    }
    private Display.BlockDisplay plate(ServerLevel level,double u,double y,double w,float h,BlockState state,Brightness light){
        var plate=EntityTypes.BLOCK_DISPLAY.create(level,EntitySpawnReason.COMMAND);
        // Block displays extend toward +x; screen-right is −x, so anchor at the screen-right edge.
        plate.snapTo(x(u)-w/2,y,FACE_Z,0,0);plate.setBlockState(state);
        plate.setTransformation(new Transformation(new Vector3f(),new Quaternionf(),new Vector3f((float)w,h,.05f),new Quaternionf()));
        plate.setBrightnessOverride(light);add(level,plate);return plate;
    }
    private Display.TextDisplay titlePanel(ServerLevel level,double u,double top,double w,String title){
        plate(level,u,top-LINE,w,LINE,Blocks.CONCRETE.pick(DyeColor.LIME).defaultBlockState(),DIM);
        return text(level,u,top-LINE/2-.13*TEXT,TEXT,title,GREEN_TITLE,false);
    }
    private void tile(ServerLevel level,String action,String label,double u,double y,double w,float h,float scale){
        var plate=plate(level,u,y,w,h,Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState(),ON);
        var text=text(level,u,y+h/2-.13*scale,scale,label,ON_TEXT,false);
        tiles.put(action,new Tile(plate,text,scale));
        var hit=EntityTypes.INTERACTION.create(level,EntitySpawnReason.COMMAND);
        hit.setCustomName(Component.literal(PREFIX+action));hit.setCustomNameVisible(false);
        hit.snapTo(x(u),y,HIT_Z,0,0);hit.setWidth((float)w);hit.setHeight(h);hit.setResponse(true);
        add(level,hit);actions.put(hit.getUUID(),action);
    }
    /** Style a tile: active tiles are white with black text, inactive ones gray with gray text. */
    private void style(String action,String label,boolean active,DyeColor color){
        var tile=tiles.get(action);if(tile==null||tile.plate().isRemoved())return;
        boolean danger=color==DyeColor.RED;
        tile.plate().setBlockState(Blocks.CONCRETE.pick(active?color:DyeColor.GRAY).defaultBlockState());
        tile.plate().setBrightnessOverride(danger?ALT:active?ON:OFF);
        tile.label().setText(Component.literal(label).withColor(danger?0xFF9999:active?ON_TEXT:OFF_TEXT));
    }

    /** Wall entities persist with the world; ones from an earlier build (unloaded at rebuild time) are stale. */
    public boolean stale(Entity entity){return entity.entityTags().contains(TAG)&&entities.stream().noneMatch(e->e.getUUID().equals(entity.getUUID()));}
    public String action(Entity entity){return actions.get(entity.getUUID());}

    public void update(View v){
        if(status==null||status.isRemoved())return;
        boolean lobby=v.phase().equals("LOBBY");
        int players=v.members().size();
        long ready=v.members().stream().filter(Member::ready).count();
        String line=!lobby?"回合进行中 · 房主可结束回合":
            v.readySeconds()>=0?"已准备 "+ready+" / "+players+" · "+v.readySeconds()+" 秒后自动开始":
            players<v.minPlayers()?"等待玩家 · 对局至少需要 "+v.minPlayers()+" 人":"已准备 "+ready+" / "+players+" · 全员准备后自动开始";
        status.setText(Component.literal(line).withColor(0xBFD4C6));
        capacity.setText(Component.literal(String.valueOf(v.maxPlayers())).withColor(0xFFFFFF));
        rosterTitle.setText(Component.literal("玩家 "+players+" / "+v.maxPlayers()).withColor(GREEN_TITLE));
        var lines=new ArrayList<Component>();
        int shown=Math.min(players,players>9?8:9);
        for(int i=0;i<shown;i++){
            var m=v.members().get(i);
            lines.add(Component.empty()
                .append(Component.literal(m.ready()?"✔ ":"• ").withColor(m.ready()?0x55FF55:0x888888))
                .append(Component.literal(m.team().equals("HUNTER")?"[猎] ":m.team().equals("ANIMAL")?"[兽] ":"").withColor(m.team().equals("HUNTER")?0xFF8877:0x88DDFF))
                .append(Component.literal(m.name()).withColor(0xFFFFFF))
                .append(Component.literal(m.host()?" ★":"").withColor(0xE5C984)));
        }
        if(shown<players)lines.add(Component.literal("+"+(players-shown)+" 人").withColor(0xAAAAAA));
        // Bottom-anchored text grows upward, so the roster is aligned to the panel top with blank padding.
        while(lines.size()<9)lines.add(Component.literal(" "));
        var body=Component.empty();for(int i=0;i<lines.size();i++){if(i>0)body.append("\n");body.append(lines.get(i));}
        roster.setText(body);
        String[] hunterLabels={"自动","1 人","2 人"};
        for(int i=0;i<3;i++){boolean on=v.hunterSetting()==i;style("hunters:"+i,(on?"◆ ":"")+hunterLabels[i],on,DyeColor.WHITE);}
        style("capacity_down","−",lobby,DyeColor.WHITE);
        style("capacity_up","+",lobby,DyeColor.WHITE);
        long hunters=v.members().stream().filter(m->m.team().equals("HUNTER")).count(),animals=v.members().stream().filter(m->m.team().equals("ANIMAL")).count();
        teams.setText(Component.literal("动物队 "+animals+"  ·  猎人队 "+hunters+"\n未选队伍 "+(players-animals-hunters)+" 人随机").withColor(0xD8D8D8));
        if(animalTeam!=null&&!animalTeam.isRemoved())animalTeam.setCustomName(teamName("动物队",animals,0x88DDFF));
        if(hunterTeam!=null&&!hunterTeam.isRemoved())hunterTeam.setCustomName(teamName("猎人队",hunters,0xFF8877));
        if(lobby)style("start","开始对局",players>=v.minPlayers(),DyeColor.LIME);
        else style("start","结束回合",true,DyeColor.RED);
        style("practice","进入练习场",lobby,DyeColor.WHITE);
        footer.setText(Component.literal(lobby?"Shift+Y 准备  ·  左键点击按钮  ·  开局后动物再选动物":"本局结束后自动回到大厅").withColor(0x8FA394));
    }
    private static Component teamName(String team,long count,int color){
        return Component.literal(team+" · "+count+" 人  ").withColor(color).append(Component.literal("点击加入").withColor(0xDDDDDD));
    }
    public void close(){for(var e:entities)if(!e.isRemoved())e.discard();entities.clear();actions.clear();tiles.clear();}
}
