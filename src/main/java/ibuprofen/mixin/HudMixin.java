package ibuprofen.mixin;

import ibuprofen.ui.HudOverlayRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders Ibuprobridging's compact top-left Status HUD when enabled.
 */
@Mixin(Hud.class)
public final class HudMixin {

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void ibuprofen$onHudRender(GuiGraphicsExtractor gfx, DeltaTracker deltaTracker, CallbackInfo ci) {
        HudOverlayRenderer.renderHud(gfx);
    }
}
