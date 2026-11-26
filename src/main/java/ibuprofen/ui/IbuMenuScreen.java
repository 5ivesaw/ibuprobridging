package ibuprofen.ui;

import com.mojang.blaze3d.platform.InputConstants;
import ibuprofen.IbuprofenMod;
import ibuprofen.command.IbuCommand;
import ibuprofen.memory.ShoppingWishList;
import ibuprofen.memory.TeamDossier;
import ibuprofen.motor.HumanProfile;
import ibuprofen.skills.nav.WalkSkill;
import ibuprofen.telemetry.DebugRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Custom-rendered dark-glass control panel toggled with '[' (MC 26.3 SDL3 KEY_LBRACKET).
 * Zero vanilla Minecraft buttons: tabbed sidebar, 2-column module cards, pill toggle switches,
 * live status pills, and 1-click access to every skill, visual overlay, and future Phase 2-6 module.
 */
public final class IbuMenuScreen extends Screen {

    private enum Tab {
        NAVIGATION("Navigation", 0xFF38BDF8),
        VISUALS("Visuals", 0xFF34D399),
        BRAIN("Brain & Memory", 0xFFA78BFA),
        RECORDER("Recorder", 0xFFFBBF24);

        final String label;
        final int accent;

        Tab(String label, int accent) {
            this.label = label;
            this.accent = accent;
        }
    }

    private record Card(
            String title,
            String subtitle,
            boolean isToggle,
            boolean enabled,
            String badge,
            int accent,
            Runnable onClick
    ) {}

    private static final int PANEL_W = 528;
    private static final int PANEL_H = 304;
    private static final int HEADER_H = 38;
    private static final int SIDEBAR_W = 132;
    private static final int FOOTER_H = 22;

    // Palette (deep obsidian glass + cyan/emerald accents)
    private static final int BG_BACKDROP = 0x8205080F;
    private static final int BG_PANEL = 0xEB0B101B;
    private static final int BG_HEADER = 0xF50F172A;
    private static final int BG_SIDEBAR = 0xE60D1422;
    private static final int BG_CARD = 0xE6131C2E;
    private static final int BG_CARD_HOVER = 0xF51B2840;
    private static final int BG_INFO = 0xD90E1626;
    private static final int BORDER_SUBTLE = 0xFF1E293B;
    private static final int BORDER_GLOW = 0x8838BDF8;
    private static final int TEXT_PRIMARY = 0xFFF1F5F9;
    private static final int TEXT_MUTED = 0xFF94A3B8;
    private static final int TEXT_DIM = 0xFF64748B;

    private static Tab activeTab = Tab.NAVIGATION;

    public IbuMenuScreen() {
        super(Component.literal("Ibuprobridging"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        IbuprofenMod mod = IbuprofenMod.get();
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        int right = left + PANEL_W;
        int bottom = top + PANEL_H;

        // 1. Soft full-screen dim backdrop
        gfx.fill(0, 0, this.width, this.height, BG_BACKDROP);

        // 2. Main floating dark-glass container + subtle outer glow
        gfx.fill(left - 1, top - 1, right + 1, bottom + 1, 0x44000000);
        gfx.fill(left, top, right, bottom, BG_PANEL);
        gfx.outline(left, top, PANEL_W, PANEL_H, BORDER_SUBTLE);
        gfx.fill(left + 1, top, right - 1, top + 2, activeTab.accent);

        // 3. Top Header Bar
        gfx.fill(left + 1, top + 2, right - 1, top + HEADER_H, BG_HEADER);
        gfx.fill(left + 1, top + HEADER_H, right - 1, top + HEADER_H + 1, BORDER_SUBTLE);

        // Brand mark + Phase badge
        gfx.fill(left + 14, top + 14, left + 22, top + 22, 0xFF38BDF8);
        gfx.fill(left + 16, top + 16, left + 20, top + 20, 0xFFE0F2FE);
        gfx.text(this.font, "IBUPROBRIDGING", left + 28, top + 15, TEXT_PRIMARY);

        int badgeX = left + 28 + this.font.width("IBUPROBRIDGING") + 8;
        drawPill(gfx, badgeX, top + 12, "PHASE 1", 0x3338BDF8, 0xFF38BDF8);

        // Close 'x' button on far right of header
        int closeX = right - 28;
        int closeY = top + 10;
        boolean closeHover = inside(mouseX, mouseY, closeX, closeY, 18, 18);
        gfx.fill(closeX, closeY, closeX + 18, closeY + 18, closeHover ? 0xCCEF4444 : 0xFF1E293B);
        gfx.centeredText(this.font, "x", closeX + 9, closeY + 5, closeHover ? 0xFFFFFFFF : TEXT_MUTED);

        // Live telemetry pills (ping, fps, REC) to the left of close button
        if (mod != null && this.minecraft != null) {
            int ping = IbuprofenMod.queryPingMs(this.minecraft, this.minecraft.player);
            int fps = this.minecraft.getFps();
            boolean rec = mod.debugRecorder() != null && mod.debugRecorder().isRecording();

            int cursorX = closeX - 8;
            if (rec) {
                int w = pillWidth("REC");
                cursorX -= w;
                drawPill(gfx, cursorX, top + 12, "REC", 0x33EF4444, 0xFFF87171);
                cursorX -= 6;
            }
            String fpsTxt = fps + " fps";
            int fpsW = pillWidth(fpsTxt);
            cursorX -= fpsW;
            drawPill(gfx, cursorX, top + 12, fpsTxt, 0xFF162033, 0xFFCBD5E1);
            cursorX -= 6;

            String pingTxt = (ping >= 0 ? ping + " ms" : "-- ms");
            int pingColor = (ping < 0) ? TEXT_MUTED : (ping < 120 ? 0xFF4ADE80 : (ping < 250 ? 0xFFFACC15 : 0xFFF87171));
            int pingW = pillWidth(pingTxt);
            cursorX -= pingW;
            drawPill(gfx, cursorX, top + 12, pingTxt, 0xFF162033, pingColor);
        }

        // 4. Left Sidebar
        int bodyTop = top + HEADER_H + 1;
        int bodyBottom = bottom - FOOTER_H;
        gfx.fill(left + 1, bodyTop, left + SIDEBAR_W, bodyBottom, BG_SIDEBAR);
        gfx.fill(left + SIDEBAR_W, bodyTop, left + SIDEBAR_W + 1, bodyBottom, BORDER_SUBTLE);

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab tab = tabs[i];
            int ty = bodyTop + 10 + i * 32;
            int tx = left + 8;
            int tw = SIDEBAR_W - 16;
            int th = 26;
            boolean selected = (tab == activeTab);
            boolean hover = inside(mouseX, mouseY, tx, ty, tw, th);

            if (selected) {
                gfx.fill(tx, ty, tx + tw, ty + th, 0xFF182438);
                gfx.fill(tx, ty + 4, tx + 3, ty + th - 4, tab.accent);
                gfx.outline(tx, ty, tw, th, 0x4438BDF8);
            } else if (hover) {
                gfx.fill(tx, ty, tx + tw, ty + th, 0xAA162033);
            }
            gfx.fill(tx + 9, ty + 10, tx + 14, ty + 15, selected ? tab.accent : TEXT_DIM);
            gfx.text(this.font, tab.label, tx + 20, ty + 9, selected ? TEXT_PRIMARY : TEXT_MUTED);
        }

        // Always-accessible emergency STOP ALL button at bottom of sidebar
        int stopX = left + 8;
        int stopY = bodyBottom - 34;
        int stopW = SIDEBAR_W - 16;
        int stopH = 24;
        boolean stopHover = inside(mouseX, mouseY, stopX, stopY, stopW, stopH);
        boolean hasSkill = mod != null && mod.activeSkill() != null;
        int stopBg = stopHover ? 0xE6DC2626 : (hasSkill ? 0xCC991B1B : 0x667F1D1D);
        gfx.fill(stopX, stopY, stopX + stopW, stopY + stopH, stopBg);
        gfx.outline(stopX, stopY, stopW, stopH, stopHover ? 0xFFFCA5A5 : 0xAAEF4444);
        gfx.centeredText(this.font, "STOP ALL", stopX + stopW / 2, stopY + 8, 0xFFFFFFFF);

        // 5. Right Content Area (2-column module cards + live info box)
        int contentLeft = left + SIDEBAR_W + 14;
        int contentW = PANEL_W - SIDEBAR_W - 28;
        gfx.text(this.font, activeTab.label.toUpperCase(), contentLeft, bodyTop + 10, activeTab.accent);

        if (mod != null) {
            List<Card> cards = buildCardsFor(activeTab, mod);
            int cardGap = 10;
            int cardW = (contentW - cardGap) / 2;
            int cardH = 54;
            int gridTop = bodyTop + 26;

            for (int i = 0; i < cards.size(); i++) {
                Card c = cards.get(i);
                int col = i % 2;
                int row = i / 2;
                int cx = contentLeft + col * (cardW + cardGap);
                int cy = gridTop + row * (cardH + cardGap);
                drawCard(gfx, c, cx, cy, cardW, cardH, inside(mouseX, mouseY, cx, cy, cardW, cardH));
            }

            // Extra live info card on Brain & Recorder tabs
            int rowsUsed = (cards.size() + 1) / 2;
            int infoY = gridTop + rowsUsed * (cardH + cardGap);
            int infoH = bodyBottom - infoY - 10;
            if (infoH >= 44) {
                if (activeTab == Tab.BRAIN) {
                    drawBrainLiveCard(gfx, mod, contentLeft, infoY, contentW, infoH);
                } else if (activeTab == Tab.RECORDER) {
                    drawRecorderLiveCard(gfx, mod, contentLeft, infoY, contentW, infoH);
                }
            }
        }

        // 6. Footer Bar
        gfx.fill(left + 1, bodyBottom, right - 1, bodyBottom + 1, BORDER_SUBTLE);
        gfx.fill(left + 1, bodyBottom + 1, right - 1, bottom - 1, BG_HEADER);
        String activeStr = mod != null ? mod.activeSkillName() : "none";
        String bodyStr = mod != null ? mod.inputArbiter().formatActiveSummary() : "idle";
        gfx.text(this.font, "Skill: " + activeStr + "   |   Body: " + bodyStr, left + 12, bodyBottom + 7, TEXT_MUTED);
        String hint = "[ or ESC to close";
        gfx.text(this.font, hint, right - 12 - this.font.width(hint), bodyBottom + 7, TEXT_DIM);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event == null || event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        double mx = event.x();
        double my = event.y();
        IbuprofenMod mod = IbuprofenMod.get();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        int right = left + PANEL_W;
        int bottom = top + PANEL_H;
        int bodyTop = top + HEADER_H + 1;
        int bodyBottom = bottom - FOOTER_H;

        // Close button
        if (inside(mx, my, right - 28, top + 10, 18, 18)) {
            closeMenu();
            return true;
        }

        // Sidebar tabs
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int ty = bodyTop + 10 + i * 32;
            if (inside(mx, my, left + 8, ty, SIDEBAR_W - 16, 26)) {
                activeTab = tabs[i];
                return true;
            }
        }

        // Emergency STOP ALL
        if (mod != null && inside(mx, my, left + 8, bodyBottom - 34, SIDEBAR_W - 16, 24)) {
            IbuCommand.tryHandle(mod, "#ibu stop");
            return true;
        }

        // Module cards in right content grid
        if (mod != null) {
            int contentLeft = left + SIDEBAR_W + 14;
            int contentW = PANEL_W - SIDEBAR_W - 28;
            int cardGap = 10;
            int cardW = (contentW - cardGap) / 2;
            int cardH = 54;
            int gridTop = bodyTop + 26;

            List<Card> cards = buildCardsFor(activeTab, mod);
            for (int i = 0; i < cards.size(); i++) {
                int col = i % 2;
                int row = i / 2;
                int cx = contentLeft + col * (cardW + cardGap);
                int cy = gridTop + row * (cardH + cardGap);
                if (inside(mx, my, cx, cy, cardW, cardH)) {
                    cards.get(i).onClick().run();
                    return true;
                }
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event != null && event.key() == InputConstants.KEY_LBRACKET) {
            closeMenu();
            return true;
        }
        return super.keyPressed(event);
    }

    private List<Card> buildCardsFor(Tab tab, IbuprofenMod mod) {
        IbuSettings cfg = mod.settings();
        List<Card> cards = new ArrayList<>();
        switch (tab) {
            case NAVIGATION -> {
                cards.add(new Card("Walk -> Crosshair", "Walk to looked-at block", false, false, "RUN", tab.accent, () -> {
                    walkToCrosshair(mod);
                    closeMenu();
                }));
                cards.add(new Card("Walk -> Shop", "Stand cleanly in front of NPC", false, false, "RUN", tab.accent, () -> {
                    IbuCommand.tryHandle(mod, "#ibu test walk shop");
                    closeMenu();
                }));
                cards.add(new Card("Walk -> Generator", "Snap to nearest dia/em gen", false, false, "RUN", tab.accent, () -> {
                    IbuCommand.tryHandle(mod, "#ibu test walk gen");
                    closeMenu();
                }));
                cards.add(new Card("Walk -> Nearest Bed", "Navigate to closest live bed", false, false, "RUN", tab.accent, () -> {
                    IbuCommand.tryHandle(mod, "#ibu test walk bed");
                    closeMenu();
                }));
            }
            case VISUALS -> {
                cards.add(new Card("Path Line", "3D cyan route & jump arcs", true, cfg.showPath, null, tab.accent,
                        () -> cfg.showPath = !cfg.showPath));
                cards.add(new Card("Goal Box", "Gold wireframe at target", true, cfg.showGoalBox, null, tab.accent,
                        () -> cfg.showGoalBox = !cfg.showGoalBox));
                cards.add(new Card("Bed Markers", "Team-colored bed outlines", true, cfg.showBedMarkers, null, tab.accent,
                        () -> cfg.showBedMarkers = !cfg.showBedMarkers));
                cards.add(new Card("Shop Markers", "Item & upgrade NPC boxes", true, cfg.showShopMarkers, null, tab.accent,
                        () -> cfg.showShopMarkers = !cfg.showShopMarkers));
                cards.add(new Card("Status HUD", "Compact top-left live panel", true, cfg.showHud, null, tab.accent,
                        () -> cfg.showHud = !cfg.showHud));
            }
            case BRAIN -> {
                String prof = capitalize(mod.inputArbiter().profile().name());
                cards.add(new Card("Motor Profile", "Human aim & click style", false, true, prof.toUpperCase(), tab.accent,
                        () -> cycleProfile(mod)));
                cards.add(new Card("Dump MatchMemory", "Print beds & wish list to chat", false, false, "CHAT", tab.accent, () -> {
                    IbuCommand.tryHandle(mod, "#ibu mem");
                    closeMenu();
                }));
                cards.add(new Card("Dump Status", "Print map, ping & channels", false, false, "CHAT", tab.accent, () -> {
                    IbuCommand.tryHandle(mod, "#ibu status");
                    closeMenu();
                }));
            }
            case RECORDER -> {
                boolean autoRec = mod.worldState().isAutoRecord();
                boolean recOn = mod.debugRecorder() != null && mod.debugRecorder().isRecording();
                cards.add(new Card("Auto-Record", "Rotate .jsonl.gz every match", true, autoRec, null, tab.accent,
                        () -> mod.worldState().setAutoRecord(!mod.worldState().isAutoRecord())));
                cards.add(new Card("Debug Recording", recOn ? "Stop & flush current log" : "Start lightweight log now",
                        true, recOn, null, 0xFFF87171,
                        () -> IbuCommand.tryHandle(mod, recOn ? "#ibu rec stop" : "#ibu rec start")));
            }
        }
        return cards;
    }

    private void drawCard(GuiGraphicsExtractor gfx, Card c, int x, int y, int w, int h, boolean hover) {
        gfx.fill(x, y, x + w, y + h, hover ? BG_CARD_HOVER : BG_CARD);
        int border = hover ? BORDER_GLOW : (c.isToggle() && c.enabled() ? withAlpha(c.accent(), 0x88) : BORDER_SUBTLE);
        gfx.outline(x, y, w, h, border);

        if (c.isToggle() && c.enabled()) {
            gfx.fill(x + 1, y + 6, x + 4, y + h - 6, c.accent());
        } else if (hover) {
            gfx.fill(x + 1, y + 10, x + 3, y + h - 10, c.accent());
        }

        gfx.text(this.font, c.title(), x + 11, y + 12, TEXT_PRIMARY);
        String sub = trimToWidth(c.subtitle(), w - 22);
        gfx.text(this.font, sub, x + 11, y + 32, TEXT_MUTED);

        if (c.isToggle()) {
            drawTogglePill(gfx, x + w - 40, y + 10, c.enabled(), c.accent());
        } else if (c.badge() != null) {
            int bw = pillWidth(c.badge());
            int bg = hover ? withAlpha(c.accent(), 0x55) : 0xFF1E293B;
            int fg = hover ? 0xFFFFFFFF : c.accent();
            drawPill(gfx, x + w - bw - 10, y + 9, c.badge(), bg, fg);
        }
    }

    private void drawTogglePill(GuiGraphicsExtractor gfx, int x, int y, boolean on, int accent) {
        int trackW = 28;
        int trackH = 12;
        int trackBg = on ? withAlpha(accent, 0xCC) : 0xFF334155;
        gfx.fill(x, y, x + trackW, y + trackH, trackBg);
        gfx.outline(x, y, trackW, trackH, on ? accent : 0xFF475569);
        int knobX = on ? (x + trackW - 10) : (x + 2);
        gfx.fill(knobX, y + 2, knobX + 8, y + trackH - 2, 0xFFFFFFFF);
    }

    private void drawBrainLiveCard(GuiGraphicsExtractor gfx, IbuprofenMod mod, int x, int y, int w, int h) {
        gfx.fill(x, y, x + w, y + h, BG_INFO);
        gfx.outline(x, y, w, h, BORDER_SUBTLE);
        gfx.text(this.font, "LIVE MATCH MEMORY", x + 10, y + 8, 0xFFA78BFA);

        Map<?, TeamDossier> teams = mod.matchMemory().getAllTeamDossiers();
        List<ShoppingWishList.WishItem> wish = mod.matchMemory().computeWishList();

        String bedsLine = "Beds tracked: " + teams.size()
                + "   |   Enemies tracked: " + mod.matchMemory().getAllEnemyDossiers().size();
        gfx.text(this.font, trimToWidth(bedsLine, w - 20), x + 10, y + 22, TEXT_PRIMARY);

        String wishLine = wish.isEmpty()
                ? "Wish List: empty (no counter-items needed yet)"
                : "Top Wish: [" + wish.get(0).priority() + "] " + wish.get(0).itemId() + " - " + wish.get(0).reason();
        gfx.text(this.font, trimToWidth(wishLine, w - 20), x + 10, y + 35, 0xFFFACC15);
    }

    private void drawRecorderLiveCard(GuiGraphicsExtractor gfx, IbuprofenMod mod, int x, int y, int w, int h) {
        gfx.fill(x, y, x + w, y + h, BG_INFO);
        gfx.outline(x, y, w, h, BORDER_SUBTLE);
        gfx.text(this.font, "TELEMETRY STATUS", x + 10, y + 8, 0xFFFBBF24);

        DebugRecorder rec = mod.debugRecorder();
        if (rec != null && rec.isRecording()) {
            String file = rec.currentFile() != null ? rec.currentFile().getFileName().toString() : "active";
            gfx.text(this.font, trimToWidth("File: " + file, w - 20), x + 10, y + 22, TEXT_PRIMARY);
            long kb = rec.uncompressedBytes() / 1024L;
            gfx.text(this.font, "Events: " + rec.lineCount() + "   |   Raw: " + kb + " KB (gzipped on disk)",
                    x + 10, y + 35, 0xFF4ADE80);
        } else {
            gfx.text(this.font, "Recorder is idle (zero disk/CPU overhead).", x + 10, y + 22, TEXT_MUTED);
            gfx.text(this.font, "Auto-record starts automatically when a BedWars match begins.",
                    x + 10, y + 35, TEXT_DIM);
        }
    }

    private int pillWidth(String text) {
        return this.font.width(text) + 12;
    }

    private void drawPill(GuiGraphicsExtractor gfx, int x, int y, String text, int bg, int fg) {
        int w = pillWidth(text);
        gfx.fill(x, y, x + w, y + 14, bg);
        gfx.text(this.font, text, x + 6, y + 3, fg);
    }

    private String trimToWidth(String text, int maxPx) {
        if (text == null || this.font.width(text) <= maxPx) {
            return text != null ? text : "";
        }
        String ell = "...";
        int cut = text.length();
        while (cut > 0 && this.font.width(text.substring(0, cut) + ell) > maxPx) {
            cut--;
        }
        return text.substring(0, cut) + ell;
    }

    private void closeMenu() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(null);
        }
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static int withAlpha(int rgb, int alpha) {
        return ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
    }

    private static void walkToCrosshair(IbuprofenMod mod) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos();
            mod.startSkill(new WalkSkill(pos.getX(), pos.getY() + 1, pos.getZ(), 0.9,
                    pos.getX() + "," + (pos.getY() + 1) + "," + pos.getZ()));
            IbuCommand.say("Walking to crosshair block (" + pos.getX() + ", " + (pos.getY() + 1) + ", " + pos.getZ() + ")...");
        } else {
            IbuCommand.say("Look at a block first, then click Walk -> Crosshair.");
        }
    }

    private static void cycleProfile(IbuprofenMod mod) {
        String cur = mod.inputArbiter().profile().name();
        HumanProfile next = switch (cur) {
            case "casual" -> HumanProfile.average();
            case "average" -> HumanProfile.sweaty();
            default -> HumanProfile.casual();
        };
        mod.inputArbiter().setProfile(next);
        IbuCommand.say("Human motor profile -> " + next.name());
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
