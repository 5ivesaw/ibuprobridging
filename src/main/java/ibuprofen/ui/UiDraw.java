package ibuprofen.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Clean 2D vector-style drawing helpers for MC 26.3 {@link GuiGraphicsExtractor}.
 * Provides smooth rounded rectangles, vertical gradients, soft layered drop shadows,
 * pill badges, and animated toggle switches without needing custom shaders.
 */
public final class UiDraw {

    private UiDraw() {}

    /**
     * Draws a soft multi-layer drop shadow around a rounded rectangle.
     */
    public static void drawShadow(GuiGraphicsExtractor gfx, int x, int y, int w, int h, int radius, int spread) {
        for (int i = spread; i >= 1; i--) {
            int alpha = Math.max(4, (28 * (spread - i + 1)) / (spread * 2));
            fillRoundedRect(gfx, x - i, y - i + 2, w + i * 2, h + i * 2, radius + i, (alpha << 24));
        }
    }

    /**
     * Draws a pixel-accurate rounded rectangle using horizontal scanlines for the corners.
     */
    public static void fillRoundedRect(GuiGraphicsExtractor gfx, int x, int y, int w, int h, int radius, int argb) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.min(radius, Math.min(w / 2, h / 2));
        if (r <= 0) {
            gfx.fill(x, y, x + w, y + h, argb);
            return;
        }
        // Center body band
        gfx.fill(x, y + r, x + w, y + h - r, argb);
        // Top & bottom rounded caps
        for (int dy = 0; dy < r; dy++) {
            double distY = (r - 0.5) - dy;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0.0, r * r - distY * distY)));
            gfx.fill(x + inset, y + dy, x + w - inset, y + dy + 1, argb);
            gfx.fill(x + inset, y + h - 1 - dy, x + w - inset, y + h - dy, argb);
        }
    }

    /**
     * Draws a rounded rectangle filled with a smooth vertical gradient.
     */
    public static void fillRoundedGradient(GuiGraphicsExtractor gfx, int x, int y, int w, int h, int radius, int topArgb, int bottomArgb) {
        if (w <= 0 || h <= 0) {
            return;
        }
        int r = Math.min(radius, Math.min(w / 2, h / 2));
        if (r <= 0) {
            gfx.fillGradient(x, y, x + w, y + h, topArgb, bottomArgb);
            return;
        }
        float invH = 1.0f / Math.max(1, h - 1);
        int midTop = lerpColor(topArgb, bottomArgb, r * invH);
        int midBot = lerpColor(topArgb, bottomArgb, (h - r) * invH);
        gfx.fillGradient(x, y + r, x + w, y + h - r, midTop, midBot);

        for (int dy = 0; dy < r; dy++) {
            double distY = (r - 0.5) - dy;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0.0, r * r - distY * distY)));
            int cTop = lerpColor(topArgb, bottomArgb, dy * invH);
            int cBot = lerpColor(topArgb, bottomArgb, (h - 1 - dy) * invH);
            gfx.fill(x + inset, y + dy, x + w - inset, y + dy + 1, cTop);
            gfx.fill(x + inset, y + h - 1 - dy, x + w - inset, y + h - dy, cBot);
        }
    }

    /**
     * Draws a crisp 1px rounded outline matching {@link #fillRoundedRect}.
     */
    public static void drawRoundedOutline(GuiGraphicsExtractor gfx, int x, int y, int w, int h, int radius, int argb) {
        if (w <= 2 || h <= 2) {
            return;
        }
        int r = Math.min(radius, Math.min(w / 2, h / 2));
        if (r <= 0) {
            gfx.outline(x, y, w, h, argb);
            return;
        }
        // Left & right straight edges
        gfx.fill(x, y + r, x + 1, y + h - r, argb);
        gfx.fill(x + w - 1, y + r, x + w, y + h - r, argb);
        // Top & bottom straight edges
        gfx.fill(x + r, y, x + w - r, y + 1, argb);
        gfx.fill(x + r, y + h - 1, x + w - r, y + h, argb);

        int prevInset = r;
        for (int dy = 0; dy < r; dy++) {
            double distY = (r - 0.5) - dy;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0.0, r * r - distY * distY)));
            int spanEnd = Math.max(inset + 1, prevInset);
            // Top-left & top-right arc pixels
            gfx.fill(x + inset, y + dy, x + spanEnd, y + dy + 1, argb);
            gfx.fill(x + w - spanEnd, y + dy, x + w - inset, y + dy + 1, argb);
            // Bottom-left & bottom-right arc pixels
            gfx.fill(x + inset, y + h - 1 - dy, x + spanEnd, y + h - dy, argb);
            gfx.fill(x + w - spanEnd, y + h - 1 - dy, x + w - inset, y + h - dy, argb);
            prevInset = inset;
        }
    }

    /**
     * Draws a smooth animated pill toggle switch (progress 0.0 = OFF, 1.0 = ON).
     */
    public static void drawToggleSwitch(GuiGraphicsExtractor gfx, int x, int y, float progress, int accentRgb) {
        int trackW = 30;
        int trackH = 14;
        float t = Math.max(0.0f, Math.min(1.0f, progress));
        int offTrack = 0xFF283244;
        int onTrack = withAlpha(accentRgb, 0xEE);
        int trackCol = lerpColor(offTrack, onTrack, t);
        int borderCol = lerpColor(0xFF3B475E, withAlpha(accentRgb, 0xFF), t);

        fillRoundedRect(gfx, x, y, trackW, trackH, 7, trackCol);
        drawRoundedOutline(gfx, x, y, trackW, trackH, 7, borderCol);

        int knobSize = 10;
        int knobX = x + 2 + Math.round(t * (trackW - knobSize - 4));
        int knobY = y + 2;
        fillRoundedRect(gfx, knobX, knobY, knobSize, knobSize, 5, 0xFFFFFFFF);
    }

    /**
     * Draws a sleek rounded pill badge with centered text.
     */
    public static int drawPill(GuiGraphicsExtractor gfx, Font font, int x, int y, String text, int bgArgb, int fgArgb) {
        int w = font.width(text) + 14;
        int h = 15;
        fillRoundedRect(gfx, x, y, w, h, 7, bgArgb);
        gfx.text(font, text, x + 7, y + 4, fgArgb, false);
        return w;
    }

    public static int pillWidth(Font font, String text) {
        return font.width(text) + 14;
    }

    public static int withAlpha(int rgb, int alpha) {
        return ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
    }

    public static int lerpColor(int c1, int c2, float t) {
        float cl = Math.max(0.0f, Math.min(1.0f, t));
        int a1 = (c1 >>> 24) & 0xFF, r1 = (c1 >>> 16) & 0xFF, g1 = (c1 >>> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >>> 24) & 0xFF, r2 = (c2 >>> 16) & 0xFF, g2 = (c2 >>> 8) & 0xFF, b2 = c2 & 0xFF;
        int a = Math.round(a1 + (a2 - a1) * cl);
        int r = Math.round(r1 + (r2 - r1) * cl);
        int g = Math.round(g1 + (g2 - g1) * cl);
        int b = Math.round(b1 + (b2 - b1) * cl);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }
}
