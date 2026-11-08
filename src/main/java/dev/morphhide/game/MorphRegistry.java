package dev.morphhide.game;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MorphRegistry {
    public static final Map<UUID, AnimalKind> SERVER = new ConcurrentHashMap<>();
    public static final Map<UUID, AnimalKind> CLIENT = new ConcurrentHashMap<>();
    /** Client: disguise entity id -> owning player. */
    public static final Map<Integer, UUID> CLIENT_PROXIES = new ConcurrentHashMap<>();
    /** Client: players whose disguise is currently grazing. */
    public static final java.util.Set<UUID> CLIENT_EATING = ConcurrentHashMap.newKeySet();
    /** Client: entity ids of fellow animals' disguises (only ever sent to the animal team). */
    public static final java.util.Set<Integer> CLIENT_TEAMMATES = ConcurrentHashMap.newKeySet();
    private MorphRegistry() {}
}
