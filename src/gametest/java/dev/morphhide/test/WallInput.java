package dev.morphhide.test;

import dev.morphhide.game.GameManager;
import dev.morphhide.game.LobbyControlWall;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.phys.EntityHitResult;

/** Presses a lobby wall control like a player: walk up, aim the crosshair, right-click. */
final class WallInput {
    private WallInput() {}

    static void press(ClientGameTestContext ctx, String action) { press(ctx, action, false); }

    /** Click a lobby team picker: the cow (animal team) or the player model (hunter team). */
    static void pickTeam(ClientGameTestContext ctx, String team, boolean leftClick) {
        ctx.setScreen(() -> null);
        UUID id = ctx.computeOnClient(mc -> mc.player.getUUID());
        double[] target = ctx.computeOnClient(mc -> {
            for (Entity e : mc.level.entitiesForRendering())
                if ((team.equals("ANIMAL") ? e instanceof net.minecraft.world.entity.animal.cow.Cow
                        : e instanceof net.minecraft.world.entity.decoration.Mannequin)
                    && e.getCustomName() != null && e.getCustomName().getString().startsWith(team.equals("ANIMAL") ? "动物队" : "猎人队"))
                    return new double[]{e.getX(), e.getY() + e.getBbHeight() / 2, e.getZ()};
            throw new AssertionError("Team picker missing: " + team);
        });
        CombatScenario.onServer(ctx, server -> server.getPlayerList().getPlayer(id)
            .teleportTo(server.getLevel(GameManager.DIMENSION), target[0], 101, target[2] - 2, Set.of(), 0, 0, true));
        ctx.waitFor(mc -> Math.abs(mc.player.getZ() - (target[2] - 2)) < .01 && mc.player.onGround(), 200);
        aimAndClick(ctx, target, leftClick, e -> e.getCustomName() != null && e.getCustomName().getString().startsWith(team.equals("ANIMAL") ? "动物队" : "猎人队"), team);
    }

    private static void aimAndClick(ClientGameTestContext ctx, double[] target, boolean leftClick,
                                    java.util.function.Predicate<Entity> expected, String what) {
        ctx.runOnClient(mc -> {
            var eye = mc.player.getEyePosition();
            double dx = target[0] - eye.x, dy = target[1] - eye.y, dz = target[2] - eye.z;
            mc.player.setYRot((float)(Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90);
            mc.player.setXRot((float)(-Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * Mth.RAD_TO_DEG));
        });
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            if (!(mc.hitResult instanceof EntityHitResult hit) || !expected.test(hit.getEntity()))
                throw new AssertionError("Crosshair is not on " + what + ": " + (mc.hitResult instanceof EntityHitResult h ? h.getEntity() + " name=" + h.getEntity().getCustomName() : mc.hitResult));
        });
        if (leftClick) ctx.getInput().pressKey(options -> options.keyAttack);
        else ctx.getInput().pressKey(options -> options.keyUse);
        ctx.waitTicks(3);
    }

    static void press(ClientGameTestContext ctx, String action, boolean leftClick) {
        ctx.setScreen(() -> null);
        UUID id = ctx.computeOnClient(mc -> mc.player.getUUID());
        double[] target = ctx.computeOnClient(mc -> {
            for (Entity e : mc.level.entitiesForRendering())
                if (e instanceof Interaction hit && hit.getCustomName() != null
                    && hit.getCustomName().getString().equals(LobbyControlWall.PREFIX + action))
                    return new double[]{e.getX(), e.getY() + hit.getHeight() / 2, e.getZ()};
            throw new AssertionError("Wall control missing on client: " + action);
        });
        CombatScenario.onServer(ctx, server -> server.getPlayerList().getPlayer(id)
            .teleportTo(server.getLevel(GameManager.DIMENSION), target[0], 101, 10, Set.of(), 0, 0, true));
        ctx.waitFor(mc -> Math.abs(mc.player.getZ() - 10) < .01 && mc.player.onGround(), 200);
        aimAndClick(ctx, target, leftClick, e -> e.getCustomName() != null
            && e.getCustomName().getString().equals(LobbyControlWall.PREFIX + action), "wall control " + action);
    }
}
