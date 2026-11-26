package ibuprofen.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import ibuprofen.ui.IbuMenuScreen;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens Ibuprobridging's global Control Panel on '[' (MC 26.3 SDL3 InputConstants.KEY_LBRACKET)
 * when playing in-game without conflicting with OneConfig's Right Shift menu.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardMenuMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void ibuprofen$onKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action != InputConstants.PRESS || event == null || minecraft == null || minecraft.player == null) {
            return;
        }
        if (event.key() == InputConstants.KEY_LBRACKET) {
            if (minecraft.gui.screen() == null) {
                minecraft.gui.setScreen(new IbuMenuScreen());
                ci.cancel();
            }
        }
    }
}
