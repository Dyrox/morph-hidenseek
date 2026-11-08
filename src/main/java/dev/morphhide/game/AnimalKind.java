package dev.morphhide.game;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;

public enum AnimalKind {
    SHEEP("绵羊", "混进羊群，躲避猎人的视线。", "中型", 0xFFADC59B, EntityTypes.SHEEP),
    PIG("小猪", "在草垛和动物群之间寻找藏身处。", "中型", 0xFFF0AFB5, EntityTypes.PIG),
    COW("奶牛", "跟着牛群走动，尽量表现得像普通动物。", "大型", 0xFFD4B99A, EntityTypes.COW),
    CHICKEN("小鸡", "体型小，可以藏在低矮的障碍后面。", "小型", 0xFFF4D985, EntityTypes.CHICKEN),
    RABBIT("兔子", "借助草丛和地形遮住自己的身影。", "小型", 0xFFD7BFA5, EntityTypes.RABBIT),
    FOX("狐狸", "在树林和动物群中躲避追捕。", "中型", 0xFFF0A267, EntityTypes.FOX);

    public final String label, description, trait;
    public final int color;
    public final EntityType<? extends Mob> entityType;
    AnimalKind(String label,String description,String trait,int color,EntityType<? extends Mob> type){
        this.label=label;this.description=description;this.trait=trait;this.color=color;this.entityType=type;
    }
    /** Vanilla diets: sheep/cow wheat, pig/rabbit carrots, chicken beetroot (seeds), fox sweet berries. */
    public Food food(){
        return switch(this){case SHEEP,COW->Food.WHEAT;case PIG,RABBIT->Food.CARROT;case CHICKEN->Food.BEETROOT;case FOX->Food.BERRIES;};
    }
    public static AnimalKind parse(String value){
        try{return valueOf(value.toUpperCase(java.util.Locale.ROOT));}
        catch(IllegalArgumentException|NullPointerException ignored){return SHEEP;}
    }
}
