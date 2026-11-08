package dev.morphhide.game;

import com.google.gson.Gson;
import dev.morphhide.MorphHide;
import dev.morphhide.network.*;
import java.util.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.Vec3;

public final class GameManager {
    public static final ResourceKey<Level> DIMENSION=ResourceKey.create(Registries.DIMENSION,Identifier.fromNamespaceAndPath("morphhide","reserve"));
    private final MinecraftServer server;
    private final GameConfig config=GameConfig.load();
    private final PlayerBackup backups;
    private final LinkedHashMap<UUID,Member> members=new LinkedHashMap<>();
    private final Map<UUID,UUID> proxies=new HashMap<>();
    private final Set<UUID> herd=new HashSet<>();
    private final List<GameView.Elimination> eliminations=new ArrayList<>();
    private final LobbyControlWall wall=new LobbyControlWall();
    private ArenaBuilder arena;
    private String phase="LOBBY",winner="";
    private int ticks,timer;
    private boolean practice,lobbyBuilt;
    /** Ready-up timer: it shrinks as more players ready, and starts at once when everyone is ready. */
    public static final int READY_TICKS=60*20;
    /** One bite (or graze) takes 3 seconds of standing still. */
    public static final int EAT_TICKS=60;
    private int readyElapsed=-1;
    private boolean lured,shrinking;
    private int shrinkStartTick;
    private final List<Entity> farmLabels=new ArrayList<>();
    /** Herd animals that died and come back elsewhere, so hunters cannot clear the map to find players. */
    private final ArrayDeque<AnimalKind> herdRespawns=new ArrayDeque<>();
    /** Herd animals walking into the final circle, with where each is heading. */
    private final Map<UUID,Vec3> shrinkHerd=new HashMap<>();
    /** Hunters who hit a real animal. Applied next tick: Weakness before the hit lands would cancel its damage. */
    private final Set<UUID> wrongHits=new HashSet<>();
    private int huntStartTick,resultId;
    private boolean huntStarted;
    private GameView.Results results;
    private final List<GameView.ResultRow> departed=new ArrayList<>();
    private int huntElapsed(){return huntStarted?ticks-huntStartTick:0;}
    /** Players who connected (e.g. LAN guests) and are moved into the lobby on the next tick, like bingo's lobby mode. */
    private final Set<UUID> pendingJoins=new LinkedHashSet<>();
    private final Gson gson=new Gson();
    private static final class Member {
        AnimalKind animal=AnimalKind.SHEEP;
        String role="WAITING",preference="AUTO",hint="";
        boolean ready;
        int eating; // ticks left of the sheep eat animation
        // Round statistics for the results screen.
        String side="";
        int outAt=-1,kills,lastKillAt=-1;
        String killer="";
        boolean left;
        int fed;            // bites of the right crop eaten this round
        boolean grazeGrass; // current eat animation found no crop: graze grass at the end
        BlockPos eatTarget;  // crop being eaten; the bite lands only after the full eating time
        Vec3 eatFrom;

        Mob proxy;
    }
    public GameManager(MinecraftServer server) {this.server=server;backups=new PlayerBackup(server);}
    public boolean contains(UUID id){return members.containsKey(id);}
    public boolean protectedEntity(Entity e){return contains(e.getUUID())||proxies.containsKey(e.getUUID())||herd.contains(e.getUUID());}
    private ServerLevel level(){return server.getLevel(DIMENSION);}
    private boolean host(ServerPlayer p){return !members.isEmpty()&&members.keySet().iterator().next().equals(p.getUUID());}
    private void tell(ServerPlayer p,String text){p.sendSystemMessage(Component.literal("§a["+MorphHide.NAME+"] §f"+text));}
    public void connected(ServerPlayer p){
        try{backups.restore(p);}catch(Exception e){MorphHide.LOGGER.error("Recovery failed",e);tell(p,"存档恢复失败，备份已保留，请查看服务器日志。");}
        send(p);
        if(autoJoin())pendingJoins.add(p.getUUID());
    }
    /** Everyone who connects to the lobby save, or to a server that already runs a lobby, joins automatically. */
    private boolean autoJoin(){return !members.isEmpty()||lobbySave();}
    private boolean lobbySave(){
        var root=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
        return root!=null&&root.toString().equals(MorphHide.LOBBY_SAVE);
    }
    private boolean worldLocked;
    /**
     * The dedicated lobby save is always a clear morning (like bingo: overworld clock via the clock
     * manager, plus the advance_time/advance_weather rules). Never applied to other worlds.
     */
    private void lockWorld(){
        worldLocked=true;
        if(!lobbySave())return;
        server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME,false,server);
        server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER,false,server);
        // Real animals must not eat the fields (vanilla rabbits raid carrots): only players do.
        server.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.MOB_GRIEFING,false,server);
        server.setWeatherParameters(6000,0,false,false);
        server.registryAccess().get(net.minecraft.world.clock.WorldClocks.OVERWORLD).ifPresent(clock->server.clockManager().setTotalTicks(clock,1000));
    }
    public void action(ServerPlayer p,String action){
        if(action.equals("sync")){send(p);return;}
        if(action.equals("join")){
            if(contains(p.getUUID()))return;
            if(level()==null){tell(p,"独立维度未加载，请重新打开世界。");return;}
            if(members.size()>=config.maxPlayers){tell(p,"大厅已满。");return;}
            try{backups.save(p);}catch(Exception e){tell(p,"无法备份玩家数据，加入已取消。");MorphHide.LOGGER.error("Backup failed",e);return;}
            if(!lobbyBuilt){ArenaBuilder.buildLobby(level());wall.build(level());lobbyBuilt=true;}
            members.put(p.getUUID(),new Member());
            prepare(p);teleport(p,ArenaBuilder.LOBBY);tell(p,"已加入大厅。点击大厅里的动物或玩家模型选择队伍，Shift+Y 准备。");
        }
        var m=members.get(p.getUUID());if(m==null)return;
        if(action.equals("leave")){leave(p,false);return;}
        if(action.equals("stop")){if(host(p))reset();else tell(p,"只有房主可以结束回合。");return;}
        if(action.equals("eat")){
            // Vanilla sheep graze: entity event 10 lowers the head for 40 ticks (EatBlockGoal).
            if(m.role.equals("ANIMAL")&&m.proxy!=null&&m.eating==0&&(phase.equals("HIDING")||phase.equals("HUNTING"))){
                m.eating=EAT_TICKS;if(m.animal==AnimalKind.SHEEP)level().broadcastEntityEvent(m.proxy,(byte)10);
                eatCrop(p,m);sync();
            }
            return;
        }
        if(action.startsWith("animal:")){
            // Animals pick their form only after the match begins, until the hunt starts.
            if(!m.role.equals("ANIMAL")||!Set.of("COUNTDOWN","GENERATING","HIDING").contains(phase)){tell(p,"开局后动物队才能选择动物（猎人出发前）。");send(p);return;}
            var picked=AnimalKind.parse(action.substring(7));
            if(picked!=m.animal&&m.fed>0)p.sendOverlayMessage(Component.literal("换了动物，进食进度清零").withColor(0xFF9A8A));
            if(picked!=m.animal)m.fed=0;
            m.animal=picked;
            p.sendOverlayMessage(Component.literal(picked.label+"：要去"+picked.food().field+"吃 "+Food.BITES+" 口"+picked.food().label).withColor(picked.food().color&0xFFFFFF));
            if(phase.equals("HIDING")&&m.proxy!=null){proxies.remove(m.proxy.getUUID());m.proxy.discard();m.proxy=null;morph(p,m);}
            sync();return;
        }
        if(!phase.equals("LOBBY")){send(p);return;}
        if(Set.of("capacity_down","capacity_up","hunters").contains(action)||action.startsWith("hunters:")){
            if(!host(p)){tell(p,"只有房主可以修改大厅设置。");return;}
            if(action.startsWith("hunters")){
                int value=action.startsWith("hunters:")?Math.clamp(action.charAt(action.length()-1)-'0',0,2):(config.hunterCount+1)%3;
                if(value!=config.hunterCount){config.hunterCount=value;unreadyAll();}
            }
            else config.maxPlayers=Math.clamp(config.maxPlayers+(action.equals("capacity_up")?1:-1),Math.max(2,members.size()),24);
            config.save();sync();return;
        }
        if(action.startsWith("role:")||action.startsWith("team:")){String pref=action.substring(5);if(Set.of("AUTO","HUNTER","ANIMAL").contains(pref)&&!pref.equals(m.preference)){m.preference=pref;m.ready=false;
            String line=p.getName().getString()+(pref.equals("HUNTER")?" 加入了猎人队":pref.equals("ANIMAL")?" 加入了动物队":" 改为随机分配队伍");
            for(var id:members.keySet()){var other=server.getPlayerList().getPlayer(id);if(other!=null)tell(other,line);}}}
        if(action.equals("ready"))m.ready=!m.ready;
        if(action.equals("practice")){
            if(host(p)){practice=true;begin();}else tell(p,"请房主在控制墙开启练习场。");
        }
        if(action.equals("start")){
            if(!host(p))tell(p,"只有房主可以开始对局。");
            else if(members.size()<Rules.requiredPlayers(config.hunterCount))tell(p,"当前设置至少需要 "+Rules.requiredPlayers(config.hunterCount)+" 人；一个人可以去练习场。");
            else {practice=false;begin();}
        }
        sync();
    }
    private void prepare(ServerPlayer p){
        // closeContainer() also closes any client screen (e.g. the animal picker); only use it for real containers.
        if(p.containerMenu!=p.inventoryMenu)p.closeContainer();
        p.getInventory().clearContent();p.removeAllEffects();p.setInvisible(false);p.setGameMode(GameType.ADVENTURE);
        p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(20);
        p.setHealth(p.getMaxHealth());p.getFoodData().setFoodLevel(20);
        var attr=p.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE);if(attr!=null)attr.setBaseValue(0);
    }
    private void teleport(ServerPlayer p,Vec3 pos){p.teleportTo(level(),pos.x,pos.y,pos.z,Set.of(),pos.equals(ArenaBuilder.LOBBY)?0:p.getYRot(),pos.equals(ArenaBuilder.LOBBY)?-10:0,true);}
    private void unreadyAll(){members.values().forEach(a->a.ready=false);readyElapsed=-1;}
    private int readyRemaining(){
        if(readyElapsed<0||members.isEmpty())return -1;
        double ratio=members.values().stream().filter(a->a.ready).count()/(double)members.size();
        return (int)Math.round(READY_TICKS*(1-ratio))-readyElapsed;
    }
    private void tickReady(){
        boolean enough=members.size()>=Rules.requiredPlayers(config.hunterCount);
        boolean anyReady=members.values().stream().anyMatch(a->a.ready);
        if(!enough||!anyReady){readyElapsed=-1;return;}
        if(readyElapsed<0)readyElapsed=0;else readyElapsed++;
        if(readyRemaining()<=0){unreadyAll();practice=false;begin();sync();}
    }
    private void begin(){
        readyElapsed=-1;phase="COUNTDOWN";timer=config.countdownSeconds*20;winner="";eliminations.clear();
        results=null;departed.clear();huntStarted=false;shrinking=false;wrongHits.clear();
        // Teams are known up front so animals can pick their form during the countdown.
        var ids=new ArrayList<>(members.keySet());Collections.shuffle(ids);
        ids.sort(Comparator.comparingInt(id->switch(members.get(id).preference){case "HUNTER"->0;case "ANIMAL"->2;default->1;}));
        int hunters=practice?0:Rules.configuredHunters(ids.size(),config.hunterCount),i=0;
        for(var id:ids){var m=members.get(id);m.role=i++<hunters?"HUNTER":"ANIMAL";m.ready=false;
            m.side=m.role;m.outAt=-1;m.kills=0;m.lastKillAt=-1;m.killer="";m.left=false;m.fed=0;
            var p=server.getPlayerList().getPlayer(id);
            if(p!=null&&m.role.equals("ANIMAL")&&ServerPlayNetworking.canSend(p,GamePackets.OpenMenu.TYPE))ServerPlayNetworking.send(p,new GamePackets.OpenMenu("animals"));
            if(p!=null)tell(p,m.role.equals("HUNTER")?"你是猎人，藏匿阶段结束后出发。":"你是动物，现在选择你的动物（猎人出发前都可以更换）。");
        }
    }
    private void morph(ServerPlayer p,Member m){
        MorphRegistry.SERVER.put(p.getUUID(),m.animal);p.setSprinting(false);p.refreshDimensions();p.setInvisible(true);
        m.proxy=spawn(m.animal,p.position(),true);
        if(m.proxy!=null){proxies.put(m.proxy.getUUID(),p.getUUID());p.getAttribute(Attributes.MAX_HEALTH).setBaseValue(m.proxy.getMaxHealth());p.setHealth(p.getMaxHealth());}
    }
    public void leave(ServerPlayer p,boolean disconnected){
        pendingJoins.remove(p.getUUID());
        Member m=members.remove(p.getUUID());
        if(m!=null&&!m.side.isEmpty()&&Set.of("HIDING","HUNTING").contains(phase)){
            if(m.outAt<0&&m.side.equals("ANIMAL"))m.outAt=huntElapsed();
            m.left=true;departed.add(row(p.getUUID(),p.getName().getString(),m,huntElapsed()));
        }
        if(m!=null){if(m.role.equals("ANIMAL")&&(phase.equals("HUNTING")||phase.equals("HIDING")))announce(p.getName().getString(),"",true);unmorph(p,m);}
        if(!disconnected)try{backups.restore(p);}catch(Exception e){MorphHide.LOGGER.error("Restore failed; backup retained",e);tell(p,"恢复失败，备份已保留。");}
        if(members.isEmpty())reset();
        else if((phase.equals("COUNTDOWN")||phase.equals("GENERATING"))&&members.size()<Rules.requiredPlayers(config.hunterCount)&&!practice)reset();
        if(!disconnected)send(p);
    }
    public void stop(){for(var id:List.copyOf(members.keySet())){var p=server.getPlayerList().getPlayer(id);if(p!=null)leave(p,false);}cleanup();arenaChunks(false);wall.close();MorphRegistry.SERVER.clear();}
    private void cleanup(){if(level()!=null){for(var id:herd){var e=level().getEntity(id);if(e!=null)e.discard();}}herd.clear();proxies.clear();herdRespawns.clear();shrinkHerd.clear();
        for(var e:farmLabels)if(!e.isRemoved())e.discard();farmLabels.clear();}
    /** Floating, see-through signs above every field: "胡萝卜田 / 猪 · 兔子 吃这里". */
    private void labelFarms(){
        for(var farm:arena.farms){
            var label=EntityTypes.TEXT_DISPLAY.create(level(),EntitySpawnReason.COMMAND);if(label==null)continue;
            label.snapTo(farm.x()+.5,ArenaBuilder.FLOOR+3.2,farm.z()+.5,0,0);
            label.setBillboardConstraints(Display.BillboardConstraints.CENTER);
            label.setText(Component.literal(farm.food().field).withColor(farm.food().color&0xFFFFFF).append(Component.literal("\n"+farm.food().eaters()+" 吃这里").withColor(0xFFFFFF)));
            label.setFlags((byte)(Display.TextDisplay.FLAG_SHADOW|Display.TextDisplay.FLAG_SEE_THROUGH));
            label.setViewRange(4f);label.setBackgroundColor(0x60000000);
            label.setTransformation(new com.mojang.math.Transformation(new org.joml.Vector3f(),new org.joml.Quaternionf(),new org.joml.Vector3f(1.6f),new org.joml.Quaternionf()));
            label.addTag("morphhide_farm");level().addFreshEntity(label);farmLabels.add(label);
        }
    }
    private void unmorph(ServerPlayer p,Member m){resetBorder(p);if(m.proxy!=null){proxies.remove(m.proxy.getUUID());m.proxy.discard();m.proxy=null;}MorphRegistry.SERVER.remove(p.getUUID());p.setInvisible(false);p.refreshDimensions();}
    private void reset(){
        for(var entry:members.entrySet()){var p=server.getPlayerList().getPlayer(entry.getKey());if(p!=null){unmorph(p,entry.getValue());prepare(p);teleport(p,ArenaBuilder.LOBBY);}var m=entry.getValue();m.role="WAITING";m.ready=false;m.hint="";m.side="";}
        cleanup();arenaChunks(false);eliminations.clear();phase="LOBBY";timer=0;practice=false;sync();
    }
    private Mob spawn(AnimalKind kind,Vec3 pos,boolean disguise){
        Mob mob=kind.entityType.create(level(),EntitySpawnReason.COMMAND);if(mob==null)return null;
        mob.snapTo(pos.x,pos.y,pos.z,0,0);mob.setPersistenceRequired();mob.addTag("morphhide_owned");
        if(disguise){mob.setNoAi(true);mob.setNoGravity(true);mob.noPhysics=true;}
        level().addFreshEntity(mob);return mob;
    }
    private void startRound(){
        int i=0;
        for(var id:members.keySet()){var p=server.getPlayerList().getPlayer(id);if(p==null)continue;var m=members.get(id);prepare(p);
            if(m.role.equals("ANIMAL")){teleport(p,arena.spawn(i));morph(p,m);
                tell(p,"你的任务：去"+m.animal.food().field+"吃 "+Food.BITES+" 口"+m.animal.food().label+"（跟着屏幕上的箭头走，站在作物旁右键）。吃饱并活到最后才算赢！");
                tell(p,"提示：每吃一口都会发出叫声；平时右键可以低头吃草装成普通动物。");}
            else if(m.role.equals("HUNTER")){teleport(p,ArenaBuilder.LOBBY);p.getInventory().setItem(0,hunterSword());p.getInventory().setSelectedSlot(0);}
            i++;
        }
        Random random=new Random(arena.seed);
        for(i=0;i<config.herdSize;i++){var pos=arena.spawn(i).add(random.nextInt(5)-2,0,random.nextInt(5)-2);var mob=spawn(AnimalKind.values()[i%6],pos,false);if(mob!=null)herd.add(mob.getUUID());}
        labelFarms();
        phase="HIDING";timer=config.hideSeconds*20;sync();
    }
    /** Wood instead of iron; unbreakable because respawning animals could otherwise wear it out mid-round. */
    private static ItemStack hunterSword(){
        var sword=new ItemStack(Items.WOODEN_SWORD);
        sword.set(net.minecraft.core.component.DataComponents.UNBREAKABLE,net.minecraft.util.Unit.INSTANCE);
        return sword;
    }
    private static AnimalKind kindOf(Entity entity){
        for(var kind:AnimalKind.values())if(kind.entityType==entity.getType())return kind;
        return null;
    }
    /** A dead herd animal is replaced, wherever and however it died. */
    public void afterDeath(LivingEntity entity){
        if(!herd.remove(entity.getUUID()))return;
        shrinkHerd.remove(entity.getUUID());
        var kind=kindOf(entity);
        if(kind!=null&&(phase.equals("HIDING")||phase.equals("HUNTING")))herdRespawns.add(kind);
    }
    private void respawnHerd(AnimalKind kind){
        var mob=spawn(kind,herdSpot(kind),false);if(mob==null)return;
        herd.add(mob.getUUID());
        if(shrinking&&shrinkHerd.size()<config.shrinkHerd)shrinkHerd.put(mob.getUUID(),shrinkTarget());
    }
    /** Free ground inside the current border, out of the hunters' sight when the arena allows it. */
    private Vec3 herdSpot(AnimalKind kind){
        var random=level().getRandom();
        int range=(int)Math.max(1,Math.min(config.arenaRadius-3,borderSize()/2-1));
        Vec3 free=null;
        for(int i=0;i<60;i++){
            int x=ArenaBuilder.CENTER_X+random.nextInt(2*range+1)-range,z=random.nextInt(2*range+1)-range;
            var feet=new BlockPos(x,ArenaBuilder.FLOOR+1,z);
            var pos=new Vec3(x+.5,feet.getY(),z+.5);
            if(!level().getBlockState(feet).isAir()||!level().getBlockState(feet.below()).getFluidState().isEmpty()||!level().noCollision(kind.entityType.getSpawnAABB(pos.x,pos.y,pos.z)))continue;
            if(free==null)free=pos;
            if(hiddenFromHunters(pos))return pos;
        }
        return free!=null?free:arena.spawn(random.nextInt(4));
    }
    private boolean hiddenFromHunters(Vec3 pos){
        if(!phase.equals("HUNTING"))return true; // hunters still wait in the lobby
        for(var e:members.entrySet()){
            if(!e.getValue().role.equals("HUNTER"))continue;
            var hunter=server.getPlayerList().getPlayer(e.getKey());if(hunter==null)continue;
            if(hunter.position().distanceTo(pos)<16)return false;
            var sight=level().clip(new ClipContext(hunter.getEyePosition(),pos.add(0,.5,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,hunter));
            if(sight.getType()==net.minecraft.world.phys.HitResult.Type.MISS)return false;
        }
        return true;
    }
    private Vec3 shrinkTarget(){
        var random=level().getRandom();
        double range=Math.max(1,config.shrinkFinalSize/2.0-2);
        return new Vec3(256.5+(random.nextDouble()*2-1)*range,ArenaBuilder.FLOOR+1,.5+(random.nextDouble()*2-1)*range);
    }
    /** Only part of the herd walks in: the closest of each species in turn, so every species stays represented. */
    private void pickShrinkHerd(){
        shrinkHerd.clear();
        var center=new Vec3(256.5,ArenaBuilder.FLOOR+1,.5);
        var byKind=new EnumMap<AnimalKind,ArrayDeque<Mob>>(AnimalKind.class);
        var alive=new ArrayList<Mob>();
        for(var id:herd)if(level().getEntity(id) instanceof Mob mob&&mob.isAlive())alive.add(mob);
        alive.sort(Comparator.comparingDouble(mob->mob.position().distanceToSqr(center)));
        for(var mob:alive){var kind=kindOf(mob);if(kind!=null)byKind.computeIfAbsent(kind,k->new ArrayDeque<>()).add(mob);}
        while(shrinkHerd.size()<config.shrinkHerd&&byKind.values().stream().anyMatch(q->!q.isEmpty()))
            for(var queue:byKind.values())if(!queue.isEmpty()&&shrinkHerd.size()<config.shrinkHerd)shrinkHerd.put(queue.poll().getUUID(),shrinkTarget());
    }
    /** Re-issued every second: idle wandering can replace the path, and a long walk gets there in partial paths. */
    private void walkShrinkHerd(){
        double limit=config.shrinkFinalSize/2.0-1;
        for(var entry:shrinkHerd.entrySet()){
            if(!(level().getEntity(entry.getKey()) instanceof Mob mob)||!mob.isAlive())continue;
            if(Math.abs(mob.getX()-256.5)<=limit&&Math.abs(mob.getZ()-.5)<=limit)continue;
            var to=entry.getValue();mob.getNavigation().moveTo(to.x,to.y,to.z,1.0);
        }
    }
    public void tick(){
        ticks++;
        if(!worldLocked)lockWorld();
        for(var id:wrongHits){
            var hunter=server.getPlayerList().getPlayer(id);if(hunter==null||!isHunter(hunter))continue;
            hunter.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOWNESS,60,1));
            hunter.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.BLINDNESS,60));
            hunter.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS,60));
        }
        wrongHits.clear();
        if(!herdRespawns.isEmpty()&&(phase.equals("HIDING")||phase.equals("HUNTING")))respawnHerd(herdRespawns.poll());
        if(shrinking&&phase.equals("HUNTING")&&ticks%20==0)walkShrinkHerd();
        if(!pendingJoins.isEmpty()){
            for(var id:List.copyOf(pendingJoins)){var p=server.getPlayerList().getPlayer(id);pendingJoins.remove(id);if(p!=null&&!contains(id))action(p,"join");}
        }
        if(phase.equals("LOBBY"))tickReady();
        if(phase.equals("GENERATING")){if(arena.tick(level()))startRound();}
        else if(!phase.equals("LOBBY")&&--timer<=0){switch(phase){
            case "COUNTDOWN"->{phase="GENERATING";cleanup();arenaChunks(true);arena=new ArenaBuilder(config.arenaRadius,new Random().nextLong());}
            case "HIDING"->{phase="HUNTING";lured=false;huntStartTick=ticks;huntStarted=true;timer=(practice?60:config.huntSeconds)*20;for(var e:members.entrySet())if(e.getValue().role.equals("HUNTER")){var p=server.getPlayerList().getPlayer(e.getKey());if(p!=null)teleport(p,new Vec3(256.5,101,0.5));}}
            case "HUNTING"->endHunt();
            case "RESULTS"->reset();
        }}
        for(var entry:members.entrySet()){
            var p=server.getPlayerList().getPlayer(entry.getKey());if(p==null)continue;var m=entry.getValue();
            // Vanilla regenerates quickly on a full bar; real animals never regenerate. During a round keep
            // food at 17 (below the 18 needed to heal, above the 6 needed to sprint) with no saturation.
            boolean inRound=phase.equals("HIDING")||phase.equals("HUNTING");
            p.getFoodData().setFoodLevel(inRound?17:20);if(inRound)p.getFoodData().setSaturation(0);
            boolean active=(phase.equals("HIDING")||phase.equals("HUNTING"))&&(m.role.equals("ANIMAL")||(m.role.equals("HUNTER")&&phase.equals("HUNTING")));
            if(active){
                double r=config.arenaRadius-2;
                double dx=p.getX()-256.5,dz=p.getZ()-.5;
                if(p.level()!=level()||p.getY()<99||Math.abs(dx)>r||Math.abs(dz)>r){teleport(p,new Vec3(256.5+Math.clamp(dx,-r+2,r-2),101,.5+Math.clamp(dz,-r+2,r-2)));}
            }else if(!m.role.equals("SPECTATOR")&&(p.level()!=level()||p.getY()<99||Math.abs(p.getX())>17||Math.abs(p.getZ())>17))teleport(p,ArenaBuilder.LOBBY);
            if(shrinking&&active&&ticks%10==0){
                double half=borderSize()/2,dx=p.getX()-256.5,dz=p.getZ()-.5;
                if(Math.abs(dx)>half||Math.abs(dz)>half){
                    if(m.role.equals("ANIMAL"))p.hurtServer(level(),p.damageSources().outOfBorder(),2);
                    else teleport(p,new Vec3(256.5+Math.clamp(dx,-half+1,half-1),p.getY(),.5+Math.clamp(dz,-half+1,half-1)));
                }
            }
            if(m.proxy!=null&&m.proxy.isRemoved()&&level().getEntity(m.proxy.getUUID()) instanceof Mob live)m.proxy=live; // reloaded instance
            if(m.proxy!=null){p.setInvisible(true);
                // The disguise has no physics, so mirror the player's grounding: vanilla chickens flap
                // whenever they are airborne, and a rabbit hops (entity event 1) when it leaves the ground.
                if(m.eating>0){
                    if(m.eatTarget!=null&&p.position().distanceTo(m.eatFrom)>1.0){
                        // Moving away interrupts the bite: nothing is eaten.
                        m.eating=0;m.eatTarget=null;
                        p.sendOverlayMessage(Component.literal("进食被打断：吃一口要站着不动 3 秒").withColor(0xFF9A8A));sync();
                    }else{
                        m.eating--;
                        if(m.eating==20&&m.animal==AnimalKind.SHEEP)level().broadcastEntityEvent(m.proxy,(byte)10); // sheep animation lasts 40 ticks
                        if(m.eating==4&&m.grazeGrass)graze(p);
                        if(m.eating==0){if(m.eatTarget!=null)finishBite(p,m);sync();}
                    }
                }
                boolean wasGrounded=m.proxy.onGround();
                DisguiseMotion.follow(m.proxy,p,m.eating>0,m.animal==AnimalKind.SHEEP);
                if(wasGrounded&&!p.onGround()&&m.animal==AnimalKind.RABBIT)level().broadcastEntityEvent(m.proxy,(byte)1);
            }
        }
        if(phase.equals("HUNTING")&&!practice&&!lured&&huntElapsedTicks()>=config.lureSeconds*20)giveLures();
        if(phase.equals("HUNTING")&&!practice&&!shrinking&&config.shrinkSeconds>0&&timer<=config.shrinkSeconds*20)startShrink();
        if(phase.equals("HUNTING")||phase.equals("HIDING")){String win=Rules.winner(count("ANIMAL"),count("HUNTER"),timer,practice);if(!win.isEmpty())finish(win);}
        if(ticks%20==0&&level()!=null){
            // Discarding mutates the live entity index and can invalidate its iterator.
            var stale=new ArrayList<Entity>();
            for(var entity:level().getAllEntities()){
                if(entity.entityTags().contains("morphhide_owned")&&!herd.contains(entity.getUUID())&&!proxies.containsKey(entity.getUUID()))stale.add(entity);
                else if(lobbyBuilt&&wall.stale(entity))stale.add(entity);
                // Anything that got in before natural spawning was blocked (old saves): the board only holds our own animals.
                else if(entity instanceof Mob&&!entity.entityTags().contains("morphhide_owned")&&wall.action(entity)==null)stale.add(entity);
                else if(entity.entityTags().contains("morphhide_farm")&&farmLabels.stream().noneMatch(l->l.getUUID().equals(entity.getUUID())))stale.add(entity);
            }
            for(var entity:stale)entity.discard();
        }
        if(ticks%10==0)sync();
    }
    /** Eat one ripe crop of the animal's food next to it; otherwise explain what to eat instead. */
    private void eatCrop(ServerPlayer p,Member m){
        Food food=m.animal.food();
        BlockPos crop=null,other=null;
        var feet=p.blockPosition();
        // Eat the closest matching plant (the one underfoot first).
        double best=Double.MAX_VALUE;
        for(var pos:BlockPos.betweenClosed(feet.offset(-1,0,-1),feet.offset(1,1,1))){
            var state=level().getBlockState(pos);
            double d=net.minecraft.world.phys.Vec3.atCenterOf(pos).distanceToSqr(p.position().add(0,.5,0));
            if(state.equals(food.ripe())&&d<best){best=d;crop=pos.immutable();}
            for(var f:Food.values())if(f!=food&&state.equals(f.ripe()))other=pos.immutable();
        }
        m.grazeGrass=crop==null;m.eatTarget=null;
        if(crop!=null&&m.fed<Food.BITES){
            m.eatTarget=crop;m.eatFrom=p.position();
            p.sendOverlayMessage(Component.literal("正在吃"+food.label+"… 站着别动 3 秒").withColor(0xFFE2A0));
        }else if(crop!=null)p.sendOverlayMessage(Component.literal("已经吃饱了，躲到最后就行").withColor(0x8CF08C));
        else if(other!=null){
            Food here=null;for(var f:Food.values())if(level().getBlockState(other).equals(f.ripe()))here=f;
            p.sendOverlayMessage(Component.literal("这是"+(here==null?"别的作物":here.label)+"，"+m.animal.label+"要吃"+food.label+"（跟着屏幕上的箭头去"+food.field+"）").withColor(0xFF9A8A));
        }
    }
    /** The bite lands after the full eating time, if the crop is still there. */
    private void finishBite(ServerPlayer p,Member m){
        var crop=m.eatTarget;m.eatTarget=null;
        Food food=m.animal.food();
        var state=level().getBlockState(crop);
        if(!state.equals(food.ripe())){p.sendOverlayMessage(Component.literal("这株已经被吃掉了，换一株").withColor(0xFF9A8A));return;}
        level().levelEvent(2001,crop,net.minecraft.world.level.block.Block.getId(state));
        level().setBlock(crop,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
        m.fed++;
        m.proxy.playAmbientSound(); // the giveaway: every bite is audible like the real animal
        p.sendOverlayMessage(Component.literal(m.fed>=Food.BITES?"吃饱了！"+food.label+" "+m.fed+"/"+Food.BITES+" · 活到最后就能赢":"吃到"+food.label+"！ "+m.fed+"/"+Food.BITES).withColor(m.fed>=Food.BITES?0x8CF08C:0xFFE2A0));
    }
    /** Same block effects as EatBlockGoal: eat the short grass at the feet, else turn grass below into dirt. */
    private void graze(ServerPlayer p){
        var pos=p.blockPosition();
        if(level().getBlockState(pos).is(net.minecraft.world.level.block.Blocks.SHORT_GRASS))level().destroyBlock(pos,false);
        else if(level().getBlockState(pos.below()).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)){
            level().levelEvent(2001,pos.below(),net.minecraft.world.level.block.Block.getId(net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState()));
            level().setBlock(pos.below(),net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(),2);
        }
    }
    private int huntElapsedTicks(){return config.huntSeconds*20-timer;}
    /** Stage 2: hunters get the vanilla tempt foods. Herd animals follow them (TemptGoal); disguised players don't. */
    private void giveLures(){
        lured=true;
        for(var e:members.entrySet()){var p=server.getPlayerList().getPlayer(e.getKey());if(p==null)continue;
            if(e.getValue().role.equals("HUNTER")){
                p.getInventory().setItem(1,new ItemStack(Items.WHEAT));     // sheep, cow
                p.getInventory().setItem(2,new ItemStack(Items.CARROT));    // pig, rabbit
                p.getInventory().setItem(3,new ItemStack(Items.WHEAT_SEEDS));// chicken
                tell(p,"诱饵阶段：手持小麦/胡萝卜/种子，真动物会跟着你走，不跟的可能是玩家！");
            }else tell(p,"诱饵阶段开始：猎人拿到了食物，真动物会跟过去，小心别露馅！");
        }
        sync();
    }
    /**
     * Keep the whole arena loaded during a round. Otherwise a disguise spawned right after a teleport
     * lands in a not-yet-loaded chunk, is unloaded to disk, and comes back as a different entity.
     */
    private void arenaChunks(boolean force){
        if(level()==null)return;
        for(int cx=(ArenaBuilder.CENTER_X-64)>>4;cx<=(ArenaBuilder.CENTER_X+64)>>4;cx++)for(int cz=-64>>4;cz<=64>>4;cz++)
            level().getChunkSource().updateChunkForced(new net.minecraft.world.level.ChunkPos(cx,cz),force); // async ticket: no synchronous load stall
    }
    private double borderStart(){return 2.0*(config.arenaRadius-2);}
    private double borderSize(){
        if(!shrinking)return borderStart();
        double t=Math.min(1,(ticks-shrinkStartTick)/(config.shrinkSeconds*20.0));
        return borderStart()+(config.shrinkFinalSize-borderStart())*t;
    }
    /** Final circle: a vanilla world border shown only to players in this round; enforced here. */
    private void startShrink(){
        shrinking=true;shrinkStartTick=ticks;pickShrinkHerd();
        var border=new net.minecraft.world.level.border.WorldBorder();
        border.setCenter(256.5,.5);border.setWarningBlocks(4);border.setWarningTime(0);
        border.lerpSizeBetween(borderStart(),config.shrinkFinalSize,config.shrinkSeconds*20L,level().getGameTime());
        var packet=new net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket(border);
        for(var e:members.entrySet()){if(e.getValue().side.isEmpty())continue;var p=server.getPlayerList().getPlayer(e.getKey());
            if(p!=null){p.connection.send(packet);tell(p,"最后 "+config.shrinkSeconds+" 秒：边界开始缩小！动物待在圈外会掉血。");}}
        sync();
    }
    private void resetBorder(ServerPlayer p){if(level()!=null)p.connection.send(new net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket(level().getWorldBorder()));}
    /** Test/admin hook: jump the hunt clock forward. */
    public void fastForwardHunt(int seconds){if(phase.equals("HUNTING")){int step=Math.min(seconds*20,timer-1);timer-=step;huntStartTick-=step;}}
    /** Test/admin hook: end the current timed phase (countdown, hiding, hunt or results) on the next tick. */
    public void skipPhase(){if(phase.equals("HUNTING"))fastForwardHunt(Integer.MAX_VALUE/40);if(!phase.equals("LOBBY")&&!phase.equals("GENERATING"))timer=1;}
    public String debugPhase(){return phase;}
    public String debugProxies(){
        var out=new StringBuilder();
        for(var m:members.values())if(m.proxy!=null)out.append(m.animal).append("[removed=").append(m.proxy.isRemoved()).append(" reason=").append(m.proxy.getRemovalReason())
            .append(" lvl=").append(m.proxy.level().dimension().identifier()).append(" pos=").append(m.proxy.blockPosition()).append(" inLevel=").append(level().getEntity(m.proxy.getUUID())!=null).append("] ");
        return out.toString();
    }
    public void debugSetHuntLeft(int seconds){if(phase.equals("HUNTING"))timer=seconds*20;}
    public List<Mob> debugShrinkHerd(){var out=new ArrayList<Mob>();for(var id:shrinkHerd.keySet())if(level().getEntity(id) instanceof Mob mob)out.add(mob);return out;}
    public long debugHerdAlive(){return herd.stream().filter(id->level().getEntity(id) instanceof Mob mob&&mob.isAlive()).count();}
    public void debugFastForwardShrink(int seconds){if(shrinking)shrinkStartTick-=seconds*20;}
    public double debugBorderSize(){return borderSize();}
    public String debugRole(UUID id){var m=members.get(id);return m==null?"":m.role;}
    public int debugHunterSetting(){return config.hunterCount;}
    public int debugCapacity(){return config.maxPlayers;}
    private int count(String role){return (int)members.values().stream().filter(m->m.role.equals(role)).count();}
    private void finish(String result){
        winner=result;phase="RESULTS";timer=15*20;
        int end=huntElapsed();
        boolean animalsLeft=departed.stream().anyMatch(r->r.side().equals("ANIMAL"));
        boolean anyCaught=members.values().stream().anyMatch(m->m.side.equals("ANIMAL")&&m.outAt>=0&&!m.killer.equals("饿死"));
        boolean starved=members.values().stream().anyMatch(m->m.killer.equals("饿死"));
        String reason=practice?"练习结束":result.equals("HUNTERS")?(starved&&!anyCaught?"动物都没吃饱，饿死了":starved?"动物都被抓到或饿死了":!anyCaught&&animalsLeft?"动物全部离开了":animalsLeft?"动物都被抓到或离开了":"所有动物都被抓到了"):
            count("HUNTER")==0?"猎人全部离开了":"时间到，吃饱的动物活到了最后";
        var rows=new ArrayList<GameView.ResultRow>(departed);
        for(var e:members.entrySet()){var m=e.getValue();if(m.side.isEmpty())continue;var pl=server.getPlayerList().getPlayer(e.getKey());
            rows.add(row(e.getKey(),pl==null?"?":pl.getName().getString(),m,end));}
        // Unique per round even across server restarts, so a client never mistakes it for a result it already played.
        resultId=new Random().nextInt(Integer.MAX_VALUE);
        results=new GameView.Results(resultId,result,reason,practice,end/20,rows);
        for(var id:members.keySet()){var pl=server.getPlayerList().getPlayer(id);if(pl!=null)tell(pl,(practice?"":result.equals("HUNTERS")?"猎人胜利！":"动物胜利！")+" "+reason+" · 用时 "+String.format("%d:%02d",end/1200,end/20%60));}
        for(var e:members.entrySet()){var p=server.getPlayerList().getPlayer(e.getKey());if(p!=null){unmorph(p,e.getValue());prepare(p);teleport(p,ArenaBuilder.LOBBY);}}
        sync();
    }
    /** Time is up: animals that never finished eating starve; only fed survivors win. */
    private void endHunt(){
        if(!practice){
            int end=huntElapsed();
            for(var e:members.entrySet()){var m=e.getValue();
                if(!m.role.equals("ANIMAL")||m.fed>=Food.BITES)continue;
                m.role="SPECTATOR";m.outAt=end;m.killer="饿死";
                var pl=server.getPlayerList().getPlayer(e.getKey());
                if(pl!=null)for(var id:members.keySet()){var other=server.getPlayerList().getPlayer(id);if(other!=null)tell(other,pl.getName().getString()+" 没吃饱，饿死了");}
            }
        }
        finish(practice||count("ANIMAL")>0?"ANIMALS":"HUNTERS");
    }
    private static GameView.ResultRow row(UUID id,String name,Member m,int end){
        boolean animal=m.side.equals("ANIMAL");
        boolean survived=animal&&m.outAt<0;
        int seconds=animal?(survived?end:m.outAt)/20:Math.max(0,m.lastKillAt)/20;
        return new GameView.ResultRow(id.toString(),name,m.side,animal?m.animal.name():"",survived,m.left,seconds,m.kills,m.killer);
    }
    private boolean isHunter(Entity entity){
        var member=entity==null?null:members.get(entity.getUUID());
        return phase.equals("HUNTING")&&member!=null&&member.role.equals("HUNTER");
    }
    /** Preserve vanilla sword damage, attack cooldown, critical hits and knockback. */
    public InteractionResult attack(ServerPlayer attacker,Entity target){
        if(!contains(attacker.getUUID())&&!protectedEntity(target))return InteractionResult.PASS;
        if(!isHunter(attacker))return InteractionResult.FAIL;
        UUID owner=proxies.get(target.getUUID());
        if(owner!=null){
            var player=server.getPlayerList().getPlayer(owner);
            if(player!=null&&attacker.distanceToSqr(target)<=16&&attacker.hasLineOfSight(target))attacker.attack(player);
            return InteractionResult.FAIL;
        }
        var member=members.get(target.getUUID());
        if(herd.contains(target.getUUID())){
            // Hitting a real animal costs the hunter 3 s of Slowness II, Blindness and Weakness.
            wrongHits.add(attacker.getUUID());
            attacker.sendOverlayMessage(Component.literal("打错了：那是真动物！减速、失明、虚弱 3 秒").withColor(0xFF7060));
            return InteractionResult.PASS;
        }
        return member!=null&&member.role.equals("ANIMAL")?InteractionResult.PASS:InteractionResult.FAIL;
    }
    /** Forward projectile/sweep hits on the visual animal to its owner's real health. */
    public boolean allowDamage(LivingEntity target,DamageSource source,float amount){
        if(!protectedEntity(target))return true;
        if(shrinking&&source.is(net.minecraft.world.damagesource.DamageTypes.OUTSIDE_BORDER)&&target instanceof ServerPlayer pl&&members.get(pl.getUUID())!=null&&members.get(pl.getUUID()).role.equals("ANIMAL"))return true;
        if(!isHunter(source.getEntity()))return false;
        UUID owner=proxies.get(target.getUUID());
        if(owner!=null){
            var player=server.getPlayerList().getPlayer(owner);
            if(player!=null)player.hurtServer(level(),source,amount);
            return false;
        }
        var member=members.get(target.getUUID());
        return (member!=null&&member.role.equals("ANIMAL"))||herd.contains(target.getUUID());
    }
    /** Only a lethal vanilla hit eliminates a player; enter spectator without a respawn screen. */
    public boolean allowDeath(LivingEntity entity,DamageSource source){
        var member=members.get(entity.getUUID());
        if(!(entity instanceof ServerPlayer player)||member==null||!member.role.equals("ANIMAL"))return true;
        member.role="SPECTATOR";member.outAt=huntElapsed();
        String hunter=source.getEntity() instanceof ServerPlayer killer?killer.getName().getString():"";
        member.killer=source.is(net.minecraft.world.damagesource.DamageTypes.OUTSIDE_BORDER)?"边界":hunter;
        if(member.killer.equals("边界"))hunter="缩圈边界";
        if(source.getEntity() instanceof ServerPlayer killer&&members.get(killer.getUUID())!=null){var k=members.get(killer.getUUID());k.kills++;k.lastKillAt=huntElapsed();}
        announce(player.getName().getString(),hunter,false);
        player.setHealth(1);unmorph(player,member);player.setGameMode(GameType.SPECTATOR);
        sync();
        return false;
    }
    private void announce(String name,String hunter,boolean left){
        eliminations.add(new GameView.Elimination(name,hunter,left));
        String text=left?name+" 退出了本局":name+" 被 "+(hunter.isEmpty()?"猎人":hunter)+" 淘汰";
        for(var id:members.keySet()){var player=server.getPlayerList().getPlayer(id);if(player!=null)tell(player,text);}
    }
    private boolean canStartRound(){return phase.equals("LOBBY")&&members.size()>=Rules.requiredPlayers(config.hunterCount);}
    public boolean isAnimalInRound(ServerPlayer p){var m=members.get(p.getUUID());return m!=null&&m.role.equals("ANIMAL")&&m.proxy!=null&&(phase.equals("HIDING")||phase.equals("HUNTING"));}
    public boolean isWallEntity(Entity entity){return wall.action(entity)!=null;}
    public InteractionResult useWall(ServerPlayer player,Entity entity){
        String action=wall.action(entity);
        if(action==null)return contains(player.getUUID())?InteractionResult.FAIL:InteractionResult.PASS;
        if(!contains(player.getUUID())||player.level()!=level()||player.distanceToSqr(entity)>49)return InteractionResult.FAIL;
        action(player,action.equals("start")&&!phase.equals("LOBBY")?"stop":action);
        return InteractionResult.SUCCESS;
    }
    private void sync(){
        var roster=new ArrayList<LobbyControlWall.Member>();
        for(var e:members.entrySet()){var player=server.getPlayerList().getPlayer(e.getKey());if(player!=null)roster.add(new LobbyControlWall.Member(player.getName().getString(),e.getValue().ready,host(player),e.getValue().preference));}
        wall.update(new LobbyControlWall.View(phase,roster,config.maxPlayers,Rules.requiredPlayers(config.hunterCount),config.hunterCount,readyRemaining()<0?-1:Rules.remainingSeconds(readyRemaining())));
        for(var p:server.getPlayerList().getPlayers())if(ServerPlayNetworking.canSend(p,GamePackets.State.TYPE))send(p);}
    public void send(ServerPlayer p){
        if(ServerPlayNetworking.canSend(p,GamePackets.State.TYPE))ServerPlayNetworking.send(p,new GamePackets.State(gson.toJson(view(p))));
    }
    public GameView view(ServerPlayer p){
        GameView v=new GameView();var m=members.get(p.getUUID());v.joined=m!=null;v.host=host(p);v.phase=phase;v.seconds=Rules.remainingSeconds(timer);v.players=members.size();v.capacity=config.maxPlayers;v.alive=count("ANIMAL");v.hunters=count("HUNTER");v.practice=practice;v.winner=winner;v.hideSeconds=config.hideSeconds;v.huntSeconds=config.huntSeconds;
        v.lured=lured&&phase.equals("HUNTING");v.lureIn=phase.equals("HUNTING")&&!practice&&!lured?Rules.remainingSeconds(config.lureSeconds*20-huntElapsedTicks()):-1;
        v.theme=arena==null?"待生成":arena.theme;v.progress=arena==null?0:arena.progress();v.eliminations=new ArrayList<>(eliminations);
        v.canStart=v.host&&canStartRound();int ready=readyRemaining();v.readySeconds=ready<0?-1:Rules.remainingSeconds(ready);v.readyTotal=READY_TICKS/20;v.hunterSetting=config.hunterCount;v.minPlayers=Rules.requiredPlayers(config.hunterCount);
        v.results=results;
        boolean round=Set.of("HIDING","HUNTING").contains(phase);
        if(round&&arena!=null)for(var farm:arena.farms)v.farms.add(new GameView.Farm(farm.food().name(),farm.x(),farm.z()));
        v.need=Food.BITES;v.shrinking=shrinking;v.borderSize=borderSize();
        v.shrinkIn=phase.equals("HUNTING")&&!practice&&!shrinking&&config.shrinkSeconds>0?Rules.remainingSeconds(timer-config.shrinkSeconds*20):-1;
        if(m!=null&&m.side.equals("ANIMAL")){v.food=m.animal.food().name();v.fed=m.fed;v.eatLeft=m.eatTarget!=null?m.eating:0;}
        if(m!=null){v.preference=m.preference;v.role=m.role;v.animal=m.animal.name();v.ready=m.ready;v.hint=m.hint;}
        for(var e:members.entrySet()){var player=server.getPlayerList().getPlayer(e.getKey());if(player!=null)v.members.add(new GameView.Member(player.getUUID().toString(),player.getName().getString(),e.getValue().ready,host(player),e.getValue().role.equals("SPECTATOR"),e.getValue().preference));if(e.getValue().proxy!=null)v.disguises.add(new GameView.Disguise(e.getKey().toString(),e.getValue().animal.name(),e.getValue().proxy.getId(),e.getValue().eating>0));}
        if(!practice&&Set.of("COUNTDOWN","GENERATING","HIDING","HUNTING").contains(phase)){
            // Animal team sees forms, progress and each other's disguises; everyone else only who is still alive.
            boolean team=m!=null&&m.side.equals("ANIMAL");
            for(var e:members.entrySet()){var mate=e.getValue();if(!mate.side.equals("ANIMAL"))continue;
                var player=server.getPlayerList().getPlayer(e.getKey());if(player==null)continue;
                v.roster.add(new GameView.Mate(e.getKey().toString(),player.getName().getString(),team?mate.animal.name():"",team?mate.fed:-1,mate.role.equals("ANIMAL")?"ALIVE":"OUT",team&&mate.proxy!=null?mate.proxy.getId():-1));}
            for(var row:departed)if(row.side().equals("ANIMAL"))v.roster.add(new GameView.Mate(row.uuid(),row.name(),team?row.animal():"",-1,"LEFT",-1));
        }
        return v;
    }
}
