package ibuprofen.ui;

import ibuprofen.IbuprofenMod;
import ibuprofen.memory.ShoppingWishList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact translucent top-left Status HUD (toggleable from the '[' menu).
 * Keeps active skill, channels, ping/FPS, and top Wish List items visible without chat spam.
 */
public final class HudOverlayRenderer {

    private HudOverlayRenderer() {}

    public static void renderHud(GuiGraphicsExtractor gfx) {
        IbuprofenMod mod = IbuprofenMod.get();
        Minecraft mc = Minecraft.getInstance();
        if (mod == null || mc == null || mc.player == null || mc.level == null) {
            return;
        }
        if (!mod.settings().showHud || mc.gui.screen() != null) {
            return;
        }

        Font font = mc.font;
        int ping = IbuprofenMod.queryPingMs(mc, mc.player);
        int fps = mc.getFps();
        String skill = mod.activeSkillName();
        String channels = mod.inputArbiter().formatActiveSummary();
        boolean recOn = mod.debugRecorder() != null && mod.debugRecorder().isRecording();

        List<String> lines = new ArrayList<>(6);
        lines.add("Ibuprobridging  " + (ping >= 0 ? ping + "ms" : "--") + " | " + fps + "fps" + (recOn ? " [REC]" : ""));
        lines.add("Skill: " + skill);
        lines.add("Body: " + channels);

        List<ShoppingWishList.WishItem> wish = mod.matchMemory().computeWishList();
        if (!wish.isEmpty()) {
            ShoppingWishList.WishItem top = wish.get(0);
            String label = "Wish: " + top.itemId() + " (" + top.reason() + ")";
            if (label.length() > 46) {
                label = label.substring(0, 45) + "...";
            }
            lines.add(label);
        }
        lines.add("Press [ for Menu | #ibu stop");

        int pad = 6;
        int lineH = 10;
        int maxW = 0;
        for (String s : lines) {
            maxW = Math.max(maxW, font.width(s));
        }
        int x0 = 8;
        int y0 = 8;
        int w = maxW + pad * 2;
        int h = lines.size() * lineH + pad * 2 - 2;

        // Clean dark-glass card with subtle cyan accent bar
        gfx.fill(x0, y0, x0 + w, y0 + h, 0xA810141C);
        gfx.fill(x0, y0, x0 + 2, y0 + h, 0xFF38BDF8);
        gfx.outline(x0, y0, w, h, 0x5538BDF8);

        for (int i = 0; i < lines.size(); i++) {
            int color = switch (i) {
                case 0 -> 0xFF38BDF8; // cyan header
                case 1 -> !"none".equals(skill) ? 0xFF4ADE80 : 0xFFE2E8F0;
                case 2 -> 0xFFCBD5E1;
                default -> (i == lines.size() - 1) ? 0xFF94A3B8 : 0xFFFACC15;
            };
            gfx.text(font, lines.get(i), x0 + pad + 2, y0 + pad + i * lineH, color);
        }
    }
}
