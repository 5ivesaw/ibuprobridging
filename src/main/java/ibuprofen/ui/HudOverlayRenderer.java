package ibuprofen.ui;

import ibuprofen.IbuprofenMod;
import ibuprofen.memory.ShoppingWishList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact, sleek rounded-glass Status HUD card (draggable via the HUD Editor or '[' menu).
 * Position persists automatically in {@link IbuSettings}.
 */
public final class HudOverlayRenderer {

    public record HudBounds(int x, int y, int w, int h) {}

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
        renderHudCard(gfx, mod, mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(), false);
    }

    /**
     * Renders the Status HUD card at its configured (clamped) coordinates and returns its screen bounds.
     * Used both during normal gameplay and inside {@link HudEditorScreen} / {@link IbuMenuScreen}.
     */
    public static HudBounds renderHudCard(
            GuiGraphicsExtractor gfx,
            IbuprofenMod mod,
            Minecraft mc,
            int screenW,
            int screenH,
            boolean highlightEditable) {

        Font font = mc.font;
        IbuSettings cfg = mod.settings();
        int ping = IbuprofenMod.queryPingMs(mc, mc.player);
        int fps = mc.getFps();
        String skill = mod.activeSkillName();
        String channels = mod.inputArbiter().formatActiveSummary();
        boolean recOn = mod.debugRecorder() != null && mod.debugRecorder().isRecording();

        String header = "IBUPROBRIDGING";
        String stats = (ping >= 0 ? ping + "ms" : "--") + "  " + fps + "fps" + (recOn ? "  REC" : "");

        List<String> bodyLines = new ArrayList<>(4);
        bodyLines.add("Skill: " + skill);
        bodyLines.add("Body: " + channels);

        List<ShoppingWishList.WishItem> wish = mod.matchMemory().computeWishList();
        if (!wish.isEmpty()) {
            ShoppingWishList.WishItem top = wish.get(0);
            String label = "Wish: " + top.itemId() + " (" + top.reason() + ")";
            if (label.length() > 48) {
                label = label.substring(0, 47) + "...";
            }
            bodyLines.add(label);
        }

        int padX = 9;
        int padY = 7;
        int lineH = 11;
        int headerW = font.width(header) + 14 + font.width(stats);
        int maxW = headerW;
        for (String s : bodyLines) {
            maxW = Math.max(maxW, font.width(s));
        }
        int w = maxW + padX * 2;
        int h = padY * 2 + 13 + bodyLines.size() * lineH;

        int x = Math.max(4, Math.min(Math.max(4, screenW - w - 4), cfg.hudX));
        int y = Math.max(4, Math.min(Math.max(4, screenH - h - 4), cfg.hudY));

        // Soft shadow + sleek rounded dark-glass card
        UiDraw.drawShadow(gfx, x, y, w, h, 7, 3);
        UiDraw.fillRoundedGradient(gfx, x, y, w, h, 7, 0xCC0F1524, 0xD60A0E18);
        int borderCol = highlightEditable ? 0xDD38BDF8 : 0x33FFFFFF;
        UiDraw.drawRoundedOutline(gfx, x, y, w, h, 7, borderCol);

        // Header row with subtle status dot
        int dotCol = !"none".equals(skill) ? 0xFF4ADE80 : 0xFF38BDF8;
        UiDraw.fillRoundedRect(gfx, x + padX, y + padY + 1, 5, 5, 2, dotCol);
        gfx.text(font, header, x + padX + 9, y + padY, 0xFFF1F5F9, false);
        gfx.text(font, stats, x + w - padX - font.width(stats), y + padY, recOn ? 0xFFF87171 : 0xFF94A3B8, false);

        // Subtle divider
        gfx.fill(x + padX, y + padY + 10, x + w - padX, y + padY + 11, 0x1FFFFFFF);

        int textY = y + padY + 14;
        for (int i = 0; i < bodyLines.size(); i++) {
            int color = switch (i) {
                case 0 -> !"none".equals(skill) ? 0xFF4ADE80 : 0xFFE2E8F0;
                case 1 -> 0xFF94A3B8;
                default -> 0xFFFACC15;
            };
            gfx.text(font, bodyLines.get(i), x + padX, textY + i * lineH, color, false);
        }

        if (highlightEditable) {
            // Corner drag handles
            int hs = 4;
            gfx.fill(x - 1, y - 1, x - 1 + hs, y - 1 + hs, 0xFF38BDF8);
            gfx.fill(x + w - hs + 1, y - 1, x + w + 1, y - 1 + hs, 0xFF38BDF8);
            gfx.fill(x - 1, y + h - hs + 1, x - 1 + hs, y + h + 1, 0xFF38BDF8);
            gfx.fill(x + w - hs + 1, y + h - hs + 1, x + w + 1, y + h + 1, 0xFF38BDF8);
        }

        return new HudBounds(x, y, w, h);
    }
}
