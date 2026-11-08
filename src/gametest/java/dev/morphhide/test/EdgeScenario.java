package dev.morphhide.test;

import dev.morphhide.MorphHide;
import dev.morphhide.client.MorphHideClient;
import dev.morphhide.client.ResultsOverlay;
import dev.morphhide.game.GameManager;
import dev.morphhide.game.MorphRegistry;
import java.util.*;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Multiplayer edge cases with fake LAN guests: permissions, capacity, ready reset, two hunters,
 * mid-round joins, attack rules, grazing, lures, hunters/animals leaving, reconnects, practice,
 * host stop and host hand-over.
 */
final class EdgeScenario {
    private EdgeScenario() {}
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void server(ClientGameTestContext ctx, Consumer<MinecraftServer> action) { CombatScenario.onServer(ctx, action); }
    private static ServerPlayer player(MinecraftServer s, UUID id) { return s.getPlayerList().getPlayer(id); }
    private static void act(ClientGameTestContext ctx, UUID id, String... actions) {
        server(ctx, s -> { for (var a : actions) MorphHide.game.action(player(s, id), a); });
    }
    private static void waitPhase(ClientGameTestContext ctx, String phase, int timeout) {
        ctx.waitFor(mc -> MorphHide.game.debugPhase().equals(phase) && MorphHideClient.state.phase.equals(phase), timeout);
    }
    /** COUNTDOWN -> GENERATING -> HIDING, skipping the timed parts. */
    private static void toHiding(ClientGameTestContext ctx) {
        waitPhase(ctx, "COUNTDOWN", 200);
        server(ctx, s -> MorphHide.game.skipPhase());
        waitPhase(ctx, "HIDING", 3000);
        ctx.setScreen(() -> null);
    }
    private static long disguises(MinecraftServer s) {
        long n = 0;
        for (var entity : s.getLevel(GameManager.DIMENSION).getAllEntities())
            if (entity instanceof Mob mob && mob.isNoAi() && MorphHide.game.protectedEntity(mob) && !MorphHide.game.isWallEntity(mob)) n++;
        return n;
    }

    static void run(ClientGameTestContext ctx) {
        UUID local = ctx.computeOnClient(mc -> mc.player.getUUID());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID(), e = UUID.randomUUID();

        // --- The lobby save is locked to a clear morning (time and weather do not advance).
        server(ctx, s -> {
            check(!s.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME) && !s.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER), "Time/weather still advance in the lobby save");
            check(!s.overworld().isRaining() && !s.overworld().isThundering(), "Lobby save should be clear");
            long t = s.clockManager().getInstance(s.registryAccess().get(net.minecraft.world.clock.WorldClocks.OVERWORLD).orElseThrow()).totalTicks();
            check(t % 24000 == 1000, "Lobby save should be frozen at morning: " + t);
        });

        // --- Permissions: guests cannot change settings or start anything.
        server(ctx, s -> { TestBots.connect(s, "GuestA", a); TestBots.connect(s, "GuestB", b); });
        ctx.waitTicks(3);
        server(ctx, s -> {
            check(MorphHide.game.contains(a) && MorphHide.game.contains(b), "Guests were not auto-joined");
            int cap = MorphHide.game.debugCapacity(), hunters = MorphHide.game.debugHunterSetting();
            for (var action : List.of("hunters:2", "capacity_down", "start", "practice", "stop")) MorphHide.game.action(player(s, a), action);
            check(MorphHide.game.debugCapacity() == cap && MorphHide.game.debugHunterSetting() == hunters && MorphHide.game.debugPhase().equals("LOBBY"),
                "A guest changed lobby settings or started a round");
            // Capacity: shrink to the current player count; one more guest must be turned away.
            for (int i = 0; i < 30; i++) MorphHide.game.action(player(s, local), "capacity_down");
            check(MorphHide.game.debugCapacity() == 3, "Capacity must not drop below the players present: " + MorphHide.game.debugCapacity());
            TestBots.connect(s, "GuestC", c);
        });
        ctx.waitTicks(3);
        server(ctx, s -> {
            check(!MorphHide.game.contains(c), "A full lobby accepted another guest");
            TestBots.disconnect(player(s, c));
            for (int i = 0; i < 30; i++) MorphHide.game.action(player(s, local), "capacity_up");
            check(MorphHide.game.debugCapacity() == 24, "Capacity should clamp at 24");
            for (int i = 0; i < 8; i++) MorphHide.game.action(player(s, local), "capacity_down");
        });

        // --- Changing the hunter count clears everyone's ready state and stops the timer.
        act(ctx, a, "ready");
        act(ctx, b, "ready");
        ctx.waitFor(mc -> MorphHideClient.state.readySeconds > 0, 40);
        act(ctx, local, "hunters:1");
        ctx.waitTicks(12);
        ctx.runOnClient(mc -> check(MorphHideClient.state.readySeconds < 0 && MorphHideClient.state.members.stream().noneMatch(m -> m.ready()),
            "Hunter change must clear ready and stop the timer"));

        // --- Two hunters with three players; teams come from the pickers; all ready starts at once.
        act(ctx, local, "hunters:2", "team:ANIMAL");
        act(ctx, a, "team:HUNTER", "ready");
        act(ctx, b, "team:HUNTER", "ready");
        act(ctx, local, "ready");
        waitPhase(ctx, "COUNTDOWN", 40);
        server(ctx, s -> {
            check(MorphHide.game.debugRole(a).equals("HUNTER") && MorphHide.game.debugRole(b).equals("HUNTER") && MorphHide.game.debugRole(local).equals("ANIMAL"),
                "Two-hunter setting with team picks assigned wrong roles");
            MorphHide.game.action(player(s, a), "animal:PIG");   // hunters cannot pick animals
            MorphHide.game.action(player(s, local), "animal:FOX");
        });
        toHiding(ctx);
        ctx.runOnClient(mc -> {
            check(MorphHideClient.state.hunters == 2 && MorphHideClient.state.alive == 1, "Expected 2 hunters and 1 animal");
            check(MorphHideClient.state.disguises.size() == 1 && MorphHideClient.state.disguises.getFirst().animal().equals("FOX"), "Only the animal may be disguised, as a fox");
        });

        // --- A guest connecting mid-round waits in the lobby with nothing in hand.
        server(ctx, s -> TestBots.connect(s, "GuestD", d));
        ctx.waitTicks(3);
        server(ctx, s -> {
            var guest = player(s, d);
            check(MorphHide.game.contains(d) && MorphHide.game.debugRole(d).equals("WAITING"), "Mid-round guest should wait");
            check(guest.level() == s.getLevel(GameManager.DIMENSION) && Math.abs(guest.getX()) < 17 && guest.getInventory().isEmpty(), "Mid-round guest must wait in the lobby, empty-handed");
        });

        // --- Attack rules: nobody hits during hiding; animals never hit; hunters may hit the herd while hunting.
        server(ctx, s -> {
            var level = s.getLevel(GameManager.DIMENSION);
            var herd = level.getEntitiesOfClass(Mob.class, player(s, local).getBoundingBox().inflate(80), m -> !m.isNoAi() && MorphHide.game.protectedEntity(m)).getFirst();
            check(AttackEntityCallback.EVENT.invoker().interact(player(s, a), level, InteractionHand.MAIN_HAND, herd, null) == InteractionResult.FAIL, "Hunters must not hit during hiding");
            check(AttackEntityCallback.EVENT.invoker().interact(player(s, local), level, InteractionHand.MAIN_HAND, herd, null) == InteractionResult.FAIL, "Animals must not attack");
        });
        server(ctx, s -> MorphHide.game.skipPhase());
        waitPhase(ctx, "HUNTING", 100);
        server(ctx, s -> {
            var level = s.getLevel(GameManager.DIMENSION);
            var herd = level.getEntitiesOfClass(Mob.class, player(s, local).getBoundingBox().inflate(80), m -> !m.isNoAi() && MorphHide.game.protectedEntity(m)).getFirst();
            check(AttackEntityCallback.EVENT.invoker().interact(player(s, a), level, InteractionHand.MAIN_HAND, herd, null) == InteractionResult.PASS, "Hunters may hit herd animals");
            check(AttackEntityCallback.EVENT.invoker().interact(player(s, local), level, InteractionHand.MAIN_HAND, player(s, a), null) == InteractionResult.FAIL, "Animals must not hit hunters");
            check(AttackEntityCallback.EVENT.invoker().interact(player(s, d), level, InteractionHand.MAIN_HAND, herd, null) == InteractionResult.FAIL, "Waiting guests must not interfere");
            check(player(s, a).getInventory().getItem(0).is(Items.WOODEN_SWORD) && player(s, a).getInventory().getItem(0).has(net.minecraft.core.component.DataComponents.UNBREAKABLE), "Hunters must carry an unbreakable wooden sword");
            // What hunters are told about the animal team: names and alive only, never forms or disguises.
            var seen = MorphHide.game.view(player(s, a)).roster;
            check(!seen.isEmpty() && seen.stream().allMatch(m -> m.animal().isEmpty() && m.fed() < 0 && m.entityId() < 0), "Roster leaks animal details to hunters: " + seen);
        });

        ctx.waitTicks(2); // the wrong-hit penalty from the rule check above lands now; it must not carry into the damage checks
        server(ctx, s -> player(s, a).removeAllEffects());

        // --- Real attack packets: the hunter can damage every herd species and the disguised player
        //     (whether the crosshair lands on the animal model or on the hidden player hitbox).
        for (var kind : dev.morphhide.game.AnimalKind.values()) {
            float[] before = new float[1];
            Mob[] target = new Mob[1];
            server(ctx, s -> {
                var level = s.getLevel(GameManager.DIMENSION);
                var hunter = player(s, a);
                target[0] = level.getEntitiesOfClass(Mob.class, hunter.getBoundingBox().inflate(300),
                    m -> !m.isNoAi() && m.getType() == kind.entityType && MorphHide.game.protectedEntity(m) && m.isAlive()).getFirst();
                var mob = target[0];
                hunter.teleportTo(level, mob.getX() - 2, mob.getY(), mob.getZ(), Set.of(), -90, 0, true);
                before[0] = mob.getHealth();
            });
            ctx.waitTicks(25); // full attack strength
            server(ctx, s -> {
                var mob = target[0];
                var hunter = player(s, a);
                hunter.teleportTo(s.getLevel(GameManager.DIMENSION), mob.getX() - 2, mob.getY(), mob.getZ(), Set.of(), -90, 0, true);
                hunter.connection.handleAttack(new net.minecraft.network.protocol.game.ServerboundAttackPacket(mob.getId()));
                check(!mob.isAlive() || mob.getHealth() < before[0], "Hunter could not hurt a herd " + kind + ": " + before[0] + " -> " + mob.getHealth());
            });
            ctx.waitTicks(2);
            server(ctx, s -> {
                var hunter = player(s, a);
                for (var effect : List.of(net.minecraft.world.effect.MobEffects.SLOWNESS, net.minecraft.world.effect.MobEffects.BLINDNESS, net.minecraft.world.effect.MobEffects.WEAKNESS))
                    check(hunter.hasEffect(effect) && hunter.getEffect(effect).getDuration() <= 60, "Hitting a real animal must apply " + effect + " for 3 s");
                hunter.removeAllEffects();
            });
        }
        // --- A dead herd animal comes back elsewhere, the count holds; stray vanilla mobs (the old unkillable horse) are removed.
        long[] herdCount = new long[1];
        Mob[] victim = new Mob[1];
        net.minecraft.world.entity.Entity[] stray = new net.minecraft.world.entity.Entity[1];
        ctx.waitTicks(5);
        server(ctx, s -> {
            var level = s.getLevel(GameManager.DIMENSION);
            herdCount[0] = MorphHide.game.debugHerdAlive();
            victim[0] = level.getEntitiesOfClass(Mob.class, player(s, local).getBoundingBox().inflate(300), m -> !m.isNoAi() && MorphHide.game.protectedEntity(m) && m.isAlive()).getFirst();
            victim[0].kill(level);
            stray[0] = net.minecraft.world.entity.EntityTypes.HORSE.create(level, net.minecraft.world.entity.EntitySpawnReason.NATURAL);
            stray[0].snapTo(256.5, 101, 10.5, 0, 0);
            level.addFreshEntity(stray[0]);
        });
        ctx.waitTicks(25);
        server(ctx, s -> {
            check(MorphHide.game.debugHerdAlive() == herdCount[0], "Killed herd animal was not replaced: " + herdCount[0] + " -> " + MorphHide.game.debugHerdAlive());
            check(stray[0].isRemoved(), "Stray vanilla horse must not stay on the board");
        });
        for (boolean viaModel : new boolean[]{true, false}) {
            float[] before = new float[1];
            ctx.waitTicks(25);
            server(ctx, s -> {
                var level = s.getLevel(GameManager.DIMENSION);
                var animal = player(s, local);
                var hunter = player(s, a);
                var proxy = level.getEntitiesOfClass(Mob.class, animal.getBoundingBox().inflate(.5), m -> m.isNoAi() && MorphHide.game.protectedEntity(m)).getFirst();
                hunter.teleportTo(level, animal.getX() - 2, animal.getY(), animal.getZ(), Set.of(), -90, 0, true);
                before[0] = animal.getHealth();
                hunter.connection.handleAttack(new net.minecraft.network.protocol.game.ServerboundAttackPacket(viaModel ? proxy.getId() : animal.getId()));
                check(animal.getHealth() < before[0], "Hunter could not hurt the disguised player via " + (viaModel ? "its animal model" : "its player hitbox"));
                animal.setHealth(animal.getMaxHealth());
            });
        }

        // --- Grazing turns the grass block underfoot into dirt, like a vanilla sheep.
        server(ctx, s -> { player(s, a).teleportTo(s.getLevel(GameManager.DIMENSION), 256.5, 101, .5, Set.of(), 0, 0, true); });
        ctx.waitFor(mc -> mc.player.onGround() && mc.player.getDeltaMovement().horizontalDistance() < 1e-3, 100); // knockback from the hits above
        ctx.waitTicks(5);
        BlockPos[] under = new BlockPos[1];
        server(ctx, s -> {
            var p = player(s, local);
            // Nothing may push the animal off the block during the 3 s graze.
            for (var mob : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(8), m -> !m.isNoAi())) mob.discard();
            under[0] = p.blockPosition().below();
            s.getLevel(GameManager.DIMENSION).setBlock(p.blockPosition(), Blocks.AIR.defaultBlockState(), 3);
            s.getLevel(GameManager.DIMENSION).setBlock(under[0], Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        });
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> MorphHideClient.send("eat"));
        ctx.waitTicks(70);
        server(ctx, s -> check(s.getLevel(GameManager.DIMENSION).getBlockState(under[0]).is(Blocks.DIRT), "Grazing did not eat the grass block"));

        // --- Lure stage: a real sheep walks toward a hunter holding wheat.
        server(ctx, s -> MorphHide.game.fastForwardHunt(95));
        ctx.waitFor(mc -> MorphHideClient.state.lured, 60);
        double[] gap = new double[1];
        Sheep[] sheep = new Sheep[1];
        server(ctx, s -> {
            var level = s.getLevel(GameManager.DIMENSION);
            for (int x = 249; x <= 264; x++) for (int z = -3; z <= 3; z++) {
                level.setBlock(new BlockPos(x, 130, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 131; y <= 133; y++) level.setBlock(new BlockPos(x, y, z),
                    (x == 249 || x == 264 || Math.abs(z) == 3 ? Blocks.GLASS : Blocks.AIR).defaultBlockState(), 3);
            }
            var hunter = player(s, a);
            hunter.teleportTo(level, 251.5, 131, .5, Set.of(), -90, 0, true);
            hunter.getInventory().setSelectedSlot(1);
            check(hunter.getMainHandItem().is(Items.WHEAT), "Hunter should hold the wheat lure");
            sheep[0] = level.getEntitiesOfClass(Sheep.class, hunter.getBoundingBox().inflate(200), m -> !m.isNoAi()).getFirst();
            sheep[0].teleportTo(258.5, 131, .5);
            gap[0] = sheep[0].distanceTo(hunter);
        });
        ctx.waitTicks(100);
        server(ctx, s -> {
            double now = sheep[0].distanceTo(player(s, a));
            check(now < gap[0] - 2, "Herd sheep ignored the wheat lure: " + gap[0] + " -> " + now);
            player(s, a).getInventory().setSelectedSlot(0);
        });

        // --- Both hunters leave: animals win, departed hunters stay in the summary.
        server(ctx, s -> TestBots.disconnect(player(s, b)));
        ctx.waitTicks(3);
        server(ctx, s -> check(MorphHide.game.debugPhase().equals("HUNTING"), "One hunter leaving must not end the round"));
        server(ctx, s -> TestBots.disconnect(player(s, a)));
        waitPhase(ctx, "RESULTS", 60);
        ctx.waitFor(mc -> ResultsOverlay.active(MorphHideClient.state), 40);
        ctx.runOnClient(mc -> {
            var r = MorphHideClient.state.results;
            check(r.winner().equals("ANIMALS") && r.reason().contains("猎人全部离开"), "Hunters leaving should hand animals the win: " + r.reason());
            check(r.rows().size() == 3 && r.rows().stream().filter(row -> row.side().equals("HUNTER")).allMatch(row -> row.left()), "Departed hunters missing from results: " + r.rows());
            var me = r.rows().stream().filter(row -> row.uuid().equals(local.toString())).findFirst().orElseThrow();
            check(me.survived() && me.animal().equals("FOX") && me.seconds() >= 90, "Local fox should survive ~95s+: " + me);
            check(Boolean.TRUE.equals(ResultsOverlay.localWon()), "Surviving animal must see the winning screen");
        });
        ctx.waitTicks(20);
        ctx.takeScreenshot("17-animals-win-hunters-left");
        server(ctx, s -> MorphHide.game.skipPhase());
        waitPhase(ctx, "LOBBY", 60);
        server(ctx, s -> {
            check(MorphHide.game.debugRole(d).equals("WAITING") && disguises(s) == 0, "Lobby after results should have no disguises");
            check(!player(s, local).isInvisible() && MorphRegistry.SERVER.isEmpty(), "Local player still disguised after the round");
        });

        // --- Reconnect: the hunter's lure items are gone (backup restored), and they rejoin the lobby.
        server(ctx, s -> TestBots.connect(s, "GuestA", a));
        ctx.waitTicks(3);
        server(ctx, s -> {
            var guest = player(s, a);
            check(MorphHide.game.contains(a) && Math.abs(guest.getX()) < 17, "Reconnected guest did not return to the lobby");
            check(!guest.getInventory().hasAnyOf(Set.of(Items.WHEAT, Items.CARROT, Items.WHEAT_SEEDS, Items.WOODEN_SWORD)), "Round items leaked into the lobby after reconnect");
        });

        // --- Round 2: an animal disconnects mid-hunt, the rest survive the clock: animals win on time.
        server(ctx, s -> TestBots.connect(s, "GuestE", e));
        ctx.waitTicks(3);
        act(ctx, local, "hunters:1", "team:ANIMAL");
        act(ctx, d, "team:ANIMAL");
        act(ctx, e, "team:ANIMAL");
        act(ctx, a, "team:HUNTER");
        act(ctx, local, "start");
        toHiding(ctx);
        server(ctx, s -> MorphHide.game.skipPhase());
        waitPhase(ctx, "HUNTING", 100);
        ctx.waitFor(mc -> MorphHideClient.state.alive == 3 && MorphHideClient.state.hunters == 1, 40);
        // Feeding: the wrong crop does nothing; three bites of the right crop fill the task and remove the crops.
        String food = ctx.computeOnClient(mc -> MorphHideClient.state.food);
        check(food.equals("BERRIES"), "A fox must be told to eat berries, got " + food);
        int[][] fields = ctx.computeOnClient(mc -> {
            var wrong = MorphHideClient.state.farms.stream().filter(f -> !f.food().equals("BERRIES")).findFirst().orElseThrow();
            var right = MorphHideClient.state.farms.stream().filter(f -> f.food().equals("BERRIES")).findFirst().orElseThrow();
            return new int[][]{{wrong.x(), wrong.z()}, {right.x(), right.z()}};
        });
        check(ctx.computeOnClient(mc -> MorphHideClient.state.farms.size()) == 8, "Expected eight food fields");
        server(ctx, s -> {
            int labels = 0;
            for (var entity : s.getLevel(GameManager.DIMENSION).getAllEntities())
                if (entity.entityTags().contains("morphhide_farm") && entity instanceof net.minecraft.world.entity.Display.TextDisplay t && t.getText().getString().contains("吃这里")) labels++;
            check(labels == 8, "Every field needs a floating sign, found " + labels);
        });
        server(ctx, s -> player(s, local).teleportTo(s.getLevel(GameManager.DIMENSION), fields[0][0] + 1.5, 101, fields[0][1] + .5, Set.of(), 0, 0, true));
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> MorphHideClient.send("eat"));
        ctx.waitTicks(70); // the (grass) eating animation still runs its full 3 s
        server(ctx, s -> check(!s.getLevel(GameManager.DIMENSION).getBlockState(new BlockPos(fields[0][0] + 1, 101, fields[0][1])).isAir(), "Eating the wrong crop must not remove it"));
        ctx.runOnClient(mc -> check(MorphHideClient.state.fed == 0, "Wrong crop must not count"));
        server(ctx, s -> player(s, local).teleportTo(s.getLevel(GameManager.DIMENSION), fields[1][0] + 1.5, 101, fields[1][1] + .5, Set.of(), 0, 0, true));
        ctx.waitTicks(10);
        ctx.takeScreenshot("19a-task-at-field");
        // A bite takes 3 s; walking away part-way interrupts it and nothing is eaten.
        ctx.runOnClient(mc -> MorphHideClient.send("eat"));
        ctx.waitTicks(15);
        ctx.runOnClient(mc -> check(MorphHideClient.state.eatLeft > 0 && MorphHideClient.state.fed == 0, "Bite should be in progress, not finished yet"));
        ctx.takeScreenshot("19a2-eating-countdown");
        ctx.waitTicks(5);
        server(ctx, s -> player(s, local).teleportTo(s.getLevel(GameManager.DIMENSION), fields[1][0] - 1.5, 101, fields[1][1] + .5, Set.of(), 0, 0, true));
        ctx.waitTicks(70);
        ctx.runOnClient(mc -> check(MorphHideClient.state.fed == 0 && MorphHideClient.state.eatLeft == 0, "Moving away must interrupt the bite"));
        server(ctx, s -> check(!s.getLevel(GameManager.DIMENSION).getBlockState(new BlockPos(fields[1][0] + 1, 101, fields[1][1])).isAir(), "An interrupted bite must not remove the crop"));
        server(ctx, s -> player(s, local).teleportTo(s.getLevel(GameManager.DIMENSION), fields[1][0] + 1.5, 101, fields[1][1] + .5, Set.of(), 0, 0, true));
        ctx.waitTicks(10);
        for (int bite = 1; bite <= 3; bite++) {
            ctx.runOnClient(mc -> MorphHideClient.send("eat"));
            final int expected = bite;
            ctx.waitTicks(40);
            ctx.runOnClient(mc -> check(MorphHideClient.state.fed == expected - 1, "A bite must not land before 3 seconds"));
            ctx.waitFor(mc -> MorphHideClient.state.fed == expected, 60);
            ctx.waitTicks(3);
        }
        server(ctx, s -> check(s.getLevel(GameManager.DIMENSION).getBlockState(new BlockPos(fields[1][0] + 1, 101, fields[1][1])).isAir(), "Eaten crop should disappear"));
        ctx.takeScreenshot("19b-task-fed");
        // Final circle: border packet, animals outside take damage, hunters are pushed back in.
        server(ctx, s -> {
            var level = s.getLevel(GameManager.DIMENSION);
            for (var id : List.of(local, a, d, e)) player(s, id).teleportTo(level, 256.5, 101, .5, Set.of(), 0, 0, true);
            MorphHide.game.debugSetHuntLeft(60);
        });
        ctx.waitFor(mc -> MorphHideClient.state.shrinking, 60);
        // Only part of the herd walks in, every species represented, and it arrives by the end of the 60 s circle.
        server(ctx, s -> {
            var group = MorphHide.game.debugShrinkHerd();
            check(group.size() == 12 && MorphHide.game.debugHerdAlive() > 12, "Exactly 12 of the herd should walk in: " + group.size());
            var kinds = new HashMap<Object, Integer>();
            for (var mob : group) kinds.merge(mob.getType(), 1, Integer::sum);
            check(kinds.size() == 6 && kinds.values().stream().allMatch(n -> n == 2), "Walking group should have 2 of each species: " + kinds);
        });
        ctx.waitTicks(1000);
        server(ctx, s -> MorphHide.game.debugSetHuntLeft(60));
        ctx.waitTicks(200);
        server(ctx, s -> {
            var group = MorphHide.game.debugShrinkHerd();
            long inside = group.stream().filter(m -> Math.abs(m.getX() - 256.5) <= 6 && Math.abs(m.getZ() - .5) <= 6).count();
            System.out.println("MORPHHIDE_SHRINK_HERD inside=" + inside + "/" + group.size());
            check(inside >= 10, "Herd did not reach the final circle: " + inside + "/" + group.size());
        });
        // Teammates: the fellow animals' disguises glow and carry a name, only for the animal team.
        ctx.runOnClient(mc -> {
            check(MorphHideClient.state.roster.size() >= 3 && MorphHideClient.state.roster.stream().anyMatch(m -> m.status().equals("ALIVE") && !m.animal().isEmpty()), "Animal team roster missing: " + MorphHideClient.state.roster);
            check(!MorphRegistry.CLIENT_TEAMMATES.isEmpty(), "No teammate disguises known to the client");
            for (int id : MorphRegistry.CLIENT_TEAMMATES) {
                var proxy = mc.level.getEntity(id);
                check(proxy != null && mc.shouldEntityAppearGlowing(proxy) && proxy.getCustomName() != null, "Teammate disguise should glow and carry a name");
            }
        });
        ctx.runOnClient(mc -> check(mc.level.getWorldBorder().getSize() < 100, "Shrinking border was not sent to the client: " + mc.level.getWorldBorder().getSize()));
        float[] hp = new float[1];
        server(ctx, s -> {
            var level = s.getLevel(GameManager.DIMENSION);
            player(s, e).teleportTo(level, 256.5, 101, 2.5, Set.of(), 0, 0, true); // keep GuestE inside: it must live to starve
            player(s, d).teleportTo(level, 256.5, 101, -1.5, Set.of(), 0, 0, true);
            MorphHide.game.debugFastForwardShrink(50);
            player(s, local).teleportTo(level, 256.5 + 20, 101, .5, Set.of(), 0, 0, true);
            player(s, a).teleportTo(level, 256.5 - 20, 101, .5, Set.of(), 0, 0, true);
            hp[0] = player(s, local).getHealth();
        });
        ctx.waitTicks(25);
        server(ctx, s -> {
            var animal = player(s, local);
            double half = MorphHide.game.debugBorderSize() / 2;
            check(animal.getHealth() < hp[0], "Animal outside the circle must take damage");
            check(Math.abs(player(s, a).getX() - 256.5) <= half + .5, "Hunter outside the circle must be pushed back in");
            animal.teleportTo(s.getLevel(GameManager.DIMENSION), 256.5, 101, .5, Set.of(), 0, 0, true);
            animal.setHealth(animal.getMaxHealth());
        });
        ctx.takeScreenshot("19c-shrinking");
        server(ctx, s -> TestBots.disconnect(player(s, d)));
        ctx.waitFor(mc -> MorphHideClient.state.alive == 2, 40);
        server(ctx, s -> MorphHide.game.skipPhase());
        waitPhase(ctx, "RESULTS", 60);
        ctx.waitFor(mc -> ResultsOverlay.active(MorphHideClient.state), 40);
        ctx.runOnClient(mc -> {
            var r = MorphHideClient.state.results;
            check(r.winner().equals("ANIMALS") && r.reason().contains("吃饱"), "A fed survivor should win on time: " + r.reason());
            var starved = r.rows().stream().filter(row -> row.uuid().equals(e.toString())).findFirst().orElseThrow();
            check(starved.killer().equals("饿死") && !starved.survived(), "An unfed animal must starve at the end: " + starved);
            var gone = r.rows().stream().filter(row -> row.uuid().equals(d.toString())).findFirst().orElseThrow();
            check(gone.left() && gone.side().equals("ANIMAL") && !gone.survived(), "Departed animal row wrong: " + gone);
            var hunter = r.rows().stream().filter(row -> row.side().equals("HUNTER")).findFirst().orElseThrow();
            check(hunter.kills() == 0, "Hunter caught nobody");
            check(Boolean.TRUE.equals(ResultsOverlay.localWon()), "Survivor should win");
        });
        ctx.waitTicks(20);
        ctx.takeScreenshot("18-animals-win-on-time");
        server(ctx, s -> MorphHide.game.skipPhase());
        waitPhase(ctx, "LOBBY", 60);
        ctx.runOnClient(mc -> check(mc.level.getWorldBorder().getSize() > 1000, "World border was not restored after the round"));

        // --- Round 3: nobody eats; when time runs out everyone starves and the hunter wins.
        act(ctx, local, "start");
        toHiding(ctx);
        server(ctx, s -> MorphHide.game.skipPhase());
        waitPhase(ctx, "HUNTING", 100);
        server(ctx, s -> MorphHide.game.skipPhase());
        waitPhase(ctx, "RESULTS", 60);
        ctx.waitFor(mc -> ResultsOverlay.active(MorphHideClient.state), 40);
        ctx.runOnClient(mc -> {
            var r = MorphHideClient.state.results;
            check(r.winner().equals("HUNTERS") && r.reason().contains("饿死"), "All animals starving should be a hunter win: " + r.reason());
            check(Boolean.FALSE.equals(ResultsOverlay.localWon()), "A starved animal lost");
        });
        ctx.takeScreenshot("20-everyone-starved");
        server(ctx, s -> MorphHide.game.skipPhase());
        waitPhase(ctx, "LOBBY", 60);
        server(ctx, s -> TestBots.disconnect(player(s, e)));
        ctx.waitTicks(3);

        // --- Practice with two players: both are animals. Only the host may stop it.
        act(ctx, local, "practice");
        waitPhase(ctx, "COUNTDOWN", 40);
        server(ctx, s -> check(MorphHide.game.debugRole(local).equals("ANIMAL") && MorphHide.game.debugRole(a).equals("ANIMAL"), "Practice must make everyone an animal"));
        toHiding(ctx);
        server(ctx, s -> {
            check(MorphHide.game.isAnimalInRound(player(s, local)) && MorphHide.game.isAnimalInRound(player(s, a)) && disguises(s) == 2, "Both practice players should be disguised: local=" + MorphHide.game.isAnimalInRound(player(s, local)) + " a=" + MorphHide.game.isAnimalInRound(player(s, a)) + " disguises=" + disguises(s) + " roles=" + MorphHide.game.debugRole(local) + "/" + MorphHide.game.debugRole(a) + " phase=" + MorphHide.game.debugPhase() + " proxies=" + MorphHide.game.debugProxies());
            check(MorphHide.game.debugProxies().indexOf("removed=true") < 0, "A disguise entity was unloaded: " + MorphHide.game.debugProxies());
            MorphHide.game.action(player(s, a), "stop");
            check(MorphHide.game.debugPhase().equals("HIDING"), "A guest stopped the practice round");
            MorphHide.game.action(player(s, local), "stop");
        });
        waitPhase(ctx, "LOBBY", 60);
        server(ctx, s -> check(disguises(s) == 0 && MorphRegistry.SERVER.isEmpty() && !player(s, a).isInvisible(), "Host stop left disguises behind"));
        ctx.runOnClient(mc -> check(MorphRegistry.CLIENT.isEmpty() && !ResultsOverlay.active(MorphHideClient.state), "Client still thinks someone is disguised"));

        // --- Host hand-over: when the host leaves, the next player can manage the lobby.
        act(ctx, local, "leave");
        ctx.waitFor(mc -> !MorphHideClient.state.joined, 40);
        server(ctx, s -> {
            MorphHide.game.action(player(s, a), "hunters:2");
            check(MorphHide.game.debugHunterSetting() == 2, "New host could not change settings");
            MorphHide.game.action(player(s, a), "hunters:0");
            TestBots.disconnectAll();
        });
        ctx.waitTicks(3);
        System.out.println("MORPHHIDE_EDGE_PASS: feeding, starvation, wrong-hit slow, shrinking border, permissions, capacity, ready reset, 2 hunters, mid-round join, attack rules, grazing, lure, hunters leave, reconnect, timeout win, animal leaves, practice x2, host stop, host hand-over");
    }
}
