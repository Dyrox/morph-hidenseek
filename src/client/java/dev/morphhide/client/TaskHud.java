package dev.morphhide.client;

import dev.morphhide.game.Food;
import dev.morphhide.network.GameView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/** Always-visible round objective under the top bar: what to eat, progress, and where the field is. */
final class TaskHud {
    private TaskHud() {}
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    /** Nearest field of this food: {dx, dz, distance} from the player, or null. */
    static double[] nearest(GameView s, Food food) {
        var p = Minecraft.getInstance().player;
        if (p == null) return null;
        double[] best = null;
        for (var farm : s.farms) {
            if (!farm.food().equals(food.name())) continue;
            double dx = farm.x() + .5 - p.getX(), dz = farm.z() + .5 - p.getZ(), d = Math.hypot(dx, dz);
            if (best == null || d < best[2]) best = new double[]{dx, dz, d};
        }
        return best;
    }

    /** Screen arrow toward a world offset, relative to where the player is looking. */
    static String arrow(double dx, double dz) {
        var p = Minecraft.getInstance().player;
        float target = (float)(Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
        float rel = Mth.wrapDegrees(target - p.getYRot());
        return ARROWS[Math.floorMod(Math.round(rel / 45f), 8)];
    }

    /** Returns the y below the drawn lines. */
    static int render(GuiGraphicsExtractor g, GameView s, int w, int y) {
        var font = Minecraft.getInstance().font;
        boolean round = s.phase.equals("HIDING") || s.phase.equals("HUNTING");
        if (!round) return y;
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.List<Integer> colors = new java.util.ArrayList<>();
        Food food = Food.parse(s.food);
        if (s.role.equals("ANIMAL") && food != null) {
            if (s.fed >= s.need) { lines.add("✔ 吃饱了！活到最后就能赢"); colors.add(0xFF8CF08C); }
            else {
                var at = nearest(s, food);
                String where = at == null ? "" : at[2] < 4 ? "  ·  就在这里，右键吃！" : "  ·  " + arrow(at[0], at[1]) + " " + Math.round(at[2]) + " 格到" + food.field;
                lines.add("任务：吃 " + s.need + " 口" + food.label + "   " + s.fed + "/" + s.need + (s.eatLeft > 0 ? "" : where));
                colors.add(food.color);
                if (s.eatLeft > 0) { lines.add("进食中… 还剩 " + String.format("%.1f", s.eatLeft / 20f) + " 秒，别动"); colors.add(0xFFFFE2A0); }
                boolean urgent = s.shrinking || (s.phase.equals("HUNTING") && s.seconds <= 60);
                if (urgent && (System.currentTimeMillis() / 400) % 2 == 0) { lines.add("⚠ 还没吃饱！时间到会饿死"); colors.add(0xFFFF6A5A); }
            }
        } else if (s.role.equals("HUNTER")) {
            lines.add("打到真动物会减速、失明、虚弱 3 秒 · 动物会去农田进食"); colors.add(0xFFE0C8A0);
        }
        if (s.shrinking) { lines.add("边界缩小中 · 范围 " + Math.round(s.borderSize) + " 格"); colors.add(0xFFFF8A70); }
        else if (s.shrinkIn >= 0 && s.shrinkIn <= 60) { lines.add("缩圈 " + s.shrinkIn + " 秒后开始"); colors.add(0xFFFFC890); }
        if (lines.isEmpty()) return y;
        int width = 0;
        for (var line : lines) width = Math.max(width, font.width(line));
        width = Math.min(w - 16, width + 16);
        g.fill((w - width) / 2, y, (w + width) / 2, y + 4 + lines.size() * 12, 0xC0101A17);
        for (int i = 0; i < lines.size(); i++) g.centeredText(font, lines.get(i), w / 2, y + 3 + i * 12, colors.get(i));
        return y + 4 + lines.size() * 12;
    }
}
