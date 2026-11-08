package dev.morphhide.test;

import dev.morphhide.MorphHide;
import dev.morphhide.game.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Real keyboard movement vs the vanilla mob's horizontal walking physics on a flat lane. */
final class MovementScenario {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static void run(ClientGameTestContext ctx) {
        UUID id = ctx.computeOnClient(mc -> mc.player.getUUID());
        var savedGame = new AtomicReference<GameManager>();
        var reference = new AtomicReference<Mob>();
        var referenceGoal = new double[]{1};
        CombatScenario.onServer(ctx, server -> {
            // No match is active. Suspend empty-lobby sync while testing each form independently.
            savedGame.set(MorphHide.game); MorphHide.game = null;
            var level = server.getLevel(GameManager.DIMENSION);
            for (int x=125;x<=137;x++) for (int z=-4;z<=100;z++)
                level.setBlock(new BlockPos(x,120,z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
            var p = server.getPlayerList().getPlayer(id);
            p.setGameMode(GameType.ADVENTURE);
            ServerTickEvents.END_SERVER_TICK.register(s -> {
                var mob = reference.get();
                if (mob == null || mob.isRemoved()) return;
                // Use Mob.setSpeed itself: it also sets zza. No guessed blocks/second constant.
                mob.setSpeed((float)(mob.getAttributeValue(Attributes.MOVEMENT_SPEED)*referenceGoal[0]));
                mob.travel(new Vec3(0, 0, mob.zza));
            });
        });
        try {
            double humanWalk = sample(ctx, id, null, false, reference, referenceGoal);
            double humanSprint = sample(ctx, id, null, true, reference, referenceGoal);
            check(humanSprint > humanWalk * 1.2, "Normal player sprint no longer works");
            double pigWalk = 0;
            for (var kind : AnimalKind.values()) {
                double speed = sample(ctx, id, kind, false, reference, referenceGoal);
                check(speed < humanWalk, kind + " walks faster than a human");
                if (kind == AnimalKind.PIG) pigWalk = speed;
            }
            double pigSprintKey = sample(ctx, id, AnimalKind.PIG, true, reference, referenceGoal);
            // Double-tap W / Ctrl: a pig fast-walks at its vanilla food-following pace (TemptGoal 1.2), not a human sprint.
            // Mob ground speed scales with the modifier squared (MoveControl sets both speed and input).
            check(Math.abs(pigSprintKey/pigWalk-1.2*1.2) < .06, "Pig fast walk should be 1.44x its walk: " + pigWalk + " -> " + pigSprintKey);
            double rabbitWalk = sample(ctx, id, AnimalKind.RABBIT, false, reference, referenceGoal);
            double rabbitFast = sample(ctx, id, AnimalKind.RABBIT, true, reference, referenceGoal);
            check(Math.abs(rabbitFast/rabbitWalk-1/(.6*.6)) < .15, "Rabbit fast walk should match its food-following pace: " + rabbitWalk + " -> " + rabbitFast);
            // A full jump cannot gain the human sprint-jump impulse.
            ctx.runOnClient(mc -> {
                mc.player.setSprinting(true);
                check(mc.player.isSprinting(), "Animal fast walk should use the sprint state");
                check(!mc.player.canSpawnSprintParticle(), "Animal fast walk must not kick up sprint dust");
                mc.player.setDeltaMovement(Vec3.ZERO);
                mc.player.jumpFromGround();
                check(mc.player.getDeltaMovement().horizontalDistance() < .001, "Animal got human sprint-jump boost");
            });
            double restored = sample(ctx, id, null, true, reference, referenceGoal);
            check(Math.abs(restored-humanSprint) < .08, "Human sprint not restored after unmorphing");
            System.out.println("MORPHHIDE_MOVEMENT_PASS: six vanilla walking comparisons, tempt-speed fast walk (pig, rabbit), no sprint-jump boost, human speed restored");
        } finally {
            ctx.getInput().releaseKey(o -> o.keyUp);
            ctx.getInput().releaseKey(o -> o.keySprint);
            ctx.runOnClient(mc -> MorphRegistry.CLIENT.remove(id));
            CombatScenario.onServer(ctx, server -> {
                var mob = reference.getAndSet(null); if (mob != null) mob.discard();
                MorphRegistry.SERVER.remove(id);
                // Regression: multiple orphaned proxies must be removed without mutating
                // the live entity iterator during traversal.
                var level = server.getLevel(GameManager.DIMENSION);
                for (int i=0;i<12;i++) {
                    var orphan = AnimalKind.PIG.entityType.create(level, EntitySpawnReason.COMMAND);
                    orphan.addTag("morphhide_owned"); orphan.setNoAi(true);
                    orphan.snapTo(135.5,121,i+30,0,0); level.addFreshEntity(orphan);
                }
                MorphHide.game = savedGame.get();
            });
            ctx.waitTicks(25);
            CombatScenario.onServer(ctx, server -> {
                for (var entity : server.getLevel(GameManager.DIMENSION).getAllEntities())
                    check(!entity.entityTags().contains("morphhide_owned"), "Orphaned game entity was not cleaned up");
            });
            System.out.println("MORPHHIDE_CLEANUP_PASS: orphan entities removed safely");
        }
    }

    private static double sample(ClientGameTestContext ctx, UUID id, AnimalKind kind, boolean sprint,
                                 AtomicReference<Mob> reference, double[] goal) {
        ctx.getInput().releaseKey(o -> o.keyUp);
        ctx.getInput().releaseKey(o -> o.keySprint);
        CombatScenario.onServer(ctx, server -> {
            var old = reference.getAndSet(null); if (old != null) old.discard();
            var p = server.getPlayerList().getPlayer(id);
            var level = server.getLevel(GameManager.DIMENSION);
            if (kind == null) MorphRegistry.SERVER.remove(id); else MorphRegistry.SERVER.put(id,kind);
            p.setSprinting(false); p.refreshDimensions();
            p.teleportTo(level,128.5,121,.5,Set.of(),0,0,true);
            p.setDeltaMovement(Vec3.ZERO);
        });
        ctx.runOnClient(mc -> {
            if (kind == null) MorphRegistry.CLIENT.remove(id); else MorphRegistry.CLIENT.put(id,kind);
            mc.player.setSprinting(false); mc.player.refreshDimensions();
        });
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> {mc.player.setYRot(0);mc.player.setXRot(0);mc.player.setDeltaMovement(Vec3.ZERO);});
        if (kind != null) CombatScenario.onServer(ctx, server -> {
            var level = server.getLevel(GameManager.DIMENSION);
            Mob mob = kind.entityType.create(level, EntitySpawnReason.COMMAND);
            mob.setNoAi(true); mob.setPersistenceRequired(); mob.snapTo(133.5,121,.5,0,0);
            mob.setOnGround(true); level.addFreshEntity(mob);
            goal[0] = sprint ? AnimalMovement.temptModifier(kind) : kind == AnimalKind.RABBIT ? .6 : 1;
            reference.set(mob);
        });
        ctx.getInput().holdKey(o -> o.keyUp);
        if (sprint) ctx.getInput().holdKey(o -> o.keySprint);
        ctx.waitTicks(20); // Compare steady motion, excluding acceleration and teleport acknowledgement.
        double[] start = ctx.computeOnClient(mc -> new double[]{mc.player.getZ(), mc.player.tickCount});
        var refStart = new double[2];
        CombatScenario.onServer(ctx, server -> {if(reference.get()!=null) {refStart[0]=reference.get().getZ();refStart[1]=reference.get().level().getGameTime();}});
        ctx.waitTicks(40);
        double[] end = ctx.computeOnClient(mc -> new double[]{mc.player.getZ(), mc.player.tickCount});
        var refEnd = new double[2];
        CombatScenario.onServer(ctx, server -> {if(reference.get()!=null) {refEnd[0]=reference.get().getZ();refEnd[1]=reference.get().level().getGameTime();}});
        double speed = (end[0]-start[0])*20/(end[1]-start[1]);
        double nativeSpeed = kind == null ? 0 : (refEnd[0]-refStart[0])*20/(refEnd[1]-refStart[1]);
        check(speed > .5, "Player did not move in " + kind);
        if (kind != null) {
            check(Math.abs(speed-nativeSpeed)/nativeSpeed < .06,
                kind+" movement differs from native physics: player="+speed+", mob="+nativeSpeed);
            ctx.runOnClient(mc -> check(mc.player.isSprinting() == sprint, "Sprint key state not applied to the animal"));
        }
        System.out.printf(Locale.ROOT,"MORPHHIDE_WALK: %s sprintKey=%s player=%.3f reference=%.3f blocks/s%n",kind,sprint,speed,nativeSpeed);
        return speed;
    }
}
