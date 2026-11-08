package dev.morphhide.network;

import java.util.ArrayList;
import java.util.List;

/** Public lobby information plus only the receiving player's private game information. */
public final class GameView {
    public String preference = "AUTO";
    public boolean joined, host, ready, practice, canStart;
    public String phase = "LOBBY", role = "VISITOR", animal = "SHEEP", theme = "待生成", winner = "", hint = "";
    public int seconds, alive, hunters, players, capacity = 16, progress;
    public int hunterSetting, minPlayers = 2;
    public int readySeconds = -1, readyTotal = 60;
    public boolean lured;
    public int lureIn = -1;
    /** Animal task: food to eat (Food name), bites eaten / needed, and every field on the map. */
    public String food = "";
    public int fed, need = 3, eatLeft;
    public List<Farm> farms = new ArrayList<>();
    public record Farm(String food, int x, int z) {}
    /** Final circle: seconds until it starts (-1 none), and its current full width while shrinking. */
    public int shrinkIn = -1;
    public boolean shrinking;
    public double borderSize;
    public int hideSeconds = 30, huntSeconds = 300;
    public List<Elimination> eliminations = new ArrayList<>();
    public record Elimination(String name, String hunter, boolean left) {}
    public List<Member> members = new ArrayList<>();
    public List<Disguise> disguises = new ArrayList<>();
    /** Animal team during a round. animal, fed and entityId are only filled in for the animal team itself. */
    public List<Mate> roster = new ArrayList<>();
    public record Mate(String uuid, String name, String animal, int fed, String status, int entityId) {}
    /** Summary of the last finished round; id changes once per round so clients animate it once. */
    public Results results;
    public record Results(int id, String winner, String reason, boolean practice, int seconds, List<ResultRow> rows) {}
    /** side: HUNTER/ANIMAL; seconds: animals = time survived, hunters = time of their last catch. */
    public record ResultRow(String uuid, String name, String side, String animal, boolean survived, boolean left, int seconds, int kills, String killer) {}
    public record Member(String uuid, String name, boolean ready, boolean host, boolean eliminated, String team) {}
    public record Disguise(String uuid, String animal, int entityId, boolean eating) {}
}
