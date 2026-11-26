package ibuprofen.ui;

import ibuprofen.IbuprofenMod;
import ibuprofen.command.IbuCommand;
import ibuprofen.motor.HumanProfile;
import ibuprofen.skills.nav.WalkSkill;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Clean, zero-bloat global control panel toggled with '[' (or Right Shift).
 * Every test, visual toggle, and future phase module registers here for 1-click access.
 */
public final class IbuMenuScreen extends Screen {

    public static final int KEY_LEFT_BRACKET = 91;   // GLFW_KEY_LEFT_BRACKET '['
    public static final int KEY_RIGHT_SHIFT = 344;   // GLFW_KEY_RIGHT_SHIFT

    private static final int PANEL_W = 474;
    private static final int PANEL_H = 226;

    public IbuMenuScreen() {
        super(Component.literal("Ibuprobridging Control Panel"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        IbuprofenMod mod = IbuprofenMod.get();
        if (mod == null) {
            return;
        }
        IbuSettings cfg = mod.settings();

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        int colW = 142;
        int gap = 12;
        int col1 = left + 12;
        int col2 = col1 + colW + gap;
        int col3 = col2 + colW + gap;
        int row0 = top + 46;
        int rowStep = 26;

        // Column 1: Phase 1 Navigation & Actions (grows with Phases 2-6)
        addRenderableWidget(Button.builder(Component.literal("Walk -> Crosshair"), b -> {
            walkToCrosshair(mod);
            closeMenu();
        }).bounds(col1, row0, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Walk -> Shop"), b -> {
            IbuCommand.tryHandle(mod, "#ibu test walk shop");
            closeMenu();
        }).bounds(col1, row0 + rowStep, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Walk -> Generator"), b -> {
            IbuCommand.tryHandle(mod, "#ibu test walk gen");
            closeMenu();
        }).bounds(col1, row0 + rowStep * 2, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Walk -> Nearest Bed"), b -> {
            IbuCommand.tryHandle(mod, "#ibu test walk bed");
            closeMenu();
        }).bounds(col1, row0 + rowStep * 3, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("STOP ALL"), b -> {
            IbuCommand.tryHandle(mod, "#ibu stop");
            rebuild();
        }).bounds(col1, row0 + rowStep * 4 + 6, colW, 22).build());

        // Column 2: Baritone-Style Visual Toggles
        addRenderableWidget(Button.builder(toggleLabel("Path Line", cfg.showPath), b -> {
            cfg.showPath = !cfg.showPath;
            rebuild();
        }).bounds(col2, row0, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Goal Box", cfg.showGoalBox), b -> {
            cfg.showGoalBox = !cfg.showGoalBox;
            rebuild();
        }).bounds(col2, row0 + rowStep, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Bed Boxes", cfg.showBedMarkers), b -> {
            cfg.showBedMarkers = !cfg.showBedMarkers;
            rebuild();
        }).bounds(col2, row0 + rowStep * 2, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Shop Boxes", cfg.showShopMarkers), b -> {
            cfg.showShopMarkers = !cfg.showShopMarkers;
            rebuild();
        }).bounds(col2, row0 + rowStep * 3, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Status HUD", cfg.showHud), b -> {
            cfg.showHud = !cfg.showHud;
            rebuild();
        }).bounds(col2, row0 + rowStep * 4 + 6, colW, 22).build());

        // Column 3: Motor Profile, Memory & Debug Recorder
        String profName = mod.inputArbiter().profile().name();
        addRenderableWidget(Button.builder(Component.literal("Profile: " + capitalize(profName)), b -> {
            cycleProfile(mod);
            rebuild();
        }).bounds(col3, row0, colW, 20).build());

        addRenderableWidget(Button.builder(toggleLabel("Auto-Rec", mod.worldState().isAutoRecord()), b -> {
            mod.worldState().setAutoRecord(!mod.worldState().isAutoRecord());
            rebuild();
        }).bounds(col3, row0 + rowStep, colW, 20).build());

        boolean recOn = mod.debugRecorder() != null && mod.debugRecorder().isRecording();
        addRenderableWidget(Button.builder(Component.literal(recOn ? "Stop Debug Log" : "Start Debug Log"), b -> {
            IbuCommand.tryHandle(mod, recOn ? "#ibu rec stop" : "#ibu rec start");
            rebuild();
        }).bounds(col3, row0 + rowStep * 2, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Print MatchMemory"), b -> {
            IbuCommand.tryHandle(mod, "#ibu mem");
            closeMenu();
        }).bounds(col3, row0 + rowStep * 3, colW, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Print Status"), b -> {
            IbuCommand.tryHandle(mod, "#ibu status");
            closeMenu();
        }).bounds(col3, row0 + rowStep * 4 + 6, colW, 22).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gfx, int mouseX, int mouseY, float partialTick) {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        // Sleek dark-glass container + header accent
        gfx.fill(left, top, left + PANEL_W, top + PANEL_H, 0xD80F141D);
        gfx.fill(left, top, left + PANEL_W, top + 24, 0xE8162030);
        gfx.fill(left, top + 24, left + PANEL_W, top + 25, 0xFF38BDF8);
        gfx.outline(left, top, PANEL_W, PANEL_H, 0xAA38BDF8);

        gfx.centeredText(this.font, "IBUPROBRIDGING  •  PHASE 1 CONTROL PANEL", left + PANEL_W / 2, top + 8, 0xFF38BDF8);

        int colW = 142;
        int gap = 12;
        int col1 = left + 12;
        int col2 = col1 + colW + gap;
        int col3 = col2 + colW + gap;
        gfx.text(this.font, "Navigation & Actions", col1 + 2, top + 33, 0xFFE2E8F0);
        gfx.text(this.font, "Visual Overlays", col2 + 2, top + 33, 0xFFE2E8F0);
        gfx.text(this.font, "Brain & Recorder", col3 + 2, top + 33, 0xFFE2E8F0);

        IbuprofenMod mod = IbuprofenMod.get();
        String footer = mod != null
                ? "Active: " + mod.activeSkillName() + "   |   Press [ or ESC to close"
                : "Press [ or ESC to close";
        gfx.centeredText(this.font, footer, left + PANEL_W / 2, top + PANEL_H - 14, 0xFF94A3B8);

        super.extractRenderState(gfx, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event != null && (event.key() == KEY_LEFT_BRACKET || event.key() == KEY_RIGHT_SHIFT)) {
            closeMenu();
            return true;
        }
        return super.keyPressed(event);
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
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

    private static Component toggleLabel(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
