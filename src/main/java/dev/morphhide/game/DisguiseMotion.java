package dev.morphhide.game;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * Moves a disguise with its player like a vanilla mob moves itself: the head tracks the view
 * immediately, yRot turns toward the walking direction at most 90°/tick (MoveControl), and the body
 * is left to the mob's own BodyRotationControl, so a standing animal turns its head first and its
 * body follows after a moment.
 */
public final class DisguiseMotion {
    private DisguiseMotion() {}

    public static void follow(Mob proxy, Player owner, boolean eating, boolean sheep) {
        double dx = owner.getX() - proxy.getX(), dz = owner.getZ() - proxy.getZ();
        proxy.setPos(owner.position());
        if (dx * dx + dz * dz > 2.5000003E-7F) {
            float walking = (float)(Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
            proxy.setYRot(Mth.approachDegrees(proxy.getYRot(), walking, 90f));
        }
        proxy.setYHeadRot(owner.getYHeadRot());
        // Grazing: sheep use their own eat animation; other animals lower the head.
        proxy.setXRot(eating && !sheep ? 40f : Mth.clamp(owner.getXRot(), -40f, 40f));
        proxy.setOnGround(owner.onGround());
    }
}
