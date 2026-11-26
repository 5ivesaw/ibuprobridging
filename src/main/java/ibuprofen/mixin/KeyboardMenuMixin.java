package ibuprofen.mixin;

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
 * Opens Ibuprobridging's global Control Panel on '[' (or Right Shift) when playing in-game.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardMenuMixin {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void ibuprofen$onKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action != 1 || event == null || minecraft == null || minecraft.player == null) {
            return;
        }
        int key = event.key();
        if (key == IbuMenuScreen.KEY_LEFT_BRACKET || key == IbuMenuScreen.KEY_RIGHT_SHIFT) {
            if (minecraft.gui.screen() == null) {
                minecraft.gui.setScreen(new IbuMenuScreen());
                ci.cancel();
            }
        }
    }
}
