package dev.morphhide.game;

import java.util.Arrays;
import java.util.stream.Collectors;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

/** What each disguise must eat (vanilla diets): a fed animal that survives to the end wins. */
public enum Food {
    WHEAT("小麦", "小麦田", Blocks.WHEAT, 0xFFE8C66A),
    CARROT("胡萝卜", "胡萝卜田", Blocks.CARROTS, 0xFFFF9A3C),
    BEETROOT("甜菜根", "甜菜田", Blocks.BEETROOTS, 0xFFE0506A),
    BERRIES("甜浆果", "浆果丛", Blocks.SWEET_BERRY_BUSH, 0xFFFF6F8A);

    public static final int BITES = 3;
    public final String label, field;
    public final Block block;
    public final int color;
    Food(String label, String field, Block block, int color) { this.label = label; this.field = field; this.block = block; this.color = color; }

    public BlockState ripe() {
        return block instanceof CropBlock crop ? crop.getStateForAge(crop.getMaxAge())
            : block.defaultBlockState().setValue(SweetBerryBushBlock.AGE, SweetBerryBushBlock.MAX_AGE);
    }
    public static Food parse(String value) {
        try { return valueOf(value); } catch (IllegalArgumentException | NullPointerException e) { return null; }
    }
    /** "猪 · 兔子" */
    public String eaters() {
        return Arrays.stream(AnimalKind.values()).filter(k -> k.food() == this).map(k -> k.label).collect(Collectors.joining(" · "));
    }
}
