package dev.morphhide.test;

import com.mojang.authlib.GameProfile;
import dev.morphhide.MorphHide;
import dev.morphhide.client.*;
import dev.morphhide.game.GameManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;

/** Real integrated-server entities; bots use vanilla GameTestHelper's EmbeddedChannel pattern. */
final class CombatScenario {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    static void onServer(ClientGameTestContext ctx, Consumer<MinecraftServer> action) {
        var result = new CompletableFuture<Void>();
        ctx.runOnClient(mc -> mc.getSingleplayerServer().execute(() -> {
            try { action.accept(mc.getSingleplayerServer()); result.complete(null); }
            catch (Throwable error) { result.completeExceptionally(error); }
        }));
        ctx.waitFor(mc -> result.isDone());
        result.join();
    }

    static void run(ClientGameTestContext ctx) {
        UUID localId = ctx.computeOnClient(mc -> mc.player.getUUID());
        var bots = new ArrayList<ServerPlayer>();
        var channels = new ArrayList<EmbeddedChannel>();
        onServer(ctx, server -> {
            // Embedded channels have no network-loop tick: drive the same player update
            // called by ServerGamePacketListenerImpl, including weapon attributes/cooldown.
            ServerTickEvents.END_SERVER_TICK.register(tickingServer -> {
                for (int i = 0; i < bots.size(); i++) {
                    var bot = bots.get(i);
                    if (bot.level().getServer() != tickingServer) continue;
                    var channel = channels.get(i);
                    // Complete the vanilla dimension-transfer handshake instead of leaving
                    // bots permanently invulnerable in the loading screen.
                    Object packet;
                    while ((packet = channel.readOutbound()) != null) {
                        if (packet instanceof ClientboundPlayerPositionPacket teleport) {
                            bot.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(
                                teleport.id(), bot.getX(), bot.getY(), bot.getZ(), bot.getYRot(), bot.getXRot()));
                        }
                    }
                    bot.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                    bot.doTick();
                }
            });
            for (String name : List.of("TestHunter", "TestAnimal")) {
                var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
                var bot = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
                var connection = new Connection(PacketFlow.SERVERBOUND);
                channels.add(new EmbeddedChannel(connection));
                server.getPlayerList().placeNewPlayer(connection, bot, cookie);
                bots.add(bot);
                // LAN guests never press the title-screen button: they must be pulled into the lobby on connect.
                check(!MorphHide.game.contains(bot.getUUID()), "Join must be deferred to the next tick");
            }
        });
        ctx.waitTicks(3);
        onServer(ctx, server -> {
            var level = server.getLevel(GameManager.DIMENSION);
            for (var bot : bots) {
                check(MorphHide.game.contains(bot.getUUID()), bot.getName().getString() + " was not auto-joined on connect");
                check(bot.level() == level && Math.abs(bot.getX()) < 17 && Math.abs(bot.getZ()) < 17, bot.getName().getString() + " did not spawn in the lobby");
            }
            // Guests pick teams through the same world entities (real UseEntityCallback / AttackEntityCallback path).
            for (var bot : bots) {
                boolean hunter = bot.getName().getString().equals("TestHunter");
                net.minecraft.world.entity.Entity picker = null;
                for (var e : level.getAllEntities())
                    if (MorphHide.game.isWallEntity(e) && (hunter ? e instanceof net.minecraft.world.entity.decoration.Mannequin : e instanceof net.minecraft.world.entity.animal.cow.Cow)) picker = e;
                check(picker != null, "Team picker missing on server");
                bot.teleportTo(level, picker.getX(), 101, picker.getZ() - 2, Set.of(), 0, 0, true);
                var result = hunter
                    ? AttackEntityCallback.EVENT.invoker().interact(bot, level, InteractionHand.MAIN_HAND, picker, null)
                    : net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.invoker().interact(bot, level, InteractionHand.MAIN_HAND, picker, null);
                check(result.consumesAction(), "Guest click on team picker was rejected: " + result);
                MorphHide.game.action(bot, "ready");
                // Keep stationary test clients out of the camera while capturing lobby UI.
                bot.teleportTo(level, hunter ? -6.5 : 6.5, 101, 3.5, Set.of(), 0, 0, true);
            }
        });
        ctx.waitFor(mc -> MorphHideClient.state.players == 3);
        ctx.waitTicks(2);
        WallInput.pickTeam(ctx, "ANIMAL", false);
        ctx.waitFor(mc -> MorphHideClient.state.preference.equals("ANIMAL"));
        ctx.setScreen(() -> null);
        onServer(ctx, server -> server.getPlayerList().getPlayer(localId)
            .teleportTo(server.getLevel(GameManager.DIMENSION), .5, 101, 6, Set.of(), 0, -6, true));
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> check(LobbyGuidance.status(MorphHideClient.state).contains("2 / 3"), "Missing shared ready count"));
        ctx.takeScreenshot("13-multiplayer-lobby");
        ctx.setScreen(() -> null);
        ctx.waitTicks(2);
        ctx.takeScreenshot("14-multiplayer-hud");
        ctx.setScreen(LobbyScreen::new);
        ctx.waitTicks(2);
        ctx.takeScreenshot("13b-lobby-roster-faces");
        ctx.setScreen(() -> null);
        // Two of three players are ready: the shared timer runs at a third of its full length.
        ctx.runOnClient(mc -> check(MorphHideClient.state.readySeconds > 0 && MorphHideClient.state.readySeconds <= 20,
            "Ready timer should be running: " + MorphHideClient.state.readySeconds));
        onServer(ctx, server -> {
            int starts = 0;
            for (var e : server.getLevel(GameManager.DIMENSION).getAllEntities())
                if (e instanceof net.minecraft.world.entity.Interaction && e.getCustomName() != null
                    && e.getCustomName().getString().equals(dev.morphhide.game.LobbyControlWall.PREFIX + "start")) starts++;
            check(starts == 1, "Stale wall controls survived a world reload: " + starts + " start tiles");
            for (var e : server.getLevel(GameManager.DIMENSION).getAllEntities())
                if (e instanceof net.minecraft.world.entity.Display.TextDisplay text && text.getText().getString().equals("结束回合"))
                    throw new AssertionError("Lobby wall shows an in-round start tile");
        });
        ctx.takeScreenshot("13c-ready-timer-hud");
        LobbyGameTest.shiftReady(ctx);
        // The animal picker opens when the round begins; pick the cow before the hunt.
        ctx.waitFor(mc -> mc.gui.screen() instanceof AnimalSelectScreen, 400);
        ctx.clickScreenButton("奶牛，大型");
        ctx.waitFor(mc -> MorphHideClient.state.animal.equals("COW"));
        ctx.clickScreenButton("确认动物选择");
        ctx.waitFor(mc -> MorphHideClient.state.phase.equals("HIDING"), 3000);
        ctx.runOnClient(mc -> {
            check(MorphHideClient.state.hunters == 1, "Three players must get exactly one hunter");
            check(MorphHideClient.state.alive == 2, "Expected two animals");
        });
        // A sword hit during hiding must leave health unchanged.
        onServer(ctx, server -> {
            var animal = server.getPlayerList().getPlayer(localId);
            float health = animal.getHealth();
            animal.hurtServer(animal.level(), animal.damageSources().playerAttack(bots.getFirst()), 100);
            check(animal.getHealth() == health, "Hiding phase must protect animals");
        });
        ctx.waitFor(mc -> MorphHideClient.state.phase.equals("HUNTING"), 1000);
        // Other clients must never draw a disguised player's human model or name tag, whatever its invisibility flag says.
        onServer(ctx, server -> bots.get(1).setInvisible(false));
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            var remote = mc.level.getPlayerByUUID(bots.get(1).getUUID());
            check(remote != null && dev.morphhide.game.MorphRegistry.CLIENT.containsKey(remote.getUUID()), "Disguised guest missing on client");
            check(!mc.getEntityRenderDispatcher().shouldRender(remote, null, 0, 0, 0, 0), "Disguised player's human model is rendered");
            var hunter = mc.level.getPlayerByUUID(bots.getFirst().getUUID());
            check(hunter != null && !dev.morphhide.game.MorphRegistry.CLIENT.containsKey(hunter.getUUID()), "Hunter must stay visible as a player");
        });
        onServer(ctx, server -> {
            // Combat assertions need a clear lane, independent of random scenery/herd pushing.
            var animal = server.getPlayerList().getPlayer(localId);
            animal.teleportTo(server.getLevel(GameManager.DIMENSION),256.5,101,.5,Set.of(),0,0,true);
            animal.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        });
        ctx.waitTicks(22);
        strike(ctx, localId, bots.getFirst());
        ctx.runOnClient(mc -> {
            check(MorphHideClient.state.eliminations.isEmpty(), "Nonlethal hit eliminated an animal");
        });
        onServer(ctx, server -> {
            var animal = server.getPlayerList().getPlayer(localId);
            check(animal.getHealth() > 0 && animal.getHealth() < animal.getMaxHealth(), "Sword must cause nonlethal vanilla damage");
        });
        float hurt = ctx.computeOnClient(mc -> mc.player.getHealth());
        ctx.waitTicks(80);
        ctx.runOnClient(mc -> check(mc.player.getHealth() <= hurt, "Animals must not regenerate during a round: " + hurt + " -> " + mc.player.getHealth()));
        ctx.takeScreenshot("09-nonlethal-health");
        // Stage 2: the lure. Hunters get vanilla tempt foods partway through the hunt.
        ctx.runOnClient(mc -> check(!MorphHideClient.state.lured && MorphHideClient.state.lureIn > 0, "Lure should be pending early in the hunt"));
        onServer(ctx, server -> MorphHide.game.fastForwardHunt(120));
        ctx.waitFor(mc -> MorphHideClient.state.lured, 100);
        onServer(ctx, server -> {
            var inv = bots.getFirst().getInventory();
            check(inv.getItem(1).is(net.minecraft.world.item.Items.WHEAT) && inv.getItem(2).is(net.minecraft.world.item.Items.CARROT)
                && inv.getItem(3).is(net.minecraft.world.item.Items.WHEAT_SEEDS), "Hunter did not receive lure foods");
            check(server.getPlayerList().getPlayer(localId).getInventory().isEmpty(), "Animals must not receive lures");
        });
        ctx.waitTicks(2);
        ctx.takeScreenshot("09b-lure-stage-hud");
        for (int i = 0; i < 10 && !ctx.computeOnClient(mc -> MorphHideClient.state.role.equals("SPECTATOR")); i++) {
            ctx.waitTicks(22);
            strike(ctx, localId, bots.getFirst());
        }
        ctx.waitFor(mc -> MorphHideClient.state.role.equals("SPECTATOR"));
        ctx.runOnClient(mc -> {
            check(mc.player.isSpectator(), "Eliminated player must spectate without respawning");
            check(MorphHideClient.state.alive == 1, "Only killed animal should be removed");
            check(MorphHideClient.state.eliminations.size() == 1, "Must emit exactly one elimination");
            check(MorphHideClient.state.eliminations.getFirst().hunter().equals("TestHunter"), "HUD lost killer name");
        });
        ctx.takeScreenshot("10-elimination-hud");
        onServer(ctx, server -> {
            var animal = bots.get(1);
            boolean hit = animal.hurtServer(animal.level(), animal.damageSources().playerAttack(bots.getFirst()), 100);
            check(hit && animal.isSpectator(), "Last animal must accept lethal damage after loading");
        });
        ctx.waitFor(mc -> MorphHideClient.state.phase.equals("RESULTS"));
        ctx.runOnClient(mc -> {
            check(MorphHideClient.state.winner.equals("HUNTERS"), "Last animal killed must award hunters victory");
            var r = MorphHideClient.state.results;
            check(r != null && r.winner().equals("HUNTERS") && r.rows().size() == 3, "Results summary missing or incomplete");
            var me = r.rows().stream().filter(row -> row.uuid().equals(mc.player.getUUID().toString())).findFirst().orElseThrow();
            check(me.side().equals("ANIMAL") && me.animal().equals("COW") && !me.survived() && me.killer().equals("TestHunter"), "Wrong result row for the local animal: " + me);
            var hunter = r.rows().stream().filter(row -> row.side().equals("HUNTER")).findFirst().orElseThrow();
            check(hunter.name().equals("TestHunter") && hunter.kills() == 2, "Hunter should be credited with both catches: " + hunter);
            check(ResultsOverlay.active(MorphHideClient.state) && Boolean.FALSE.equals(ResultsOverlay.localWon()), "A caught animal must see the losing results screen");
        });
        ctx.waitTicks(20); // Allow return teleport and HUD packets to settle before visual review.
        ctx.takeScreenshot("11-hunters-win");
        onServer(ctx, server -> {
            MorphHide.game.action(server.getPlayerList().getPlayer(localId), "stop");
            for (var bot : bots) {
                MorphHide.game.leave(bot, false);
                server.getPlayerList().remove(bot);
            }
            bots.clear();
            for (var channel : channels) channel.finishAndReleaseAll();
        });
        ctx.waitFor(mc -> MorphHideClient.state.phase.equals("LOBBY") && MorphHideClient.state.players == 1);
        ctx.waitTicks(25);
        onServer(ctx, server -> {
            // After a whole round in the arena the lobby wall and both team pickers must still exist.
            var level = server.getLevel(GameManager.DIMENSION);
            int starts = 0, mannequins = 0, cows = 0;
            for (var e : level.getAllEntities()) {
                if (!MorphHide.game.isWallEntity(e)) continue;
                if (e instanceof net.minecraft.world.entity.Interaction && e.getCustomName().getString().endsWith(":start")) starts++;
                if (e instanceof net.minecraft.world.entity.decoration.Mannequin) mannequins++;
                if (e instanceof net.minecraft.world.entity.animal.cow.Cow) cows++;
            }
            check(starts == 1 && mannequins == 1 && cows == 1, "Lobby wall lost entities after a round: start=" + starts + " mannequin=" + mannequins + " cow=" + cows);
            check(level.getForceLoadedChunks().contains(net.minecraft.world.level.ChunkPos.pack(-1, 0)), "Lobby chunks are not kept loaded");
        });
        ctx.waitTicks(5);
        System.out.println("MORPHHIDE_COMBAT_PASS: hiding protection, real sword damage, lethal elimination, spectator, HUD, hunters win");
    }

    private static void strike(ClientGameTestContext ctx, UUID localId, ServerPlayer hunter) {
        onServer(ctx, server -> {
            var animal = server.getPlayerList().getPlayer(localId);
            if (animal.isSpectator()) return;
            var level = server.getLevel(GameManager.DIMENSION);
            hunter.teleportTo(level, animal.getX() + 1.5, animal.getY(), animal.getZ(), Set.of(), 90, 0, true);
            var proxy = level.getEntitiesOfClass(Mob.class, animal.getBoundingBox().inflate(.2),
                mob -> mob.isNoAi() && MorphHide.game.protectedEntity(mob)).stream().findFirst().orElseThrow();
            check(hunter.hasLineOfSight(proxy), "Combat fixture requires an unobstructed target");
            float before = animal.getHealth();
            float strength = hunter.getAttackStrengthScale(0);
            AttackEntityCallback.EVENT.invoker().interact(hunter, level, InteractionHand.MAIN_HAND, proxy, null);
            System.out.println("MORPHHIDE_SWORD_HIT: health " + before + " -> " + animal.getHealth() + ", strength=" + strength + ", spectator=" + animal.isSpectator());
        });
    }
}
