package dev.morphhide.game;

import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;

public final class GameConfig {
    public int hideSeconds = 5;
    public int huntSeconds = 210;
    /** Seconds into the hunt when hunters receive animal food (real animals follow it; disguised players do not). */
    public int lureSeconds = 90;
    /** The arena border shrinks for everyone during the last seconds of the hunt. */
    public int shrinkSeconds = 60;
    public int shrinkFinalSize = 12;
    /** How many herd animals walk into the final circle. */
    public int shrinkHerd = 12;
    public int countdownSeconds = 5;
    public int configVersion; // absent in pre-v2 files
    public int herdSize = 54;
    public int maxPlayers = 16;
    public int arenaRadius = 48;
    public int hunterCount = 0; // 0 = automatic; otherwise 1 or 2

    public void save() {
        try { Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("morphhide.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(this)); }
        catch (Exception e) { dev.morphhide.MorphHide.LOGGER.error("Cannot save lobby settings", e); }
    }

    public static GameConfig load() {
        var path = FabricLoader.getInstance().getConfigDir().resolve("morphhide.json");
        var gson = new GsonBuilder().setPrettyPrinting().create();
        GameConfig config = new GameConfig();
        try {
            if (Files.exists(path)) {
                var parsed = gson.fromJson(Files.readString(path), GameConfig.class);
                if (parsed != null) config = parsed;
            }
            config.hideSeconds = Math.clamp(config.hideSeconds, 3, 90);
            config.huntSeconds = Math.clamp(config.huntSeconds, 60, 900);
            config.lureSeconds = Math.clamp(config.lureSeconds, 0, config.huntSeconds);
            config.shrinkSeconds = Math.clamp(config.shrinkSeconds, 0, config.huntSeconds);
            config.shrinkFinalSize = Math.clamp(config.shrinkFinalSize, 4, 64);
            config.shrinkHerd = Math.clamp(config.shrinkHerd, 0, 40);
            // v2: the pre-round countdown default dropped from 10 to 5 seconds.
            if (config.configVersion < 2 && config.countdownSeconds == 10) config.countdownSeconds = 5;
            // v3: staged hunt; 5 minutes became 3:30 with a lure stage at 1:30.
            if (config.configVersion < 3 && config.huntSeconds == 300) config.huntSeconds = 210;
            // v4: hiding dropped from 30 to 5 seconds.
            if (config.configVersion < 4 && config.hideSeconds == 30) config.hideSeconds = 5;
            config.configVersion = 4;
            config.countdownSeconds = Math.clamp(config.countdownSeconds, 3, 30);
            config.herdSize = Math.clamp(config.herdSize, 18, 96);
            config.hunterCount = Math.clamp(config.hunterCount, 0, 2);
            config.maxPlayers = Math.clamp(config.maxPlayers, 2, 24);
            config.arenaRadius = Math.clamp(config.arenaRadius, 32, 64);
            Files.writeString(path, gson.toJson(config));
        } catch (Exception e) { dev.morphhide.MorphHide.LOGGER.error("Cannot read/write morphhide config", e); }
        return config;
    }
}
