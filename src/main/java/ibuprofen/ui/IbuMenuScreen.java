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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Modern frosted-glass ClickGUI toggled with '[' (MC 26.3 SDL3 KEY_LBRACKET).
 * Features native Gaussian background blur, smooth rounded cards, animated pill switches,
 * live status pills, and 1-click HUD Layout Editor.
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
            String id,
            String title,
            String subtitle,
            boolean isToggle,
            boolean enabled,
            String badge,
            int accent,
            Runnable onClick
    ) {}

    private static final int PANEL_W = 540;
    private static final int PANEL_H = 316;
    private static final int HEADER_H = 42;
    private static final int SIDEBAR_W = 138;
    private static final int FOOTER_H = 24;

    private static final int TEXT_PRIMARY = 0xFFF8FAFC;
    private static final int TEXT_MUTED = 0xFF94A3B8;
    private static final int TEXT_DIM = 0xFF64748B;

    private static Tab activeTab = Tab.NAVIGATION;

    private final Map<String, Float> toggleAnim = new HashMap<>();
    private long lastFrameNanos = 0L;

    public IbuMenuScreen() {
        super(Component.literal("Ibuprobridging"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 0.016f : Math.min(0.05f, (now - lastFrameNanos) / 1_000_000_000.0f);
        lastFrameNanos = now;

        IbuprofenMod mod = IbuprofenMod.get();
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        int right = left + PANEL_W;
        int bottom = top + PANEL_H;

        // 1. Subtle dark vignette backdrop (MC 26.3 already blurs screen stratum once per frame)
        gfx.fill(0, 0, this.width, this.height, 0x6E04070E);

        // 2. Floating rounded dark-glass window + soft layered shadow
        UiDraw.drawShadow(gfx, left, top, PANEL_W, PANEL_H, 10, 7);
        UiDraw.fillRoundedGradient(gfx, left, top, PANEL_W, PANEL_H, 10, 0xEC101624, 0xF20A0E18);
        UiDraw.drawRoundedOutline(gfx, left, top, PANEL_W, PANEL_H, 10, 0x2EFFFFFF);

        // 3. Header Bar
        gfx.fill(left + 10, top + HEADER_H, right - 10, top + HEADER_H + 1, 0x1AFFFFFF);

        // Glowing brand pill icon + crisp title
        UiDraw.fillRoundedRect(gfx, left + 16, top + 14, 14, 14, 4, UiDraw.withAlpha(activeTab.accent, 0x33));
        UiDraw.fillRoundedRect(gfx, left + 19, top + 17, 8, 8, 3, activeTab.accent);
        gfx.text(this.font, "IBUPROBRIDGING", left + 36, top + 17, TEXT_PRIMARY, false);

        int phaseX = left + 36 + this.font.width("IBUPROBRIDGING") + 8;
        UiDraw.drawPill(gfx, this.font, phaseX, top + 13, "PHASE 1", UiDraw.withAlpha(activeTab.accent, 0x28), activeTab.accent);

        // Close button (top right)
        int closeX = right - 32;
        int closeY = top + 12;
        boolean closeHover = UiDraw.inside(mouseX, mouseY, closeX, closeY, 18, 18);
        UiDraw.fillRoundedRect(gfx, closeX, closeY, 18, 18, 5, closeHover ? 0xDDEF4444 : 0xFF1A2233);
        gfx.centeredText(this.font, "x", closeX + 9, closeY + 5, closeHover ? 0xFFFFFFFF : TEXT_MUTED);

        // "Move HUD" pill button right next to close button
        int hudBtnW = UiDraw.pillWidth(this.font, "Move HUD");
        int hudBtnX = closeX - hudBtnW - 8;
        int hudBtnY = top + 13;
        boolean hudBtnHover = UiDraw.inside(mouseX, mouseY, hudBtnX, hudBtnY, hudBtnW, 15);
        UiDraw.drawPill(gfx, this.font, hudBtnX, hudBtnY, "Move HUD",
                hudBtnHover ? 0xFF0284C7 : 0xFF192438,
                hudBtnHover ? 0xFFFFFFFF : 0xFF38BDF8);

        // Live telemetry pills (ping, fps, REC)
        if (mod != null && this.minecraft != null) {
            int ping = IbuprofenMod.queryPingMs(this.minecraft, this.minecraft.player);
            int fps = this.minecraft.getFps();
            boolean rec = mod.debugRecorder() != null && mod.debugRecorder().isRecording();

            int cursorX = hudBtnX - 8;
            if (rec) {
                int w = UiDraw.pillWidth(this.font, "REC");
                cursorX -= w;
                UiDraw.drawPill(gfx, this.font, cursorX, top + 13, "REC", 0x33EF4444, 0xFFF87171);
                cursorX -= 6;
            }
            String fpsTxt = fps + " fps";
            int fpsW = UiDraw.pillWidth(this.font, fpsTxt);
            cursorX -= fpsW;
            UiDraw.drawPill(gfx, this.font, cursorX, top + 13, fpsTxt, 0xFF151E30, 0xFFCBD5E1);
            cursorX -= 6;

            String pingTxt = (ping >= 0 ? ping + " ms" : "-- ms");
            int pingCol = (ping < 0) ? TEXT_MUTED : (ping < 120 ? 0xFF4ADE80 : (ping < 250 ? 0xFFFACC15 : 0xFFF87171));
            int pingW = UiDraw.pillWidth(this.font, pingTxt);
            cursorX -= pingW;
            UiDraw.drawPill(gfx, this.font, cursorX, top + 13, pingTxt, 0xFF151E30, pingCol);
        }

        // 4. Left Sidebar Rail
        int bodyTop = top + HEADER_H + 1;
        int bodyBottom = bottom - FOOTER_H;
        gfx.fill(left + SIDEBAR_W, bodyTop + 8, left + SIDEBAR_W + 1, bodyBottom - 8, 0x1AFFFFFF);

        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            Tab tab = tabs[i];
            int tx = left + 10;
            int ty = bodyTop + 12 + i * 34;
            int tw = SIDEBAR_W - 20;
            int th = 28;
            boolean selected = (tab == activeTab);
            boolean hover = UiDraw.inside(mouseX, mouseY, tx, ty, tw, th);

            if (selected) {
                UiDraw.fillRoundedRect(gfx, tx, ty, tw, th, 7, UiDraw.withAlpha(tab.accent, 0x24));
                UiDraw.drawRoundedOutline(gfx, tx, ty, tw, th, 7, UiDraw.withAlpha(tab.accent, 0x66));
                UiDraw.fillRoundedRect(gfx, tx + 3, ty + 6, 3, th - 12, 1, tab.accent);
            } else if (hover) {
                UiDraw.fillRoundedRect(gfx, tx, ty, tw, th, 7, 0x18FFFFFF);
            }
            UiDraw.fillRoundedRect(gfx, tx + 11, ty + 11, 5, 5, 2, selected ? tab.accent : TEXT_DIM);
            gfx.text(this.font, tab.label, tx + 21, ty + 10, selected ? TEXT_PRIMARY : TEXT_MUTED, false);
        }

        // Emergency STOP ALL button pinned at bottom of sidebar
        int stopX = left + 10;
        int stopY = bodyBottom - 36;
        int stopW = SIDEBAR_W - 20;
        int stopH = 26;
        boolean stopHover = UiDraw.inside(mouseX, mouseY, stopX, stopY, stopW, stopH);
        boolean hasSkill = mod != null && mod.activeSkill() != null;
        int stopBg = stopHover ? 0xE6DC2626 : (hasSkill ? 0xCC991B1B : 0x55991B1B);
        UiDraw.fillRoundedRect(gfx, stopX, stopY, stopW, stopH, 7, stopBg);
        UiDraw.drawRoundedOutline(gfx, stopX, stopY, stopW, stopH, 7, stopHover ? 0xFFFCA5A5 : 0x88EF4444);
        gfx.centeredText(this.font, "STOP ALL", stopX + stopW / 2, stopY + 9, 0xFFFFFFFF);

        // 5. Right Content Area (spacious 2-column rounded module cards)
        int contentLeft = left + SIDEBAR_W + 16;
        int contentW = PANEL_W - SIDEBAR_W - 32;
        gfx.text(this.font, activeTab.label.toUpperCase(), contentLeft + 2, bodyTop + 12, activeTab.accent, false);

        if (mod != null) {
            List<Card> cards = buildCardsFor(activeTab, mod);
            int cardGap = 10;
            int cardW = (contentW - cardGap) / 2;
            int cardH = 56;
            int gridTop = bodyTop + 28;

            for (int i = 0; i < cards.size(); i++) {
                Card c = cards.get(i);
                int col = i % 2;
                int row = i / 2;
                int cx = contentLeft + col * (cardW + cardGap);
                int cy = gridTop + row * (cardH + cardGap);
                boolean hover = UiDraw.inside(mouseX, mouseY, cx, cy, cardW, cardH);
                drawCard(gfx, c, cx, cy, cardW, cardH, hover, dt);
            }

            int rowsUsed = (cards.size() + 1) / 2;
            int infoY = gridTop + rowsUsed * (cardH + cardGap);
            int infoH = bodyBottom - infoY - 10;
            if (infoH >= 48) {
                if (activeTab == Tab.BRAIN) {
                    drawBrainLiveCard(gfx, mod, contentLeft, infoY, contentW, infoH);
                } else if (activeTab == Tab.RECORDER) {
                    drawRecorderLiveCard(gfx, mod, contentLeft, infoY, contentW, infoH);
                }
            }
        }

        // 6. Footer Bar
        gfx.fill(left + 10, bodyBottom, right - 10, bodyBottom + 1, 0x1AFFFFFF);
        String activeStr = mod != null ? mod.activeSkillName() : "none";
        String bodyStr = mod != null ? mod.inputArbiter().formatActiveSummary() : "idle";
        gfx.text(this.font, "Skill: " + activeStr + "   •   Body: " + bodyStr, left + 14, bodyBottom + 8, TEXT_MUTED, false);
        String hint = "[ or ESC to close";
        gfx.text(this.font, hint, right - 14 - this.font.width(hint), bodyBottom + 8, TEXT_DIM, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event == null || event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
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
        int closeX = right - 32;
        if (UiDraw.inside(mx, my, closeX, top + 12, 18, 18)) {
            closeMenu();
            return true;
        }

        // Move HUD button
        int hudBtnW = UiDraw.pillWidth(this.font, "Move HUD");
        int hudBtnX = closeX - hudBtnW - 8;
        if (UiDraw.inside(mx, my, hudBtnX, top + 13, hudBtnW, 15)) {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(new HudEditorScreen(this));
            }
            return true;
        }

        // Sidebar tabs
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int ty = bodyTop + 12 + i * 34;
            if (UiDraw.inside(mx, my, left + 10, ty, SIDEBAR_W - 20, 28)) {
                activeTab = tabs[i];
                return true;
            }
        }

        // Emergency STOP ALL
        if (mod != null && UiDraw.inside(mx, my, left + 10, bodyBottom - 36, SIDEBAR_W - 20, 26)) {
            IbuCommand.tryHandle(mod, "#ibu stop");
            return true;
        }

        // Module cards
        if (mod != null) {
            int contentLeft = left + SIDEBAR_W + 16;
            int contentW = PANEL_W - SIDEBAR_W - 32;
            int cardGap = 10;
            int cardW = (contentW - cardGap) / 2;
            int cardH = 56;
            int gridTop = bodyTop + 28;

            List<Card> cards = buildCardsFor(activeTab, mod);
            for (int i = 0; i < cards.size(); i++) {
                int col = i % 2;
                int row = i / 2;
                int cx = contentLeft + col * (cardW + cardGap);
                int cy = gridTop + row * (cardH + cardGap);
                if (UiDraw.inside(mx, my, cx, cy, cardW, cardH)) {
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
                cards.add(new Card("walk_cross", "Walk -> Crosshair", "Walk to looked-at block", false, false, "RUN", tab.accent, () -> {
                    walkToCrosshair(mod);
                    closeMenu();
                }));
                cards.add(new Card("walk_shop", "Walk -> Shop", "Stand cleanly in front of NPC", false, false, "RUN", tab.accent, () -> {
                    IbuCommand.tryHandle(mod, "#ibu test walk shop");
                    closeMenu();
                }));
                cards.add(new Card("walk_gen", "Walk -> Generator", "Snap to nearest dia/em gen", false, false, "RUN", tab.accent, () -> {
                    IbuCommand.tryHandle(mod, "#ibu test walk gen");
                    closeMenu();
                }));
                cards.add(new Card("walk_bed", "Walk -> Nearest Bed", "Navigate to closest live bed", false, false, "RUN", tab.accent, () -> {
                    IbuCommand.tryHandle(mod, "#ibu test walk bed");
                    closeMenu();
                }));
            }
            case VISUALS -> {
                cards.add(new Card("vis_path", "Path Line", "3D cyan route & jump arcs", true, cfg.showPath, null, tab.accent, () -> {
                    cfg.showPath = !cfg.showPath;
                    cfg.save();
                }));
                cards.add(new Card("vis_goal", "Goal Box", "Gold wireframe at target", true, cfg.showGoalBox, null, tab.accent, () -> {
                    cfg.showGoalBox = !cfg.showGoalBox;
                    cfg.save();
                }));
                cards.add(new Card("vis_bed", "Bed Markers", "Team-colored bed outlines", true, cfg.showBedMarkers, null, tab.accent, () -> {
                    cfg.showBedMarkers = !cfg.showBedMarkers;
                    cfg.save();
                }));
                cards.add(new Card("vis_shop", "Shop Markers", "Item & upgrade NPC boxes", true, cfg.showShopMarkers, null, tab.accent, () -> {
                    cfg.showShopMarkers = !cfg.showShopMarkers;
                    cfg.save();
                }));
                cards.add(new Card("vis_hud", "Status HUD", "Floating live status card", true, cfg.showHud, null, tab.accent, () -> {
                    cfg.showHud = !cfg.showHud;
                    cfg.save();
                }));
                cards.add(new Card("vis_hud_pos", "Edit HUD Layout", "Drag HUD card anywhere", false, false, "EDIT", 0xFF38BDF8, () -> {
                    if (this.minecraft != null) {
                        this.minecraft.gui.setScreen(new HudEditorScreen(this));
                    }
                }));
            }
            case BRAIN -> {
                String prof = capitalize(mod.inputArbiter().profile().name());
                cards.add(new Card("brain_prof", "Motor Profile", "Human aim & click preset", false, true, prof.toUpperCase(), tab.accent,
                        () -> cycleProfile(mod)));
                cards.add(new Card("brain_mem", "Dump MatchMemory", "Print beds & wish list to chat", false, false, "CHAT", tab.accent, () -> {
                    IbuCommand.tryHandle(mod, "#ibu mem");
                    closeMenu();
                }));
                cards.add(new Card("brain_status", "Dump Status", "Print map, ping & channels", false, false, "CHAT", tab.accent, () -> {
                    IbuCommand.tryHandle(mod, "#ibu status");
                    closeMenu();
                }));
            }
            case RECORDER -> {
                boolean autoRec = mod.worldState().isAutoRecord();
                boolean recOn = mod.debugRecorder() != null && mod.debugRecorder().isRecording();
                cards.add(new Card("rec_auto", "Auto-Record", "Rotate .jsonl.gz every match", true, autoRec, null, tab.accent,
                        () -> mod.worldState().setAutoRecord(!mod.worldState().isAutoRecord())));
                cards.add(new Card("rec_now", "Debug Recording", recOn ? "Stop & flush current log" : "Start lightweight log now",
                        true, recOn, null, 0xFFF87171,
                        () -> IbuCommand.tryHandle(mod, recOn ? "#ibu rec stop" : "#ibu rec start")));
            }
        }
        return cards;
    }

    private void drawCard(GuiGraphicsExtractor gfx, Card c, int x, int y, int w, int h, boolean hover, float dt) {
        int topCol = hover ? 0xF01C263B : 0xE6141C2E;
        int botCol = hover ? 0xF0161F31 : 0xE6101624;
        UiDraw.fillRoundedGradient(gfx, x, y, w, h, 8, topCol, botCol);

        int borderCol = hover
                ? UiDraw.withAlpha(c.accent(), 0xAA)
                : (c.isToggle() && c.enabled() ? UiDraw.withAlpha(c.accent(), 0x55) : 0x22FFFFFF);
        UiDraw.drawRoundedOutline(gfx, x, y, w, h, 8, borderCol);

        gfx.text(this.font, c.title(), x + 12, y + 13, TEXT_PRIMARY, false);
        gfx.text(this.font, trimToWidth(c.subtitle(), w - 24), x + 12, y + 34, TEXT_MUTED, false);

        if (c.isToggle()) {
            float target = c.enabled() ? 1.0f : 0.0f;
            float cur = toggleAnim.getOrDefault(c.id(), target);
            cur += (target - cur) * Math.min(1.0f, dt * 14.0f);
            toggleAnim.put(c.id(), cur);
            UiDraw.drawToggleSwitch(gfx, x + w - 42, y + 11, cur, c.accent());
        } else if (c.badge() != null) {
            int bw = UiDraw.pillWidth(this.font, c.badge());
            int bg = hover ? UiDraw.withAlpha(c.accent(), 0x44) : 0xFF1C2538;
            int fg = hover ? 0xFFFFFFFF : c.accent();
            UiDraw.drawPill(gfx, this.font, x + w - bw - 10, y + 10, c.badge(), bg, fg);
        }
    }

    private void drawBrainLiveCard(GuiGraphicsExtractor gfx, IbuprofenMod mod, int x, int y, int w, int h) {
        UiDraw.fillRoundedRect(gfx, x, y, w, h, 8, 0xCC0E1422);
        UiDraw.drawRoundedOutline(gfx, x, y, w, h, 8, 0x22FFFFFF);
        gfx.text(this.font, "LIVE MATCH MEMORY", x + 12, y + 9, 0xFFA78BFA, false);

        Map<?, TeamDossier> teams = mod.matchMemory().getAllTeamDossiers();
        List<ShoppingWishList.WishItem> wish = mod.matchMemory().computeWishList();

        String bedsLine = "Beds tracked: " + teams.size()
                + "   •   Enemies tracked: " + mod.matchMemory().getAllEnemyDossiers().size();
        gfx.text(this.font, trimToWidth(bedsLine, w - 24), x + 12, y + 24, TEXT_PRIMARY, false);

        String wishLine = wish.isEmpty()
                ? "Wish List: empty (no counter-items needed yet)"
                : "Top Wish: [" + wish.get(0).priority() + "] " + wish.get(0).itemId() + " — " + wish.get(0).reason();
        gfx.text(this.font, trimToWidth(wishLine, w - 24), x + 12, y + 38, 0xFFFACC15, false);
    }

    private void drawRecorderLiveCard(GuiGraphicsExtractor gfx, IbuprofenMod mod, int x, int y, int w, int h) {
        UiDraw.fillRoundedRect(gfx, x, y, w, h, 8, 0xCC0E1422);
        UiDraw.drawRoundedOutline(gfx, x, y, w, h, 8, 0x22FFFFFF);
        gfx.text(this.font, "TELEMETRY STATUS", x + 12, y + 9, 0xFFFBBF24, false);

        DebugRecorder rec = mod.debugRecorder();
        if (rec != null && rec.isRecording()) {
            String file = rec.currentFile() != null ? rec.currentFile().getFileName().toString() : "active";
            gfx.text(this.font, trimToWidth("File: " + file, w - 24), x + 12, y + 24, TEXT_PRIMARY, false);
            long kb = rec.uncompressedBytes() / 1024L;
            gfx.text(this.font, "Events: " + rec.lineCount() + "   •   Raw: " + kb + " KB (gzipped on disk)",
                    x + 12, y + 38, 0xFF4ADE80, false);
        } else {
            gfx.text(this.font, "Recorder is idle (zero disk/CPU overhead).", x + 12, y + 24, TEXT_MUTED, false);
            gfx.text(this.font, "Auto-record starts automatically when a BedWars match begins.",
                    x + 12, y + 38, TEXT_DIM, false);
        }
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
