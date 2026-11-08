package dev.morphhide.client;

import dev.morphhide.game.AnimalKind;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.*;
import java.util.*;

/** A transparent model gallery: real dropdowns, live filtering and accessible entity tiles. */
public final class AnimalSelectScreen extends Screen {
    private static final String[] FILTERS={"全部动物","大型","中型","小型"};
    private final Mob[] previews=new Mob[6];
    private final List<AbstractWidget> base=new ArrayList<>();
    private final List<VeilButton> cards=new ArrayList<>();
    private final List<VeilButton> popup=new ArrayList<>();
    private EditBox search;
    private String signature="",query="";
    private int filter,dropdown,left,top,pw,ph,gridY,gridH,barY,dropW,popX,popY,popH;
    private boolean selectable;
    private AnimalKind hover;

    public AnimalSelectScreen(){super(Component.literal(dev.morphhide.MorphHide.NAME+" · 动物选择"));}
    public static String phase(String p){return switch(p){case "COUNTDOWN"->"即将出发";case "GENERATING"->"生成地图";case "HIDING"->"藏匿时间";case "HUNTING"->"追捕中";case "RESULTS"->"本局结算";default->"大厅";};}
    private String signature(){var s=MorphHideClient.state;return s.joined+":"+s.phase+":"+s.role+":"+s.animal+":"+s.players;}
    private VeilButton button(String text,int x,int y,int w,Runnable action){
        var b=new VeilButton(x,y,w,18,text,action);base.add(b);return addRenderableWidget(b);
    }
    @Override protected void init(){
        clearWidgets();base.clear();cards.clear();popup.clear();dropdown=0;signature=signature();
        var s=MorphHideClient.state;selectable=MorphHideClient.canPickAnimal();
        pw=Math.min(720,width-16);ph=Math.min(440,height-16);left=(width-pw)/2;top=(height-ph)/2;
        barY=top+34;gridY=barY+25;gridH=ph-139;dropW=Math.min(130,(pw-28)/3);
        if(minecraft.level!=null)for(int i=0;i<6;i++)if(previews[i]==null||previews[i].level()!=minecraft.level)previews[i]=PreviewEntities.create(AnimalKind.values()[i].entityType,minecraft.level);
        if(selectable){
            button("类型: "+FILTERS[filter]+" ▾",left+8,barY,dropW,()->openDropdown(1));
            int sx=left+12+dropW;
            search=new EditBox(font,sx,barY,pw-20-dropW,18,Component.literal("搜索动物"));
            search.setMaxLength(40);search.setHint(Component.literal("搜索…"));search.setValue(query);
            search.setResponder(value->{query=value;buildGrid();});base.add(search);addRenderableWidget(search);
            buildGrid();
        }
        int bottom=top+ph-24;
        button("返回游戏",left+8,bottom,90,this::onClose);
        var confirm=button("确认动物选择",left+pw-148,bottom,140,this::onClose);
        confirm.active=selectable;
    }

    private boolean matches(AnimalKind kind){
        boolean category=filter==0||kind.trait.equals(FILTERS[filter]);
        String q=query.strip().toLowerCase(Locale.ROOT);
        return category&&(kind.label+kind.trait+kind.name().toLowerCase(Locale.ROOT)).contains(q);
    }
    private void buildGrid(){
        for(var card:cards){base.remove(card);removeWidget(card);}cards.clear();
        List<AnimalKind> animals=Arrays.stream(AnimalKind.values()).filter(this::matches).toList();
        int cols=pw>=580?6:3,rows=Math.max(1,(animals.size()+cols-1)/cols);
        int cw=(pw-20-(cols-1)*6)/cols,ch=Math.min(132,(gridH-(rows-1)*6)/rows);
        for(int i=0;i<animals.size();i++){
            var kind=animals.get(i);
            var card=new VeilButton(left+10+(i%cols)*(cw+6),gridY+(i/cols)*(ch+6),cw,ch,kind.label+"，"+kind.trait,()->{if(selectable)MorphHideClient.send("animal:"+kind.name());}){
                @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float a){
                    boolean focus=dropdown==0&&isHoveredOrFocused(),selected=MorphHideClient.state.animal.equals(kind.name());
                    if(focus)hover=kind;
                    int x=getX(),y=getY(),w=getWidth(),h=getHeight();
                    // Most of the cell stays transparent so the world remains visible behind the models.
                    if(selected||focus){
                        g.fill(x+2,y+3,x+w+2,y+h+3,0x44000000);
                        g.fill(x,y,x+w,y+h,selected?0x663E5948:0x4430393A);
                        int edge=selected?0xFFE2C782:0xFF80998B;
                        g.fill(x,y,x+w,y+1,edge);g.fill(x,y+h-1,x+w,y+h,edge);
                        g.fill(x,y,x+1,y+h,edge);g.fill(x+w-1,y,x+w,y+h,edge);
                    }
                    int mh=h-14,cx=x+w/2;
                    shadow(g,cx,y+mh-3,Math.min(w/4,22));
                    Mob model=previews[kind.ordinal()];
                    if(model!=null){
                        // Scale by physical dimensions so rabbits and cows both remain legible without clipping.
                        float bodyHeight=model.getBbHeight();
                        int size=Math.max(8,(int)Math.min((mh-6)/Math.max(.7f,bodyHeight),(w-14)/Math.max(1.2f,model.getBbWidth()*1.7f)));
                        InventoryScreen.extractEntityInInventoryFollowsMouse(g,x+3,y+2,x+w-3,y+mh,size,.06f,mx,my,model);
                    }
                    g.centeredText(font,(selected?"◆ ":"")+kind.label,cx,y+h-11,selected?0xFFFFE2A0:0xFFE3E6DE);
                    if(h>75)g.text(font,kind.trait,x+5,y+5,0xFFB3C6B6,true);
                    String eats="吃"+kind.food().label;
                    g.text(font,eats,x+w-5-font.width(eats),y+5,kind.food().color,true);
                }
            };
            card.active=selectable;
            card.setTooltip(Tooltip.create(Component.literal(kind.label+" · "+kind.trait+"\n要去"+kind.food().field+"吃 "+dev.morphhide.game.Food.BITES+" 口"+kind.food().label+"\n"+kind.description)));
            cards.add(card);base.add(card);addRenderableWidget(card);
        }
    }
    private void openDropdown(int which){
        dropdown=which;for(var b:base)b.active=false;
        String[] labels=FILTERS;
        popX=left+8;popY=barY+20;popH=labels.length*20;
        for(int i=0;i<labels.length;i++){
            final int index=i;
            boolean selected=filter==i;
            var option=new VeilButton(popX,popY+i*20,dropW,20,(selected?"◆ ":"")+labels[i],()->{
                filter=index;
                init();
            }){
                @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float a){g.nextStratum();super.extractContents(g,mx,my,a);}
            };
            popup.add(option);addRenderableWidget(option);
        }
        setFocused(popup.getFirst());
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        if(dropdown!=0&&(event.x()<popX||event.x()>=popX+dropW||event.y()<popY||event.y()>=popY+popH)){init();return true;}
        return super.mouseClicked(event,doubleClick);
    }
    @Override public boolean keyPressed(KeyEvent event){
        if(dropdown!=0&&event.isEscape()){init();return true;}
        return super.keyPressed(event);
    }
    @Override public void tick(){
        // Hunters have been released: the choice is locked, get out of the way.
        if(selectable&&!MorphHideClient.canPickAnimal()){onClose();return;}
        if(!signature().equals(signature))init();
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractBackground(GuiGraphicsExtractor g,int mx,int my,float a){g.fill(0,0,width,height,0x88080D12);}
    private static void shadow(GuiGraphicsExtractor g,int x,int y,int radius){
        g.fill(x-radius+3,y-3,x+radius-3,y+3,0x33000000);
        g.fill(x-radius,y-1,x+radius,y+2,0x44000000);
        g.fill(x-radius+6,y,x+radius-6,y+2,0x55000000);
    }
    /** Lobby roster with skin faces; other players' animal choices stay private. */
    private void players(GuiGraphicsExtractor g,int detailY){
        var s=MorphHideClient.state;if(s.members.isEmpty())return;
        int areaW=Math.min(260,pw/2-12),x0=left+pw-areaW-8,colW=areaW>=230?areaW/2:areaW,cols=areaW/colW,slots=cols*3;
        int shown=s.members.size()>slots?slots-1:s.members.size();
        for(int i=0;i<shown;i++){
            var member=s.members.get(i);int x=x0+(i/3)*colW,y=detailY+6+(i%3)*14;
            java.util.UUID id=java.util.UUID.fromString(member.uuid());
            var info=minecraft.getConnection()==null?null:minecraft.getConnection().getPlayerInfo(id);
            if(member.host())g.fill(x-1,y-1,x+13,y+13,0xFFE2C782);
            PlayerFaceExtractor.extractRenderState(g,info==null?DefaultPlayerSkin.get(id):info.getSkin(),x,y,12);
            g.fill(x+15,y+2,x+17,y+10,member.ready()?0xFF8FD16A:0xFF6B756F);
            String name=font.plainSubstrByWidth(member.name()+(member.host()?" · 房主":""),colW-24);
            g.text(font,name,x+20,y+2,member.ready()?0xFFD9EDC9:0xFFD0D6CF,true);
        }
        if(shown<s.members.size()){int i=shown;g.text(font,"+"+(s.members.size()-shown)+" 人",x0+(i/3)*colW+20,detailY+8+(i%3)*14,0xFFB5C6BD,true);}
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float a){
        var s=MorphHideClient.state;hover=null;
        g.fill(left,top,left+pw,top+29,0xAA0E1719);
        g.fill(left,top,left+pw,top+1,0xFF847A59);
        TitleEntry.icon(g,left+7,top+1,27);
        g.text(font,dev.morphhide.MorphHide.NAME,left+39,top+5,0xFFFFE2A0,true);
        g.text(font,selectable?"选择你的动物 · "+phase(s.phase)+" "+s.seconds+" 秒 · 猎人出发前可随时更换":phase(s.phase),left+39,top+17,0xFFABBCB0,true);
        g.text(font,s.players+" / "+s.capacity,left+pw-48,top+10,0xFFD7C8A5,true);
        int detailY=top+ph-75;
        g.fill(left,detailY,left+pw,top+ph,0xF210191C);
        g.fill(left,detailY,left+pw,detailY+1,0x997F856C);
        if(!selectable){
            String heading=s.phase.equals("LOBBY")?"动物在开局后选择":s.phase.equals("RESULTS")?(s.practice?"练习结束":s.winner.equals("ANIMALS")?"动物胜利":"猎人胜利"):phase(s.phase);
            g.centeredText(font,heading,left+pw/2,gridY+8,0xFFFFDEA0);
            String body=s.phase.equals("LOBBY")?"在大厅点击牛加入动物队，点击玩家模型加入猎人队":s.phase.equals("GENERATING")?"正在生成场地："+s.progress+"%":s.theme+" · "+s.seconds+" 秒";
            g.centeredText(font,body,left+pw/2,gridY+26,0xFFBCC9BA);
            int y=gridY+44;for(var member:s.members){if(y>detailY-12)break;g.text(font,member.name()+" · "+(member.eliminated()?"已淘汰":"未淘汰"),left+18,y,0xFFD9DECF);y+=12;}
        }else if(cards.isEmpty())g.centeredText(font,"没有匹配的动物，请调整筛选或搜索",left+pw/2,gridY+18,0xFFD4D9CD);
        // Render models first so hover/focus can drive the persistent description below.
        super.extractRenderState(g,mx,my,a);
        var kind=AnimalKind.parse(s.animal);
        if(selectable){
            g.text(font,"已选："+kind.label+" · "+kind.trait+"  /  点击模型切换动物",left+10,detailY+6,kind.color,true);
            g.text(font,"任务：去"+kind.food().field+"吃 "+dev.morphhide.game.Food.BITES+" 口"+kind.food().label+" · 吃饱并活到最后才算赢",left+10,detailY+20,kind.food().color,true);
            g.text(font,(s.practice?"练习场 · 没有猎人":"藏匿阶段结束后猎人出发")+" · 猎人出发前可按 M 更换",left+10,detailY+33,0xFFCCD4C8,true);
            players(g,detailY);
        }else if(!s.phase.equals("LOBBY")){
            g.text(font,s.practice?"单人练习 · 无猎人，可体验变身和地图":"正式对局 · 动物被猎人击杀后淘汰",left+10,detailY+7,0xFFFFE2A0,true);
            g.text(font,s.host?"点击「返回大厅」可结束当前回合":"本局结束后会自动返回大厅",left+10,detailY+23,0xFFCCD4C8,true);
        }

    }
}
