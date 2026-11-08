package dev.morphhide.test;

import dev.morphhide.MorphHide;
import dev.morphhide.client.*;
import dev.morphhide.game.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.CameraType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.Level;

public final class LobbyGameTest implements FabricClientGameTest {
    private static java.util.List<String> buttons(net.minecraft.client.Minecraft mc){
        return mc.gui.screen().children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button)
            .map(c->((net.minecraft.client.gui.components.Button)c).getMessage().getString()).toList();
    }
    static void shiftReady(ClientGameTestContext ctx){
        ctx.getInput().holdShift();
        ctx.getInput().pressKey(MorphHideClient.readyKey);
        ctx.getInput().releaseShift();
        ctx.waitFor(mc->!mc.player.isCrouching(),40); // Stand back up before aiming at anything.
        ctx.waitTicks(2);
    }
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    @Override public void runTest(ClientGameTestContext ctx){
        // Old saved settings move to the new 5 s hiding time; the UI steps below then use a longer one.
        try {
            var path=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("morphhide.json");
            java.nio.file.Files.writeString(path,"{\"hideSeconds\":30,\"configVersion\":3}");
            check(GameConfig.load().hideSeconds==5,"Saved 30 s hiding time was not migrated to 5 s");
            java.nio.file.Files.writeString(path,"{\"hideSeconds\":30,\"configVersion\":4}");
            check(GameConfig.load().hideSeconds==30,"A deliberately chosen hiding time must be kept");
        } catch(java.io.IOException e){throw new AssertionError(e);}
        // Keep test runs silent.
        ctx.runOnClient(mc->{mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);mc.options.save();});
        ctx.waitForScreen(TitleScreen.class);
        ctx.waitTicks(45); // Let the vanilla title fade-in finish before capturing it.
        ctx.takeScreenshot("01-title-entry");
        ctx.clickScreenButton("Morph Hide n Seek");
        ctx.waitFor(mc->mc.gui.screen() instanceof LobbyScreen&&MorphHideClient.state.joined,2400);
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> {
            var buttons=buttons(mc);
            check(buttons.contains("准备"), "Lobby panel needs a ready button");
            check(!buttons.contains("选择动物"), "Animals must not be chosen in the lobby");
            check(!buttons.contains("退出小游戏"), "Lobby panel must not offer a quit button");
        });
        ctx.takeScreenshot("02-lobby-roster");
        ctx.setScreen(() -> null);
        ctx.waitTicks(2);
        ctx.takeScreenshot("12-solo-lobby-hud");
        CombatScenario.onServer(ctx, server -> server.getPlayerList().getPlayers().getFirst()
            .teleportTo(server.getLevel(GameManager.DIMENSION),.5,101,4.5,java.util.Set.of(),0,-6,true));
        ctx.waitTicks(10);
        ctx.takeScreenshot("15-control-wall");
        // Left click operates the wall just like right click.
        WallInput.press(ctx,"hunters:2",true);
        ctx.waitFor(mc->MorphHideClient.state.hunterSetting==2&&MorphHideClient.state.minPlayers==3);
        ctx.waitTicks(25);
        ctx.takeScreenshot("16-wall-tooltip");
        WallInput.press(ctx,"hunters:0");
        ctx.waitFor(mc->MorphHideClient.state.hunterSetting==0);
        int capacity=ctx.computeOnClient(mc->MorphHideClient.state.capacity);
        WallInput.press(ctx,"capacity_down",true);
        ctx.waitFor(mc->MorphHideClient.state.capacity==capacity-1);
        WallInput.press(ctx,"capacity_up");
        ctx.waitFor(mc->MorphHideClient.state.capacity==capacity);
        WallInput.pickTeam(ctx,"HUNTER",true);
        ctx.waitFor(mc->MorphHideClient.state.preference.equals("HUNTER"));
        WallInput.pickTeam(ctx,"ANIMAL",false);
        ctx.waitFor(mc->MorphHideClient.state.preference.equals("ANIMAL"));
        ctx.runOnClient(mc->{
            check(MorphHideClient.readyKey.getDefaultKey().getValue()==com.mojang.blaze3d.platform.InputConstants.KEY_Y,"Ready key must default to Y");
            int labelled=0;
            for(var e:mc.level.entitiesForRendering())if(e.getCustomName()!=null&&(e.getCustomName().getString().startsWith("动物队")||e.getCustomName().getString().startsWith("猎人队"))){
                check(e.getCustomName().getString().endsWith("点击加入"),"Team picker label not unified: "+e.getCustomName().getString());labelled++;}
            check(labelled==2,"Expected two team pickers, got "+labelled);
        });
        ctx.runOnClient(mc->check(!mc.player.isSpectator()&&mc.level.getEntitiesOfClass(net.minecraft.world.entity.animal.cow.Cow.class,mc.player.getBoundingBox().inflate(16)).size()==1,"Team cow missing or duplicated"));
        // Plain Y must not ready; Shift+Y toggles. Solo ready must not start a match.
        ctx.getInput().pressKey(MorphHideClient.readyKey);
        ctx.waitTicks(5);
        ctx.runOnClient(mc->check(!MorphHideClient.state.ready,"Y without Shift must not ready"));
        shiftReady(ctx);
        ctx.waitFor(mc->MorphHideClient.state.ready);
        ctx.waitTicks(5);
        ctx.runOnClient(mc->check(MorphHideClient.state.phase.equals("LOBBY")&&MorphHideClient.state.readySeconds<0,"Solo ready must not start a match"));
        shiftReady(ctx);
        ctx.waitFor(mc->!MorphHideClient.state.ready);
        WallInput.press(ctx,"practice");
        // Animals choose their form once the round begins: the picker opens by itself.
        ctx.waitFor(mc->MorphHideClient.state.phase.equals("COUNTDOWN"),200);
        ctx.waitFor(mc->mc.gui.screen() instanceof AnimalSelectScreen,100);
        ctx.waitFor(mc->MorphHideClient.state.phase.equals("HIDING"),3000);
        ctx.waitTicks(10);
        ctx.runOnClient(mc->check(mc.gui.screen() instanceof AnimalSelectScreen&&!buttons(mc).contains("开始单人练习"),"Picker should stay open while hiding"));
        for(var kind:AnimalKind.values()){
            ctx.clickScreenButton(kind.label+"，"+kind.trait);
            ctx.waitFor(mc->MorphHideClient.state.animal.equals(kind.name())&&MorphHideClient.state.disguises.stream().anyMatch(d->d.animal().equals(kind.name())));
            ctx.waitTicks(3);
        }
        ctx.clickScreenButton("类型: 全部动物 ▾");
        ctx.takeScreenshot("03-dropdown");
        ctx.clickScreenButton("小型");
        ctx.takeScreenshot("04-small-animals");
        ctx.runOnClient(mc->{
            var box=(EditBox)mc.gui.screen().children().stream().filter(c->c instanceof EditBox).findFirst().orElseThrow();
            box.setValue("rabbit");
        });
        ctx.waitTick();
        ctx.clickScreenButton("兔子，小型");
        ctx.waitFor(mc->MorphHideClient.state.animal.equals("RABBIT"));
        ctx.takeScreenshot("05-search-rabbit");
        ctx.runOnClient(mc->{
            ((EditBox)mc.gui.screen().children().stream().filter(c->c instanceof EditBox).findFirst().orElseThrow()).setValue("");
        });
        ctx.clickScreenButton("类型: 小型 ▾");ctx.clickScreenButton("全部动物");
        ctx.clickScreenButton("小鸡，小型");
        ctx.waitFor(mc->MorphHideClient.state.animal.equals("CHICKEN")&&MorphHideClient.state.disguises.stream().anyMatch(d->d.animal().equals("CHICKEN")));
        ctx.waitTicks(3);
        ctx.takeScreenshot("05b-animal-picker-in-match");
        ctx.clickScreenButton("确认动物选择");
        ctx.waitFor(mc->mc.gui.screen()==null);
        ctx.waitTicks(30);
        ctx.runOnClient(mc->{check(mc.level.dimension().equals(GameManager.DIMENSION),"Not in reserve");check(mc.player.getX()>190,"Not teleported to arena");});
        // A standing chicken disguise must rest its wings like a vanilla chicken on the ground.
        ctx.runOnClient(mc->{
            var disguise=MorphHideClient.state.disguises.stream().filter(d->d.uuid().equals(mc.player.getUUID().toString())).findFirst().orElseThrow();
            var chicken=(net.minecraft.world.entity.animal.chicken.Chicken)mc.level.getEntity(disguise.entityId());
            check(mc.player.onGround(),"Practice player should be standing");
            check(chicken.onGround(),"Chicken disguise is not grounded");
            check(chicken.flapSpeed<.01f,"Standing chicken disguise keeps flapping: "+chicken.flapSpeed);
        });
        // The disguise follows its owner directly: no lag behind, no sliding after stopping.
        ctx.getInput().holdKeyFor(options->options.keyUp,15);
        ctx.waitTicks(1);
        ctx.runOnClient(mc->{
            var proxy=MorphHideClient.state.disguises.stream().filter(d->d.uuid().equals(mc.player.getUUID().toString())).map(d->mc.level.getEntity(d.entityId())).findFirst().orElseThrow();
            check(proxy.position().distanceTo(mc.player.position())<.05,"Disguise lags behind its player: "+proxy.position().distanceTo(mc.player.position()));
        });
        ctx.waitTicks(15);
        double[] stopped=ctx.computeOnClient(mc->{var proxy=mc.level.getEntity(MorphHideClient.state.disguises.getFirst().entityId());return new double[]{proxy.getX(),proxy.getZ(),mc.player.getX(),mc.player.getZ()};});
        ctx.waitTicks(10);
        // Herd animals may push the player; the disguise must move exactly with the player, never on its own.
        ctx.runOnClient(mc->{var proxy=mc.level.getEntity(MorphHideClient.state.disguises.getFirst().entityId());
            double proxyMoved=Math.hypot(proxy.getX()-stopped[0],proxy.getZ()-stopped[1]),playerMoved=Math.hypot(mc.player.getX()-stopped[2],mc.player.getZ()-stopped[3]);
            check(Math.abs(proxyMoved-playerMoved)<.01&&proxy.position().distanceTo(mc.player.position())<.05,"Disguise drifts from its player after stopping: proxy "+proxyMoved+" vs player "+playerMoved);});
        // Standing still and looking aside: the head turns at once, the body follows later (vanilla BodyRotationControl).
        // Clear nearby herd animals so nothing pushes the player during the rotation check.
        CombatScenario.onServer(ctx, server -> {
            var p=server.getPlayerList().getPlayers().getFirst();
            for(var mob:p.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,p.getBoundingBox().inflate(10),mob->!mob.isNoAi()))mob.discard();
            p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        });
        ctx.waitTicks(40);
        float startYaw=ctx.computeOnClient(mc->mc.player.getYRot());
        float startBody=ctx.computeOnClient(mc->((net.minecraft.world.entity.LivingEntity)mc.level.getEntity(MorphHideClient.state.disguises.getFirst().entityId())).yBodyRot);
        ctx.runOnClient(mc->{mc.player.setYRot(startYaw+60);mc.player.setYHeadRot(startYaw+60);});
        ctx.waitTicks(3);
        ctx.runOnClient(mc->{var proxy=(net.minecraft.world.entity.LivingEntity)mc.level.getEntity(MorphHideClient.state.disguises.getFirst().entityId());
            check(Math.abs(net.minecraft.util.Mth.wrapDegrees(proxy.getYHeadRot()-(startYaw+60)))<5,"Disguise head did not follow the view");
            check(Math.abs(net.minecraft.util.Mth.wrapDegrees(proxy.yBodyRot-startBody))<5,"Disguise body snapped instantly with the head: "+startBody+" -> "+proxy.yBodyRot);});
        ctx.waitTicks(60);
        ctx.runOnClient(mc->{var proxy=(net.minecraft.world.entity.LivingEntity)mc.level.getEntity(MorphHideClient.state.disguises.getFirst().entityId());
            check(Math.abs(net.minecraft.util.Mth.wrapDegrees(proxy.yBodyRot-(startYaw+60)))<10,"Disguise body never caught up with the head");});
        // Every animal can graze: a chicken lowers its head.
        ctx.getInput().pressKey(options->options.keyUse);
        ctx.waitFor(mc->MorphHideClient.state.disguises.getFirst().eating(),20);
        ctx.waitTicks(2);
        ctx.runOnClient(mc->check(mc.level.getEntity(MorphHideClient.state.disguises.getFirst().entityId()).getXRot()>30,"Grazing chicken did not lower its head"));
        ctx.waitFor(mc->!MorphHideClient.state.disguises.getFirst().eating(),80);
        // Full disguise: no human arm in first person, and the inventory doll is the animal.
        ctx.runOnClient(mc->check(DisguisePreview.localDisguised()&&DisguisePreview.forLocalPlayer()!=null,"Disguise preview missing"));
        ctx.runOnClient(mc->{var kind=dev.morphhide.game.AnimalKind.parse(MorphHideClient.state.animal);check(DisguisePreview.forLocalPlayer().getType()==kind.entityType,"Inventory doll should be the "+kind);});
        ctx.setScreen(()->new net.minecraft.client.gui.screens.inventory.InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
        ctx.waitTicks(3);
        ctx.takeScreenshot("06c-inventory-as-animal");
        ctx.setScreen(()->null);
        ctx.waitTicks(2);
        ctx.takeScreenshot("06d-first-person-no-arm");
        // Right-click as a sheep plays the vanilla grazing animation on the disguise.
        ctx.runOnClient(mc->MorphHideClient.send("animal:SHEEP"));
        ctx.waitFor(mc->MorphHideClient.state.disguises.stream().anyMatch(d->d.animal().equals("SHEEP")));
        ctx.waitTicks(5);
        ctx.getInput().pressKey(options->options.keyUse);
        ctx.waitTicks(10);
        ctx.runOnClient(mc->{
            var disguise=MorphHideClient.state.disguises.stream().filter(d->d.uuid().equals(mc.player.getUUID().toString())).findFirst().orElseThrow();
            var sheep=(net.minecraft.world.entity.animal.sheep.Sheep)mc.level.getEntity(disguise.entityId());
            check(sheep.getHeadEatPositionScale(0)>0,"Right-click did not make the sheep disguise graze");
        });
        ctx.takeScreenshot("06b-sheep-grazing");
        CombatScenario.onServer(ctx, server -> {
            long disguises=0;
            for(var e:server.getLevel(GameManager.DIMENSION).getAllEntities())if(e instanceof net.minecraft.world.entity.Mob mob&&mob.isNoAi()&&MorphHide.game.protectedEntity(mob)&&!MorphHide.game.isWallEntity(mob))disguises++;
            check(disguises==1,"Re-picking animals left extra disguises: "+disguises);
        });
        ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        ctx.waitTicks(5);
        ctx.takeScreenshot("06-practice-map");
        ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
        ctx.waitFor(mc->MorphHideClient.state.phase.equals("HUNTING"),1000);
        ctx.takeScreenshot("07-practice-hud");
        // Practice ends: everyone "wins", so the results show the title pop, confetti and summary.
        CombatScenario.onServer(ctx, server -> MorphHide.game.fastForwardHunt(120));
        ctx.waitFor(mc->MorphHideClient.state.phase.equals("RESULTS")&&MorphHideClient.state.results!=null,200);
        ctx.waitTicks(8);
        ctx.takeScreenshot("07b-results-confetti");
        ctx.waitTicks(30);
        ctx.takeScreenshot("07c-results-summary");
        ctx.runOnClient(mc->{var r=MorphHideClient.state.results;
            check(r.practice()&&r.rows().size()==1&&r.rows().getFirst().survived(),"Practice results should list the surviving player");});
        ctx.setScreen(LobbyScreen::new);
        ctx.clickScreenButton("结束回合并回大厅");
        ctx.waitFor(mc->MorphHideClient.state.phase.equals("LOBBY"));
        ctx.waitTicks(5);
        ctx.runOnClient(mc->MorphHideClient.send("leave"));
        ctx.waitFor(mc->!MorphHideClient.state.joined&&mc.level.dimension().equals(Level.OVERWORLD));
        ctx.runOnClient(mc->check(!mc.player.isInvisible(),"Still invisible after leaving"));
        CombatScenario.onServer(ctx, server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            player.getInventory().setItem(4, new ItemStack(Items.DIAMOND, 7));
        });
        var oldServer = ctx.computeOnClient(mc -> mc.getSingleplayerServer());
        ctx.runOnClient(mc -> mc.disconnectWithSavingScreen());
        ctx.waitFor(mc -> mc.level == null && oldServer.isShutdown(), 1200);
        ctx.waitTicks(2);
        ctx.setScreen(TitleScreen::new);
        ctx.waitForScreen(TitleScreen.class);
        ctx.clickScreenButton("Morph Hide n Seek");
        ctx.waitFor(mc->mc.gui.screen() instanceof LobbyScreen&&MorphHideClient.state.joined,2400);
        ctx.waitTicks(10);
        ctx.takeScreenshot("08-reopened-lobby");
        CombatScenario.run(ctx);
        EdgeScenario.run(ctx);
        ctx.runOnClient(mc->MorphHideClient.send("leave"));
        ctx.waitFor(mc->!MorphHideClient.state.joined);
        CombatScenario.onServer(ctx, server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            check(player.gameMode.getGameModeForPlayer() == GameType.CREATIVE, "Original game mode was not restored");
            check(player.getInventory().getItem(4).is(Items.DIAMOND) && player.getInventory().getItem(4).getCount() == 7, "Original inventory was not restored");
            check(player.getMaxHealth() == 20 && !player.isInvisible(), "Animal attributes were not restored");
        });
        MovementScenario.run(ctx);
        var finalServer = ctx.computeOnClient(mc -> mc.getSingleplayerServer());
        ctx.runOnClient(mc -> mc.disconnectWithSavingScreen());
        ctx.waitFor(mc -> mc.level == null && finalServer.isShutdown(), 1200);
        ctx.waitTicks(2);
        ctx.setScreen(TitleScreen::new);
        System.out.println("MORPHHIDE_E2E_PASS: title, six models, filter, search, practice, leave, reopen, inventory/mode restore, shutdown");
    }
}
