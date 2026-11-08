package dev.morphhide;

import dev.morphhide.game.GameManager;
import dev.morphhide.network.GamePackets;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import org.slf4j.*;

public final class MorphHide implements ModInitializer {
    public static final String NAME="Morph Hide n Seek";
    public static final String ID="morphhide";
    /** Save folder of the dedicated lobby world (keep the old id so upgrades reopen the same save). */
    public static final String LOBBY_SAVE="WildVeil_Lobby";
    public static final Logger LOGGER=LoggerFactory.getLogger(ID);
    public static GameManager game;
    public void onInitialize(){
        GamePackets.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server->game=new GameManager(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server->{if(game!=null)game.stop();game=null;});
        ServerTickEvents.END_SERVER_TICK.register(server->{if(game!=null)game.tick();});
        ServerPlayNetworking.registerGlobalReceiver(GamePackets.Action.TYPE,(packet,context)->{if(game!=null)game.action(context.player(),packet.action());});
        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{if(game!=null)game.connected(handler.player);});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{if(game!=null)game.leave(handler.player,true);});
        AttackEntityCallback.EVENT.register((player,level,hand,target,hit)->{
            // Left-click works on wall tiles and team pickers, like right-click.
            if(game!=null&&!level.isClientSide()&&player instanceof ServerPlayer p&&game.isWallEntity(target))return game.useWall(p,target);
            if(game!=null&&!level.isClientSide()&&(game.contains(player.getUUID())||game.protectedEntity(target))){if(player instanceof ServerPlayer p)return game.attack(p,target);return InteractionResult.FAIL;}return InteractionResult.PASS;
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity,source,amount)->game==null||game.allowDamage(entity,source,amount));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity,source)->{if(game!=null)game.afterDeath(entity);});
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity,source,amount)->game==null||game.allowDeath(entity,source));
        UseBlockCallback.EVENT.register((p,l,h,hit)->game!=null&&!l.isClientSide()&&game.contains(p.getUUID())?InteractionResult.FAIL:InteractionResult.PASS);
        UseEntityCallback.EVENT.register((p,l,h,e,hit)->{
            if(game!=null&&!l.isClientSide()&&p instanceof ServerPlayer player){
                if(h!=net.minecraft.world.InteractionHand.MAIN_HAND)return InteractionResult.PASS;
                return game.useWall(player,e);
            }
            return InteractionResult.PASS;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher,registry,environment)->{
            var root=Commands.literal("morph").executes(c->{if(game!=null)game.action(c.getSource().getPlayerOrException(),"join");return 1;});
            for(String action:new String[]{"join","leave","ready","start","stop","practice"})root.then(Commands.literal(action).executes(c->{if(game!=null)game.action(c.getSource().getPlayerOrException(),action);return 1;}));
            dispatcher.register(root);
        });
    }
}
