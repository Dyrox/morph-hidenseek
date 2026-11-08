package dev.morphhide.client;

import com.google.gson.Gson;
import dev.morphhide.game.*;
import dev.morphhide.network.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.util.*;

public final class MorphHideClient implements ClientModInitializer {
    public static GameView state=new GameView();
    public static net.minecraft.client.KeyMapping readyKey;
    /** Shown in hints: the ready chord is Shift + the ready key (Y by default). */
    public static String readyChord(){return "Shift+"+(readyKey==null?"Y":readyKey.getTranslatedKeyMessage().getString());}
    /** Animals pick their form from the countdown until the hunters are released. */
    /** Animal disguises graze on right-click (sheep use the vanilla eat animation, others lower the head). */
    public static boolean canGraze(){return state.joined&&state.role.equals("ANIMAL")&&(state.phase.equals("HIDING")||state.phase.equals("HUNTING"));}
    public static boolean canPickAnimal(){return state.joined&&state.role.equals("ANIMAL")&&(state.phase.equals("COUNTDOWN")||state.phase.equals("GENERATING")||state.phase.equals("HIDING"));}
    /** Look-at tooltips for wall tiles: {title, description}. */
    static String[] wallTooltip(String action,GameView s){
        boolean lobby=s.phase.equals("LOBBY");
        if(action.startsWith("hunters:")){int n=action.charAt(action.length()-1)-'0';
            return new String[]{"猎人数："+(n==0?"自动":n+" 人"),(n==0?"每 5 人 1 名猎人，最多 2 名":"固定 "+n+" 名猎人，至少需要 "+(n+1)+" 人")+" · 仅房主可改，修改会取消所有人的准备"};}
        return switch(action){
            case "capacity_down"->new String[]{"减少人数上限","房主设置大厅最多可容纳的玩家数"};
            case "capacity_up"->new String[]{"增加人数上限","房主设置大厅最多可容纳的玩家数"};
            case "start"->lobby?new String[]{"开始对局（房主）","不等待准备，立即开始 "+s.minPlayers+" 人以上的对局"}:new String[]{"结束回合（房主）","结束当前回合，所有人返回大厅"};
            case "practice"->new String[]{"进入练习场（房主）","没有猎人，全员变身自由探索地图"};
            default->new String[]{action,""};
        };
    }
    public static void send(String action){if(ClientPlayNetworking.canSend(GamePackets.Action.TYPE))ClientPlayNetworking.send(new GamePackets.Action(action));}
    public void onInitializeClient(){
        ClientPlayNetworking.registerGlobalReceiver(GamePackets.State.TYPE,(packet,context)->{
            state=new Gson().fromJson(packet.json(),GameView.class);
            ResultsOverlay.onState(state);
            var old=new HashMap<>(MorphRegistry.CLIENT);var previous=new HashSet<>(old.keySet());MorphRegistry.CLIENT.clear();
            MorphRegistry.CLIENT_PROXIES.clear();MorphRegistry.CLIENT_EATING.clear();
            for(var disguise:state.disguises){var owner=UUID.fromString(disguise.uuid());MorphRegistry.CLIENT.put(owner,AnimalKind.parse(disguise.animal()));
                MorphRegistry.CLIENT_PROXIES.put(disguise.entityId(),owner);if(disguise.eating())MorphRegistry.CLIENT_EATING.add(owner);}
            MorphRegistry.CLIENT_TEAMMATES.clear();
            var me=context.client().player;
            for(var mate:state.roster){
                if(mate.entityId()<0||me==null||mate.uuid().equals(me.getUUID().toString()))continue;
                MorphRegistry.CLIENT_TEAMMATES.add(mate.entityId());
                // Client-side only: the server never names disguises, so hunters see no tag.
                var proxy=context.client().level==null?null:context.client().level.getEntity(mate.entityId());
                if(proxy!=null&&proxy.getCustomName()==null){proxy.setCustomName(net.minecraft.network.chat.Component.literal(mate.name()).withColor(TeamHud.MATE_COLOR));proxy.setCustomNameVisible(true);}
            }
            previous.addAll(MorphRegistry.CLIENT.keySet());
            if(context.client().level!=null)for(var id:previous){var p=context.client().level.getPlayerByUUID(id);if(p!=null&&old.get(id)!=MorphRegistry.CLIENT.get(id))p.refreshDimensions();}
        });
        ClientPlayNetworking.registerGlobalReceiver(GamePackets.OpenMenu.TYPE,(packet,context)->{
            if(context.client().player!=null)context.client().gui.setScreen(packet.page().equals("animals")?new AnimalSelectScreen():new LobbyScreen());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{state=new GameView();ResultsOverlay.reset();MorphRegistry.CLIENT.clear();MorphRegistry.CLIENT_PROXIES.clear();MorphRegistry.CLIENT_EATING.clear();MorphRegistry.CLIENT_TEAMMATES.clear();QuickLobby.cancel();});
        var category=net.minecraft.client.KeyMapping.Category.register(Identifier.fromNamespaceAndPath("morphhide","game"));
        var menu=net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new net.minecraft.client.KeyMapping("key.morphhide.menu",com.mojang.blaze3d.platform.InputConstants.KEY_M,category));
        readyKey=net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(new net.minecraft.client.KeyMapping("key.morphhide.ready_shift",com.mojang.blaze3d.platform.InputConstants.KEY_Y,category));
        // Runs before vanilla keybind handling, so the use click becomes "eat" instead of a block/item use.
        ClientTickEvents.START_CLIENT_TICK.register(client->{
            if(client.player!=null&&client.gui.screen()==null&&canGraze())while(client.options.keyUse.consumeClick())send("eat");
        });
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            QuickLobby.tick(client);
            if(client.level!=null&&client.player!=null)for(var disguise:state.disguises)if(disguise.uuid().equals(client.player.getUUID().toString())){
                var proxy=client.level.getEntity(disguise.entityId());
                if(proxy!=null)proxy.setInvisible(client.options.getCameraType().isFirstPerson());
            }
            while(readyKey.consumeClick())if(client.player!=null&&state.joined&&state.phase.equals("LOBBY")&&client.options.keyShift.isDown())send("ready");
            while(menu.consumeClick())if(client.player!=null){send("sync");client.gui.setScreen(canPickAnimal()?new AnimalSelectScreen():new LobbyScreen());}});
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("morphhide","status"),(g,delta)->{
            if(!state.joined)return;var mc=Minecraft.getInstance();if(mc.gui.screen() instanceof LobbyScreen||mc.gui.screen() instanceof AnimalSelectScreen)return;var s=state;int w=mc.getWindow().getGuiScaledWidth();
            if(ResultsOverlay.active(s)){ResultsOverlay.render(g,s);return;}
            boolean lobby=s.phase.equals("LOBBY"),results=s.phase.equals("RESULTS");
            String menuKey=menu.getTranslatedKeyMessage().getString();
            int panelWidth=Math.min(w-16,300);
            String phaseLabel=results?(s.practice?"练习结束":s.winner.equals("ANIMALS")?"动物胜利":"猎人胜利"):
                s.practice?"单人练习 · "+LobbyScreen.phase(s.phase):LobbyScreen.phase(s.phase);
            String clock=lobby||s.phase.equals("GENERATING")?"":"  "+String.format("%02d:%02d",s.seconds/60,s.seconds%60);
            if(lobby){
                // Compact ready-up panel: state line, pulsing call to action, countdown bar and one mark per player.
                String readyKeyName=readyChord();
                long ready=s.members.stream().filter(GameView.Member::ready).count();
                boolean running=s.readySeconds>=0;
                int x0=(w-panelWidth)/2,bottom=running?61:45;
                g.fill(x0,6,x0+panelWidth,bottom,0xB0101A17);
                g.fill(x0,6,x0+panelWidth,7,0xFF6F8F5F);
                String head=s.players<s.minPlayers?"等待玩家 · 对局至少 "+s.minPlayers+" 人 · 当前 "+s.players+" 人":
                    running?"已准备 "+ready+" / "+s.players+" · "+s.readySeconds+" 秒后开始":"已准备 "+ready+" / "+s.players+" · 全员准备后自动开始";
                g.centeredText(mc.font,head,w/2,12,0xFFA3F5A3);
                double pulse=Math.sin(System.currentTimeMillis()/1000.0*2*Math.PI)*.4+.6;
                int cta=s.ready?0xFFFFFFFF:((int)(pulse*255)<<24)|0xFFFFFF;
                g.centeredText(mc.font,s.ready?"✔ 你已准备 · 再按 "+readyKeyName+" 取消":"按 "+readyKeyName+" 准备  ·  左键点击牛 / 玩家模型加入队伍",w/2,26,cta);
                if(running){
                    int bx=x0+12,bw=panelWidth-24,by=40;
                    float progress=1f-Math.clamp(s.readySeconds/(float)Math.max(1,s.readyTotal),0f,1f);
                    g.fill(bx,by,bx+bw,by+4,0x66FFFFFF);g.fill(bx,by,bx+(int)(bw*progress),by+4,0xFFFFFFFF);
                    int count=Math.min(10,s.players),cw=mc.font.width("✔")+2,cx=w/2-count*cw/2;
                    for(int i=0;i<count;i++){boolean on=i*s.players<count*ready;g.text(mc.font,on?"✔":"•",cx+i*cw,49,on?0xFFA3F5A3:0xFFFFFFFF,true);}
                }
            }else{
                g.fill((w-panelWidth)/2,7,(w+panelWidth)/2,43,0xDD15251F);
                g.centeredText(mc.font,dev.morphhide.MorphHide.NAME+"  /  "+phaseLabel+clock,w/2,13,0xFFE6DCC5);
                String detail=results?"本局结束 · "+s.seconds+" 秒后返回大厅":
                    s.phase.equals("GENERATING")?"场地生成 "+s.progress+"%":s.practice?"无猎人 · 自由探索 · 当前动物："+AnimalKind.parse(s.animal).label:
                    (s.phase.equals("HUNTING")?(s.lured?"诱饵阶段 · ":s.lureIn>=0?"诱饵 "+s.lureIn+" 秒后发放 · ":""):"")+"存活动物 "+s.alive+"  /  猎人 "+s.hunters+"  ·  "+(s.role.equals("HUNTER")?"猎人":s.role.equals("ANIMAL")?AnimalKind.parse(s.animal).label:s.role.equals("SPECTATOR")?"观战":"等待下一局");
                g.centeredText(mc.font,detail,w/2,29,0xFFAAC5AF);
                TaskHud.render(g,s,w,46);
            }
            int y=mc.getWindow().getGuiScaledHeight()-68;
            String action=lobby?"":canPickAnimal()?"当前动物："+AnimalKind.parse(s.animal).label+" · 按 "+menuKey+" 更换（猎人出发前）"+(canGraze()?" · 右键吃草":""):
                results?"回合结束 · 等待返回大厅":s.practice?"自由探索地图 · 按 "+menuKey+" 可返回大厅":
                s.role.equals("SPECTATOR")?"你已被淘汰 · 正在观战 · "+menuKey+" 菜单":
                s.role.equals("HUNTER")?"找出并击杀所有动物玩家 · 蹲守农田 · "+menuKey+" 菜单":
                s.role.equals("ANIMAL")?"吃饱并活到最后 · 右键进食/吃草 · "+menuKey+" 菜单":"即将开局 · "+menuKey+" 菜单";
            if(!lobby)g.centeredText(mc.font,action,w/2,y,0xFFF0DCAD);
            TeamHud.render(g,s,100); // below the tallest task panel, which is centred and can be wide
            if(!s.eliminations.isEmpty()){
                int panelW=Math.min(230,w-16),x=w-panelW-8,start=Math.max(0,s.eliminations.size()-4),lineY=68;
                g.fill(x,50,w-8,69+(s.eliminations.size()-start)*12,0xBB10191C);
                g.text(mc.font,"淘汰记录",x+6,54,0xFFE6C99D,true);
                for(int i=start;i<s.eliminations.size();i++){
                    var event=s.eliminations.get(i);
                    String line=event.left()?event.name()+" 退出本局":event.name()+" 被 "+(event.hunter().isEmpty()?"猎人":event.hunter())+" 淘汰";
                    g.text(mc.font,mc.font.plainSubstrByWidth(line,panelW-12),x+6,lineY,0xFFFFB8A5,true);lineY+=12;
                }
            }
            if(mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit&&hit.getEntity().getCustomName()!=null){
                String name=hit.getEntity().getCustomName().getString();
                if(name.startsWith(LobbyControlWall.PREFIX)){
                    String[] tip=wallTooltip(name.substring(LobbyControlWall.PREFIX.length()),s);
                    String title="左键 · "+tip[0];
                    int tw=Math.min(w-16,Math.max(mc.font.width(title),mc.font.width(tip[1]))+16),hintY=mc.getWindow().getGuiScaledHeight()/2+16;
                    g.fill((w-tw)/2,hintY-4,(w+tw)/2,hintY+(tip[1].isEmpty()?12:24),0xF0100010);
                    g.fill((w-tw)/2,hintY-4,(w+tw)/2,hintY-3,0xFF5000A0);
                    g.centeredText(mc.font,title,w/2,hintY,0xFF99FF99);
                    if(!tip[1].isEmpty())g.centeredText(mc.font,mc.font.plainSubstrByWidth(tip[1],tw-8),w/2,hintY+12,0xFFD0D0D0);
                }
            }
            if(!s.hint.isEmpty())g.centeredText(mc.font,s.hint,w/2,y-13,0xFFFFFFFF);
        });
    }
}
