package dev.morphhide.client;

import dev.morphhide.game.AnimalKind;
import dev.morphhide.network.GameView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Left-side list of the animal team: who is still alive and, for the team itself, form and feeding progress. */
public final class TeamHud {
    private TeamHud() {}
    public static final int MATE_COLOR = 0x6CF08C;
    private static final int MAX_ROWS = 12, WIDTH = 150;

    static void render(GuiGraphicsExtractor g, GameView s, int y) {
        if (s.roster.isEmpty()) return;
        var mc = Minecraft.getInstance();
        String me = mc.player == null ? "" : mc.player.getUUID().toString();
        long alive = s.roster.stream().filter(m -> m.status().equals("ALIVE")).count();
        int rows = Math.min(MAX_ROWS, s.roster.size()), x = 8;
        g.fill(x, y, x + WIDTH, y + 17 + rows * 11, 0xBB10191C);
        g.text(mc.font, "动物队 · 存活 " + alive + "/" + s.roster.size(), x + 6, y + 4, 0xFFE6C99D, true);
        int line = y + 16;
        for (int i = 0; i < rows; i++) {
            var mate = s.roster.get(i);
            boolean live = mate.status().equals("ALIVE");
            String detail = mate.status().equals("LEFT") ? "退出" : !live ? "出局"
                : mate.animal().isEmpty() ? "" : AnimalKind.parse(mate.animal()).label + " " + (mate.fed() >= s.need ? "✔" : mate.fed() + "/" + s.need);
            int detailWidth = mc.font.width(detail);
            String name = (live ? "● " : "✖ ") + mate.name();
            int color = !live ? 0xFF8A8A8A : mate.uuid().equals(me) ? 0xFFFFFFFF : 0xFF000000 | MATE_COLOR;
            g.text(mc.font, mc.font.plainSubstrByWidth(name, WIDTH - 14 - detailWidth - 4), x + 6, line, color, true);
            if (!detail.isEmpty()) g.text(mc.font, detail, x + WIDTH - 6 - detailWidth, line, !live ? 0xFF8A8A8A : mate.fed() >= s.need ? 0xFF8CF08C : 0xFFE0C8A0, true);
            line += 11;
        }
    }
}
