package dev.morphhide.client;
import dev.morphhide.network.GameView;
public final class LobbyGuidance {
    private LobbyGuidance() {}
    public static String status(GameView s){
        long ready=s.members.stream().filter(GameView.Member::ready).count();
        return "当前 "+s.players+" 人 · 已准备 "+ready+" / "+s.players+" · 猎人 "+(s.hunterSetting==0?"自动":s.hunterSetting);
    }
    public static String nextStep(GameView s){return "先选择动物，再按 "+(MorphHideClient.readyKey==null?"R":MorphHideClient.readyKey.getTranslatedKeyMessage().getString())+" 准备 · 全员准备后自动开始，房主也可在控制墙直接开始";}
}
