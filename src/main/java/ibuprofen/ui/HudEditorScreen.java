package ibuprofen.ui;

import com.mojang.blaze3d.platform.InputConstants;
import ibuprofen.IbuprofenMod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Lunar-style HUD Layout Editor: drag the Status HUD card anywhere on screen with center/edge snapping.
 * Position is automatically saved to {@code config/ibuprobridging.properties}.
 */
public final class HudEditorScreen extends Screen {

    private final Screen parent;
    private boolean dragging = false;
    private int dragOffsetX = 0;
    private int dragOffsetY = 0;
    private HudOverlayRenderer.HudBounds lastBounds = new HudOverlayRenderer.HudBounds(10, 10, 160, 56);

    public HudEditorScreen(Screen parent) {
        super(Component.literal("Edit HUD Layout"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod == null || this.minecraft == null) {
            return;
        }

        // Light translucent dim + subtle center snap crosshairs while dragging
        gfx.fill(0, 0, this.width, this.height, 0x4405080F);
        if (dragging) {
            int cx = this.width / 2;
            int cy = this.height / 2;
            gfx.fill(cx, 0, cx + 1, this.height, 0x3338BDF8);
            gfx.fill(0, cy, this.width, cy + 1, 0x3338BDF8);
        }

        // Render live HUD card with drag handles
        this.lastBounds = HudOverlayRenderer.renderHudCard(gfx, mod, this.minecraft, this.width, this.height, true);

        // Floating bottom toolbar: coordinates + Reset + Back/Done
        int barW = 286;
        int barH = 34;
        int bx = (this.width - barW) / 2;
        int by = this.height - barH - 18;
        UiDraw.drawShadow(gfx, bx, by, barW, barH, 9, 4);
        UiDraw.fillRoundedRect(gfx, bx, by, barW, barH, 9, 0xEE0D1320);
        UiDraw.drawRoundedOutline(gfx, bx, by, barW, barH, 9, 0x4438BDF8);

        String coords = "HUD (" + lastBounds.x() + ", " + lastBounds.y() + ")";
        gfx.text(this.font, coords, bx + 12, by + 13, 0xFFCBD5E1, false);

        int resetX = bx + barW - 134;
        int resetY = by + 7;
        boolean resetHover = UiDraw.inside(mouseX, mouseY, resetX, resetY, 60, 20);
        UiDraw.fillRoundedRect(gfx, resetX, resetY, 60, 20, 6, resetHover ? 0xFF28354C : 0xFF1A2233);
        gfx.centeredText(this.font, "Reset", resetX + 30, resetY + 6, 0xFFE2E8F0);

        int doneX = bx + barW - 68;
        int doneY = by + 7;
        boolean doneHover = UiDraw.inside(mouseX, mouseY, doneX, doneY, 58, 20);
        UiDraw.fillRoundedRect(gfx, doneX, doneY, 58, 20, 6, doneHover ? 0xFF0EA5E9 : 0xFF0284C7);
        gfx.centeredText(this.font, "Done", doneX + 29, doneY + 6, 0xFFFFFFFF);

        gfx.centeredText(this.font, "Drag the HUD card anywhere on screen", this.width / 2, by - 14, 0xFFE2E8F0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event == null || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(event, doubleClick);
        }
        double mx = event.x();
        double my = event.y();
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod == null) {
            return false;
        }

        // Check HUD card grab
        if (UiDraw.inside(mx, my, lastBounds.x() - 4, lastBounds.y() - 4, lastBounds.w() + 8, lastBounds.h() + 8)) {
            dragging = true;
            dragOffsetX = (int) Math.round(mx) - lastBounds.x();
            dragOffsetY = (int) Math.round(my) - lastBounds.y();
            return true;
        }

        int barW = 286;
        int barH = 34;
        int bx = (this.width - barW) / 2;
        int by = this.height - barH - 18;

        // Reset button
        if (UiDraw.inside(mx, my, bx + barW - 134, by + 7, 60, 20)) {
            mod.settings().hudX = 10;
            mod.settings().hudY = 10;
            mod.settings().save();
            return true;
        }

        // Done button
        if (UiDraw.inside(mx, my, bx + barW - 68, by + 7, 58, 20)) {
            returnToParent();
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging && event != null) {
            IbuprofenMod mod = IbuprofenMod.get();
            if (mod != null) {
                int nx = (int) Math.round(event.x()) - dragOffsetX;
                int ny = (int) Math.round(event.y()) - dragOffsetY;

                // Soft magnetic snap to screen center and 10px margins
                int centerTargetX = (this.width - lastBounds.w()) / 2;
                int centerTargetY = (this.height - lastBounds.h()) / 2;
                if (Math.abs(nx - centerTargetX) <= 6) nx = centerTargetX;
                if (Math.abs(ny - centerTargetY) <= 6) ny = centerTargetY;
                if (Math.abs(nx - 10) <= 5) nx = 10;
                if (Math.abs(ny - 10) <= 5) ny = 10;

                mod.settings().hudX = Math.max(4, Math.min(Math.max(4, this.width - lastBounds.w() - 4), nx));
                mod.settings().hudY = Math.max(4, Math.min(Math.max(4, this.height - lastBounds.h() - 4), ny));
                return true;
            }
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging) {
            dragging = false;
            IbuprofenMod mod = IbuprofenMod.get();
            if (mod != null) {
                mod.settings().save();
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event != null && (event.key() == InputConstants.KEY_LBRACKET || event.key() == InputConstants.KEY_ESCAPE)) {
            returnToParent();
            return true;
        }
        return super.keyPressed(event);
    }

    private void returnToParent() {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod != null) {
            mod.settings().save();
        }
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }
}
