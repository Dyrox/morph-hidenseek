package dev.morphhide.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import dev.morphhide.network.GamePackets;

/** A dedicated local save: one title-screen click opens the world and joins the reserve. */
public final class QuickLobby {
    // Keep the existing directory identifier so upgrades reopen the same save.
    public static final String SAVE=dev.morphhide.MorphHide.LOBBY_SAVE;
    private static boolean pending, requested;
    private static int waiting;
    public static void open(){
        var mc=Minecraft.getInstance();if(pending)return;
        pending=true;requested=false;waiting=0;
        var parent=new TitleScreen();
        var flows=mc.createWorldOpenFlows();
        if(mc.getLevelSource().levelExists(SAVE)){
            flows.openWorld(SAVE,()->{cancel();mc.gui.setScreen(parent);});
        }else{
            var settings=new LevelSettings(dev.morphhide.MorphHide.NAME+" · 大厅",GameType.ADVENTURE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL,false,false),false,WorldDataConfiguration.DEFAULT);
            flows.createFreshLevel(SAVE,settings,new WorldOptions(73649218L,false,false),WorldPresets::createNormalWorldDimensions,parent);
        }
    }
    public static void cancel(){pending=false;requested=false;waiting=0;}
    public static void tick(Minecraft mc){
        if(!pending)return;
        // Loading may take a while. Returning to the title screen means cancelled/failed loading.
        if(mc.player==null){if(mc.gui.screen() instanceof TitleScreen)cancel();return;}
        if(!requested&&ClientPlayNetworking.canSend(GamePackets.Action.TYPE)){
            requested=true;MorphHideClient.send("join");
        }
        if(MorphHideClient.state.joined&&mc.level!=null&&mc.level.dimension().equals(dev.morphhide.game.GameManager.DIMENSION)){
            cancel();mc.gui.setScreen(new LobbyScreen());
        }else if(++waiting>400){cancel();mc.gui.setScreen(new LobbyScreen());}
    }
}
