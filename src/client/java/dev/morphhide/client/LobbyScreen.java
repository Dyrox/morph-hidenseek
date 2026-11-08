package dev.morphhide.client;

import dev.morphhide.MorphHide;
import dev.morphhide.game.AnimalKind;
import dev.morphhide.network.GameView;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;

/** Lobby overview; animal selection and world-wall match controls are separate actions. */
public final class LobbyScreen extends Screen {
    private int left,top,pw,ph,page,rows;
    private String signature="";
    public LobbyScreen(){super(Component.literal(MorphHide.NAME+" · 大厅面板"));}
    public static String phase(String p){return switch(p){case "COUNTDOWN"->"即将出发";case "GENERATING"->"生成地图";case "HIDING"->"藏匿时间";case "HUNTING"->"追捕中";case "RESULTS"->"本局结算";default->"大厅";};}
    private String signature(){var s=MorphHideClient.state;return s.phase+":"+s.joined+":"+s.host+":"+s.players+":"+s.ready;}
    private void button(String label,int x,int y,int width,Runnable action){addRenderableWidget(new VeilButton(x,y,width,20,label,action));}
    @Override protected void init(){
        clearWidgets();signature=signature();
        pw=Math.min(620,width-16);ph=Math.min(400,height-16);left=(width-pw)/2;top=(height-ph)/2;
        rows=Math.max(1,(ph-120)/28);var s=MorphHideClient.state;
        page=Math.clamp(page,0,Math.max(0,(s.members.size()-1)/rows));
        button("返回游戏",left+8,top+ph-26,72,this::onClose);
        if(!s.joined)button("进入大厅",left+pw-148,top+ph-26,140,()->MorphHideClient.send("join"));
        else if(s.phase.equals("LOBBY"))button(s.ready?"取消准备":"准备",left+pw-148,top+ph-26,140,()->MorphHideClient.send("ready"));
        else if(MorphHideClient.canPickAnimal())button("选择动物",left+pw-148,top+ph-26,140,()->minecraft.gui.setScreen(new AnimalSelectScreen()));
        if(!s.phase.equals("LOBBY")&&s.joined&&s.host)button("结束回合并回大厅",left+84,top+ph-26,140,()->MorphHideClient.send("stop"));
        if(s.members.size()>rows){
            button("<",left+pw-80,top+57,24,()->{page=Math.max(0,page-1);init();});
            button(">",left+pw-32,top+57,24,()->{page=Math.min((s.members.size()-1)/rows,page+1);init();});
        }
    }
    @Override public void tick(){if(!signature.equals(signature()))init();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float dt){g.fill(0,0,width,height,0x88101518);}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float dt){
        var s=MorphHideClient.state;boolean lobby=s.phase.equals("LOBBY");
        g.fill(left,top,left+pw,top+ph,0xEC152126);
        g.fill(left,top,left+pw,top+2,0xFFBCA573);
        TitleEntry.icon(g,left+8,top+4,29);
        g.text(font,MorphHide.NAME,left+44,top+8,0xFFFFE2A0,true);
        g.text(font,phase(s.phase)+(lobby?" · 选队伍后 "+MorphHideClient.readyChord()+" 准备":" · "+s.seconds+" 秒"),left+44,top+23,0xFFBDD1C5,true);
        g.text(font,s.players+" / "+s.capacity,left+pw-48,top+10,0xFFE1D4AE,true);
        String hint=lobby?(s.readySeconds>=0?s.readySeconds+" 秒后自动开始 · ":"")+"全员准备后自动开始 · 至少 "+s.minPlayers+" 人 · "+MorphHideClient.readyChord()+" 准备":
            s.phase.equals("RESULTS")?(s.practice?"练习结束":s.winner.equals("ANIMALS")?"动物胜利":"猎人胜利"):
            s.practice?"练习场 · 没有猎人":"动物被猎人击杀后淘汰，进入观战";
        g.text(font,hint,left+12,top+43,0xFFD7C58F,true);
        long ready=s.members.stream().filter(GameView.Member::ready).count();
        g.text(font,lobby?"玩家 · 已准备 "+ready+" / "+s.players:"本局玩家",left+12,top+65,0xFFCCDAD0,true);
        for(int i=0;i<rows;i++){
            int index=page*rows+i;if(index>=s.members.size())break;
            var member=s.members.get(index);int y=top+82+i*28;
            g.fill(left+8,y,left+pw-8,y+25,member.eliminated()?0xFF30292D:0xFF26383B);
            UUID id=UUID.fromString(member.uuid());var info=minecraft.getConnection()==null?null:minecraft.getConnection().getPlayerInfo(id);
            PlayerFaceExtractor.extractRenderState(g,info==null?DefaultPlayerSkin.get(id):info.getSkin(),left+13,y+3,20);
            g.text(font,font.plainSubstrByWidth(member.name()+(member.host()?" · 房主":""),pw-160),left+42,y+8,0xFFE5EDE5,true);
            String team=member.team().equals("HUNTER")?"猎人队 · ":member.team().equals("ANIMAL")?"动物队 · ":"随机 · ";
            String status=lobby?team+(member.ready()?"已准备":"未准备"):member.eliminated()?"已淘汰 · 观战":s.phase.equals("COUNTDOWN")||s.phase.equals("GENERATING")?"等待出发":"未淘汰";
            g.text(font,status,left+pw-8-font.width(status)-6,y+8,member.eliminated()?0xFFF0AA9F:member.ready()?0xFFB8DB8E:0xFFB3C3BB,true);
        }
        g.text(font,lobby?"左键点击大厅里的牛加入动物队，点击玩家模型加入猎人队 · 开局后动物再选动物":"按「返回游戏」继续",left+12,top+ph-43,0xFFB5C6BD,true);
        super.extractRenderState(g,mx,my,dt);
    }
}
