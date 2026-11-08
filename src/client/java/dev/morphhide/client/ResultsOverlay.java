package dev.morphhide.client;

import dev.morphhide.game.AnimalKind;
import dev.morphhide.network.GameView;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * End-of-round results, modelled on bingo's win feedback: a sound sting, a popping title, a
 * confetti burst of ★ for the winners, then a summary of who was what and how long they lasted.
 */
public final class ResultsOverlay {
    private ResultsOverlay() {}
    private static final int[] COLORS={0xF5C0A3,0xA3F5A3,0xA3F5C7,0xA3ABF5,0xCCA3F5,0xF5A3D6,0xFFE2A0};
    private record Star(double vx,double vy,double rot,double spin,int color){}
    private static final List<Star> stars=new ArrayList<>();
    private static int shownId=-1;
    private static long startedAt;
    /** Side of the local player in the shown round: true win, false loss, null not playing. */
    private static Boolean won;

    static void onState(GameView s){
        if(s.results==null||s.results.id()==shownId||!s.phase.equals("RESULTS"))return;
        shownId=s.results.id();startedAt=System.currentTimeMillis();stars.clear();
        var mc=Minecraft.getInstance();
        String me=mc.player==null?"":mc.player.getUUID().toString();
        won=null;
        for(var row:s.results.rows())if(row.uuid().equals(me))won=s.results.practice()||row.side().equals(s.results.winner().equals("HUNTERS")?"HUNTER":"ANIMAL");
        if(!Boolean.FALSE.equals(won)){
            var random=new Random();
            for(int i=0;i<70;i++){double theta=random.nextDouble()*Math.PI*2,v=(1+random.nextDouble())*2.2;
                stars.add(new Star(Math.cos(theta)*v,Math.sin(theta)*v-1.6,random.nextDouble()*Math.PI*2,(random.nextDouble()-.5)*.6,COLORS[random.nextInt(COLORS.length)]));}
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,1f,1f));
        }else mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BEACON_DEACTIVATE,.8f,1f));
    }

    public static Boolean localWon(){return won;}
    static void reset(){shownId=-1;won=null;stars.clear();}
    public static boolean active(GameView s){return s.phase.equals("RESULTS")&&s.results!=null&&s.results.id()==shownId;}

    private static String clock(int seconds){return String.format("%d:%02d",seconds/60,seconds%60);}
    private static float easeOutBack(float t){float c=1.70158f;return 1+(c+1)*(float)Math.pow(t-1,3)+c*(float)Math.pow(t-1,2);}
    private static int alpha(int rgb,float a){return ((int)(Math.clamp(a,0f,1f)*255)<<24)|(rgb&0xFFFFFF);}

    static void render(GuiGraphicsExtractor g,GameView s){
        var mc=Minecraft.getInstance();var font=mc.font;var r=s.results;
        int w=mc.getWindow().getGuiScaledWidth(),h=mc.getWindow().getGuiScaledHeight();
        float t=(System.currentTimeMillis()-startedAt)/1000f;
        g.fill(0,0,w,h,alpha(0x05080A,Math.min(1,t*2)*.62f));
        boolean animals=r.winner().equals("ANIMALS");
        String title=r.practice()?"练习结束":animals?"动物胜利！":"猎人胜利！";
        int titleColor=r.practice()?0xFFE2A0:animals?0x8CF08C:0xFF8C78;
        int titleY=Math.max(24,h/2-112);
        float pop=easeOutBack(Math.min(1,t/.45f))*3f;
        g.pose().pushMatrix();g.pose().translate(w/2f,titleY);g.pose().scale(Math.max(.01f,pop),Math.max(.01f,pop));
        g.centeredText(font,title,0,-4,alpha(titleColor,Math.min(1,t*3)));
        g.pose().popMatrix();
        float sub=Math.clamp((t-.35f)*3,0f,1f);
        if(won!=null&&!r.practice()){
            g.pose().pushMatrix();g.pose().translate(w/2f,titleY+24);g.pose().scale(1.6f,1.6f);
            g.centeredText(font,won?"你赢了":"你输了",0,-4,alpha(won?0xFFE2A0:0xC8C8C8,sub));
            g.pose().popMatrix();
        }
        g.centeredText(font,r.reason()+"  ·  追捕用时 "+clock(r.seconds()),w/2,titleY+40,alpha(0xD0DAD2,sub));

        // Summary table: hunters first, then animals by time survived.
        var rows=new ArrayList<>(r.rows());
        rows.sort(Comparator.comparing((GameView.ResultRow row)->row.side().equals("HUNTER")?0:1).thenComparing(row->-row.seconds()));
        int pw=Math.min(380,w-24),x0=(w-pw)/2,y=titleY+56,rowH=16;
        int visible=Math.min(rows.size(),Math.max(1,(h-y-28)/rowH));
        float table=Math.clamp((t-.6f)*3,0f,1f);
        if(table>0){
            g.fill(x0,y-4,x0+pw,y+visible*rowH+4,alpha(0x101A1C,table*.9f));
            for(int i=0;i<visible;i++){
                var row=rows.get(i);int ry=y+i*rowH;
                UUID id=UUID.fromString(row.uuid());
                var info=mc.getConnection()==null?null:mc.getConnection().getPlayerInfo(id);
                PlayerFaceExtractor.extractRenderState(g,info==null?DefaultPlayerSkin.get(id):info.getSkin(),x0+6,ry+2,10);
                boolean hunter=row.side().equals("HUNTER");
                String role=hunter?"猎人":AnimalKind.parse(row.animal()).label;
                g.text(font,font.plainSubstrByWidth(row.name(),pw/2-70),x0+22,ry+3,alpha(0xFFFFFF,table),true);
                g.text(font,role,x0+pw/2-24,ry+3,alpha(hunter?0xFF8C78:0x8CD8FF,table),true);
                var animal=hunter?null:DisguisePreview.model(AnimalKind.parse(row.animal()));
                if(animal!=null&&table>=1){
                    // Small live model facing the viewer, fitted to the row.
                    int ix0=x0+pw/2-44,ix1=ix0+18,iy1=ry+rowH;
                    int size=Math.max(4,(int)Math.min(13/Math.max(.5f,animal.getBbHeight()),16/Math.max(.5f,animal.getBbWidth()*1.5f)));
                    net.minecraft.client.gui.screens.inventory.InventoryScreen.extractEntityInInventoryFollowsMouse(g,ix0,ry,ix1,iy1,size,.0625f,(ix0+ix1)/2f,(ry+iy1)/2f,animal);
                }
                String result=hunter?(row.left()?"中途离开 · ":"")+"抓到 "+row.kills()+" 只"+(row.kills()>0?" · 最后一只 "+clock(row.seconds()):""):
                    row.survived()?"存活到最后 · "+clock(row.seconds()):
                    row.left()?"中途离开 · "+clock(row.seconds()):
                    row.killer().equals("饿死")?"没吃饱，饿死了":
                    row.killer().equals("边界")?"被缩圈边界淘汰 · 坚持 "+clock(row.seconds()):
                    "被 "+(row.killer().isEmpty()?"猎人":row.killer())+" 抓到 · 坚持 "+clock(row.seconds());
                int color=hunter?0xF0D8B0:row.survived()?0xA3F5A3:0xF0AA9F;
                g.text(font,result,x0+pw-6-font.width(result),ry+3,alpha(color,table),true);
            }
            if(visible<rows.size())g.centeredText(font,"…还有 "+(rows.size()-visible)+" 人",w/2,y+visible*rowH+6,alpha(0xAAAAAA,table));
        }
        g.centeredText(font,s.seconds+" 秒后返回大厅",w/2,h-22,alpha(0xBBBBBB,table));

        // Confetti: bingo-style ★ burst from the title, fading with distance, with a little gravity.
        double ticks=t*20,range=Math.max(w,h)*.75;
        for(var star:stars){
            double x=star.vx()*ticks,yy=star.vy()*ticks+.035*ticks*ticks;
            float a=1f-(float)Math.pow(Math.hypot(x,yy)/range,3);
            if(a<=.03f||t>6)continue;
            g.pose().pushMatrix();g.pose().translate((float)(w/2f+x),(float)(titleY+yy));g.pose().rotate((float)(star.rot()+star.spin()*ticks));
            g.text(font,"★",-3,-4,alpha(star.color(),a),false);
            g.pose().popMatrix();
        }
    }
}
