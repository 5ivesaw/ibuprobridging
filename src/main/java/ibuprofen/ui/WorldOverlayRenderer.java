package ibuprofen.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import ibuprofen.IbuprofenMod;
import ibuprofen.skills.Skill;
import ibuprofen.skills.nav.NavMoveType;
import ibuprofen.skills.nav.NavStep;
import ibuprofen.skills.nav.WalkSkill;
import ibuprofen.world.BedwarsMapCore.BedPOI;
import ibuprofen.world.BedwarsMapCore.ShopNPC;
import ibuprofen.world.BedwarsMapCore.TeamColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Baritone-style 3D world overlay renderer for MC 26.3 (Vulkan/RenderPearl safe).
 * Draws glowing smooth path lines, goal wireframes, remembered bed boxes, and shop markers.
 */
public final class WorldOverlayRenderer {

    private static final StagedVertexBuffer STAGED = new StagedVertexBuffer(() -> "Ibuprobridging Overlay", 256);

    private WorldOverlayRenderer() {}

    public static void renderWorldOverlays(CameraRenderState cameraState) {
        IbuprofenMod mod = IbuprofenMod.get();
        Minecraft mc = Minecraft.getInstance();
        if (mod == null || mc == null || mc.level == null || mc.player == null || cameraState == null || cameraState.pos == null) {
            return;
        }
        IbuSettings cfg = mod.settings();
        if (!cfg.showPath && !cfg.showGoalBox && !cfg.showBedMarkers && !cfg.showShopMarkers) {
            return;
        }

        try {
            PoseStack stack = new PoseStack();
            stack.mulPose(cameraState.viewRotationMatrix);
            Vec3 cam = cameraState.pos;

            StagedVertexBuffer.Draw draw = STAGED.appendDraw(
                    DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, PrimitiveTopology.LINES);
            BufferBuilder buf = (BufferBuilder) STAGED.getVertexBuilder(draw);
            boolean emittedAny = false;

            Skill skill = mod.activeSkill();
            if (skill instanceof WalkSkill walk) {
                List<NavStep> steps = walk.activeSteps();
                int curIdx = walk.currentStepIndex();

                if (cfg.showPath && steps != null && steps.size() >= 2) {
                    for (int i = Math.max(0, curIdx - 1); i < steps.size() - 1; i++) {
                        NavStep a = steps.get(i);
                        NavStep b = steps.get(i + 1);
                        boolean activeSeg = (i == curIdx - 1 || i == curIdx);
                        float r = activeSeg ? 0.25f : 0.05f;
                        float g = activeSeg ? 1.00f : 0.85f;
                        float bl = activeSeg ? 0.45f : 0.95f;
                        float width = activeSeg ? 3.2f : 2.2f;
                        if (b.moveType() == NavMoveType.PARKOUR_JUMP) {
                            r = 1.0f; g = 0.65f; bl = 0.10f; // Orange arc indicator for parkour jumps
                        }
                        emitLine(buf, stack,
                                a.centerX() - cam.x, a.targetTopY() + 0.16 - cam.y, a.centerZ() - cam.z,
                                b.centerX() - cam.x, b.targetTopY() + 0.16 - cam.y, b.centerZ() - cam.z,
                                r, g, bl, 0.92f, width);
                        emittedAny = true;
                    }
                }

                if (cfg.showGoalBox) {
                    AABB goalBox = new AABB(
                            walk.goalX(), walk.goalY(), walk.goalZ(),
                            walk.goalX() + 1.0, walk.goalY() + 1.0, walk.goalZ() + 1.0);
                    emitBox(buf, stack, goalBox, cam, 1.0f, 0.84f, 0.15f, 0.95f, 2.8f);
                    emittedAny = true;
                }
            }

            if (cfg.showBedMarkers) {
                for (BedPOI bed : mod.worldState().mapCore().getBeds()) {
                    if (bed.destroyed()) {
                        continue;
                    }
                    float[] rgb = teamRgb(bed.team());
                    double minX = Math.min(bed.x(), bed.footX());
                    double minY = Math.min(bed.y(), bed.footY());
                    double minZ = Math.min(bed.z(), bed.footZ());
                    double maxX = Math.max(bed.x(), bed.footX()) + 1.0;
                    double maxY = Math.max(bed.y(), bed.footY()) + 0.6;
                    double maxZ = Math.max(bed.z(), bed.footZ()) + 1.0;
                    emitBox(buf, stack, new AABB(minX, minY, minZ, maxX, maxY, maxZ), cam,
                            rgb[0], rgb[1], rgb[2], 0.90f, 2.4f);
                    emittedAny = true;
                }
            }

            if (cfg.showShopMarkers) {
                for (ShopNPC npc : mod.worldState().mapCore().getShopNpcs()) {
                    AABB box = new AABB(npc.x(), npc.y(), npc.z(), npc.x() + 1.0, npc.y() + 2.0, npc.z() + 1.0);
                    boolean upg = npc.label() != null && npc.label().startsWith("UPGRADES");
                    emitBox(buf, stack, box, cam, upg ? 0.75f : 0.35f, upg ? 0.35f : 0.90f, 1.0f, 0.80f, 2.0f);
                    emittedAny = true;
                }
            }

            if (emittedAny) {
                STAGED.upload();
                StagedVertexBuffer.ExecuteInfo info = STAGED.getExecuteInfo(draw);
                if (info != null) {
                    var prepared = RenderTypes.lines().prepare();
                    var target = mc.gameRenderer.mainRenderTarget();
                    try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                            () -> "Ibuprobridging Lines Pass",
                            target.getColorTextureView(),
                            Optional.empty(),
                            target.getDepthTextureView(),
                            OptionalDouble.empty())) {
                        prepared.drawFromBuffer(info, pass);
                    }
                }
            }
            STAGED.endDraw();
        } catch (Throwable ignored) {
            // Visuals are non-critical: never crash Minecraft on a render state edge case.
        } finally {
            try {
                STAGED.endFrame();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void emitBox(
            BufferBuilder buf, PoseStack stack, AABB box, Vec3 cam,
            float r, float g, float b, float a, float width) {
        double x0 = box.minX - cam.x, y0 = box.minY - cam.y, z0 = box.minZ - cam.z;
        double x1 = box.maxX - cam.x, y1 = box.maxY - cam.y, z1 = box.maxZ - cam.z;
        // bottom
        emitLine(buf, stack, x0, y0, z0, x1, y0, z0, r, g, b, a, width);
        emitLine(buf, stack, x1, y0, z0, x1, y0, z1, r, g, b, a, width);
        emitLine(buf, stack, x1, y0, z1, x0, y0, z1, r, g, b, a, width);
        emitLine(buf, stack, x0, y0, z1, x0, y0, z0, r, g, b, a, width);
        // top
        emitLine(buf, stack, x0, y1, z0, x1, y1, z0, r, g, b, a, width);
        emitLine(buf, stack, x1, y1, z0, x1, y1, z1, r, g, b, a, width);
        emitLine(buf, stack, x1, y1, z1, x0, y1, z1, r, g, b, a, width);
        emitLine(buf, stack, x0, y1, z1, x0, y1, z0, r, g, b, a, width);
        // pillars
        emitLine(buf, stack, x0, y0, z0, x0, y1, z0, r, g, b, a, width);
        emitLine(buf, stack, x1, y0, z0, x1, y1, z0, r, g, b, a, width);
        emitLine(buf, stack, x1, y0, z1, x1, y1, z1, r, g, b, a, width);
        emitLine(buf, stack, x0, y0, z1, x0, y1, z1, r, g, b, a, width);
    }

    private static void emitLine(
            BufferBuilder buf, PoseStack stack,
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            float r, float g, float b, float a, float width) {
        double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-5) {
            return;
        }
        float nx = (float) (dx / len), ny = (float) (dy / len), nz = (float) (dz / len);
        PoseStack.Pose pose = stack.last();
        buf.addVertex(pose, (float) x1, (float) y1, (float) z1)
                .setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(width);
        buf.addVertex(pose, (float) x2, (float) y2, (float) z2)
                .setColor(r, g, b, a).setNormal(pose, nx, ny, nz).setLineWidth(width);
    }

    private static float[] teamRgb(TeamColor team) {
        if (team == null) return new float[]{1.0f, 1.0f, 1.0f};
        return switch (team) {
            case RED -> new float[]{1.0f, 0.25f, 0.25f};
            case BLUE -> new float[]{0.25f, 0.50f, 1.0f};
            case GREEN -> new float[]{0.25f, 0.95f, 0.35f};
            case YELLOW -> new float[]{1.0f, 0.95f, 0.20f};
            case AQUA -> new float[]{0.20f, 0.95f, 0.95f};
            case PINK -> new float[]{1.0f, 0.40f, 0.85f};
            case GRAY -> new float[]{0.70f, 0.70f, 0.75f};
            default -> new float[]{0.95f, 0.95f, 0.95f};
        };
    }
}
