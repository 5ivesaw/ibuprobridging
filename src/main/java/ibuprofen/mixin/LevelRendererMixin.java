package ibuprofen.mixin;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import ibuprofen.ui.WorldOverlayRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders toggleable 3D Baritone-style path lines, goal boxes, and bed/shop overlays.
 */
@Mixin(LevelRenderer.class)
public final class LevelRendererMixin {

    @Inject(method = "render", at = @At("RETURN"))
    private void ibuprofen$onWorldRender(
            GraphicsResourceAllocator resourceAllocator,
            boolean renderOutline,
            CameraRenderState cameraState,
            GpuBufferSlice terrainFog,
            Vector4f fogColor,
            boolean shouldRenderSky,
            boolean consistentDepthRequired,
            CallbackInfo ci) {
        WorldOverlayRenderer.renderWorldOverlays(cameraState);
    }
}
