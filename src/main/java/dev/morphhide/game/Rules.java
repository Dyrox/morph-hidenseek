package dev.morphhide.game;

public final class Rules {
    private Rules() {}
    public static int hunterCount(int players) { return Math.clamp((players + 4) / 5, 1, 2); }
    public static int requiredPlayers(int hunterSetting) { return Math.max(2, hunterSetting + 1); }
    public static int configuredHunters(int players, int setting) { return setting == 0 ? hunterCount(players) : Math.clamp(setting, 1, Math.max(1, players-1)); }
    public static int remainingSeconds(int ticks) { return Math.max(0, (ticks + 19) / 20); }
    public static String winner(int aliveHiders, int hunters, int ticksLeft, boolean practice) {
        if (aliveHiders == 0) return "HUNTERS";
        if ((!practice && hunters == 0) || ticksLeft <= 0) return "ANIMALS";
        return "";
    }
}
