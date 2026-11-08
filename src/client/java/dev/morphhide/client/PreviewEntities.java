package dev.morphhide.client;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/** Detached GUI models never receive a spawn packet, so they need their own IDs. */
public final class PreviewEntities {
    private static final AtomicInteger NEXT_ID = new AtomicInteger(-1);

    private PreviewEntities() {}

    public static Mob create(EntityType<? extends Mob> type, Level level) {
        Mob entity = type.create(level, EntitySpawnReason.COMMAND);
        if (entity != null) {
            // 26.3's client Level returns 0 until a network spawn assigns an ID.
            // ItemModelResolver reads it even for empty equipment slots. These
            // negative IDs are local only: do not register the models in the world.
            entity.setId(NEXT_ID.getAndDecrement());
        }
        return entity;
    }
}
